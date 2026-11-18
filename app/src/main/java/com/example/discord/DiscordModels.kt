package com.example.discord

import androidx.compose.runtime.Immutable

@Immutable
sealed class DiscordConnectionState {
    data object Disconnected : DiscordConnectionState()
    data object Connecting : DiscordConnectionState()
    data class Connected(
        val username: String,
        val activeStatusText: String? = null
    ) : DiscordConnectionState()
    data class Error(
        val message: String,
        val isAuthError: Boolean = false
    ) : DiscordConnectionState()
}

@Immutable
data class DiscordPresencePayload(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val gameName: String = "Mobile Game",
    val fps: Int = 60,
    val resolutionLabel: String = "1080p",
    val bitrateMbps: Int = 20,
    val startedAtEpochMs: Long = 0L,
    val detectedAppName: String? = null,
    val detectedAppPackage: String? = null,
    val isLarkPlayerRunning: Boolean = false,
    val largeImageUrl: String = "https://example.com/cat_clotch.png",
    val smallImageUrl: String? = null
) {
    fun effectiveGameTitle(): String {
        return detectedAppName?.takeIf { it.isNotBlank() }
            ?: gameName.trim().ifEmpty { "Mobile Game" }
    }

    fun activityName(): String {
        return when {
            detectedAppName != null -> detectedAppName
            isRecording -> "Clotch"
            isLarkPlayerRunning -> "Lark Player"
            else -> "Clotch"
        }
    }

    fun activityType(): Int {
        return if (!isRecording && detectedAppName == null && isLarkPlayerRunning) {
            2 // Listening
        } else {
            0 // Playing
        }
    }

    fun formatStatusText(): String {
        val activeTitle = effectiveGameTitle()
        return when {
            isRecording && isPaused -> "Paused $activeTitle at ${fps}FPS"
            isRecording -> "Recording $activeTitle at ${fps}FPS"
            detectedAppName != null -> "Playing $detectedAppName"
            isLarkPlayerRunning -> "Listening to Music"
            else -> "Idle"
        }
    }

    fun formatSubDetails(): String {
        return when {
            isRecording && isLarkPlayerRunning ->
                "Listening on Lark Player • $resolutionLabel"
            isRecording ->
                "$resolutionLabel • ${bitrateMbps} Mbps • Clotch"
            detectedAppName != null && isLarkPlayerRunning ->
                "Listening on Lark Player"
            detectedAppName != null ->
                "Playing via Clotch"
            isLarkPlayerRunning ->
                "Lark Player • Background"
            else ->
                "No supported app detected"
        }
    }
}
