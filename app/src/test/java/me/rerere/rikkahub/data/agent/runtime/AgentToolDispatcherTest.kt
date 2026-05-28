package app.amber.feature.runtime

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationRetrySetting
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

    @Test
    fun safeReadOnlyToolRetriesTransientFailure() = runBlocking {
        var attempts = 0
        val result = dispatcher.execute(
            tool = toolCall("file_read"),
            toolDef = Tool(
                name = "file_read",
                description = "",
                execute = {
                    attempts++
                    if (attempts == 1) error("HTTP 503 temporarily unavailable")
                    listOf(UIMessagePart.Text("ok"))
                },
            ),
            retrySetting = retrySetting(),
        )!!

        assertEquals(2, attempts)
        assertEquals("ok", (result.output.single() as UIMessagePart.Text).text)
    }

    @Test
    fun mutatingToolDoesNotAutoRetry() = runBlocking {
        var attempts = 0
        val result = dispatcher.execute(
            tool = toolCall("memory_tool", input = """{"action":"create"}"""),
            toolDef = Tool(
                name = "memory_tool",
                description = "",
                execute = {
                    attempts++
                    error("HTTP 503 temporarily unavailable")
                },
            ),
            retrySetting = retrySetting(),
        )!!
        val text = (result.output.single() as UIMessagePart.Text).text

        assertEquals(1, attempts)
        assertTrue(text.contains("\"status\":\"failed\""))
    }

    @Test
    fun hookObservesSuccessfulAndFailedCalls() = runBlocking {
        val events = mutableListOf<String>()
        val hooked = AgentToolDispatcher(
            Json { ignoreUnknownKeys = true },
            PermissionDecisionResolver(),
            hooks = listOf(
                object : ToolInvocationHook {
                    override suspend fun before(request: ToolInvocationRequest): ToolInvocationResult? {
                        events += "before:${request.tool.toolName}"
                        return null
                    }

                    override suspend fun after(
                        request: ToolInvocationRequest,
                        result: ToolInvocationResult,
                    ): ToolInvocationResult {
                        events += "after:${request.tool.toolName}"
                        return result
                    }

                    override suspend fun onError(
                        request: ToolInvocationRequest,
                        error: Throwable,
                    ): ToolInvocationResult? {
                        events += "error:${request.tool.toolName}"
                        return null
                    }
                }
            ),
        )

        hooked.execute(toolCall("file_read"), tool("file_read", "ok"))
        hooked.execute(
            toolCall("conversation_search"),
            Tool(name = "conversation_search", description = "", execute = { error("boom") }),
        )

        assertEquals(
            listOf("before:file_read", "after:file_read", "before:conversation_search", "error:conversation_search"),
            events,
        )
    }

    @Test
    fun validationHookCanReturnStructuredFailure() = runBlocking {
        val hooked = AgentToolDispatcher(
            Json { ignoreUnknownKeys = true },
            PermissionDecisionResolver(),
            hooks = listOf(ToolArgumentValidationHook()),
        )

        val result = hooked.execute(toolCall("missing_tool"), toolDef = null)!!
        val text = (result.output.single() as UIMessagePart.Text).text

        assertTrue(text.contains("\"status\":\"failed\""))
        assertTrue(text.contains("missing_tool"))
        assertTrue(text.contains("permission_trace"))
    }

    @Test
    fun hookFailureDoesNotInterruptToolExecutionButCancellationPropagates() = runBlocking {
        val throwingHook = object : ToolInvocationHook {
            override suspend fun before(request: ToolInvocationRequest): ToolInvocationResult? {
                error("hook failed")
            }
        }
        val hooked = AgentToolDispatcher(
            Json { ignoreUnknownKeys = true },
            PermissionDecisionResolver(),
            hooks = listOf(throwingHook),
        )

        val result = hooked.execute(toolCall("file_read"), tool("file_read", "ok"))!!
        assertEquals("ok", (result.output.single() as UIMessagePart.Text).text)

        val cancellingHook = object : ToolInvocationHook {
            override suspend fun before(request: ToolInvocationRequest): ToolInvocationResult? {
                throw CancellationException("stop")
            }
        }
        val cancellingDispatcher = AgentToolDispatcher(
            Json { ignoreUnknownKeys = true },
            PermissionDecisionResolver(),
            hooks = listOf(cancellingHook),
        )

        try {
            cancellingDispatcher.execute(toolCall("file_read"), tool("file_read", "ok"))
        } catch (error: CancellationException) {
            assertEquals("stop", error.message)
            return@runBlocking
        }
        error("CancellationException was not propagated")
    }

    private fun tool(name: String, output: String) = Tool(
        name = name,
        description = "",
        execute = { listOf(UIMessagePart.Text(output)) },
    )

    private fun retrySetting() = GenerationRetrySetting(
        enabled = true,
        maxRetries = 5,
        initialDelayMs = 1L,
        maxDelayMs = 1L,
        jitterRatio = 0f,
    )

    private fun toolCall(name: String, id: String = "call_$name", input: String = "{}") = UIMessagePart.Tool(
        toolCallId = id,
        toolName = name,
        input = input,
    )
}
