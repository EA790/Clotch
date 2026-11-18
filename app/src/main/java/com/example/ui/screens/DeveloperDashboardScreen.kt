package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VideogameAsset
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.diagnostics.CrashLogger
import com.example.diagnostics.DeveloperStatsMonitor
import com.example.diagnostics.DeveloperTelemetrySnapshot
import com.example.ui.components.ClotchCardShape
import com.example.ui.components.ClotchInnerCardShape
import com.example.ui.components.TactileIconButton
import com.example.ui.components.TactilePillButton
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.ClotchBlue
import com.example.ui.theme.CrimsonAccent
import com.example.ui.theme.DiscordBlurple
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.SignalEmeraldMuted
import com.example.ui.theme.SlateCard
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DeveloperDashboardScreen(
    onBack: () -> Unit,
    onShowToast: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var snapshot by remember { mutableStateOf(DeveloperTelemetrySnapshot()) }
    var history by remember { mutableStateOf<List<DeveloperTelemetrySnapshot>>(emptyList()) }
    var crashLogPreview by remember { mutableStateOf("Loading crash logs...") }
    var isDumping by remember { mutableStateOf(false) }
    var lastDumpPath by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        crashLogPreview = withContext(Dispatchers.IO) {
            CrashLogger.readRecentCrashLogs(maxChars = 4_000)
        }
        while (isActive) {
            snapshot = DeveloperStatsMonitor.sampleSnapshot(context)
            history = DeveloperStatsMonitor.getHistorySnapshot()
            delay(1000L)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 580.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header + Back
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TactileIconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("dev_dashboard_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Settings",
                        tint = TextPrimary
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Developer Dashboard",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = "Live 1s telemetry • Off-main-thread diagnostics",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            // Dump Stats Pill CTA
            TactilePillButton(
                onClick = {
                    if (isDumping) return@TactilePillButton
                    isDumping = true
                    scope.launch {
                        val res = DeveloperStatsMonitor.dumpStatsToTxt(context)
                        isDumping = false
                        res.onSuccess { path ->
                            lastDumpPath = path
                            onShowToast("Saved stats report to $path")
                        }.onFailure { err ->
                            onShowToast("Failed dumping stats: ${err.localizedMessage ?: "Error"}")
                        }
                    }
                },
                enabled = !isDumping,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("dump_stats_button"),
                containerColor = ClotchBlue,
                contentColor = TextPrimary
            ) {
                if (isDumping) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = TextPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Dump Stats to TXT", style = MaterialTheme.typography.labelLarge)
            }

            if (lastDumpPath != null) {
                Surface(
                    color = SignalEmeraldMuted,
                    shape = ClotchInnerCardShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Last export: $lastDumpPath",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 11.sp,
                        color = SignalEmerald,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Memory Card
            DevStatCard(
                title = "Memory",
                icon = Icons.Outlined.Memory,
                accentColor = ClotchBlue
            ) {
                LinearProgressIndicator(
                    progress = { snapshot.ramUtilizationRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(50)),
                    color = if (snapshot.lowMemory) CrimsonAccent else ClotchBlue,
                    trackColor = CarbonSurface
                )
                DevMetricRow("Total RAM", DeveloperStatsMonitor.formatBytes(snapshot.totalRamBytes))
                DevMetricRow("Available RAM", DeveloperStatsMonitor.formatBytes(snapshot.availableRamBytes))
                DevMetricRow("App RAM (PSS)", DeveloperStatsMonitor.formatBytes(snapshot.appRamUsageBytes))
                DevMetricRow(
                    "JVM Heap",
                    "${DeveloperStatsMonitor.formatBytes(snapshot.jvmHeapUsedBytes)} / ${DeveloperStatsMonitor.formatBytes(snapshot.jvmHeapMaxBytes)}"
                )
            }

            // Storage Card
            DevStatCard(
                title = "Storage",
                icon = Icons.Outlined.Storage,
                accentColor = SignalEmerald
            ) {
                DevMetricRow("APK Size", DeveloperStatsMonitor.formatBytes(snapshot.appApkSizeBytes))
                DevMetricRow("App Data", DeveloperStatsMonitor.formatBytes(snapshot.appDataSizeBytes))
                DevMetricRow("App Cache", DeveloperStatsMonitor.formatBytes(snapshot.appCacheSizeBytes))
                DevMetricRow("Total App Footprint", DeveloperStatsMonitor.formatBytes(snapshot.appTotalStorageBytes))
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    color = CarbonSurface,
                    shape = ClotchInnerCardShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        DevMetricRow(
                            "Device Free / Total",
                            "${DeveloperStatsMonitor.formatBytes(snapshot.deviceFreeStorageBytes)} / ${DeveloperStatsMonitor.formatBytes(snapshot.deviceTotalStorageBytes)}"
                        )
                    }
                }
            }

            // CPU Card
            DevStatCard(
                title = "CPU & Device",
                icon = Icons.Outlined.Speed,
                accentColor = WarningAmber
            ) {
                DevMetricRow(
                    "CPU Usage",
                    String.format(Locale.US, "%.1f%% (%d cores)", snapshot.cpuUsagePercent, snapshot.cpuCores)
                )
                DevMetricRow("App Process CPU", "${snapshot.appProcessCpuMs} ms")
                DevMetricRow("Device Model", snapshot.deviceModel)
                DevMetricRow("OS / ABI", "${snapshot.androidRelease} • ${snapshot.primaryAbi}")
            }

            // GPU Card
            DevStatCard(
                title = "GPU & Graphics",
                icon = Icons.Outlined.VideogameAsset,
                accentColor = DiscordBlurple
            ) {
                DevMetricRow("OpenGL ES", snapshot.gpuInfo.glEsVersion)
                DevMetricRow("Renderer", snapshot.gpuInfo.renderer)
                DevMetricRow("Vendor", snapshot.gpuInfo.vendor)
            }

            // Sample History Card
            DevStatCard(
                title = "Telemetry History (${history.size} ticks)",
                icon = Icons.Outlined.Speed,
                accentColor = ClotchBlue
            ) {
                val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
                if (history.isEmpty()) {
                    Text(
                        text = "Sampling telemetry...",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                } else {
                    history.asReversed().take(8).forEach { sample ->
                        Text(
                            text = "${timeFmt.format(Date(sample.capturedAtMs))} • " +
                                "CPU ${String.format(Locale.US, "%.1f%%", sample.cpuUsagePercent)} • " +
                                "App ${DeveloperStatsMonitor.formatBytes(sample.appRamUsageBytes)} • " +
                                "Free ${DeveloperStatsMonitor.formatBytes(sample.availableRamBytes)}",
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 11.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Latest Crash Logs Card
            DevStatCard(
                title = "Latest Crash Logs",
                icon = Icons.Outlined.BugReport,
                accentColor = CrimsonAccent
            ) {
                Surface(
                    color = CarbonSurface,
                    shape = ClotchInnerCardShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = crashLogPreview,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun DevStatCard(
    title: String,
    icon: ImageVector,
    accentColor: Color,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SlateCard,
        shape = ClotchCardShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            }
            content()
        }
    }
}

@Composable
private fun DevMetricRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Text(
            text = value,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 12.sp,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}
