package app.amber.core.model.nativebridge

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Phase 2 switch in front of [RegexTransformerNative]. Default is disabled —
 * callers stay on Kotlin's JVM `String.replace(Regex, …)` path.
 *
 * ## ⚠ Why diff sampling matters more here
 *
 * Per [RegexTransformerNative]'s KDoc, Java `Pattern` and Rust `regex` accept
 * overlapping but not identical syntax. Rust silently skips rules with
 * lookbehind / backreferences / possessive quantifiers — the **output is
 * different but neither side throws**, so the Switch will return a valid
 * (incorrect) string instead of `null`. The classic load-failure / panic
 * fallback path does NOT catch this. Diff sampling is the only reliable
 * detector. Phase 2 rollout MUST set a non-zero `samplingRate` for regex
 * until divergence is empirically zero.
 */
object RegexNativeSwitch {

    private const val TAG = "RegexNativeSwitch"
    const val COMPONENT_NAME: String = "regex"

    interface Config {
        fun enabled(): Boolean
        fun samplingRate(): Float
        fun onLoadFailure(error: Throwable)
        fun onNativePanic(stage: String, error: Throwable?)
        fun onDiff(stage: String, equal: Boolean, jvmSummary: String, nativeSummary: String)
    }

    object DisabledConfig : Config {
        override fun enabled(): Boolean = false
        override fun samplingRate(): Float = 0f
        override fun onLoadFailure(error: Throwable) {}
        override fun onNativePanic(stage: String, error: Throwable?) {}
        override fun onDiff(stage: String, equal: Boolean, jvmSummary: String, nativeSummary: String) {}
    }

    @Volatile
    var config: Config = DisabledConfig

    private val loadFailureReported = AtomicBoolean(false)
    private val firstSuccessLogged = AtomicBoolean(false)

    /**
     * Cheap pre-check exposed to callers so they can avoid building parallel
     * pattern/replacement arrays for `applyOrNull` when the native path is
     * disabled — a hot-path concern because `String.replaceRegexes` runs per
     * streamed token (review P1-1 / P2-3). Same `@Volatile` read as
     * `config.enabled()` so calling this first then `applyOrNull` is racy
     * only in the harmless direction (we may build arrays just before the
     * Switch is disabled mid-token).
     */
    fun isEnabled(): Boolean = config.enabled()

    /**
     * One-shot warning fires the first time the regex native path runs with
     * `enabled=true` but `samplingRate=0`. The class KDoc documents that
     * Rust silently produces wrong output for Java-only syntax (lookbehind,
     * backreferences, possessive quantifiers); diff sampling is the only
     * detector. Production rollout MUST set a non-zero sample rate until
     * divergence is empirically zero (review P3-3).
     */
    private val zeroSampleWarned = AtomicBoolean(false)

    /**
     * Run the regex pipeline natively. Returns `null` if disabled / native
     * unavailable / native errored. [jvmFallback] is invoked only inside the
     * sampling path (it produces the JVM output for comparison; the production
     * caller is responsible for the actual JVM fallback when this returns null).
     */
    fun applyOrNull(
        input: String,
        findPatterns: Array<String>,
        replacements: Array<String>,
        jvmFallback: () -> String,
    ): String? {
        val cfg = config
        if (!cfg.enabled()) return null
        if (!RegexTransformerNative.available) {
            if (loadFailureReported.compareAndSet(false, true)) {
                RegexTransformerNative.lastLoadError()?.let { cfg.onLoadFailure(it) }
            }
            return null
        }
        // Warning fires only once per process when native is actually live
        // and sampling is disabled — placed after the availability check so
        // it doesn't spam when the .so simply failed to load.
        if (cfg.samplingRate() <= 0f && zeroSampleWarned.compareAndSet(false, true)) {
            Log.w(
                TAG,
                "Regex native enabled but samplingRate=0 — lookbehind/backref/" +
                    "possessive divergences will go undetected. Set NATIVE_PATH_" +
                    "SAMPLING_RATE > 0 in DataStore before relying on this " +
                    "path in production.",
            )
        }
        val nativeOutput = try {
            RegexTransformerNative.apply(input, findPatterns, replacements)
        } catch (t: Throwable) {
            Log.w(TAG, "native apply threw — falling back to JVM", t)
            cfg.onNativePanic("apply", t)
            return null
        }
        if (nativeOutput == null) {
            cfg.onNativePanic("apply", null)
            return null
        }
        if (firstSuccessLogged.compareAndSet(false, true)) {
            // rulesIn = patterns we handed to Rust. The crate skips any rule
            // whose regex won't compile (lookbehind, backrefs, possessive
            // quantifiers, ...), so the applied count may be lower — see
            // RegexTransformerNative class KDoc. Diff sampling is the only
            // way to observe the skip rate.
            Log.i(TAG, "native regex ok (first success): rulesIn=${findPatterns.size} len=${nativeOutput.length}")
        }
        val rate = cfg.samplingRate()
        // Predicate matches Office/Highlight/Markdown — single style across
        // the 4 Switches (final-sweep P2-2).
        if (rate <= 0f || Random.nextFloat() >= rate) return nativeOutput
        try {
            val jvmOutput = jvmFallback()
            cfg.onDiff(
                stage = "apply",
                equal = jvmOutput == nativeOutput,
                // Rule count goes into the payload, not the stage label, so
                // Crashlytics doesn't fragment groups across rule-count
                // values.
                jvmSummary = "rules=${findPatterns.size} " + summarize(jvmOutput),
                nativeSummary = "rules=${findPatterns.size} " + summarize(nativeOutput),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "diff-sampling JVM fallback threw; skipping diff", t)
        }
        return nativeOutput
    }

    private fun summarize(s: String): String {
        val head = if (s.length <= 256) s else s.substring(0, 256) + "...(+${s.length - 256})"
        return "len=${s.length} head=$head"
    }
}
