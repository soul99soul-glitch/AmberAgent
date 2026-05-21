package me.rerere.document.nativebridge

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Phase 2 production switch in front of [OfficeParserNative].
 *
 * Default state is "disabled" — every entry point returns `null`, the caller
 * keeps using the JVM parser. The `app` module configures [config] once at
 * startup based on user prefs + Remote Config.
 *
 * Telemetry hooks ([Config.onLoadFailure] / [Config.onNativePanic] /
 * [Config.onDiff]) let the app forward into Crashlytics without this module
 * having to know about Firebase. Bridge ([OfficeParserNative]) stays `internal`.
 */
object OfficeNativeSwitch {

    private const val TAG = "OfficeNativeSwitch"
    const val COMPONENT_NAME: String = "office"

    /**
     * D-1: xlsx is net-new — no JVM `XlsxParser` exists, so diff sampling has
     * no byte-equivalent baseline and would flood Crashlytics with cosmetic
     * differences against the app-side helper. Same kill-switch shape as
     * `MarkdownNativeSwitch.HTML_DIFF_ENABLED`. Flip to `true` only after
     * a Java-side xlsx parser lands that can be compared against.
     */
    private const val XLSX_DIFF_ENABLED: Boolean = false

    interface Config {
        /** Whether to attempt the native path at all. Default returns false. */
        fun enabled(): Boolean

        /**
         * 0.0..1.0 — when native is enabled, fraction of calls that also run the
         * JVM path and report the comparison via [onDiff]. 0 by default
         * (no diff sampling).
         */
        fun samplingRate(): Float

        /** Called once when `System.loadLibrary` fails. */
        fun onLoadFailure(error: Throwable)

        /** Called when the native call throws / returns NativeUnavailable mid-flight. */
        fun onNativePanic(stage: String, error: Throwable?)

        /**
         * Called from the sampling path with summaries of both outputs. The
         * caller MUST keep `jvm` / `native` short — they are forwarded into
         * the message body of `NativePathDivergence` so Crashlytics groups by
         * component:stage and the variable payload stays inline rather than
         * leaking into global custom keys.
         */
        fun onDiff(stage: String, equal: Boolean, jvmSummary: String, nativeSummary: String)
    }

    /** No-op fallback so unconfigured modules (tests, library consumers) stay JVM. */
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
    private val firstDocxSuccessLogged = AtomicBoolean(false)
    private val firstPptxSuccessLogged = AtomicBoolean(false)
    private val firstEpubSuccessLogged = AtomicBoolean(false)
    private val firstXlsxSuccessLogged = AtomicBoolean(false)

    private fun firstSuccessFlag(stage: String): AtomicBoolean? = when (stage) {
        "docx" -> firstDocxSuccessLogged
        "pptx" -> firstPptxSuccessLogged
        "epub" -> firstEpubSuccessLogged
        "xlsx" -> firstXlsxSuccessLogged
        // If a future stage gets added without updating this mapper, return
        // null so we log once with a warning rather than silently sharing the
        // docx flag (which would double-log) — final-sweep R2 P3-b.
        else -> null
    }

    /**
     * Returns the native parse result, or `null` if the caller should use JVM.
     * Always returns `null` if [Config.enabled] is false; never throws.
     *
     * [jvmFallback] is invoked only inside the sampling path — when sampling
     * does not fire it is not called at all (zero overhead in steady-state).
     */
    fun parseDocxOrNull(file: File, jvmFallback: () -> String): String? =
        runOneShot("docx", jvmFallback) { OfficeParserNative.parseDocx(file) }

    fun parsePptxOrNull(file: File, jvmFallback: () -> String): String? =
        runOneShot("pptx", jvmFallback) { OfficeParserNative.parsePptx(file) }

    fun parseEpubOrNull(file: File, jvmFallback: () -> String): String? =
        runOneShot("epub", jvmFallback) { OfficeParserNative.parseEpub(file) }

    /**
     * Phase 3 D-1: net-new (no JVM `XlsxParser` exists). [jvmFallback] is
     * still required because diff-sampling needs a comparison baseline — the
     * caller passes the existing app-side xlsx helper. When native fails,
     * caller must fall back to the same helper via the standard Elvis
     * pattern.
     */
    fun parseXlsxOrNull(file: File, jvmFallback: () -> String): String? =
        runOneShot("xlsx", jvmFallback) { OfficeParserNative.parseXlsx(file) }

    private inline fun runOneShot(
        stage: String,
        jvmFallback: () -> String,
        nativeBlock: () -> OfficeParserNative.Result,
    ): String? {
        val cfg = config
        if (!cfg.enabled()) return null
        // Eagerly detect load failure once and surface to telemetry.
        if (!OfficeParserNative.available) {
            if (loadFailureReported.compareAndSet(false, true)) {
                OfficeParserNative.lastLoadError()?.let { cfg.onLoadFailure(it) }
            }
            return null
        }
        val native = try {
            nativeBlock()
        } catch (t: Throwable) {
            // catch_unwind on the Rust side normally prevents this, but keep a
            // belt-and-suspenders catch in case the JNI shim itself throws.
            Log.w(TAG, "native $stage threw — falling back to JVM", t)
            cfg.onNativePanic(stage, t)
            return null
        }
        val nativeOutput = when (native) {
            is OfficeParserNative.Result.Success -> native.output
            OfficeParserNative.Result.NativeUnavailable -> {
                cfg.onNativePanic(stage, null)
                return null
            }
        }
        // One-shot success log per stage so canary devices see each parser's
        // first native success independently (final-sweep P3-1).
        val flag = firstSuccessFlag(stage)
        if (flag == null) {
            Log.w(TAG, "native $stage ok (first-success flag missing; update firstSuccessFlag mapper)")
        } else if (flag.compareAndSet(false, true)) {
            Log.i(TAG, "native $stage ok (first success): len=${nativeOutput.length}")
        }
        maybeSample(cfg, stage, nativeOutput, jvmFallback)
        return nativeOutput
    }

    private inline fun maybeSample(
        cfg: Config,
        stage: String,
        nativeOutput: String,
        jvmFallback: () -> String,
    ) {
        // xlsx has no JVM byte-equivalent baseline (Phase 3 D-1).
        if (stage == "xlsx" && !XLSX_DIFF_ENABLED) return
        val rate = cfg.samplingRate()
        // Combined form matches the other 3 Switches (final-sweep R2 P3-a).
        if (rate <= 0f || Random.nextFloat() >= rate) return
        val jvmOutput = try {
            jvmFallback()
        } catch (t: Throwable) {
            Log.w(TAG, "diff-sampling JVM fallback threw; skipping diff", t)
            return
        }
        val equal = jvmOutput == nativeOutput
        cfg.onDiff(
            stage = stage,
            equal = equal,
            jvmSummary = summarize(jvmOutput),
            nativeSummary = summarize(nativeOutput),
        )
    }

    private fun summarize(s: String): String {
        // 256-char window + length so Crashlytics custom-key payload stays
        // well under the 1KB per-key ceiling.
        val head = if (s.length <= 256) s else s.substring(0, 256) + "...(+${s.length - 256})"
        return "len=${s.length} head=$head"
    }
}
