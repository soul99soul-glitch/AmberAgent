package app.amber.feature.novel.workspace

import android.app.Application
import app.amber.ai.core.MessageRole
import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.TextModelGateway
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageChoice
import app.amber.ai.ui.UIMessagePart
import app.amber.core.ai.AILoggingManager
import app.amber.core.ai.ChatGenerationRoundEngine
import app.amber.core.ai.DefaultRunKernel
import app.amber.core.ai.GenerationRunSession
import app.amber.core.context.AgentCapabilitySnapshotBuilder
import app.amber.core.context.ConversationContextEngine
import app.amber.core.context.ConversationContextRepository
import app.amber.core.infra.AppScope
import app.amber.core.memory.recall.MemoryRecallStore
import app.amber.core.repository.MemoryRepository
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.ContextCompactionSetting
import app.amber.core.settings.GenerativeUiSetting
import app.amber.core.settings.ModelGroupSessionDefault
import app.amber.core.settings.Settings
import app.amber.core.settings.resolveSessionDefaults
import app.amber.feature.prompts.AgentPromptConfigRepository
import app.amber.feature.runtime.AgentToolDispatcher
import app.amber.feature.runtime.DurableRuntimeTestBase
import app.amber.feature.runtime.PermissionDecisionResolver
import app.amber.feature.novelworkspace.NovelWorkspaceFile
import app.amber.feature.novelworkspace.NovelWorkspaceInstaller
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceManifestRenderer
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import java.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real kernel, context preparation and token fitting; only provider I/O is scripted. */
@OptIn(ExperimentalUuidApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NovelWorkspaceContinuityContextTest : DurableRuntimeTestBase() {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true }
    private val model = Model(
        modelId = "gpt-5.4",
        abilities = listOf(ModelAbility.TOOL),
        contextWindowTokens = 128_000,
    )
    private val chapterBody = "背景景物。".repeat(1_600) + "本章结尾：沈砚自称去年到过南城。"
    private val earlierFact = "第一章原句：沈砚从未到过南城。"
    private val reviewPrompt = "只读一致性审稿。\n## Earlier chapter facts\n$earlierFact\n## Complete current chapter\n$chapterBody"

    private class CapturingGateway : TextModelGateway<ProviderSetting.OpenAI> {
        val requests = mutableListOf<List<UIMessage>>()

        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()

        override suspend fun complete(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk = error("This test uses streaming")

        override suspend fun stream(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = flow {
            requests += messages
            val part = if (requests.size == 1) {
                UIMessagePart.Tool(toolCallId = "read_1", toolName = "novel_workspace_read", input = """{"path":"project.md"}""")
            } else {
                UIMessagePart.Text("审稿完成")
            }
            emit(MessageChunk(
                id = "review_${requests.size}",
                model = "gpt-5.4",
                choices = listOf(UIMessageChoice(
                    index = 0,
                    delta = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part)),
                    message = null,
                    finishReason = if (part is UIMessagePart.Tool) "tool_calls" else "stop",
                )),
            ))
        }
    }

    private fun settings() = Settings(
        providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
        systemPrompt = "",
        contextMessageSize = 1,
        modelGroupSessionDefaults = listOf(ModelGroupSessionDefault("openai_reasoning", contextMessageSize = 1)),
        agentRuntime = AgentRuntimeSetting(
            agentSoulMarkdown = "",
            enableRecentChatsReference = false,
            enableCoreMemory = false,
            enableShortTermMemory = false,
            enableLongTermMemory = false,
            generativeUi = GenerativeUiSetting(enabled = false),
            // Exercise the prepared editor as well as the non-conversation message limit.
            contextCompaction = ContextCompactionSetting(enabled = true),
        ),
    )

    private fun kernel(gateway: CapturingGateway): DefaultRunKernel {
        val client = OkHttpClient()
        val catalog = ProviderCatalog(
            openAIProvider = app.amber.ai.provider.providers.OpenAIProvider(client, context),
            googleProvider = app.amber.ai.provider.providers.GoogleProvider(client, context),
            claudeProvider = app.amber.ai.provider.providers.ClaudeProvider(client, context),
            openAITextGateway = gateway,
        )
        val conversations = conversationRepository()
        val memory = MemoryRepository(
            memoryDAO = database.memoryDao(),
            candidateDAO = database.memoryCandidateDao(),
            eventDAO = database.memoryEventDao(),
            appDatabase = database,
        )
        val engine = ChatGenerationRoundEngine(
            context = context,
            providerCatalog = catalog,
            json = json,
            memoryRecallStore = MemoryRecallStore(memory),
            conversationRepo = conversations,
            aiLoggingManager = AILoggingManager(),
            conversationContextEngine = ConversationContextEngine(
                providerCatalog = catalog,
                json = json,
                contextRepository = ConversationContextRepository(
                    compactDAO = database.conversationCompactDao(),
                    eventDAO = database.conversationContextEventDao(),
                    conversationRepository = conversations,
                ),
                appScope = AppScope(),
                capabilitySnapshotBuilder = AgentCapabilitySnapshotBuilder(),
                promptConfigRepository = AgentPromptConfigRepository(context),
                context = context,
            ),
        )
        return DefaultRunKernel(context, AgentToolDispatcher(json, PermissionDecisionResolver()), engine)
    }

    private suspend fun runReview(settings: Settings, gateway: CapturingGateway) {
        var reads = 0
        kernel(gateway).run(GenerationRunSession(
            settings = settings,
            model = model,
            messages = listOf(UIMessage.system(reviewPrompt), UIMessage.user("审核本章，返回严格 JSON。")),
            tools = listOf(Tool(name = "novel_workspace_read", description = "Read manuscript", execute = {
                reads += 1
                listOf(UIMessagePart.Text("原稿读取完成"))
            })),
            maxSteps = 4,
        )).toList()
        assertEquals(1, reads)
        assertToolRound(gateway)
    }

    private fun assertToolRound(gateway: CapturingGateway) {
        assertEquals("The provider must see an initial request and a request after the tool result", 2, gateway.requests.size)
        assertTrue(gateway.requests[1].flatMap { it.getTools() }.any {
            it.toolCallId == "read_1" && it.isExecuted
        })
    }

    @Test
    fun `workspace runtime preserves full chapter and earlier facts at provider across tool rounds`() = runBlocking {
        val original = settings()
        val before = original.copy()
        val gateway = CapturingGateway()
        val directory = temporaryFolder.newFolder("book")
        val projectRaw = NovelWorkspaceMarkdown.render(listOf("id" to "P-1", "kind" to "project", "title" to "上下文测试"), body = "原稿读取完成")
        NovelWorkspaceInstaller.install(listOf(
            NovelWorkspaceFile("manifest.yaml", NovelWorkspaceManifestRenderer.render(Instant.parse("2026-09-30T00:00:00Z"), "P-1", 1, 1, "主线")),
            NovelWorkspaceFile("project.md", projectRaw),
            NovelWorkspaceFile("branches/主线/branch.md", NovelWorkspaceMarkdown.render(listOf("id" to "B-1", "kind" to "branch", "title" to "主线"), body = "")),
        ), directory)
        val store = NovelWorkspaceStore(directory)
        val treeBefore = store.fileTree()
        val headBefore = NovelWorkspaceLedger.load(directory).head
        val runtime = NovelWorkspaceRuntime(kernel(gateway))

        val events = runtime.runTurn(NovelWorkspaceRuntime.TurnRequest(
            projectDirectory = directory,
            branchId = "B-1",
            branchSlug = "主线",
            userText = "审核本章，返回严格 JSON。",
            systemPrompt = reviewPrompt,
            settings = original,
            model = model,
            maxSteps = 4,
            readOnlyTools = true,
        )).toList()

        assertTrue(events.any { it is NovelWorkspaceRuntime.TurnEvent.Completed })
        assertFalse(events.any { it is NovelWorkspaceRuntime.TurnEvent.Failed })
        assertToolRound(gateway)
        assertTrue(gateway.requests[1].flatMap { it.getTools() }.single { it.toolCallId == "read_1" }
            .output.filterIsInstance<UIMessagePart.Text>().any { it.text == projectRaw })
        gateway.requests.forEachIndexed { round, messages ->
            val systems = messages.filter { it.role == MessageRole.SYSTEM }.map { it.toText() }
            assertTrue("Round $round must retain the complete audit prompt", systems.any { it.contains(reviewPrompt) })
            assertTrue("Round $round must include the chapter tail", systems.any { it.contains(chapterBody) })
            assertTrue("Round $round must retain earlier verified facts", systems.any { it.contains(earlierFact) })
        }
        assertEquals(before, original)
        assertEquals(1, original.contextMessageSize)
        assertEquals(1, original.copy(contextMessageSize = 0).resolveSessionDefaults(model).contextMessageSize)
        assertEquals(treeBefore, store.fileTree())
        assertEquals(headBefore, NovelWorkspaceLedger.load(directory).head)
        assertTrue(runtime.pendingProposals.value.isEmpty())
    }

    @Test
    fun `ordinary settings still apply the one message limit`() = runBlocking {
        val original = settings()
        val gateway = CapturingGateway()

        runReview(original, gateway)

        gateway.requests.forEach { messages ->
            assertFalse(messages.any { it.toText().contains(chapterBody) })
            assertFalse(messages.any { it.toText().contains(earlierFact) })
        }
        assertEquals(1, original.resolveSessionDefaults(model).contextMessageSize)
    }
}
