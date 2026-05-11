package me.rerere.rikkahub.data.agent.webmount.oauth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 2 M2.0.3 — Cross-process OAuth resume state.
 *
 * Holds the PKCE `code_verifier` + `state` + `providerId` for in-flight
 * authorization-code flows, so the resume path can complete the token
 * exchange even if Android kills AmberAgent's process while the user is
 * authorizing in the browser.
 *
 * Each entry has a `startedAtMs` timestamp; entries older than the TTL
 * are GC'd in [purgeStale] (called on process start by
 * [WebMountOAuthClient.init]). The TTL must outlast the OAuth user-action
 * window (browser → app handoff) but stay short enough that abandoned
 * flows don't accumulate verifiers indefinitely. 15 minutes covers a
 * patient user closing the browser, switching apps, and coming back.
 *
 * Storage is [EncryptedSharedPreferences]; falls back to an in-memory map
 * if the keystore is unavailable (e.g. corrupted backup restore) — in
 * that fallback case, process-kill resume won't work but the in-process
 * happy path still does.
 *
 * Atomicity: [consume] reads-and-removes in one logical step so the live
 * coroutine and the resume path can each safely call it; first caller
 * gets the entry, second gets null. The store relies on SharedPreferences
 * single-process semantics, which AmberAgent is (one process).
 */
class PendingOAuthStore(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val prefs: SharedPreferences? = tryCreateEncryptedPrefs(context)
    private val mem = ConcurrentHashMap<String, PendingOAuthEntry>()

    @Synchronized
    fun put(entry: PendingOAuthEntry) {
        val raw = json.encodeToString(PendingOAuthEntry.serializer(), entry)
        if (prefs != null) {
            prefs.edit().putString(entry.state, raw).apply()
        } else {
            mem[entry.state] = entry
        }
    }

    /**
     * Read the pending entry without removing it. Used by callers that want
     * to validate the entry can be acted on (provider registered, etc.)
     * before committing to removal via [consume]. Returns null if missing.
     */
    @Synchronized
    fun peek(state: String): PendingOAuthEntry? {
        val raw = prefs?.getString(state, null)
        return if (raw != null) {
            runCatching {
                json.decodeFromString(PendingOAuthEntry.serializer(), raw)
            }.onFailure { Log.w(TAG, "Failed to decode pending entry for state (peek)", it) }
                .getOrNull()
        } else {
            mem[state]
        }
    }

    /**
     * Atomically fetch and remove the pending entry for the given state.
     * Returns null if no entry exists (either never put, already consumed,
     * or GC'd by [purgeStale]). Synchronized so two concurrent consume()
     * callers cannot both observe the same entry.
     */
    @Synchronized
    fun consume(state: String): PendingOAuthEntry? {
        if (prefs != null) {
            val raw = prefs.getString(state, null) ?: return null
            prefs.edit().remove(state).apply()
            return runCatching {
                json.decodeFromString(PendingOAuthEntry.serializer(), raw)
            }.onFailure { Log.w(TAG, "Failed to decode pending entry for state", it) }
                .getOrNull()
        }
        return mem.remove(state)
    }

    /** Drop entries older than [maxAgeMs]. Safe to call concurrently. */
    fun purgeStale(maxAgeMs: Long) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        if (prefs != null) {
            val toRemove = prefs.all.entries.mapNotNull { (key, value) ->
                val raw = value as? String ?: return@mapNotNull key
                runCatching {
                    val entry = json.decodeFromString(PendingOAuthEntry.serializer(), raw)
                    if (entry.startedAtMs < cutoff) key else null
                }.getOrElse { key } // can't decode → also drop
            }
            if (toRemove.isNotEmpty()) {
                val editor = prefs.edit()
                toRemove.forEach { editor.remove(it) }
                editor.apply()
                Log.i(TAG, "Purged ${toRemove.size} stale pending OAuth entries")
            }
        }
        mem.entries.removeIf { it.value.startedAtMs < cutoff }
    }

    private fun tryCreateEncryptedPrefs(context: Context): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (error: Throwable) {
        Log.e(TAG, "PendingOAuthStore encrypted prefs unavailable, falling back to memory", error)
        null
    }

    companion object {
        private const val TAG = "WebMountPendingOAuth"
        private const val FILE = "amberagent_webmount_oauth_pending"
    }
}

/** One in-flight OAuth authorization. Persisted across process death. */
@Serializable
data class PendingOAuthEntry(
    val state: String,
    val providerId: String,
    val codeVerifier: String,
    val startedAtMs: Long,
) {
    /** Defensive: never leak the verifier via toString. */
    override fun toString(): String =
        "PendingOAuthEntry(state=${state.take(6)}…, providerId=$providerId, " +
            "codeVerifier=***redacted***, startedAtMs=$startedAtMs)"
}
