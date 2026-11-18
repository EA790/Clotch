package com.example.ui.editor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
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
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.editor.EditorPreviewError
import com.example.editor.model.ColorFilterPreset
import com.example.editor.model.EditorFontChoice
import com.example.editor.model.EditorProject
import com.example.editor.model.TextAnimationType
import com.example.editor.model.TimelineVideoClip
import com.example.editor.model.UpscaleTarget
import com.example.editor.model.VoiceoverState
import com.example.ui.components.ClotchCardShape
import com.example.ui.components.ClotchPillShape
import com.example.ui.components.TactilePillButton
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.ClotchBlue
import com.example.ui.theme.CrimsonAccent
import com.example.ui.theme.ElevatedSlate
import com.example.ui.theme.InterFamily
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SpaceGroteskFamily
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
@Composable
fun EditorPreviewStage(
    project: EditorProject,
    playheadMsFlow: StateFlow<Long>,
    exoPlayer: ExoPlayer?,
    previewError: EditorPreviewError?,
    isBusy: Boolean,
    voiceoverState: VoiceoverState,
    isEditorActive: Boolean = true,
    onPreparePreview: () -> Unit = {},
    onStopPreview: () -> Unit = {},
    onRetryPreview: () -> Unit = {},
    onConvertWebmToMp4: (String?) -> Unit = {},
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
                pv.visibility = if (exoPlayer != null && previewError == null) View.VISIBLE else View.INVISIBLE
                if (pv.player !== exoPlayer) {
                    pv.player = exoPlayer
                }
                if (exoPlayer != null) pv.onResume() else pv.onPause()
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (isEditorActive) {
                        latestOnPrepare()
                        playerViewRef?.let { pv ->
                            pv.visibility = if (exoPlayer != null) View.VISIBLE else View.INVISIBLE
                            if (pv.player !== exoPlayer) {
                                pv.player = exoPlayer
                            }
                            if (exoPlayer != null) pv.onResume()
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
    val hasBlurBg = project.selectedVideoClip?.blurBackground == true

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 195.dp, max = 235.dp),
        color = Color.Black,
        shape = ClotchCardShape
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (hasBlurBg) {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TactilePillButton(
                            onClick = onImportGallery,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("empty_import_gallery_button"),
                            containerColor = ClotchBlue,
                            contentColor = TextPrimary
                        ) {
                            Text("Gallery Video")
                        }
                        TactilePillButton(
                            onClick = onOpenSavedClips,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("empty_saved_clips_button"),
                            containerColor = ElevatedSlate,
                            contentColor = TextPrimary
                        ) {
                            Text("Saved Clips")
                        }
                    }
                }
            } else if (previewError != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(CarbonSurface)
                        .padding(16.dp)
                        .testTag("editor_preview_error_card"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = WarningAmber,
                        modifier = Modifier.size(30.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = previewError.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TactilePillButton(
                            onClick = onRetryPreview,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("editor_preview_retry_button"),
                            containerColor = ElevatedSlate,
                            contentColor = TextPrimary
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Retry")
                        }
                        if (previewError.isWebmFallbackAvailable) {
                            TactilePillButton(
                                onClick = { onConvertWebmToMp4(previewError.failingClipId) },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("editor_convert_webm_button"),
                                containerColor = ClotchBlue,
                                contentColor = TextPrimary
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Transform,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.size(6.dp))
                                Text("Convert to MP4")
                            }
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

                    val playheadState = playheadMsFlow.collectAsStateWithLifecycle()

                    if (isEditorActive && exoPlayer != null) {
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
                                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                                    setKeepContentOnPlayerReset(true)
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    player = exoPlayer
                                    visibility = View.VISIBLE
                                    playerViewRef = this
                                }
                            },
                            update = { pv ->
                                playerViewRef = pv
                                pv.setKeepContentOnPlayerReset(true)
                                pv.visibility = View.VISIBLE
                                if (pv.player !== exoPlayer) {
                                    pv.player = exoPlayer
                                    pv.onResume()
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
                                    val currentPlayhead = playheadState.value
                                    val activeWindow = project.findVideoClipAt(currentPlayhead)
                                    if (activeWindow != null) {
                                        val activeClip = activeWindow.clip
                                        val localOffset = (currentPlayhead - activeWindow.timelineStartMs).coerceAtLeast(0L)
                                        val kfEval = activeClip.evaluateKeyframesAt(localOffset)
                                        rotationZ = activeClip.rotationDegrees.toFloat()
                                        translationX = kfEval.posX * stageW
                                        translationY = kfEval.posY * stageH
                                        scaleX = kfEval.scale
                                        scaleY = kfEval.scale
                                        alpha = kfEval.opacity
                                    }
                                }
                        )
                    }

                    PreviewLiveOverlaysLayer(
                        project = project,
                        playheadMsFlow = playheadMsFlow,
                        stageW = stageW,
                        stageH = stageH,
                        onSelectOverlay = onSelectOverlay,
                        onSelectSticker = onSelectSticker
                    )
                }
            }

            PreviewActiveClipBadgesLayer(
                project = project,
                playheadMsFlow = playheadMsFlow,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            )

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
private fun PreviewLiveOverlaysLayer(
    project: EditorProject,
    playheadMsFlow: StateFlow<Long>,
    stageW: Float,
    stageH: Float,
    onSelectOverlay: (String) -> Unit,
    onSelectSticker: (String) -> Unit
) {
    val playheadMs by playheadMsFlow.collectAsStateWithLifecycle()
    val activeClip = remember(project, playheadMs) {
        project.findVideoClipAt(playheadMs)?.clip
    }

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
        .filter { playheadMs in it.timelineStartMs..it.timelineEndMs }
        .forEach { ov ->
            val elapsed = (playheadMs - ov.timelineStartMs).coerceAtLeast(0L)
            val remaining = (ov.timelineEndMs - playheadMs).coerceAtLeast(0L)
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
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = ((ov.posX - 0.5f) * stageW * 0.75f).roundToInt(),
                                y = ((ov.posY - 0.5f) * stageH * 0.8f + slideOffsetY).roundToInt()
                            )
                        }
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
        }

    // Live Stickers at Playhead
    project.stickers
        .filter { playheadMs in it.timelineStartMs..it.timelineEndMs }
        .forEach { st ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = ((st.posX - 0.5f) * stageW * 0.8f).roundToInt(),
                                y = ((st.posY - 0.5f) * stageH * 0.8f).roundToInt()
                            )
                        }
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

@Composable
private fun PreviewActiveClipBadgesLayer(
    project: EditorProject,
    playheadMsFlow: StateFlow<Long>,
    modifier: Modifier = Modifier
) {
    val playheadMs by playheadMsFlow.collectAsStateWithLifecycle()
    val activeClip: TimelineVideoClip? = remember(project, playheadMs) {
        project.findVideoClipAt(playheadMs)?.clip
    }
    if (activeClip != null) {
        Row(
            modifier = modifier,
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
}

@Composable
private fun PreviewFxBadge(
    label: String,
    accent: Color = TextPrimary
) {
    Surface(
        color = ObsidianBg.copy(alpha = 0.80f),
        shape = ClotchPillShape
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            color = accent,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
