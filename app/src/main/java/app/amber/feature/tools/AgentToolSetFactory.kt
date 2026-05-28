package app.amber.feature.tools

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import app.amber.core.settings.Settings

class AgentToolSetFactory(
    private val localTools: LocalTools,
) {
    fun forDeepRead(
        settings: Settings,
        writerTools: List<Tool>,
    ): List<Tool> {
        val researchRawTools = createSearchTools(settings)
            .filter { it.name in DEEP_READ_RESEARCH_TOOL_NAMES }

        val rawTools = buildList {
            addAll(researchRawTools)
            add(localTools.timeTool)
            // Keep Deep Read hidden runs deterministic and auto-approvable. Subagents remain
            // available to normal chat, but hidden runs should not silently start background runs.
            addAll(writerTools)
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
