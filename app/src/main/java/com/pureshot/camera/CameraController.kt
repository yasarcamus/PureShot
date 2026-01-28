package com.pureshot.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * CameraController v2.0 - Camera2 API wrapper with ZERO post-processing
 * 
 * Yenilikler:
 * - MediaStore ile galeri entegrasyonu
 * - Ön/arka kamera desteği
 * - Zoom kontrolü
 */
class CameraController(
    private val context: Context,
    private val textureView: TextureView
) {
    companion object {
        private const val TAG = "PureShot"
        const val CAMERA_BACK = 0
        const val CAMERA_FRONT = 1
    }

    private val cameraManager: CameraManager = 
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    
    // Camera characteristics
    private var cameraId: String = ""
    private var sensorSize: Size = Size(0, 0)
    private var isoRange: android.util.Range<Int>? = null
    private var exposureRange: android.util.Range<Long>? = null
    private var supportsRaw: Boolean = false
    private var maxZoom: Float = 1f
    private var activeArraySize: android.graphics.Rect? = null
    
    // Current camera (back = 0, front = 1)
    var currentCamera: Int = CAMERA_BACK
    
    // Manual control values
    var manualIso: Int = 100
    var manualExposureNs: Long = 10_000_000L
    var manualWhiteBalance: Int = 5500
    var manualFocusDistance: Float = 0f
    var useManualMode: Boolean = false
    var currentZoom: Float = 1f
    
    // Callbacks
    var onImageSaved: ((String, Uri?) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onCameraReady: (() -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun openCamera() {
        startBackgroundThread()
        
        try {
            cameraId = getCameraId(currentCamera)
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            loadCameraCapabilities(characteristics)
            
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCameraPreviewSession()
                    Log.d(TAG, "Camera opened: $cameraId")
                    onCameraReady?.invoke()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    onError?.invoke("Camera error: $error")
                }
            }, backgroundHandler)
            
        } catch (e: CameraAccessException) {
            onError?.invoke("Camera access error: ${e.message}")
        }
    }

    private fun getCameraId(cameraType: Int): String {
        val facing = if (cameraType == CAMERA_FRONT) 
            CameraCharacteristics.LENS_FACING_FRONT 
        else 
            CameraCharacteristics.LENS_FACING_BACK
            
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) == facing) {
                return id
            }
        }
        return cameraManager.cameraIdList[0]
    }

    fun switchCamera() {
        close()
        currentCamera = if (currentCamera == CAMERA_BACK) CAMERA_FRONT else CAMERA_BACK
        openCamera()
    }

    private fun loadCameraCapabilities(characteristics: CameraCharacteristics) {
        isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        
        activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        activeArraySize?.let {
            sensorSize = Size(it.width(), it.height())
        }
        
        maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        supportsRaw = capabilities?.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
        ) == true
        
        Log.d(TAG, "Camera: ISO=$isoRange, MaxZoom=$maxZoom, RAW=$supportsRaw")
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = textureView.surfaceTexture ?: return
            
            val previewSize = getOptimalPreviewSize()
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            
            val surface = Surface(texture)
            
            val captureSize = getLargestOutputSize()
            imageReader = ImageReader.newInstance(
                captureSize.width, 
                captureSize.height,
                ImageFormat.JPEG, 
                2
            )
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                image?.let { saveImageToGallery(it) }
            }, backgroundHandler)
            
            val captureBuilder = cameraDevice?.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            ) ?: return
            
            captureBuilder.addTarget(surface)
            applyZeroProcessing(captureBuilder)
            applyZoom(captureBuilder)
            
            cameraDevice?.createCaptureSession(
                listOf(surface, imageReader?.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(
                                captureBuilder.build(),
                                null,
                                backgroundHandler
                            )
                        } catch (e: CameraAccessException) {
                            onError?.invoke("Preview error: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        onError?.invoke("Session configuration failed")
                    }
                },
                backgroundHandler
            )
            
        } catch (e: CameraAccessException) {
            onError?.invoke("Session error: ${e.message}")
        }
    }

    private fun applyZoom(builder: CaptureRequest.Builder) {
        activeArraySize?.let { sensor ->
            val zoom = currentZoom.coerceIn(1f, maxZoom)
            val centerX = sensor.width() / 2
            val centerY = sensor.height() / 2
            val deltaX = ((sensor.width() / zoom) / 2).toInt()
            val deltaY = ((sensor.height() / zoom) / 2).toInt()
            
            val cropRegion = android.graphics.Rect(
                centerX - deltaX,
                centerY - deltaY,
                centerX + deltaX,
                centerY + deltaY
            )
            builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
        }
    }

    fun setZoom(zoom: Float) {
        currentZoom = zoom.coerceIn(1f, maxZoom)
        updatePreview()
    }

    fun getMaxZoom(): Float = maxZoom

    private fun applyZeroProcessing(builder: CaptureRequest.Builder) {
        builder.apply {
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
            set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF)
            set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_OFF)
            set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
            set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF)
            set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF)
            
            if (useManualMode) {
                applyManualControls(this)
            }
        }
    }

    private fun applyManualControls(builder: CaptureRequest.Builder) {
        builder.apply {
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            
            isoRange?.let { range ->
                val clampedIso = manualIso.coerceIn(range.lower, range.upper)
                set(CaptureRequest.SENSOR_SENSITIVITY, clampedIso)
            }
            
            exposureRange?.let { range ->
                val clampedExposure = manualExposureNs.coerceIn(range.lower, range.upper)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, clampedExposure)
            }
            
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance)
        }
    }

    fun captureImage() {
        try {
            val captureBuilder = cameraDevice?.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE
            ) ?: return
            
            imageReader?.surface?.let { captureBuilder.addTarget(it) }
            
            applyZeroProcessing(captureBuilder)
            applyZoom(captureBuilder)
            
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            
            val rotation = if (currentCamera == CAMERA_FRONT) 270 else 90
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, rotation)
            
            captureSession?.capture(
                captureBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        Log.d(TAG, "Capture completed!")
                    }
                },
                backgroundHandler
            )
            
        } catch (e: CameraAccessException) {
            onError?.invoke("Capture error: ${e.message}")
        }
    }

    private fun saveImageToGallery(image: Image) {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "PureShot_$timestamp.jpg"
        
        try {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/PureShot")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )
                
                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { output ->
                        output.write(bytes)
                    }
                    
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(it, contentValues, null, null)
                }
                
                uri
            } else {
                val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                val pureShotDir = File(dcimDir, "PureShot")
                if (!pureShotDir.exists()) pureShotDir.mkdirs()
                
                val outputFile = File(pureShotDir, fileName)
                FileOutputStream(outputFile).use { output ->
                    output.write(bytes)
                }
                
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(outputFile.absolutePath),
                    arrayOf("image/jpeg"),
                    null
                )
                
                Uri.fromFile(outputFile)
            }
            
            onImageSaved?.invoke(fileName, uri)
            Log.d(TAG, "Image saved to gallery: $fileName")
            
        } catch (e: Exception) {
            onError?.invoke("Save error: ${e.message}")
            Log.e(TAG, "Save error", e)
        } finally {
            image.close()
        }
    }

    private fun getOptimalPreviewSize(): Size {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: arrayOf()
        
        val targetRatio = 16.0 / 9.0
        return sizes.minByOrNull { 
            kotlin.math.abs(it.width.toDouble() / it.height - targetRatio) 
        } ?: Size(1920, 1080)
    }

    private fun getLargestOutputSize(): Size {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.JPEG) ?: arrayOf()
        
        return sizes.maxByOrNull { it.width * it.height } ?: Size(4000, 3000)
    }

    fun updatePreview() {
        try {
            val texture = textureView.surfaceTexture ?: return
            val surface = Surface(texture)
            
            val builder = cameraDevice?.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            ) ?: return
            
            builder.addTarget(surface)
            applyZeroProcessing(builder)
            applyZoom(builder)
            
            captureSession?.setRepeatingRequest(
                builder.build(),
                null,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Update preview error: ${e.message}")
        }
    }

    fun getCameraInfo(): CameraInfo {
        return CameraInfo(
            isoRange = isoRange,
            exposureRange = exposureRange,
            sensorSize = sensorSize,
            supportsRaw = supportsRaw,
            maxZoom = maxZoom,
            isFrontCamera = currentCamera == CAMERA_FRONT
        )
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Thread join error: ${e.message}")
        }
    }

    fun close() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        stopBackgroundThread()
    }

    data class CameraInfo(
        val isoRange: android.util.Range<Int>?,
        val exposureRange: android.util.Range<Long>?,
        val sensorSize: Size,
        val supportsRaw: Boolean,
        val maxZoom: Float,
        val isFrontCamera: Boolean
    )
}
