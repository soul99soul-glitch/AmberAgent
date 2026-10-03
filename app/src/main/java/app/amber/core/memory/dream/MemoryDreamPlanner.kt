package app.amber.core.memory.dream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
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
import app.amber.core.memory.model.MemoryWorkerDreamGate
import kotlinx.serialization.Serializable
import app.amber.core.memory.model.MemoryRecord
import app.amber.core.memory.model.MemoryScope
import app.amber.core.memory.prompt.MemoryDreamPrompt
import app.amber.core.memory.store.MemoryProfileStore
import app.amber.core.memory.store.MemoryRepository
import app.amber.core.memory.telemetry.MemoryEventLogger
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext

interface MemoryDreamPlanProvider {
    suspend fun plan(): MemoryDreamPlanSplit
}

/**
 * The two plan sources kept separate so callers can auto-apply the
 * deterministic [maintenance] part while still sending the model-produced
 * [model] part through review. [merged] reproduces the legacy single-plan
 * view for paths that review everything together.
 */
@Serializable
data class MemoryDreamPlanSplit(
    val maintenance: MemoryDreamPlan = MemoryDreamPlan(),
    val model: MemoryDreamPlan? = null,
) {
    val hasChanges: Boolean
        get() = maintenance.hasChanges || model?.hasChanges == true

    fun merged(): MemoryDreamPlan = maintenance.mergeWith(model)
}

