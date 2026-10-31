package app.amber.core.service

import android.content.Context
import android.util.Log
import app.amber.agent.AppScope
import app.amber.agent.BuildConfig
import app.amber.agent.R
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.ModelAbility
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.finishPendingTools
import app.amber.ai.ui.finishReasoning
import app.amber.ai.ui.isEmptyInputMessage
import app.amber.ai.ui.ToolApprovalState
import app.amber.core.agent.runtime.AgentRunHandle
import app.amber.core.automation.ScreenCaptureManager
import app.amber.core.ai.mcp.McpManager
import app.amber.core.context.ConversationContextEngine
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.model.toMessageNode
import app.amber.core.settings.CapabilityFlags
import app.amber.core.settings.Capability
import app.amber.core.settings.Settings
import app.amber.core.settings.findProvider
import app.amber.core.settings.getCurrentChatModel
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.core.utils.ChatSendTransitionTracker
import app.amber.feature.runtime.NotificationApprovalCheck
import app.amber.feature.runtime.NotificationApprovalTokenRegistry
import app.amber.feature.runtime.PauseReason
import app.amber.feature.runtime.RunOwnershipRegistry
import app.amber.feature.runtime.RunRecoveryService
import app.amber.feature.runtime.RunTerminalState
import app.amber.feature.runtime.RunTerminalStore
import app.amber.feature.runtime.StoredResponseGateway
import app.amber.feature.runtime.StoredResponseStopCancel
import app.amber.feature.runtime.ToolEffectLedger
import app.amber.feature.runtime.argsDigest
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private const val TAG = "ChatRunCoordinator"

