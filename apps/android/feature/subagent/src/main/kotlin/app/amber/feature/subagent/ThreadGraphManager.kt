package app.amber.feature.subagent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * P4-02 (capability parity plan): persisted thread graph operations.
 *
 * Pure decision + persistence layer over [ThreadGraphStore] (no coroutine
 * jobs, no UI). SubAgentManager delegates every thread-graph touch point here
 * so cold-start recovery, cancellation and result delivery share one code
 * path and stay unit-testable with a fake store.
 *
 * Semantics:
 *  - A thread node is persisted RUNNING *before* its generation job launches
 *    (write-ahead: a crash right after subagent_start leaves a recoverable
 *    node, never a phantom in-memory run).
 *  - A node still RUNNING without a live in-process run is a process-death
 *    victim: reads reconcile it to INTERRUPTED (cold-start recovery of
 *    waiting runs — plan §P4-02 "冷启动后可恢复等待、取消和已完成结果").
 *  - Messages move queued → delivered → persisted; delivered messages are
 *    promoted to persisted only when the thread's result for that turn lands,
 *    so nothing silently disappears between dequeue and result persistence.
 *  - Only terminal statuses write a [ThreadResultRecord]; APPROVAL_REQUIRED
 *    (pause) persists the node only.
 */
class ThreadGraphManager(
    private val store: ThreadGraphStore,
    private val json: Json,
) {

    companion object {
        /** Root -> child -> grandchild is the complete supported graph. */
        const val MAX_THREAD_DEPTH = 2
    }

    data class ThreadStartContext(
        val rootRunId: String,
        val parentThreadId: String?,
    )

    class ThreadGraphDepthLimitException(
        val parentThreadId: String,
        val parentDepth: Int,
    ) : IllegalStateException(
        "Thread depth limit reached: parent $parentThreadId is at depth " +
            "$parentDepth; maximum supported depth is $MAX_THREAD_DEPTH.",
    )

    /** UI-facing snapshot of a persisted thread; null when unknown/flag-off. */
    data class ThreadGraphState(
        val status: SubAgentRunStatus,
        val startedAtMs: Long,
        val updatedAtMs: Long,
        val finalAnswer: String = "",
    )

    /** Definition + task restored from a persisted node (followup_task). */
    data class ThreadRestore(
        val definition: SubAgentDefinition,
        val task: SubAgentTaskSpec,
    )

    // ── lifecycle ────────────────────────────────────────────────────────

    /**
     * Resolve the parent graph context before the caller registers/launches a
     * run. A parentRunId that is not a persisted thread is the root run
     * identity, so the first child is depth 1. A persisted thread id becomes
     * parentThreadId and inherits its rootRunId.
     */
    suspend fun prepareStart(
        parentRunId: String?,
        fallbackRootRunId: String,
    ): ThreadStartContext {
        val parent = parentRunId?.let { store.getNode(it) }
        val parentThreadId = parent?.threadId
        if (parentThreadId != null) checkParentDepth(parentThreadId)
        return ThreadStartContext(
            rootRunId = parent?.rootRunId ?: parentRunId ?: fallbackRootRunId,
            parentThreadId = parentThreadId,
        )
    }

    /** Write-ahead node persist, called before the generation job launches. */
    suspend fun startNode(
        run: SubAgentRun,
        rootRunId: String,
        parentThreadId: String? = null,
    ) {
        // Followup turns reuse the existing graph identity and must retain the
        // original parent/root instead of resetting the node to a root child.
        val existing = store.getNode(run.runId)
        val effectiveParentThreadId = existing?.parentThreadId ?: parentThreadId
        val effectiveRootRunId = existing?.rootRunId ?: rootRunId
        if (effectiveParentThreadId != null) {
            checkParentDepth(effectiveParentThreadId, childThreadId = run.runId)
        }
        store.upsertNode(
            ThreadNodeRecord(
                threadId = run.runId,
                parentThreadId = effectiveParentThreadId,
                rootRunId = effectiveRootRunId,
                conversationId = run.parentConversationId.toString(),
                status = SubAgentRunStatus.RUNNING.name,
                task = encodeTaskPayload(run.definition, run.task),
                startedAtMs = run.startedAtMs,
                updatedAtMs = run.startedAtMs,
            )
        )
    }

    /**
     * Persist the node status + terminal result. [result.status] must be the
     * final status of the run (may differ from the runner's own result status
     * when the caller overrode it, e.g. step-limit mapping).
     */
    suspend fun finishNode(
        runId: String,
        status: SubAgentRunStatus,
        result: SubAgentResult,
        displayText: String,
    ) {
        val node = store.getNode(runId) ?: return // flag flipped mid-run; nothing to persist
        val nowMs = Instant.now().toEpochMilli()
        store.upsertNode(
            node.copy(
                status = status.name,
                updatedAtMs = nowMs,
            )
        )
        if (status.isTerminalThreadStatus()) {
            store.upsertResult(
                ThreadResultRecord(
                    threadId = runId,
                    finalAnswer = displayText,
                    artifactsJson = json.encodeToString(
                        ThreadArtifacts(
                            findings = result.findings,
                            evidence = result.evidence,
                            risks = result.risks,
                            recommendedNextSteps = result.recommendedNextSteps,
                            confidence = result.confidence,
                            error = result.error,
                        )
                    ),
                    terminalReason = status.name.lowercase(),
                    finishedAtMs = nowMs,
                )
            )
            // The consuming turn's result landed: delivered messages are now
            // durable with the answer that reflects them.
            store.markDeliveredMessagesPersisted(runId)
        }
    }

    /** Pause (approval wait): persist the node as APPROVAL_REQUIRED, no result. */
    suspend fun pauseNode(runId: String) {
        val node = store.getNode(runId) ?: return
        store.upsertNode(node.copy(status = SubAgentRunStatus.APPROVAL_REQUIRED.name))
    }

    /**
     * Definition + task restored from a persisted node, so followup_task can
     * continue an idle thread after a cold start.
     */
    suspend fun restoreForFollowup(threadId: String): ThreadRestore? {
        val node = store.getNode(threadId) ?: return null
        val (definition, task) = decodeTaskPayload(node.task) ?: return null
        return if (definition != null && task != null) ThreadRestore(definition, task) else null
    }

    /** Cold-start reconciliation + payload for a thread with no live run.
     * A persisted RUNNING node is a process-death victim → INTERRUPTED; any
     * DELIVERED mailbox records from an uncompleted turn return to QUEUED.
     * Returns null when the thread is unknown to the store.
     */
    suspend fun restorePayload(threadId: String): JsonObject? {
        val node = store.getNode(threadId) ?: return null
        val status = runCatching { SubAgentRunStatus.valueOf(node.status) }
            .getOrDefault(SubAgentRunStatus.INTERRUPTED)
        val effective = when (status) {
            SubAgentRunStatus.RUNNING -> {
                val interrupted = markInterrupted(threadId, node)
                store.requeueDeliveredMessages(threadId)
                interrupted
            }
            SubAgentRunStatus.INTERRUPTED,
            SubAgentRunStatus.APPROVAL_REQUIRED,
            -> {
                // No live owner can finish the consuming turn after a cold
                // start; make its claimed mailbox visible to the next turn.
                store.requeueDeliveredMessages(threadId)
                status
            }
            else -> status
        }
        return persistedPayload(node, effective)
    }

    /** Interrupt a thread that is not (or no longer) running in-process. */
    suspend fun interruptPersisted(threadId: String, finalAnswer: String): JsonObject? {
        val node = store.getNode(threadId) ?: return null
        val status = runCatching { SubAgentRunStatus.valueOf(node.status) }
            .getOrDefault(SubAgentRunStatus.INTERRUPTED)
        if (status == SubAgentRunStatus.RUNNING) {
            store.upsertNode(node.copy(status = SubAgentRunStatus.INTERRUPTED.name))
            store.upsertResult(
                ThreadResultRecord(
                    threadId = threadId,
                    finalAnswer = finalAnswer,
                    artifactsJson = "{}",
                    terminalReason = SubAgentRunStatus.INTERRUPTED.name.lowercase(),
                    finishedAtMs = Instant.now().toEpochMilli(),
                )
            )
            store.markDeliveredMessagesPersisted(threadId)
            return persistedPayload(node.copy(status = SubAgentRunStatus.INTERRUPTED.name), SubAgentRunStatus.INTERRUPTED)
        }
        return persistedPayload(node, status)
    }

    /**
     * Cancel a thread with no live run (cold-start cancellation): a stale
     * RUNNING / INTERRUPTED / APPROVAL_REQUIRED node becomes CANCELLED with a
     * terminal result.
     */
    suspend fun cancelPersisted(threadId: String): JsonObject? {
        val node = store.getNode(threadId) ?: return null
        val status = runCatching { SubAgentRunStatus.valueOf(node.status) }
            .getOrDefault(SubAgentRunStatus.INTERRUPTED)
        if (status == SubAgentRunStatus.CANCELLED) return persistedPayload(node, status)
        if (
            status != SubAgentRunStatus.RUNNING &&
            status != SubAgentRunStatus.INTERRUPTED &&
            status != SubAgentRunStatus.APPROVAL_REQUIRED
        ) return null
        val cancelled = node.copy(status = SubAgentRunStatus.CANCELLED.name)
        store.upsertNode(cancelled)
        store.upsertResult(
            ThreadResultRecord(
                threadId = threadId,
                finalAnswer = "",
                artifactsJson = "{}",
                terminalReason = SubAgentRunStatus.CANCELLED.name.lowercase(),
                finishedAtMs = Instant.now().toEpochMilli(),
            )
        )
        store.markDeliveredMessagesPersisted(threadId)
        return persistedPayload(cancelled, SubAgentRunStatus.CANCELLED)
    }

    // ── messages ─────────────────────────────────────────────────────────

    /** Enqueue a followup task (QUEUED). Returns the persisted record. */
    suspend fun enqueueFollowup(threadId: String, task: SubAgentTaskSpec): ThreadMessageRecord {
        val record = ThreadMessageRecord(
            messageId = UUID.randomUUID().toString(),
            threadId = threadId,
            sender = "parent",
            recipient = "thread:$threadId",
            kind = "followup",
            payload = json.encodeToString(SubAgentTaskSpec.serializer(), task),
            payloadDigest = payloadDigest(json.encodeToString(SubAgentTaskSpec.serializer(), task)),
            deliveryState = ThreadDeliveryState.QUEUED.name,
            createdAtMs = Instant.now().toEpochMilli(),
            updatedAtMs = Instant.now().toEpochMilli(),
        )
        store.enqueueMessage(record)
        return record
    }

    /** Enqueue a runtime message (QUEUED). Returns the persisted record. */
    suspend fun enqueueMessage(threadId: String, text: String): ThreadMessageRecord {
        val nowMs = Instant.now().toEpochMilli()
        val record = ThreadMessageRecord(
            messageId = UUID.randomUUID().toString(),
            threadId = threadId,
            sender = "parent",
            recipient = "thread:$threadId",
            kind = "message",
            payload = text,
            payloadDigest = payloadDigest(text),
            deliveryState = ThreadDeliveryState.QUEUED.name,
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
        )
        store.enqueueMessage(record)
        return record
    }

    /** Mark one message delivered (handed to a live generation). */
    suspend fun markDelivered(messageId: String) {
        store.markMessageDelivered(messageId)
    }

    /** Atomically claim QUEUED messages when the thread next runs. */
    suspend fun drainQueued(threadId: String): List<ThreadMessageRecord> =
        store.claimQueuedMessages(threadId)

    // ── queries ──────────────────────────────────────────────────────────

    suspend fun listByRootRun(rootRunId: String): List<ThreadNodeRecord> =
        store.listNodesByRootRun(rootRunId)

    suspend fun getState(threadId: String): ThreadGraphState? {
        val node = store.getNode(threadId) ?: return null
        val status = runCatching { SubAgentRunStatus.valueOf(node.status) }
            .getOrDefault(SubAgentRunStatus.INTERRUPTED)
        val result = store.getResult(threadId)
        return ThreadGraphState(
            status = status,
            startedAtMs = node.startedAtMs,
            updatedAtMs = node.updatedAtMs,
            finalAnswer = result?.finalAnswer.orEmpty(),
        )
    }

    // ── payload / helpers ────────────────────────────────────────────────

    /**
     * Read payload in the same shape as [subAgentRunToPayload] so the parent
     * model consumes persisted threads exactly like in-memory ones — the
     * child's final answer comes back to the parent run through this payload.
     */
    private suspend fun persistedPayload(
        node: ThreadNodeRecord,
        status: SubAgentRunStatus,
    ): JsonObject? {
        val (definition, task) = decodeTaskPayload(node.task) ?: (null to null)
        val result = store.getResult(node.threadId)
        return buildJsonObject {
            put("status", status.name.lowercase())
            put("run_id", node.threadId)
            put("subagent_id", definition?.id ?: "unknown")
            put("subagent_name", definition?.name ?: "unknown")
            put("dynamic", definition?.dynamic == true)
            put("task_objective", task?.objective.orEmpty().take(1_000))
            put("started_at_ms", node.startedAtMs)
            put("updated_at_ms", node.updatedAtMs)
            if (result != null && status.isTerminalThreadStatus()) {
                val artifacts = runCatching {
                    json.decodeFromString<ThreadArtifacts>(result.artifactsJson)
                }.getOrDefault(ThreadArtifacts())
                put(
                    "result",
                    json.encodeToString(
                        SubAgentResult(
                            status = status,
                            summary = result.finalAnswer,
                            findings = artifacts.findings,
                            evidence = artifacts.evidence,
                            risks = artifacts.risks,
                            confidence = artifacts.confidence,
                            recommendedNextSteps = artifacts.recommendedNextSteps,
                            error = artifacts.error,
                        )
                    )
                )
            }
        }
    }

    private fun decodeTaskPayload(taskJson: String): Pair<SubAgentDefinition?, SubAgentTaskSpec?>? {
        val taskPayload = runCatching {
            json.parseToJsonElement(taskJson) as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return null
        val definition = taskPayload["definition"]
            ?.let { runCatching { json.decodeFromString<SubAgentDefinition>(it.toString()) }.getOrNull() }
        val task = taskPayload["task"]
            ?.let { runCatching { json.decodeFromString<SubAgentTaskSpec>(it.toString()) }.getOrNull() }
        return definition to task
    }

    private suspend fun markInterrupted(threadId: String, node: ThreadNodeRecord): SubAgentRunStatus {
        store.upsertNode(node.copy(status = SubAgentRunStatus.INTERRUPTED.name))
        return SubAgentRunStatus.INTERRUPTED
    }

    private suspend fun checkParentDepth(parentThreadId: String, childThreadId: String? = null) {
        require(parentThreadId != childThreadId) {
            "A thread cannot be its own parent: $parentThreadId"
        }
        val parentDepth = depthOfNode(parentThreadId)
        if (parentDepth >= MAX_THREAD_DEPTH) {
            throw ThreadGraphDepthLimitException(parentThreadId, parentDepth)
        }
    }

    private suspend fun depthOfNode(threadId: String): Int {
        var currentId = threadId
        var depth = 0
        val visited = mutableSetOf<String>()
        while (true) {
            require(visited.add(currentId)) {
                "Thread graph contains a parent cycle at $currentId"
            }
            val node = store.getNode(currentId)
                ?: error("Unknown parent thread: $currentId")
            depth += 1
            val parentId = node.parentThreadId ?: return depth
            currentId = parentId
        }
    }

    private fun encodeTaskPayload(definition: SubAgentDefinition, task: SubAgentTaskSpec): String =
        buildJsonObject {
            put("definition", json.encodeToJsonElement(SubAgentDefinition.serializer(), definition))
            put("task", json.encodeToJsonElement(SubAgentTaskSpec.serializer(), task))
        }.toString()

    private fun SubAgentRunStatus.isTerminalThreadStatus(): Boolean = when (this) {
        SubAgentRunStatus.COMPLETED,
        SubAgentRunStatus.FAILED,
        SubAgentRunStatus.CANCELLED,
        SubAgentRunStatus.TIMED_OUT,
        SubAgentRunStatus.INTERRUPTED,
        -> true

        SubAgentRunStatus.RUNNING,
        SubAgentRunStatus.APPROVAL_REQUIRED,
        -> false
    }

    /** Stable SHA-256 hex digest of a message payload. */
    internal fun payloadDigest(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
