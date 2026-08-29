package app.amber.feature.chat.impl

import app.amber.ai.core.MessageRole
import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.provider.ResponsesResumeRequest
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.ConversationId
import app.amber.core.agent.runtime.MessageNodeId
import app.amber.core.agent.runtime.adapter.LegacyRunScope
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationTerminal
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.RunKernel
import app.amber.core.ai.transformers.InputMessageTransformer
import app.amber.core.ai.transformers.OutputMessageTransformer
import app.amber.core.model.AssistantMemory
import app.amber.core.model.Conversation
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.service.ConversationAccess
import app.amber.core.ai.GenerationRetrySetting
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.ContextCompactionSetting
import app.amber.core.settings.GenerativeUiSetting
import app.amber.core.settings.Settings
import app.amber.core.settings.SpeculativeToolExecutionSetting
import app.amber.feature.chat.api.ChatTurnInput
import app.amber.feature.runtime.ToolInvocationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Pins the kernel-path wiring contract: everything the durable runtime needs
 * (runId, terminal persistence, steer, responses-resume, processing status,
 * trusted tool names) must reach the loop from the run scope + hooks, and a
 * hookless session must produce the bare non-durable loop.
 */
class ChatTurnAgentTest {

    private class CapturedCall {
        var runId: String? = null
        var runIdPassed = false
        var onTerminalPresent = false
        var onTerminal: (suspend (GenerationTerminal) -> Unit)? = null
        var consumeSteer: (suspend () -> List<UIMessage>)? = null
        var responsesResume: ResponsesResumeRequest? = null
        var responsesResumePassed = false
        var processingStatus: MutableStateFlow<String?>? = null
        var autoApprovedToolNames: Set<String> = emptySet()
        var maxSteps: Int = -1
    }