/** Owns admitted chat dispatches and their stop/resume commands, independently of UI entry points. */
class ChatRunCoordinator(
    private val context: Context,
    private val appScope: AppScope,
    private val settingsStore: SettingsAggregator,
    private val conversationStore: ConversationStore,
    private val pendingMessageQueue: PendingMessageQueue,
    private val errorStore: ChatErrorStore,
    private val runPresenter: ChatRunPresenter,
    private val approvalActions: ChatApprovalActions,
    private val contextEngine: ConversationContextEngine,
    private val screenCaptureManager: ScreenCaptureManager,
    private val userInputPreprocessor: UserInputPreprocessor,
    private val conversationEditor: ConversationEditor,
    private val mcpManager: McpManager,
    private val agentRunner: app.amber.core.agent.runtime.AgentRunner? = null,
    private val agentEventStore: app.amber.core.agent.runtime.AgentEventStore? = null,
    private val capabilityFlags: CapabilityFlags? = null,
    private val toolEffectLedger: ToolEffectLedger? = null,
    private val runTerminalStore: RunTerminalStore? = null,
    private val runRecovery: RunRecoveryService? = null,
    private val coldStartRecoveryGate: app.amber.feature.runtime.ColdStartRuntimeRecoveryGate? = null,
    private val runOwnershipRegistry: RunOwnershipRegistry? = null,
    private val notificationApprovalTokens: NotificationApprovalTokenRegistry? = null,
    private val responsesResumeStore: app.amber.ai.provider.ResponseResumeStore? = null,
    private val storedResponseGateway: StoredResponseGateway? = null,
    private val storedResponseStopCancel: StoredResponseStopCancel? = null,
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
) {
    private val _generationDoneFlow = MutableSharedFlow<Uuid>(extraBufferCapacity = 16)
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()
    private val restoreWriteGateRegistration: AutoCloseable?

    private suspend fun useDurableRuntime(): Boolean = chatDurableRuntimeEnabled(
        capabilityFlags, toolEffectLedger, runTerminalStore, runRecovery,
    )

    /**
     * P6-01: the Stop path touches the server only when the capability is
     * fully on (capability flag + durable runtime + resume store wiring).
     */
    private suspend fun durableRuntimeForStop(): Boolean =
        useDurableRuntime() &&
            capabilityFlags?.isEnabled(app.amber.core.settings.Capability.OpenAIResponsesResume) == true &&
            responsesResumeStore != null &&
            storedResponseGateway != null

    /**
     * P6-01 MAJOR: the user switch ([ProviderSetting.OpenAI.enableResponsesResume])
     * is part of the Stop-path gate too — off means the pre-P6-01 behavior
     * (local cancel only, no server call, no WAITING_EXTERNAL). The switch,
     * the persisted cursor and the strict provider match are all resolved in
     * one place by [StoredResponseGateway.resolve] — the same session the
     * recovery worker uses. No cursor means nothing was stored server-side —
     * nothing to cancel.
     */
    private suspend fun storedResponseToggleOnForRun(runId: String): Boolean {
        val gateway = storedResponseGateway ?: return false
        return gateway.resolve(runId)?.api != null
    }

    init {
        restoreWriteGateRegistration = restoreWriteGate?.addRestoreLifecycleListener(
            onStarted = {
                activeKernelRuns.value.values.forEach { agentRunner?.cancel(it) }
                conversationStore.loadedSessionIds().forEach { conversationId ->
                    runPresenter.stopGenerationKeepAlive(conversationId)
                    runPresenter.cancelLiveStatus(conversationId)
                }
                approvalActions.clearAllTrust()
                screenCaptureManager.releaseSession()
            },
            onFinished = { succeeded, dataCommitted ->
                if (succeeded || dataCommitted) approvalActions.clearAllTrust()
            },
        )
    }

    fun cleanup() {
        restoreWriteGateRegistration?.close()
    }

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession =
        conversationStore.session(conversationId)

    private fun recordPendingMessageEvent(
        conversationId: Uuid,
        event: String,
        messageId: String? = null,
        count: Int? = null,
        detail: String? = null,
    ) = pendingMessageQueue.recordPendingMessageEvent(conversationId, event, messageId, count, detail)

    private fun restoreWriteContext(expectedRestoreEpoch: Long? = restoreWriteGate?.currentEpoch()): CoroutineContext =
        expectedRestoreEpoch?.let(::SyncRestoreWriteEpoch) ?: EmptyCoroutineContext

    private suspend fun expectedRestoreEpoch(): Long? =
        coroutineContext[SyncRestoreWriteEpoch]?.value

    private suspend fun captureRestoreEpoch(): Long? {
        val gate = restoreWriteGate ?: return null
        return coroutineContext[SyncRestoreWriteEpoch]?.value ?: gate.currentEpoch()
    }

    private suspend fun <T> withRestoreEpoch(
        expectedRestoreEpoch: Long?,
        block: suspend () -> T,
    ): T {
        if (restoreWriteGate == null) return block()
        val epoch = expectedRestoreEpoch
            ?: coroutineContext[SyncRestoreWriteEpoch]?.value
            ?: restoreWriteGate.currentEpoch()
        return withContext(SyncRestoreWriteEpoch(epoch)) { block() }
    }

    /** Capture the caller's epoch before a read/transform/write operation. */
    private suspend fun <T> withCapturedRestoreWriteContext(
        block: suspend () -> T,
    ): T = withRestoreEpoch(captureRestoreEpoch(), block)

    suspend fun initializeConversation(conversationId: Uuid) {
        conversationStore.ensureInitialized(conversationId)
        launchPendingDispatchIfNeeded(conversationId, getOrCreateSession(conversationId))
    }

    private fun launchPendingDispatchIfNeeded(
        conversationId: Uuid,
        session: ConversationSession,
    ) {
        if (!session.isGenerating && session.pendingUserMessages.value.isNotEmpty()) {
            launchViaKernel(conversationId)
        }
    }

    private suspend fun ensureFullConversationLoaded(conversationId: Uuid): Conversation =
        conversationStore.ensureFullConversationLoaded(conversationId)

    fun sendMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        queueMode: PendingUserMessageMode = PendingUserMessageMode.FOLLOWUP,
    ): Boolean {
        if (content.isEmptyInputMessage()) return false

        val session = getOrCreateSession(conversationId)
        val previousDispatch = session.getJob()
        val waitingForCleanup = previousDispatch != null && !previousDispatch.isActive && !previousDispatch.isCompleted
        val processedContent = userInputPreprocessor.process(content)
        val pendingMessage = PendingUserMessage(
            id = Uuid.random().toString(),
            parts = processedContent,
            answer = answer,
            mode = if (session.isGenerating) queueMode else PendingUserMessageMode.FOLLOWUP,
        )

        // A matching Stop can still cancel the waiting successor drain. Keep
        // accepted input in the existing durable queue until the handoff ends.
        if (session.isGenerating || waitingForCleanup || session.pendingUserMessages.value.isNotEmpty()) {
            val accepted = pendingMessageQueue.enqueuePendingUserMessage(conversationId, pendingMessage)
            if (!accepted) {
                errorStore.addError(
                    IllegalStateException(context.getString(R.string.chat_page_queue_full_error)),
                    conversationId = conversationId,
                    title = context.getString(R.string.chat_page_queue_message_not_added)
                )
                return false
            } else {
                pendingMessageQueue.persistCurrentPendingMessagesDurably(conversationId)
                recordPendingMessageEvent(
                    conversationId = conversationId,
                    event = "enqueue",
                    messageId = pendingMessage.id,
                    detail = pendingMessage.mode.name.lowercase(),
                )
            }
            // A queue restored after process death must stay ahead of a new
            // message entered while the session is idle; otherwise the new
            // message would bypass the durable FIFO and run first.
            if (!session.isGenerating) {
                launchViaKernel(conversationId)
            }
            return true
        }

        launchViaKernel(conversationId, pendingMessage)
        return true
    }

    /** Admit one fresh task message and bind its launch to the background owner. */
    internal suspend fun startTaskMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
    ): ChatTaskRun = admitChatTaskRun {
        require(!content.isEmptyInputMessage()) { "Task message cannot be empty" }
        val runner = requireNotNull(agentRunner) { "Agent runtime is unavailable" }
        coldStartRecoveryGate?.awaitReady()
        val session = getOrCreateSession(conversationId)
        ensureFullConversationLoaded(conversationId)
        val durableOwner = if (useDurableRuntime()) {
            runTerminalStore?.activeForConversation(conversationId.toString())
        } else {
            null
        }
        check(
            !session.isGenerating && session.pendingUserMessages.value.isEmpty() &&
                !session.state.value.hasPendingOrUnexecutedTools() && durableOwner == null
        ) { "The task conversation already has running or pending work" }
        val message = PendingUserMessage(
            id = Uuid.random().toString(),
            parts = userInputPreprocessor.process(content),
        )
        val launchedRun = CompletableDeferred<AgentRunHandle>()
        val dispatchJob = checkNotNull(
            launchViaKernel(conversationId, firstMessage = message, firstRunLaunch = launchedRun)
        ) { "Chat generation did not start" }
        ChatTaskRun(
            launchedRun,
            runner,
            dispatchJob,
            awaitDispatchHandoff = { runId -> awaitChatRunDispatchHandoff(session, activeKernelRuns, runId) },
        ) { runId ->
            stopGeneration(conversationId, runId.value)
        }
    }

    private val activeKernelRuns =
        MutableStateFlow<Map<Uuid, app.amber.core.agent.runtime.AgentRunId>>(emptyMap())

    /** Conversations whose run paused for the user (approval / ask_user). The
     *  kernel dispatch wait ends on pause — these are invisible to
     *  activeKernelRuns but app-external surfaces (task bubble) must keep
     *  showing them until a resume dispatch or a stop clears the entry. */
    private val pausedForUserConversations = MutableStateFlow<Set<Uuid>>(emptySet())

    /** Latest terminal RunStatus per conversation, recorded when a dispatch
     *  wait ends in a terminal state or the dispatch job is cancelled. */
    private val _lastRunOutcomes = MutableStateFlow<Map<Uuid, app.amber.core.agent.runtime.RunStatus>>(emptyMap())
    val lastRunOutcomes: StateFlow<Map<Uuid, app.amber.core.agent.runtime.RunStatus>> =
        _lastRunOutcomes.asStateFlow()

    /** Conversation ids with an active or user-paused run; drives app-external surfaces (task bubble). */
    val activeConversationIds: StateFlow<Set<Uuid>> =
        combine(activeKernelRuns, pausedForUserConversations) { runs, paused -> runs.keys + paused }
            .stateIn(appScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptySet())

    /** Latest active kernel-path run for a conversation, or null if none. */
    fun getActiveKernelRunFlow(
        conversationId: Uuid,
        scope: CoroutineScope,
    ): StateFlow<app.amber.core.agent.runtime.AgentRunId?> =
        activeKernelRuns
            .map { it[conversationId] }
            .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, activeKernelRuns.value[conversationId])

    /** Exposes the AgentRunner for UI ViewModels to call observe() directly. */
    fun kernelRunner(): app.amber.core.agent.runtime.AgentRunner? = agentRunner

    /**
     * Kernel dispatch loop for a conversation: drains the pending queue one
     * turn at a time (mirroring the legacy loop's structure), each turn
     * executed by the AgentRunner. [resumeWithoutNewMessage] starts the
     * drain with a turn over the current conversation (tool approval /
     * regenerate / outcome retry / edit-regenerate) instead of a newly
     * appended user message; [messageRange] restricts that resume turn to a
     * conversation window (variant regenerate).
     */
    /**
     * External entry into the kernel dispatcher: launches the queue-draining
     * dispatch loop as the conversation's session job. Callers that already
     * run INSIDE the session job (approval / regenerate / in-loop resume)
     * must use [runKernelDispatchLoop] inline instead — this guard would
     * deterministically swallow them, since the caller's own job is what
     * makes [ConversationSession.isGenerating] true.
     */
    private fun launchViaKernel(
        conversationId: Uuid,
        firstMessage: PendingUserMessage? = null,
        resumeWithoutNewMessage: Boolean = false,
        messageRange: ClosedRange<Int>? = null,
        expectedPausedRunId: app.amber.core.agent.runtime.AgentRunId? = null,
        expectedRestoreEpoch: Long? = restoreWriteGate?.currentEpoch(),
        firstRunLaunch: CompletableDeferred<AgentRunHandle>? = null,
    ): Job? {
        val runner = agentRunner ?: return null
        val session = getOrCreateSession(conversationId)
        if (session.isGenerating) {
            // Same guard as the legacy loop: a running turn absorbs new
            // messages into the queue; external resume requests during an
            // active turn are dropped (the active turn owns the conversation).
            firstMessage?.let { message ->
                if (!pendingMessageQueue.enqueuePendingUserMessage(conversationId, message)) {
                    errorStore.addError(
                        IllegalStateException(context.getString(R.string.chat_page_queue_full_error)),
                        conversationId = conversationId,
                        title = context.getString(R.string.chat_page_queue_message_not_added)
                    )
                } else {
                    pendingMessageQueue.persistCurrentPendingMessagesDurably(conversationId)
                    recordPendingMessageEvent(
                        conversationId = conversationId,
                        event = "enqueue",
                        messageId = message.id,
                        detail = message.mode.name.lowercase(),
                    )
                }
            }
            return null
        }
        // Capture before launching: this is the preceding dispatch, never the
        // new job itself. Cancellation may already be running its cleanup.
        val previousJob = session.getJob()
        val job = appScope.launch(restoreWriteContext(expectedRestoreEpoch)) {
            previousJob?.join()
            runKernelDispatchLoop(
                conversationId = conversationId,
                session = session,
                runner = runner,
                firstMessage = firstMessage,
                resumeFirst = resumeWithoutNewMessage,
                messageRange = messageRange,
                expectedPausedRunId = expectedPausedRunId,
                firstRunLaunch = firstRunLaunch,
            )
        }
        session.setJob(job)
        return job
    }

    /**
     * The kernel dispatch loop: drains the pending queue one turn at a time
     * (mirroring the retired legacy loop's structure), each turn executed by
     * the AgentRunner. [resumeFirst] starts the drain with a turn over the
     * current conversation (tool approval / regenerate / outcome retry /
     * edit-regenerate) instead of a newly appended user message;
     * [messageRange] restricts that resume turn to a conversation window
     * (variant regenerate).
     *
     * Suspending and job-agnostic: safe to run inline on an existing session
     * job or under the [launchViaKernel] wrapper.
     */
    private suspend fun runKernelDispatchLoop(
        conversationId: Uuid,
        session: ConversationSession,
        runner: app.amber.core.agent.runtime.AgentRunner,
        firstMessage: PendingUserMessage? = null,
        resumeFirst: Boolean = false,
        messageRange: ClosedRange<Int>? = null,
        expectedPausedRunId: app.amber.core.agent.runtime.AgentRunId? = null,
        firstRunLaunch: CompletableDeferred<AgentRunHandle>? = null,
    ) {
        var pendingResume = resumeFirst
        var pendingRange = messageRange
        var nextMessage = if (resumeFirst) {
            null
        } else {
            firstMessage ?: pendingMessageQueue.dequeueNextPendingUserMessageDurably(conversationId)
        }
        var pendingTaskLaunch = firstRunLaunch
        while (pendingResume || nextMessage != null) {
            val taskLaunch = pendingTaskLaunch
            pendingTaskLaunch = null
            try {
                if (pendingResume) {
                    pendingResume = false
                    val range = pendingRange
                    pendingRange = null
                    val lastNode = conversationStore.getConversationFlow(conversationId).value
                        .messageNodes.lastOrNull()
                    if (lastNode != null) {
                        dispatchKernelTurn(
                            conversationId = conversationId,
                            session = session,
                            runner = runner,
                            messageNodeId = lastNode.id,
                            userMessageText = "",
                            messageRange = range,
                            expectedPausedRunId = expectedPausedRunId,
                            taskLaunch = taskLaunch,
                        )
                    }
                    _generationDoneFlow.emit(conversationId)
                    // Settings-driven recovery owns only the paused run. It
                    // must never use its successful completion as a reason to
                    // drain queued user messages into a fresh run.
                    if (expectedPausedRunId != null) return
                } else {
                    val dispatchMessage =
                        pendingMessageQueue.preparePendingMessageForDispatch(
                            conversationId, nextMessage!!, PendingUserMessageDisplayCopy.from(context),
                        )
                    recordPendingMessageEvent(
                        conversationId = conversationId,
                        event = "dequeue",
                        messageId = dispatchMessage.id,
                        detail = dispatchMessage.mode.name.lowercase(),
                    )
                    if (resolveIdleToolBlockerBeforeDispatch(conversationId, dispatchMessage)) {
                        _generationDoneFlow.emit(conversationId)
                        val conversation = conversationStore.getConversationFlow(conversationId).value
                        if (conversation.hasPendingOrUnexecutedTools()) {
                            break
                        }
                        nextMessage =
                            pendingMessageQueue.dequeueNextPendingUserMessageDurably(conversationId)
                        continue
                    }
                    val userNode = appendUserMessage(conversationId, dispatchMessage)
                    if (dispatchMessage.answer) {
                        dispatchKernelTurn(
                            conversationId = conversationId,
                            session = session,
                            runner = runner,
                            messageNodeId = userNode.id,
                            userMessageText = dispatchMessage.previewText(
                                maxChars = 4000,
                                copy = PendingUserMessageDisplayCopy.from(context),
                            ),
                            taskLaunch = taskLaunch,
                        )
                        _generationDoneFlow.emit(conversationId)
                    }
                    // 仅追加、未触发生成：不能 emit generationDoneFlow。
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                taskLaunch?.completeExceptionally(e)
                e.printStackTrace()
                errorStore.addError(
                    e,
                    conversationId,
                    title = context.getString(R.string.error_title_send_message),
                )
            } finally {
                taskLaunch?.completeExceptionally(IllegalStateException("Chat generation did not start"))
            }

            val conversation = conversationStore.getConversationFlow(conversationId).value
            if (conversation.hasPendingOrUnexecutedTools()) {
                break
            }
            nextMessage = pendingMessageQueue.dequeueNextPendingUserMessageDurably(conversationId)
        }
    }

    /**
     * One kernel-dispatched generation turn: pre-flight (sanitize, reset
     * suggestions, tool-availability warning), runner launch, then wait for
     * the run's terminal state. Resume turns pass the persisted paused runId
     * so the ledger / terminal store / event log continue the same run.
     */
    private suspend fun dispatchKernelTurn(
        conversationId: Uuid,
        session: ConversationSession,
        runner: app.amber.core.agent.runtime.AgentRunner,
        messageNodeId: Uuid,
        userMessageText: String,
        messageRange: ClosedRange<Int>? = null,
        expectedPausedRunId: app.amber.core.agent.runtime.AgentRunId? = null,
        taskLaunch: CompletableDeferred<AgentRunHandle>? = null,
    ) {
        try {
            coldStartRecoveryGate?.awaitReady()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorStore.addError(error, conversationId, title = "Runtime recovery failed")
            return
        }
        val settings = settingsStore.settingsFlow.first()
        // Legacy parity: a turn with no chat model configured ends silently.
        val model = settings.getCurrentChatModel() ?: return

        val input = app.amber.feature.chat.api.ChatTurnInput(
            conversationId = app.amber.core.agent.runtime.ConversationId(conversationId.toString()),
            messageNodeId = app.amber.core.agent.runtime.MessageNodeId(messageNodeId.toString()),
            assistantId = app.amber.core.agent.runtime.AssistantId("default"),
            userMessageText = userMessageText,
            messageRangeStart = messageRange?.start,
            messageRangeEndExclusive = messageRange?.let { it.endInclusive + 1 },
        )
        // P1-03 parity with the legacy loop: a paused run (approval /
        // resumable) is resumed under the SAME runId so the ledger,
        // terminal store and event log all continue the same run. Automatic
        // approval recovery is stricter: it can only claim this conversation's
        // still-live WAITING_USER row and never falls back to a new run.
        val durable = useDurableRuntime()
        val activeTerminal = if (durable) {
            runTerminalStore?.activeForConversation(conversationId.toString())
        } else {
            null
        }
        if (
            expectedPausedRunId != null &&
            !isExpectedWaitingUserRun(activeTerminal, conversationId, expectedPausedRunId)
        ) {
            return
        }
        val resumeRunId = activeTerminal
            ?.let { app.amber.core.agent.runtime.AgentRunId(it.runId) }
        val resumeCursor = resumeRunId?.let { responsesResumeStore?.load(it.value) }
        if (resumeCursor != null && resumeCursor.providerId != model.findProvider(settings.providers)?.id?.toString()) {
            errorStore.addError(
                IllegalStateException(context.getString(R.string.chat_error_switch_provider_to_resume)),
                conversationId,
                title = context.getString(R.string.error_title_regenerate_message),
            )
            return
        }
        var activeRunId: app.amber.core.agent.runtime.AgentRunId? = null
        var launchedRunId: app.amber.core.agent.runtime.AgentRunId? = null
        if (expectedPausedRunId != null) {
            // Register the known run before the asynchronous AgentRunner gate.
            // Stop then reaches AgentRunner.cancel(), whose pending-cancel
            // handoff covers the gap before its job is registered.
            activeKernelRuns.update { current ->
                if (current[conversationId] == null) {
                    current + (conversationId to expectedPausedRunId)
                } else {
                    current
                }
            }
            if (activeKernelRuns.value[conversationId] != expectedPausedRunId) return
            activeRunId = expectedPausedRunId
        }
        var pauseRecorded = false
        try {
            // Stop can win while policy/model preflight is running. Recheck
            // the durable owner immediately before launch; fixed requested
            // runId is retained below so a later Stop can never mint fresh work.
            if (
                expectedPausedRunId != null &&
                !isExpectedWaitingUserRun(
                    runTerminalStore?.activeForConversation(conversationId.toString()),
                    conversationId,
                    expectedPausedRunId,
                )
            ) {
                return
            }
            val perfEnabled = BuildConfig.DEBUG && runCatching { Log.isLoggable("AmberChatPerf", Log.DEBUG) }.getOrDefault(false)
            val prepareStartedAt = if (perfEnabled) System.nanoTime() else 0L
            prepareKernelGenerationTurn(conversationId, settings, model)
            if (perfEnabled) {
                Log.d("AmberChatPerf", "prepareKernelGenerationTurn elapsedMs=${(System.nanoTime() - prepareStartedAt) / 1_000_000.0}")
            }
            if (
                expectedPausedRunId != null &&
                !isExpectedWaitingUserRun(
                    runTerminalStore?.activeForConversation(conversationId.toString()),
                    conversationId,
                    expectedPausedRunId,
                )
            ) {
                return
            }
            // Validate the caller's epoch and capture the runner's launch epoch
            // in one short gate section; a restore cannot slip between them.
            val preLaunchSnapshot = resumeRunId?.let { runner.observe(it).value }
            val handle = withConversationWrite {
                runner.launch(
                    app.amber.feature.chat.api.ChatTurnDescriptor.ID,
                    input,
                    requestedRunId = resumeRunId,
                )
            }.getOrElse { e ->
                taskLaunch?.completeExceptionally(e)
                errorStore.addError(e, conversationId, title = "Kernel dispatch failed")
                return
            }
            launchedRunId = handle.runId
            if (expectedPausedRunId != null && handle.runId != expectedPausedRunId) {
                runner.cancel(handle.runId)
                return
            }
            if (activeRunId == null) {
                activeKernelRuns.update { it + (conversationId to handle.runId) }
                activeRunId = handle.runId
                // 新 run 开始即清本会话旧终态，过渡 tick 才不会把上一轮成败错标到本轮。
                _lastRunOutcomes.update { it - conversationId }
                // A resume dispatch takes the conversation back from the
                // user-paused set in the same breath it re-registers as active.
                pausedForUserConversations.update { it - conversationId }
            }
            taskLaunch?.complete(handle)
            val snapshots = runner.observe(handle.runId)
            val endedSnapshot = awaitChatRunDispatchEnd(snapshots, preLaunchSnapshot)
            if (endedSnapshot.status.isPause) {
                pauseRecorded = true
                pausedForUserConversations.update { it + conversationId }
            } else {
                _lastRunOutcomes.update { it + (conversationId to endedSnapshot.status) }
            }
        } catch (cancelled: CancellationException) {
            activeRunId?.let(runner::cancel)
            _lastRunOutcomes.update { it + (conversationId to app.amber.core.agent.runtime.RunStatus.CANCELLED) }
            throw cancelled
        } finally {
            // Terminal snapshots precede the independent handler's durable cleanup.
            // Keep dispatch ownership through that cleanup before draining the next input.
            launchedRunId?.let { runId ->
                withContext(NonCancellable) { runner.awaitRunSettled(runId) }
            }
            // 本 dispatch 未以 pause 退出 = 会话已不在等待用户态：无论从终态、
            // 取消还是任何 bail 路径离开，都回收 paused 条目，避免僵尸气泡。
            if (!pauseRecorded) {
                pausedForUserConversations.update { it - conversationId }
            }
            activeRunId?.let { runId ->
                clearChatRunOnDispatchCompletion(
                    session, activeKernelRuns, runId, session.getJob() ?: coroutineContext[Job]!!,
                )
            }
        }
    }

    /** Reset suggestions and warn about unavailable tools before runner launch. */
    private suspend fun prepareKernelGenerationTurn(
        conversationId: Uuid,
        settings: Settings,
        model: app.amber.ai.provider.Model,
    ) {
        // reset suggestions
        conversationStore.updateConversation(
            conversationId,
            conversationStore.getConversationFlow(conversationId).value.copy(chatSuggestions = emptyList()),
            checkDeletedFiles = false,
        )
        // memory tool
        if (!model.abilities.contains(ModelAbility.TOOL)) {
            if (settings.enableWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                errorStore.addError(
                    IllegalStateException(context.getString(R.string.tools_warning)),
                    conversationId,
                    title = context.getString(R.string.error_title_tool_unavailable)
                )
            }
        }
    }

    /**
     * Continue/resume generation on the current conversation state —
     * outcome retry/abandon, edit-and-regenerate, notification approval.
     * External entry (launches a new session job); the kernel dispatcher
     * owns the queued-message drain after the turn.
     *
     * Callers already running inside the session job MUST use
     * [continueGenerationInline] — the launcher's isGenerating guard would
     * swallow the request (the caller's own job is the active one).
     */
    private suspend fun continueGeneration(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null,
        expectedPausedRunId: app.amber.core.agent.runtime.AgentRunId? = null,
    ) {
        launchViaKernel(
            conversationId,
            resumeWithoutNewMessage = true,
            messageRange = messageRange,
            expectedPausedRunId = expectedPausedRunId,
            expectedRestoreEpoch = captureRestoreEpoch(),
        )
    }

    /**
     * Resume generation from within an existing session-job context (in-app
     * tool approval, regenerate, in-loop blocker resume): runs the resume
     * turn plus the queued-message drain inline on the caller's job —
     * the retired legacy loop's inline handleMessageComplete parity.
     */
    private suspend fun continueGenerationInline(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null,
    ) {
        val runner = agentRunner ?: return
        runKernelDispatchLoop(
            conversationId = conversationId,
            session = getOrCreateSession(conversationId),
            runner = runner,
            resumeFirst = true,
            messageRange = messageRange,
        )
    }

    /**
     * Steer drain shared by the legacy loop and the kernel-dispatched turn:
     * dequeue queued STEER messages, persist the shrinkage and record the
     * consumption event.
     */
    internal suspend fun consumeSteerMessagesForRun(conversationId: Uuid): List<UIMessage> {
        getOrCreateSession(conversationId)
        return pendingMessageQueue.consumeSteerMessagesForRun(conversationId)
    }

    private suspend fun resolveIdleToolBlockerBeforeDispatch(
        conversationId: Uuid,
        message: PendingUserMessage,
    ): Boolean {
        val decision = approvalActions.resolveIdleToolBlockerBeforeDispatch(conversationId, message)
        if (!decision.changed) return false
        recordPendingMessageEvent(
            conversationId = conversationId,
            event = if (decision.shouldResume) "pending_tool_resume" else "pending_tool_cancel",
            messageId = message.id,
            count = decision.blockingToolNames.size,
            detail = decision.blockingToolNames.joinToString(separator = ","),
        )
        if (decision.shouldResume) continueGenerationInline(conversationId)
        return decision.shouldResume
    }

    private suspend fun appendUserMessage(
        conversationId: Uuid,
        message: PendingUserMessage,
    ): MessageNode {
        val session = getOrCreateSession(conversationId)
        if (!session.timelineLoadState.value.initialized) {
            initializeConversation(conversationId)
        }
        val userNode = UIMessage(
            role = MessageRole.USER,
            parts = message.parts,
        ).toMessageNode()
        ChatSendTransitionTracker.markSentUserMessage(
            conversationId = conversationId.toString(),
            messageId = userNode.currentMessage.id.toString(),
        )
        conversationStore.appendUserNode(conversationId, userNode)
        return userNode
    }

    // ---- 重新生成消息 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>,
        regenerate: Boolean = false,
    ) {
        withCapturedRestoreWriteContext {
            val edited = conversationEditor.editMessage(conversationId, messageId, parts)
            if (edited && regenerate) continueGeneration(conversationId)
        }
    }

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        val session = getOrCreateSession(conversationId)
        val oldJob = session.getJob()
        oldJob?.cancel()

        session.setJob(appScope.launch(restoreWriteContext()) {
            // Wait for the cancelled generation's onCompletion to finish writing,
            // so it doesn't race with our state mutations below.
            oldJob?.let { runCatching { it.join() } }
            try {
                val conversation = ensureFullConversationLoaded(conversationId)

                // Regenerate replaces an interrupted response; it must not
                // reuse that response's runId or overwrite its resume cursor.
                if (message.role == MessageRole.USER || regenerateAssistantMsg) {
                    val previousRun = runTerminalStore?.activeForConversation(conversationId.toString())
                        ?.takeIf { it.state in RESPONSES_RESUME_STATES }
                    if (previousRun != null) {
                        check(checkNotNull(storedResponseStopCancel).cancelForRegeneration(
                            previousRun.runId, checkNotNull(runTerminalStore),
                        )) { context.getString(R.string.chat_error_cancel_not_confirmed) }
                        runRecovery?.reconcileStartedEffects(previousRun.runId)
                        approvalActions.refreshOutcomeUnknown()
                    }
                }

                when {
                    message.role == MessageRole.USER -> {
                        // 如果是用户消息，则截止到当前消息（按 id 查找，避免值相等在并发写后 miss）
                        val node = conversation.getMessageNodeByMessageId(message.id)
                        if (node == null) {
                            errorStore.addError(
                                IllegalStateException("Message node not found for regenerate: ${message.id}"),
                                conversationId,
                                title = context.getString(R.string.error_title_regenerate_message),
                            )
                            return@launch
                        }
                        val nodeIndex = conversation.messageNodes.indexOf(node) + 1
                        contextEngine.invalidateCompacts(conversationId, "message_regenerated")
                        conversationStore.saveConversation(
                            conversationId,
                            conversation.copy(messageNodes = conversation.messageNodes.take(nodeIndex)),
                        )
                        continueGenerationInline(conversationId)
                    }

                    regenerateAssistantMsg -> {
                        val nodeIndex = conversation.messageNodes.indexOfFirst { node ->
                            node.messages.any { it.id == message.id }
                        }
                        if (nodeIndex < 0) {
                            errorStore.addError(
                                IllegalStateException("Message node not found for regenerate: ${message.id}"),
                                conversationId,
                                title = context.getString(R.string.error_title_regenerate_message),
                            )
                            return@launch
                        }
                        contextEngine.invalidateCompacts(conversationId, "message_regenerated")
                        // The answer being regenerated is a branch point: any
                        // later messages were generated from the old answer
                        // and must not remain in the active context while the
                        // replacement is streamed. Keep the target node so
                        // the new answer can become its selected variant.
                        conversationStore.saveConversation(
                            conversationId,
                            conversation.copy(
                                messageNodes = conversation.messageNodes.take(nodeIndex + 1),
                            ),
                        )
                        continueGenerationInline(conversationId, messageRange = 0..<nodeIndex)
                    }

                    else -> conversationStore.saveConversation(conversationId, conversation)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                errorStore.addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        })
    }

    /**
     * 失败卡片上的“重试”：没有进行中的生成时，按应用内“重新生成”的同一路径
     * 重新生成最后一条消息。已有生成在跑（例如用户已在应用里重试）则什么都不做。
     */
    suspend fun retryFromNotification(conversationId: Uuid, runId: String?) {
        if (conversationStore.getGenerationJobStateFlow(conversationId).value?.isActive == true) {
            Log.i(TAG, "retryFromNotification: generation already active for $conversationId (run=$runId)")
            return
        }
        val lastMessage = ensureFullConversationLoaded(conversationId).currentMessages.lastOrNull() ?: return
        runPresenter.cancelLiveStatus(conversationId)
        regenerateAtMessage(conversationId, lastMessage)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        val writeContext = restoreWriteContext()
        appScope.launch(writeContext) {
            session.dispatchToolApproval(
                scope = appScope,
                context = writeContext,
                onError = { error ->
                    errorStore.addError(error, conversationId, title = context.getString(R.string.error_title_tool_approval))
                },
            ) { decisionPersisted ->
                applyToolApprovalDecision(
                    conversationId, toolCallId, approved, reason, answer,
                    onDecisionPersisted = decisionPersisted,
                )
            }
        }
    }

    /**
     * P8-10/P8-11 — notification approve/deny/ask_user-reply with one-time
     * token validation (parity plan §P8-10/§P8-11 L1). The token was issued by
     * the notification builder bound to runId + conversationId + toolCallId +
     * args digest; consuming it is single-use, so replaying the same action
     * (double tap, approve then deny, stale notification of an ended run) is
     * rejected and nothing is executed. Returns true only when the decision
     * was applied to the tool call the user actually saw.
     */
    suspend fun handleNotificationApproval(
        conversationId: Uuid,
        runId: String?,
        toolCallId: String,
        approved: Boolean,
        reason: String,
        answer: String?,
        token: String,
    ): Boolean = withCapturedRestoreWriteContext restore@{
        val registry = notificationApprovalTokens ?: return@restore false
        val binding = registry.consume(token) ?: return@restore false
        val conversation = ensureFullConversationLoaded(conversationId)
        val toolPart = conversation.messageNodes
            .asSequence()
            .flatMap { node -> node.messages.asSequence() }
            .flatMap { message -> message.parts.asSequence() }
            .filterIsInstance<UIMessagePart.Tool>()
            .firstOrNull { it.toolCallId == toolCallId }
        if (!NotificationApprovalCheck.isValid(
                binding = binding,
                intentRunId = runId,
                conversationId = conversationId.toString(),
                toolCallId = toolCallId,
                currentArgsDigest = toolPart?.let { argsDigest(it.input) },
                toolStillPending = toolPart?.isPending == true,
            )
        ) {
            Log.w(
                TAG,
                "handleNotificationApproval: rejected token conversation=$conversationId run=$runId toolCall=$toolCallId"
            )
            return@restore false
        }
        if (approved && toolPart != null && app.amber.feature.runtime.AgentLiveStatusNotifier.requiresInAppApprovalOnly(toolPart)) {
            Log.w(
                TAG,
                "handleNotificationApproval: rejected high-risk/mandatory approval from notification " +
                    "conversation=$conversationId run=$runId tool=${toolPart.toolName} toolCall=$toolCallId"
            )
            return@restore false
        }
        val session = getOrCreateSession(conversationId)
        session.dispatchToolApproval(
            scope = appScope,
            context = restoreWriteContext(captureRestoreEpoch()),
            onError = { error ->
                errorStore.addError(error, conversationId, title = context.getString(R.string.error_title_tool_approval))
            },
        ) { decisionPersisted ->
            applyToolApprovalDecision(
                conversationId, toolCallId, approved, reason, answer,
                onDecisionPersisted = decisionPersisted,
            )
        }
    }

    /**
     * Shared core of the in-app and notification approval paths: mark the
     * tool call's approval state, record the capability approval audit bound
     * to the args digest, and resume generation when nothing is left pending.
     */
    private suspend fun applyToolApprovalDecision(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String,
        answer: String?,
        onDecisionPersisted: () -> Unit = {},
    ) {
        val result = approvalActions.applyToolApprovalDecision(
            conversationId, toolCallId, approved, reason, answer,
        )
        if (!result.applied) return
        onDecisionPersisted()
        if (result.shouldResume) continueGenerationInline(conversationId)
        _generationDoneFlow.emit(conversationId)
    }

    /**
     * Re-evaluate one visible conversation after its high-risk approval setting
     * changes. This is deliberately not a background sweep: it only resumes a
     * persisted WAITING_USER run under the exact same run id.
     */
    suspend fun resumePendingToolsWithCurrentApprovalSettings(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.join()
        // Respect Stop: changing a setting must not revive a cancelled run.
        if (previousJob?.isCancelled == true) return
        val expectedRunId = approvalActions.pendingRunForCurrentApprovalSettings(
            conversationId,
            settingsStore.settingsFlow.value.agentRuntime.autoApproveHighRiskToolCalls,
        ) ?: return
        continueGeneration(
            conversationId = conversationId,
            expectedPausedRunId = expectedRunId,
        )
    }

    fun approvePendingAutoApprovableTools(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        if (session.isGenerating) return

        val job = appScope.launch(restoreWriteContext()) {
            try {
                val decision = approvalActions.approvePendingAutoApprovableTools(conversationId) ?: return@launch
                if (decision.shouldResume) continueGenerationInline(conversationId)

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                errorStore.addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job)
    }

    suspend fun reconcileOutcomeUnknown(conversationId: Uuid, effectId: String, retry: Boolean) {
        withCapturedRestoreWriteContext {
            if (approvalActions.reconcileOutcomeUnknown(conversationId, effectId, retry)) {
                continueGeneration(conversationId)
            }
        }
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        val cancellation = UIMessagePart.Text(
            """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
        )
        return tool.copy(
            output = listOf(cancellation),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user"),
        )
    }

    internal suspend fun stopRunForDeletion(conversationId: Uuid) {
        val kernelRun = activeKernelRuns.value[conversationId]
        val persistedRun = runTerminalStore?.activeForConversation(conversationId.toString())
        stopGeneration(conversationId, kernelRun?.value ?: persistedRun?.runId)
        if (kernelRun != null) {
            agentRunner?.observe(kernelRun)?.first { it.status.isTerminal || it.status.isPause }
        }
        conversationStore.sessionOrNull(conversationId)?.getJob()?.let { job ->
            job.cancel()
            runCatching { job.join() }
        }
        val remaining = persistedRun?.let { runTerminalStore?.get(it.runId) }
            ?.takeIf { !it.state.isTerminal && it.conversationId == conversationId.toString() } ?: return
        if (responsesResumeStore?.load(remaining.runId) != null) {
            // An unconfirmed remote cancel still needs recovery to settle its
            // real outcome, even though the local conversation is being deleted.
            runTerminalStore?.pause(remaining.runId, RunTerminalState.WAITING_EXTERNAL, PauseReason.USER_STOP)
            agentEventStore?.transitionRun(
                app.amber.core.agent.runtime.AgentRunId(remaining.runId),
                app.amber.core.agent.runtime.RunStatus.PAUSE_STATES,
                app.amber.core.agent.runtime.RunStatus.RUNNING,
                reason = "conversation_deleted_server_cancel_pending",
            )
            agentEventStore?.transitionRun(
                app.amber.core.agent.runtime.AgentRunId(remaining.runId),
                app.amber.core.agent.runtime.RunStatus.LIVE_STATES,
                app.amber.core.agent.runtime.RunStatus.WAITING_EXTERNAL,
                reason = "conversation_deleted_server_cancel_pending",
            )
        } else {
            runRecovery?.reconcileStartedEffects(remaining.runId)
            runTerminalStore?.finish(remaining.runId, RunTerminalState.CANCELLED, PauseReason.USER_STOP)
            agentEventStore?.transitionRun(
                app.amber.core.agent.runtime.AgentRunId(remaining.runId),
                app.amber.core.agent.runtime.RunStatus.LIVE_STATES,
                app.amber.core.agent.runtime.RunStatus.CANCELLED,
                reason = "conversation_deleted",
            )
        }
    }

    /** No handler remains to turn this Stop's unresolved remote outcome into a durable pause. */
    private suspend fun parkUnconfirmedStoppedResponse(conversationId: Uuid, runId: String): Boolean {
        val terminalStore = runTerminalStore ?: return false
        val terminal = terminalStore.get(runId)?.takeIf {
            it.conversationId == conversationId.toString() &&
                (it.state == RunTerminalState.WAITING_USER || it.state in RESPONSES_RESUME_STATES) &&
                it.finishedAtMs == null
        } ?: return false
        if (activeKernelRuns.value[conversationId] != null ||
            conversationStore.sessionOrNull(conversationId)?.getJob()?.isCompleted == false
        ) return false
        val id = app.amber.core.agent.runtime.AgentRunId(runId)
        // Compete with Runner's pause-only activation claim before changing
        // the terminal row. A resumed activation owns RUNNING when this loses.
        val claim = agentEventStore?.transitionRun(
            id, app.amber.core.agent.runtime.RunStatus.PAUSE_STATES,
            app.amber.core.agent.runtime.RunStatus.RUNNING,
            reason = "user_stop_server_cancel_pending",
        )
        if (agentEventStore != null && claim !is app.amber.core.agent.runtime.RunTransitionResult.Applied) return false
        terminalStore.pause(terminal.runId, RunTerminalState.WAITING_EXTERNAL, PauseReason.USER_STOP)
        if (terminalStore.get(runId)?.state != RunTerminalState.WAITING_EXTERNAL) return false
        val transition = agentEventStore?.transitionRun(
            id, setOf(app.amber.core.agent.runtime.RunStatus.RUNNING),
            app.amber.core.agent.runtime.RunStatus.WAITING_EXTERNAL,
            reason = "user_stop_server_cancel_pending",
        )
        check(agentEventStore == null ||
            transition is app.amber.core.agent.runtime.RunTransitionResult.Applied ||
            (transition is app.amber.core.agent.runtime.RunTransitionResult.Rejected &&
                transition.current == app.amber.core.agent.runtime.RunStatus.WAITING_EXTERNAL)
        ) { "Stopped response pause was not committed to the run event owner for $runId" }
        storedResponseStopCancel?.clearStopUnconfirmed(runId)
        // Match a handler that parks at WAITING_EXTERNAL: the remote run is
        // unresolved, so app-external surfaces must not announce completion.
        pausedForUserConversations.update { it + conversationId }
        _lastRunOutcomes.update { it - conversationId }
        runCatching { runRecovery?.reconcileStartedEffects(runId) }
        runCatching { approvalActions.refreshOutcomeUnknown() }
        return true
    }

    suspend fun stopGeneration(conversationId: Uuid, runId: String? = null) {
        val activeRun = activeKernelRuns.value[conversationId]
        if (runId != null && activeRun != null && activeRun.value != runId) return
        val sessionJob = conversationStore.sessionOrNull(conversationId)?.getJob()
        // A WAITING_USER pause intentionally releases both the session job
        // and the active-kernel map. For a UI Stop only, recover that one
        // same-conversation durable owner; notification Stops retain their
        // explicit runId ownership contract.
        val pausedFallbackRunId = if (runId == null && activeRun == null && sessionJob?.isActive != true) {
            runTerminalStore?.activeForConversation(conversationId.toString())
                ?.takeIf {
                    it.conversationId == conversationId.toString() &&
                        it.state == RunTerminalState.WAITING_USER &&
                        it.finishedAtMs == null
                }
                ?.runId
        } else {
            null
        }
        val targetRunId = runId ?: activeRun?.value ?: pausedFallbackRunId
        // P6-01: when the run has a stored server-side response, cancel it
        // server-side FIRST and await a decidable outcome — before cancelling
        // the local job, so onCompletion sees the decision. A cancel that
        // cannot be confirmed keeps the run in WAITING_EXTERNAL (never
        // pretend cancelled); recovery settles it later.
        var serverCancelUnconfirmed = false
        val stopCancel = storedResponseStopCancel
        val durableStopEnabled = targetRunId != null && durableRuntimeForStop()
        if (durableStopEnabled) {
            val activeRun = runTerminalStore?.activeForConversation(conversationId.toString())
            val requestedRunId = targetRunId!!
            val terminalOwnsRun = activeRun != null &&
                !activeRun.state.isTerminal &&
                activeRun.conversationId == conversationId.toString() &&
                activeRun.runId == requestedRunId
            val terminalOwnsAnotherRun = activeRun != null &&
                activeRun.runId != requestedRunId
            val kernelOwnsRun = activeKernelRuns.value[conversationId]?.value == requestedRunId
            if (terminalOwnsAnotherRun || (!terminalOwnsRun && !kernelOwnsRun)) {
                Log.w(
                    TAG,
                    "stopGeneration: refusing server cancel for unowned run " +
                        "conversation=$conversationId runId=$targetRunId",
                )
                return
            }
        }
        if (
            durableStopEnabled &&
            storedResponseToggleOnForRun(targetRunId!!) &&
            stopCancel != null
        ) {
            runCatching { stopCancel.cancelStored(targetRunId!!) }
                .onSuccess { decidable ->
                    if (!decidable) serverCancelUnconfirmed = true
                }
                .onFailure { error ->
                    Log.w(TAG, "stopGeneration: server cancel failed for run $targetRunId", error)
                    serverCancelUnconfirmed = true
                }
        }
        // Capability/provider resolution and server cancellation suspend. A newer
        // dispatch must not inherit an older Stop's cancellation or UI cleanup.
        val currentRun = activeKernelRuns.value[conversationId]
        if ((currentRun != null && currentRun.value != targetRunId) ||
            conversationStore.sessionOrNull(conversationId)?.getJob() !== sessionJob
        ) return
        val withoutHandler = currentRun == null && sessionJob?.isCompleted != false
        val parkedUnconfirmedPause = serverCancelUnconfirmed && withoutHandler &&
            parkUnconfirmedStoppedResponse(conversationId, targetRunId!!)
        // The claim itself suspends. A same-run resume can replace the
        // session job even though the runId stays unchanged.
        val afterParkRun = activeKernelRuns.value[conversationId]
        if ((afterParkRun != null && afterParkRun.value != targetRunId) ||
            conversationStore.sessionOrNull(conversationId)?.getJob() !== sessionJob
        ) return
        if (serverCancelUnconfirmed && withoutHandler && !parkedUnconfirmedPause) return
        if (serverCancelUnconfirmed && !parkedUnconfirmedPause) {
            storedResponseStopCancel?.markStopUnconfirmed(targetRunId!!)
        }
        val activeKernelRun = activeKernelRuns.value[conversationId]
        val cancelledByOwner = targetRunId != null &&
            runOwnershipRegistry?.cancel(
                runId = targetRunId,
                conversationId = conversationId.toString(),
            ) == true
        // UI Stop remains conversation-scoped even when it resolved the
        // persisted pause above; do not lose the session-job cancellation.
        val cancelledBySession = if (sessionJob != null && (runId == null || activeRun?.value == runId)) {
            sessionJob.cancel()
            true
        } else {
            false
        }
        // Kernel-dispatched runs are owned by the AgentRunner, not the
        // session job or the ownership registry — cancel through the runner;
        // its CancellationException path settles the durable records.
        val kernelRunId = if (parkedUnconfirmedPause) null else activeKernelRun
            ?.takeIf { targetRunId == null || it.value == targetRunId }
            ?: targetRunId
                ?.takeIf { candidate ->
                    runTerminalStore?.activeForConversation(conversationId.toString())?.let { terminal ->
                        terminal.runId == candidate &&
                            terminal.conversationId == conversationId.toString() &&
                            !terminal.state.isTerminal
                    } == true
                }
                ?.let { app.amber.core.agent.runtime.AgentRunId(it) }
        val cancelledKernelRun = kernelRunId?.let { candidate ->
            // AgentRunner preserves a cancel intent if its launch gate has
            // not registered the job yet, which closes Stop vs auto-resume.
            agentRunner?.cancel(candidate)
            true
        } ?: false
        // WAITING_USER has no active generation Job by design: onCompletion
        // releases the in-memory owner while the persisted terminal keeps the
        // approval resumable. Notification Stop therefore falls back to the
        // same run-scoped durable owner, with conversation + state checked
        // atomically so a stale or cross-conversation runId cannot stop work.
        val cancelledPersistedWaitingRun = targetRunId != null &&
            !serverCancelUnconfirmed && !cancelledByOwner &&
            runTerminalStore?.cancelWaitingUser(
                runId = targetRunId,
                conversationId = conversationId.toString(),
            ) == true
        val cancelled = parkedUnconfirmedPause || cancelledByOwner || cancelledBySession || cancelledPersistedWaitingRun || cancelledKernelRun
        if (cancelledBySession) {
            withContext(NonCancellable) { sessionJob!!.join() }
        }
        if (!cancelled) {
            if (serverCancelUnconfirmed) {
                // Nothing local to cancel — the flag has no consumer; drop it.
                targetRunId?.let { storedResponseStopCancel?.clearStopUnconfirmed(it) }
            }
            Log.i(TAG, "stopGeneration: nothing to cancel conversation=$conversationId runId=$targetRunId")
            return
        }
        if (cancelledPersistedWaitingRun) {
            // There is no flow completion left to stop the foreground
            // keep-alive after a persisted WAITING_USER pause is cancelled.
            pausedForUserConversations.update { it - conversationId }
            // 暂停态停止没有 dispatch 终态可等：直接写终态，任务气泡据此立即收起而非误报完成。
            _lastRunOutcomes.update { it + (conversationId to app.amber.core.agent.runtime.RunStatus.CANCELLED) }
            runPresenter.stopGenerationKeepAlive(conversationId)
            // No flow completion will settle the event-store row either —
            // move it to CANCELLED here or it stays a live WAITING_USER row
            // forever (replayUnfinished deliberately skips pause states).
            runCatching {
                agentEventStore?.transitionRun(
                    app.amber.core.agent.runtime.AgentRunId(targetRunId!!),
                    app.amber.core.agent.runtime.RunStatus.PAUSE_STATES,
                    app.amber.core.agent.runtime.RunStatus.CANCELLED,
                    reason = "user_stop",
                )
            }
            // A composite tool can park with its outer effect still STARTED
            // (nested approval checkpoint). Cold-start recovery skips terminal
            // rows, so classify the effect here or a non-idempotent tool's
            // unknown outcome is never surfaced (Step 3-5).
            runCatching { runRecovery?.reconcileStartedEffects(targetRunId!!) }
            runCatching { approvalActions.refreshOutcomeUnknown() }
        }
        val successorRun = activeKernelRuns.value[conversationId]
        val successorJob = conversationStore.sessionOrNull(conversationId)?.getJob()
        if ((successorRun != null && successorRun.value != targetRunId) ||
            (successorJob != null && successorJob !== sessionJob)
        ) return
        runPresenter.cancelLiveStatus(conversationId)
        approvalActions.clearTrust(conversationId)
        screenCaptureManager.releaseSession()

        val currentConversation = conversationStore.getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val cancelledAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val updatedMessage = lastMessage
            .finishPendingTools(::cancelToolByUser)
            .let { message ->
                if (message.role == MessageRole.ASSISTANT) {
                    message.copy(finishedAt = message.finishedAt ?: cancelledAt)
                } else {
                    message
                }
            }
            .finishReasoning()
        if (updatedMessage == lastMessage) return

        val updatedTail = lastNode.copy(
            messages = lastNode.messages.map { message ->
                if (message.id == lastMessage.id) updatedMessage else message
            },
        )
        val nodes = currentConversation.messageNodes.toMutableList()
        nodes[nodes.lastIndex] = updatedTail
        conversationStore.saveConversation(conversationId, currentConversation.copy(messageNodes = nodes))
    }

    /**
     * P1-06: explicitly resume the preserved pending queue after a Stop.
     * Drains queued messages (including any STEER entries, which are sent as
     * ordinary follow-up turns) and starts generation for each.
     */
    fun resumePendingQueue(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        if (session.pendingUserMessages.value.isEmpty()) return
        launchViaKernel(conversationId)
    }

    private suspend fun <T> withConversationWrite(block: suspend () -> T): T {
        val gate = restoreWriteGate
        return if (gate == null) {
            block()
        } else {
            gate.withCurrentWriterOrCancel(block)
        }
    }

}

private fun Conversation.hasPendingOrUnexecutedTools(): Boolean =
    currentMessages.lastOrNull()?.getTools()?.any { !it.isExecuted || it.isPending } == true
