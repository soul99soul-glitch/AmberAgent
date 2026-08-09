package app.amber.ai.provider.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.util.ImageEncodingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * Unit tests for GoogleProvider message building logic.
 * Tests the conversion from UIMessage list to Google Gemini API format,
 * specifically focusing on multi-round reasoning/tool scenarios.
 *
 * Google API format:
 * - role: "user" or "model"
 * - parts array containing text, functionCall, functionResponse
 * - thought: true for reasoning parts
 */
class GoogleProviderMessageTest {

    private lateinit var provider: GoogleProvider

    @Before
    fun setUp() {
        provider = GoogleProvider()
    }

    // Helper to invoke private buildContents method via reflection
    private fun invokeBuildContents(messages: List<UIMessage>): JsonArray {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildContents",
            List::class.java
        )
        method.isAccessible = true
        return method.invoke(provider, messages) as JsonArray
    }

    @Test
    fun `multi-round tool calls should produce functionCall followed by functionResponse`() {
        // Scenario: Multiple rounds of tool calls
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Let me search"),
                createExecutedTool("call_1", "search", """{"query": "test"}""", "Search result"),
                UIMessagePart.Text("Now calculating"),
                createExecutedTool("call_2", "calculate", """{"expr": "2+2"}""", "4"),
                UIMessagePart.Text("The answer is 4")
            )
        )

        val messages = listOf(
            UIMessage.user("Calculate something"),
            assistantMessage
        )

        val result = invokeBuildContents(messages)

        // Google format:
        // 1. user message
        // 2. model message with [text, functionCall(search)]
        // 3. user message with [functionResponse(search)]
        // 4. model message with [text, functionCall(calculate)]
        // 5. user message with [functionResponse(calculate)]
        // 6. model message with [text]

        // Collect all functionCall and functionResponse parts
        val functionCalls = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val functionResponses = mutableListOf<kotlinx.serialization.json.JsonObject>()

        for (msg in result) {
            val msgObj = msg.jsonObject
            val parts = msgObj["parts"]?.jsonArray ?: continue
            for (part in parts) {
                val partObj = part.jsonObject
                if (partObj.containsKey("functionCall")) {
                    functionCalls.add(partObj["functionCall"]!!.jsonObject)
                }
                if (partObj.containsKey("functionResponse")) {
                    functionResponses.add(partObj["functionResponse"]!!.jsonObject)
                }
            }
        }

        assertEquals("Should have 2 functionCall parts", 2, functionCalls.size)
        assertEquals("Should have 2 functionResponse parts", 2, functionResponses.size)

        // Verify functionCall contents
        assertEquals("search", functionCalls[0]["name"]?.jsonPrimitive?.content)
        assertEquals("calculate", functionCalls[1]["name"]?.jsonPrimitive?.content)

        // Verify functionResponse contents
        assertEquals("search", functionResponses[0]["name"]?.jsonPrimitive?.content)
        assertEquals("calculate", functionResponses[1]["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `image encoding failure should fail instead of dropping image part`() {
        try {
            invokeBuildContents(
                listOf(
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Image("content://missing-image"))
                    )
                )
            )
            fail("Expected image encoding failure")
        } catch (error: InvocationTargetException) {
            assertTrue(error.cause is ImageEncodingException)
        }
    }

    @Test
    fun `functionCall in model should be followed by user message with functionResponse`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Using tool"),
                createExecutedTool("call_abc", "my_tool", "{}", "Tool output")
            )
        )

        val messages = listOf(
            UIMessage.user("Use a tool"),
            assistantMessage
        )

        val result = invokeBuildContents(messages)

        // Find model message with functionCall
        var modelWithFunctionCallIndex = -1
        for (i in result.indices) {
            val msg = result[i].jsonObject
            if (msg["role"]?.jsonPrimitive?.content == "model") {
                val parts = msg["parts"]?.jsonArray ?: continue
                if (parts.any { it.jsonObject.containsKey("functionCall") }) {
                    modelWithFunctionCallIndex = i
                    break
                }
            }
        }

        assertTrue("Should find model with functionCall", modelWithFunctionCallIndex >= 0)
        assertTrue("Should not be last message", modelWithFunctionCallIndex < result.size - 1)

        // Next message should be user with functionResponse
        val nextMsg = result[modelWithFunctionCallIndex + 1].jsonObject
        assertEquals("user", nextMsg["role"]?.jsonPrimitive?.content)
        val nextParts = nextMsg["parts"]?.jsonArray
        assertTrue("Next message should have functionResponse",
            nextParts?.any { it.jsonObject.containsKey("functionResponse") } == true)
    }

    @Test
    fun `parallel tool calls should be in same model message`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Running multiple tools"),
                createExecutedTool("call_1", "tool_a", "{}", "Result A"),
                createExecutedTool("call_2", "tool_b", "{}", "Result B"),
                createExecutedTool("call_3", "tool_c", "{}", "Result C"),
                UIMessagePart.Text("All done")
            )
        )

        val messages = listOf(
            UIMessage.user("Do multiple things"),
            assistantMessage
        )

        val result = invokeBuildContents(messages)

        // Find model message with all functionCall parts
        var foundModelWithMultipleCalls = false
        for (msg in result) {
            val msgObj = msg.jsonObject
            if (msgObj["role"]?.jsonPrimitive?.content != "model") continue

            val parts = msgObj["parts"]?.jsonArray ?: continue
            val functionCallParts = parts.filter { it.jsonObject.containsKey("functionCall") }

            if (functionCallParts.size == 3) {
                foundModelWithMultipleCalls = true
                // Verify tool names
                val toolNames = functionCallParts.map {
                    it.jsonObject["functionCall"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                }
                assertTrue(toolNames.contains("tool_a"))
                assertTrue(toolNames.contains("tool_b"))
                assertTrue(toolNames.contains("tool_c"))
                break
            }
        }

        assertTrue("Should have model with 3 parallel functionCall parts",
            foundModelWithMultipleCalls)

        // Verify corresponding functionResponse parts in user message
        var foundUserWithMultipleResponses = false
        for (msg in result) {
            val msgObj = msg.jsonObject
            if (msgObj["role"]?.jsonPrimitive?.content != "user") continue

            val parts = msgObj["parts"]?.jsonArray ?: continue
            val responseParts = parts.filter { it.jsonObject.containsKey("functionResponse") }

            if (responseParts.size == 3) {
                foundUserWithMultipleResponses = true
                break
            }
        }

        assertTrue("Should have user with 3 functionResponse parts",
            foundUserWithMultipleResponses)
    }

    @Test
    fun `user message parts should be correctly formatted`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Text("Hello, how are you?")
                )
            )
        )

        val result = invokeBuildContents(messages)

        assertEquals(1, result.size)
        val userMsg = result[0].jsonObject
        assertEquals("user", userMsg["role"]?.jsonPrimitive?.content)

        val parts = userMsg["parts"]?.jsonArray
        assertTrue("Parts should not be null", parts != null)
        assertTrue("Parts should not be empty", parts!!.isNotEmpty())

        val textPart = parts.find { it.jsonObject.containsKey("text") }?.jsonObject
        assertEquals("Hello, how are you?", textPart?.get("text")?.jsonPrimitive?.content)
    }

    @Test
    fun `functionResponse should contain correct result structure`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                createExecutedTool("call_1", "my_tool", """{"input": "test"}""", "Expected output value")
            )
        )

        val messages = listOf(
            UIMessage.user("Use tool"),
            assistantMessage
        )

        val result = invokeBuildContents(messages)

        // Find functionResponse
        var functionResponse: kotlinx.serialization.json.JsonObject? = null
        for (msg in result) {
            val msgObj = msg.jsonObject
            val parts = msgObj["parts"]?.jsonArray ?: continue
            for (part in parts) {
                if (part.jsonObject.containsKey("functionResponse")) {
                    functionResponse = part.jsonObject["functionResponse"]?.jsonObject
                    break
                }
            }
            if (functionResponse != null) break
        }

        assertTrue("Should find functionResponse", functionResponse != null)
        assertEquals("my_tool", functionResponse!!["name"]?.jsonPrimitive?.content)

        // Verify response structure
        val response = functionResponse["response"]?.jsonObject
        assertTrue("Response should contain result",
            response?.containsKey("result") == true)
        assertTrue("Result should contain expected output",
            response?.get("result")?.jsonPrimitive?.content?.contains("Expected output value") == true)
    }

    // ==================== Helper Functions ====================

    private fun createExecutedTool(
        callId: String,
        name: String,
        input: String,
        output: String
    ): UIMessagePart.Tool {
        return UIMessagePart.Tool(
            toolCallId = callId,
            toolName = name,
            input = input,
            output = listOf(UIMessagePart.Text(output))
        )
    }
}
