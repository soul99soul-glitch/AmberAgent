package app.amber.feature.runtime

import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.ssh.SshProfileStore
import app.amber.feature.terminal.SshRouteResolver
import app.amber.feature.terminal.TerminalRuntimeKind
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Binds a terminal approval to the selected runtime and every hop's durable configuration. */
class SshApprovalTargetGuard(
    private val profiles: SshProfileStore,
    private val defaultRuntime: () -> TerminalRuntimeKind,
) {
    suspend fun capture(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        if (!handles(tool.toolName)) return tool
        val target = try {
            snapshot(tool.toolName, tool.input)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Invalid routes remain unexecutable even if someone fixes them while
            // this approval is parked. A fresh call must describe the new target.
            buildJsonObject { put("invalid", true) }
        }
        return tool.copy(metadata = JsonObject(tool.metadata.orEmpty() + (METADATA_KEY to target)))
    }

    suspend fun isCurrent(tool: UIMessagePart.Tool): Boolean {
        if (!handles(tool.toolName)) return true
        val expected = tool.metadata?.get(METADATA_KEY) as? JsonObject
            ?: return tool.approvalState != ToolApprovalState.Approved
        return try {
            expected["invalid"] == null && expected == snapshot(tool.toolName, tool.input)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            false
        }
    }

    private suspend fun snapshot(toolName: String, input: String): JsonObject {
        val args = Json.parseToJsonElement(input) as JsonObject
        val profileId = args.string("profile_id") ?: args.string("ssh_profile_id")
        val runtime = args.string("runtime")?.let { requireNotNull(TerminalRuntimeKind.fromWire(it)) }
            ?: if (profileId != null) TerminalRuntimeKind.REMOTE_SSH else if (toolName == "terminal_execute") TerminalRuntimeKind.BUILTIN_ALPINE else defaultRuntime()
        return buildJsonObject {
            put("runtime", runtime.wireName)
            if (runtime == TerminalRuntimeKind.REMOTE_SSH) {
                val route = profiles.resolveRoute(profileId)
                put("profile_id", route.last().id)
                put("route_digest", SshRouteResolver.routeDigest(route))
            }
        }
    }

    companion object {
        const val METADATA_KEY = "ssh_approval_target"
        const val ROUTE_DIGEST_INPUT = "__amber_ssh_route_digest"
        fun handles(name: String) = name == "terminal_execute" || name == "terminal_job_start"

        fun executionArgs(tool: UIMessagePart.Tool, args: JsonElement): JsonElement {
            val target = tool.metadata?.get(METADATA_KEY) as? JsonObject ?: return args
            val digest = target["route_digest"] ?: return args
            return JsonObject((args as JsonObject) + (ROUTE_DIGEST_INPUT to digest))
        }
    }
}

private fun JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
