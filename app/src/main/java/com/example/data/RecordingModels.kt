package com.example.data

import android.net.Uri

enum class VideoResolution(
    val label: String,
    val shortLabel: String,
    val shortSide: Int,
    val longSide: Int
) {
    LD_240P("240p", "240p", 240, 426),
    SD_360P("360p", "360p", 360, 640),
    SD_480P("480p", "480p", 480, 854),
    HD_720P("720p HD", "720p", 720, 1280),
    FHD_1080P("1080p FHD", "1080p", 1080, 1920),
    QHD_1440P("1440p QHD", "1440p", 1440, 2560)
}

enum class FrameRateOption(val fps: Int, val label: String) {
    FPS_24(24, "24 FPS"),
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

enum class VideoFormat(
    val label: String,
    val extension: String,
    val mimeType: String
) {
    MP4("MP4", "mp4", "video/mp4"),
    MKV("MKV", "mkv", "video/x-matroska"),
    THREE_GPP("3GPP", "3gp", "video/3gpp")
}

enum class RecordingMode(val label: String) {
    AUDIO_AND_VIDEO("Audio + Video"),
    VIDEO_ONLY("Video Only")
}

enum class AudioCaptureSource(val label: String, val shortLabel: String) {
    GAME_ONLY("Game Audio Only", "Game Audio"),
    MIC_ONLY("Mic Only", "Mic Audio"),
    BOTH("Both (Separate Tracks)", "Game + Mic"),
    NONE("No Audio", "Silent")
}

data class ClotchSettings(
    val resolution: VideoResolution = VideoResolution.FHD_1080P,
    val fps: FrameRateOption = FrameRateOption.FPS_60,
    val bitrate: BitrateOption = BitrateOption.MBPS_20,
    val orientation: OrientationOption = OrientationOption.AUTO,
    val countdown: CountdownOption = CountdownOption.OFF,
    val videoFormat: VideoFormat = VideoFormat.MP4,
    val recordingMode: RecordingMode = RecordingMode.AUDIO_AND_VIDEO,
    val audioCaptureSource: AudioCaptureSource = AudioCaptureSource.BOTH,
    val recordAudioSeparately: Boolean = false,
    val recordMicAudio: Boolean = true,
    val showQuickNotification: Boolean = true,
    val gameName: String = "Mobile Game",
    val discordRpcEnabled: Boolean = true,
    val discordToken: String = "",
    val geminiApiKey: String = ""
) {
    val effectiveAudioSource: AudioCaptureSource
        get() = when {
            recordingMode == RecordingMode.VIDEO_ONLY && recordAudioSeparately -> AudioCaptureSource.MIC_ONLY
            recordingMode == RecordingMode.VIDEO_ONLY -> AudioCaptureSource.NONE
            audioCaptureSource == AudioCaptureSource.NONE -> AudioCaptureSource.MIC_ONLY
            else -> audioCaptureSource
        }

    val needsMicPermission: Boolean
        get() = (recordingMode == RecordingMode.AUDIO_AND_VIDEO && audioCaptureSource != AudioCaptureSource.NONE) ||
            (recordingMode == RecordingMode.VIDEO_ONLY && recordAudioSeparately)
}

data class RecordedClip(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val dateAddedSec: Long,
    val mimeType: String = "video/mp4",
    val isFileUri: Boolean = false
)
