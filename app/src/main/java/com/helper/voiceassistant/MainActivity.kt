package com.helper.voiceassistant

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.helper.voiceassistant.manager.MediaButtonManager
import com.helper.voiceassistant.presentation.navigation.AppNavHost
import com.helper.voiceassistant.presentation.theme.VoiceAssistantTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var buttons: MediaButtonManager

    /**
     * Keyboard / remote keys chosen in Settings control the assistant while the app is open. They are
     * taken before the views see them: otherwise Enter / D-pad keys move focus and press on-screen buttons.
     * Activity.dispatchKeyEvent is public platform API; the lint restriction comes from androidx.core's override.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        buttons.onWindowKey(event) || super.dispatchKeyEvent(event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoiceAssistantTheme {
                AppNavHost()
            }
        }
    }
}
