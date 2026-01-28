package com.pureshot.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.pureshot.camera.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * MainActivity v2.0 - PureShot Camera
 * 
 * Yenilikler:
 * - Zoom slider + pinch-to-zoom
 * - Grid overlay toggle
 * - Kamera değiştirme (ön/arka)
 * - Son fotoğraf thumbnail
 * - Güncelleme kontrolü
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 100
    }

    private lateinit var binding: ActivityMainBinding
    private var cameraController: CameraController? = null
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    
    private var isManualMode = false
    private var isGridVisible = false
    private var maxZoom = 10f

    // ISO range
    private var minIso = 50
    private var maxIso = 6400

    // Shutter speeds (nanoseconds)
    private val shutterSpeeds = listOf(
        1_000_000_000L / 8000,
        1_000_000_000L / 4000,
        1_000_000_000L / 2000,
        1_000_000_000L / 1000,
        1_000_000_000L / 500,
        1_000_000_000L / 250,
        1_000_000_000L / 125,
        1_000_000_000L / 60,
        1_000_000_000L / 30,
        1_000_000_000L / 15,
        1_000_000_000L / 8,
        1_000_000_000L / 4,
        1_000_000_000L / 2,
        1_000_000_000L * 1,
        1_000_000_000L * 2,
        1_000_000_000L * 4,
    )

    private val minWb = 2000
    private val maxWb = 10000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupGestureDetector()
        checkCameraPermission()
        checkForUpdates()
    }

    private fun setupUI() {
        // Hide manual controls initially
        binding.isoControl.visibility = View.GONE
        binding.shutterControl.visibility = View.GONE
        binding.wbControl.visibility = View.GONE

        // Shutter button
        binding.btnCapture.setOnClickListener {
            cameraController?.captureImage()
            animateShutterButton()
        }

        // Manual mode toggle
        binding.btnManualMode.setOnClickListener {
            isManualMode = !isManualMode
            toggleManualControls()
        }

        // Grid toggle
        binding.btnGrid.setOnClickListener {
            isGridVisible = !isGridVisible
            binding.gridOverlay.visibility = if (isGridVisible) View.VISIBLE else View.GONE
            binding.btnGrid.alpha = if (isGridVisible) 1f else 0.5f
        }

        // Camera switch
        binding.btnSwitchCamera.setOnClickListener {
            cameraController?.switchCamera()
            // Animate rotation
            binding.btnSwitchCamera.animate()
                .rotationBy(180f)
                .setDuration(300)
                .start()
        }

        // Zoom slider
        binding.seekBarZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val zoom = 1f + (maxZoom - 1f) * progress / 100f
                binding.tvZoomValue.text = String.format("%.1fx", zoom)
                if (fromUser) {
                    cameraController?.setZoom(zoom)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                binding.tvZoomLevel.visibility = View.VISIBLE
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                binding.tvZoomLevel.postDelayed({
                    binding.tvZoomLevel.visibility = View.GONE
                }, 1000)
            }
        })

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
        binding.seekBarShutter.progress = 4
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

        // Last photo thumbnail click
        binding.imgLastPhoto.setOnClickListener {
            // TODO: Open gallery or last photo
            showStatus("Galeri açılıyor...")
        }
    }

    private fun setupGestureDetector() {
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val currentZoom = cameraController?.currentZoom ?: 1f
                val newZoom = (currentZoom * scaleFactor).coerceIn(1f, maxZoom)
                
                cameraController?.setZoom(newZoom)
                
                // Update slider
                val progress = ((newZoom - 1f) / (maxZoom - 1f) * 100).toInt()
                binding.seekBarZoom.progress = progress
                binding.tvZoomValue.text = String.format("%.1fx", newZoom)
                
                // Show zoom indicator
                binding.tvZoomLevel.text = String.format("%.1fx", newZoom)
                binding.tvZoomLevel.visibility = View.VISIBLE
                
                return true
            }
            
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                binding.tvZoomLevel.postDelayed({
                    binding.tvZoomLevel.visibility = View.GONE
                }, 1000)
            }
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    private fun toggleManualControls() {
        val visibility = if (isManualMode) View.VISIBLE else View.GONE
        binding.isoControl.visibility = visibility
        binding.shutterControl.visibility = visibility
        binding.wbControl.visibility = visibility
        binding.btnManualMode.alpha = if (isManualMode) 1f else 0.6f
        
        cameraController?.useManualMode = isManualMode
        cameraController?.updatePreview()
    }

    private fun animateShutterButton() {
        binding.btnCapture.animate()
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(50)
            .withEndAction {
                binding.btnCapture.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(50)
                    .start()
            }
            .start()
    }

    private fun formatShutterSpeed(ns: Long): String {
        val seconds = ns / 1_000_000_000.0
        return when {
            seconds >= 1 -> "${seconds.toInt()}s"
            else -> "1/${(1.0 / seconds).toInt()}"
        }
    }

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

    private fun setupCamera() {
        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                openCamera()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        if (binding.textureView.isAvailable) {
            openCamera()
        }
    }

    private fun openCamera() {
        cameraController = CameraController(this, binding.textureView).apply {
            onImageSaved = { fileName, uri ->
                runOnUiThread {
                    showStatus("📸 $fileName")
                    // Update thumbnail
                    uri?.let { updateThumbnail(it) }
                }
            }
            onError = { error ->
                runOnUiThread {
                    showStatus("⚠️ $error")
                }
            }
            onCameraReady = {
                runOnUiThread {
                    updateCameraInfo()
                }
            }
        }
        cameraController?.openCamera()
    }

    private fun updateCameraInfo() {
        val info = cameraController?.getCameraInfo()
        info?.let {
            val camera = if (it.isFrontCamera) "Ön" else "Arka"
            val resolution = "${it.sensorSize.width}x${it.sensorSize.height}"
            binding.tvCameraInfo.text = "PureShot • $camera • $resolution"

            // Update ranges
            it.isoRange?.let { range ->
                minIso = range.lower
                maxIso = range.upper
            }
            
            maxZoom = it.maxZoom
        }
    }

    private fun updateThumbnail(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 8 // Downsample for thumbnail
                }
                val bitmap = BitmapFactory.decodeStream(input, null, options)
                binding.imgLastPhoto.setImageBitmap(bitmap)
            }
        } catch (e: Exception) {
            // Ignore thumbnail errors
        }
    }

    private fun checkForUpdates() {
        val updateChecker = UpdateChecker(this)
        lifecycleScope.launch {
            val updateInfo = updateChecker.checkForUpdate()
            updateInfo?.let { info ->
                if (info.hasUpdate) {
                    runOnUiThread {
                        showUpdateDialog(info, updateChecker)
                    }
                }
            }
        }
    }

    private fun showUpdateDialog(info: UpdateChecker.UpdateInfo, checker: UpdateChecker) {
        AlertDialog.Builder(this)
            .setTitle("🚀 Güncelleme Mevcut!")
            .setMessage("Yeni sürüm: v${info.latestVersion}\n\n${info.releaseNotes}")
            .setPositiveButton("İndir") { _, _ ->
                checker.openDownloadPage(info.downloadUrl)
            }
            .setNegativeButton("Sonra") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showStatus(message: String) {
        binding.tvStatus.text = message
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.alpha = 1f
        binding.tvStatus.postDelayed({
            binding.tvStatus.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    binding.tvStatus.visibility = View.GONE
                }
                .start()
        }, 2000)
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
