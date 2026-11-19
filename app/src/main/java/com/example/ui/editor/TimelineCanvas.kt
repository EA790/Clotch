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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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

private sealed interface FilmstripSlotItem {
    val stableKey: String
    val widthDp: Dp

    data class ThumbSlot(
        override val stableKey: String,
        val sourceUri: android.net.Uri,
        val sampleTimeMs: Long,
        val bucketKey: Long,
        override val widthDp: Dp
    ) : FilmstripSlotItem

    data class TransitionSpacer(
        override val stableKey: String,
        override val widthDp: Dp
    ) : FilmstripSlotItem
}

@Composable
fun TimelineCanvas(
    project: EditorProject,
    playheadMsFlow: StateFlow<Long>,
    thumbnailCache: EditorThumbnailCache,
    isPlaying: Boolean,
    onSeekTo: (Long) -> Unit,
    onScrubFinished: () -> Unit = {},
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
    val maxTimelineWidthDp = 10_000f
    val timelineEndPadDp = 180f
    val rawDpPerMs = (64f * project.zoomLevel) / 1000f
    val totalMs = project.totalTimelineDurationMs.coerceAtLeast(10_000L)
    val dpPerMs = minOf(
        rawDpPerMs,
        (maxTimelineWidthDp - timelineEndPadDp) / totalMs.toFloat()
    ).coerceAtLeast(0.0005f)
    val pxPerMs = (with(density) { (dpPerMs * 1000f).dp.toPx() } / 1000f).coerceAtLeast(0.001f)
    val trackPadDp = 16.dp
    val trackPadPx = with(density) { trackPadDp.toPx() }
    val transitionBadgePx = with(density) { 32.dp.toPx() }

    val contentWidthDp: Dp = ((totalMs * dpPerMs) + timelineEndPadDp)
        .coerceIn(320f, maxTimelineWidthDp)
        .dp
    val latestTotalDuration by rememberUpdatedState(project.totalTimelineDurationMs.coerceAtLeast(1000L))

    // Quantized horizontal viewport bounds to prevent scroll-pixel recomposition jitter
    val visibleWindowPx by remember {
        derivedStateOf {
            val start = scrollState.value
            val viewport = scrollState.viewportSize.coerceAtLeast(1080)
            val bucket = 180
            val qStart = (start / bucket) * bucket
            val qEnd = (((start + viewport) / bucket) + 1) * bucket
            qStart.toFloat() to qEnd.toFloat()
        }
    }
    val visibleStartPx = visibleWindowPx.first
    val visibleEndPx = visibleWindowPx.second

    val filmstripLazyState = rememberLazyListState()
    val filmstripItems by remember(project.videoClips, dpPerMs) {
        derivedStateOf {
            buildList {
                project.videoClips.forEachIndexed { index, clip ->
                    val clipWidthDp = (clip.effectiveDurationMs * dpPerMs).dp
                        .coerceIn(64.dp, 10000.dp)
                    val thumbCount = ((clipWidthDp.value / 68f).toInt()).coerceIn(1, 120)
                    val slotWidthDp = (clipWidthDp / thumbCount.toFloat()).coerceIn(1.dp, 10000.dp)
                    for (slot in 0 until thumbCount) {
                        val fraction = (slot + 0.5f) / thumbCount.toFloat()
                        val sampleTimeMs = clip.trimStartMs + (clip.effectiveDurationMs * fraction).toLong()
                        val bucketKey = sampleTimeMs / 500L
                        add(
                            FilmstripSlotItem.ThumbSlot(
                                stableKey = "thumb_${clip.id}_$slot",
                                sourceUri = clip.sourceUri,
                                sampleTimeMs = sampleTimeMs,
                                bucketKey = bucketKey,
                                widthDp = slotWidthDp
                            )
                        )
                    }
                    if (index < project.videoClips.lastIndex) {
                        add(
                            FilmstripSlotItem.TransitionSpacer(
                                stableKey = "gap_${clip.id}",
                                widthDp = 32.dp
                            )
                        )
                    }
                }
            }
        }
    }

    val cumulativeItemOffsetsPx = remember(filmstripItems, density, trackPadPx) {
        val offsets = IntArray(filmstripItems.size + 1)
        var acc = trackPadPx
        filmstripItems.forEachIndexed { idx, item ->
            offsets[idx] = acc.roundToInt()
            acc += with(density) { item.widthDp.toPx() }
        }
        offsets[filmstripItems.size] = acc.roundToInt()
        offsets
    }

    LaunchedEffect(scrollState, cumulativeItemOffsetsPx, filmstripItems.size) {
        if (filmstripItems.isEmpty()) return@LaunchedEffect
        snapshotFlow { scrollState.value }.collect { scrollPx ->
            val maxIdx = filmstripItems.lastIndex
            if (maxIdx < 0) return@collect
            var low = 0
            var high = maxIdx
            var foundIdx = 0
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (cumulativeItemOffsetsPx[mid] <= scrollPx) {
                    foundIdx = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            val offsetInsideItem = (scrollPx - cumulativeItemOffsetsPx[foundIdx]).coerceAtLeast(0)
            filmstripLazyState.scrollToItem(index = foundIdx, scrollOffset = offsetInsideItem)
        }
    }

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
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val viewportWidthDp = maxWidth.coerceIn(1.dp, 10000.dp)

            // Truly lazy viewport-bounded LazyRow for filmstrip thumbnails:
            // Only composes/decodes frames visible inside the viewport width.
            if (project.videoClips.isNotEmpty()) {
                LazyRow(
                    state = filmstripLazyState,
                    userScrollEnabled = false,
                    contentPadding = PaddingValues(horizontal = trackPadDp),
                    modifier = Modifier
                        .padding(top = 40.dp)
                        .width(viewportWidthDp)
                        .widthIn(max = 10000.dp)
                        .height(80.dp)
                        .clip(RoundedCornerShape(14.dp))
                ) {
                    items(
                        items = filmstripItems,
                        key = { it.stableKey }
                    ) { slotItem ->
                        when (slotItem) {
                            is FilmstripSlotItem.TransitionSpacer -> {
                                Spacer(
                                    modifier = Modifier
                                        .width(slotItem.widthDp.coerceIn(1.dp, 10000.dp))
                                        .height(80.dp)
                                )
                            }
                            is FilmstripSlotItem.ThumbSlot -> {
                                LazyFilmstripThumbCell(
                                    slot = slotItem,
                                    thumbnailCache = thumbnailCache
                                )
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .padding(vertical = 10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(contentWidthDp)
                        .widthIn(max = 10000.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Single-Canvas Time Ruler (Zero child composables during scrolling)
                        TimelineRuler(
                            totalMs = totalMs,
                            zoomLevel = project.zoomLevel,
                            pxPerMs = pxPerMs,
                            trackPadDp = trackPadDp,
                            visibleStartPx = visibleStartPx,
                            visibleEndPx = visibleEndPx,
                            maxSeekMs = latestTotalDuration,
                            onSeekTo = onSeekTo,
                            onScrubFinished = onScrubFinished,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(24.dp)
                        )

                        // Video Track interactive selection, trim handles & transition badges aligned over LazyRow filmstrip
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 10000.dp)
                                .padding(horizontal = trackPadDp)
                        ) {
                            if (project.videoClips.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .width(240.dp)
                                        .widthIn(max = 10000.dp)
                                        .height(80.dp)
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
                                        .widthIn(max = 10000.dp)
                                        .height(80.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    project.videoClips.forEachIndexed { index, clip ->
                                        val clipWidthDp = (clip.effectiveDurationMs * dpPerMs).dp
                                            .coerceIn(64.dp, 10000.dp)

                                        VideoTrackClipItem(
                                            clip = clip,
                                            index = index,
                                            totalClips = project.videoClips.size,
                                            widthDp = clipWidthDp,
                                            pxPerMs = pxPerMs,
                                            isSelected = clip.id == project.selectedVideoClipId,
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
                                .padding(horizontal = trackPadDp, vertical = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "TEXT & CAPTIONS",
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

                    // Independent Audio Tracks (Game / Chat / Mic / BGM / Voiceover)
                    if (project.audioClips.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = trackPadDp, vertical = 2.dp),
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

                // True InShot / Premiere Playhead Needle: overlaps directly ON TOP of video frames & all tracks
                TimelinePlayheadNeedle(
                    playheadMsFlow = playheadMsFlow,
                    trackPadPx = trackPadPx,
                    pxPerMs = pxPerMs,
                    maxSeekMs = latestTotalDuration,
                    onSeekTo = onSeekTo,
                    onScrubFinished = onScrubFinished,
                    modifier = Modifier
                        .matchParentSize()
                        .zIndex(25f)
                )
            }
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
 * InShot/CapCut-style playhead: small top pill handle + thin 2.dp vertical white needle
 * drawn directly OVER the video filmstrip, overlays, and audio tracks with direct drag scrubbing.
 */
@Composable
private fun TimelinePlayheadNeedle(
    playheadMsFlow: StateFlow<Long>,
    trackPadPx: Float,
    pxPerMs: Float,
    maxSeekMs: Long,
    onSeekTo: (Long) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playheadMs by playheadMsFlow.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val latestMaxSeek by rememberUpdatedState(maxSeekMs)
    val latestPlayheadMs by rememberUpdatedState(playheadMs)
    val touchHalfWidthPx = with(density) { 14.dp.toPx() }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = (trackPadPx + playheadMs * pxPerMs - touchHalfWidthPx).roundToInt(),
                        y = 0
                    )
                }
                .width(28.dp)
                .fillMaxHeight()
                .pointerInput(pxPerMs) {
                    var dragStartMs = 0L
                    var accumulatedPx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            dragStartMs = latestPlayheadMs
                            accumulatedPx = 0f
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            accumulatedPx += dragAmount
                            val targetMs = (dragStartMs + (accumulatedPx / pxPerMs).roundToLong())
                                .coerceIn(0L, latestMaxSeek)
                            onSeekTo(targetMs)
                        },
                        onDragEnd = { onScrubFinished() },
                        onDragCancel = { onScrubFinished() }
                    )
                },
            contentAlignment = Alignment.TopCenter
        ) {
            // Top horizontal rounded cap overlapping the ruler edge
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .width(16.dp)
                    .height(8.dp)
                    .shadow(6.dp, RoundedCornerShape(4.dp), ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(RoundedCornerShape(4.dp))
                    .background(TextPrimary),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(ClotchBlue)
                )
            }
            // Thin 2.dp crisp white needle extending directly over all frames & tracks below
            Box(
                modifier = Modifier
                    .padding(top = 13.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .shadow(4.dp, RoundedCornerShape(1.dp), ambientColor = Color.Black, spotColor = Color.Black)
                    .background(TextPrimary)
            )
        }
    }
}

@Composable
private fun TimelineRuler(
    totalMs: Long,
    zoomLevel: Float,
    pxPerMs: Float,
    trackPadDp: Dp,
    visibleStartPx: Float,
    visibleEndPx: Float,
    maxSeekMs: Long,
    onSeekTo: (Long) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val textMeasurer = rememberTextMeasurer()
    val latestMaxSeek by rememberUpdatedState(maxSeekMs)
    val labelStyle = remember {
        TextStyle(
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            color = TextMuted
        )
    }
    val stepMs = when {
        zoomLevel >= 2.2f -> 1000L
        zoomLevel >= 1.2f -> 2000L
        else -> 5000L
    }
    val safePxPerMs = pxPerMs.coerceAtLeast(0.001f)
    val marginPx = 260f
    val startTickMs = (((visibleStartPx - marginPx).coerceAtLeast(0f) / safePxPerMs) / stepMs)
        .toLong()
        .coerceAtLeast(0L) * stepMs
    val endTickMs = minOf(
        totalMs,
        ((((visibleEndPx + marginPx) / safePxPerMs) / stepMs).toLong() + 1L) * stepMs
    )

    Box(
        modifier = modifier
            .padding(horizontal = trackPadDp)
            .pointerInput(pxPerMs) {
                detectTapGestures { offset ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val targetMs = (offset.x / pxPerMs).roundToLong().coerceIn(0L, latestMaxSeek)
                    onSeekTo(targetMs)
                    onScrubFinished()
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
                    },
                    onDragEnd = { onScrubFinished() },
                    onDragCancel = { onScrubFinished() }
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
                        start = Offset(x, h - 7f),
                        end = Offset(x, h - 1f),
                        strokeWidth = 2f
                    )
                    val measured = textMeasurer.measure(
                        text = formatTimelineSec(t),
                        style = labelStyle
                    )
                    if (x + measured.size.width + 4f <= size.width) {
                        drawText(
                            textLayoutResult = measured,
                            topLeft = Offset(x + 4f, 0f)
                        )
                    }
                }
                t += stepMs
            }
        }
    }
}

@Composable
private fun LazyFilmstripThumbCell(
    slot: FilmstripSlotItem.ThumbSlot,
    thumbnailCache: EditorThumbnailCache
) {
    val context = LocalContext.current
    val cachedInstant = remember(slot.sourceUri, slot.bucketKey) {
        thumbnailCache.peekFrame(slot.sourceUri, slot.sampleTimeMs)
    }
    val bitmap by produceState<Bitmap?>(
        initialValue = cachedInstant,
        key1 = slot.sourceUri,
        key2 = slot.bucketKey
    ) {
        if (value == null) {
            value = thumbnailCache.getFrame(context, slot.sourceUri, slot.sampleTimeMs)
        }
    }
    Box(
        modifier = Modifier
            .width(slot.widthDp.coerceIn(1.dp, 10000.dp))
            .height(80.dp)
            .background(ObsidianBg)
    ) {
        val bmp = bitmap
        if (bmp != null && !bmp.isRecycled) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun VideoTrackClipItem(
    clip: TimelineVideoClip,
    index: Int,
    totalClips: Int,
    widthDp: Dp,
    pxPerMs: Float,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onBeginContinuousEdit: () -> Unit,
    onCommitContinuousEdit: () -> Unit,
    onTrim: (newStartMs: Long, newEndMs: Long) -> Unit,
    onMoveOrder: (targetIndex: Int) -> Unit
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val currentClip by rememberUpdatedState(clip)
    var dragReorderOffsetPx by remember { mutableFloatStateOf(0f) }
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
            .border(2.dp, ClotchBlue, clipShape)
    } else {
        Modifier
            .clip(clipShape)
            .border(1.dp, ElevatedSlate.copy(alpha = 0.55f), clipShape)
    }

    Box(
        modifier = Modifier
            .width(widthDp.coerceIn(64.dp, 10000.dp))
            .widthIn(max = 10000.dp)
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
    val startDp = (audioClip.timelineStartMs * dpPerMs).dp.coerceIn(0.dp, 9850.dp)
    val widthDp = (audioClip.effectiveDurationMs * dpPerMs).dp.coerceIn(148.dp, 10000.dp)

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
    val startDp = (overlay.timelineStartMs * dpPerMs).dp.coerceIn(0.dp, 9900.dp)
    val widthDp = (overlay.durationMs * dpPerMs).dp.coerceIn(80.dp, 10000.dp)
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
    val widthDp = (sticker.durationMs * dpPerMs).dp.coerceIn(56.dp, 10000.dp)
    TactileSurface(
        onClick = onSelect,
        modifier = Modifier
            .width(widthDp)
            .widthIn(max = 10000.dp)
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
