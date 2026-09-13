package app.amber.feature.ui.components.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubAgentDockOverviewPresentationTest {
    @Test
    fun overviewRetainsFindingsButExcludesReasoningAndProtocolContent() {
        val original = SubAgentDockDetails(
            objective = "An instruction is not a progress update",
            summary = """
                > Internal reasoning
                ```json
                {"arguments":{"command":"raw command"}}
                ```
                ## **已核对连接状态**
                subagent_report
            """.trimIndent(),
            stages = listOf(
                SubAgentDockStage(SubAgentDockStageKind.REASONING, "Reasoning", "Internal reasoning"),
                SubAgentDockStage(SubAgentDockStageKind.TOOL, "Tool", "Raw tool output"),
                SubAgentDockStage(SubAgentDockStageKind.FINDING, "检查连接", "服务已恢复。"),
            ),
            output = "Original transcript",
            available = true,
        )
        val overview = original.readableOverview()
        assertNull(overview.objective)
        assertEquals("已核对连接状态", overview.summary)
        assertEquals(listOf("服务已恢复。"), overview.stages.map { it.text })
        assertEquals("", overview.output)
        assertEquals("Original transcript", original.output)
    }
}
