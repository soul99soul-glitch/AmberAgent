package me.rerere.rikkahub.data.agent.runtime

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolDispatcherTest {
    private val dispatcher = AgentToolDispatcher(Json { ignoreUnknownKeys = true }, PermissionDecisionResolver())

    @Test
    fun executeAddsPermissionTraceMetadata() = runBlocking {
        val result = dispatcher.execute(
            tool = toolCall("file_read"),
            toolDef = tool("file_read", "ok"),
            autoApproveTools = false,
        )!!

        val trace = result.metadata!!["permission_trace"]!!.jsonObject

        assertEquals("file_read", trace["tool_name"]!!.jsonPrimitive.content)
        assertEquals("allow", trace["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun executeBatchKeepsOriginalOrderForReadOnlyTools() = runBlocking {
        val tools = listOf(toolCall("file_read", "a"), toolCall("conversation_search", "b"))
        val defs = mapOf(
            "file_read" to tool("file_read", "first"),
            "conversation_search" to tool("conversation_search", "second"),
        )

        val result = dispatcher.executeBatch(
            tools = tools,
            toolDefinitions = defs,
            autoApproveTools = false,
        )

        assertEquals(listOf("a", "b"), result.map { it.toolCallId })
        assertEquals("first", (result[0].output.single() as UIMessagePart.Text).text)
        assertEquals("second", (result[1].output.single() as UIMessagePart.Text).text)
    }

    @Test
    fun toolExceptionReturnsShortStructuredFailure() = runBlocking {
        val result = dispatcher.execute(
            tool = toolCall("file_read"),
            toolDef = Tool(
                name = "file_read",
                description = "",
                execute = { error("boom at me.rerere.Internal(File.kt:1)") },
            ),
        )!!
        val text = (result.output.single() as UIMessagePart.Text).text

        assertTrue(text.contains("\"status\":\"failed\""))
        assertTrue(!text.contains("at me.rerere."))
        assertTrue(text.contains("permission_trace"))
    }

    private fun tool(name: String, output: String) = Tool(
        name = name,
        description = "",
        execute = { listOf(UIMessagePart.Text(output)) },
    )

    private fun toolCall(name: String, id: String = "call_$name") = UIMessagePart.Tool(
        toolCallId = id,
        toolName = name,
        input = "{}",
    )
}
