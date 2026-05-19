package me.rerere.rikkahub.data.agent.board.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class BoardPromptTest {
    @Test
    fun promptTellsAgentNotToFillCapsWithNoise() {
        val prompt = BoardPrompt.build(
            scoredSignals = emptyList(),
            focusRules = emptyList(),
            nowMs = 0L,
        )

        assertTrue(prompt.contains("最多 5 条"))
        assertTrue(prompt.contains("items: []"))
        assertTrue(prompt.contains("普通聊天"))
        assertTrue(prompt.contains("chat_history"))
        assertTrue(prompt.contains("长文生成测试"))
        assertTrue(prompt.contains("source_ref"))
        assertTrue(prompt.contains("signal_time"))
    }
}
