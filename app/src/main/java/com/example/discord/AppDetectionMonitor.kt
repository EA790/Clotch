package com.example.discord

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Process

data class SupportedAppEntry(
    val packageName: String,
    val displayName: String,
    val catImageUrl: String
)

data class DetectedAppsResult(
    val activeGame: SupportedAppEntry? = null,
    val isLarkPlayerRunning: Boolean = false,
    val larkCatImageUrl: String = LARK_CAT_IMAGE_URL,
    val scannedAtMs: Long = 0L
) {
    val hasAnyDetectedApp: Boolean get() = activeGame != null || isLarkPlayerRunning

    companion object {
        const val LARK_CAT_IMAGE_URL = "https://example.com/cat_larkplayer.png"
    }
}

object AppDetectionMonitor {

    val SUPPORTED_GAMES: List<SupportedAppEntry> = listOf(
        SupportedAppEntry(
            packageName = "com.tencent.ig",
            displayName = "PUBG Mobile",
            catImageUrl = "https://example.com/cat_pubg.png"
        ),
        SupportedAppEntry(
            packageName = "com.pubg.imobile",
            displayName = "BGMI",
            catImageUrl = "https://example.com/cat_pubg.png"
        ),
        SupportedAppEntry(
            packageName = "com.activision.callofduty.shooter",
            displayName = "Call of Duty: Mobile",
            catImageUrl = "https://example.com/cat_codm.png"
        ),
        SupportedAppEntry(
            packageName = "com.dts.freefireth",
            displayName = "Free Fire",
            catImageUrl = "https://example.com/cat_freefire.png"
        ),
        SupportedAppEntry(
            packageName = "com.miHoYo.GenshinImpact",
            displayName = "Genshin Impact",
            catImageUrl = "https://example.com/cat_genshin.png"
        ),
        SupportedAppEntry(
            packageName = "com.supercell.brawlstars",
            displayName = "Brawl Stars",
            catImageUrl = "https://example.com/cat_brawlstars.png"
        ),
        SupportedAppEntry(
            packageName = "com.riotgames.league.wildrift",
            displayName = "Wild Rift",
            catImageUrl = "https://example.com/cat_wildrift.png"
        ),
        SupportedAppEntry(
            packageName = "com.mojang.minecraftpe",
            displayName = "Minecraft",
            catImageUrl = "https://example.com/cat_minecraft.png"
        ),
        SupportedAppEntry(
            packageName = "com.roblox.client",
            displayName = "Roblox",
            catImageUrl = "https://example.com/cat_roblox.png"
        ),
        SupportedAppEntry(
            packageName = "com.mobile.legends",
            displayName = "Mobile Legends",
            catImageUrl = "https://example.com/cat_mlbb.png"
        )
    )

    private val SUPPORTED_BY_PACKAGE = SUPPORTED_GAMES.associateBy { it.packageName }

    private val LARK_PLAYER_PACKAGES = setOf(
        "com.dywx.larkplayer",
        "com.larkplayer"
    )

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun scanRecentApps(context: Context): DetectedAppsResult {
        if (!hasUsageStatsPermission(context)) {
            return DetectedAppsResult(scannedAtMs = System.currentTimeMillis())
        }

        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return DetectedAppsResult(scannedAtMs = System.currentTimeMillis())

        val now = System.currentTimeMillis()
        val recencyMap = mutableMapOf<String, Long>()

        // 1. Collect UsageStats over the last 30 minutes
        val statsList = runCatching {
            usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 30L * 60L * 1000L,
                now
            )
        }.getOrNull().orEmpty()

        for (stat in statsList) {
            if (stat.lastTimeUsed > 0L) {
                val existing = recencyMap[stat.packageName] ?: 0L
                if (stat.lastTimeUsed > existing) {
                    recencyMap[stat.packageName] = stat.lastTimeUsed
                }
            }
        }

        // 2. Refine with UsageEvents (ACTIVITY_RESUMED / MOVE_TO_FOREGROUND) in last 10 minutes
        val events = runCatching {
            usageStatsManager.queryEvents(now - 10L * 60L * 1000L, now)
        }.getOrNull()

        if (events != null) {
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val isForegroundEvent =
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                        @Suppress("DEPRECATION")
                        (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND)
                if (isForegroundEvent && event.packageName != null) {
                    val existing = recencyMap[event.packageName] ?: 0L
                    if (event.timeStamp > existing) {
                        recencyMap[event.packageName] = event.timeStamp
                    }
                }
            }
        }

        // 3. Pick the first detected supported game from recents sorted most-recent first
        val sortedRecentPackages = recencyMap.entries
            .sortedByDescending { it.value }
            .map { it.key }

        val firstDetectedGame = sortedRecentPackages
            .firstNotNullOfOrNull { pkg -> SUPPORTED_BY_PACKAGE[pkg] }

        // 4. Detect if Lark Player is running in the background
        val larkLastUsed = LARK_PLAYER_PACKAGES.maxOfOrNull { recencyMap[it] ?: 0L } ?: 0L
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val isMusicPlaying = audioManager?.isMusicActive == true
        val isLarkRecent = larkLastUsed > 0L && (now - larkLastUsed) <= 15L * 60L * 1000L
        val isLarkRunning = (larkLastUsed > 0L && isMusicPlaying) || isLarkRecent

        return DetectedAppsResult(
            activeGame = firstDetectedGame,
            isLarkPlayerRunning = isLarkRunning,
            scannedAtMs = now
        )
    }
}
