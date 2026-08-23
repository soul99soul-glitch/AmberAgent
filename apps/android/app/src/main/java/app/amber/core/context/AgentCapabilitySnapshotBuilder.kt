package app.amber.core.context

import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessage
import app.amber.feature.task.AgentTaskStore
import app.amber.feature.tools.ToolRegistry

class AgentCapabilitySnapshotBuilder(
    private val agentTaskStore: AgentTaskStore? = null,
) {
    fun build(tools: List<Tool>, maxChars: Int = DEFAULT_MAX_CHARS): UIMessage {
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
        val raw = """
            [AmberAgent capability snapshot after context compaction]
            This snapshot refreshes current runtime abilities after compact summaries. It is not a user message.

            Available tool categories:
            ${categorySummary.ifBlank { "- No tools currently available." }}

            Sensitive / mutating boundaries:
            $boundarySummary

            Skills and workflows:
            Use tool_search to expose callable schemas for hidden tools. tools_list is catalog/debug only and does not make hidden tools callable. OfficePro, Skills, MCP, Sub Agent, Model Council and Cron are visible only when their tools appear above.

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
