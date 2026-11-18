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
import com.example.data.ClotchSettings
import com.example.data.OrientationOption
import com.example.data.RecordingMode
import com.example.data.VideoFormat
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var controller: RecordingController

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var separateAudioRecorder: MediaRecorder? = null
    private var internalAudioCapturer: InternalAudioCapturer? = null

    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var pendingMediaUri: Uri? = null
    private var tempMp4ForPostProcess: File? = null
    private var activeVideoFormat: VideoFormat = VideoFormat.MP4
    private var activeFileTimestamp: String = ""
    private var outputFileName: String? = null
    private var captureInternalGameAudio: Boolean = false

    private var audioFileDescriptor: ParcelFileDescriptor? = null
    private var pendingAudioUri: Uri? = null
    private var separateAudioFileName: String? = null

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

        val hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val requestedSource = settings.effectiveAudioSource
        val canCaptureGameAudio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            hasMicPermission &&
            settings.videoFormat != VideoFormat.THREE_GPP

        val wantsGameAudio = (requestedSource == AudioCaptureSource.GAME_ONLY ||
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

        val muxMicInVideo = !wantsGameAudio && hasMicPermission &&
            ((requestedSource == AudioCaptureSource.MIC_ONLY &&
                !(settings.recordingMode == RecordingMode.VIDEO_ONLY && settings.recordAudioSeparately)) ||
                requestedSource == AudioCaptureSource.BOTH)

        // Never open two simultaneous MediaRecorder(AudioSource.MIC) sessions
        val recordSeparateMic = hasMicPermission && !muxMicInVideo && (
            (wantsGameAudio && requestedSource == AudioCaptureSource.BOTH) ||
                (settings.recordingMode == RecordingMode.VIDEO_ONLY && settings.recordAudioSeparately)
            )

        captureInternalGameAudio = wantsGameAudio
        val fgsUsesMic = muxMicInVideo || recordSeparateMic || wantsGameAudio

        val notification = buildRecordingNotification(
            settings = settings,
            isPaused = false
        )

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && fgsUsesMic) {
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
            controller.onServiceError("Failed to start foreground service: ${e.localizedMessage}")
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
        activeFileTimestamp = fileTimestamp
        val finalFileName = "clip_${fileTimestamp}.${settings.videoFormat.extension}"
        outputFileName = finalFileName

        val prepared = prepareRecorderWithFallback(
            fileTimestamp = fileTimestamp,
            finalFileName = finalFileName,
            format = settings.videoFormat,
            width = targetW,
            height = targetH,
            fps = settings.fps.fps,
            bitrateBps = settings.bitrate.bps,
            useMic = muxMicInVideo,
            stageTempMp4 = (settings.videoFormat == VideoFormat.MKV || wantsGameAudio)
        )

        if (!prepared) {
            controller.onServiceError("Device video encoder rejected recording profile")
            cleanupAndStopForeground()
            return
        }

        if (recordSeparateMic) {
            prepareSeparateAudioRecorder(fileTimestamp)
        }

        if (wantsGameAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pcmFile = File(cacheDir, "game_${fileTimestamp}.pcm")
            val capturer = InternalAudioCapturer(pcmFile)
            if (capturer.start(projection)) {
                internalAudioCapturer = capturer
            } else {
                captureInternalGameAudio = false
                controller.showToast("Game audio capture unsupported by active app; saving video + mic")
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
            if (separateAudioRecorder != null) {
                runCatching {
                    separateAudioRecorder?.start()
                }.onFailure {
                    releaseSeparateAudioOnly()
                    closeSeparateAudioHandles(deletePending = true)
                    separateAudioFileName = null
                    controller.showToast("Separate mic track skipped; recording video only")
                }
            }
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
            controller.onServiceError("Screen recording failed to start: ${e.localizedMessage}")
            cleanupMediaResources(deletePending = true)
            cleanupAndStopForeground()
        }
    }

    private fun prepareSeparateAudioRecorder(fileTimestamp: String) {
        val audioName = "mic_${fileTimestamp}.m4a"
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        val configured = runCatching {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(128_000)
            recorder.setAudioSamplingRate(44_100)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, audioName)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                    put(
                        MediaStore.Audio.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MUSIC}/Clotch"
                    )
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Failed to insert MediaStore audio row")
                val pfd = contentResolver.openFileDescriptor(uri, "w")
                    ?: throw IllegalStateException("Failed to open audio descriptor")
                pendingAudioUri = uri
                audioFileDescriptor = pfd
                recorder.setOutputFile(pfd.fileDescriptor)
            } else {
                val musicDir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Clotch")
                if (!musicDir.exists()) musicDir.mkdirs()
                val outFile = File(musicDir, audioName)
                recorder.setOutputFile(outFile.absolutePath)
            }

            recorder.prepare()
            separateAudioRecorder = recorder
            separateAudioFileName = audioName
            true
        }.getOrElse {
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
            closeSeparateAudioHandles(deletePending = true)
            controller.showToast("Separate audio recorder unavailable on this device")
            false
        }

        if (!configured) {
            separateAudioFileName = null
        }
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
        // MKV or Game Audio multi-track records to a temporary MP4 in cacheDir first, then post-processes via FFmpegKit
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
            runCatching { separateAudioRecorder?.pause() }
            internalAudioCapturer?.setPaused(true)
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
            runCatching { separateAudioRecorder?.resume() }
            internalAudioCapturer?.setPaused(false)
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
        val audioName = separateAudioFileName
        val fileStem = activeFileTimestamp
        val recorder = mediaRecorder
        val audioRec = separateAudioRecorder
        val gameCapturer = internalAudioCapturer
        internalAudioCapturer = null

        val stopSucceeded = if (recorder != null) {
            runCatching {
                recorder.stop()
                true
            }.getOrDefault(false)
        } else {
            false
        }

        val audioStopSucceeded = if (audioRec != null) {
            runCatching {
                audioRec.stop()
                true
            }.getOrDefault(false)
        } else {
            false
        }

        val gamePcmFile = gameCapturer?.stop()

        releaseRecorderOnly()
        releaseSeparateAudioOnly()
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        runCatching { mediaProjection?.stop() }
        mediaProjection = null

        closeSeparateAudioHandles(deletePending = !audioStopSucceeded)

        val tempMp4 = tempMp4ForPostProcess
        tempMp4ForPostProcess = null

        val needsPostProcess = (activeVideoFormat == VideoFormat.MKV || gamePcmFile != null || tempMp4 != null)
        if (needsPostProcess && stopSucceeded && tempMp4 != null && tempMp4.exists() && savedName != null) {
            controller.showToast(
                when {
                    gamePcmFile != null && activeVideoFormat == VideoFormat.MKV -> "Saving Game Audio + MKV tracks..."
                    gamePcmFile != null -> "Saving Game Audio + separate tracks..."
                    else -> "Remuxing recording to MKV via FFmpeg..."
                }
            )
            serviceScope.launch {
                val postOk = postProcessAndPublishRecording(
                    tempMp4 = tempMp4,
                    gamePcmFile = gamePcmFile,
                    fileTimestamp = fileStem,
                    finalFileName = savedName,
                    targetFormat = activeVideoFormat
                )
                cleanupAndStopForeground()
                controller.onServiceRecordingStopped(
                    savedFileName = if (postOk) savedName else null,
                    audioFileName = if (audioStopSucceeded) audioName else null
                )
            }
        } else {
            tempMp4?.delete()
            gamePcmFile?.delete()
            closeVideoOutputHandles(deletePending = !stopSucceeded)
            cleanupAndStopForeground()
            controller.onServiceRecordingStopped(
                savedFileName = if (stopSucceeded) savedName else null,
                audioFileName = if (audioStopSucceeded) audioName else null
            )
            if (fromSystemCallback && !stopSucceeded) {
                controller.showToast("Screen capture ended by system")
            }
        }
    }

    private suspend fun postProcessAndPublishRecording(
        tempMp4: File,
        gamePcmFile: File?,
        fileTimestamp: String,
        finalFileName: String,
        targetFormat: VideoFormat
    ): Boolean = withContext(Dispatchers.IO) {
        val tempGameM4a = if (gamePcmFile != null && gamePcmFile.exists() && gamePcmFile.length() > 0L) {
            File(cacheDir, "game_${fileTimestamp}.m4a").takeIf { outM4a ->
                GameAudioPostProcessor.encodePcmToM4a(gamePcmFile, outM4a)
            }
        } else {
            null
        }

        if (tempGameM4a != null) {
            saveFileToMusicMediaStore(tempGameM4a, "game_${fileTimestamp}.m4a", "audio/mp4")
        }

        val stagedFinal = File(cacheDir, finalFileName)
        var videoPublished = false
        return@withContext try {
            val muxOk = if (tempGameM4a != null) {
                GameAudioPostProcessor.muxVideoWithGameAudio(tempMp4, tempGameM4a, stagedFinal)
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
                // Emergency fallback: preserve original video in app-external Movies/Clotch
                val safeFallbackName = finalFileName.replace(".mkv", ".mp4", ignoreCase = true)
                val fallbackDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Clotch")
                if (!fallbackDir.exists()) fallbackDir.mkdirs()
                val fallbackDest = File(fallbackDir, safeFallbackName)
                videoPublished = runCatching {
                    tempMp4.copyTo(fallbackDest, overwrite = true)
                    fallbackDest.exists() && fallbackDest.length() > 0L
                }.getOrDefault(false)
            }
            videoPublished
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            com.example.diagnostics.CrashLogger.logError(
                "ScreenRecordService.PostProcess",
                "Separate audio post-processing failed; attempting raw video preservation",
                t
            )
            val fallbackMp4Name = finalFileName.replace(".mkv", ".mp4", ignoreCase = true)
            videoPublished = if (tempMp4.exists() && tempMp4.length() > 0L) {
                saveFileToMoviesMediaStore(tempMp4, fallbackMp4Name, VideoFormat.MP4.mimeType)
            } else {
                false
            }
            if (!videoPublished) {
                controller.showToast("Audio separation failed; raw video preserved in cache if needed")
            }
            videoPublished
        } finally {
            if (videoPublished) {
                runCatching { tempMp4.delete() }
            }
            runCatching { gamePcmFile?.delete() }
            runCatching { tempGameM4a?.delete() }
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
        releaseSeparateAudioOnly()
        internalAudioCapturer?.releaseAndDelete()
        internalAudioCapturer = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        runCatching { mediaProjection?.stop() }
        mediaProjection = null

        tempMp4ForPostProcess?.delete()
        tempMp4ForPostProcess = null

        closeVideoOutputHandles(deletePending = deletePending)
        closeSeparateAudioHandles(deletePending = deletePending)
    }

    private fun releaseRecorderOnly() {
        val recorder = mediaRecorder
        mediaRecorder = null
        if (recorder != null) {
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
        }
    }

    private fun releaseSeparateAudioOnly() {
        val recorder = separateAudioRecorder
        separateAudioRecorder = null
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

    private fun closeSeparateAudioHandles(deletePending: Boolean) {
        runCatching { audioFileDescriptor?.close() }
        audioFileDescriptor = null

        val uri = pendingAudioUri
        pendingAudioUri = null
        if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (deletePending) {
                runCatching { contentResolver.delete(uri, null, null) }
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
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
            "Recording ${settings.gameName} • ${activeFps}FPS (${settings.videoFormat.label})"
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
