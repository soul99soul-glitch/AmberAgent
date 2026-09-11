package app.amber.core.ai

import android.app.Application
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.ImageGenerationParams
import app.amber.ai.provider.ImageModelGateway
import app.amber.ai.provider.Modality
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.TextModelGateway
import app.amber.ai.ui.ImageGenerationResult
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageChoice
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.util.ImageEncodingException
import app.amber.core.context.AgentCapabilitySnapshotBuilder
import app.amber.core.context.ConversationContextEngine
import app.amber.core.context.ConversationContextRepository
import app.amber.core.infra.AppScope
import app.amber.core.memory.recall.MemoryRecallStore
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.repository.MemoryRepository
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.ContextCompactionSetting
import app.amber.core.settings.GenerativeUiSetting
import app.amber.core.settings.Settings
import app.amber.feature.prompts.AgentPromptConfigRepository
import app.amber.feature.runtime.DurableRuntimeTestBase
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wire-attempt isolation of the round verdict: [ChatGenerationRoundEngine]
 * must decide `outputLimitReached` from the finish_reason of the wire attempt
 * whose output is actually ADOPTED — a truncation reported by a failed or
 * abandoned attempt (retry / Generative-UI repair / vision fallback) must
 * never leak into the verdict. The production components are real; the only
 * fake is the provider gateway boundary.
 */
