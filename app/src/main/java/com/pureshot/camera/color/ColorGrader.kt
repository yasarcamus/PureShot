package com.pureshot.camera.color

import android.graphics.Bitmap
import android.util.Log

/**
 * ColorGrader - LUT tabanlı renk düzeltme
 * 
 * iPhone ve Pixel benzeri renk profilleri uygular
 */
class ColorGrader {

    companion object {
        private const val TAG = "ColorGrader"
    }

    private var currentLut: LutLoader.Lut3D? = null
    private var intensity = 1.0f

    /**
     * LUT ayarla
     */
    fun setLut(lut: LutLoader.Lut3D?) {
        currentLut = lut
        Log.d(TAG, "LUT set: ${lut?.name ?: "None"}")
    }

    /**
     * Efekt yoğunluğu (0-1)
     */
    fun setIntensity(intensity: Float) {
        this.intensity = intensity.coerceIn(0f, 1f)
    }

    /**
     * LUT'u bitmap'e uygula
     */
    fun apply(input: Bitmap): Bitmap {
        val lut = currentLut ?: return input
        if (intensity <= 0f) return input

        val width = input.width
        val height = input.height
        val result = input.copy(Bitmap.Config.ARGB_8888, true)

        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, 0, 0, width, height)

        val lutSize = lut.size
        val lutData = lut.data

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val a = (pixel shr 24) and 0xFF
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            // LUT lookup (trilinear interpolation)
            val newColor = lookupLut(r, g, b, lutSize, lutData)

            // Blend with original based on intensity
            val finalR = lerp(r, newColor.first, intensity)
            val finalG = lerp(g, newColor.second, intensity)
            val finalB = lerp(b, newColor.third, intensity)

            pixels[i] = (a shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * LUT lookup with nearest neighbor (simplified)
     */
    private fun lookupLut(r: Int, g: Int, b: Int, size: Int, data: FloatArray): Triple<Int, Int, Int> {
        // Map 0-255 to 0-(size-1)
        val rIdx = (r * (size - 1) / 255).coerceIn(0, size - 1)
        val gIdx = (g * (size - 1) / 255).coerceIn(0, size - 1)
        val bIdx = (b * (size - 1) / 255).coerceIn(0, size - 1)

        // Calculate index into LUT data
        val index = (bIdx * size * size + gIdx * size + rIdx) * 3

        return if (index + 2 < data.size) {
            Triple(
                (data[index] * 255).toInt().coerceIn(0, 255),
                (data[index + 1] * 255).toInt().coerceIn(0, 255),
                (data[index + 2] * 255).toInt().coerceIn(0, 255)
            )
        } else {
            Triple(r, g, b)
        }
    }

    private fun lerp(a: Int, b: Int, t: Float): Int {
        return (a + (b - a) * t).toInt().coerceIn(0, 255)
    }

    /**
     * Preset LUT profilleri (software fallback)
     */
    fun applyPreset(input: Bitmap, preset: ColorPreset): Bitmap {
        val width = input.width
        val height = input.height
        val result = input.copy(Bitmap.Config.ARGB_8888, true)

        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val a = (pixel shr 24) and 0xFF
            var r = (pixel shr 16) and 0xFF
            var g = (pixel shr 8) and 0xFF
            var b = pixel and 0xFF

            when (preset) {
                ColorPreset.IPHONE -> {
                    // Sıcak, hafif saturated
                    r = (r * 1.02).toInt().coerceIn(0, 255)
                    g = (g * 1.01).toInt().coerceIn(0, 255)
                    // Hafif contrast artışı
                    r = ((r - 128) * 1.05 + 128).toInt().coerceIn(0, 255)
                    g = ((g - 128) * 1.05 + 128).toInt().coerceIn(0, 255)
                    b = ((b - 128) * 1.05 + 128).toInt().coerceIn(0, 255)
                }
                ColorPreset.PIXEL_HDR -> {
                    // Yüksek contrast, vibrant
                    r = ((r - 128) * 1.15 + 128).toInt().coerceIn(0, 255)
                    g = ((g - 128) * 1.15 + 128).toInt().coerceIn(0, 255)
                    b = ((b - 128) * 1.15 + 128).toInt().coerceIn(0, 255)
                }
                ColorPreset.GOLDEN_HOUR -> {
                    r = (r * 1.1).toInt().coerceIn(0, 255)
                    g = (g * 1.05).toInt().coerceIn(0, 255)
                    b = (b * 0.9).toInt().coerceIn(0, 255)
                }
                ColorPreset.CINEMATIC -> {
                    // Teal & Orange look
                    r = (r * 1.05).toInt().coerceIn(0, 255)
                    g = (g * 0.95).toInt().coerceIn(0, 255)
                    b = (b * 1.1).toInt().coerceIn(0, 255)
                }
                ColorPreset.NONE -> {
                    // No change
                }
            }

            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    enum class ColorPreset {
        NONE,
        IPHONE,
        PIXEL_HDR,
        GOLDEN_HOUR,
        CINEMATIC
    }
}
