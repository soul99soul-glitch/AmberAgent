package app.amber.feature.runtime

import app.amber.agent.data.db.dao.RunTerminalDAO
import app.amber.agent.data.db.dao.ToolEffectDAO
import app.amber.agent.data.db.entity.ToolEffectEntity
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.tools.ToolEffectClass
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException

private const val TAG = "ToolEffectLedger"

/** Schema version of the ledger table — surfaced on the debug page. */
const val TOOL_EFFECT_LEDGER_SCHEMA_VERSION = 1

/** Stable effect states of the write-ahead protocol (P1-02). */
enum class ToolEffectStatus {
    /** Validated + digest computed, persisted before approval/execution. */
    PREPARED,

    /** Approval done, execution started (or safe to retry after crash). */
    STARTED,

    /** Execution returned; the tool result is available for replay. */
    FINISHED,

    /** Execution failed before (or without) a usable result. */
    FAILED,

    /**
     * A non-idempotent effect was started but never finished; the external
     * side effect may or may not have happened. Needs user confirmation.
     */
    OUTCOME_UNKNOWN,

    /** User confirmed retry or abandon; the effect is no longer pending. */
    RECONCILED,
}

data class ToolEffect(
    val effectId: String,
    val runId: String?,
    val turnId: Int?,
    val toolCallId: String,
    val toolName: String,
    val argsDigest: String,
    val approvalDigest: String?,
    val effectClass: ToolEffectClass,
    val status: ToolEffectStatus,
    val startedAtMs: Long,
    val finishedAtMs: Long?,
    val resultSummary: String?,
    val resultPayload: String?,
    val errorCategory: String?,
    val messagePersistenceCursor: String?,
) {
    companion object {
        fun from(entity: ToolEffectEntity): ToolEffect = ToolEffect(
            effectId = entity.effectId,
            runId = entity.runId,
            turnId = entity.turnId,
            toolCallId = entity.toolCallId,
            toolName = entity.toolName,
            argsDigest = entity.argsDigest,
            approvalDigest = entity.approvalDigest,
            effectClass = runCatching { ToolEffectClass.valueOf(entity.effectClass) }
                .getOrDefault(ToolEffectClass.NON_IDEMPOTENT_WRITE),
            status = runCatching { ToolEffectStatus.valueOf(entity.status) }
                .getOrDefault(ToolEffectStatus.OUTCOME_UNKNOWN),
            startedAtMs = entity.startedAtMs,
            finishedAtMs = entity.finishedAtMs,
            resultSummary = entity.resultSummary,
            resultPayload = entity.resultPayload,
            errorCategory = entity.errorCategory,
            messagePersistenceCursor = entity.messagePersistenceCursor,
        )
    }
}

/**
 * Durable Tool Effect Ledger (P1-02) — write-ahead protocol:
 *
 *  1. validate + digest args            (prepare)
 *  2. persist PREPARED                  (prepare)
 *  3. after approval persist STARTED    (markStarted, binds approval digest)
 *  4. execute
 *  5. persist FINISHED / FAILED         (finish / fail)
 *  6. tool result written to the conversation (caller)
 *  7. next model round                  (caller)
 *
 * [prepare] is idempotent per (runId, toolCallId): a tool call that was
 * already prepared (same run, or an earlier run of the same conversation
 * after a crash) reuses its effect instead of creating a duplicate. A
 * FINISHED effect with the same args is returned as-is (never a second
 * row) — a re-emitted call is the duplicate guard's decision, not a new
 * execution.
 */
interface ToolEffectLedger {
    suspend fun prepare(
        runId: String,
        turnId: Int,
        toolCallId: String,
        toolName: String,
        input: String,
        effectClass: ToolEffectClass,
        messagePersistenceCursor: String? = null,
    ): ToolEffect

    suspend fun get(effectId: String): ToolEffect?

    suspend fun getByToolCallId(toolCallId: String): ToolEffect?

