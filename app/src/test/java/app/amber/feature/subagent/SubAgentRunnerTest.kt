package app.amber.feature.subagent

import app.amber.ai.core.MessageRole
import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.Generator
import app.amber.core.ai.transformers.InputMessageTransformer
import app.amber.core.ai.transformers.OutputMessageTransformer
import app.amber.core.model.Assistant
import app.amber.core.model.AssistantMemory
import app.amber.core.model.Conversation
import app.amber.core.model.DEFAULT_ASSISTANT_ID
import app.amber.core.settings.Settings
import app.amber.feature.runtime.ToolInvocationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentRunnerTest {
    private val visibleAnswer = "可见答案已经生成。"

    @Test
    fun pureTextRunFallsBackWhenReportToolArgumentsFailAfterVisibleText() = runBlocking {
        val runner = GenerationSubAgentRunner(
            FakeGenerator(
                error = IllegalArgumentException(
                    "invalid params: invalid function arguments json string for tool_call_id call_1"
                )
            )
        )
        val liveText = MutableStateFlow("")
        val liveParts = MutableStateFlow<List<UIMessagePart>>(emptyList())

        val result = runner.run(
            settings = settings(),
            definition = definition(toolAllowlist = emptySet()),
            task = task(),
            tools = emptyList(),
            liveText = liveText,
            liveParts = liveParts,
        )

        assertEquals(SubAgentRunStatus.COMPLETED, result.status)
        assertEquals(visibleAnswer, result.summary)
        assertEquals(visibleAnswer, liveText.value)
        assertTrue(result.risks.any { it.contains("Structured subagent report failed") })
    }

    @Test
    fun ordinaryGenerationErrorIsNotTreatedAsCompletedEvenWithVisibleText() = runBlocking {
        val runner = GenerationSubAgentRunner(FakeGenerator(error = IllegalStateException("network unavailable")))

        val result = runner.run(
            settings = settings(),
            definition = definition(toolAllowlist = emptySet()),
            task = task(),
            tools = emptyList(),
            liveText = MutableStateFlow(""),
            liveParts = MutableStateFlow<List<UIMessagePart>>(emptyList()),
        )

        assertEquals(SubAgentRunStatus.FAILED, result.status)
        assertTrue(result.error.contains("network unavailable"))
    }

    @Test
    fun toolEnabledRunDoesNotFallbackOnReportToolArgumentError() = runBlocking {
        val runner = GenerationSubAgentRunner(
            FakeGenerator(
                error = IllegalArgumentException(
                    "invalid params: invalid function arguments json string for tool_call_id call_1"
                )
            )
        )

        val result = runner.run(
            settings = settings(),
            definition = definition(toolAllowlist = setOf("file_read")),
            task = task(),
            tools = emptyList(),
            liveText = MutableStateFlow(""),
            liveParts = MutableStateFlow<List<UIMessagePart>>(emptyList()),
        )

        assertEquals(SubAgentRunStatus.FAILED, result.status)
    }

    @Test
    fun streamsSearchToolPartsForRenderTimePresentation() = runBlocking {
        val searchTool = UIMessagePart.Tool(
            toolCallId = "call-search",
            toolName = "search_web",
            input = """{"query":"Will Smith tour"}""",
            output = listOf(
                UIMessagePart.Text(
                    """
                    {
                      "items": [
                        {
                          "title": "Will Smith tour news",
                          "url": "https://example.com/will-smith-tour",
                          "source": "Example",
                          "images": ["https://img.example/will-smith.jpg"]
                        }
                      ],
                      "total_images": 1
                    }
                    """.trimIndent()
                )
            ),
        )
        val runner = GenerationSubAgentRunner(
            FakeGenerator(
                assistantMessage = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(searchTool, UIMessagePart.Text(visibleAnswer)),
                )
            )
        )
        val liveText = MutableStateFlow("")
        val liveParts = MutableStateFlow<List<UIMessagePart>>(emptyList())

        val result = runner.run(
            settings = settings(),
            definition = definition(toolAllowlist = emptySet()),
            task = task(),
            tools = emptyList(),
            liveText = liveText,
            liveParts = liveParts,
        )

        assertEquals(SubAgentRunStatus.COMPLETED, result.status)
        assertEquals(visibleAnswer, liveText.value)
        assertTrue(liveParts.value.any { it is UIMessagePart.Tool && it.toolName == "search_web" })
    }

    private fun settings(): Settings {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        return Settings(
            chatModelId = model.id,
            providers = listOf(
                ProviderSetting.OpenAI(
                    name = "Test Provider",
                    apiKey = "test-key",
                    baseUrl = "https://example.test/v1",
                    models = listOf(model),
                )
            ),
            assistants = listOf(
                Assistant(
                    id = DEFAULT_ASSISTANT_ID,
                    name = "Parent",
                    systemPrompt = "Parent prompt",
                )
            ),
        )
    }

    private fun definition(toolAllowlist: Set<String>) = SubAgentDefinition(
        id = "micro-poet",
        name = "Micro Poet",
        description = "Use when a tiny creative text task should run without external tools.",
        systemPrompt = "Boundaries: do not use external sources. Report output as a concise final answer.",
        toolAllowlist = toolAllowlist,
        dynamic = true,
    )

    private fun task() = SubAgentTaskSpec(
        objective = "写一个一句话答案。",
        outputFormat = "一句话。",
        toolsAndSources = "No tools.",
        boundaries = "Do not use external sources.",
    )

    private inner class FakeGenerator(
        private val error: Throwable? = null,
        private val assistantMessage: UIMessage = UIMessage.assistant(visibleAnswer),
    ) : Generator {
        override fun generateText(
            settings: Settings,
            model: Model,
            messages: List<UIMessage>,
            inputTransformers: List<InputMessageTransformer>,
            outputTransformers: List<OutputMessageTransformer>,
            assistant: Assistant,
            memories: List<AssistantMemory>?,
            tools: List<Tool>,
            maxSteps: Int,
            processingStatus: MutableStateFlow<String?>,
            autoApproveTools: Boolean,
            autoApproveHighRiskTools: Boolean,
            autoApprovedToolNames: Set<String>,
            invocationContext: ToolInvocationContext,
            conversation: Conversation?,
            consumeSteerMessages: suspend () -> List<UIMessage>,
        ): Flow<GenerationChunk> = flow {
            emit(GenerationChunk.Messages(messages + assistantMessage))
            error?.let { throw it }
        }
    }
}
