package dev.opencode.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.ui.AppRoot
import dev.opencode.mobile.ui.theme.OpenCodeTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as OpenCodeApp).container

        setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                val settings by container.settings.settings.collectAsStateWithLifecycle()
                OpenCodeTheme(mode = settings.themeMode) {
                    AppRoot()
                }
            }
        }
    }

    override fun onDestroy() {
        // Release the loopback port when the task is actually going away, not on
        // a configuration change.
        if (isFinishing) {
            (application as OpenCodeApp).container.preview.stop()
        }
        super.onDestroy()
    }
}
