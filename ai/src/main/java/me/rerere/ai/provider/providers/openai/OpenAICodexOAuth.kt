package me.rerere.ai.provider.providers.openai

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.util.json
import me.rerere.common.http.await
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.max
import kotlin.uuid.Uuid

const val OPENAI_CODEX_AUTH_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
const val OPENAI_CODEX_AUTH_ISSUER = "https://auth.openai.com"
const val OPENAI_CODEX_DEVICE_URL = "$OPENAI_CODEX_AUTH_ISSUER/codex/device"
const val OPENAI_CHATGPT_BACKEND_BASE_URL = "https://chatgpt.com/backend-api"
const val OPENAI_CODEX_BACKEND_BASE_URL = "https://chatgpt.com/backend-api/codex"
const val OPENAI_CODEX_CLIENT_VERSION = "0.128.0"
const val OPENAI_CODEX_ORIGINATOR = "amberagent_android"

private const val PREF_NAME = "openai_codex_oauth"
private const val OPENAI_CODEX_USAGE_URL = "$OPENAI_CHATGPT_BACKEND_BASE_URL/wham/usage"
private const val REFRESH_SKEW_MS = 2 * 60 * 1000L
private const val DEVICE_LOGIN_TIMEOUT_MS = 15 * 60 * 1000L
private const val FALLBACK_TOKEN_LIFETIME_MS = 45 * 60 * 1000L
private const val JSON_MEDIA_TYPE = "application/json"

@Serializable
data class OpenAICodexAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val accountId: String? = null,
    val email: String? = null,
    val planType: String? = null,
    val idToken: String? = null,
)

data class OpenAICodexDeviceAuthorization(
    val verificationUrl: String,
    val userCode: String,
    val intervalSeconds: Long,
    internal val deviceAuthId: String,
)

data class OpenAICodexUsageStatus(
    val planType: String? = null,
    val fiveHour: OpenAICodexUsageWindow? = null,
    val weekly: OpenAICodexUsageWindow? = null,
)

data class OpenAICodexUsageWindow(
    val usedPercent: Double,
    val windowDurationSeconds: Long? = null,
    val resetsAtEpochSeconds: Long? = null,
)

class OpenAICodexAuthStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun get(providerId: Uuid): OpenAICodexAuthTokens? {
        val raw = prefs.getString(providerId.toString(), null) ?: return null
        return runCatching { json.decodeFromString<OpenAICodexAuthTokens>(raw) }.getOrNull()
    }

    fun save(providerId: Uuid, tokens: OpenAICodexAuthTokens) {
        prefs.edit().putString(providerId.toString(), json.encodeToString(tokens)).apply()
    }

    fun clear(providerId: Uuid) {
        prefs.edit().remove(providerId.toString()).apply()
    }

    fun exportRawJsonForSync(): String {
        val values = prefs.all.mapNotNull { (key, value) ->
            val raw = value as? String ?: return@mapNotNull null
            key to raw
        }.toMap()
        return json.encodeToString(values)
    }

    fun restoreRawJsonFromSync(raw: String) {
        val values = runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrNull() ?: return
        prefs.edit().apply {
            clear()
            values.forEach { (key, value) ->
                putString(key, value)
            }
        }.apply()
    }
}

