package com.vrcam.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import com.vrcam.app.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: Recording? = null
    private var isRecording = false
    private var buttonVisible = true

    // Shared SurfaceTexture — one camera feed drawn to both views simultaneously
    private var sharedSurfaceTexture: SurfaceTexture? = null
    private var leftSurface: Surface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullscreen()
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            setupTextureViews()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
        }

        binding.btnRecord.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }
        binding.root.setOnClickListener {
            toggleButtonVisibility()
        }
    }

    /**
     * Wait for both TextureViews to be ready, then start camera.
     * Both TextureViews share the same SurfaceTexture so they show
     * identical frames with zero lag between them.
     */
    private fun setupTextureViews() {
        binding.previewLeft.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                sharedSurfaceTexture = st
                leftSurface = Surface(st)

                // Attach the same SurfaceTexture to the right view too
                // This makes both views render the exact same frames simultaneously
                if (binding.previewRight.isAvailable) {
                    binding.previewRight.surfaceTexture = st
                } else {
                    binding.previewRight.surfaceTextureListener =
                        object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                st2: SurfaceTexture, w: Int, h: Int
                            ) {
                                binding.previewRight.surfaceTexture = sharedSurfaceTexture
                                startCamera()
                            }
                            override fun onSurfaceTextureSizeChanged(
                                st: SurfaceTexture, w: Int, h: Int) {}
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = false
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                }
                startCamera()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun startCamera() {
        val surface = leftSurface ?: return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // Video capture
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // Preview using our shared surface directly
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider { request ->
                request.provideSurface(surface, cameraExecutor) {}
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, videoCapture
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecording() {
        val vc = videoCapture ?: return
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VRCam_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VRCam")
            }
        }
        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        currentRecording = vc.output
            .prepareRecording(this, mediaStoreOutput)
            .apply {
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) withAudioEnabled()
            }
            .start(ContextCompat.getMainExecutor(this), Consumer { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        runOnUiThread {
                            binding.btnRecord.setBackgroundResource(R.drawable.bg_btn_stop)
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        runOnUiThread {
                            binding.btnRecord.setBackgroundResource(R.drawable.bg_btn_record)
                        }
                        if (!event.hasError()) {
                            Toast.makeText(this, "Video saved!", Toast.LENGTH_SHORT).show()
                        }
                    }
                    else -> {}
                }
            })
    }

    private fun stopRecording() {
        currentRecording?.stop()
        currentRecording = null
    }

    private fun toggleButtonVisibility() {
        binding.btnRecord.animate()
            .alpha(if (buttonVisible) 0.15f else 0.6f)
            .setDuration(300).start()
        buttonVisible = !buttonVisible
    }

    private fun goFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(
                    WindowInsets.Type.statusBars() or
                    WindowInsets.Type.navigationBars()
                )
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) setupTextureViews()
            else {
                Toast.makeText(this, "Camera permission needed!", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        goFullscreen()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        currentRecording?.stop()
        leftSurface?.release()
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()
    }
}
