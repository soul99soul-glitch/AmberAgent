package app.amber.core.context

import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessage
import app.amber.feature.task.AgentTaskStore
import app.amber.feature.tools.ToolRegistry

class AgentCapabilitySnapshotBuilder(
    private val agentTaskStore: AgentTaskStore? = null,
) {
    suspend fun build(tools: List<Tool>, maxChars: Int = DEFAULT_MAX_CHARS): UIMessage {
        return build(tools = tools, tasks = agentTaskStore?.list().orEmpty(), maxChars = maxChars)
    }

    internal fun build(
        tools: List<Tool>,
        tasks: List<app.amber.feature.task.AgentTaskSnapshot>,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): UIMessage {
        val metadata = runCatching { ToolRegistry.from(tools).metadata }.getOrDefault(emptyList())
        val categorySummary = metadata
            .groupBy { it.category }
            .entries
            .sortedBy { it.key }
            .joinToString("\n") { (category, items) ->
                "- $category: ${items.take(12).joinToString(", ") { it.name }}${if (items.size > 12) " (+${items.size - 12})" else ""}"
            }
        val boundarySummary = metadata
            .filter { it.mutates || it.risk.name != "Normal" }
            .take(24)
            .joinToString(", ") { "${it.name}:${it.risk.name.lowercase()}" }
            .ifBlank { "No mutating or sensitive tools in this run." }
        val taskSummary = tasks.take(8).joinToString("\n") { task ->
            val retry = if (task.retryPolicy.retryable) " · retryable" else ""
            val output = if (task.outputRef?.exists == true) " · output readable" else ""
            "- ${task.taskId} · ${task.type} · ${task.status.name.lowercase()} · ${task.recoveryState.name.lowercase()}$retry$output · ${task.title.take(80)}"
        }.ifBlank {
            "- No known background tasks."
        }
        val screenAutomationGuidance = if (metadata.any { it.name == "screen_run_goal" }) {
            """

            Screen automation:
            - When Jev screen automation is enabled, prefer screen_run_goal for bounded user-requested screen goals. It reads a limited accessibility-node snapshot and chooses among safe candidates in a finite loop.
            - Jev is a judgment service, not a JavaScript engine. If it is disabled or unavailable, use the individual screen tools and their normal permission gates.
            """.trimIndent()
        } else {
            ""
        }
        val webAutomationGuidance = if (metadata.any { it.name == "wm_run_goal" }) {
            """

            Web automation:
            - When Jev web automation is enabled, prefer wm_run_goal for bounded user-requested web goals on an existing WebMount session. It observes the live page and chooses among low-risk actions in a finite loop.
            - Jev is a judgment service, not a JavaScript engine. If it is disabled or unavailable, use the individual wm_* tools and their normal permission gates.
            """.trimIndent()
        } else {
            ""
        }
        val raw = """
            [AmberAgent capability snapshot after context compaction]
            This snapshot refreshes current runtime abilities after compact summaries. It is not a user message.

            Available tool categories:
            ${categorySummary.ifBlank { "- No tools currently available." }}

            Sensitive / mutating boundaries:
            $boundarySummary

            Skills and workflows:
            Use tool_search to expose callable schemas for hidden tools. tools_list is catalog/debug only and does not make hidden tools callable. Skills, MCP, Sub Agent, Model Council and Cron are visible only when their tools appear above.
$screenAutomationGuidance$webAutomationGuidance

            Background tasks:
            $taskSummary
        """.trimIndent()
        val text = if (raw.length <= maxChars) {
            "$raw\n\ntruncated=false"
        } else {
            raw.take(maxChars - 48) + "\n\ntruncated=true"
        }
        return UIMessage.system(text)
    }

    private companion object {
        const val DEFAULT_MAX_CHARS = 8_000
    }
}
