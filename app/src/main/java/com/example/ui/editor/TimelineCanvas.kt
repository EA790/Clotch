package com.example.ui.editor

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.editor.engine.EditorThumbnailCache
import com.example.editor.model.AudioClipKind
import com.example.editor.model.EditorProject
import com.example.editor.model.TimelineAudioClip
import com.example.editor.model.TimelineSticker
import com.example.editor.model.TimelineTextOverlay
import com.example.editor.model.TimelineVideoClip
import com.example.editor.model.TransitionType
import com.example.ui.components.ClotchCardShape
import com.example.ui.components.ClotchPillShape
import com.example.ui.components.TactileSurface
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.ClotchBlue
import com.example.ui.theme.ClotchBlueMuted
import com.example.ui.theme.CrimsonAccent
import com.example.ui.theme.CrimsonGlow
import com.example.ui.theme.DiscordBlurple
import com.example.ui.theme.DiscordBlurpleMuted
import com.example.ui.theme.ElevatedSlate
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.SignalEmeraldMuted
import com.example.ui.theme.SlateCard
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import com.example.ui.theme.WarningAmberMuted
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun TimelineCanvas(
    project: EditorProject,
    playheadMsFlow: StateFlow<Long>,
    thumbnailCache: EditorThumbnailCache,
    isPlaying: Boolean,
    onSeekTo: (Long) -> Unit,
    onSelectVideoClip: (String?) -> Unit,
    onSelectAudioClip: (String?) -> Unit,
    onSelectOverlay: (String?) -> Unit = {},
    onSelectSticker: (String?) -> Unit = {},
    onOpenTransitionPicker: (afterClipId: String) -> Unit = {},
    onBeginContinuousEdit: () -> Unit,
    onCommitContinuousEdit: () -> Unit,
    onTrimVideoClip: (clipId: String, newStartMs: Long, newEndMs: Long) -> Unit,
    onMoveVideoClip: (fromIndex: Int, toIndex: Int) -> Unit,
    onMoveAudioClipStart: (audioId: String, newStartMs: Long) -> Unit,
    onToggleAudioMute: (audioId: String) -> Unit = {},
    onMoveOverlayStart: (overlayId: String, newStartMs: Long) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    val pxPerSecond = 64f * project.zoomLevel
    val dpPerMs = pxPerSecond / 1000f
    val pxPerMs = with(density) { pxPerSecond.dp.toPx() } / 1000f
    val rowHorizPadPx = with(density) { 12.dp.toPx() }
    val transitionBadgePx = with(density) { 32.dp.toPx() }

    val totalMs = project.totalTimelineDurationMs.coerceAtLeast(10_000L)
    val contentWidthDp: Dp = ((totalMs * dpPerMs) + 160f).dp
    val latestTotalDuration by rememberUpdatedState(project.totalTimelineDurationMs.coerceAtLeast(1000L))

    // Quantized horizontal viewport bounds to prevent scroll-pixel jitter recompositions
    val visibleWindowPx by remember {
        derivedStateOf {
            val start = scrollState.value
            val viewport = scrollState.viewportSize.coerceAtLeast(1080)
            val bucket = 160
            val qStart = (start / bucket) * bucket
            val qEnd = (((start + viewport) / bucket) + 1) * bucket
            qStart.toFloat() to qEnd.toFloat()
        }
    }
    val visibleStartPx = visibleWindowPx.first
    val visibleEndPx = visibleWindowPx.second

    TimelineAutoScrollEffect(
        playheadMsFlow = playheadMsFlow,
        isPlaying = isPlaying,
        pxPerMs = pxPerMs,
        scrollState = scrollState
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("timeline_canvas"),
        color = CarbonSurface,
        shape = ClotchCardShape
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(vertical = 10.dp)
        ) {
            Box(modifier = Modifier.width(contentWidthDp)) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Time Ruler (Virtualized to visible viewport)
                    TimelineRuler(
                        totalMs = totalMs,
                        zoomLevel = project.zoomLevel,
                        dpPerMs = dpPerMs,
                        pxPerMs = pxPerMs,
                        visibleStartPx = visibleStartPx,
                        visibleEndPx = visibleEndPx,
                        maxSeekMs = latestTotalDuration,
                        onSeekTo = onSeekTo,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                    )

                    // Video Track Header + Continuous Filmstrip Clips
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "VIDEO TRACK",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )

                        if (project.videoClips.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .width(240.dp)
                                    .height(74.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(SlateCard),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Import a video or saved clip",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(80.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(ObsidianBg.copy(alpha = 0.45f))
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                var cursorX = rowHorizPadPx
                                project.videoClips.forEachIndexed { index, clip ->
                                    val clipWidthDp = (clip.effectiveDurationMs * dpPerMs).dp.coerceAtLeast(64.dp)
                                    val clipWidthPx = with(density) { clipWidthDp.toPx() }
                                    val clipLeftPx = cursorX
                                    val clipRightPx = clipLeftPx + clipWidthPx
                                    cursorX = clipRightPx + if (index < project.videoClips.lastIndex) transitionBadgePx else 0f

                                    VideoTrackClipItem(
                                        clip = clip,
                                        index = index,
                                        totalClips = project.videoClips.size,
                                        widthDp = clipWidthDp,
                                        clipLeftPx = clipLeftPx,
                                        clipWidthPx = clipWidthPx,
                                        visibleStartPx = visibleStartPx,
                                        visibleEndPx = visibleEndPx,
                                        pxPerMs = pxPerMs,
                                        isSelected = clip.id == project.selectedVideoClipId,
                                        thumbnailCache = thumbnailCache,
                                        onSelect = { onSelectVideoClip(clip.id) },
                                        onBeginContinuousEdit = onBeginContinuousEdit,
                                        onCommitContinuousEdit = onCommitContinuousEdit,
                                        onTrim = { newStart, newEnd ->
                                            onTrimVideoClip(clip.id, newStart, newEnd)
                                        },
                                        onMoveOrder = { targetIndex ->
                                            onMoveVideoClip(index, targetIndex)
                                        }
                                    )

                                    if (index < project.videoClips.lastIndex) {
                                        val transSpec = project.transitions[clip.id]
                                        val hasTransition = transSpec != null && transSpec.type != TransitionType.NONE
                                        TactileSurface(
                                            onClick = { onOpenTransitionPicker(clip.id) },
                                            color = if (hasTransition) ClotchBlue else ElevatedSlate,
                                            shape = CircleShape,
                                            pressedScale = 0.90f,
                                            modifier = Modifier
                                                .padding(horizontal = 2.dp)
                                                .size(28.dp)
                                                .zIndex(6f)
                                                .testTag("transition_badge_${clip.id}")
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                                                    contentDescription = "Edit clip transition",
                                                    tint = TextPrimary,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Text Overlays, Auto Captions & Stickers Track
                    if (project.overlays.isNotEmpty() || project.stickers.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "TEXT, CAPTIONS & STICKERS",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            project.overlays.forEach { overlay ->
                                OverlayTrackClipRow(
                                    overlay = overlay,
                                    dpPerMs = dpPerMs,
                                    pxPerMs = pxPerMs,
                                    isSelected = overlay.id == project.selectedOverlayId,
                                    onSelect = { onSelectOverlay(overlay.id) },
                                    onBeginContinuousEdit = onBeginContinuousEdit,
                                    onCommitContinuousEdit = onCommitContinuousEdit,
                                    onMoveStartMs = { newStart -> onMoveOverlayStart(overlay.id, newStart) }
                                )
                            }
                            if (project.stickers.isNotEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    project.stickers.forEach { sticker ->
                                        StickerTrackPill(
                                            sticker = sticker,
                                            dpPerMs = dpPerMs,
                                            isSelected = sticker.id == project.selectedStickerId,
                                            onSelect = { onSelectSticker(sticker.id) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Audio Tracks (Game / Chat / Mic / BGM / Voiceover)
                    if (project.audioClips.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "AUDIO TRACKS (${project.audioClips.size})",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            project.audioClips.forEach { audioClip ->
                                AudioTrackClipRow(
                                    audioClip = audioClip,
                                    dpPerMs = dpPerMs,
                                    pxPerMs = pxPerMs,
                                    isSelected = audioClip.id == project.selectedAudioClipId,
                                    onSelect = { onSelectAudioClip(audioClip.id) },
                                    onToggleMute = { onToggleAudioMute(audioClip.id) },
                                    onBeginContinuousEdit = onBeginContinuousEdit,
                                    onCommitContinuousEdit = onCommitContinuousEdit,
                                    onMoveStartMs = { newStartMs ->
                                        onMoveAudioClipStart(audioClip.id, newStartMs)
                                    }
                                )
                            }
                        }
                    }
                }

                // InShot-Style Playhead (thin vertical white line + small horizontal top handle)
                TimelinePlayheadNeedle(
                    playheadMsFlow = playheadMsFlow,
                    dpPerMs = dpPerMs
                )
            }
        }
    }
}

@Composable
private fun TimelineAutoScrollEffect(
    playheadMsFlow: StateFlow<Long>,
    isPlaying: Boolean,
    pxPerMs: Float,
    scrollState: androidx.compose.foundation.ScrollState
) {
    if (!isPlaying) return
    val playheadMs by playheadMsFlow.collectAsStateWithLifecycle()
    LaunchedEffect(playheadMs, pxPerMs) {
        val targetPx = (playheadMs * pxPerMs).roundToInt()
        val viewport = scrollState.viewportSize
        if (viewport > 0 && targetPx > scrollState.value + viewport - 120) {
            scrollState.scrollTo((targetPx - viewport / 3).coerceAtLeast(0))
        }
    }
}

/**
 * InShot-style playhead: small crisp horizontal rounded handle at top + thin 2.dp vertical white line.
 */
@Composable
private fun TimelinePlayheadNeedle(
    playheadMsFlow: StateFlow<Long>,
    dpPerMs: Float
) {
    val playheadMs by playheadMsFlow.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val basePadPx = with(density) { 4.dp.toPx() }
    val pxPerMs = with(density) { (dpPerMs * 1000f).dp.toPx() } / 1000f

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = (basePadPx + playheadMs * pxPerMs).roundToInt(),
                    y = 0
                )
            }
            .width(16.dp)
            .fillMaxHeight()
            .zIndex(12f),
        contentAlignment = Alignment.TopCenter
    ) {
        // Small horizontal top handle
        Box(
            modifier = Modifier
                .width(16.dp)
                .height(5.dp)
                .shadow(4.dp, RoundedCornerShape(3.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .clip(RoundedCornerShape(3.dp))
                .background(TextPrimary)
        )
        // Thin vertical white needle
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .width(2.dp)
                .fillMaxHeight()
                .shadow(4.dp, RoundedCornerShape(1.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .background(TextPrimary)
        )
    }
}

@Composable
private fun TimelineRuler(
    totalMs: Long,
    zoomLevel: Float,
    dpPerMs: Float,
    pxPerMs: Float,
    visibleStartPx: Float,
    visibleEndPx: Float,
    maxSeekMs: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val latestMaxSeek by rememberUpdatedState(maxSeekMs)
    val stepMs = when {
        zoomLevel >= 2.2f -> 1000L
        zoomLevel >= 1.2f -> 2000L
        else -> 5000L
    }
    val safePxPerMs = pxPerMs.coerceAtLeast(0.001f)
    val marginPx = 240f
    val startTickMs = (((visibleStartPx - marginPx).coerceAtLeast(0f) / safePxPerMs) / stepMs)
        .toLong()
        .coerceAtLeast(0L) * stepMs
    val endTickMs = minOf(
        totalMs,
        ((((visibleEndPx + marginPx) / safePxPerMs) / stepMs).toLong() + 1L) * stepMs
    )

    Box(
        modifier = modifier
            .padding(horizontal = 12.dp)
            .pointerInput(pxPerMs) {
                detectTapGestures { offset ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val targetMs = (offset.x / pxPerMs).roundToLong().coerceIn(0L, latestMaxSeek)
                    onSeekTo(targetMs)
                }
            }
            .pointerInput(pxPerMs) {
                var dragMs = 0L
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        dragMs = (offset.x / pxPerMs).roundToLong().coerceIn(0L, latestMaxSeek)
                        onSeekTo(dragMs)
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        dragMs = (dragMs + (dragAmount / pxPerMs).roundToLong()).coerceIn(0L, latestMaxSeek)
                        onSeekTo(dragMs)
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val h = size.height
            var t = startTickMs
            while (t <= endTickMs) {
                val x = t * pxPerMs
                if (x <= size.width) {
                    drawLine(
                        color = TextMuted.copy(alpha = 0.65f),
                        start = Offset(x, h - 8f),
                        end = Offset(x, h - 1f),
                        strokeWidth = 2f
                    )
                }
                t += stepMs
            }
        }

        var tick = startTickMs
        while (tick <= endTickMs) {
            val tickSnapshot = tick
            key(tickSnapshot) {
                val xDp = (tickSnapshot * dpPerMs).dp
                Text(
                    text = formatTimelineSec(tickSnapshot),
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    color = TextMuted,
                    modifier = Modifier.offset(x = xDp + 2.dp, y = 0.dp)
                )
            }
            tick += stepMs
        }
    }
}

@Composable
private fun VideoTrackClipItem(
    clip: TimelineVideoClip,
    index: Int,
    totalClips: Int,
    widthDp: Dp,
    clipLeftPx: Float,
    clipWidthPx: Float,
    visibleStartPx: Float,
    visibleEndPx: Float,
    pxPerMs: Float,
    isSelected: Boolean,
    thumbnailCache: EditorThumbnailCache,
    onSelect: () -> Unit,
    onBeginContinuousEdit: () -> Unit,
    onCommitContinuousEdit: () -> Unit,
    onTrim: (newStartMs: Long, newEndMs: Long) -> Unit,
    onMoveOrder: (targetIndex: Int) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val currentClip by rememberUpdatedState(clip)
    var dragReorderOffsetPx by remember { mutableFloatStateOf(0f) }

    val thumbCount = ((widthDp.value / 68f).toInt()).coerceIn(1, 120)
    val slotWidthPx = (clipWidthPx / thumbCount.toFloat()).coerceAtLeast(1f)
    val clipShape = RoundedCornerShape(12.dp)

    val selectionModifier = if (isSelected) {
        Modifier
            .shadow(
                elevation = 12.dp,
                shape = clipShape,
                ambientColor = ClotchBlue,
                spotColor = ClotchBlue
            )
            .clip(clipShape)
            .background(SlateCard)
            .border(2.dp, ClotchBlue, clipShape)
    } else {
        Modifier
            .clip(clipShape)
            .background(SlateCard)
    }

    Box(
        modifier = Modifier
            .width(widthDp)
            .fillMaxHeight()
            .offset { IntOffset(dragReorderOffsetPx.roundToInt(), 0) }
            .zIndex(if (dragReorderOffsetPx != 0f) 5f else if (isSelected) 3f else 1f)
            .then(selectionModifier)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSelect()
            }
            .pointerInput(clip.id, index, totalClips) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSelect()
                        dragReorderOffsetPx = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragReorderOffsetPx += dragAmount.x
                        val thresholdPx = with(density) { 48.dp.toPx() }
                        if (dragReorderOffsetPx > thresholdPx && index < totalClips - 1) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onMoveOrder(index + 1)
                            dragReorderOffsetPx = 0f
                        } else if (dragReorderOffsetPx < -thresholdPx && index > 0) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onMoveOrder(index - 1)
                            dragReorderOffsetPx = 0f
                        }
                    },
                    onDragEnd = { dragReorderOffsetPx = 0f },
                    onDragCancel = { dragReorderOffsetPx = 0f }
                )
            }
            .testTag("timeline_video_clip_${clip.id}")
    ) {
        // Continuous Filmstrip Thumbnails (zero gap, lazy-loaded for visible viewport tiles)
        val prefetchPx = 280f
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            for (slot in 0 until thumbCount) {
                val slotLeftPx = clipLeftPx + slot * slotWidthPx
                val slotRightPx = slotLeftPx + slotWidthPx
                val isTileVisible = slotRightPx >= (visibleStartPx - prefetchPx) &&
                    slotLeftPx <= (visibleEndPx + prefetchPx)

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(ObsidianBg)
                ) {
                    if (isTileVisible) {
                        val fraction = (slot + 0.5f) / thumbCount.toFloat()
                        val sampleTimeMs = clip.trimStartMs + (clip.effectiveDurationMs * fraction).toLong()
                        val bitmap by produceState<Bitmap?>(
                            initialValue = null,
                            key1 = clip.sourceUri,
                            key2 = sampleTimeMs / 500L
                        ) {
                            value = thumbnailCache.getFrame(context, clip.sourceUri, sampleTimeMs)
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap!!.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }

        // Top-left label pill
        Surface(
            color = ObsidianBg.copy(alpha = 0.72f),
            shape = ClotchPillShape,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = if (isSelected) 22.dp else 6.dp, vertical = 5.dp)
        ) {
            Text(
                text = clip.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
            )
        }

        if (clip.isMuted || clip.audioDetached) {
            Surface(
                color = ObsidianBg.copy(alpha = 0.8f),
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = if (isSelected) 22.dp else 6.dp, bottom = 5.dp)
                    .size(20.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = "Clip muted",
                        tint = TextSecondary,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }

        // Left & Right Trim Handles when selected
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(20.dp)
                    .fillMaxHeight()
                    .background(ClotchBlue)
                    .pointerInput(clip.id, pxPerMs) {
                        var startSnapshot by mutableLongStateOf(currentClip.trimStartMs)
                        detectHorizontalDragGestures(
                            onDragStart = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onBeginContinuousEdit()
                                startSnapshot = currentClip.trimStartMs
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                val deltaMs = (dragAmount / pxPerMs).roundToLong()
                                startSnapshot = (startSnapshot + deltaMs)
                                    .coerceIn(0L, currentClip.trimEndMs - 250L)
                                onTrim(startSnapshot, currentClip.trimEndMs)
                            },
                            onDragEnd = { onCommitContinuousEdit() },
                            onDragCancel = { onCommitContinuousEdit() }
                        )
                    }
                    .testTag("trim_handle_start_${clip.id}"),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(22.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(TextPrimary)
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(20.dp)
                    .fillMaxHeight()
                    .background(ClotchBlue)
                    .pointerInput(clip.id, pxPerMs) {
                        var endSnapshot by mutableLongStateOf(currentClip.trimEndMs)
                        detectHorizontalDragGestures(
                            onDragStart = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onBeginContinuousEdit()
                                endSnapshot = currentClip.trimEndMs
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                val deltaMs = (dragAmount / pxPerMs).roundToLong()
                                endSnapshot = (endSnapshot + deltaMs)
                                    .coerceIn(currentClip.trimStartMs + 250L, currentClip.sourceDurationMs)
                                onTrim(currentClip.trimStartMs, endSnapshot)
                            },
                            onDragEnd = { onCommitContinuousEdit() },
                            onDragCancel = { onCommitContinuousEdit() }
                        )
                    }
                    .testTag("trim_handle_end_${clip.id}"),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(22.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(TextPrimary)
                )
            }
        }
    }
}

