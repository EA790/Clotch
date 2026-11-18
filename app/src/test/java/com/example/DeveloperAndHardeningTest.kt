package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.diagnostics.CrashLogger
import com.example.diagnostics.DeveloperStatsMonitor
import com.example.discord.DiscordConnectionState
import com.example.discord.DiscordProfileManager
import com.example.discord.DiscordRpcClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeveloperAndHardeningTest {

    @Test
    fun devPasswordVerification_acceptsExpectedSha256AndRejectsInvalid() {
        val computed = DeveloperStatsMonitor.sha256Hex("clotch_dev_2024")
        assertEquals(DeveloperStatsMonitor.DEV_PASSWORD_SHA256, computed)
        assertTrue(DeveloperStatsMonitor.verifyPassword("clotch_dev_2024"))
        assertTrue(DeveloperStatsMonitor.verifyPassword("  clotch_dev_2024  "))
        assertFalse(DeveloperStatsMonitor.verifyPassword(""))
        assertFalse(DeveloperStatsMonitor.verifyPassword("wrong_password"))
    }

    @Test
    fun developerStatsMonitor_samplesTelemetryAndDumpsTxt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        CrashLogger.install(context)

        val snapshot = DeveloperStatsMonitor.sampleSnapshot(context)
        assertTrue(snapshot.cpuCores >= 1)
        assertTrue(snapshot.jvmHeapMaxBytes > 0L)
        assertTrue(snapshot.deviceModel.isNotBlank())
        assertTrue(snapshot.gpuInfo.glEsVersion.isNotBlank())

        val dumpResult = DeveloperStatsMonitor.dumpStatsToTxt(context)
        assertTrue(dumpResult.isSuccess)
        val reportPath = dumpResult.getOrThrow()
        assertTrue(reportPath.contains("clotch_stats_"))
        assertTrue(reportPath.endsWith(".txt"))

        val fileIfDirect = File(reportPath)
        if (fileIfDirect.exists()) {
            val content = fileIfDirect.readText()
            assertTrue(content.contains("CLOTCH DEVELOPER DIAGNOSTICS REPORT"))
            assertTrue(content.contains("--- MEMORY ---"))
            assertTrue(content.contains("--- STORAGE ---"))
            assertTrue(content.contains("--- CPU & GPU ---"))
            assertTrue(content.contains("--- LATEST CRASH LOGS ---"))
        }
    }

    @Test
    fun discordProfileManager_sanitizesTokensAndValidatesClientIds() {
        assertEquals("abc.def.ghi", DiscordProfileManager.sanitizeToken("  \"abc.def.ghi\"  "))
        assertEquals("abc.def.ghi", DiscordProfileManager.sanitizeToken("'abc.def.ghi'"))

        assertTrue(DiscordProfileManager.isHeaderSafeToken("abc.def.ghi"))
        assertFalse(DiscordProfileManager.isHeaderSafeToken(""))
        assertFalse(DiscordProfileManager.isHeaderSafeToken("bad\ntoken"))
        assertFalse(DiscordProfileManager.isHeaderSafeToken("bad token with spaces"))

        assertTrue(DiscordProfileManager.isValidOAuthClientId("962990036020756480"))
        assertFalse(DiscordProfileManager.isValidOAuthClientId("not_numeric_id"))
        assertFalse(DiscordProfileManager.isValidOAuthClientId("12345"))
    }

    @Test
    fun discordRpcClient_rejectsMalformedTokenWithoutCrashing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = DiscordRpcClient(context)
        try {
            client.connect(token = "token\nwith\rbad\tchars", showSuccessToast = false)
            val state = client.connectionState.value
            assertTrue(state is DiscordConnectionState.Error)
            assertTrue((state as DiscordConnectionState.Error).isAuthError)
        } finally {
            client.release()
        }
    }
}
