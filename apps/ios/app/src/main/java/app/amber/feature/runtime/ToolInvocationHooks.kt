package app.amber.feature.runtime

import android.util.Log
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.runtime.toAgentToolFailurePayload

private const val HOOK_TAG = "ToolInvocationHook"

data class ToolInvocationRequest(
    val tool: UIMessagePart.Tool,
    val toolDef: Tool?,
    val parsedArgs: JsonElement?,
    val permissionDecision: PermissionDecision,
    val invocationContext: ToolInvocationContext,
    val startedAtMs: Long,
)

data class ToolInvocationResult(
    val output: List<UIMessagePart>,
    val metadata: JsonObject = buildJsonObject {},
)

interface ToolInvocationHook {
    suspend fun before(request: ToolInvocationRequest): ToolInvocationResult? = null

    suspend fun after(
        request: ToolInvocationRequest,
        result: ToolInvocationResult,
    ): ToolInvocationResult = result

    suspend fun onError(
        request: ToolInvocationRequest,
        error: Throwable,
    ): ToolInvocationResult? = null
}

fun defaultToolInvocationHooks(): List<ToolInvocationHook> = listOf(
    ToolTraceHook(),
    ToolArgumentValidationHook(),
    ToolFailureNormalizeHook(),
)

class ToolTraceHook : ToolInvocationHook {
    override suspend fun before(request: ToolInvocationRequest): ToolInvocationResult? {
        log("start ${request.tool.toolName} action=${request.permissionDecision.action}")
        return null
    }

    override suspend fun after(
        request: ToolInvocationRequest,
        result: ToolInvocationResult,
    ): ToolInvocationResult {
        log("success ${request.tool.toolName} durationMs=${System.currentTimeMillis() - request.startedAtMs}")
        return result
    }

    override suspend fun onError(
        request: ToolInvocationRequest,
        error: Throwable,
    ): ToolInvocationResult? {
        log("failed ${request.tool.toolName} durationMs=${System.currentTimeMillis() - request.startedAtMs} error=${error.message}")
        return null
    }

    private fun log(message: String) {
        runCatching { Log.i(HOOK_TAG, message) }
    }
}

class ToolArgumentValidationHook : ToolInvocationHook {
    override suspend fun before(request: ToolInvocationRequest): ToolInvocationResult? {
        if (request.toolDef == null) {
            return validationFailure(request, "Tool ${request.tool.toolName} not found")
        }
        val args = request.parsedArgs ?: return validationFailure(request, "Tool arguments were not parsed")
        if (runCatching { args.jsonObject }.isFailure) {
            return validationFailure(request, "Tool arguments must be a JSON object")
        }
        return null
    }

    private fun validationFailure(request: ToolInvocationRequest, message: String): ToolInvocationResult =
        ToolInvocationResult(
            output = listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("status", "failed")
                        put("message", message)
                        put("recoverable", false)
                        put("permission_trace", request.permissionDecision.trace.toJson())
                    }.toString()
                )
            )
        )
}

class ToolFailureNormalizeHook : ToolInvocationHook {
    override suspend fun onError(
        request: ToolInvocationRequest,
        error: Throwable,
    ): ToolInvocationResult =
        ToolInvocationResult(
            output = listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        error.toAgentToolFailurePayload().forEach { (key, value) -> put(key, value) }
                        put("permission_trace", request.permissionDecision.trace.toJson())
                    }.toString()
                )
            )
        )
}
