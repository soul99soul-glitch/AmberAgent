package app.amber.feature.ui.components.richtext.nativebridge

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JNI bridge to the `html-diff-normalizer` Rust crate (TD.Rust.1c).
 *
 * Runs only inside the shadow-compare sampling path of [MarkdownNativeSwitch];
 * NOT in the hot render path. Performance benefit is modest (~5-10x over the
 * Jsoup-based JVM normalizer at typical chunk sizes) but the crate exists so
 * the JVM path's Jsoup dependency can be pruned from the Crashlytics
 * comparison hot loop on devices that have the .so loaded.
 *
 * Returns null when the .so isn't loaded or on JNI failure; caller falls
 * back to `HtmlDiffNormalizer.normalize()`.
 */
internal object HtmlDiffNormalizerNative {

    private const val TAG = "HtmlDiffNormalizerNative"
    private const val LIB_NAME = "html_diff_normalizer"

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

    fun normalize(html: String): String? {
        ensureLoaded()
        if (!loaded.get()) return null
        return normalizeNative(html)
    }

    @JvmStatic
    private external fun normalizeNative(html: String): String?
}
