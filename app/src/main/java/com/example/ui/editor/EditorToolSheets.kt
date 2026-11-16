package com.example.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.RecordedClip
import com.example.data.VideoFormat
import com.example.editor.model.EditorExportConfig
import com.example.editor.model.EditorExportResolution
import com.example.editor.model.EditorExportState
import com.example.editor.model.TimelineAudioClip
import com.example.editor.model.TimelineVideoClip
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.ClotchBlue
import com.example.ui.theme.ClotchBlueMuted
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
import java.util.Locale

@Composable
fun SelectedClipInspectorPanel(
    selectedVideoClip: TimelineVideoClip?,
    selectedVideoIndex: Int,
    totalVideoClips: Int,
    selectedAudioClip: TimelineAudioClip?,
    onBeginContinuousEdit: () -> Unit,
    onCommitContinuousEdit: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onFadeInChange: (Long) -> Unit,
    onFadeOutChange: (Long) -> Unit,
    onExtractAudio: () -> Unit,
    onMoveVideoOrder: (fromIndex: Int, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeTitle = selectedVideoClip?.displayName ?: selectedAudioClip?.displayName
    val activeVolume = selectedVideoClip?.volume ?: selectedAudioClip?.volume ?: 1f
    val activeMuted = selectedVideoClip?.isMuted ?: selectedAudioClip?.isMuted ?: false
    val activeFadeInMs = selectedVideoClip?.fadeInMs ?: selectedAudioClip?.fadeInMs ?: 0L
    val activeFadeOutMs = selectedVideoClip?.fadeOutMs ?: selectedAudioClip?.fadeOutMs ?: 0L
    val activeDurationMs = selectedVideoClip?.effectiveDurationMs ?: selectedAudioClip?.effectiveDurationMs ?: 0L

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("clip_inspector_panel"),
        color = SlateCard,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, HairlineBorder)
    ) {
        if (activeTitle == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.AudioFile,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Tap any video or audio clip on the timeline to adjust volume, fades, mute, extract audio, or reorder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header with Clip Name + Mute Toggle + Reorder arrows
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                "Duration: %.1fs • Audio: %s",
                                activeDurationMs / 1000f,
                                if (activeMuted) "Muted" else "${(activeVolume * 100).toInt()}%"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }

                    if (selectedVideoClip != null && totalVideoClips > 1) {
                        IconButton(
                            onClick = { onMoveVideoOrder(selectedVideoIndex, selectedVideoIndex - 1) },
                            enabled = selectedVideoIndex > 0,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("move_clip_left_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Move clip earlier",
                                tint = if (selectedVideoIndex > 0) TextPrimary else TextMuted
                            )
                        }
                        IconButton(
                            onClick = { onMoveVideoOrder(selectedVideoIndex, selectedVideoIndex + 1) },
                            enabled = selectedVideoIndex < totalVideoClips - 1,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("move_clip_right_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Move clip later",
                                tint = if (selectedVideoIndex < totalVideoClips - 1) TextPrimary else TextMuted
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onToggleMute,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("toggle_mute_button"),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            1.dp,
                            if (activeMuted) CrimsonAccent else HairlineBorder
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (activeMuted) CrimsonGlow else CarbonSurface,
                            contentColor = if (activeMuted) CrimsonAccent else TextPrimary
                        )
                    ) {
                        Icon(
                            imageVector = if (activeMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                            contentDescription = if (activeMuted) "Unmute clip" else "Mute clip",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (activeMuted) "Muted" else "Mute")
                    }
                }

                // Volume Slider (0%..200%)
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Volume",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                        Text(
                            text = "${(activeVolume * 100).toInt()}%",
                            fontFamily = JetBrainsMonoFamily,
                            style = MaterialTheme.typography.labelMedium,
                            color = ClotchBlue
                        )
                    }
                    Slider(
                        value = activeVolume,
                        onValueChange = {
                            onBeginContinuousEdit()
                            onVolumeChange(it)
                        },
                        onValueChangeFinished = { onCommitContinuousEdit() },
                        valueRange = 0f..2f,
                        modifier = Modifier.testTag("clip_volume_slider"),
                        colors = SliderDefaults.colors(
                            thumbColor = ClotchBlue,
                            activeTrackColor = ClotchBlue,
                            inactiveTrackColor = ElevatedSlate
                        )
                    )
                }

                // Audio Fade In & Fade Out Sliders
                val maxFadeSec = ((activeDurationMs / 2L).coerceAtMost(5000L) / 1000f).coerceAtLeast(0.5f)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = String.format(Locale.US, "Fade In: %.1fs", activeFadeInMs / 1000f),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Slider(
                            value = (activeFadeInMs / 1000f).coerceIn(0f, maxFadeSec),
                            onValueChange = {
                                onBeginContinuousEdit()
                                onFadeInChange((it * 1000f).toLong())
                            },
                            onValueChangeFinished = { onCommitContinuousEdit() },
                            valueRange = 0f..maxFadeSec,
                            modifier = Modifier.testTag("clip_fade_in_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = SignalEmerald,
                                activeTrackColor = SignalEmerald,
                                inactiveTrackColor = ElevatedSlate
                            )
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = String.format(Locale.US, "Fade Out: %.1fs", activeFadeOutMs / 1000f),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Slider(
                            value = (activeFadeOutMs / 1000f).coerceIn(0f, maxFadeSec),
                            onValueChange = {
                                onBeginContinuousEdit()
                                onFadeOutChange((it * 1000f).toLong())
                            },
                            onValueChangeFinished = { onCommitContinuousEdit() },
                            valueRange = 0f..maxFadeSec,
                            modifier = Modifier.testTag("clip_fade_out_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = SignalEmerald,
                                activeTrackColor = SignalEmerald,
                                inactiveTrackColor = ElevatedSlate
                            )
                        )
                    }
                }

                // Extract Audio action for video clips
                if (selectedVideoClip != null) {
                    Button(
                        onClick = onExtractAudio,
                        enabled = !selectedVideoClip.audioDetached,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("extract_audio_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ClotchBlueMuted,
                            contentColor = ClotchBlue,
                            disabledContainerColor = ElevatedSlate,
                            disabledContentColor = TextMuted
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AudioFile,
                            contentDescription = "Extract audio track",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (selectedVideoClip.audioDetached) {
                                "Audio Extracted to Audio Track"
                            } else {
                                "Extract Audio Track"
                            },
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SavedClipsPickerDialog(
    clips: List<RecordedClip>,
    onPickClip: (RecordedClip) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateCard,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("Add Saved Clotch Clip") },
        text = {
            if (clips.isEmpty()) {
                Text(
                    text = "No saved recordings found in Movies/Clotch yet. Record gameplay or import a video from your gallery.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(clips, key = { it.id }) { clip ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPickClip(clip) }
                                .testTag("pick_saved_clip_${clip.id}"),
                            color = CarbonSurface,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, HairlineBorder)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Movie,
                                    contentDescription = null,
                                    tint = ClotchBlue,
                                    modifier = Modifier.size(22.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = clip.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val sec = (clip.durationMs / 1000L).coerceAtLeast(0L)
                                    Text(
                                        text = "${clip.width}x${clip.height} • ${sec / 60}:${String.format(Locale.US, "%02d", sec % 60)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = TextSecondary)
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportEditorDialog(
    exportState: EditorExportState,
    onStartExport: (EditorExportConfig) -> Unit,
    onCancelExport: () -> Unit,
    onDismiss: () -> Unit,
    onOpenClips: () -> Unit
) {
    var selectedFormat by remember { mutableStateOf(VideoFormat.MP4) }
    var selectedResolution by remember { mutableStateOf(EditorExportResolution.FHD_1080P) }
    var selectedFps by remember { mutableIntStateOf(60) }
    var selectedBitrate by remember { mutableIntStateOf(16) }

    AlertDialog(
        onDismissRequest = {
            if (exportState !is EditorExportState.Exporting) onDismiss()
        },
        containerColor = SlateCard,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Text(
                text = when (exportState) {
                    is EditorExportState.Idle -> "Export Video"
                    is EditorExportState.Exporting -> "Rendering Timeline..."
                    is EditorExportState.Completed -> "Export Complete"
                    is EditorExportState.Error -> "Export Failed"
                }
            )
        },
        text = {
            when (exportState) {
                is EditorExportState.Idle -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Container Format", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(VideoFormat.MP4, VideoFormat.MKV).forEach { fmt ->
                                ExportChoiceChip(
                                    label = fmt.label,
                                    selected = selectedFormat == fmt,
                                    onClick = { selectedFormat = fmt }
                                )
                            }
                        }

                        Text("Resolution", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EditorExportResolution.entries.forEach { res ->
                                ExportChoiceChip(
                                    label = res.label,
                                    selected = selectedResolution == res,
                                    onClick = { selectedResolution = res }
                                )
                            }
                        }

                        Text("Frame Rate & Bitrate", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(30, 60).forEach { fps ->
                                ExportChoiceChip(
                                    label = "$fps FPS",
                                    selected = selectedFps == fps,
                                    onClick = { selectedFps = fps }
                                )
                            }
                            listOf(8, 16, 24).forEach { mbps ->
                                ExportChoiceChip(
                                    label = "$mbps Mbps",
                                    selected = selectedBitrate == mbps,
                                    onClick = { selectedBitrate = mbps }
                                )
                            }
                        }
                    }
                }
                is EditorExportState.Exporting -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LinearProgressIndicator(
                            progress = { exportState.progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = ClotchBlue,
                            trackColor = ElevatedSlate
                        )
                        Text(
                            text = exportState.statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                    }
                }
                is EditorExportState.Completed -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Export succeeded",
                            tint = SignalEmerald,
                            modifier = Modifier.size(28.dp)
                        )
                        Column {
                            Text(
                                text = "Saved to Movies/Clotch",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary
                            )
                            Text(
                                text = exportState.fileName,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
                is EditorExportState.Error -> {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = "Export error",
                            tint = CrimsonAccent,
                            modifier = Modifier.size(26.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = exportState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "Crash & FFmpeg log: ${exportState.logPath}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (exportState) {
                is EditorExportState.Idle -> {
                    Button(
                        onClick = {
                            onStartExport(
                                EditorExportConfig(
                                    format = selectedFormat,
                                    resolution = selectedResolution,
                                    fps = selectedFps,
                                    bitrateMbps = selectedBitrate
                                )
                            )
                        },
                        modifier = Modifier.testTag("confirm_export_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ClotchBlue,
                            contentColor = TextPrimary
                        )
                    ) {
                        Text("Render & Save")
                    }
                }
                is EditorExportState.Exporting -> {
                    TextButton(onClick = onCancelExport) {
                        Text("Cancel", color = CrimsonAccent)
                    }
                }
                is EditorExportState.Completed -> {
                    Button(
                        onClick = onOpenClips,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ClotchBlue,
                            contentColor = TextPrimary
                        )
                    ) {
                        Text("View in Clips")
                    }
                }
                is EditorExportState.Error -> {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElevatedSlate,
                            contentColor = TextPrimary
                        )
                    ) {
                        Text("Close")
                    }
                }
            }
        },
        dismissButton = {
            if (exportState is EditorExportState.Idle || exportState is EditorExportState.Completed) {
                TextButton(onClick = onDismiss) {
                    Text("Close", color = TextSecondary)
                }
            }
        }
    )
}

@Composable
private fun ExportChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (selected) ClotchBlueMuted else CarbonSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (selected) ClotchBlue else HairlineBorder)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) ClotchBlue else TextSecondary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}
