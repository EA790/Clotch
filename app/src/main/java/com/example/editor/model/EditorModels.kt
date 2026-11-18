package com.example.editor.model

import android.net.Uri
import androidx.compose.runtime.Immutable
import com.example.data.VideoFormat
import java.util.UUID

enum class EditorAspectRatio(
    val label: String,
    val widthRatio: Float,
    val heightRatio: Float,
    val exportWidth: Int,
    val exportHeight: Int
) {
    RATIO_16_9("16:9", 16f, 9f, 1920, 1080),
    RATIO_9_16("9:16", 9f, 16f, 1080, 1920),
    RATIO_1_1("1:1", 1f, 1f, 1080, 1080)
}

enum class AudioClipKind(val label: String) {
    GAME("Game Audio"),
    CHAT("Discord / Chat"),
    MIC("Microphone"),
    EXTRACTED("Extracted"),
    BGM("Music"),
    VOICEOVER("Voiceover")
}

enum class EditorFontChoice(val label: String) {
    SPACE_GROTESK("Space Grotesk"),
    INTER("Inter Clean"),
    JETBRAINS_MONO("Studio Mono")
}

enum class TextAnimationType(val label: String) {
    NONE("Static"),
    FADE("Fade"),
    SLIDE_UP("Slide Up"),
    TYPEWRITER("Typewriter")
}

enum class ColorFilterPreset(val label: String) {
    NONE("Original"),
    WARM("Warm"),
    COOL("Cool"),
    VINTAGE("Vintage"),
    CYBER("Cyber")
}

enum class CropMode(val label: String) {
    FULL("Full"),
    CENTER_SQUARE("1:1 Crop"),
    TOP_HALF("Top Half"),
    BOTTOM_HALF("Bottom Half"),
    CINEMA_21_9("21:9 Cinema")
}

enum class TransitionType(val label: String, val ffmpegXfadeName: String) {
    NONE("Cut", "none"),
    FADE("Fade", "fade"),
    SLIDE_LEFT("Slide", "slideleft"),
    ZOOM_IN("Iris/Zoom", "circleopen")
}

@Immutable
data class TransitionSpec(
    val type: TransitionType = TransitionType.NONE,
    val durationMs: Long = 500L
)

enum class ChromaKeyMode(val label: String, val subtitle: String) {
    MOVING("Moving (Human/Hair)", "ML Kit Selfie Segmentation"),
    STILL("Still (Objects)", "ML Kit Object Detection")
}

enum class ChromaKeyBgStyle(val label: String) {
    TRANSPARENT_DARK("Dark Studio"),
    NEON_GAMING("Neon Grid"),
    BLUR_BG("Blur Backdrop"),
    SOLID_GREEN("Green Screen")
}

@Immutable
data class ChromaKeyConfig(
    val enabled: Boolean = false,
    val mode: ChromaKeyMode = ChromaKeyMode.MOVING,
    val bgStyle: ChromaKeyBgStyle = ChromaKeyBgStyle.TRANSPARENT_DARK
)

enum class UpscaleTarget(val label: String, val shortSide: Int, val longSide: Int) {
    OFF("Native", 0, 0),
    UPSCALE_1080P("1080p Lanczos", 1080, 1920),
    UPSCALE_4K_2160P("4K Lanczos", 2160, 3840)
}

enum class AutoCaptionMode(val label: String) {
    FAST_OFFLINE("Fast / Offline"),
    AI_GEMINI("AI-Powered (Gemini 1.5 Flash)")
}

@Immutable
data class ClipKeyframe(
    val id: String = UUID.randomUUID().toString(),
    val timeOffsetMs: Long,
    val posX: Float = 0f,
    val posY: Float = 0f,
    val scale: Float = 1.0f,
    val opacity: Float = 1.0f
)

@Immutable
data class KeyframeEval(
    val posX: Float = 0f,
    val posY: Float = 0f,
    val scale: Float = 1.0f,
    val opacity: Float = 1.0f
)

