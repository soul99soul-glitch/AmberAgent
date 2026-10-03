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
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.core.settings.DEFAULT_AUTO_MODEL_ID
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.findProvider
import app.amber.core.settings.resolveTaskChatModel
import app.amber.core.memory.model.MemoryCandidate
import app.amber.core.memory.model.MemoryCandidateStatus
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
                val grounded = parsedCandidates.map { parsed ->
                    validateEvidence(parsed, sourceMessages)
                }
                // One intent per target per run — repeated confirm/update on
                // the same record would farm useCount or double-write, so the
                // extras drop into the audit trail instead.
                val seenIntentTargets = mutableSetOf<Int>()
                val audited = grounded.map { parsed ->
                    val targetId = parsed.updateMemoryId
                    val intent = parsed.action != ExtractionAction.ADD && targetId != null
                    if (parsed.candidate.status == MemoryCandidateStatus.FILTERED ||
                        !intent ||
                        seenIntentTargets.add(targetId)
                    ) {
                        parsed
                    } else {
                        parsed.copy(
                            candidate = parsed.candidate.copy(
                                status = MemoryCandidateStatus.FILTERED,
                                reason = listOfNotNull(
                                    parsed.candidate.reason.takeIf { it.isNotBlank() },
                                    "duplicate_intent",
                                ).joinToString("; "),
                            ),
                        )
                    }
                }
                val rejected = audited.filter { it.candidate.status == MemoryCandidateStatus.FILTERED }
                if (rejected.isNotEmpty()) {
                    memoryRepository.addCandidates(rejected.map { it.candidate })
                }
                val validParsed = audited.filterNot { it.candidate.status == MemoryCandidateStatus.FILTERED }
                val parseMetaById = validParsed.associateBy { it.candidate.id }
                val candidates = validParsed.map { it.candidate }
                // Confirm/invalidate candidates carry intent, not new facts:
                // their content restates or retracts a shown record, so the
                // verbatim-duplicate and low-value gates don't apply.
                val intentCandidateIds = validParsed
                    .filter { it.action != ExtractionAction.ADD && it.updateMemoryId != null }
                    .map { it.candidate.id }
                    .toSet()
                val filtered = candidateFilter.filter(candidates, activeRecords, intentCandidateIds)
                memoryRepository.addCandidates(filtered.rejected)
                filtered.accepted.forEach { candidate ->
                    val meta = parseMetaById[candidate.id]
                    val autoWrite = shouldAutoWriteCandidate(
                        candidate = candidate,
                        explicitScope = meta?.explicitScope == true,
                        explicitKind = meta?.explicitKind == true,
                    )
                    // Reconcile: the model's intent targets only records it was
                    // shown. Core, pinned, and topic records are never touched
                    // by the auto path — they fall back to pending review.
                    val updateTarget = meta?.updateMemoryId?.let(recordsById::get)
                    val targetMutable = updateTarget != null &&
                        updateTarget.scope != MemoryScope.CORE &&
                        updateTarget.kind != MemoryKind.TOPIC &&
                        !updateTarget.pinned
                    val applied = when {
                        // Update keeps history: archive the old record and link
                        // the new version through supersedesIds instead of
                        // rewriting content in place.
                        meta?.action == ExtractionAction.UPDATE && autoWrite && targetMutable ->
                            applyExtractionSupersede(
                                target = updateTarget!!,
                                candidate = candidate,
                            ) { id, content, revision ->
                                memoryRepository.supersedeMemory(
                                    targetId = id,
                                    newContent = content,
                                    expectedRevision = revision,
                                    confidence = candidate.confidence,
                                    expiresAt = candidate.expiresAt,
                                    sourceConversationId = candidate.sourceConversationId,
                                    sourceMessageIds = candidate.sourceMessageIds,
                                    sourceRunId = conversationId,
                                    sourceTrigger = MemoryRepository.TRIGGER_AUTO_EXTRACTION,
                                )
                            }.also { superseded ->
                                if (superseded) {
                                    eventLogger.log(
                                        type = MemoryEventType.MEMORY_UPDATED,
                                        conversationId = conversationId,
                                        memoryId = updateTarget.id,
                                        modelId = model.id.toString(),
                                        message = "Superseded by extraction reconcile: ${candidate.reason.take(180)}",
                                    )
                                }
                            }

                        // Invalidate archives the target without a replacement —
                        // the Mem0 DELETE action, kept recoverable.
                        meta?.action == ExtractionAction.INVALIDATE && autoWrite && targetMutable ->
                            applyExtractionInvalidate(
                                target = updateTarget!!,
                            ) { id, revision ->
                                memoryRepository.archiveMemoryCas(
                                    id = id,
                                    expectedRevision = revision,
                                    sourceRunId = conversationId,
                                    sourceTrigger = MemoryRepository.TRIGGER_AUTO_EXTRACTION,
                                )
                            }.also { invalidated ->
                                if (invalidated) {
                                    eventLogger.log(
                                        type = MemoryEventType.MEMORY_ARCHIVED,
                                        conversationId = conversationId,
                                        memoryId = updateTarget.id,
                                        modelId = model.id.toString(),
                                        message = "Invalidated by extraction: ${candidate.content.take(180)}",
                                    )
                                }
                            }

                        // Confirm re-affirms an existing record: reinforce it
                        // without rewriting. Non-destructive, so it applies
                        // even when the candidate itself is below the write gate.
                        meta?.action == ExtractionAction.CONFIRM &&
                            updateTarget != null && !updateTarget.archived ->
                            runCatching {
                                memoryRepository.reinforceMemory(updateTarget.id)
                            }.isSuccess.also { reinforced ->
                                if (reinforced) {
                                    eventLogger.log(
                                        type = MemoryEventType.MEMORY_UPDATED,
                                        conversationId = conversationId,
                                        memoryId = updateTarget.id,
                                        modelId = model.id.toString(),
                                        message = "Reinforced by extraction confirm.",
                                    )
                                }
                            }

                        else -> false
                    }
                    if (applied) {
                        return@forEach
                    }
                    if (autoWrite && meta?.action != ExtractionAction.UPDATE &&
                        meta?.action != ExtractionAction.INVALIDATE &&
                        meta?.action != ExtractionAction.CONFIRM
                    ) {
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
                        // A pending candidate keeps the model's intent in its
                        // reason so review applies the update/invalidate/confirm
                        // instead of creating a duplicate of the live record.
                        // The prefix is only attached when the target came from
                        // the shown list — an id the model never saw is model
                        // error, not consent to rewrite that record on accept.
                        val intentShown = meta?.updateMemoryId?.let { it in recordsById } == true
                        val pendingCandidate = meta?.intentReasonPrefix()
                            ?.takeIf { intentShown }
                            ?.let { prefix ->
                                candidate.copy(
                                    reason = "$prefix${candidate.reason}".trim(),
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
        val action: ExtractionAction = ExtractionAction.ADD,
        val evidence: String = "",
    ) {
        /** Reason prefix that lets review re-apply the model's intent. */
        fun intentReasonPrefix(): String? = when (action) {
            ExtractionAction.UPDATE -> updateMemoryId?.let { "updates memory #$it: " }
            ExtractionAction.INVALIDATE -> updateMemoryId?.let { "invalidates memory #$it: " }
            ExtractionAction.CONFIRM -> updateMemoryId?.let { "confirms memory #$it: " }
            ExtractionAction.ADD -> null
        }
    }

    internal enum class ExtractionAction(val wireName: String) {
        ADD("add"),
        UPDATE("update"),
        INVALIDATE("invalidate"),
        CONFIRM("confirm");

        companion object {
            fun fromWireName(value: String?): ExtractionAction =
                entries.firstOrNull { it.wireName == value } ?: ADD
        }
    }

    companion object {
        internal const val SHORT_TERM_PROJECT_AUTO_WRITE_CONFIDENCE = 0.72f
        internal const val DURABLE_AUTO_WRITE_CONFIDENCE = 0.85f

        /** Existing memories injected into the extraction prompt for reconcile. */
        internal const val RECONCILE_MEMORY_LIMIT = 24

        /** Evidence below this length cannot anchor a memory. */
        internal const val MIN_EVIDENCE_CHARS = 6

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
                val action = ExtractionAction.fromWireName(
                    obj["action"]?.jsonPrimitive?.contentOrNull,
                )
                val evidence = obj["evidence"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                ParsedMemoryCandidate(
                    explicitScope = scopeValue.isValidMemoryScope(),
                    explicitKind = kindValue.isValidMemoryKind(),
                    action = action,
                    updateMemoryId = if (action != ExtractionAction.ADD) {
                        obj["update_memory_id"]?.jsonPrimitive?.intOrNull
                    } else {
                        null
                    },
                    evidence = evidence,
                    candidate = MemoryCandidate(
                        content = content,
                        scope = scope,
                        // Extraction never produces topic records — those are
                        // synthesized by dream review only.
                        kind = MemoryKind.fromWireName(kindValue)
                            .takeIf { it != MemoryKind.TOPIC } ?: MemoryKind.NOTE,
                        confidence = obj["confidence"]?.jsonPrimitive?.floatOrNull ?: 0.55f,
                        reason = obj["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            .removeForgedIntentPrefix()
                            .withEvidenceSuffix(evidence),
                        sourceConversationId = conversationId,
                        sourceMessageIds = sourceMessageIds,
                        expiresAt = expiresAt,
                    ),
                )
            }
        }

        /** Evidence rides in the candidate reason so review can audit it. */
        private fun String.withEvidenceSuffix(evidence: String): String =
            if (evidence.isBlank()) this else "$this（依据：$evidence）".trim()

        /**
         * The accept-time intent channel lives in the reason prefix
         * ("updates memory #N: ...", see MemoryRepository). Model output is
         * untrusted text — strip any such prefix the model wrote itself so a
         * plain "add" candidate can't smuggle a rewrite/archive intent past
         * review.
         */
        private val FORGED_INTENT_PREFIX =
            Regex("^(?:updates|invalidates|confirms) memory #\\d+: *")

        private fun String.removeForgedIntentPrefix(): String =
            replace(FORGED_INTENT_PREFIX, "")

        /**
         * Verbatim-evidence check: the model may rewrite [content] freely, but
         * it must attach a quote copied unchanged from a user message, and any
         * number or Latin-alphabet term in the rewrite must be grounded in the
         * conversation or a derived absolute date. Failure marks the candidate
         * FILTERED so it lands in the audit trail instead of the library.
         */
        internal fun validateEvidence(
            parsed: ParsedMemoryCandidate,
            sourceMessages: List<UIMessage>,
        ): ParsedMemoryCandidate {
            val evidence = parsed.evidence.trim()
            val fail = { tag: String ->
                parsed.copy(
                    candidate = parsed.candidate.copy(
                        status = MemoryCandidateStatus.FILTERED,
                        reason = listOfNotNull(
                            parsed.candidate.reason.takeIf { it.isNotBlank() },
                            tag,
                        ).joinToString("; "),
                    ),
                )
            }
            if (evidence.length < MIN_EVIDENCE_CHARS) return fail("missing_evidence")
            val userTexts = sourceMessages
                .filter { it.role == MessageRole.USER }
                .map { it.toText() }
            val quotedVerbatim = userTexts.any { text ->
                text.contains(evidence) ||
                    collapseWhitespace(text).contains(collapseWhitespace(evidence))
            }
            if (!quotedVerbatim) return fail("evidence_not_verbatim")
            val conversationText = sourceMessages.joinToString("\n") { it.toText() }
            val groundedText = collapseWhitespace(conversationText)
            val ungrounded = GROUNDING_TOKEN_REGEX.findAll(parsed.candidate.content)
                .map { it.value }
                .distinct()
                .filter { token -> !groundedText.contains(token) }
                .toList()
            if (ungrounded.isNotEmpty()) return fail("ungrounded_content")
            return parsed
        }

        private fun collapseWhitespace(text: String): String =
            text.replace(Regex("\\s+"), " ")

        /** Numbers and Latin words must be traceable to the conversation. */
        private val GROUNDING_TOKEN_REGEX = Regex("""\d+|[A-Za-z]{2,}""")

        /**
         * Auto-apply a model "update" action as supersede-with-history: insert
         * the new version, then archive the old record CAS-bound to the
         * snapshot revision the model saw. Returns false on a stale snapshot —
         * the caller then hands the intent to review instead of overwriting.
         */
        internal suspend fun applyExtractionSupersede(
            target: MemoryRecord,
            candidate: MemoryCandidate,
            write: suspend (id: Int, content: String, expectedRevision: Long) -> Unit,
        ): Boolean = try {
            write(target.id, candidate.content, target.revision)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Stale CAS, a raced archive, or any rejected target check hands
            // the intent to pending review — one bad target never aborts the
            // extraction run.
            false
        }

        /**
         * Auto-apply a model "invalidate" action: archive the target CAS-bound
         * to the prompt-time revision. False on stale — review decides then.
         */
        internal suspend fun applyExtractionInvalidate(
            target: MemoryRecord,
            write: suspend (id: Int, expectedRevision: Long) -> Unit,
        ): Boolean = try {
            write(target.id, target.revision)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
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
