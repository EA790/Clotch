package com.example.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ClotchSettings
import com.example.recording.RecorderState
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.ClotchBlue
import com.example.ui.theme.CrimsonAccent
import com.example.ui.theme.CrimsonGlow
import com.example.ui.theme.ElevatedSlate
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.SlateCard
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import com.example.ui.theme.WarningAmberMuted
import java.util.Locale

@Composable
fun CaptureScreen(
    settings: ClotchSettings,
    recorderState: RecorderState,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onPauseCapture: () -> Unit,
    onResumeCapture: () -> Unit,
    onCancelCountdown: () -> Unit,
    onToggleQuickNotification: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenClips: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val isRecording = recorderState is RecorderState.Recording
    val isPaused = recorderState is RecorderState.Paused

    val infiniteTransition = rememberInfiniteTransition(label = "rec_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Screen Recording",
                style = MaterialTheme.typography.headlineMedium,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            // Main Recorder Card styled in Clotch Midnight Navy & SlateCard
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = SlateCard,
                shape = RoundedCornerShape(18.dp),
                border = when {
                    isRecording -> BorderStroke(1.5.dp, CrimsonAccent.copy(alpha = 0.7f))
                    isPaused -> BorderStroke(1.5.dp, WarningAmber.copy(alpha = 0.7f))
                    else -> null
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = when (recorderState) {
                                    is RecorderState.Recording -> "LIVE RECORDING • ${settings.gameName}"
                                    is RecorderState.Paused -> "RECORDING PAUSED"
                                    is RecorderState.Countdown -> "STARTING CAPTURE"
                                    is RecorderState.Error -> "CAPTURE ERROR"
                                    RecorderState.Idle -> "READY TO RECORD"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isRecording) CrimsonAccent else TextSecondary
                            )

                            val readoutText = when (recorderState) {
                                is RecorderState.Recording -> formatElapsedTime(recorderState.elapsedMs)
                                is RecorderState.Paused -> formatElapsedTime(recorderState.elapsedMs)
                                is RecorderState.Countdown -> "00:00:0${recorderState.secondsLeft}"
                                is RecorderState.Error -> "00:00:00"
                                RecorderState.Idle -> "00:00:00"
                            }

                            Text(
                                text = readoutText,
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 38.sp,
                                letterSpacing = 1.5.sp,
                                color = TextPrimary
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isRecording) CrimsonAccent.copy(alpha = pulseAlpha * 0.25f)
                                    else ElevatedSlate
                                )
                                .border(
                                    width = 1.5.dp,
                                    color = if (isRecording) CrimsonAccent else HairlineBorder,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FiberManualRecord,
                                contentDescription = "Recording status indicator",
                                tint = if (isRecording) CrimsonAccent else TextMuted,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val activeRes = when (recorderState) {
                            is RecorderState.Recording -> "${minOf(recorderState.activeWidth, recorderState.activeHeight)}p"
                            is RecorderState.Paused -> "${minOf(recorderState.activeWidth, recorderState.activeHeight)}p"
                            else -> settings.resolution.shortLabel
                        }
                        val activeFps = when (recorderState) {
                            is RecorderState.Recording -> "${recorderState.activeFps} FPS"
                            is RecorderState.Paused -> "${recorderState.activeFps} FPS"
                            else -> settings.fps.label
                        }
                        val activeBitrate = when (recorderState) {
                            is RecorderState.Recording -> "${recorderState.activeBitrateMbps} Mbps"
                            is RecorderState.Paused -> "${recorderState.activeBitrateMbps} Mbps"
                            else -> settings.bitrate.label
                        }

                        CaptureSpecChip(activeRes, highlighted = true)
                        CaptureSpecChip(activeFps, highlighted = true)
                        CaptureSpecChip(settings.videoFormat.label, highlighted = false)
                        CaptureSpecChip(activeBitrate, highlighted = false)
                        CaptureSpecChip(settings.orientation.label, highlighted = false)
                        val audioLabel = when {
                            settings.recordingMode == com.example.data.RecordingMode.AUDIO_AND_VIDEO -> "Audio+Video"
                            settings.recordAudioSeparately -> "Separate Audio"
                            else -> "Video Only"
                        }
                        CaptureSpecChip(
                            text = audioLabel,
                            icon = if (settings.needsMicPermission) Icons.Filled.Mic else Icons.Filled.MicOff,
                            highlighted = false
                        )
                    }

                    if (recorderState is RecorderState.Error) {
                        Surface(
                            color = CrimsonGlow,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, CrimsonAccent.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = recorderState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when {
                            isRecording || isPaused -> {
                                OutlinedButton(
                                    onClick = if (isPaused) onResumeCapture else onPauseCapture,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(54.dp)
                                        .testTag("pause_resume_button"),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, HairlineBorder),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = ElevatedSlate,
                                        contentColor = TextPrimary
                                    )
                                ) {
                                    Icon(
                                        imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                        contentDescription = if (isPaused) "Resume" else "Pause"
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (isPaused) "Resume" else "Pause")
                                }

                                Button(
                                    onClick = onStopCapture,
                                    modifier = Modifier
                                        .weight(1.3f)
                                        .height(54.dp)
                                        .testTag("record_toggle_button"),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = CrimsonAccent,
                                        contentColor = TextPrimary
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Stop,
                                        contentDescription = "Stop recording"
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Stop Capture")
                                }
                            }

                            recorderState is RecorderState.Countdown -> {
                                OutlinedButton(
                                    onClick = onCancelCountdown,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(54.dp)
                                        .testTag("record_toggle_button"),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, WarningAmber),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = WarningAmberMuted,
                                        contentColor = TextPrimary
                                    )
                                ) {
                                    Icon(Icons.Filled.Stop, contentDescription = "Cancel countdown")
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Cancel Countdown (${recorderState.secondsLeft}s)")
                                }
                            }

                            else -> {
                                Button(
                                    onClick = onStartCapture,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(54.dp)
                                        .testTag("record_toggle_button"),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ClotchBlue,
                                        contentColor = TextPrimary
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PlayArrow,
                                        contentDescription = "Start recording"
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Start Screen Recording",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontSize = 15.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Notification Controls Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = SlateCard,
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(ElevatedSlate),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (settings.showQuickNotification) {
                                    Icons.Filled.NotificationsActive
                                } else {
                                    Icons.Outlined.Notifications
                                },
                                contentDescription = null,
                                tint = if (settings.showQuickNotification) SignalEmerald else TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Notification Quick Controls",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary
                            )
                            Text(
                                text = "Start, pause & stop from notification shade",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }

                    Switch(
                        checked = settings.showQuickNotification,
                        onCheckedChange = onToggleQuickNotification,
                        modifier = Modifier.testTag("quick_notification_switch"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextPrimary,
                            checkedTrackColor = SignalEmerald,
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = ElevatedSlate,
                            uncheckedBorderColor = HairlineBorder
                        )
                    )
                }
            }

            // Quick shortcuts row to Settings & Saved Clips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    onClick = onOpenClips,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    color = SlateCard,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VideoLibrary,
                            contentDescription = "Saved Clips",
                            tint = ClotchBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Saved Clips",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                    }
                }

                Surface(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    color = SlateCard,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = "Encoder Settings",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Quality Settings",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureSpecChip(
    text: String,
    highlighted: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Surface(
        color = if (highlighted) ElevatedSlate else CarbonSurface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, HairlineBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(13.dp)
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = if (highlighted) TextPrimary else TextSecondary
            )
        }
    }
}

private fun formatElapsedTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}