@OptIn(ExperimentalUuidApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatGenerationRoundEngineTest : DurableRuntimeTestBase() {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Scripted fake at the provider boundary: wire attempt N replays
     * `attempts[N]` — emit its chunks, then optionally throw.
     */
    private class ScriptedGateway(
        private val attempts: List<Attempt>,
    ) : TextModelGateway<ProviderSetting.OpenAI>, ImageModelGateway<ProviderSetting.OpenAI> {

        data class Attempt(
            val chunks: List<MessageChunk>,
            val errorAfterChunks: Throwable? = null,
        )

        val received = mutableListOf<List<UIMessage>>()
        private var call = 0

        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()

        override suspend fun complete(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk = error("these tests use the streaming path only")

        override suspend fun stream(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = flow {
            received += messages
            val attempt = attempts.getOrNull(call++) ?: error("no script for wire attempt $call")
            attempt.chunks.forEach { emit(it) }
            attempt.errorAfterChunks?.let { throw it }
        }

        override suspend fun generateImage(
            providerSetting: ProviderSetting.OpenAI,
            params: ImageGenerationParams,
        ): ImageGenerationResult = error("not used")
    }

    private fun visionModel() = Model(
        modelId = "engine-test-model",
        displayName = "Engine Test Model",
        inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        contextWindowTokens = 128_000,
    )

    private fun providerSetting(model: Model) = ProviderSetting.OpenAI(
        id = Uuid.random(),
        name = "Engine Test OpenAI",
        models = listOf(model),
        baseUrl = "https://api.openai.com/v1",
    )

    private fun settings(model: Model, provider: ProviderSetting.OpenAI) = Settings(
        providers = listOf(provider.copy(models = listOf(model))),
        systemPrompt = "You are the engine test assistant.",
        agentRuntime = AgentRuntimeSetting(
            agentSoulMarkdown = "",
            enableRecentChatsReference = false,
            enableCoreMemory = false,
            enableShortTermMemory = false,
            enableLongTermMemory = false,
            generativeUi = GenerativeUiSetting(enabled = false),
            contextCompaction = ContextCompactionSetting(enabled = false),
        ),
    )

    private fun engine(gateway: ScriptedGateway): ChatGenerationRoundEngine {
        val providerCatalog = ProviderCatalog(
            openAIProvider = app.amber.ai.provider.providers.OpenAIProvider(OkHttpClient(), context),
            googleProvider = app.amber.ai.provider.providers.GoogleProvider(OkHttpClient(), context),
            claudeProvider = app.amber.ai.provider.providers.ClaudeProvider(OkHttpClient(), context),
            openAITextGateway = gateway,
            openAIImageGateway = gateway,
        )
        val conversationRepo = conversationRepository()
        val memoryRepo = MemoryRepository(
            memoryDAO = database.memoryDao(),
            candidateDAO = database.memoryCandidateDao(),
            eventDAO = database.memoryEventDao(),
            appDatabase = database,
        )
        val contextEngine = ConversationContextEngine(
            providerCatalog = providerCatalog,
            json = json,
            contextRepository = ConversationContextRepository(
                compactDAO = database.conversationCompactDao(),
                eventDAO = database.conversationContextEventDao(),
                conversationRepository = conversationRepo,
            ),
            appScope = AppScope(),
            capabilitySnapshotBuilder = AgentCapabilitySnapshotBuilder(),
            promptConfigRepository = AgentPromptConfigRepository(context),
            context = context,
        )
        return ChatGenerationRoundEngine(
            context = context,
            providerCatalog = providerCatalog,
            json = json,
            memoryRecallStore = MemoryRecallStore(memoryRepo),
            conversationRepo = conversationRepo,
            aiLoggingManager = AILoggingManager(),
            conversationContextEngine = contextEngine,
        )
    }

    private suspend fun request(model: Model, settings: Settings, messages: List<UIMessage>) =
        GenerationRoundRequest(
            settings = settings,
            messages = messages,
            transformers = emptyList(),
            model = model,
            tools = emptyList(),
            memories = emptyList(),
            stream = true,
            processingStatus = MutableStateFlow(null),
            conversation = conversationFor(messages),
            speculativeRunner = null,
            loopBudgetPrompt = "",
            responsesResume = null,
        )

    private suspend fun conversationFor(messages: List<UIMessage>): Conversation {
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = AMBER_AGENT_ID,
            messageNodes = messages.map { MessageNode.of(it) },
        )
        conversationRepository().insertConversation(conversation)
        return conversation
    }

    /** An image-carrying user turn — the vision fallback's precondition. */
    private fun imageUserMessage(): UIMessage = UIMessage(
        role = MessageRole.USER,
        parts = listOf(
            UIMessagePart.Text("这张图里是什么？"),
            // Plain URL (not a data: URI): the conversation row refuses to
            // persist base64 parts, and the vision fallback only needs a
            // non-blank image URL.
            UIMessagePart.Image(url = "https://example.com/cat.png"),
        ),
    )

    private fun deltaChunk(
        text: String?,
        finishReason: String?,
    ): MessageChunk = MessageChunk(
        id = "engine_test_chunk",
        model = "engine-test-model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = if (text != null) {
                    UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))
                } else {
                    null
                },
                message = null,
                finishReason = finishReason,
            )
        ),
    )

    @Test
    fun `a truncation from a failed attempt does not leak into the adopted fallback attempt`() = runBlocking {
        val model = visionModel()
        val gateway = ScriptedGateway(
            listOf(
                // Attempt 1 (primary): reports `length`, then dies with an
                // image-decode failure → the vision fallback adopts attempt 2.
                ScriptedGateway.Attempt(
                    chunks = listOf(deltaChunk(text = "写到一半", finishReason = "length")),
                    errorAfterChunks = ImageEncodingException(
                        imageUrl = "https://example.com/cat.png",
                        cause = IllegalStateException("decode failed"),
                    ),
                ),
                // Attempt 2 (vision fallback): succeeds, no finish_reason.
                ScriptedGateway.Attempt(chunks = listOf(deltaChunk(text = "图里是一只猫。", finishReason = null))),
            ),
        )
        val settings = settings(model, providerSetting(model))
        val messages = listOf(imageUserMessage())

        val outcome = engine(gateway).generateRound(request(model, settings, messages)) { }

        assertTrue("the vision fallback must have run", gateway.received.size == 2)
        assertFalse(
            "the abandoned attempt's `length` must not truncate the adopted output",
            outcome.outputLimitReached,
        )
    }

    @Test
    fun `a truncation in the adopted attempt still reports outputLimitReached`() = runBlocking {
        val model = visionModel()
        val gateway = ScriptedGateway(
            listOf(
                // The only attempt completes normally with `length` — a real
                // truncation of the adopted output.
                ScriptedGateway.Attempt(chunks = listOf(deltaChunk(text = "写到一半", finishReason = "length"))),
            ),
        )
        val settings = settings(model, providerSetting(model))
        val messages = listOf(imageUserMessage())

        val outcome = engine(gateway).generateRound(request(model, settings, messages)) { }

        assertTrue(outcome.outputLimitReached)
    }

    @Test
    fun `a normal stop after a truncation within one attempt wins`() = runBlocking {
        val model = visionModel()
        val gateway = ScriptedGateway(
            listOf(
                ScriptedGateway.Attempt(
                    chunks = listOf(
                        deltaChunk(text = "写到一半", finishReason = "length"),
                        deltaChunk(text = null, finishReason = "stop"),
                    ),
                ),
            ),
        )
        val settings = settings(model, providerSetting(model))
        val messages = listOf(imageUserMessage())

        val outcome = engine(gateway).generateRound(request(model, settings, messages)) { }

        assertFalse(outcome.outputLimitReached)
    }
}
