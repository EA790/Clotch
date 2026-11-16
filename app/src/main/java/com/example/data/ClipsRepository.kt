package com.example.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ClipsRepository(private val context: Context) {

    suspend fun loadRecordedClips(): List<RecordedClip> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            loadFromMediaStore()
        } else {
            loadFromAppMoviesDir()
        }
    }

    private fun loadFromMediaStore(): List<RecordedClip> {
        val clips = mutableListOf<RecordedClip>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.IS_PENDING} = 0"
        val selectionArgs = arrayOf("%Movies/Clotch%")
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        runCatching {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val wCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val hCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    val name = cursor.getString(nameCol) ?: "clip_clotch.mp4"
                    val mime = cursor.getString(mimeCol) ?: inferMimeType(name)
                    clips.add(
                        RecordedClip(
                            id = id,
                            uri = contentUri,
                            displayName = name,
                            durationMs = cursor.getLong(durCol),
                            sizeBytes = cursor.getLong(sizeCol),
                            width = cursor.getInt(wCol),
                            height = cursor.getInt(hCol),
                            dateAddedSec = cursor.getLong(dateCol),
                            mimeType = mime,
                            isFileUri = false
                        )
                    )
                }
            }
        }
        return clips
    }

    private fun loadFromAppMoviesDir(): List<RecordedClip> {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Clotch")
        if (!dir.exists()) return emptyList()
        val supportedExt = setOf("mp4", "mkv", "3gp")
        val files = dir.listFiles { file ->
            file.isFile && file.extension.lowercase() in supportedExt && file.length() > 0L
        }?.sortedByDescending { it.lastModified() } ?: return emptyList()

        return files.mapIndexed { index, file ->
            var durationMs = 0L
            var width = 0
            var height = 0
            runCatching {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                retriever.release()
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            RecordedClip(
                id = file.lastModified() + index,
                uri = uri,
                displayName = file.name,
                durationMs = durationMs,
                sizeBytes = file.length(),
                width = width,
                height = height,
                dateAddedSec = file.lastModified() / 1000L,
                mimeType = inferMimeType(file.name),
                isFileUri = true
            )
        }
    }

    private fun inferMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
            fileName.endsWith(".3gp", ignoreCase = true) -> "video/3gpp"
            else -> "video/mp4"
        }
    }

    suspend fun deleteClip(clip: RecordedClip): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (!clip.isFileUri && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.delete(clip.uri, null, null) > 0
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Clotch")
                val target = File(dir, clip.displayName)
                target.exists() && target.delete()
            }
        }.getOrDefault(false)
    }

    fun getShareUri(clip: RecordedClip): Uri = clip.uri
}
