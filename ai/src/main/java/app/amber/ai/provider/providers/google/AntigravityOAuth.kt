package app.amber.ai.provider.providers.google

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import app.amber.ai.BuildConfig
import app.amber.ai.provider.Model
import app.amber.ai.provider.providers.OAuthTokenSecureStore
import app.amber.ai.util.json
import app.amber.common.http.await
import app.amber.common.oauth.LoopbackOAuthCallbackServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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

/** Google Antigravity 独立登录协议常量；不得与 Gemini Code Assist 身份混用。 */
const val ANTIGRAVITY_OAUTH_CLIENT_ID = BuildConfig.ANTIGRAVITY_OAUTH_CLIENT_ID
const val ANTIGRAVITY_OAUTH_CLIENT_SECRET = BuildConfig.ANTIGRAVITY_OAUTH_CLIENT_SECRET
const val ANTIGRAVITY_OAUTH_AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
const val ANTIGRAVITY_OAUTH_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
const val ANTIGRAVITY_OAUTH_REDIRECT_URI = "http://localhost:51121/oauth-callback"
const val ANTIGRAVITY_CLOUDCODE_BASE_URL = "https://daily-cloudcode-pa.googleapis.com"
const val ANTIGRAVITY_ORIGIN = "https://antigravity.google"
private const val ANTIGRAVITY_SCOPE = "openid https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile https://www.googleapis.com/auth/cclog https://www.googleapis.com/auth/experimentsandconfigs"
private const val ANTIGRAVITY_PREF_NAME = "antigravity_oauth"
private const val ANTIGRAVITY_KEY_ALIAS = "amberagent_antigravity_oauth"
private const val REFRESH_SKEW_MS = 2 * 60 * 1000L
private const val AUTH_TIMEOUT_MS = 5 * 60 * 1000L
private const val FALLBACK_TOKEN_LIFETIME_MS = 60 * 60 * 1000L
private const val LRO_POLL_INTERVAL_MS = 5_000L
private const val MAX_LRO_POLL_ITERATIONS = 12
private const val TAG = "AntigravityOAuth"

@Serializable
data class AntigravityOAuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMillis: Long,
    val idToken: String? = null,
    val email: String? = null,
    val projectId: String? = null,
    val onboardedTier: String? = null,
)

enum class AntigravityAuthStatusCode(val wireValue: String) {
    NOT_SIGNED_IN("not_signed_in"), TOKEN_MISSING("token_missing"), TOKEN_EXPIRED("token_expired"),
    PROJECT_MISSING("project_missing"), ONBOARDING_REQUIRED("onboarding_required"),
    CLIENT_UNAVAILABLE("client_unavailable"), READY("ready"),
}

data class AntigravityAuthStatus(val code: AntigravityAuthStatusCode, val usable: Boolean) {
    companion object {
        fun from(tokens: AntigravityOAuthTokens?, nowMillis: Long): AntigravityAuthStatus {
            if (tokens == null) return AntigravityAuthStatus(AntigravityAuthStatusCode.NOT_SIGNED_IN, false)
            if (tokens.accessToken.isBlank()) return AntigravityAuthStatus(AntigravityAuthStatusCode.TOKEN_MISSING, false)
            if (tokens.expiresAtMillis <= nowMillis && tokens.refreshToken.isNullOrBlank()) {
                return AntigravityAuthStatus(AntigravityAuthStatusCode.TOKEN_EXPIRED, false)
            }
            if (tokens.projectId.isNullOrBlank()) return AntigravityAuthStatus(AntigravityAuthStatusCode.PROJECT_MISSING, true)
            if (tokens.onboardedTier.isNullOrBlank()) return AntigravityAuthStatus(AntigravityAuthStatusCode.ONBOARDING_REQUIRED, true)
            if (tokens.expiresAtMillis <= nowMillis) return AntigravityAuthStatus(AntigravityAuthStatusCode.TOKEN_EXPIRED, true)
            return AntigravityAuthStatus(AntigravityAuthStatusCode.READY, true)
        }
        fun clientUnavailable() = AntigravityAuthStatus(AntigravityAuthStatusCode.CLIENT_UNAVAILABLE, false)
    }
}