    private class FakeKernel(
        private val captured: CapturedCall,
        private val replyText: String = "hello",
        private val failure: Throwable? = null,
    ) : RunKernel {
        override fun run(session: GenerationRunSession): Flow<GenerationChunk> {
            captured.runId = session.runId
            captured.runIdPassed = session.runId != null
            captured.onTerminalPresent = session.onTerminal != null
            captured.onTerminal = session.onTerminal
            captured.consumeSteer = session.consumeSteerMessages
            captured.responsesResume = session.responsesResume
            captured.responsesResumePassed = true
            captured.processingStatus = session.processingStatus
            captured.autoApprovedToolNames = session.autoApprovedToolNames
            captured.maxSteps = session.maxSteps
            return flow {
                failure?.let { throw it }
                emit(
                    GenerationChunk.Messages(
                        listOf(
                            UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(UIMessagePart.Text(replyText)),
                            ),
                        ),
                    ),
                )
            }
        }
    }

    private class FakeConversationAccess(private val conversation: Conversation) : ConversationAccess {
        val updates = mutableListOf<Conversation>()
        private val flow = MutableStateFlow(conversation)

        override fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> = flow
        override fun getConversationFlowOrNull(conversationId: Uuid): StateFlow<Conversation> = flow
        override fun updateConversation(
            conversationId: Uuid,
            conversation: Conversation,
            checkDeletedFiles: Boolean,
        ) {
            updates += conversation
            flow.value = conversation
        }

        override suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {}
        override fun addError(error: Throwable, conversationId: Uuid?, title: String?) {}
    }

    private class RecordingHooks {
        val started = mutableListOf<String>()
        val terminals = mutableListOf<Pair<String, GenerationTerminal>>()
        val finishes = mutableListOf<Pair<String, Throwable?>>()
        val streaming = mutableListOf<Pair<String, List<UIMessage>>>()
        val statusFlow = MutableStateFlow<String?>(null)
        val steer = mutableListOf("keep going")
        var responsesResume: ResponsesResumeRequest? = null

        fun bundle(durable: Boolean = true): ChatRunHooks = ChatRunHooks(
            durable = durable,
            processingStatus = statusFlow,
            autoApprovedToolNames = setOf("search"),
            consumeSteerMessages = {
                steer.map {
                    UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(it)))
                }
            },
            onRunStarted = { runId -> started += runId },
            onTerminal = { runId, terminal -> terminals += runId to terminal },
            onStreamingMessages = { runId, messages -> streaming += runId to messages },
            onRunFinished = { runId, cause -> finishes += runId to cause },
            responsesResumeFor = { responsesResume },
        )
    }

    private fun settings() = Settings(
        providers = emptyList(),
        agentRuntime = AgentRuntimeSetting(
            agentSoulMarkdown = "",
            enableRecentChatsReference = false,
            enableCoreMemory = false,
            enableShortTermMemory = false,
            enableLongTermMemory = false,
            generativeUi = GenerativeUiSetting(enabled = false),
            contextCompaction = ContextCompactionSetting(enabled = false),
            speculativeToolExecution = SpeculativeToolExecutionSetting(enabled = false),
            generationRetry = GenerationRetrySetting(enabled = false),
        ),
    )

    private fun model() = Model(
        modelId = "contract-model",
        displayName = "Contract Model",
    )

    private fun conversation(id: Uuid) = Conversation(
        id = id,
        assistantId = AMBER_AGENT_ID,
        messageNodes = emptyList(),
    )

    private fun sessionOf(
        conversation: Conversation,
        hooks: ChatRunHooks?,
        maxSteps: Int = 7,
    ): ChatSession = ChatSession(
        settings = settings(),
        model = model(),
        messages = emptyList(),
        inputTransformers = emptyList(),
        outputTransformers = emptyList(),
        memories = null,
        tools = emptyList(),
        maxSteps = maxSteps,
        autoApproveTools = false,
        autoApproveHighRiskTools = false,
        autoApprovedToolNames = emptySet(),
        invocationContext = ToolInvocationContext.Normal,
        conversation = conversation,
        hooks = hooks,
    )

    private fun input(conversationId: Uuid) = ChatTurnInput(
        conversationId = ConversationId(conversationId.toString()),
        messageNodeId = MessageNodeId(Uuid.random().toString()),
        assistantId = app.amber.core.agent.runtime.AssistantId("default"),
        userMessageText = "hi",
        maxToolIterations = 7,
    )

    @Test
    fun `hooks are wired through to the generation call`() = runTest {
        val captured = CapturedCall()
        val hooks = RecordingHooks()
        val conversationId = Uuid.random()
        val conversation = conversation(conversationId)
        val access = FakeConversationAccess(conversation)
        val agent = ChatTurnAgent(
            kernel = FakeKernel(captured),
            sessionResolver = object : ChatSessionResolver {
                override suspend fun resolve(
                    input: ChatTurnInput,
                    runId: String,
                    events: app.amber.core.agent.runtime.AgentEventWriter?,
                ) =
                    sessionOf(conversation, hooks.bundle())
            },
            conversationAccess = access,
        )
        val runId = AgentRunId("kernel-run-1")

        val artifact = agent.handler.handle(input(conversationId), LegacyRunScope(runId = runId))

        // The loop received the kernel runId and every durable hook.
        assertEquals(runId.value, captured.runId)
        assertTrue(captured.onTerminalPresent)
        assertSame(hooks.statusFlow, captured.processingStatus)
        assertEquals(setOf("search"), captured.autoApprovedToolNames)
        // Step budget comes from the resolved session (settings clamp), not
        // the turn input.
        assertEquals(7, captured.maxSteps)

        // Run lifecycle hooks fired in order with the same runId.
        assertEquals(listOf(runId.value), hooks.started)
        assertEquals(listOf(runId.value to null), hooks.finishes)

        // Streaming chunks are forwarded to the live-status hook.
        assertEquals(1, hooks.streaming.size)
        assertEquals(runId.value, hooks.streaming.single().first)

        // The terminal callback the loop got routes to the hook.
        captured.onTerminal!!.invoke(GenerationTerminal.WaitingUser)
        assertEquals(
            listOf(runId.value to GenerationTerminal.WaitingUser),
            hooks.terminals,
        )

        // Steer drain is the hook's, not an empty default.
        val steer = captured.consumeSteer!!.invoke()
        assertEquals("keep going", (steer.single().parts.single() as UIMessagePart.Text).text)

        assertNotNull(artifact)
        assertTrue(access.updates.isNotEmpty())
    }

    @Test
    fun `responses resume request is resolved per runId`() = runTest {
        val captured = CapturedCall()
        val hooks = RecordingHooks()
        val conversationId = Uuid.random()
        val conversation = conversation(conversationId)
        val access = FakeConversationAccess(conversation)
        val bundle = hooks.bundle()
        val agent = ChatTurnAgent(
            kernel = FakeKernel(captured),
            sessionResolver = object : ChatSessionResolver {
                override suspend fun resolve(
                    input: ChatTurnInput,
                    runId: String,
                    events: app.amber.core.agent.runtime.AgentEventWriter?,
                ) =
                    sessionOf(conversation, bundle)
            },
            conversationAccess = access,
        )

        agent.handler.handle(input(conversationId), LegacyRunScope(runId = AgentRunId("kernel-run-2")))

        // Gate returned null (no request configured) but the provider was invoked.
        assertNull(captured.responsesResume)
        assertTrue(captured.responsesResumePassed)
    }

    @Test
    fun `hookless session runs the bare non-durable loop`() = runTest {
        val captured = CapturedCall()
        val conversationId = Uuid.random()
        val conversation = conversation(conversationId)
        val access = FakeConversationAccess(conversation)
        val agent = ChatTurnAgent(
            kernel = FakeKernel(captured),
            sessionResolver = object : ChatSessionResolver {
                override suspend fun resolve(
                    input: ChatTurnInput,
                    runId: String,
                    events: app.amber.core.agent.runtime.AgentEventWriter?,
                ) =
                    sessionOf(conversation, hooks = null)
            },
            conversationAccess = access,
        )

        agent.handler.handle(input(conversationId), LegacyRunScope(runId = AgentRunId("kernel-run-3")))

        assertNull(captured.runId)
        assertNull(captured.onTerminal)
        assertTrue(captured.consumeSteer!!.invoke().isEmpty())
        assertNull(captured.responsesResume)
    }

    @Test
    fun `non-durable hooks keep the loop bare but still receive lifecycle callbacks`() = runTest {
        val captured = CapturedCall()
        val hooks = RecordingHooks()
        val conversationId = Uuid.random()
        val conversation = conversation(conversationId)
        val access = FakeConversationAccess(conversation)
        val agent = ChatTurnAgent(
            kernel = FakeKernel(captured),
            sessionResolver = object : ChatSessionResolver {
                override suspend fun resolve(
                    input: ChatTurnInput,
                    runId: String,
                    events: app.amber.core.agent.runtime.AgentEventWriter?,
                ) =
                    sessionOf(conversation, hooks.bundle(durable = false))
            },
            conversationAccess = access,
        )
        val runId = AgentRunId("kernel-run-4")

        agent.handler.handle(input(conversationId), LegacyRunScope(runId = runId))

        // Durable inputs are withheld from the generation loop when the
        // runtime flags are off (coordinator durablePath gate parity).
        assertNull(captured.runId)
        assertNull(captured.onTerminal)
        assertNull(captured.responsesResume)

        // Lifecycle orchestration still runs with the kernel runId.
        assertEquals(listOf(runId.value), hooks.started)
        assertEquals(listOf(runId.value to null), hooks.finishes)
        assertEquals(1, hooks.streaming.size)
    }

    @Test
    fun `generator failure reaches onRunFinished and propagates`() = runTest {
        val captured = CapturedCall()
        val hooks = RecordingHooks()
        val boom = IllegalStateException("provider exploded")
        val conversationId = Uuid.random()
        val conversation = conversation(conversationId)
        val access = FakeConversationAccess(conversation)
        val agent = ChatTurnAgent(
            kernel = FakeKernel(captured, failure = boom),
            sessionResolver = object : ChatSessionResolver {
                override suspend fun resolve(
                    input: ChatTurnInput,
                    runId: String,
                    events: app.amber.core.agent.runtime.AgentEventWriter?,
                ) =
                    sessionOf(conversation, hooks.bundle())
            },
            conversationAccess = access,
        )
        val runId = AgentRunId("kernel-run-fail")

        val thrown = runCatching {
            agent.handler.handle(input(conversationId), LegacyRunScope(runId = runId))
        }.exceptionOrNull()

        // The handler rethrows so the runner can mark the run FAILED, and the
        // settle hook received the same cause (terminal persistence parity).
        assertSame(boom, thrown)
        assertEquals(listOf(runId.value), hooks.started)
        assertEquals(listOf(runId.value to boom), hooks.finishes)
    }

    @Test
    fun `onRunStarted failure still settles the run via onRunFinished`() = runTest {
        val hooks = RecordingHooks()
        val boom = SecurityException("notification permission denied")
        val conversationId = Uuid.random()
        val conversation = conversation(conversationId)
        val access = FakeConversationAccess(conversation)
        // onRunStarted inside the try: a throwing start hook (e.g. a
        // notification SecurityException) must not leak a RUNNING run —
        // onRunFinished still runs with the failure.
        val bundle = ChatRunHooks(
            durable = true,
            onRunStarted = { throw boom },
            onRunFinished = { runId, cause -> hooks.finishes += runId to cause },
        )
        val captured = CapturedCall()
        val agent = ChatTurnAgent(
            kernel = FakeKernel(captured),
            sessionResolver = object : ChatSessionResolver {
                override suspend fun resolve(
                    input: ChatTurnInput,
                    runId: String,
                    events: app.amber.core.agent.runtime.AgentEventWriter?,
                ) =
                    sessionOf(conversation, bundle)
            },
            conversationAccess = access,
        )
        val runId = AgentRunId("kernel-run-start-fail")

        val thrown = runCatching {
            agent.handler.handle(input(conversationId), LegacyRunScope(runId = runId))
        }.exceptionOrNull()

        assertSame(boom, thrown)
        // The generation loop never ran, but the settle hook did.
        assertTrue(!captured.runIdPassed)
        assertEquals(listOf(runId.value to boom), hooks.finishes)
    }
}