@Composable
private fun AudioTrackClipRow(
    audioClip: TimelineAudioClip,
    dpPerMs: Float,
    pxPerMs: Float,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggleMute: () -> Unit,
    onBeginContinuousEdit: () -> Unit,
    onCommitContinuousEdit: () -> Unit,
    onMoveStartMs: (Long) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val latestAudio by rememberUpdatedState(audioClip)
    val startDp = (audioClip.timelineStartMs * dpPerMs).dp
    val widthDp = (audioClip.effectiveDurationMs * dpPerMs).dp.coerceAtLeast(148.dp)

    val (tint, bg, icon) = when (audioClip.kind) {
        AudioClipKind.GAME -> Triple(ClotchBlue, ClotchBlueMuted, Icons.Filled.SportsEsports)
        AudioClipKind.CHAT -> Triple(DiscordBlurple, DiscordBlurpleMuted, Icons.Filled.HeadsetMic)
        AudioClipKind.MIC -> Triple(CrimsonAccent, CrimsonGlow, Icons.Filled.Mic)
        AudioClipKind.EXTRACTED -> Triple(ClotchBlue, ClotchBlueMuted, Icons.Filled.GraphicEq)
        AudioClipKind.BGM -> Triple(SignalEmerald, SignalEmeraldMuted, Icons.Filled.MusicNote)
        AudioClipKind.VOICEOVER -> Triple(WarningAmber, WarningAmberMuted, Icons.Filled.Mic)
    }

    val containerColor by animateColorAsState(
        targetValue = if (audioClip.isMuted) ElevatedSlate else bg,
        animationSpec = tween(160),
        label = "audio_clip_bg"
    )
    val audioShape = RoundedCornerShape(12.dp)
    val selectedMod = if (isSelected) {
        Modifier
            .shadow(8.dp, audioShape, ambientColor = tint, spotColor = tint)
            .border(1.5.dp, TextPrimary, audioShape)
    } else {
        Modifier
    }

    Box(modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Surface(
            modifier = Modifier
                .offset(x = startDp)
                .width(widthDp)
                .height(46.dp)
                .then(selectedMod)
                .pointerInput(audioClip.id, pxPerMs) {
                    var startDragMs = latestAudio.timelineStartMs
                    detectHorizontalDragGestures(
                        onDragStart = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect()
                            onBeginContinuousEdit()
                            startDragMs = latestAudio.timelineStartMs
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val deltaMs = (dragAmount / pxPerMs).roundToLong()
                            startDragMs = (startDragMs + deltaMs).coerceAtLeast(0L)
                            onMoveStartMs(startDragMs)
                        },
                        onDragEnd = { onCommitContinuousEdit() },
                        onDragCancel = { onCommitContinuousEdit() }
                    )
                }
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelect()
                }
                .testTag("timeline_audio_clip_${audioClip.id}"),
            color = containerColor,
            shape = audioShape
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = audioClip.kind.label,
                    tint = if (audioClip.isMuted) TextMuted else tint,
                    modifier = Modifier.size(16.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = audioClip.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (audioClip.isMuted) TextSecondary else TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (audioClip.isMuted) {
                            "MUTED • ${formatTimelineSec(audioClip.effectiveDurationMs)}"
                        } else {
                            "${(audioClip.volume * 100).toInt()}% • ${formatTimelineSec(audioClip.effectiveDurationMs)}"
                        },
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                        color = if (audioClip.isMuted) CrimsonAccent else TextSecondary,
                        maxLines = 1
                    )
                }
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleMute()
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("audio_track_mute_${audioClip.id}")
                ) {
                    Icon(
                        imageVector = if (audioClip.isMuted) {
                            Icons.AutoMirrored.Filled.VolumeOff
                        } else {
                            Icons.AutoMirrored.Filled.VolumeUp
                        },
                        contentDescription = if (audioClip.isMuted) "Unmute ${audioClip.displayName}" else "Mute ${audioClip.displayName}",
                        tint = if (audioClip.isMuted) CrimsonAccent else TextPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlayTrackClipRow(
    overlay: TimelineTextOverlay,
    dpPerMs: Float,
    pxPerMs: Float,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onBeginContinuousEdit: () -> Unit,
    onCommitContinuousEdit: () -> Unit,
    onMoveStartMs: (Long) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val latest by rememberUpdatedState(overlay)
    val startDp = (overlay.timelineStartMs * dpPerMs).dp
    val widthDp = (overlay.durationMs * dpPerMs).dp.coerceAtLeast(80.dp)
    val tint = if (overlay.isCaption) SignalEmerald else ClotchBlue
    val bg = if (overlay.isCaption) SignalEmeraldMuted else ClotchBlueMuted
    val shape = RoundedCornerShape(10.dp)

    val selectedMod = if (isSelected) {
        Modifier
            .shadow(6.dp, shape, ambientColor = tint, spotColor = tint)
            .border(1.5.dp, TextPrimary, shape)
    } else {
        Modifier
    }

    Box(modifier = Modifier.fillMaxWidth().height(40.dp)) {
        Surface(
            modifier = Modifier
                .offset(x = startDp)
                .width(widthDp)
                .height(36.dp)
                .then(selectedMod)
                .pointerInput(overlay.id, pxPerMs) {
                    var dragStart = latest.timelineStartMs
                    detectHorizontalDragGestures(
                        onDragStart = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect()
                            onBeginContinuousEdit()
                            dragStart = latest.timelineStartMs
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragStart = (dragStart + (dragAmount / pxPerMs).roundToLong()).coerceAtLeast(0L)
                            onMoveStartMs(dragStart)
                        },
                        onDragEnd = { onCommitContinuousEdit() },
                        onDragCancel = { onCommitContinuousEdit() }
                    )
                }
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelect()
                }
                .testTag("timeline_overlay_clip_${overlay.id}"),
            color = bg,
            shape = shape
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = if (overlay.isCaption) Icons.Filled.AutoAwesome else Icons.Filled.TextFields,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = overlay.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun StickerTrackPill(
    sticker: TimelineSticker,
    dpPerMs: Float,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val widthDp = (sticker.durationMs * dpPerMs).dp.coerceAtLeast(56.dp)
    TactileSurface(
        onClick = onSelect,
        modifier = Modifier
            .width(widthDp)
            .height(34.dp)
            .then(
                if (isSelected) {
                    Modifier.border(1.5.dp, ClotchBlue, ClotchPillShape)
                } else {
                    Modifier
                }
            )
            .testTag("timeline_sticker_${sticker.id}"),
        color = SlateCard,
        shape = ClotchPillShape,
        pressedScale = 0.94f
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = sticker.emoji, fontSize = 16.sp)
        }
    }
}

private fun formatTimelineSec(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%d:%02d", mins, secs)
}
