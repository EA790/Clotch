package com.example.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.diagnostics.CrashLogger
import com.example.diagnostics.DiscordLogger
import com.example.discord.DiscordProfileManager
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.ClotchBlue
import com.example.ui.theme.DiscordBlurple
import com.example.ui.theme.ElevatedSlate
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateCard
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicBoolean

private const val DISCORD_LOGIN_URL = "https://discord.com/login"

private val EXTRACT_DISCORD_TOKEN_JS = """
    (function() {
        try {
            var token = null;
            if (window.localStorage && typeof window.localStorage.getItem === 'function') {
                token = window.localStorage.getItem('token');
            }
            if (!token && document.body) {
                var iframe = document.createElement('iframe');
                iframe.style.display = 'none';
                document.body.appendChild(iframe);
                try {
                    if (iframe.contentWindow && iframe.contentWindow.localStorage) {
                        token = iframe.contentWindow.localStorage.getItem('token');
                    }
                } finally {
                    document.body.removeChild(iframe);
                }
            }
            return token || '';
        } catch (err) {
            return '';
        }
    })();
""".trimIndent()

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DiscordWebViewLoginDialog(
    onDismiss: () -> Unit,
    onTokenExtracted: (String) -> Unit
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(DISCORD_LOGIN_URL) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var statusText by remember { mutableStateOf("Sign in to Discord to connect KizzyRPC") }
    val tokenDispatched = remember { AtomicBoolean(false) }
    val latestOnTokenExtracted by rememberUpdatedState(onTokenExtracted)
    val latestOnDismiss by rememberUpdatedState(onDismiss)

    fun evaluateTokenExtraction(view: WebView?, triggerSource: String) {
        if (view == null || tokenDispatched.get()) return
        runCatching {
            view.evaluateJavascript(EXTRACT_DISCORD_TOKEN_JS) { rawResult ->
                if (tokenDispatched.get()) return@evaluateJavascript
                val parsed = decodeJsStringResult(rawResult)
                val cleanToken = DiscordProfileManager.sanitizeToken(parsed)
                if (
                    cleanToken.length >= 20 &&
                    !cleanToken.equals("null", ignoreCase = true) &&
                    !cleanToken.equals("undefined", ignoreCase = true) &&
                    DiscordProfileManager.isHeaderSafeToken(cleanToken)
                ) {
                    if (tokenDispatched.compareAndSet(false, true)) {
                        DiscordLogger.logTokenExtracted("WebView ($triggerSource)", cleanToken)
                        statusText = "Token detected, connecting Discord..."
                        latestOnTokenExtracted(cleanToken)
                    }
                } else if (triggerSource == "Manual") {
                    DiscordLogger.logWebViewEvent(
                        event = "Manual extraction found no token yet",
                        url = currentUrl
                    )
                    statusText = "No token yet — finish signing in above first"
                }
            }
        }.onFailure { t ->
            CrashLogger.logError("DiscordWebViewLogin.JS", "evaluateJavascript failed ($triggerSource)", t)
            DiscordLogger.logWebViewEvent("JS evaluation failed ($triggerSource)", currentUrl, t.message)
        }
    }

    LaunchedEffect(Unit) {
        DiscordLogger.logWebViewEvent("WebView login opened", DISCORD_LOGIN_URL)
        while (isActive && !tokenDispatched.get()) {
            delay(1_200L)
            val wv = webViewRef ?: continue
            val urlNow = currentUrl
            if (urlNow.contains("discord.com", ignoreCase = true)) {
                evaluateTokenExtraction(wv, "Poll")
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.clearHistory()
                    wv.removeAllViews()
                    wv.destroy()
                }
            }
            webViewRef = null
        }
    }

    Dialog(
        onDismissRequest = { latestOnDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .systemBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            color = SlateCard,
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        DiscordLogoBadge(size = 34.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Discord Web Login",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = currentUrl,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TactileIconButton(
                            onClick = { webViewRef?.reload() },
                            modifier = Modifier.testTag("discord_webview_reload_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Reload Discord login page",
                                tint = TextSecondary
                            )
                        }
                        TactileIconButton(
                            onClick = { latestOnDismiss() },
                            modifier = Modifier.testTag("discord_webview_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close Discord login",
                                tint = TextPrimary
                            )
                        }
                    }
                }

                if (loadProgress in 1..99) {
                    LinearProgressIndicator(
                        progress = { loadProgress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = DiscordBlurple,
                        trackColor = CarbonSurface
                    )
                }

                // Embedded Discord WebView
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    @Suppress("DEPRECATION")
                                    databaseEnabled = true
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                    userAgentString = userAgentString.replace("; wv", "")
                                }
                                runCatching {
                                    val cm = CookieManager.getInstance()
                                    cm.setAcceptCookie(true)
                                    cm.setAcceptThirdPartyCookies(this, true)
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        loadProgress = newProgress
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(
                                        view: WebView?,
                                        url: String?,
                                        favicon: Bitmap?
                                    ) {
                                        if (!url.isNullOrBlank()) {
                                            currentUrl = url
                                            DiscordLogger.logWebViewEvent("Page started", url)
                                        }
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        if (!url.isNullOrBlank()) {
                                            currentUrl = url
                                            DiscordLogger.logWebViewEvent("Page finished", url)
                                        }
                                        evaluateTokenExtraction(view, "onPageFinished")
                                    }

                                    override fun doUpdateVisitedHistory(
                                        view: WebView?,
                                        url: String?,
                                        isReload: Boolean
                                    ) {
                                        super.doUpdateVisitedHistory(view, url, isReload)
                                        if (!url.isNullOrBlank()) {
                                            currentUrl = url
                                            DiscordLogger.logWebViewEvent("SPA route updated", url)
                                        }
                                        evaluateTokenExtraction(view, "SPA route")
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val nextUrl = request?.url?.toString().orEmpty()
                                        if (nextUrl.startsWith("clotch://", ignoreCase = true)) {
                                            DiscordLogger.logDeepLinkReceived(
                                                nextUrl,
                                                "Intercepted inside WebView"
                                            )
                                            return true
                                        }
                                        return false
                                    }
                                }
                                webViewRef = this
                                loadUrl(DISCORD_LOGIN_URL)
                            }
                        }
                    )
                }

                // Footer Status & Manual Extract Trigger
                Surface(
                    color = CarbonSurface,
                    shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 10.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        TactilePillButton(
                            onClick = { evaluateTokenExtraction(webViewRef, "Manual") },
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("discord_webview_extract_button"),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            containerColor = ClotchBlue,
                            contentColor = TextPrimary
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Key,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sync Token", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

internal fun decodeJsStringResult(rawResult: String?): String {
    if (rawResult.isNullOrBlank() || rawResult == "null" || rawResult == "undefined") {
        return ""
    }
    return runCatching {
        val parsed = JSONTokener(rawResult).nextValue()
        parsed?.toString().orEmpty()
    }.getOrElse {
        rawResult.trim().removeSurrounding("\"").replace("\\\"", "\"")
    }
}
