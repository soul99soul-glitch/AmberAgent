package me.rerere.rikkahub.data.context

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.dao.ConversationCompactDAO
import me.rerere.rikkahub.data.db.dao.ConversationContextEventDAO
import me.rerere.rikkahub.data.db.entity.ConversationCompactEntity
import me.rerere.rikkahub.data.db.entity.ConversationContextEventEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

class ConversationContextRepository(
    private val compactDAO: ConversationCompactDAO,
    private val eventDAO: ConversationContextEventDAO,
    private val conversationRepository: ConversationRepository,
) {
    fun getCompactsFlow(conversationId: Uuid): Flow<List<ConversationCompact>> {
        return compactDAO.getCompactsOfConversation(conversationId.toString()).map { list ->
            list.map { it.toDomain() }
        }
    }

    suspend fun getCompacts(conversationId: Uuid): List<ConversationCompact> {
        return compactDAO.getCompactsOfConversationOnce(conversationId.toString()).map { it.toDomain() }
    }

    suspend fun getCompact(id: String): ConversationCompact? {
        return compactDAO.getCompactById(id)?.toDomain()
    }

    suspend fun insertCompact(compact: ConversationCompact) {
        compactDAO.insert(compact.toEntity())
    }

    suspend fun copyValidCompactsToConversation(
        sourceConversationId: Uuid,
        targetConversation: Conversation,
        reason: String,
    ): Int {
        val targetMessageIds = targetConversation.currentMessages
            .map { it.id.toString() }
            .toSet()
        val targetLastIndex = targetConversation.messageNodes.lastIndex
        val eligibleCompacts = getCompacts(sourceConversationId)
            .filter { compact ->
                compact.status == "completed" &&
                    compact.sourceEndIndex <= targetLastIndex &&
                    compact.sourceMessageIds.isNotEmpty() &&
                    compact.sourceMessageIds.all { it in targetMessageIds }
            }
        if (eligibleCompacts.isEmpty()) return 0

        val idMapping = eligibleCompacts.associate { compact -> compact.id to Uuid.random().toString() }
        val now = System.currentTimeMillis()
        eligibleCompacts.forEach { compact ->
            insertCompact(
                compact.copy(
                    id = idMapping.getValue(compact.id),
                    conversationId = targetConversation.id.toString(),
                    summary = CompactSummaryPayloads.remapCoveredCompactIds(compact.summary, idMapping),
                    updatedAt = now,
                )
            )
        }
        insertEvent(
            conversationId = targetConversation.id,
            eventType = reason,
            summaryId = null,
            message = "Copied ${eligibleCompacts.size} compact summaries from fork parent",
        )
        return eligibleCompacts.size
    }

    suspend fun invalidateCompacts(conversationId: Uuid, reason: String) {
        compactDAO.deleteByConversation(conversationId.toString())
        insertEvent(conversationId, "compact_invalidated", null, reason)
    }

    suspend fun insertEvent(conversationId: Uuid, eventType: String, summaryId: String?, message: String) {
        eventDAO.insert(
            ConversationContextEventEntity(
                id = Uuid.random().toString(),
                conversationId = conversationId.toString(),
                eventType = eventType,
                summaryId = summaryId,
                message = message.take(2_000),
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun search(conversationId: Uuid, query: String, limit: Int): List<ContextSearchResult> {
        val compactResults = compactDAO.search(
            conversationId = conversationId.toString(),
            query = query,
            limit = limit.coerceIn(1, 20),
        ).map { entity ->
            val searchable = CompactSummaryPayloads.searchableText(entity.summary)
            ContextSearchResult(
                source = "compact_summary",
                id = entity.id,
                preview = searchable.previewAround(query),
            )
        }
        val conversation = conversationRepository.getConversationById(conversationId) ?: return compactResults
        val messageResults = conversation.messageNodes.mapIndexedNotNull { index, node ->
            val text = node.currentMessage.toText()
            if (!text.contains(query, ignoreCase = true)) return@mapIndexedNotNull null
            ContextSearchResult(
                source = "message",
                id = node.currentMessage.id.toString(),
                preview = text.previewAround(query),
                nodeIndex = index,
            )
        }.take((limit - compactResults.size).coerceAtLeast(0))
        return (compactResults + messageResults).take(limit)
    }

    suspend fun expand(conversationId: Uuid, sourceId: String, radius: Int): List<UIMessage> {
        val conversation = conversationRepository.getConversationById(conversationId) ?: return emptyList()
        val compact = compactDAO.getCompactById(sourceId)?.toDomain()
        if (compact != null) {
            val ids = compact.sourceMessageIds.toSet()
            return conversation.currentMessages.filter { it.id.toString() in ids }
        }
        val index = conversation.currentMessages.indexOfFirst { it.id.toString() == sourceId }
        if (index < 0) return emptyList()
        val start = (index - radius.coerceIn(0, 8)).coerceAtLeast(0)
        val end = (index + radius.coerceIn(0, 8)).coerceAtMost(conversation.currentMessages.lastIndex)
        return conversation.currentMessages.subList(start, end + 1)
    }

    private fun ConversationCompactEntity.toDomain() = ConversationCompact(
        id = id,
        conversationId = conversationId,
        summary = summary,
        level = level,
        sourceStartIndex = sourceStartIndex,
        sourceEndIndex = sourceEndIndex,
        sourceMessageIds = JsonInstant.decodeFromString(sourceMessageIdsJson),
        tokenEstimate = tokenEstimate,
        createdAt = createdAt,
        updatedAt = updatedAt,
        status = status,
    )

    private fun ConversationCompact.toEntity() = ConversationCompactEntity(
        id = id,
        conversationId = conversationId,
        summary = summary,
        level = level,
        sourceStartIndex = sourceStartIndex,
        sourceEndIndex = sourceEndIndex,
        sourceMessageIdsJson = JsonInstant.encodeToString(sourceMessageIds),
        tokenEstimate = tokenEstimate,
        createdAt = createdAt,
        updatedAt = updatedAt,
        status = status,
    )

    private fun String.previewAround(query: String): String {
        if (query.isBlank()) return take(240)
        val index = indexOf(query, ignoreCase = true)
        if (index < 0) return take(240)
        val start = (index - 120).coerceAtLeast(0)
        val end = (index + query.length + 120).coerceAtMost(length)
        return substring(start, end)
    }
}
