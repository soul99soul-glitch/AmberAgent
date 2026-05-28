package app.amber.feature.ui.components.richtext.nativebridge

import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import app.amber.agent.ui.components.richtext.nativebridge.MarkdownParserNative

/**
 * Phase 2 switch in front of [MarkdownParserNative]. Two independent stages:
 *
 * - `html`: native `pulldown-cmark` HTML emit replaces the JetBrains
 *   `HtmlGenerator` path in `MarkdownNew.kt`.
 * - `ast`: native packed binary AST replaces the JetBrains tree builder feed
 *   into the Compose markdown renderer in `Markdown.kt`.
 *
 * The two stages are gated independently because the AST path requires the
 * Markdown.kt migration to converge first (see SPIKE_PLAN §8.3 — markdown AST
 * is intentionally last). Default for both is disabled.
 */
object MarkdownNativeSwitch {

    private const val TAG = "MarkdownNativeSwitch"
    const val COMPONENT_NAME: String = "markdown"

    /** HTML diff sampling enabled now that [HtmlDiffNormalizer] is in place
     *  (Phase 3 C). The normalizer collapses whitespace / attr order /
     *  entity-escape divergences between JetBrains `HtmlGenerator` and
     *  pulldown-cmark so only genuine differences survive to Crashlytics. */
    private const val HTML_DIFF_ENABLED = true

    interface Config {
        fun htmlEnabled(): Boolean
        fun astEnabled(): Boolean
        fun samplingRate(): Float
        fun onLoadFailure(error: Throwable)
        fun onNativePanic(stage: String, error: Throwable?)
        fun onDiff(stage: String, equal: Boolean, jvmSummary: String, nativeSummary: String)
    }

    object DisabledConfig : Config {
        override fun htmlEnabled(): Boolean = false
        override fun astEnabled(): Boolean = false
        override fun samplingRate(): Float = 0f
        override fun onLoadFailure(error: Throwable) {}
        override fun onNativePanic(stage: String, error: Throwable?) {}
        override fun onDiff(stage: String, equal: Boolean, jvmSummary: String, nativeSummary: String) {}
    }

    @Volatile
    var config: Config = DisabledConfig

    private val loadFailureReported = AtomicBoolean(false)
    private val firstHtmlSuccessLogged = AtomicBoolean(false)
    private val firstAstSuccessLogged = AtomicBoolean(false)

    /** Cheap pre-checks exposed to callers so they can skip allocating a
     *  jvmFallback lambda + state on the hot streaming path when native is
     *  disabled. Parity with `RegexNativeSwitch.isEnabled()`. */
    fun isHtmlEnabled(): Boolean = config.htmlEnabled()

    fun isAstEnabled(): Boolean = config.astEnabled()

    /** Sample rate (0..1) exposed for the shadow-compare path in Markdown.kt
     *  so it can gate the JNI + decode cost on a per-call dice roll rather
     *  than running on every streaming tick (review Step 5 P1-1). */
    fun sampleRate(): Float = config.samplingRate().coerceIn(0f, 1f)

    /** Caller-facing panic hook for cases where the bridge returned a blob
     *  but `PackedAstReader` rejected the header / version (review Step 5
     *  P3-1). Routes through the same Crashlytics pipeline as bridge-level
     *  panics so the failure mode is observable. */
    fun reportAstBlobRejected() {
        config.onNativePanic("ast", null)
    }

    /**
     * Native markdown → HTML. Returns `null` when disabled / native unavailable
     * / native errored — callers MUST fall back to the JetBrains HtmlGenerator
     * path. [jvmFallback] is invoked only inside the sampling path.
     *
     * Diff sampling for the HTML stage is wired through [HtmlDiffNormalizer]
     * which canonicalises whitespace, attribute order, entity escape style,
     * and self-closing-tag style — so only genuine semantic differences reach
     * Crashlytics. Set `samplingRate > 0` to start collecting divergence data.
     * [HTML_DIFF_ENABLED] gates the entire compare path as a one-flip
     * kill-switch in case the normalizer itself needs to be backed out.
     */
    fun renderHtmlOrNull(text: String, jvmFallback: () -> String): String? {
        val cfg = config
        if (!cfg.htmlEnabled()) return null
        if (!checkAvailability(cfg)) return null
        val nativeHtml = try {
            MarkdownParserNative.renderHtml(text)
        } catch (t: Throwable) {
            Log.w(TAG, "native renderHtml threw — falling back to JVM", t)
            cfg.onNativePanic("html", t)
            return null
        }
        if (nativeHtml == null) {
            cfg.onNativePanic("html", null)
            return null
        }
        if (firstHtmlSuccessLogged.compareAndSet(false, true)) {
            Log.i(TAG, "native markdown html ok (first success): len=${nativeHtml.length}")
        }
        sampleAndCompareStrings(cfg, "html", nativeHtml, jvmFallback)
        return nativeHtml
    }

