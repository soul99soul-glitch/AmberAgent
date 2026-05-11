package me.rerere.rikkahub.data.agent.webmount.oauth

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Routes `amberagent://oauth/<provider>` callbacks captured by RouteActivity
 * back to whichever coroutine is currently awaiting the authorization code
 * for that provider+state.
 *
 * RouteActivity.onNewIntent calls [dispatch] when it sees an OAuth callback;
 * the OAuth provider (e.g. FeishuOAuthProvider) launched the browser intent
 * with a known `state` value and is now collecting on [events], matching
 * by state to pull out its own callback.
 */
class OAuthCallbackDispatcher {

    private val _events = MutableSharedFlow<OAuthCallback>(
        replay = 0,
        extraBufferCapacity = 8,
    )
    val events: SharedFlow<OAuthCallback> = _events.asSharedFlow()

    /**
     * Parse a callback URI and emit it. Returns true if dispatched; false if
     * the URI shape didn't match (caller decides whether to log).
     */
    fun dispatch(uri: Uri): Boolean {
        if (uri.scheme != "amberagent" || uri.host != "oauth") return false
        val provider = uri.pathSegments.firstOrNull()
        if (provider.isNullOrBlank()) {
            Log.w(TAG, "OAuth callback missing provider segment: $uri")
            return false
        }
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        val error = uri.getQueryParameter("error")
        val errorDescription = uri.getQueryParameter("error_description")
        val callback = OAuthCallback(
            provider = provider,
            code = code,
            state = state,
            error = error,
            errorDescription = errorDescription,
        )
        val emitted = _events.tryEmit(callback)
        if (!emitted) {
            Log.w(TAG, "OAuth callback buffer full, dropping for provider=$provider")
        }
        return emitted
    }

    companion object {
        private const val TAG = "WebMountOAuthDispatcher"
    }
}

data class OAuthCallback(
    val provider: String,
    val code: String?,
    val state: String?,
    val error: String?,
    val errorDescription: String?,
) {
    val isSuccess: Boolean get() = error == null && !code.isNullOrBlank()
}
