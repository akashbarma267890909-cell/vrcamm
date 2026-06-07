package com.vrcam.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private val handler = Handler(Looper.getMainLooper())
    private var mirrorRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullscreen()
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
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

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(binding.previewLeft.surfaceProvider)

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, videoCapture
                )
                // Start high quality mirror after camera starts
                handler.postDelayed({ startHighQualityMirror() }, 500)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error!", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * High quality mirror — runs at 60fps using hardware canvas.
     * Much smoother than the 30fps bitmap copy approach.
     */
    private fun startHighQualityMirror() {
        mirrorRunnable = object : Runnable {
            override fun run() {
                try {
                    val bmp = binding.previewLeft.bitmap
                    if (bmp != null && !bmp.isRecycled &&
                        binding.previewRight.isAvailable) {
                        val canvas: Canvas? = binding.previewRight.lockHardwareCanvas()
                        if (canvas != null) {
                            val src = Rect(0, 0, bmp.width, bmp.height)
                            val dst = Rect(
                                0, 0,
                                binding.previewRight.width,
                                binding.previewRight.height
                            )
                            canvas.drawBitmap(bmp, src, dst, null)
                            binding.previewRight.unlockCanvasAndPost(canvas)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore frame errors silently
                }
                handler.postDelayed(this, 16) // 60fps
            }
        }
        handler.post(mirrorRunnable!!)
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
            if (allPermissionsGranted()) startCamera()
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
        mirrorRunnable?.let { handler.removeCallbacks(it) }
        cameraExecutor.shutdown()
        currentRecording?.stop()
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
