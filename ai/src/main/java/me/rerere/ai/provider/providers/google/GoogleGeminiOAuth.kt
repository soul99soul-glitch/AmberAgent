package me.rerere.ai.provider.providers.google

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.util.json
import me.rerere.common.http.await
import me.rerere.common.oauth.LoopbackOAuthCallbackServer
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.uuid.Uuid

const val GOOGLE_GEMINI_CODE_ASSIST_BASE_URL = "https://cloudcode-pa.googleapis.com"

// Public OAuth client_id + client_secret published by Google's official gemini-cli
// (https://github.com/google-gemini/gemini-cli/blob/main/packages/core/src/code_assist/oauth2.ts).
// For installed-app OAuth clients Google explicitly states the secret "is not a secret"
// — PKCE is what makes the flow safe, not secret concealment. Reusing the gemini-cli
// client_id is the only way to talk to cloudcode-pa, but it's worth acknowledging that
// Google may treat third-party clients reusing it as out-of-policy; the UI carries a
// ToS disclaimer to that effect (see ProviderConfigureGoogle).
private const val GEMINI_OAUTH_CLIENT_ID =
    "681255809395-oo8ft2oprdrnp9e3aqf6av3hmdib135j.apps.googleusercontent.com"
private const val GEMINI_OAUTH_CLIENT_SECRET = "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"
private const val GOOGLE_OAUTH_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
private const val GOOGLE_OAUTH_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
private const val GOOGLE_OAUTH_SCOPE =
    "https://www.googleapis.com/auth/cloud-platform " +
        "https://www.googleapis.com/auth/userinfo.email " +
        "https://www.googleapis.com/auth/userinfo.profile"

private const val PREF_NAME = "google_gemini_oauth"
private const val REFRESH_SKEW_MS = 2 * 60 * 1000L
private const val AUTH_TIMEOUT_MS = 5 * 60 * 1000L
private const val FALLBACK_TOKEN_LIFETIME_MS = 60 * 60 * 1000L
private const val TAG = "GoogleGeminiOAuth"

/**
 * Token bundle persisted per-providerId.
 *
 *  - [accessToken] / [refreshToken] / [expiresAtMillis]: standard OAuth2 fields.
 *  - [email] / [idToken]: optional, decoded from the id_token JWT during token
 *    exchange so the UI can show "已连接 user@example.com" without an extra call.
 *  - [projectId] / [onboardedTier]: filled in by commit #4 once we wire
 *    `cloudcode-pa:loadCodeAssist` + `:onboardUser`. Commit #3 leaves them null
 *    on first login; refresh preserves whatever was there before.
 */
@Serializable
data class GoogleGeminiAuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMillis: Long,
    val email: String? = null,
    val idToken: String? = null,
    val projectId: String? = null,
    val onboardedTier: String? = null,
)

class GoogleGeminiAuthStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun get(providerId: Uuid): GoogleGeminiAuthTokens? {
        val raw = prefs.getString(providerId.toString(), null) ?: return null
        return runCatching { json.decodeFromString<GoogleGeminiAuthTokens>(raw) }
            .getOrNull()
            .also { if (it == null) prefs.edit().remove(providerId.toString()).apply() }
    }

    fun save(providerId: Uuid, tokens: GoogleGeminiAuthTokens) {
        prefs.edit().putString(providerId.toString(), json.encodeToString(tokens)).apply()
    }

    fun clear(providerId: Uuid) {
        prefs.edit().remove(providerId.toString()).apply()
    }

    /** Serialize ALL provider tokens into a single JSON blob for Sync export. The blob is
     *  encrypted at the SyncArchive layer; we just hand over the raw map. */
    fun exportRawJsonForSync(): String {
        val all = prefs.all.mapNotNull { (key, value) ->
            val str = value as? String ?: return@mapNotNull null
            key to str
        }.toMap()
        return json.encodeToString(all)
    }

    /** Inverse of [exportRawJsonForSync] — wipes current store then writes all entries. */
    fun restoreRawJsonFromSync(raw: String) {
        val map = runCatching { json.decodeFromString<Map<String, String>>(raw) }
            .getOrNull() ?: return
        prefs.edit().clear().apply()
        prefs.edit().apply { map.forEach { (k, v) -> putString(k, v) } }.apply()
    }
}

/**
 * Orchestrates the full OAuth 2.0 Authorization Code + PKCE flow against Google's
 * accounts/oauth2 endpoints, using a [LoopbackOAuthCallbackServer] on 127.0.0.1:53682
 * for the redirect URI (gemini-cli's installed-app client only allows loopback).
 *
 * Commit #3 scope: end-to-end OAuth, persisted tokens, refresh. Does NOT yet call
 * cloudcode-pa's `loadCodeAssist` / `onboardUser` — that lives in commit #4 alongside
 * the chat-completion endpoint integration.
 */
