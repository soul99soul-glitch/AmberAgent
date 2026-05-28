package me.rerere.rikkahub.data.nativepath

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.remoteconfig.ConfigUpdate
import com.google.firebase.remoteconfig.ConfigUpdateListener
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.document.nativebridge.OfficeNativeSwitch
import me.rerere.highlight.nativebridge.HighlightNativeSwitch
import me.rerere.rikkahub.AppScope
import app.amber.core.settings.prefs.NativePathPrefs
import me.rerere.rikkahub.data.model.nativebridge.RegexNativeSwitch
import me.rerere.rikkahub.ui.components.richtext.nativebridge.MarkdownNativeSwitch

/**
 * Wires the 4 `*NativeSwitch` objects to user prefs + Remote Config + Crashlytics.
 *
 * Lifecycle:
 * 1. App startup constructs this via Koin, then calls [install] from
 *    [me.rerere.rikkahub.RikkaHubApp].
 * 2. [install] reads the current [NativePathPrefs] snapshot and pushes a
 *    config impl into each Switch. Subsequent user-toggle edits are picked
 *    up on the next call because each impl reads `prefs.flow.value` lazily.
 * 3. Remote Config kill-switch: a `true`-valued `native_path_kill_switch`
 *    forces all components off regardless of user prefs. Cached locally via
 *    a `@Volatile` field, refreshed by an RC `ConfigUpdateListener`, so the
 *    hot-path is one volatile read instead of an RC client call per request.
 *
 * Each diff / panic / load-failure event is encoded into a fresh
 * [NativePathFailure] or [NativePathDivergence] instance whose **message
 * itself** carries `component:stage` plus the payload. We deliberately do NOT
 * call `setCustomKey(...)` for per-event data — Crashlytics custom keys are
 * process-global and the snapshot taken inside `recordException` would race
 * across concurrent components (review P2-1).
 */
