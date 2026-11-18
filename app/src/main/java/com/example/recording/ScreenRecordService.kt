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
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.ClotchApplication
import com.example.MainActivity
import com.example.R
import com.example.data.AudioCaptureSource
import com.example.data.AudioTrackMode
import com.example.data.ClotchSettings
import com.example.data.OrientationOption
import com.example.data.RecordingMode
import com.example.data.VideoFormat
import com.example.diagnostics.CrashLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
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

    private val serviceExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        CrashLogger.logError("ScreenRecordService", "Uncaught service coroutine failure", throwable)
        controller.showToast("Recording encountered an error; cleanup applied")
        cleanupAndStopForeground()
    }

    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + serviceExceptionHandler)

    private lateinit var controller: RecordingController

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var internalAudioCapturer: InternalAudioCapturer? = null
    private var micPcmCapturer: MicPcmCapturer? = null

    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var pendingMediaUri: Uri? = null
    private var tempMp4ForPostProcess: File? = null
    private var activeVideoFormat: VideoFormat = VideoFormat.MP4
    private var activeTrackMode: AudioTrackMode = AudioTrackMode.SEPARATE_AUDIO
    private var activeFileTimestamp: String = ""
    private var outputFileName: String? = null
    private var saveCompanionTracks: Boolean = false

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
                if (mediaProjection != null || mediaRecorder != null) {
                    return START_NOT_STICKY
                }
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
        activeVideoFormat = settings.videoFormat
        activeTrackMode = settings.audioTrackMode

        var hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val requestedSource = settings.effectiveAudioSource
        val canCaptureGameAudio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            hasMicPermission &&
            settings.videoFormat != VideoFormat.THREE_GPP

        val wantsGameAudioInitial = (requestedSource == AudioCaptureSource.GAME_ONLY ||
            requestedSource == AudioCaptureSource.BOTH) && canCaptureGameAudio

        if ((requestedSource == AudioCaptureSource.GAME_ONLY || requestedSource == AudioCaptureSource.BOTH) &&
            !canCaptureGameAudio && hasMicPermission
        ) {
            val reason = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                "Game audio capture requires Android 10+; recording mic audio"
            } else {
                "Internal game audio unavailable for 3GPP; switch to MP4/MKV for multi-track"
            }
            controller.showToast(reason)
        }

        val wantsMicInitial = hasMicPermission && (
            requestedSource == AudioCaptureSource.MIC_ONLY ||
                requestedSource == AudioCaptureSource.BOTH ||
                (!wantsGameAudioInitial && (requestedSource == AudioCaptureSource.GAME_ONLY || requestedSource == AudioCaptureSource.BOTH))
            )

        val separateMode = settings.audioTrackMode == AudioTrackMode.SEPARATE_AUDIO ||
            (settings.recordingMode == RecordingMode.VIDEO_ONLY && settings.recordAudioSeparately)
        saveCompanionTracks = separateMode && (wantsGameAudioInitial || wantsMicInitial)

        val notification = buildRecordingNotification(
            settings = settings,
            isPaused = false
        )

        val fgsUsesMic = wantsGameAudioInitial || wantsMicInitial
        val fgsStarted = startForegroundSafely(notification, fgsUsesMic)
        if (!fgsStarted.first) {
            controller.onServiceError("Failed to start foreground recording service")
            stopSelf()
            return
        }
        if (fgsUsesMic && !fgsStarted.second) {
            // Android 14+ restricted FGS mic start; continue screen capture cleanly without mic crash
            hasMicPermission = false
            controller.showToast("Microphone FGS restricted by system; recording screen video")
        }

        val wantsGameAudio = wantsGameAudioInitial && hasMicPermission
        val wantsMicAudio = wantsMicInitial && hasMicPermission
        saveCompanionTracks = separateMode && (wantsGameAudio || wantsMicAudio)

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
        activeFileTimestamp = fileTimestamp
        val finalFileName = "clip_${fileTimestamp}.${settings.videoFormat.extension}"
        outputFileName = finalFileName

        // Decide whether mic can go straight into MediaRecorder (Normal Audio, Mic-only, MP4)
        // vs PCM capture (Separate Audio OR Normal Mixed Game+Mic OR MKV).
        val directMicInMediaRecorder = wantsMicAudio && !wantsGameAudio && !separateMode &&
            settings.videoFormat != VideoFormat.MKV
        val usePcmMicCapture = wantsMicAudio && !directMicInMediaRecorder
        val stageTempMp4 = settings.videoFormat == VideoFormat.MKV ||
            wantsGameAudio ||
            usePcmMicCapture

        val prepared = prepareRecorderWithFallback(
            fileTimestamp = fileTimestamp,
            finalFileName = finalFileName,
            format = settings.videoFormat,
            width = targetW,
            height = targetH,
            fps = settings.fps.fps,
            bitrateBps = settings.bitrate.bps,
            useMic = directMicInMediaRecorder,
            stageTempMp4 = stageTempMp4
        )

        if (!prepared) {
            controller.onServiceError("Device video encoder rejected recording profile")
            cleanupAndStopForeground()
            return
        }

        if (wantsGameAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val gamePcm = File(cacheDir, "game_${fileTimestamp}.pcm")
            val capturer = InternalAudioCapturer(gamePcm)
            if (capturer.start(projection)) {
                internalAudioCapturer = capturer
            } else {
                controller.showToast("Game audio capture unavailable; continuing video + mic")
            }
        }

        if (usePcmMicCapture) {
            val micPcm = File(cacheDir, "mic_${fileTimestamp}.pcm")
            val micCapturer = MicPcmCapturer(micPcm)
            if (micCapturer.start()) {
                micPcmCapturer = micCapturer
            } else {
                controller.showToast("Microphone unavailable right now; continuing screen recording")
            }
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
                fileName = finalFileName,
                width = activeWidth,
                height = activeHeight,
                fps = activeFps,
                bitrateMbps = activeBitrateMbps
            )
            updateNotification(settings = settings, isPaused = false)
            startTimerLoop()
        } catch (e: Exception) {
            CrashLogger.logError("ScreenRecordService", "Screen recording start failed", e)
            controller.onServiceError("Screen recording failed to start: ${e.localizedMessage}")
            cleanupMediaResources(deletePending = true)
            cleanupAndStopForeground()
        }
    }

    private fun startForegroundSafely(
        notification: Notification,
        withMicrophone: Boolean
    ): Pair<Boolean, Boolean> {
        val preferredType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && withMicrophone) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }

        val firstTry = runCatching {
            ServiceCompat.startForeground(
                this,
                RecordingController.NOTIFICATION_ID,
                notification,
                preferredType
            )
        }
        if (firstTry.isSuccess) {
            return true to withMicrophone
        }

        if (withMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fallbackTry = runCatching {
                ServiceCompat.startForeground(
                    this,
                    RecordingController.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            }
            if (fallbackTry.isSuccess) {
                CrashLogger.logError(
                    "ScreenRecordService.FGS",
                    "Microphone FGS rejected; fell back to MediaProjection FGS only",
                    firstTry.exceptionOrNull()
                )
                return true to false
            }
        }
        CrashLogger.logError(
            "ScreenRecordService.FGS",
            "Unable to start foreground service",
            firstTry.exceptionOrNull()
        )
        return false to false
    }

    private fun prepareRecorderWithFallback(
        fileTimestamp: String,
        finalFileName: String,
        format: VideoFormat,
        width: Int,
        height: Int,
        fps: Int,
        bitrateBps: Int,
        useMic: Boolean,
        stageTempMp4: Boolean
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
            if (tryPrepareSingleConfig(
                    fileTimestamp = fileTimestamp,
                    finalFileName = finalFileName,
                    format = format,
                    width = w,
                    height = h,
                    fps = candidateFps,
                    bitrateBps = candidateBitrate,
                    useMic = useMic,
                    stageTempMp4 = stageTempMp4
                )
            ) {
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
        fileTimestamp: String,
        finalFileName: String,
        format: VideoFormat,
        width: Int,
        height: Int,
        fps: Int,
        bitrateBps: Int,
        useMic: Boolean,
        stageTempMp4: Boolean
    ): Boolean {
        releaseRecorderOnly()
        closeVideoOutputHandles(deletePending = true)

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

            val outputFormatConst = when {
                stageTempMp4 -> MediaRecorder.OutputFormat.MPEG_4
                format == VideoFormat.THREE_GPP -> MediaRecorder.OutputFormat.THREE_GPP
                else -> MediaRecorder.OutputFormat.MPEG_4
            }
            recorder.setOutputFormat(outputFormatConst)

            if (!configureVideoOutputTarget(recorder, fileTimestamp, finalFileName, format, stageTempMp4)) {
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
            closeVideoOutputHandles(deletePending = true)
            false
        }
    }

    private fun configureVideoOutputTarget(
        recorder: MediaRecorder,
        fileTimestamp: String,
        finalFileName: String,
        format: VideoFormat,
        stageTempMp4: Boolean
    ): Boolean {
        if (stageTempMp4) {
            val tempFile = File(cacheDir, "temp_clip_${fileTimestamp}.mp4")
            if (tempFile.exists()) tempFile.delete()
            tempMp4ForPostProcess = tempFile
            recorder.setOutputFile(tempFile.absolutePath)
            return true
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, finalFileName)
                put(MediaStore.Video.Media.MIME_TYPE, format.mimeType)
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
            val outFile = File(moviesDir, finalFileName)
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
            internalAudioCapturer?.setPaused(true)
            micPcmCapturer?.setPaused(true)
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
            internalAudioCapturer?.setPaused(false)
            micPcmCapturer?.setPaused(false)
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
        val fileStem = activeFileTimestamp
        val recorder = mediaRecorder
        val gameCapturer = internalAudioCapturer
        val micCapturer = micPcmCapturer
        internalAudioCapturer = null
        micPcmCapturer = null

        val stopSucceeded = if (recorder != null) {
            runCatching {
                recorder.stop()
                true
            }.onFailure { t ->
                CrashLogger.logError("ScreenRecordService.Stop", "MediaRecorder.stop failed", t)
            }.getOrDefault(false)
        } else {
            false
        }

        val gamePcmFile = gameCapturer?.stop()
        val micPcmFile = micCapturer?.stop()

        releaseRecorderOnly()
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        runCatching { mediaProjection?.stop() }
        mediaProjection = null

        val tempMp4 = tempMp4ForPostProcess
        tempMp4ForPostProcess = null

        val needsPostProcess = (activeVideoFormat == VideoFormat.MKV ||
            gamePcmFile != null ||
            micPcmFile != null ||
            tempMp4 != null)

        if (needsPostProcess && stopSucceeded && tempMp4 != null && tempMp4.exists() && savedName != null) {
            val toastMsg = when {
                saveCompanionTracks && gamePcmFile != null && micPcmFile != null ->
                    "Saving Video + Game & Mic separate tracks..."
                saveCompanionTracks ->
                    "Saving separate audio track + video..."
                gamePcmFile != null && micPcmFile != null ->
                    "Mixing Game Audio + Mic into clip..."
                activeVideoFormat == VideoFormat.MKV ->
                    "Remuxing recording to MKV..."
                else -> "Finalizing recording..."
            }
            controller.showToast(toastMsg)

            serviceScope.launch {
                val (videoOk, companionMicName) = postProcessAndPublishRecording(
                    tempMp4 = tempMp4,
                    gamePcmFile = gamePcmFile,
                    micPcmFile = micPcmFile,
                    fileTimestamp = fileStem,
                    finalFileName = savedName,
                    targetFormat = activeVideoFormat,
                    publishSeparateTracks = saveCompanionTracks
                )
                cleanupAndStopForeground()
                controller.onServiceRecordingStopped(
                    savedFileName = if (videoOk) savedName else null,
                    audioFileName = companionMicName
                )
            }
        } else {
            runCatching { tempMp4?.delete() }
            runCatching { gamePcmFile?.delete() }
            runCatching { micPcmFile?.delete() }
            closeVideoOutputHandles(deletePending = !stopSucceeded)
            cleanupAndStopForeground()
            controller.onServiceRecordingStopped(
                savedFileName = if (stopSucceeded) savedName else null,
                audioFileName = null
            )
            if (fromSystemCallback && !stopSucceeded) {
                controller.showToast("Screen capture ended by system")
            }
        }
    }

    private suspend fun postProcessAndPublishRecording(
        tempMp4: File,
        gamePcmFile: File?,
        micPcmFile: File?,
        fileTimestamp: String,
        finalFileName: String,
        targetFormat: VideoFormat,
        publishSeparateTracks: Boolean
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        val tempGameM4a = if (gamePcmFile != null && gamePcmFile.exists() && gamePcmFile.length() > 0L) {
            File(cacheDir, "game_${fileTimestamp}.m4a").takeIf { outM4a ->
                GameAudioPostProcessor.encodePcmToM4a(gamePcmFile, outM4a)
            }
        } else {
            null
        }

        val tempMicM4a = if (micPcmFile != null && micPcmFile.exists() && micPcmFile.length() > 0L) {
            File(cacheDir, "mic_${fileTimestamp}.m4a").takeIf { outM4a ->
                GameAudioPostProcessor.encodePcmToM4a(micPcmFile, outM4a)
            }
        } else {
            null
        }

        var tempMixedM4a: File? = null
        var publishedMicName: String? = null
        val stagedFinal = File(cacheDir, finalFileName)
        var videoPublished = false

        try {
            if (publishSeparateTracks) {
                // Save separate Game Audio and Microphone tracks to Music/Clotch
                if (tempGameM4a != null) {
                    saveFileToMusicMediaStore(tempGameM4a, "game_${fileTimestamp}.m4a", "audio/mp4")
                }
                if (tempMicM4a != null) {
                    val micDisplayName = "mic_${fileTimestamp}.m4a"
                    if (saveFileToMusicMediaStore(tempMicM4a, micDisplayName, "audio/mp4")) {
                        publishedMicName = micDisplayName
                    }
                }
            }

            // Determine primary audio stream to embed inside the video file itself
            val primaryVideoAudio: File? = if (publishSeparateTracks) {
                tempGameM4a ?: tempMicM4a
            } else {
                when {
                    gamePcmFile != null && micPcmFile != null -> {
                        File(cacheDir, "mixed_${fileTimestamp}.m4a").takeIf { outMixed ->
                            GameAudioPostProcessor.mixPcmTracksToM4a(gamePcmFile, micPcmFile, outMixed)
                        }.also { tempMixedM4a = it } ?: tempGameM4a ?: tempMicM4a
                    }
                    else -> tempGameM4a ?: tempMicM4a
                }
            }

            val muxOk = if (primaryVideoAudio != null) {
                GameAudioPostProcessor.muxVideoWithAudio(tempMp4, primaryVideoAudio, stagedFinal)
            } else if (targetFormat == VideoFormat.MKV) {
                val cmd = "-y -i \"${tempMp4.absolutePath}\" -c copy \"${stagedFinal.absolutePath}\""
                val session = runCatching { FFmpegKit.execute(cmd) }.getOrNull()
                session != null && ReturnCode.isSuccess(session.returnCode) &&
                    stagedFinal.exists() && stagedFinal.length() > 0L
            } else {
                false
            }

            videoPublished = if (muxOk && stagedFinal.exists() && stagedFinal.length() > 0L) {
                saveFileToMoviesMediaStore(stagedFinal, finalFileName, targetFormat.mimeType)
            } else {
                val fallbackMp4Name = finalFileName.replace(".mkv", ".mp4", ignoreCase = true)
                saveFileToMoviesMediaStore(tempMp4, fallbackMp4Name, VideoFormat.MP4.mimeType)
            }

            if (!videoPublished && tempMp4.exists() && tempMp4.length() > 0L) {
                val safeFallbackName = finalFileName.replace(".mkv", ".mp4", ignoreCase = true)
                val fallbackDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Clotch")
                if (!fallbackDir.exists()) fallbackDir.mkdirs()
                val fallbackDest = File(fallbackDir, safeFallbackName)
                videoPublished = runCatching {
                    tempMp4.copyTo(fallbackDest, overwrite = true)
                    fallbackDest.exists() && fallbackDest.length() > 0L
                }.getOrDefault(false)
            }
            videoPublished to publishedMicName
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            CrashLogger.logError(
                "ScreenRecordService.PostProcess",
                "Audio post-processing failed; attempting raw video preservation",
                t
            )
            val fallbackMp4Name = finalFileName.replace(".mkv", ".mp4", ignoreCase = true)
            videoPublished = if (tempMp4.exists() && tempMp4.length() > 0L) {
                saveFileToMoviesMediaStore(tempMp4, fallbackMp4Name, VideoFormat.MP4.mimeType)
            } else {
                false
            }
            if (!videoPublished) {
                controller.showToast("Audio mix failed; raw video preserved if available")
            }
            videoPublished to publishedMicName
        } finally {
            if (videoPublished) {
                runCatching { tempMp4.delete() }
            }
            runCatching { gamePcmFile?.delete() }
            runCatching { micPcmFile?.delete() }
            runCatching { tempGameM4a?.delete() }
            runCatching { tempMicM4a?.delete() }
            runCatching { tempMixedM4a?.delete() }
            runCatching { stagedFinal.delete() }
        }
    }

    private fun saveFileToMusicMediaStore(
        sourceFile: File,
        displayName: String,
        mimeType: String
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Clotch")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                return try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(sourceFile).use { input -> input.copyTo(out) }
                    } ?: error("Null output stream for $uri")
                    val doneValues = ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                    }
                    contentResolver.update(uri, doneValues, null, null)
                    true
                } catch (_: Exception) {
                    runCatching { contentResolver.delete(uri, null, null) }
                    false
                }
            } else {
                val musicDir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Clotch")
                if (!musicDir.exists()) musicDir.mkdirs()
                sourceFile.copyTo(File(musicDir, displayName), overwrite = true)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun saveFileToMoviesMediaStore(
        sourceFile: File,
        displayName: String,
        mimeType: String
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MOVIES}/Clotch"
                    )
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                return try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(out)
                        }
                    } ?: error("Null output stream for $uri")
                    val doneValues = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    contentResolver.update(uri, doneValues, null, null)
                    true
                } catch (_: Exception) {
                    runCatching { contentResolver.delete(uri, null, null) }
                    false
                }
            } else {
                val moviesDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Clotch")
                if (!moviesDir.exists()) moviesDir.mkdirs()
                val dest = File(moviesDir, displayName)
                sourceFile.copyTo(dest, overwrite = true)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun cleanupMediaResources(deletePending: Boolean) {
        releaseRecorderOnly()
        internalAudioCapturer?.releaseAndDelete()
        internalAudioCapturer = null
        micPcmCapturer?.releaseAndDelete()
        micPcmCapturer = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        runCatching { mediaProjection?.stop() }
        mediaProjection = null

        tempMp4ForPostProcess?.delete()
        tempMp4ForPostProcess = null

        closeVideoOutputHandles(deletePending = deletePending)
    }

    private fun releaseRecorderOnly() {
        val recorder = mediaRecorder
        mediaRecorder = null
        if (recorder != null) {
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
        }
    }

    private fun closeVideoOutputHandles(deletePending: Boolean) {
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
                val totalMs =
                    accumulatedElapsedMs + (SystemClock.elapsedRealtime() - activeSegmentStartMs)
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
            "Recording ${settings.gameName} • ${activeFps}FPS (${settings.audioTrackMode.shortLabel})"
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
        timerJob = null
        if (!isStopping) {
            cleanupMediaResources(deletePending = false)
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}