class MemoryDreamPlanner(
    private val settingsStore: SettingsAggregator,
    private val providerCatalog: ProviderCatalog,
    private val json: Json,
    private val memoryRepository: MemoryRepository,
    private val eventLogger: MemoryEventLogger,
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
    private val planStore: MemoryDreamPlanStore? = null,
    private val profileStore: MemoryProfileStore? = null,
) : MemoryDreamPlanProvider {
    override suspend fun plan(): MemoryDreamPlanSplit =
        withContext(captureWriteContext()) { planInternal() }

    private suspend fun planInternal(): MemoryDreamPlanSplit {
        val now = System.currentTimeMillis()
        val settings = settingsStore.settingsFlow.value
        val records = memoryRepository.getAllRecords()
        val candidates = memoryRepository.getPendingCandidates()
        val localPlan = if (MemoryWorkerDreamGate.isMaintenanceEnabled(settings.agentRuntime.memoryWorker)) {
            planMaintenance(records, candidates, now)
        } else {
            MemoryDreamPlan()
        }
        val modelPlan = try {
            planWithModel(settings, records, candidates)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }

        eventLogger.log(
            type = MemoryEventType.DREAM_PLANNED,
            // Count each source separately: the merged view caps ignore ids at 48
            // and would under-report what an auto-applied maintenance run did.
            message = "maintenance(${localPlan.countsText()}) model(${modelPlan?.countsText() ?: "none"})",
        )
        return MemoryDreamPlanSplit(maintenance = localPlan, model = modelPlan)
    }

    private suspend fun captureWriteContext(): CoroutineContext {
        coroutineContext[SyncRestoreWriteEpoch]?.let { return it }
        val gate = restoreWriteGate ?: return EmptyCoroutineContext
        // A manual/background owner without a token waits for an active restore
        // to finish before taking its snapshot. The no-op lock is released
        // before the model call, so restore is never held behind network work.
        gate.withWriter { Unit }
        return SyncRestoreWriteEpoch(gate.currentEpoch())
    }

    private suspend fun planWithModel(
        settings: Settings,
        records: List<MemoryRecord>,
        candidates: List<MemoryCandidate>,
    ): MemoryDreamPlan? {
        val worker = settings.agentRuntime.memoryWorker
        if (!worker.enabled || !MemoryWorkerDreamGate.isModelDreamEnabled(worker)) return null
        val model = resolveDaydreamModel(settings) ?: return null
        val provider = model.findProvider(settings.providers) ?: return null
        val now = System.currentTimeMillis()
        // Expired records are maintenance zombies — recall can't see them and
        // the plan archives them, so the model has no use for them either.
        val promptRecords = records
            .filter { !it.archived && it.expiresAt.let { exp -> exp == null || exp > now } }
            .take(80)
        // Lexically-similar pairs are surfaced so the model can resolve them
        // by semantics: merge true duplicates, supersede outdated records, or
        // leave them. Pairs a recent plan already suggested are not re-flagged
        // — repeating the same hint every run is churn.
        val recentPlans = runCatching {
            planStore?.getRecentPlans(RECENT_PLAN_HISTORY).orEmpty()
        }.getOrDefault(emptyList())
        val nearDuplicatePairs = findNearDuplicatePairs(promptRecords)
            .filterNot { pair -> pairHandledByPlans(pair, recentPlans) }
        val currentProfile = runCatching { profileStore?.get() }.getOrNull()
        val response = providerCatalog.text(provider).complete(
            providerSetting = provider,
            messages = listOf(
                UIMessage.user(
                    MemoryDreamPrompt.build(
                        records = promptRecords,
                        candidates = candidates.take(50),
                        nearDuplicatePairs = nearDuplicatePairs,
                        currentProfile = currentProfile,
                    )
                )
            ),
            params = TextGenerationParams(
                model = model,
                reasoningLevel = worker.daydreamReasoningLevel,
            ),
        )
        val text = response.choices.firstOrNull()?.message?.toText().orEmpty()
        return parseModelPlanJson(text, records, candidates, json)
    }

    private fun resolveDaydreamModel(settings: Settings) =
        settings.agentRuntime.memoryWorker.let { worker ->
            when {
                worker.daydreamModelId != DEFAULT_AUTO_MODEL_ID ->
                    settings.resolveTaskChatModel(worker.daydreamModelId)

                worker.daydreamFollowCompressModel ->
                    settings.resolveTaskChatModel(settings.compressModelId)

                worker.modelId != DEFAULT_AUTO_MODEL_ID ->
                    settings.resolveTaskChatModel(worker.modelId)

                worker.followCompressModel ->
                    settings.resolveTaskChatModel(settings.compressModelId)

                else -> settings.resolveTaskChatModel(settings.chatModelId)
            }
        } ?: settings.resolveTaskChatModel(settings.compressModelId)
            ?: settings.resolveTaskChatModel(settings.chatModelId)

    companion object {
        fun planLocally(
            records: List<MemoryRecord>,
            candidates: List<MemoryCandidate>,
            now: Long = System.currentTimeMillis(),
        ): MemoryDreamPlan = planMaintenance(records, candidates, now)

        fun planMaintenance(
            records: List<MemoryRecord>,
            candidates: List<MemoryCandidate>,
            now: Long = System.currentTimeMillis(),
        ): MemoryDreamPlan {
            val activeRecords = records.filterNot { it.archived }
            // Expired records are zombies: invisible to recall, unreachable by
            // review — archive them in any scope. Pinned and topic records stay
            // user-controlled.
            val expiredRecords = activeRecords.filter { record ->
                record.kind != MemoryKind.TOPIC &&
                    !record.pinned &&
                    record.expiresAt?.let { it <= now } == true
            }
            // Topics are dream-synthesized views keyed by title; verbatim
            // content duplication is not a merge signal for them.
            val duplicateGroups = activeRecords
                .filter { it.kind != MemoryKind.TOPIC }
                .groupBy { normalize(it.content) }
                .values
                .filter { group -> group.size > 1 && group.maxOf { it.content.length } >= 8 }
                .map { group ->
                    val sorted = group.sortedWith(
                        compareByDescending<MemoryRecord> { it.pinned }
                            .thenByDescending { it.scope == MemoryScope.LONG_TERM }
                            .thenByDescending { it.confidence }
                            .thenByDescending { it.updatedAt }
                    )
                    MemoryMergeSuggestion(
                        targetMemoryId = sorted.first().id,
                        duplicateMemoryIds = sorted.drop(1).map { it.id },
                        mergedContent = sorted.first().content,
                        reason = "内容高度重复，保留可信度或层级更高的一条。",
                    )
                }
            // Promotion requires reinforcement: surfaced/recalled at least
            // twice. A single recall is not evidence the fact is durable.
            val promoteIds = activeRecords
                .filter { record ->
                    record.scope == MemoryScope.SHORT_TERM &&
                        record.kind == MemoryKind.PROJECT &&
                        record.expiresAt == null &&
                        record.confidence >= 0.82f &&
                        record.useCount >= PROMOTE_MIN_USE_COUNT
                }
                .map { it.id }
            // The other side of the lifecycle: a short-term record nobody ever
            // used and nobody touched for a month is stale, not durable. Pinned
            // and time-bound records are exempt on purpose.
            val promoteIdSet = promoteIds.toSet()
            val staleShortTerm = activeRecords
                .filter { record ->
                    record.scope == MemoryScope.SHORT_TERM &&
                        record.kind != MemoryKind.TOPIC &&
                        !record.pinned &&
                        record.expiresAt == null &&
                        record.lastUsedAt.let { it == null || it < now - STALE_SHORT_TERM_MS } &&
                        record.updatedAt < now - STALE_SHORT_TERM_MS &&
                        // Reinforcement wins over staleness: a record used
                        // repeatedly promotes this run, it never archives.
                        record.id !in promoteIdSet
                }
                .map { it.id }
            val activeNormalized = activeRecords.map { normalize(it.content) }.toSet()
            val noisyCandidateIds = candidates
                .filter { candidate ->
                    candidate.content.trim().length < 12 ||
                        candidate.confidence < 0.45f ||
                        normalize(candidate.content) in activeNormalized
                }
                .map { it.id }
            // Pending candidates that expired or sat unreviewed past the TTL
            // are auto-ignored — the queue must drain itself, not grow forever.
            val staleCandidateIds = candidates
                .filter { candidate ->
                    candidate.expiresAt?.let { it <= now } == true ||
                        candidate.createdAt <= now - PENDING_CANDIDATE_TTL_MS
                }
                .map { it.id }
            val flaggedCandidateIds = (noisyCandidateIds + staleCandidateIds).toSet()
            // Soft cap on the pending queue: once exceeded, drop the
            // lowest-confidence (then oldest) overflow after other flags.
            val overflowCount = (candidates.size - MAX_PENDING_CANDIDATES) - flaggedCandidateIds.size
            val overflowCandidateIds = if (overflowCount > 0) {
                candidates
                    .filter { it.id !in flaggedCandidateIds }
                    .sortedWith(
                        compareBy<MemoryCandidate> { it.confidence }.thenBy { it.createdAt }
                    )
                    .take(overflowCount)
                    .map { it.id }
            } else {
                emptyList()
            }
            return MemoryDreamPlan(
                mergeSuggestions = duplicateGroups,
                promoteMemoryIds = promoteIds.distinct(),
                archiveMemoryIds = (expiredRecords.map { it.id } + staleShortTerm).distinct(),
                ignoreCandidateIds = (noisyCandidateIds + staleCandidateIds + overflowCandidateIds).distinct(),
                notes = buildList {
                    if (duplicateGroups.isNotEmpty()) add("发现 ${duplicateGroups.size} 组可能重复的记忆，可合并后归档副本。")
                    if (promoteIds.isNotEmpty()) add("发现 ${promoteIds.size} 条反复使用的短期项目记忆，可提升为长期记忆。")
                    if (expiredRecords.isNotEmpty()) add("发现 ${expiredRecords.size} 条过期记忆，可归档。")
                    if (staleShortTerm.isNotEmpty()) add("发现 ${staleShortTerm.size} 条长期未被使用的短期记忆，可归档。")
                    if (noisyCandidateIds.isNotEmpty()) add("发现 ${noisyCandidateIds.size} 条低价值或重复候选，可忽略。")
                    if (staleCandidateIds.isNotEmpty()) add("发现 ${staleCandidateIds.size} 条已过期或长期未审批的候选，可忽略。")
                    if (overflowCandidateIds.isNotEmpty()) add("待审批候选超出上限，可忽略置信度最低的 ${overflowCandidateIds.size} 条。")
                },
            )
        }

        /** Pending candidates older than this are auto-ignored during maintenance. */
        internal const val PENDING_CANDIDATE_TTL_MS: Long = 14L * 24 * 60 * 60 * 1000

        /** Short-term promotion requires this many reinforcements. */
        internal const val PROMOTE_MIN_USE_COUNT: Int = 2

        /** Short-term records unused and untouched this long are archived as stale. */
        internal const val STALE_SHORT_TERM_MS: Long = 30L * 24 * 60 * 60 * 1000

        /** Soft cap on the pending-candidate queue; overflow drops lowest confidence first. */
        internal const val MAX_PENDING_CANDIDATES: Int = 100

        /** Jaccard similarity at or above this flags a pair as near-duplicate. */
        internal const val NEAR_DUP_THRESHOLD: Float = 0.45f

        /** Near-duplicate pairs surfaced to the model prompt per run. */
        internal const val MAX_NEAR_DUP_PAIRS: Int = 12

        /** Plan history scanned when suppressing already-suggested pairs. */
        private const val RECENT_PLAN_HISTORY: Int = 8

        /** A synthesized profile shorter than this is not meaningful. */
        private const val MIN_PROFILE_CHARS: Int = 16

        /** Hard cap on the model's profile output before it is persisted. */
        private const val MAX_PROFILE_CHARS: Int = 2_000

        private fun normalize(text: String): String =
            text.lowercase().filter { it.isLetterOrDigit() }.take(200)

        /**
         * Exact-normalize dedupe catches verbatim copies only. This scans
         * active, non-topic records for lexical near-duplicates so the model
         * can decide semantically: merge, supersede, or leave alone.
         */
        internal fun findNearDuplicatePairs(
            records: List<MemoryRecord>,
            threshold: Float = NEAR_DUP_THRESHOLD,
        ): List<NearDuplicatePair> {
            val managed = records.filter { !it.archived && it.kind != MemoryKind.TOPIC }
            val pairs = mutableListOf<NearDuplicatePair>()
            for (i in managed.indices) {
                for (j in i + 1 until managed.size) {
                    val score = jaccardSimilarity(managed[i].content, managed[j].content)
                    if (score >= threshold) {
                        pairs += NearDuplicatePair(managed[i].id, managed[j].id, score)
                    }
                }
            }
            return pairs.sortedByDescending { it.similarity }.take(MAX_NEAR_DUP_PAIRS)
        }

        /**
         * Mixed-script Jaccard: whitespace/punctuation word tokens for Latin
         * text plus character bigrams for CJK runs (CJK has no word spacing).
         */
        internal fun jaccardSimilarity(a: String, b: String): Float {
            val tokensA = memoryTokens(a)
            val tokensB = memoryTokens(b)
            if (tokensA.isEmpty() || tokensB.isEmpty()) return 0f
            val union = tokensA.union(tokensB).size
            if (union == 0) return 0f
            return tokensA.intersect(tokensB).size.toFloat() / union
        }

        private fun memoryTokens(text: String): Set<String> {
            val normalized = text.lowercase()
            val wordTokens = WORD_TOKEN_REGEX.findAll(normalized)
                .map { it.value }
                .filter { it.length > 1 }
                .toSet()
            val cjkBigrams = CJK_RUN_REGEX.findAll(normalized)
                .flatMap { match ->
                    match.value.windowed(2).ifEmpty { listOf(match.value) }
                }
                .toSet()
            return wordTokens + cjkBigrams
        }

        private val WORD_TOKEN_REGEX = Regex("[a-z0-9_]+")
        private val CJK_RUN_REGEX = Regex("[\\u4e00-\\u9fff]+")

        /**
         * A pair is "handled" when a recent plan already proposed a merge over
         * it or a supersede touching either side — re-flagging it is churn.
         */
        internal fun pairHandledByPlans(
            pair: NearDuplicatePair,
            plans: List<MemoryDreamPlan>,
        ): Boolean = plans.any { plan ->
            plan.supersedeSuggestions.any { suggestion ->
                pair.firstId in suggestion.oldMemoryIds ||
                    pair.secondId in suggestion.oldMemoryIds
            } || plan.mergeSuggestions.any { suggestion ->
                val covered = setOf(suggestion.targetMemoryId) + suggestion.duplicateMemoryIds
                pair.firstId in covered && pair.secondId in covered
            }
        }

        internal fun parseModelPlanJson(
            raw: String,
            records: List<MemoryRecord>,
            candidates: List<MemoryCandidate>,
            json: Json = Json,
        ): MemoryDreamPlan {
            val memoryIds = records.map { it.id }.toSet()
            val candidateIds = candidates.map { it.id }.toSet()
            val managedIds = records.filter { record ->
                !record.archived &&
                    record.scope != MemoryScope.CORE &&
                    record.kind != MemoryKind.TOPIC
            }.map { it.id }.toSet()
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
            val merges = root["merge"]?.jsonArray.orEmpty().mapNotNull { item ->
                val obj = item.jsonObject
                val target = obj["target_memory_id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: return@mapNotNull null
                if (target !in memoryIds) return@mapNotNull null
                val duplicates = obj["duplicate_memory_ids"]?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() }
                    .filter { it in memoryIds && it != target }
                    .distinct()
                if (duplicates.isEmpty()) return@mapNotNull null
                MemoryMergeSuggestion(
                    targetMemoryId = target,
                    duplicateMemoryIds = duplicates,
                    mergedContent = obj["merged_content"]?.jsonPrimitive?.contentOrNull,
                    reason = obj["reason"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
            val supersedes = root["supersede"]?.jsonArray.orEmpty().mapNotNull { item ->
                val obj = item.jsonObject
                val oldIds = obj["old_memory_ids"]?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() }
                    .filter { it in memoryIds }
                    .distinct()
                if (oldIds.isEmpty()) return@mapNotNull null
                val newContent = obj["new_content"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.length >= 8 }
                    ?: return@mapNotNull null
                MemorySupersedeSuggestion(
                    oldMemoryIds = oldIds,
                    newContent = newContent,
                    scope = MemoryScope.fromWireName(obj["scope"]?.jsonPrimitive?.contentOrNull),
                    kind = MemoryKind.fromWireName(obj["kind"]?.jsonPrimitive?.contentOrNull),
                    confidence = obj["confidence"]?.jsonPrimitive?.floatOrNull ?: 0.7f,
                    reason = obj["reason"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
            val userProfile = root["user_profile"]?.jsonObject?.let { obj ->
                val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.length >= MIN_PROFILE_CHARS }
                    ?: return@let null
                MemoryProfileSuggestion(
                    content = content.take(MAX_PROFILE_CHARS),
                    reason = obj["reason"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
            return MemoryDreamPlan(
                mergeSuggestions = merges,
                userProfile = userProfile,
                promoteMemoryIds = root["promote"]?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() }
                    .filter { it in memoryIds }
                    .distinct(),
                archiveMemoryIds = root["archive"]?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() }
                    .filter { it in memoryIds }
                    .distinct(),
                ignoreCandidateIds = root["delete_suggestions"]?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNull }
                    .filter { it in candidateIds }
                    .distinct(),
                supersedeSuggestions = supersedes,
                // Topic members must be dream-managed records: active, non-core,
                // and never another topic (no nesting).
                topicSuggestions = root["topics"]?.jsonArray.orEmpty().mapNotNull { item ->
                    val obj = item.jsonObject
                    val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?.takeIf { it.length >= 8 } ?: return@mapNotNull null
                    val memberIds = obj["member_memory_ids"]?.jsonArray.orEmpty()
                        .mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() }
                        .filter { it in managedIds }
                        .distinct()
                    if (memberIds.size < 2) return@mapNotNull null
                    MemoryTopicSuggestion(
                        title = title.take(80),
                        memberMemoryIds = memberIds,
                        content = content.take(2_000),
                        reason = obj["reason"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                }.take(4),
                notes = root["notes"]?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNull?.take(240) }
                    .take(6),
            )
        }
    }
}

private fun MemoryDreamPlan.countsText(): String =
    "merge=${mergeSuggestions.size},promote=${promoteMemoryIds.size}," +
        "archive=${archiveMemoryIds.size},supersede=${supersedeSuggestions.size}," +
        "ignore=${ignoreCandidateIds.size},topics=${topicSuggestions.size}"

internal fun MemoryDreamPlan.mergeWith(other: MemoryDreamPlan?): MemoryDreamPlan {
    if (other == null) return this
    val localTargets = mergeSuggestions.map { it.targetMemoryId to it.duplicateMemoryIds.toSet() }.toSet()
    val modelMerges = other.mergeSuggestions.filterNot { suggestion ->
        (suggestion.targetMemoryId to suggestion.duplicateMemoryIds.toSet()) in localTargets
    }
    return copy(
        mergeSuggestions = (mergeSuggestions + modelMerges).take(12),
        promoteMemoryIds = (promoteMemoryIds + other.promoteMemoryIds).distinct().take(24),
        archiveMemoryIds = (archiveMemoryIds + other.archiveMemoryIds).distinct().take(48),
        ignoreCandidateIds = (ignoreCandidateIds + other.ignoreCandidateIds).distinct().take(48),
        supersedeSuggestions = (supersedeSuggestions + other.supersedeSuggestions)
            .distinctBy { it.oldMemoryIds.toSet() to it.newContent }
            .take(12),
        topicSuggestions = (topicSuggestions + other.topicSuggestions)
            .distinctBy { it.title.lowercase().filter(Char::isLetterOrDigit) }
            .take(8),
        userProfile = userProfile ?: other.userProfile,
        notes = (notes + other.notes).distinct().take(12),
    )
}

@Serializable
data class MemoryDreamPlan(
    val mergeSuggestions: List<MemoryMergeSuggestion> = emptyList(),
    val promoteMemoryIds: List<Int> = emptyList(),
    val archiveMemoryIds: List<Int> = emptyList(),
    val ignoreCandidateIds: List<String> = emptyList(),
    val supersedeSuggestions: List<MemorySupersedeSuggestion> = emptyList(),
    val topicSuggestions: List<MemoryTopicSuggestion> = emptyList(),
    val userProfile: MemoryProfileSuggestion? = null,
    val notes: List<String> = emptyList(),
) {
    val hasChanges: Boolean
        get() = mergeSuggestions.isNotEmpty() ||
            promoteMemoryIds.isNotEmpty() ||
            archiveMemoryIds.isNotEmpty() ||
            ignoreCandidateIds.isNotEmpty() ||
            supersedeSuggestions.isNotEmpty() ||
            topicSuggestions.isNotEmpty() ||
            userProfile != null
}

/**
 * Model-produced user-profile synthesis: a short derived summary of durable
 * user traits and preferences. Reviewable like every other model op — it is
 * written to the profile store only when its plan is applied.
 */
@Serializable
data class MemoryProfileSuggestion(
    val content: String,
    val reason: String = "",
)

/** A lexically-similar record pair surfaced to the dream model prompt. */
data class NearDuplicatePair(
    val firstId: Int,
    val secondId: Int,
    val similarity: Float,
)

/**
 * Model-produced topic synthesis: group related memories under a named topic
 * document. Semantic output — always goes through pending review, never the
 * auto-applied maintenance path.
 */
@Serializable
data class MemoryTopicSuggestion(
    val title: String,
    val memberMemoryIds: List<Int>,
    val content: String,
    val reason: String = "",
)

@Serializable
data class MemoryMergeSuggestion(
    val targetMemoryId: Int,
    val duplicateMemoryIds: List<Int>,
    val mergedContent: String? = null,
    val reason: String = "",
)

@Serializable
data class MemorySupersedeSuggestion(
    val oldMemoryIds: List<Int>,
    val newContent: String,
    val scope: MemoryScope,
    val kind: MemoryKind,
    val confidence: Float = 0.7f,
    val reason: String = "",
)
