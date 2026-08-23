package app.amber.feature.ui.pages.synara

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.synaraCompanionDataStore by preferencesDataStore(name = "synara_companion")

/**
 * Persists the last Synara LAN connection used by the Amber companion surface.
 * Token is stored in app-private DataStore (not exported); treat it like a password.
 */
class SynaraConnectionStore(
    private val context: Context,
) {
    val connectionFlow: Flow<SynaraConnection> = context.synaraCompanionDataStore.data
        .map { prefs ->
            SynaraConnection(
                host = prefs[HOST] ?: "",
                port = prefs[PORT] ?: SynaraConnection.DEFAULT_PORT,
                token = prefs[TOKEN] ?: "",
                useHttps = prefs[USE_HTTPS] ?: false,
            )
        }
        .distinctUntilChanged()

    suspend fun save(connection: SynaraConnection) {
        context.synaraCompanionDataStore.edit { prefs ->
            prefs[HOST] = connection.host.trim()
            prefs[PORT] = connection.port.coerceIn(1, 65535)
            prefs[TOKEN] = connection.token.trim()
            prefs[USE_HTTPS] = connection.useHttps
        }
    }

    private companion object {
        val HOST = stringPreferencesKey("host")
        val PORT = intPreferencesKey("port")
        val TOKEN = stringPreferencesKey("token")
        val USE_HTTPS = booleanPreferencesKey("use_https")
    }
}
