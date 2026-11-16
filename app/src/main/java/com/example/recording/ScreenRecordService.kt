package com.example.recording

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.ClotchApplication
import com.example.MainActivity
import com.example.R
import com.example.data.ClotchSettings
import com.example.data.OrientationOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START = "com.example.recording.ACTION_START"
        const val ACTION_STOP = "com.example.recording.ACTION_STOP"
        const val ACTION_PAUSE = "com.example.recording.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.recording.ACTION_RESUME"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var controller: RecordingController

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var pendingMediaUri: Uri? = null
    private var outputFileName: String? = null

    private var timerJob: Job? = null
    private var activeSegmentStartMs: Long = 0L
    private var accumulatedElapsedMs: Long = 0L
    private var isPaused: Boolean = false
    private var isStopping: Boolean = false

    private var activeWidth: Int = 1080
    private var activeHeight: Int = 1920
    private var activeFps: Int = 60
    private var activeBitrateMbps: Int = 20

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!isStopping) {
                stopRecordingAndService(fromSystemCallback = true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        controller = (application as ClotchApplication).recordingController
        controller.ensureNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val projectionData = IntentCompat.getParcelableExtra(
                    intent,
                    EXTRA_PROJECTION_DATA,
                    Intent::class.java
                )
                if (resultCode == Activity.RESULT_OK && projectionData != null) {
                    startCapturePipeline(resultCode, projectionData)
                } else {
                    controller.onServiceError("Screen capture permission was not granted")
                    stopSelf()
                }
            }

            ACTION_PAUSE -> pauseCapture()
            ACTION_RESUME -> resumeCapture()
            ACTION_STOP -> stopRecordingAndService(fromSystemCallback = false)
        }
        return START_NOT_STICKY
    }

    private fun startCapturePipeline(resultCode: Int, projectionData: Intent) {
        isStopping = false
        isPaused = false
        accumulatedElapsedMs = 0L

        val settings = controller.settings.value
        val hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val useMic = settings.recordMicAudio && hasMicPermission

        val notification = buildRecordingNotification(
            settings = settings,
            isPaused = false
        )

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && useMic) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(
                this,
                RecordingController.NOTIFICATION_ID,
                notification,
                fgsType
            )
        } catch (e: Exception) {
            controller.onServiceError("Failed to start foreground recording service: ${e.localizedMessage}")
            stopSelf()
            return
        }

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            projectionManager.getMediaProjection(resultCode, projectionData)
        } catch (e: Exception) {
            controller.onServiceError("Unable to initialize MediaProjection: ${e.localizedMessage}")
            cleanupAndStopForeground()
            return
        }

        if (projection == null) {
            controller.onServiceError("MediaProjection session unavailable")
            cleanupAndStopForeground()
            return
        }

        mediaProjection = projection
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        val (targetW, targetH) = resolveDimensions(settings)
        val densityDpi = resources.displayMetrics.densityDpi

        val fileTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "Clotch_${fileTimestamp}.mp4"
        outputFileName = fileName

        val prepared = prepareRecorderWithFallback(
            fileName = fileName,
            width = targetW,
            height = targetH,
            fps = settings.fps.fps,
            bitrateBps = settings.bitrate.bps,
            useMic = useMic
        )

        if (!prepared) {
            controller.onServiceError("Device video encoder rejected recording profile")
            cleanupAndStopForeground()
            return
        }

        try {
            val surface = mediaRecorder?.surface
                ?: throw IllegalStateException("MediaRecorder surface is null")

            virtualDisplay = projection.createVirtualDisplay(
                "ClotchScreenCapture",
                activeWidth,
                activeHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            )

            mediaRecorder?.start()
            activeSegmentStartMs = SystemClock.elapsedRealtime()

            controller.onServiceRecordingStarted(
                startedAtMs = activeSegmentStartMs,
                fileName = fileName,
                width = activeWidth,
                height = activeHeight,
                fps = activeFps,
                bitrateMbps = activeBitrateMbps
            )
            updateNotification(settings = settings, isPaused = false)
            startTimerLoop()
        } catch (e: Exception) {
            controller.onServiceError("Screen recording failed to start: ${e.localizedMessage}")
            cleanupMediaResources(deletePending = true)
            cleanupAndStopForeground()
        }
    }

    private fun prepareRecorderWithFallback(
        fileName: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrateBps: Int,
        useMic: Boolean
    ): Boolean {
        val candidates = listOf(
            Triple(Pair(width, height), fps, bitrateBps),
            Triple(Pair(width, height), fps.coerceAtMost(60), bitrateBps.coerceAtMost(16_000_000)),
            Triple(
                Pair(if (width > height) 1280 else 720, if (width > height) 720 else 1280),
                30,
                8_000_000
            )
        )

        candidates.forEachIndexed { index, (dims, candidateFps, candidateBitrate) ->
            val w = dims.first and 1.inv()
            val h = dims.second and 1.inv()
            if (tryPrepareSingleConfig(fileName, w, h, candidateFps, candidateBitrate, useMic)) {
                activeWidth = w
                activeHeight = h
                activeFps = candidateFps
                activeBitrateMbps = (candidateBitrate / 1_000_000).coerceAtLeast(1)
                if (index > 0) {
                    controller.showToast(
                        "Adjusted capture to ${minOf(w, h)}p ${candidateFps}FPS for hardware stability"
                    )
                }
                return true
            }
        }
        return false
    }

    private fun tryPrepareSingleConfig(
        fileName: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrateBps: Int,
        useMic: Boolean
    ): Boolean {
        releaseRecorderOnly()
        closeOutputHandles(deletePending = true)

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        return try {
            if (useMic) {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

            if (!configureOutputTarget(recorder, fileName)) {
                recorder.release()
                return false
            }

            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (useMic) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioEncodingBitRate(128_000)
                recorder.setAudioSamplingRate(44_100)
            }
            recorder.setVideoSize(width, height)
            recorder.setVideoFrameRate(fps)
            recorder.setVideoEncodingBitRate(bitrateBps)
            recorder.prepare()

            mediaRecorder = recorder
            true
        } catch (_: Exception) {
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
            closeOutputHandles(deletePending = true)
            false
        }
    }

    private fun configureOutputTarget(recorder: MediaRecorder, fileName: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/Clotch"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            val pfd = contentResolver.openFileDescriptor(uri, "w")
            if (pfd == null) {
                runCatching { contentResolver.delete(uri, null, null) }
                return false
            }
            pendingMediaUri = uri
            parcelFileDescriptor = pfd
            recorder.setOutputFile(pfd.fileDescriptor)
            true
        } else {
            val moviesDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Clotch")
            if (!moviesDir.exists()) moviesDir.mkdirs()
            val outFile = File(moviesDir, fileName)
            recorder.setOutputFile(outFile.absolutePath)
            true
        }
    }

    private fun resolveDimensions(settings: ClotchSettings): Pair<Int, Int> {
        val isDeviceLandscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val useLandscape = when (settings.orientation) {
            OrientationOption.AUTO -> isDeviceLandscape
            OrientationOption.LANDSCAPE -> true
            OrientationOption.PORTRAIT -> false
        }
        val shortSide = settings.resolution.shortSide
        val longSide = settings.resolution.longSide
        return if (useLandscape) {
            longSide to shortSide
        } else {
            shortSide to longSide
        }
    }

    private fun pauseCapture() {
        if (isPaused || isStopping || mediaRecorder == null) return
        try {
            mediaRecorder?.pause()
            accumulatedElapsedMs += (SystemClock.elapsedRealtime() - activeSegmentStartMs)
            isPaused = true
            timerJob?.cancel()
            controller.onServiceRecordingPaused(accumulatedElapsedMs)
            updateNotification(settings = controller.settings.value, isPaused = true)
        } catch (e: Exception) {
            controller.showToast("Pause unsupported on this encoder: ${e.localizedMessage}")
        }
    }

    private fun resumeCapture() {
        if (!isPaused || isStopping || mediaRecorder == null) return
        try {
            mediaRecorder?.resume()
            activeSegmentStartMs = SystemClock.elapsedRealtime()
            isPaused = false
            controller.onServiceRecordingResumed(activeSegmentStartMs)
            updateNotification(settings = controller.settings.value, isPaused = false)
            startTimerLoop()
        } catch (e: Exception) {
            controller.showToast("Failed to resume recording: ${e.localizedMessage}")
        }
    }

    private fun stopRecordingAndService(fromSystemCallback: Boolean) {
        if (isStopping) return
        isStopping = true
        timerJob?.cancel()

        val savedName = outputFileName
        val recorder = mediaRecorder
        var stopSucceeded = false

        if (recorder != null) {
            stopSucceeded = runCatching {
                recorder.stop()
                true
            }.getOrDefault(false)
        }

        cleanupMediaResources(deletePending = !stopSucceeded)
        cleanupAndStopForeground()

        controller.onServiceRecordingStopped(if (stopSucceeded) savedName else null)
        if (fromSystemCallback && !stopSucceeded) {
            controller.showToast("Screen capture ended by system")
        }
    }

    private fun cleanupMediaResources(deletePending: Boolean) {
        releaseRecorderOnly()
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        runCatching { mediaProjection?.stop() }
        mediaProjection = null

        closeOutputHandles(deletePending = deletePending)
    }

    private fun releaseRecorderOnly() {
        val recorder = mediaRecorder
        mediaRecorder = null
        if (recorder != null) {
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
        }
    }

    private fun closeOutputHandles(deletePending: Boolean) {
        runCatching { parcelFileDescriptor?.close() }
        parcelFileDescriptor = null

        val uri = pendingMediaUri
        pendingMediaUri = null
        if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (deletePending) {
                runCatching { contentResolver.delete(uri, null, null) }
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                runCatching { contentResolver.update(uri, values, null, null) }
            }
        }
    }

    private fun cleanupAndStopForeground() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startTimerLoop() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive && !isPaused && !isStopping) {
                val totalMs = accumulatedElapsedMs + (SystemClock.elapsedRealtime() - activeSegmentStartMs)
                controller.onServiceRecordingTick(totalMs)
                delay(500L)
            }
        }
    }

    private fun updateNotification(settings: ClotchSettings, isPaused: Boolean) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            RecordingController.NOTIFICATION_ID,
            buildRecordingNotification(settings, isPaused)
        )
    }

    private fun buildRecordingNotification(
        settings: ClotchSettings,
        isPaused: Boolean
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseOrResumeAction = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        val pauseOrResumeLabel = if (isPaused) {
            getString(R.string.action_resume_recording)
        } else {
            getString(R.string.action_pause_recording)
        }
        val pauseOrResumeIcon = if (isPaused) {
            android.R.drawable.ic_media_play
        } else {
            android.R.drawable.ic_media_pause
        }
        val pauseOrResumeIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = pauseOrResumeAction
        }
        val pauseOrResumePending = PendingIntent.getService(
            this,
            102,
            pauseOrResumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusTitle = if (isPaused) {
            "Clotch Paused • ${activeWidth.coerceAtMost(activeHeight)}p ${activeFps}FPS"
        } else {
            "Recording ${settings.gameName} • ${activeFps}FPS"
        }

        val discordSub = if (settings.discordRpcEnabled) {
            "Discord RPC: Recording ${settings.gameName} at ${activeFps}FPS"
        } else {
            "${activeBitrateMbps} Mbps • Tap Stop to save clip"
        }

        return NotificationCompat.Builder(this, RecordingController.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_busy)
            .setContentTitle(statusTitle)
            .setContentText(discordSub)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(!isPaused)
            .setContentIntent(openPending)
            .addAction(pauseOrResumeIcon, pauseOrResumeLabel, pauseOrResumePending)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_stop_recording),
                stopPending
            )
            .build()
    }

    override fun onDestroy() {
        timerJob?.cancel()
        serviceScope.cancel()
        if (!isStopping) {
            cleanupMediaResources(deletePending = false)
        }
        super.onDestroy()
    }
}
