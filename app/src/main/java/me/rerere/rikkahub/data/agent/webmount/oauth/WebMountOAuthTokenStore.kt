package me.rerere.rikkahub.data.agent.webmount.oauth

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import me.rerere.rikkahub.data.agent.webmount.core.WebMountOAuthToken
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory placeholder for M1.5. The real impl will back this with
 * `androidx.security:security-crypto` EncryptedSharedPreferences.
 *
 * Defined now so adapter signatures already accept the store and M1.5 doesn't
 * have to thread types through every adapter.
 */
class WebMountOAuthTokenStore {
    private val mem = ConcurrentHashMap<String, WebMountOAuthToken>()
    private val _updates = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val updates: SharedFlow<String> = _updates.asSharedFlow()

    fun put(provider: String, token: WebMountOAuthToken) {
        mem[provider] = token
        _updates.tryEmit(provider)
    }

    fun get(provider: String): WebMountOAuthToken? = mem[provider]

    fun clear(provider: String) {
        mem.remove(provider)
        _updates.tryEmit(provider)
    }

    fun providers(): Set<String> = mem.keys.toSet()
}
