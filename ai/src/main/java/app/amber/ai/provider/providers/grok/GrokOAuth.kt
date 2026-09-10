package app.amber.ai.provider.providers.grok

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import app.amber.ai.provider.Model
import app.amber.ai.provider.providers.OAuthTokenSecureStore
import app.amber.ai.util.json
import app.amber.common.http.await
import app.amber.common.oauth.LoopbackOAuthCallbackServer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.uuid.Uuid

/** Grok CLI OAuth 与 CLI proxy 的公开协议常量（与 iOS 对端保持一致）。 */
const val GROK_OAUTH_CLIENT_ID = "********-****-****-****-************"
const val GROK_OAUTH_AUTHORIZATION_ENDPOINT = "https://auth.x.ai/oauth2/authorize"
const val GROK_OAUTH_TOKEN_ENDPOINT = "https://auth.x.ai/oauth2/token"
const val GROK_CLI_PROXY_BASE_URL = "https://cli-chat-proxy.grok.com/v1"
const val GROK_CLI_PROXY_HOST = "cli-chat-proxy.grok.com"
const val GROK_OAUTH_REDIRECT_URI = "http://127.0.0.1:8787/callback"
private const val GROK_OAUTH_SCOPE = "openid profile email offline_access grok-cli:access api:access conversations:read conversations:write"
private const val PREF_NAME = "grok_web_oauth"
private const val KEY_ALIAS = "amberagent_grok_web_oauth"
private const val REFRESH_SKEW_MS = 2 * 60 * 1000L
private const val FALLBACK_TOKEN_LIFETIME_MS = 60 * 60 * 1000L
private const val AUTH_TIMEOUT_MS = 5 * 60 * 1000L
private const val TAG = "GrokOAuth"

/** provider UUID 绑定的凭据；只序列化进 Keystore 保护的 OAuthTokenSecureStore。 */
@Serializable
data class GrokOAuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMillis: Long,
    val idToken: String? = null,
    val email: String? = null,
)

enum class GrokAuthStatusCode(val wireValue: String) {
    NOT_SIGNED_IN("not_signed_in"), TOKEN_MISSING("token_missing"), TOKEN_EXPIRED("token_expired"), READY("ready"),
}

data class GrokAuthStatus(val code: GrokAuthStatusCode, val usable: Boolean) {
    companion object {
        fun from(tokens: GrokOAuthTokens?, nowMillis: Long): GrokAuthStatus {
            if (tokens == null) return GrokAuthStatus(GrokAuthStatusCode.NOT_SIGNED_IN, false)
            if (tokens.accessToken.isBlank()) return GrokAuthStatus(GrokAuthStatusCode.TOKEN_MISSING, false)
            if (tokens.expiresAtMillis <= nowMillis) {
                return GrokAuthStatus(GrokAuthStatusCode.TOKEN_EXPIRED, !tokens.refreshToken.isNullOrBlank())
            }
            return GrokAuthStatus(GrokAuthStatusCode.READY, true)
        }
    }
}

class GrokAuthStore(context: Context) {
    private val store = OAuthTokenSecureStore(context, PREF_NAME, KEY_ALIAS)
    private val backupStore = OAuthTokenSecureStore(context, "${PREF_NAME}_backup", "${KEY_ALIAS}_backup")

    fun get(providerId: Uuid): GrokOAuthTokens? {
        val raw = store.get(providerId.toString()) ?: return null
        return runCatching { json.decodeFromString<GrokOAuthTokens>(raw) }.getOrNull()
            .also { if (it == null) store.remove(providerId.toString()) }
    }

    fun save(providerId: Uuid, tokens: GrokOAuthTokens) {
        store.put(providerId.toString(), json.encodeToString(tokens))
    }

    fun exportRawJsonForSync(): String = json.encodeToString(store.exportPlainValues())
    fun restoreRawJsonFromSync(raw: String) {
        val values = runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrNull() ?: return
        store.replacePlainValues(values)
    }

    fun exportBackupRawJsonForSync(): String = json.encodeToString(backupStore.exportPlainValues())
    fun restoreBackupRawJsonFromSync(raw: String) {
        val values = runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrNull() ?: return
        backupStore.replacePlainValues(values)
    }

    fun clear(providerId: Uuid) {
        clearTokens(providerId)
        clearBackup(providerId)
    }

    fun clearTokens(providerId: Uuid) = store.remove(providerId.toString())

