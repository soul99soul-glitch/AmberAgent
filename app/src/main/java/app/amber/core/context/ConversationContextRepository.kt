package app.amber.core.context

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import app.amber.ai.ui.UIMessage
import app.amber.agent.data.db.dao.ConversationCompactDAO
import app.amber.agent.data.db.dao.ConversationContextEventDAO
import app.amber.agent.data.db.entity.ConversationCompactEntity
import app.amber.agent.data.db.entity.ConversationContextEventEntity
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.core.utils.JsonInstant
import kotlin.uuid.Uuid

class ConversationContextRepository(
    private val compactDAO: ConversationCompactDAO,
    private val eventDAO: ConversationContextEventDAO,
    private val conversationRepository: ConversationRepository,
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
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

    suspend fun insertCompact(compact: ConversationCompact) = withDurableWrite {
        insertCompactInternal(compact)
    }

    suspend fun copyValidCompactsToConversation(
        sourceConversationId: Uuid,
        targetConversation: Conversation,
        reason: String,
    ): Int = withDurableWrite {
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
        if (eligibleCompacts.isEmpty()) return@withDurableWrite 0

        val idMapping = eligibleCompacts.associate { compact -> compact.id to Uuid.random().toString() }
        val now = System.currentTimeMillis()
        eligibleCompacts.forEach { compact ->
            insertCompactInternal(
                compact.copy(
                    id = idMapping.getValue(compact.id),
                    conversationId = targetConversation.id.toString(),
                    summary = CompactSummaryPayloads.remapCoveredCompactIds(compact.summary, idMapping),
                    updatedAt = now,
                )
            )
        }
        insertEventInternal(
            conversationId = targetConversation.id,
            eventType = reason,
            summaryId = null,
            message = "Copied ${eligibleCompacts.size} compact summaries from fork parent",
        )
        eligibleCompacts.size
    }

    suspend fun invalidateCompacts(conversationId: Uuid, reason: String) = withDurableWrite {
        compactDAO.deleteByConversation(conversationId.toString())
        insertEventInternal(conversationId, "compact_invalidated", null, reason)
    }

    suspend fun insertEvent(conversationId: Uuid, eventType: String, summaryId: String?, message: String) =
        withDurableWrite {
            insertEventInternal(conversationId, eventType, summaryId, message)
        }

    private suspend fun insertCompactInternal(compact: ConversationCompact) {
        compactDAO.insert(compact.toEntity())
    }

    private suspend fun insertEventInternal(
        conversationId: Uuid,
        eventType: String,
        summaryId: String?,
        message: String,
    ) {
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

    suspend fun search(
        conversationId: Uuid,
        query: String,
        limit: Int,
        role: String? = null,
    ): List<ContextSearchResult> = searchPage(conversationId, query, limit, role).results

    suspend fun searchPage(
        conversationId: Uuid,
        query: String,
        limit: Int,
        role: String? = null,
    ): ContextSearchPage {
        val conversation = conversationRepository.getConversationById(conversationId)
        val existingMessageIds = conversation?.messageNodes?.map { it.currentMessage.id.toString() }?.toSet()
        val rawCompacts = compactDAO.search(
            conversationId = conversationId.toString(),
            query = query,
            limit = Int.MAX_VALUE,
        )
        val coveredCompactIds = rawCompacts
            .flatMap { CompactSummaryPayloads.parse(it.summary)?.coveredCompactIds.orEmpty() }
            .toSet()
        val compactResults = rawCompacts
            .filter { it.id !in coveredCompactIds }
            .distinctBy { it.id }
            .map { entity ->
                val searchable = CompactSummaryPayloads.searchableText(entity.summary)
                ContextSearchResult(
                    source = "compact_summary",
                    id = entity.id,
                    preview = searchable.previewAround(query),
                    stale = ContextSearchStaleness.isStale(
                        sourceMessageIds = JsonInstant.decodeFromString(entity.sourceMessageIdsJson),
                        existingMessageIds = existingMessageIds,
                    ),
                    role = "compact_summary",
                )
            }
        val messageResults = conversation?.messageNodes?.mapIndexedNotNull { index, node ->
            val text = node.currentMessage.toText()
            if (!text.contains(query, ignoreCase = true)) return@mapIndexedNotNull null
            ContextSearchResult(
                source = "message",
                id = node.currentMessage.id.toString(),
                preview = text.previewAround(query),
                nodeIndex = index,
                role = node.currentMessage.role.name.lowercase(),
            )
        }.orEmpty()
        return ContextSearchBudget.deduplicateAndFilter(
            results = compactResults + messageResults,
            role = role,
            limit = limit,
        )
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

    /**
     * Expand with the stale marker the model needs to distrust a compact whose sources are gone.
     * A compact hit reports `stale` when none of its source messages survive in the transcript.
     */
    suspend fun expandResult(conversationId: Uuid, sourceId: String, radius: Int): ContextExpandResult {
        val conversation = conversationRepository.getConversationById(conversationId)
            ?: return ContextExpandResult(messages = emptyList(), stale = false)
        val compact = compactDAO.getCompactById(sourceId)?.toDomain()
        if (compact == null) {
            return ContextExpandResult(
                messages = expandWithin(conversation.currentMessages, sourceId, radius),
                stale = false,
            )
        }
        val ids = compact.sourceMessageIds.toSet()
        val existingIds = conversation.currentMessages.map { it.id.toString() }.toSet()
        return ContextExpandResult(
            messages = conversation.currentMessages.filter { it.id.toString() in ids },
            stale = ContextSearchStaleness.isStale(
                sourceMessageIds = compact.sourceMessageIds,
                existingMessageIds = existingIds,
            ),
        )
    }

    private fun expandWithin(messages: List<UIMessage>, sourceId: String, radius: Int): List<UIMessage> {
        val index = messages.indexOfFirst { it.id.toString() == sourceId }
        if (index < 0) return emptyList()
        val start = (index - radius.coerceIn(0, 8)).coerceAtLeast(0)
        val end = (index + radius.coerceIn(0, 8)).coerceAtMost(messages.lastIndex)
        return messages.subList(start, end + 1)
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

    private suspend fun <T> withDurableWrite(block: suspend () -> T): T {
        val gate = restoreWriteGate
        return if (gate == null) block() else gate.withCurrentWriterOrCancel(block)
    }
}
