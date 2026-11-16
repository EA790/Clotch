package com.example.discord

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.my.kizzyrpc.KizzyRPC
import com.my.kizzyrpc.model.Activity
import com.my.kizzyrpc.model.Assets
import com.my.kizzyrpc.model.Timestamps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Discord Rich Presence client powered by KizzyRPC (dead8309/KizzyRPC).
 * Uses the user's Discord token and supports external largeImage URLs.
 * All errors are gracefully caught and surfaced via Toast without interrupting recording.
 */
class DiscordRpcClient(private val context: Context) {

    companion object {
        private const val CLOTCH_DISCORD_APP_ID = "962990036020756480"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _connectionState =
        MutableStateFlow<DiscordConnectionState>(DiscordConnectionState.Disconnected)
    val connectionState: StateFlow<DiscordConnectionState> = _connectionState.asStateFlow()

    private val _currentPresence = MutableStateFlow<DiscordPresencePayload?>(null)
    val currentPresence: StateFlow<DiscordPresencePayload?> = _currentPresence.asStateFlow()

    @Volatile
    private var kizzyRpc: KizzyRPC? = null

    @Volatile
    private var activeToken: String = ""

    @Volatile
    private var lastErrorToastAtMs = 0L

    fun connect(token: String, showSuccessToast: Boolean = false) {
        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) {
            val msg = "Discord RPC unavailable: Add your Discord token in Settings or Sign In"
            _connectionState.value = DiscordConnectionState.Error(msg, isAuthError = true)
            showToast(msg)
            return
        }

        if (!isNetworkAvailable()) {
            val msg = "Discord RPC connection failed: No internet connection"
            _connectionState.value = DiscordConnectionState.Error(msg)
            showToast(msg)
            return
        }

        scope.launch {
            connectAndPushInternal(
                token = cleanToken,
                payload = _currentPresence.value,
                showSuccessToast = showSuccessToast
            )
        }
    }

    fun disconnect(clearPresence: Boolean = true) {
        if (clearPresence) {
            _currentPresence.value = null
        }
        scope.launch {
            runCatching { kizzyRpc?.closeRPC() }
            kizzyRpc = null
            activeToken = ""
            _connectionState.value = DiscordConnectionState.Disconnected
        }
    }

    fun updatePresence(
        payload: DiscordPresencePayload?,
        rpcEnabled: Boolean,
        token: String
    ) {
        _currentPresence.value = payload

        if (!rpcEnabled) {
            if (kizzyRpc != null) {
                scope.launch {
                    runCatching { kizzyRpc?.closeRPC() }
                    kizzyRpc = null
                    _connectionState.value = DiscordConnectionState.Disconnected
                }
            }
            return
        }

        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) {
            if (payload != null && (payload.isRecording || payload.detectedAppName != null || payload.isLarkPlayerRunning)) {
                val msg = "Discord RPC skipped: Sign in or add your Discord token"
                _connectionState.value = DiscordConnectionState.Error(msg, isAuthError = true)
                showRateLimitedToast(msg)
            }
            return
        }

        if (!isNetworkAvailable()) {
            val msg = "Discord RPC error: Network unavailable"
            _connectionState.value = DiscordConnectionState.Error(msg)
            showRateLimitedToast(msg)
            return
        }

        scope.launch {
            if (payload == null) {
                runCatching { kizzyRpc?.closeRPC() }
                kizzyRpc = null
                _connectionState.value = DiscordConnectionState.Disconnected
            } else {
                connectAndPushInternal(
                    token = cleanToken,
                    payload = payload,
                    showSuccessToast = false
                )
            }
        }
    }

    private fun connectAndPushInternal(
        token: String,
        payload: DiscordPresencePayload?,
        showSuccessToast: Boolean
    ) {
        try {
            if (kizzyRpc == null || activeToken != token) {
                runCatching { kizzyRpc?.closeRPC() }
                _connectionState.value = DiscordConnectionState.Connecting
                kizzyRpc = KizzyRPC(token)
                activeToken = token
            }

            val rpc = kizzyRpc
                ?: throw IllegalStateException("KizzyRPC failed to initialize")

            val effectivePayload = payload ?: DiscordPresencePayload(
                isRecording = false,
                gameName = "Clotch",
                largeImageUrl = "https://example.com/cat_clotch.png"
            )

            val activity = Activity(
                applicationId = CLOTCH_DISCORD_APP_ID,
                name = effectivePayload.activityName(),
                details = effectivePayload.formatStatusText(),
                state = effectivePayload.formatSubDetails(),
                type = effectivePayload.activityType(),
                timestamps = if (effectivePayload.startedAtEpochMs > 0L) {
                    Timestamps(start = effectivePayload.startedAtEpochMs)
                } else {
                    Timestamps(start = System.currentTimeMillis())
                },
                assets = Assets(
                    largeImage = effectivePayload.largeImageUrl,
                    smallImage = effectivePayload.smallImageUrl,
                    largeText = effectivePayload.effectiveGameTitle(),
                    smallText = if (effectivePayload.isLarkPlayerRunning) "Lark Player" else "Clotch"
                )
            )

            rpc.setActivity(
                activity = activity,
                status = "online",
                since = System.currentTimeMillis()
            )

            _connectionState.value = DiscordConnectionState.Connected(
                username = "Discord RPC Active",
                activeStatusText = effectivePayload.formatStatusText()
            )
            if (showSuccessToast) {
                showToast("Kizzy Discord RPC connected")
            }
        } catch (e: Exception) {
            val detail = e.localizedMessage?.takeIf { it.isNotBlank() } ?: "Kizzy RPC failure"
            val msg = "Discord RPC error: $detail"
            _connectionState.value = DiscordConnectionState.Error(msg)
            showRateLimitedToast(msg)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showRateLimitedToast(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastErrorToastAtMs < 4_000L) return
        lastErrorToastAtMs = now
        showToast(message)
    }

    fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }
}
