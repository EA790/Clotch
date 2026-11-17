package com.example.recording

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.diagnostics.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class InternalAudioCapturer(
    private val pcmOutputFile: File
) {
    companion object {
        const val SAMPLE_RATE = 44_100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var readerThread: Thread? = null
    private val isCapturing = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    fun start(mediaProjection: MediaProjection): Boolean {
        if (isCapturing.get()) return true

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(4096)
        val bufferSize = maxOf(minBuf * 2, 16_384)

        val captureConfig = try {
            AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        } catch (t: Throwable) {
            CrashLogger.logError("InternalAudioCapturer", "AudioPlaybackCaptureConfiguration failed", t)
            return false
        }

        val format = AudioFormat.Builder()
            .setEncoding(AUDIO_FORMAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .build()

        val record = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (t: Throwable) {
            CrashLogger.logError("InternalAudioCapturer", "AudioRecord.Builder failed", t)
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            return false
        }

        audioRecord = record
        isPaused.set(false)
        isCapturing.set(true)

        return try {
            record.startRecording()
            readerThread = Thread({
                writePcmLoop(record, bufferSize)
            }, "ClotchGameAudioThread").apply { start() }
            true
        } catch (t: Throwable) {
            isCapturing.set(false)
            runCatching { record.release() }
            audioRecord = null
            CrashLogger.logError("InternalAudioCapturer", "startRecording failed", t)
            false
        }
    }

    private fun writePcmLoop(record: AudioRecord, bufferSize: Int) {
        if (pcmOutputFile.exists()) pcmOutputFile.delete()
        val buffer = ByteArray(bufferSize)
        var outStream: BufferedOutputStream? = null
        try {
            outStream = BufferedOutputStream(FileOutputStream(pcmOutputFile), bufferSize * 2)
            while (isCapturing.get()) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0 && !isPaused.get()) {
                    outStream.write(buffer, 0, read)
                }
            }
            outStream.flush()
        } catch (t: Throwable) {
            CrashLogger.logError("InternalAudioCapturer", "PCM read loop interrupted", t)
        } finally {
            runCatching { outStream?.close() }
        }
    }

    fun setPaused(paused: Boolean) {
        isPaused.set(paused)
    }

    fun stop(): File? {
        if (!isCapturing.getAndSet(false)) {
            return pcmOutputFile.takeIf { it.exists() && it.length() > 0L }
        }
        val rec = audioRecord
        audioRecord = null
        if (rec != null) {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        runCatching { readerThread?.join(1500L) }
        readerThread = null
        return pcmOutputFile.takeIf { it.exists() && it.length() > 0L }
    }

    fun releaseAndDelete() {
        stop()
        runCatching { pcmOutputFile.delete() }
    }
}

object GameAudioPostProcessor {

    suspend fun encodePcmToM4a(pcmFile: File, outM4a: File): Boolean = withContext(Dispatchers.IO) {
        if (!pcmFile.exists() || pcmFile.length() <= 0L) return@withContext false
        if (outM4a.exists()) outM4a.delete()

        val args = arrayOf(
            "-y",
            "-f", "s16le",
            "-ar", InternalAudioCapturer.SAMPLE_RATE.toString(),
            "-ac", "2",
            "-i", pcmFile.absolutePath,
            "-c:a", "aac",
            "-b:a", "192k",
            outM4a.absolutePath
        )
        val session = FFmpegKit.executeWithArguments(args)
        val ok = ReturnCode.isSuccess(session.returnCode) && outM4a.exists() && outM4a.length() > 0L
        if (!ok) {
            CrashLogger.logFfmpegFailure(
                operation = "EncodeGameAudioM4a",
                command = args.joinToString(" "),
                returnCode = session.returnCode?.toString(),
                logs = session.allLogsAsString ?: ""
            )
        }
        ok
    }

    suspend fun muxVideoWithGameAudio(
        videoFile: File,
        gameAudioM4a: File?,
        destFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || videoFile.length() <= 0L) return@withContext false
        if (destFile.exists()) destFile.delete()

        val args = if (gameAudioM4a != null && gameAudioM4a.exists() && gameAudioM4a.length() > 0L) {
            arrayOf(
                "-y",
                "-i", videoFile.absolutePath,
                "-i", gameAudioM4a.absolutePath,
                "-map", "0:v:0",
                "-map", "1:a:0",
                "-c:v", "copy",
                "-c:a", "aac",
                "-b:a", "192k",
                "-shortest",
                destFile.absolutePath
            )
        } else {
            arrayOf(
                "-y",
                "-i", videoFile.absolutePath,
                "-c", "copy",
                destFile.absolutePath
            )
        }

        val session = FFmpegKit.executeWithArguments(args)
        val ok = ReturnCode.isSuccess(session.returnCode) && destFile.exists() && destFile.length() > 0L
        if (!ok) {
            CrashLogger.logFfmpegFailure(
                operation = "MuxVideoWithGameAudio",
                command = args.joinToString(" "),
                returnCode = session.returnCode?.toString(),
                logs = session.allLogsAsString ?: ""
            )
        }
        ok
    }
}
