package com.pureshot.camera.color

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * LutLoader - 3D LUT yükleme
 * 
 * .cube ve .3dl formatlarını destekler
 */
class LutLoader(private val context: Context) {

    companion object {
        private const val TAG = "LutLoader"
        private const val LUT_SIZE = 33 // Standart 33x33x33 LUT
    }

    data class Lut3D(
        val name: String,
        val size: Int,
        val data: FloatArray // R, G, B değerleri
    )

    /**
     * LUT dosyasını yükle
     */
    fun loadFromAssets(fileName: String): Lut3D? {
        return try {
            context.assets.open("luts/$fileName").use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                parseCubeFile(fileName, reader)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load LUT: $fileName - ${e.message}")
            null
        }
    }

    /**
     * .cube formatını parse et
     */
    private fun parseCubeFile(name: String, reader: BufferedReader): Lut3D {
        var size = LUT_SIZE
        val dataList = mutableListOf<Float>()

        reader.forEachLine { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("LUT_3D_SIZE") -> {
                    size = trimmed.split(" ").lastOrNull()?.toIntOrNull() ?: LUT_SIZE
                }
                trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("TITLE") -> {
                    val values = trimmed.split(Regex("\\s+"))
                    if (values.size >= 3) {
                        try {
                            dataList.add(values[0].toFloat())
                            dataList.add(values[1].toFloat())
                            dataList.add(values[2].toFloat())
                        } catch (e: NumberFormatException) {
                            // Skip non-numeric lines
                        }
                    }
                }
            }
        }

        return Lut3D(
            name = name.removeSuffix(".cube"),
            size = size,
            data = dataList.toFloatArray()
        )
    }

    /**
     * Mevcut LUT'ları listele
     */
    fun listAvailableLuts(): List<String> {
        return try {
            context.assets.list("luts")?.filter { 
                it.endsWith(".cube") || it.endsWith(".3dl") 
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list LUTs: ${e.message}")
            emptyList()
        }
    }
}
