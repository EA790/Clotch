package com.example

import android.net.Uri
import com.example.data.VideoFormat
import com.example.editor.model.AudioClipKind
import com.example.editor.model.EditorAspectRatio
import com.example.editor.model.EditorExportConfig
import com.example.editor.model.EditorExportResolution
import com.example.editor.model.EditorHistoryStack
import com.example.editor.model.EditorProject
import com.example.editor.model.TimelineAudioClip
import com.example.editor.model.TimelineVideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorModelsTest {

    @Test
    fun timelineVideoWindows_computeContiguousStartsAndEnds() {
        val clip1 = TimelineVideoClip(
            id = "c1",
            sourceUri = Uri.parse("file:///tmp/a.mp4"),
            displayName = "Clip A",
            sourceDurationMs = 10_000L,
            trimStartMs = 1_000L,
            trimEndMs = 6_000L
        )
        val clip2 = TimelineVideoClip(
            id = "c2",
            sourceUri = Uri.parse("file:///tmp/b.mp4"),
            displayName = "Clip B",
            sourceDurationMs = 8_000L,
            trimStartMs = 0L,
            trimEndMs = 4_000L
        )
        val project = EditorProject(videoClips = listOf(clip1, clip2))

        assertEquals(9_000L, project.totalVideoDurationMs)
        val windows = project.videoWindows()
        assertEquals(2, windows.size)
        assertEquals(0L, windows[0].timelineStartMs)
        assertEquals(5_000L, windows[0].timelineEndMs)
        assertEquals(5_000L, windows[1].timelineStartMs)
        assertEquals(9_000L, windows[1].timelineEndMs)

        val hit = project.findVideoClipAt(6_500L)
        assertNotNull(hit)
        assertEquals("c2", hit?.clip?.id)
    }

    @Test
    fun timelineDuration_respectsLongerAudioTrack() {
        val clip = TimelineVideoClip(
            id = "c1",
            sourceUri = Uri.parse("file:///tmp/a.mp4"),
            displayName = "Clip A",
            sourceDurationMs = 5_000L
        )
        val bgm = TimelineAudioClip(
            id = "a1",
            sourceUri = Uri.parse("file:///tmp/bgm.mp3"),
            displayName = "BGM",
            kind = AudioClipKind.BGM,
            timelineStartMs = 2_000L,
            sourceDurationMs = 10_000L
        )
        val project = EditorProject(
            videoClips = listOf(clip),
            audioClips = listOf(bgm)
        )
        assertEquals(5_000L, project.totalVideoDurationMs)
        assertEquals(12_000L, project.totalTimelineDurationMs)
    }

    @Test
    fun historyStack_undoAndRedoRestoreSnapshotsAndClearRedoOnNewEdit() {
        val history = EditorHistoryStack()
        val s0 = EditorProject(projectName = "Empty")
        val s1 = EditorProject(projectName = "Added Clip")
        val s2 = EditorProject(projectName = "Trimmed Clip")
        val sBranch = EditorProject(projectName = "Split Clip")

        history.pushSnapshot(s0)
        history.pushSnapshot(s1)
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)

        val undone1 = history.undo(s2)
        assertEquals(s1, undone1)
        assertTrue(history.canRedo)

        val redone = history.redo(undone1!!)
        assertEquals(s2, redone)

        val undoneAgain = history.undo(s2)!!
        history.pushSnapshot(undoneAgain)
        assertFalse(history.canRedo)
        assertEquals(sBranch.projectName, "Split Clip")
    }

    @Test
    fun exportConfig_resolvesEvenDimensionsAcrossAspectRatios() {
        val cfg = EditorExportConfig(
            format = VideoFormat.MKV,
            resolution = EditorExportResolution.FHD_1080P,
            fps = 60,
            bitrateMbps = 16
        )
        assertEquals(1920 to 1080, cfg.resolveDimensions(EditorAspectRatio.RATIO_16_9))
        assertEquals(1080 to 1920, cfg.resolveDimensions(EditorAspectRatio.RATIO_9_16))
        assertEquals(1080 to 1080, cfg.resolveDimensions(EditorAspectRatio.RATIO_1_1))
    }

    @Test
    fun mutedOrDetachedClip_reportsZeroEffectiveVolume() {
        val detached = TimelineVideoClip(
            sourceUri = Uri.parse("file:///tmp/v.mp4"),
            displayName = "V",
            sourceDurationMs = 4_000L,
            volume = 1.5f,
            audioDetached = true
        )
        val active = detached.copy(audioDetached = false, isMuted = false)
        assertEquals(0f, detached.effectiveVolume)
        assertEquals(1.5f, active.effectiveVolume)
        assertNull(EditorHistoryStack().undo(EditorProject()))
    }
}
