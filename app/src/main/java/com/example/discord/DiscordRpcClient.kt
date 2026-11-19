package com.example.discord

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.example.diagnostics.CrashLogger
import com.example.diagnostics.DiscordLogger
import com.my.kizzyrpc.KizzyRPC
import com.my.kizzyrpc.model.Activity
import com.my.kizzyrpc.model.Assets
import com.my.kizzyrpc.model.Timestamps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Discord Rich Presence client powered by KizzyRPC (dead8309/KizzyRPC).
 * Uses the user's Discord token and supports external largeImage URLs.
 * All errors are gracefully caught and surfaced without interrupting recording.
 */
class DiscordRpcClient(private val context: Context) {

    companion object {
        private const val CLOTCH_DISCORD_APP_ID = "962990036020756480"
        private const val ERROR_TOAST_COOLDOWN_MS = 4_000L
    }

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rpcMutex = Mutex()
    private val released = AtomicBoolean(false)

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
    private var desiredToken: String = ""

    @Volatile
    private var desiredRpcEnabled: Boolean = true

    @Volatile
    private var manuallyDisconnected: Boolean = false

    @Volatile
    private var isAuthRejected: Boolean = false

    @Volatile
    private var lastErrorToastAtMs: Long = 0L

    private val networkLock = Any()
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallbackRegistered: Boolean = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleNetworkRestored()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            val hasInternet =
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            if (hasInternet) {
                handleNetworkRestored()
            }
        }

        override fun onLost(network: Network) {
            if (released.get()) return
            if (!isNetworkAvailable()) {
                scope.launch {
                    rpcMutex.withLock {
                        closeSocketLocked()
                        if (desiredRpcEnabled && desiredToken.isNotBlank() && !manuallyDisconnected) {
                            val errMsg = "Discord RPC waiting for internet connection"
                            DiscordLogger.logRpcState("Error", "$errMsg (network lost)")
                            _connectionState.value = DiscordConnectionState.Error(
                                message = errMsg,
                                isAuthError = false
                            )
                        } else {
                            DiscordLogger.logRpcState("Disconnected", "Network lost while idle/disabled")
                            _connectionState.value = DiscordConnectionState.Disconnected
                        }
                    }
                }
            }
        }
    }

    init {
        DiscordLogger.install(appContext)
        registerNetworkCallback()
    }

    fun connect(token: String, showSuccessToast: Boolean) {
        if (released.get()) return
        val cleanToken = DiscordProfileManager.sanitizeToken(token)
        desiredToken = cleanToken
        desiredRpcEnabled = true
        manuallyDisconnected = false
        isAuthRejected = false

        DiscordLogger.logRpcAttempt(cleanToken, "connect(showSuccessToast=$showSuccessToast)")

        if (!DiscordProfileManager.isHeaderSafeToken(cleanToken)) {
            val msg = if (cleanToken.isEmpty()) {
                "Discord RPC unavailable: Add your Discord token in Settings or Sign In"
            } else {
                "Discord token invalid: Remove spaces or unsupported characters"
            }
            isAuthRejected = cleanToken.isNotEmpty()
            DiscordLogger.logRpcState("Error", "Validation failed before connect: $msg (isAuthError=true)")
            _connectionState.value = DiscordConnectionState.Error(msg, isAuthError = true)
            showToast(msg)
            return
        }

        if (!isNetworkAvailable()) {
            val msg = "Discord RPC waiting for internet connection"
            DiscordLogger.logRpcState("Error", "$msg (no active internet capability)")
            _connectionState.value = DiscordConnectionState.Error(msg, isAuthError = false)
            showToast(msg)
            return
        }

        scope.launch {
            rpcMutex.withLock {
                connectAndPushLocked(
                    token = cleanToken,
                    payload = _currentPresence.value,
                    showSuccessToast = showSuccessToast
                )
            }
        }
    }

    fun disconnect(clearPresence: Boolean) {
        manuallyDisconnected = true
        if (clearPresence) {
            _currentPresence.value = null
        }
        DiscordLogger.logRpcState("Disconnected", "Manual disconnect requested (clearPresence=$clearPresence)")
        if (released.get()) return
        scope.launch {
            rpcMutex.withLock {
                closeSocketLocked()
                _connectionState.value = DiscordConnectionState.Disconnected
            }
        }
    }

    fun updatePresence(
        payload: DiscordPresencePayload?,
        rpcEnabled: Boolean,
        token: String
    ) {
        if (released.get()) return
        val cleanToken = DiscordProfileManager.sanitizeToken(token)
        if (cleanToken != desiredToken) {
            isAuthRejected = false
        }
        desiredRpcEnabled = rpcEnabled
        desiredToken = cleanToken
        _currentPresence.value = payload

        if (!rpcEnabled) {
            scope.launch {
                rpcMutex.withLock {
                    if (_connectionState.value !is DiscordConnectionState.Disconnected) {
                        DiscordLogger.logRpcState("Disconnected", "Discord RPC toggled off")
                    }
                    closeSocketLocked()
                    _connectionState.value = DiscordConnectionState.Disconnected
                }
            }
            return
        }

        // Preserve any non-null payload activation unless user explicitly keeps RPC offline.
        if (payload != null) {
            manuallyDisconnected = false
        }

        if (!DiscordProfileManager.isHeaderSafeToken(cleanToken)) {
            if (payload != null && (payload.isRecording || payload.detectedAppName != null || payload.isLarkPlayerRunning)) {
                val msg = if (cleanToken.isEmpty()) {
                    "Discord RPC skipped: Sign in or add your Discord token"
                } else {
                    "Discord token invalid: Remove spaces or unsupported characters"
                }
                DiscordLogger.logRpcState("Error", "UpdatePresence validation failed: $msg (token=${DiscordLogger.maskToken(cleanToken)})")
                _connectionState.value = DiscordConnectionState.Error(msg, isAuthError = true)
                showRateLimitedToast(msg)
            } else {
                scope.launch {
                    rpcMutex.withLock {
                        closeSocketLocked()
                        _connectionState.value = DiscordConnectionState.Disconnected
                    }
                }
            }
            return
        }

        if (isAuthRejected) {
            return
        }

        if (!isNetworkAvailable()) {
            val msg = "Discord RPC waiting for internet connection"
            DiscordLogger.logRpcState("Error", "$msg during updatePresence")
            _connectionState.value = DiscordConnectionState.Error(msg, isAuthError = false)
            showRateLimitedToast(msg)
            return
        }

        scope.launch {
            rpcMutex.withLock {
                if (payload == null) {
                    if (_connectionState.value !is DiscordConnectionState.Disconnected) {
                        DiscordLogger.logRpcState("Disconnected", "Presence payload cleared")
                    }
                    closeSocketLocked()
                    _connectionState.value = DiscordConnectionState.Disconnected
                } else {
                    DiscordLogger.logRpcAttempt(
                        cleanToken,
                        "updatePresence(game=${payload.effectiveGameTitle()}, recording=${payload.isRecording})"
                    )
                    connectAndPushLocked(
                        token = cleanToken,
                        payload = payload,
                        showSuccessToast = false
                    )
                }
            }
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        unregisterNetworkCallback()
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { kizzyRpc?.closeRPC() }
        kizzyRpc = null
        activeToken = ""
        if (_connectionState.value !is DiscordConnectionState.Disconnected) {
            DiscordLogger.logRpcState("Disconnected", "DiscordRpcClient released")
        }
        _connectionState.value = DiscordConnectionState.Disconnected
        scope.cancel()
    }

    private fun handleNetworkRestored() {
        if (released.get()) return
        val tokenSnapshot = desiredToken
        val rpcEnabled = desiredRpcEnabled
        if (!rpcEnabled || tokenSnapshot.isBlank() || manuallyDisconnected || isAuthRejected) {
            return
        }
        val state = _connectionState.value
        val shouldReconnect = state is DiscordConnectionState.Error ||
            (state is DiscordConnectionState.Disconnected && _currentPresence.value != null)
        if (!shouldReconnect) return

        scope.launch {
            rpcMutex.withLock {
                if (released.get() || !desiredRpcEnabled || manuallyDisconnected || isAuthRejected) {
                    return@withLock
                }
                if (!isNetworkAvailable()) return@withLock
                val latestToken = desiredToken
                if (latestToken.isBlank()) return@withLock
                connectAndPushLocked(
                    token = latestToken,
                    payload = _currentPresence.value,
                    showSuccessToast = false
                )
            }
        }
    }

    private fun connectAndPushLocked(
        token: String,
        payload: DiscordPresencePayload?,
        showSuccessToast: Boolean
    ) {
        if (released.get()) return
        try {
            if (kizzyRpc == null || activeToken != token) {
                runCatching { kizzyRpc?.closeRPC() }
                DiscordLogger.logRpcState(
                    "Connecting",
                    "Opening KizzyRPC session (token=${DiscordLogger.maskToken(token)}, appId=$CLOTCH_DISCORD_APP_ID)"
                )
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

            isAuthRejected = false
            val statusSummary = effectivePayload.formatStatusText()
            DiscordLogger.logRpcState(
                "Connected",
                "KizzyRPC active | activity=${effectivePayload.activityName()} | status=$statusSummary"
            )
            _connectionState.value = DiscordConnectionState.Connected(
                username = "Discord RPC Active",
                activeStatusText = statusSummary
            )
            if (showSuccessToast) {
                showToast("Kizzy Discord RPC connected")
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            CrashLogger.logError("DiscordRpcClient", "KizzyRPC connection or activity push failed", t)
            closeSocketLocked()
            val detail = t.localizedMessage?.takeIf { it.isNotBlank() }
                ?: t.cause?.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "Kizzy RPC failure"
            val authFailure = isLikelyAuthError(detail) ||
                isLikelyAuthError(t.cause?.message.orEmpty())
            if (authFailure) {
                isAuthRejected = true
            }
            val msg = if (authFailure) {
                "Discord token rejected: Please sign in again"
            } else {
                "Discord RPC error: $detail"
            }
            DiscordLogger.logRpcState(
                "Error",
                "KizzyRPC failed (authFailure=$authFailure, token=${DiscordLogger.maskToken(token)}): $msg",
                t
            )
            _connectionState.value = DiscordConnectionState.Error(msg, isAuthError = authFailure)
            showRateLimitedToast(msg)
        }
    }

    private fun closeSocketLocked() {
        runCatching { kizzyRpc?.closeRPC() }
        kizzyRpc = null
        activeToken = ""
    }

    private fun isLikelyAuthError(detail: String): Boolean {
        val lower = detail.lowercase()
        return lower.contains("401") ||
            lower.contains("4004") ||
            lower.contains("unauthorized") ||
            lower.contains("invalid token") ||
            lower.contains("authentication")
    }

    private fun registerNetworkCallback() {
        synchronized(networkLock) {
            if (networkCallbackRegistered || released.get()) return
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            connectivityManager = cm
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    cm.registerDefaultNetworkCallback(networkCallback)
                } else {
                    val request = NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                    cm.registerNetworkCallback(request, networkCallback)
                }
                networkCallbackRegistered = true
            }
        }
    }

    private fun unregisterNetworkCallback() {
        synchronized(networkLock) {
            if (!networkCallbackRegistered) return
            runCatching {
                connectivityManager?.unregisterNetworkCallback(networkCallback)
            }
            networkCallbackRegistered = false
            connectivityManager = null
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)
    }

    private fun showRateLimitedToast(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastErrorToastAtMs < ERROR_TOAST_COOLDOWN_MS) return
        lastErrorToastAtMs = now
        showToast(message)
    }

    fun showToast(message: String) {
        if (released.get()) return
        mainHandler.post {
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }
}

