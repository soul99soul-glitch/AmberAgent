package me.rerere.rikkahub.data.model.nativebridge

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridge to the `regex-transformer` Rust crate.
 *
 * Spike-phase: NOT wired into [me.rerere.rikkahub.data.model.replaceRegexes];
 * benchmark/equivalence tests call directly.
 *
 * Kotlin adapter handles the rule-filtering (enabled / visualOnly /
 * affectingScope) and feeds the JNI surface parallel pattern + replacement
 * arrays. This keeps the JNI shape narrow.
 */
internal object RegexTransformerNative {

    private const val TAG = "RegexTransformerNative"
    private const val LIB_NAME = "regex_transformer"

    private val loaded = AtomicBoolean(false)
    private var loadError: Throwable? = null

    val available: Boolean
        get() {
            ensureLoaded()
            return loaded.get()
        }

    private fun ensureLoaded() {
        if (loaded.get() || loadError != null) return
        synchronized(this) {
            if (loaded.get() || loadError != null) return
            try {
                System.loadLibrary(LIB_NAME)
                loaded.set(true)
                Log.i(TAG, "loaded native library: $LIB_NAME")
            } catch (t: Throwable) {
                loadError = t
                Log.w(TAG, "failed to load native library $LIB_NAME — will fall back to JVM", t)
            }
        }
    }

    /**
     * Apply a sequence of regex find/replace rules to [input].
     *
     * @param findPatterns parallel array of regex patterns (must be same length as [replacements])
     * @param replacements parallel array of replacement strings; standard
     *                     group reference syntax `$1`, `$<name>` supported
     *                     identically to Kotlin's `String.replace(Regex, String)`.
     * @return transformed string, or null if native is unavailable / errored.
     *         Caller MUST fall back to JVM `String.replaceRegexes` on null.
     */
    fun apply(input: String, findPatterns: Array<String>, replacements: Array<String>): String? {
        ensureLoaded()
        if (!loaded.get()) return null
        if (findPatterns.size != replacements.size) {
            Log.w(TAG, "findPatterns/replacements length mismatch — caller bug")
            return null
        }
        if (findPatterns.isEmpty()) return input
        return applyRegexesNative(input, findPatterns, replacements)
    }

    @JvmStatic
    private external fun applyRegexesNative(
        input: String,
        findPatterns: Array<String>,
        replacements: Array<String>,
    ): String?
}
