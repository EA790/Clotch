package com.example.diagnostics

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {

    private const val TAG = "ClotchCrashLogger"
    private const val FOLDER_NAME = "Clotch-Log"
    private const val FILE_NAME = "clotch_crash.txt"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var installed = false

    private val writeLock = Any()

    fun getLogDisplayPath(): String = "Documents/$FOLDER_NAME/$FILE_NAME"

    fun install(context: Context) {
        appContext = context.applicationContext
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    logUncaughtException(thread, throwable)
                }
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    fun logUncaughtException(thread: Thread, throwable: Throwable) {
        val stackTrace = stackTraceToString(throwable)
        val entry = buildHeader("UNCAUGHT EXCEPTION") +
            "Thread: ${thread.name} (id=${thread.id})\n" +
            "Exception: ${throwable::class.java.name}: ${throwable.message}\n" +
            "StackTrace:\n$stackTrace\n" +
            "------------------------------------------------------------\n\n"
        Log.e(TAG, entry)
        writeLogEntry(entry)
    }

    fun logError(source: String, message: String, throwable: Throwable? = null) {
        val stack = throwable?.let { "\nStackTrace:\n${stackTraceToString(it)}" } ?: ""
        val entry = buildHeader("ERROR [$source]") +
            "Message: $message$stack\n" +
            "------------------------------------------------------------\n\n"
        Log.e(TAG, entry)
        writeLogEntry(entry)
    }

    fun logFfmpegFailure(
        operation: String,
        command: String,
        returnCode: String?,
        logs: String
    ) {
        val trimmedLogs = if (logs.length > 16_000) {
            "...\n" + logs.takeLast(16_000)
        } else {
            logs
        }
        val entry = buildHeader("FFMPEG FAILURE [$operation]") +
            "ReturnCode: ${returnCode ?: "unknown"}\n" +
            "Command:\n$command\n" +
            "FFmpeg Output:\n$trimmedLogs\n" +
            "------------------------------------------------------------\n\n"
        Log.e(TAG, entry)
        writeLogEntry(entry)
    }

    private fun buildHeader(kind: String): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        return "=== CLOTCH $kind ===\n" +
            "Timestamp: $timestamp\n" +
            "App: ${BuildConfig.APPLICATION_ID} v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "Device: ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n"
    }

    private fun stackTraceToString(throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun writeLogEntry(entry: String) {
        val context = appContext ?: return
        synchronized(writeLock) {
            val wrotePublic = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    writeToPublicDocumentsMediaStore(context, entry)
                } else {
                    writeToLegacyPublicDocuments(entry)
                }
            }.getOrDefault(false)

            if (!wrotePublic) {
                runCatching {
                    val fallbackDir = File(
                        context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir,
                        FOLDER_NAME
                    )
                    if (!fallbackDir.exists()) fallbackDir.mkdirs()
                    File(fallbackDir, FILE_NAME).appendText(entry, Charsets.UTF_8)
                }
            }
        }
    }

    private fun writeToPublicDocumentsMediaStore(context: Context, entry: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/$FOLDER_NAME"

        var targetUri: Uri? = null
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ? AND " +
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf(FILE_NAME, "%$FOLDER_NAME%")

        resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                targetUri = Uri.withAppendedPath(collection, id.toString())
            }
        }

        if (targetUri == null) {
            val values = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath)
            }
            targetUri = resolver.insert(collection, values)
        }

        val uri = targetUri ?: return false
        resolver.openOutputStream(uri, "wa")?.use { stream ->
            stream.write(entry.toByteArray(Charsets.UTF_8))
            stream.flush()
            return true
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun writeToLegacyPublicDocuments(entry: String): Boolean {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val logDir = File(docsDir, FOLDER_NAME)
        if (!logDir.exists() && !logDir.mkdirs()) {
            return false
        }
        val logFile = File(logDir, FILE_NAME)
        logFile.appendText(entry, Charsets.UTF_8)
        return true
    }
}
