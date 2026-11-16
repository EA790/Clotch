package com.example.editor.engine

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import com.example.diagnostics.CrashLogger
import java.io.File

class VoiceoverRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun startRecording(): File? {
        stopAndReleaseInternal()
        return try {
            val dir = File(context.cacheDir, "editor_voiceover")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "voiceover_${System.currentTimeMillis()}.m4a")
            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(192_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = mediaRecorder
            outputFile = file
            file
        } catch (t: Throwable) {
            CrashLogger.logError("VoiceoverRecorder", "Failed to start voiceover recording", t)
            stopAndReleaseInternal()
            null
        }
    }

    data class RecordedVoiceover(
        val uri: Uri,
        val file: File,
        val durationMs: Long
    )

    fun stopRecording(): RecordedVoiceover? {
        val target = outputFile
        try {
            recorder?.stop()
        } catch (t: Throwable) {
            CrashLogger.logError("VoiceoverRecorder", "Failed cleanly stopping voiceover MediaRecorder", t)
        } finally {
            stopAndReleaseInternal()
        }
        if (target == null || !target.exists() || target.length() <= 0L) {
            return null
        }
        val durationMs = probeDurationMs(target).coerceAtLeast(500L)
        return RecordedVoiceover(
            uri = Uri.fromFile(target),
            file = target,
            durationMs = durationMs
        )
    }

    fun cancelRecording() {
        val target = outputFile
        stopAndReleaseInternal()
        runCatching { target?.delete() }
    }

    private fun probeDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Throwable) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun stopAndReleaseInternal() {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        outputFile = null
    }
}
