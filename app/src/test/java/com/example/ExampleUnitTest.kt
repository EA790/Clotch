package com.example

import com.example.data.BitrateOption
import com.example.data.ClotchSettings
import com.example.data.FrameRateOption
import com.example.data.OrientationOption
import com.example.data.VideoResolution
import com.example.discord.DiscordPresencePayload
import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun defaultSettings_matchGamer1080p60Spec() {
        val defaults = ClotchSettings()
        assertEquals(VideoResolution.FHD_1080P, defaults.resolution)
        assertEquals(FrameRateOption.FPS_60, defaults.fps)
        assertEquals(BitrateOption.MBPS_20, defaults.bitrate)
        assertEquals(OrientationOption.AUTO, defaults.orientation)
        assertEquals("Mobile Game", defaults.gameName)
    }

    @Test
    fun discordPresence_formatsRecordingStatusString() {
        val payload = DiscordPresencePayload(
            isRecording = true,
            isPaused = false,
            gameName = "Mobile Game",
            fps = 60,
            resolutionLabel = "1080p",
            bitrateMbps = 20,
            startedAtEpochMs = 1_700_000_000_000L
        )
        assertEquals("Recording Mobile Game at 60FPS", payload.formatStatusText())
    }
}
