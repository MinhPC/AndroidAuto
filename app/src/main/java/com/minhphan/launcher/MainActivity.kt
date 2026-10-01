package com.minhphan.launcher

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minhphan.launcher.ui.LauncherApp
import com.minhphan.launcher.ui.LauncherTheme
import com.minhphan.launcher.ui.rememberDarkTheme

class MainActivity : ComponentActivity() {
    // In Vietnamese whatever the head unit's language (see AppLanguage): an override kept through every configuration
    // change, so day and night and the rest still follow the system.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        applyOverrideConfiguration(appLanguageOverride())
        useAppLocale()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        useAppLocale()
    }

    private val viewModel: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android's own status bar stays along the top; Home lays itself out under it.
        enableEdgeToEdge()
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val screenLight by viewModel.screenLight.collectAsStateWithLifecycle()
            LauncherTheme(darkTheme = rememberDarkTheme(settings.theme, screenLight.dimmed)) {
                LauncherApp(viewModel)
            }
        }
        // Home paints every pixel itself, so the window's own background would only be painted over on every frame,
        // a whole screen of work for the GPU; the system's starting window still shows the theme's colour at launch.
        window.setBackgroundDrawable(null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            viewModel.onHomePressed()
        }
    }
}
