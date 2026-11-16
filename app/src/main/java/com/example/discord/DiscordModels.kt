package com.example.discord

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

data class DiscordPresencePayload(
    val isRecording: Boolean,
    val isPaused: Boolean,
    val gameName: String,
    val fps: Int,
    val resolutionLabel: String,
    val bitrateMbps: Int,
    val startedAtEpochMs: Long
) {
    fun formatStatusText(): String {
        val cleanGame = gameName.trim().ifEmpty { "Mobile Game" }
        return if (isPaused) {
            "Paused $cleanGame at ${fps}FPS"
        } else {
            "Recording $cleanGame at ${fps}FPS"
        }
    }

    fun formatSubDetails(): String {
        return "$resolutionLabel • ${bitrateMbps} Mbps • Clotch"
    }
}
