package me.rerere.rikkahub.data.agent.webmount.oauth

import io.ktor.client.HttpClient
import me.rerere.common.oauth.LoopbackOAuthCallbackServer
import me.rerere.rikkahub.data.agent.webmount.core.WebMountOAuthToken

/**
 * One OAuth provider plugged into [WebMountOAuthClient]. Each implementor
 * knows its own authorization / token endpoints and request body shape;
 * the orchestrator (the OAuthClient) handles the dance: state generation,
 * launching the browser, awaiting callback, persisting the token.
 */
interface OAuthProvider {
    val id: String
    val displayName: String

    /** Some authorization servers refuse custom-scheme redirect URIs and only accept
     *  loopback HTTP (Feishu rejects `amberagent://` outright; Google's installed-app
     *  client only allows loopback). When true, [WebMountOAuthClient.connect] spins
     *  up a [LoopbackOAuthCallbackServer] on the standard port and uses
     *  [LoopbackOAuthCallbackServer.DEFAULT_REDIRECT_URI] as the redirect_uri.
     *  Default false keeps the historical deep-link path for providers that accept it. */
    val requiresLoopback: Boolean get() = false

    /** Default redirect URI:
     *   - deep-link providers: `amberagent://oauth/<id>` (RouteActivity intent-filter)
     *   - loopback providers:  `http://127.0.0.1:53682/callback` (LoopbackOAuthCallbackServer)
     */
    val defaultRedirectUri: String
        get() = if (requiresLoopback) {
            LoopbackOAuthCallbackServer.DEFAULT_REDIRECT_URI
        } else {
            "amberagent://oauth/$id"
        }

    /**
     * Build the full URL we ask the system browser to open. Implementations
     * URL-encode the params and slot in the user's `credentials` plus the
     * orchestrator-generated state + PKCE code_challenge.
     */
    fun buildAuthorizationUrl(
        credentials: OAuthAppCredentials,
        state: String,
        codeChallenge: String,
    ): String

    /**
     * Exchange the `code` (received via deep-link callback) for an access +
     * refresh token. Per RFC 7636 we send `code_verifier`; some providers
     * also require client_secret.
     */
    suspend fun exchangeCode(
        credentials: OAuthAppCredentials,
        code: String,
        codeVerifier: String,
        http: HttpClient,
    ): WebMountOAuthToken

    /** Refresh an expired access token via the existing refresh_token. */
    suspend fun refresh(
        credentials: OAuthAppCredentials,
        refreshToken: String,
        http: HttpClient,
    ): WebMountOAuthToken

    /** Help-text shown next to the "Connect" button — what the user needs to do first. */
    fun setupHint(): String = "Register an OAuth app on the provider's open platform, set the redirect URI to $defaultRedirectUri, then paste the app_id and (if confidential) app_secret in the fields above."
}
