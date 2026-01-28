package com.pureshot.camera.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.pureshot.camera.CameraController
import com.pureshot.camera.R
import com.pureshot.camera.databinding.FragmentCameraBinding

/**
 * CameraFragment - Ana kamera arayüzü
 */
class CameraFragment : Fragment() {

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 100
    }

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private var cameraController: CameraController? = null
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private var isGridVisible = false
    private var currentMode = CaptureMode.AUTO
    private var maxZoom = 10f

    enum class CaptureMode {
        AUTO, PRO, PORTRAIT, NIGHT, HDR
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        setupGestureDetector()
        checkCameraPermission()
    }

    private fun setupUI() {
        // Shutter button
        binding.btnCapture.setOnClickListener {
            cameraController?.captureImage()
            animateShutterButton()
        }

        // Grid toggle
        binding.btnGrid.setOnClickListener {
            isGridVisible = !isGridVisible
            binding.gridOverlay.visibility = if (isGridVisible) View.VISIBLE else View.GONE
            binding.btnGrid.alpha = if (isGridVisible) 1f else 0.6f
        }

        // Camera switch
        binding.btnSwitchCamera.setOnClickListener {
            cameraController?.switchCamera()
            binding.btnSwitchCamera.animate()
                .rotationBy(180f)
                .setDuration(300)
                .start()
        }

        // Mode buttons
        setupModeButtons()

        // LUT button
        binding.btnLut.setOnClickListener {
            showStatus("🎨 LUT seçimi yakında!")
        }
    }

    private fun setupModeButtons() {
        val modeButtons = listOf(
            binding.btnModeAuto to CaptureMode.AUTO,
            binding.btnModePro to CaptureMode.PRO,
            binding.btnModePortrait to CaptureMode.PORTRAIT,
            binding.btnModeNight to CaptureMode.NIGHT,
            binding.btnModeHdr to CaptureMode.HDR
        )

        modeButtons.forEach { (button, mode) ->
            button.setOnClickListener {
                setMode(mode, modeButtons)
            }
        }
    }

    private fun setMode(mode: CaptureMode, buttons: List<Pair<View, CaptureMode>>) {
        currentMode = mode

        // Update button states
        buttons.forEach { (button, buttonMode) ->
            val textView = button as? android.widget.TextView
            if (buttonMode == mode) {
                textView?.setTextColor(ContextCompat.getColor(requireContext(), R.color.secondary))
            } else {
                textView?.setTextColor(0x88FFFFFF.toInt())
            }
        }

        // Update mode indicator
        binding.tvModeIndicator.text = mode.name

        // Show/hide pro controls
        binding.controlsPanel.visibility = if (mode == CaptureMode.PRO) View.VISIBLE else View.GONE

        // Update camera mode
        cameraController?.useManualMode = (mode == CaptureMode.PRO)
        cameraController?.updatePreview()

        showStatus("${mode.name} mod aktif")
    }

    private fun setupGestureDetector() {
        scaleGestureDetector = ScaleGestureDetector(
            requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val scaleFactor = detector.scaleFactor
                    val currentZoom = cameraController?.currentZoom ?: 1f
                    val newZoom = (currentZoom * scaleFactor).coerceIn(1f, maxZoom)

                    cameraController?.setZoom(newZoom)

                    binding.tvZoomLevel.text = String.format("%.1fx", newZoom)
                    binding.tvZoomLevel.visibility = View.VISIBLE

                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    binding.tvZoomLevel.postDelayed({
                        binding.tvZoomLevel.visibility = View.GONE
                    }, 1000)
                }
            }
        )

        binding.textureView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            true
        }
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

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
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
                showStatus("⚠️ Kamera izni gerekli")
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
        cameraController = CameraController(requireContext(), binding.textureView).apply {
            onImageSaved = { fileName, uri ->
                activity?.runOnUiThread {
                    showStatus("📸 $fileName")
                    uri?.let { updateThumbnail(it) }
                }
            }
            onError = { error ->
                activity?.runOnUiThread {
                    showStatus("⚠️ $error")
                }
            }
            onCameraReady = {
                activity?.runOnUiThread {
                    updateCameraInfo()
                }
            }
        }
        cameraController?.openCamera()
    }

    private fun updateCameraInfo() {
        val info = cameraController?.getCameraInfo()
        info?.let {
            maxZoom = it.maxZoom
        }
    }

    private fun updateThumbnail(uri: Uri) {
        try {
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 8
                }
                val bitmap = BitmapFactory.decodeStream(input, null, options)
                binding.imgLastPhoto.setImageBitmap(bitmap)
            }
        } catch (e: Exception) {
            // Ignore
        }
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

    override fun onDestroyView() {
        super.onDestroyView()
        cameraController?.close()
        _binding = null
    }
}
