package com.example.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ClotchApplication
import com.example.data.AudioCaptureSource
import com.example.data.BitrateOption
import com.example.data.ClotchSettings
import com.example.data.CountdownOption
import com.example.data.FrameRateOption
import com.example.data.OrientationOption
import com.example.data.RecordedClip
import com.example.data.RecordingMode
import com.example.data.VideoFormat
import com.example.data.VideoResolution
import com.example.discord.DetectedAppsResult
import com.example.discord.DiscordConnectionState
import com.example.discord.DiscordPresencePayload
import com.example.discord.DiscordProfileManager
import com.example.discord.DiscordUserProfile
import com.example.recording.RecorderState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ClotchViewModel(application: Application) : AndroidViewModel(application) {

    private val controller = (application as ClotchApplication).recordingController
    private val settingsRepo = controller.settingsRepository
    private val profileManager = DiscordProfileManager(application)

    val settings: StateFlow<ClotchSettings> = controller.settings
    val recorderState: StateFlow<RecorderState> = controller.recorderState
    val clips: StateFlow<List<RecordedClip>> = controller.clips
    val discordConnectionState: StateFlow<DiscordConnectionState> =
        controller.discordConnectionState
    val discordPresence: StateFlow<DiscordPresencePayload?> =
        controller.discordPresence
    val detectedApps: StateFlow<DetectedAppsResult> = controller.detectedApps
    val hasUsageStatsPermission: StateFlow<Boolean> = controller.hasUsageStatsPermission

    val discordProfile: StateFlow<DiscordUserProfile> = profileManager.profileFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = DiscordUserProfile()
        )

    val oauthClientId: StateFlow<String> = profileManager.oauthClientIdFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ""
        )

    private val _isFetchingProfile = MutableStateFlow(false)
    val isFetchingProfile: StateFlow<Boolean> = _isFetchingProfile.asStateFlow()

    fun syncIdleNotification() {
        controller.syncIdleNotification(settings.value)
    }

    fun refreshUsageStatsPermission() {
        if (controller.refreshUsageStatsPermission()) {
            controller.scanAppsNow(showFeedbackToast = false)
        }
    }

    fun scanAppsNow() {
        controller.scanAppsNow(showFeedbackToast = true)
    }

    fun startCountdownAndCapture(onReadyToRequestProjection: () -> Unit) {
        controller.startWithOptionalCountdown(onReadyToRequestProjection)
    }

    fun cancelCountdown() {
        controller.cancelCountdown()
    }

    fun onMediaProjectionApproved(resultCode: Int, data: Intent) {
        controller.startScreenRecordService(resultCode, data)
    }

    fun onMediaProjectionDenied() {
        controller.showToast("Screen capture permission denied")
    }

    fun stopRecording() {
        controller.stopRecording()
    }

    fun pauseRecording() {
        controller.pauseRecording()
    }

    fun resumeRecording() {
        controller.resumeRecording()
    }

    fun setResolution(resolution: VideoResolution) {
        viewModelScope.launch {
            settingsRepo.setResolution(resolution)
            controller.syncIdleNotification(settings.value.copy(resolution = resolution))
        }
    }

    fun setFps(fps: FrameRateOption) {
        viewModelScope.launch {
            settingsRepo.setFps(fps)
            controller.syncIdleNotification(settings.value.copy(fps = fps))
        }
    }

    fun setBitrate(bitrate: BitrateOption) {
        viewModelScope.launch {
            settingsRepo.setBitrate(bitrate)
            controller.syncIdleNotification(settings.value.copy(bitrate = bitrate))
        }
    }

    fun setOrientation(orientation: OrientationOption) {
        viewModelScope.launch {
            settingsRepo.setOrientation(orientation)
        }
    }

    fun setCountdown(countdown: CountdownOption) {
        viewModelScope.launch {
            settingsRepo.setCountdown(countdown)
        }
    }

    fun setVideoFormat(format: VideoFormat) {
        viewModelScope.launch {
            settingsRepo.setVideoFormat(format)
            controller.syncIdleNotification(settings.value.copy(videoFormat = format))
        }
    }

    fun setRecordingMode(mode: RecordingMode) {
        viewModelScope.launch {
            settingsRepo.setRecordingMode(mode)
        }
    }

    fun setAudioCaptureSource(source: AudioCaptureSource) {
        viewModelScope.launch {
            settingsRepo.setAudioCaptureSource(source)
        }
    }

    fun setRecordAudioSeparately(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.setRecordAudioSeparately(enabled)
        }
    }

    fun setRecordMicAudio(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.setRecordMicAudio(enabled)
        }
    }

    fun setShowQuickNotification(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.setShowQuickNotification(enabled)
            controller.syncIdleNotification(settings.value.copy(showQuickNotification = enabled))
        }
    }

    fun setGameName(name: String) {
        viewModelScope.launch {
            settingsRepo.setGameName(name)
            controller.syncIdleNotification(settings.value.copy(gameName = name.ifBlank { "Mobile Game" }))
            controller.pushCompositePresence()
        }
    }

    fun setDiscordRpcEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.setDiscordRpcEnabled(enabled)
            if (!enabled) {
                controller.discordRpcClient.disconnect(clearPresence = true)
            } else {
                controller.pushCompositePresence()
            }
        }
    }

    fun setDiscordToken(token: String) {
        viewModelScope.launch {
            settingsRepo.setDiscordToken(token)
        }
    }

    fun setGeminiApiKey(apiKey: String) {
        viewModelScope.launch {
            settingsRepo.setGeminiApiKey(apiKey)
        }
    }

    fun connectDiscordRpc(tokenOverride: String? = null) {
        controller.connectDiscordRpc(tokenOverride)
    }

    fun disconnectDiscordRpc() {
        controller.disconnectDiscordRpc()
    }

    fun signInWithDiscordToken(token: String, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _isFetchingProfile.value = true
            val result = profileManager.fetchAndSaveProfile(token, isBearerOAuth = false)
            _isFetchingProfile.value = false
            if (result.isSuccess) {
                settingsRepo.setDiscordToken(token)
                controller.connectDiscordRpc(token)
                onComplete(true)
            } else {
                onComplete(false)
            }
        }
    }

    fun buildOAuthUrlAndSaveClientId(clientId: String): String {
        viewModelScope.launch {
            profileManager.saveOAuthClientId(clientId)
        }
        return profileManager.buildOAuthAuthorizeUrl(clientId)
    }

    fun handleOAuthAccessToken(accessToken: String) {
        viewModelScope.launch {
            _isFetchingProfile.value = true
            profileManager.fetchAndSaveProfile(accessToken, isBearerOAuth = true)
            _isFetchingProfile.value = false
        }
    }

    fun signOutDiscord() {
        viewModelScope.launch {
            profileManager.clearProfile()
            settingsRepo.setDiscordToken("")
            controller.disconnectDiscordRpc()
        }
    }

    fun refreshClips() {
        controller.refreshClips()
    }

    fun deleteClip(clip: RecordedClip) {
        controller.deleteClip(clip)
    }

    fun showToast(message: String) {
        controller.showToast(message)
    }

    override fun onCleared() {
        super.onCleared()
        profileManager.shutdown()
    }
}