    /**
     * Native markdown → packed AST. Returns `null` when disabled / native
     * unavailable. Diff-sampling is intentionally not wired here because there
     * is no symmetric JVM packed-AST output to compare against — callers
     * compare semantically (node count / type sequence) via [reportAstDiff]
     * by constructing summaries themselves.
     */
    fun parseAstOrNull(text: String): ByteArray? {
        val cfg = config
        if (!cfg.astEnabled()) return null
        if (!checkAvailability(cfg)) return null
        val blob = try {
            MarkdownParserNative.parse(text)
        } catch (t: Throwable) {
            Log.w(TAG, "native parseAst threw — falling back to JVM", t)
            cfg.onNativePanic("ast", t)
            return null
        }
        if (blob == null) {
            // Bridge returned null for "available but errored mid-flight"
            // (e.g. Rust returned an empty blob after a caught panic). Mirror
            // the symmetric branch in the other Switches so the AST stage
            // doesn't go silently un-instrumented (review P1-2).
            cfg.onNativePanic("ast", null)
            return null
        }
        if (firstAstSuccessLogged.compareAndSet(false, true)) {
            Log.i(TAG, "native markdown ast ok (first success): bytes=${blob.size}")
        }
        return blob
    }

    /**
     * Public hook so callers that perform their own AST-equivalence checks can
     * route diff reports through the same Crashlytics pipeline.
     *
     * **Sample-rate gate runs inside this method** — callers do NOT need to
     * wrap calls in a sampling check. The caller IS responsible for the
     * semantic comparison itself; this method only decides whether to forward
     * the result to telemetry.
     *
     * **Divergences are reported unconditionally**, regardless of sample rate
     * (round-2 review R2-P1-1) — if the caller went to the trouble of doing
     * the compare and found a mismatch, that's exactly the signal we cannot
     * afford to drop. Matches are forwarded only when `samplingRate > 0`
     * (and at that frequency) so the dashboard can confirm sampling is live.
     */
    fun reportAstDiff(equal: Boolean, jvmSummary: String, nativeSummary: String) {
        val cfg = config
        if (!equal) {
            cfg.onDiff("ast", false, jvmSummary, nativeSummary)
            return
        }
        val rate = cfg.samplingRate()
        if (rate > 0f && Random.nextFloat() < rate) {
            cfg.onDiff("ast", true, jvmSummary, nativeSummary)
        }
    }

    /** Returns true if native is loaded; reports the load failure once otherwise. */
    private fun checkAvailability(cfg: Config): Boolean {
        if (MarkdownParserNative.available) return true
        if (loadFailureReported.compareAndSet(false, true)) {
            MarkdownParserNative.lastLoadError()?.let { cfg.onLoadFailure(it) }
        }
        return false
    }

    private inline fun sampleAndCompareStrings(
        cfg: Config,
        stage: String,
        nativeOutput: String,
        jvmFallback: () -> String,
    ) {
        // Don't sample on the composition thread — Jsoup normalize + the
        // jvmFallback (re-runs JetBrains HtmlGenerator) can block the UI
        // on initial composition. Background sampling still covers the
        // hot path because streaming chunks render under
        // `Dispatchers.Default` (Codex review P2-3).
        if (Looper.myLooper() == Looper.getMainLooper()) return
        // One-flip kill switch for the HTML compare path (HtmlDiffNormalizer
        // is now in place; flag is `true` by default). If the normalizer
        // itself starts producing false positives at rollout time, flip the
        // const back to `false` and ship a hotfix without touching the
        // sampling logic.
        if (stage == "html" && !HTML_DIFF_ENABLED) return
        val rate = cfg.samplingRate()
        // Combined form matches the other 3 Switches (final-sweep R2 P3-a).
        if (rate <= 0f || Random.nextFloat() >= rate) return
        val jvmOutput = try {
            jvmFallback()
        } catch (t: Throwable) {
            Log.w(TAG, "diff-sampling JVM fallback threw; skipping diff", t)
            return
        }
        // For the HTML stage, canonicalise both sides before comparison so
        // cosmetic differences (whitespace, attr order, entity escape) don't
        // explode into Crashlytics noise. For other stages the raw equality
        // check is right.
        val (jvmCmp, nativeCmp) = if (stage == "html") {
            HtmlDiffNormalizer.normalize(jvmOutput) to HtmlDiffNormalizer.normalize(nativeOutput)
        } else {
            jvmOutput to nativeOutput
        }
        cfg.onDiff(
            stage = stage,
            equal = jvmCmp == nativeCmp,
            jvmSummary = summarize(jvmOutput),
            nativeSummary = summarize(nativeOutput),
        )
    }

    private fun summarize(s: String): String {
        val head = if (s.length <= 256) s else s.substring(0, 256) + "...(+${s.length - 256})"
        return "len=${s.length} head=$head"
    }
}