class AntigravityAuthStore(context: Context) {
    private val store = OAuthTokenSecureStore(context, ANTIGRAVITY_PREF_NAME, ANTIGRAVITY_KEY_ALIAS)
    fun get(providerId: Uuid): AntigravityOAuthTokens? = store.get(providerId.toString())?.let {
        runCatching { json.decodeFromString<AntigravityOAuthTokens>(it) }.getOrNull()
    }?.also { if (it == null) store.remove(providerId.toString()) }
    fun save(providerId: Uuid, tokens: AntigravityOAuthTokens) = store.put(providerId.toString(), json.encodeToString(tokens))
    fun clear(providerId: Uuid) = store.remove(providerId.toString())
    fun exportRawJsonForSync(): String = json.encodeToString(store.exportPlainValues())
    fun restoreRawJsonFromSync(raw: String) { runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrNull()?.let(store::replacePlainValues) }
}

/** Antigravity OAuth client: per-provider refresh single-flight and logout generation. */
class AntigravityOAuthClient(private val httpClient: OkHttpClient, private val authStore: AntigravityAuthStore) {
    private companion object {
        val refreshLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
        val generations = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val generationLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }
    fun cached(providerId: Uuid) = authStore.get(providerId)
    fun authStatus(providerId: Uuid, nowMillis: Long = System.currentTimeMillis()) =
        if (ANTIGRAVITY_OAUTH_CLIENT_ID.isBlank() || ANTIGRAVITY_OAUTH_CLIENT_SECRET.isBlank()) {
            AntigravityAuthStatus.clientUnavailable()
        } else {
            AntigravityAuthStatus.from(authStore.get(providerId), nowMillis)
        }
    /** Stable per-provider epoch for callers that suspend between login and commit. */
    fun sessionGeneration(providerId: Uuid): Long = generation(providerId)
    fun logout(providerId: Uuid) = synchronized(generationLock(providerId)) {
        val key = providerId.toString()
        generations[key] = (generations[key] ?: 0L) + 1L
        authStore.clear(providerId)
    }

    private fun generation(id: Uuid): Long = synchronized(generationLock(id)) {
        generations[id.toString()] ?: 0L
    }

    private fun beginAuthorization(id: Uuid): Long = synchronized(generationLock(id)) {
        val key = id.toString()
        val next = (generations[key] ?: 0L) + 1L
        generations[key] = next
        next
    }

    private fun saveIfCurrent(id: Uuid, expectedGeneration: Long, tokens: AntigravityOAuthTokens): Boolean =
        synchronized(generationLock(id)) {
            if (generations[id.toString()] ?: 0L != expectedGeneration) return@synchronized false
            authStore.save(id, tokens)
            true
        }

    private fun invalidateIfCurrent(id: Uuid, expectedGeneration: Long): Boolean =
        synchronized(generationLock(id)) {
            val key = id.toString()
            if (generations[key] ?: 0L != expectedGeneration) return@synchronized false
            generations[key] = expectedGeneration + 1L
            authStore.clear(id)
            true
        }

    private fun generationLock(id: Uuid): Any =
        generationLocks.getOrPut(id.toString()) { Any() }

    private fun staleSession(): Nothing = error("Antigravity OAuth 会话已变更，已丢弃过期响应，请重试。")

    private fun requireClientConfiguration() {
        check(ANTIGRAVITY_OAUTH_CLIENT_ID.isNotBlank() && ANTIGRAVITY_OAUTH_CLIENT_SECRET.isNotBlank()) {
            "此构建未配置 Antigravity OAuth 客户端。"
        }
    }

    suspend fun authorize(context: Context, providerId: Uuid): AntigravityOAuthTokens {
        requireClientConfiguration()
        val server = LoopbackOAuthCallbackServer(port = 51121, callbackPath = "/oauth-callback")
        val loginGeneration = beginAuthorization(providerId)
        return server.use { running ->
            val verifier = generateCodeVerifier()
            val state = randomState()
            val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val params = listOf("response_type" to "code", "client_id" to ANTIGRAVITY_OAUTH_CLIENT_ID, "redirect_uri" to ANTIGRAVITY_OAUTH_REDIRECT_URI, "scope" to ANTIGRAVITY_SCOPE, "state" to state, "code_challenge" to challenge, "code_challenge_method" to "S256", "access_type" to "offline", "prompt" to "consent")
            val url = ANTIGRAVITY_OAUTH_AUTHORIZATION_ENDPOINT + "?" + params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val callback = withTimeoutOrNull(AUTH_TIMEOUT_MS) { running.awaitCallback() } ?: error("Antigravity 授权超时，请重试。")
            require(callback.isSuccess) { "Antigravity 授权失败：${callback.error.orEmpty()} ${callback.errorDescription.orEmpty()}".trim() }
            require(callback.state == state) { "Antigravity OAuth state 不一致，请重试。" }
            val tokens = exchange(callback.code!!, verifier)
            if (!saveIfCurrent(providerId, loginGeneration, tokens)) staleSession()
            ensureOnboarded(providerId)
        }
    }

