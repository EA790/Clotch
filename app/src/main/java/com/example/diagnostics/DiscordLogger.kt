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
import java.util.concurrent.Executors

/**
 * Dedicated diagnostic logger for Discord login (WebView / OAuth2) and KizzyRPC lifecycle events.
 * Writes asynchronously off the Main thread to Documents/Clotch-Log/discord_log.txt.
 */
object DiscordLogger {

    private const val TAG = "ClotchDiscordLogger"
    private const val FOLDER_NAME = "Clotch-Log"
    private const val FILE_NAME = "discord_log.txt"

    @Volatile
    private var appContext: Context? = null

    private val writeLock = Any()
    private val logExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ClotchDiscordLogWriter").apply { isDaemon = true }
    }

    fun getLogDisplayPath(): String = "Documents/$FOLDER_NAME/$FILE_NAME"

    fun install(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    /**
     * Masks a Discord token for safe logging, revealing only the first 5 characters.
     */
    fun maskToken(rawToken: String?): String {
        val clean = rawToken.orEmpty()
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trim()
        if (clean.isEmpty()) return "<empty>"
        if (clean.length <= 5) return "${clean.take(2)}*** (len=${clean.length})"
        return "${clean.take(5)}*** (len=${clean.length})"
    }

    /**
     * Masks any access_token or code parameter embedded in a callback URI string before logging.
     */
    fun maskUriSecrets(rawUri: String?): String {
        if (rawUri.isNullOrBlank()) return "<null>"
        return rawUri.replace(
            Regex("(access_token=)([^&#]+)", RegexOption.IGNORE_CASE)
        ) { match ->
            "${match.groupValues[1]}${maskToken(match.groupValues[2])}"
        }
    }

    fun logOAuthUrlGenerated(url: String) {
        logEvent("OAUTH_URL_GENERATED", "Exact URL: $url")
    }

    fun logOAuthBrowserOpened(url: String) {
        logEvent("OAUTH_BROWSER_OPENED", "Launching browser Intent for URL: $url")
    }

    fun logDeepLinkReceived(rawUri: String?, summary: String) {
        logEvent(
            "DEEP_LINK_RECEIVED",
            "URI: ${maskUriSecrets(rawUri)}\nSummary: $summary"
        )
    }

    fun logTokenExtracted(source: String, token: String) {
        logEvent(
            "TOKEN_EXTRACTED",
            "Source: $source | Token: ${maskToken(token)}"
        )
    }

    fun logWebViewEvent(event: String, url: String? = null, detail: String? = null) {
        val parts = buildList {
            add("Event: $event")
            if (!url.isNullOrBlank()) add("URL: ${maskUriSecrets(url)}")
            if (!detail.isNullOrBlank()) add("Detail: $detail")
        }.joinToString(" | ")
        logEvent("DISCORD_WEBVIEW", parts)
    }

    fun logRpcAttempt(token: String, reason: String) {
        logEvent(
            "KIZZY_RPC_CONNECT_ATTEMPT",
            "Reason: $reason | Token: ${maskToken(token)}"
        )
    }

    fun logRpcState(state: String, detail: String = "", throwable: Throwable? = null) {
        val body = buildString {
            append("State: ").append(state)
            if (detail.isNotBlank()) {
                append("\nDetail: ").append(detail)
            }
            if (throwable != null) {
                append("\nException: ").append(throwable::class.java.name)
                    .append(": ").append(throwable.message)
                append("\nStackTrace:\n").append(stackTraceToString(throwable))
            }
        }
        logEvent("KIZZY_RPC_STATE", body, isError = throwable != null || state.equals("Error", ignoreCase = true))
    }

    fun logEvent(category: String, message: String, isError: Boolean = false) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        val entry = "=== DISCORD [$category] ===\n" +
            "Timestamp: $timestamp\n" +
            "App: ${BuildConfig.APPLICATION_ID} v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "$message\n" +
            "------------------------------------------------------------\n\n"
        if (isError) {
            Log.e(TAG, entry)
        } else {
            Log.d(TAG, entry)
        }
        enqueueWrite(entry)
    }

    fun readRecentDiscordLogs(context: Context? = appContext, maxChars: Int = 24_000): String {
        val ctx = context?.applicationContext ?: appContext ?: return "DiscordLogger not initialized."
        runCatching { logExecutor.submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS) }
        synchronized(writeLock) {
            val content = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    readFromPublicDocumentsMediaStore(ctx, FILE_NAME)
                } else {
                    readFromLegacyPublicDocuments(FILE_NAME)
                }
            }.getOrNull() ?: runCatching {
                val fallbackFile = File(
                    File(
                        ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: ctx.filesDir,
                        FOLDER_NAME
                    ),
                    FILE_NAME
                )
                if (fallbackFile.exists()) fallbackFile.readText(Charsets.UTF_8) else null
            }.getOrNull()

            if (content.isNullOrBlank()) {
                return "No Discord logs recorded in ${getLogDisplayPath()}."
            }
            return if (content.length > maxChars) {
                "... (truncated)\n" + content.takeLast(maxChars)
            } else {
                content
            }
        }
    }

    private fun enqueueWrite(entry: String) {
        val ctx = appContext ?: return
        runCatching {
            logExecutor.execute {
                writeLogEntrySync(ctx, entry)
            }
        }
    }

    private fun writeLogEntrySync(context: Context, entry: String) {
        synchronized(writeLock) {
            val wrotePublic = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    writeToPublicDocumentsMediaStore(context, FILE_NAME, entry)
                } else {
                    writeToLegacyPublicDocuments(FILE_NAME, entry)
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
        content: String
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
        return try {
            resolver.openOutputStream(uri, "wa")?.use { stream ->
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
        content: String
    ): Boolean {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val logDir = File(docsDir, FOLDER_NAME)
        if (!logDir.exists() && !logDir.mkdirs()) {
            return false
        }
        val logFile = File(logDir, fileName)
        logFile.appendText(content, Charsets.UTF_8)
        return true
    }

    private fun stackTraceToString(throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}
