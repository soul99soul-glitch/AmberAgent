package app.amber.feature.jscell

import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSException
import com.whl.quickjs.wrapper.QuickJSObject

/**
 * P4-03 engine contract (parity plan §10 P4-03).
 *
 * A session is one isolated JS runtime bound to one cell. `evaluate` is
 * synchronous (QuickJS semantics) and is always called from the cell's own
 * worker thread; `close` is called from the same thread once the cell is
 * terminated/finished, never concurrently with `evaluate`.
 *
 * The interface keeps the runtime state machine unit-testable on the JVM —
 * QuickJS native libraries cannot load in Robolectric — and lets tests inject
 * a fake engine that simulates long-running code, memory-limit failures and
 * nested tool calls.
 */
interface JsCellEngine {
    /**
     * @param console receives console.log/info/warn/error lines during evaluate
     * (already individually capped; the runtime applies the total cap).
     * @param callTool host function `js_call_tool(name, argsJson)` exposed to JS;
     * returns a JSON string (result payload or structured error). Never throws.
     */
    fun createSession(
        limits: JsCellLimits,
        console: (String) -> Unit,
        callTool: (toolName: String, argsJson: String) -> String,
    ): JsCellSession
}

interface JsCellSession : AutoCloseable {
    /** Runs one program; returns the outcome. Must not be called concurrently. */
    fun evaluate(code: String): JsEvalOutcome

    override fun close()
}

sealed class JsEvalOutcome {
    data class Completed(val result: String) : JsEvalOutcome()
    data class Failed(val error: String) : JsEvalOutcome()
}

/**
 * QuickJS-backed engine (ES2020). Hard limits enforced here:
 *
 *  - memory: [JsCellLimits.memoryBytes] via QuickJS setMemoryLimit;
 *  - nesting/stack depth: [JsCellLimits.maxStackSize] via setMaxStackSize;
 *  - host surface: only `js_call_tool` + console are exposed to JS — the cell
 *    never receives app-internal objects, and there is no network or
 *    filesystem API (nothing is registered, so nothing can be reached).
 *
 * Runtime time limits cannot preempt a blocking QuickJS evaluate (the wrapper
 * exposes no interrupt handler), so the runtime enforces them at the state
 * machine: on timeout the cell is TERMINATED and the session is closed by the
 * worker thread as soon as the current evaluate returns.
 */
class QuickJsCellEngine : JsCellEngine {
    override fun createSession(
        limits: JsCellLimits,
        console: (String) -> Unit,
        callTool: (toolName: String, argsJson: String) -> String,
    ): JsCellSession = QuickJsSession(limits, console, callTool)

    private class QuickJsSession(
        private val limits: JsCellLimits,
        private val console: (String) -> Unit,
        private val callTool: (String, String) -> String,
    ) : JsCellSession {
        private val context: QuickJSContext = QuickJSContext.create().apply {
            setMemoryLimit(limits.memoryBytes)
            setMaxStackSize(limits.maxStackSize)
            setConsole(object : QuickJSContext.Console {
                override fun log(info: String?) = console("[LOG] $info")
                override fun info(info: String?) = console("[INFO] $info")
                override fun warn(info: String?) = console("[WARN] $info")
                override fun error(info: String?) = console("[ERROR] $info")
            })
            getGlobalObject().setProperty(
                JS_CALL_TOOL_HOST_NAME,
                JSCallFunction { args ->
                    val name = args.getOrNull(0)?.toString() ?: ""
                    val argsJson = args.getOrNull(1)?.toString() ?: "{}"
                    callTool(name, argsJson)
                },
            )
        }

        @Synchronized
        override fun evaluate(code: String): JsEvalOutcome = try {
            val result = context.evaluate(code)
            val payload = when (result) {
                null -> "null"
                is QuickJSObject -> result.stringify()
                else -> result.toString()
            }
            JsEvalOutcome.Completed(payload)
        } catch (error: QuickJSException) {
            // Native hard-limit errors (memory/stack) surface as QuickJSException.
            JsEvalOutcome.Failed(error.message ?: error.toString())
        } catch (error: Throwable) {
            JsEvalOutcome.Failed(error.message ?: error.toString())
        }

        @Synchronized
        override fun close() {
            runCatching { context.close() }
        }

        private companion object {
            const val JS_CALL_TOOL_HOST_NAME = "js_call_tool"
        }
    }
}
