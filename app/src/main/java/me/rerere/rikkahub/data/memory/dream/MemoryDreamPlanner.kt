package me.rerere.rikkahub.data.memory.dream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.DEFAULT_AUTO_MODEL_ID
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.resolveTaskChatModel
import me.rerere.rikkahub.data.memory.model.MemoryCandidate
import me.rerere.rikkahub.data.memory.model.MemoryEventType
import me.rerere.rikkahub.data.memory.model.MemoryKind
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.memory.model.MemoryRecord
import me.rerere.rikkahub.data.memory.model.MemoryScope
import me.rerere.rikkahub.data.memory.prompt.MemoryDreamPrompt
import me.rerere.rikkahub.data.memory.store.MemoryRepository
import me.rerere.rikkahub.data.memory.telemetry.MemoryEventLogger

class MemoryDreamPlanner(
    private val settingsStore: SettingsAggregator,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepository: MemoryRepository,
    private val eventLogger: MemoryEventLogger,
) {
    suspend fun plan(): MemoryDreamPlan {
        val now = System.currentTimeMillis()
        val records = memoryRepository.getAllRecords()
        val candidates = memoryRepository.getPendingCandidates()
        val localPlan = planLocally(records, candidates, now)
        val modelPlan = runCatching {
            planWithModel(settingsStore.settingsFlow.value, records, candidates)
        }.getOrNull()

        val plan = localPlan.merge(modelPlan)
        eventLogger.log(
            type = MemoryEventType.DREAM_PLANNED,
            message = buildString {
                append("merge=${plan.mergeSuggestions.size}, promote=${plan.promoteMemoryIds.size}, ")
                append("archive=${plan.archiveMemoryIds.size}, ignore=${plan.ignoreCandidateIds.size}")
            },
        )
        return plan
    }

    private suspend fun planWithModel(
        settings: Settings,
        records: List<MemoryRecord>,
        candidates: List<MemoryCandidate>,
    ): MemoryDreamPlan? {
        val worker = settings.agentRuntime.memoryWorker
        if (!worker.enabled || !worker.dreamEnabled) return null
        val model = resolveDaydreamModel(settings) ?: return null
        val provider = model.findProvider(settings.providers) ?: return null
        val response = providerManager.getProviderByType(provider).generateText(
            providerSetting = provider,
            messages = listOf(
                UIMessage.user(
                    MemoryDreamPrompt.build(
                        records = records.filterNot { it.archived }.take(80),
                        candidates = candidates.take(50),
                    )
                )
            ),
            params = TextGenerationParams(
                model = model,
                reasoningLevel = worker.daydreamReasoningLevel,
            ),
        )
        val text = response.choices.firstOrNull()?.message?.toText().orEmpty()
        return parseModelPlan(text, records, candidates)
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

    private fun parseModelPlan(
        raw: String,
        records: List<MemoryRecord>,
        candidates: List<MemoryCandidate>,
    ): MemoryDreamPlan {
        val memoryIds = records.map { it.id }.toSet()
        val candidateIds = candidates.map { it.id }.toSet()
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
        return MemoryDreamPlan(
            mergeSuggestions = merges,
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
            notes = root["notes"]?.jsonArray.orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull?.take(240) }
                .take(6),
        )
    }

    private fun MemoryDreamPlan.merge(other: MemoryDreamPlan?): MemoryDreamPlan {
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
            notes = (notes + other.notes).distinct().take(12),
        )
    }

    companion object {
        fun planLocally(
            records: List<MemoryRecord>,
            candidates: List<MemoryCandidate>,
            now: Long = System.currentTimeMillis(),
        ): MemoryDreamPlan {
            val activeRecords = records.filterNot { it.archived }
            val expiredProjects = records.filter { record ->
                !record.archived &&
                    record.scope == MemoryScope.SHORT_TERM &&
                    record.expiresAt?.let { it <= now } == true
            }
            val duplicateGroups = activeRecords
                .groupBy { normalize(it.content) }
                .values
                .filter { group -> group.size > 1 && group.first().content.length >= 8 }
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
            val promoteIds = activeRecords
                .filter { record ->
                    record.scope == MemoryScope.SHORT_TERM &&
                        record.kind == MemoryKind.PROJECT &&
                        record.expiresAt == null &&
                        record.confidence >= 0.82f &&
                        record.lastUsedAt != null
                }
                .map { it.id }
            val noisyCandidateIds = candidates
                .filter { candidate ->
                    candidate.content.trim().length < 12 ||
                        candidate.confidence < 0.45f ||
                        normalize(candidate.content) in activeRecords.map { normalize(it.content) }.toSet()
                }
                .map { it.id }
            return MemoryDreamPlan(
                mergeSuggestions = duplicateGroups,
                promoteMemoryIds = promoteIds.distinct(),
                archiveMemoryIds = expiredProjects.map { it.id }.distinct(),
                ignoreCandidateIds = noisyCandidateIds.distinct(),
                notes = buildList {
                    if (duplicateGroups.isNotEmpty()) add("发现 ${duplicateGroups.size} 组可能重复的记忆，可合并后归档副本。")
                    if (promoteIds.isNotEmpty()) add("发现 ${promoteIds.size} 条反复使用的短期项目记忆，可提升为长期记忆。")
                    if (expiredProjects.isNotEmpty()) add("发现 ${expiredProjects.size} 条过期短期项目记忆，可归档。")
                    if (noisyCandidateIds.isNotEmpty()) add("发现 ${noisyCandidateIds.size} 条低价值或重复候选，可忽略。")
                },
            )
        }

        private fun normalize(text: String): String =
            text.lowercase().filter { it.isLetterOrDigit() }.take(200)
    }
}

@Serializable
data class MemoryDreamPlan(
    val mergeSuggestions: List<MemoryMergeSuggestion> = emptyList(),
    val promoteMemoryIds: List<Int> = emptyList(),
    val archiveMemoryIds: List<Int> = emptyList(),
    val ignoreCandidateIds: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
) {
    val hasChanges: Boolean
        get() = mergeSuggestions.isNotEmpty() ||
            promoteMemoryIds.isNotEmpty() ||
            archiveMemoryIds.isNotEmpty() ||
            ignoreCandidateIds.isNotEmpty()
}

@Serializable
data class MemoryMergeSuggestion(
    val targetMemoryId: Int,
    val duplicateMemoryIds: List<Int>,
    val mergedContent: String? = null,
    val reason: String = "",
)
