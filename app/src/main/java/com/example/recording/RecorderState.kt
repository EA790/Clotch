package com.example.recording

sealed interface RecorderState {
    data object Idle : RecorderState
    data class Countdown(val secondsLeft: Int) : RecorderState
    data class Recording(
        val startedAtMs: Long,
        val elapsedMs: Long,
        val fileName: String,
        val activeWidth: Int,
        val activeHeight: Int,
        val activeFps: Int,
        val activeBitrateMbps: Int
    ) : RecorderState

    data class Paused(
        val elapsedMs: Long,
        val fileName: String,
        val activeWidth: Int,
        val activeHeight: Int,
        val activeFps: Int,
        val activeBitrateMbps: Int
    ) : RecorderState

    data class Error(val message: String) : RecorderState
}
