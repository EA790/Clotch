package com.example.editor.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.data.VideoFormat
import com.example.diagnostics.CrashLogger
import com.example.editor.model.EditorExportConfig
import com.example.editor.model.EditorProject
import com.example.editor.model.TimelineVideoClip
import com.example.editor.model.TransitionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.resume

data class ProbedMediaInfo(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val hasAudio: Boolean,
    val displayName: String
)

sealed interface ExtractAudioResult {
    data class Success(val uri: Uri, val file: File, val durationMs: Long) : ExtractAudioResult
    data class Failure(val message: String) : ExtractAudioResult
}

sealed interface ExportRenderResult {
    data class Success(val uri: Uri, val fileName: String) : ExportRenderResult
    data class Failure(val message: String, val logPath: String) : ExportRenderResult
}

data class FreezeFrameMediaResult(
    val mp4Uri: Uri,
    val pngPath: String,
    val width: Int,
    val height: Int
)

object FFmpegEditorEngine {

    fun stageUriForExternal(context: Context, uri: Uri): File? =
        stageUriToLocalFile(context, uri)

    suspend fun probeMedia(context: Context, uri: Uri, fallbackName: String = "Clip"): ProbedMediaInfo =
        withContext(Dispatchers.IO) {
            val resolvedName = resolveDisplayName(context, uri) ?: fallbackName
            var durationMs = 0L
            var width = 1920
            var height = 1080
            var hasAudio = false

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                if (rawW > 0 && rawH > 0) {
                    if (rotation == 90 || rotation == 270) {
                        width = rawH
                        height = rawW
                    } else {
                        width = rawW
                        height = rawH
                    }
                }
                val audioFlag = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                hasAudio = audioFlag.equals("yes", ignoreCase = true)
            } catch (t: Throwable) {
                CrashLogger.logError("FFmpegEditorEngine.probeMedia", "Retriever probe failed for $uri", t)
            } finally {
                runCatching { retriever.release() }
            }

            if (durationMs <= 0L) {
                val staged = stageUriToLocalFile(context, uri)
                if (staged != null) {
                    val info = runCatching { FFprobeKit.getMediaInformation(staged.absolutePath) }
                        .getOrNull()?.mediaInformation
                    val probeDurSec = info?.duration?.toDoubleOrNull()
                    if (probeDurSec != null && probeDurSec > 0.0) {
                        durationMs = (probeDurSec * 1000.0).toLong()
                    }
                    val streams = info?.streams.orEmpty()
                    if (!hasAudio) {
                        hasAudio = streams.any { it.type.equals("audio", ignoreCase = true) }
                    }
                }
            }