class OpenAICodexOAuthClient(
    private val client: OkHttpClient,
    private val authStore: OpenAICodexAuthStore,
) {
    private val refreshMutex = Mutex()

    fun getCached(providerId: Uuid): OpenAICodexAuthTokens? = authStore.get(providerId)

    fun logout(providerId: Uuid) {
        authStore.clear(providerId)
    }

    suspend fun requestDeviceCode(): OpenAICodexDeviceAuthorization {
        val requestBody = json.encodeToString(
            UserCodeRequest(clientId = OPENAI_CODEX_AUTH_CLIENT_ID)
        )
        val request = Request.Builder()
            .url("$OPENAI_CODEX_AUTH_ISSUER/api/accounts/deviceauth/usercode")
            .addCodexHeaders()
            .addHeader("Content-Type", JSON_MEDIA_TYPE)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .build()

        val response = client.newCall(request).await()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            error("Codex OAuth device code request failed: ${response.code} ${body.toSafeOAuthError()}")
        }

        val result = json.decodeFromString<UserCodeResponse>(body)
        val userCode = result.userCode.ifBlank { result.userCodeAlias }
        return OpenAICodexDeviceAuthorization(
            verificationUrl = OPENAI_CODEX_DEVICE_URL,
            userCode = userCode,
            intervalSeconds = result.interval?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.toLongOrNull()
                ?.coerceAtLeast(1L)
                ?: 5L,
            deviceAuthId = result.deviceAuthId,
        )
    }

    suspend fun pollDeviceCode(providerId: Uuid, authorization: OpenAICodexDeviceAuthorization): OpenAICodexAuthTokens {
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < DEVICE_LOGIN_TIMEOUT_MS) {
            val requestBody = json.encodeToString(
                TokenPollRequest(
                    deviceAuthId = authorization.deviceAuthId,
                    userCode = authorization.userCode,
                )
            )
            val request = Request.Builder()
                .url("$OPENAI_CODEX_AUTH_ISSUER/api/accounts/deviceauth/token")
                .addCodexHeaders()
                .addHeader("Content-Type", JSON_MEDIA_TYPE)
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
                .build()

            val response = client.newCall(request).await()
            val body = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> {
                    val code = json.decodeFromString<DeviceCodeSuccessResponse>(body)
                    val tokens = exchangeAuthorizationCode(code)
                    authStore.save(providerId, tokens)
                    return tokens
                }

                response.code == 403 || response.code == 404 -> {
                    delay(authorization.intervalSeconds * 1000L)
                }

                else -> {
                    error("Codex OAuth device polling failed: ${response.code} ${body.toSafeOAuthError()}")
                }
            }
        }

        error("Codex OAuth device login timed out after 15 minutes.")
    }

    suspend fun getValidAccessToken(providerId: Uuid, forceRefresh: Boolean = false): String {
        val current = authStore.get(providerId)
            ?: error("Codex OAuth is not signed in. Open the OpenAI provider settings and sign in first.")
        val now = System.currentTimeMillis()
        if (!forceRefresh && current.expiresAtMillis - REFRESH_SKEW_MS > now) {
            return current.accessToken
        }
        return refresh(providerId).accessToken
    }

    suspend fun fetchUsage(providerId: Uuid): OpenAICodexUsageStatus {
        val token = getValidAccessToken(providerId, forceRefresh = false)
        var response = client.newCall(buildUsageRequest(token, authStore.get(providerId))).await()
        if (response.code == 401) {
            response.close()
            val retryToken = getValidAccessToken(providerId, forceRefresh = true)
            response = client.newCall(buildUsageRequest(retryToken, authStore.get(providerId))).await()
        }

        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            error("Codex usage request failed: ${response.code} ${body.toSafeOAuthError()}")
        }
        return parseUsageStatus(body)
    }

    suspend fun refresh(providerId: Uuid): OpenAICodexAuthTokens = refreshMutex.withLock {
        val current = authStore.get(providerId)
            ?: error("Codex OAuth is not signed in. Open the OpenAI provider settings and sign in first.")

        val requestBody = json.encodeToString(
            RefreshTokenRequest(refreshToken = current.refreshToken)
        )
        val request = Request.Builder()
            .url("$OPENAI_CODEX_AUTH_ISSUER/oauth/token")
            .addCodexHeaders()
            .addHeader("Content-Type", JSON_MEDIA_TYPE)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .build()

        val response = client.newCall(request).await()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            error("Codex OAuth token refresh failed: ${response.code} ${body.toSafeOAuthError()}")
        }

        val refresh = json.decodeFromString<RefreshTokenResponse>(body)
        val accessToken = refresh.accessToken ?: current.accessToken
        val idToken = refresh.idToken ?: current.idToken
        val merged = buildTokens(
            accessToken = accessToken,
            refreshToken = refresh.refreshToken ?: current.refreshToken,
            idToken = idToken,
            fallback = current,
        )
        authStore.save(providerId, merged)
        merged
    }

    private suspend fun exchangeAuthorizationCode(code: DeviceCodeSuccessResponse): OpenAICodexAuthTokens {
        val redirectUri = "$OPENAI_CODEX_AUTH_ISSUER/deviceauth/callback"
        val requestBody = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code.authorizationCode)
            .add("redirect_uri", redirectUri)
            .add("client_id", OPENAI_CODEX_AUTH_CLIENT_ID)
            .add("code_verifier", code.codeVerifier)
            .build()
        val request = Request.Builder()
            .url("$OPENAI_CODEX_AUTH_ISSUER/oauth/token")
            .addCodexHeaders()
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .post(requestBody)
            .build()

        val response = client.newCall(request).await()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            error("Codex OAuth token exchange failed: ${response.code} ${body.toSafeOAuthError()}")
        }

        val tokenResponse = json.decodeFromString<TokenExchangeResponse>(body)
        return buildTokens(
            accessToken = tokenResponse.accessToken,
            refreshToken = tokenResponse.refreshToken,
            idToken = tokenResponse.idToken,
        )
    }

    private fun buildUsageRequest(token: String, tokens: OpenAICodexAuthTokens?): Request {
        return Request.Builder()
            .url(OPENAI_CODEX_USAGE_URL)
            .addOpenAICodexBackendHeaders(tokens)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
    }

    private fun Request.Builder.addCodexHeaders(): Request.Builder {
        return this
            .addHeader("Accept", "application/json")
            .addHeader("originator", OPENAI_CODEX_ORIGINATOR)
    }
}

private fun parseUsageStatus(body: String): OpenAICodexUsageStatus {
    val root = json.parseToJsonElement(body).jsonObject
    val rateLimit = root["rate_limit"]?.jsonObject
    return OpenAICodexUsageStatus(
        planType = root.stringOrNull("plan_type"),
        fiveHour = rateLimit?.usageWindow("primary_window"),
        weekly = rateLimit?.usageWindow("secondary_window"),
    )
}

