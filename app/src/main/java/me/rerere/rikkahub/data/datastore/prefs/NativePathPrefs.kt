package me.rerere.rikkahub.data.datastore.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.PreferencesKeys
import me.rerere.rikkahub.utils.toMutableStateFlow

/**
 * Per-component enable flags for the Rust JNI production switch.
 *
 * **Personal-use config**: the 4 user-facing flags (`office`, `highlight`,
 * `regex`, `markdownHtml`) default to `true` so the Rust path runs out of
 * the box. This deliberately drops the "default JVM + gradual rollout"
 * stance documented in SPIKE_PLAN §8.3 — that gate was sized for an
 * enterprise rollout, which this app doesn't have. The RC kill switch
 * (`native_path_kill_switch`) remains the one-flip insurance if a native
 * crash surfaces in the wild.
 *
 * `markdownAst` stays `false`: it's a shadow-compare-only hook (the
 * renderer still consumes the JVM tree), turning it on without a renderer
 * swap is pure overhead.
 *
 * `sampleRate` defaults to `0` — diff sampling is off because there's no
 * dashboard to monitor and the rendering paths diverge cosmetically on the
 * markdown HTML stage even with the normalizer. Set > 0 explicitly when
 * actively comparing engines.
 *
 * **StateFlow lag note**: the [flow] published via `toMutableStateFlow`
 * lags DataStore writes by one coroutine tick. Acceptable: the next call
 * picks up the new value sub-second later.
 *
 * **Cold-start order**: the initial value seeded by `toMutableStateFlow`
 * is `NativePathPrefsData()` (the data class defaults — now native-on for
 * the user-facing flags), so a fresh install or freshly-opted-out user
 * sees the native path immediately on cold start. DataStore-stored values
 * override on the first emission a few ms later.
 */
data class NativePathPrefsData(
    val office: Boolean = true,
    val highlight: Boolean = true,
    val regex: Boolean = true,
    val markdownHtml: Boolean = true,
    val markdownAst: Boolean = false,
    val sampleRate: Float = 0f,
)

class NativePathPrefs(
    private val dataStore: DataStore<Preferences>,
    scope: AppScope,
) {
    internal val rawFlow: Flow<NativePathPrefsData> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { readFrom(it) }
        .distinctUntilChanged()

    val flow: StateFlow<NativePathPrefsData> = rawFlow
        .toMutableStateFlow(scope, NativePathPrefsData())

    suspend fun update(transform: (NativePathPrefsData) -> NativePathPrefsData) {
        dataStore.edit { p ->
            val current = readFrom(p)
            val next = transform(current)
            if (next == current) return@edit
            writeTo(p, next)
        }
    }

    private fun readFrom(p: Preferences): NativePathPrefsData = NativePathPrefsData(
        // Personal-use defaults: native on for the 4 user-facing flags so
        // a fresh install with no DataStore-written keys runs Rust. To opt
        // out of any individual stage, write `false` to the corresponding
        // key — `update { it.copy(office = false) }`. See class KDoc for
        // why we don't follow the §8.3 HARD GATE default-JVM stance.
        office = p[PreferencesKeys.NATIVE_PATH_OFFICE] ?: true,
        highlight = p[PreferencesKeys.NATIVE_PATH_HIGHLIGHT] ?: true,
        regex = p[PreferencesKeys.NATIVE_PATH_REGEX] ?: true,
        markdownHtml = p[PreferencesKeys.NATIVE_PATH_MARKDOWN_HTML] ?: true,
        markdownAst = p[PreferencesKeys.NATIVE_PATH_MARKDOWN_AST] ?: false,
        sampleRate = (p[PreferencesKeys.NATIVE_PATH_SAMPLING_RATE] ?: 0f).coerceIn(0f, 1f),
    )

    private fun writeTo(p: androidx.datastore.preferences.core.MutablePreferences, data: NativePathPrefsData) {
        p[PreferencesKeys.NATIVE_PATH_OFFICE] = data.office
        p[PreferencesKeys.NATIVE_PATH_HIGHLIGHT] = data.highlight
        p[PreferencesKeys.NATIVE_PATH_REGEX] = data.regex
        p[PreferencesKeys.NATIVE_PATH_MARKDOWN_HTML] = data.markdownHtml
        p[PreferencesKeys.NATIVE_PATH_MARKDOWN_AST] = data.markdownAst
        p[PreferencesKeys.NATIVE_PATH_SAMPLING_RATE] = data.sampleRate.coerceIn(0f, 1f)
    }
}
