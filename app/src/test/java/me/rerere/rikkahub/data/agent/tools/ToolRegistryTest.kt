package me.rerere.rikkahub.data.agent.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {
    @Test
    fun duplicateToolNamesAreRejected() {
        val tools = listOf(stubTool("same"), stubTool("same"))

        val error = runCatching { ToolRegistry.from(tools) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("Duplicate tool names"))
    }

    @Test
    fun registryEnforcesOutputBudget() = runBlocking {
        val registry = ToolRegistry.from(
            listOf(
                stubTool("large_output") {
                    listOf(UIMessagePart.Text("x".repeat(90_000)))
                }
            )
        )

        val output = registry.tools().single().execute(JsonObject(emptyMap()))
        val text = (output.single() as UIMessagePart.Text).text

        assertTrue(text.length < 90_000)
        assertTrue(text.contains("tool output truncated"))
    }

    @Test
    fun registryMetadataIncludesCategoryAndBudget() {
        val registry = ToolRegistry.from(listOf(stubTool("file_read")))

        val metadata = registry.metadata.single()

        assertEquals("workspace", metadata.category)
        assertEquals(FILE_READ_HARD_MAX_CHARS + 2_048, metadata.outputBudgetChars)
    }

    @Test
    fun officeProToolsUseOfficeCategory() {
        val registry = ToolRegistry.from(
            listOf(
                stubTool("officepro_dashboard"),
                stubTool("officepro_capture_context"),
                stubTool("officepro_make_report", needsApproval = true),
            )
        )

        assertEquals(listOf("office", "office", "office"), registry.metadata.map { it.category })
        assertTrue(registry.metadata.single { it.name == "officepro_make_report" }.needsApproval)
        assertTrue(registry.metadata.single { it.name == "officepro_make_report" }.mutates)
    }

    @Test
    fun conversationContextToolsUseContextCategory() {
        val registry = ToolRegistry.from(
            listOf(
                stubTool("conversation_context_status"),
                stubTool("conversation_search"),
                stubTool("conversation_expand"),
                stubTool("conversation_compact"),
            )
        )

        assertEquals(listOf("context", "context", "context", "context"), registry.metadata.map { it.category })
        assertTrue(registry.metadata.single { it.name == "conversation_compact" }.mutates)
    }

    private fun stubTool(
        name: String,
        needsApproval: Boolean = false,
        execute: suspend (kotlinx.serialization.json.JsonElement) -> List<UIMessagePart> = {
            listOf(UIMessagePart.Text("ok"))
        },
    ) = Tool(
        name = name,
        description = "test tool",
        needsApproval = needsApproval,
        execute = execute,
    )
}
