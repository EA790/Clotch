package com.example.editor.engine

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import com.example.diagnostics.CrashLogger
import com.example.diagnostics.MemoryPressureListener
import com.example.diagnostics.MemoryPressureManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Heap-bounded LRU thumbnail cache for timeline filmstrips.
 * Never keeps a MediaMetadataRetriever instance pinned open between frame decodes:
 * each decode opens a retriever, extracts one frame, and immediately calls release()
 * in a try/finally block so native codecs & file descriptors are freed right away.
 */
class EditorThumbnailCache {

    companion object {
        private const val BUCKET_MS = 500L

        private fun computeMaxCacheKb(): Int {
            val maxHeapKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt().coerceAtLeast(32 * 1024)
            return (maxHeapKb / 24).coerceIn(4 * 1024, 8 * 1024)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val maxCacheKb = computeMaxCacheKb()

    private val memoryCache = object : LruCache<String, Bitmap>(maxCacheKb) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
    }

    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Bitmap?>>()

    private val pressureListener = MemoryPressureListener { level ->
        onTrimMemory(level)
    }

    init {
        MemoryPressureManager.registerListener(pressureListener)
    }

    fun peekFrame(
        uri: Uri,
        timeMs: Long,
        targetWidth: Int = 160,
        targetHeight: Int = 90
    ): Bitmap? {
        val key = buildCacheKey(uri, timeMs, targetWidth, targetHeight)
        return memoryCache.get(key)?.takeIf { !it.isRecycled }
    }

    suspend fun getFrame(
        context: Context,
        uri: Uri,
        timeMs: Long,
        targetWidth: Int = 160,
        targetHeight: Int = 90
    ): Bitmap? {
        val safeW = targetWidth.coerceIn(64, 180)
        val safeH = targetHeight.coerceIn(48, 110)
        val key = buildCacheKey(uri, timeMs, safeW, safeH)

        memoryCache.get(key)?.takeIf { !it.isRecycled }?.let { return it }

        val existing = inFlight[key]
        if (existing != null) {
            return runCatching { existing.await() }.getOrNull()
        }

        val deferred = CompletableDeferred<Bitmap?>()
        val raced = inFlight.putIfAbsent(key, deferred)
        if (raced != null) {
            return runCatching { raced.await() }.getOrNull()
        }

        val appContext = context.applicationContext
        val bucketMs = ((timeMs.coerceAtLeast(0L)) / BUCKET_MS) * BUCKET_MS
        return try {
            val extracted = withContext(decodeDispatcher) {
                coroutineContext.ensureActive()
                memoryCache.get(key)?.takeIf { !it.isRecycled }?.let { return@withContext it }

                val timeUs = (bucketMs * 1000L).coerceAtLeast(0L)
                val rawBitmap = extractSingleFrameSafely(
                    appContext = appContext,
                    uri = uri,
                    timeUs = timeUs,
                    targetWidth = safeW,
                    targetHeight = safeH
                )
                toCompactThumbnail(rawBitmap, safeW, safeH)
            }

            if (extracted != null && !extracted.isRecycled) {
                memoryCache.put(key, extracted)
            }
            deferred.complete(extracted)
            extracted
        } catch (ce: CancellationException) {
            deferred.cancel(ce)
            throw ce
        } catch (oom: OutOfMemoryError) {
            onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
            CrashLogger.logError("EditorThumbnailCache", "OOM extracting frame at ${bucketMs}ms for $uri", oom)
            deferred.complete(null)
            null
        } catch (_: Throwable) {
            deferred.complete(null)
            null
        } finally {
            inFlight.remove(key, deferred)
        }
    }

    private fun extractSingleFrameSafely(
        appContext: Context,
        uri: Uri,
        timeUs: Long,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        var retriever: MediaMetadataRetriever? = null
        return try {
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
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    targetWidth,
                    targetHeight
                ) ?: retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
            } else {
                retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
            }
        } catch (oom: OutOfMemoryError) {
            onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
            CrashLogger.logError("EditorThumbnailCache", "OOM decoding thumbnail frame for $uri", oom)
            null
        } catch (t: Throwable) {
            CrashLogger.logError("EditorThumbnailCache", "Thumbnail decode failed for $uri", t)
            null
        } finally {
            runCatching { retriever?.release() }
        }
    }

    private fun toCompactThumbnail(
        raw: Bitmap?,
        maxW: Int,
        maxH: Int
    ): Bitmap? {
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
            val canvas = Canvas(compact)
            val srcRect = android.graphics.Rect(0, 0, softwareBitmap.width, softwareBitmap.height)
            val dstRect = android.graphics.Rect(0, 0, outW, outH)
            canvas.drawBitmap(softwareBitmap, srcRect, dstRect, null)
            softwareBitmap.recycle()
            compact
        } catch (oom: OutOfMemoryError) {
            softwareBitmap.recycle()
            onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
            null
        } catch (_: Throwable) {
            softwareBitmap.recycle()
            null
        }
    }

    private fun buildCacheKey(uri: Uri, timeMs: Long, targetWidth: Int, targetHeight: Int): String {
        val safeW = targetWidth.coerceIn(64, 180)
        val safeH = targetHeight.coerceIn(48, 110)
        val bucketMs = ((timeMs.coerceAtLeast(0L)) / BUCKET_MS) * BUCKET_MS
        return "${uri}_${bucketMs}_${safeW}x${safeH}"
    }

    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                memoryCache.evictAll()
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                memoryCache.trimToSize(memoryCache.size() / 2)
            }
            else -> {
                memoryCache.trimToSize((memoryCache.size() * 3) / 4)
            }
        }
    }

    fun clear() {
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        memoryCache.evictAll()
    }

    fun release() {
        MemoryPressureManager.unregisterListener(pressureListener)
        clear()
    }

    internal fun currentSizeKb(): Int = memoryCache.size()
}
