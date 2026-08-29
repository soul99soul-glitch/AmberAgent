package app.amber.feature.subagent

import app.amber.ai.core.MessageRole
import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationTerminal
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.RunKernel
import app.amber.core.ai.transformers.InputMessageTransformer
import app.amber.core.ai.transformers.OutputMessageTransformer
import app.amber.core.model.AssistantMemory
import app.amber.core.model.Conversation
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
            FakeKernel(
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
        val runner = GenerationSubAgentRunner(FakeKernel(error = IllegalStateException("network unavailable")))

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
            FakeKernel(
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
            FakeKernel(
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

    @Test
    fun narrowedParentPolicyReachesTheChildSession() = runBlocking {
        val kernel = SessionCapturingKernel()
        val runner = GenerationSubAgentRunner(kernel)
        val liveText = MutableStateFlow("")
        val liveParts = MutableStateFlow<List<UIMessagePart>>(emptyList())

        // Parent narrows the child's sandbox (allowShell=false); the payload
        // carries no extra narrowing. The child session must carry exactly the
        // parent policy — a child can never widen it.
        runner.run(
            settings = settings(),
            definition = definition(toolAllowlist = emptySet()),
            task = task(),
            tools = emptyList(),
            liveText = liveText,
            liveParts = liveParts,
            parentPolicy = app.amber.feature.runtime.ExecutionPolicy(allowShell = false),
        )

        // (The runner may issue a second report-retry kernel run; every child
        // session must carry the same policy.)
        val seen = kernel.sessions.first().executionPolicy
        assertEquals(false, seen.allowShell)
        assertTrue(kernel.sessions.all { it.executionPolicy == seen })

        // Parent-permissive + payload narrowing: the child policy passes through.
        val kernel2 = SessionCapturingKernel()
        GenerationSubAgentRunner(kernel2).run(
            settings = settings(),
            definition = definition(toolAllowlist = emptySet()),
            task = task(),
            tools = emptyList(),
            liveText = MutableStateFlow(""),
            liveParts = MutableStateFlow(emptyList()),
            childPolicy = app.amber.feature.runtime.ExecutionPolicy(
                allowedDomains = listOf("example.com"),
            ),
        )
        val narrowed = kernel2.sessions.first().executionPolicy
        assertEquals(listOf("example.com"), narrowed.allowedDomains)
        assertEquals(true, narrowed.allowShell)
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
            systemPrompt = "Parent prompt",
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

    private inner class FakeKernel(
        private val error: Throwable? = null,
        private val assistantMessage: UIMessage = UIMessage.assistant(visibleAnswer),
    ) : RunKernel {
        override fun run(session: GenerationRunSession): Flow<GenerationChunk> = flow {
            emit(GenerationChunk.Messages(session.messages + assistantMessage))
            error?.let { throw it }
        }
    }

    /** Records every session the kernel is asked to run. */
    private inner class SessionCapturingKernel : RunKernel {
        val sessions = mutableListOf<GenerationRunSession>()

        override fun run(session: GenerationRunSession): Flow<GenerationChunk> = flow {
            sessions += session
            emit(GenerationChunk.Messages(session.messages + UIMessage.assistant(visibleAnswer)))
        }
    }
}
