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

    fun readRecentCrashLogs(maxChars: Int = 24_000): String {
        val context = appContext ?: return "CrashLogger not initialized."
        synchronized(writeLock) {
            val content = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    readFromPublicDocumentsMediaStore(context, FILE_NAME)
                } else {
                    readFromLegacyPublicDocuments(FILE_NAME)
                }
            }.getOrNull() ?: runCatching {
                val fallbackFile = File(
                    File(
                        context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir,
                        FOLDER_NAME
                    ),
                    FILE_NAME
                )
                if (fallbackFile.exists()) fallbackFile.readText(Charsets.UTF_8) else null
            }.getOrNull()

            if (content.isNullOrBlank()) {
                return "No crash logs recorded in ${getLogDisplayPath()}."
            }
            return if (content.length > maxChars) {
                "... (truncated)\n" + content.takeLast(maxChars)
            } else {
                content
            }
        }
    }

    fun writeReportToClotchLog(context: Context, fileName: String, content: String): String {
        val appCtx = context.applicationContext
        synchronized(writeLock) {
            val wrotePublic = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    writeToPublicDocumentsMediaStore(appCtx, fileName, content, append = false)
                } else {
                    writeToLegacyPublicDocuments(fileName, content, append = false)
                }
            }.getOrDefault(false)

            if (wrotePublic) {
                return "Documents/$FOLDER_NAME/$fileName"
            }

            val fallbackDir = File(
                appCtx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: appCtx.filesDir,
                FOLDER_NAME
            )
            if (!fallbackDir.exists()) fallbackDir.mkdirs()
            val outFile = File(fallbackDir, fileName)
            outFile.writeText(content, Charsets.UTF_8)
            return outFile.absolutePath
        }
    }

    private fun writeLogEntry(entry: String) {
        val context = appContext ?: return
        synchronized(writeLock) {
            val wrotePublic = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    writeToPublicDocumentsMediaStore(context, FILE_NAME, entry, append = true)
                } else {
                    writeToLegacyPublicDocuments(FILE_NAME, entry, append = true)
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

    private fun readFromPublicDocumentsMediaStore(context: Context, fileName: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ? AND " +
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf(fileName, "%$FOLDER_NAME%")

        resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                val uri = Uri.withAppendedPath(collection, id.toString())
                return resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun readFromLegacyPublicDocuments(fileName: String): String? {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val logFile = File(File(docsDir, FOLDER_NAME), fileName)
        return if (logFile.exists()) logFile.readText(Charsets.UTF_8) else null
    }

    private fun writeToPublicDocumentsMediaStore(
        context: Context,
        fileName: String,
        content: String,
        append: Boolean
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/$FOLDER_NAME"

        var targetUri: Uri? = null
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ? AND " +
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf(fileName, "%$FOLDER_NAME%")

        resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                targetUri = Uri.withAppendedPath(collection, id.toString())
            }
        }

        var createdNewRow = false
        if (targetUri == null) {
            val values = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
                put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath)
            }
            targetUri = resolver.insert(collection, values)
            createdNewRow = targetUri != null
        }

        val uri = targetUri ?: return false
        val mode = if (append) "wa" else "wt"
        return try {
            resolver.openOutputStream(uri, mode)?.use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.flush()
                true
            } ?: run {
                if (createdNewRow) runCatching { resolver.delete(uri, null, null) }
                false
            }
        } catch (t: Throwable) {
            if (createdNewRow) runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun writeToLegacyPublicDocuments(
        fileName: String,
        content: String,
        append: Boolean
    ): Boolean {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val logDir = File(docsDir, FOLDER_NAME)
        if (!logDir.exists() && !logDir.mkdirs()) {
            return false
        }
        val logFile = File(logDir, fileName)
        if (append) {
            logFile.appendText(content, Charsets.UTF_8)
        } else {
            logFile.writeText(content, Charsets.UTF_8)
        }
        return true
    }
}
