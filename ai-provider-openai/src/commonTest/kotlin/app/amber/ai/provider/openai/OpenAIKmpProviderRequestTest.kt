package app.amber.ai.provider.openai

import app.amber.ai.core.MessageRole
import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse

class OpenAIKmpProviderRequestTest {
    private val provider = OpenAIKmpProvider()
    private val setting = ProviderSetting.OpenAI(
        apiKey = "sk-test",
        baseUrl = "https://api.openai.com/v1",
    )

    @Test
    fun chatCompletionsToolsDisableParallelToolCalls() {
        val body = provider.buildChatCompletionRequest(
            providerSetting = setting,
            messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("weather?")))),
            params = TextGenerationParams(model = toolModel(), tools = listOf(testTool())),
            stream = false,
        )

        assertFalse(body.getValue("parallel_tool_calls").jsonPrimitive.boolean)
    }

    @Test
    fun responsesToolsDisableParallelToolCalls() {
        val body = provider.buildResponsesRequestBody(
            providerSetting = setting,
            messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("weather?")))),
            params = TextGenerationParams(model = toolModel(), tools = listOf(testTool())),
            stream = false,
        )

        assertFalse(body.getValue("parallel_tool_calls").jsonPrimitive.boolean)
    }

    private fun toolModel(): Model = Model(
        modelId = "gpt-5",
        displayName = "GPT-5",
        abilities = listOf(ModelAbility.TOOL),
    )

    private fun testTool(): Tool = Tool(
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
}