@Immutable
data class TimelineTextOverlay(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val timelineStartMs: Long,
    val durationMs: Long = 3000L,
    val fontChoice: EditorFontChoice = EditorFontChoice.SPACE_GROTESK,
    val colorArgb: Long = 0xFFFFFFFF,
    val hasBackgroundPill: Boolean = true,
    val animation: TextAnimationType = TextAnimationType.FADE,
    val posX: Float = 0.5f,
    val posY: Float = 0.82f,
    val fontSizeSp: Float = 22f,
    val isCaption: Boolean = false
) {
    val timelineEndMs: Long
        get() = timelineStartMs + durationMs.coerceAtLeast(300L)
}

@Immutable
data class TimelineSticker(
    val id: String = UUID.randomUUID().toString(),
    val emoji: String,
    val timelineStartMs: Long,
    val durationMs: Long = 3000L,
    val posX: Float = 0.5f,
    val posY: Float = 0.35f,
    val scale: Float = 1.0f
) {
    val timelineEndMs: Long
        get() = timelineStartMs + durationMs.coerceAtLeast(300L)
}

@Immutable
data class TimelineVideoClip(
    val id: String = UUID.randomUUID().toString(),
    val sourceUri: Uri,
    val displayName: String,
    val sourceDurationMs: Long,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = sourceDurationMs,
    val volume: Float = 1.0f,
    val isMuted: Boolean = false,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val audioDetached: Boolean = false,
    val width: Int = 1920,
    val height: Int = 1080,
    val speed: Float = 1.0f,
    val filterPreset: ColorFilterPreset = ColorFilterPreset.NONE,
    val brightness: Float = 0f,
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f,
    val blurBackground: Boolean = false,
    val rotationDegrees: Int = 0,
    val cropMode: CropMode = CropMode.FULL,
    val keyframes: List<ClipKeyframe> = emptyList(),
    val chromaKey: ChromaKeyConfig = ChromaKeyConfig(),
    val upscaleTarget: UpscaleTarget = UpscaleTarget.OFF,
    val isReversed: Boolean = false,
    val isFreezeFrame: Boolean = false,
    val freezeImagePath: String? = null
) {
    val sourceSpanMs: Long
        get() = (trimEndMs - trimStartMs).coerceAtLeast(250L)

    val effectiveDurationMs: Long
        get() = if (isFreezeFrame) {
            sourceDurationMs.coerceAtLeast(500L)
        } else {
            (sourceSpanMs / speed.coerceIn(0.25f, 4.0f)).toLong().coerceAtLeast(250L)
        }

    val effectiveVolume: Float
        get() = if (isMuted || audioDetached || isFreezeFrame) 0f else volume.coerceIn(0f, 2f)

    fun evaluateKeyframesAt(localOffsetMs: Long): KeyframeEval {
        if (keyframes.isEmpty()) return KeyframeEval()
        val sorted = keyframes.sortedBy { it.timeOffsetMs }
        if (localOffsetMs <= sorted.first().timeOffsetMs) {
            val k = sorted.first()
            return KeyframeEval(k.posX, k.posY, k.scale, k.opacity)
        }
        if (localOffsetMs >= sorted.last().timeOffsetMs) {
            val k = sorted.last()
            return KeyframeEval(k.posX, k.posY, k.scale, k.opacity)
        }
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (localOffsetMs in a.timeOffsetMs..b.timeOffsetMs) {
                val span = (b.timeOffsetMs - a.timeOffsetMs).coerceAtLeast(1L).toFloat()
                val t = ((localOffsetMs - a.timeOffsetMs).toFloat() / span).coerceIn(0f, 1f)
                return KeyframeEval(
                    posX = a.posX + (b.posX - a.posX) * t,
                    posY = a.posY + (b.posY - a.posY) * t,
                    scale = a.scale + (b.scale - a.scale) * t,
                    opacity = a.opacity + (b.opacity - a.opacity) * t
                )
            }
        }
        return KeyframeEval()
    }
}

