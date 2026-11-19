package com.example

import android.content.ComponentCallbacks2
import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.example.data.AudioCaptureSource
import com.example.data.ClotchSettings
import com.example.data.CompanionTrackMatcher
import com.example.data.RecordingMode
import com.example.data.VideoFormat
import com.example.diagnostics.MemoryPressureListener
import com.example.diagnostics.MemoryPressureManager
import com.example.editor.engine.EditorThumbnailCache
import com.example.editor.engine.FFmpegEditorEngine
import com.example.editor.engine.FFmpegFilterBuilder
import com.example.editor.engine.Media3ExportEngine
import com.example.editor.model.AudioClipKind
import com.example.editor.model.ClipKeyframe
import com.example.editor.model.ColorFilterPreset
import com.example.editor.model.CropMode
import com.example.editor.model.EditorAspectRatio
import com.example.editor.model.EditorExportConfig
import com.example.editor.model.EditorExportResolution
import com.example.editor.model.EditorHistoryStack
import com.example.editor.model.EditorProject
import com.example.editor.model.TimelineAudioClip
import com.example.editor.model.TimelineVideoClip
import com.example.editor.model.TransitionSpec
import com.example.editor.model.TransitionType
import com.example.editor.model.UpscaleTarget
import com.example.ui.editor.TimelineCanvas
import com.example.ui.theme.ClotchTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorModelsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

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
    }

    @Test
    fun exportConfig_resolvesEvenDimensionsAnd4KUpscale() {
        val cfg = EditorExportConfig(
            format = VideoFormat.MKV,
            resolution = EditorExportResolution.FHD_1080P,
            fps = 60,
            bitrateMbps = 16
        )
        assertEquals(1920 to 1080, cfg.resolveDimensions(EditorAspectRatio.RATIO_16_9))
        assertEquals(1080 to 1920, cfg.resolveDimensions(EditorAspectRatio.RATIO_9_16))
        assertEquals(1080 to 1080, cfg.resolveDimensions(EditorAspectRatio.RATIO_1_1))
        assertEquals(
            3840 to 2160,
            cfg.resolveDimensions(EditorAspectRatio.RATIO_16_9, UpscaleTarget.UPSCALE_4K_2160P)
        )
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

    @Test
    fun speedAndTransitions_adjustTimelineDurationAndAudioTempo() {
        val clip1 = TimelineVideoClip(
            id = "c1",
            sourceUri = Uri.parse("file:///tmp/a.mp4"),
            displayName = "Fast Clip",
            sourceDurationMs = 8_000L,
            speed = 2.0f
        )
        val clip2 = TimelineVideoClip(
            id = "c2",
            sourceUri = Uri.parse("file:///tmp/b.mp4"),
            displayName = "Clip 2",
            sourceDurationMs = 4_000L,
            speed = 4.0f
        )
        assertEquals(4_000L, clip1.effectiveDurationMs)
        assertEquals(1_000L, clip2.effectiveDurationMs)

        val project = EditorProject(
            videoClips = listOf(clip1, clip2),
            transitions = mapOf("c1" to TransitionSpec(TransitionType.FADE, 500L))
        )
        assertEquals(4_500L, project.totalVideoDurationMs)

        val audio4x = FFmpegFilterBuilder.buildClipAudioChain(0, 1, clip2, hasAudioStream = true)
        assertTrue(audio4x.contains("atempo=2.0"))
    }

    @Test
    fun keyframesAndStage2Filters_interpolateAndBuildFfmpegChains() {
        val clip = TimelineVideoClip(
            sourceUri = Uri.parse("file:///tmp/c.mp4"),
            displayName = "Graded Clip",
            sourceDurationMs = 6_000L,
            filterPreset = ColorFilterPreset.CYBER,
            brightness = 0.1f,
            contrast = 1.2f,
            saturation = 1.3f,
            blurBackground = true,
            rotationDegrees = 90,
            cropMode = CropMode.CENTER_SQUARE,
            upscaleTarget = UpscaleTarget.UPSCALE_4K_2160P,
            isReversed = true,
            keyframes = listOf(
                ClipKeyframe(timeOffsetMs = 0L, posX = 0f, scale = 1.0f, opacity = 1.0f),
                ClipKeyframe(timeOffsetMs = 2_000L, posX = 0.2f, scale = 1.5f, opacity = 0.5f)
            )
        )
        val mid = clip.evaluateKeyframesAt(1_000L)
        assertEquals(0.1f, mid.posX, 0.01f)
        assertEquals(1.25f, mid.scale, 0.01f)
        assertEquals(0.75f, mid.opacity, 0.01f)

        val vChain = FFmpegFilterBuilder.buildClipVideoChain(
            inputIndex = 0,
            clipIndex = 0,
            clip = clip,
            evenW = 3840,
            evenH = 2160,
            targetFps = 60,
            matteInputIndex = null
        ).joinToString(";")
        assertTrue(vChain.contains("reverse"))
        assertTrue(vChain.contains("transpose=1"))
        assertTrue(vChain.contains("boxblur=20:3"))
        assertTrue(vChain.contains("flags=lanczos"))
    }

    @Test
    fun companionTrackMatcher_identifiesGameChatAndMicByTimestampStem() {
        val stem = CompanionTrackMatcher.extractClipTimestampStem("clip_20261117_051600.mp4")
        assertEquals("20261117_051600", stem)
        assertNull(CompanionTrackMatcher.extractClipTimestampStem("random_video.mp4"))

        assertEquals(
            AudioClipKind.GAME,
            CompanionTrackMatcher.classifyCompanionAudio("game_20261117_051600.m4a", stem!!)
        )
        assertEquals(
            AudioClipKind.CHAT,
            CompanionTrackMatcher.classifyCompanionAudio("discord_20261117_051600.m4a", stem)
        )
        assertEquals(
            AudioClipKind.MIC,
            CompanionTrackMatcher.classifyCompanionAudio("mic_20261117_051600.m4a", stem)
        )
        assertEquals(
            AudioClipKind.MIC,
            CompanionTrackMatcher.classifyCompanionAudio("audio_20261117_051600.m4a", stem)
        )
        assertNull(
            CompanionTrackMatcher.classifyCompanionAudio("mic_19990101_000000.m4a", stem)
        )
    }

    @Test
    fun separateAudioTracks_muteAndAdjustVolumeIndependently() {
        val game = TimelineAudioClip(
            id = "a_game",
            sourceUri = Uri.parse("file:///tmp/game.m4a"),
            displayName = AudioClipKind.GAME.label,
            kind = AudioClipKind.GAME,
            sourceDurationMs = 5_000L,
            volume = 0.8f,
            isMuted = false
        )
        val mic = TimelineAudioClip(
            id = "a_mic",
            sourceUri = Uri.parse("file:///tmp/mic.m4a"),
            displayName = AudioClipKind.MIC.label,
            kind = AudioClipKind.MIC,
            sourceDurationMs = 5_000L,
            volume = 1.4f,
            isMuted = true
        )
        val project = EditorProject(audioClips = listOf(game, mic))

        assertEquals(0.8f, project.audioClips[0].effectiveVolume, 0.001f)
        assertEquals(0.0f, project.audioClips[1].effectiveVolume, 0.001f)

        val settingsBoth = ClotchSettings(
            recordingMode = RecordingMode.AUDIO_AND_VIDEO,
            audioCaptureSource = AudioCaptureSource.BOTH
        )
        assertEquals(AudioCaptureSource.BOTH, settingsBoth.effectiveAudioSource)
        assertTrue(settingsBoth.needsMicPermission)

        val settingsSilent = ClotchSettings(
            recordingMode = RecordingMode.VIDEO_ONLY,
            audioCaptureSource = AudioCaptureSource.NONE,
            recordAudioSeparately = false
        )
        assertEquals(AudioCaptureSource.NONE, settingsSilent.effectiveAudioSource)
        assertFalse(settingsSilent.needsMicPermission)

        val settingsNormal = ClotchSettings(
            recordingMode = RecordingMode.AUDIO_AND_VIDEO,
            audioCaptureSource = AudioCaptureSource.BOTH,
            audioTrackMode = com.example.data.AudioTrackMode.NORMAL_AUDIO,
            recordAudioSeparately = false
        )
        assertEquals(com.example.data.AudioTrackMode.NORMAL_AUDIO, settingsNormal.audioTrackMode)
        assertFalse(settingsNormal.isSeparateAudioActive)
        assertTrue(settingsNormal.needsMicPermission)
    }

    @Test
    fun memoryPressureManager_notifiesAndUnregistersThumbnailCacheCleanly() {
        var receivedLevel = -1
        val listener = MemoryPressureListener { level -> receivedLevel = level }
        MemoryPressureManager.registerListener(listener)

        val thumbCache = EditorThumbnailCache()
        try {
            MemoryPressureManager.dispatchTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
            assertEquals(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, receivedLevel)

            MemoryPressureManager.unregisterListener(listener)
            receivedLevel = -1
            MemoryPressureManager.dispatchLowMemory()
            assertEquals(-1, receivedLevel)
        } finally {
            thumbCache.release()
        }
    }

    @Test
    fun media3ExportEngine_acceptsMp4WithSeparateM4aAndRejectsUnsupportedFx() {
        val baseClip = TimelineVideoClip(
            id = "v1",
            sourceUri = Uri.parse("file:///tmp/main.mp4"),
            displayName = "clip.mp4",
            sourceDurationMs = 15_000L
        )
        val separateMic = TimelineAudioClip(
            id = "mic1",
            sourceUri = Uri.parse("file:///tmp/mic.m4a"),
            displayName = "Microphone",
            kind = AudioClipKind.MIC,
            timelineStartMs = 0L,
            sourceDurationMs = 15_000L
        )
        val mp4Config = EditorExportConfig(format = VideoFormat.MP4)
        val separateProject = EditorProject(
            videoClips = listOf(baseClip),
            audioClips = listOf(separateMic)
        )
        assertTrue(Media3ExportEngine.canExportWithMedia3(separateProject, mp4Config))

        val mkvConfig = EditorExportConfig(format = VideoFormat.MKV)
        assertFalse(Media3ExportEngine.canExportWithMedia3(separateProject, mkvConfig))

        val filteredProject = separateProject.copy(
            videoClips = listOf(baseClip.copy(filterPreset = ColorFilterPreset.CYBER))
        )
        assertFalse(Media3ExportEngine.canExportWithMedia3(filteredProject, mp4Config))
    }

    @Test
    fun ffmpegEditorEngine_probeInvalidUriReturnsProbeFailedWithoutCrashing() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = FFmpegEditorEngine.probeMedia(
            context = context,
            uri = Uri.parse("file:///nonexistent_clotch_test_video.mp4"),
            fallbackName = "BrokenClip"
        )
        assertFalse(info.probeSucceeded)
        assertEquals("nonexistent_clotch_test_video.mp4", info.displayName)
    }

    @Test
    fun timelineCanvas_rendersLong60MinuteVideoWithoutWidthOverflowCrash() {
        val thumbCache = EditorThumbnailCache()
        val longClip = TimelineVideoClip(
            id = "long_60min",
            sourceUri = Uri.parse("file:///tmp/long_60min.mp4"),
            displayName = "Long Recording",
            sourceDurationMs = 3_600_000L,
            trimStartMs = 0L,
            trimEndMs = 3_600_000L
        )
        val micClip = TimelineAudioClip(
            id = "long_mic",
            sourceUri = Uri.parse("file:///tmp/long_mic.m4a"),
            displayName = "Microphone",
            kind = AudioClipKind.MIC,
            timelineStartMs = 0L,
            sourceDurationMs = 3_600_000L
        )
        val project = EditorProject(
            videoClips = listOf(longClip),
            audioClips = listOf(micClip),
            zoomLevel = 2.5f
        )
        val playheadFlow = MutableStateFlow(600_000L)

        try {
            composeTestRule.setContent {
                ClotchTheme {
                    TimelineCanvas(
                        project = project,
                        playheadMsFlow = playheadFlow,
                        thumbnailCache = thumbCache,
                        isPlaying = false,
                        onSeekTo = {},
                        onSelectVideoClip = {},
                        onSelectAudioClip = {},
                        onBeginContinuousEdit = {},
                        onCommitContinuousEdit = {},
                        onTrimVideoClip = { _, _, _ -> },
                        onMoveVideoClip = { _, _ -> },
                        onMoveAudioClipStart = { _, _ -> }
                    )
                }
            }
            composeTestRule.onNodeWithTag("timeline_canvas").assertIsDisplayed()
            composeTestRule.onNodeWithTag("timeline_video_clip_long_60min").assertIsDisplayed()
        } finally {
            thumbCache.release()
        }
    }
}
