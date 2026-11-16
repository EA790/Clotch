package com.example.editor.model

import android.net.Uri
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
    EXTRACTED("Extracted"),
    BGM("Music"),
    VOICEOVER("Voiceover")
}

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
    val height: Int = 1080
) {
    val effectiveDurationMs: Long
        get() = (trimEndMs - trimStartMs).coerceAtLeast(250L)

    val effectiveVolume: Float
        get() = if (isMuted || audioDetached) 0f else volume.coerceIn(0f, 2f)
}

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

data class VideoClipWindow(
    val clip: TimelineVideoClip,
    val index: Int,
    val timelineStartMs: Long,
    val timelineEndMs: Long
)

data class EditorProject(
    val projectId: String = UUID.randomUUID().toString(),
    val projectName: String = "Clotch Edit",
    val aspectRatio: EditorAspectRatio = EditorAspectRatio.RATIO_16_9,
    val videoClips: List<TimelineVideoClip> = emptyList(),
    val audioClips: List<TimelineAudioClip> = emptyList(),
    val playheadMs: Long = 0L,
    val zoomLevel: Float = 1.0f,
    val selectedVideoClipId: String? = null,
    val selectedAudioClipId: String? = null
) {
    val totalVideoDurationMs: Long
        get() = videoClips.sumOf { it.effectiveDurationMs }

    val totalTimelineDurationMs: Long
        get() = maxOf(
            totalVideoDurationMs,
            audioClips.maxOfOrNull { it.timelineEndMs } ?: 0L
        )

    fun videoWindows(): List<VideoClipWindow> {
        var cursor = 0L
        return videoClips.mapIndexed { index, clip ->
            val start = cursor
            val end = cursor + clip.effectiveDurationMs
            cursor = end
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
    QHD_1440P("1440p", 2560, 1440)
}

data class EditorExportConfig(
    val format: VideoFormat = VideoFormat.MP4,
    val resolution: EditorExportResolution = EditorExportResolution.FHD_1080P,
    val fps: Int = 60,
    val bitrateMbps: Int = 16
) {
    fun resolveDimensions(aspectRatio: EditorAspectRatio): Pair<Int, Int> {
        return when (aspectRatio) {
            EditorAspectRatio.RATIO_16_9 -> resolution.longSide to resolution.shortSide
            EditorAspectRatio.RATIO_9_16 -> resolution.shortSide to resolution.longSide
            EditorAspectRatio.RATIO_1_1 -> resolution.shortSide to resolution.shortSide
        }
    }
}

sealed interface EditorExportState {
    data object Idle : EditorExportState
    data class Exporting(val progress: Float, val statusText: String) : EditorExportState
    data class Completed(val outputUri: Uri, val fileName: String) : EditorExportState
    data class Error(val message: String, val logPath: String) : EditorExportState
}

sealed interface VoiceoverState {
    data object Idle : VoiceoverState
    data class Recording(
        val startedAtPlayheadMs: Long,
        val elapsedMs: Long,
        val outputFilePath: String
    ) : VoiceoverState
}