@Immutable
data class TimelineAudioClip(
    val id: String = UUID.randomUUID().toString(),
    val sourceUri: Uri,
    val displayName: String,
    val kind: AudioClipKind,
    val timelineStartMs: Long = 0L,
    val sourceDurationMs: Long,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = sourceDurationMs,
    val volume: Float = 1.0f,
    val isMuted: Boolean = false,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L
) {
    val effectiveDurationMs: Long
        get() = (trimEndMs - trimStartMs).coerceAtLeast(250L)

    val timelineEndMs: Long
        get() = timelineStartMs + effectiveDurationMs

    val effectiveVolume: Float
        get() = if (isMuted) 0f else volume.coerceIn(0f, 2f)
}

@Immutable
data class VideoClipWindow(
    val clip: TimelineVideoClip,
    val index: Int,
    val timelineStartMs: Long,
    val timelineEndMs: Long
)

@Immutable
data class EditorProject(
    val projectId: String = UUID.randomUUID().toString(),
    val projectName: String = "Clotch Edit",
    val aspectRatio: EditorAspectRatio = EditorAspectRatio.RATIO_16_9,
    val videoClips: List<TimelineVideoClip> = emptyList(),
    val audioClips: List<TimelineAudioClip> = emptyList(),
    val overlays: List<TimelineTextOverlay> = emptyList(),
    val stickers: List<TimelineSticker> = emptyList(),
    val transitions: Map<String, TransitionSpec> = emptyMap(),
    val playheadMs: Long = 0L,
    val zoomLevel: Float = 1.0f,
    val selectedVideoClipId: String? = null,
    val selectedAudioClipId: String? = null,
    val selectedOverlayId: String? = null,
    val selectedStickerId: String? = null
) {
    val totalVideoDurationMs: Long
        get() {
            if (videoClips.isEmpty()) return 0L
            val rawSum = videoClips.sumOf { it.effectiveDurationMs }
            val transitionOverlap = videoClips.dropLast(1).sumOf { clip ->
                val spec = transitions[clip.id]
                if (spec != null && spec.type != TransitionType.NONE) {
                    spec.durationMs.coerceAtMost(clip.effectiveDurationMs / 2)
                } else {
                    0L
                }
            }
            return (rawSum - transitionOverlap).coerceAtLeast(250L)
        }

    val totalTimelineDurationMs: Long
        get() = maxOf(
            totalVideoDurationMs,
            audioClips.maxOfOrNull { it.timelineEndMs } ?: 0L,
            overlays.maxOfOrNull { it.timelineEndMs } ?: 0L,
            stickers.maxOfOrNull { it.timelineEndMs } ?: 0L
        )

    fun videoWindows(): List<VideoClipWindow> {
        var cursor = 0L
        return videoClips.mapIndexed { index, clip ->
            val start = cursor
            val end = cursor + clip.effectiveDurationMs
            val overlap = if (index < videoClips.lastIndex) {
                val spec = transitions[clip.id]
                if (spec != null && spec.type != TransitionType.NONE) {
                    spec.durationMs.coerceAtMost(clip.effectiveDurationMs / 2)
                } else {
                    0L
                }
            } else {
                0L
            }
            cursor = (end - overlap).coerceAtLeast(start)
            VideoClipWindow(
                clip = clip,
                index = index,
                timelineStartMs = start,
                timelineEndMs = end
            )
        }
    }

    fun clipStartTimeMs(clipId: String): Long {
        return videoWindows().firstOrNull { it.clip.id == clipId }?.timelineStartMs ?: 0L
    }

    fun findVideoClipAt(timeMs: Long): VideoClipWindow? {
        val windows = videoWindows()
        if (windows.isEmpty()) return null
        val clamped = timeMs.coerceIn(0L, totalVideoDurationMs.coerceAtLeast(1L))
        return windows.firstOrNull { clamped in it.timelineStartMs until it.timelineEndMs }
            ?: windows.last()
    }

    val selectedVideoClip: TimelineVideoClip?
        get() = videoClips.firstOrNull { it.id == selectedVideoClipId }

    val selectedAudioClip: TimelineAudioClip?
        get() = audioClips.firstOrNull { it.id == selectedAudioClipId }

    val selectedOverlay: TimelineTextOverlay?
        get() = overlays.firstOrNull { it.id == selectedOverlayId }

    val selectedSticker: TimelineSticker?
        get() = stickers.firstOrNull { it.id == selectedStickerId }

    val highestUpscaleTarget: UpscaleTarget
        get() = when {
            videoClips.any { it.upscaleTarget == UpscaleTarget.UPSCALE_4K_2160P } -> UpscaleTarget.UPSCALE_4K_2160P
            videoClips.any { it.upscaleTarget == UpscaleTarget.UPSCALE_1080P } -> UpscaleTarget.UPSCALE_1080P
            else -> UpscaleTarget.OFF
        }
}

