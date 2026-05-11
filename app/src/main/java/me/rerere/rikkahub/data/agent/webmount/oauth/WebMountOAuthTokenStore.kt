package me.rerere.rikkahub.data.agent.webmount.oauth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.agent.webmount.core.WebMountOAuthToken
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent encrypted store for [WebMountOAuthToken]s and per-provider app
 * credentials (app_id / app_secret pairs entered by the user in the WebMount
 * Stations panel).
 *
 * Backed by androidx.security.crypto EncryptedSharedPreferences (AES256_SIV
 * for keys, AES256_GCM for values). Falls back to a plain in-memory map if
 * EncryptedSharedPreferences initialization throws (e.g. keystore corrupted
 * after a system restore) — the fallback is logged loudly and the store
 * stays usable so the rest of the app doesn't crash, but tokens won't
 * survive a process restart.
 */
class WebMountOAuthTokenStore(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val tokens: SharedPreferences? = tryCreateEncryptedPrefs(context, TOKEN_FILE)
    private val creds: SharedPreferences? = tryCreateEncryptedPrefs(context, CRED_FILE)
    private val memTokens = ConcurrentHashMap<String, WebMountOAuthToken>()
    private val memCreds = ConcurrentHashMap<String, OAuthAppCredentials>()
    private val _updates = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val updates: SharedFlow<String> = _updates.asSharedFlow()

    // ---- tokens ------------------------------------------------------------

    fun putToken(provider: String, token: WebMountOAuthToken) {
        val raw = json.encodeToString(JsonElement.serializer(), token.toJson())
        if (tokens != null) {
            tokens.edit().putString(provider, raw).apply()
        } else {
            memTokens[provider] = token
        }
        _updates.tryEmit(provider)
    }

    fun getToken(provider: String): WebMountOAuthToken? {
        val raw = tokens?.getString(provider, null)
        return if (raw != null) {
            runCatching { tokenFromJson(json.parseToJsonElement(raw).jsonObject) }.getOrNull()
        } else {
            memTokens[provider]
        }
    }

    fun clearToken(provider: String) {
        tokens?.edit()?.remove(provider)?.apply()
        memTokens.remove(provider)
        _updates.tryEmit(provider)
    }

    fun tokenProviders(): Set<String> =
        tokens?.all?.keys?.toSet() ?: memTokens.keys.toSet()

    // ---- credentials -------------------------------------------------------

    fun putCredentials(provider: String, cred: OAuthAppCredentials) {
        val raw = json.encodeToString(JsonElement.serializer(), cred.toJson())
        if (creds != null) {
            creds.edit().putString(provider, raw).apply()
        } else {
            memCreds[provider] = cred
        }
        _updates.tryEmit("$provider:credentials")
    }

    fun getCredentials(provider: String): OAuthAppCredentials? {
        val raw = creds?.getString(provider, null)
        return if (raw != null) {
            runCatching { credentialsFromJson(json.parseToJsonElement(raw).jsonObject) }.getOrNull()
        } else {
            memCreds[provider]
        }
    }

    fun clearCredentials(provider: String) {
        creds?.edit()?.remove(provider)?.apply()
        memCreds.remove(provider)
        _updates.tryEmit("$provider:credentials")
    }

    // ---- M1.1 compatibility shims (called by WebMountManager ctor) ---------

    @Deprecated("Use putToken")
    fun put(provider: String, token: WebMountOAuthToken) = putToken(provider, token)

    @Deprecated("Use getToken")
    fun get(provider: String): WebMountOAuthToken? = getToken(provider)

    @Deprecated("Use clearToken")
    fun clear(provider: String) = clearToken(provider)

    @Deprecated("Use tokenProviders")
    fun providers(): Set<String> = tokenProviders()

    // ---- serialization helpers --------------------------------------------

    private fun WebMountOAuthToken.toJson(): JsonObject = buildJsonObject {
        put("provider", provider)
        put("access_token", accessToken)
        refreshToken?.let { put("refresh_token", it) }
        scope?.let { put("scope", it) }
        put("token_type", tokenType)
        put("expires_at_ms", expiresAtMs)
        put("acquired_at_ms", acquiredAtMs)
        userOpenId?.let { put("user_open_id", it) }
    }

    private fun tokenFromJson(o: JsonObject): WebMountOAuthToken =
        WebMountOAuthToken(
            provider = o.stringOrThrow("provider"),
            accessToken = o.stringOrThrow("access_token"),
            refreshToken = o["refresh_token"]?.jsonPrimitive?.contentOrNull,
            scope = o["scope"]?.jsonPrimitive?.contentOrNull,
            tokenType = o["token_type"]?.jsonPrimitive?.contentOrNull ?: "Bearer",
            expiresAtMs = o["expires_at_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
            acquiredAtMs = o["acquired_at_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
            userOpenId = o["user_open_id"]?.jsonPrimitive?.contentOrNull,
        )

    private fun OAuthAppCredentials.toJson(): JsonObject = buildJsonObject {
        put("provider", provider)
        put("app_id", appId)
        appSecret?.let { put("app_secret", it) }
        redirectUri?.let { put("redirect_uri", it) }
        scope?.let { put("scope", it) }
    }

    private fun credentialsFromJson(o: JsonObject): OAuthAppCredentials =
        OAuthAppCredentials(
            provider = o.stringOrThrow("provider"),
            appId = o.stringOrThrow("app_id"),
            appSecret = o["app_secret"]?.jsonPrimitive?.contentOrNull,
            redirectUri = o["redirect_uri"]?.jsonPrimitive?.contentOrNull,
            scope = o["scope"]?.jsonPrimitive?.contentOrNull,
        )

    private fun JsonObject.stringOrThrow(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull
            ?: error("$name missing in WebMount OAuth payload")

    private fun tryCreateEncryptedPrefs(context: Context, fileName: String): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (error: Throwable) {
        Log.e(TAG, "Failed to create EncryptedSharedPreferences for $fileName; falling back to memory", error)
        null
    }

    companion object {
        private const val TAG = "WebMountOAuthStore"
        private const val TOKEN_FILE = "amberagent_webmount_oauth_tokens"
        private const val CRED_FILE = "amberagent_webmount_oauth_creds"
    }
}

/**
 * Per-provider OAuth app registration (the developer's app_id + secret,
 * which the user enters in WebMount Stations → Connect → ⚙).
 * `appSecret` is optional — public clients (PKCE without secret) leave it null.
 */
data class OAuthAppCredentials(
    val provider: String,
    val appId: String,
    val appSecret: String? = null,
    val redirectUri: String? = null,
    val scope: String? = null,
) {
    /** Defensive: never leak the secret via toString. */
    override fun toString(): String =
        "OAuthAppCredentials(provider=$provider, appId=$appId, " +
            "appSecret=${if (appSecret == null) "null" else "***redacted***"}, " +
            "redirectUri=$redirectUri, scope=$scope)"
}
