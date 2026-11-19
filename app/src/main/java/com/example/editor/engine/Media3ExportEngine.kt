package com.example.editor.engine

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.example.data.VideoFormat
import com.example.diagnostics.CrashLogger
import com.example.editor.model.ColorFilterPreset
import com.example.editor.model.CropMode
import com.example.editor.model.EditorExportConfig
import com.example.editor.model.EditorProject
import com.example.editor.model.TransitionType
import com.example.editor.model.UpscaleTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Hardware-accelerated Media3 Transformer exporter.
 * Used to compose MP4 video + Separate Audio (.m4a) / BGM / voiceover tracks
 * rapidly via Android hardware codecs without running software FFmpeg.
 */
@OptIn(UnstableApi::class)
object Media3ExportEngine {

    fun canExportWithMedia3(project: EditorProject, config: EditorExportConfig): Boolean {
        if (project.videoClips.isEmpty()) return false
        if (config.format == VideoFormat.MKV) return false
        if (project.overlays.isNotEmpty() || project.stickers.isNotEmpty()) return false
        if (project.transitions.values.any { it.type != TransitionType.NONE }) return false

        val hasUnsupportedVideoFx = project.videoClips.any { clip ->
            clip.isReversed ||
                clip.isFreezeFrame ||
                clip.chromaKey.enabled ||
                clip.blurBackground ||
                clip.filterPreset != ColorFilterPreset.NONE ||
                kotlin.math.abs(clip.brightness) > 0.01f ||
                kotlin.math.abs(clip.contrast - 1f) > 0.01f ||
                kotlin.math.abs(clip.saturation - 1f) > 0.01f ||
                clip.cropMode != CropMode.FULL ||
                clip.keyframes.isNotEmpty() ||
                kotlin.math.abs(clip.speed - 1f) > 0.01f ||
                clip.upscaleTarget != UpscaleTarget.OFF ||
                clip.fadeInMs > 0L ||
                clip.fadeOutMs > 0L
        }
        if (hasUnsupportedVideoFx) return false

        val activeAudio = project.audioClips.filter { !it.isMuted && it.effectiveVolume > 0.01f }
        val hasUnsupportedAudioFx = activeAudio.any { audio ->
            audio.timelineStartMs > 250L ||
                audio.fadeInMs > 0L ||
                audio.fadeOutMs > 0L ||
                kotlin.math.abs(audio.effectiveVolume - 1f) > 0.15f
        }
        return !hasUnsupportedAudioFx
    }

    suspend fun exportComposition(
        context: Context,
        project: EditorProject,
        config: EditorExportConfig,
        onProgress: (Float, String) -> Unit
    ): ExportRenderResult? = withContext(Dispatchers.IO) {
        val (targetW, targetH) = config.resolveDimensions(project.aspectRatio, project.highestUpscaleTarget)
        val evenW = (targetW - (targetW % 2)).coerceAtLeast(320)
        val evenH = (targetH - (targetH % 2)).coerceAtLeast(240)

        val tempOutDir = File(context.cacheDir, "editor_exports").apply { if (!exists()) mkdirs() }
        val outFileName = "clotch_edit_${System.currentTimeMillis()}.mp4"
        val tempOutFile = File(tempOutDir, outFileName)
        runCatching { tempOutFile.delete() }

        onProgress(0.08f, "Hardware merging MP4 + M4A via Media3...")

        val composition = runCatching {
            buildComposition(project, evenW, evenH)
        }.onFailure { t ->
            CrashLogger.logError("Media3ExportEngine.Build", "Unable to build Media3 Composition", t)
        }.getOrNull() ?: return@withContext null

        val transformError = runTransformerOnMainLooper(
            context = context,
            composition = composition,
            outputFile = tempOutFile,
            onProgress = onProgress
        )

        if (transformError != null || !tempOutFile.exists() || tempOutFile.length() <= 0L) {
            if (transformError != null) {
                CrashLogger.logError("Media3ExportEngine.Run", "Media3 Transformer failed; falling back if needed", transformError)
            }
            runCatching { tempOutFile.delete() }
            return@withContext null
        }

        onProgress(0.96f, "Saving to Movies/Clotch...")
        val savedUri = FFmpegEditorEngine.publishExportToMoviesClotchPublic(
            context = context,
            sourceFile = tempOutFile,
            fileName = outFileName,
            mimeType = VideoFormat.MP4.mimeType
        )
        if (savedUri == null) {
            runCatching { tempOutFile.delete() }
            return@withContext ExportRenderResult.Failure(
                "Failed to save exported MP4 to Movies/Clotch",
                CrashLogger.getLogDisplayPath()
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { tempOutFile.delete() }
        }
        onProgress(1.0f, "Export complete")
        ExportRenderResult.Success(savedUri, outFileName)
    }

    private fun buildComposition(
        project: EditorProject,
        evenW: Int,
        evenH: Int
    ): Composition {
        val videoItems = project.videoClips.map { clip ->
            val mediaBuilder = MediaItem.Builder().setUri(clip.sourceUri)
            if (clip.trimStartMs > 0L || clip.trimEndMs < clip.sourceDurationMs - 50L) {
                mediaBuilder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.trimStartMs.coerceAtLeast(0L))
                        .setEndPositionMs(clip.trimEndMs.coerceAtLeast(clip.trimStartMs + 250L))
                        .build()
                )
            }
            val effects = buildList<Effect> {
                if (clip.rotationDegrees != 0) {
                    add(
                        ScaleAndRotateTransformation.Builder()
                            .setRotationDegrees(clip.rotationDegrees.toFloat())
                            .build()
                    )
                }
                add(
                    Presentation.createForWidthAndHeight(
                        evenW,
                        evenH,
                        Presentation.LAYOUT_SCALE_TO_FIT
                    )
                )
            }
            val muteClipAudio = clip.isMuted || clip.audioDetached || clip.effectiveVolume <= 0.01f
            EditedMediaItem.Builder(mediaBuilder.build())
                .setRemoveAudio(muteClipAudio)
                .setEffects(androidx.media3.transformer.Effects(emptyList(), effects))
                .build()
        }

