package com.example.diagnostics

import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.compose.runtime.Immutable
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Immutable
data class GpuInfo(
    val glEsVersion: String = "Unknown",
    val renderer: String = "Probing GPU...",
    val vendor: String = "Unknown"
)

@Immutable
data class DeveloperTelemetrySnapshot(
    val capturedAtMs: Long = System.currentTimeMillis(),
    val totalRamBytes: Long = 0L,
    val availableRamBytes: Long = 0L,
    val appRamUsageBytes: Long = 0L,
    val jvmHeapUsedBytes: Long = 0L,
    val jvmHeapMaxBytes: Long = 0L,
    val lowMemory: Boolean = false,
    val appApkSizeBytes: Long = 0L,
    val appDataSizeBytes: Long = 0L,
    val appCacheSizeBytes: Long = 0L,
    val deviceFreeStorageBytes: Long = 0L,
    val deviceTotalStorageBytes: Long = 0L,
    val cpuUsagePercent: Float = 0f,
    val cpuCores: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
    val appProcessCpuMs: Long = 0L,
    val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val androidRelease: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    val primaryAbi: String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
    val gpuInfo: GpuInfo = GpuInfo()
) {
    val usedRamBytes: Long
        get() = (totalRamBytes - availableRamBytes).coerceAtLeast(0L)

    val ramUtilizationRatio: Float
        get() = if (totalRamBytes > 0L) (usedRamBytes.toFloat() / totalRamBytes.toFloat()).coerceIn(0f, 1f) else 0f

    val appTotalStorageBytes: Long
        get() = appApkSizeBytes + appDataSizeBytes + appCacheSizeBytes
}

object DeveloperStatsMonitor {

    // SHA-256 hex digest of developer access credential (verified by unit tests; never stored in plaintext).
    internal const val DEV_PASSWORD_SHA256 =
        "adfa03224256287ba9a75b7b34ce51fa7e1b355922a3a11ee78ee94ab4b61fc5"

    private const val MAX_HISTORY_SAMPLES = 60

    private val lock = Any()
    private val historyBuffer = ArrayDeque<DeveloperTelemetrySnapshot>()

    private var cachedGpuInfo: GpuInfo? = null
    private var lastProcTotalTicks: Long = -1L
    private var lastProcIdleTicks: Long = -1L
    private var lastProcessCpuTimeMs: Long = -1L
    private var lastProcessWallTimeMs: Long = -1L