class GoogleGeminiOAuthClient(
    private val httpClient: OkHttpClient,
    private val authStore: GoogleGeminiAuthStore,
) {
    private val refreshMutex = Mutex()

    /**
     * Run the full authorization-code + PKCE flow in the foreground:
     *   1. Bind loopback server (single-shot, port 53682).
     *   2. Generate PKCE pair + state nonce.
     *   3. Open the Google consent page in Chrome Custom Tabs / system browser.
     *   4. Wait for the browser to redirect to 127.0.0.1:53682/callback with `code`.
     *   5. Exchange `code` + `code_verifier` for access/refresh tokens.
     *   6. Decode id_token JWT to surface the user's email in the UI.
     *   7. Persist to [GoogleGeminiAuthStore].
     *
     * Throws on any failure with a human-readable message. Caller is responsible for
     * keeping the app in the foreground — loopback socket dies with the process and
     * there's no resume mechanism (unlike webmount's deep-link path).
     */
    suspend fun authorize(context: Context, providerId: Uuid): GoogleGeminiAuthTokens {
        val server = try {
            LoopbackOAuthCallbackServer()
        } catch (error: Throwable) {
            throw IllegalStateException(error.message ?: "无法启动本地回环 server", error)
        }
        return server.use { running ->
            val state = randomState()
            val verifier = generateCodeVerifier()
            val challenge = s256Challenge(verifier)
            val redirectUri = LoopbackOAuthCallbackServer.DEFAULT_REDIRECT_URI
            launchBrowser(context, buildAuthorizationUrl(redirectUri, state, challenge))
            val callback = withTimeoutOrNull(AUTH_TIMEOUT_MS) { running.awaitCallback() }
                ?: error("Google 授权超时 — 浏览器未在 ${AUTH_TIMEOUT_MS / 1000}s 内返回授权码。")
            require(callback.isSuccess) {
                "Google 授权失败：${callback.error.orEmpty()} ${callback.errorDescription.orEmpty()}".trim()
            }
            require(callback.state == state) {
                "Google 授权 state 不一致，可能遭到中间人篡改 (got=${callback.state?.take(6)}…, expected=${state.take(6)}…)。"
            }
            val code = callback.code ?: error("Google 授权回调缺少 code 参数。")
            val tokens = exchangeAuthorizationCode(code, verifier, redirectUri)
            authStore.save(providerId, tokens)
            tokens
        }
    }

    suspend fun refresh(providerId: Uuid): GoogleGeminiAuthTokens = refreshMutex.withLock {
        val current = authStore.get(providerId)
            ?: error("没有可用的 Google OAuth token，请重新登录。")
        val refreshToken = current.refreshToken
            ?: error("Google OAuth 没有 refresh_token — 通常意味着上次登录时未带 prompt=consent，请重新登录。")
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", GEMINI_OAUTH_CLIENT_ID)
            .add("client_secret", GEMINI_OAUTH_CLIENT_SECRET)
            .build()
        val response = httpClient.newCall(
            Request.Builder().url(GOOGLE_OAUTH_TOKEN_ENDPOINT).post(body).build()
        ).await()
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            error("Google OAuth refresh 失败：HTTP ${response.code} ${text.take(300)}")
        }
        val parsed = json.parseToJsonElement(text).jsonObject
        val accessToken = parsed["access_token"]?.jsonPrimitive?.contentOrNull
            ?: error("Google OAuth refresh 响应缺少 access_token: ${text.take(300)}")
        val expiresIn = parsed["expires_in"]?.jsonPrimitive?.longOrNull
            ?: (FALLBACK_TOKEN_LIFETIME_MS / 1000)
        // Google may rotate refresh_token; preserve old one if not rotated.
        val newRefreshToken = parsed["refresh_token"]?.jsonPrimitive?.contentOrNull
            ?: current.refreshToken
        val newIdToken = parsed["id_token"]?.jsonPrimitive?.contentOrNull ?: current.idToken
        val newEmail = newIdToken?.let { decodeIdTokenEmail(it) } ?: current.email
        val merged = current.copy(
            accessToken = accessToken,
            refreshToken = newRefreshToken,
            idToken = newIdToken,
            email = newEmail,
            expiresAtMillis = System.currentTimeMillis() + expiresIn * 1000L,
        )
        authStore.save(providerId, merged)
        merged
    }

    /** Return a valid access token, refreshing in place if within the skew window or
     *  if the caller insists ([forceRefresh] = true after a 401 from the API). */
    suspend fun getValidAccessToken(providerId: Uuid, forceRefresh: Boolean = false): String? {
        val current = authStore.get(providerId) ?: return null
        val now = System.currentTimeMillis()
        if (!forceRefresh && current.expiresAtMillis - now > REFRESH_SKEW_MS) {
            return current.accessToken
        }
        return runCatching { refresh(providerId).accessToken }
            .onFailure { Log.w(TAG, "Refresh failed for provider $providerId", it) }
            .getOrNull()
    }

    fun logout(providerId: Uuid) {
        authStore.clear(providerId)
    }

    // ----------------------------------------------------------------------

    private fun buildAuthorizationUrl(redirectUri: String, state: String, codeChallenge: String): String {
        val params = listOf(
            "response_type" to "code",
            "client_id" to GEMINI_OAUTH_CLIENT_ID,
            "redirect_uri" to redirectUri,
            "scope" to GOOGLE_OAUTH_SCOPE,
            "state" to state,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            // access_type=offline + prompt=consent forces Google to actually issue a
            // refresh_token (otherwise on re-consent it returns only access_token, and
            // refresh() above explicitly errors out without one).
            "access_type" to "offline",
            "prompt" to "consent",
            "include_granted_scopes" to "true",
        )
        return GOOGLE_OAUTH_AUTH_ENDPOINT + "?" +
            params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    }

    private fun launchBrowser(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private suspend fun exchangeAuthorizationCode(
        code: String,
        codeVerifier: String,
        redirectUri: String,
    ): GoogleGeminiAuthTokens {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("code_verifier", codeVerifier)
            .add("redirect_uri", redirectUri)
            .add("client_id", GEMINI_OAUTH_CLIENT_ID)
            .add("client_secret", GEMINI_OAUTH_CLIENT_SECRET)
            .build()
        val response = httpClient.newCall(
            Request.Builder().url(GOOGLE_OAUTH_TOKEN_ENDPOINT).post(body).build()
        ).await()
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            error("Google OAuth token 交换失败：HTTP ${response.code} ${text.take(300)}")
        }
        val parsed = json.parseToJsonElement(text).jsonObject
        val accessToken = parsed["access_token"]?.jsonPrimitive?.contentOrNull
            ?: error("Google OAuth 响应缺少 access_token: ${text.take(300)}")
        val refreshToken = parsed["refresh_token"]?.jsonPrimitive?.contentOrNull
        val expiresIn = parsed["expires_in"]?.jsonPrimitive?.longOrNull
            ?: (FALLBACK_TOKEN_LIFETIME_MS / 1000)
        val idToken = parsed["id_token"]?.jsonPrimitive?.contentOrNull
        return GoogleGeminiAuthTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            idToken = idToken,
            email = idToken?.let { decodeIdTokenEmail(it) },
            expiresAtMillis = System.currentTimeMillis() + expiresIn * 1000L,
        )
    }

    /** Best-effort: decode the `email` claim from Google's id_token JWT so the UI can
     *  show "已连接 user@example.com" without a separate /userinfo call. JWT shape is
     *  `header.payload.signature`; we only need the payload middle segment. */
    private fun decodeIdTokenEmail(idToken: String): String? = runCatching {
        val parts = idToken.split(".")
        if (parts.size < 2) return@runCatching null
        val payload = Base64.decode(
            parts[1],
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        json.parseToJsonElement(String(payload, Charsets.UTF_8))
            .jsonObject["email"]
            ?.jsonPrimitive
            ?.contentOrNull
    }.getOrNull()

    // -- PKCE + state primitives ---------------------------------------------------

    private val secureRandom = SecureRandom()

    private fun randomState(): String {
        val buf = ByteArray(24)
        secureRandom.nextBytes(buf)
        return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun generateCodeVerifier(): String {
        val buf = ByteArray(64)
        secureRandom.nextBytes(buf)
        return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun s256Challenge(verifier: String): String {
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(sha, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}

/**
 * Seed model IDs to put into [me.rerere.ai.provider.ProviderSetting.Google.models] right
 * after a successful Gemini OAuth login, so the user lands on a usable provider without
 * having to type model names.
 *
 * **Why hardcoded:** there is no public "list available models" endpoint for the
 * cloudcode-pa OAuth path. The CodeAssistServer's full method list
 * (streamGenerateContent / generateContent / onboardUser / loadCodeAssist /
 * countTokens / retrieveUserQuota / ...) has no listModels method, and `loadCodeAssist`
 * itself only returns tier info (FREE / LEGACY / STANDARD), not a model list. The
 * adjacent `generativelanguage.googleapis.com/v1beta/models` ListModels endpoint exists
 * but expects API-key auth — calling it with the gemini-cli OAuth bearer returns
 * `403 ACCESS_TOKEN_SCOPE_INSUFFICIENT` because that path wants the
 * `generative-language.retriever` scope and our token only carries `cloud-platform`.
 *
 * Reference: google-gemini/gemini-cli's own `packages/core/src/config/models.ts`
 * also hardcodes the family — we mirror it. Update this list when the upstream cli
 * adds/drops a tier-FREE-eligible model. The 2.0-flash family was dropped upstream;
 * 2.5-flash-lite and the 3.x previews replaced it.
 */
fun defaultGeminiOAuthModelList(): List<me.rerere.ai.provider.Model> {
    return GEMINI_OAUTH_FALLBACK_MODEL_IDS.map { id ->
        me.rerere.ai.provider.Model(
            modelId = id,
            displayName = id,
        )
    }
}

private val GEMINI_OAUTH_FALLBACK_MODEL_IDS = listOf(
    "gemini-2.5-pro",
    "gemini-2.5-flash",
    "gemini-2.5-flash-lite",
    "gemini-3-pro-preview",
    "gemini-3-flash-preview",
)
