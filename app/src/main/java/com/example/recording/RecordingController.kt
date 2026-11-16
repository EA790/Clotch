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
import com.example.discord.DiscordConnectionState
import com.example.discord.DiscordPresencePayload
import com.example.discord.DiscordRpcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RecordingController(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "clotch_recording_channel"
        const val NOTIFICATION_ID = 1001
        const val IDLE_NOTIFICATION_ID = 1002
        const val ACTION_QUICK_START = "com.example.action.QUICK_START"
        const val ACTION_DISMISS_HUD = "com.example.action.DISMISS_HUD"
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

    val discordConnectionState: StateFlow<DiscordConnectionState> =
        discordRpcClient.connectionState
    val discordPresence: StateFlow<DiscordPresencePayload?> =
        discordRpcClient.currentPresence

    private var countdownJob: Job? = null

    init {
        ensureNotificationChannel()
        refreshClips()
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
            .setContentTitle("Clotch Ready • ${currentSettings.resolution.shortLabel} ${currentSettings.fps.fps}FPS")
            .setContentText("Tap Start to capture ${currentSettings.gameName} (${currentSettings.bitrate.label})")
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

        val currentSettings = settings.value
        val payload = DiscordPresencePayload(
            isRecording = true,
            isPaused = false,
            gameName = currentSettings.gameName,
            fps = fps,
            resolutionLabel = "${minOf(width, height)}p",
            bitrateMbps = bitrateMbps,
            startedAtEpochMs = System.currentTimeMillis()
        )
        discordRpcClient.updatePresence(
            payload = payload,
            rpcEnabled = currentSettings.discordRpcEnabled,
            token = currentSettings.discordToken
        )
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
            val currentSettings = settings.value
            val payload = DiscordPresencePayload(
                isRecording = true,
                isPaused = true,
                gameName = currentSettings.gameName,
                fps = current.activeFps,
                resolutionLabel = "${minOf(current.activeWidth, current.activeHeight)}p",
                bitrateMbps = current.activeBitrateMbps,
                startedAtEpochMs = 0L
            )
            discordRpcClient.updatePresence(
                payload = payload,
                rpcEnabled = currentSettings.discordRpcEnabled,
                token = currentSettings.discordToken
            )
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
            val currentSettings = settings.value
            val payload = DiscordPresencePayload(
                isRecording = true,
                isPaused = false,
                gameName = currentSettings.gameName,
                fps = current.activeFps,
                resolutionLabel = "${minOf(current.activeWidth, current.activeHeight)}p",
                bitrateMbps = current.activeBitrateMbps,
                startedAtEpochMs = System.currentTimeMillis() - current.elapsedMs
            )
            discordRpcClient.updatePresence(
                payload = payload,
                rpcEnabled = currentSettings.discordRpcEnabled,
                token = currentSettings.discordToken
            )
        }
    }

    fun onServiceRecordingStopped(savedFileName: String?) {
        _recorderState.value = RecorderState.Idle
        val currentSettings = settings.value
        discordRpcClient.updatePresence(
            payload = null,
            rpcEnabled = currentSettings.discordRpcEnabled,
            token = currentSettings.discordToken
        )
        refreshClips()
        syncIdleNotification(currentSettings)
        if (!savedFileName.isNullOrBlank()) {
            showToast("Saved clip: $savedFileName")
        }
    }

    fun onServiceError(message: String) {
        _recorderState.value = RecorderState.Error(message)
        val currentSettings = settings.value
        discordRpcClient.updatePresence(
            payload = null,
            rpcEnabled = currentSettings.discordRpcEnabled,
            token = currentSettings.discordToken
        )
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
            _clips.value = clipsRepository.loadRecordedClips()
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
        discordRpcClient.connect(token, showSuccessToast = true)
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
}
