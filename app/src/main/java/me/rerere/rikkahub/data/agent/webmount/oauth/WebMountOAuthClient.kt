package me.rerere.rikkahub.data.agent.webmount.oauth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.agent.webmount.core.WebMountOAuthToken
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates OAuth Authorization Code + PKCE flows for WebMount.
 *
 *  1. Generate `state` and PKCE `code_verifier`/`code_challenge`.
 *  2. Build the provider's authorization URL and open it in a Custom Tab.
 *  3. Suspend on `OAuthCallbackDispatcher.events.first { matches state }`.
 *  4. Exchange the returned `code` for an access/refresh token via the
 *     provider's token endpoint.
 *  5. Persist tokens to [WebMountOAuthTokenStore] (encrypted).
 *
 *  Also exposes [getValidAccessToken] for the request path — inline-refresh
 *  if the cached token is within [REFRESH_SKEW_MS] of expiry.
 *
 *  ## Threat model
 *
 *  If another app on the device declares the same `amberagent://oauth/<provider>`
 *  intent-filter, Android's intent resolver will show a chooser (it cannot
 *  silently intercept the callback). Even in the worst case where the user
 *  picks the malicious app, that app receives only the authorization `code`
 *  + `state`. It cannot exchange the code for tokens because the PKCE
 *  `code_verifier` is generated and held only within this process — never
 *  transmitted over the wire and never persisted. The user's `state`
 *  expectation must also match, which the attacker can't predict. So PKCE
 *  is what makes WebMount's deep-link callback safe against rogue apps.
 */
class WebMountOAuthClient(
    private val context: Context,
    private val store: WebMountOAuthTokenStore,
    private val dispatcher: OAuthCallbackDispatcher,
    private val http: HttpClient,
) {

    private val providers = ConcurrentHashMap<String, OAuthProvider>()

    fun register(provider: OAuthProvider) {
        providers[provider.id] = provider
    }

    fun providers(): Collection<OAuthProvider> = providers.values

    fun provider(id: String): OAuthProvider? = providers[id]

    /** Run the full authorization-code+PKCE flow. */
    suspend fun connect(providerId: String): ConnectResult {
        val provider = providers[providerId]
            ?: return ConnectResult.NotConfigured("Unknown OAuth provider id: $providerId")
        val credentials = store.getCredentials(providerId)
            ?: return ConnectResult.NotConfigured(
                "App credentials for '$providerId' are missing. " +
                    "Set them in WebMount Stations settings first."
            )
        val state = PkceUtils.randomState()
        val verifier = PkceUtils.generateCodeVerifier()
        val challenge = PkceUtils.s256Challenge(verifier)
        val authUrl = provider.buildAuthorizationUrl(credentials, state, challenge)

        return runCatching {
            launchAuth(authUrl)
            val callback = withTimeoutOrNull(AUTH_TIMEOUT_MS) {
                dispatcher.events.first { it.provider == providerId && it.state == state }
            } ?: return ConnectResult.Failed("OAuth callback timed out after ${AUTH_TIMEOUT_MS / 1000}s")

            if (!callback.isSuccess) {
                return ConnectResult.Failed(
                    "Provider returned error: ${callback.error ?: "unknown"} ${callback.errorDescription.orEmpty()}".trim()
                )
            }
            val code = callback.code
                ?: return ConnectResult.Failed("Provider callback had no `code` param")

            val token = provider.exchangeCode(credentials, code, verifier, http)
            store.putToken(providerId, token)
            ConnectResult.Success(token)
        }.getOrElse { error ->
            Log.e(TAG, "OAuth connect failed for $providerId", error)
            ConnectResult.Failed(error.message ?: error.toString())
        }
    }

    /** Disconnect — drop the stored token. Keeps the app credentials. */
    fun disconnect(providerId: String) {
        store.clearToken(providerId)
    }

    /**
     * Return an unexpired access token, refreshing inline if needed.
     * Returns null if no token exists or refresh failed.
     */
    suspend fun getValidAccessToken(providerId: String): String? {
        val provider = providers[providerId] ?: return null
        val current = store.getToken(providerId) ?: return null
        if (!current.isExpired(skewMs = REFRESH_SKEW_MS)) return current.accessToken
        val refreshToken = current.refreshToken ?: return null
        val credentials = store.getCredentials(providerId) ?: return null
        return runCatching {
            val refreshed = provider.refresh(credentials, refreshToken, http)
            store.putToken(providerId, refreshed)
            refreshed.accessToken
        }.onFailure { Log.w(TAG, "Inline refresh failed for $providerId", it) }
            .getOrNull()
    }

    // ----------------------------------------------------------------------

    private fun launchAuth(url: String) {
        val uri = Uri.parse(url)
        // Try Custom Tab first — it preserves the user's browser cookies, fast,
        // back-button returns to AmberAgent cleanly. Fall back to a plain
        // ACTION_VIEW intent if no Custom Tab provider is installed.
        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabsIntent.intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            customTabsIntent.launchUrl(context, uri)
        } catch (error: Throwable) {
            Log.w(TAG, "Custom Tab launch failed, falling back to ACTION_VIEW", error)
            val fallback = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallback)
        }
    }

    sealed class ConnectResult {
        data class Success(val token: WebMountOAuthToken) : ConnectResult()
        data class NotConfigured(val reason: String) : ConnectResult()
        data class Failed(val reason: String) : ConnectResult()
    }

    companion object {
        private const val TAG = "WebMountOAuthClient"
        private const val AUTH_TIMEOUT_MS = 5 * 60 * 1000L   // 5 minutes for user to log in
        private const val REFRESH_SKEW_MS = 5 * 60 * 1000L   // refresh if <5 min remaining
    }
}
