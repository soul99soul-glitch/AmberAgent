package app.amber.core.memory.extraction

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import app.amber.core.settings.DEFAULT_AUTO_MODEL_ID
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.findProvider
import app.amber.core.settings.resolveTaskChatModel
import app.amber.core.memory.model.MemoryCandidate
import app.amber.core.memory.model.MemoryEventType
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryScope
import app.amber.core.memory.prompt.MemoryExtractionPrompt
import app.amber.core.memory.store.MemoryRepository
import app.amber.core.memory.telemetry.MemoryEventLogger
import app.amber.core.model.Conversation
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

class MemoryExtractor(
    private val settingsStore: SettingsAggregator,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepository: MemoryRepository,
    private val eventLogger: MemoryEventLogger,
    private val candidateFilter: MemoryCandidateFilter = MemoryCandidateFilter(),
) {
    private val lastRunAt = ConcurrentHashMap<Uuid, Long>()

    suspend fun extractAfterConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
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
            val prompt = MemoryExtractionPrompt.build(
                messages = sourceMessages,
                sourceMessageIds = sourceIds,
                locale = Locale.getDefault().displayName,
            )
            val response = providerManager.getProviderByType(provider).generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = TextGenerationParams(model = model),
            )
            val text = response.choices.firstOrNull()?.message?.toText().orEmpty()
            val candidates = parseCandidates(
                raw = text,
                conversationId = conversationId,
                sourceMessageIds = sourceIds,
            )
            val filtered = candidateFilter.filter(candidates, memoryRepository.getAllActiveRecords())
            memoryRepository.addCandidates(filtered.rejected)
            filtered.accepted.forEach { candidate ->
                val autoWrite = candidate.scope == MemoryScope.SHORT_TERM &&
                    candidate.kind == MemoryKind.PROJECT &&
                    candidate.confidence >= 0.72f
                if (autoWrite) {
                    val memory = memoryRepository.addMemory(
                        scope = candidate.scope,
                        kind = candidate.kind,
                        content = candidate.content,
                        sourceConversationId = candidate.sourceConversationId,
                        sourceMessageIds = candidate.sourceMessageIds,
                        expiresAt = candidate.expiresAt,
                        confidence = candidate.confidence,
                    )
                    eventLogger.log(
                        type = MemoryEventType.MEMORY_CREATED,
                        conversationId = conversationId,
                        memoryId = memory.id,
                        modelId = model.id.toString(),
                        message = "Auto-created short-term project memory.",
                    )
                } else {
                    memoryRepository.addCandidate(candidate)
                    eventLogger.log(
                        type = MemoryEventType.CANDIDATE_CREATED,
                        conversationId = conversationId,
                        candidateId = candidate.id,
                        modelId = model.id.toString(),
                        message = candidate.reason,
                    )
                }
            }
        }.onFailure { error ->
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

    private fun resolveMemoryModel(settings: Settings) =
        when {
            settings.agentRuntime.memoryWorker.modelId != DEFAULT_AUTO_MODEL_ID ->
                settings.resolveTaskChatModel(settings.agentRuntime.memoryWorker.modelId)

            settings.agentRuntime.memoryWorker.followCompressModel ->
                settings.resolveTaskChatModel(settings.compressModelId)

            else -> settings.resolveTaskChatModel(settings.chatModelId)
        } ?: settings.resolveTaskChatModel(settings.chatModelId)

    private fun parseCandidates(
        raw: String,
        conversationId: String,
        sourceMessageIds: List<String>,
    ): List<MemoryCandidate> {
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
            MemoryCandidate(
                content = content,
                scope = MemoryScope.fromWireName(obj["scope"]?.jsonPrimitive?.contentOrNull),
                kind = MemoryKind.fromWireName(obj["kind"]?.jsonPrimitive?.contentOrNull),
                confidence = obj["confidence"]?.jsonPrimitive?.floatOrNull ?: 0.55f,
                reason = obj["reason"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                sourceConversationId = conversationId,
                sourceMessageIds = sourceMessageIds,
                expiresAt = expiresInDays?.let { System.currentTimeMillis() + it * 86_400_000L },
            )
        }
    }
}
