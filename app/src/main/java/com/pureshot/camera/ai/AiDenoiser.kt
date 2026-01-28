package com.pureshot.camera.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * AiDenoiser - AI tabanlı gürültü azaltma
 * 
 * Xiaomi'nin agresif gürültü azaltmasının aksine,
 * detayları koruyarak doğal bir şekilde gürültüyü azaltır.
 */
class AiDenoiser(private val context: Context) {

    companion object {
        private const val TAG = "AiDenoise"
    }

    private var isInitialized = false
    var strength = 0.5f // 0-1 arası

    fun initialize() {
        isInitialized = true
        Log.d(TAG, "AI Denoiser initialized")
    }

    /**
     * Gürültü azaltma uygula
     */
    fun process(input: Bitmap): Bitmap {
        if (!isInitialized || strength <= 0) {
            return input
        }

        return applyNonLocalMeansLite(input)
    }

    /**
     * Basit bilateral-like filter (placeholder)
     * Gerçek implementasyonda TFLite denoising model kullanılacak
     */
    private fun applyNonLocalMeansLite(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        
        val pixels = IntArray(width * height)
        val output = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        val radius = 2
        val sigma = (strength * 30).toInt().coerceIn(10, 50)
        
        for (y in radius until height - radius) {
            for (x in radius until width - radius) {
                val centerPixel = pixels[y * width + x]
                val centerR = (centerPixel shr 16) and 0xFF
                val centerG = (centerPixel shr 8) and 0xFF
                val centerB = centerPixel and 0xFF
                
                var sumR = 0.0
                var sumG = 0.0
                var sumB = 0.0
                var weightSum = 0.0
                
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val pixel = pixels[(y + dy) * width + (x + dx)]
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        
                        // Renk benzerliğine göre ağırlık
                        val colorDist = kotlin.math.sqrt(
                            ((r - centerR) * (r - centerR) +
                             (g - centerG) * (g - centerG) +
                             (b - centerB) * (b - centerB)).toDouble()
                        )
                        val weight = kotlin.math.exp(-colorDist / sigma)
                        
                        sumR += r * weight
                        sumG += g * weight
                        sumB += b * weight
                        weightSum += weight
                    }
                }
                
                val newR = (sumR / weightSum).toInt().coerceIn(0, 255)
                val newG = (sumG / weightSum).toInt().coerceIn(0, 255)
                val newB = (sumB / weightSum).toInt().coerceIn(0, 255)
                
                output[y * width + x] = (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB
            }
        }
        
        result.setPixels(output, 0, width, 0, 0, width, height)
        return result
    }

    fun close() {
        isInitialized = false
    }
}
