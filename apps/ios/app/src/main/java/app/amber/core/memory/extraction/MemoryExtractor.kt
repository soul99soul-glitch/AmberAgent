package app.amber.core.memory.extraction

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.amber.ai.provider.ProviderManager
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
import app.amber.core.memory.model.MemoryScope
import app.amber.core.memory.prompt.MemoryExtractionPrompt
import app.amber.core.memory.safety.isSensitiveMemoryContent
import app.amber.core.memory.store.MemoryRepository
import app.amber.core.memory.telemetry.MemoryEventLogger
import app.amber.core.memory.time.MemoryTimeAnchorParser
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
            val parsedCandidates = parseCandidates(
                raw = text,
                conversationId = conversationId,
                sourceMessageIds = sourceIds,
            )
            val parseMetaById = parsedCandidates.associateBy { it.candidate.id }
            val candidates = parsedCandidates.map { it.candidate }
            val filtered = candidateFilter.filter(candidates, memoryRepository.getAllActiveRecords())
            memoryRepository.addCandidates(filtered.rejected)
            filtered.accepted.forEach { candidate ->
                val meta = parseMetaById[candidate.id]
                val autoWrite = shouldAutoWriteCandidate(
                    candidate = candidate,
                    explicitScope = meta?.explicitScope == true,
                    explicitKind = meta?.explicitKind == true,
                )
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
            ParsedMemoryCandidate(
                explicitScope = scopeValue.isValidMemoryScope(),
                explicitKind = kindValue.isValidMemoryKind(),
                candidate = MemoryCandidate(
                    content = content,
                    scope = scope,
                    kind = MemoryKind.fromWireName(kindValue),
                    confidence = obj["confidence"]?.jsonPrimitive?.floatOrNull ?: 0.55f,
                    reason = obj["reason"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    sourceConversationId = conversationId,
                    sourceMessageIds = sourceMessageIds,
                    expiresAt = expiresAt,
                ),
            )
        }
    }

    private fun String?.isValidMemoryScope(): Boolean =
        MemoryScope.entries.any { it.wireName == this }

    private fun String?.isValidMemoryKind(): Boolean =
        MemoryKind.entries.any { it.wireName == this }

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

    private data class ParsedMemoryCandidate(
        val candidate: MemoryCandidate,
        val explicitScope: Boolean,
        val explicitKind: Boolean,
    )

    companion object {
        internal const val SHORT_TERM_PROJECT_AUTO_WRITE_CONFIDENCE = 0.72f
        internal const val DURABLE_AUTO_WRITE_CONFIDENCE = 0.85f

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
