package com.example.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
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
import com.example.editor.model.TimelineVideoClip
import com.example.editor.model.UpscaleTarget
import com.example.ui.components.ClotchCardShape
import com.example.ui.components.ClotchInnerCardShape
import com.example.ui.components.ClotchInputShape
import com.example.ui.components.ClotchPillShape
import com.example.ui.components.TactileIconButton
import com.example.ui.components.TactileOptionPill
import com.example.ui.components.TactilePillButton
import com.example.ui.components.TactileSurface
import com.example.ui.components.clotchTonalFieldColors
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.ClotchBlue
import com.example.ui.theme.ClotchBlueMuted
import com.example.ui.theme.CrimsonAccent
import com.example.ui.theme.CrimsonGlow
import com.example.ui.theme.ElevatedSlate
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
    val haptic = LocalHapticFeedback.current
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
        shape = ClotchCardShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = if (selectedOv != null) {
                    if (selectedOv.isCaption) "Edit Timeline Caption" else "Edit Text Overlay"
                } else {
                    "Text Overlay & Animation"
                },
                style = MaterialTheme.typography.titleMedium,
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
                shape = ClotchInputShape,
                colors = clotchTonalFieldColors(containerColor = CarbonSurface)
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EditorFontChoice.entries.forEach { fc ->
                    TactileOptionPill(
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
                    TactileOptionPill(
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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TEXT_COLORS.forEach { hex ->
                        val isSelected = colorArgb == hex
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(hex.toInt()))
                                .then(
                                    if (isSelected) {
                                        Modifier.border(width = 2.5.dp, color = ClotchBlue, shape = CircleShape)
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    colorArgb = hex
                                }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pill BG", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = hasPill,
                        onCheckedChange = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            hasPill = it
                        },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = ClotchBlue,
                            uncheckedTrackColor = CarbonSurface,
                            uncheckedBorderColor = Color.Transparent
                        )
                    )
                }
            }

            if (selectedOv != null) {
                Text(
                    text = String.format(Locale.US, "Position (X %.0f%% • Y %.0f%%)", ovPosX * 100, ovPosY * 100),
                    fontFamily = JetBrainsMonoFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Slider(
                        value = ovPosX,
                        onValueChange = { ovPosX = it },
                        onValueChangeFinished = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                        valueRange = 0.1f..0.9f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = ClotchBlue, activeTrackColor = ClotchBlue)
                    )
                    Slider(
                        value = ovPosY,
                        onValueChange = { ovPosY = it },
                        onValueChangeFinished = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                        valueRange = 0.1f..0.9f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = ClotchBlue, activeTrackColor = ClotchBlue)
                    )
                }
                TactilePillButton(
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
                        .testTag("apply_overlay_edit_button"),
                    containerColor = ClotchBlue,
                    contentColor = TextPrimary
                ) {
                    Text("Save Overlay Changes")
                }
            } else {
                TactilePillButton(
                    onClick = {
                        onAddTextOverlay(textInput, fontChoice, colorArgb, animation, hasPill)
                        textInput = ""
                    },
                    enabled = textInput.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_text_overlay_button"),
                    containerColor = ClotchBlue,
                    contentColor = TextPrimary
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add Text at Playhead")
                }
            }

            // Hybrid Auto Captions (Offline SpeechRecognizer default + Gemini 1.5 Flash)
            Surface(
                color = CarbonSurface,
                shape = ClotchInnerCardShape
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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
                            Surface(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onOpenSettingsForKey()
                                },
                                color = ClotchBlueMuted,
                                shape = ClotchPillShape
                            ) {
                                Text(
                                    text = "Set Gemini Key",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ClotchBlue,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AutoCaptionMode.entries.forEach { mode ->
                            TactileOptionPill(
                                label = mode.label,
                                selected = captionMode == mode,
                                onClick = { captionMode = mode },
                                unselectedColor = SlateCard
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
                                TactilePillButton(
                                    onClick = onStopOfflineCaption,
                                    containerColor = CrimsonAccent,
                                    contentColor = TextPrimary,
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
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

                    TactilePillButton(
                        onClick = { onGenerateCaptions(captionMode) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("generate_auto_captions_button"),
                        containerColor = ElevatedSlate,
                        contentColor = TextPrimary
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
                    TactileSurface(
                        onClick = { onAddSticker(emoji) },
                        color = CarbonSurface,
                        shape = RoundedCornerShape(14.dp),
                        pressedScale = 0.90f,
                        modifier = Modifier.size(48.dp)
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
    val haptic = LocalHapticFeedback.current
    var scale by remember(sticker.id) { mutableFloatStateOf(sticker.scale) }
    var posX by remember(sticker.id) { mutableFloatStateOf(sticker.posX) }
    var posY by remember(sticker.id) { mutableFloatStateOf(sticker.posY) }
    Surface(
        color = ClotchBlueMuted,
        shape = ClotchInnerCardShape
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Sticker ${sticker.emoji} • Scale ${String.format(Locale.US, "%.1fx", scale)}",
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary
            )
            Slider(
                value = scale,
                onValueChange = { scale = it },
                onValueChangeFinished = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onUpdateSticker(posX, posY, scale, sticker.durationMs)
                },
                valueRange = 0.5f..2.5f,
                colors = SliderDefaults.colors(thumbColor = ClotchBlue, activeTrackColor = ClotchBlue)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Slider(
                    value = posX,
                    onValueChange = { posX = it },
                    onValueChangeFinished = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onUpdateSticker(posX, posY, scale, sticker.durationMs)
                    },
                    valueRange = 0.1f..0.9f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = ClotchBlue, activeTrackColor = ClotchBlue)
                )
                Slider(
                    value = posY,
                    onValueChange = { posY = it },
                    onValueChangeFinished = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onUpdateSticker(posX, posY, scale, sticker.durationMs)
                    },
                    valueRange = 0.1f..0.9f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = ClotchBlue, activeTrackColor = ClotchBlue)
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
    val haptic = LocalHapticFeedback.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = SlateCard,
        shape = ClotchCardShape
    ) {
        if (selectedClip == null) {
            Box(modifier = Modifier.padding(18.dp)) {
                Text(
                    "Select a video clip on the timeline to apply filters, color grading, speed, or background blur.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Color Filter Presets", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ColorFilterPreset.entries.forEach { preset ->
                        TactileOptionPill(
                            label = preset.label,
                            selected = selectedClip.filterPreset == preset,
                            onClick = { onSelectFilter(preset) }
                        )
                    }
                }

                // Brightness / Contrast / Saturation
                Text(
                    text = String.format(
                        Locale.US,
                        "Brightness %.2f • Contrast %.2f • Saturation %.2f",
                        selectedClip.brightness,
                        selectedClip.contrast,
                        selectedClip.saturation
                    ),
                    fontFamily = JetBrainsMonoFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Slider(
                    value = selectedClip.brightness,
                    onValueChange = {
                        onBeginContinuousEdit()
                        onColorAdjust(it, selectedClip.contrast, selectedClip.saturation)
                    },
                    onValueChangeFinished = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onCommitContinuousEdit()
                    },
                    valueRange = -0.5f..0.5f,
                    colors = SliderDefaults.colors(thumbColor = ClotchBlue, activeTrackColor = ClotchBlue)
                )
                Slider(
                    value = selectedClip.contrast,
                    onValueChange = {
                        onBeginContinuousEdit()
                        onColorAdjust(selectedClip.brightness, it, selectedClip.saturation)
                    },
                    onValueChangeFinished = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onCommitContinuousEdit()
                    },
                    valueRange = 0.5f..2.0f,
                    colors = SliderDefaults.colors(thumbColor = ClotchBlue, activeTrackColor = ClotchBlue)
                )
                Slider(
                    value = selectedClip.saturation,
                    onValueChange = {
                        onBeginContinuousEdit()
                        onColorAdjust(selectedClip.brightness, selectedClip.contrast, it)
                    },
                    onValueChangeFinished = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onCommitContinuousEdit()
                    },
                    valueRange = 0.0f..2.0f,
                    colors = SliderDefaults.colors(thumbColor = SignalEmerald, activeTrackColor = SignalEmerald)
                )

                Text("Speed (Pitch-Corrected)", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0.5f, 1.0f, 2.0f, 4.0f).forEach { spd ->
                        TactileOptionPill(
                            label = "${spd}x",
                            selected = kotlin.math.abs(selectedClip.speed - spd) < 0.05f,
                            onClick = { onSelectSpeed(spd) }
                        )
                    }
                }

                Surface(
                    color = CarbonSurface,
                    shape = ClotchInnerCardShape
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Background Blur", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                            Text("Fill vertical/pillarbox edges with blurred video", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                        Switch(
                            checked = selectedClip.blurBackground,
                            onCheckedChange = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onToggleBlurBackground()
                            },
                            modifier = Modifier.testTag("background_blur_switch"),
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = ClotchBlue,
                                uncheckedTrackColor = ElevatedSlate,
                                uncheckedBorderColor = Color.Transparent
                            )
                        )
                    }
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
    val haptic = LocalHapticFeedback.current
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
        shape = ClotchCardShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Project Aspect Ratio", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EditorAspectRatio.entries.forEach { ratio ->
                    TactileOptionPill(
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
                    Text(
                        "Crop & Rotate (${selectedClip.rotationDegrees}°)",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    TactilePillButton(
                        onClick = onRotate90,
                        modifier = Modifier.testTag("rotate_90_button"),
                        containerColor = CarbonSurface,
                        contentColor = TextPrimary,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.RotateRight,
                            contentDescription = "Rotate 90 degrees",
                            tint = ClotchBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Rotate 90°")
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CropMode.entries.forEach { cm ->
                        TactileOptionPill(
                            label = cm.label,
                            selected = selectedClip.cropMode == cm,
                            onClick = { onSelectCropMode(cm) }
                        )
                    }
                }

                Surface(
                    color = CarbonSurface,
                    shape = ClotchInnerCardShape
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Keyframes (Pos / Scale / Opacity)", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text(
                            text = String.format(Locale.US, "X: %.2f  Y: %.2f  Scale: %.2fx  Alpha: %.0f%%", kfX, kfY, kfScale, kfOpacity * 100f),
                            fontFamily = JetBrainsMonoFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Slider(
                                value = kfX,
                                onValueChange = { kfX = it },
                                onValueChangeFinished = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                                valueRange = -0.4f..0.4f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(thumbColor = ClotchBlue, activeTrackColor = ClotchBlue)
                            )
                            Slider(
                                value = kfY,
                                onValueChange = { kfY = it },
                                onValueChangeFinished = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                                valueRange = -0.4f..0.4f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(thumbColor = ClotchBlue, activeTrackColor = ClotchBlue)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Slider(
                                value = kfScale,
                                onValueChange = { kfScale = it },
                                onValueChangeFinished = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                                valueRange = 0.5f..2.5f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(thumbColor = ClotchBlue, activeTrackColor = ClotchBlue)
                            )
                            Slider(
                                value = kfOpacity,
                                onValueChange = { kfOpacity = it },
                                onValueChangeFinished = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                                valueRange = 0.1f..1.0f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(thumbColor = ClotchBlue, activeTrackColor = ClotchBlue)
                            )
                        }
                        TactilePillButton(
                            onClick = { onSaveKeyframe(kfX, kfY, kfScale, kfOpacity) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("save_keyframe_button"),
                            containerColor = ClotchBlue,
                            contentColor = TextPrimary
                        ) {
                            Text("Add Keyframe at Playhead")
                        }

                        if (selectedClip.keyframes.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                selectedClip.keyframes.forEach { kf ->
                                    Surface(
                                        color = ElevatedSlate,
                                        shape = ClotchPillShape
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = String.format(Locale.US, "%.1fs (%.1fx)", kf.timeOffsetMs / 1000f, kf.scale),
                                                fontFamily = JetBrainsMonoFamily,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextPrimary
                                            )
                                            TactileIconButton(
                                                onClick = { onDeleteKeyframe(selectedClip.id, kf.id) }
                                            ) {
                                                Icon(
                                                    Icons.Filled.DeleteOutline,
                                                    contentDescription = "Delete keyframe",
                                                    tint = CrimsonAccent,
                                                    modifier = Modifier.size(16.dp)
                                                )
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
    val haptic = LocalHapticFeedback.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = SlateCard,
        shape = ClotchCardShape
    ) {
        if (selectedClip == null) {
            Box(modifier = Modifier.padding(18.dp)) {
                Text(
                    "Select a video clip on the timeline to access Reverse, Freeze Frame, Hybrid AI Chroma Key, and Lanczos Upscaling.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Time Effects", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TactilePillButton(
                        onClick = onToggleReverse,
                        enabled = !selectedClip.isFreezeFrame,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("reverse_clip_button"),
                        containerColor = if (selectedClip.isReversed) CrimsonGlow else CarbonSurface,
                        contentColor = if (selectedClip.isReversed) CrimsonAccent else TextPrimary
                    ) {
                        Icon(Icons.Filled.FastRewind, contentDescription = "Reverse clip", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (selectedClip.isReversed) "Reversed" else "Reverse")
                    }

                    TactilePillButton(
                        onClick = onInsertFreezeFrame,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("freeze_frame_button"),
                        containerColor = ClotchBlueMuted,
                        contentColor = ClotchBlue
                    ) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = "Freeze frame", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Freeze 3s")
                    }
                }

                // Hybrid AI Green Screen (Chroma Key)
                Surface(
                    color = CarbonSurface,
                    shape = ClotchInnerCardShape
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.AutoFixHigh, contentDescription = null, tint = SignalEmerald, modifier = Modifier.size(18.dp))
                                Text("AI Chroma Key (Hybrid)", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                            }
                            Switch(
                                checked = selectedClip.chromaKey.enabled,
                                onCheckedChange = { enabled ->
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onUpdateChromaKey(selectedClip.chromaKey.copy(enabled = enabled))
                                },
                                modifier = Modifier.testTag("chroma_key_switch"),
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = SignalEmerald,
                                    uncheckedTrackColor = ElevatedSlate,
                                    uncheckedBorderColor = Color.Transparent
                                )
                            )
                        }

                        if (selectedClip.chromaKey.enabled) {
                            Text("Detection Mode (Exclusive)", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ChromaKeyMode.entries.forEach { m ->
                                    TactileOptionPill(
                                        label = m.label,
                                        selected = selectedClip.chromaKey.mode == m,
                                        onClick = { onUpdateChromaKey(selectedClip.chromaKey.copy(mode = m)) },
                                        accent = SignalEmerald,
                                        unselectedColor = SlateCard
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
                                    TactileOptionPill(
                                        label = bg.label,
                                        selected = selectedClip.chromaKey.bgStyle == bg,
                                        onClick = { onUpdateChromaKey(selectedClip.chromaKey.copy(bgStyle = bg)) },
                                        accent = SignalEmerald,
                                        unselectedColor = SlateCard
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
                                        .height(128.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                )
                            }
                            TactilePillButton(
                                onClick = onRefreshChromaPreview,
                                modifier = Modifier.fillMaxWidth(),
                                containerColor = ElevatedSlate,
                                contentColor = TextPrimary
                            ) {
                                Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = SignalEmerald, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Refresh AI Cutout Preview")
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
                        TactileOptionPill(
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
