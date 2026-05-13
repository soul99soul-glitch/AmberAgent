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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.util.json
import me.rerere.common.http.await
import me.rerere.common.oauth.LoopbackOAuthCallbackServer
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
// gemini-cli's User-Agent template (contentGenerator.ts:243-258). The server uses this
// to gate Pro / Free tier identification — a generic OkHttp UA gets classified as
// unknown-client and silently dropped into standard-tier even for Pro subscribers.
// We pose as gemini-cli; the trade-off is staying in the same "third-party reusing
// public client_id" ToS bucket already disclosed in the editor warning.
private const val CLOUDCODE_PA_USER_AGENT =
    "GeminiCLI/0.40.0/gemini-2.5-pro (android; arm64; amberagent)"
private const val LRO_POLL_INTERVAL_MS = 5_000L
private const val MAX_LRO_POLL_ITERATIONS = 12
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
            Log.i(TAG, "OAuth tokens stored for provider=$providerId, kicking off cloudcode-pa onboard…")
            // Run loadCodeAssist+onboardUser inside the login flow so the user pays the
            // ~5-15s onboarding cost upfront (with the "登录中" busy indicator) instead
            // of waiting 30s on the first chat. If onboarding fails, we still keep the
            // token — user can retry by sending a chat (ensureOnboarded is idempotent).
            val onboarded = runCatching { ensureOnboarded(providerId) }
                .onFailure { Log.w(TAG, "Onboard during login failed; will retry on first chat", it) }
                .getOrNull() ?: tokens
            Log.i(TAG, "Onboard done: projectId=${onboarded.projectId?.take(20)}… tier=${onboarded.onboardedTier}")
            onboarded
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

    /**
     * Resolve the cloudaicompanionProject identifier for the user, calling
     * `cloudcode-pa:loadCodeAssist` first and, if the user isn't already onboarded
     * to a tier, falling through to `cloudcode-pa:onboardUser` + LRO poll.
     * Persists the resolved project + tier into the stored tokens so subsequent
     * chat requests can wrap the v1internal body without an extra round-trip.
     *
     *  Reference: gemini-cli `packages/core/src/code_assist/setup.ts`.
     *  Behaviour:
     *   - tokens.projectId non-null and not "" → skip everything (already resolved)
     *   - else loadCodeAssist → if `cloudaicompanionProject` present → save it
     *   - else pick FREE tier (or first allowedTier) → onboardUser → poll LRO until
     *     `done=true` → save `response.cloudaicompanionProject.id`
     *
     * Throws on any HTTP failure with a Chinese-readable message.
     */
    suspend fun ensureOnboarded(providerId: Uuid): GoogleGeminiAuthTokens {
        val current = authStore.get(providerId)
            ?: error("尚未登录 Google OAuth，无法 onboard cloudcode-pa。")
        if (!current.projectId.isNullOrBlank() && !current.onboardedTier.isNullOrBlank()) {
            return current
        }
        val accessToken = getValidAccessToken(providerId)
            ?: error("无法刷新 Google OAuth token，请重新登录。")

        // 1) loadCodeAssist — CRITICAL: gemini-cli setup.ts:177-183 OMITS the
        // `cloudaicompanionProject` field entirely when the user has no
        // GOOGLE_CLOUD_PROJECT env var (JSON.stringify drops undefined keys). If we
        // send `""` instead, the server reads that as "user supplied an explicit
        // (empty) project" and irreversibly classifies the account as
        // userDefinedCloudaicompanionProject=true → standard-tier, masking the
        // Pro / Free subscription. Pro users then get 429 MODEL_CAPACITY_EXHAUSTED
        // forever. We mirror gemini-cli's body shape exactly — no project field at
        // all when we don't have one, and we don't slip a duetProject in either.
        val loadResp = postCloudCodeAssistJson(
            accessToken,
            ":loadCodeAssist",
            buildJsonObject {
                putJsonObject("metadata") {
                    put("ideType", "IDE_UNSPECIFIED")
                    put("platform", "PLATFORM_UNSPECIFIED")
                    put("pluginType", "GEMINI")
                }
            },
        )
        val currentProject = loadResp["cloudaicompanionProject"]?.jsonPrimitive?.contentOrNull
        val currentTier = loadResp["currentTier"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        if (!currentProject.isNullOrBlank() && !currentTier.isNullOrBlank()) {
            val updated = current.copy(projectId = currentProject, onboardedTier = currentTier)
            authStore.save(providerId, updated)
            return updated
        }

        // 2) onboardUser — pick the first allowed tier (FREE for individual accounts),
        // server returns a long-running operation we have to poll until done=true.
        val allowedTiers = loadResp["allowedTiers"]?.let {
            (it as? kotlinx.serialization.json.JsonArray)?.mapNotNull { item ->
                item.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            }
        }.orEmpty()
        val chosenTier = allowedTiers.firstOrNull { it == "FREE" }
            ?: allowedTiers.firstOrNull()
            ?: "FREE"
        // gemini-cli setup.ts L167-182: FREE tier passes cloudaicompanionProject =
        // undefined so the server assigns one; only paid/enterprise tiers send back
        // a pre-existing project id. Mirror that.
        val onboardResp = postCloudCodeAssistJson(
            accessToken,
            ":onboardUser",
            buildJsonObject {
                put("tierId", chosenTier)
                putJsonObject("metadata") {
                    put("ideType", "IDE_UNSPECIFIED")
                    put("platform", "PLATFORM_UNSPECIFIED")
                    put("pluginType", "GEMINI")
                }
                if (chosenTier != "FREE" && !currentProject.isNullOrBlank()) {
                    put("cloudaicompanionProject", currentProject)
                }
            },
        )

        val resolved = pollOnboardOperation(accessToken, onboardResp)
            ?: error("onboardUser 没有返回 cloudaicompanionProject。原始响应：${onboardResp.toString().take(300)}")
        val merged = current.copy(projectId = resolved, onboardedTier = chosenTier)
        authStore.save(providerId, merged)
        return merged
    }

    private suspend fun pollOnboardOperation(
        accessToken: String,
        initialResponse: JsonObject,
    ): String? {
        // gemini-cli setup.ts L187-193 + server.ts L139-164: poll uses GET (not POST)
        // and URL is just /v1internal/{name} where `name` already contains
        // `operations/...`. `done` is a boolean, not a string. 5s interval.
        var current = initialResponse
        repeat(MAX_LRO_POLL_ITERATIONS) { iteration ->
            val done = current["done"]?.jsonPrimitive?.booleanOrNull == true
            if (done) {
                val response = current["response"]?.jsonObject
                val errorObj = current["error"]?.jsonObject
                if (errorObj != null) {
                    error("onboardUser LRO 报错：${errorObj.toString().take(300)}")
                }
                // gemini-cli's OnboardUserResponse keeps the project under
                // `cloudaicompanionProject.id`, so peel that nested object.
                return response
                    ?.get("cloudaicompanionProject")?.jsonObject
                    ?.get("id")?.jsonPrimitive?.contentOrNull
            }
            val opName = current["name"]?.jsonPrimitive?.contentOrNull ?: return null
            delay(LRO_POLL_INTERVAL_MS)
            current = getCloudCodeAssistJson(accessToken, opName)
            Log.d(TAG, "Onboard LRO poll iter=$iteration done=${current["done"]}")
        }
        error("onboardUser LRO 轮询 ${LRO_POLL_INTERVAL_MS * MAX_LRO_POLL_ITERATIONS / 1000}s 仍未完成")
    }

    /**
     * Stream a chat completion through cloudcode-pa's v1internal endpoint. Returns the
     * raw OkHttp Response so caller (GoogleProvider) can hook its existing SSE parser.
     * Body is the v1internal wrapper `{ model, project, request: {...} }`; caller passes
     * the inner standard Gemini-API request payload.
     */
    suspend fun streamGenerateContent(
        accessToken: String,
        modelId: String,
        projectId: String,
        innerRequest: JsonObject,
    ): Request = buildCloudCodeAssistRequest(
        accessToken = accessToken,
        path = "/v1internal:streamGenerateContent?alt=sse",
        modelId = modelId,
        projectId = projectId,
        innerRequest = innerRequest,
    )

    /** Same as [streamGenerateContent] but for the non-streaming generateContent path. */
    suspend fun generateContent(
        accessToken: String,
        modelId: String,
        projectId: String,
        innerRequest: JsonObject,
    ): Request = buildCloudCodeAssistRequest(
        accessToken = accessToken,
        path = "/v1internal:generateContent",
        modelId = modelId,
        projectId = projectId,
        innerRequest = innerRequest,
    )

    private fun buildCloudCodeAssistRequest(
        accessToken: String,
        path: String,
        modelId: String,
        projectId: String,
        innerRequest: JsonObject,
    ): Request {
        // gemini-cli's converter.ts L89-98 sends model / project / request +
        // user_prompt_id (snake_case, used for server-side tracing). Sending it
        // matches what the official CLI emits — keeps us indistinguishable from
        // gemini-cli at the protocol level.
        val wrapper = buildJsonObject {
            put("model", modelId)
            put("project", projectId)
            put("user_prompt_id", Uuid.random().toString())
            put("request", innerRequest)
        }
        return Request.Builder()
            .url("$GOOGLE_GEMINI_CODE_ASSIST_BASE_URL$path")
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .header("User-Agent", CLOUDCODE_PA_USER_AGENT)
            .header(
                "Client-Metadata",
                "ideType=IDE_UNSPECIFIED,platform=PLATFORM_UNSPECIFIED,pluginType=GEMINI",
            )
            .header("x-activity-request-id", Uuid.random().toString())
            .post(wrapper.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private suspend fun postCloudCodeAssistJson(
        accessToken: String,
        method: String,
        body: JsonObject,
    ): JsonObject {
        val url = "$GOOGLE_GEMINI_CODE_ASSIST_BASE_URL/v1internal$method"
        // Two cosmetic headers a couple of mature wrappers ship that the canonical
        // gemini-cli also sends: Client-Metadata (echoes the JSON metadata block as a
        // single comma-joined header value — KashifKhn/gemini-proxy constants.ts:22-23)
        // and x-activity-request-id (per-call UUID, both opencode-gemini-auth and
        // KashifKhn ship it). Neither flips a ghost-project user out of standard-tier
        // (issue #22648 et al. confirm this is server-side), but at least keeps us
        // wire-format indistinguishable from those projects.
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .header("User-Agent", CLOUDCODE_PA_USER_AGENT)
            .header(
                "Client-Metadata",
                "ideType=IDE_UNSPECIFIED,platform=PLATFORM_UNSPECIFIED,pluginType=GEMINI",
            )
            .header("x-activity-request-id", Uuid.random().toString())
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        Log.i(TAG, "cloudcode-pa $method request: $body")
        val response = httpClient.newCall(request).await()
        val text = response.body?.string().orEmpty()
        Log.i(TAG, "cloudcode-pa $method response (${response.code}): ${text.take(2000)}")
        if (!response.isSuccessful) {
            error("cloudcode-pa $method 失败：HTTP ${response.code} ${text.take(300)}")
        }
        return runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { error("cloudcode-pa $method 响应不是 JSON：${text.take(300)}") }
    }

    /** GET against `https://cloudcode-pa.googleapis.com/v1internal/{operationName}`. Used
     *  for LRO polling — operationName already contains the `operations/...` prefix the
     *  server sent back, so we just append it after `/v1internal/`. Reference:
     *  gemini-cli `server.ts` `getOperationUrl` + `requestGetOperation`. */
    private suspend fun getCloudCodeAssistJson(
        accessToken: String,
        operationName: String,
    ): JsonObject {
        val url = "$GOOGLE_GEMINI_CODE_ASSIST_BASE_URL/v1internal/$operationName"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", CLOUDCODE_PA_USER_AGENT)
            .get()
            .build()
        val response = httpClient.newCall(request).await()
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            error("cloudcode-pa LRO poll 失败：HTTP ${response.code} ${text.take(300)}")
        }
        return runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { error("cloudcode-pa LRO poll 响应不是 JSON：${text.take(300)}") }
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

// Model IDs cloudcode-pa actually serves under typical (ghost-project / standard-tier)
// OAuth accounts, ordered by recency. Verified end-to-end on device:
//   - 3.1 preview pair is the newest tier of preview the Code Assist endpoint
//     currently hands out; soft default.
//   - 3 preview pair confirmed working on the same account state.
//   - 2.5 family removed: it 429s with MODEL_CAPACITY_EXHAUSTED on accounts that
//     Google has bound to a ghost cloudaicompanionProject (gemini-cli issues
//     #22648 / #24937 et al.). Anyone with a fresh project can re-add them by hand
//     via the editor's "+ add model" — keeping them out of the auto-seed avoids the
//     "I clicked refresh and got a wall of 404/429 models" first-run experience.
private val GEMINI_OAUTH_FALLBACK_MODEL_IDS = listOf(
    "gemini-3.1-pro-preview",
    "gemini-3-pro-preview",
    "gemini-3-flash-preview",
)

/** Model IDs we've previously shipped as defaults that the current cloudcode-pa fallback
 *  list considers stale — either because the endpoint outright rejects them (404 for the
 *  GA-named 3.x family, 404 for the legacy 2.0 family) or because the practical user base
 *  for OAuth chat is dominated by ghost-project accounts where they 429 every time (2.5
 *  family). Used by the editor's re-login path: if the user's current model list is
 *  entirely composed of these IDs (i.e. it's the leftover from an earlier APK's seed,
 *  not a deliberate user curation), it's safe to overwrite with the current default set.
 *  A single user-added model that isn't on this list is enough to opt-out of migration. */
val OBSOLETE_GEMINI_OAUTH_MODEL_IDS: Set<String> = setOf(
    // 3.x GA names not exposed via cloudcode-pa
    "gemini-3.1-pro",
    "gemini-3.1-flash",
    "gemini-3-pro",
    "gemini-3-flash",
    // 3.1-flash-preview was briefly auto-seeded but real-device dogfooding shows
    // cloudcode-pa returns 404 for it. Only the pro variant of the 3.1 preview
    // is live so far.
    "gemini-3.1-flash-preview",
    // 2.0 family — long since retired upstream
    "gemini-2.0-flash",
    "gemini-2.0-flash-exp",
    // 2.5 family — exists on cloudcode-pa but 429s on ghost-project accounts which
    // covers ~all OAuth users we've seen so far. Fresh Pro / FREE tier accounts can
    // still add them manually via "+ add model" in the editor.
    "gemini-2.5-pro",
    "gemini-2.5-flash",
    "gemini-2.5-flash-lite",
)
