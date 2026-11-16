package com.example.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ClotchApplication
import com.example.data.BitrateOption
import com.example.data.ClotchSettings
import com.example.data.CountdownOption
import com.example.data.FrameRateOption
import com.example.data.OrientationOption
import com.example.data.RecordedClip
import com.example.data.VideoResolution
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
            val state = recorderState.value
            if (state is RecorderState.Recording || state is RecorderState.Paused) {
                val isPaused = state is RecorderState.Paused
                val fps = when (state) {
                    is RecorderState.Recording -> state.activeFps
                    is RecorderState.Paused -> state.activeFps
                    else -> settings.value.fps.fps
                }
                val width = when (state) {
                    is RecorderState.Recording -> state.activeWidth
                    is RecorderState.Paused -> state.activeWidth
                    else -> settings.value.resolution.shortSide
                }
                val height = when (state) {
                    is RecorderState.Recording -> state.activeHeight
                    is RecorderState.Paused -> state.activeHeight
                    else -> settings.value.resolution.longSide
                }
                val bitrate = when (state) {
                    is RecorderState.Recording -> state.activeBitrateMbps
                    is RecorderState.Paused -> state.activeBitrateMbps
                    else -> settings.value.bitrate.mbps
                }
                controller.discordRpcClient.updatePresence(
                    payload = DiscordPresencePayload(
                        isRecording = true,
                        isPaused = isPaused,
                        gameName = name.ifBlank { "Mobile Game" },
                        fps = fps,
                        resolutionLabel = "${minOf(width, height)}p",
                        bitrateMbps = bitrate,
                        startedAtEpochMs = System.currentTimeMillis()
                    ),
                    rpcEnabled = settings.value.discordRpcEnabled,
                    token = settings.value.discordToken
                )
            }
        }
    }

    fun setDiscordRpcEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.setDiscordRpcEnabled(enabled)
            if (!enabled) {
                controller.discordRpcClient.disconnect(clearPresence = true)
            }
        }
    }

    fun setDiscordToken(token: String) {
        viewModelScope.launch {
            settingsRepo.setDiscordToken(token)
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
}
