package app.amber.core.service

import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.amber.agent.data.db.fts.MessageFtsManager
import app.amber.ai.core.MessageRole
import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageAnnotation
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.isEphemeralInterjectionMessage
import app.amber.core.agent.runtime.AgentDescriptorId
import app.amber.core.agent.runtime.AgentInput
import app.amber.core.agent.runtime.AgentRunHandle
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunSnapshot
import app.amber.core.agent.runtime.AgentRunner
import app.amber.core.agent.runtime.InMemoryAgentEventStore
import app.amber.core.agent.runtime.RunStatus
import app.amber.core.agent.runtime.adapter.LegacyRunScope
import app.amber.core.ai.DefaultRunKernel
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationRoundEngine
import app.amber.core.ai.GenerationRoundOutcome
import app.amber.core.ai.GenerationRoundRequest
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.GenerationUpdate
import app.amber.core.ai.RunExecutionPhase
import app.amber.core.ai.RunInterjectionController
import app.amber.core.ai.RunKernel
import app.amber.core.ai.sanitizeMessagesForModelRound
import app.amber.core.ai.toGenerationSettings
import app.amber.core.files.FilesManager
import app.amber.core.infra.AppScope
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.core.repository.FilesRepository
import app.amber.core.settings.CapabilityFlags
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.utils.JsonInstant
import app.amber.feature.chat.api.ChatTurnInput
import app.amber.feature.chat.impl.ChatRunHooks
import app.amber.feature.chat.impl.ChatSession
import app.amber.feature.chat.impl.ChatSessionResolver
import app.amber.feature.chat.impl.ChatTurnAgent
import app.amber.feature.runtime.AgentLiveStatusNotifier
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.runtime.AgentToolDispatcher
import app.amber.feature.runtime.DurableRuntimeTestBase
import app.amber.feature.runtime.NotificationApprovalTokenRegistry
import app.amber.feature.runtime.PermissionDecisionResolver
import app.amber.feature.runtime.REPLAN_REQUIRED_EFFECT_CATEGORY
import app.amber.feature.runtime.RunOwnershipRegistry
import app.amber.feature.runtime.ToolEffectStatus
import app.amber.feature.runtime.ToolInvocationContext
import app.amber.feature.task.AgentTaskScheduler
import app.amber.feature.task.AgentTaskStore
import app.amber.review.ReflectionFixture
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiveInterjectionTest : DurableRuntimeTestBase() {
    @get:Rule val directory = TemporaryFolder()
    private val fixtures = mutableListOf<Fixture>()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUpMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        fixtures.forEach { it.close() }
        Dispatchers.resetMain()
    }

    @Test
    fun `fast local routing handles stop, stop-all, and explicit new-task prefix without auxiliary call`() = runBlocking {
        var auxiliaryCalls = 0
        val triager = LiveInterjectionTriager(
            auxiliaryClient = LiveInterjectionAuxiliaryClient { _, _, _ ->
                auxiliaryCalls++
                """{"intent":"steer_active","reply":"should not run"}"""
            },
        )
        val snapshot = ActiveTaskRuntimeSnapshot(
            taskId = "task-1",
            title = "分析日志",
            phase = RunExecutionPhase.EXECUTING_TOOLS,
            activeToolName = "web_search",
            completedToolCount = 1,
            elapsedSeconds = 8,
        )

        val stopSingle = triager.classify(Uuid.random(), "停一下", snapshot)
        assertEquals(LiveInterjectionIntent.STOP, stopSingle.intent)
        assertFalse(stopSingle.stopAll)
        assertTrue(stopSingle.usedFastLocalPath)

        val stopAll = triager.classify(Uuid.random(), "全部停止", snapshot)
        assertEquals(LiveInterjectionIntent.STOP, stopAll.intent)
        assertTrue(stopAll.stopAll)
        assertTrue(stopAll.usedFastLocalPath)

        val newTask = triager.classify(Uuid.random(), "新任务：帮我查明天上海天气", snapshot)
        assertEquals(LiveInterjectionIntent.INDEPENDENT_NEW_TASK, newTask.intent)
        assertTrue(newTask.usedFastLocalPath)

        assertEquals("Fast local routing must never invoke the auxiliary model", 0, auxiliaryCalls)
    }

    @Test
    fun `auxiliary timeout falls back to honest local status reply carrying active tool and elapsed time`() = runBlocking {
        val triager = LiveInterjectionTriager(
            auxiliaryClient = LiveInterjectionAuxiliaryClient { _, _, _ ->
                delay(5_000)
                """{"intent":"steer_active","reply":"late"}"""
            },
            timeoutMs = 50L,
        )
        val snapshot = ActiveTaskRuntimeSnapshot(
            taskId = "task-1",
            title = "整理周报",
            phase = RunExecutionPhase.EXECUTING_TOOLS,
            activeToolName = "workspace_search",
            completedToolCount = 2,
            elapsedSeconds = 14,
        )

        val status = triager.classify(Uuid.random(), "卡住了吗？", snapshot)
        assertEquals(LiveInterjectionIntent.STATUS_REPLY, status.intent)
        assertTrue(status.fellBackToLocal)
        assertTrue(status.reply.orEmpty().contains("workspace_search"))
        assertTrue(status.reply.orEmpty().contains("14"))

        val steer = triager.classify(Uuid.random(), "顺便改成英文输出", snapshot)
        assertEquals(LiveInterjectionIntent.STEER_ACTIVE, steer.intent)
        assertTrue(steer.fellBackToLocal)
        assertTrue(steer.reply.orEmpty().contains("workspace_search"))
    }

    @Test
    fun `status inquiry replies on timeline without interrupting active task or polluting model alternation`() = runBlocking {
        withTimeout(15_000) {
            val capturedPrompts = CopyOnWriteArrayList<String>()
            val triager = LiveInterjectionTriager(
                auxiliaryClient = LiveInterjectionAuxiliaryClient { _, prompt, _ ->
                    capturedPrompts += prompt
                    """{"intent":"status_reply","reply":"还在执行 web_search，已经跑了 6 秒，马上就好。"}"""
                },
            )
            var nowMs = 10_000L
            val fixture = createFixture(triager = triager, clockMs = { nowMs })
            val convId = Uuid.random()
            fixture.repo.insertConversation(Conversation.ofId(convId))
            fixture.store.ensureInitialized(convId)

            val taskARelease = CompletableDeferred<Unit>()
            var taskAPreempted = false
            fixture.runner.onTurn("调研 Kotlin 协程") { session ->
                session.interjectionController?.onPhaseChanged(
                    phase = RunExecutionPhase.EXECUTING_TOOLS,
                    activeToolName = "web_search",
                    completedToolCount = 1,
                )
                val streamingAssistant = UIMessage.assistant("正在检索中…")
                emit(GenerationChunk.Messages(session.messages + streamingAssistant))
                try {
                    taskARelease.await()
                } catch (cancelled: CancellationException) {
                    taskAPreempted = true
                    throw cancelled
                }
                val finalMessages = session.messages + streamingAssistant.copy(
                    parts = listOf(UIMessagePart.Text("调研报告完成")),
                )
                emit(GenerationChunk.Messages(finalMessages))
            }

            assertTrue(fixture.coordinator.submitMessage(convId, listOf(UIMessagePart.Text("调研 Kotlin 协程"))))
            fixture.runner.awaitTurnStarted("调研 Kotlin 协程")
            nowMs += 6_000L

            assertTrue(fixture.coordinator.submitMessage(convId, listOf(UIMessagePart.Text("怎么了"))))
            // Wait for the instant status reply to land on the timeline while Task A is still active
            val midConv = fixture.store.getConversationFlow(convId).first { conv ->
                conv.currentMessages.any { msg ->
                    msg.annotations.any { it is UIMessageAnnotation.LiveInterjection && it.kind == "status_reply" }
                }
            }
            assertEquals(1, fixture.coordinator.uiState.value.activeTasksForConversation(convId.toString()).size)
            assertFalse("Status inquiry must not interrupt the running task", taskAPreempted)
            assertTrue(capturedPrompts.single().contains("active_tool: web_search"))
            assertTrue(capturedPrompts.single().contains("elapsed_seconds: 6"))

            // Release Task A and verify final timeline + model sanitization
            taskARelease.complete(Unit)
            fixture.runner.awaitTurnCompleted("调研 Kotlin 协程")

            val finalMessages = fixture.store.getConversationFlow(convId).value.currentMessages
            assertTrue(finalMessages.any { it.toText() == "调研报告完成" })
            assertEquals(1, finalMessages.count { it.role == MessageRole.USER && it.toText() == "怎么了" })
            assertTrue(
                "Status reply must stay visible on the timeline",
                finalMessages.any { it.toText().contains("web_search") && it.isEphemeralInterjectionMessage },
            )
            // Model-bound messages must drop ephemeral status Q&A and keep clean role alternation
            val modelMessages = sanitizeMessagesForModelRound(finalMessages)
            assertTrue(modelMessages.none { it.isEphemeralInterjectionMessage })
            assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), modelMessages.map { it.role })
        }
    }

    @Test
    fun `thinking phase steer cancels in-flight round retracts draft and reruns immediately without duplicate user bubbles`() = runBlocking {
        withTimeout(15_000) {
            val round1Entered = CompletableDeferred<Unit>()
            val round1Cancelled = CompletableDeferred<Unit>()
            val round2Messages = CopyOnWriteArrayList<UIMessage>()
            val roundCount = AtomicInteger(0)

            val roundEngine = object : GenerationRoundEngine {
                override suspend fun generateRound(
                    request: GenerationRoundRequest,
                    onUpdateMessages: suspend (GenerationUpdate) -> Unit,
                ): GenerationRoundOutcome {
                    val attempt = roundCount.incrementAndGet()
                    if (attempt == 1) {
                        val draft = UIMessage.assistant("废弃的草稿第一段…")
                        onUpdateMessages(GenerationUpdate.streamingTail(request.messages + draft))
                        round1Entered.complete(Unit)
                        try {
                            CompletableDeferred<Unit>().await()
                        } catch (cancelled: CancellationException) {
                            round1Cancelled.complete(Unit)
                            throw cancelled
                        }
                    }
                    round2Messages.clear()
                    round2Messages.addAll(request.messages)
                    val answer = UIMessage.assistant("按新要求的三点总结完成")
                    onUpdateMessages(GenerationUpdate.full(request.messages + answer))
                    return GenerationRoundOutcome(outputLimitReached = false)
                }
            }

            val kernel = DefaultRunKernel(
                context = context,
                toolDispatcher = AgentToolDispatcher(json, PermissionDecisionResolver()),
                roundEngine = roundEngine,
            )
            val triager = LiveInterjectionTriager(
                auxiliaryClient = LiveInterjectionAuxiliaryClient { _, _, _ ->
                    """{"intent":"steer_active","reply":"收到，改成只用三点总结。"}"""
                },
            )
            val fixture = createFixture(triager = triager, realKernel = kernel)
            val convId = Uuid.random()
            fixture.repo.insertConversation(Conversation.ofId(convId))
            fixture.store.ensureInitialized(convId)

            assertTrue(fixture.coordinator.submitMessage(convId, listOf(UIMessagePart.Text("写一篇长文章"))))
            round1Entered.await()

            // Wait until the streamed draft lands in ConversationStore before preempting
            fixture.store.getConversationFlow(convId).first { conv ->
                conv.currentMessages.any { it.toText() == "废弃的草稿第一段…" }
            }

            // Send mid-thinking steer interjection
            assertTrue(fixture.coordinator.submitMessage(convId, listOf(UIMessagePart.Text("改成只用三点总结"))))
            round1Cancelled.await()
            fixture.runner.awaitTurnCompleted("写一篇长文章")

            assertEquals(2, roundCount.get())
            assertTrue(round2Messages.any { it.role == MessageRole.USER && it.toText() == "改成只用三点总结" })

            // Verify model sanitization merges consecutive USER turns cleanly
            val sanitizedForProvider = sanitizeMessagesForModelRound(round2Messages)
            assertEquals(listOf(MessageRole.USER), sanitizedForProvider.map { it.role })
            assertTrue(sanitizedForProvider.single().toText().contains("写一篇长文章"))
            assertTrue(sanitizedForProvider.single().toText().contains("改成只用三点总结"))

            // Verify final timeline: retracted draft gone, single user bubble, natural chronology
            val finalMessages = fixture.store.getConversationFlow(convId).value.currentMessages
            assertFalse(
                "Uncommitted draft from preempted round must be retracted",
                finalMessages.any { it.toText().contains("废弃的草稿") },
            )
            assertEquals(
                "Steer user message must never be duplicated on the timeline",
                1,
                finalMessages.count { it.role == MessageRole.USER && it.toText() == "改成只用三点总结" },
            )
            assertEquals(
                listOf(
                    "写一篇长文章",
                    "改成只用三点总结",
                    "收到，改成只用三点总结。",
                    "按新要求的三点总结完成",
                ),
                finalMessages.map { it.toText() },
            )
        }
    }

    @Test
    fun `tool phase steer lets active atomic tool finish skips unstarted sibling with paired replan_required and replans`() = runBlocking {
        withTimeout(15_000) {
            val tool1Started = CompletableDeferred<Unit>()
            val tool1Release = CompletableDeferred<Unit>()
            val executedToolNames = CopyOnWriteArrayList<String>()
            val steerChannel = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
            val pendingSteers = CopyOnWriteArrayList<UIMessage>()

            val controller = object : RunInterjectionController {
                override fun hasPendingSteer(): Boolean = pendingSteers.isNotEmpty()
                override val steerSignal: Flow<Unit> = steerChannel.asSharedFlow()
                override suspend fun consumePendingSteer(): List<UIMessage> {
                    val snapshot = pendingSteers.toList()
                    pendingSteers.clear()
                    return snapshot
                }
            }

            val tools = listOf(
                Tool(
                    name = "atomic_step_1",
                    description = "first write tool",
                    needsApproval = true,
                    execute = {
                        executedToolNames += "atomic_step_1"
                        tool1Started.complete(Unit)
                        tool1Release.await()
                        listOf(UIMessagePart.Text("""{"status":"ok","value":"step1-done"}"""))
                    },
                ),
                Tool(
                    name = "atomic_step_2",
                    description = "second write tool",
                    needsApproval = true,
                    execute = {
                        executedToolNames += "atomic_step_2"
                        listOf(UIMessagePart.Text("""{"status":"ok","value":"step2-done"}"""))
                    },
                ),
            )

            val roundInputs = CopyOnWriteArrayList<List<UIMessage>>()
            val roundEngine = object : GenerationRoundEngine {
                override suspend fun generateRound(
                    request: GenerationRoundRequest,
                    onUpdateMessages: suspend (GenerationUpdate) -> Unit,
                ): GenerationRoundOutcome {
                    roundInputs += request.messages
                    val reply = if (roundInputs.size == 1) {
                        UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(
                                UIMessagePart.Tool(
                                    toolCallId = "call-1",
                                    toolName = "atomic_step_1",
                                    input = "{}",
                                    approvalState = ToolApprovalState.Auto,
                                ),
                                UIMessagePart.Tool(
                                    toolCallId = "call-2",
                                    toolName = "atomic_step_2",
                                    input = "{}",
                                    approvalState = ToolApprovalState.Auto,
                                ),
                            ),
                        )
                    } else {
                        UIMessage.assistant("已结合新指令重规划完成")
                    }
                    onUpdateMessages(GenerationUpdate.full(request.messages + reply))
                    return GenerationRoundOutcome(outputLimitReached = false)
                }
            }

            val flags = CapabilityFlags(object : DataStore<Preferences> {
                override val data = MutableStateFlow(emptyPreferences())
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    transform(data.value).also { data.value = it }
            })
            val kernel = DefaultRunKernel(
                context = context,
                toolDispatcher = AgentToolDispatcher(json, PermissionDecisionResolver()),
                roundEngine = roundEngine,
                toolEffectLedger = ledger,
                capabilityFlags = flags,
            )

            val testModel = Model(modelId = "test-model", abilities = listOf(ModelAbility.TOOL))
            val runId = "durable-tool-steer-run"
            val chunks = CopyOnWriteArrayList<GenerationChunk>()
            val job = launch {
                kernel.run(
                    GenerationRunSession(
                        settings = Settings().toGenerationSettings(testModel),
                        model = testModel,
                        messages = listOf(UIMessage.user("执行两步操作")),
                        tools = tools,
                        maxSteps = 6,
                        autoApproveTools = true,
                        autoApproveHighRiskTools = true,
                        interjectionController = controller,
                        runId = runId,
                        onTerminal = {},
                    ),
                ).toList(chunks)
            }

            tool1Started.await()
            // Redirect arrives while atomic_step_1 is actively executing
            pendingSteers += UIMessage.user("第二步不用做了，直接总结")
            steerChannel.tryEmit(Unit)
            tool1Release.complete(Unit)
            job.join()

            // Verify atomic_step_1 completed safely and atomic_step_2 never started
            assertEquals(listOf("atomic_step_1"), executedToolNames)
            assertEquals(2, roundInputs.size)

            // Verify paired tool_result for both calls before replanning
            val secondRoundInput = roundInputs[1]
            val assistantWithTools = secondRoundInput.first { it.role == MessageRole.ASSISTANT }
            val toolParts = assistantWithTools.getTools()
            assertEquals(2, toolParts.size)
            val call1 = toolParts.first { it.toolCallId == "call-1" }
            val call2 = toolParts.first { it.toolCallId == "call-2" }
            assertTrue(call1.isExecuted)
            assertTrue(call1.output.filterIsInstance<UIMessagePart.Text>().single().text.contains("step1-done"))
            assertTrue("Skipped sibling tool must carry a paired tool_result", call2.isExecuted)
            val call2Payload = json.parseToJsonElement(
                call2.output.filterIsInstance<UIMessagePart.Text>().single().text,
            ).jsonObject
            assertEquals("not_sent", call2Payload["status"]?.jsonPrimitive?.content)
            assertEquals(REPLAN_REQUIRED_EFFECT_CATEGORY, call2Payload["reason"]?.jsonPrimitive?.content)

            // Verify durable ledger terminalized skipped effect cleanly
            val effect2 = ledger.getByToolCallId("call-2")
            assertNotNull(effect2)
            assertEquals(ToolEffectStatus.FAILED, effect2!!.status)
            assertEquals(REPLAN_REQUIRED_EFFECT_CATEGORY, effect2.errorCategory)

            // Verify user steer entered round 2 context right after settled tool batch
            assertEquals("第二步不用做了，直接总结", secondRoundInput.last().toText())
            val finalMessages = (chunks.last() as GenerationChunk.Messages).messages
            assertEquals("已结合新指令重规划完成", finalMessages.last().toText())
        }
    }

    @Test
    fun `steer arriving at terminal boundary seamlessly launches continuation without losing message`() = runBlocking {
        withTimeout(15_000) {
            val triageGate = CompletableDeferred<Unit>()
            val triageEntered = CompletableDeferred<Unit>()
            val triager = LiveInterjectionTriager(
                auxiliaryClient = LiveInterjectionAuxiliaryClient { _, _, _ ->
                    triageEntered.complete(Unit)
                    triageGate.await()
                    """{"intent":"steer_active","reply":"收到，补充到结果里。"}"""
                },
                timeoutMs = 5_000L,
            )
            val fixture = createFixture(triager = triager)
            val convId = Uuid.random()
            fixture.repo.insertConversation(Conversation.ofId(convId))
            fixture.store.ensureInitialized(convId)

            val taskARelease = CompletableDeferred<Unit>()
            fixture.runner.onTurn("原任务 A") { session ->
                taskARelease.await()
                emit(GenerationChunk.Messages(session.messages + UIMessage.assistant("原任务 A 完成")))
            }
            fixture.runner.onTurn("再补一条边界条件") { session ->
                val sawOriginalReply = session.messages.any { it.toText() == "原任务 A 完成" }
                emit(
                    GenerationChunk.Messages(
                        session.messages + UIMessage.assistant("续答完成(sawOriginal=$sawOriginalReply)"),
                    ),
                )
            }

            assertTrue(fixture.coordinator.submitMessage(convId, listOf(UIMessagePart.Text("原任务 A"))))
            fixture.runner.awaitTurnStarted("原任务 A")

            // User interjects while A is running; triage is held until right after A finishes
            assertTrue(fixture.coordinator.submitMessage(convId, listOf(UIMessagePart.Text("再补一条边界条件"))))
            triageEntered.await()

            // Task A completes and hits terminal state before triage finishes
            taskARelease.complete(Unit)
            fixture.runner.awaitTurnCompleted("原任务 A")

            // Now triage returns STEER_ACTIVE targeting the just-finished Task A -> must launch continuation!
            triageGate.complete(Unit)
            fixture.runner.awaitTurnCompleted("再补一条边界条件")

            val finalMessages = fixture.store.getConversationFlow(convId).value.currentMessages
            assertEquals(
                "Interjection user message must appear exactly once",
                1,
                finalMessages.count { it.role == MessageRole.USER && it.toText() == "再补一条边界条件" },
            )
            assertTrue(
                "Continuation turn must see Task A's final answer and reply seamlessly",
                finalMessages.any { it.toText() == "续答完成(sawOriginal=true)" },
            )
        }
    }

    @Test
    fun `unconsumed steer attached right before task completion triggers atomic continuation`() = runBlocking {
        withTimeout(15_000) {
            val triager = LiveInterjectionTriager(
                auxiliaryClient = LiveInterjectionAuxiliaryClient { _, _, _ ->
                    """{"intent":"steer_active","reply":"收到，继续补充。"}"""
                },
            )
            val fixture = createFixture(triager = triager)
            val convId = Uuid.random()
            fixture.repo.insertConversation(Conversation.ofId(convId))
            fixture.store.ensureInitialized(convId)

            val taskARelease = CompletableDeferred<Unit>()
            // Script deliberately does NOT call consumeSteerMessages(), simulating a steer
            // landing in the microsecond window after kernel's final check and before task exit.
            fixture.runner.onTurn("即将结束的任务") { session ->
                taskARelease.await()
                emit(GenerationChunk.Messages(session.messages + UIMessage.assistant("原任务结束")))
            }
            fixture.runner.onTurn("别漏掉最后一条") { session ->
                emit(GenerationChunk.Messages(session.messages + UIMessage.assistant("终态边界无缝续答成功")))
            }

            assertTrue(fixture.coordinator.submitMessage(convId, listOf(UIMessagePart.Text("即将结束的任务"))))
            fixture.runner.awaitTurnStarted("即将结束的任务")

            assertTrue(fixture.coordinator.submitMessage(convId, listOf(UIMessagePart.Text("别漏掉最后一条"))))
            // Wait until the followup is recorded on the active task, then let Task A finish without consuming it
            fixture.coordinator.uiState.first { state ->
                state.submissionsForConversation(convId.toString()).isNotEmpty()
            }
            taskARelease.complete(Unit)
            fixture.runner.awaitTurnCompleted("即将结束的任务")
            fixture.runner.awaitTurnCompleted("别漏掉最后一条")

            val finalMessages = fixture.store.getConversationFlow(convId).value.currentMessages
            assertEquals(1, finalMessages.count { it.role == MessageRole.USER && it.toText() == "别漏掉最后一条" })
            assertTrue(finalMessages.any { it.toText() == "终态边界无缝续答成功" })
        }
    }

    private fun createFixture(
        triager: LiveInterjectionTriager = LiveInterjectionTriager(),
        realKernel: RunKernel? = null,
        clockMs: () -> Long = System::currentTimeMillis,
    ): Fixture {
        val root = directory.newFolder()
        val fileContext = object : ContextWrapper(context) { override fun getFilesDir() = root }
        val scope = AppScope()
        val files = FilesManager(fileContext, FilesRepository(database.managedFileDao()), scope)
        val repo = ConversationRepository(
            database.conversationDao(),
            database.messageNodeDao(),
            database.messageStatsDao(),
            database.favoriteDao(),
            database,
            files,
            MessageFtsManager(database),
        )
        val model = Model(modelId = "test-model", abilities = listOf(ModelAbility.TOOL))
        val initialSettings = Settings(
            chatModelId = model.id,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
        )
        val settings = ReflectionFixture.allocate<SettingsAggregator>()
        ReflectionFixture.set(
            settings,
            "_settingsFlow",
            MutableStateFlow(
                initialSettings.copy(
                    agentRuntime = initialSettings.agentRuntime.copy(
                        enableLiveStatusNotification = false,
                        keepGenerationAliveInBackground = false,
                    ),
                ),
            ),
        )
        val pendingStore = PendingMessageStore(fileContext, JsonInstant)
        val queue = PendingMessageQueue(scope, pendingStore)
        val errors = ChatErrorStore()
        val store = ConversationStore(repo, settings, scope, files, queue, errors)
        val scheduler = AgentTaskScheduler(AgentTaskStore(fileContext, Json, loadDispatcher = Dispatchers.Unconfined))
        val presenter = ChatRunPresenter(
            fileContext,
            scope,
            AgentToolActivityStore(),
            AgentLiveStatusNotifier(context, NotificationApprovalTokenRegistry()),
            scheduler,
        )
        val events = InMemoryAgentEventStore()
        val ownership = RunOwnershipRegistry()
        val runner = TestTurnRunner(scope, store, realKernel)
        val coordinator = LocalAsyncTaskCoordinator(
            context = fileContext,
            appScope = scope,
            settingsStore = settings,
            conversationStore = store,
            userInputPreprocessor = UserInputPreprocessor(settings),
            runPresenter = presenter,
            agentRunner = runner,
            agentEventStore = events,
            runTerminalStore = runTerminalStore,
            runOwnershipRegistry = ownership,
            recoveryFileOverride = File(root, "recovery.json"),
            liveInterjectionTriager = triager,
            clockMs = clockMs,
        )
        runner.coordinator = coordinator
        return Fixture(coordinator, store, repo, scope, runner, presenter, queue).also(fixtures::add)
    }

    private class Fixture(
        val coordinator: LocalAsyncTaskCoordinator,
        val store: ConversationStore,
        val repo: ConversationRepository,
        val scope: AppScope,
        val runner: TestTurnRunner,
        val presenter: ChatRunPresenter,
        val queue: PendingMessageQueue,
    ) {
        fun close() {
            presenter.cleanup()
            queue.cleanup()
            scope.cancel()
        }
    }

    private class TestTurnRunner(
        private val scope: AppScope,
        private val conversationStore: ConversationStore,
        private val realKernel: RunKernel?,
    ) : AgentRunner {
        lateinit var coordinator: LocalAsyncTaskCoordinator
        private val scripts = ConcurrentHashMap<String, suspend kotlinx.coroutines.flow.FlowCollector<GenerationChunk>.(GenerationRunSession) -> Unit>()
        private val startedSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val completedSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val snapshots = ConcurrentHashMap<AgentRunId, MutableStateFlow<AgentRunSnapshot>>()
        private val jobs = ConcurrentHashMap<AgentRunId, Job>()

        fun onTurn(
            userText: String,
            block: suspend kotlinx.coroutines.flow.FlowCollector<GenerationChunk>.(GenerationRunSession) -> Unit,
        ) {
            scripts[userText] = block
            startedSignals.putIfAbsent(userText, CompletableDeferred())
            completedSignals.putIfAbsent(userText, CompletableDeferred())
        }

        suspend fun awaitTurnStarted(userText: String) {
            startedSignals.computeIfAbsent(userText) { CompletableDeferred() }.await()
        }

        suspend fun awaitTurnCompleted(userText: String) {
            completedSignals.computeIfAbsent(userText) { CompletableDeferred() }.await()
            coordinator.awaitTaskSettledByTitle(userText)
        }

        override fun <I : AgentInput> launch(
            descriptorId: AgentDescriptorId,
            input: I,
            requestedRunId: AgentRunId?,
        ): Result<AgentRunHandle> {
            val turnInput = input as ChatTurnInput
            val runId = requestedRunId ?: AgentRunId(Uuid.random().toString())
            val handle = AgentRunHandle(runId, descriptorId)
            val snapshotFlow = MutableStateFlow(
                AgentRunSnapshot(
                    runId = runId,
                    parentRunId = null,
                    descriptorId = descriptorId,
                    status = RunStatus.RUNNING,
                    startedAt = 1L,
                    finishedAt = null,
                ),
            )
            snapshots[runId] = snapshotFlow

            val kernel = realKernel ?: object : RunKernel {
                override fun run(session: GenerationRunSession): Flow<GenerationChunk> = flow {
                    startedSignals.computeIfAbsent(turnInput.userMessageText) { CompletableDeferred() }.complete(Unit)
                    val script = scripts[turnInput.userMessageText]
                    if (script != null) {
                        script(session)
                    } else {
                        emit(GenerationChunk.Messages(session.messages + UIMessage.assistant("Reply:${turnInput.userMessageText}")))
                    }
                }
            }

            val resolver = object : ChatSessionResolver {
                override suspend fun resolve(
                    input: ChatTurnInput,
                    runId: String,
                    events: app.amber.core.agent.runtime.AgentEventWriter?,
                ): ChatSession {
                    val convId = Uuid.parse(input.conversationId.value)
                    val anchorId = Uuid.parse(input.messageNodeId.value)
                    coordinator.bindRunToAnchorNode(anchorId, runId)
                    val conv = conversationStore.getConversationFlow(convId).value
                    val anchorIndex = conv.messageNodes.indexOfFirst { it.id == anchorId }
                    val slicedMessages = if (anchorIndex >= 0) {
                        conv.messageNodes.subList(0, anchorIndex + 1)
                            .map { it.currentMessage }
                            .filterNot { it.isEphemeralInterjectionMessage }
                    } else {
                        conv.currentMessages.filterNot { it.isEphemeralInterjectionMessage }
                    }
                    val hooks = ChatRunHooks(
                        durable = true,
                        processingStatus = coordinator.processingStatusForRun(runId, anchorId) ?: MutableStateFlow(null),
                        consumeSteerMessages = { coordinator.consumeFollowupsForRun(runId, anchorId) },
                        interjectionController = coordinator.interjectionControllerForRun(runId, anchorId),
                        onStreamingMessages = { rId, msgs ->
                            coordinator.onStreamingMessages(rId, anchorId, msgs)
                        },
                        onRunFinished = { _, _ ->
                            conversationStore.persistConversationWindow(
                                convId,
                                conversationStore.getConversationFlow(convId).value,
                                indexFts = true,
                            )
                        },
                    )
                    return ChatSession(
                        settings = Settings(),
                        model = Model(modelId = "test-model", abilities = listOf(ModelAbility.TOOL)),
                        messages = slicedMessages,
                        inputTransformers = emptyList(),
                        outputTransformers = emptyList(),
                        memories = emptyList(),
                        tools = emptyList(),
                        maxSteps = 8,
                        autoApproveTools = true,
                        autoApproveHighRiskTools = true,
                        autoApprovedToolNames = emptySet(),
                        invocationContext = ToolInvocationContext.Normal,
                        conversation = conv,
                        hooks = hooks,
                    )
                }
            }

            val agent = ChatTurnAgent(kernel, resolver, conversationStore)
            val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    agent.handler.handle(turnInput, LegacyRunScope(runId = runId))
                    snapshotFlow.value = snapshotFlow.value.copy(status = RunStatus.COMPLETED)
                } catch (cancelled: CancellationException) {
                    snapshotFlow.value = snapshotFlow.value.copy(status = RunStatus.CANCELLED)
                    throw cancelled
                } catch (_: Throwable) {
                    snapshotFlow.value = snapshotFlow.value.copy(status = RunStatus.FAILED)
                } finally {
                    completedSignals.computeIfAbsent(turnInput.userMessageText) { CompletableDeferred() }.complete(Unit)
                }
            }
            jobs[runId] = job
            return Result.success(handle)
        }

        override fun observe(runId: AgentRunId): StateFlow<AgentRunSnapshot> = snapshots.getValue(runId)

        override fun cancel(runId: AgentRunId) {
            jobs[runId]?.cancel()
            snapshots[runId]?.let { flow ->
                flow.value = flow.value.copy(status = RunStatus.CANCELLED)
            }
        }

        override suspend fun awaitRunSettled(runId: AgentRunId) {
            jobs[runId]?.join()
        }

        override suspend fun listUnfinishedRuns(): List<AgentRunSnapshot> =
            snapshots.values.map { it.value }.filterNot { it.status.isTerminal }
    }
}
