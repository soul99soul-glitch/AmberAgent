package app.amber.feature.tools

import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.core.createSpawnAgentToolDeclaration
import app.amber.ai.core.createListAgentsToolDeclaration
import app.amber.ai.core.createInterruptAgentToolDeclaration
import app.amber.ai.ui.UIMessagePart
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P1-c: 线程编排工具的中文 tool_search 命中契约。六个编排名新增中文词条
 * （子代理/线程/编排/并行 等，纯增量），中文 query 必须命中 spawn_agent；
 * 且编排工具保持非常驻（不在首轮可见名单）。
 */
class OrchestrationToolSearchAliasTest {

    private val orchestrationTools: List<Tool> = listOf(
        createSpawnAgentToolDeclaration(),
        createListAgentsToolDeclaration(),
        createInterruptAgentToolDeclaration(),
    )

    private fun registry(): ToolRegistry = ToolRegistry.from(orchestrationTools)

    @Test
    fun chineseQueryHitsSpawnAgent() {
        val index = ToolSearchIndex(registry(), null)
        val payload = index.searchPayload("子代理", null, 5)

        assertEquals("ok", payload["status"]?.jsonPrimitive?.contentOrNull)
        val expanded = payload["expanded_tools"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull }
        assertTrue("spawn_agent" in expanded, "中文 query「子代理」必须命中 spawn_agent，实际: $expanded")
    }

    @Test
    fun threadAndOrchestrationQueriesHitAllThree() {
        val index = ToolSearchIndex(registry(), null)
        val expanded = index.searchPayload("线程 编排 并行", null, 5)
            .let { it["expanded_tools"]!!.jsonArray.map { el -> el.jsonPrimitive.contentOrNull } }

        assertEquals(
            setOf("spawn_agent", "list_agents", "interrupt_agent"),
            expanded.toSet(),
            "「线程 编排 并行」必须命中全部三个编排名，实际: $expanded",
        )
    }

    @Test
    fun interruptQueriesHitInterruptAgent() {
        val index = ToolSearchIndex(registry(), null)
        val expanded = index.searchPayload("中断子代理", null, 5)
            .let { it["expanded_tools"]!!.jsonArray.map { el -> el.jsonPrimitive.contentOrNull } }
        assertTrue("interrupt_agent" in expanded, "「中断子代理」必须命中 interrupt_agent，实际: $expanded")
    }

    @Test
    fun orchestrationToolsStayNonResident() {
        assertFalse(
            ToolExposureState.isResidentTool("spawn_agent", null),
            "spawn_agent 非常驻（进 deferred 池，tool_search 命中后才可见）",
        )
        assertFalse(ToolExposureState.isResidentTool("list_agents", null))
        assertFalse(ToolExposureState.isResidentTool("interrupt_agent", null))
    }
}
