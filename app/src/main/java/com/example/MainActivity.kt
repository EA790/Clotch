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

            val data: Uri? = intent?.data
            if (data != null && data.scheme == "clotch" && data.host == "discord-callback") {
                val fragment = data.encodedFragment.orEmpty()
                val queryParams = data.encodedQuery.orEmpty()

                val error = extractParam(fragment, "error") ?: extractParam(queryParams, "error")
                val errorDesc = extractParam(fragment, "error_description")
                    ?: extractParam(queryParams, "error_description")
                if (!error.isNullOrBlank()) {
                    val friendly = errorDesc?.replace('+', ' ')?.takeIf { it.isNotBlank() } ?: error
                    Toast.makeText(
                        applicationContext,
                        "Discord OAuth2 cancelled or denied: $friendly",
                        Toast.LENGTH_LONG
                    ).show()
                    intent.data = null
                    return
                }

                val token = extractParam(fragment, "access_token")
                    ?: extractParam(queryParams, "access_token")
                if (!token.isNullOrBlank()) {
                    oauthAccessToken = token
                } else {
                    Toast.makeText(
                        applicationContext,
                        "Discord OAuth2 did not return an access token",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                intent.data = null
            }
        } catch (t: Throwable) {
            CrashLogger.logError("MainActivity.handleIncomingIntent", "Failed processing deep-link intent", t)
            Toast.makeText(applicationContext, "Unable to process callback URI", Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractParam(raw: String, key: String): String? {
        if (raw.isBlank()) return null
        return raw.split("&")
            .mapNotNull { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2 && parts[0] == key) {
                    runCatching { Uri.decode(parts[1]) }.getOrNull()
                } else {
                    null
                }
            }
            .firstOrNull()
    }
}
