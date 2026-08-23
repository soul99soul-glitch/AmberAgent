package app.amber.feature.ui.pages.zcode

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.zcodeDataStore by preferencesDataStore(name = "zcode_companion")

/** Persists the last ZCode share URL opened from Amber. */
class ZCodeUrlStore(
    private val context: Context,
) {
    val urlFlow: Flow<String> = context.zcodeDataStore.data
        .map { it[URL].orEmpty() }
        .distinctUntilChanged()

    suspend fun save(url: String) {
        context.zcodeDataStore.edit { prefs ->
            prefs[URL] = url.trim()
        }
    }

    private companion object {
        val URL = stringPreferencesKey("url")
    }
}
