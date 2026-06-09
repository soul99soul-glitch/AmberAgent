package app.amber.feature.ui.components.richtext.nativebridge

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JNI bridge to the `markdown-preprocess` Rust crate (TD.Rust.1b).
 *
 * The crate combines 3 Kotlin regex passes (inline LaTeX, block LaTeX,
 * bare URL linkify) into a single allocation-light scan. JNI overhead
 * is ~50µs; the Kotlin preProcess() typically takes 200-1500µs on
 * messages 500-5000 chars long, so the native path saves the bulk of
 * the regex compile + iteration cost for streamed messages.
 *
 * Per ADR-0004 HARD GATE: catch_unwind on the Rust side; null return
 * triggers fallback to the Kotlin preProcess implementation.
 */
internal object MarkdownPreprocessNative {

    private const val TAG = "MarkdownPreprocessNative"
    private const val LIB_NAME = "markdown_preprocess"

    private val loaded = AtomicBoolean(false)
    @Volatile private var loadAttempted = false

    val available: Boolean
        get() {
            ensureLoaded()
            return loaded.get()
        }

    private fun ensureLoaded() {
        if (loaded.get() || loadAttempted) return
        synchronized(this) {
            if (loaded.get() || loadAttempted) return
            loadAttempted = true
            try {
                System.loadLibrary(LIB_NAME)
                loaded.set(true)
                Log.i(TAG, "loaded native library: $LIB_NAME")
            } catch (t: Throwable) {
                Log.w(TAG, "failed to load native library $LIB_NAME — will fall back to JVM", t)
            }
        }
    }

    /** Returns the preprocessed markdown, or null on JNI / panic / unavailable. */
    fun preprocess(input: String): String? {
        ensureLoaded()
        if (!loaded.get()) return null
        return preprocessNative(input)
    }

    @JvmStatic
    private external fun preprocessNative(input: String): String?
}
