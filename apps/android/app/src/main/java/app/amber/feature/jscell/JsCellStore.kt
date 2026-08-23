package app.amber.feature.jscell

import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import app.amber.core.infra.PreferencesKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * P4-03 persistence for JS cells (parity plan §10 P4-03).
 *
 * Follows the CapabilityPermissionStore pattern: one standalone Settings
 * DataStore key (`js_cells`), no Room migration. Only cell metadata + store
 * content + terminal state are persisted — never an in-flight JS stack
 * (first-version scope: no cross-restart resume of executing JS).
 *
 * Rollback rules (§17.2): disabling the `js_cell_runtime` flag never deletes
 * this key; terminal records stay readable.
 */
class JsCellStore(
    private val dataStore: DataStore<Preferences>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    val cellsFlow: Flow<List<JsCellRecord>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> decode(prefs[PreferencesKeys.JS_CELLS]) }
        .distinctUntilChanged()

    suspend fun cells(): List<JsCellRecord> = cellsFlow.first()

    suspend fun get(cellId: String): JsCellRecord? = cells().firstOrNull { it.cellId == cellId }

    /** Insert or replace one record. */
    suspend fun upsert(record: JsCellRecord) {
        dataStore.edit { prefs ->
            val records = decode(prefs[PreferencesKeys.JS_CELLS]).toMutableList()
            records.removeAll { it.cellId == record.cellId }
            records.add(record)
            prefs[PreferencesKeys.JS_CELLS] = encode(records)
        }
    }

    /**
     * Cold-start recovery: any cell persisted as WAITING/RUNNING was executing
     * in a process that no longer exists. It is never pretended to be alive —
     * it is marked TERMINATED(process_restart) while store content and terminal
     * records are preserved.
     *
     * @return number of cells marked terminated.
     */
    suspend fun reconcileAfterColdStart(now: Long = System.currentTimeMillis()): Int {
        var terminated = 0
        dataStore.edit { prefs ->
            val records = decode(prefs[PreferencesKeys.JS_CELLS])
            val reconciled = records.map { record ->
                if (record.statusEnum in setOf(JsCellStatus.RUNNING, JsCellStatus.WAITING)) {
                    terminated++
                    record.copy(
                        status = JsCellStatus.TERMINATED.name,
                        updatedAtMs = now,
                        error = JsCellTerminationReasons.PROCESS_RESTART,
                    )
                } else {
                    record
                }
            }
            prefs[PreferencesKeys.JS_CELLS] = encode(reconciled)
        }
        return terminated
    }

    private fun decode(raw: String?): List<JsCellRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(JsCellRecord.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun encode(records: List<JsCellRecord>): String =
        json.encodeToString(ListSerializer(JsCellRecord.serializer()), records)
}
