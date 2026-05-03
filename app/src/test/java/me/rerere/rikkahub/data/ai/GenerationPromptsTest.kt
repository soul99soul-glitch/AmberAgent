package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationPromptsTest {
    @Test
    fun soulPromptWrapsAgentsMarkdown() {
        val prompt = buildAgentSoulPrompt("# agents.md\n\nStay agentic.")

        assertTrue(prompt.contains("<agents_md>"))
        assertTrue(prompt.contains("Stay agentic."))
        assertTrue(prompt.contains("</agents_md>"))
    }

    @Test
    fun memoryPromptSkipsEmptyBuckets() {
        val prompt = buildLongTermMemoryPrompt(emptyList())

        assertTrue(prompt.isEmpty())
    }

    @Test
    fun memoryPromptLabelsLayer() {
        val prompt = buildShortTermMemoryPrompt(
            listOf(AssistantMemory(id = 7, content = "Current task is packaging a skill."))
        )

        assertTrue(prompt.contains("Short-Term Memories"))
        assertTrue(prompt.contains("Current task is packaging a skill."))
        assertFalse(prompt.contains("Long-Term Memories"))
    }
}
