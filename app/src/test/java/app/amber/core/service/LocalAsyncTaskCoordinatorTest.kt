package app.amber.core.service

import android.content.ContextWrapper
import app.amber.agent.data.db.fts.MessageFtsManager
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageAnnotation
import app.amber.ai.ui.UIMessagePart
import app.amber.core.agent.runtime.AgentDescriptorId
import app.amber.core.agent.runtime.AgentInput
import app.amber.core.agent.runtime.AgentRunHandle
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunSnapshot
import app.amber.core.agent.runtime.AgentRunner
import app.amber.core.agent.runtime.InMemoryAgentEventStore
import app.amber.core.agent.runtime.RunStatus
import app.amber.core.agent.runtime.adapter.LegacyRunScope
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.RunKernel
import app.amber.core.files.FilesManager
import app.amber.core.infra.AppScope
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.core.repository.FilesRepository
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.utils.JsonInstant
import app.amber.feature.chat.api.ChatTurnInput
import app.amber.feature.chat.impl.ChatRunHooks
import app.amber.feature.chat.impl.ChatSession
import app.amber.feature.chat.impl.ChatSessionResolver
import app.amber.feature.chat.impl.ChatTurnAgent
import app.amber.feature.macgateway.CloudInputDisposition
import app.amber.feature.runtime.AgentLiveStatusNotifier
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.runtime.DurableRuntimeTestBase
import app.amber.feature.runtime.NotificationApprovalTokenRegistry
import app.amber.feature.runtime.RunOwnershipRegistry
import app.amber.feature.runtime.ToolInvocationContext
import app.amber.feature.task.AgentTaskScheduler
import app.amber.feature.task.AgentTaskStore
import app.amber.review.ReflectionFixture
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalAsyncTaskCoordinatorTest : DurableRuntimeTestBase() {
    @get:Rule val directory = TemporaryFolder()
    private val fixtures = mutableListOf<AsyncFixture>()

    @Before
    fun setMainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun closeFixtures() {
        fixtures.forEach { it.close() }
        Dispatchers.resetMain()
    }

    @Test
    fun `same conversation task B starts and answers immediately while task A is still running`() = runBlocking {
        withTimeout(15_000) {
            val fixture = createFixture(maxConcurrentTasks = 3)
            val convId = Uuid.random()
            fixture.repo.insertConversation(Conversation.ofId(convId))
            fixture.store.ensureInitialized(convId)

            val taskARunGate = CompletableDeferred<Unit>()
            fixture.runner.onTurn("Question A") { session ->
                val streamingAssistantA = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "tool-a-1",
                            toolName = "web_search",
                            input = """{"q":"A"}""",
                            approvalState = ToolApprovalState.Auto,
                        ),
                    ),
                )
                emit(
                    GenerationChunk.Messages(
                        session.messages + streamingAssistantA,
                    ),
                )
                taskARunGate.await()
                val steer = session.consumeSteerMessages()
                emit(
                    GenerationChunk.Messages(
                        session.messages + steer + streamingAssistantA.copy(
                            parts = listOf(UIMessagePart.Text("Answer A final")),
                        ),
                    ),
                )
            }
            fixture.runner.onTurn("Question B") { session ->
                emit(
                    GenerationChunk.Messages(
                        session.messages + UIMessage.assistant("Answer B immediate"),
                    ),
                )
            }

            assertTrue(fixture.coordinator.submitMessage(convId, listOf(UIMessagePart.Text("Question A"))))
            fixture.runner.awaitTurnStarted("Question A")

            // While Task A is still running (blocked on taskARunGate), send Question B.
            assertTrue(fixture.coordinator.submitMessage(convId, listOf(UIMessagePart.Text("Question B"))))
            fixture.runner.awaitTurnCompleted("Question B")

            // Task B has already answered while Task A is still active!
            val activeTasksMidFlight = fixture.coordinator.uiState.value.activeTasksForConversation(convId.toString())
            assertEquals(1, activeTasksMidFlight.size)
            assertEquals("Question A", activeTasksMidFlight.single().title)

            val midMessages = fixture.store.getConversationFlow(convId).value.currentMessages.map { it.toText() }
            assertTrue("Answer B must already be in conversation before A finishes", midMessages.contains("Answer B immediate"))

            // Now let Task A finish: its final answer lands at Task A's position (before Question B).
            taskARunGate.complete(Unit)
            fixture.runner.awaitTurnCompleted("Question A")

            val finalMessages = fixture.store.getConversationFlow(convId).value.currentMessages.map { it.toText() }
            assertEquals(
                listOf("Question A", "Answer A final", "Question B", "Answer B immediate"),
                finalMessages,
            )
        }
    }

    @Test
    fun `normal message starts new task while explicitly selected task consumes followup at next reasoning boundary`() = runBlocking {
        withTimeout(15_000) {
            val fixture = createFixture(maxConcurrentTasks = 3)
            val convId = Uuid.random()
            fixture.repo.insertConversation(Conversation.ofId(convId))
            fixture.store.ensureInitialized(convId)

            val step1Barrier = CompletableDeferred<Unit>()
            fixture.runner.onTurn("Task A") { session ->
                val step1Assistant = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("Step 1 thinking")),
                )
                emit(GenerationChunk.Messages(session.messages + step1Assistant))
                step1Barrier.await()
                // Next reasoning boundary drains Task A's followup queue
                val followups = session.consumeSteerMessages()
                val followupTexts = followups.joinToString(",") { it.toText() }
                val step2Assistant = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("Answer A with followup: $followupTexts")),
                )
                emit(GenerationChunk.Messages(session.messages + step1Assistant + followups + step2Assistant))
            }

            assertTrue(fixture.coordinator.submitMessage(convId, listOf(UIMessagePart.Text("Task A"))))
            fixture.runner.awaitTurnStarted("Task A")
            val taskAId = fixture.coordinator.uiState.value
                .activeTasksForConversation(convId.toString())
                .single()
                .workItemId

            // Explicit selection of Task A routes the next submission as a followup to Task A
            fixture.coordinator.selectFollowupTarget(convId, taskAId)
            assertTrue(fixture.coordinator.submitMessage(convId, listOf(UIMessagePart.Text("Extra constraint X"))))

            // Before reasoning boundary: disposition is RECEIVED, and no second task was created
            assertEquals(1, fixture.coordinator.uiState.value.activeTasksForConversation(convId.toString()).size)
            val receivedSub = fixture.coordinator.uiState.value.submissionsForConversation(convId.toString()).single()
            assertEquals(CloudInputDisposition.RECEIVED, receivedSub.state)
            assertEquals(taskAId, receivedSub.targetWorkItemId)

            // Release reasoning boundary -> Task A consumes the followup -> disposition becomes CONSUMED
            step1Barrier.complete(Unit)
            fixture.runner.awaitTurnCompleted("Task A")

            val consumedSub = fixture.coordinator.uiState.value.submissionsForConversation(convId.toString()).single()
            assertEquals(CloudInputDisposition.CONSUMED, consumedSub.state)

            val texts = fixture.store.getConversationFlow(convId).value.currentMessages.map { it.toText() }
            assertTrue(texts.contains("Extra constraint X"))
            assertTrue(texts.contains("Answer A with followup: Extra constraint X"))
        }
    }

    @Test
    fun `stopping task A only stops A cancels unconsumed followups and updates progress in place`() = runBlocking {
        withTimeout(15_000) {
            val fixture = createFixture(maxConcurrentTasks = 3)
            val convId = Uuid.random()
            fixture.repo.insertConversation(Conversation.ofId(convId))
            fixture.store.ensureInitialized(convId)

            val hangA = CompletableDeferred<Unit>()
            val hangB = CompletableDeferred<Unit>()
            val partialIdA = Uuid.random()

            fixture.runner.onTurn("Task A") { session ->
                emit(
                    GenerationChunk.Messages(
                        session.messages + UIMessage(
                            id = partialIdA,
                            role = MessageRole.ASSISTANT,
                            parts = listOf(
                                UIMessagePart.Text("Partial A"),
                                UIMessagePart.Tool(
                                    toolCallId = "call-a",
                                    toolName = "search_docs",
                                    input = "{}",
                                    approvalState = ToolApprovalState.Auto,
                                ),
                            ),
                        ),
                    ),
                )
                hangA.await()
            }
            fixture.runner.onTurn("Task B") { session ->
                hangB.await()
                emit(GenerationChunk.Messages(session.messages + UIMessage.assistant("Completed B")))
            }

            fixture.coordinator.submitMessage(convId, listOf(UIMessagePart.Text("Task A")))
            fixture.runner.awaitTurnStarted("Task A")
            fixture.coordinator.submitMessage(convId, listOf(UIMessagePart.Text("Task B")))
            fixture.runner.awaitTurnStarted("Task B")

            val tasks = fixture.coordinator.uiState.value.activeTasksForConversation(convId.toString())
            assertEquals(2, tasks.size)
            val taskA = tasks.first { it.title == "Task A" }
            val taskB = tasks.first { it.title == "Task B" }

            // Verify single-line in-place progress update on Task A
            assertEquals("执行工具：search_docs", taskA.progressText)
            fixture.coordinator.updateTaskProgress(taskA.workItemId, "读取文件：README.md\n第二行忽略")
            val updatedTaskA = fixture.coordinator.uiState.value
                .activeTasksForConversation(convId.toString())
                .first { it.workItemId == taskA.workItemId }
            assertEquals("读取文件：README.md", updatedTaskA.progressText)
            assertEquals(2, fixture.coordinator.uiState.value.activeTasksForConversation(convId.toString()).size)

            // Queue a followup to Task A, then stop Task A before it reaches a reasoning boundary
            fixture.coordinator.submitFollowup(convId, taskA.workItemId, listOf(UIMessagePart.Text("Never consumed")))
            assertEquals(
                CloudInputDisposition.RECEIVED,
                fixture.coordinator.uiState.value.submissionsForConversation(convId.toString()).single().state,
            )

            // Stop ONLY Task A
            assertTrue(fixture.coordinator.stopTask(convId, taskA.workItemId))

            // Unconsumed followup is marked CANCELLED, Task B is still active!
            assertEquals(
                CloudInputDisposition.CANCELLED,
                fixture.coordinator.uiState.value.submissionsForConversation(convId.toString()).single().state,
            )
            val remainingActive = fixture.coordinator.uiState.value.activeTasksForConversation(convId.toString())
            assertEquals(listOf(taskB.workItemId), remainingActive.map { it.workItemId })

            // Let Task B finish normally
            hangB.complete(Unit)
            fixture.runner.awaitTurnCompleted("Task B")

            val finalNodes = fixture.store.getConversationFlow(convId).value.messageNodes
            assertEquals(
                listOf("Task A", "Partial A", "Task B", "Completed B"),
                finalNodes.map { it.currentMessage.toText().trim() },
            )
            val settledMessageA = finalNodes[1].currentMessage
            assertTrue(settledMessageA.annotations.any { it is UIMessageAnnotation.GenerationInterrupted })
            assertTrue(settledMessageA.getTools().single().isExecuted)
        }
    }

    @Test
    fun `different conversations run concurrently without blocking and respect small concurrency cap`() = runBlocking {
        withTimeout(15_000) {
            val fixture = createFixture(maxConcurrentTasks = 2)
            val conv1 = Uuid.random()
            val conv2 = Uuid.random()
            fixture.repo.insertConversation(Conversation.ofId(conv1))
            fixture.repo.insertConversation(Conversation.ofId(conv2))
            fixture.store.ensureInitialized(conv1)
            fixture.store.ensureInitialized(conv2)

            val gate1 = CompletableDeferred<Unit>()
            val gate2 = CompletableDeferred<Unit>()

            fixture.runner.onTurn("Conv1 Task1") { session ->
                gate1.await()
                emit(GenerationChunk.Messages(session.messages + UIMessage.assistant("Conv1 Reply1")))
            }
            fixture.runner.onTurn("Conv2 Task2") { session ->
                gate2.await()
                emit(GenerationChunk.Messages(session.messages + UIMessage.assistant("Conv2 Reply2")))
            }
            fixture.runner.onTurn("Conv1 Task3 Queued") { session ->
                emit(GenerationChunk.Messages(session.messages + UIMessage.assistant("Conv1 Reply3")))
            }

            // Submit Task 1 in conv1 and Task 2 in conv2 -> both run immediately (cap = 2)
            fixture.coordinator.submitMessage(conv1, listOf(UIMessagePart.Text("Conv1 Task1")))
            fixture.coordinator.submitMessage(conv2, listOf(UIMessagePart.Text("Conv2 Task2")))
            fixture.runner.awaitTurnStarted("Conv1 Task1")
            fixture.runner.awaitTurnStarted("Conv2 Task2")

            // Submit Task 3 in conv1 -> exceeds cap = 2, so it enters QUEUED state with queued status text
            fixture.coordinator.submitMessage(conv1, listOf(UIMessagePart.Text("Conv1 Task3 Queued")))
            val conv1TasksWhileFull = fixture.coordinator.uiState.value.activeTasksForConversation(conv1.toString())
            assertEquals(2, conv1TasksWhileFull.size)
            val queuedTask = conv1TasksWhileFull.first { it.title == "Conv1 Task3 Queued" }
            assertEquals("queued", queuedTask.outcome)
            assertTrue(queuedTask.progressText.orEmpty().contains("排队中"))

            // Finish Task 2 in conv2 -> frees a slot -> Task 3 in conv1 automatically dequeues and completes!
            gate2.complete(Unit)
            fixture.runner.awaitTurnCompleted("Conv2 Task2")
            fixture.runner.awaitTurnCompleted("Conv1 Task3 Queued")

            gate1.complete(Unit)
            fixture.runner.awaitTurnCompleted("Conv1 Task1")

            assertEquals(
                listOf("Conv2 Task2", "Conv2 Reply2"),
                fixture.store.getConversationFlow(conv2).value.currentMessages.map { it.toText() },
            )
            assertEquals(
                listOf("Conv1 Task1", "Conv1 Reply1", "Conv1 Task3 Queued", "Conv1 Reply3"),
                fixture.store.getConversationFlow(conv1).value.currentMessages.map { it.toText() },
            )
        }
    }

    @Test
    fun `cold start recovery marks unfinished and queued tasks interrupted at their anchor positions`() = runBlocking {
        withTimeout(15_000) {
            val recoveryFile = File(directory.newFolder(), "local_async_tasks_recovery.json")
            val fixture1 = createFixture(maxConcurrentTasks = 1, recoveryFile = recoveryFile)
            val convId = Uuid.random()
            fixture1.repo.insertConversation(Conversation.ofId(convId))
            fixture1.store.ensureInitialized(convId)

            val neverFinish = CompletableDeferred<Unit>()
            val firstChunkMerged = CompletableDeferred<Unit>()
            fixture1.runner.onTurn("Running before kill") { session ->
                emit(
                    GenerationChunk.Messages(
                        session.messages + UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(UIMessagePart.Text("Half streamed before OS kill")),
                        ),
                    ),
                )
                firstChunkMerged.complete(Unit)
                neverFinish.await()
            }

            // Task 1 starts running; Task 2 queues behind it (maxConcurrentTasks = 1)
            fixture1.coordinator.submitMessage(convId, listOf(UIMessagePart.Text("Running before kill")))
            fixture1.runner.awaitTurnStarted("Running before kill")
            firstChunkMerged.await()
            fixture1.coordinator.submitMessage(convId, listOf(UIMessagePart.Text("Queued before kill")))
            fixture1.store.getConversationFlow(convId).first { conv ->
                conv.messageNodes.any { it.currentMessage.toText() == "Queued before kill" }
            }

            assertTrue("Recovery snapshot file must exist while tasks are active/queued", recoveryFile.exists())

            // Simulate OS process death and app reopen with a fresh coordinator reading the recovery file
            val fixture2 = createFixture(
                maxConcurrentTasks = 2,
                recoveryFile = recoveryFile,
                sharedStore = fixture1.store,
                sharedRepo = fixture1.repo,
            )
            fixture2.coordinator.recoverInterruptedTasks()

            assertFalse("Recovery file should be cleaned up after recovery", recoveryFile.exists())
            val recoveredNodes = fixture2.store.getConversationFlow(convId).value.messageNodes
            assertEquals(4, recoveredNodes.size)
            assertEquals("Running before kill", recoveredNodes[0].currentMessage.toText())
            assertTrue(
                recoveredNodes[1].currentMessage.annotations.any { it is UIMessageAnnotation.GenerationInterrupted },
            )
            assertEquals("Queued before kill", recoveredNodes[2].currentMessage.toText())
            assertTrue(
                recoveredNodes[3].currentMessage.annotations.any { it is UIMessageAnnotation.GenerationInterrupted },
            )
            neverFinish.complete(Unit)
            fixture1.runner.awaitTurnCompleted("Running before kill")
        }
    }

    private fun createFixture(
        maxConcurrentTasks: Int = 3,
        recoveryFile: File = File(directory.newFolder(), "recovery.json"),
        sharedStore: ConversationStore? = null,
        sharedRepo: ConversationRepository? = null,
    ): AsyncFixture {
        val root = directory.newFolder()
        val fileContext = object : ContextWrapper(context) { override fun getFilesDir() = root }
        val scope = AppScope()
        val files = FilesManager(fileContext, FilesRepository(database.managedFileDao()), scope)
        val repo = sharedRepo ?: ConversationRepository(
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
        val store = sharedStore ?: ConversationStore(repo, settings, scope, files, queue, errors)
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
        val runner = ScriptedTurnRunner(scope, store)
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
            maxConcurrentTasks = maxConcurrentTasks,
            recoveryFileOverride = recoveryFile,
        )
        runner.coordinator = coordinator
        return AsyncFixture(coordinator, store, repo, scope, runner, presenter, queue).also(fixtures::add)
    }

    private class AsyncFixture(
        val coordinator: LocalAsyncTaskCoordinator,
        val store: ConversationStore,
        val repo: ConversationRepository,
        val scope: AppScope,
        val runner: ScriptedTurnRunner,
        val presenter: ChatRunPresenter,
        val queue: PendingMessageQueue,
    ) {
        fun close() {
            presenter.cleanup()
            queue.cleanup()
            scope.cancel()
        }
    }

    private class ScriptedTurnRunner(
        private val scope: AppScope,
        private val conversationStore: ConversationStore,
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

            val kernel = object : RunKernel {
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
                        conv.messageNodes.subList(0, anchorIndex + 1).map { it.currentMessage }
                    } else {
                        conv.currentMessages
                    }
                    val hooks = ChatRunHooks(
                        durable = true,
                        processingStatus = coordinator.processingStatusForRun(runId, anchorId) ?: MutableStateFlow(null),
                        consumeSteerMessages = { coordinator.consumeFollowupsForRun(runId, anchorId) },
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
                        model = Model(modelId = "test-model"),
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
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    snapshotFlow.value = snapshotFlow.value.copy(status = RunStatus.CANCELLED)
                    throw cancelled
                } catch (e: Throwable) {
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
