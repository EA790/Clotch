package com.example.discord

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Lightweight Discord Gateway v10 WebSocket RPC client for Android.
 * Maintains a stable connection with heartbeats and exponential-backoff reconnection,
 * updating OP 3 Presence when screen recording starts/pauses/stops.
 * All failures are gracefully handled without impacting screen recording and surfaced via Toast.
 */
class DiscordRpcClient(private val context: Context) {

    companion object {
        private const val DISCORD_GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"
        private const val OP_DISPATCH = 0
        private const val OP_HEARTBEAT = 1
        private const val OP_IDENTIFY = 2
        private const val OP_PRESENCE_UPDATE = 3
        private const val OP_RECONNECT = 7
        private const val OP_INVALID_SESSION = 9
        private const val OP_HELLO = 10
        private const val OP_HEARTBEAT_ACK = 11
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _connectionState =
        MutableStateFlow<DiscordConnectionState>(DiscordConnectionState.Disconnected)
    val connectionState: StateFlow<DiscordConnectionState> = _connectionState.asStateFlow()

    private val _currentPresence = MutableStateFlow<DiscordPresencePayload?>(null)
    val currentPresence: StateFlow<DiscordPresencePayload?> = _currentPresence.asStateFlow()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var lastSequence: Int? = null

    @Volatile
    private var activeToken: String = ""

    @Volatile
    private var connectedUsername: String = "Discord User"

    @Volatile
    private var userInitiatedDisconnect = false

    @Volatile
    private var reconnectAttempt = 0

    @Volatile
    private var lastErrorToastAtMs = 0L

    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null

    fun connect(token: String, showSuccessToast: Boolean = false) {
        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) {
            val msg = "Discord RPC unavailable: Add your Discord token in Settings"
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

        activeToken = cleanToken
        userInitiatedDisconnect = false
        reconnectJob?.cancel()
        openWebSocket(showSuccessToast)
    }

    fun disconnect(clearPresence: Boolean = true) {
        userInitiatedDisconnect = true
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        if (clearPresence) {
            _currentPresence.value = null
            runCatching { sendClearPresenceInternal() }
        }
        runCatching { webSocket?.close(1000, "Client disconnected") }
        webSocket = null
        _connectionState.value = DiscordConnectionState.Disconnected
    }

    /**
     * Called when recording starts, pauses, resumes, or stops.
     * Always updates local presence preview; attempts live Discord OP 3 sync if enabled.
     */
    fun updatePresence(
        payload: DiscordPresencePayload?,
        rpcEnabled: Boolean,
        token: String
    ) {
        _currentPresence.value = payload

        if (!rpcEnabled) {
            if (webSocket != null) {
                runCatching { sendClearPresenceInternal() }
            }
            return
        }

        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) {
            // Only notify if recording started with RPC enabled but no token set
            if (payload != null && payload.isRecording) {
                val msg = "Discord RPC skipped: Configure Discord token in Settings"
                _connectionState.value = DiscordConnectionState.Error(msg, isAuthError = true)
                showRateLimitedToast(msg)
            }
            return
        }

        activeToken = cleanToken
        val currentState = _connectionState.value

        if (currentState is DiscordConnectionState.Connected && webSocket != null) {
            val sent = if (payload != null) {
                sendPresenceInternal(payload)
            } else {
                sendClearPresenceInternal()
            }
            if (!sent) {
                val msg = "Discord RPC failed to send status update"
                _connectionState.value = DiscordConnectionState.Error(msg)
                showRateLimitedToast(msg)
            } else {
                _connectionState.value = DiscordConnectionState.Connected(
                    username = connectedUsername,
                    activeStatusText = payload?.formatStatusText()
                )
            }
        } else if (payload != null && payload.isRecording) {
            // Auto-connect seamlessly when recording starts
            connect(cleanToken, showSuccessToast = false)
        }
    }

