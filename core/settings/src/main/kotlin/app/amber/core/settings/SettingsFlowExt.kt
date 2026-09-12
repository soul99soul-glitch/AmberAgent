package app.amber.core.settings

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn

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

/**
 * Share a decoded settings stream before it is exposed as the legacy
 * StateFlow. The preference-domain StateFlows are retained for callers that
 * need an immediate placeholder value, while SettingsAggregator can consume
 * the same replayed upstream value without running readFrom/rehydration a
 * second time.
 *
 * Decode and secret rehydration are deliberately upstream of shareIn and are
 * moved off the main thread. Only the inexpensive StateFlow assignment runs
 * in the caller's scope.
 */
internal fun <T> Flow<T>.shareSettingsRawFlow(scope: CoroutineScope): SharedFlow<T> =
    flowOn(Dispatchers.Default)
        .catch {
            // Keep the legacy fatal contract from toMutableStateFlow: a
            // malformed/corrupt settings stream must halt the process rather
            // than silently terminate the shared upstream and leave every
            // consumer on a stale snapshot.
            it.printStackTrace()
            Log.e("SettingsFlowExt", "Error while collecting settings flow: ${it.message}", it)
            Runtime.getRuntime().halt(1)
            throw it
        }
        .shareIn(scope, SharingStarted.Eagerly, replay = 1)
