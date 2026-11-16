package com.example.editor.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EditorThumbnailCache {

    private val memoryCache = object : LruCache<String, Bitmap>(80) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    suspend fun getFrame(
        context: Context,
        uri: Uri,
        timeMs: Long,
        targetWidth: Int = 160,
        targetHeight: Int = 90
    ): Bitmap? {
        val bucketMs = (timeMs / 500L) * 500L
        val key = "${uri}_${bucketMs}"
        memoryCache.get(key)?.let { return it }

        val extracted = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val timeUs = (bucketMs * 1000L).coerceAtLeast(0L)
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
            } catch (_: Throwable) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }

        if (extracted != null) {
            memoryCache.put(key, extracted)
        }
        return extracted
    }

    fun clear() {
        memoryCache.evictAll()
    }
}
