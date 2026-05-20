package me.rerere.document.nativebridge

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridge to the `office-parsers` Rust crate (see `native/office-parsers/`).
 *
 * Spike-phase contract:
 * - Loaded lazily and at most once. If `System.loadLibrary` fails the bridge
 *   stays in [available] = false and all calls return [Result.NativeUnavailable];
 *   callers MUST fall back to the JVM implementation.
 * - JNI methods return the same `String` shape the JVM [me.rerere.document.DocxParser]
 *   / [me.rerere.document.PptxParser] emit. Sentinel error prefixes mirror JVM
 *   ("Error parsing DOCX file: ...", "Unable to find document content ...").
 *
 * NOT wired into [DocxParser] / [PptxParser] during Phase 1 — benchmarks call
 * the bridge directly. See `docs/RUST_NATIVE_SPIKE_PLAN.md` §3.
 */
internal object OfficeParserNative {

    private const val TAG = "OfficeParserNative"
    private const val LIB_NAME = "office_parsers"

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
     * Result of a native parse attempt.
     * - [Success] — output produced (may still be a JVM-compatible error sentinel string)
     * - [NativeUnavailable] — `.so` missing or load failed; caller MUST fall back
     */
    sealed class Result {
        data class Success(val output: String) : Result()
        data object NativeUnavailable : Result()
    }

    fun parseDocx(file: File): Result {
        ensureLoaded()
        if (!loaded.get()) return Result.NativeUnavailable
        // Native side returns nullable String (see rust_to_jstring fallback
        // behavior). null = JVM allocation failure inside JNI → downgrade
        // to NativeUnavailable so caller falls back to JVM parser.
        val output = parseDocxNative(file.absolutePath) ?: return Result.NativeUnavailable
        return Result.Success(output)
    }

    fun parsePptx(file: File): Result {
        ensureLoaded()
        if (!loaded.get()) return Result.NativeUnavailable
        val output = parsePptxNative(file.absolutePath) ?: return Result.NativeUnavailable
        return Result.Success(output)
    }

    fun parseEpub(file: File): Result {
        ensureLoaded()
        if (!loaded.get()) return Result.NativeUnavailable
        val output = parseEpubNative(file.absolutePath) ?: return Result.NativeUnavailable
        return Result.Success(output)
    }

    @JvmStatic
    private external fun parseDocxNative(path: String): String?

    @JvmStatic
    private external fun parsePptxNative(path: String): String?

    @JvmStatic
    private external fun parseEpubNative(path: String): String?
}