            ProbedMediaInfo(
                durationMs = durationMs.coerceAtLeast(1000L),
                width = width,
                height = height,
                hasAudio = hasAudio,
                displayName = resolvedName
            )
        }

    suspend fun extractAudioToM4a(
        context: Context,
        clip: TimelineVideoClip
    ): ExtractAudioResult = withContext(Dispatchers.IO) {
        val inputFile = stageUriToLocalFile(context, clip.sourceUri)
            ?: return@withContext ExtractAudioResult.Failure("Unable to access source video file")

        if (!probeHasAudioStream(inputFile.absolutePath)) {
            return@withContext ExtractAudioResult.Failure("This clip has no audio stream to extract")
        }

        val outDir = File(context.cacheDir, "editor_audio").apply { if (!exists()) mkdirs() }
        val outFile = File(outDir, "extracted_${clip.id}_${System.currentTimeMillis()}.m4a")
        val startSec = formatSec(clip.trimStartMs)
        val durSec = formatSec(clip.sourceSpanMs)

        val args = arrayOf(
            "-y",
            "-ss", startSec,
            "-t", durSec,
            "-i", inputFile.absolutePath,
            "-vn",
            "-c:a", "aac",
            "-b:a", "192k",
            outFile.absolutePath
        )

        val session = executeFfmpeg(args)
        if (ReturnCode.isSuccess(session.returnCode) && outFile.exists() && outFile.length() > 0L) {
            ExtractAudioResult.Success(
                uri = Uri.fromFile(outFile),
                file = outFile,
                durationMs = clip.sourceSpanMs
            )
        } else {
            val cmdStr = args.joinToString(" ")
            CrashLogger.logFfmpegFailure(
                operation = "ExtractAudio",
                command = cmdStr,
                returnCode = session.returnCode?.toString(),
                logs = session.allLogsAsString ?: ""
            )
            runCatching { outFile.delete() }
            ExtractAudioResult.Failure(
                "FFmpeg audio extraction failed. Full logs saved to ${CrashLogger.getLogDisplayPath()}"
            )
        }
    }

    suspend fun createFreezeFrameMedia(
        context: Context,
        clip: TimelineVideoClip,
        sourceTimeMs: Long,
        durationMs: Long = 3000L
    ): FreezeFrameMediaResult? = withContext(Dispatchers.IO) {
        val outDir = File(context.cacheDir, "editor_fx").apply { if (!exists()) mkdirs() }
        val pngFile = File(outDir, "freeze_${System.currentTimeMillis()}.png")
        val mp4File = File(outDir, "freeze_${System.currentTimeMillis()}.mp4")
        val retriever = MediaMetadataRetriever()
        var w = clip.width
        var h = clip.height

        try {
            retriever.setDataSource(context, clip.sourceUri)
            val bmp = retriever.getFrameAtTime(
                sourceTimeMs.coerceAtLeast(0L) * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: return@withContext null
            w = bmp.width
            h = bmp.height
            pngFile.outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            bmp.recycle()
        } catch (t: Throwable) {
            CrashLogger.logError("CreateFreezeFrame", "Unable to grab frame at ${sourceTimeMs}ms", t)
            return@withContext null
        } finally {
            runCatching { retriever.release() }
        }

        val durSec = formatSec(durationMs)
        val evenW = (w - (w % 2)).coerceAtLeast(320)
        val evenH = (h - (h % 2)).coerceAtLeast(240)
        val args = arrayOf(
            "-y",
            "-loop", "1",
            "-t", durSec,
            "-i", pngFile.absolutePath,
            "-vf", "scale=$evenW:$evenH,format=yuv420p",
            "-c:v", "mpeg4",
            "-q:v", "4",
            mp4File.absolutePath
        )
        val session = executeFfmpeg(args)
        if (ReturnCode.isSuccess(session.returnCode) && mp4File.exists() && mp4File.length() > 0L) {
            FreezeFrameMediaResult(Uri.fromFile(mp4File), pngFile.absolutePath, evenW, evenH)
        } else {
            CrashLogger.logFfmpegFailure(
                operation = "CreateFreezeFrame",
                command = args.joinToString(" "),
                returnCode = session.returnCode?.toString(),
                logs = session.allLogsAsString ?: ""
            )
            runCatching { pngFile.delete() }
            runCatching { mp4File.delete() }
            null
        }
    }

    suspend fun exportTimeline(
        context: Context,
        project: EditorProject,
        config: EditorExportConfig,
        chromaProcessor: HybridChromaKeyProcessor = HybridChromaKeyProcessor(),
        onProgress: (Float, String) -> Unit
    ): ExportRenderResult = withContext(Dispatchers.IO) {
        if (project.videoClips.isEmpty()) {
            return@withContext ExportRenderResult.Failure(
                "Add at least one video clip before exporting.",
                CrashLogger.getLogDisplayPath()
            )
        }

        onProgress(0.03f, "Staging timeline media...")
        pruneStaleTempCaches(context)

        val stagedMap = LinkedHashMap<String, File>()
        val clipUris = project.videoClips.map { clip ->
            if (clip.isFreezeFrame && !clip.freezeImagePath.isNullOrBlank()) {
                Uri.fromFile(File(clip.freezeImagePath))
            } else {
                clip.sourceUri
            }
        }
        val allUris = clipUris + project.audioClips.map { it.sourceUri }
        for (uri in allUris) {
            val key = uri.toString()
            if (!stagedMap.containsKey(key)) {
                val file = stageUriToLocalFile(context, uri)
                if (file == null) {
                    val msg = "Failed to stage media URI for FFmpeg: $uri"
                    CrashLogger.logError("ExportTimeline", msg)
                    return@withContext ExportRenderResult.Failure(msg, CrashLogger.getLogDisplayPath())
                }
                stagedMap[key] = file
            }
        }

        val (targetW, targetH) = config.resolveDimensions(project.aspectRatio, project.highestUpscaleTarget)
        val evenW = targetW - (targetW % 2)
        val evenH = targetH - (targetH % 2)
        val targetFps = config.fps.coerceIn(24, 120)
        val totalDurationMs = project.totalTimelineDurationMs.coerceAtLeast(1L)
        val totalDurationSec = formatSec(totalDurationMs)

        // Generate ML Kit Chroma Key matte videos sequentially (never running Moving & Still concurrently)
        val matteInputFiles = mutableMapOf<String, File>()
        val tempCleanupFiles = mutableListOf<File>()

        project.videoClips.forEachIndexed { idx, clip ->
            if (clip.chromaKey.enabled && !clip.isFreezeFrame) {
                onProgress(0.06f, "AI Chroma Key (${clip.chromaKey.mode.label}) clip ${idx + 1}...")
                val staged = stagedMap.getValue(clip.sourceUri.toString())
                val matte = chromaProcessor.generateAlphaMatteVideo(
                    context = context,
                    stagedVideoFile = staged,
                    clip = clip,
                    outWidth = evenW,
                    outHeight = evenH
                ) { p ->
                    onProgress(0.06f + p * 0.14f, "Segmenting clip ${idx + 1} (${(p * 100).toInt()}%)")
                }
                if (matte != null) {
                    matteInputFiles[clip.id] = matte
                    tempCleanupFiles.add(matte)
                }
            }
        }

        // Rasterize Text Overlays, Captions & Stickers to crisp ARGB PNGs
        val overlayFiles = project.overlays.map { ov ->
            OverlayRasterizer.rasterizeTextOverlayToPng(context, ov, evenW, evenH).also {
                tempCleanupFiles.add(it)
            }
        }
        val stickerFiles = project.stickers.map { st ->
            OverlayRasterizer.rasterizeStickerToPng(context, st, evenW).also {
                tempCleanupFiles.add(it)
            }
        }

        // Build ordered FFmpeg inputs
        val regularInputPaths = mutableListOf<String>()
        stagedMap.values.forEach { f ->
            if (!regularInputPaths.contains(f.absolutePath)) regularInputPaths.add(f.absolutePath)
        }
        val matteInputIndexMap = mutableMapOf<String, Int>()
        matteInputFiles.forEach { (clipId, matteFile) ->
            matteInputIndexMap[clipId] = regularInputPaths.size
            regularInputPaths.add(matteFile.absolutePath)
        }
        val pathToInputIndex = regularInputPaths.withIndex().associate { it.value to it.index }
        val audioPresenceMap = regularInputPaths.associateWith { probeHasAudioStream(it) }

        val loopedPngStartIndex = regularInputPaths.size
        val loopedPngPaths = (overlayFiles + stickerFiles).map { it.absolutePath }

        val filterParts = mutableListOf<String>()

        project.videoClips.forEachIndexed { idx, clip ->
            val srcUri = if (clip.isFreezeFrame && !clip.freezeImagePath.isNullOrBlank()) {
                Uri.fromFile(File(clip.freezeImagePath))
            } else {
                clip.sourceUri
            }
            val filePath = stagedMap.getValue(srcUri.toString()).absolutePath
            val inIdx = pathToInputIndex.getValue(filePath)
            val matteIdx = matteInputIndexMap[clip.id]

            filterParts.addAll(
                FFmpegFilterBuilder.buildClipVideoChain(
                    inputIndex = inIdx,
                    clipIndex = idx,
                    clip = clip,
                    evenW = evenW,
                    evenH = evenH,
                    targetFps = targetFps,
                    matteInputIndex = matteIdx
                )
            )
            filterParts.add(
                FFmpegFilterBuilder.buildClipAudioChain(
                    inputIndex = inIdx,
                    clipIndex = idx,
                    clip = clip,
                    hasAudioStream = audioPresenceMap[filePath] == true
                )
            )
        }

        // Chain video clips with optional xfade transitions
        val hasExtraAudio = project.audioClips.any { !it.isMuted && it.effectiveVolume > 0f }
        val baseVideoLabel = if (overlayFiles.isEmpty() && stickerFiles.isEmpty()) "[vout]" else "[vbase_v]"
        val baseAudioLabel = if (hasExtraAudio) "[vbase_a]" else "[aout]"
        val hasAnyTransitions = project.videoClips.dropLast(1).any { clip ->
            val spec = project.transitions[clip.id]
            spec != null && spec.type != TransitionType.NONE
        }

        if (project.videoClips.size == 1) {
            filterParts.add("[v0]null$baseVideoLabel")
            filterParts.add("[va0]anull$baseAudioLabel")
        } else if (!hasAnyTransitions) {
            val concatInputs = StringBuilder()
            project.videoClips.indices.forEach { idx -> concatInputs.append("[v$idx][va$idx]") }
            filterParts.add("${concatInputs}concat=n=${project.videoClips.size}:v=1:a=1$baseVideoLabel$baseAudioLabel")
        } else {
            var curV = "[v0]"
            var curA = "[va0]"
            var timelineCursorMs = project.videoClips[0].effectiveDurationMs
            for (i in 1 until project.videoClips.size) {
                val prevClip = project.videoClips[i - 1]
                val spec = project.transitions[prevClip.id]
                val nextV = "[v$i]"
                val nextA = "[va$i]"
                val outV = if (i == project.videoClips.lastIndex) baseVideoLabel else "[vx$i]"
                val outA = if (i == project.videoClips.lastIndex) baseAudioLabel else "[ax$i]"

                val overlapMs = if (spec != null && spec.type != TransitionType.NONE) {
                    spec.durationMs.coerceIn(150L, (prevClip.effectiveDurationMs / 2).coerceAtLeast(150L))
                } else {
                    0L
                }

                if (overlapMs > 0L && spec != null) {
                    val offsetSec = formatSec((timelineCursorMs - overlapMs).coerceAtLeast(0L))
                    val durSec = formatSec(overlapMs)
                    filterParts.add(
                        "${curV}${nextV}xfade=transition=${spec.type.ffmpegXfadeName}:duration=$durSec:offset=$offsetSec$outV"
                    )
                    filterParts.add("${curA}${nextA}acrossfade=d=$durSec$outA")
                    timelineCursorMs = timelineCursorMs + project.videoClips[i].effectiveDurationMs - overlapMs
                } else {
                    filterParts.add("${curV}${curA}${nextV}${nextA}concat=n=2:v=1:a=1$outV$outA")
                    timelineCursorMs += project.videoClips[i].effectiveDurationMs
                }
                curV = outV
                curA = outA
            }
        }

        // Composite Text Overlays & Stickers over baseVideoLabel -> [vout]
        if (overlayFiles.isNotEmpty() || stickerFiles.isNotEmpty()) {
            var curV = baseVideoLabel
            var step = 0
            val totalOverlayCount = project.overlays.size + project.stickers.size

            project.overlays.forEachIndexed { idx, overlay ->
                val isLast = (step == totalOverlayCount - 1)
                val outLabel = if (isLast) "[vout]" else "[vov$step]"
                val inputIdx = loopedPngStartIndex + idx
                filterParts.addAll(
                    FFmpegFilterBuilder.buildTextOverlayFilter(
                        baseLabel = curV,
                        overlayInputIndex = inputIdx,
                        overlay = overlay,
                        stepIndex = step,
                        outLabel = outLabel
                    )
                )
                curV = outLabel
                step++
            }

            project.stickers.forEachIndexed { idx, sticker ->
                val isLast = (step == totalOverlayCount - 1)
                val outLabel = if (isLast) "[vout]" else "[vov$step]"
                val inputIdx = loopedPngStartIndex + project.overlays.size + idx
                filterParts.add(
                    FFmpegFilterBuilder.buildStickerOverlayFilter(
                        baseLabel = curV,
                        stickerInputIndex = inputIdx,
                        sticker = sticker,
                        stepIndex = step,
                        outLabel = outLabel
                    )
                )
                curV = outLabel
                step++
            }
        }

        // Mix extra audio tracks (Extracted, BGM, Voiceover)
        if (hasExtraAudio) {
            val activeAudioClips = project.audioClips.filter { !it.isMuted && it.effectiveVolume > 0f }
            val mixLabels = mutableListOf("[vbase_a]")
            activeAudioClips.forEachIndexed { aIdx, audio ->
                val filePath = stagedMap.getValue(audio.sourceUri.toString()).absolutePath
                val inIdx = pathToInputIndex.getValue(filePath)
                if (audioPresenceMap[filePath] == true) {
                    val startSec = formatSec(audio.trimStartMs)
                    val endSec = formatSec(audio.trimEndMs.coerceAtLeast(audio.trimStartMs + 250L))
                    val volStr = formatFloat(audio.effectiveVolume)
                    val chain = mutableListOf(
                        "atrim=start=$startSec:end=$endSec",
                        "asetpts=PTS-STARTPTS",
                        "aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo",
                        "volume=$volStr"
                    )
                    if (audio.fadeInMs > 0L) {
                        val fadeInSec = formatSec(audio.fadeInMs.coerceAtMost(audio.effectiveDurationMs / 2))
                        chain.add("afade=t=in:st=0:d=$fadeInSec")
                    }
                    if (audio.fadeOutMs > 0L) {
                        val fadeOutDurMs = audio.fadeOutMs.coerceAtMost(audio.effectiveDurationMs / 2)
                        val fadeOutStartMs = (audio.effectiveDurationMs - fadeOutDurMs).coerceAtLeast(0L)
                        chain.add("afade=t=out:st=${formatSec(fadeOutStartMs)}:d=${formatSec(fadeOutDurMs)}")
                    }
                    if (audio.timelineStartMs > 0L) {
                        chain.add("adelay=${audio.timelineStartMs}|${audio.timelineStartMs}")
                    }
                    val label = "[xa$aIdx]"
                    filterParts.add("[$inIdx:a]${chain.joinToString(",")}$label")
                    mixLabels.add(label)
                }
            }
            if (mixLabels.size > 1) {
                filterParts.add(
                    "${mixLabels.joinToString("")}amix=inputs=${mixLabels.size}:duration=first:dropout_transition=0:normalize=0[aout]"
                )
            } else {
                filterParts.add("[vbase_a]anull[aout]")
            }
        }

        val filterComplex = filterParts.joinToString(";")
        val tempOutDir = File(context.cacheDir, "editor_exports").apply { if (!exists()) mkdirs() }
        val ext = if (config.format == VideoFormat.MKV) "mkv" else "mp4"
        val outFileName = "clotch_edit_${System.currentTimeMillis()}.$ext"
        val tempOutFile = File(tempOutDir, outFileName)

        onProgress(0.22f, "Encoding ${evenW}x${evenH} ${config.format.label}...")

        val codecAttempts = listOf("libopenh264", "mpeg4")
        var lastSession: FFmpegSession? = null
        var lastCommand = ""
        var succeeded = false

        try {
            for (videoCodec in codecAttempts) {
                runCatching { tempOutFile.delete() }
                val args = buildList {
                    add("-y")
                    regularInputPaths.forEach { path ->
                        val isImageInput = path.endsWith(".png", ignoreCase = true)
                        if (isImageInput) {
                            add("-loop")
                            add("1")
                            add("-t")
                            add(totalDurationSec)
                        }
                        add("-i")
                        add(path)
                    }
                    loopedPngPaths.forEach { pngPath ->
                        add("-loop")
                        add("1")
                        add("-t")
                        add(totalDurationSec)
                        add("-i")
                        add(pngPath)
                    }
                    add("-filter_complex")
                    add(filterComplex)
                    add("-map")
                    add("[vout]")
                    add("-map")
                    add("[aout]")
                    add("-c:v")
                    add(videoCodec)
                    add("-b:v")
                    add("${config.bitrateMbps}M")
                    add("-pix_fmt")
                    add("yuv420p")
                    add("-c:a")
                    add("aac")
                    add("-b:a")
                    add("192k")
                    if (ext == "mp4") {
                        add("-movflags")
                        add("+faststart")
                    }
                    add(tempOutFile.absolutePath)
                }.toTypedArray()

                lastCommand = args.joinToString(" ")
                val session = executeFfmpegWithProgress(args, totalDurationMs) { pct ->
                    onProgress((0.24f + pct * 0.72f).coerceIn(0.24f, 0.96f), "Rendering ${(pct * 100).toInt()}%")
                }
                lastSession = session
                if (ReturnCode.isSuccess(session.returnCode) && tempOutFile.exists() && tempOutFile.length() > 0L) {
                    succeeded = true
                    break
                }
            }
        } finally {
            tempCleanupFiles.forEach { runCatching { it.delete() } }
        }

        if (!succeeded) {
            CrashLogger.logFfmpegFailure(
                operation = "ExportTimeline_${config.format.label}",
                command = lastCommand,
                returnCode = lastSession?.returnCode?.toString(),
                logs = lastSession?.allLogsAsString ?: "No FFmpeg session output"
            )
            runCatching { tempOutFile.delete() }
            return@withContext ExportRenderResult.Failure(
                "FFmpeg render failed. Stack & FFmpeg logs saved to ${CrashLogger.getLogDisplayPath()}",
                CrashLogger.getLogDisplayPath()
            )
        }

        onProgress(0.97f, "Saving to Movies/Clotch...")
        val savedUri = publishExportToMoviesClotch(context, tempOutFile, outFileName, config.format.mimeType)
        if (savedUri == null) {
            val msg = "Failed to save rendered file $outFileName to Movies/Clotch"
            CrashLogger.logError("ExportTimeline.Publish", msg)
            runCatching { tempOutFile.delete() }
            return@withContext ExportRenderResult.Failure(msg, CrashLogger.getLogDisplayPath())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { tempOutFile.delete() }
        }
        onProgress(1.0f, "Export complete")
        ExportRenderResult.Success(savedUri, outFileName)
    }

    private suspend fun executeFfmpeg(args: Array<String>): FFmpegSession =
        suspendCancellableCoroutine { cont ->
            val session = FFmpegKit.executeWithArgumentsAsync(args) { completedSession ->
                if (cont.isActive) {
                    cont.resume(completedSession)
                }
            }
            cont.invokeOnCancellation {
                runCatching { session.cancel() }
            }
        }

    private suspend fun executeFfmpegWithProgress(
        args: Array<String>,
        totalDurationMs: Long,
        onPercent: (Float) -> Unit
    ): FFmpegSession = suspendCancellableCoroutine { cont ->
        val session = FFmpegKit.executeWithArgumentsAsync(
            args,
            { completedSession ->
                if (cont.isActive) {
                    cont.resume(completedSession)
                }
            },
            { _ -> },
            { stats ->
                val timeMs = stats.time
                if (totalDurationMs > 0L && timeMs > 0) {
                    val ratio = (timeMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
                    onPercent(ratio)
                }
            }
        )
        cont.invokeOnCancellation {
            runCatching { session.cancel() }
        }
    }

    private fun probeHasAudioStream(filePath: String): Boolean {
        if (filePath.endsWith(".png", ignoreCase = true)) return false
        val info = runCatching { FFprobeKit.getMediaInformation(filePath) }
            .getOrNull()?.mediaInformation
        val streams = info?.streams.orEmpty()
        if (streams.isNotEmpty()) {
            return streams.any { it.type.equals("audio", ignoreCase = true) }
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                .equals("yes", ignoreCase = true)
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun stageUriToLocalFile(context: Context, uri: Uri): File? {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val file = uri.path?.let { File(it) }
            if (file != null && file.exists() && file.length() > 0L) {
                return file
            }
        }
        val stagingDir = File(context.cacheDir, "editor_staging").apply { if (!exists()) mkdirs() }
        val name = resolveDisplayName(context, uri) ?: "media"
        val ext = name.substringAfterLast('.', "mp4").take(8).ifBlank { "mp4" }
        val hash = sha256Short(uri.toString())
        val stagedFile = File(stagingDir, "${hash}.$ext")
        if (stagedFile.exists() && stagedFile.length() > 0L) {
            return stagedFile
        }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                stagedFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            if (stagedFile.exists() && stagedFile.length() > 0L) stagedFile else null
        }.onFailure { t ->
            CrashLogger.logError("FFmpegEditorEngine.stageUri", "Unable to copy URI $uri", t)
        }.getOrNull()
    }

    private fun publishExportToMoviesClotch(
        context: Context,
        sourceFile: File,
        fileName: String,
        mimeType: String
    ): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Clotch")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            return runCatching {
                resolver.openOutputStream(uri)?.use { out ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(out, bufferSize = 64 * 1024)
                    }
                } ?: error("Null output stream for $uri")
                val finish = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                resolver.update(uri, finish, null, null)
                uri
            }.onFailure {
                runCatching { resolver.delete(uri, null, null) }
            }.getOrNull()
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Clotch")
            if (!dir.exists()) dir.mkdirs()
            val dest = File(dir, fileName)
            sourceFile.copyTo(dest, overwrite = true)
            return Uri.fromFile(dest)
        }
    }

    private fun pruneStaleTempCaches(context: Context) {
        val cutoffMs = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        val dirs = listOf("editor_staging", "editor_captions", "editor_overlays", "editor_exports")
        dirs.forEach { name ->
            val dir = File(context.cacheDir, name)
            if (dir.exists()) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && file.lastModified() in 1L until cutoffMs) {
                        runCatching { file.delete() }
                    }
                }
            }
        }
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            return uri.lastPathSegment
        }
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private fun sha256Short(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.take(10).joinToString("") { "%02x".format(it) }
    }

    private fun formatSec(ms: Long): String = FFmpegFilterBuilder.formatSec(ms)
    private fun formatFloat(v: Float): String = FFmpegFilterBuilder.formatFloat(v)
}
