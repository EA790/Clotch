package com.example.discord

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.diagnostics.CrashLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val Context.discordProfileStore: DataStore<Preferences> by preferencesDataStore(
    name = "clotch_discord_profile"
)

data class DiscordUserProfile(
    val id: String = "",
    val username: String = "",
    val globalName: String = "",
    val avatarUrl: String = "",
    val bio: String = "",
    val isBearerOAuth: Boolean = false
) {
    val isSignedIn: Boolean get() = id.isNotBlank() && username.isNotBlank()
    val displayTitle: String get() = globalName.ifBlank { username }
}

class DiscordProfileManager(private val context: Context) {

    companion object {
        const val OAUTH_REDIRECT_URI = "clotch://discord-callback"
        private const val DISCORD_API_ME = "https://discord.com/api/v10/users/@me"

        fun sanitizeToken(rawToken: String): String {
            return rawToken.trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .trim()
        }

        fun isHeaderSafeToken(token: String): Boolean {
            if (token.isEmpty()) return false
            return token.all { ch ->
                val code = ch.code
                code in 0x21..0x7E
            }
        }

        fun isValidOAuthClientId(clientId: String): Boolean {
            val clean = clientId.trim()
            return clean.length in 15..22 && clean.all { it.isDigit() }
        }
    }

    private object Keys {
        val USER_ID = stringPreferencesKey("discord_user_id")
        val USERNAME = stringPreferencesKey("discord_username")
        val GLOBAL_NAME = stringPreferencesKey("discord_global_name")
        val AVATAR_URL = stringPreferencesKey("discord_avatar_url")
        val BIO = stringPreferencesKey("discord_bio")
        val IS_BEARER = booleanPreferencesKey("discord_is_bearer")
        val OAUTH_CLIENT_ID = stringPreferencesKey("discord_oauth_client_id")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val profileFlow: Flow<DiscordUserProfile> = context.discordProfileStore.data.map { prefs ->
        DiscordUserProfile(
            id = prefs[Keys.USER_ID] ?: "",
            username = prefs[Keys.USERNAME] ?: "",
            globalName = prefs[Keys.GLOBAL_NAME] ?: "",
            avatarUrl = prefs[Keys.AVATAR_URL] ?: "",
            bio = prefs[Keys.BIO] ?: "",
            isBearerOAuth = prefs[Keys.IS_BEARER] ?: false
        )
    }

    val oauthClientIdFlow: Flow<String> = context.discordProfileStore.data.map { prefs ->
        prefs[Keys.OAUTH_CLIENT_ID] ?: ""
    }

    suspend fun saveOAuthClientId(clientId: String) {
        context.discordProfileStore.edit { it[Keys.OAUTH_CLIENT_ID] = clientId.trim() }
    }

    fun buildOAuthAuthorizeUrl(clientId: String): String {
        return Uri.Builder()
            .scheme("https")
            .authority("discord.com")
            .appendPath("oauth2")
            .appendPath("authorize")
            .appendQueryParameter("client_id", clientId.trim())
            .appendQueryParameter("redirect_uri", OAUTH_REDIRECT_URI)
            .appendQueryParameter("response_type", "token")
            .appendQueryParameter("scope", "identify")
            .build()
            .toString()
    }

    suspend fun fetchAndSaveProfile(
        rawToken: String,
        isBearerOAuth: Boolean
    ): Result<DiscordUserProfile> = withContext(Dispatchers.IO) {
        val cleanToken = sanitizeToken(rawToken)
        if (!isHeaderSafeToken(cleanToken)) {
            val err = IllegalArgumentException("Invalid Discord token format (remove spaces or special characters)")
            showToast(err.message!!)
            return@withContext Result.failure(err)
        }

        try {
            val authHeader = if (isBearerOAuth) "Bearer $cleanToken" else cleanToken
            val request = Request.Builder()
                .url(DISCORD_API_ME)
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty().trim()
                if (!response.isSuccessful) {
                    val msg = when (response.code) {
                        401 -> "Discord sign-in failed: Invalid or expired token"
                        403 -> "Discord sign-in rejected (403 Forbidden)"
                        429 -> "Discord rate-limited request. Try again shortly."
                        else -> "Discord sign-in failed (HTTP ${response.code})"
                    }
                    showToast(msg)
                    return@withContext Result.failure(IllegalStateException(msg))
                }

                if (bodyString.isEmpty() || !bodyString.startsWith("{")) {
                    val msg = "Discord returned an unexpected non-JSON response"
                    CrashLogger.logError("DiscordProfileManager", "$msg: ${bodyString.take(200)}")
                    showToast(msg)
                    return@withContext Result.failure(IllegalStateException(msg))
                }

                val json = JSONObject(bodyString)
                val id = json.optString("id", "").takeIf { it != "null" } ?: ""
                val username = json.optString("username", "").takeIf { it != "null" } ?: ""
                if (id.isBlank() || username.isBlank()) {
                    val msg = "Discord profile response missing user ID or username"
                    showToast(msg)
                    return@withContext Result.failure(IllegalStateException(msg))
                }

                val globalName = json.optString("global_name", "")
                    .takeIf { it.isNotBlank() && it != "null" } ?: ""
                val avatarHash = json.optString("avatar", "")
                    .takeIf { it.isNotBlank() && it != "null" }
                val bio = json.optString("bio", "")
                    .takeIf { it.isNotBlank() && it != "null" } ?: ""
                val discriminator = json.optString("discriminator", "0")

                val avatarUrl = if (avatarHash != null && id.isNotBlank()) {
                    val ext = if (avatarHash.startsWith("a_")) "gif" else "png"
                    "https://cdn.discordapp.com/avatars/$id/$avatarHash.$ext?size=256"
                } else {
                    val index = if (discriminator != "0") {
                        (discriminator.toIntOrNull() ?: 0) % 5
                    } else {
                        ((id.toLongOrNull() ?: 0L) shr 22).toInt().and(0x7FFFFFFF) % 6
                    }
                    "https://cdn.discordapp.com/embed/avatars/$index.png"
                }

                val profile = DiscordUserProfile(
                    id = id,
                    username = username,
                    globalName = globalName,
                    avatarUrl = avatarUrl,
                    bio = bio,
                    isBearerOAuth = isBearerOAuth
                )

                context.discordProfileStore.edit { prefs ->
                    prefs[Keys.USER_ID] = profile.id
                    prefs[Keys.USERNAME] = profile.username
                    prefs[Keys.GLOBAL_NAME] = profile.globalName
                    prefs[Keys.AVATAR_URL] = profile.avatarUrl
                    prefs[Keys.BIO] = profile.bio
                    prefs[Keys.IS_BEARER] = profile.isBearerOAuth
                }

                showToast("Signed in to Discord as ${profile.displayTitle}")
                Result.success(profile)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            CrashLogger.logError("DiscordProfileManager", "Discord sign-in failed", t)
            val msg = "Discord sign-in error: ${t.localizedMessage ?: "Network unavailable"}"
            showToast(msg)
            Result.failure(t)
        }
    }

    suspend fun clearProfile() {
        context.discordProfileStore.edit { prefs ->
            prefs.remove(Keys.USER_ID)
            prefs.remove(Keys.USERNAME)
            prefs.remove(Keys.GLOBAL_NAME)
            prefs.remove(Keys.AVATAR_URL)
            prefs.remove(Keys.BIO)
            prefs.remove(Keys.IS_BEARER)
        }
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    fun shutdown() {
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { httpClient.dispatcher.cancelAll() }
        runCatching { httpClient.connectionPool.evictAll() }
    }
}
