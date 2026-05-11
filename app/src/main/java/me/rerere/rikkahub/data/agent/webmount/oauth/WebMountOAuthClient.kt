package me.rerere.rikkahub.data.agent.webmount.oauth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.AppScope
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
    private val pendingStore: PendingOAuthStore,
    private val dispatcher: OAuthCallbackDispatcher,
    private val http: HttpClient,
    private val appScope: AppScope,
) {

    private val providers = ConcurrentHashMap<String, OAuthProvider>()

    /**
     * States currently being handled by a live in-process [connect] coroutine.
     * The resume collector below uses this to skip callbacks whose live
     * owner is still around — only "orphaned" callbacks (live coroutine
     * died with the process) get picked up by [tryResume].
     */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    init {
        // Phase 2 M2.0.3 — resume orphaned OAuth flows after process death.
        //
        // The dispatcher's events flow re-emits every callback, including
        // those whose original `connect()` coroutine died with the process.
        // Pair with the encrypted [pendingStore] so we still have the
        // code_verifier for the exchange.
        appScope.launch {
            dispatcher.events.collect { callback -> handleEventForResume(callback) }
        }
        // Drop pending entries that have outlived the OAuth user-action window.
        appScope.launch { pendingStore.purgeStale(PENDING_TTL_MS) }
    }

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

        // Persist BEFORE launching the browser so a process death between
        // here and the callback can be recovered.
        pendingStore.put(
            PendingOAuthEntry(
                state = state,
                providerId = providerId,
                codeVerifier = verifier,
                startedAtMs = System.currentTimeMillis(),
            )
        )
        inFlight.add(state)

        return try {
            runCatching {
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
        } finally {
            // Either we finished or threw — clean up the pending entry and
            // in-flight marker so the resume path doesn't fire later.
            pendingStore.consume(state)
            inFlight.remove(state)
        }
    }

    /**
     * Called for every dispatcher event. Skips callbacks whose live
     * [connect] coroutine is still around (the live one will handle it).
     * For orphaned callbacks, consume the persisted [PendingOAuthEntry]
     * and complete the token exchange in the background — the result
     * lands in [WebMountOAuthTokenStore] and the UI's
     * [WebMountOAuthTokenStore.updates] subscriber re-renders.
     */
    private suspend fun handleEventForResume(callback: OAuthCallback) {
        val state = callback.state ?: return
        // Give the live coroutine a moment to register inFlight if the
        // event arrived basically simultaneously with connect() start.
        // In practice the connect() always adds to inFlight before
        // launching the browser, so this is just defense-in-depth.
        if (state in inFlight) return
        val entry = pendingStore.consume(state) ?: return
        if (entry.providerId != callback.provider) {
            Log.w(TAG, "Resume mismatched provider for state ${state.take(6)}…")
            return
        }
        val provider = providers[entry.providerId]
            ?: run { Log.w(TAG, "Resume: provider ${entry.providerId} not registered"); return }
        val credentials = store.getCredentials(entry.providerId)
            ?: run { Log.w(TAG, "Resume: credentials for ${entry.providerId} missing"); return }
        if (!callback.isSuccess) {
            Log.i(TAG, "Resume: provider returned error for ${entry.providerId}: ${callback.error}")
            return
        }
        val code = callback.code ?: return
        runCatching {
            val token = provider.exchangeCode(credentials, code, entry.codeVerifier, http)
            store.putToken(entry.providerId, token)
            Log.i(TAG, "Resumed OAuth for ${entry.providerId} after process restart")
        }.onFailure { Log.w(TAG, "Resume token exchange failed for ${entry.providerId}", it) }
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
        // Outlives AUTH_TIMEOUT_MS so a slow browser handoff after the
        // live connect() coroutine timed out can still be resumed if the
        // callback eventually arrives.
        private const val PENDING_TTL_MS = 15 * 60 * 1000L   // 15 min GC window
    }
}
