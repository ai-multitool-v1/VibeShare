package com.setbd.vibeshare.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.setbd.vibeshare.app.navigation.AppNavHost
import com.setbd.vibeshare.app.transfer.TransferCoordinator
import com.setbd.vibeshare.domain.model.SettingsState
import com.setbd.vibeshare.domain.repository.SettingsRepository
import com.setbd.vibeshare.ui.theme.VibeShareTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Single-activity Compose host. Uses the Android SplashScreen API with a
 * short branded sequence (logo fade/scale + glow) without artificial delay.
 */
class MainActivity : ComponentActivity() {

    private val settingsRepository by inject<SettingsRepository>()
    private val coordinator by inject<TransferCoordinator>()

    private val settingsState = MutableStateFlow(SettingsState())
    private var appReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        splash.setKeepOnScreenCondition { !appReady }

        lifecycleScope.launch {
            settingsRepository.settings.collect { state ->
                settingsState.value = state
                coordinator.seedSettings(state)
                appReady = true
            }
        }

        setContent {
            val state by settingsState.collectAsState()
            VibeShareTheme(themeMode = state.themeMode) {
                AppNavHost()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Keep sessions discoverable only while the app is interactive.
        if (!coordinator.hosting.value.active) {
            coordinator.stopHosting()
        }
    }
}
