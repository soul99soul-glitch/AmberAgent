package app.amber.feature.webmount.oauth

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import app.amber.common.oauth.LoopbackOAuthCallbackServer
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
import app.amber.feature.webmount.core.WebMountOAuthToken
import app.amber.agent.R
import java.net.URLEncoder

/**
 * 飞书 (Feishu/Lark) OAuth provider.
 *
 * Uses the v2 OAuth endpoint (`/open-apis/authen/v2/oauth/token`) which
 * accepts the standard OAuth body shape. PKCE is supported via `code_verifier`.
 *
 * Feishu's developer console does NOT accept custom-scheme redirect URIs —
 * registering `amberagent://oauth/feishu` silently leaves the allowlist empty
 * and the authorize endpoint returns error 20029 "redirect_uri 请求不合法"
 * on every attempt. Per Feishu's official "配置重定向 URL" doc the redirect
 * must be a complete http(s) URL with exact string match, and Lark's own MCP
 * SDK uses `http://127.0.0.1:<port>/callback`. We follow the same RFC 8252
 * loopback pattern: [requiresLoopback] returns true so WebMountOAuthClient
 * spins up [LoopbackOAuthCallbackServer] on port 53682 before launching the
 * browser, and the registered redirect_uri must be exactly the constant
 * [LoopbackOAuthCallbackServer.DEFAULT_REDIRECT_URI].
 */
object FeishuOAuthProvider : OAuthProvider {

    override val id: String = "feishu"
    override val displayName: String = "Feishu"
    override val requiresLoopback: Boolean = true

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
        errorCopy: OAuthProviderErrorCopy,
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
        return tokenRequest(http, body, credentials, errorCopy)
    }

    override suspend fun refresh(
        credentials: OAuthAppCredentials,
        refreshToken: String,
        http: HttpClient,
        errorCopy: OAuthProviderErrorCopy,
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
        return tokenRequest(http, body, credentials, errorCopy)
    }

    override fun setupHint(): String =
        "Create a Feishu Open Platform app, register $defaultRedirectUri as the exact redirect URI, " +
            "then enter the App ID, App Secret, and required document scopes."

    override fun setupHint(context: Context): String =
        context.getString(
            R.string.setting_webmount_oauth_feishu_setup_hint,
            LoopbackOAuthCallbackServer.DEFAULT_REDIRECT_URI,
        )

    // ----------------------------------------------------------------------

    private suspend fun tokenRequest(
        http: HttpClient,
        body: JsonObject,
        credentials: OAuthAppCredentials,
        errorCopy: OAuthProviderErrorCopy,
    ): WebMountOAuthToken {
        val response = http.post(TOKEN_ENDPOINT) {
            contentType(ContentType.Application.Json)
            headers { append(HttpHeaders.Accept, "application/json") }
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        require(response.status.isSuccess()) {
            errorCopy.tokenEndpointFailed(response.status.value, text.take(500))
        }
        val parsed = json.parseToJsonElement(text).jsonObject
        // The v2 endpoint typically returns the OAuth body at the top level:
        //   {access_token, refresh_token, expires_in, refresh_expires_in, scope}
        // The v1 wrapped shape {code, msg, data:{...}} is rare on v2 but defended against.
        val payload = parsed["data"]?.jsonObject ?: parsed
        val errCode = parsed["code"]?.jsonPrimitive?.intOrNull
        if (errCode != null && errCode != 0) {
            val msg = parsed["msg"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            error(errorCopy.oauthError(errCode, msg))
        }
        val accessToken = payload["access_token"]?.jsonPrimitive?.contentOrNull
            ?: error(errorCopy.missingAccessToken(text.take(400)))
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
