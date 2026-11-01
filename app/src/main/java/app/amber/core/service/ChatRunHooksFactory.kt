package app.amber.core.service

import android.content.Context
import android.util.Log
import app.amber.agent.AppScope
import app.amber.agent.R
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.ResponseResumeStore
import app.amber.ai.provider.providers.openai.supportsResponsesResume
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.finishReasoning
import app.amber.core.ai.tools.LocalTools
import app.amber.core.automation.ScreenCaptureManager
import app.amber.core.memory.extraction.MemoryExtractor
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.model.Conversation
import app.amber.core.recap.ConversationRecapGenerator
import app.amber.core.recap.ConversationRecapStore
import app.amber.core.recap.isRecapEligible
import app.amber.core.settings.Capability
import app.amber.core.settings.CapabilityFlags
import app.amber.core.settings.findProvider
import app.amber.core.settings.getCurrentChatModel
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.feature.runtime.PauseReason
import app.amber.feature.runtime.RunOwnershipRegistry
import app.amber.feature.runtime.RunRecoveryService
import app.amber.feature.runtime.RunTerminalState
import app.amber.feature.runtime.RunTerminalStore
import app.amber.feature.runtime.StoredResponseStopCancel
import app.amber.feature.runtime.ToolEffectLedger
import app.amber.feature.runtime.terminalForFlowEnd
import app.amber.feature.subagent.SubAgentManager
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "ChatRunHooksFactory"

