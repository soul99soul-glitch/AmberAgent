package app.amber.core.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import app.amber.feature.tools.ToolRegistry
import me.rerere.rikkahub.utils.JsonInstant

/**
 * Factory for the `tool_policy_explain` agent tool — lets the model probe how
 * AmberAgent would evaluate one tool invocation (category / risk / approval
 * needs / concurrency / speculative-execution eligibility) without actually
 * executing it.
 *
 * Pure registry lookup — no class-scoped dependencies — so this is a true
 * top-level factory with no delegator needed on the LocalTools side.
 *
 * Extracted from `LocalTools.createToolPolicyExplainTool` in M1.4 continuation.
 */
fun createToolPolicyExplainTool(registry: ToolRegistry): Tool = Tool(
    name = "tool_policy_explain",
    description = "Explain how AmberAgent would evaluate one tool invocation without executing it.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("tool_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Tool name to evaluate.")
                })
                put("input", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional JSON string input for dynamic policy evaluation.")
                })
            },
            required = listOf("tool_name")
        )
    },
    execute = { input ->
        val toolName = input.jsonObject["tool_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val rawInput = input.jsonObject["input"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val toolInput = runCatching {
            JsonInstant.parseToJsonElement(rawInput.ifBlank { "{}" })
        }.getOrNull()
        val policy = registry.evaluateInvocation(toolName, toolInput)
        val payload = buildJsonObject {
            put("status", if (policy == null) "not_found" else "ok")
            put("tool_name", toolName)
            policy?.let {
                put("category", it.category)
                put("risk", it.risk.name.lowercase())
                put("mutates", it.mutates)
                put("needs_approval", it.needsApproval)
                put("allows_auto_approval", it.autoApprovable)
                put("concurrency_safe", it.concurrencySafe)
                it.parallelGroup?.let { group -> put("parallel_group", group) }
                it.requiresForegroundAppPackage?.let { pkg -> put("requires_foreground_app_package", pkg) }
                put("speculative_eligible", it.speculativeEligible)
                it.speculativeBlockReason?.let { reason -> put("speculative_block_reason", reason) }
                put("output_budget_chars", it.outputBudgetChars)
                put("hard_blocked", it.hardBlocked)
                it.reason?.let { reason -> put("reason", reason) }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
