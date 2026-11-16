package com.example.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.editor.model.AutoCaptionMode
import com.example.editor.model.AutoCaptionState
import com.example.editor.model.ChromaKeyBgStyle
import com.example.editor.model.ChromaKeyConfig
import com.example.editor.model.ChromaKeyMode
import com.example.editor.model.ColorFilterPreset
import com.example.editor.model.CropMode
import com.example.editor.model.EditorAspectRatio
import com.example.editor.model.EditorFontChoice
import com.example.editor.model.EditorProject
import com.example.editor.model.TextAnimationType
import com.example.editor.model.TimelineSticker
import com.example.editor.model.TimelineTextOverlay
import com.example.editor.model.TimelineVideoClip
import com.example.editor.model.UpscaleTarget
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
import com.example.ui.theme.WarningAmber
import java.util.Locale

private val STICKER_EMOJIS = listOf("🔥", "💀", "🏆", "🎯", "⚡", "😂", "🎮", "💯", "👑", "🚀")
private val TEXT_COLORS = listOf(0xFFFFFFFFL, 0xFF2B6BFFL, 0xFFFF2E54L, 0xFF1BE47BL, 0xFFF59E0BL)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextAndCaptionsPanel(
    project: EditorProject,
    geminiApiKey: String,
    autoCaptionState: AutoCaptionState,
    onAddTextOverlay: (String, EditorFontChoice, Long, TextAnimationType, Boolean) -> Unit,
    onUpdateSelectedOverlay: (String, EditorFontChoice, Long, TextAnimationType, Boolean, Long, Float, Float) -> Unit,
    onGenerateCaptions: (AutoCaptionMode) -> Unit,
    onStopOfflineCaption: () -> Unit,
    onOpenSettingsForKey: () -> Unit,
    onAddSticker: (String) -> Unit,
    onUpdateSelectedSticker: (Float, Float, Float, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedOv = project.selectedOverlay
    val selectedSt = project.selectedSticker

    var textInput by remember(selectedOv?.id, selectedOv?.text) {
        mutableStateOf(selectedOv?.text ?: "")
    }
    var fontChoice by remember(selectedOv?.id) {
        mutableStateOf(selectedOv?.fontChoice ?: EditorFontChoice.SPACE_GROTESK)
    }
    var colorArgb by remember(selectedOv?.id) {
        mutableLongStateOf(selectedOv?.colorArgb ?: 0xFFFFFFFFL)
    }
    var animation by remember(selectedOv?.id) {
        mutableStateOf(selectedOv?.animation ?: TextAnimationType.FADE)
    }
    var hasPill by remember(selectedOv?.id) {
        mutableStateOf(selectedOv?.hasBackgroundPill ?: true)
    }
    var ovPosX by remember(selectedOv?.id) { mutableFloatStateOf(selectedOv?.posX ?: 0.5f) }
    var ovPosY by remember(selectedOv?.id) { mutableFloatStateOf(selectedOv?.posY ?: 0.8f) }
    var captionMode by remember { mutableStateOf(AutoCaptionMode.FAST_OFFLINE) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = SlateCard,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, HairlineBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (selectedOv != null) {
                    if (selectedOv.isCaption) "Edit Timeline Caption" else "Edit Text Overlay"
                } else {
                    "Text Overlay & Animation"
                },
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary
            )

            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                placeholder = { Text("Enter gamer callout or subtitle...") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("overlay_text_input"),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ClotchBlue,
                    unfocusedBorderColor = HairlineBorder,
                    focusedContainerColor = CarbonSurface,
                    unfocusedContainerColor = CarbonSurface,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EditorFontChoice.entries.forEach { fc ->
                    ToolChip(
                        label = fc.label,
                        selected = fontChoice == fc,
                        onClick = { fontChoice = fc }
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextAnimationType.entries.forEach { anim ->
                    ToolChip(
                        label = anim.label,
                        selected = animation == anim,
                        onClick = { animation = anim }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TEXT_COLORS.forEach { hex ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(hex.toInt()))
                                .border(
                                    width = if (colorArgb == hex) 2.5.dp else 1.dp,
                                    color = if (colorArgb == hex) ClotchBlue else HairlineBorder,
                                    shape = CircleShape
                                )
                                .clickable { colorArgb = hex }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pill BG", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Spacer(modifier = Modifier.width(6.dp))
                    Switch(
                        checked = hasPill,
                        onCheckedChange = { hasPill = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = ClotchBlue)
                    )
                }
            }

            if (selectedOv != null) {
                Text(
                    text = String.format(Locale.US, "Position (X %.0f%% • Y %.0f%%)", ovPosX * 100, ovPosY * 100),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Slider(
                        value = ovPosX,
                        onValueChange = { ovPosX = it },
                        valueRange = 0.1f..0.9f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = ClotchBlue, activeTrackColor = ClotchBlue)
                    )
                    Slider(
                        value = ovPosY,
                        onValueChange = { ovPosY = it },
                        valueRange = 0.1f..0.9f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = ClotchBlue, activeTrackColor = ClotchBlue)
                    )
                }
                Button(
                    onClick = {
                        onUpdateSelectedOverlay(
                            textInput.ifBlank { selectedOv.text },
                            fontChoice,
                            colorArgb,
                            animation,
                            hasPill,
                            selectedOv.durationMs,
                            ovPosX,
                            ovPosY
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("apply_overlay_edit_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClotchBlue, contentColor = TextPrimary)
                ) {
                    Text("Save Overlay Changes")
                }
            } else {
                Button(
                    onClick = {
                        onAddTextOverlay(textInput, fontChoice, colorArgb, animation, hasPill)
                        textInput = ""
                    },
                    enabled = textInput.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("add_text_overlay_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ClotchBlue,
                        contentColor = TextPrimary,
                        disabledContainerColor = ElevatedSlate,
                        disabledContentColor = TextMuted
                    )
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add Text at Playhead")
                }
            }

            // Hybrid Auto Captions (Offline SpeechRecognizer default + Gemini 1.5 Flash)
            Surface(
                color = CarbonSurface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, HairlineBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Auto Captions (Hybrid)",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        if (geminiApiKey.isBlank()) {
                            Text(
                                text = "Set Gemini Key",
                                style = MaterialTheme.typography.labelSmall,
                                color = ClotchBlue,
                                modifier = Modifier.clickable { onOpenSettingsForKey() }
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AutoCaptionMode.entries.forEach { mode ->
                            ToolChip(
                                label = mode.label,
                                selected = captionMode == mode,
                                onClick = { captionMode = mode }
                            )
                        }
                    }

                    when (autoCaptionState) {
                        is AutoCaptionState.Generating -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = ClotchBlue)
                                Text(autoCaptionState.status, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            }
                        }
                        is AutoCaptionState.ListeningOffline -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = autoCaptionState.partialText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SignalEmerald,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = onStopOfflineCaption,
                                    modifier = Modifier.height(40.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonAccent)
                                ) {
                                    Icon(Icons.Filled.Stop, contentDescription = "Stop listening", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Stop")
                                }
                            }
                        }
                        is AutoCaptionState.Message -> {
                            Text(autoCaptionState.text, style = MaterialTheme.typography.bodySmall, color = WarningAmber)
                        }
                        is AutoCaptionState.Idle -> Unit
                    }

                    Button(
                        onClick = { onGenerateCaptions(captionMode) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("generate_auto_captions_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ElevatedSlate, contentColor = TextPrimary)
                    ) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = ClotchBlue, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (captionMode == AutoCaptionMode.FAST_OFFLINE) {
                                "Capture Offline Speech Caption"
                            } else {
                                "Generate AI Captions (Gemini)"
                            }
                        )
                    }
                }
            }

            // Stickers & Emojis Row
            Text("Stickers & Emojis", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                STICKER_EMOJIS.forEach { emoji ->
                    Surface(
                        onClick = { onAddSticker(emoji) },
                        color = CarbonSurface,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, HairlineBorder),
                        modifier = Modifier.size(46.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(text = emoji, fontSize = 20.sp)
                        }
                    }
                }
            }

            if (selectedSt != null) {
                StickerAdjustRow(
                    sticker = selectedSt,
                    onUpdateSticker = onUpdateSelectedSticker
                )
            }
        }
    }
}

