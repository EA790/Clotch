package com.example.recording

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.diagnostics.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
                if (read < 0) {
                    break
                }
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

/**
 * Reliable PCM microphone capturer producing 44.1kHz stereo s16le PCM.
 * Avoids opening a second MediaRecorder pointing at a MediaStore ParcelFileDescriptor,
 * which frequently fails/crashes across Android 10-15 and emulators.
 */
class MicPcmCapturer(
    private val pcmOutputFile: File
) {
    private var audioRecord: AudioRecord? = null
    private var readerThread: Thread? = null
    private val isCapturing = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private var capturedChannels: Int = 1

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isCapturing.get()) return true

        val candidates = listOf(
            Pair(AudioFormat.CHANNEL_IN_STEREO, 2),
            Pair(AudioFormat.CHANNEL_IN_MONO, 1)
        )

        for ((channelMask, chCount) in candidates) {
            val minBuf = AudioRecord.getMinBufferSize(
                InternalAudioCapturer.SAMPLE_RATE,
                channelMask,
                InternalAudioCapturer.AUDIO_FORMAT
            )
            if (minBuf <= 0) continue

            val bufferSize = maxOf(minBuf * 2, 16_384)
            val sources = listOf(
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.DEFAULT
            )
            for (src in sources) {
                val record = try {
                    AudioRecord(
                        src,
                        InternalAudioCapturer.SAMPLE_RATE,
                        channelMask,
                        InternalAudioCapturer.AUDIO_FORMAT,
                        bufferSize
                    )
                } catch (_: Throwable) {
                    null
                }
                if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                    capturedChannels = chCount
                    audioRecord = record
                    isPaused.set(false)
                    isCapturing.set(true)
                    return try {
                        record.startRecording()
                        readerThread = Thread({
                            writeStereoPcmLoop(record, bufferSize, chCount)
                        }, "ClotchMicPcmThread").apply { start() }
                        true
                    } catch (t: Throwable) {
                        isCapturing.set(false)
                        runCatching { record.release() }
                        audioRecord = null
                        CrashLogger.logError("MicPcmCapturer", "startRecording failed", t)
                        false
                    }
                } else {
                    runCatching { record?.release() }
                }
            }
        }
        return false
    }

    private fun writeStereoPcmLoop(record: AudioRecord, bufferSize: Int, sourceChannels: Int) {
        if (pcmOutputFile.exists()) pcmOutputFile.delete()
        val buffer = ByteArray(bufferSize)
        val stereoBuf = if (sourceChannels == 1) ByteArray(bufferSize * 2) else null
        var outStream: BufferedOutputStream? = null
        try {
            outStream = BufferedOutputStream(FileOutputStream(pcmOutputFile), bufferSize * 2)
            while (isCapturing.get()) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) break
                if (read > 0 && !isPaused.get()) {
                    if (stereoBuf != null) {
                        // Duplicate mono sample to L+R so mixed stereo math stays sample-accurate
                        var outIdx = 0
                        var i = 0
                        while (i + 1 < read) {
                            val lo = buffer[i]
                            val hi = buffer[i + 1]
                            stereoBuf[outIdx++] = lo
                            stereoBuf[outIdx++] = hi
                            stereoBuf[outIdx++] = lo
                            stereoBuf[outIdx++] = hi
                            i += 2
                        }
                        outStream.write(stereoBuf, 0, outIdx)
                    } else {
                        outStream.write(buffer, 0, read)
                    }
                }
            }
            outStream.flush()
        } catch (t: Throwable) {
            CrashLogger.logError("MicPcmCapturer", "Mic PCM read loop interrupted", t)
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

    fun verifyMp4HasVideoTrack(file: File): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var hasVideo = false
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    hasVideo = true
                    break
                }
            }
            hasVideo
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { extractor.release() }
        }
    }

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
        val ffmpegOk = runCatching {
            val session = FFmpegKit.executeWithArguments(args)
            val ok = ReturnCode.isSuccess(session.returnCode) && outM4a.exists() && outM4a.length() > 0L
            if (!ok) {
                CrashLogger.logFfmpegFailure(
                    operation = "EncodeAudioM4a",
                    command = args.joinToString(" "),
                    returnCode = session.returnCode?.toString(),
                    logs = session.allLogsAsString ?: ""
                )
            }
            ok
        }.onFailure { t ->
            CrashLogger.logError("GameAudioPostProcessor.Encode", "FFmpegKit encode threw", t)
        }.getOrDefault(false)

        if (ffmpegOk) return@withContext true

        // Pure-Android MediaCodec + MediaMuxer fallback when FFmpegKit fails
        runCatching { outM4a.delete() }
        encodeStereoPcmViaMediaCodec(pcmFile, outM4a)
    }

    suspend fun mixPcmTracksToM4a(
        gamePcmFile: File?,
        micPcmFile: File?,
        outM4a: File
    ): Boolean = withContext(Dispatchers.IO) {
        val hasGame = gamePcmFile != null && gamePcmFile.exists() && gamePcmFile.length() > 0L
        val hasMic = micPcmFile != null && micPcmFile.exists() && micPcmFile.length() > 0L

        when {
            hasGame && !hasMic -> encodePcmToM4a(gamePcmFile!!, outM4a)
            !hasGame && hasMic -> encodePcmToM4a(micPcmFile!!, outM4a)
            !hasGame && !hasMic -> false
            else -> {
                if (outM4a.exists()) outM4a.delete()
                val args = arrayOf(
                    "-y",
                    "-f", "s16le",
                    "-ar", InternalAudioCapturer.SAMPLE_RATE.toString(),
                    "-ac", "2",
                    "-i", gamePcmFile!!.absolutePath,
                    "-f", "s16le",
                    "-ar", InternalAudioCapturer.SAMPLE_RATE.toString(),
                    "-ac", "2",
                    "-i", micPcmFile!!.absolutePath,
                    "-filter_complex", "[0:a][1:a]amix=inputs=2:duration=longest:dropout_transition=0:normalize=0[aout]",
                    "-map", "[aout]",
                    "-c:a", "aac",
                    "-b:a", "192k",
                    outM4a.absolutePath
                )
                val ffmpegOk = runCatching {
                    val session = FFmpegKit.executeWithArguments(args)
                    val ok = ReturnCode.isSuccess(session.returnCode) && outM4a.exists() && outM4a.length() > 0L
                    if (!ok) {
                        CrashLogger.logFfmpegFailure(
                            operation = "MixNormalAudioM4a",
                            command = args.joinToString(" "),
                            returnCode = session.returnCode?.toString(),
                            logs = session.allLogsAsString ?: ""
                        )
                    }
                    ok
                }.onFailure { t ->
                    CrashLogger.logError("GameAudioPostProcessor.Mix", "FFmpegKit mix threw", t)
                }.getOrDefault(false)

                if (ffmpegOk) return@withContext true

                // Kotlin clamped 16-bit PCM summer + MediaCodec fallback
                runCatching { outM4a.delete() }
                val mixedPcm = File(outM4a.parentFile ?: gamePcmFile.parentFile, "mixed_${System.currentTimeMillis()}.pcm")
                return@withContext try {
                    if (mixTwoStereoPcmFiles(gamePcmFile, micPcmFile, mixedPcm)) {
                        encodeStereoPcmViaMediaCodec(mixedPcm, outM4a)
                    } else {
                        encodePcmToM4a(gamePcmFile, outM4a)
                    }
                } finally {
                    runCatching { mixedPcm.delete() }
                }
            }
        }
    }

    suspend fun muxVideoWithAudio(
        videoFile: File,
        audioM4a: File?,
        destFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || videoFile.length() <= 0L) return@withContext false
        if (destFile.exists()) destFile.delete()

        val hasAudio = audioM4a != null && audioM4a.exists() && audioM4a.length() > 0L
        val args = if (hasAudio) {
            arrayOf(
                "-y",
                "-i", videoFile.absolutePath,
                "-i", audioM4a!!.absolutePath,
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

        val ffmpegOk = runCatching {
            val session = FFmpegKit.executeWithArguments(args)
            val ok = ReturnCode.isSuccess(session.returnCode) && destFile.exists() && destFile.length() > 0L
            if (!ok) {
                CrashLogger.logFfmpegFailure(
                    operation = "MuxVideoWithAudio",
                    command = args.joinToString(" "),
                    returnCode = session.returnCode?.toString(),
                    logs = session.allLogsAsString ?: ""
                )
            }
            ok
        }.onFailure { t ->
            CrashLogger.logError("GameAudioPostProcessor.Mux", "FFmpegKit mux threw", t)
        }.getOrDefault(false)

        if (ffmpegOk) return@withContext true

        // Native MediaMuxer fallback if FFmpeg failed
        muxViaAndroidMediaMuxer(videoFile, audioM4a, destFile)
    }

    private fun mixTwoStereoPcmFiles(firstPcm: File, secondPcm: File, destPcm: File): Boolean {
        return try {
            val chunkBytes = 8192
            val bufA = ByteArray(chunkBytes)
            val bufB = ByteArray(chunkBytes)
            val outBuf = ByteArray(chunkBytes)
            BufferedInputStream(FileInputStream(firstPcm)).use { inA ->
                BufferedInputStream(FileInputStream(secondPcm)).use { inB ->
                    BufferedOutputStream(FileOutputStream(destPcm)).use { out ->
                        while (true) {
                            val readA = inA.read(bufA)
                            val readB = inB.read(bufB)
                            if (readA <= 0 && readB <= 0) break
                            val maxBytes = maxOf(readA.coerceAtLeast(0), readB.coerceAtLeast(0))
                            val evenBytes = maxBytes and 1.inv()
                            var i = 0
                            while (i < evenBytes) {
                                val sampleA = if (i + 1 < readA) {
                                    ((bufA[i + 1].toInt() shl 8) or (bufA[i].toInt() and 0xFF)).toShort().toInt()
                                } else {
                                    0
                                }
                                val sampleB = if (i + 1 < readB) {
                                    ((bufB[i + 1].toInt() shl 8) or (bufB[i].toInt() and 0xFF)).toShort().toInt()
                                } else {
                                    0
                                }
                                val summed = (sampleA + sampleB).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                outBuf[i] = (summed and 0xFF).toByte()
                                outBuf[i + 1] = ((summed ushr 8) and 0xFF).toByte()
                                i += 2
                            }
                            if (evenBytes > 0) {
                                out.write(outBuf, 0, evenBytes)
                            }
                        }
                        out.flush()
                    }
                }
            }
            destPcm.exists() && destPcm.length() > 0L
        } catch (t: Throwable) {
            CrashLogger.logError("GameAudioPostProcessor.PcmMix", "Software PCM mix failed", t)
            runCatching { destPcm.delete() }
            false
        }
    }

    private fun encodeStereoPcmViaMediaCodec(pcmFile: File, outM4a: File): Boolean {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputStream: BufferedInputStream? = null
        return try {
            val sampleRate = InternalAudioCapturer.SAMPLE_RATE
            val channels = 2
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(outM4a.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false

            inputStream = BufferedInputStream(FileInputStream(pcmFile))
            val readBuf = ByteArray(4096)
            val bufferInfo = MediaCodec.BufferInfo()
            var totalSamplesWritten = 0L
            var sawInputEos = false
            var sawOutputEos = false
            var idleOutputSpins = 0

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            inBuf.clear()
                            val bytesRead = inputStream.read(readBuf, 0, minOf(readBuf.size, inBuf.remaining()))
                            if (bytesRead <= 0) {
                                codec.queueInputBuffer(
                                    inIdx,
                                    0,
                                    0,
                                    totalSamplesWritten * 1_000_000L / sampleRate,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                sawInputEos = true
                            } else {
                                inBuf.order(ByteOrder.LITTLE_ENDIAN)
                                inBuf.put(readBuf, 0, bytesRead)
                                val ptsUs = totalSamplesWritten * 1_000_000L / sampleRate
                                totalSamplesWritten += bytesRead / (channels * 2)
                                codec.queueInputBuffer(inIdx, 0, bytesRead, ptsUs, 0)
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        idleOutputSpins = 0
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        idleOutputSpins = 0
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                            bufferInfo.size > 0 && muxerStarted
                        ) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                        }
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEos = true
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (sawInputEos) {
                            idleOutputSpins++
                            if (idleOutputSpins > 100) {
                                break
                            }
                        }
                    }
                }
            }

            if (muxerStarted) {
                muxer.stop()
            }
            outM4a.exists() && outM4a.length() > 0L
        } catch (t: Throwable) {
            CrashLogger.logError("GameAudioPostProcessor.MediaCodec", "Native AAC encode failed", t)
            runCatching { outM4a.delete() }
            false
        } finally {
            runCatching { inputStream?.close() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { muxer?.release() }
        }
    }

    private fun muxViaAndroidMediaMuxer(
        videoFile: File,
        audioFile: File?,
        destFile: File
    ): Boolean {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            if (destFile.exists()) destFile.delete()
            videoExtractor.setDataSource(videoFile.absolutePath)
            var videoTrackSrc = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val fmt = videoExtractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    videoTrackSrc = i
                    videoFormat = fmt
                    break
                }
            }
            if (videoTrackSrc < 0 || videoFormat == null) return false

            var audioTrackSrc = -1
            var audioFormat: MediaFormat? = null
            if (audioFile != null && audioFile.exists() && audioFile.length() > 0L) {
                runCatching {
                    audioExtractor.setDataSource(audioFile.absolutePath)
                    for (i in 0 until audioExtractor.trackCount) {
                        val fmt = audioExtractor.getTrackFormat(i)
                        val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                        if (mime.startsWith("audio/")) {
                            audioTrackSrc = i
                            audioFormat = fmt
                            break
                        }
                    }
                }
            }

            muxer = MediaMuxer(
                destFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            val dstVideoTrack = muxer.addTrack(videoFormat)
            val dstAudioTrack = if (audioTrackSrc >= 0 && audioFormat != null) {
                runCatching { muxer.addTrack(audioFormat!!) }.getOrDefault(-1)
            } else {
                -1
            }
            muxer.start()

            val buf = ByteBuffer.allocate(512 * 1024)
            val info = MediaCodec.BufferInfo()

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
