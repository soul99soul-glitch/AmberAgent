package app.amber.feature.ui.pages.zcode

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.zcodeDataStore by preferencesDataStore(name = "zcode_companion")

/** The user-selected ZCode page and the optional Amber agent bridge. */
data class ZCodeConnection(
    val url: String = "",
    val agentEnabled: Boolean = false,
    val sessionId: String? = null,
)

/** Persists the last ZCode share URL and its pooled WebMount session identity. */
class ZCodeUrlStore(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.zcodeDataStore)

    val connectionFlow: Flow<ZCodeConnection> = dataStore.data
        .map { prefs ->
            ZCodeConnection(
                url = prefs[URL].orEmpty().trim(),
                agentEnabled = prefs[AGENT_ENABLED] ?: false,
                sessionId = prefs[SESSION_ID]
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },
            )
        }
        .distinctUntilChanged()

    /** Compatibility projection for callers that only need the saved URL. */
    val urlFlow: Flow<String> = connectionFlow
        .map { it.url }
        .distinctUntilChanged()

    suspend fun save(url: String) {
        val normalized = url.trim()
        dataStore.edit { prefs ->
            if (prefs[URL].orEmpty().trim() != normalized) {
                prefs.remove(SESSION_ID)
            }
            prefs[URL] = normalized
        }
    }

    suspend fun setAgentEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[AGENT_ENABLED] = enabled
        }
    }

    /**
     * Bind a newly-created pooled session only if the user has kept the same
     * URL selected. A stale page must never replace a newer connection.
     *
     * @return true when the binding was written, false when the URL changed
     *         or the candidate values were blank.
     */
    suspend fun recordSession(expectedUrl: String, sessionId: String): Boolean {
        val expected = expectedUrl.trim()
        val candidate = sessionId.trim()
        if (expected.isEmpty() || candidate.isEmpty()) return false
        var recorded = false
        dataStore.edit { prefs ->
            if (prefs[URL].orEmpty().trim() == expected) {
                prefs[SESSION_ID] = candidate
                recorded = true
            }
        }
        return recorded
    }

    private companion object {
        val URL = stringPreferencesKey("url")
        val AGENT_ENABLED = booleanPreferencesKey("agent_enabled")
        val SESSION_ID = stringPreferencesKey("session_id")
    }
}
