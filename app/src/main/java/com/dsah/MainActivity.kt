package com.dsha.zipforge

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dsha.zipforge.data.AppSettings
import com.dsha.zipforge.data.RecentRepository
import com.dsha.zipforge.data.SettingsRepository
import com.dsha.zipforge.task.TaskManager
import com.dsha.zipforge.ui.AppRoot
import com.dsha.zipforge.ui.theme.UnzipTheme

class MainActivity : ComponentActivity() {

    private val requestNotification =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TaskManager.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }

        val settingsRepository = SettingsRepository(applicationContext)
        val recentRepository = RecentRepository(applicationContext)

        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            UnzipTheme(themeMode = settings.theme) {
                AppRoot(
                    settingsRepository = settingsRepository,
                    recentRepository = recentRepository,
                )
            }
        }
    }
}
