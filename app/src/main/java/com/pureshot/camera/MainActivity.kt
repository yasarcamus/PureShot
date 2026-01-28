package com.pureshot.camera

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.pureshot.camera.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * MainActivity v3.0 - PureShot Pro
 * 
 * Bottom navigation ile 3 sekmeli yapı:
 * - Kamera
 * - Pro Ayarları
 * - Rehber
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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

        setupNavigation()
        checkForUpdates()
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        binding.bottomNav.setupWithNavController(navController)
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
}
