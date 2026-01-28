package com.pureshot.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pureshot.camera.databinding.ActivityMainBinding

/**
 * MainActivity - PureShot Camera
 * 
 * Minimalist kamera arayüzü ile sensörden gelen ham veriyi yakalar.
 * Tüm Xiaomi post-processing filtreleri devre dışı bırakılmıştır.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 100
    }

    private lateinit var binding: ActivityMainBinding
    private var cameraController: CameraController? = null
    private var isManualMode = false

    // ISO range (sensörden okunacak)
    private var minIso = 50
    private var maxIso = 6400

    // Shutter speed range (nanoseconds)
    private val shutterSpeeds = listOf(
        1_000_000_000L / 8000,  // 1/8000s
        1_000_000_000L / 4000,  // 1/4000s
        1_000_000_000L / 2000,  // 1/2000s
        1_000_000_000L / 1000,  // 1/1000s
        1_000_000_000L / 500,   // 1/500s
        1_000_000_000L / 250,   // 1/250s
        1_000_000_000L / 125,   // 1/125s
        1_000_000_000L / 60,    // 1/60s
        1_000_000_000L / 30,    // 1/30s
        1_000_000_000L / 15,    // 1/15s
        1_000_000_000L / 8,     // 1/8s
        1_000_000_000L / 4,     // 1/4s
        1_000_000_000L / 2,     // 1/2s
        1_000_000_000L * 1,     // 1s
        1_000_000_000L * 2,     // 2s
        1_000_000_000L * 4,     // 4s
    )

    // White balance range (Kelvin)
    private val minWb = 2000
    private val maxWb = 10000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full screen immersive
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkCameraPermission()
    }

    private fun setupUI() {
        // Initially hide manual controls
        binding.controlsPanel.visibility = View.GONE

        // Shutter button - INSTANT CAPTURE
        binding.btnCapture.setOnClickListener {
            cameraController?.captureImage()
            // Visual feedback
            it.animate()
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(50)
                .withEndAction {
                    it.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(50)
                        .start()
                }
                .start()
        }

        // Manual mode toggle
        binding.btnManualMode.setOnClickListener {
            isManualMode = !isManualMode
            binding.controlsPanel.visibility = if (isManualMode) View.VISIBLE else View.GONE
            cameraController?.useManualMode = isManualMode
            cameraController?.updatePreview()
            
            // Update button appearance
            binding.btnManualMode.alpha = if (isManualMode) 1f else 0.5f
        }

        // ISO slider
        binding.seekBarIso.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val iso = minIso + (maxIso - minIso) * progress / 100
                binding.tvIsoValue.text = iso.toString()
                cameraController?.manualIso = iso
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                cameraController?.updatePreview()
            }
        })

        // Shutter speed slider
        binding.seekBarShutter.max = shutterSpeeds.size - 1
        binding.seekBarShutter.progress = 4 // Default 1/500s
        binding.seekBarShutter.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val shutterNs = shutterSpeeds[progress]
                binding.tvShutterValue.text = formatShutterSpeed(shutterNs)
                cameraController?.manualExposureNs = shutterNs
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                cameraController?.updatePreview()
            }
        })

        // White balance slider
        binding.seekBarWb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val wb = minWb + (maxWb - minWb) * progress / 100
                binding.tvWbValue.text = "${wb}K"
                cameraController?.manualWhiteBalance = wb
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                cameraController?.updatePreview()
            }
        })

        // Gallery button - open gallery app
        binding.btnGallery.setOnClickListener {
            // Open gallery to PureShot folder
            showStatus("Galeri açılıyor...")
        }
    }

    /**
     * Format shutter speed for display
     */
    private fun formatShutterSpeed(ns: Long): String {
        val seconds = ns / 1_000_000_000.0
        return when {
            seconds >= 1 -> "${seconds.toInt()}s"
            else -> "1/${(1.0 / seconds).toInt()}"
        }
    }

    /**
     * Check and request camera permission
     */
    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST
            )
        } else {
            setupCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setupCamera()
            } else {
                Toast.makeText(this, "Kamera izni gerekli", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    /**
     * Setup camera with TextureView
     */
    private fun setupCamera() {
        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        // If texture is already available
        if (binding.textureView.isAvailable) {
            openCamera()
        }
    }

    /**
     * Open camera and start preview
     */
    private fun openCamera() {
        cameraController = CameraController(this, binding.textureView).apply {
            onImageSaved = { path ->
                runOnUiThread {
                    showStatus("Kaydedildi! 📸")
                }
            }
            onError = { error ->
                runOnUiThread {
                    showStatus("Hata: $error")
                }
            }
        }
        cameraController?.openCamera()

        // Update camera info after opening
        binding.textureView.postDelayed({
            updateCameraInfo()
        }, 500)
    }

    /**
     * Update camera info display
     */
    private fun updateCameraInfo() {
        val info = cameraController?.getCameraInfo()
        info?.let {
            val resolution = "${it.sensorSize.width}x${it.sensorSize.height}"
            val raw = if (it.supportsRaw) "RAW ✓" else "JPEG"
            binding.tvCameraInfo.text = "PureShot • $resolution • $raw"

            // Update ISO range from camera
            it.isoRange?.let { range ->
                minIso = range.lower
                maxIso = range.upper
            }
        }
    }

    /**
     * Show status message
     */
    private fun showStatus(message: String) {
        binding.tvStatus.text = message
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.animate()
            .alpha(1f)
            .setDuration(200)
            .withEndAction {
                binding.tvStatus.postDelayed({
                    binding.tvStatus.animate()
                        .alpha(0f)
                        .setDuration(300)
                        .withEndAction {
                            binding.tvStatus.visibility = View.GONE
                        }
                        .start()
                }, 1500)
            }
            .start()
    }

    override fun onResume() {
        super.onResume()
        if (binding.textureView.isAvailable) {
            openCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        cameraController?.close()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraController?.close()
    }
}
