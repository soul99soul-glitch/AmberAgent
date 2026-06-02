package app.amber.core.ai.transformers

import app.amber.feature.subagent.SubAgentMode
import app.amber.feature.subagent.SubAgentRuntimeSetting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniAppPromptTransformerTest {
    @Test
    fun onlyExplicitMiniAppRequestsTrigger() {
        assertTrue(MiniAppPromptTransformer.isExplicitMiniAppRequest("帮我做一个小应用：喝水记录器"))
        assertTrue(MiniAppPromptTransformer.isExplicitMiniAppRequest("Create a MiniApp for timers"))
        assertTrue(MiniAppPromptTransformer.isExplicitMiniAppRequest("做个小程序"))

        assertFalse(MiniAppPromptTransformer.isExplicitMiniAppRequest("做一个今日看板总结"))
        assertFalse(MiniAppPromptTransformer.isExplicitMiniAppRequest("写一个工具调用方案"))
        assertFalse(MiniAppPromptTransformer.isExplicitMiniAppRequest("生成一个计算器思路"))
    }

    @Test
    fun presentationRequestsDoNotAccidentallyTriggerMiniAppHarness() {
        assertFalse(MiniAppPromptTransformer.isExplicitMiniAppRequest("不要做小应用，给我做 guizang PPT 预览"))
        assertFalse(MiniAppPromptTransformer.isExplicitMiniAppRequest("别跑去做小应用，用 guizang-ppt-skill 做演示稿"))
        assertFalse(MiniAppPromptTransformer.isExplicitMiniAppRequest("用 guizang skill 做一个演示，别给我小程序"))

        assertTrue(MiniAppPromptTransformer.isExplicitMiniAppRequest("把这个 PPT 做成小应用"))
        assertTrue(MiniAppPromptTransformer.isExplicitMiniAppRequest("生成一个幻灯片小应用版"))
    }

    @Test
    fun extractsRevisionAppIdFromModifyPrompt() {
        val id = "123e4567-e89b-12d3-a456-426614174000"
        assertEquals(
            id,
            MiniAppPromptTransformer.revisionAppId(
                """
                修改小应用
                appId: $id
                用户修改意见：
                按钮改小一点
                """.trimIndent(),
            ),
        )
        assertEquals(
            7,
            MiniAppPromptTransformer.revisionVersion(
                """
                修改小应用
                appId: $id
                currentVersion: 7
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun miniAppInstructionRequiresSubAgentReviewWhenEnabled() {
        val rosterInstruction = MiniAppPromptTransformer.miniAppInstruction(
            SubAgentRuntimeSetting(enabled = true, mode = SubAgentMode.ROSTER)
        )
        assertTrue(rosterInstruction.contains("subagent_start"))
        assertTrue(rosterInstruction.contains("MiniAppReviewer"))
        assertTrue(rosterInstruction.contains("tool_profile=\"none\""))
        assertTrue(rosterInstruction.contains("review/debug"))
        assertTrue(rosterInstruction.contains("修一轮"))

        val dynamicInstruction = MiniAppPromptTransformer.miniAppInstruction(
            SubAgentRuntimeSetting(enabled = true, mode = SubAgentMode.SMART_DYNAMIC)
        )
        assertTrue(dynamicInstruction.contains("custom_subagent"))
        assertTrue(dynamicInstruction.contains("MiniAppReviewer"))
        assertTrue(dynamicInstruction.contains("tool_profile=\"none\""))
    }

    @Test
    fun miniAppInstructionUsesOracleWhenDynamicSubAgentsAreDisabled() {
        val instruction = MiniAppPromptTransformer.miniAppInstruction(
            SubAgentRuntimeSetting(
                enabled = true,
                mode = SubAgentMode.ROSTER,
                allowDynamicSubAgents = false,
            )
        )

        assertTrue(instruction.contains("subagent_id=\"oracle\""))
        assertTrue(instruction.contains("不读取外部文件/历史"))
    }

    @Test
    fun miniAppInstructionFallsBackToSelfCheckWithoutSubAgent() {
        val instruction = MiniAppPromptTransformer.miniAppInstruction(null)

        assertTrue(instruction.contains("SubAgent 当前不可用"))
        assertTrue(instruction.contains("生成后自检"))
        assertFalse(instruction.contains("subagent_start"))
    }
}