    /**
     * Every effect ever prepared for [toolCallId], oldest first (the newest
     * is the current attempt). Payload-hygiene sweeps iterate ALL rows so a
     * terminal row is found even when younger rows share the callId.
     */
    suspend fun listByToolCallId(toolCallId: String): List<ToolEffect>

    suspend fun listByRun(runId: String): List<ToolEffect>

    suspend fun listByConversation(conversationId: String): List<ToolEffect>

    suspend fun listOutcomeUnknown(): List<ToolEffect>

    /**
     * Post-approval transition. Idempotent: already-STARTED effects stay
     * STARTED, and a FINISHED effect is never downgraded (a FINISHED row can
     * surface as a prepare reuse product; the duplicate guard owns it, not a
     * re-execution).
     */
    suspend fun markStarted(effectId: String, approvalDigest: String)

    /** Success: stores the result payload so it can be replayed without re-execution. */
    suspend fun finish(effectId: String, output: List<UIMessagePart>)

    /**
     * The tool result has been written into the conversation; the replay
     * payload is no longer read (the replay window ends once the result
     * lands). Clears [ToolEffect.resultPayload] so full tool output does not
     * accumulate in the ledger as a plaintext sink (P1-01 secret hygiene).
     * The FINISHED state, summary and timestamps are kept.
     */
    suspend fun markResultPersisted(effectId: String)

    /** Failure: records an error category; the effect can be retried normally. */
    suspend fun fail(effectId: String, errorCategory: String, output: List<UIMessagePart> = emptyList())

    /**
     * Retention (P1-02): delete terminal effects (FINISHED / FAILED /
     * RECONCILED, incl. abandoned) not touched for more than [maxAgeMs].
     * Called at cold start so the ledger never grows unbounded.
     */
    suspend fun deleteTerminalOlderThan(maxAgeMs: Long): Int

    /** Recovery: a started non-idempotent effect whose outcome is unknown. */
    suspend fun markOutcomeUnknown(effectId: String, errorCategory: String)

    /**
     * User decision on an OUTCOME_UNKNOWN effect. retry=true makes the effect
     * executable again; retry=false records the abandoned result (structured
     * rejection) and blocks future execution.
     */
    suspend fun reconcile(effectId: String, retry: Boolean, abandonOutput: List<UIMessagePart> = emptyList())
}

