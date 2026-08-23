package app.amber.ai.provider.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.MessageRole
import app.amber.ai.core.SYSTEM_PROMPT_CACHE_CONTROL_METADATA
import app.amber.ai.core.SYSTEM_PROMPT_CACHE_DISABLED
import app.amber.ai.core.SYSTEM_PROMPT_CACHE_EPHEMERAL
import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClaudeProviderPromptCacheTest {
    private lateinit var provider: ClaudeProvider

    @Before
    fun setUp() {
        provider = ClaudeProvider(OkHttpClient())
    }

    private fun buildRequest(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean = false
    ): JsonObject {
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessageRequest",
            ProviderSetting.Claude::class.java,
            List::class.java,
            TextGenerationParams::class.java,
            Boolean::class.javaPrimitiveType!!
        )
        method.isAccessible = true
        return method.invoke(provider, providerSetting, messages, params, stream) as JsonObject
    }

    private fun dummyTool(): Tool {
        return Tool(
            name = "dummy_tool",
            description = "dummy",
            parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
            execute = { emptyList() }
        )
    }

    @Test
    fun `promptCaching=false should not add cache_control anywhere`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = false)
        val messages = listOf(
            UIMessage.system("system prompt"),
            UIMessage.user("hello")
        )
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(dummyTool())
        )

        val request = buildRequest(providerSetting, messages, params)

        // system should not have cache_control
        val system = request["system"]?.jsonArray
        assertNotNull(system)
        assertTrue(system!!.isNotEmpty())
        assertNull(system.last().jsonObject["cache_control"])

        // tools should not have cache_control
        val tools = request["tools"]?.jsonArray
        assertNotNull(tools)
        assertTrue(tools!!.isNotEmpty())
        assertNull(tools.last().jsonObject["cache_control"])

        // messages should not have cache_control
        val msgs = request["messages"]!!.jsonArray
        msgs.forEach { msg ->
            val content = msg.jsonObject["content"]?.jsonArray
            content?.forEach { block ->
                assertNull(block.jsonObject["cache_control"])
            }
        }
    }

    @Test
    fun `promptCaching=true should not cache unmarked system block but should cache last tool`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = true)
        val messages = listOf(
            UIMessage.system("system prompt"),
            UIMessage.user("hello")
        )
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(dummyTool())
        )

        val request = buildRequest(providerSetting, messages, params)

        // system should only be cached when a static block is explicitly marked
        val system = request["system"]!!.jsonArray
        assertNull(system.last().jsonObject["cache_control"])

        // tools should have cache_control
        val tools = request["tools"]!!.jsonArray
        val toolsCacheControl = tools.last().jsonObject["cache_control"]!!.jsonObject
        assertEquals("ephemeral", toolsCacheControl["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `promptCaching=true should honor explicit system cache marker`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = true)
        val messages = listOf(
            UIMessage(
                role = MessageRole.SYSTEM,
                parts = listOf(
                    UIMessagePart.Text(
                        text = "static prompt",
                        metadata = buildJsonObject {
                            put(SYSTEM_PROMPT_CACHE_CONTROL_METADATA, SYSTEM_PROMPT_CACHE_EPHEMERAL)
                        },
                    ),
                    UIMessagePart.Text("dynamic reminder"),
                    UIMessagePart.Text("tool schema prompt"),
                ),
            ),
            UIMessage.user("hello")
        )
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = emptyList()),
            tools = emptyList()
        )

        val request = buildRequest(providerSetting, messages, params)
        val system = request["system"]!!.jsonArray

        assertEquals("static prompt", system[0].jsonObject["text"]!!.jsonPrimitive.content)
        val staticCacheControl = system[0].jsonObject["cache_control"]!!.jsonObject
        assertEquals("ephemeral", staticCacheControl["type"]!!.jsonPrimitive.content)
        assertNull(system[1].jsonObject["cache_control"])
        assertNull(system[2].jsonObject["cache_control"])
    }

    @Test
    fun `promptCaching=true should disable system cache when disabled marker exists`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = true)
        val messages = listOf(
            UIMessage(
                role = MessageRole.SYSTEM,
                parts = listOf(
                    UIMessagePart.Text(
                        text = "before injection",
                        metadata = buildJsonObject {
                            put(SYSTEM_PROMPT_CACHE_CONTROL_METADATA, SYSTEM_PROMPT_CACHE_DISABLED)
                        },
                    ),
                    UIMessagePart.Text(
                        text = "static prompt",
                        metadata = buildJsonObject {
                            put(SYSTEM_PROMPT_CACHE_CONTROL_METADATA, SYSTEM_PROMPT_CACHE_EPHEMERAL)
                        },
                    ),
                    UIMessagePart.Text("dynamic reminder"),
                ),
            ),
            UIMessage.user("hello")
        )
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = emptyList()),
            tools = emptyList()
        )

        val request = buildRequest(providerSetting, messages, params)
        val system = request["system"]!!.jsonArray

        system.forEach { part ->
            assertNull(part.jsonObject["cache_control"])
        }
    }

    @Test
    fun `promptCaching=true without system should add cache_control to last tool`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = true)
        val messages = listOf(UIMessage.user("hello"))
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(dummyTool())
        )

        val request = buildRequest(providerSetting, messages, params)

        assertNull(request["system"])

        val tools = request["tools"]!!.jsonArray
        val cacheControl = tools.last().jsonObject["cache_control"]!!.jsonObject
        assertEquals("ephemeral", cacheControl["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `promptCaching=true should add cache_control to second-to-last real user message`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = true)
        val messages = listOf(
            UIMessage.system("system prompt"),
            UIMessage.user("first question"),
            UIMessage.assistant("first answer"),
            UIMessage.user("second question"),
            UIMessage.assistant("second answer"),
            UIMessage.user("third question")
        )
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = emptyList()),
            tools = emptyList()
        )

        val request = buildRequest(providerSetting, messages, params)
        val msgs = request["messages"]!!.jsonArray

        // Find all real user messages (not tool_result)
        val userMsgIndices = msgs.mapIndexedNotNull { index, msg ->
            val obj = msg.jsonObject
            if (obj["role"]?.jsonPrimitive?.content == "user") {
                val content = obj["content"]?.jsonArray
                val isToolResult = content?.any {
                    it.jsonObject["type"]?.jsonPrimitive?.content == "tool_result"
                } == true
                if (!isToolResult) index else null
            } else null
        }

        // Should have 3 real user messages
        assertEquals(3, userMsgIndices.size)

        // Second-to-last (index 1 in userMsgIndices) should have cache_control
        val targetMsg = msgs[userMsgIndices[1]].jsonObject
        val content = targetMsg["content"]!!.jsonArray
        val cacheControl = content.last().jsonObject["cache_control"]!!.jsonObject
        assertEquals("ephemeral", cacheControl["type"]!!.jsonPrimitive.content)

        // Last user message should NOT have cache_control
        val lastMsg = msgs[userMsgIndices[2]].jsonObject
        val lastContent = lastMsg["content"]!!.jsonArray
        assertNull(lastContent.last().jsonObject["cache_control"])

        // First user message should NOT have cache_control
        val firstMsg = msgs[userMsgIndices[0]].jsonObject
        val firstContent = firstMsg["content"]!!.jsonArray
        assertNull(firstContent.last().jsonObject["cache_control"])
    }

    @Test
    fun `promptCaching=true with only one user message should not add cache_control to messages`() {
        val providerSetting = ProviderSetting.Claude(promptCaching = true)
        val messages = listOf(
            UIMessage.system("system prompt"),
            UIMessage.user("only question")
        )
        val params = TextGenerationParams(
            model = Model(modelId = "claude-test", abilities = emptyList()),
            tools = emptyList()
        )

        val request = buildRequest(providerSetting, messages, params)
        val msgs = request["messages"]!!.jsonArray

        // Only one user message, so no cache_control on messages
        msgs.forEach { msg ->
            val content = msg.jsonObject["content"]?.jsonArray
            content?.forEach { block ->
                assertNull(block.jsonObject["cache_control"])
            }
        }
    }
}
