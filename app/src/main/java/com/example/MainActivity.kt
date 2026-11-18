package com.example

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.diagnostics.CrashLogger
import com.example.discord.DiscordOAuthCallbackParser
import com.example.discord.DiscordOAuthCallbackResult
import com.example.recording.RecordingController
import com.example.ui.ClotchApp
import com.example.ui.theme.ClotchTheme

class MainActivity : ComponentActivity() {

    private var quickStartTriggerCount by mutableIntStateOf(0)
    private var oauthAccessToken by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        handleIncomingIntent(intent)

        setContent {
            ClotchTheme {
                ClotchApp(
                    quickStartTriggerCount = quickStartTriggerCount,
                    oauthAccessToken = oauthAccessToken,
                    onOAuthTokenConsumed = { oauthAccessToken = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        try {
            if (intent?.action == RecordingController.ACTION_QUICK_START) {
                intent.action = null
                quickStartTriggerCount++
                return
            }

            when (val parsed = DiscordOAuthCallbackParser.parse(intent?.data)) {
                is DiscordOAuthCallbackResult.NotOAuthCallback -> Unit
                is DiscordOAuthCallbackResult.Token -> {
                    oauthAccessToken = parsed.accessToken
                    intent?.data = null
                }
                is DiscordOAuthCallbackResult.Error -> {
                    Toast.makeText(
                        applicationContext,
                        parsed.message,
                        Toast.LENGTH_LONG
                    ).show()
                    intent?.data = null
                }
            }
        } catch (t: Throwable) {
            CrashLogger.logError("MainActivity.handleIncomingIntent", "Failed processing deep-link intent", t)
            Toast.makeText(applicationContext, "Unable to process callback URI", Toast.LENGTH_SHORT).show()
        }
    }
}
