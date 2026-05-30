package app.amber.core.json.expr

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JNI bridge to the `json-expr` Rust crate (TD.Rust.2).
 *
 * Drop-in for `app.amber.common.http.JsonExpression`'s
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
object JsonExprNative {

    private const val TAG = "JsonExprNative"
    private const val LIB_NAME = "json_expr"
    const val COMPONENT_NAME: String = "json_expr"

    interface Config {
        fun enabled(): Boolean
        fun onLoadFailure(error: Throwable)
        fun onNativePanic(stage: String, error: Throwable?)
    }

    object DisabledConfig : Config {
        override fun enabled(): Boolean = false
        override fun onLoadFailure(error: Throwable) {}
        override fun onNativePanic(stage: String, error: Throwable?) {}
    }

    @Volatile
    var config: Config = DisabledConfig

    private val loaded = AtomicBoolean(false)
    private val loadFailureReported = AtomicBoolean(false)
    @Volatile private var loadAttempted = false

    val available: Boolean
        get() {
            val cfg = config
            if (!cfg.enabled()) return false
            return checkAvailability(cfg)
        }

    private fun checkAvailability(cfg: Config): Boolean {
        ensureLoaded(cfg)
        return loaded.get()
    }

    private fun ensureLoaded(cfg: Config) {
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
                if (loadFailureReported.compareAndSet(false, true)) {
                    cfg.onLoadFailure(t)
                }
            }
        }
    }

    /** Returns the evaluated value as String, or null on parse/eval/JNI error. */
    fun evaluate(rootJson: String, expr: String): String? {
        val cfg = config
        if (!cfg.enabled() || !checkAvailability(cfg)) return null
        return try {
            evaluateNative(rootJson, expr)
        } catch (t: Throwable) {
            Log.w(TAG, "native evaluate threw — falling back to JVM", t)
            cfg.onNativePanic("evaluate", t)
            null
        }
    }

    fun isValid(expr: String): Boolean? {
        val cfg = config
        if (!cfg.enabled() || !checkAvailability(cfg)) return null
        return try {
            isValidNative(expr)
        } catch (t: Throwable) {
            Log.w(TAG, "native isValid threw — falling back to JVM", t)
            cfg.onNativePanic("is_valid", t)
            null
        }
    }

    @JvmStatic
    private external fun evaluateNative(rootJson: String, expr: String): String?

    @JvmStatic
    private external fun isValidNative(expr: String): Boolean
}
