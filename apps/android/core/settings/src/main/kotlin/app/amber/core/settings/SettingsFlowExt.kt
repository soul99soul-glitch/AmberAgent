package app.amber.core.settings

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Collect this Flow into a MutableStateFlow seeded with [initial], reading on
 * the given [scope]. On collection failure (e.g. a DataStore deserialization
 * exception) the process is halted — matching the legacy behavior in :app's
 * CoroutineUtils.toMutableStateFlow which the settings preference files
 * originally called.
 *
 * Kept module-internal to :core:settings because it's a tight binding to the
 * "settings DataStore must always have a fresh state-flow" invariant;
 * production prefs code uses it everywhere, tests can pre-seed.
 */
internal fun <T> Flow<T>.toMutableStateFlow(
    scope: CoroutineScope,
    initial: T,
): MutableStateFlow<T> {
    val stateFlow = MutableStateFlow(initial)
    scope.launch {
        runCatching {
            this@toMutableStateFlow.collect { stateFlow.value = it }
        }.onFailure {
            it.printStackTrace()
            Log.e("SettingsFlowExt", "Error while collecting settings flow: ${it.message}", it)
            Runtime.getRuntime().halt(1)
        }
    }
    return stateFlow
}