    fun sha256Hex(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format(Locale.US, "%02x", b))
        }
        return sb.toString()
    }

    fun verifyPassword(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return false
        val actualBytes = sha256Hex(trimmed).toByteArray(Charsets.US_ASCII)
        val expectedBytes = DEV_PASSWORD_SHA256.toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(actualBytes, expectedBytes)
    }

    fun getHistorySnapshot(): List<DeveloperTelemetrySnapshot> = synchronized(lock) {
        historyBuffer.toList()
    }

    suspend fun sampleSnapshot(context: Context): DeveloperTelemetrySnapshot = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val now = System.currentTimeMillis()

        // 1. Memory
        val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(memInfo) }

        val pssBytes = runCatching {
            val myPid = Process.myPid()
            val procMem = am?.getProcessMemoryInfo(intArrayOf(myPid))?.firstOrNull()
            val totalPssKb = procMem?.totalPss?.takeIf { it > 0 }?.toLong() ?: Debug.getPss()
            totalPssKb * 1024L
        }.getOrDefault(Debug.getPss() * 1024L)

        val runtime = Runtime.getRuntime()
        val jvmUsed = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        val jvmMax = runtime.maxMemory().coerceAtLeast(1L)

        // 2. Storage
        val (apkBytes, dataBytes, cacheBytes) = queryAppStorageBytes(appCtx)
        val (freeStorage, totalStorage) = queryDeviceDataPartitionStorage()

        // 3. CPU
        val cores = runtime.availableProcessors().coerceAtLeast(1)
        val processCpuMs = runCatching { Process.getElapsedCpuTime() }.getOrDefault(0L)
        val cpuPercent = sampleCpuUsagePercent(cores, processCpuMs, now)

        // 4. GPU (cached once resolved cleanly via EGL14 offscreen context)
        val gpu = resolveGpuInfoOnce(appCtx, am)

        val snapshot = DeveloperTelemetrySnapshot(
            capturedAtMs = now,
            totalRamBytes = memInfo.totalMem.coerceAtLeast(0L),
            availableRamBytes = memInfo.availMem.coerceAtLeast(0L),
            appRamUsageBytes = pssBytes.coerceAtLeast(jvmUsed),
            jvmHeapUsedBytes = jvmUsed,
            jvmHeapMaxBytes = jvmMax,
            lowMemory = memInfo.lowMemory,
            appApkSizeBytes = apkBytes,
            appDataSizeBytes = dataBytes,
            appCacheSizeBytes = cacheBytes,
            deviceFreeStorageBytes = freeStorage,
            deviceTotalStorageBytes = totalStorage,
            cpuUsagePercent = cpuPercent,
            cpuCores = cores,
            appProcessCpuMs = processCpuMs,
            gpuInfo = gpu
        )

        synchronized(lock) {
            historyBuffer.addLast(snapshot)
            while (historyBuffer.size > MAX_HISTORY_SAMPLES) {
                historyBuffer.removeFirst()
            }
        }
        snapshot
    }

    suspend fun dumpStatsToTxt(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val appCtx = context.applicationContext
            val latest = sampleSnapshot(appCtx)
            val history = getHistorySnapshot()
            val crashLogs = CrashLogger.readRecentCrashLogs(maxChars = 24_000)

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(latest.capturedAtMs))
            val fileName = "clotch_stats_$timestamp.txt"
            val reportText = buildDumpReport(latest, history, crashLogs)
            CrashLogger.writeReportToClotchLog(appCtx, fileName, reportText)
        }
    }

    internal fun buildDumpReport(
        latest: DeveloperTelemetrySnapshot,
        history: List<DeveloperTelemetrySnapshot>,
        recentCrashLogs: String
    ): String {
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
        val shortTimeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

        return buildString {
            appendLine("============================================================")
            appendLine("CLOTCH DEVELOPER DIAGNOSTICS REPORT")
            appendLine("============================================================")
            appendLine("Generated: ${timeFmt.format(Date(latest.capturedAtMs))}")
            appendLine("App: ${BuildConfig.APPLICATION_ID} v${BuildConfig.VERSION_NAME} (versionCode=${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${latest.deviceModel}")
            appendLine("OS: ${latest.androidRelease} | ABI: ${latest.primaryAbi}")
            appendLine()
            appendLine("--- MEMORY ---")
            appendLine("Total RAM:     ${formatBytes(latest.totalRamBytes)}")
            appendLine("Available RAM: ${formatBytes(latest.availableRamBytes)}")
            appendLine("Used RAM:      ${formatBytes(latest.usedRamBytes)} (${String.format(Locale.US, "%.1f", latest.ramUtilizationRatio * 100f)}%)")
            appendLine("App RAM (PSS): ${formatBytes(latest.appRamUsageBytes)}")
            appendLine("JVM Heap:      ${formatBytes(latest.jvmHeapUsedBytes)} / ${formatBytes(latest.jvmHeapMaxBytes)}")
            appendLine("Low Memory:    ${latest.lowMemory}")
            appendLine()
            appendLine("--- STORAGE ---")
            appendLine("App APK Size:  ${formatBytes(latest.appApkSizeBytes)}")
            appendLine("App Data Size: ${formatBytes(latest.appDataSizeBytes)}")
            appendLine("App Cache:     ${formatBytes(latest.appCacheSizeBytes)}")
            appendLine("App Total:     ${formatBytes(latest.appTotalStorageBytes)}")
            appendLine("Device Free:   ${formatBytes(latest.deviceFreeStorageBytes)} / ${formatBytes(latest.deviceTotalStorageBytes)}")
            appendLine()
            appendLine("--- CPU & GPU ---")
            appendLine("CPU Usage:     ${String.format(Locale.US, "%.1f%%", latest.cpuUsagePercent)} (${latest.cpuCores} cores)")
            appendLine("Process CPU:   ${latest.appProcessCpuMs} ms")
            appendLine("OpenGL ES:     ${latest.gpuInfo.glEsVersion}")
            appendLine("GPU Renderer:  ${latest.gpuInfo.renderer}")
            appendLine("GPU Vendor:    ${latest.gpuInfo.vendor}")
            appendLine()
            appendLine("--- RESOURCE HISTORY (${history.size} SAMPLES) ---")
            history.forEach { sample ->
                appendLine(
                    "[${shortTimeFmt.format(Date(sample.capturedAtMs))}] " +
                        "CPU=${String.format(Locale.US, "%5.1f%%", sample.cpuUsagePercent)} | " +
                        "AppRAM=${formatBytes(sample.appRamUsageBytes)} | " +
                        "AvailRAM=${formatBytes(sample.availableRamBytes)} | " +
                        "Cache=${formatBytes(sample.appCacheSizeBytes)}"
                )
            }
            appendLine()
            appendLine("--- LATEST CRASH LOGS ---")
            appendLine(recentCrashLogs)
        }
    }

    private fun queryAppStorageBytes(context: Context): Triple<Long, Long, Long> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val statsResult = runCatching {
                val statsMgr = context.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
                val pkg = context.packageName
                val userHandle = Process.myUserHandle()
                if (statsMgr != null) {
                    val s = statsMgr.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, userHandle)
                    Triple(s.appBytes, s.dataBytes, s.cacheBytes)
                } else {
                    null
                }
            }.getOrNull()
            if (statsResult != null) return statsResult
        }

        // Fallback via File walks + ApplicationInfo.sourceDir (safe on Robolectric & pre-O)
        val apkSize = runCatching {
            val src = context.applicationInfo?.sourceDir
            if (!src.isNullOrBlank()) File(src).length().coerceAtLeast(0L) else 0L
        }.getOrDefault(0L)

        val internalCache = dirSizeBytes(context.cacheDir)
        val extCache = context.externalCacheDirs.orEmpty().sumOf { dirSizeBytes(it) }
        val totalCache = internalCache + extCache

        val internalData = dirSizeBytes(context.filesDir) +
            (context.dataDir?.let { dirSizeBytes(File(it, "databases")) + dirSizeBytes(File(it, "shared_prefs")) } ?: 0L)
        val extData = context.getExternalFilesDirs(null).orEmpty().sumOf { dirSizeBytes(it) }
        val totalData = internalData + extData

        return Triple(apkSize, totalData, totalCache)
    }

    private fun dirSizeBytes(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length().coerceAtLeast(0L)
        var sum = 0L
        dir.listFiles()?.forEach { child ->
            sum += if (child.isDirectory) dirSizeBytes(child) else child.length().coerceAtLeast(0L)
        }
        return sum
    }

    private fun queryDeviceDataPartitionStorage(): Pair<Long, Long> {
        return runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            stat.availableBytes to stat.totalBytes
        }.getOrDefault(0L to 0L)
    }

    private fun sampleCpuUsagePercent(cores: Int, processCpuMs: Long, wallNowMs: Long): Float {
        // Attempt /proc/stat first (works on < Android 8 and permissive kernels)
        val procStatPercent = runCatching {
            RandomAccessFile("/proc/stat", "r").use { raf ->
                val line = raf.readLine() ?: return@use null
                if (!line.startsWith("cpu ")) return@use null
                val parts = line.trim().split(Regex("\\s+")).drop(1)
                if (parts.size < 4) return@use null
                val values = parts.mapNotNull { it.toLongOrNull() }
                if (values.size < 4) return@use null
                val idle = values[3] + values.getOrElse(4) { 0L }
                val total = values.sum()
                synchronized(lock) {
                    val prevTotal = lastProcTotalTicks
                    val prevIdle = lastProcIdleTicks
                    lastProcTotalTicks = total
                    lastProcIdleTicks = idle
                    if (prevTotal > 0L && total > prevTotal) {
                        val dTotal = (total - prevTotal).toFloat()
                        val dIdle = (idle - prevIdle).coerceAtLeast(0L).toFloat()
                        ((dTotal - dIdle) / dTotal * 100f).coerceIn(0f, 100f)
                    } else {
                        null
                    }
                }
            }
        }.getOrNull()

        if (procStatPercent != null) return procStatPercent

        // Reliable SELinux-safe fallback: process CPU time delta normalized across cores
        synchronized(lock) {
            val prevCpu = lastProcessCpuTimeMs
            val prevWall = lastProcessWallTimeMs
            lastProcessCpuTimeMs = processCpuMs
            lastProcessWallTimeMs = wallNowMs
            if (prevCpu >= 0L && prevWall > 0L && wallNowMs > prevWall) {
                val dCpu = (processCpuMs - prevCpu).coerceAtLeast(0L).toFloat()
                val dWall = (wallNowMs - prevWall).coerceAtLeast(1L).toFloat()
                return (dCpu / (dWall * cores.toFloat()) * 100f).coerceIn(0f, 100f)
            }
        }
        return 0f
    }

    private fun resolveGpuInfoOnce(context: Context, activityManager: ActivityManager?): GpuInfo {
        cachedGpuInfo?.let { return it }

        val glEsVersion = runCatching {
            activityManager?.deviceConfigurationInfo?.glEsVersion?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: "OpenGL ES 3.0"

        val eglResult = runCatching {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return@runCatching null
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return@runCatching null
            }

            var surface = EGL14.EGL_NO_SURFACE
            var eglContext = EGL14.EGL_NO_CONTEXT
            try {
                val configAttr = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val numConfigs = IntArray(1)
                if (!EGL14.eglChooseConfig(display, configAttr, 0, configs, 0, 1, numConfigs, 0) ||
                    numConfigs[0] <= 0 || configs[0] == null
                ) {
                    return@runCatching null
                }

                val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0)
                if (eglContext == EGL14.EGL_NO_CONTEXT) return@runCatching null

                val surfAttr = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
                surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfAttr, 0)
                if (surface == EGL14.EGL_NO_SURFACE) return@runCatching null

                if (!EGL14.eglMakeCurrent(display, surface, surface, eglContext)) {
                    return@runCatching null
                }

                val renderer = GLES20.glGetString(GLES20.GL_RENDERER)?.takeIf { it.isNotBlank() } ?: "GPU Renderer"
                val vendor = GLES20.glGetString(GLES20.GL_VENDOR)?.takeIf { it.isNotBlank() } ?: "Unknown"
                val versionStr = GLES20.glGetString(GLES20.GL_VERSION)?.takeIf { it.isNotBlank() } ?: glEsVersion
                GpuInfo(
                    glEsVersion = versionStr,
                    renderer = renderer,
                    vendor = vendor
                )
            } finally {
                runCatching {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                    )
                }
                if (surface != EGL14.EGL_NO_SURFACE) {
                    runCatching { EGL14.eglDestroySurface(display, surface) }
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    runCatching { EGL14.eglDestroyContext(display, eglContext) }
                }
                runCatching { EGL14.eglTerminate(display) }
            }
        }.getOrNull()

        val finalInfo = eglResult ?: GpuInfo(
            glEsVersion = "OpenGL ES $glEsVersion",
            renderer = "${Build.HARDWARE} Hardware GPU",
            vendor = Build.MANUFACTURER
        )
        cachedGpuInfo = finalInfo
        return finalInfo
    }

    fun formatBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return when {
            safe >= 1_073_741_824L -> String.format(Locale.US, "%.2f GB", safe / 1_073_741_824.0)
            safe >= 1_048_576L -> String.format(Locale.US, "%.1f MB", safe / 1_048_576.0)
            safe >= 1024L -> String.format(Locale.US, "%.1f KB", safe / 1024.0)
            else -> "$safe B"
        }
    }
}
