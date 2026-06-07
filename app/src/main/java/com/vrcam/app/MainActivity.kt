package com.vrcam.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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

    // CameraX video capture use case
    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: Recording? = null
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep screen on while using VR
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Go completely fullscreen — hide status bar, nav bar, everything
        goFullscreen()

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Check permissions then start camera
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
        }

        // Record button — tiny, bottom center, barely visible
        binding.btnRecord.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }

        // Tap anywhere else on screen to briefly show/hide the record button
        binding.root.setOnClickListener {
            toggleButtonVisibility()
        }
    }

    // -------------------------------------------------------------------------
    // Camera setup
    // -------------------------------------------------------------------------

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // --- Preview for LEFT eye ---
            val previewLeft = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewLeft.surfaceProvider)
            }

            // --- Preview for RIGHT eye ---
            val previewRight = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewRight.surfaceProvider)
            }

            // --- Video capture ---
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // Use back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()

                // Bind preview left + video capture to lifecycle
                // Note: CameraX only supports one preview per camera instance
                // We mirror the same surface to both views using a custom approach
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    previewLeft,
                    videoCapture
                )

                // Bind second preview separately
                // We use a second camera provider future for the right eye mirror
                bindRightPreview(cameraProvider, cameraSelector)

            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Binds the right-eye preview to the same back camera.
     * CameraX allows multiple Preview use cases bound at once in newer versions.
     */
    private fun bindRightPreview(
        cameraProvider: ProcessCameraProvider,
        cameraSelector: CameraSelector
    ) {
        val previewRight = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewRight.surfaceProvider)
        }

        try {
            // Re-bind with both previews + video
            cameraProvider.unbindAll()

            val previewLeft = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewLeft.surfaceProvider)
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                previewLeft,
                previewRight,
                videoCapture
            )
        } catch (e: Exception) {
            // Some devices don't support dual preview — fall back to single preview
            // and use a TextureView copy approach
            bindSinglePreviewWithMirror(cameraProvider, cameraSelector)
        }
    }

    /**
     * Fallback for devices that don't support dual Preview use cases.
     * Binds one preview to the left eye and mirrors it to the right
     * using a SurfaceView copy technique.
     */
    private fun bindSinglePreviewWithMirror(
        cameraProvider: ProcessCameraProvider,
        cameraSelector: CameraSelector
    ) {
        val previewLeft = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewLeft.surfaceProvider)
        }

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                previewLeft,
                videoCapture
            )

            // Mirror left preview to right using PreviewView's bitmap
            // This runs at ~30fps update loop
            mirrorLeftToRight()

        } catch (e: Exception) {
            Toast.makeText(this, "Camera failed to start", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Continuously copies the left PreviewView bitmap to the right PreviewView.
     * This is the fallback mirror approach for devices that don't support dual Preview.
     */
    private fun mirrorLeftToRight() {
        val handler = android.os.Handler(mainLooper)
        val mirrorRunnable = object : Runnable {
            override fun run() {
                try {
                    val bitmap = binding.previewLeft.bitmap
                    if (bitmap != null) {
                        binding.previewRight.setImageBitmap(bitmap)
                    }
                } catch (e: Exception) {
                    // Ignore frame errors
                }
                handler.postDelayed(this, 33)  // ~30fps
            }
        }
        handler.post(mirrorRunnable)
    }

    // -------------------------------------------------------------------------
    // Recording
    // -------------------------------------------------------------------------

    private fun startRecording() {
        val videoCapture = videoCapture ?: return

        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(System.currentTimeMillis())
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

        currentRecording = videoCapture.output
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
                        runOnUiThread { updateRecordButton() }
                    }
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        runOnUiThread { updateRecordButton() }
                        if (event.hasError()) {
                            Toast.makeText(this, "Recording error", Toast.LENGTH_SHORT).show()
                        } else {
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

    private fun updateRecordButton() {
        if (isRecording) {
            // Show tiny red stop square
            binding.btnRecord.setBackgroundResource(R.drawable.bg_btn_stop)
        } else {
            // Show tiny red record circle
            binding.btnRecord.setBackgroundResource(R.drawable.bg_btn_record)
        }
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private var buttonVisible = true

    private fun toggleButtonVisibility() {
        if (buttonVisible) {
            binding.btnRecord.animate().alpha(0.15f).setDuration(300).start()
        } else {
            binding.btnRecord.animate().alpha(0.6f).setDuration(300).start()
        }
        buttonVisible = !buttonVisible
    }

    /**
     * Full immersive fullscreen — hides status bar, nav bar, notch area.
     * This is critical for VR so nothing interrupts the split view.
     */
    private fun goFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(
                    WindowInsets.Type.statusBars() or
                    WindowInsets.Type.navigationBars() or
                    WindowInsets.Type.systemBars()
                )
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Camera and microphone permissions are needed to use VRCam",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        goFullscreen()  // Re-apply fullscreen after any interruption
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        currentRecording?.stop()
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}
