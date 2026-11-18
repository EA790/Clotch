package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.clotchDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "clotch_settings"
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val RESOLUTION = stringPreferencesKey("video_resolution")
        val FPS = stringPreferencesKey("video_fps")
        val BITRATE = stringPreferencesKey("video_bitrate")
        val ORIENTATION = stringPreferencesKey("video_orientation")
        val COUNTDOWN = stringPreferencesKey("countdown_option")
        val VIDEO_FORMAT = stringPreferencesKey("video_format")
        val RECORDING_MODE = stringPreferencesKey("recording_mode")
        val AUDIO_CAPTURE_SOURCE = stringPreferencesKey("audio_capture_source")
        val AUDIO_TRACK_MODE = stringPreferencesKey("audio_track_mode")
        val RECORD_AUDIO_SEPARATELY = booleanPreferencesKey("record_audio_separately")
        val RECORD_MIC_AUDIO = booleanPreferencesKey("record_mic_audio")
        val SHOW_QUICK_NOTIFICATION = booleanPreferencesKey("show_quick_notification")
        val GAME_NAME = stringPreferencesKey("game_name")
        val DISCORD_RPC_ENABLED = booleanPreferencesKey("discord_rpc_enabled")
        val DISCORD_TOKEN = stringPreferencesKey("discord_token")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
    }

    val settingsFlow: Flow<ClotchSettings> = context.clotchDataStore.data.map { prefs ->
        val mode = prefs[Keys.RECORDING_MODE]
            ?.let { name -> RecordingMode.entries.firstOrNull { it.name == name } }
            ?: RecordingMode.AUDIO_AND_VIDEO
        val savedSeparate = prefs[Keys.RECORD_AUDIO_SEPARATELY]
        val trackMode = prefs[Keys.AUDIO_TRACK_MODE]
            ?.let { name -> AudioTrackMode.entries.firstOrNull { it.name == name } }
            ?: if (savedSeparate == true) AudioTrackMode.SEPARATE_AUDIO else AudioTrackMode.NORMAL_AUDIO
        val separateAudio = savedSeparate ?: (trackMode == AudioTrackMode.SEPARATE_AUDIO)
        val audioSource = prefs[Keys.AUDIO_CAPTURE_SOURCE]
            ?.let { name -> AudioCaptureSource.entries.firstOrNull { it.name == name } }
            ?: if (mode == RecordingMode.VIDEO_ONLY) AudioCaptureSource.NONE else AudioCaptureSource.BOTH

        ClotchSettings(
            resolution = prefs[Keys.RESOLUTION]
                ?.let { name -> VideoResolution.entries.firstOrNull { it.name == name } }
                ?: VideoResolution.FHD_1080P,
            fps = prefs[Keys.FPS]
                ?.let { name -> FrameRateOption.entries.firstOrNull { it.name == name } }
                ?: FrameRateOption.FPS_60,
            bitrate = prefs[Keys.BITRATE]
                ?.let { name -> BitrateOption.entries.firstOrNull { it.name == name } }
                ?: BitrateOption.MBPS_20,
            orientation = prefs[Keys.ORIENTATION]
                ?.let { name -> OrientationOption.entries.firstOrNull { it.name == name } }
                ?: OrientationOption.AUTO,
            countdown = prefs[Keys.COUNTDOWN]
                ?.let { name -> CountdownOption.entries.firstOrNull { it.name == name } }
                ?: CountdownOption.OFF,
            videoFormat = prefs[Keys.VIDEO_FORMAT]
                ?.let { name -> VideoFormat.entries.firstOrNull { it.name == name } }
                ?: VideoFormat.MP4,
            recordingMode = mode,
            audioCaptureSource = audioSource,
            audioTrackMode = trackMode,
            recordAudioSeparately = separateAudio,
            recordMicAudio = (mode == RecordingMode.AUDIO_AND_VIDEO && audioSource != AudioCaptureSource.GAME_ONLY) || separateAudio,
            showQuickNotification = prefs[Keys.SHOW_QUICK_NOTIFICATION] ?: true,
            gameName = prefs[Keys.GAME_NAME]?.takeIf { it.isNotBlank() } ?: "Mobile Game",
            discordRpcEnabled = prefs[Keys.DISCORD_RPC_ENABLED] ?: true,
            discordToken = prefs[Keys.DISCORD_TOKEN] ?: "",
            geminiApiKey = prefs[Keys.GEMINI_API_KEY] ?: ""
        )
    }

    suspend fun setResolution(resolution: VideoResolution) {
        context.clotchDataStore.edit { it[Keys.RESOLUTION] = resolution.name }
    }

    suspend fun setFps(fps: FrameRateOption) {
        context.clotchDataStore.edit { it[Keys.FPS] = fps.name }
    }

    suspend fun setBitrate(bitrate: BitrateOption) {
        context.clotchDataStore.edit { it[Keys.BITRATE] = bitrate.name }
    }

    suspend fun setOrientation(orientation: OrientationOption) {
        context.clotchDataStore.edit { it[Keys.ORIENTATION] = orientation.name }
    }

    suspend fun setCountdown(countdown: CountdownOption) {
        context.clotchDataStore.edit { it[Keys.COUNTDOWN] = countdown.name }
    }

    suspend fun setVideoFormat(format: VideoFormat) {
        context.clotchDataStore.edit { it[Keys.VIDEO_FORMAT] = format.name }
    }

    suspend fun setRecordingMode(mode: RecordingMode) {
        context.clotchDataStore.edit {
            it[Keys.RECORDING_MODE] = mode.name
            val existingSource = it[Keys.AUDIO_CAPTURE_SOURCE]
                ?.let { name -> AudioCaptureSource.entries.firstOrNull { s -> s.name == name } }
            if (mode == RecordingMode.AUDIO_AND_VIDEO && (existingSource == null || existingSource == AudioCaptureSource.NONE)) {
                it[Keys.AUDIO_CAPTURE_SOURCE] = AudioCaptureSource.BOTH.name
            }
            it[Keys.RECORD_MIC_AUDIO] = mode == RecordingMode.AUDIO_AND_VIDEO ||
                (it[Keys.RECORD_AUDIO_SEPARATELY] ?: false)
        }
    }

    suspend fun setAudioCaptureSource(source: AudioCaptureSource) {
        context.clotchDataStore.edit {
            it[Keys.AUDIO_CAPTURE_SOURCE] = source.name
            if (source == AudioCaptureSource.NONE) {
                it[Keys.RECORDING_MODE] = RecordingMode.VIDEO_ONLY.name
                it[Keys.RECORD_MIC_AUDIO] = it[Keys.RECORD_AUDIO_SEPARATELY] ?: false
            } else {
                it[Keys.RECORDING_MODE] = RecordingMode.AUDIO_AND_VIDEO.name
                it[Keys.RECORD_MIC_AUDIO] = source == AudioCaptureSource.MIC_ONLY || source == AudioCaptureSource.BOTH
            }
        }
    }

    suspend fun setAudioTrackMode(trackMode: AudioTrackMode) {
        context.clotchDataStore.edit {
            it[Keys.AUDIO_TRACK_MODE] = trackMode.name
            val separate = trackMode == AudioTrackMode.SEPARATE_AUDIO
            it[Keys.RECORD_AUDIO_SEPARATELY] = separate
            val mode = it[Keys.RECORDING_MODE]
                ?.let { name -> RecordingMode.entries.firstOrNull { m -> m.name == name } }
                ?: RecordingMode.AUDIO_AND_VIDEO
            it[Keys.RECORD_MIC_AUDIO] = mode == RecordingMode.AUDIO_AND_VIDEO || separate
        }
    }

    suspend fun setRecordAudioSeparately(enabled: Boolean) {
        context.clotchDataStore.edit {
            it[Keys.RECORD_AUDIO_SEPARATELY] = enabled
            it[Keys.AUDIO_TRACK_MODE] = if (enabled) {
                AudioTrackMode.SEPARATE_AUDIO.name
            } else {
                AudioTrackMode.NORMAL_AUDIO.name
            }
            val mode = it[Keys.RECORDING_MODE]
                ?.let { name -> RecordingMode.entries.firstOrNull { m -> m.name == name } }
                ?: RecordingMode.AUDIO_AND_VIDEO
            it[Keys.RECORD_MIC_AUDIO] = mode == RecordingMode.AUDIO_AND_VIDEO || enabled
        }
    }

    suspend fun setRecordMicAudio(enabled: Boolean) {
        context.clotchDataStore.edit {
            it[Keys.RECORD_MIC_AUDIO] = enabled
            it[Keys.RECORDING_MODE] = if (enabled) {
                RecordingMode.AUDIO_AND_VIDEO.name
            } else {
                RecordingMode.VIDEO_ONLY.name
            }
            it[Keys.AUDIO_CAPTURE_SOURCE] = if (enabled) {
                AudioCaptureSource.BOTH.name
            } else {
                AudioCaptureSource.NONE.name
            }
        }
    }

    suspend fun setShowQuickNotification(enabled: Boolean) {
        context.clotchDataStore.edit { it[Keys.SHOW_QUICK_NOTIFICATION] = enabled }
    }

    suspend fun setGameName(name: String) {
        context.clotchDataStore.edit { it[Keys.GAME_NAME] = name.trim().ifEmpty { "Mobile Game" } }
    }

    suspend fun setDiscordRpcEnabled(enabled: Boolean) {
        context.clotchDataStore.edit { it[Keys.DISCORD_RPC_ENABLED] = enabled }
    }

    suspend fun setDiscordToken(token: String) {
        context.clotchDataStore.edit { it[Keys.DISCORD_TOKEN] = token.trim() }
    }

    suspend fun setGeminiApiKey(apiKey: String) {
        context.clotchDataStore.edit { it[Keys.GEMINI_API_KEY] = apiKey.trim() }
    }
}
