package com.pureshot.camera

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL

/**
 * UpdateChecker - GitHub Releases üzerinden güncelleme kontrolü
 */
class UpdateChecker(private val context: Context) {
    
    companion object {
        private const val TAG = "UpdateChecker"
        private const val GITHUB_API_URL = 
            "https://api.github.com/repos/yasarcamus/PureShot/releases/latest"
        private const val CURRENT_VERSION = "3.0.0"
    }
    
    data class UpdateInfo(
        val hasUpdate: Boolean,
        val latestVersion: String,
        val downloadUrl: String,
        val releaseNotes: String
    )
    
    /**
     * GitHub'dan en son sürümü kontrol et
     */
    suspend fun checkForUpdate(): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(GITHUB_API_URL).openConnection()
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                
                val response = connection.getInputStream().bufferedReader().readText()
                val json = org.json.JSONObject(response)
                
                val tagName = json.optString("tag_name", "v1.0.0")
                    .removePrefix("v")
                val body = json.optString("body", "")
                
                // APK download URL bul
                val assets = json.optJSONArray("assets")
                var downloadUrl = ""
                if (assets != null && assets.length() > 0) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk")) {
                            downloadUrl = asset.optString("browser_download_url", "")
                            break
                        }
                    }
                }
                
                // Fallback to releases page
                if (downloadUrl.isEmpty()) {
                    downloadUrl = "https://github.com/yasarcamus/PureShot/releases/latest"
                }
                
                val hasUpdate = isNewerVersion(tagName, CURRENT_VERSION)
                
                UpdateInfo(
                    hasUpdate = hasUpdate,
                    latestVersion = tagName,
                    downloadUrl = downloadUrl,
                    releaseNotes = body
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed: ${e.message}")
                null
            }
        }
    }
    
    /**
     * Versiyon karşılaştırması
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        try {
            val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
            
            for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Version compare error: ${e.message}")
        }
        return false
    }
    
    /**
     * İndirme sayfasını aç
     */
    fun openDownloadPage(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Open URL error: ${e.message}")
        }
    }
}
