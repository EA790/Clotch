package com.example

import com.example.data.BitrateOption
import com.example.data.ClotchSettings
import com.example.data.FrameRateOption
import com.example.data.OrientationOption
import com.example.data.RecordingMode
import com.example.data.VideoFormat
import com.example.data.VideoResolution
import com.example.discord.AppDetectionMonitor
import com.example.discord.DiscordPresencePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun defaultSettings_matchGamer1080p60Spec() {
        val defaults = ClotchSettings()
        assertEquals(VideoResolution.FHD_1080P, defaults.resolution)
        assertEquals(FrameRateOption.FPS_60, defaults.fps)
        assertEquals(BitrateOption.MBPS_20, defaults.bitrate)
        assertEquals(OrientationOption.AUTO, defaults.orientation)
        assertEquals(VideoFormat.MP4, defaults.videoFormat)
        assertEquals(RecordingMode.AUDIO_AND_VIDEO, defaults.recordingMode)
        assertEquals("Mobile Game", defaults.gameName)
    }

    @Test
    fun discordPresence_formatsRecordingAndMultiAppStatus() {
        val recordingPayload = DiscordPresencePayload(
            isRecording = true,
            isPaused = false,
            gameName = "Mobile Game",
            fps = 60,
            resolutionLabel = "1080p",
            bitrateMbps = 20,
            startedAtEpochMs = 1_700_000_000_000L
        )
        assertEquals("Recording Mobile Game at 60FPS", recordingPayload.formatStatusText())

        val multiAppPayload = DiscordPresencePayload(
            isRecording = false,
            detectedAppName = "PUBG Mobile",
            isLarkPlayerRunning = true,
            largeImageUrl = "https://example.com/cat_pubg.png"
        )
        assertEquals("Playing PUBG Mobile", multiAppPayload.formatStatusText())
        assertEquals("Listening on Lark Player", multiAppPayload.formatSubDetails())
        assertTrue(AppDetectionMonitor.SUPPORTED_GAMES.any { it.displayName == "PUBG Mobile" })
    }
}
