package me.rerere.rikkahub.data.agent.webmount.oauth

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.agent.webmount.core.WebMountOAuthToken
import java.net.URLEncoder

/**
 * 飞书 (Feishu/Lark) OAuth provider.
 *
 * Uses the v2 OAuth endpoint (`/open-apis/authen/v2/oauth/token`) which
 * accepts the standard OAuth body shape. PKCE is supported via `code_verifier`.
 *
 * The redirect URI registered on the user's app in the 飞书 open platform
 * console must match `amberagent://oauth/feishu` (or whatever the user puts
 * in [OAuthAppCredentials.redirectUri]). Confidential apps include
 * `client_secret`; public/marketplace apps with PKCE may leave it null.
 */
object FeishuOAuthProvider : OAuthProvider {

    override val id: String = "feishu"
    override val displayName: String = "飞书"

    private const val AUTHORIZATION_ENDPOINT = "https://accounts.feishu.cn/open-apis/authen/v1/authorize"
    private const val TOKEN_ENDPOINT = "https://open.feishu.cn/open-apis/authen/v2/oauth/token"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun buildAuthorizationUrl(
        credentials: OAuthAppCredentials,
        state: String,
        codeChallenge: String,
    ): String {
        val redirect = credentials.redirectUri ?: defaultRedirectUri
        // 飞书 v1 authorize endpoint uses BOTH `app_id` and `client_id` in the wild
        // — older versions accept only `app_id`, newer OAuth-standard ones accept
        // `client_id`. We send the same value under both names so either path
        // works; the endpoint ignores the one it doesn't recognize.
        val pairs = mutableListOf(
            "app_id" to credentials.appId,
            "client_id" to credentials.appId,
            "redirect_uri" to redirect,
            "response_type" to "code",
            "state" to state,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
        )
        credentials.scope?.takeIf { it.isNotBlank() }?.let { pairs.add("scope" to it) }
        val qs = pairs.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        return "$AUTHORIZATION_ENDPOINT?$qs"
    }

    override suspend fun exchangeCode(
        credentials: OAuthAppCredentials,
        code: String,
        codeVerifier: String,
        http: HttpClient,
    ): WebMountOAuthToken {
        // See [buildAuthorizationUrl] — send both app_id/app_secret AND
        // client_id/client_secret so v1 and v2 token endpoints both work.
        val body = buildJsonObject {
            put("grant_type", "authorization_code")
            put("app_id", credentials.appId)
            put("client_id", credentials.appId)
            credentials.appSecret?.let {
                put("app_secret", it)
                put("client_secret", it)
            }
            put("code", code)
            put("code_verifier", codeVerifier)
            put("redirect_uri", credentials.redirectUri ?: defaultRedirectUri)
        }
        return tokenRequest(http, body, credentials)
    }

    override suspend fun refresh(
        credentials: OAuthAppCredentials,
        refreshToken: String,
        http: HttpClient,
    ): WebMountOAuthToken {
        val body = buildJsonObject {
            put("grant_type", "refresh_token")
            put("app_id", credentials.appId)
            put("client_id", credentials.appId)
            credentials.appSecret?.let {
                put("app_secret", it)
                put("client_secret", it)
            }
            put("refresh_token", refreshToken)
        }
        return tokenRequest(http, body, credentials)
    }

    override fun setupHint(): String =
        "1. 去飞书开放平台 (open.feishu.cn) 创建一个应用,在「安全设置 → 重定向 URL」里加入 amberagent://oauth/feishu。" +
            "2. 把 App ID + App Secret 复制到上方输入框。" +
            "3. 在「权限管理」里开启所需的云文档 scope (如 docs:doc:read / docs:doc:write)。"

    // ----------------------------------------------------------------------

    private suspend fun tokenRequest(
        http: HttpClient,
        body: JsonObject,
        credentials: OAuthAppCredentials,
    ): WebMountOAuthToken {
        val response = http.post(TOKEN_ENDPOINT) {
            contentType(ContentType.Application.Json)
            headers { append(HttpHeaders.Accept, "application/json") }
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        require(response.status.isSuccess()) {
            "飞书 token endpoint returned ${response.status.value}: ${text.take(500)}"
        }
        val parsed = json.parseToJsonElement(text).jsonObject
        // The v2 endpoint typically returns the OAuth body at the top level:
        //   {access_token, refresh_token, expires_in, refresh_expires_in, scope}
        // The v1 wrapped shape {code, msg, data:{...}} is rare on v2 but defended against.
        val payload = parsed["data"]?.jsonObject ?: parsed
        val errCode = parsed["code"]?.jsonPrimitive?.intOrNull
        if (errCode != null && errCode != 0) {
            val msg = parsed["msg"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            error("飞书 OAuth error code=$errCode msg=$msg")
        }
        val accessToken = payload["access_token"]?.jsonPrimitive?.contentOrNull
            ?: error("missing access_token in 飞书 response: ${text.take(400)}")
        val now = System.currentTimeMillis()
        val expiresIn = payload["expires_in"]?.jsonPrimitive?.longOrNull ?: 7200L
        return WebMountOAuthToken(
            provider = id,
            accessToken = accessToken,
            refreshToken = payload["refresh_token"]?.jsonPrimitive?.contentOrNull,
            scope = payload["scope"]?.jsonPrimitive?.contentOrNull ?: credentials.scope,
            tokenType = payload["token_type"]?.jsonPrimitive?.contentOrNull ?: "Bearer",
            expiresAtMs = now + expiresIn * 1000L,
            acquiredAtMs = now,
            userOpenId = payload["open_id"]?.jsonPrimitive?.contentOrNull,
        )
    }
}
