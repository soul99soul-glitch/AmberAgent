package app.amber.feature.tools

import app.amber.ai.core.Tool
import app.amber.core.ai.tools.LocalTools
import app.amber.core.ai.tools.createSearchTools
import app.amber.core.settings.Settings

class AgentToolSetFactory(
    private val localTools: LocalTools,
) {
    fun forDeepRead(
        settings: Settings,
        writerTools: List<Tool>,
        descriptionContext: DeepReadToolDescriptionContext? = null,
    ): List<Tool> {
        val researchRawTools = createSearchTools(settings)
            .filter { it.name in DEEP_READ_RESEARCH_TOOL_NAMES }
            .map { it.withDeepReadDescriptionContext(descriptionContext) }
        val writerRawTools = writerTools
            .map { it.withDeepReadDescriptionContext(descriptionContext) }

        val rawTools = buildList {
            addAll(researchRawTools)
            add(localTools.timeTool)
            // Keep Deep Read hidden runs deterministic and auto-approvable. Subagents remain
            // available to normal chat, but hidden runs should not silently start background runs.
            addAll(writerRawTools)
        }

        val registry = ToolRegistry.from(rawTools)
        return registry.tools() +
            createToolSearchTool(registry) +
            localTools.registryIntrospectionTools(registry)
    }

    companion object {
        val DEEP_READ_RESEARCH_TOOL_NAMES = setOf(
            "search_web",
            "scrape_web",
            "search_sources_status",
            "search_strategy_explain",
        )
    }
}

data class DeepReadToolDescriptionContext(
    val stageLabel: String? = null,
    val writerToolName: String? = null,
    val stageTimeoutSeconds: Long,
)

internal fun Tool.withDeepReadDescriptionContext(context: DeepReadToolDescriptionContext?): Tool {
    context ?: return this
    val guidance = when {
        name == "search_web" || name == "scrape_web" -> context.searchGuidance()
        name.startsWith("deep_read_write_") -> context.writerGuidance(name)
        else -> null
    } ?: return this
    return copy(description = "$description\n\n$guidance")
}

private fun DeepReadToolDescriptionContext.searchGuidance(): String =
    "Deep Read timing: 本段预算约 $stageTimeoutSeconds 秒；只为关键事实缺口、矛盾证据或预抓正文不足调用本工具，拿到足够证据后优先调用 ${writerToolName ?: "the current deep_read_write_* tool"}。"

private fun DeepReadToolDescriptionContext.writerGuidance(toolName: String): String =
    "Deep Read timing: 本段${stageLabel?.let { "（$it）" }.orEmpty()}预算约 $stageTimeoutSeconds 秒；优先调用 ${writerToolName ?: toolName}，再只为关键事实缺口或矛盾证据补充 search_web/scrape_web。"