private fun JsonObject.usageWindow(name: String): OpenAICodexUsageWindow? {
    val window = this[name]?.jsonObject ?: return null
    val usedPercent = window.doubleOrNull("used_percent") ?: return null
    val resetAt = window.longOrNull("reset_at")
        ?: window.longOrNull("resets_at")
        ?: window.longOrNull("reset_after_seconds")?.let { (System.currentTimeMillis() / 1000L) + it }
    return OpenAICodexUsageWindow(
        usedPercent = usedPercent,
        windowDurationSeconds = window.longOrNull("limit_window_seconds"),
        resetsAtEpochSeconds = resetAt,
    )
}

private fun JsonObject.stringOrNull(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.longOrNull(name: String): Long? =
    this[name]?.jsonPrimitive?.longOrNull

private fun JsonObject.doubleOrNull(name: String): Double? =
    this[name]?.jsonPrimitive?.doubleOrNull

fun Request.Builder.addOpenAICodexBackendHeaders(tokens: OpenAICodexAuthTokens?): Request.Builder {
    addHeader("Accept", "application/json")
    addHeader("originator", OPENAI_CODEX_ORIGINATOR)
    if (!tokens?.accountId.isNullOrBlank()) {
        addHeader("ChatGPT-Account-Id", tokens.accountId!!)
    }
    return this
}

private fun buildTokens(
    accessToken: String,
    refreshToken: String,
    idToken: String?,
    fallback: OpenAICodexAuthTokens? = null,
): OpenAICodexAuthTokens {
    val accessClaims = accessToken.jwtPayload()
    val idClaims = idToken?.jwtPayload()
    val authClaims = idClaims?.get("https://api.openai.com/auth")?.jsonObject
    val profileClaims = idClaims?.get("https://api.openai.com/profile")?.jsonObject
    val expiresAt = accessClaims
        ?.get("exp")
        ?.jsonPrimitive
        ?.longOrNull
        ?.let { it * 1000L }
        ?: (System.currentTimeMillis() + FALLBACK_TOKEN_LIFETIME_MS)

    return OpenAICodexAuthTokens(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtMillis = max(expiresAt, System.currentTimeMillis() + REFRESH_SKEW_MS),
        accountId = authClaims?.get("chatgpt_account_id")?.jsonPrimitive?.contentOrNull
            ?: fallback?.accountId,
        email = idClaims?.get("email")?.jsonPrimitive?.contentOrNull
            ?: profileClaims?.get("email")?.jsonPrimitive?.contentOrNull
            ?: fallback?.email,
        planType = authClaims?.get("chatgpt_plan_type")?.jsonPrimitive?.contentOrNull
            ?: fallback?.planType,
        idToken = idToken ?: fallback?.idToken,
    )
}

private fun String.jwtPayload() = runCatching {
    val payload = split('.').getOrNull(1) ?: return@runCatching null
    val bytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    json.parseToJsonElement(bytes.decodeToString()).jsonObject
}.getOrNull()

private fun String.toSafeOAuthError(): String {
    if (isBlank()) return ""
    return runCatching {
        val obj = json.parseToJsonElement(this).jsonObject
        obj["error_description"]?.jsonPrimitive?.contentOrNull
            ?: obj["error"]?.jsonPrimitive?.contentOrNull
            ?: obj["message"]?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: take(240)
}

@Serializable
private data class UserCodeRequest(
    @SerialName("client_id")
    val clientId: String,
)

@Serializable
private data class UserCodeResponse(
    @SerialName("device_auth_id")
    val deviceAuthId: String,
    @SerialName("user_code")
    val userCode: String = "",
    @SerialName("usercode")
    val userCodeAlias: String = "",
    val interval: kotlinx.serialization.json.JsonElement? = null,
)

@Serializable
private data class TokenPollRequest(
    @SerialName("device_auth_id")
    val deviceAuthId: String,
    @SerialName("user_code")
    val userCode: String,
)

@Serializable
private data class DeviceCodeSuccessResponse(
    @SerialName("authorization_code")
    val authorizationCode: String,
    @SerialName("code_challenge")
    val codeChallenge: String,
    @SerialName("code_verifier")
    val codeVerifier: String,
)

@Serializable
private data class TokenExchangeResponse(
    @SerialName("id_token")
    val idToken: String,
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String,
)

@Serializable
private data class RefreshTokenRequest(
    @SerialName("client_id")
    val clientId: String = OPENAI_CODEX_AUTH_CLIENT_ID,
    @SerialName("grant_type")
    val grantType: String = "refresh_token",
    @SerialName("refresh_token")
    val refreshToken: String,
)

@Serializable
private data class RefreshTokenResponse(
    @SerialName("id_token")
    val idToken: String? = null,
    @SerialName("access_token")
    val accessToken: String? = null,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
)
