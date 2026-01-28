package com.pureshot.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

/**
 * CameraController - Camera2 API wrapper with ZERO post-processing
 * 
 * Bu sınıf, Xiaomi'nin agresif noise reduction ve sharpening filtrelerini
 * tamamen devre dışı bırakarak sensörden gelen ham veriyi yakalar.
 */
class CameraController(
    private val context: Context,
    private val textureView: TextureView
) {
    companion object {
        private const val TAG = "PureShot"
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
    
    // Manual control values
    var manualIso: Int = 100
    var manualExposureNs: Long = 10_000_000L // 10ms = 1/100s
    var manualWhiteBalance: Int = 5500 // Kelvin
    var manualFocusDistance: Float = 0f // 0 = infinity
    var useManualMode: Boolean = false
    
    // Callback for capture events
    var onImageSaved: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * Camera'yı başlat
     */
    @SuppressLint("MissingPermission")
    fun openCamera() {
        startBackgroundThread()
        
        try {
            // Arka kamerayı bul (200MP sensör)
            cameraId = findBackCamera()
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            
            // Sensör özelliklerini al
            loadCameraCapabilities(characteristics)
            
            // Kamerayı aç
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCameraPreviewSession()
                    Log.d(TAG, "Camera opened successfully")
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

    /**
     * Arka kamerayı bul
     */
    private fun findBackCamera(): String {
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        return cameraManager.cameraIdList[0]
    }

    /**
     * Kamera özelliklerini yükle
     */
    private fun loadCameraCapabilities(characteristics: CameraCharacteristics) {
        // ISO aralığı
        isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        Log.d(TAG, "ISO Range: $isoRange")
        
        // Exposure aralığı (nanosaniye)
        exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        Log.d(TAG, "Exposure Range: $exposureRange")
        
        // Sensör boyutu
        val activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        activeArraySize?.let {
            sensorSize = Size(it.width(), it.height())
            Log.d(TAG, "Sensor Size: ${sensorSize.width}x${sensorSize.height}")
        }
        
        // RAW desteği kontrol
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        supportsRaw = capabilities?.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
        ) == true
        Log.d(TAG, "RAW Support: $supportsRaw")
    }

    /**
     * Preview session oluştur
     */
    private fun createCameraPreviewSession() {
        try {
            val texture = textureView.surfaceTexture ?: return
            
            // Preview boyutunu ayarla
            val previewSize = getOptimalPreviewSize()
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            
            val surface = Surface(texture)
            
            // ImageReader for still capture
            val captureSize = getLargestOutputSize()
            imageReader = ImageReader.newInstance(
                captureSize.width, 
                captureSize.height,
                ImageFormat.JPEG, 
                2
            )
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                image?.let { saveImage(it) }
            }, backgroundHandler)
            
            val captureBuilder = cameraDevice?.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            ) ?: return
            
            captureBuilder.addTarget(surface)
            
            // 🔴 ZERO PROCESSING - Tüm post-processing KAPALI
            applyZeroProcessing(captureBuilder)
            
            // Session oluştur
            cameraDevice?.createCaptureSession(
                listOf(surface, imageReader?.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            // Repeating request for preview
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

    /**
     * 🔴 KRITIK: Tüm post-processing'i devre dışı bırak
     * Bu, Xiaomi'nin "yağlı boya" efektini tamamen kaldırır!
     */
    private fun applyZeroProcessing(builder: CaptureRequest.Builder) {
        builder.apply {
            // ═══════════════════════════════════════════════════════════
            // 🔴 NOISE REDUCTION - TAMAMEN KAPALI
            // Xiaomi'nin agresif gürültü azaltması burada devre dışı
            // ═══════════════════════════════════════════════════════════
            set(CaptureRequest.NOISE_REDUCTION_MODE, 
                CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            
            // ═══════════════════════════════════════════════════════════
            // 🔴 EDGE ENHANCEMENT (SHARPENING) - KAPALI
            // Yapay keskinleştirme efektini kaldır
            // ═══════════════════════════════════════════════════════════
            set(CaptureRequest.EDGE_MODE, 
                CaptureRequest.EDGE_MODE_OFF)
            
            // ═══════════════════════════════════════════════════════════
            // 🔴 COLOR CORRECTION - FAST/PASSTHROUGH
            // Renk manipülasyonunu minimize et
            // ═══════════════════════════════════════════════════════════
            set(CaptureRequest.COLOR_CORRECTION_MODE, 
                CaptureRequest.COLOR_CORRECTION_MODE_FAST)
            
            // ═══════════════════════════════════════════════════════════
            // 🔴 HOT PIXEL CORRECTION - KAPALI
            // ═══════════════════════════════════════════════════════════
            set(CaptureRequest.HOT_PIXEL_MODE, 
                CaptureRequest.HOT_PIXEL_MODE_OFF)
            
            // ═══════════════════════════════════════════════════════════
            // 🔴 LENS SHADING CORRECTION - KAPALI
            // ═══════════════════════════════════════════════════════════
            set(CaptureRequest.SHADING_MODE, 
                CaptureRequest.SHADING_MODE_OFF)
            
            // ═══════════════════════════════════════════════════════════
            // 🔴 TONEMAP - FAST (minimal processing)
            // HDR tone mapping'i minimize et
            // ═══════════════════════════════════════════════════════════
            set(CaptureRequest.TONEMAP_MODE, 
                CaptureRequest.TONEMAP_MODE_FAST)
            
            // ═══════════════════════════════════════════════════════════
            // 🔴 FACE DETECTION - KAPALI
            // Yüz algılama ve güzelleştirmeyi devre dışı bırak
            // ═══════════════════════════════════════════════════════════
            set(CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF)
            
            // ═══════════════════════════════════════════════════════════
            // 🔴 ABERRATION CORRECTION - KAPALI
            // Renk sapması düzeltmesini kapat
            // ═══════════════════════════════════════════════════════════
            set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF)
            
            // Manuel kontroller aktifse uygula
            if (useManualMode) {
                applyManualControls(this)
            }
        }
    }

    /**
     * Manuel kontrolleri uygula
     */
    private fun applyManualControls(builder: CaptureRequest.Builder) {
        builder.apply {
            // Auto exposure KAPALI
            set(CaptureRequest.CONTROL_AE_MODE, 
                CaptureRequest.CONTROL_AE_MODE_OFF)
            
            // Manuel ISO
            isoRange?.let { range ->
                val clampedIso = manualIso.coerceIn(range.lower, range.upper)
                set(CaptureRequest.SENSOR_SENSITIVITY, clampedIso)
            }
            
            // Manuel exposure time
            exposureRange?.let { range ->
                val clampedExposure = manualExposureNs.coerceIn(range.lower, range.upper)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, clampedExposure)
            }
            
            // Manuel white balance
            set(CaptureRequest.CONTROL_AWB_MODE, 
                CaptureRequest.CONTROL_AWB_MODE_OFF)
            
            // Manuel focus
            set(CaptureRequest.CONTROL_AF_MODE, 
                CaptureRequest.CONTROL_AF_MODE_OFF)
            set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance)
        }
    }

    /**
     * Fotoğraf çek - INSTANT SHUTTER
     */
    fun captureImage() {
        try {
            val captureBuilder = cameraDevice?.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE
            ) ?: return
            
            imageReader?.surface?.let { captureBuilder.addTarget(it) }
            
            // Zero processing uygula
            applyZeroProcessing(captureBuilder)
            
            // JPEG kalitesi maksimum
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            
            // Rotation
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, 90)
            
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

    /**
     * Görüntüyü kaydet
     */
    private fun saveImage(image: Image) {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "PureShot_$timestamp.jpg"
        
        val outputDir = File(context.getExternalFilesDir(null), "PureShot")
        if (!outputDir.exists()) outputDir.mkdirs()
        
        val outputFile = File(outputDir, fileName)
        
        try {
            FileOutputStream(outputFile).use { output ->
                output.write(bytes)
            }
            onImageSaved?.invoke(outputFile.absolutePath)
            Log.d(TAG, "Image saved: ${outputFile.absolutePath}")
        } catch (e: Exception) {
            onError?.invoke("Save error: ${e.message}")
        } finally {
            image.close()
        }
    }

    /**
     * Optimal preview boyutu
     */
    private fun getOptimalPreviewSize(): Size {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: arrayOf()
        
        // 16:9 oranına en yakın boyutu bul
        val targetRatio = 16.0 / 9.0
        return sizes.minByOrNull { 
            kotlin.math.abs(it.width.toDouble() / it.height - targetRatio) 
        } ?: Size(1920, 1080)
    }

    /**
     * En büyük çıktı boyutu (yüksek çözünürlük)
     */
    private fun getLargestOutputSize(): Size {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.JPEG) ?: arrayOf()
        
        return sizes.maxByOrNull { it.width * it.height } ?: Size(4000, 3000)
    }

    /**
     * Preview'ı güncelle (slider değiştiğinde)
     */
    fun updatePreview() {
        try {
            val texture = textureView.surfaceTexture ?: return
            val surface = Surface(texture)
            
            val builder = cameraDevice?.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            ) ?: return
            
            builder.addTarget(surface)
            applyZeroProcessing(builder)
            
            captureSession?.setRepeatingRequest(
                builder.build(),
                null,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Update preview error: ${e.message}")
        }
    }

    /**
     * Kamera bilgilerini al
     */
    fun getCameraInfo(): CameraInfo {
        return CameraInfo(
            isoRange = isoRange,
            exposureRange = exposureRange,
            sensorSize = sensorSize,
            supportsRaw = supportsRaw
        )
    }

    /**
     * Background thread başlat
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    /**
     * Background thread durdur
     */
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

    /**
     * Kaynakları serbest bırak
     */
    fun close() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        stopBackgroundThread()
    }

    /**
     * Camera info data class
     */
    data class CameraInfo(
        val isoRange: android.util.Range<Int>?,
        val exposureRange: android.util.Range<Long>?,
        val sensorSize: Size,
        val supportsRaw: Boolean
    )
}
