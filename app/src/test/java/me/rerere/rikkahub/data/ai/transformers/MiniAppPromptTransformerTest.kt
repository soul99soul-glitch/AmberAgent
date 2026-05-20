package me.rerere.rikkahub.data.ai.transformers

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
}