        val sequences = mutableListOf<EditedMediaItemSequence>()
        sequences.add(EditedMediaItemSequence.Builder(videoItems).build())

        val activeAudioClips = project.audioClips.filter { !it.isMuted && it.effectiveVolume > 0.01f }
        activeAudioClips.forEach { audio ->
            val mediaBuilder = MediaItem.Builder().setUri(audio.sourceUri)
            if (audio.trimStartMs > 0L || audio.trimEndMs < audio.sourceDurationMs - 50L) {
                mediaBuilder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(audio.trimStartMs.coerceAtLeast(0L))
                        .setEndPositionMs(audio.trimEndMs.coerceAtLeast(audio.trimStartMs + 250L))
                        .build()
                )
            }
            val audioEdited = EditedMediaItem.Builder(mediaBuilder.build())
                .setRemoveVideo(true)
                .build()
            sequences.add(EditedMediaItemSequence.Builder(listOf(audioEdited)).build())
        }

        return Composition.Builder(sequences).build()
    }

    private suspend fun runTransformerOnMainLooper(
        context: Context,
        composition: Composition,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ): Throwable? = suspendCancellableCoroutine { cont ->
        val mainHandler = Handler(Looper.getMainLooper())
        var transformer: Transformer? = null
        var progressRunnable: Runnable? = null

        fun stopPolling() {
            progressRunnable?.let { mainHandler.removeCallbacks(it) }
            progressRunnable = null
        }

        mainHandler.post {
            try {
                val instance = Transformer.Builder(context.applicationContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            stopPolling()
                            if (cont.isActive) {
                                cont.resume(null)
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            stopPolling()
                            if (cont.isActive) {
                                cont.resume(exportException)
                            }
                        }
                    })
                    .build()
                transformer = instance

                val progressHolder = ProgressHolder()
                val poller = object : Runnable {
                    override fun run() {
                        val state = runCatching { instance.getProgress(progressHolder) }
                            .getOrDefault(Transformer.PROGRESS_STATE_NOT_STARTED)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            val pct = (progressHolder.progress / 100f).coerceIn(0f, 1f)
                            onProgress(
                                (0.12f + pct * 0.82f).coerceIn(0.12f, 0.94f),
                                "Hardware exporting ${(pct * 100).toInt()}%"
                            )
                        }
                        if (state != Transformer.PROGRESS_STATE_NOT_STARTED && cont.isActive) {
                            mainHandler.postDelayed(this, 200L)
                        }
                    }
                }
                progressRunnable = poller

                instance.start(composition, outputFile.absolutePath)
                mainHandler.postDelayed(poller, 150L)
            } catch (t: Throwable) {
                stopPolling()
                if (cont.isActive) {
                    cont.resume(t)
                }
            }
        }

        cont.invokeOnCancellation {
            mainHandler.post {
                stopPolling()
                runCatching { transformer?.cancel() }
            }
        }
    }
}
