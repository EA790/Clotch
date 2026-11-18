package com.example.recording

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.ClipsRepository
import com.example.data.ClotchSettings
import com.example.data.RecordedClip
import com.example.data.SettingsRepository
import com.example.discord.AppDetectionMonitor
import com.example.discord.DetectedAppsResult
import com.example.discord.DiscordConnectionState
import com.example.discord.DiscordPresencePayload
import com.example.discord.DiscordRpcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingController(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "clotch_recording_channel"
        const val NOTIFICATION_ID = 1001
        const val IDLE_NOTIFICATION_ID = 1002
        const val ACTION_QUICK_START = "com.example.action.QUICK_START"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    val settingsRepository = SettingsRepository(context)
    val clipsRepository = ClipsRepository(context)
    val discordRpcClient = DiscordRpcClient(context)

    val settings: StateFlow<ClotchSettings> = settingsRepository.settingsFlow
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = ClotchSettings()
        )

    private val _recorderState = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val recorderState: StateFlow<RecorderState> = _recorderState.asStateFlow()

    private val _clips = MutableStateFlow<List<RecordedClip>>(emptyList())
    val clips: StateFlow<List<RecordedClip>> = _clips.asStateFlow()

    private val _isLoadingClips = MutableStateFlow(true)
    val isLoadingClips: StateFlow<Boolean> = _isLoadingClips.asStateFlow()

    private val _detectedApps = MutableStateFlow(DetectedAppsResult())
    val detectedApps: StateFlow<DetectedAppsResult> = _detectedApps.asStateFlow()

    private val _hasUsageStatsPermission =
        MutableStateFlow(AppDetectionMonitor.hasUsageStatsPermission(context))
    val hasUsageStatsPermission: StateFlow<Boolean> = _hasUsageStatsPermission.asStateFlow()

    val discordConnectionState: StateFlow<DiscordConnectionState> =
        discordRpcClient.connectionState
    val discordPresence: StateFlow<DiscordPresencePayload?> =
        discordRpcClient.currentPresence

    private var countdownJob: Job? = null
    private var appDetectionJob: Job? = null

    init {
        ensureNotificationChannel()
        refreshClips()
        startAppDetectionLoop()
    }

    fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_recording_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notification_channel_recording_desc)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun refreshUsageStatsPermission(): Boolean {
        val granted = AppDetectionMonitor.hasUsageStatsPermission(context)
        _hasUsageStatsPermission.value = granted
        return granted
    }

    fun scanAppsNow(showFeedbackToast: Boolean = false) {
        scope.launch {
            val hasPerm = refreshUsageStatsPermission()
            if (!hasPerm) {
                if (showFeedbackToast) {
                    showToast("Grant Usage Access permission to detect games & Lark Player")
                }
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                AppDetectionMonitor.scanRecentApps(context)
            }
            val prev = _detectedApps.value
            _detectedApps.value = result
            val changed = prev.activeGame?.packageName != result.activeGame?.packageName ||
                prev.isLarkPlayerRunning != result.isLarkPlayerRunning

            if (changed || showFeedbackToast) {
                pushCompositePresence()
            }
            if (showFeedbackToast) {
                val msg = when {
                    result.activeGame != null && result.isLarkPlayerRunning ->
                        "Detected ${result.activeGame.displayName} + Lark Player"
                    result.activeGame != null ->
                        "Detected ${result.activeGame.displayName}"
                    result.isLarkPlayerRunning ->
                        "Detected Lark Player in background"
                    else ->
                        "No supported game or Lark Player detected in recents"
                }
                showToast(msg)
            }
        }
    }

    private fun startAppDetectionLoop() {
        appDetectionJob?.cancel()
        appDetectionJob = scope.launch {
            while (isActive) {
                if (refreshUsageStatsPermission()) {
                    val result = withContext(Dispatchers.IO) {
                        AppDetectionMonitor.scanRecentApps(context)
                    }
                    val prev = _detectedApps.value
                    _detectedApps.value = result
                    val changed = prev.activeGame?.packageName != result.activeGame?.packageName ||
                        prev.isLarkPlayerRunning != result.isLarkPlayerRunning
                    if (changed) {
                        pushCompositePresence()
                    }
                }
                delay(5_000L)
            }
        }
    }

    fun pushCompositePresence() {
        val currentSettings = settings.value
        val detected = _detectedApps.value
        val recState = _recorderState.value

        val isRec = recState is RecorderState.Recording
        val isPaused = recState is RecorderState.Paused

        if (!isRec && !isPaused && !detected.hasAnyDetectedApp) {
            // Only clear if nothing is detected and not recording
            if (discordPresence.value != null) {
                discordRpcClient.updatePresence(
                    payload = null,
                    rpcEnabled = currentSettings.discordRpcEnabled,
                    token = currentSettings.discordToken
                )
            }
            return
        }

        val activeFps = when (recState) {
            is RecorderState.Recording -> recState.activeFps
            is RecorderState.Paused -> recState.activeFps
            else -> currentSettings.fps.fps
        }
        val activeRes = when (recState) {
            is RecorderState.Recording -> "${minOf(recState.activeWidth, recState.activeHeight)}p"
            is RecorderState.Paused -> "${minOf(recState.activeWidth, recState.activeHeight)}p"
            else -> currentSettings.resolution.shortLabel
        }
        val activeBitrate = when (recState) {
            is RecorderState.Recording -> recState.activeBitrateMbps
            is RecorderState.Paused -> recState.activeBitrateMbps
            else -> currentSettings.bitrate.mbps
        }

        val largeImage = when {
            detected.activeGame != null -> detected.activeGame.catImageUrl
            detected.isLarkPlayerRunning -> DetectedAppsResult.LARK_CAT_IMAGE_URL
            else -> "https://example.com/cat_clotch.png"
        }
        val smallImage = if (detected.activeGame != null && detected.isLarkPlayerRunning) {
            DetectedAppsResult.LARK_CAT_IMAGE_URL
        } else {
            null
        }

        val payload = DiscordPresencePayload(
            isRecording = isRec || isPaused,
            isPaused = isPaused,
            gameName = currentSettings.gameName,
            fps = activeFps,
            resolutionLabel = activeRes,
            bitrateMbps = activeBitrate,
            startedAtEpochMs = if (isRec) System.currentTimeMillis() else 0L,
            detectedAppName = detected.activeGame?.displayName,
            detectedAppPackage = detected.activeGame?.packageName,
            isLarkPlayerRunning = detected.isLarkPlayerRunning,
            largeImageUrl = largeImage,
            smallImageUrl = smallImage
        )

        discordRpcClient.updatePresence(
            payload = payload,
            rpcEnabled = currentSettings.discordRpcEnabled,
            token = currentSettings.discordToken
        )
    }

    fun syncIdleNotification(currentSettings: ClotchSettings = settings.value) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val isActive = _recorderState.value is RecorderState.Recording ||
            _recorderState.value is RecorderState.Paused

        if (!currentSettings.showQuickNotification || isActive) {
            manager.cancel(IDLE_NOTIFICATION_ID)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        ensureNotificationChannel()

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            context,
            200,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val quickStartIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_QUICK_START
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val quickStartPending = PendingIntent.getActivity(
            context,
            201,
            quickStartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(
                "Clotch Ready • ${currentSettings.resolution.shortLabel} ${currentSettings.fps.fps}FPS (${currentSettings.videoFormat.label})"
            )
            .setContentText("Tap Start to capture ${currentSettings.gameName}")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openPending)
            .addAction(
                android.R.drawable.ic_media_play,
                context.getString(R.string.action_start_recording),
                quickStartPending
            )
            .build()

        manager.notify(IDLE_NOTIFICATION_ID, notification)
    }

    fun cancelIdleNotification() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.cancel(IDLE_NOTIFICATION_ID)
    }

    fun startWithOptionalCountdown(onReadyToLaunchProjection: () -> Unit) {
        val seconds = settings.value.countdown.seconds
        if (seconds <= 0) {
            onReadyToLaunchProjection()
            return
        }

        countdownJob?.cancel()
        countdownJob = scope.launch {
            for (remaining in seconds downTo 1) {
                _recorderState.value = RecorderState.Countdown(remaining)
                delay(1000L)
            }
            _recorderState.value = RecorderState.Idle
            onReadyToLaunchProjection()
        }
    }

    fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        if (_recorderState.value is RecorderState.Countdown) {
            _recorderState.value = RecorderState.Idle
        }
    }

    fun startScreenRecordService(resultCode: Int, data: Intent) {
        cancelIdleNotification()
        val intent = Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecordService.EXTRA_PROJECTION_DATA, data)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopRecording() {
        val intent = Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun pauseRecording() {
        val intent = Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_PAUSE
        }
        context.startService(intent)
    }

    fun resumeRecording() {
        val intent = Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_RESUME
        }
        context.startService(intent)
    }

    fun onServiceRecordingStarted(
        startedAtMs: Long,
        fileName: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrateMbps: Int
    ) {
        _recorderState.value = RecorderState.Recording(
            startedAtMs = startedAtMs,
            elapsedMs = 0L,
            fileName = fileName,
            activeWidth = width,
            activeHeight = height,
            activeFps = fps,
            activeBitrateMbps = bitrateMbps
        )
        pushCompositePresence()
    }

    fun onServiceRecordingTick(elapsedMs: Long) {
        val current = _recorderState.value
        if (current is RecorderState.Recording) {
            _recorderState.value = current.copy(elapsedMs = elapsedMs)
        }
    }

    fun onServiceRecordingPaused(elapsedMs: Long) {
        val current = _recorderState.value
        if (current is RecorderState.Recording) {
            _recorderState.value = RecorderState.Paused(
                elapsedMs = elapsedMs,
                fileName = current.fileName,
                activeWidth = current.activeWidth,
                activeHeight = current.activeHeight,
                activeFps = current.activeFps,
                activeBitrateMbps = current.activeBitrateMbps
            )
            pushCompositePresence()
        }
    }

    fun onServiceRecordingResumed(startedAtMs: Long) {
        val current = _recorderState.value
        if (current is RecorderState.Paused) {
            _recorderState.value = RecorderState.Recording(
                startedAtMs = startedAtMs,
                elapsedMs = current.elapsedMs,
                fileName = current.fileName,
                activeWidth = current.activeWidth,
                activeHeight = current.activeHeight,
                activeFps = current.activeFps,
                activeBitrateMbps = current.activeBitrateMbps
            )
            pushCompositePresence()
        }
    }

    fun onServiceRecordingStopped(savedFileName: String?, audioFileName: String? = null) {
        _recorderState.value = RecorderState.Idle
        pushCompositePresence()
        refreshClips()
        syncIdleNotification(settings.value)
        if (!savedFileName.isNullOrBlank()) {
            val msg = if (!audioFileName.isNullOrBlank()) {
                "Saved $savedFileName + audio $audioFileName"
            } else {
                "Saved Movies/Clotch/$savedFileName"
            }
            showToast(msg)
        }
    }

    fun onServiceError(message: String) {
        _recorderState.value = RecorderState.Error(message)
        pushCompositePresence()
        showToast(message)
        scope.launch {
            delay(3000L)
            if (_recorderState.value is RecorderState.Error) {
                _recorderState.value = RecorderState.Idle
                syncIdleNotification()
            }
        }
    }

    fun refreshClips() {
        scope.launch {
            _isLoadingClips.value = true
            try {
                _clips.value = clipsRepository.loadRecordedClips()
            } finally {
                _isLoadingClips.value = false
            }
        }
    }

    fun deleteClip(clip: RecordedClip) {
        scope.launch {
            val deleted = clipsRepository.deleteClip(clip)
            if (deleted) {
                refreshClips()
                showToast("Deleted ${clip.displayName}")
            } else {
                showToast("Could not delete clip")
            }
        }
    }

    fun connectDiscordRpc(tokenOverride: String? = null) {
        val token = tokenOverride ?: settings.value.discordToken
        discordRpcClient.connect(token = token, showSuccessToast = true)
        pushCompositePresence()
    }

    fun disconnectDiscordRpc() {
        discordRpcClient.disconnect(clearPresence = true)
        showToast("Discord RPC disconnected")
    }

    fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    fun release() {
        countdownJob?.cancel()
        countdownJob = null
        appDetectionJob?.cancel()
        appDetectionJob = null
        mainHandler.removeCallbacksAndMessages(null)
        discordRpcClient.release()
        scope.cancel()
    }
}
