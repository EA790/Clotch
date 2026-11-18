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
        if (ok) return@withContext true

        CrashLogger.logFfmpegFailure(
            operation = "MuxVideoWithGameAudio",
            command = args.joinToString(" "),
            returnCode = session.returnCode?.toString(),
            logs = session.allLogsAsString ?: ""
        )

        // Native MediaMuxer fallback if FFmpeg failed
        muxViaAndroidMediaMuxer(videoFile, gameAudioM4a, destFile)
    }

    private fun muxViaAndroidMediaMuxer(
        videoFile: File,
        audioFile: File?,
        destFile: File
    ): Boolean {
        val videoExtractor = android.media.MediaExtractor()
        val audioExtractor = android.media.MediaExtractor()
        var muxer: android.media.MediaMuxer? = null
        return try {
            if (destFile.exists()) destFile.delete()
            videoExtractor.setDataSource(videoFile.absolutePath)
            var videoTrackSrc = -1
            var videoFormat: android.media.MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val fmt = videoExtractor.getTrackFormat(i)
                val mime = fmt.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    videoTrackSrc = i
                    videoFormat = fmt
                    break
                }
            }
            if (videoTrackSrc < 0 || videoFormat == null) return false

            var audioTrackSrc = -1
            var audioFormat: android.media.MediaFormat? = null
            if (audioFile != null && audioFile.exists() && audioFile.length() > 0L) {
                runCatching {
                    audioExtractor.setDataSource(audioFile.absolutePath)
                    for (i in 0 until audioExtractor.trackCount) {
                        val fmt = audioExtractor.getTrackFormat(i)
                        val mime = fmt.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                        if (mime.startsWith("audio/")) {
                            audioTrackSrc = i
                            audioFormat = fmt
                            break
                        }
                    }
                }
            }

            muxer = android.media.MediaMuxer(
                destFile.absolutePath,
                android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            val dstVideoTrack = muxer.addTrack(videoFormat)
            val dstAudioTrack = if (audioTrackSrc >= 0 && audioFormat != null) {
                runCatching { muxer.addTrack(audioFormat!!) }.getOrDefault(-1)
            } else {
                -1
            }
            muxer.start()

            val buf = java.nio.ByteBuffer.allocate(512 * 1024)
            val info = android.media.MediaCodec.BufferInfo()

            videoExtractor.selectTrack(videoTrackSrc)
            while (true) {
                info.offset = 0
                info.size = videoExtractor.readSampleData(buf, 0)
                if (info.size < 0) break
                info.presentationTimeUs = videoExtractor.sampleTime
                info.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(dstVideoTrack, buf, info)
                videoExtractor.advance()
            }

            if (dstAudioTrack >= 0 && audioTrackSrc >= 0) {
                audioExtractor.selectTrack(audioTrackSrc)
                while (true) {
                    info.offset = 0
                    info.size = audioExtractor.readSampleData(buf, 0)
                    if (info.size < 0) break
                    info.presentationTimeUs = audioExtractor.sampleTime
                    info.flags = audioExtractor.sampleFlags
                    muxer.writeSampleData(dstAudioTrack, buf, info)
                    audioExtractor.advance()
                }
            }
            muxer.stop()
            destFile.exists() && destFile.length() > 0L
        } catch (t: Throwable) {
            CrashLogger.logError("GameAudioPostProcessor.MediaMuxer", "Native mux fallback failed", t)
            runCatching { destFile.delete() }
            false
        } finally {
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }
}