class RoomToolEffectLedger(
    private val dao: ToolEffectDAO,
    private val runTerminalDao: RunTerminalDAO,
    private val json: Json,
    private val now: () -> Long = System::currentTimeMillis,
) : ToolEffectLedger {

    override suspend fun prepare(
        runId: String,
        turnId: Int,
        toolCallId: String,
        toolName: String,
        input: String,
        effectClass: ToolEffectClass,
        messagePersistenceCursor: String?,
    ): ToolEffect {
        val argsDigest = argsDigest(input)
        val rows = dao.getByToolCallId(toolCallId)
        // Same run, already FINISHED with the same args (the model re-emitted
        // a call whose execution is already on the ledger): return the
        // finished effect instead of minting a second PREPARED row. The
        // digest match is what the duplicate-tool-call guard keys on, so the
        // re-emission is skipped by signature and the effect is never
        // re-executed; markStarted also refuses to rewrite a FINISHED row.
        rows.firstOrNull {
            it.runId == runId &&
                it.status == ToolEffectStatus.FINISHED.name &&
                it.argsDigest == argsDigest
        }?.let { return ToolEffect.from(it) }
        // Same run: the previous prepare (approval round) is reused.
        rows.filter { it.isReusable() }
            .firstOrNull { it.runId == runId }
            ?.let { return ToolEffect.from(it) }
        // Same conversation, earlier run (crash then resume with a new runId):
        // rebind the effect to the current run instead of duplicating it.
        val conversationId = runTerminalDao.getByRunId(runId)?.conversationId
        if (conversationId != null) {
            val sameConversation = dao.listByConversation(conversationId)
                .filter { it.toolCallId == toolCallId && it.isReusable() }
                .firstOrNull()
            if (sameConversation != null) {
                val rebound = sameConversation.copy(
                    runId = runId,
                    turnId = turnId,
                    messagePersistenceCursor = messagePersistenceCursor,
                    updatedAtMs = now(),
                )
                dao.upsert(rebound)
                return ToolEffect.from(rebound)
            }
        }
        val nowMs = now()
        val entity = ToolEffectEntity(
            effectId = UUID.randomUUID().toString(),
            runId = runId,
            turnId = turnId,
            toolCallId = toolCallId,
            toolName = toolName,
            argsDigest = argsDigest,
            approvalDigest = null,
            effectClass = effectClass.name,
            status = ToolEffectStatus.PREPARED.name,
            startedAtMs = nowMs,
            finishedAtMs = null,
            resultSummary = null,
            resultPayload = null,
            errorCategory = null,
            messagePersistenceCursor = messagePersistenceCursor,
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
        )
        dao.upsert(entity)
        return ToolEffect.from(entity)
    }

    override suspend fun get(effectId: String): ToolEffect? =
        dao.getByEffectId(effectId)?.let(ToolEffect::from)

    override suspend fun getByToolCallId(toolCallId: String): ToolEffect? =
        dao.getByToolCallId(toolCallId).lastOrNull()?.let(ToolEffect::from)

    override suspend fun listByToolCallId(toolCallId: String): List<ToolEffect> =
        dao.getByToolCallId(toolCallId).map(ToolEffect::from)

    override suspend fun listByRun(runId: String): List<ToolEffect> =
        dao.listByRun(runId).map(ToolEffect::from)

    override suspend fun listByConversation(conversationId: String): List<ToolEffect> =
        dao.listByConversation(conversationId).map(ToolEffect::from)

    override suspend fun listOutcomeUnknown(): List<ToolEffect> =
        dao.listOutcomeUnknown().map(ToolEffect::from)

    override suspend fun markStarted(effectId: String, approvalDigest: String) {
        val entity = dao.getByEffectId(effectId) ?: return
        if (entity.status == ToolEffectStatus.STARTED.name) return
        if (entity.status == ToolEffectStatus.FINISHED.name) return
        dao.upsert(
            entity.copy(
                status = ToolEffectStatus.STARTED.name,
                approvalDigest = approvalDigest,
                updatedAtMs = now(),
            )
        )
    }

    override suspend fun finish(effectId: String, output: List<UIMessagePart>) {
        val entity = dao.getByEffectId(effectId) ?: return
        val payload = runCatching { json.encodeToString(output) }.getOrNull()
        dao.upsert(
            entity.copy(
                status = ToolEffectStatus.FINISHED.name,
                finishedAtMs = now(),
                resultSummary = outputSummary(output),
                resultPayload = payload,
                errorCategory = null,
                updatedAtMs = now(),
            )
        )
    }

    override suspend fun markResultPersisted(effectId: String) {
        val entity = dao.getByEffectId(effectId) ?: return
        if (entity.status != ToolEffectStatus.FINISHED.name && entity.status != ToolEffectStatus.FAILED.name) return
        if (entity.resultPayload == null) return
        dao.upsert(
            entity.copy(
                resultPayload = null,
                updatedAtMs = now(),
            )
        )
    }

    override suspend fun deleteTerminalOlderThan(maxAgeMs: Long): Int {
        val cutoffMs = now() - maxAgeMs
        return dao.deleteTerminalOlderThan(
            statuses = listOf(
                ToolEffectStatus.FINISHED.name,
                ToolEffectStatus.FAILED.name,
                ToolEffectStatus.RECONCILED.name,
            ),
            cutoffMs = cutoffMs,
        )
    }

    override suspend fun fail(effectId: String, errorCategory: String, output: List<UIMessagePart>) {
        val entity = dao.getByEffectId(effectId) ?: return
        val payload = runCatching { json.encodeToString(output) }.getOrNull()
        dao.upsert(
            entity.copy(
                status = ToolEffectStatus.FAILED.name,
                finishedAtMs = now(),
                resultSummary = outputSummary(output),
                resultPayload = payload,
                errorCategory = errorCategory,
                updatedAtMs = now(),
            )
        )
    }

    override suspend fun markOutcomeUnknown(effectId: String, errorCategory: String) {
        val entity = dao.getByEffectId(effectId) ?: return
        dao.upsert(
            entity.copy(
                status = ToolEffectStatus.OUTCOME_UNKNOWN.name,
                errorCategory = errorCategory,
                updatedAtMs = now(),
            )
        )
    }

    override suspend fun reconcile(effectId: String, retry: Boolean, abandonOutput: List<UIMessagePart>) {
        val entity = dao.getByEffectId(effectId) ?: return
        val nowMs = now()
        dao.upsert(
            entity.copy(
                status = ToolEffectStatus.RECONCILED.name,
                finishedAtMs = if (retry) null else nowMs,
                resultSummary = if (retry) null else outputSummary(abandonOutput),
                resultPayload = if (retry) null else runCatching { json.encodeToString(abandonOutput) }.getOrNull(),
                errorCategory = if (retry) null else "abandoned",
                updatedAtMs = nowMs,
            )
        )
    }

    private fun outputSummary(output: List<UIMessagePart>): String? {
        val text = output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        if (text.isBlank()) return null
        return text.replace(Regex("\\s+"), " ").take(200)
    }

    /**
     * An effect is reusable when the tool was never executed (PREPARED), or
     * execution may have started but a retry is safe (STARTED), or the user
     * explicitly confirmed a retry (RECONCILED without "abandoned").
     */
    private fun ToolEffectEntity.isReusable(): Boolean = when (status) {
        ToolEffectStatus.PREPARED.name,
        ToolEffectStatus.STARTED.name,
        -> true

        ToolEffectStatus.RECONCILED.name -> errorCategory != "abandoned"

        else -> false
    }
}