/** Creates one activation's hooks and retains its checkpoint/terminal publication order. */
class ChatRunHooksFactory(
    private val context: Context,
    private val appScope: AppScope,
    private val settingsStore: SettingsAggregator,
    private val conversationStore: ConversationStore,
    private val pendingMessageQueue: PendingMessageQueue,
    private val errorStore: ChatErrorStore,
    private val runPresenter: ChatRunPresenter,
    private val approvalActions: ChatApprovalActions,
    private val localTools: LocalTools,
    private val subAgentManager: SubAgentManager,
    private val screenCaptureManager: ScreenCaptureManager,
    private val aiAuxiliaryGenerator: AiAuxiliaryGenerator,
    private val memoryExtractor: MemoryExtractor,
    private val agentEventStore: app.amber.core.agent.runtime.AgentEventStore? = null,
    private val runTerminalStore: RunTerminalStore? = null,
    private val runRecovery: RunRecoveryService? = null,
    private val runOwnershipRegistry: RunOwnershipRegistry? = null,
    private val toolEffectLedger: ToolEffectLedger? = null,
    private val capabilityFlags: CapabilityFlags? = null,
    private val responsesResumeStore: ResponseResumeStore? = null,
    private val storedResponseStopCancel: StoredResponseStopCancel? = null,
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
    private val recapGenerator: ConversationRecapGenerator? = null,
    private val recapStore: ConversationRecapStore? = null,
    private var localAsyncTaskCoordinator: LocalAsyncTaskCoordinator? = null,
) {
    internal fun bindLocalAsyncTaskCoordinator(coordinator: LocalAsyncTaskCoordinator) {
        localAsyncTaskCoordinator = coordinator
    }

    internal suspend fun create(
        conversationId: Uuid,
        cancelGeneration: suspend (conversationId: Uuid, runId: String?) -> Unit,
        runIdHint: String? = null,
        anchorNodeId: Uuid? = null,
    ): app.amber.feature.chat.impl.ChatRunHooks {
        val generationRestoreEpoch = captureRestoreEpoch()
        val durable = chatDurableRuntimeEnabled(capabilityFlags, toolEffectLedger, runTerminalStore, runRecovery)
        val existingRun = if (durable) {
            runTerminalStore?.activeForConversation(conversationId.toString())
        } else {
            null
        }
        val turnSettings = settingsStore.settingsFlow.first()
        val senderName = turnSettings.getCurrentChatModel()?.displayName
            ?: context.getString(R.string.app_name)
        // This turn's generation-task id, captured between start and finish.
        var generationTaskId: String? = null
        var reportedTerminal: app.amber.core.ai.GenerationTerminal? = null
        var responseRequest: app.amber.ai.provider.ResponsesResumeRequest? = null
        val streamedMessageIds = ConcurrentHashMap.newKeySet<Uuid>()
        val managedTask = localAsyncTaskCoordinator?.findTaskForRun(runIdHint, anchorNodeId)
        return app.amber.feature.chat.impl.ChatRunHooks(
            durable = durable,
            processingStatus = managedTask?.processingStatus
                ?: conversationStore.session(conversationId).processingStatus,
            autoApprovedToolNames = approvalActions.trustedToolNames(conversationId),
            consumeSteerMessages = {
                val coordinator = localAsyncTaskCoordinator
                val task = coordinator?.findTaskForRun(runIdHint, anchorNodeId)
                if (coordinator != null && task != null) {
                    coordinator.consumeFollowupsForRun(runIdHint ?: task.runId ?: task.taskId, anchorNodeId)
                } else {
                    pendingMessageQueue.consumeSteerMessagesForRun(conversationId)
                }
            },
            onRunStarted = { runId ->
                if (durable) {
                    runTerminalStore!!.begin(runId, conversationId.toString(), AMBER_AGENT_ID.toString())
                    // P1-05: the hook runs inside the runner's handler
                    // coroutine — its Job owns the provider transport
                    // collected downstream, so it is the cancellation owner
                    // for (assistantId, conversationId, runId).
                    kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.let { job ->
                        runCatching {
                            runOwnershipRegistry?.register(
                                assistantId = AMBER_AGENT_ID.toString(),
                                conversationId = conversationId.toString(),
                                runId = runId,
                                job = job,
                            )
                        }
                    }
                }
                runPresenter.updateLiveStatus(
                    conversationId = conversationId,
                    messages = conversationStore.getConversationFlow(conversationId).value.currentMessages,
                    senderName = senderName,
                    enabled = turnSettings.agentRuntime.enableLiveStatusNotification,
                    hideSensitive = turnSettings.agentRuntime.hideSensitiveLiveStatus,
                    runId = runId.takeIf { durable },
                )
                runPresenter.startGenerationKeepAlive(
                    conversationId, senderName, turnSettings.agentRuntime.keepGenerationAliveInBackground,
                )
                generationTaskId = runPresenter.startGenerationTask(
                    conversationId = conversationId,
                    runId = runId,
                    senderName = senderName,
                    modelName = senderName,
                    autoRetry = turnSettings.agentRuntime.generationRetry.enabled,
                    maxRetries = turnSettings.agentRuntime.generationRetry.maxRetries,
                    cancel = {
                        cancelGeneration(conversationId, runId)
                        true
                    },
                )
            },
            onTerminal = { _, terminal ->
                // Final content must reach durable storage before either run
                // owner publishes completion, a limit, or an approval pause.
                reportedTerminal = terminal
            },
            onStreamingMessages = { runId, messages ->
                messages.forEach { streamedMessageIds.add(it.id) }
                localAsyncTaskCoordinator?.onStreamingMessages(runId, anchorNodeId, messages)
                runPresenter.updateLiveStatus(
                    conversationId = conversationId,
                    messages = messages,
                    senderName = senderName,
                    enabled = turnSettings.agentRuntime.enableLiveStatusNotification,
                    hideSensitive = turnSettings.agentRuntime.hideSensitiveLiveStatus,
                    runId = runId.takeIf { durable },
                )
                // M1: the executed tool results are durable in the
                // conversation now — the ledger replay payload is no longer
                // read, so drop it (bounded retention).
                if (conversationStore.checkpointConversation(conversationId, conversationStore.getConversationFlow(conversationId).value)) {
                    clearPersistedToolPayloads(durable, messages)
                }
            },
            onRunFinished = { runId, cause ->
                finalizeChatGeneration {
                    runCatching { runOwnershipRegistry?.unregister(runId) }
                    if (restoreWriteGate?.isWriteAllowed(generationRestoreEpoch) == false) {
                        generationTaskId?.let {
                            runPresenter.finishGenerationTask(it, CancellationException("Generation interrupted by restore"), runId)
                        }
                        localTools.endWebMountRun(
                            runId = runId,
                            conversationId = conversationId.toString(),
                            reason = "generation superseded by restore",
                        )
                        return@finalizeChatGeneration
                    }
                    val updatedAt = Instant.now()
                    conversationStore.updateConversationState(conversationId) { current ->
                        current.copy(
                            messageNodes = current.messageNodes.map { node ->
                                node.copy(
                                    messages = node.messages.map { msg ->
                                        if (streamedMessageIds.isEmpty() || msg.id in streamedMessageIds) {
                                            msg.finishReasoning()
                                        } else {
                                            msg
                                        }
                                    },
                                )
                            },
                            updateAt = maxOf(current.updateAt, updatedAt),
                        )
                    }
                    val updatedConversation = conversationStore.getConversationFlow(conversationId).value
                    val checkpointLoadState = conversationStore.session(conversationId).timelineLoadState.value
                    val conversationCheckpointed = conversationStore.checkpointConversation(
                        conversationId, updatedConversation, force = true,
                    )
                    if (restoreWriteGate?.isWriteAllowed(generationRestoreEpoch) == false) {
                        generationTaskId?.let {
                            runPresenter.finishGenerationTask(it, CancellationException("Generation interrupted by restore"), runId)
                        }
                        localTools.endWebMountRun(
                            runId = runId,
                            conversationId = conversationId.toString(),
                            reason = "generation superseded by restore",
                        )
                        return@finalizeChatGeneration
                    }
                    var terminalPublish: RunTerminalState? = null
                    var terminalOwnerSucceeded = !durable
                    if (durable) {
                        val (state, reason) = terminalForFlowEnd(cause, reportedTerminal)
                        val serverCancelPending = storedResponseStopCancel?.consumeStopUnconfirmed(runId) == true
                        val target = when {
                            serverCancelPending -> RunTerminalState.WAITING_EXTERNAL
                            !conversationCheckpointed && cause == null -> RunTerminalState.RESUMABLE
                            else -> state
                        }
                        val targetReason = when {
                            serverCancelPending -> PauseReason.USER_STOP
                            target == RunTerminalState.RESUMABLE -> null
                            else -> reason
                        }
                        terminalOwnerSucceeded = runCatching {
                            if (target.isTerminal) {
                                runTerminalStore!!.finish(runId, target, targetReason)
                            } else {
                                runTerminalStore!!.pause(runId, target, targetReason)
                            }
                            val persisted = runTerminalStore.get(runId)
                            terminalPublish = persisted?.state
                            // Keep the kernel's CAS state in agreement with the
                            // durable owner, including deferred checkpoints.
                            val persistedState = persisted?.state?.let {
                                app.amber.core.agent.runtime.RunStatus.valueOf(it.name)
                            }
                            val kernelTransition = if (persistedState != null) {
                                agentEventStore?.transitionRun(
                                    app.amber.core.agent.runtime.AgentRunId(runId),
                                    app.amber.core.agent.runtime.RunStatus.LIVE_STATES,
                                    persistedState,
                                )
                            } else {
                                null
                            }
                            val kernelOwnerSucceeded = agentEventStore == null ||
                                kernelTransition is app.amber.core.agent.runtime.RunTransitionResult.Applied ||
                                (kernelTransition is app.amber.core.agent.runtime.RunTransitionResult.Rejected &&
                                    kernelTransition.current == persistedState)
                            persisted?.state == target && kernelOwnerSucceeded
                        }.onFailure { error ->
                            Log.w(TAG, "terminal owner persist failed for $conversationId", error)
                        }.getOrDefault(false)
                        if (terminalOwnerSucceeded && conversationCheckpointed &&
                            !serverCancelPending &&
                            terminalPublish in setOf(RunTerminalState.COMPLETED, RunTerminalState.STEP_LIMIT)
                        ) {
                            responseRequest?.let { request ->
                                runCatching { request.store.clear(request.runId) }
                                    .onFailure { Log.w(TAG, "stored response cursor clear failed for $conversationId", it) }
                            }
                        }
                        if (state == RunTerminalState.CANCELLED || state == RunTerminalState.FAILED || serverCancelPending) {
                            runCatching { runRecovery!!.reconcileStartedEffects(runId) }
                            runCatching { approvalActions.refreshOutcomeUnknown() }
                        }
                        if (state == RunTerminalState.CANCELLED) {
                            runCatching { subAgentManager.cancelByRootRun(runId, conversationId.toString()) }
                        }
                    }
                    if (terminalOwnerSucceeded && terminalPublish != RunTerminalState.WAITING_USER) {
                        localTools.endWebMountRun(
                            runId = runId,
                            conversationId = conversationId.toString(),
                            reason = if (cause == null) "generation finished" else "generation failed",
                            preservePendingHandoff = terminalPublish == RunTerminalState.COMPLETED,
                        )
                    }

                    // P8-11: a paused approval run keeps the live notification
                    // (approve/deny/reply/stop actions); a true completion turns
                    // it into the green "done" card, other terminal outcomes
                    // dismiss it, failures replace it with the failure card.
                    val waitingForApproval = terminalPublish == RunTerminalState.WAITING_USER &&
                        conversationStore.getConversationFlow(conversationId).value.currentMessages.any { message ->
                            message.parts.any { it is UIMessagePart.Tool && it.isPending }
                        }
                    // Completion only when the run truly completed (STEP_LIMIT /
                    // WAITING_USER are never "done"); non-durable turns have no
                    // persisted state and complete with the flow.
                    val completed = cause == null &&
                        (!durable || (terminalPublish == RunTerminalState.COMPLETED && terminalOwnerSucceeded))
                    val alertOnCompletion = !runPresenter.isForeground.value &&
                        turnSettings.displaySetting.enableNotificationOnMessageGeneration
                    val completedCardShown = completed && turnSettings.agentRuntime.enableLiveStatusNotification
                    when {
                        waitingForApproval -> runPresenter.updateLiveStatus(
                            conversationId = conversationId,
                            messages = conversationStore.getConversationFlow(conversationId).value.currentMessages,
                            senderName = senderName,
                            enabled = turnSettings.agentRuntime.enableLiveStatusNotification,
                            hideSensitive = turnSettings.agentRuntime.hideSensitiveLiveStatus,
                            runId = runId.takeIf { durable },
                        )

                        completedCardShown -> runPresenter.notifyCompleted(
                            conversationId = conversationId,
                            senderName = senderName,
                            messages = conversationStore.getConversationFlow(conversationId).value.currentMessages,
                            hideSensitive = turnSettings.agentRuntime.hideSensitiveLiveStatus,
                            alert = alertOnCompletion,
                            runId = runId.takeIf { durable },
                        )

                        cause == null -> runPresenter.cancelLiveStatus(conversationId)

                        cause is CancellationException ||
                            !turnSettings.agentRuntime.enableLiveStatusNotification ->
                            runPresenter.cancelLiveStatus(conversationId)

                        else -> runPresenter.notifyFailure(
                            conversationId = conversationId,
                            senderName = senderName,
                            cause = cause,
                            runId = runId.takeIf { durable },
                        )
                    }

                    // Keep-alive follows the persisted terminal state: pauses
                    // keep it (the user can resume from the notification);
                    // terminal states stop it unless a queued continuation
                    // starts the next turn right away.
                    val shouldStopKeepAlive = if (durable) {
                        runCatching { runTerminalStore?.get(runId)?.state?.isTerminal == true }
                            .getOrDefault(true)
                    } else {
                        true
                    }
                    if (shouldStopKeepAlive && !hasQueuedContinuation(conversationId, runId)) {
                        runPresenter.stopGenerationKeepAlive(conversationId)
                    }

                    conversationStore.clearCheckpoint(conversationId)
                    generationTaskId?.let { runPresenter.finishGenerationTask(it, cause, runId) }
                    cleanupRunResourcesIfDone(conversationId, updatedConversation, runId)

                    check(terminalOwnerSucceeded) {
                        "Chat terminal state was not durably committed for $conversationId"
                    }

                    if (cause == null) {
                        // The green live "done" card already covers completion
                        // (and alerts when backgrounded); the plain completion
                        // notification remains for live status turned off.
                        if (completed && alertOnCompletion && !completedCardShown) {
                            runPresenter.sendGenerationDoneNotification(
                                conversationId, conversationStore.getConversationFlow(conversationId).value.currentMessages,
                                senderName, runId.takeIf { durable },
                            )
                        }
                        // Success side-effects (legacy onSuccess parity):
                        // window persistence, title, suggestions, memory.
                        val finalConversation = conversationStore.persistFinalWindow(
                            conversationId = conversationId,
                            checkpointed = conversationCheckpointed,
                            checkpointConversation = updatedConversation,
                            checkpointLoadState = checkpointLoadState,
                        )
                        cleanupRunResourcesIfDone(conversationId, finalConversation, runId)
                        launchWithConversationReference(conversationId, generationRestoreEpoch) {
                            aiAuxiliaryGenerator.generateTitle(conversationId, finalConversation)
                        }
                        launchWithConversationReference(conversationId, generationRestoreEpoch) {
                            aiAuxiliaryGenerator.generateSuggestion(conversationId, finalConversation)
                        }
                        // Recap only after a finished turn (not an approval pause or step limit).
                        // The paged window is checked first so short chats skip the full load.
                        val recapWindowLoad = conversationStore.session(conversationId).timelineLoadState.value
                        val recapCandidate = finalConversation.isRecapEligible() ||
                            (recapWindowLoad.initialized && !recapWindowLoad.isFullyLoaded)
                        if (recapGenerator != null && completed && recapCandidate &&
                            !finalConversation.hasPendingOrUnexecutedTools()
                        ) {
                            launchWithConversationReference(conversationId, generationRestoreEpoch) {
                                generateRecap(conversationId, force = false)
                            }
                        }
                        if (!finalConversation.hasPendingOrUnexecutedTools()) {
                            appScope.launch(Dispatchers.IO + restoreWriteContext(generationRestoreEpoch)) {
                                memoryExtractor.extractAfterConversation(
                                    conversationStore.loadFullConversationForGeneration(conversationId)
                                )
                            }
                        }
                    } else {
                        approvalActions.clearTrust(conversationId)
                        screenCaptureManager.releaseSession()
                        surfaceGenerationFailure(conversationId, cause)
                    }
                }
            },
            responsesResumeFor = resume@{ runId ->
                if (!durable) return@resume null
                val currentSettings = settingsStore.settingsFlow.first()
                val currentModel = currentSettings.getCurrentChatModel()
                val resumeProvider =
                    currentModel?.findProvider(currentSettings.providers) as? ProviderSetting.OpenAI
                val currentConversation = conversationStore.getConversationFlow(conversationId).value
                if (
                    resumeProvider != null &&
                    resumeProvider.enableResponsesResume &&
                    resumeProvider.supportsResponsesResume() &&
                    capabilityFlags?.isEnabled(Capability.OpenAIResponsesResume) == true &&
                    responsesResumeStore != null &&
                    (
                        // continuation: same runId, re-attach to the stored response
                        (existingRun != null &&
                            existingRun.runId == runId &&
                            existingRun.state in RESPONSES_RESUME_STATES &&
                            currentConversation.currentMessages.lastOrNull()?.role == MessageRole.ASSISTANT) ||
                            // fresh generation: new runId, write-ahead cursor
                            existingRun == null
                        )
                ) {
                    app.amber.ai.provider.ResponsesResumeRequest(
                        runId = runId,
                        store = responsesResumeStore,
                        resumeFrom = existingRun?.takeIf { it.state in RESPONSES_RESUME_STATES }
                            ?.let { responsesResumeStore.load(it.runId) },
                    ).also { responseRequest = it }
                } else {
                    null
                }
            },
        )
    }

    private fun surfaceGenerationFailure(conversationId: Uuid, cause: Throwable) {
        if (cause is CancellationException) return
        val (errorTitle, surfacedError) = when (cause) {
            is app.amber.core.context.ContextCompactionFailedException -> {
                val hint = context.getString(
                    R.string.error_auto_compact_failed_hint,
                    cause.phase,
                    cause.compactionReason,
                )
                context.getString(R.string.error_title_compress_conversation) to RuntimeException(hint, cause)
            }

            // P1-04: the final token fit could not satisfy the hard budget
            // even after trimming — the request was never sent.
            is app.amber.core.context.ContextTooLargeException ->
                context.getString(R.string.chat_page_compress_recent_content_too_large) to cause

            else -> context.getString(R.string.error_title_generation) to cause
        }
        errorStore.addError(surfacedError, conversationId, title = errorTitle)
    }

    private suspend fun clearPersistedToolPayloads(durablePath: Boolean, messages: List<UIMessage>) {
        if (!durablePath) return
        val ledger = toolEffectLedger ?: return
        for (message in messages) {
            for (part in message.parts) {
                if (part !is UIMessagePart.Tool || !part.isExecuted) continue
                runCatching {
                    // A callId can have several ledger rows (retries, older
                    // attempts); sweep them all — markResultPersisted only
                    // touches FINISHED/FAILED rows with a payload, so this
                    // can never corrupt a live PREPARED/STARTED effect.
                    ledger.listByToolCallId(part.toolCallId).forEach { effect ->
                        ledger.markResultPersisted(effect.effectId)
                    }
                }.onFailure { error ->
                    Log.w(TAG, "clearPersistedToolPayloads failed for ${part.toolCallId}", error)
                }
            }
        }
    }

    private fun hasQueuedContinuation(conversationId: Uuid, excludingRunId: String? = null): Boolean =
        conversationStore.sessionOrNull(conversationId)?.pendingUserMessages?.value?.isNotEmpty() == true ||
            localAsyncTaskCoordinator?.hasActiveOrQueuedTasks(conversationId, excludingRunId) == true

    private fun cleanupRunResourcesIfDone(
        conversationId: Uuid,
        conversation: Conversation,
        excludingRunId: String? = null,
    ) {
        if (localAsyncTaskCoordinator?.hasActiveOrQueuedTasks(conversationId, excludingRunId) == true) return
        if (conversation.hasPendingOrUnexecutedTools()) return
        approvalActions.clearTrust(conversationId)
        screenCaptureManager.releaseSession()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        expectedRestoreEpoch: Long? = null,
        block: suspend () -> Unit,
    ): Job = appScope.launch(restoreWriteContext(expectedRestoreEpoch)) {
        val session = conversationStore.session(conversationId)
        session.acquire()
        try {
            block()
        } finally {
            session.release()
        }
    }

    private fun restoreWriteContext(expectedRestoreEpoch: Long? = restoreWriteGate?.currentEpoch()): CoroutineContext =
        expectedRestoreEpoch?.let(::SyncRestoreWriteEpoch) ?: EmptyCoroutineContext

    private suspend fun captureRestoreEpoch(): Long? {
        val gate = restoreWriteGate ?: return null
        return coroutineContext[SyncRestoreWriteEpoch]?.value ?: gate.currentEpoch()
    }

    fun observeRecap(conversationId: Uuid): StateFlow<app.amber.core.recap.RecapState> =
        recapStore?.observe(conversationId.toString())
            ?: MutableStateFlow(app.amber.core.recap.RecapState(loaded = true)).asStateFlow()

    suspend fun loadRecap(conversationId: Uuid) {
        recapStore?.ensureLoaded(conversationId.toString())
    }

    /**
     * Generates the recap from the full current branch. Freshness is checked by the
     * generator against the full conversation; below the threshold the state becomes
     * `ineligible`. Load failures end in `failed` instead of escaping to the caller.
     */
    suspend fun generateRecap(conversationId: Uuid, force: Boolean) {
        val generator = recapGenerator ?: return
        val full = try {
            conversationStore.loadFullConversationForGeneration(conversationId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error.printStackTrace()
            recapStore?.markFailed(conversationId.toString(), app.amber.core.recap.RecapFailure.ERROR)
            return
        }
        generator.generate(full, force)
    }

}

private fun Conversation.hasPendingOrUnexecutedTools(): Boolean =
    currentMessages.lastOrNull()
        ?.getTools()
        ?.any { !it.isExecuted || it.isPending } == true
