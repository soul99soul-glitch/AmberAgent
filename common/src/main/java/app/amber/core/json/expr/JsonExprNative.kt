package app.amber.core.json.expr

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JNI bridge to the `json-expr` Rust crate (TD.Rust.2).
 *
 * Drop-in for `me.rerere.common.http.JsonExpression`'s
 * `evaluateJsonExpr` + `isJsonExprValid`. When loaded + flag-enabled,
 * routes through the native lexer+parser+evaluator on serde_json. Falls
 * back to the Kotlin implementation on null return / not loaded.
 *
 * **Restriction**: field names cannot start with `x` or `X` (those chars
 * are tokenized as STAR — the multiplication alias). This matches the
 * Kotlin DSL exactly; if any existing JsonExpression callers used a field
 * starting with `x`, the JVM path would also reject it.
 *
 * Per ADR-0004 HARD GATE: catch_unwind on Rust side; null return signals
 * the caller to fall back to the Kotlin path.
 */
internal object JsonExprNative {

    private const val TAG = "JsonExprNative"
    private const val LIB_NAME = "json_expr"

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

    /** Returns the evaluated value as String, or null on parse/eval/JNI error. */
    fun evaluate(rootJson: String, expr: String): String? {
        ensureLoaded()
        if (!loaded.get()) return null
        return evaluateNative(rootJson, expr)
    }

    fun isValid(expr: String): Boolean? {
        ensureLoaded()
        if (!loaded.get()) return null
        return isValidNative(expr)
    }

    @JvmStatic
    private external fun evaluateNative(rootJson: String, expr: String): String?

    @JvmStatic
    private external fun isValidNative(expr: String): Boolean
}
