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
        val RECORD_MIC_AUDIO = booleanPreferencesKey("record_mic_audio")
        val SHOW_QUICK_NOTIFICATION = booleanPreferencesKey("show_quick_notification")
        val GAME_NAME = stringPreferencesKey("game_name")
        val DISCORD_RPC_ENABLED = booleanPreferencesKey("discord_rpc_enabled")
        val DISCORD_TOKEN = stringPreferencesKey("discord_token")
    }

    val settingsFlow: Flow<ClotchSettings> = context.clotchDataStore.data.map { prefs ->
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
            recordMicAudio = prefs[Keys.RECORD_MIC_AUDIO] ?: false,
            showQuickNotification = prefs[Keys.SHOW_QUICK_NOTIFICATION] ?: true,
            gameName = prefs[Keys.GAME_NAME]?.takeIf { it.isNotBlank() } ?: "Mobile Game",
            discordRpcEnabled = prefs[Keys.DISCORD_RPC_ENABLED] ?: true,
            discordToken = prefs[Keys.DISCORD_TOKEN] ?: ""
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

    suspend fun setRecordMicAudio(enabled: Boolean) {
        context.clotchDataStore.edit { it[Keys.RECORD_MIC_AUDIO] = enabled }
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
}
