package app.amber.feature.subagent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunner
import app.amber.core.agent.runtime.RunStatus
import app.amber.core.ai.GenerationTerminal
import app.amber.core.settings.Capability
import app.amber.core.settings.CapabilityFlags
import app.amber.core.settings.Settings
import app.amber.feature.history.SessionAccessGrantStore
import app.amber.feature.runtime.ExecutionPolicy
import app.amber.core.infra.AppScope
import app.amber.feature.task.AgentTaskSnapshot
import app.amber.feature.task.AgentTaskOutputRef
import app.amber.feature.task.AgentTaskRetryPolicy
import app.amber.feature.task.AgentTaskStatus
import app.amber.feature.task.AgentTaskStore
import app.amber.feature.task.running
import app.amber.feature.task.toQueueState
import app.amber.core.settings.prefs.SettingsAggregator
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.uuid.Uuid

class SubAgentManager(
    context: Context,
    private val appScope: AppScope,
    private val settingsStore: SettingsAggregator,
    private val json: Json,
    private val agentTaskStore: AgentTaskStore,
    private val sessionAccessGrantStore: SessionAccessGrantStore,
    /**
     * Kernel entry: every generation turn runs as an [AgentRunner] run
     * (descriptor [SubAgentTurnDescriptor]). The manager owns the durable
     * THREAD lifecycle (ThreadGraphStore); the runner owns the per-turn RUN
     * lifecycle (run row + CAS + artifact). Runtime objects reach the turn
     * handler through [turnPayloads], keyed by the per-turn run id.
     */
    private val agentRunner: AgentRunner,
    private val turnPayloads: SubAgentTurnPayloads,
    // P4-02: persistent thread graph (thread_graph_v2 flag). Null/flag-off
    // keeps the exact legacy in-memory behavior — no store writes, no reads.
    private val threadGraphStore: ThreadGraphStore? = null,
    private val capabilityFlags: CapabilityFlags? = null,
) {
    private val runDir = File(context.filesDir, "amberagent/subagents/runs").also { it.mkdirs() }
    private val runs = ConcurrentHashMap<String, RuntimeRun>()
    private val admissionLock = Any()
    /** Serialize lifecycle operations that can otherwise resurrect a thread. */
    private val threadOperationLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Per-run streaming text flows. The runner writes the assistant's evolving response here as
     * generation chunks arrive; UI subscribes via [liveTextFlow]. Entries are kept after the run
     * finishes so a freshly-opened sheet can display the final text; cleaned up via [LIVE_TEXT_CAP].
     */
    private val liveTextFlows = ConcurrentHashMap<String, MutableStateFlow<String>>()
    private val livePartsFlows = ConcurrentHashMap<String, MutableStateFlow<List<UIMessagePart>>>()
    /** Per-thread lifecycle stream used by task cards after a followup starts. */
    private val runStateFlows = ConcurrentHashMap<String, MutableStateFlow<SubAgentRun?>>()

    /**
     * P4-02: per-running-thread mailbox for send_message. Messages delivered
     * into the mailbox are drained by the runner via consumeSteerMessages
     * (steer messages appended between tool rounds). Created at start/followup
     * when the thread graph is enabled.
     */
    private val mailboxes = ConcurrentHashMap<String, Channel<UIMessage>>()

    private val threadGraphManager: ThreadGraphManager by lazy {
        ThreadGraphManager(requireNotNull(threadGraphStore), json)
    }

    /** P4-02 gate: persistence, new tools and cascade are all flag-gated. */
    private suspend fun threadGraphEnabled(): Boolean =
        threadGraphStore != null &&
            capabilityFlags?.isEnabled(Capability.ThreadGraphV2) == true

    private suspend fun captureThreadGraphWriteContext(): CoroutineContext =
        threadGraphStore?.captureWriteContext() ?: EmptyCoroutineContext

    private fun threadOperationLock(threadId: String): Mutex =
        threadOperationLocks.getOrPut(threadId) { Mutex() }

    suspend fun start(
        parentConversationId: Uuid,
        input: JsonObject,
        parentTools: List<Tool>,
        parentRunId: String? = null,
        parentToolsForRun: ((String) -> List<Tool>)? = null,
        onRunFinished: ((String, String, Boolean) -> Unit)? = null,
        /**
         * P1-7: the parent run's sandbox policy at subagent_start time. Null
         * (producer cannot know it) keeps the permissive v1 default.
         */
        parentPolicy: ExecutionPolicy? = null,
    ): JsonObject = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.value
        val subAgentSetting = settings.agentRuntime.subAgent
        if (!subAgentSetting.enabled) {
            return@withContext errorPayload("subagent_disabled", "Subagent experimental mode is disabled.")
        }

        val parentToolNames = parentTools.map { it.name }.toSet()
        val task = runCatching { SubAgentValidator.parseTask(input) }
            .getOrElse { return@withContext errorPayload("invalid_task", it.message ?: it.toString()) }
        val definition = runCatching {
            SubAgentValidator.resolveDefinition(input, subAgentSetting, parentToolNames).definition
        }.getOrElse {
            return@withContext errorPayload("invalid_subagent", it.message ?: it.toString())
        }
        val effectiveDefinition = if (definition.dynamic) {
            runCatching {
                SubAgentValidator.validateToolAllowlist(definition.toolAllowlist, parentToolNames)
            }.getOrElse {
                return@withContext errorPayload("invalid_tools", it.message ?: it.toString())
            }
            definition
        } else {
            definition.copy(
                toolAllowlist = definition.toolAllowlist
                    .filter { it in parentToolNames }
                    .toSet()
            )
        }
        if (!effectiveDefinition.dynamic && effectiveDefinition.toolAllowlist.isEmpty()) {
            return@withContext errorPayload(
                "no_allowed_tools",
                "No allowed tools are currently available for subagent ${definition.id}."
            )
        }
        val historyGrant = if (effectiveDefinition.isHistoryReader() && task.sourceSessionIds.isNotEmpty()) {
            sessionAccessGrantStore.create(
                sessionIds = task.sourceSessionIds,
                maxChars = effectiveDefinition.outputBudgetChars * 4,
                purpose = task.objective,
                sourceConversationId = parentConversationId.toString(),
            )
        } else {
            task.sessionGrantId.takeIf { it.isNotBlank() }?.let { sessionAccessGrantStore.get(it) }
        }
        val effectiveTask = if (historyGrant != null && task.sessionGrantId.isBlank()) {
            task.copy(sessionGrantId = historyGrant.grantId)
        } else {
            task
        }

        val now = Instant.now().toEpochMilli()
        val runId = Uuid.random().toString()
        // A followup keeps the public thread/run id, but every generation gets
        // its own opaque host-owned WebMount scope. It must never be reused by
        // a later turn or exposed through the model payload.
        val webMountScopeId = Uuid.random().toString()
        val scopedParentTools = parentToolsForRun?.invoke(webMountScopeId) ?: parentTools
        val allowedTools = scopedParentTools
            .filterNot { it.name.startsWith("subagent_") }
            .filter { it.name in effectiveDefinition.toolAllowlist }
            .map { tool ->
                if (tool.name in HISTORY_FULL_READ_TOOLS && historyGrant != null) {
                    tool.copy(needsApproval = false, allowsAutoApproval = true)
                } else {
                    tool
                }
            }

        val transcript = File(runDir, "$runId.jsonl")
        val run = SubAgentRun(
            runId = runId,
            parentConversationId = parentConversationId,
            definition = effectiveDefinition,
            task = effectiveTask,
            status = SubAgentRunStatus.RUNNING,
            transcriptPath = transcript.absolutePath,
            startedAtMs = now,
        )
        val threadGraph = threadGraphEnabled()
        // Capture once before the write-ahead node and before the model job
        // starts. The same opaque context follows every terminal callback;
        // it must never be reacquired after a restore.
        val threadGraphWriteContext = if (threadGraph) {
            requireNotNull(threadGraphStore).captureWriteContext()
        } else {
            EmptyCoroutineContext
        }
        val runtimeRun = RuntimeRun(
            snapshot = run,
            writeContext = threadGraphWriteContext,
            webMountScopeId = webMountScopeId,
            onRunFinished = onRunFinished,
        )
        val threadStart = if (threadGraph) {
            try {
                withContext(threadGraphWriteContext) {
                    threadGraphManager.prepareStart(
                        parentRunId = parentRunId,
                        fallbackRootRunId = parentConversationId.toString(),
                    )
                }
            } catch (error: ThreadGraphManager.ThreadGraphDepthLimitException) {
                return@withContext errorPayload("thread_depth_limit", error.message ?: "Thread depth limit reached.")
            } catch (error: IllegalArgumentException) {
                return@withContext errorPayload("invalid_parent_thread", error.message ?: "Invalid parent thread.")
            }
        } else {
            null
        }
        val admissionError = synchronized(admissionLock) {
            val runLimit = subAgentSetting.maxConcurrentRuns.coerceAtLeast(1)
            val running = runs.values.count { it.snapshot.status.running }
            when {
                running >= runLimit -> {
                    "too_many_subagents" to "Subagent concurrency limit reached."
                }

                effectiveDefinition.dynamic &&
                    runs.values.count { it.snapshot.status.running && it.snapshot.definition.dynamic } >= runLimit -> {
                    "too_many_dynamic_subagents" to "Dynamic subagent per-turn limit reached."
                }

                else -> {
                    runs[runId] = runtimeRun
                    null
                }
            }
        }
        if (admissionError != null) {
            return@withContext errorPayload(admissionError.first, admissionError.second)
        }
        runStateFlows[runId] = MutableStateFlow(run)
        agentTaskStore.register(run.toAgentTaskSnapshot(), cancel = {
            cancel(runId)
            true
        })
        appendEvent(runtimeRun, "started", runToPayload(run))

        // P4-02 write-ahead: persist the RUNNING node BEFORE the job launches,
        // so a crash right after subagent_start leaves a recoverable thread
        // instead of a phantom in-memory run.
        if (threadGraph) {
            val startContext = requireNotNull(threadStart)
            withContext(threadGraphWriteContext) {
                threadGraphManager.startNode(
                    run = run,
                    rootRunId = startContext.rootRunId,
                    parentThreadId = startContext.parentThreadId,
                )
            }
        }

        // Live text flow for UI subscribers — created BEFORE the runner starts so a sheet opened
        // immediately after subagent_start sees the same flow that will be written to.
        val liveText = MutableStateFlow("")
        val liveParts = MutableStateFlow<List<UIMessagePart>>(emptyList())
        liveTextFlows[runId] = liveText
        livePartsFlows[runId] = liveParts
        runStateFlows.getOrPut(runId) { MutableStateFlow(run) }.value = run
        capLiveTextFlows()
        if (threadGraph) {
            mailboxes[runId] = Channel(Channel.UNLIMITED)
        }

        if (!launchTurn(
                runtimeRun = runtimeRun,
                settings = settings,
                tools = scopedSubAgentTools(allowedTools),
                liveText = liveText,
                liveParts = liveParts,
                threadGraph = threadGraph,
                previousAnswer = "",
                parentConversationId = parentConversationId,
                parentRunId = parentRunId,
                followup = false,
                parentPolicy = parentPolicy,
            )
        ) {
            finish(
                runtimeRun,
                SubAgentResult(
                    status = SubAgentRunStatus.FAILED,
                    error = "Sub-agent turn agent is not registered in the kernel.",
                ),
            )
            return@withContext errorPayload("launch_failed", "Sub-agent turn could not be launched.")
        }

        runToPayload(run)
    }

    suspend fun read(runId: String): JsonObject = withContext(Dispatchers.IO) {
        val live = runs[runId]?.snapshot
        if (live != null) return@withContext runToPayload(live)
        // P4-02: no live run — fall back to the persisted thread graph
        // (cold start / eviction). A persisted RUNNING node is a
        // process-death victim and is reconciled to INTERRUPTED here.
        if (threadGraphEnabled()) {
            withContext(captureThreadGraphWriteContext()) {
                threadGraphManager.restorePayload(runId)
            }?.let { return@withContext it }
        }
        readMissingRun(runId)
    }

    suspend fun wait(runId: String, waitTimeoutMs: Long): JsonObject = withContext(Dispatchers.IO) {
        val current = runs[runId]?.snapshot
        if (current == null) {
            // P4-02: the thread is not live (restart/eviction). Waiting for
            // a dead process is pointless — reconcile and return its state.
            if (threadGraphEnabled()) {
                withContext(captureThreadGraphWriteContext()) {
                    threadGraphManager.restorePayload(runId)
                }?.let { return@withContext it }
            }
            return@withContext readMissingRun(runId)
        }
        if (!current.status.running) return@withContext runToPayload(current)

        // AgentTaskStore already publishes terminal transitions through a
        // StateFlow. Observe that event instead of polling the in-memory run.
        val timeoutMs = waitTimeoutMs.coerceIn(0, 60_000L)
        if (timeoutMs > 0L) {
            withTimeoutOrNull(timeoutMs) {
                agentTaskStore.tasksFlow.first { tasks ->
                    val taskStopped = tasks.firstOrNull { it.taskId == runId }?.status?.running == false
                    val runPausedOrStopped = runs[runId]?.snapshot?.status?.running == false
                    taskStopped || runPausedOrStopped
                }
            }
        }
        read(runId)
    }

    suspend fun cancel(runId: String): JsonObject = withContext(Dispatchers.IO) {
        threadOperationLock(runId).withLock {
            val runtimeRun = runs[runId]
            if (runtimeRun != null) {
                runtimeRun.pendingTerminal = SubAgentRunStatus.CANCELLED
                runtimeRun.turnRunId?.let(agentRunner::cancel)
                runtimeRun.job?.cancel()
                finishLocked(
                    runtimeRun,
                    SubAgentResult(
                        status = SubAgentRunStatus.CANCELLED,
                        summary = "Subagent run was cancelled.",
                    )
                )
                runToPayload(runtimeRun.snapshot)
            } else {
                // P4-02: cancel a persisted thread with no live run (cold-start
                // cancellation — a stale RUNNING/INTERRUPTED node becomes
                // CANCELLED with a terminal result).
                if (threadGraphEnabled()) {
                    withContext(captureThreadGraphWriteContext()) {
                        threadGraphManager.cancelPersisted(runId)
                    }?.let { return@withLock it }
                }
                readMissingRun(runId)
            }
        }
    }

    /**
     * P4-02 — followup_task: continue an idle (non-running) thread with a new
     * task. The followup is enqueued (queued), delivered when the new
     * generation starts, and marked persisted when its result lands. The child
     * reuses the same threadId (thread graph identity) and is seeded with its
     * previous final answer so the continuation is not context-blind.
     */
    suspend fun followup(
        parentConversationId: Uuid,
        threadId: String,
        input: JsonObject,
        parentTools: List<Tool>,
        parentRunId: String? = null,
        parentToolsForRun: ((String) -> List<Tool>)? = null,
        onRunFinished: ((String, String, Boolean) -> Unit)? = null,
        /** P1-7: same contract as [start] — the calling run's sandbox policy. */
        parentPolicy: ExecutionPolicy? = null,
    ): JsonObject = withContext(Dispatchers.IO) {
        threadOperationLock(threadId).withLock {
            if (!threadGraphEnabled()) {
                return@withLock errorPayload("thread_graph_disabled", "Thread graph is not enabled.")
            }
            val threadGraphWriteContext = captureThreadGraphWriteContext()
            val settings = settingsStore.settingsFlow.value
            val subAgentSetting = settings.agentRuntime.subAgent
            if (!subAgentSetting.enabled) {
                return@withLock errorPayload("subagent_disabled", "Subagent experimental mode is disabled.")
            }
            val live = runs[threadId]?.snapshot
            var node = withContext(threadGraphWriteContext) {
                threadGraphManager.getState(threadId)
            }
            if (live == null && node == null) {
                return@withLock errorPayload("not_found", "Unknown thread_id: $threadId")
            }
            if (live == null && node != null) {
                // A cold-start followup can arrive before the parent has issued
                // read/wait. Reconcile the persisted owner here so a message
                // claimed by the dead generation returns to QUEUED before the
                // new generation drains it.
                withContext(threadGraphWriteContext) {
                    threadGraphManager.restorePayload(threadId)
                    node = threadGraphManager.getState(threadId)
                }
            }
            if (live?.status == SubAgentRunStatus.RUNNING || node?.status == SubAgentRunStatus.RUNNING) {
                return@withLock errorPayload("thread_running", "Thread $threadId is still running; followup is only allowed on an idle thread.")
            }
            if (live?.status == SubAgentRunStatus.CANCELLED || node?.status == SubAgentRunStatus.CANCELLED) {
                return@withLock errorPayload("thread_cancelled", "Thread $threadId was cancelled and cannot be continued.")
            }
            val restored = withContext(threadGraphWriteContext) {
                restoreThreadForFollowup(threadId, live, node)
            } ?: return@withLock errorPayload(
                "not_found",
                "Unknown thread_id: $threadId",
            )
            val followupTask = runCatching { SubAgentValidator.parseTask(input) }
                .getOrElse { return@withLock errorPayload("invalid_task", it.message ?: it.toString()) }
            // Merge the original task context so the continuation stays within the
            // thread's boundaries; the followup objective drives the new turn.
            val mergedTask = followupTask.copy(
                context = buildString {
                    append(restored.task.context)
                    if (restored.task.context.isNotBlank() && followupTask.context.isNotBlank()) append("\n")
                    append(followupTask.context)
                },
            )
            val webMountScopeId = Uuid.random().toString()
            val scopedParentTools = parentToolsForRun?.invoke(webMountScopeId) ?: parentTools
            withContext(threadGraphWriteContext) {
                threadGraphManager.enqueueFollowup(threadId, mergedTask)
            }
            launchFollowupGeneration(
                parentConversationId = parentConversationId,
                threadId = threadId,
                definition = restored.definition,
                task = mergedTask,
                parentTools = scopedParentTools,
                parentRunId = parentRunId,
                previousAnswer = restored.previousAnswer,
                writeContext = threadGraphWriteContext,
                webMountScopeId = webMountScopeId,
                onRunFinished = onRunFinished,
                parentPolicy = parentPolicy,
            )
            withContext(threadGraphWriteContext) { read(threadId) }
        }
    }

    /**
     * P4-02 — send_message: deliver a message to a thread. A live thread
     * receives it through its mailbox immediately (delivered); a thread that
     * is not running keeps it queued — it is delivered when the thread next
     * runs (followup) and persisted once that turn's result lands. A message
     * is never dropped between dequeue and result persistence.
    */
    suspend fun sendMessage(threadId: String, message: String): JsonObject = withContext(Dispatchers.IO) {
        threadOperationLock(threadId).withLock {
            if (!threadGraphEnabled()) {
                return@withLock errorPayload("thread_graph_disabled", "Thread graph is not enabled.")
            }
            if (message.isBlank()) {
                return@withLock errorPayload("invalid_message", "message must not be blank.")
            }
            val threadGraphWriteContext = captureThreadGraphWriteContext()
            val node = withContext(threadGraphWriteContext) {
                threadGraphManager.getState(threadId)
            }
            if (node == null) {
                return@withLock errorPayload("not_found", "Unknown thread_id: $threadId")
            }
            if (node.status == SubAgentRunStatus.CANCELLED) {
                return@withLock errorPayload("thread_cancelled", "Thread $threadId was cancelled.")
            }
            val safeMessage = message.take(MAX_SEND_MESSAGE_CHARS)
            val record = withContext(threadGraphWriteContext) {
                threadGraphManager.enqueueMessage(threadId, safeMessage)
            }
            val live = runs[threadId]?.snapshot
            val delivered = live?.status == SubAgentRunStatus.RUNNING && mailboxes[threadId]?.trySend(
                UIMessage.user(safeMessage)
            )?.isSuccess == true
            if (delivered) {
                withContext(threadGraphWriteContext) {
                    threadGraphManager.markDelivered(record.messageId)
                }
            }
            val updatedAtMs = withContext(threadGraphWriteContext) {
                threadGraphManager.getState(threadId)?.updatedAtMs
            }
            buildJsonObject {
                put("status", "ok")
                put("thread_id", threadId)
                put("message_id", record.messageId)
                put("delivery_state", if (delivered) ThreadDeliveryState.DELIVERED.name.lowercase() else ThreadDeliveryState.QUEUED.name.lowercase())
                put("digest", record.payloadDigest)
                updatedAtMs?.let { put("updated_at_ms", it) }
            }
        }
    }

    /**
     * P4-02 — interrupt: stop the thread's current turn but keep the thread.
     * The thread becomes INTERRUPTED (resumable via followup_task); it is
     * never deleted and never marked CANCELLED/COMPLETED.
     */
    suspend fun interrupt(threadId: String): JsonObject = withContext(Dispatchers.IO) {
        threadOperationLock(threadId).withLock {
            if (!threadGraphEnabled()) {
                return@withLock errorPayload("thread_graph_disabled", "Thread graph is not enabled.")
            }
            val runtimeRun = runs[threadId]
            if (runtimeRun != null) {
                val live = runtimeRun.snapshot
                if (live.status != SubAgentRunStatus.RUNNING) {
                    return@withLock errorPayload("thread_not_running", "Thread $threadId is not running.")
                }
                runtimeRun.pendingTerminal = SubAgentRunStatus.INTERRUPTED
                runtimeRun.turnRunId?.let(agentRunner::cancel)
                runtimeRun.job?.cancel()
                // Preserve the thread: status INTERRUPTED + a result carrying the
                // text produced so far; the node stays (followup can continue it).
                // The terminal snapshot is written back under the run lock (same
                // path as finish()) so same-process followup/read/persistedState
                // never observe a stale RUNNING after an interrupt.
                val partialText = liveTextFlows[threadId]?.value.orEmpty().ifBlank { live.displayText }
                val result = SubAgentResult(
                    status = SubAgentRunStatus.INTERRUPTED,
                    summary = "Subagent turn was interrupted.",
                )
                val next = writeTerminalSnapshot(runtimeRun, SubAgentRunStatus.INTERRUPTED, result, partialText)
                    ?: return@withLock errorPayload("thread_not_running", "Thread $threadId is not running.")
                withContext(runtimeRun.writeContext) {
                    // Interrupt is a terminal generation outcome too: use the
                    // same durable result, task snapshot, finished event and
                    // WebMount cleanup path as a normal runner return.
                    persistTerminal(runtimeRun, next, result, partialText)
                }
                runToPayload(next)
            } else {
                val payload = withContext(captureThreadGraphWriteContext()) {
                    threadGraphManager.interruptPersisted(threadId, finalAnswer = "")
                }
                    ?: return@withLock errorPayload("not_found", "Unknown thread_id: $threadId")
                payload
            }
        }
    }

    /**
     * P4-02 — cascade cancellation: cancel every thread started by [rootRunId]
     * in [conversationId]. Called when the parent run is cancelled (user stop
     * or notification stop); replaces the pre-Phase-4 detach policy. Gated by
     * the flag — off keeps the legacy detached behavior.
     */
    suspend fun cancelByRootRun(rootRunId: String, conversationId: String): JsonObject = withContext(Dispatchers.IO) {
        if (!threadGraphEnabled()) {
            return@withContext errorPayload("thread_graph_disabled", "Thread graph is not enabled.")
        }
        val threadGraphWriteContext = captureThreadGraphWriteContext()
        val nodes = withContext(threadGraphWriteContext) {
            threadGraphManager.listByRootRun(rootRunId)
        }
            .filter { it.conversationId == conversationId }
        var cancelled = 0
        for (node in nodes) {
            threadOperationLock(node.threadId).withLock {
                val currentNode = withContext(threadGraphWriteContext) {
                    threadGraphManager.getState(node.threadId)
                }
                val status = currentNode?.status
                    ?: runCatching { SubAgentRunStatus.valueOf(node.status) }.getOrNull()
                if (
                    status == SubAgentRunStatus.RUNNING ||
                    status == SubAgentRunStatus.INTERRUPTED ||
                    status == SubAgentRunStatus.APPROVAL_REQUIRED
                ) {
                    val live = runs[node.threadId]
                    if (live != null) {
                        live.pendingTerminal = SubAgentRunStatus.CANCELLED
                        live.turnRunId?.let(agentRunner::cancel)
                        live.job?.cancel()
                        finishLocked(
                            live,
                            SubAgentResult(
                                status = SubAgentRunStatus.CANCELLED,
                                summary = "Subagent run was cancelled because the parent run was cancelled.",
                            )
                        )
                    } else {
                        withContext(threadGraphWriteContext) {
                            threadGraphManager.cancelPersisted(node.threadId)
                        }
                    }
                    cancelled++
                } else if (status == SubAgentRunStatus.CANCELLED) {
                    cancelled++
                }
            }
        }
        buildJsonObject {
            put("status", "ok")
            put("root_run_id", rootRunId)
            put("cancelled", cancelled)
        }
    }

    /**
     * P4-02 — UI-facing persisted thread state (status + final answer). Null
     * when the thread is unknown, the flag is off, or the thread is still
     * live (callers use the in-memory snapshot then).
     */
    suspend fun persistedState(threadId: String): ThreadGraphManager.ThreadGraphState? {
        if (!threadGraphEnabled()) return null
        val live = runs[threadId]?.snapshot
        if (live != null) return null // live runs are read from memory
        return threadGraphManager.getState(threadId)
    }

    fun listBuiltIns(): List<SubAgentDefinition> {
        val setting = settingsStore.settingsFlow.value.agentRuntime.subAgent
        val builtIns = if (setting.mode == SubAgentMode.SMART_DYNAMIC) {
            emptyList()
        } else {
            SubAgentDefinitions.builtIns.map { it.applyOverride(setting.overrides[it.id]) }
        }
        val customDefinitions = if (setting.mode == SubAgentMode.SMART_DYNAMIC) {
            setting.customDefinitions.map { custom ->
                custom.copy(
                    toolAllowlist = custom.toolAllowlist.intersect(SubAgentValidator.defaultDynamicReadOnlyTools),
                    dynamic = true,
                )
            }
        } else {
            setting.customDefinitions
        }
        return builtIns + customDefinitions
    }

    /**
     * UI-facing live stream of a subagent's accumulating assistant text. Null = unknown runId.
     *
     * **Completion signal**: this flow does NOT carry a "done" marker. UI should observe
     * [snapshot] (or its status) in parallel; when `status.running == false`, the latest text
     * is the final text. A `combine(liveTextFlow, snapshotFlow)` pattern works well.
     */
    fun liveTextFlow(runId: String): StateFlow<String>? = liveTextFlows[runId]?.asStateFlow()

    fun livePartsFlow(runId: String): StateFlow<List<UIMessagePart>>? = livePartsFlows[runId]?.asStateFlow()

    /**
     * Lifecycle updates for a task card. Create the per-run stream on demand so a card restored
     * from history can subscribe before a later followup starts the same thread in this process.
     */
    fun runStateFlow(runId: String): StateFlow<SubAgentRun?> =
        runStateFlows.getOrPut(runId) { MutableStateFlow(runs[runId]?.snapshot) }.asStateFlow()

    /** Snapshot of a known run, or null if it was never started or was already evicted. */
    fun snapshot(runId: String): SubAgentRun? = runs[runId]?.snapshot

    /** True iff Model Council experimental mode is currently on. Used by SubAgentTools to
     *  decide whether to advertise @council alongside the regular subagent roster. */
    fun isModelCouncilEnabled(): Boolean =
        settingsStore.settingsFlow.value.agentRuntime.modelCouncil.enabled

    fun runtimeMode(): SubAgentMode =
        settingsStore.settingsFlow.value.agentRuntime.subAgent.mode

    /**
     * Keep live UI flows bounded. Iterate the flow keys (not [runs].values) so orphaned
     * entries — flows whose run snapshot was already evicted elsewhere — are also reclaimed.
     * Active runs (status.running) are skipped: the runner is still writing to them.
     *
     * Race note: two concurrent [start] calls can both pass the size check and both insert
     * before this runs, so the cap is soft. Worst case: temporarily 65–66 entries, never an
     * eviction of a still-active run. Acceptable.
     */
    private fun capLiveTextFlows() {
        if (
            liveTextFlows.size <= LIVE_TEXT_CAP &&
            livePartsFlows.size <= LIVE_TEXT_CAP &&
            runStateFlows.size <= LIVE_TEXT_CAP
        ) return
        // Build (runId, lastUpdate) for every live-text key and pick the oldest non-running ones.
        val candidates = (liveTextFlows.keys + livePartsFlows.keys + runStateFlows.keys).mapNotNull { id ->
            val snap = runs[id]?.snapshot
            when {
                snap == null -> id to 0L  // orphan: definitely evictable, sort earliest
                snap.status.running -> null  // active: keep
                else -> id to snap.updatedAtMs
            }
        }.distinctBy { it.first }.sortedBy { it.second }
        val toDrop = maxOf(liveTextFlows.size, livePartsFlows.size, runStateFlows.size) - LIVE_TEXT_CAP
        candidates.take(toDrop).forEach { (id, _) ->
            liveTextFlows.remove(id)
            livePartsFlows.remove(id)
            runStateFlows.remove(id)
        }
    }

    fun runtimeSummary(): JsonObject {
        val setting = settingsStore.settingsFlow.value.agentRuntime.subAgent
        return buildJsonObject {
            put("enabled", setting.enabled)
            put("mode", setting.mode.name.lowercase())
            put("allow_dynamic_subagents", setting.allowDynamicSubAgents)
            put("max_concurrent_runs", setting.maxConcurrentRuns)
            put("dynamic_run_limit", setting.maxConcurrentRuns)
            put("tool_profiles", SubAgentToolProfile.entries.joinToString(",") { it.name.lowercase() })
            put("max_depth", ThreadGraphManager.MAX_THREAD_DEPTH)
            put("timeout_ms", setting.timeoutMs)
            put("max_turns", setting.maxTurns)
            put("output_budget_chars", setting.outputBudgetChars)
            put("running", runs.values.count { it.snapshot.status.running })
        }
    }

    private suspend fun finish(runtimeRun: RuntimeRun, result: SubAgentResult, displayText: String = "") {
        val runId = runtimeRun.snapshot.runId
        threadOperationLock(runId).withLock {
            finishLocked(runtimeRun, result, displayText)
        }
    }

    /** Finish while the caller already owns the per-thread lifecycle lock. */
    private suspend fun finishLocked(runtimeRun: RuntimeRun, result: SubAgentResult, displayText: String = "") {
        val runId = runtimeRun.snapshot.runId
        if (runs[runId] !== runtimeRun) return
        withContext(runtimeRun.writeContext) {
            finishInCapturedContext(runtimeRun, result, displayText)
        }
    }

    private suspend fun finishInCapturedContext(
        runtimeRun: RuntimeRun,
        result: SubAgentResult,
        displayText: String,
    ) {
        val runId = runtimeRun.snapshot.runId
        // The runner may ignore cancellation and return after a followup has
        // installed a newer RuntimeRun for the same public thread id. That old
        // callback must not publish into the new generation.
        if (runs[runId] !== runtimeRun) return
        // P1-03: a step-limited child must never be published as COMPLETED.
        // The generator reported StepLimit via onTerminal; map the runner's
        // "completed" result to TIMED_OUT (thread status + payload).
        val effectiveResult = if (runtimeRun.stepLimited && result.status == SubAgentRunStatus.COMPLETED) {
            result.copy(status = SubAgentRunStatus.TIMED_OUT)
        } else {
            result
        }
        val next = writeTerminalSnapshot(runtimeRun, effectiveResult.status, effectiveResult, displayText)
            ?: return
        persistTerminal(runtimeRun, next, effectiveResult, displayText)
    }

    /** Persist and close every terminal generation through one lifecycle path. */
    private suspend fun persistTerminal(
        runtimeRun: RuntimeRun,
        next: SubAgentRun,
        result: SubAgentResult,
        displayText: String,
    ) {
        try {
            if (threadGraphEnabled()) {
                threadGraphManager.finishNode(
                    runId = next.runId,
                    status = result.status,
                    result = result,
                    displayText = displayText.ifBlank { next.displayText },
                )
            }
        } finally {
            // The host scope belongs to this generation even when a restore
            // epoch rejects its durable write. Close it by identity before the
            // callback can disappear with the failed persistence attempt.
            if (next.status != SubAgentRunStatus.RUNNING && next.status != SubAgentRunStatus.APPROVAL_REQUIRED) {
                runtimeRun.onRunFinished?.invoke(
                    runtimeRun.webMountScopeId,
                    "generation ${next.status.name.lowercase()}",
                    next.status == SubAgentRunStatus.COMPLETED,
                )
            }
        }
        agentTaskStore.update(
            taskId = next.runId,
            status = next.status.toAgentTaskStatus(),
            summary = result.summary.ifBlank { result.findings.joinToString("; ").take(1_000) },
            error = result.error.takeIf { it.isNotBlank() },
            cancelCapability = result.status == SubAgentRunStatus.APPROVAL_REQUIRED,
            clearError = result.error.isBlank(),
            clearLastErrorCode = true,
        )
        appendEvent(runtimeRun, "finished", runToPayload(next, includeDisplayText = true))
    }

    /**
     * The single place in-memory state transitions to a terminal status:
     * write the terminal snapshot back under the run lock, carrying the
     * partial/final text produced so far. Returns null when the run is no
     * longer running (e.g. another caller already claimed the terminal
     * state) — the caller must then not persist or report a terminal it
     * does not own.
     */
    private fun writeTerminalSnapshot(
        runtimeRun: RuntimeRun,
        status: SubAgentRunStatus,
        result: SubAgentResult,
        displayText: String,
    ): SubAgentRun? = synchronized(runtimeRun) {
        val current = runtimeRun.snapshot
        val cancellingApproval =
            current.status == SubAgentRunStatus.APPROVAL_REQUIRED && status == SubAgentRunStatus.CANCELLED
        if (!current.status.running && !cancellingApproval) return@synchronized null
        current.copy(
            status = status,
            result = result,
            displayText = displayText.ifBlank { current.displayText },
            updatedAtMs = Instant.now().toEpochMilli(),
        ).also { runtimeRun.snapshot = it }
            .also { next -> runStateFlows[runtimeRun.snapshot.runId]?.value = next }
    }

    /**
     * P4-02 child onTerminal: only the pause is handled here (terminal states
     * are persisted by [finish] once the runner returns). WAITING_USER =
     * approval pause — the thread stays, its node becomes APPROVAL_REQUIRED so
     * the pause survives a restart. STEP_LIMIT / OUTPUT_LIMIT / GUARD_STOPPED
     * mark the run so [finish] maps it to TIMED_OUT instead of COMPLETED.
     */
    private suspend fun handleChildTerminal(runtimeRun: RuntimeRun, terminal: GenerationTerminal) {
        if (!isCurrentRunning(runtimeRun)) return
        when (terminal) {
            GenerationTerminal.WaitingUser -> {
                if (threadGraphEnabled()) {
                    threadGraphManager.pauseNode(runtimeRun.snapshot.runId)
                }
            }
            GenerationTerminal.StepLimit -> runtimeRun.stepLimited = true
            // Kernel guards, not completions: a truncated or duplicate-stopped
            // child must never be published as COMPLETED either.
            GenerationTerminal.OutputLimit -> runtimeRun.stepLimited = true
            is GenerationTerminal.GuardStopped -> runtimeRun.stepLimited = true
        }
    }

    private fun isCurrentRunning(runtimeRun: RuntimeRun): Boolean = synchronized(runtimeRun) {
        runs[runtimeRun.snapshot.runId] === runtimeRun &&
            runtimeRun.snapshot.status == SubAgentRunStatus.RUNNING
    }

    /** Drain messages delivered to a running thread into its generation (steer). */
    private suspend fun drainMailbox(runId: String): List<UIMessage> {
        val mailbox = mailboxes[runId] ?: return emptyList()
        val drained = mutableListOf<UIMessage>()
        while (true) {
            val message = mailbox.tryReceive().getOrNull() ?: break
            drained += message
        }
        return drained
    }

    private class FollowupRestore(
        val definition: SubAgentDefinition,
        val task: SubAgentTaskSpec,
        val previousAnswer: String,
    )

    /** Restore the thread's definition + task from memory or the persisted node. */
    private suspend fun restoreThreadForFollowup(
        threadId: String,
        live: SubAgentRun?,
        node: ThreadGraphManager.ThreadGraphState?,
    ): FollowupRestore? {
        if (live != null) {
            return FollowupRestore(
                definition = live.definition,
                task = live.task,
                previousAnswer = live.displayText,
            )
        }
        if (node != null) {
            val restore = threadGraphManager.restoreForFollowup(threadId) ?: return null
            return FollowupRestore(
                definition = restore.definition,
                task = restore.task,
                previousAnswer = node.finalAnswer,
            )
        }
        return null
    }

    /**
     * P4-02 — launch a followup generation on an existing (idle) thread. The
     * same threadId stays the thread's identity; the node returns to RUNNING
     * (write-ahead) and the child is seeded with its previous final answer.
     */
    private suspend fun launchFollowupGeneration(
        parentConversationId: Uuid,
        threadId: String,
        definition: SubAgentDefinition,
        task: SubAgentTaskSpec,
        parentTools: List<Tool>,
        parentRunId: String?,
        previousAnswer: String,
        writeContext: CoroutineContext,
        webMountScopeId: String,
        onRunFinished: ((String, String, Boolean) -> Unit)?,
        parentPolicy: ExecutionPolicy?,
    ) {
        val settings = settingsStore.settingsFlow.value
        val now = Instant.now().toEpochMilli()
        val allowedTools = parentTools
            .filterNot { it.name.startsWith("subagent_") }
            .filter { it.name in definition.toolAllowlist }
        val run = SubAgentRun(
            runId = threadId,
            parentConversationId = parentConversationId,
            definition = definition,
            task = task,
            status = SubAgentRunStatus.RUNNING,
            displayText = previousAnswer,
            transcriptPath = File(runDir, "$threadId.jsonl").absolutePath,
            startedAtMs = withContext(writeContext) {
                threadGraphManager.getState(threadId)?.startedAtMs
            } ?: now,
            updatedAtMs = now,
        )
        val runtimeRun = RuntimeRun(
            snapshot = run,
            writeContext = writeContext,
            webMountScopeId = webMountScopeId,
            onRunFinished = onRunFinished,
        )
        runs[threadId] = runtimeRun
        agentTaskStore.register(run.toAgentTaskSnapshot(), cancel = {
            cancel(threadId)
            true
        })
        appendEvent(runtimeRun, "followup_started", runToPayload(run))

        val liveText = MutableStateFlow(previousAnswer)
        val liveParts = MutableStateFlow<List<UIMessagePart>>(emptyList())
        liveTextFlows[threadId] = liveText
        livePartsFlows[threadId] = liveParts
        runStateFlows.getOrPut(threadId) { MutableStateFlow(run) }.value = run
        capLiveTextFlows()
        mailboxes[threadId] = Channel(Channel.UNLIMITED)

        // Write-ahead node + deliver queued messages (followup/send) before
        // the job launches — nothing waits in the void if the process dies.
        withContext(writeContext) {
            threadGraphManager.startNode(run = run, rootRunId = parentRunId ?: parentConversationId.toString())
            threadGraphManager.drainQueued(threadId)
                .filter { it.kind == "message" }
                .forEach { message ->
                    mailboxes[threadId]?.trySend(UIMessage.user(message.payload))
                }
        }

        if (!launchTurn(
                runtimeRun = runtimeRun,
                settings = settings,
                tools = scopedSubAgentTools(allowedTools),
                liveText = liveText,
                liveParts = liveParts,
                threadGraph = true,
                previousAnswer = previousAnswer,
                parentConversationId = parentConversationId,
                parentRunId = parentRunId,
                followup = true,
                parentPolicy = parentPolicy,
            )
        ) {
            finish(
                runtimeRun,
                SubAgentResult(
                    status = SubAgentRunStatus.FAILED,
                    error = "Sub-agent turn agent is not registered in the kernel.",
                ),
            )
        }
    }

    /**
     * Launch one generation turn of a thread as an [AgentRunner] run. The
     * threadId stays the durable thread identity; the turn gets a FRESH run
     * id so a followup never re-opens a run row that already reached a
     * write-once terminal state. Returns false when the turn agent is not
     * registered (the caller settles the thread as FAILED).
     */
    private fun launchTurn(
        runtimeRun: RuntimeRun,
        settings: Settings,
        tools: List<Tool>,
        liveText: MutableStateFlow<String>,
        liveParts: MutableStateFlow<List<UIMessagePart>>,
        threadGraph: Boolean,
        previousAnswer: String,
        parentConversationId: Uuid,
        parentRunId: String?,
        followup: Boolean,
        parentPolicy: ExecutionPolicy?,
    ): Boolean {
        val threadId = runtimeRun.snapshot.runId
        val turnRunId = AgentRunId.new()
        // P4-02: kernelRunId + onTerminal activate the kernel's durable path
        // for this child turn — child tool effects land in the unified
        // ToolEffectLedger under the per-turn run id. onTerminal only handles
        // the pause (approval); terminal states are persisted by finish()
        // once the turn's artifact lands.
        turnPayloads.register(
            turnRunId.value,
            SubAgentTurnPayloads.Payload(
                threadId = threadId,
                settings = settings,
                definition = runtimeRun.snapshot.definition,
                task = runtimeRun.snapshot.task,
                tools = tools,
                liveText = liveText,
                liveParts = liveParts,
                kernelRunId = if (threadGraph) turnRunId.value else null,
                onTerminal = if (threadGraph) {
                    { terminal -> handleChildTerminal(runtimeRun, terminal) }
                } else {
                    null
                },
                consumeSteerMessages = if (threadGraph) {
                    { drainMailbox(threadId) }
                } else {
                    { emptyList() }
                },
                previousAnswer = previousAnswer,
                parentPolicy = parentPolicy,
            ),
        )
        val handle = agentRunner.launch(
            SubAgentTurnDescriptor.ID,
            SubAgentTurnInput(
                threadId = threadId,
                parentConversationId = parentConversationId.toString(),
                parentRunId = parentRunId,
                definitionId = runtimeRun.snapshot.definition.id,
                objective = runtimeRun.snapshot.task.objective,
                followup = followup,
            ),
            requestedRunId = turnRunId,
        ).getOrElse {
            turnPayloads.remove(turnRunId.value)
            return false
        }
        runtimeRun.turnRunId = handle.runId
        runtimeRun.job = appScope.launch(Dispatchers.IO) {
            awaitTurnTerminal(runtimeRun, handle.runId)
        }
        return true
    }

    /**
     * Settle the thread from its turn's terminal (or approval-pause) state.
     * A completed/parked turn carries the [SubAgentResult] artifact; a FAILED
     * run carries the cause. The turn timeout is enforced here, at the
     * manager: on expiry the turn is cancelled and the thread settles as
     * TIMED_OUT (pendingTerminal tells the observer the manager owns the
     * terminal write for manager-initiated cancel/interrupt/timeout).
     */
    private suspend fun awaitTurnTerminal(runtimeRun: RuntimeRun, turnRunId: AgentRunId) {
        val threadId = runtimeRun.snapshot.runId
        try {
            val snapshot = try {
                withTimeout(runtimeRun.snapshot.definition.timeoutMs) {
                    agentRunner.observe(turnRunId).first { it.status.isTerminal || it.status.isPause }
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                runtimeRun.pendingTerminal = SubAgentRunStatus.TIMED_OUT
                agentRunner.cancel(turnRunId)
                finish(
                    runtimeRun,
                    SubAgentResult(
                        status = SubAgentRunStatus.TIMED_OUT,
                        error = timeout.message ?: "Subagent turn timed out.",
                    ),
                    displayText = liveTextFlows[threadId]?.value.orEmpty(),
                )
                return
            }
            val displayText = liveTextFlows[threadId]?.value.orEmpty()
            when {
                // COMPLETED and a realigned approval pause both carry the
                // result artifact (the runner keeps it on the snapshot when
                // the terminal CAS is rejected by a persisted pause).
                snapshot.artifact is SubAgentResult ->
                    finish(runtimeRun, snapshot.artifact as SubAgentResult, displayText)

                snapshot.status == RunStatus.CANCELLED && runtimeRun.pendingTerminal != null -> Unit

                snapshot.status == RunStatus.WAITING_USER ->
                    finish(
                        runtimeRun,
                        SubAgentResult(
                            status = SubAgentRunStatus.APPROVAL_REQUIRED,
                            summary = "Subagent requested approval.",
                            risks = listOf("Subagent cannot self-approve sensitive tools."),
                        ),
                        displayText,
                    )

                // Defensive: a turn cancelled without the manager initiating
                // it must not leave the thread RUNNING forever.
                snapshot.status == RunStatus.CANCELLED ->
                    finish(
                        runtimeRun,
                        SubAgentResult(
                            status = SubAgentRunStatus.CANCELLED,
                            summary = "Subagent run was cancelled.",
                        ),
                        displayText,
                    )

                else ->
                    finish(
                        runtimeRun,
                        SubAgentResult(
                            status = SubAgentRunStatus.FAILED,
                            error = snapshot.error?.message ?: "Subagent turn ended ${snapshot.status}",
                        ),
                        displayText,
                    )
            }
        } finally {
            turnPayloads.remove(turnRunId.value)
        }
    }

    private fun readMissingRun(runId: String): JsonObject {
        val transcript = File(runDir, "$runId.jsonl")
        return if (transcript.exists()) {
            buildJsonObject {
                put("status", SubAgentRunStatus.INTERRUPTED.name.lowercase())
                put("run_id", runId)
                put("transcript_available", true)
                put("error", "Subagent run is no longer active in memory.")
            }
        } else {
            errorPayload("not_found", "Unknown subagent run_id: $runId")
        }
    }

    private fun appendEvent(runtimeRun: RuntimeRun, event: String, payload: JsonObject) {
        val line = buildJsonObject {
            put("event", event)
            put("created_at_ms", Instant.now().toEpochMilli())
            put("payload", payload)
        }
        File(runtimeRun.snapshot.transcriptPath).appendText(line.toString() + "\n")
    }

    private fun runToPayload(run: SubAgentRun, includeDisplayText: Boolean = false): JsonObject =
        subAgentRunToPayload(run, json, includeDisplayText)

    private fun errorPayload(code: String, message: String): JsonObject = buildJsonObject {
        put("status", "failed")
        put("error", message)
        put("code", code)
    }

    private fun SubAgentRun.toAgentTaskSnapshot() = AgentTaskSnapshot(
        taskId = runId,
        type = "subagent",
        title = definition.name,
        sourceConversationId = parentConversationId.toString(),
        status = status.toAgentTaskStatus(),
        queueState = status.toAgentTaskStatus().toQueueState("subagent"),
        outputPath = transcriptPath,
        outputRef = AgentTaskOutputRef(
            type = "transcript",
            path = transcriptPath,
            exists = File(transcriptPath).exists(),
        ),
        retryPolicy = AgentTaskRetryPolicy(
            retryable = status == SubAgentRunStatus.FAILED,
            requiresApproval = false,
            maxRetries = 1,
            reason = "Sub Agent retry starts a new isolated run from the original task spec.",
        ),
        sourceToolName = "subagent_start",
        // A thread followup deliberately retains its original startedAtMs, but the task-store
        // row represents this generation's activation so UI observers can distinguish it.
        createdAtMs = updatedAtMs,
        updatedAtMs = updatedAtMs,
        cancelCapability = status.running,
        summary = task.objective.take(1_000),
    )

    private fun SubAgentRunStatus.toAgentTaskStatus(): AgentTaskStatus = when (this) {
        SubAgentRunStatus.RUNNING,
        SubAgentRunStatus.APPROVAL_REQUIRED -> AgentTaskStatus.RUNNING
        SubAgentRunStatus.COMPLETED -> AgentTaskStatus.COMPLETED
        SubAgentRunStatus.FAILED -> AgentTaskStatus.FAILED
        SubAgentRunStatus.CANCELLED -> AgentTaskStatus.CANCELLED
        SubAgentRunStatus.TIMED_OUT -> AgentTaskStatus.TIMED_OUT
        SubAgentRunStatus.INTERRUPTED -> AgentTaskStatus.INTERRUPTED
    }

    private class RuntimeRun(
        @Volatile var snapshot: SubAgentRun,
        /** Awaits the current turn's terminal/pause and settles the thread. */
        @Volatile var job: Job? = null,
        /** Run id of the in-flight generation turn (null between turns). */
        @Volatile var turnRunId: AgentRunId? = null,
        /**
         * Set by manager-initiated cancel/interrupt/timeout BEFORE the turn
         * is cancelled, so the awaiter knows the manager owns the terminal
         * write (and which status) when the runner reports CANCELLED.
         */
        @Volatile var pendingTerminal: SubAgentRunStatus? = null,
        /** P1-03: the generator reported StepLimit for this run (see [finish]). */
        @Volatile var stepLimited: Boolean = false,
        val writeContext: CoroutineContext = EmptyCoroutineContext,
        val webMountScopeId: String = Uuid.random().toString(),
        val onRunFinished: ((String, String, Boolean) -> Unit)? = null,
    )

    private fun SubAgentDefinition.isHistoryReader(): Boolean =
        id == "historian" ||
            toolAllowlist.any { it in HISTORY_FULL_READ_TOOLS }

    private companion object {
        val HISTORY_FULL_READ_TOOLS = setOf("session_read", "session_expand")

        /** Soft cap on how many run-text flows we keep around. Plenty for normal use. */
        const val LIVE_TEXT_CAP = 64

        /** send_message payload cap (per message). */
        const val MAX_SEND_MESSAGE_CHARS = 4_000
    }
}

fun subAgentRunToPayload(
    run: SubAgentRun,
    json: Json,
    includeDisplayText: Boolean = false,
): JsonObject = buildJsonObject {
    put("status", run.status.name.lowercase())
    put("run_id", run.runId)
    put("subagent_id", run.definition.id)
    put("subagent_name", run.definition.name)
    put("dynamic", run.definition.dynamic)
    put("task_objective", run.task.objective.take(1_000))
    put("started_at_ms", run.startedAtMs)
    put("updated_at_ms", run.updatedAtMs)
    run.task.sessionGrantId.takeIf { it.isNotBlank() }?.let { put("session_grant_id", it) }
    run.result?.let { put("result", json.encodeToString(it)) }
    if (run.displayText.isNotBlank()) {
        put("display_text_chars", run.displayText.length)
        if (includeDisplayText) put("display_text", run.displayText)
    }
}
