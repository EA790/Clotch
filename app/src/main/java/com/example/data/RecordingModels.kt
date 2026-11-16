package com.example.data

import android.net.Uri

enum class VideoResolution(
    val label: String,
    val shortLabel: String,
    val shortSide: Int,
    val longSide: Int
) {
    HD_720P("720p HD", "720p", 720, 1280),
    FHD_1080P("1080p FHD", "1080p", 1080, 1920),
    QHD_1440P("1440p QHD", "1440p", 1440, 2560)
}

enum class FrameRateOption(val fps: Int, val label: String) {
    FPS_30(30, "30 FPS"),
    FPS_60(60, "60 FPS"),
    FPS_90(90, "90 FPS"),
    FPS_120(120, "120 FPS")
}

enum class BitrateOption(val mbps: Int, val label: String) {
    MBPS_6(6, "6 Mbps"),
    MBPS_12(12, "12 Mbps"),
    MBPS_20(20, "20 Mbps"),
    MBPS_30(30, "30 Mbps"),
    MBPS_50(50, "50 Mbps");

    val bps: Int get() = mbps * 1_000_000
}

enum class OrientationOption(val label: String) {
    AUTO("Auto"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape")
}

enum class CountdownOption(val seconds: Int, val label: String) {
    OFF(0, "Off"),
    THREE(3, "3s"),
    FIVE(5, "5s")
}

data class ClotchSettings(
    val resolution: VideoResolution = VideoResolution.FHD_1080P,
    val fps: FrameRateOption = FrameRateOption.FPS_60,
    val bitrate: BitrateOption = BitrateOption.MBPS_20,
    val orientation: OrientationOption = OrientationOption.AUTO,
    val countdown: CountdownOption = CountdownOption.OFF,
    val recordMicAudio: Boolean = false,
    val showQuickNotification: Boolean = true,
    val gameName: String = "Mobile Game",
    val discordRpcEnabled: Boolean = true,
    val discordToken: String = ""
)

data class RecordedClip(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val dateAddedSec: Long,
    val isFileUri: Boolean = false
)
