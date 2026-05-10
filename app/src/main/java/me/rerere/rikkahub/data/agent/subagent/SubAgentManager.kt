package me.rerere.rikkahub.data.agent.subagent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.agent.history.SessionAccessGrantStore
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.agent.task.AgentTaskSnapshot
import me.rerere.rikkahub.data.agent.task.AgentTaskOutputRef
import me.rerere.rikkahub.data.agent.task.AgentTaskRetryPolicy
import me.rerere.rikkahub.data.agent.task.AgentTaskStatus
import me.rerere.rikkahub.data.agent.task.AgentTaskStore
import me.rerere.rikkahub.data.agent.task.toQueueState
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

class SubAgentManager(
    context: Context,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val runner: SubAgentRunner,
    private val agentTaskStore: AgentTaskStore,
    private val sessionAccessGrantStore: SessionAccessGrantStore,
) {
    private val runDir = File(context.filesDir, "amberagent/subagents/runs").also { it.mkdirs() }
    private val runs = ConcurrentHashMap<String, RuntimeRun>()

    /**
     * Per-run streaming text flows. The runner writes the assistant's evolving response here as
     * generation chunks arrive; UI subscribes via [liveTextFlow]. Entries are kept after the run
     * finishes so a freshly-opened sheet can display the final text; cleaned up via [LIVE_TEXT_CAP].
     */
    private val liveTextFlows = ConcurrentHashMap<String, MutableStateFlow<String>>()

    suspend fun start(
        parentConversationId: Uuid,
        input: JsonObject,
        parentTools: List<Tool>,
    ): JsonObject = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.value
        val subAgentSetting = settings.agentRuntime.subAgent
        if (!subAgentSetting.enabled) {
            return@withContext errorPayload("subagent_disabled", "Subagent experimental mode is disabled.")
        }

        val running = runs.values.count { it.snapshot.status.running }
        if (running >= subAgentSetting.maxConcurrentRuns.coerceAtLeast(1)) {
            return@withContext errorPayload("too_many_subagents", "Subagent concurrency limit reached.")
        }

        val parentToolNames = parentTools.map { it.name }.toSet()
        val task = runCatching { SubAgentValidator.parseTask(input) }
            .getOrElse { return@withContext errorPayload("invalid_task", it.message ?: it.toString()) }
        val definition = runCatching {
            SubAgentValidator.resolveDefinition(input, subAgentSetting, parentToolNames).definition
        }.getOrElse {
            return@withContext errorPayload("invalid_subagent", it.message ?: it.toString())
        }
        if (definition.dynamic) {
            val runningDynamic = runs.values.count { it.snapshot.status.running && it.snapshot.definition.dynamic }
            if (runningDynamic >= DEFAULT_SUB_AGENT_MAX_CONCURRENT_RUNS) {
                return@withContext errorPayload(
                    "too_many_dynamic_subagents",
                    "Dynamic subagent per-turn limit reached."
                )
            }
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
        if (effectiveDefinition.toolAllowlist.isEmpty()) {
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

        val allowedTools = parentTools
            .filterNot { it.name.startsWith("subagent_") }
            .filter { it.name in effectiveDefinition.toolAllowlist }
            .map { tool ->
                if (tool.name in HISTORY_FULL_READ_TOOLS && historyGrant != null) {
                    tool.copy(needsApproval = false, allowsAutoApproval = true)
                } else {
                    tool
                }
            }

        val now = Instant.now().toEpochMilli()
        val runId = Uuid.random().toString()
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
        val runtimeRun = RuntimeRun(run)
        runs[runId] = runtimeRun
        agentTaskStore.register(run.toAgentTaskSnapshot(), cancel = {
            cancel(runId)
            true
        })
        appendEvent(runtimeRun, "started", runToPayload(run))

        // Live text flow for UI subscribers — created BEFORE the runner starts so a sheet opened
        // immediately after subagent_start sees the same flow that will be written to.
        val liveText = MutableStateFlow("")
        liveTextFlows[runId] = liveText
        capLiveTextFlows()

        runtimeRun.job = appScope.launch(Dispatchers.IO) {
            val result = runCatching {
                withTimeout(definition.timeoutMs) {
                    runner.run(settings, effectiveDefinition, effectiveTask, allowedTools, liveText)
                }
            }.fold(
                onSuccess = { it },
                onFailure = { error ->
                    val timedOut = error is kotlinx.coroutines.TimeoutCancellationException
                    SubAgentResult(
                        status = if (timedOut) SubAgentRunStatus.TIMED_OUT else SubAgentRunStatus.FAILED,
                        error = error.message ?: error::class.java.simpleName,
                    )
                }
            )
            finish(runId, result)
        }

        runToPayload(run)
    }

    suspend fun read(runId: String): JsonObject = withContext(Dispatchers.IO) {
        val run = runs[runId]?.snapshot ?: return@withContext readMissingRun(runId)
        runToPayload(run)
    }

    suspend fun wait(runId: String, waitTimeoutMs: Long): JsonObject = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + waitTimeoutMs.coerceIn(0, 60_000L)
        while (System.currentTimeMillis() < deadline) {
            val current = runs[runId]?.snapshot ?: return@withContext readMissingRun(runId)
            if (!current.status.running) return@withContext runToPayload(current)
            delay(200)
        }
        read(runId)
    }

    suspend fun cancel(runId: String): JsonObject = withContext(Dispatchers.IO) {
        val runtimeRun = runs[runId] ?: return@withContext readMissingRun(runId)
        runtimeRun.job?.cancel()
        finish(
            runId,
            SubAgentResult(
                status = SubAgentRunStatus.CANCELLED,
                summary = "Subagent run was cancelled.",
            )
        )
        runToPayload(runtimeRun.snapshot)
    }

    fun listBuiltIns(): List<SubAgentDefinition> {
        val setting = settingsStore.settingsFlow.value.agentRuntime.subAgent
        val builtIns = SubAgentDefinitions.builtIns.map { it.applyOverride(setting.overrides[it.id]) }
        return builtIns + setting.customDefinitions
    }

    /**
     * UI-facing live stream of a subagent's accumulating assistant text. Null = unknown runId.
     *
     * **Completion signal**: this flow does NOT carry a "done" marker. UI should observe
     * [snapshot] (or its status) in parallel; when `status.running == false`, the latest text
     * is the final text. A `combine(liveTextFlow, snapshotFlow)` pattern works well.
     */
    fun liveTextFlow(runId: String): StateFlow<String>? = liveTextFlows[runId]?.asStateFlow()

    /** Snapshot of a known run, or null if it was never started or was already evicted. */
    fun snapshot(runId: String): SubAgentRun? = runs[runId]?.snapshot

    /** True iff Model Council experimental mode is currently on. Used by SubAgentTools to
     *  decide whether to advertise @council alongside the regular subagent roster. */
    fun isModelCouncilEnabled(): Boolean =
        settingsStore.settingsFlow.value.agentRuntime.modelCouncil.enabled

    /**
     * Keep [liveTextFlows] bounded. Iterate the flow keys (not [runs].values) so orphaned
     * entries — flows whose run snapshot was already evicted elsewhere — are also reclaimed.
     * Active runs (status.running) are skipped: the runner is still writing to them.
     *
     * Race note: two concurrent [start] calls can both pass the size check and both insert
     * before this runs, so the cap is soft. Worst case: temporarily 65–66 entries, never an
     * eviction of a still-active run. Acceptable.
     */
    private fun capLiveTextFlows() {
        if (liveTextFlows.size <= LIVE_TEXT_CAP) return
        // Build (runId, lastUpdate) for every live-text key and pick the oldest non-running ones.
        val candidates = liveTextFlows.keys.mapNotNull { id ->
            val snap = runs[id]?.snapshot
            when {
                snap == null -> id to 0L  // orphan: definitely evictable, sort earliest
                snap.status.running -> null  // active: keep
                else -> id to snap.updatedAtMs
            }
        }.sortedBy { it.second }
        val toDrop = liveTextFlows.size - LIVE_TEXT_CAP
        candidates.take(toDrop).forEach { (id, _) -> liveTextFlows.remove(id) }
    }

    fun runtimeSummary(): JsonObject {
        val setting = settingsStore.settingsFlow.value.agentRuntime.subAgent
        return buildJsonObject {
            put("enabled", setting.enabled)
            put("allow_dynamic_subagents", setting.allowDynamicSubAgents)
            put("max_concurrent_runs", setting.maxConcurrentRuns)
            put("dynamic_run_limit", DEFAULT_SUB_AGENT_MAX_CONCURRENT_RUNS)
            put("max_depth", 1)
            put("timeout_ms", setting.timeoutMs)
            put("max_turns", setting.maxTurns)
            put("output_budget_chars", setting.outputBudgetChars)
            put("running", runs.values.count { it.snapshot.status.running })
        }
    }

    private fun finish(runId: String, result: SubAgentResult) {
        val runtimeRun = runs[runId] ?: return
        val current = runtimeRun.snapshot
        if (!current.status.running) return
        val status = result.status
        val next = current.copy(
            status = status,
            result = result,
            updatedAtMs = Instant.now().toEpochMilli(),
        )
        runtimeRun.snapshot = next
        appScope.launch(Dispatchers.IO) {
            agentTaskStore.update(
                taskId = runId,
                status = status.toAgentTaskStatus(),
                summary = result.summary.ifBlank { result.findings.joinToString("; ").take(1_000) },
                error = result.error.takeIf { it.isNotBlank() },
                cancelCapability = false,
            )
        }
        appendEvent(runtimeRun, "finished", runToPayload(next))
    }

    private fun readMissingRun(runId: String): JsonObject {
        val transcript = File(runDir, "$runId.jsonl")
        return if (transcript.exists()) {
            buildJsonObject {
                put("status", SubAgentRunStatus.INTERRUPTED.name.lowercase())
                put("run_id", runId)
                put("transcript_path", transcript.absolutePath)
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

    private fun runToPayload(run: SubAgentRun): JsonObject = buildJsonObject {
        put("status", run.status.name.lowercase())
        put("run_id", run.runId)
        put("subagent_id", run.definition.id)
        put("subagent_name", run.definition.name)
        put("dynamic", run.definition.dynamic)
        put("transcript_path", run.transcriptPath)
        put("started_at_ms", run.startedAtMs)
        put("updated_at_ms", run.updatedAtMs)
        put("definition", json.encodeToString(run.definition))
        put("task", json.encodeToString(run.task))
        run.task.sessionGrantId.takeIf { it.isNotBlank() }?.let { put("session_grant_id", it) }
        run.result?.let { put("result", json.encodeToString(it)) }
    }

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
        createdAtMs = startedAtMs,
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
        @Volatile var job: Job? = null,
    )

    private fun SubAgentDefinition.isHistoryReader(): Boolean =
        id == "historian" ||
            toolAllowlist.any { it in HISTORY_FULL_READ_TOOLS }

    private companion object {
        val HISTORY_FULL_READ_TOOLS = setOf("session_read", "session_expand")

        /** Soft cap on how many run-text flows we keep around. Plenty for normal use. */
        const val LIVE_TEXT_CAP = 64
    }
}