class NativePathBootstrap(
    private val prefs: NativePathPrefs,
    private val crashlytics: FirebaseCrashlytics,
    private val remoteConfig: FirebaseRemoteConfig,
    private val scope: AppScope,
) {
    companion object {
        private const val TAG = "NativePathBootstrap"
        const val REMOTE_KILL_SWITCH_KEY: String = "native_path_kill_switch"
    }

    /**
     * Cached kill-switch value. Updated by [refreshKillSwitch] only — that
     * helper serializes all writes through a synchronized block (round-2
     * review R2-P2-2) so two concurrent RC callbacks (config-update +
     * activate-complete) can't inter-leave reads and produce a stale cache.
     * Read on every `enabled()` call in the four Config impls. Defaults to
     * false (same as the RC XML default) so a missing RC instance fails safe.
     */
    @Volatile
    private var killSwitchCached: Boolean = false

    private val killSwitchLock = Any()

    /**
     * Atomic read-and-publish: a fresh `getBoolean` read is paired with the
     * write under one lock, so concurrent refreshers observe a consistent
     * before/after instead of racing two stale RC fetches against each other.
     */
    private fun refreshKillSwitch() {
        synchronized(killSwitchLock) {
            val v = readKillSwitchSafe()
            killSwitchCached = v
            // Emit the breadcrumb inside the lock so concurrent refreshers
            // can't interleave writes vs. log emissions — breadcrumbs stay
            // in cache-write order (final-sweep R2 P3-c). `Crashlytics.log`
            // is an in-memory ring-buffer append; safe to hold the lock
            // across the call.
            runCatching {
                crashlytics.log("native_path kill_switch=$v")
            }.onFailure { Log.w(TAG, "Crashlytics breadcrumb log failed", it) }
        }
    }

    fun install() {
        // Seed once from whatever RC has already activated. setDefaultsAsync
        // in RikkaHubApp.onCreate is fire-and-forget; until it activates,
        // getBoolean returns the Java default `false` (= allow native), which
        // is also the safe default. The activate listener below repaints
        // killSwitchCached when defaults / fetched values become live (review
        // P2-6).
        refreshKillSwitch()
        installKillSwitchListener()
        installActivationRefresh()

        OfficeNativeSwitch.config = OfficeConfigImpl()
        HighlightNativeSwitch.config = HighlightConfigImpl()
        MarkdownNativeSwitch.config = MarkdownConfigImpl()
        RegexNativeSwitch.config = RegexConfigImpl()

        Log.i(TAG, "NativePath switches installed (kill_switch=$killSwitchCached)")
    }

    /**
     * Kick a fresh `activate()` and refresh the cached kill switch when it
     * completes. `activate()` is idempotent — if `RikkaHubApp.onCreate`'s
     * `fetchAndActivate()` already finished, this resolves immediately;
     * otherwise it blocks until activation lands, at which point the XML
     * default and any fetched override become readable. Without this hop, an
     * emergency XML default flip to `true` would be ignored at cold start
     * until the next user-triggered RC refresh.
     */
    private fun installActivationRefresh() {
        runCatching {
            remoteConfig.activate().addOnCompleteListener {
                refreshKillSwitch()
                Log.i(TAG, "RC activate completed; kill_switch=$killSwitchCached")
            }
        }.onFailure { Log.w(TAG, "Failed to attach RC activate listener", it) }
    }

    private fun installKillSwitchListener() {
        runCatching {
            remoteConfig.addOnConfigUpdateListener(object : ConfigUpdateListener {
                override fun onUpdate(configUpdate: ConfigUpdate) {
                    if (REMOTE_KILL_SWITCH_KEY in configUpdate.updatedKeys) {
                        remoteConfig.activate()
                            .addOnSuccessListener {
                                refreshKillSwitch()
                                Log.i(TAG, "Remote kill_switch refreshed: $killSwitchCached")
                            }
                            // Without this branch a transient activate() failure
                            // (rare; local cache corruption) would leave the cached
                            // value stale and the operator would never know
                            // (final-sweep P3-2).
                            .addOnFailureListener {
                                Log.w(TAG, "RC activate after kill-switch update failed", it)
                            }
                    }
                }

                override fun onError(error: FirebaseRemoteConfigException) {
                    // Don't escalate — RC listener errors are common transient
                    // events; the cached value stays at whatever was last seen.
                    Log.w(TAG, "RC ConfigUpdateListener error", error)
                }
            })
        }.onFailure { Log.w(TAG, "Failed to register RC kill-switch listener", it) }
    }

    private fun readKillSwitchSafe(): Boolean = try {
        remoteConfig.getBoolean(REMOTE_KILL_SWITCH_KEY)
    } catch (t: Throwable) {
        Log.w(TAG, "Remote Config kill_switch read failed; treating as false", t)
        false
    }

    private fun sampleRate(): Float = prefs.flow.value.sampleRate.coerceIn(0f, 1f)

    private fun recordLoad(component: String, error: Throwable) {
        dispatchRecord {
            crashlytics.recordException(
                NativePathFailure(component = component, stage = "load", cause = error)
            )
        }
    }

    private fun recordPanic(component: String, stage: String, error: Throwable?) {
        dispatchRecord {
            crashlytics.recordException(
                NativePathFailure(component = component, stage = stage, cause = error)
            )
        }
    }

    private fun recordDiff(
        component: String,
        stage: String,
        equal: Boolean,
        jvmSummary: String,
        nativeSummary: String,
    ) {
        if (equal) return  // Don't burn Crashlytics quota on matching outputs.
        val jvmText = truncate(jvmSummary)
        val nativeText = truncate(nativeSummary)
        dispatchRecord {
            crashlytics.recordException(
                NativePathDivergence(
                    component = component,
                    stage = stage,
                    jvmSummary = jvmText,
                    nativeSummary = nativeText,
                )
            )
        }
    }

    /**
     * Off-loads the [FirebaseCrashlytics.recordException] call to a background
     * dispatcher so production hot paths (regex per message, markdown HTML per
     * streaming chunk) don't pay the stack-capture + serialization cost inline
     * (review P2-7).
     *
     * Failures from the dispatch itself are swallowed and logged — Crashlytics
     * outage must never block the caller.
     */
    private fun dispatchRecord(block: () -> Unit) {
        // If the AppScope is cancelled (process shutting down), `scope.launch`
        // becomes a no-op and the report is lost. Fall back to an inline call
        // so a late-fired panic/divergence still has a chance to reach
        // Crashlytics before the process exits (round 2 review R2-P2-4).
        if (!scope.isActive) {
            try {
                block()
            } catch (t: Throwable) {
                Log.w(TAG, "Crashlytics inline-fallback failed (scope cancelled)", t)
            }
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (t: Throwable) {
                Log.w(TAG, "Crashlytics dispatch failed", t)
            }
        }
    }

    /** Cap payload at ~900 chars so the encoded message stays well under
     *  Crashlytics' per-event limit even when both sides are included. */
    private fun truncate(s: String): String =
        if (s.length <= 900) s else s.substring(0, 900) + "...(+${s.length - 900})"

    // -----------------------------------------------------------------------
    // Per-Switch Config implementations. Each enabled() check reads the cached
    // kill switch plus the StateFlow prefs snapshot — both are @Volatile reads,
    // so safe-for-hot-path.
    // -----------------------------------------------------------------------

    private inner class OfficeConfigImpl : OfficeNativeSwitch.Config {
        override fun enabled(): Boolean = !killSwitchCached && prefs.flow.value.office
        override fun samplingRate(): Float = sampleRate()
        override fun onLoadFailure(error: Throwable) =
            recordLoad(OfficeNativeSwitch.COMPONENT_NAME, error)
        override fun onNativePanic(stage: String, error: Throwable?) =
            recordPanic(OfficeNativeSwitch.COMPONENT_NAME, stage, error)
        override fun onDiff(stage: String, equal: Boolean, jvmSummary: String, nativeSummary: String) =
            recordDiff(OfficeNativeSwitch.COMPONENT_NAME, stage, equal, jvmSummary, nativeSummary)
    }

    private inner class HighlightConfigImpl : HighlightNativeSwitch.Config {
        override fun enabled(): Boolean = !killSwitchCached && prefs.flow.value.highlight
        override fun samplingRate(): Float = sampleRate()
        override fun onLoadFailure(error: Throwable) =
            recordLoad(HighlightNativeSwitch.COMPONENT_NAME, error)
        override fun onNativePanic(stage: String, error: Throwable?) =
            recordPanic(HighlightNativeSwitch.COMPONENT_NAME, stage, error)
        override fun onDiff(stage: String, equal: Boolean, jvmSummary: String, nativeSummary: String) =
            recordDiff(HighlightNativeSwitch.COMPONENT_NAME, stage, equal, jvmSummary, nativeSummary)
    }

    private inner class MarkdownConfigImpl : MarkdownNativeSwitch.Config {
        override fun htmlEnabled(): Boolean = !killSwitchCached && prefs.flow.value.markdownHtml
        override fun astEnabled(): Boolean = !killSwitchCached && prefs.flow.value.markdownAst
        override fun samplingRate(): Float = sampleRate()
        override fun onLoadFailure(error: Throwable) =
            recordLoad(MarkdownNativeSwitch.COMPONENT_NAME, error)
        override fun onNativePanic(stage: String, error: Throwable?) =
            recordPanic(MarkdownNativeSwitch.COMPONENT_NAME, stage, error)
        override fun onDiff(stage: String, equal: Boolean, jvmSummary: String, nativeSummary: String) =
            recordDiff(MarkdownNativeSwitch.COMPONENT_NAME, stage, equal, jvmSummary, nativeSummary)
    }

    private inner class RegexConfigImpl : RegexNativeSwitch.Config {
        override fun enabled(): Boolean = !killSwitchCached && prefs.flow.value.regex
        override fun samplingRate(): Float = sampleRate()
        override fun onLoadFailure(error: Throwable) =
            recordLoad(RegexNativeSwitch.COMPONENT_NAME, error)
        override fun onNativePanic(stage: String, error: Throwable?) =
            recordPanic(RegexNativeSwitch.COMPONENT_NAME, stage, error)
        override fun onDiff(stage: String, equal: Boolean, jvmSummary: String, nativeSummary: String) =
            recordDiff(RegexNativeSwitch.COMPONENT_NAME, stage, equal, jvmSummary, nativeSummary)
    }
}