    suspend fun getValidAccessToken(providerId: Uuid, forceRefresh: Boolean = false): String {
        val current = authStore.get(providerId) ?: error("尚未登录 Antigravity，请先登录。")
        if (!forceRefresh && current.expiresAtMillis - System.currentTimeMillis() > REFRESH_SKEW_MS) return current.accessToken
        return refresh(providerId).accessToken
    }

    suspend fun refresh(providerId: Uuid): AntigravityOAuthTokens = refreshLocks.getOrPut(providerId.toString()) { Mutex() }.withLock {
        requireClientConfiguration()
        val generationAtStart = generation(providerId)
        val current = authStore.get(providerId) ?: error("Antigravity OAuth token 不存在，请重新登录。")
        val refreshToken = current.refreshToken?.takeIf { it.isNotBlank() } ?: error("Antigravity OAuth 没有 refresh_token，请重新登录。")
        val body = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refreshToken).add("client_id", ANTIGRAVITY_OAUTH_CLIENT_ID).add("client_secret", ANTIGRAVITY_OAUTH_CLIENT_SECRET).build()
        val response = httpClient.newCall(Request.Builder().url(ANTIGRAVITY_OAUTH_TOKEN_ENDPOINT).post(body).build()).await()
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            if (text.contains("invalid_grant", ignoreCase = true)) {
                invalidateIfCurrent(providerId, generationAtStart)
            }
            error("Antigravity OAuth 刷新失败：HTTP ${response.code}")
        }
        val merged = parseTokens(text, current)
        if (!saveIfCurrent(providerId, generationAtStart, merged)) staleSession()
        merged
    }

    suspend fun requireUsableSession(providerId: Uuid): AntigravityOAuthTokens {
        authStore.get(providerId) ?: error("尚未登录 Antigravity，请先在设置里登录。")
        getValidAccessToken(providerId)
        val onboarded = ensureOnboarded(providerId)
        val refreshed = getValidAccessToken(providerId)
        val resolved = authStore.get(providerId)?.copy(accessToken = refreshed) ?: onboarded.copy(accessToken = refreshed)
        require(AntigravityAuthStatus.from(resolved, System.currentTimeMillis()).code == AntigravityAuthStatusCode.READY) { "Antigravity onboarding 尚未完成，请稍后重试。" }
        return resolved
    }

    suspend fun ensureOnboarded(providerId: Uuid): AntigravityOAuthTokens {
        val generationAtStart = generation(providerId)
        val current = authStore.get(providerId) ?: error("尚未登录 Antigravity，无法 onboard。")
        if (!current.projectId.isNullOrBlank() && !current.onboardedTier.isNullOrBlank()) {
            if (generation(providerId) != generationAtStart) staleSession()
            return current
        }
        // Onboarding spans multiple network calls and an LRO poll; a logout
        // during that window must stop late responses from writing tokens
        // back into the store (same guard as the refresh path).
        fun persistIfCurrent(tokens: AntigravityOAuthTokens): AntigravityOAuthTokens {
            if (!saveIfCurrent(providerId, generationAtStart, tokens)) staleSession()
            return tokens
        }
        val token = getValidAccessToken(providerId)
        val load = cloudJson(token, ":loadCodeAssist", buildJsonObject { putJsonObject("metadata") { put("ideType", "ANTIGRAVITY"); put("platform", "ANDROID"); put("pluginType", "GEMINI") } })
        val project = load["cloudaicompanionProject"]?.let { element ->
            element.jsonPrimitive.contentOrNull
        } ?: load["cloudaicompanionProject"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        val tier = load["currentTier"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        if (!project.isNullOrBlank() && !tier.isNullOrBlank()) {
            return persistIfCurrent(current.copy(projectId = project, onboardedTier = tier))
        }
        val allowed = load["allowedTiers"]?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.orEmpty()
        val chosen = allowed.firstOrNull { it == "FREE" } ?: allowed.firstOrNull() ?: "FREE"
        val body = buildJsonObject { put("tierId", chosen); putJsonObject("metadata") { put("ideType", "ANTIGRAVITY"); put("platform", "ANDROID"); put("pluginType", "GEMINI") }; if (chosen != "FREE" && !project.isNullOrBlank()) put("cloudaicompanionProject", project) }
        val resolved = pollOnboard(token, cloudJson(token, ":onboardUser", body)) ?: error("onboardUser 没有返回 cloudaicompanionProject。")
        return persistIfCurrent(current.copy(projectId = resolved, onboardedTier = chosen))
    }

    private suspend fun pollOnboard(token: String, initial: JsonObject): String? {
        var current = initial
        repeat(MAX_LRO_POLL_ITERATIONS) {
            if (current["done"]?.jsonPrimitive?.booleanOrNull == true) {
                current["error"]?.let { error("onboardUser LRO 报错：${it.toString().take(300)}") }
                return current["response"]?.jsonObject?.get("cloudaicompanionProject")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            }
            val name = current["name"]?.jsonPrimitive?.contentOrNull ?: return null
            delay(LRO_POLL_INTERVAL_MS)
            current = getCloudJson(token, name)
        }
        error("onboardUser LRO 轮询超时，请稍后重试。")
    }

    fun streamGenerateContent(accessToken: String, modelId: String, projectId: String, innerRequest: JsonObject): Request = cloudRequest(accessToken, "/v1internal:streamGenerateContent?alt=sse", modelId, projectId, innerRequest)
    fun generateContent(accessToken: String, modelId: String, projectId: String, innerRequest: JsonObject): Request = cloudRequest(accessToken, "/v1internal:generateContent", modelId, projectId, innerRequest)

    suspend fun listModels(providerId: Uuid): List<Model> {
        val token = getValidAccessToken(providerId)
        val request = Request.Builder().url("$ANTIGRAVITY_CLOUDCODE_BASE_URL/v1internal:fetchAvailableModels").header("Authorization", "Bearer $token").header("User-Agent", ANTIGRAVITY_USER_AGENT).header("Client-Metadata", ANTIGRAVITY_CLIENT_METADATA).post(buildJsonObject { authStore.get(providerId)?.projectId?.let { put("project", it) } }.toString().toRequestBody("application/json".toMediaType())).build()
        val response = httpClient.newCall(request).await(); val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) error("Antigravity 模型请求失败：HTTP ${response.code}")
        return parseAntigravityModels(body).ifEmpty { defaultAntigravityModels() }
    }

    private fun cloudRequest(token: String, path: String, model: String, project: String, inner: JsonObject) = Request.Builder().url("$ANTIGRAVITY_CLOUDCODE_BASE_URL$path").header("Authorization", "Bearer $token").header("Content-Type", "application/json").header("User-Agent", ANTIGRAVITY_USER_AGENT).header("Client-Metadata", ANTIGRAVITY_CLIENT_METADATA).header("Origin", ANTIGRAVITY_ORIGIN).post(buildJsonObject { put("model", model); put("project", project); put("userAgent", "antigravity"); put("requestType", "agent"); put("requestId", "agent-${Uuid.random()}"); put("request", inner) }.toString().toRequestBody("application/json".toMediaType())).build()
    private suspend fun cloudJson(token: String, method: String, body: JsonObject) = requestJson(Request.Builder().url("$ANTIGRAVITY_CLOUDCODE_BASE_URL/v1internal$method").header("Authorization", "Bearer $token").header("Content-Type", "application/json").header("User-Agent", ANTIGRAVITY_USER_AGENT).header("Client-Metadata", ANTIGRAVITY_CLIENT_METADATA).post(body.toString().toRequestBody("application/json".toMediaType())).build(), "cloudcode $method")
    private suspend fun getCloudJson(token: String, name: String) = requestJson(Request.Builder().url("$ANTIGRAVITY_CLOUDCODE_BASE_URL/v1internal/$name").header("Authorization", "Bearer $token").header("User-Agent", ANTIGRAVITY_USER_AGENT).get().build(), "cloudcode poll")
    private suspend fun requestJson(request: Request, label: String): JsonObject { val r = httpClient.newCall(request).await(); val text = r.body?.string().orEmpty(); if (!r.isSuccessful) error("$label 失败：HTTP ${r.code}"); return json.parseToJsonElement(text).jsonObject }
    private suspend fun exchange(code: String, verifier: String): AntigravityOAuthTokens { val body = FormBody.Builder().add("grant_type", "authorization_code").add("code", code).add("code_verifier", verifier).add("redirect_uri", ANTIGRAVITY_OAUTH_REDIRECT_URI).add("client_id", ANTIGRAVITY_OAUTH_CLIENT_ID).add("client_secret", ANTIGRAVITY_OAUTH_CLIENT_SECRET).build(); val r = httpClient.newCall(Request.Builder().url(ANTIGRAVITY_OAUTH_TOKEN_ENDPOINT).post(body).build()).await(); val text = r.body?.string().orEmpty(); if (!r.isSuccessful) error("Antigravity OAuth 令牌交换失败：HTTP ${r.code}"); return parseTokens(text, null) }
    private fun parseTokens(raw: String, fallback: AntigravityOAuthTokens?): AntigravityOAuthTokens { val o = json.parseToJsonElement(raw).jsonObject; val access = o["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: error("Antigravity OAuth 响应缺少 access_token"); val refresh = o["refresh_token"]?.jsonPrimitive?.contentOrNull ?: fallback?.refreshToken; val id = o["id_token"]?.jsonPrimitive?.contentOrNull ?: fallback?.idToken; val expires = o["expires_in"]?.jsonPrimitive?.longOrNull ?: FALLBACK_TOKEN_LIFETIME_MS / 1000; return AntigravityOAuthTokens(access, refresh, System.currentTimeMillis() + expires * 1000, id, fallback?.email ?: id?.let(::decodeEmail), fallback?.projectId, fallback?.onboardedTier) }
    private fun decodeEmail(jwt: String): String? = runCatching { val bytes = Base64.decode(jwt.split('.')[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP); json.parseToJsonElement(String(bytes)).jsonObject["email"]?.jsonPrimitive?.contentOrNull }.getOrNull()
    private fun randomState() = randomBytes(24)
    private fun generateCodeVerifier() = randomBytes(64)
    private fun randomBytes(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }.let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }
}

const val ANTIGRAVITY_USER_AGENT = "antigravity/1.1.13 (android; arm64)"
const val ANTIGRAVITY_CLIENT_METADATA = "{\"ideType\":\"ANTIGRAVITY\",\"platform\":\"ANDROID\",\"pluginType\":\"GEMINI\"}"

fun parseAntigravityModels(raw: String): List<Model> = runCatching {
    val catalog = json.parseToJsonElement(raw).jsonObject["models"]?.jsonObject ?: return@runCatching emptyList()
    catalog.keys.mapNotNull { rawId ->
        val id = rawId.trim(); val lower = id.lowercase()
        if (id.isBlank() || !lower.startsWith("gemini-") || lower.startsWith("gemini-2.") || lower.contains("image") || lower.contains("thinking") || lower.contains("-agent")) return@mapNotNull null
        val base = listOf("-extra-low", "-low", "-medium", "-high", "-tiered", "-preview", "-latest").firstOrNull { lower.endsWith(it) }?.let { id.dropLast(it.length) } ?: id
        if (base in setOf("gemini-flash", "gemini-pro", "gemini-flash-lite")) null else Model(modelId = base, displayName = base, abilities = listOf(app.amber.ai.provider.ModelAbility.REASONING, app.amber.ai.provider.ModelAbility.TOOL))
    }.distinctBy { it.modelId }.sortedByDescending { it.modelId }
}.getOrDefault(emptyList())

fun defaultAntigravityModels() = listOf("gemini-3.7-flash", "gemini-3.5-flash", "gemini-3.1-pro", "gemini-3-pro", "gemini-3-flash").map { Model(it, it, abilities = listOf(app.amber.ai.provider.ModelAbility.REASONING, app.amber.ai.provider.ModelAbility.TOOL)) }