/** Stable SHA-256 hex digest used for args/approval digests. */
internal fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

/** Display-only tool-arg metadata keys excluded from both digest and execution args. */
internal val TOOL_DISPLAY_METADATA_KEYS = setOf("display_title")

/**
 * Strips display-only metadata at every nesting level and canonicalizes the
 * JSON (recursive lexicographic key sort; array order preserved) so the
 * digested args equal the executed args and are stable against key-order
 * permutations from different providers/models.
 */
internal fun JsonElement.withoutToolDisplayMetadata(): JsonElement = when (this) {
    is JsonObject -> JsonObject(
        entries
            .filter { (key, _) -> key !in TOOL_DISPLAY_METADATA_KEYS }
            .associate { (key, value) -> key to value.withoutToolDisplayMetadata() }
            .toSortedMap(),
    )

    is JsonArray -> JsonArray(map { it.withoutToolDisplayMetadata() })
    else -> this
}

/**
 * Argument digest over the raw input, stripped of display-only metadata and
 * canonicalized (recursive key sort). NOTE: digests persisted before the
 * canonicalization change do not match digests of the same args computed
 * today — that is acceptable, the digest is an intra-run consistency key
 * (writer and reader always use the same function within one process).
 */
internal fun argsDigest(input: String): String {
    val normalized = runCatching {
        digestJson.parseToJsonElement(input.ifBlank { "{}" })
            .withoutToolDisplayMetadata()
            .toString()
    }.getOrDefault(input)
    return sha256Hex(normalized)
}

private val digestJson = Json {
    ignoreUnknownKeys = true
}

/** Approval digest binding runId + toolCallId + args digest + decision. */
internal fun approvalDigest(runId: String, toolCallId: String, argsDigest: String): String =
    sha256Hex(listOf(runId, toolCallId, argsDigest, "approved").joinToString("|"))

internal fun Throwable.errorCategory(): String = when (this) {
    is CancellationException -> "cancelled"
    else -> this::class.simpleName?.take(80) ?: "unknown"
}
