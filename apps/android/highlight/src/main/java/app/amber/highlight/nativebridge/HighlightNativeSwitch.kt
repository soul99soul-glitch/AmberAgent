package app.amber.highlight.nativebridge

import android.util.Log
import app.amber.highlight.HighlightToken
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Phase 2 production switch in front of [HighlighterNative]. Default is
 * disabled — every call returns `null` so the caller stays on the QuickJS+Prism
 * JVM path. The `app` module configures [config] once at startup based on
 * user prefs + Remote Config.
 *
 * Comparing token lists rather than strings is by design: token equality is
 * what affects rendered output. Lengths and a head snippet of the joined
 * content strings are passed to [Config.onDiff] for triage.
 *
 * ## Concurrency profile change vs. JVM path (round 1 review P2-1)
 *
 * The original JVM `Highlighter.highlight` serializes all calls through a
 * single-thread `executor` (one shared QuickJS context per instance, can't
 * safely parallelize). The native path is reentrant — tree-sitter grammars
 * are `&'static` immutable, a fresh `Highlighter` is constructed per JNI
 * call, no shared mutable state. So concurrent callers (e.g. many code
 * blocks rendering at once during streaming) run in **parallel** under the
 * native path instead of serializing. That is the intended performance
 * benefit, but it does mean canary devices with native ON may see higher
 * peak CPU than the JVM baseline. Diff sampling stays valid because token
 * equality is still per-call.
 */
object HighlightNativeSwitch {

    private const val TAG = "HighlightNativeSwitch"
    const val COMPONENT_NAME: String = "highlight"

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
     * Returns the native highlight tokens, or `null` if the caller should
     * fall back to the JVM QuickJS+Prism path. [jvmFallback] is invoked only
     * inside the sampling path (zero overhead in steady-state when
     * samplingRate == 0).
     */
    suspend fun highlightOrNull(
        code: String,
        language: String,
        jvmFallback: suspend () -> List<HighlightToken>,
    ): List<HighlightToken>? {
        val cfg = config
        if (!cfg.enabled()) return null
        if (!HighlighterNative.available) {
            if (loadFailureReported.compareAndSet(false, true)) {
                HighlighterNative.lastLoadError()?.let { cfg.onLoadFailure(it) }
            }
            return null
        }
        val nativeTokens = try {
            HighlighterNative.highlight(code, language)
        } catch (t: Throwable) {
            Log.w(TAG, "native highlight threw — falling back to JVM", t)
            cfg.onNativePanic("highlight", t)
            return null
        }
        if (nativeTokens == null) {
            cfg.onNativePanic("highlight", null)
            return null
        }
        // One-shot success log so canary devices can confirm the native path
        // is firing without flooding logcat on long markdown rendering.
        if (firstSuccessLogged.compareAndSet(false, true)) {
            Log.i(TAG, "native highlight ok (first success): lang=$language tokens=${nativeTokens.size}")
        }
        // Diff sampling — runs the JVM path in parallel and reports the
        // comparison. Done after native succeeds so a failing JVM fallback
        // doesn't drop the native result.
        val rate = cfg.samplingRate()
        // Predicate form: early-return when the dice fall outside the sample
        // window. Matches OfficeNativeSwitch / MarkdownNativeSwitch (final
        // sweep P2-2 — single style across all 4 Switches).
        if (rate <= 0f || Random.nextFloat() >= rate) return nativeTokens
        try {
            val jvmTokens = jvmFallback()
            val equal = jvmTokens == nativeTokens
            cfg.onDiff(
                // Stage stays low-cardinality ("highlight" for all langs)
                // so Crashlytics doesn't bucket per-language issues —
                // language goes into the payload instead.
                stage = "highlight",
                equal = equal,
                jvmSummary = "lang=$language " + summarize(jvmTokens),
                nativeSummary = "lang=$language " + summarize(nativeTokens),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "diff-sampling JVM fallback threw; skipping diff", t)
        }
        return nativeTokens
    }

    private fun summarize(tokens: List<HighlightToken>): String {
        val totalLen = tokens.sumOf { t ->
            when (t) {
                is HighlightToken.Plain -> t.content.length
                is HighlightToken.Token.StringContent -> t.length
                is HighlightToken.Token.StringListContent -> t.length
                is HighlightToken.Token.Nested -> t.length
            }
        }
        val head = tokens.take(8).joinToString("|") { t ->
            when (t) {
                is HighlightToken.Plain -> "P(${t.content.length})"
                is HighlightToken.Token.StringContent -> "${t.type}(${t.length})"
                is HighlightToken.Token.StringListContent -> "${t.type}[](${t.length})"
                is HighlightToken.Token.Nested -> "${t.type}{}(${t.length})"
            }
        }
        return "count=${tokens.size} totalLen=$totalLen head=$head"
    }
}
