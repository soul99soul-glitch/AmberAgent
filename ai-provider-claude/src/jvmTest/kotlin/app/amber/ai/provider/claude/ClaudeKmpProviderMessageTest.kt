package app.amber.ai.provider.claude

import app.amber.ai.core.MessageRole
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ClaudeKmpProviderMessageTest {

    private val provider = ClaudeKmpProvider()

    private val claudeSetting = ProviderSetting.Claude(
        id = kotlin.uuid.Uuid.parse("00000000-0000-0000-0000-000000000001"),
        name = "Claude",
        apiKey = "sk-ant-test",
        baseUrl = "https://api.anthropic.com/v1",
        promptCaching = false,
    )

    private fun reasoningModel(): Model = Model(
        modelId = "claude-sonnet-4-5",
        displayName = "Claude Sonnet 4.5",
        abilities = listOf(ModelAbility.REASONING, ModelAbility.TOOL),
    )

    @Test
    fun `buildMessageRequest sets model, max_tokens, stream, and messages`() {
        val params = TextGenerationParams(
            model = Model(modelId = "claude-sonnet-4-5", displayName = "Sonnet"),
            maxTokens = 1024,
        )
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text("you are helpful"))),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hello"))),
        )

        val body = provider.callBuildMessageRequest(claudeSetting, messages, params, stream = true)

        assertEquals("claude-sonnet-4-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(1024, body["max_tokens"]!!.jsonPrimitive.intOrNull)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        // system extracted out of messages into top-level "system" array
        assertNotNull(body["system"])
        assertEquals(1, body["system"]!!.jsonArray.size)
        // messages array excludes the SYSTEM role entry
        val msgs = body["messages"]!!.jsonArray
        assertEquals(1, msgs.size)
        assertEquals("user", msgs[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `temperature is omitted when reasoning is enabled, present otherwise`() {
        val msgs = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi"))))

        // Reasoning ON -> temperature suppressed
        val reasoningParams = TextGenerationParams(
            model = reasoningModel(),
            temperature = 0.5f,
            reasoningLevel = ReasoningLevel.MEDIUM,
        )
        val reasoningBody = provider.callBuildMessageRequest(claudeSetting, msgs, reasoningParams)
        assertNull(reasoningBody["temperature"])
        assertNotNull(reasoningBody["thinking"])
        assertEquals("adaptive", reasoningBody["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("medium", reasoningBody["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)

        // Reasoning OFF -> temperature present
        val plainParams = TextGenerationParams(
            model = Model(modelId = "m", displayName = "m", abilities = emptyList()),
            temperature = 0.7f,
        )
        val plainBody = provider.callBuildMessageRequest(claudeSetting, msgs, plainParams)
        assertEquals(0.7f, plainBody["temperature"]!!.jsonPrimitive.content.toFloat())
    }

    @Test
    fun `tools emit name, description, and input_schema`() {
        val tool = Tool(
            name = "get_weather",
            description = "Get the weather",
            parameters = {
                app.amber.ai.core.InputSchema.Obj(
                    properties = buildJsonObject { },
                    required = emptyList(),
                )
            },
            execute = { emptyList() },
        )
        val params = TextGenerationParams(
            model = reasoningModel(),
            tools = listOf(tool),
        )
        val msgs = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("weather?"))))

        val body = provider.callBuildMessageRequest(claudeSetting, msgs, params)
        val tools = body["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        val toolObj = tools[0].jsonObject
        assertEquals("get_weather", toolObj["name"]!!.jsonPrimitive.content)
        assertEquals("Get the weather", toolObj["description"]!!.jsonPrimitive.content)
        assertNotNull(toolObj["input_schema"])
        // cache_control is NOT present when promptCaching=false
        assertNull(toolObj["cache_control"])
    }

    @Test
    fun `tools disable parallel tool use`() {
        val tool = Tool(
            name = "get_weather",
            description = "Get the weather",
            parameters = {
                app.amber.ai.core.InputSchema.Obj(
                    properties = buildJsonObject { },
                    required = emptyList(),
                )
            },
            execute = { emptyList() },
        )
        val params = TextGenerationParams(model = reasoningModel(), tools = listOf(tool))
        val msgs = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("weather?"))))

        val body = provider.callBuildMessageRequest(claudeSetting, msgs, params)
        val toolChoice = body.getValue("tool_choice").jsonObject

        assertTrue(toolChoice.getValue("disable_parallel_tool_use").jsonPrimitive.boolean)
    }

    @Test
    fun `promptCaching on adds cache_control to the last tool`() {
        val cachingSetting = claudeSetting.copy(promptCaching = true)
        val tool = Tool(
            name = "t",
            description = "d",
            parameters = {
                app.amber.ai.core.InputSchema.Obj(
                    properties = buildJsonObject { },
                    required = emptyList(),
                )
            },
            execute = { emptyList() },
        )
        val params = TextGenerationParams(model = reasoningModel(), tools = listOf(tool))
        val msgs = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("x"))))

        val body = provider.callBuildMessageRequest(cachingSetting, msgs, params)
        val toolObj = body["tools"]!!.jsonArray[0].jsonObject
        val cache = toolObj["cache_control"]
        assertNotNull(cache)
        assertEquals("ephemeral", cache.jsonObject.getValue("type").jsonPrimitive.content)
    }
}

// Test-visible trampoline: buildMessageRequest is internal to the module but
// jvmTest is a separate source set that can see internal members (same module).
private fun ClaudeKmpProvider.callBuildMessageRequest(
    setting: ProviderSetting.Claude,
    messages: List<UIMessage>,
    params: TextGenerationParams,
    stream: Boolean = false,
): JsonObject = this.buildMessageRequest(setting, messages, params, stream)
