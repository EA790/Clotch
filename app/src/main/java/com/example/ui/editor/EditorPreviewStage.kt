package com.example.ui.editor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.editor.model.ColorFilterPreset
import com.example.editor.model.EditorFontChoice
import com.example.editor.model.EditorProject
import com.example.editor.model.TextAnimationType
import com.example.editor.model.UpscaleTarget
import com.example.editor.model.VoiceoverState
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.ClotchBlue
import com.example.ui.theme.CrimsonAccent
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.InterFamily
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SpaceGroteskFamily
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.WarningAmber
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
@Composable
fun EditorPreviewStage(
    project: EditorProject,
    exoPlayer: ExoPlayer,
    isBusy: Boolean,
    voiceoverState: VoiceoverState,
    isEditorActive: Boolean = true,
    onPreparePreview: () -> Unit = {},
    onStopPreview: () -> Unit = {},
    onImportGallery: () -> Unit,
    onOpenSavedClips: () -> Unit,
    onSelectOverlay: (String) -> Unit,
    onSelectSticker: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnPrepare by rememberUpdatedState(onPreparePreview)
    val latestOnStop by rememberUpdatedState(onStopPreview)
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

    DisposableEffect(lifecycleOwner, exoPlayer, isEditorActive) {
        if (!isEditorActive) {
            playerViewRef?.let { pv ->
                pv.visibility = View.INVISIBLE
                pv.onPause()
                pv.player = null
            }
            latestOnStop()
        } else {
            latestOnPrepare()
            playerViewRef?.let { pv ->
                pv.visibility = View.VISIBLE
                if (pv.player !== exoPlayer) {
                    pv.player = exoPlayer
                }
                pv.onResume()
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (isEditorActive) {
                        latestOnPrepare()
                        playerViewRef?.let { pv ->
                            pv.visibility = View.VISIBLE
                            if (pv.player !== exoPlayer) {
                                pv.player = exoPlayer
                            }
                            pv.onResume()
                        }
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    playerViewRef?.let { pv ->
                        pv.visibility = View.INVISIBLE
                        pv.onPause()
                        pv.player = null
                    }
                    latestOnStop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            playerViewRef?.let { pv ->
                pv.visibility = View.INVISIBLE
                pv.onPause()
                pv.player = null
            }
            latestOnStop()
        }
    }

    val ratio = project.aspectRatio.widthRatio / project.aspectRatio.heightRatio
    val activeWindow = project.findVideoClipAt(project.playheadMs)
    val activeClip = activeWindow?.clip
    val localOffsetMs = if (activeWindow != null) {
        (project.playheadMs - activeWindow.timelineStartMs).coerceAtLeast(0L)
    } else {
        0L
    }
    val kfEval = activeClip?.evaluateKeyframesAt(localOffsetMs)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 195.dp, max = 235.dp),
        color = Color.Black,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, HairlineBorder)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (activeClip?.blurBackground == true) {
                        Brush.verticalGradient(listOf(Color(0xFF1A2642), ObsidianBg, Color(0xFF16223A)))
                    } else {
                        Brush.verticalGradient(listOf(Color.Black, Color.Black))
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (project.videoClips.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(CarbonSurface)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.MovieCreation,
                        contentDescription = null,
                        tint = ClotchBlue,
                        modifier = Modifier.size(34.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Clotch Video Editor",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onImportGallery,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("empty_import_gallery_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ClotchBlue,
                                contentColor = TextPrimary
                            )
                        ) {
                            Text("Gallery Video")
                        }
                        OutlinedButton(
                            onClick = onOpenSavedClips,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("empty_saved_clips_button"),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, HairlineBorder)
                        ) {
                            Text("Saved Clips", color = TextPrimary)
                        }
                    }
                }
            } else {
                // Aspect-Ratio bounded Viewport
                BoxWithConstraints(
                    modifier = Modifier
                        .aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                ) {
                    val stageW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                    val stageH = constraints.maxHeight.toFloat().coerceAtLeast(1f)

                    AndroidView(
                        factory = { ctx ->
                            val inflated = LayoutInflater.from(ctx).inflate(
                                R.layout.view_editor_player,
                                null,
                                false
                            ) as PlayerView
                            inflated.apply {
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                setKeepContentOnPlayerReset(false)
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                player = if (isEditorActive) exoPlayer else null
                                visibility = if (isEditorActive) View.VISIBLE else View.INVISIBLE
                                playerViewRef = this
                            }
                        },
                        update = { pv ->
                            playerViewRef = pv
                            val targetPlayer = if (isEditorActive) exoPlayer else null
                            pv.visibility = if (isEditorActive) View.VISIBLE else View.INVISIBLE
                            if (pv.player !== targetPlayer) {
                                if (targetPlayer == null) {
                                    pv.onPause()
                                }
                                pv.player = targetPlayer
                                if (targetPlayer != null) {
                                    pv.onResume()
                                }
                            }
                        },
                        onReset = { pv ->
                            pv.visibility = View.INVISIBLE
                            pv.onPause()
                            pv.player = null
                            exoPlayer.clearVideoSurface()
                        },
                        onRelease = { pv ->
                            pv.visibility = View.INVISIBLE
                            pv.onPause()
                            pv.player = null
                            exoPlayer.clearVideoSurface()
                            if (playerViewRef === pv) {
                                playerViewRef = null
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                if (activeClip != null && kfEval != null) {
                                    rotationZ = activeClip.rotationDegrees.toFloat()
                                    translationX = kfEval.posX * stageW
                                    translationY = kfEval.posY * stageH
                                    scaleX = kfEval.scale
                                    scaleY = kfEval.scale
                                    alpha = if (isEditorActive) kfEval.opacity else 0f
                                } else if (!isEditorActive) {
                                    alpha = 0f
                                }
                            }
                    )

                    // Live color filter preview tint overlay
                    val tintOverlayColor = when (activeClip?.filterPreset) {
                        ColorFilterPreset.WARM -> Color(0x28FF8A33)
                        ColorFilterPreset.COOL -> Color(0x282B6BFF)
                        ColorFilterPreset.VINTAGE -> Color(0x30A67C52)
                        ColorFilterPreset.CYBER -> Color(0x2A7F3BFF)
                        else -> Color.Transparent
                    }
                    if (tintOverlayColor != Color.Transparent) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(tintOverlayColor)
                        )
                    }

                    // Live Text & Auto Caption Overlays at Playhead
                    project.overlays
                        .filter { project.playheadMs in it.timelineStartMs..it.timelineEndMs }
                        .forEach { ov ->
                            val elapsed = (project.playheadMs - ov.timelineStartMs).coerceAtLeast(0L)
                            val remaining = (ov.timelineEndMs - project.playheadMs).coerceAtLeast(0L)
                            val animAlpha = when (ov.animation) {
                                TextAnimationType.FADE, TextAnimationType.SLIDE_UP -> {
                                    val inRatio = (elapsed / 350f).coerceIn(0f, 1f)
                                    val outRatio = (remaining / 350f).coerceIn(0f, 1f)
                                    minOf(inRatio, outRatio)
                                }
                                else -> 1f
                            }
                            val slideOffsetY = if (ov.animation == TextAnimationType.SLIDE_UP) {
                                ((1f - (elapsed / 350f).coerceIn(0f, 1f)) * 28f)
                            } else {
                                0f
                            }
                            val visibleText = if (ov.animation == TextAnimationType.TYPEWRITER) {
                                val frac = (elapsed / minOf(ov.durationMs * 0.7f, 1800f).coerceAtLeast(250f))
                                    .coerceIn(0f, 1f)
                                val chars = (ov.text.length * frac).roundToInt().coerceIn(1, ov.text.length)
                                ov.text.take(chars)
                            } else {
                                ov.text
                            }
                            val fontFamily: FontFamily = when (ov.fontChoice) {
                                EditorFontChoice.SPACE_GROTESK -> SpaceGroteskFamily
                                EditorFontChoice.INTER -> InterFamily
                                EditorFontChoice.JETBRAINS_MONO -> JetBrainsMonoFamily
                            }
                            Box(
                                modifier = Modifier
                                    .offset {
                                        IntOffset(
                                            x = ((ov.posX - 0.5f) * stageW * 0.75f).roundToInt(),
                                            y = ((ov.posY - 0.5f) * stageH * 0.8f + slideOffsetY).roundToInt()
                                        )
                                    }
                                    .align(Alignment.Center)
                                    .graphicsLayer { alpha = animAlpha }
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (ov.hasBackgroundPill) ObsidianBg.copy(alpha = 0.78f) else Color.Transparent
                                    )
                                    .clickable { onSelectOverlay(ov.id) }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = visibleText,
                                    color = Color(ov.colorArgb.toInt()),
                                    fontFamily = fontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = (ov.fontSizeSp * 0.65f).coerceIn(12f, 22f).sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                    // Live Stickers at Playhead
                    project.stickers
                        .filter { project.playheadMs in it.timelineStartMs..it.timelineEndMs }
                        .forEach { st ->
                            Box(
                                modifier = Modifier
                                    .offset {
                                        IntOffset(
                                            x = ((st.posX - 0.5f) * stageW * 0.8f).roundToInt(),
                                            y = ((st.posY - 0.5f) * stageH * 0.8f).roundToInt()
                                        )
                                    }
                                    .align(Alignment.Center)
                                    .graphicsLayer {
                                        scaleX = st.scale
                                        scaleY = st.scale
                                    }
                                    .clickable { onSelectSticker(st.id) }
                            ) {
                                Text(text = st.emoji, fontSize = 28.sp)
                            }
                        }
                }
            }

            // Active Clip FX Status Badges
            if (activeClip != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    PreviewFxBadge(project.aspectRatio.label)
                    if (activeClip.speed != 1.0f) {
                        PreviewFxBadge("${activeClip.speed}x")
                    }
                    if (activeClip.isReversed) {
                        PreviewFxBadge("REV", CrimsonAccent)
                    }
                    if (activeClip.isFreezeFrame) {
                        PreviewFxBadge("FREEZE", WarningAmber)
                    }
                    if (activeClip.chromaKey.enabled) {
                        PreviewFxBadge("AI CHROMA", ClotchBlue)
                    }
                    if (activeClip.upscaleTarget != UpscaleTarget.OFF) {
                        PreviewFxBadge(
                            if (activeClip.upscaleTarget == UpscaleTarget.UPSCALE_4K_2160P) "4K" else "1080P",
                            ClotchBlue
                        )
                    }
                }
            }

            if (isBusy) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ObsidianBg.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = ClotchBlue)
                }
            }

            if (voiceoverState is VoiceoverState.Recording) {
                Surface(
                    color = CrimsonAccent,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                ) {
                    val totalSec = (voiceoverState.elapsedMs.coerceAtLeast(0L) / 1000L)
                    Text(
                        text = String.format(Locale.US, "REC %02d:%02d", totalSec / 60L, totalSec % 60L),
                        fontFamily = JetBrainsMonoFamily,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewFxBadge(
    label: String,
    accent: Color = TextPrimary
) {
    Surface(
        color = ObsidianBg.copy(alpha = 0.78f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            color = accent,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