class EditorHistoryStack(private val maxEntries: Int = 40) {
    private val undoStack = ArrayDeque<EditorProject>()
    private val redoStack = ArrayDeque<EditorProject>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun pushSnapshot(previousState: EditorProject) {
        if (undoStack.lastOrNull() == previousState) return
        undoStack.addLast(previousState)
        while (undoStack.size > maxEntries) {
            undoStack.removeFirst()
        }
        redoStack.clear()
    }

    fun undo(currentState: EditorProject): EditorProject? {
        if (undoStack.isEmpty()) return null
        val previous = undoStack.removeLast()
        redoStack.addLast(currentState)
        return previous
    }

    fun redo(currentState: EditorProject): EditorProject? {
        if (redoStack.isEmpty()) return null
        val next = redoStack.removeLast()
        undoStack.addLast(currentState)
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}

enum class EditorExportResolution(
    val label: String,
    val longSide: Int,
    val shortSide: Int
) {
    HD_720P("720p", 1280, 720),
    FHD_1080P("1080p", 1920, 1080),
    QHD_1440P("1440p", 2560, 1440),
    UHD_4K("4K", 3840, 2160)
}

@Immutable
data class EditorExportConfig(
    val format: VideoFormat = VideoFormat.MP4,
    val resolution: EditorExportResolution = EditorExportResolution.FHD_1080P,
    val fps: Int = 60,
    val bitrateMbps: Int = 16
) {
    fun resolveDimensions(
        aspectRatio: EditorAspectRatio,
        upscaleOverride: UpscaleTarget = UpscaleTarget.OFF
    ): Pair<Int, Int> {
        val longSide = maxOf(resolution.longSide, upscaleOverride.longSide)
        val shortSide = maxOf(resolution.shortSide, upscaleOverride.shortSide)
        return when (aspectRatio) {
            EditorAspectRatio.RATIO_16_9 -> longSide to shortSide
            EditorAspectRatio.RATIO_9_16 -> shortSide to longSide
            EditorAspectRatio.RATIO_1_1 -> shortSide to shortSide
        }
    }
}

@Immutable
sealed interface EditorExportState {
    data object Idle : EditorExportState
    data class Exporting(val progress: Float, val statusText: String) : EditorExportState
    data class Completed(val outputUri: Uri, val fileName: String) : EditorExportState
    data class Error(val message: String, val logPath: String) : EditorExportState
}

@Immutable
sealed interface VoiceoverState {
    data object Idle : VoiceoverState
    data class Recording(
        val startedAtPlayheadMs: Long,
        val elapsedMs: Long,
        val outputFilePath: String
    ) : VoiceoverState
}

@Immutable
sealed interface AutoCaptionState {
    data object Idle : AutoCaptionState
    data class Generating(val status: String) : AutoCaptionState
    data class ListeningOffline(val partialText: String) : AutoCaptionState
    data class Message(val text: String) : AutoCaptionState
}