@Composable
private fun StickerAdjustRow(
    sticker: TimelineSticker,
    onUpdateSticker: (Float, Float, Float, Long) -> Unit
) {
    var scale by remember(sticker.id) { mutableFloatStateOf(sticker.scale) }
    var posX by remember(sticker.id) { mutableFloatStateOf(sticker.posX) }
    var posY by remember(sticker.id) { mutableFloatStateOf(sticker.posY) }
    Surface(
        color = CarbonSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, ClotchBlue.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Sticker ${sticker.emoji} • Scale ${String.format(Locale.US, "%.1fx", scale)}",
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary
            )
            Slider(
                value = scale,
                onValueChange = { scale = it },
                onValueChangeFinished = { onUpdateSticker(posX, posY, scale, sticker.durationMs) },
                valueRange = 0.5f..2.5f,
                colors = SliderDefaults.colors(thumbColor = ClotchBlue, activeTrackColor = ClotchBlue)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Slider(
                    value = posX,
                    onValueChange = { posX = it },
                    onValueChangeFinished = { onUpdateSticker(posX, posY, scale, sticker.durationMs) },
                    valueRange = 0.1f..0.9f,
                    modifier = Modifier.weight(1f)
                )
                Slider(
                    value = posY,
                    onValueChange = { posY = it },
                    onValueChangeFinished = { onUpdateSticker(posX, posY, scale, sticker.durationMs) },
                    valueRange = 0.1f..0.9f,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorAndSpeedPanel(
    selectedClip: TimelineVideoClip?,
    onSelectFilter: (ColorFilterPreset) -> Unit,
    onBeginContinuousEdit: () -> Unit,
    onCommitContinuousEdit: () -> Unit,
    onColorAdjust: (Float, Float, Float) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onToggleBlurBackground: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = SlateCard,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, HairlineBorder)
    ) {
        if (selectedClip == null) {
            Box(modifier = Modifier.padding(16.dp)) {
                Text("Select a video clip on the timeline to apply filters, color grading, speed, or background blur.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Color Filter Presets", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ColorFilterPreset.entries.forEach { preset ->
                        ToolChip(
                            label = preset.label,
                            selected = selectedClip.filterPreset == preset,
                            onClick = { onSelectFilter(preset) }
                        )
                    }
                }

                // Brightness / Contrast / Saturation
                Text(
                    text = String.format(Locale.US, "Brightness %.2f • Contrast %.2f • Saturation %.2f", selectedClip.brightness, selectedClip.contrast, selectedClip.saturation),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Slider(
                    value = selectedClip.brightness,
                    onValueChange = {
                        onBeginContinuousEdit()
                        onColorAdjust(it, selectedClip.contrast, selectedClip.saturation)
                    },
                    onValueChangeFinished = { onCommitContinuousEdit() },
                    valueRange = -0.5f..0.5f,
                    colors = SliderDefaults.colors(thumbColor = ClotchBlue, activeTrackColor = ClotchBlue)
                )
                Slider(
                    value = selectedClip.contrast,
                    onValueChange = {
                        onBeginContinuousEdit()
                        onColorAdjust(selectedClip.brightness, it, selectedClip.saturation)
                    },
                    onValueChangeFinished = { onCommitContinuousEdit() },
                    valueRange = 0.5f..2.0f,
                    colors = SliderDefaults.colors(thumbColor = ClotchBlue, activeTrackColor = ClotchBlue)
                )
                Slider(
                    value = selectedClip.saturation,
                    onValueChange = {
                        onBeginContinuousEdit()
                        onColorAdjust(selectedClip.brightness, selectedClip.contrast, it)
                    },
                    onValueChangeFinished = { onCommitContinuousEdit() },
                    valueRange = 0.0f..2.0f,
                    colors = SliderDefaults.colors(thumbColor = SignalEmerald, activeTrackColor = SignalEmerald)
                )

                Text("Speed (Pitch-Corrected)", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0.5f, 1.0f, 2.0f, 4.0f).forEach { spd ->
                        ToolChip(
                            label = "${spd}x",
                            selected = kotlin.math.abs(selectedClip.speed - spd) < 0.05f,
                            onClick = { onSelectSpeed(spd) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Background Blur", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text("Fill vertical/pillarbox edges with blurred video", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                    Switch(
                        checked = selectedClip.blurBackground,
                        onCheckedChange = { onToggleBlurBackground() },
                        modifier = Modifier.testTag("background_blur_switch"),
                        colors = SwitchDefaults.colors(checkedTrackColor = ClotchBlue)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransformAndKeyframesPanel(
    project: EditorProject,
    selectedClip: TimelineVideoClip?,
    onSelectAspectRatio: (EditorAspectRatio) -> Unit,
    onRotate90: () -> Unit,
    onSelectCropMode: (CropMode) -> Unit,
    onSaveKeyframe: (Float, Float, Float, Float) -> Unit,
    onDeleteKeyframe: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeWin = project.findVideoClipAt(project.playheadMs)
    val localMs = activeWin?.let { (project.playheadMs - it.timelineStartMs).coerceAtLeast(0L) } ?: 0L
    val eval = selectedClip?.evaluateKeyframesAt(localMs)

    var kfX by remember(selectedClip?.id, localMs / 1000L) { mutableFloatStateOf(eval?.posX ?: 0f) }
    var kfY by remember(selectedClip?.id, localMs / 1000L) { mutableFloatStateOf(eval?.posY ?: 0f) }
    var kfScale by remember(selectedClip?.id, localMs / 1000L) { mutableFloatStateOf(eval?.scale ?: 1f) }
    var kfOpacity by remember(selectedClip?.id, localMs / 1000L) { mutableFloatStateOf(eval?.opacity ?: 1f) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = SlateCard,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, HairlineBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Project Aspect Ratio", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorAspectRatio.entries.forEach { ratio ->
                    ToolChip(
                        label = ratio.label,
                        selected = project.aspectRatio == ratio,
                        onClick = { onSelectAspectRatio(ratio) }
                    )
                }
            }

            if (selectedClip != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Crop & Rotate (${selectedClip.rotationDegrees}°)", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                    OutlinedButton(
                        onClick = onRotate90,
                        modifier = Modifier.height(48.dp).testTag("rotate_90_button"),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, HairlineBorder)
                    ) {
                        Icon(Icons.Filled.RotateRight, contentDescription = "Rotate 90 degrees", tint = ClotchBlue, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Rotate 90°", color = TextPrimary)
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CropMode.entries.forEach { cm ->
                        ToolChip(
                            label = cm.label,
                            selected = selectedClip.cropMode == cm,
                            onClick = { onSelectCropMode(cm) }
                        )
                    }
                }

                Surface(
                    color = CarbonSurface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, HairlineBorder)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Keyframes (Pos / Scale / Opacity)", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text(
                            text = String.format(Locale.US, "X: %.2f  Y: %.2f  Scale: %.2fx  Alpha: %.0f%%", kfX, kfY, kfScale, kfOpacity * 100f),
                            fontFamily = JetBrainsMonoFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Slider(value = kfX, onValueChange = { kfX = it }, valueRange = -0.4f..0.4f, modifier = Modifier.weight(1f))
                            Slider(value = kfY, onValueChange = { kfY = it }, valueRange = -0.4f..0.4f, modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Slider(value = kfScale, onValueChange = { kfScale = it }, valueRange = 0.5f..2.5f, modifier = Modifier.weight(1f))
                            Slider(value = kfOpacity, onValueChange = { kfOpacity = it }, valueRange = 0.1f..1.0f, modifier = Modifier.weight(1f))
                        }
                        Button(
                            onClick = { onSaveKeyframe(kfX, kfY, kfScale, kfOpacity) },
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("save_keyframe_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ClotchBlue, contentColor = TextPrimary)
                        ) {
                            Text("Add Keyframe at Playhead")
                        }

                        if (selectedClip.keyframes.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                selectedClip.keyframes.forEach { kf ->
                                    Surface(
                                        color = SlateCard,
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, HairlineBorder)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(start = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = String.format(Locale.US, "%.1fs (%.1fx)", kf.timeOffsetMs / 1000f, kf.scale),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextPrimary
                                            )
                                            IconButton(
                                                onClick = { onDeleteKeyframe(selectedClip.id, kf.id) },
                                                modifier = Modifier.minimumInteractiveComponentSize()
                                            ) {
                                                Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete keyframe", tint = CrimsonAccent, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProEffectsChromaUpscalePanel(
    selectedClip: TimelineVideoClip?,
    chromaPreviewBitmap: Bitmap?,
    onToggleReverse: () -> Unit,
    onInsertFreezeFrame: () -> Unit,
    onUpdateChromaKey: (ChromaKeyConfig) -> Unit,
    onRefreshChromaPreview: () -> Unit,
    onSelectUpscale: (UpscaleTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = SlateCard,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, HairlineBorder)
    ) {
        if (selectedClip == null) {
            Box(modifier = Modifier.padding(16.dp)) {
                Text("Select a video clip on the timeline to access Reverse, Freeze Frame, Hybrid AI Chroma Key, and Lanczos Upscaling.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Time Effects", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onToggleReverse,
                        enabled = !selectedClip.isFreezeFrame,
                        modifier = Modifier.weight(1f).height(48.dp).testTag("reverse_clip_button"),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, if (selectedClip.isReversed) CrimsonAccent else HairlineBorder),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selectedClip.isReversed) CrimsonGlow else CarbonSurface,
                            contentColor = if (selectedClip.isReversed) CrimsonAccent else TextPrimary
                        )
                    ) {
                        Icon(Icons.Filled.FastRewind, contentDescription = "Reverse clip", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (selectedClip.isReversed) "Reversed" else "Reverse")
                    }

                    OutlinedButton(
                        onClick = onInsertFreezeFrame,
                        modifier = Modifier.weight(1f).height(48.dp).testTag("freeze_frame_button"),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, ClotchBlue.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = CarbonSurface, contentColor = ClotchBlue)
                    ) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = "Freeze frame", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Freeze 3s")
                    }
                }

                // Hybrid AI Green Screen (Chroma Key)
                Surface(
                    color = CarbonSurface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, HairlineBorder)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Filled.AutoFixHigh, contentDescription = null, tint = SignalEmerald, modifier = Modifier.size(18.dp))
                                Text("AI Chroma Key (Hybrid)", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                            }
                            Switch(
                                checked = selectedClip.chromaKey.enabled,
                                onCheckedChange = { enabled ->
                                    onUpdateChromaKey(selectedClip.chromaKey.copy(enabled = enabled))
                                },
                                modifier = Modifier.testTag("chroma_key_switch"),
                                colors = SwitchDefaults.colors(checkedTrackColor = SignalEmerald)
                            )
                        }

                        if (selectedClip.chromaKey.enabled) {
                            Text("Detection Mode (Exclusive)", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ChromaKeyMode.entries.forEach { m ->
                                    ToolChip(
                                        label = m.label,
                                        selected = selectedClip.chromaKey.mode == m,
                                        onClick = { onUpdateChromaKey(selectedClip.chromaKey.copy(mode = m)) }
                                    )
                                }
                            }
                            Text(
                                text = "Engine: ${selectedClip.chromaKey.mode.subtitle}",
                                style = MaterialTheme.typography.bodySmall,
                                color = SignalEmerald
                            )

                            Text("Background Replacement", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ChromaKeyBgStyle.entries.forEach { bg ->
                                    ToolChip(
                                        label = bg.label,
                                        selected = selectedClip.chromaKey.bgStyle == bg,
                                        onClick = { onUpdateChromaKey(selectedClip.chromaKey.copy(bgStyle = bg)) }
                                    )
                                }
                            }

                            if (chromaPreviewBitmap != null) {
                                Image(
                                    bitmap = chromaPreviewBitmap.asImageBitmap(),
                                    contentDescription = "AI Chroma Key preview frame",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(120.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .border(1.dp, SignalEmerald.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                )
                            }
                            OutlinedButton(
                                onClick = onRefreshChromaPreview,
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, HairlineBorder)
                            ) {
                                Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = SignalEmerald, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Refresh AI Cutout Preview", color = TextPrimary)
                            }
                        }
                    }
                }

                // FFmpeg Lanczos Video Upscaling
                Text("Video Upscaling (FFmpeg Lanczos)", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    UpscaleTarget.entries.forEach { target ->
                        ToolChip(
                            label = target.label,
                            selected = selectedClip.upscaleTarget == target,
                            onClick = { onSelectUpscale(target) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolChip(
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
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) ClotchBlue else TextSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
        )
    }
}
