package app.amber.feature.webmount.oauth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.common.oauth.LoopbackOAuthCallbackServer
import me.rerere.rikkahub.AppScope
import app.amber.feature.webmount.core.WebMountOAuthToken
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

        // Loopback providers (Feishu, eventually Google Gemini) need a `http://127.0.0.1:<port>`
        // redirect URI because the provider's developer console refuses custom scheme. We
        // spin up the local server BEFORE building the auth URL so a port-bind failure is
        // surfaced as a clean ConnectResult.Failed instead of going as far as the browser
        // and then dying mid-OAuth. The server lives only for the duration of this call —
        // accept() is single-shot, close() in `finally` tears it down even on early return.
        val loopbackServer: LoopbackOAuthCallbackServer? = if (provider.requiresLoopback) {
            try {
                LoopbackOAuthCallbackServer()
            } catch (error: Throwable) {
                Log.e(TAG, "Loopback server bind failed for $providerId", error)
                return ConnectResult.Failed(error.message ?: error.toString())
            }
        } else {
            null
        }
        val effectiveCredentials = if (loopbackServer != null) {
            val userOverride = credentials.redirectUri
            if (!userOverride.isNullOrBlank() && userOverride != LoopbackOAuthCallbackServer.DEFAULT_REDIRECT_URI) {
                // A non-default redirectUri pinned by the user can't actually work for
                // loopback providers — we have to bind a fixed port and tell the IDP
                // the matching URL. Log it loudly so the user can find their own mistake
                // in logcat rather than silently watch a "OAuth failed" toast.
                Log.w(
                    TAG,
                    "Ignoring user-supplied redirectUri='$userOverride' for loopback provider $providerId; " +
                        "overriding with ${LoopbackOAuthCallbackServer.DEFAULT_REDIRECT_URI}. " +
                        "If you wanted to bind a different port, this code path doesn't support that today.",
                )
            }
            credentials.copy(redirectUri = LoopbackOAuthCallbackServer.DEFAULT_REDIRECT_URI)
        } else {
            credentials
        }
        val authUrl = provider.buildAuthorizationUrl(effectiveCredentials, state, challenge)

        // Persist BEFORE launching the browser so a process death between here and the
        // callback can be recovered — for DEEP-LINK providers only. The loopback path
        // can't be resumed across process death anyway (the bound socket dies with the
        // process), and the resume collector only listens on dispatcher.events which
        // loopback never feeds. Putting a PendingOAuthEntry for loopback would just
        // leave a stale row in encrypted prefs until purgeStale GCs it 15 min later.
        if (loopbackServer == null) {
            pendingStore.put(
                PendingOAuthEntry(
                    state = state,
                    providerId = providerId,
                    codeVerifier = verifier,
                    startedAtMs = System.currentTimeMillis(),
                )
            )
        }
        inFlight.add(state)

        return try {
            runCatching {
                launchAuth(authUrl)
                val callback = withTimeoutOrNull(AUTH_TIMEOUT_MS) {
                    if (loopbackServer != null) {
                        // Single-shot server.accept; cancellation propagates to socket close.
                        // OAuthCallbackResult lives in common (provider-agnostic), wrap it as
                        // the webmount-flavored OAuthCallback so the rest of this function
                        // can treat both paths uniformly.
                        loopbackServer.awaitCallback().toWebMountCallback(providerId)
                    } else {
                        // Deep-link path: RouteActivity routes amberagent:// callbacks here.
                        dispatcher.events.first { it.provider == providerId && it.state == state }
                    }
                } ?: return ConnectResult.Failed("OAuth callback timed out after ${AUTH_TIMEOUT_MS / 1000}s")

                if (!callback.isSuccess) {
                    return ConnectResult.Failed(
                        "Provider returned error: ${callback.error ?: "unknown"} ${callback.errorDescription.orEmpty()}".trim()
                    )
                }
                // Loopback path: server can't know the expected state ahead of time, so
                // verify here. Deep-link path already filtered by state in the events.first
                // predicate, but a double-check is cheap.
                if (loopbackServer != null && callback.state != state) {
                    return ConnectResult.Failed(
                        "Loopback callback state mismatch (got=${callback.state?.take(6)}…, expected=${state.take(6)}…)"
                    )
                }
                val code = callback.code
                    ?: return ConnectResult.Failed("Provider callback had no `code` param")

                val token = provider.exchangeCode(effectiveCredentials, code, verifier, http)
                store.putToken(providerId, token)
                // M2.0 review B-2 fix: only consume the pending entry on
                // confirmed exchange success. Timeout / provider-error /
                // missing-code paths leave the entry so a delayed callback
                // (or process-death recovery) within PENDING_TTL_MS can
                // still complete. Stale entries are GC'd by purgeStale.
                pendingStore.consume(state)
                ConnectResult.Success(token)
            }.getOrElse { error ->
                Log.e(TAG, "OAuth connect failed for $providerId", error)
                ConnectResult.Failed(error.message ?: error.toString())
            }
        } finally {
            // Only release the in-flight marker — the pending entry is
            // intentionally kept on non-success paths (see B-2 fix above).
            inFlight.remove(state)
            // Loopback server is single-shot but defensive close() handles the
            // timeout / early-return / exception paths in one place.
            loopbackServer?.close()
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
        if (state in inFlight) return
        if (!callback.isSuccess) {
            // Provider-level error (e.g. user denied) — log and leave the
            // pending entry to be GC'd, so a later retry doesn't collide.
            Log.i(TAG, "Resume: callback for ${callback.provider} carries error=${callback.error}")
            return
        }
        val code = callback.code ?: return
        // M2.0 review W-1 fix: peek at the pending entry first so we don't
        // remove it before confirming we can actually handle the exchange.
        // The provider / credentials check used to run AFTER consume(),
        // meaning a misregistered provider would silently lose the
        // verifier and force the user to redo the whole OAuth dance.
        val peeked = pendingStore.peek(state) ?: return
        if (peeked.providerId != callback.provider) {
            Log.w(TAG, "Resume mismatched provider for state ${state.take(6)}…")
            return
        }
        val provider = providers[peeked.providerId]
            ?: run { Log.w(TAG, "Resume: provider ${peeked.providerId} not registered"); return }
        val credentials = store.getCredentials(peeked.providerId)
            ?: run { Log.w(TAG, "Resume: credentials for ${peeked.providerId} missing"); return }
        // Validation passed — now atomically consume. If a race with the
        // live coroutine raced us to consume(), the entry is gone and we
        // bail; the live path will have stored the token.
        val entry = pendingStore.consume(state) ?: return
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

    /** Map the provider-agnostic loopback callback result to webmount's OAuthCallback
     *  which carries a `provider` tag. The tag is supplied by the caller (always equals
     *  the in-flight [providerId]), so this is a pure projection — same wire data, just
     *  tagged for downstream dispatcher routing if anyone wants to re-emit later. */
    private fun me.rerere.common.oauth.OAuthCallbackResult.toWebMountCallback(providerId: String): OAuthCallback =
        OAuthCallback(
            provider = providerId,
            code = code,
            state = state,
            error = error,
            errorDescription = errorDescription,
        )

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
