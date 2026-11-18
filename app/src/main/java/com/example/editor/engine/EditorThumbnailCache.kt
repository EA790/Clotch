package com.example.editor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class EditorThumbnailCache {

    companion object {
        // 12 MB byte-bounded RGB_565 bitmap pool (~420 filmstrip frames in ~12 MB)
        private const val MAX_CACHE_KB = 12 * 1024
        private const val BUCKET_MS = 500L
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val memoryCache = object : LruCache<String, Bitmap>(MAX_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
    }

    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Bitmap?>>()

    // Guarded strictly by single-threaded decodeDispatcher
    private var pooledUriKey: String? = null
    private var pooledRetriever: MediaMetadataRetriever? = null

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
        val safeW = targetWidth.coerceIn(64, 200)
        val safeH = targetHeight.coerceIn(48, 120)
        val bucketMs = ((timeMs.coerceAtLeast(0L)) / BUCKET_MS) * BUCKET_MS
        val key = "${uri}_${bucketMs}_${safeW}x${safeH}"

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
        return try {
            val extracted = withContext(decodeDispatcher + NonCancellable) {
                memoryCache.get(key)?.takeIf { !it.isRecycled }?.let { return@withContext it }

                val retriever = obtainPooledRetriever(appContext, uri) ?: return@withContext null
                val timeUs = (bucketMs * 1000L).coerceAtLeast(0L)
                val rawBitmap = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(
                            timeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            safeW,
                            safeH
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
                } catch (_: Throwable) {
                    // Retriever may have lost its underlying FD/decoder; reset pool for next request
                    releasePooledRetriever()
                    null
                }
                toCompactThumbnail(rawBitmap, safeW, safeH)
            }

            if (extracted != null && !extracted.isRecycled) {
                memoryCache.put(key, extracted)
            }
            deferred.complete(extracted)
            extracted
        } catch (_: Throwable) {
            deferred.complete(null)
            null
        } finally {
            inFlight.remove(key, deferred)
        }
    }

    private fun obtainPooledRetriever(appContext: Context, uri: Uri): MediaMetadataRetriever? {
        val uriKey = uri.toString()
        if (pooledUriKey == uriKey && pooledRetriever != null) {
            return pooledRetriever
        }
        releasePooledRetriever()

        val retriever = MediaMetadataRetriever()
        val opened = try {
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
            true
        } catch (_: Throwable) {
            false
        }

        return if (opened) {
            pooledUriKey = uriKey
            pooledRetriever = retriever
            retriever
        } else {
            runCatching { retriever.release() }
            null
        }
    }

    private fun releasePooledRetriever() {
        val existing = pooledRetriever
        pooledRetriever = null
        pooledUriKey = null
        if (existing != null) {
            runCatching { existing.release() }
        }
    }

    private fun toCompactThumbnail(
        raw: Bitmap?,
        maxW: Int,
        maxH: Int
    ): Bitmap? {
        raw ?: return null
        if (raw.isRecycled) return null

        val scale = minOf(
            maxW.toFloat() / raw.width.coerceAtLeast(1),
            maxH.toFloat() / raw.height.coerceAtLeast(1),
            1f
        )
        val outW = (raw.width * scale).toInt().coerceAtLeast(32)
        val outH = (raw.height * scale).toInt().coerceAtLeast(32)

        if (raw.width <= maxW && raw.height <= maxH && raw.config == Bitmap.Config.RGB_565) {
            return raw
        }

        return try {
            val compact = Bitmap.createBitmap(outW, outH, Bitmap.Config.RGB_565)
            val canvas = Canvas(compact)
            val srcRect = android.graphics.Rect(0, 0, raw.width, raw.height)
            val dstRect = android.graphics.Rect(0, 0, outW, outH)
            canvas.drawBitmap(raw, srcRect, dstRect, null)
            raw.recycle()
            compact
        } catch (_: Throwable) {
            raw.recycle()
            null
        }
    }

    private fun buildCacheKey(uri: Uri, timeMs: Long, targetWidth: Int, targetHeight: Int): String {
        val safeW = targetWidth.coerceIn(64, 200)
        val safeH = targetHeight.coerceIn(48, 120)
        val bucketMs = ((timeMs.coerceAtLeast(0L)) / BUCKET_MS) * BUCKET_MS
        return "${uri}_${bucketMs}_${safeW}x${safeH}"
    }

    fun clear() {
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        memoryCache.evictAll()
        releasePooledRetriever()
    }
}
