package app.amber.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import app.amber.core.infra.PreferencesKeys

/**
 * Stable capability IDs from the Android/iOS capability parity plan
 * (docs/plans/2026-08-13-android-ios-capability-parity-closure-plan.md, Phase 0).
 *
 * Each capability gets an independent switch so behavior changes can be
 * gray-scaled per capability instead of behind one "iOS parity" master switch.
 * Release defaults are capability-specific: only the durable runtime core
 * (tool effects + typed terminal) is enabled when its key is absent. Other
 * capabilities remain off until explicitly enabled.
 */
enum class Capability(
    val id: String,
    val defaultEnabled: Boolean = false,
) {
    DurableToolEffects("durable_tool_effects", defaultEnabled = true),
    TypedRunTerminal("typed_run_terminal", defaultEnabled = true),
    CapabilityPermissions("capability_permissions"),
    WorkspaceArtifactsV2("workspace_artifacts_v2"),
    RecipeRuntime("recipe_runtime"),
    ThreadGraphV2("thread_graph_v2"),
    OpenAIResponsesResume("openai_responses_resume"),
    NovelPackageV2("novel_package_v2"),
    SyncProviderV2("sync_provider_v2"),
    /**
     * P4-03 persistent JS cells (parity plan §10 P4-03, P3 priority): cell
     * create/run/wait/terminate/store/load tools. Default OFF; first-version
     * scope is debug/advanced users only, and a process restart never resumes
     * in-flight JS — only saved store content and terminal states survive.
     */
    JSCellRuntime("js_cell_runtime"),
}

/**
 * Snapshot of capability switches after applying stored overrides and
 * capability-specific defaults.
 */
data class CapabilityFlagsData(
    val enabled: Set<Capability> = emptySet(),
)

/**
 * Per-capability feature flags persisted in the Settings DataStore.
 *
 * Storage: one boolean key `capability_<id>` (see [PreferencesKeys.capabilityFlag]).
 * Toggling a switch only writes that single key — it never rewrites or deletes
 * other settings, and disabling a flag keeps any data the capability already
 * produced (rollback rules in plan §17.2). A missing key uses the capability's
 * release default; an explicit false remains persisted as false.
 *
 * Usage:
 * ```
 * if (capabilityFlags.isEnabled(Capability.RecipeRuntime)) { ... }
 * capabilityFlags.setEnabled(Capability.RecipeRuntime, true)
 * ```
 */
class CapabilityFlags(
    private val dataStore: DataStore<Preferences>,
) {
    val flow: Flow<CapabilityFlagsData> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs -> readFrom(prefs) }
        .distinctUntilChanged()

    /** Current on/off state for one capability. */
    suspend fun isEnabled(capability: Capability): Boolean =
        dataStore.data.first()[PreferencesKeys.capabilityFlag(capability.id)] ?: capability.defaultEnabled

    /** Flip one capability switch. Only that key is written, including false. */
    suspend fun setEnabled(capability: Capability, enabled: Boolean) {
        dataStore.edit { prefs ->
            val key = PreferencesKeys.capabilityFlag(capability.id)
            prefs[key] = enabled
        }
    }

    private fun readFrom(prefs: Preferences): CapabilityFlagsData = CapabilityFlagsData(
        enabled = Capability.entries
            .filter { prefs[PreferencesKeys.capabilityFlag(it.id)] ?: it.defaultEnabled }
            .toSet(),
    )
}