    private fun openWebSocket(showSuccessToast: Boolean) {
        heartbeatJob?.cancel()
        runCatching { webSocket?.cancel() }
        _connectionState.value = DiscordConnectionState.Connecting

        val request = Request.Builder()
            .url(DISCORD_GATEWAY_URL)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleGatewayMessage(webSocket, text, showSuccessToast)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                heartbeatJob?.cancel()
                if (userInitiatedDisconnect || code == 1000) {
                    _connectionState.value = DiscordConnectionState.Disconnected
                    return
                }
                val isAuthFailure = code == 4004 || code == 4014 || code == 4013
                val message = when (code) {
                    4004 -> "Discord RPC failed: Authentication failed (invalid token)"
                    4014 -> "Discord RPC failed: Disallowed gateway intents"
                    else -> "Discord RPC disconnected (code $code)"
                }
                _connectionState.value = DiscordConnectionState.Error(message, isAuthError = isAuthFailure)
                showRateLimitedToast(message)

                if (!isAuthFailure) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                heartbeatJob?.cancel()
                if (userInitiatedDisconnect) return

                val detail = t.localizedMessage?.takeIf { it.isNotBlank() } ?: "Connection lost"
                val message = "Discord RPC error: $detail"
                _connectionState.value = DiscordConnectionState.Error(message)
                showRateLimitedToast(message)
                scheduleReconnect()
            }
        })
    }

    private fun handleGatewayMessage(
        socket: WebSocket,
        text: String,
        showSuccessToast: Boolean
    ) {
        try {
            val json = JSONObject(text)
            val op = json.optInt("op", -1)
            if (!json.isNull("s")) {
                lastSequence = json.optInt("s")
            }

            when (op) {
                OP_HELLO -> {
                    val d = json.optJSONObject("d")
                    val intervalMs = d?.optLong("heartbeat_interval", 41250L) ?: 41250L
                    startHeartbeatLoop(socket, intervalMs)
                    sendIdentify(socket, activeToken)
                }

                OP_HEARTBEAT -> {
                    sendHeartbeat(socket)
                }

                OP_HEARTBEAT_ACK -> {
                    // Heartbeat acknowledged cleanly
                }

                OP_DISPATCH -> {
                    val eventType = json.optString("t", "")
                    if (eventType == "READY") {
                        val userObj = json.optJSONObject("d")?.optJSONObject("user")
                        val globalName = userObj?.optString("global_name", "")?.takeIf { it.isNotBlank() && it != "null" }
                        val username = userObj?.optString("username", "")?.takeIf { it.isNotBlank() && it != "null" }
                        connectedUsername = globalName ?: username ?: "Discord User"

                        val pending = _currentPresence.value
                        if (pending != null) {
                            sendPresenceInternal(pending)
                        }
                        _connectionState.value = DiscordConnectionState.Connected(
                            username = connectedUsername,
                            activeStatusText = pending?.formatStatusText()
                        )
                        if (showSuccessToast) {
                            showToast("Discord RPC connected as $connectedUsername")
                        }
                    }
                }

                OP_RECONNECT -> {
                    socket.close(4000, "Gateway requested reconnect")
                    scheduleReconnect()
                }

                OP_INVALID_SESSION -> {
                    val resumable = json.optBoolean("d", false)
                    if (!resumable) {
                        val msg = "Discord RPC failed: Invalid session or token rejected"
                        _connectionState.value = DiscordConnectionState.Error(msg, isAuthError = true)
                        showRateLimitedToast(msg)
                        socket.close(4004, "Invalid session")
                    } else {
                        scheduleReconnect()
                    }
                }
            }
        } catch (e: Exception) {
            val msg = "Discord RPC parse error: ${e.localizedMessage ?: "Malformed payload"}"
            _connectionState.value = DiscordConnectionState.Error(msg)
            showRateLimitedToast(msg)
        }
    }

    private fun startHeartbeatLoop(socket: WebSocket, intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            val jitter = (intervalMs * Random.nextDouble(0.1, 0.5)).toLong()
            delay(jitter)
            while (isActive) {
                if (!sendHeartbeat(socket)) break
                delay(intervalMs)
            }
        }
    }

    private fun sendHeartbeat(socket: WebSocket): Boolean {
        return try {
            val payload = JSONObject().apply {
                put("op", OP_HEARTBEAT)
                if (lastSequence != null) {
                    put("d", lastSequence)
                } else {
                    put("d", JSONObject.NULL)
                }
            }
            socket.send(payload.toString())
        } catch (_: Exception) {
            false
        }
    }

    private fun sendIdentify(socket: WebSocket, token: String) {
        try {
            val properties = JSONObject().apply {
                put("os", "android")
                put("browser", "Clotch")
                put("device", "Clotch")
            }
            val data = JSONObject().apply {
                put("token", token)
                put("intents", 0)
                put("properties", properties)
                _currentPresence.value?.let { pending ->
                    put("presence", buildPresenceData(pending))
                }
            }
            val identify = JSONObject().apply {
                put("op", OP_IDENTIFY)
                put("d", data)
            }
            socket.send(identify.toString())
        } catch (e: Exception) {
            val msg = "Discord RPC identify failed: ${e.localizedMessage ?: "Unknown error"}"
            _connectionState.value = DiscordConnectionState.Error(msg)
            showRateLimitedToast(msg)
        }
    }

    private fun sendPresenceInternal(payload: DiscordPresencePayload): Boolean {
        val socket = webSocket ?: return false
        return try {
            val root = JSONObject().apply {
                put("op", OP_PRESENCE_UPDATE)
                put("d", buildPresenceData(payload))
            }
            socket.send(root.toString())
        } catch (_: Exception) {
            false
        }
    }

    private fun sendClearPresenceInternal(): Boolean {
        val socket = webSocket ?: return false
        return try {
            val data = JSONObject().apply {
                put("since", JSONObject.NULL)
                put("activities", JSONArray())
                put("status", "online")
                put("afk", false)
            }
            val root = JSONObject().apply {
                put("op", OP_PRESENCE_UPDATE)
                put("d", data)
            }
            socket.send(root.toString())
        } catch (_: Exception) {
            false
        }
    }

    private fun buildPresenceData(payload: DiscordPresencePayload): JSONObject {
        val activity = JSONObject().apply {
            put("name", "Clotch")
            put("type", 0) // Playing
            put("details", payload.formatStatusText()) // e.g. "Recording Mobile Game at 60FPS"
            put("state", payload.formatSubDetails())
            if (payload.isRecording && !payload.isPaused && payload.startedAtEpochMs > 0L) {
                put(
                    "timestamps",
                    JSONObject().apply { put("start", payload.startedAtEpochMs) }
                )
            }
        }
        return JSONObject().apply {
            put("since", if (payload.startedAtEpochMs > 0L) payload.startedAtEpochMs else JSONObject.NULL)
            put("activities", JSONArray().apply { put(activity) })
            put("status", "online")
            put("afk", false)
        }
    }

    private fun scheduleReconnect() {
        if (userInitiatedDisconnect || activeToken.isBlank()) return
        if (reconnectJob?.isActive == true) return
        if (reconnectAttempt >= 4) return

        val backoffMs = (2000L * (1 shl reconnectAttempt)).coerceAtMost(30_000L)
        reconnectAttempt++
        reconnectJob = scope.launch {
            delay(backoffMs)
            if (!userInitiatedDisconnect && isNetworkAvailable()) {
                openWebSocket(showSuccessToast = false)
            }
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
