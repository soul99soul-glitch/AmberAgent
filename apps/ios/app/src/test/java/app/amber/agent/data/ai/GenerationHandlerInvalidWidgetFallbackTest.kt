package app.amber.core.ai

import android.app.Application
import androidx.room.Room
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.ImageGenerationParams
import app.amber.ai.provider.Model
import app.amber.ai.provider.Provider
import app.amber.ai.provider.ProviderManager
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.ImageGenerationResult
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageChoice
import app.amber.agent.AppScope
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.fts.MessageFtsManager
import app.amber.core.context.AgentCapabilitySnapshotBuilder
import app.amber.core.context.ConversationContextEngine
import app.amber.core.context.ConversationContextRepository
import app.amber.core.files.FilesManager
import app.amber.core.memory.pollution.PollutedConversationStore
import app.amber.core.memory.recall.MemoryRecallStore
import app.amber.core.model.Assistant
import app.amber.core.repository.ConversationRepository
import app.amber.core.repository.FilesRepository
import app.amber.core.repository.MemoryRepository
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.ContextCompactionSetting
import app.amber.core.settings.GenerativeUiSetting
import app.amber.core.settings.Settings
import app.amber.feature.prompts.AgentPromptConfigRepository
import app.amber.feature.runtime.AgentToolDispatcher
import app.amber.feature.runtime.PermissionDecisionResolver
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GenerationHandlerInvalidWidgetFallbackTest {
    private lateinit var database: AppDatabase
    private lateinit var appScope: AppScope

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        appScope = AppScope()
    }

    @After
    fun tearDown() {
        appScope.cancel()
        database.close()
    }

    @Test
    fun invalidRequiredWidgetRetriesThroughVisibleFallbackPath() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val json = Json { ignoreUnknownKeys = true }
        val model = Model(modelId = "fake-chat", displayName = "Fake Chat")
        val providerSetting = ProviderSetting.OpenAI(name = "Fake", models = listOf(model))
        val fakeProvider = InvalidThenValidWidgetProvider(model)
        val providerManager = ProviderManager(context).apply {
            registerProvider("openai", fakeProvider)
        }
        val filesManager = FilesManager(
            context = context,
            repository = FilesRepository(database.managedFileDao()),
            appScope = appScope,
        )
        val conversationRepository = ConversationRepository(
            conversationDAO = database.conversationDao(),
            messageNodeDAO = database.messageNodeDao(),
            messageStatsDAO = database.messageStatsDao(),
            favoriteDAO = database.favoriteDao(),
            database = database,
            filesManager = filesManager,
            messageFtsManager = MessageFtsManager(database),
        )
        val memoryRepository = MemoryRepository(
            memoryDAO = database.memoryDao(),
            candidateDAO = database.memoryCandidateDao(),
            eventDAO = database.memoryEventDao(),
        )
        val handler = GenerationHandler(
            context = context,
            providerManager = providerManager,
            json = json,
            memoryRepo = memoryRepository,
            memoryRecallStore = MemoryRecallStore(memoryRepository),
            conversationRepo = conversationRepository,
            aiLoggingManager = AILoggingManager(),
            conversationContextEngine = ConversationContextEngine(
                providerManager = providerManager,
                json = json,
                contextRepository = ConversationContextRepository(
                    compactDAO = database.conversationCompactDao(),
                    eventDAO = database.conversationContextEventDao(),
                    conversationRepository = conversationRepository,
                ),
                appScope = appScope,
                capabilitySnapshotBuilder = AgentCapabilitySnapshotBuilder(),
                promptConfigRepository = AgentPromptConfigRepository(context),
            ),
            toolDispatcher = AgentToolDispatcher(json, PermissionDecisionResolver()),
            pollutedConversationStore = PollutedConversationStore(
                file = context.cacheDir.resolve("generation-handler-invalid-widget-test.json"),
                json = json,
            ),
        )
        val assistant = Assistant(
            chatModelId = model.id,
            contextMessageSize = 32,
            streamOutput = true,
        )
        val settings = Settings(
            chatModelId = model.id,
            providers = listOf(providerSetting),
            assistants = listOf(assistant),
            assistantId = assistant.id,
            agentRuntime = AgentRuntimeSetting(
                enableCoreMemory = false,
                enableShortTermMemory = false,
                enableLongTermMemory = false,
                enableRecentChatsReference = false,
                generativeUi = GenerativeUiSetting(enabled = true),
                contextCompaction = ContextCompactionSetting(enabled = false),
                generationRetry = GenerationRetrySetting(enabled = false),
            ),
        )

        val chunks = handler.generateText(
            settings = settings,
            model = model,
            messages = listOf(UIMessage.user("[ROUTE:svg]\n画一个登录流程图")),
            assistant = assistant,
            maxSteps = 1,
        ).toList()

        assertEquals(2, fakeProvider.requests.size)
        val retry = fakeProvider.requests[1]
        assertEquals(ReasoningLevel.OFF, retry.params.reasoningLevel)
        assertTrue(retry.params.tools.isEmpty())
        assertTrue(
            retry.messages.any { message ->
                message.toText().contains("Visible Generative UI Retry") &&
                    message.toText().contains("previous stream")
            },
        )
        val finalText = (chunks.last() as GenerationChunk.Messages).messages.last().toText()
        assertTrue(finalText.contains("```show-widget"))
        assertTrue(finalText.contains("<svg"))
    }

    private class InvalidThenValidWidgetProvider(
        private val model: Model,
    ) : Provider<ProviderSetting.OpenAI> {
        val requests = mutableListOf<Request>()

        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = listOf(model)

        override suspend fun generateText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk = error("Non-streaming generation is not used by this test")

        override suspend fun streamText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> {
            requests += Request(messages, params)
            val text = if (requests.size == 1) {
                "这里仅返回普通文字，没有生成要求的可视化。"
            } else {
                """
                ```show-widget
                {"title":"Login","widget_code":"<svg viewBox=\"0 0 20 20\"><circle cx=\"10\" cy=\"10\" r=\"4\"/></svg>"}
                ```
                """.trimIndent()
            }
            return flowOf(
                MessageChunk(
                    id = "chunk-${requests.size}",
                    model = model.modelId,
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage.assistant(text),
                            message = null,
                            finishReason = "stop",
                        )
                    ),
                )
            )
        }

        override suspend fun generateImage(
            providerSetting: ProviderSetting,
            params: ImageGenerationParams,
        ): ImageGenerationResult = error("Image generation is not used by this test")
    }

    private data class Request(
        val messages: List<UIMessage>,
        val params: TextGenerationParams,
    )
}
