package app.amber.core.memory.extraction

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.core.settings.DEFAULT_AUTO_MODEL_ID
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.findProvider
import app.amber.core.settings.resolveTaskChatModel
import app.amber.core.memory.model.MemoryCandidate
import app.amber.core.memory.model.MemoryEventType
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryRecord
import app.amber.core.memory.model.MemoryScope
import app.amber.core.memory.prompt.MemoryExtractionPrompt
import app.amber.core.memory.recall.MemoryRecallStore
import app.amber.core.memory.safety.isSensitiveMemoryContent
import app.amber.core.memory.store.MemoryRepository
import app.amber.core.memory.store.MemoryStaleException
import app.amber.core.memory.telemetry.MemoryEventLogger
import app.amber.core.memory.time.MemoryTimeAnchorParser
import app.amber.core.model.Conversation
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.core.utils.appLocaleDisplayName
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid

class MemoryExtractor(
    private val settingsStore: SettingsAggregator,
    private val providerCatalog: ProviderCatalog,
    private val json: Json,
    private val memoryRepository: MemoryRepository,
    private val eventLogger: MemoryEventLogger,
    private val context: Context,
    private val candidateFilter: MemoryCandidateFilter = MemoryCandidateFilter(),
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
) {
    private val lastRunAt = ConcurrentHashMap<Uuid, Long>()

    suspend fun extractAfterConversation(conversation: Conversation) {
        // ChatService supplies the epoch captured before dispatching this work.
        // Standalone extraction captures the current generation before any model
        // call; repository writes then reject the result if restore starts later.
        val writeContext = captureWriteContext()
        withContext(Dispatchers.IO + writeContext) {
            val settings = settingsStore.settingsFlow.value
            val worker = settings.agentRuntime.memoryWorker
            val conversationId = conversation.id.toString()
            if (!worker.enabled || !worker.extractionEnabled) {
                eventLogger.log(
                    type = MemoryEventType.EXTRACTION_SKIPPED,
                    conversationId = conversationId,
                    message = "Memory worker disabled.",
                    messageCount = conversation.currentMessages.size,
                )
                return@withContext
            }
            val now = System.currentTimeMillis()
            val previous = lastRunAt[conversation.id] ?: 0L
            if (now - previous < 120_000L) {
                eventLogger.log(
                    type = MemoryEventType.EXTRACTION_SKIPPED,
                    conversationId = conversationId,
                    message = "Debounced.",
                    messageCount = conversation.currentMessages.size,
                )
                return@withContext
            }
            val todayStart = now - (now % 86_400_000L)
            val runsToday = memoryRepository.countEventsSince(MemoryEventType.EXTRACTION_STARTED, todayStart)
            if (runsToday >= worker.maxDailyRuns.coerceAtLeast(1)) {
                eventLogger.log(
                    type = MemoryEventType.EXTRACTION_SKIPPED,
                    conversationId = conversationId,
                    message = "Daily memory worker limit reached.",
                    messageCount = conversation.currentMessages.size,
                )
                return@withContext
            }
            lastRunAt[conversation.id] = now

            val model = resolveMemoryModel(settings)
            if (model == null) {
                eventLogger.log(
                    type = MemoryEventType.EXTRACTION_SKIPPED,
                    conversationId = conversationId,
                    message = "No memory worker model available.",
                    messageCount = conversation.currentMessages.size,
                )
                return@withContext
            }
            val provider = model.findProvider(settings.providers)
            if (provider == null) {
                eventLogger.log(
                    type = MemoryEventType.EXTRACTION_SKIPPED,
                    conversationId = conversationId,
                    modelId = model.id.toString(),
                    message = "Memory worker model provider not found.",
                    messageCount = conversation.currentMessages.size,
                )
                return@withContext
            }

            val startedAt = System.currentTimeMillis()
            eventLogger.log(
                type = MemoryEventType.EXTRACTION_STARTED,
                conversationId = conversationId,
                modelId = model.id.toString(),
                messageCount = conversation.currentMessages.size,
            )

            runCatching {
                val sourceMessages = conversation.currentMessages.takeLast(16)
                val sourceIds = sourceMessages.map { it.id.toString() }
                val activeRecords = memoryRepository.getAllActiveRecords()
                val shownRecords = selectRelevantRecords(activeRecords, sourceMessages)
                // Update targets must come from the shown list — an id the model
                // never saw is model error, not consent to rewrite that record.
                val recordsById = shownRecords.associateBy { it.id }
                val prompt = MemoryExtractionPrompt.build(
                    messages = sourceMessages,
                    sourceMessageIds = sourceIds,
                    locale = context.appLocaleDisplayName(),
                    existingMemories = shownRecords,
                )
                val response = providerCatalog.text(provider).complete(
                    providerSetting = provider,
                    messages = listOf(UIMessage.user(prompt)),
                    params = TextGenerationParams(
                        model = model,
                        sessionId = conversationId,
                    ),
                )
                val text = response.choices.firstOrNull()?.message?.toText().orEmpty()
                val parsedCandidates = parseCandidates(
                    json = json,
                    raw = text,
                    conversationId = conversationId,
                    sourceMessageIds = sourceIds,
                )
                val parseMetaById = parsedCandidates.associateBy { it.candidate.id }
                val candidates = parsedCandidates.map { it.candidate }
                val filtered = candidateFilter.filter(candidates, activeRecords)
                memoryRepository.addCandidates(filtered.rejected)
                filtered.accepted.forEach { candidate ->
                    val meta = parseMetaById[candidate.id]
                    val autoWrite = shouldAutoWriteCandidate(
                        candidate = candidate,
                        explicitScope = meta?.explicitScope == true,
                        explicitKind = meta?.explicitKind == true,
                    )
                    // Reconcile: a model "update" rewrites the target in place via
                    // CAS bound to the snapshot revision. Core and pinned records
                    // are never touched; a stale snapshot goes to review instead
                    // of adding. The gate still requires the candidate's own
                    // explicit scope/kind even though an update keeps the
                    // target's classification — conservative on purpose.
                    val updateTarget = meta?.updateMemoryId?.let(recordsById::get)
                    // Topics are dream-synthesized; extraction updates must
                    // never rewrite them — they fall back to pending review.
                    val updateAttempted = autoWrite &&
                        updateTarget != null &&
                        updateTarget.scope != MemoryScope.CORE &&
                        updateTarget.kind != MemoryKind.TOPIC &&
                        !updateTarget.pinned
                    val updateApplied = updateAttempted && applyExtractionUpdate(
                        target = updateTarget!!,
                        newContent = candidate.content,
                    ) { id, content, revision ->
                        memoryRepository.updateContentCas(
                            id = id,
                            content = content,
                            expectedRevision = revision,
                            sourceRunId = conversationId,
                            sourceTrigger = MemoryRepository.TRIGGER_AUTO_EXTRACTION,
                        )
                    }
                    if (updateApplied) {
                        eventLogger.log(
                            type = MemoryEventType.MEMORY_UPDATED,
                            conversationId = conversationId,
                            memoryId = updateTarget!!.id,
                            modelId = model.id.toString(),
                            message = "Auto-updated by extraction reconcile.",
                        )
                    } else if (autoWrite && !updateAttempted) {
                        val memory = memoryRepository.addMemory(
                            scope = candidate.scope,
                            kind = candidate.kind,
                            content = candidate.content,
                            sourceConversationId = candidate.sourceConversationId,
                            sourceMessageIds = candidate.sourceMessageIds,
                            expiresAt = candidate.expiresAt,
                            confidence = candidate.confidence,
                            // P2-06 provenance: automatic extraction writes are
                            // distinguishable from tool-driven writes.
                            sourceTrigger = MemoryRepository.TRIGGER_AUTO_EXTRACTION,
                        )
                        eventLogger.log(
                            type = if (candidate.isDurableAutoWrite()) {
                                MemoryEventType.DURABLE_MEMORY_CREATED
                            } else {
                                MemoryEventType.MEMORY_CREATED
                            },
                            conversationId = conversationId,
                            memoryId = memory.id,
                            modelId = model.id.toString(),
                            message = candidate.autoWriteEventMessage(),
                        )
                    } else {
                        // A pending candidate that the model meant as an update
                        // keeps the target link in its reason so review doesn't
                        // create a duplicate of the still-live record.
                        val pendingCandidate = meta?.updateMemoryId?.let { targetId ->
                            candidate.copy(
                                reason = "updates memory #$targetId: ${candidate.reason}".trim(),
                            )
                        } ?: candidate
                        memoryRepository.addCandidate(pendingCandidate)
                        eventLogger.log(
                            type = MemoryEventType.CANDIDATE_CREATED,
                            conversationId = conversationId,
                            candidateId = pendingCandidate.id,
                            modelId = model.id.toString(),
                            message = pendingCandidate.reason,
                        )
                    }
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                eventLogger.log(
                    type = MemoryEventType.EXTRACTION_FAILED,
                    conversationId = conversationId,
                    modelId = model.id.toString(),
                    message = error.message ?: error::class.java.simpleName,
                    durationMs = System.currentTimeMillis() - startedAt,
                    messageCount = conversation.currentMessages.size,
                )
            }
        }
    }

    private suspend fun captureWriteContext(): CoroutineContext {
        coroutineContext[SyncRestoreWriteEpoch]?.let { return it }
        val gate = restoreWriteGate ?: return EmptyCoroutineContext
        // A standalone extractor has no generation from ChatService. Wait for
        // an in-flight restore before reading limits or starting the model call;
        // the short lock is released before any network work begins.
        gate.withWriter { Unit }
        return SyncRestoreWriteEpoch(gate.currentEpoch())
    }

    private fun resolveMemoryModel(settings: Settings) =
        when {
            settings.agentRuntime.memoryWorker.modelId != DEFAULT_AUTO_MODEL_ID ->
                settings.resolveTaskChatModel(settings.agentRuntime.memoryWorker.modelId)

            settings.agentRuntime.memoryWorker.followCompressModel ->
                settings.resolveTaskChatModel(settings.compressModelId)

            else -> settings.resolveTaskChatModel(settings.chatModelId)
        } ?: settings.resolveTaskChatModel(settings.chatModelId)

    private fun selectRelevantRecords(
        records: List<MemoryRecord>,
        messages: List<UIMessage>,
    ): List<MemoryRecord> {
        val conversationTokens = MemoryRecallStore.tokenize(
            messages.joinToString("\n") { it.toText() }
        )
        return records
            .map { record ->
                record to MemoryRecallStore.tokenize(record.content).count(conversationTokens::contains)
            }
            .sortedWith(
                compareByDescending<Pair<MemoryRecord, Int>> { it.second }
                    .thenByDescending { it.first.updatedAt }
            )
            .map { it.first }
            .take(RECONCILE_MEMORY_LIMIT)
    }

    private fun MemoryCandidate.isDurableAutoWrite(): Boolean =
        scope == MemoryScope.LONG_TERM &&
            kind in setOf(MemoryKind.USER, MemoryKind.FEEDBACK) &&
            confidence >= DURABLE_AUTO_WRITE_CONFIDENCE

    private fun MemoryCandidate.autoWriteEventMessage(): String =
        when {
            isDurableAutoWrite() && kind == MemoryKind.USER -> "Auto-created durable user memory."
            isDurableAutoWrite() && kind == MemoryKind.FEEDBACK -> "Auto-created durable feedback memory."
            else -> "Auto-created short-term project memory."
        }

    internal data class ParsedMemoryCandidate(
        val candidate: MemoryCandidate,
        val explicitScope: Boolean,
        val explicitKind: Boolean,
        val updateMemoryId: Int?,
    )

    companion object {
        internal const val SHORT_TERM_PROJECT_AUTO_WRITE_CONFIDENCE = 0.72f
        internal const val DURABLE_AUTO_WRITE_CONFIDENCE = 0.85f

        /** Existing memories injected into the extraction prompt for reconcile. */
        internal const val RECONCILE_MEMORY_LIMIT = 24

        private fun String?.isValidMemoryScope(): Boolean =
            MemoryScope.entries.any { it.wireName == this }

        private fun String?.isValidMemoryKind(): Boolean =
            MemoryKind.entries.any { it.wireName == this }

        internal fun parseCandidates(
            json: Json,
            raw: String,
            conversationId: String,
            sourceMessageIds: List<String>,
        ): List<ParsedMemoryCandidate> {
            val cleaned = raw.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
                .let { text ->
                    val start = text.indexOf('{')
                    val end = text.lastIndexOf('}')
                    if (start >= 0 && end > start) text.substring(start, end + 1) else text
                }
            val root = json.parseToJsonElement(cleaned).jsonObject
            return root["candidates"]?.jsonArray.orEmpty().take(5).mapNotNull { item ->
                val obj = item.jsonObject
                val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (content.isBlank()) return@mapNotNull null
                val expiresInDays = obj["expires_in_days"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                val scopeValue = obj["scope"]?.jsonPrimitive?.contentOrNull
                val kindValue = obj["kind"]?.jsonPrimitive?.contentOrNull
                val scope = MemoryScope.fromWireName(scopeValue)
                val expiresAt = resolveCandidateExpiresAt(content, scope, expiresInDays)
                val isUpdate = obj["action"]?.jsonPrimitive?.contentOrNull == "update"
                ParsedMemoryCandidate(
                    explicitScope = scopeValue.isValidMemoryScope(),
                    explicitKind = kindValue.isValidMemoryKind(),
                    updateMemoryId = if (isUpdate) {
                        obj["update_memory_id"]?.jsonPrimitive?.intOrNull
                    } else {
                        null
                    },
                    candidate = MemoryCandidate(
                        content = content,
                        scope = scope,
                        // Extraction never produces topic records — those are
                        // synthesized by dream review only.
                        kind = MemoryKind.fromWireName(kindValue)
                            .takeIf { it != MemoryKind.TOPIC } ?: MemoryKind.NOTE,
                        confidence = obj["confidence"]?.jsonPrimitive?.floatOrNull ?: 0.55f,
                        reason = obj["reason"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        sourceConversationId = conversationId,
                        sourceMessageIds = sourceMessageIds,
                        expiresAt = expiresAt,
                    ),
                )
            }
        }

        /**
         * Auto-apply a model "update" action: the CAS binds the revision the
         * model saw at prompt time. Returns false when the record moved in
         * between — the caller then hands the update intent to review instead
         * of overwriting or adding a stale duplicate.
         */
        internal suspend fun applyExtractionUpdate(
            target: MemoryRecord,
            newContent: String,
            write: suspend (id: Int, content: String, expectedRevision: Long) -> Unit,
        ): Boolean = try {
            write(target.id, newContent, target.revision)
            true
        } catch (stale: MemoryStaleException) {
            false
        }


        internal fun shouldAutoWriteCandidate(
            candidate: MemoryCandidate,
            explicitScope: Boolean,
            explicitKind: Boolean,
        ): Boolean {
            if (candidate.sensitive || isSensitiveMemoryContent(candidate.content)) return false
            if (!explicitScope || !explicitKind) return false
            val shortTermProject = candidate.scope == MemoryScope.SHORT_TERM &&
                candidate.kind == MemoryKind.PROJECT &&
                candidate.confidence >= SHORT_TERM_PROJECT_AUTO_WRITE_CONFIDENCE
            val durableMemory = candidate.scope == MemoryScope.LONG_TERM &&
                candidate.kind in setOf(MemoryKind.USER, MemoryKind.FEEDBACK) &&
                candidate.confidence >= DURABLE_AUTO_WRITE_CONFIDENCE
            return shortTermProject || durableMemory
        }

        internal fun resolveCandidateExpiresAt(
            content: String,
            scope: MemoryScope,
            expiresInDays: Long?,
            now: Long = System.currentTimeMillis(),
        ): Long? =
            expiresInDays?.let { now + it * 86_400_000L }
                ?: scope.takeIf { it == MemoryScope.SHORT_TERM }
                    ?.let { MemoryTimeAnchorParser.deriveExpiresAt(content, now) }
    }
}
