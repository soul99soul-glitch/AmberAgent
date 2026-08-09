package app.amber.feature.tools

import app.amber.ai.core.Tool
import app.amber.ai.core.createSpawnAgentToolDeclaration
import app.amber.ai.core.createListAgentsToolDeclaration
import app.amber.ai.core.createInterruptAgentToolDeclaration
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 线程编排工具的中文搜索别名与非常驻契约。 */
class OrchestrationToolSearchAliasTest {

    private val orchestrationTools: List<Tool> = listOf(
        createSpawnAgentToolDeclaration(),
        createListAgentsToolDeclaration(),
        createInterruptAgentToolDeclaration(),
    )

    private fun registry(): ToolRegistry = ToolRegistry.from(orchestrationTools)

    @Test
    fun chineseQueriesHitExpectedOrchestrationTools() {
        val index = ToolSearchIndex(registry(), null)
        mapOf(
            "子代理" to setOf("spawn_agent"),
            "线程 编排 并行" to orchestrationTools.mapTo(mutableSetOf(), Tool::name),
            "中断子代理" to setOf("interrupt_agent"),
        ).forEach { (query, expected) ->
            val expanded = index.searchPayload(query, null, 5)["expanded_tools"]!!
                .jsonArray.mapTo(mutableSetOf()) { it.jsonPrimitive.content }
            assertTrue(expanded.containsAll(expected), "query=$query expected=$expected actual=$expanded")
        }
    }

    @Test
    fun orchestrationToolsStayNonResident() {
        orchestrationTools.forEach { tool ->
            assertFalse(ToolExposureState.isResidentTool(tool.name, null), "${tool.name} 应保持非常驻")
        }
    }
}
