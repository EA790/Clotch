package com.example

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.discord.DiscordConnectionState
import com.example.discord.DiscordOAuthCallbackParser
import com.example.discord.DiscordOAuthCallbackResult
import com.example.discord.DiscordPresencePayload
import com.example.discord.DiscordProfileManager
import com.example.discord.DiscordRpcClient
import com.example.recording.RecordingController
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiscordAndLifecycleTest {

    private suspend fun awaitState(
        timeoutMs: Long = 1_500L,
        predicate: () -> Boolean
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            delay(20L)
        }
        return predicate()
    }

    @Test
    fun connectWithBlankToken_reportsAuthErrorWithoutThrowing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = DiscordRpcClient(context)
        try {
            client.connect(token = "   ", showSuccessToast = false)
            val state = client.connectionState.value
            assertTrue(state is DiscordConnectionState.Error)
            assertTrue((state as DiscordConnectionState.Error).isAuthError)
        } finally {
            client.release()
        }
    }

    @Test
    fun connectWhenNetworkUnavailable_reportsWaitingError() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowCm = Shadows.shadowOf(cm)
        val activeNetwork = cm.activeNetwork
        if (activeNetwork != null) {
            val noInternetCaps = ShadowNetworkCapabilities.newInstance()
            Shadows.shadowOf(noInternetCaps).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowCm.setNetworkCapabilities(activeNetwork, noInternetCaps)
        } else {
            shadowCm.clearAllNetworks()
        }

        val client = DiscordRpcClient(context)
        try {
            client.connect(token = "fake_token_for_offline_check", showSuccessToast = true)
            val state = client.connectionState.value
            assertTrue(state is DiscordConnectionState.Error)
            val error = state as DiscordConnectionState.Error
            assertFalse(error.isAuthError)
            assertTrue(error.message.contains("internet", ignoreCase = true))
        } finally {
            client.release()
        }
    }

    @Test
    fun disconnectAndUpdatePresenceDisabled_clearOrKeepPresenceAsRequested() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = DiscordRpcClient(context)
        try {
            val payload = DiscordPresencePayload(
                isRecording = true,
                gameName = "Valorant",
                fps = 60,
                resolutionLabel = "1080p",
                bitrateMbps = 12,
                largeImageUrl = "https://example.com/cat.png"
            )

            // Blank token + active recording surfaces auth error while tracking desired payload
            client.updatePresence(payload = payload, rpcEnabled = true, token = "")
            assertNotNull(client.currentPresence.value)
            assertTrue(client.connectionState.value is DiscordConnectionState.Error)

            // Disconnecting without clearing keeps payload cached for future reconnect
            client.disconnect(clearPresence = false)
            assertTrue(awaitState { client.connectionState.value is DiscordConnectionState.Disconnected })
            assertNotNull(client.currentPresence.value)

            // Disconnecting with clearPresence clears both payload and connection state
            client.disconnect(clearPresence = true)
            assertTrue(awaitState { client.connectionState.value is DiscordConnectionState.Disconnected })
            assertNull(client.currentPresence.value)

            // Disabling RPC sets state to Disconnected cleanly
            client.updatePresence(payload = payload, rpcEnabled = false, token = "some_token")
            assertTrue(awaitState { client.connectionState.value is DiscordConnectionState.Disconnected })
        } finally {
            client.release()
            client.release() // verify idempotent release
        }
    }

    @Test
    fun recordingController_disconnectAndReleaseAreSafe() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = RecordingController(context)
        try {
            controller.disconnectDiscordRpc()
            assertTrue(
                awaitState {
                    controller.discordConnectionState.value is DiscordConnectionState.Disconnected
                }
            )
            assertEquals(null, controller.discordPresence.value)
        } finally {
            controller.release()
            controller.release()
        }
    }

    @Test
    fun discordOAuthParser_extractsFragmentTokenAndBuildsImplicitGrantUrl() {
        val defaultUrl = DiscordProfileManager.buildOAuthAuthorizeUrl("123456789012345678")
        assertTrue(defaultUrl.contains("response_type=token"))
        assertTrue(defaultUrl.contains("redirect_uri=clotch%3A%2F%2Fdiscord-callback"))
        assertTrue(defaultUrl.contains("scope=identify%20rpc"))

        val fallbackUrl = DiscordProfileManager.buildOAuthAuthorizeUrl(
            clientId = "123456789012345678",
            useIdentifyFallback = true
        )
        assertTrue(fallbackUrl.contains("response_type=token"))
        assertTrue(fallbackUrl.contains("redirect_uri=clotch%3A%2F%2Fdiscord-callback"))
        assertTrue(fallbackUrl.contains("scope=identify"))
        assertFalse(fallbackUrl.contains("rpc"))

        val successUri = Uri.parse(
            "clotch://discord-callback#access_token=abc_oauth_token_999&token_type=Bearer&expires_in=604800"
        )
        val parsed = DiscordOAuthCallbackParser.parse(successUri)
        assertTrue(parsed is DiscordOAuthCallbackResult.Token)
        assertEquals("abc_oauth_token_999", (parsed as DiscordOAuthCallbackResult.Token).accessToken)

        val errUri = Uri.parse(
            "clotch://discord-callback?error=invalid_request&error_description=Invalid%20OAuth2%20redirect_uri"
        )
        val errParsed = DiscordOAuthCallbackParser.parse(errUri)
        assertTrue(errParsed is DiscordOAuthCallbackResult.Error)
        val redirectErr = errParsed as DiscordOAuthCallbackResult.Error
        assertTrue(redirectErr.isRedirectUriError)
        assertTrue(redirectErr.message.contains("clotch://discord-callback"))

        val scopeErrUri = Uri.parse(
            "clotch://discord-callback?error=invalid_scope&error_description=Invalid%20scope%20rpc"
        )
        val scopeErrParsed = DiscordOAuthCallbackParser.parse(scopeErrUri)
        assertTrue(scopeErrParsed is DiscordOAuthCallbackResult.Error)
        assertTrue((scopeErrParsed as DiscordOAuthCallbackResult.Error).message.contains("fallback", ignoreCase = true))
    }
}
