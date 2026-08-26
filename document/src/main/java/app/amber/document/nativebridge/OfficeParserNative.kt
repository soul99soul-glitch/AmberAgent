package app.amber.document.nativebridge

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** JNI bridge for the retained XLSX/calamine reader. */
internal object OfficeParserNative {
    private const val TAG = "OfficeParserNative"
    private const val LIB_NAME = "office_parsers"

    private val loaded = AtomicBoolean(false)
    @Volatile private var loadError: Throwable? = null

    val available: Boolean
        get() {
            ensureLoaded()
            return loaded.get()
        }

    internal fun lastLoadError(): Throwable? = loadError

    private fun ensureLoaded() {
        if (loaded.get() || loadError != null) return
        synchronized(this) {
            if (loaded.get() || loadError != null) return
            try {
                System.loadLibrary(LIB_NAME)
                loaded.set(true)
                Log.i(TAG, "loaded native library: $LIB_NAME")
            } catch (error: Throwable) {
                loadError = error
                Log.w(TAG, "failed to load native library $LIB_NAME", error)
            }
        }
    }

    sealed class Result {
        data class Success(val output: String) : Result()
        data object NativeUnavailable : Result()
    }

    fun parseXlsx(file: File): Result {
        ensureLoaded()
        if (!loaded.get()) return Result.NativeUnavailable
        return parseXlsxNative(file.absolutePath)?.let(Result::Success)
            ?: Result.NativeUnavailable
    }

    @JvmStatic
    private external fun parseXlsxNative(path: String): String?
}