/**
 * Per-event failure record. Carrying `component` / `stage` / the underlying
 * cause as constructor arguments — not as global Crashlytics custom keys —
 * means concurrent native-path events on different components don't race on
 * shared mutable state (review P2-1). Crashlytics groups by exception class
 * + message-prefix, so all `office:load` failures bucket together regardless
 * of which other components were busy in parallel.
 *
 * **Message split is intentional** (round 2 review R2-P3-2): null-cause
 * collapses to "...native unavailable" while a thrown cause produces
 * "...native failure". These reflect distinct bridge contracts —
 * `unavailable` means the bridge returned a sentinel (NativeUnavailable /
 * null blob) without throwing, a `failure` means an actual Throwable came
 * out. Triagers want them in separate Crashlytics issue groups.
 */
class NativePathFailure(
    val component: String,
    val stage: String,
    cause: Throwable?,
) : RuntimeException(
    "$component:$stage native ${if (cause == null) "unavailable" else "failure"}",
    cause,
)

/**
 * Per-event divergence record. Carries the JVM and native summaries inline
 * in the message body (after a newline so Crashlytics' dashboard preview
 * still shows the static prefix as the issue title). Same race-free design as
 * [NativePathFailure].
 */
class NativePathDivergence(
    val component: String,
    val stage: String,
    val jvmSummary: String,
    val nativeSummary: String,
) : RuntimeException(
    "$component:$stage native output diverges from JVM\n" +
        "jvm:    $jvmSummary\n" +
        "native: $nativeSummary"
)