    /** endpoint 备份同样只放在 Keystore 保护的 side-table，退出后供 UI 恢复。 */
    fun getBackup(providerId: Uuid): String? = backupStore.get(providerId.toString())
    fun saveBackup(providerId: Uuid, baseUrl: String) {
        // Legacy sync archives may contain only the managed endpoint.
        if (baseUrl == GROK_CLI_PROXY_BASE_URL) return
        if (backupStore.get(providerId.toString()) == null) backupStore.put(providerId.toString(), baseUrl)
    }
    fun clearBackup(providerId: Uuid) = backupStore.remove(providerId.toString())
}

/** Grok OAuth client：PKCE、回环授权、single-flight 刷新和 generation 竞态保护。 */
class GrokOAuthClient(private val httpClient: OkHttpClient, private val authStore: GrokAuthStore) {
    private companion object {
        val refreshLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
        val generations = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val generationLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }

    fun cached(providerId: Uuid): GrokOAuthTokens? = authStore.get(providerId)
    fun authStatus(providerId: Uuid, nowMillis: Long = System.currentTimeMillis()) =
        GrokAuthStatus.from(authStore.get(providerId), nowMillis)
    /** Stable per-provider epoch for callers that suspend between login and commit. */
    fun sessionGeneration(providerId: Uuid): Long = generation(providerId)

    fun logout(providerId: Uuid) {
        synchronized(generationLock(providerId)) {
            val key = providerId.toString()
            generations[key] = (generations[key] ?: 0L) + 1L
            // 登出只清 token；endpoint backup 由设置页在恢复原 endpoint 后单独清理。
            authStore.clearTokens(providerId)
        }
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

    private fun saveIfCurrent(id: Uuid, expectedGeneration: Long, tokens: GrokOAuthTokens): Boolean =
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
            authStore.clearTokens(id)
            true
        }

    private fun generationLock(id: Uuid): Any =
        generationLocks.getOrPut(id.toString()) { Any() }

    private fun staleSession(): Nothing = error("Grok OAuth 会话已变更，已丢弃过期响应，请重试。")

