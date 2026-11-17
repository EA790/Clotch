package com.example.discord

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.my.kizzyrpc.KizzyRPC
import com.my.kizzyrpc.model.Activity
import com.my.kizzyrpc.model.Assets
import com.my.kizzyrpc.model.Timestamps
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DiscordRpcClient(private val context: Context) {

    companion object {
        const val CLOTCH_DISCORD_APP_ID = "1567535923669770360"
        private const val PREFS_NAME = "clotch_discord_prefs"
        private const val KEY_DISCORD_ENABLED = "discord_enabled"
        private const val MIN_PUSH_COOLDOWN_MS = 5_000L
        private const val KEEPALIVE_INTERVAL_MS = 60_000L
        private const val RECONNECT_COOLDOWN_MS = 5_000L
        private const val REASSERT_DELAY_MS = 5_000L
        private const val WORKER_TICK_MS = 250L
        private const val ERROR_TOAST_COOLDOWN_MS = 4_000L

        private val DISCORD_RPC_CONNECTED = AtomicBoolean(false)
        private val DISCORD_RPC_ENABLED = AtomicBoolean(true)

        fun isDiscordRpcActive(): Boolean = DISCORD_RPC_CONNECTED.get()

        fun isDiscordRpcEnabled(): Boolean = DISCORD_RPC_ENABLED.get()

        fun setDiscordRpcEnabled(enabled: Boolean, context: Context? = null) {
            DISCORD_RPC_ENABLED.set(enabled)
            if (context != null) {
                persistDiscordRpcEnabled(context, enabled)
            }
        }

        fun persistDiscordRpcEnabled(context: Context, enabled: Boolean) {
            runCatching {
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_DISCORD_ENABLED, enabled)
                    .apply()
            }
        }
    }

    private data class ActivitySignature(
        val appId: String,
        val name: String,
        val details: String,
        val state: String,
        val type: Int,
        val startEpochMs: Long,
        val largeImage: String?,
        val smallImage: String?,
        val largeText: String?,
        val smallText: String?
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rpcMutex = Mutex()

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
    private var authFailedToken: String = ""

    @Volatile
    private var rpcEnabled: Boolean = loadInitialEnabledState()

    @Volatile
    private var allowIdlePresence: Boolean = false

    @Volatile
    private var pendingSuccessToast: Boolean = false

    @Volatile
    private var dirty: Boolean = false

    @Volatile
    private var reassertDeadlineElapsedMs: Long = 0L

    @Volatile
    private var lastPushElapsedMs: Long = 0L

    @Volatile
    private var lastReconnectElapsedMs: Long = 0L

    @Volatile
    private var lastErrorToastAtMs: Long = 0L

    @Volatile
    private var warnedWaitingForConnection: Boolean = false

    @Volatile
    private var lastSentSignature: ActivitySignature? = null

    @Volatile
    private var activeSessionKey: String = ""

    @Volatile
    private var sessionStartedEpochMs: Long = System.currentTimeMillis()

    @Volatile
    private var connectedSinceEpochMs: Long = System.currentTimeMillis()

    private var workerJob: Job? = null
    private var networkCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (rpcEnabled && desiredToken.isNotEmpty()) {
                dirty = true
                lastReconnectElapsedMs = 0L
            }
        }

        override fun onLost(network: Network) {
            if (!isNetworkAvailable()) {
                scope.launch {
                    rpcMutex.withLock {
                        closeSocketLocked()
                        if (rpcEnabled && (_currentPresence.value != null || allowIdlePresence)) {
                            _connectionState.value =
                                DiscordConnectionState.Error("Discord RPC waiting for network")
                        }
                    }
                }
            }
        }
    }

    init {
        DISCORD_RPC_ENABLED.set(rpcEnabled)
        registerNetworkMonitor()
        startWorkerLoop()
    }

    fun isRpcActive(): Boolean = isDiscordRpcActive()

    fun isRpcEnabled(): Boolean = isDiscordRpcEnabled()

    fun setRpcEnabled(enabled: Boolean) {
        rpcEnabled = enabled
        setDiscordRpcEnabled(enabled, appContext)
        if (!enabled) {
            allowIdlePresence = false
            scope.launch {
                rpcMutex.withLock {
                    closeSocketLocked()
                    lastSentSignature = null
                    dirty = false
                    reassertDeadlineElapsedMs = 0L
                    _connectionState.value = DiscordConnectionState.Disconnected
                }
            }
        } else {
            dirty = true
            lastSentSignature = null
            lastReconnectElapsedMs = 0L
        }
    }

    fun connect(token: String, showSuccessToast: Boolean = false) {
        val cleanToken = sanitizeToken(token)
        if (!isValidTokenFormat(cleanToken)) {
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

        desiredToken = cleanToken
        authFailedToken = ""
        rpcEnabled = true
        setDiscordRpcEnabled(true, appContext)
        allowIdlePresence = true
        pendingSuccessToast = showSuccessToast
        dirty = true
        lastSentSignature = null
        lastPushElapsedMs = 0L
        lastReconnectElapsedMs = 0L
        reassertDeadlineElapsedMs = SystemClock.elapsedRealtime() + REASSERT_DELAY_MS

        scope.launch {
            rpcMutex.withLock {
                tickLocked(forceImmediate = true)
            }
        }
    }

    fun disconnect(clearPresence: Boolean = true) {
        allowIdlePresence = false
        pendingSuccessToast = false
        reassertDeadlineElapsedMs = 0L
        dirty = false
        if (clearPresence) {
            _currentPresence.value = null
            activeSessionKey = ""
        }
        scope.launch {
            rpcMutex.withLock {
                closeSocketLocked()
                activeToken = ""
                lastSentSignature = null
                _connectionState.value = DiscordConnectionState.Disconnected
            }
        }
    }

    fun updatePresence(
        payload: DiscordPresencePayload?,
        rpcEnabled: Boolean,
        token: String
    ) {
        val previousPayload = _currentPresence.value
        _currentPresence.value = payload
        this.rpcEnabled = rpcEnabled
        setDiscordRpcEnabled(rpcEnabled, appContext)

        if (!rpcEnabled) {
            allowIdlePresence = false
            reassertDeadlineElapsedMs = 0L
            dirty = false
            scope.launch {
                rpcMutex.withLock {
                    closeSocketLocked()
                    lastSentSignature = null
                    _connectionState.value = DiscordConnectionState.Disconnected
                }
            }
            return
        }

        val cleanToken = sanitizeToken(token)
        if (cleanToken.isNotEmpty() && cleanToken != desiredToken) {
            desiredToken = cleanToken
            if (authFailedToken != cleanToken) {
                authFailedToken = ""
            }
            dirty = true
            lastSentSignature = null
            lastReconnectElapsedMs = 0L
        } else if (cleanToken.isEmpty()) {
            desiredToken = ""
        }

        if (payload == null) {
            allowIdlePresence = false
            activeSessionKey = ""
            reassertDeadlineElapsedMs = 0L
            dirty = false
            scope.launch {
                rpcMutex.withLock {
                    closeSocketLocked()
                    lastSentSignature = null
                    _connectionState.value = DiscordConnectionState.Disconnected
                }
            }
            return
        }

        val newSessionKey = computeSessionKey(payload)
        val sessionChanged = newSessionKey != activeSessionKey
        if (sessionChanged) {
            activeSessionKey = newSessionKey
            sessionStartedEpochMs = if (payload.startedAtEpochMs > 0L) {
                payload.startedAtEpochMs
            } else {
                System.currentTimeMillis()
            }
            reassertDeadlineElapsedMs = SystemClock.elapsedRealtime() + REASSERT_DELAY_MS
        } else if (payload.startedAtEpochMs > 0L && payload.startedAtEpochMs != sessionStartedEpochMs) {
            sessionStartedEpochMs = payload.startedAtEpochMs
        }

        if (previousPayload != payload || sessionChanged || kizzyRpc == null) {
            dirty = true
        }

        if (!isValidTokenFormat(cleanToken)) {
            if (isMeaningfulPayload(payload)) {
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
            rpcMutex.withLock {
                tickLocked(forceImmediate = sessionChanged || kizzyRpc == null)
            }
        }
    }

    fun shutdown() {
        unregisterNetworkMonitor()
        workerJob?.cancel()
        workerJob = null
        runCatching { kizzyRpc?.closeRPC() }
        kizzyRpc = null
        DISCORD_RPC_CONNECTED.set(false)
        _connectionState.value = DiscordConnectionState.Disconnected
        scope.cancel()
    }

    private fun startWorkerLoop() {
        workerJob?.cancel()
        workerJob = scope.launch {
            while (isActive) {
                rpcMutex.withLock {
                    runCatching { tickLocked(forceImmediate = false) }
                }
                delay(WORKER_TICK_MS)
            }
        }
    }

    private fun tickLocked(forceImmediate: Boolean) {
        if (!rpcEnabled) {
            if (kizzyRpc != null || DISCORD_RPC_CONNECTED.get()) {
                closeSocketLocked()
                lastSentSignature = null
                _connectionState.value = DiscordConnectionState.Disconnected
            }
            return
        }

        val payload = _currentPresence.value
        if (payload == null && !allowIdlePresence) {
            if (kizzyRpc != null || DISCORD_RPC_CONNECTED.get()) {
                closeSocketLocked()
                lastSentSignature = null
                _connectionState.value = DiscordConnectionState.Disconnected
            }
            return
        }

        val token = desiredToken
        if (!isValidTokenFormat(token)) {
            closeSocketLocked()
            return
        }

        if (token == authFailedToken && !forceImmediate) {
            return
        }

        if (!isNetworkAvailable()) {
            if (DISCORD_RPC_CONNECTED.get() || kizzyRpc != null) {
                closeSocketLocked()
                _connectionState.value = DiscordConnectionState.Error("Discord RPC error: Network unavailable")
            }
            return
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        if (reassertDeadlineElapsedMs in 1..nowElapsed) {
            reassertDeadlineElapsedMs = 0L
            dirty = true
            lastSentSignature = null
        }

        if (kizzyRpc == null || activeToken != token) {
            val canAttemptReconnect = forceImmediate ||
                lastReconnectElapsedMs == 0L ||
                (nowElapsed - lastReconnectElapsedMs >= RECONNECT_COOLDOWN_MS)

            if (!canAttemptReconnect) {
                return
            }

            lastReconnectElapsedMs = nowElapsed
            closeSocketLocked()
            _connectionState.value = DiscordConnectionState.Connecting

            try {
                kizzyRpc = KizzyRPC(token)
                activeToken = token
                connectedSinceEpochMs = System.currentTimeMillis()
                dirty = true
                lastSentSignature = null
                warnedWaitingForConnection = false
            } catch (e: Exception) {
                handleRpcExceptionLocked(e)
                return
            }
        }

        val rpc = kizzyRpc ?: return
        val cooldownElapsed = forceImmediate ||
            lastPushElapsedMs == 0L ||
            (nowElapsed - lastPushElapsedMs >= MIN_PUSH_COOLDOWN_MS)
        val keepaliveDue = lastPushElapsedMs == 0L ||
            (nowElapsed - lastPushElapsedMs >= KEEPALIVE_INTERVAL_MS)

        if (!(dirty && cooldownElapsed) && !keepaliveDue) {
            return
        }

        val effectivePayload = payload ?: buildIdlePayload()
        val (activity, signature) = buildActivityAndSignature(effectivePayload)

        if (signature != lastSentSignature || keepaliveDue || forceImmediate) {
            try {
                pushActivityWithFallback(rpc, activity)
                DISCORD_RPC_CONNECTED.set(true)
                lastSentSignature = signature
                lastPushElapsedMs = SystemClock.elapsedRealtime()
                dirty = false
                authFailedToken = ""
                warnedWaitingForConnection = false

                _connectionState.value = DiscordConnectionState.Connected(
                    username = "Discord RPC Active",
                    activeStatusText = signature.details
                )

                if (pendingSuccessToast) {
                    pendingSuccessToast = false
                    showToast("Kizzy Discord RPC connected")
                }
            } catch (e: Exception) {
                pendingSuccessToast = false
                handleRpcExceptionLocked(e)
            }
        } else {
            dirty = false
        }
    }

    private fun pushActivityWithFallback(rpc: KizzyRPC, activity: Activity) {
        try {
            rpc.setActivity(
                activity = activity,
                status = "online",
                since = connectedSinceEpochMs
            )
        } catch (firstError: Exception) {
            if (activity.assets != null) {
                val fallbackActivity = Activity(
                    applicationId = activity.applicationId,
                    name = activity.name,
                    details = activity.details,
                    state = activity.state,
                    type = activity.type,
                    timestamps = activity.timestamps,
                    assets = null
                )
                rpc.setActivity(
                    activity = fallbackActivity,
                    status = "online",
                    since = connectedSinceEpochMs
                )
            } else {
                throw firstError
            }
        }
    }

    private fun buildActivityAndSignature(
        payload: DiscordPresencePayload
    ): Pair<Activity, ActivitySignature> {
        val rawName = runCatching { payload.activityName() }.getOrNull().orEmpty().trim()
        val rawTitle = runCatching { payload.effectiveGameTitle() }.getOrNull().orEmpty().trim()
        val rawDetails = runCatching { payload.formatStatusText() }.getOrNull().orEmpty().trim()
        val rawState = runCatching { payload.formatSubDetails() }.getOrNull().orEmpty().trim()
        val rawType = runCatching { payload.activityType() }.getOrDefault(0).coerceIn(0, 5)

        val resolvedName = when {
            rawName.isNotEmpty() -> rawName
            rawTitle.isNotEmpty() -> rawTitle
            payload.gameName.isNotBlank() -> payload.gameName.trim()
            else -> "Clotch"
        }

        val resolvedDetails = when {
            rawDetails.isNotEmpty() -> rawDetails
            payload.isRecording -> "Recording $resolvedName"
            payload.isLarkPlayerRunning -> "Listening on Lark Player"
            !payload.detectedAppName.isNullOrBlank() -> payload.detectedAppName.trim()
            else -> "Idle"
        }

        val resolvedState = when {
            rawState.isNotEmpty() -> rawState
            payload.isRecording -> "Clipping Active"
            payload.isLarkPlayerRunning -> "Background Audio"
            else -> "Ready"
        }

        val resolvedStartMs = when {
            payload.startedAtEpochMs > 0L -> payload.startedAtEpochMs
            sessionStartedEpochMs > 0L -> sessionStartedEpochMs
            else -> System.currentTimeMillis().also { sessionStartedEpochMs = it }
        }

        val cleanLargeImage = sanitizeAssetUrl(payload.largeImageUrl)
        val cleanSmallImage = sanitizeAssetUrl(payload.smallImageUrl)
        val cleanLargeText = (rawTitle.ifEmpty { resolvedName }).takeIf { it.isNotBlank() }
        val cleanSmallText = if (payload.isLarkPlayerRunning) "Lark Player" else "Clotch"

        val assets = if (cleanLargeImage != null || cleanSmallImage != null) {
            Assets(
                largeImage = cleanLargeImage,
                smallImage = cleanSmallImage,
                largeText = cleanLargeText,
                smallText = cleanSmallText
            )
        } else {
            null
        }

        val activity = Activity(
            applicationId = CLOTCH_DISCORD_APP_ID,
            name = resolvedName,
            details = resolvedDetails,
            state = resolvedState,
            type = rawType,
            timestamps = Timestamps(start = resolvedStartMs),
            assets = assets
        )

        val signature = ActivitySignature(
            appId = CLOTCH_DISCORD_APP_ID,
            name = resolvedName,
            details = resolvedDetails,
            state = resolvedState,
            type = rawType,
            startEpochMs = resolvedStartMs,
            largeImage = cleanLargeImage,
            smallImage = cleanSmallImage,
            largeText = cleanLargeText,
            smallText = cleanSmallText
        )

        return activity to signature
    }

    private fun tickLocked(forceImmediate: Boolean) {
        if (!rpcEnabled) {
            if (kizzyRpc != null || DISCORD_RPC_CONNECTED.get()) {
                closeSocketLocked()
                lastSentSignature = null
                _connectionState.value = DiscordConnectionState.Disconnected
            }
            return
        }

        val payload = _currentPresence.value
        if (payload == null && !allowIdlePresence) {
            if (kizzyRpc != null || DISCORD_RPC_CONNECTED.get()) {
                closeSocketLocked()
                lastSentSignature = null
                _connectionState.value = DiscordConnectionState.Disconnected
            }
            return
        }

        val token = desiredToken
        if (!isValidTokenFormat(token)) {
            closeSocketLocked()
            return
        }

        if (token == authFailedToken && !forceImmediate) return

        if (!isNetworkAvailable()) {
            if (DISCORD_RPC_CONNECTED.get() || kizzyRpc != null) {
                closeSocketLocked()
                _connectionState.value = DiscordConnectionState.Error("Discord RPC error: Network unavailable")
            }
            return
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        if (reassertDeadlineElapsedMs in 1..nowElapsed) {
            reassertDeadlineElapsedMs = 0L
            dirty = true
            lastSentSignature = null
        }

        if (kizzyRpc == null || activeToken != token) {
            val canAttemptReconnect = forceImmediate ||
                lastReconnectElapsedMs == 0L ||
                (nowElapsed - lastReconnectElapsedMs >= RECONNECT_COOLDOWN_MS)
            if (!canAttemptReconnect) return

            lastReconnectElapsedMs = nowElapsed
            closeSocketLocked()
            _connectionState.value = DiscordConnectionState.Connecting

            try {
                kizzyRpc = KizzyRPC(token)
                activeToken = token
                connectedSinceEpochMs = System.currentTimeMillis()
                dirty = true
                lastSentSignature = null
                warnedWaitingForConnection = false
            } catch (e: Exception) {
                handleRpcExceptionLocked(e)
                return
            }
        }

        val rpc = kizzyRpc ?: return
        val cooldownElapsed = forceImmediate ||
            lastPushElapsedMs == 0L ||
            (nowElapsed - lastPushElapsedMs >= MIN_PUSH_COOLDOWN_MS)
        val keepaliveDue = lastPushElapsedMs == 0L ||
            (nowElapsed - lastPushElapsedMs >= KEEPALIVE_INTERVAL_MS)

        if (!(dirty && cooldownElapsed) && !keepaliveDue) return

        val effectivePayload = payload ?: buildIdlePayload()
        val (activity, signature) = buildActivityAndSignature(effectivePayload)

        if (signature != lastSentSignature || keepaliveDue || forceImmediate) {
            try {
                pushActivityWithFallback(rpc, activity)
                DISCORD_RPC_CONNECTED.set(true)
                lastSentSignature = signature
                lastPushElapsedMs = SystemClock.elapsedRealtime()
                dirty = false
                authFailedToken = ""
                warnedWaitingForConnection = false
                _connectionState.value = DiscordConnectionState.Connected(
                    username = "Discord RPC Active",
                    activeStatusText = signature.details
                )
                if (pendingSuccessToast) {
                    pendingSuccessToast = false
                    showToast("Kizzy Discord RPC connected")
                }
            } catch (e: Exception) {
                pendingSuccessToast = false
                handleRpcExceptionLocked(e)
            }
        } else {
            dirty = false
        }
    }

    private fun startWorkerLoop() {
        workerJob?.cancel()
        workerJob = scope.launch {
            while (isActive) {
                rpcMutex.withLock {
                    runCatching { tickLocked(forceImmediate = false) }
                }
                delay(WORKER_TICK_MS)
            }
        }
    }

    fun setRpcEnabled(enabled: Boolean) {
        rpcEnabled = enabled
        setDiscordRpcEnabled(enabled, appContext)
        if (!enabled) {
            allowIdlePresence = false
            scope.launch {
                rpcMutex.withLock {
                    closeSocketLocked()
                    lastSentSignature = null
                    dirty = false
                    reassertDeadlineElapsedMs = 0L
                    _connectionState.value = DiscordConnectionState.Disconnected
                }
            }
        } else {
            dirty = true
            lastSentSignature = null
            lastReconnectElapsedMs = 0L
        }
    }

    fun connect(token: String, showSuccessToast: Boolean = false) {
        val cleanToken = sanitizeToken(token)
        if (!isValidTokenFormat(cleanToken)) {
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

        desiredToken = cleanToken
        authFailedToken = ""
        rpcEnabled = true
        setDiscordRpcEnabled(true, appContext)
        allowIdlePresence = true
        pendingSuccessToast = showSuccessToast
        dirty = true
        lastSentSignature = null
        lastPushElapsedMs = 0L
        lastReconnectElapsedMs = 0L
        reassertDeadlineElapsedMs = SystemClock.elapsedRealtime() + REASSERT_DELAY_MS

        scope.launch {
            rpcMutex.withLock {
                tickLocked(forceImmediate = true)
            }
        }
    }

    fun disconnect(clearPresence: Boolean = true) {
        allowIdlePresence = false
        pendingSuccessToast = false
        reassertDeadlineElapsedMs = 0L
        dirty = false
        if (clearPresence) {
            _currentPresence.value = null
            activeSessionKey = ""
        }
        scope.launch {
            rpcMutex.withLock {
                closeSocketLocked()
                activeToken = ""
                lastSentSignature = null
                _connectionState.value = DiscordConnectionState.Disconnected
            }
        }
    }

    fun updatePresence(
        payload: DiscordPresencePayload?,
        rpcEnabled: Boolean,
        token: String
    ) {
        val previousPayload = _currentPresence.value
        _currentPresence.value = payload
        this.rpcEnabled = rpcEnabled
        setDiscordRpcEnabled(rpcEnabled, appContext)

        if (!rpcEnabled) {
            allowIdlePresence = false
            reassertDeadlineElapsedMs = 0L
            dirty = false
            scope.launch {
                rpcMutex.withLock {
                    closeSocketLocked()
                    lastSentSignature = null
                    _connectionState.value = DiscordConnectionState.Disconnected
                }
            }
            return
        }

        val cleanToken = sanitizeToken(token)
        if (cleanToken.isNotEmpty() && cleanToken != desiredToken) {
            desiredToken = cleanToken
            if (authFailedToken != cleanToken) authFailedToken = ""
            dirty = true
            lastSentSignature = null
            lastReconnectElapsedMs = 0L
        } else if (cleanToken.isEmpty()) {
            desiredToken = ""
        }

        if (payload == null) {
            allowIdlePresence = false
            activeSessionKey = ""
            reassertDeadlineElapsedMs = 0L
            dirty = false
            scope.launch {
                rpcMutex.withLock {
                    closeSocketLocked()
                    lastSentSignature = null
                    _connectionState.value = DiscordConnectionState.Disconnected
                }
            }
            return
        }

        val newSessionKey = computeSessionKey(payload)
        val sessionChanged = newSessionKey != activeSessionKey
        if (sessionChanged) {
            activeSessionKey = newSessionKey
            sessionStartedEpochMs = if (payload.startedAtEpochMs > 0L) {
                payload.startedAtEpochMs
            } else {
                System.currentTimeMillis()
            }
            reassertDeadlineElapsedMs = SystemClock.elapsedRealtime() + REASSERT_DELAY_MS
        } else if (payload.startedAtEpochMs > 0L && payload.startedAtEpochMs != sessionStartedEpochMs) {
            sessionStartedEpochMs = payload.startedAtEpochMs
        }

        if (previousPayload != payload || sessionChanged || kizzyRpc == null) {
            dirty = true
        }

        if (!isValidTokenFormat(cleanToken)) {
            if (isMeaningfulPayload(payload)) {
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
            rpcMutex.withLock {
                tickLocked(forceImmediate = sessionChanged || kizzyRpc == null)
            }
        }
    }

    fun shutdown() {
        unregisterNetworkMonitor()
        workerJob?.cancel()
        workerJob = null
        runCatching { kizzyRpc?.closeRPC() }
        kizzyRpc = null
        DISCORD_RPC_CONNECTED.set(false)
        _connectionState.value = DiscordConnectionState.Disconnected
        scope.cancel()
    }
}
