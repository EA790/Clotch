package com.example.editor.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class EditorThumbnailCache {

    companion object {
        // 12 MB byte-bounded bitmap pool for timeline filmstrips (prevents OOM on 29+ min 4K clips)
        private const val MAX_CACHE_KB = 12 * 1024
        private const val MAX_CONCURRENT_RETRIEVERS = 2
    }

    private val memoryCache = object : LruCache<String, Bitmap>(MAX_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
    }

    private val retrieverSemaphore = Semaphore(MAX_CONCURRENT_RETRIEVERS)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Bitmap?>>()

    suspend fun getFrame(
        context: Context,
        uri: Uri,
        timeMs: Long,
        targetWidth: Int = 160,
        targetHeight: Int = 90
    ): Bitmap? {
        val safeW = targetWidth.coerceIn(64, 240)
        val safeH = targetHeight.coerceIn(48, 180)
        val bucketMs = ((timeMs.coerceAtLeast(0L)) / 500L) * 500L
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
            val extracted = withContext(Dispatchers.IO) {
                retrieverSemaphore.withPermit {
                    currentCoroutineContext().ensureActive()
                    memoryCache.get(key)?.takeIf { !it.isRecycled }?.let { return@withPermit it }

                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(appContext, uri)
                        currentCoroutineContext().ensureActive()
                        val timeUs = (bucketMs * 1000L).coerceAtLeast(0L)
                        val rawBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
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
                        downsampleIfNeeded(rawBitmap, safeW, safeH)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        null
                    } finally {
                        runCatching { retriever.release() }
                    }
                }
            }

            if (extracted != null && !extracted.isRecycled) {
                memoryCache.put(key, extracted)
            }
            deferred.complete(extracted)
            extracted
        } catch (ce: CancellationException) {
            deferred.cancel(ce)
            throw ce
        } catch (_: Throwable) {
            deferred.complete(null)
            null
        } finally {
            inFlight.remove(key, deferred)
        }
    }

    private fun downsampleIfNeeded(
        raw: Bitmap?,
        maxW: Int,
        maxH: Int
    ): Bitmap? {
        raw ?: return null
        if (raw.isRecycled) return null
        if (raw.width <= maxW * 2 && raw.height <= maxH * 2) {
            return raw
        }
        val scale = minOf(maxW.toFloat() / raw.width.coerceAtLeast(1), maxH.toFloat() / raw.height.coerceAtLeast(1))
        val outW = (raw.width * scale).toInt().coerceAtLeast(32)
        val outH = (raw.height * scale).toInt().coerceAtLeast(32)
        return try {
            val scaled = Bitmap.createScaledBitmap(raw, outW, outH, true)
            if (scaled !== raw) {
                raw.recycle()
            }
            scaled
        } catch (_: Throwable) {
            raw.recycle()
            null
        }
    }

    fun clear() {
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        memoryCache.evictAll()
    }
}
