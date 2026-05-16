package me.rerere.rikkahub.data.agent.board.agent

import me.rerere.rikkahub.data.agent.board.TodayBoardDensity
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardPromptTest {
    @Test
    fun promptTellsAgentNotToFillCapsWithNoise() {
        val prompt = BoardPrompt.build(
            scoredSignals = emptyList(),
            focusRules = emptyList(),
            density = TodayBoardDensity.COMPACT,
            nowMs = 0L,
        )

        assertTrue(prompt.contains("上限不是配额"))
        assertTrue(prompt.contains("items: []"))
        assertTrue(prompt.contains("普通聊天测试"))
        assertTrue(prompt.contains("chat_history"))
        assertTrue(prompt.contains("长文生成测试"))
    }
}
