package com.example.ui.screens

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import android.util.LruCache
import com.example.diagnostics.MemoryPressureListener
import com.example.diagnostics.MemoryPressureManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.RecordedClip
import com.example.ui.components.ClotchCardShape
import com.example.ui.components.ClotchInnerCardShape
import com.example.ui.components.ClotchPillShape
import com.example.ui.components.ShimmerBox
import com.example.ui.components.TactileIconButton
import com.example.ui.components.TactilePillButton
import com.example.ui.components.TactileSurface
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.ClotchBlue
import com.example.ui.theme.ClotchBlueMuted
import com.example.ui.theme.CrimsonAccent
import com.example.ui.theme.CrimsonGlow
import com.example.ui.theme.ElevatedSlate
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.SlateCard
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

@Composable
fun ClipsScreen(
    clips: List<RecordedClip>,
    isLoadingClips: Boolean = false,
    onRefreshClips: () -> Unit,
    onDeleteClip: (RecordedClip) -> Unit,
    onShowMessage: (String) -> Unit,
    onEditClip: (RecordedClip) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var clipPendingDelete by remember { mutableStateOf<RecordedClip?>(null) }

    LaunchedEffect(Unit) {
        onRefreshClips()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Saved Clips",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = if (isLoadingClips && clips.isEmpty()) {
                            "Scanning Movies/Clotch..."
                        } else {
                            "${clips.size} ${if (clips.size == 1) "recording" else "recordings"} in Movies/Clotch"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                TactileIconButton(
                    onClick = onRefreshClips,
                    modifier = Modifier.testTag("refresh_clips_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh clips",
                        tint = TextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            when {
                isLoadingClips && clips.isEmpty() -> {
                    ClipsShimmerLoadingList(modifier = Modifier.weight(1f))
                }
                clips.isEmpty() -> {
                    EmptyClipsState(modifier = Modifier.weight(1f))
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(clips, key = { it.id }) { clip ->
                            ClipItemCard(
                                clip = clip,
                                onPlay = {
                                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(clip.uri, clip.mimeType)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    runCatching {
                                        context.startActivity(viewIntent)
                                    }.onFailure {
                                        onShowMessage("No video player found to open ${clip.displayName}")
                                    }
                                },
                                onShare = {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "video/mp4"
                                        putExtra(Intent.EXTRA_STREAM, clip.uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    runCatching {
                                        context.startActivity(
                                            Intent.createChooser(shareIntent, "Share Clotch Clip")
                                        )
                                    }.onFailure {
                                        onShowMessage("Unable to share clip")
                                    }
                                },
                                onEdit = { onEditClip(clip) },
                                onDelete = { clipPendingDelete = clip }
                            )
                        }
                    }
                }
            }
        }
    }

    clipPendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { clipPendingDelete = null },
            containerColor = SlateCard,
            shape = RoundedCornerShape(24.dp),
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = {
                Text(
                    text = "Delete Recording?",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = "This permanently removes ${target.displayName} from your device storage.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TactilePillButton(
                    onClick = {
                        onDeleteClip(target)
                        clipPendingDelete = null
                    },
                    containerColor = CrimsonAccent,
                    contentColor = TextPrimary
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { clipPendingDelete = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun ClipsShimmerLoadingList(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("clips_shimmer_loader"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        repeat(3) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = SlateCard,
                shape = ClotchCardShape
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        ShimmerBox(
                            modifier = Modifier.size(60.dp),
                            shape = RoundedCornerShape(14.dp),
                            baseColor = CarbonSurface
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ShimmerBox(
                                modifier = Modifier
                                    .fillMaxWidth(0.7f)
                                    .height(16.dp),
                                shape = ClotchPillShape,
                                baseColor = CarbonSurface
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ShimmerBox(
                                    modifier = Modifier
                                        .width(56.dp)
                                        .height(18.dp),
                                    shape = ClotchPillShape,
                                    baseColor = CarbonSurface
                                )
                                ShimmerBox(
                                    modifier = Modifier
                                        .width(48.dp)
                                        .height(18.dp),
                                    shape = ClotchPillShape,
                                    baseColor = CarbonSurface
                                )
                            }
                            ShimmerBox(
                                modifier = Modifier
                                    .width(96.dp)
                                    .height(12.dp),
                                shape = ClotchPillShape,
                                baseColor = CarbonSurface
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ShimmerBox(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = ClotchPillShape,
                            baseColor = CarbonSurface
                        )
                        ShimmerBox(
                            modifier = Modifier
                                .width(54.dp)
                                .height(46.dp),
                            shape = ClotchPillShape,
                            baseColor = CarbonSurface
                        )
                        ShimmerBox(
                            modifier = Modifier
                                .width(54.dp)
                                .height(46.dp),
                            shape = ClotchPillShape,
                            baseColor = CarbonSurface
                        )
                        ShimmerBox(
                            modifier = Modifier
                                .width(54.dp)
                                .height(46.dp),
                            shape = ClotchPillShape,
                            baseColor = CarbonSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyClipsState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = SlateCard,
            shape = ClotchCardShape
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(ElevatedSlate),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Videocam,
                        contentDescription = null,
                        tint = ClotchBlue,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Text(
                    text = "No Clips Captured Yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Text(
                    text = "Open Recording from the home screen to capture gameplay at up to 60 FPS and save MP4 clips here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun ClipItemCard(
    clip: RecordedClip,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val formattedSize = remember(clip.sizeBytes) {
        Formatter.formatShortFileSize(context, clip.sizeBytes)
    }
    val formattedDate = remember(clip.dateAddedSec) {
        if (clip.dateAddedSec > 0L) {
            SimpleDateFormat("MMM d • HH:mm", Locale.getDefault())
                .format(Date(clip.dateAddedSec * 1000L))
        } else {
            "Just now"
        }
    }
    val formattedDuration = remember(clip.durationMs) {
        val totalSec = (clip.durationMs / 1000L).coerceAtLeast(0L)
        String.format(Locale.US, "%02d:%02d", totalSec / 60L, totalSec % 60L)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("clip_card_${clip.id}"),
        color = SlateCard,
        shape = ClotchCardShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                val thumbnailBitmap by produceState<Bitmap?>(
                    initialValue = ClipsThumbnailCache.get(clip.uri, clip.sizeBytes),
                    key1 = clip.uri,
                    key2 = clip.sizeBytes
                ) {
                    if (value == null) {
                        value = extractFirstVideoFrame(context, clip.uri, clip.sizeBytes)
                    }
                }

                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(ClotchInnerCardShape)
                        .background(CarbonSurface),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = thumbnailBitmap
                    if (bmp != null && !bmp.isRecycled) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Thumbnail for ${clip.displayName}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(ClotchInnerCardShape)
                        )
                    } else {
                        ShimmerBox(
                            modifier = Modifier.fillMaxSize(),
                            shape = ClotchInnerCardShape,
                            baseColor = CarbonSurface
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = clip.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val resTag = if (clip.width > 0 && clip.height > 0) {
                            "${clip.width}×${clip.height}"
                        } else {
                            "MP4"
                        }
                        ClipMetaPill(resTag)
                        ClipMetaPill(formattedDuration, mono = true)
                        ClipMetaPill(formattedSize)
                    }

                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TactilePillButton(
                    onClick = onPlay,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("play_clip_${clip.id}"),
                    containerColor = ElevatedSlate,
                    contentColor = TextPrimary
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play clip",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Play", style = MaterialTheme.typography.labelLarge)
                }

                TactileSurface(
                    onClick = onEdit,
                    modifier = Modifier
                        .height(48.dp)
                        .width(56.dp)
                        .testTag("edit_clip_${clip.id}"),
                    color = ClotchBlueMuted,
                    shape = ClotchPillShape,
                    pressedScale = 0.93f,
                    hapticType = HapticFeedbackType.LongPress
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.MovieCreation,
                            contentDescription = "Edit clip",
                            tint = ClotchBlue,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                TactileSurface(
                    onClick = onShare,
                    modifier = Modifier
                        .height(48.dp)
                        .width(56.dp)
                        .testTag("share_clip_${clip.id}"),
                    color = CarbonSurface,
                    shape = ClotchPillShape,
                    pressedScale = 0.93f
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = "Share clip",
                            tint = TextSecondary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                TactileSurface(
                    onClick = onDelete,
                    modifier = Modifier
                        .height(48.dp)
                        .width(56.dp)
                        .testTag("delete_clip_${clip.id}"),
                    color = CrimsonGlow,
                    shape = ClotchPillShape,
                    pressedScale = 0.93f,
                    hapticType = HapticFeedbackType.LongPress
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.DeleteOutline,
                            contentDescription = "Delete clip",
                            tint = CrimsonAccent,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipMetaPill(label: String, mono: Boolean = false) {
    Surface(
        color = CarbonSurface,
        shape = ClotchPillShape
    ) {
        Text(
            text = label,
            fontFamily = if (mono) JetBrainsMonoFamily else MaterialTheme.typography.labelSmall.fontFamily,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

private object ClipsThumbnailCache {
    @OptIn(ExperimentalCoroutinesApi::class)
    val decodeDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val cache = object : LruCache<String, Bitmap>(4 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    @Suppress("DEPRECATION")
    private val pressureListener = MemoryPressureListener { level ->
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        ) {
            cache.evictAll()
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            cache.trimToSize(cache.size() / 2)
        }
    }

    init {
        MemoryPressureManager.registerListener(pressureListener)
    }

    fun get(uri: Uri, sizeBytes: Long): Bitmap? = cache.get("${uri}_${sizeBytes}")?.takeIf { !it.isRecycled }

    fun put(uri: Uri, sizeBytes: Long, bitmap: Bitmap) {
        cache.put("${uri}_${sizeBytes}", bitmap)
    }

    fun trimAll() {
        cache.evictAll()
    }
}

private suspend fun extractFirstVideoFrame(context: Context, uri: Uri, sizeBytes: Long): Bitmap? {
    ClipsThumbnailCache.get(uri, sizeBytes)?.let { return it }
    val appContext = context.applicationContext
    return withContext(ClipsThumbnailCache.decodeDispatcher) {
        ClipsThumbnailCache.get(uri, sizeBytes)?.let { return@withContext it }
        var retriever: MediaMetadataRetriever? = null
        val rawDecoded = try {
            retriever = MediaMetadataRetriever()
            var pfdOk = false
            if (uri.scheme.equals("content", ignoreCase = true)) {
                appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                    pfdOk = true
                }
            }
            if (!pfdOk) {
                retriever.setDataSource(appContext, uri)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    192,
                    192
                ) ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } else {
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (oom: OutOfMemoryError) {
            ClipsThumbnailCache.trimAll()
            com.example.diagnostics.CrashLogger.logError("ClipsScreen.Thumbnail", "OOM extracting clip thumb for $uri", oom)
            null
        } catch (t: Throwable) {
            com.example.diagnostics.CrashLogger.logError("ClipsScreen.Thumbnail", "Failed extracting clip thumb for $uri", t)
            null
        } finally {
            runCatching { retriever?.release() }
        }
        val compact = compactThumbBitmap(rawDecoded, 192, 192)
        if (compact != null && !compact.isRecycled) {
            ClipsThumbnailCache.put(uri, sizeBytes, compact)
        }
        compact
    }
}

private fun compactThumbBitmap(raw: Bitmap?, maxW: Int, maxH: Int): Bitmap? {
    raw ?: return null
    if (raw.isRecycled) return null
    val softwareBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        raw.config == Bitmap.Config.HARDWARE
    ) {
        val copied = runCatching { raw.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        raw.recycle()
        copied ?: return null
    } else {
        raw
    }
    if (softwareBitmap.width <= maxW &&
        softwareBitmap.height <= maxH &&
        softwareBitmap.config == Bitmap.Config.RGB_565
    ) {
        return softwareBitmap
    }
    val scale = minOf(
        maxW.toFloat() / softwareBitmap.width.coerceAtLeast(1),
        maxH.toFloat() / softwareBitmap.height.coerceAtLeast(1),
        1f
    )
    val outW = (softwareBitmap.width * scale).toInt().coerceAtLeast(32)
    val outH = (softwareBitmap.height * scale).toInt().coerceAtLeast(32)
    return try {
        val compact = Bitmap.createBitmap(outW, outH, Bitmap.Config.RGB_565)
        Canvas(compact).drawBitmap(
            softwareBitmap,
            android.graphics.Rect(0, 0, softwareBitmap.width, softwareBitmap.height),
            android.graphics.Rect(0, 0, outW, outH),
            null
        )
        softwareBitmap.recycle()
        compact
    } catch (_: OutOfMemoryError) {
        softwareBitmap.recycle()
        ClipsThumbnailCache.trimAll()
        null
    } catch (_: Throwable) {
        softwareBitmap.recycle()
        null
    }
}
