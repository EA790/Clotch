package com.example

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
        if (intent?.action == RecordingController.ACTION_QUICK_START) {
            intent.action = null
            quickStartTriggerCount++
            return
        }

        val data: Uri? = intent?.data
        if (data != null && data.scheme == "clotch" && data.host == "discord-callback") {
            val fragment = data.fragment.orEmpty()
            val queryParams = data.query.orEmpty()
            val token = extractTokenParam(fragment) ?: extractTokenParam(queryParams)
            if (!token.isNullOrBlank()) {
                oauthAccessToken = token
            }
            intent.data = null
        }
    }

    private fun extractTokenParam(raw: String): String? {
        if (raw.isBlank()) return null
        return raw.split("&")
            .mapNotNull { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2 && parts[0] == "access_token") {
                    Uri.decode(parts[1])
                } else {
                    null
                }
            }
            .firstOrNull()
    }
}