    suspend fun authorize(context: Context, providerId: Uuid): GrokOAuthTokens {
        val server = LoopbackOAuthCallbackServer(port = 8787)
        val loginGeneration = beginAuthorization(providerId)
        return server.use { running ->
            val verifier = randomBytes(64)
            val state = randomBytes(24)
            val challenge = Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
            )
            val url = buildString {
                append(GROK_OAUTH_AUTHORIZATION_ENDPOINT).append('?')
                val values = listOf(
                    "response_type" to "code", "client_id" to GROK_OAUTH_CLIENT_ID,
                    "redirect_uri" to GROK_OAUTH_REDIRECT_URI, "scope" to GROK_OAUTH_SCOPE,
                    "state" to state, "nonce" to randomBytes(16), "code_challenge" to challenge,
                    "code_challenge_method" to "S256", "referrer" to "grok-cli",
                )
                append(values.joinToString("&") { (key, value) ->
                    "$key=${URLEncoder.encode(value, "UTF-8")}"
                })
            }
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val callback = withTimeoutOrNull(AUTH_TIMEOUT_MS) { running.awaitCallback() }
                ?: error("Grok 授权超时，请重试。")
            require(callback.isSuccess) { "Grok 授权失败：${callback.error.orEmpty()} ${callback.errorDescription.orEmpty()}".trim() }
            require(callback.state == state) { "Grok OAuth state 不一致，请重试。" }
            val tokens = exchange(callback.code!!, verifier)
            if (!saveIfCurrent(providerId, loginGeneration, tokens)) staleSession()
            tokens
        }
    }

    suspend fun getValidAccessToken(providerId: Uuid, forceRefresh: Boolean = false): String {
        val current = authStore.get(providerId) ?: error("尚未登录 Grok，请先登录。")
        if (!forceRefresh && current.expiresAtMillis - System.currentTimeMillis() > REFRESH_SKEW_MS) {
            return current.accessToken
        }
        return refresh(providerId).accessToken
    }

    suspend fun refresh(providerId: Uuid): GrokOAuthTokens = refreshLocks.getOrPut(providerId.toString()) { Mutex() }.withLock {
        val generationAtStart = generation(providerId)
        val current = authStore.get(providerId) ?: error("Grok OAuth token 不存在，请重新登录。")
        val refreshToken = current.refreshToken?.takeIf { it.isNotBlank() }
            ?: error("Grok OAuth 没有 refresh_token，请重新登录。")
        val body = FormBody.Builder().add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken).add("client_id", GROK_OAUTH_CLIENT_ID).build()
        val response = httpClient.newCall(Request.Builder().url(GROK_OAUTH_TOKEN_ENDPOINT).post(body).build()).await()
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            if (runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull == "invalid_grant" }.getOrDefault(false)) {
                invalidateIfCurrent(providerId, generationAtStart)
            }
            error("Grok OAuth 刷新失败：HTTP ${response.code}")
        }
        val merged = parseTokens(text, current)
        // logout 后到达的旧刷新响应不得复活会话；generation 变化时丢弃结果。
        if (!saveIfCurrent(providerId, generationAtStart, merged)) staleSession()
        merged
    }

    suspend fun listModels(providerId: Uuid): List<Model> {
        var response = modelsRequest(getValidAccessToken(providerId))
        if (response.code == 401) {
            response.close()
            response = modelsRequest(getValidAccessToken(providerId, forceRefresh = true))
        }
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) error("Grok 模型请求失败：HTTP ${response.code}")
        return parseGrokModels(body).ifEmpty { defaultGrokOAuthModels() }
    }

    private suspend fun modelsRequest(token: String) = httpClient.newCall(
        Request.Builder().url("$GROK_CLI_PROXY_BASE_URL/models").header("Authorization", "Bearer $token")
            .header("Accept", "application/json").grokHeaders().get().build()
    ).await()

    private suspend fun exchange(code: String, verifier: String): GrokOAuthTokens {
        val body = FormBody.Builder().add("grant_type", "authorization_code").add("code", code)
            .add("code_verifier", verifier).add("redirect_uri", GROK_OAUTH_REDIRECT_URI)
            .add("client_id", GROK_OAUTH_CLIENT_ID).build()
        val response = httpClient.newCall(Request.Builder().url(GROK_OAUTH_TOKEN_ENDPOINT).post(body).build()).await()
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) error("Grok OAuth 令牌交换失败：HTTP ${response.code}")
        return parseTokens(text, null)
    }

    private fun parseTokens(raw: String, fallback: GrokOAuthTokens?): GrokOAuthTokens {
        val obj = json.parseToJsonElement(raw).jsonObject
        val access = obj["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("Grok OAuth 响应缺少 access_token")
        val expires = obj["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val idToken = obj["id_token"]?.jsonPrimitive?.contentOrNull ?: fallback?.idToken
        return GrokOAuthTokens(access, obj["refresh_token"]?.jsonPrimitive?.contentOrNull ?: fallback?.refreshToken,
            System.currentTimeMillis() + (expires ?: FALLBACK_TOKEN_LIFETIME_MS / 1000L) * 1000L,
            idToken, fallback?.email ?: idToken?.let(::decodeEmail))
    }

    private fun randomBytes(size: Int): String = ByteArray(size).also { SecureRandom().nextBytes(it) }
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }

    private fun decodeEmail(jwt: String): String? = runCatching {
        val part = jwt.split('.')[1]
        val bytes = Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject["email"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
}

private fun okhttp3.Request.Builder.grokHeaders() = apply {
    header("User-Agent", "grok-shell/0.2.101 (android; arm64)")
    header("x-grok-client-identifier", "grok-shell")
    header("x-grok-client-version", "0.2.101")
    header("x-grok-client-mode", "interactive")
    header("X-XAI-Token-Auth", "xai-grok-cli")
    header("x-authenticateresponse", "authenticate-response")
}

fun parseGrokModels(raw: String): List<Model> = runCatching {
    val root = json.parseToJsonElement(raw)
    val array = when {
        root is JsonObject -> root["data"]?.jsonArray ?: root["models"]?.jsonArray ?: JsonArray(emptyList())
        root is JsonArray -> root
        else -> JsonArray(emptyList())
    }
    array.mapNotNull { item ->
        val obj = item.jsonObject
        val id = (obj["id"] ?: obj["model"])?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (id.isBlank()) null else Model(modelId = id, displayName =
            (obj["name"] ?: obj["display_name"])?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { id })
    }
}.getOrDefault(emptyList())

fun defaultGrokOAuthModels(): List<Model> = listOf(
    Model(modelId = "grok-4.6", displayName = "Grok 4.6"),
    Model(modelId = "grok-4.5", displayName = "Grok 4.5"),
    Model(modelId = "grok-4.20-0309-reasoning", displayName = "Grok 4.20 Reasoning"),
    Model(modelId = "grok-build", displayName = "Grok Build"),
)
