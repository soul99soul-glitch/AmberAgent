package app.amber.core.repository

import android.database.sqlite.SQLiteBlobTooBigException
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.MessageStatsDAO
import me.rerere.rikkahub.data.db.dao.findNodeIdContainingMessage
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageDayStatEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeStatEntity
import app.amber.core.files.FilesManager
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val messageStatsDAO: MessageStatsDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val filesManager: FilesManager,
    private val messageFtsManager: MessageFtsManager,
) {
    companion object {
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
    }

    suspend fun getRecentConversations(assistantId: Uuid, limit: Int = 10): List<Conversation> {
        return conversationDAO.getRecentConversationsOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit
        ).map { entity ->
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        }
    }

    suspend fun getRecentConversations(limit: Int = 10): List<Conversation> {
        return conversationDAO.getRecentConversations(limit).map { entity ->
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        }
    }

    suspend fun getRecentConversationSummaries(assistantId: Uuid, limit: Int = 10): List<Conversation> {
        return conversationDAO.getRecentConversationSummariesOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit
        ).map(::conversationSummaryToConversation)
    }

    suspend fun getRecentConversationSummaries(limit: Int = 10): List<Conversation> {
        return conversationDAO.getRecentConversationSummaries(limit)
            .map(::conversationSummaryToConversation)
    }

    fun getConversationsOfAssistant(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString())
            .map { flow ->
                flow.map { entity ->
                    // 列表视图不需要完整的 nodes，使用空列表
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun getConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    suspend fun getConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.getConversationsOfAssistantPaging(assistantId.toString())
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun searchConversationsOfAssistantPage(
        assistantId: Uuid,
        titleKeyword: String,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.searchConversationsOfAssistantPaging(
            assistantId = assistantId.toString(),
            searchText = titleKeyword
        )
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    fun searchConversations(titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversations(titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsPaging(titleKeyword: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.searchConversationsPaging(titleKeyword) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun searchConversationsOfAssistant(assistantId: Uuid, titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversationsOfAssistant(assistantId.toString(), titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsOfAssistantPaging(assistantId: Uuid, titleKeyword: String): Flow<PagingData<Conversation>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = INITIAL_LOAD_SIZE,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                conversationDAO.searchConversationsOfAssistantPaging(
                    assistantId.toString(),
                    titleKeyword
                )
            }
        ).flow.map { pagingData ->
            pagingData.map { entity ->
                conversationSummaryToConversation(entity)
            }
        }

    suspend fun getConversationById(uuid: Uuid): Conversation? {
        val entity = conversationDAO.getConversationById(uuid.toString())
        return if (entity != null) {
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        } else null
    }

    suspend fun getConversationSummaryById(uuid: Uuid): Conversation? {
        return conversationDAO.getConversationSummaryById(uuid.toString())
            ?.let(::conversationSummaryToConversation)
    }

    suspend fun getConversationTailById(uuid: Uuid, limit: Int): ConversationWindow? {
        val entity = conversationDAO.getConversationById(uuid.toString()) ?: return null
        val total = messageNodeDAO.countNodesOfConversation(entity.id)
        val safeLimit = limit.coerceAtLeast(0)
        val offset = (total - safeLimit).coerceAtLeast(0)
        val nodes = loadMessageNodesPage(
            conversationId = entity.id,
            limit = safeLimit,
            offset = offset,
        )
        return ConversationWindow(
            conversation = conversationEntityToConversation(entity, nodes),
            totalNodeCount = total,
            oldestLoadedIndex = offset,
        )
    }

    suspend fun getConversationNodePage(
        conversationId: Uuid,
        offset: Int,
        limit: Int,
    ): List<MessageNode> {
        return loadMessageNodesPage(
            conversationId = conversationId.toString(),
            offset = offset.coerceAtLeast(0),
            limit = limit.coerceAtLeast(0),
        )
    }

    suspend fun getConversationNodeRange(
        conversationId: Uuid,
        startIndex: Int,
        endIndex: Int,
    ): List<MessageNode> {
        if (endIndex < startIndex) return emptyList()
        val entities = messageNodeDAO.getNodesOfConversationRange(
            conversationId = conversationId.toString(),
            startIndex = startIndex.coerceAtLeast(0),
            endIndex = endIndex.coerceAtLeast(0),
        )
        return decodeMessageNodeEntities(entities)
    }

    suspend fun getConversationNodeIndex(
        conversationId: Uuid,
        nodeId: String,
    ): Int? {
        return messageNodeDAO.getNodeIndex(
            conversationId = conversationId.toString(),
            nodeId = nodeId,
        )
    }

    suspend fun findNodeIdForMessage(
        conversationId: Uuid,
        messageId: String,
    ): String? {
        return messageFtsManager.findNodeIdForMessage(
            conversationId = conversationId.toString(),
            messageId = messageId,
        )
    }

    suspend fun findNodeIdContainingMessage(
        conversationId: Uuid,
        messageId: String,
    ): String? {
        return messageNodeDAO.findNodeIdContainingMessage(
            conversationId = conversationId.toString(),
            messageId = messageId,
        )
    }

    suspend fun countConversationNodes(conversationId: Uuid): Int {
        return messageNodeDAO.countNodesOfConversation(conversationId.toString())
    }

    suspend fun existsConversationById(uuid: Uuid): Boolean {
        return conversationDAO.existsById(uuid.toString())
    }

    suspend fun insertConversation(conversation: Conversation) {
        database.withTransaction {
            conversationDAO.insert(
                conversationToConversationEntity(conversation)
            )
            saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
        }
        messageFtsManager.indexConversation(conversation)
    }

    suspend fun updateConversation(conversation: Conversation) {
        database.withTransaction {
            conversationDAO.update(
                conversationToConversationEntity(conversation)
            )
            // 删除旧的节点，插入新的节点
            messageNodeDAO.deleteByConversation(conversation.id.toString())
            saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
        }
        messageFtsManager.indexConversation(conversation)
    }

    suspend fun upsertConversationWindow(
        conversation: Conversation,
        firstNodeIndex: Int,
        indexFts: Boolean,
    ) {
        val conversationId = conversation.id.toString()
        val startIndex = firstNodeIndex.coerceAtLeast(0)
        val endIndex = startIndex + conversation.messageNodes.size - 1
        val overwrittenNodeIds = database.withTransaction {
            conversationDAO.update(
                conversationToConversationEntity(conversation)
            )
            val oldNodeIds = if (endIndex >= startIndex) {
                messageNodeDAO.getNodeIdsOfConversationRange(
                    conversationId = conversationId,
                    startIndex = startIndex,
                    endIndex = endIndex,
                )
            } else {
                emptyList()
            }
            if (endIndex >= startIndex) {
                messageNodeDAO.deleteByConversationRange(
                    conversationId = conversationId,
                    startIndex = startIndex,
                    endIndex = endIndex,
                )
            }
            saveMessageNodes(
                conversationId = conversationId,
                nodes = conversation.messageNodes,
                firstNodeIndex = startIndex,
            )
            oldNodeIds
        }
        if (indexFts) {
            if (overwrittenNodeIds.isNotEmpty()) {
                messageFtsManager.deleteNodeIds(overwrittenNodeIds)
            }
            messageFtsManager.indexConversationNodes(conversation)
        }
    }

    suspend fun updateConversationMetadata(
        conversationId: Uuid,
        title: String? = null,
        chatSuggestions: List<String>? = null,
        updateAt: Instant = Instant.now(),
    ) {
        val entity = conversationDAO.getConversationById(conversationId.toString()) ?: return
        val updatedTitle = title ?: entity.title
        conversationDAO.update(
            entity.copy(
                title = updatedTitle,
                chatSuggestions = chatSuggestions?.let { JsonInstant.encodeToString(it) }
                    ?: entity.chatSuggestions,
                updateAt = updateAt.toEpochMilli(),
            )
        )
        messageFtsManager.updateConversationMetadata(
            conversationId = conversationId.toString(),
            title = updatedTitle,
            updateAt = updateAt,
        )
    }

    suspend fun deleteConversation(conversation: Conversation) {
        // 获取完整的 Conversation（包含 messageNodes）以正确清理文件
        val fullConversation = if (conversation.messageNodes.isEmpty()) {
            getConversationById(conversation.id) ?: conversation
        } else {
            conversation
        }
        messageFtsManager.deleteConversation(conversation.id.toString())
        database.withTransaction {
            // message_node 会通过 CASCADE 自动删除
            conversationDAO.delete(
                conversationToConversationEntity(conversation)
            )
        }
        filesManager.deleteChatFiles(fullConversation.files)
        // Drop any generate_image tool output bound to this conversation.
        // The dir is `filesDir/chat_images/{conversationId}/` and is created
        // lazily on first generation — deleteRecursively no-ops when missing.
        filesManager.deleteChatImagesDir(conversation.id)
    }

    suspend fun searchMessages(keyword: String) = messageFtsManager.search(keyword)

    suspend fun rebuildAllIndexes(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
        messageFtsManager.deleteAll()
        val allIds = conversationDAO.getAllIds()
        val total = allIds.size
        allIds.forEachIndexed { index, id ->
            val entity = conversationDAO.getConversationById(id) ?: return@forEachIndexed
            val nodes = loadMessageNodes(entity.id)
            val conversation = conversationEntityToConversation(entity, nodes)
            messageFtsManager.indexConversation(conversation)
            onProgress(index + 1, total)
        }
    }

    suspend fun deleteConversationOfAssistant(assistantId: Uuid) {
        getConversationsOfAssistant(assistantId).first().forEach { conversation ->
            deleteConversation(conversation)
        }
    }

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        require(conversation.messageNodes.none { it.messages.any { message -> message.hasBase64Part() } })
        return ConversationEntity(
            id = conversation.id.toString(),
            title = conversation.title,
            nodes = "[]",  // nodes 现在存储在单独的表中
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            assistantId = conversation.assistantId.toString(),
            chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
            isPinned = conversation.isPinned,
            autoApproveToolCalls = conversation.autoApproveToolCalls,
        )
    }

    fun conversationEntityToConversation(
        conversationEntity: ConversationEntity,
        messageNodes: List<MessageNode>
    ): Conversation {
        return Conversation(
            id = Uuid.parse(conversationEntity.id),
            title = conversationEntity.title,
            messageNodes = messageNodes.filter { it.messages.isNotEmpty() },
            createAt = Instant.ofEpochMilli(conversationEntity.createAt),
            updateAt = Instant.ofEpochMilli(conversationEntity.updateAt),
            assistantId = Uuid.parse(conversationEntity.assistantId),
            chatSuggestions = JsonInstant.decodeFromString(conversationEntity.chatSuggestions),
            isPinned = conversationEntity.isPinned,
            autoApproveToolCalls = conversationEntity.autoApproveToolCalls,
        )
    }

    fun getPinnedConversations(): Flow<List<Conversation>> {
        return conversationDAO
            .getPinnedConversations()
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    suspend fun togglePinStatus(conversationId: Uuid) {
        conversationDAO.updatePinStatus(
            id = conversationId.toString(),
            isPinned = !(getConversationById(conversationId)?.isPinned ?: false)
        )
    }

    private fun conversationSummaryToConversation(entity: LightConversationEntity): Conversation {
        return Conversation(
            id = Uuid.parse(entity.id),
            assistantId = Uuid.parse(entity.assistantId),
            title = entity.title,
            isPinned = entity.isPinned,
            createAt = Instant.ofEpochMilli(entity.createAt),
            updateAt = Instant.ofEpochMilli(entity.updateAt),
            messageNodes = emptyList(),
        )
    }

    private suspend fun loadMessageNodes(conversationId: String): List<MessageNode> {
        val total = messageNodeDAO.countNodesOfConversation(conversationId)
        if (total <= 0) return emptyList()
        return loadMessageNodesRange(conversationId = conversationId, offset = 0, total = total)
    }

    private suspend fun loadMessageNodesPage(
        conversationId: String,
        offset: Int,
        limit: Int,
    ): List<MessageNode> {
        if (limit <= 0) return emptyList()
        return loadMessageNodesRange(
            conversationId = conversationId,
            offset = offset,
            total = offset + limit,
            pageLimit = limit,
        )
    }

    private suspend fun loadMessageNodesRange(
        conversationId: String,
        offset: Int,
        total: Int,
        pageLimit: Int = 64,
    ): List<MessageNode> {
        val favoriteNodeIds = favoriteDAO
            .getFavoriteNodeIdsOfConversation(conversationId)
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .toSet()

        return database.withTransaction {
            val nodes = mutableListOf<MessageNode>()
            var currentOffset = offset.coerceAtLeast(0)
            val endExclusive = total.coerceAtLeast(currentOffset)
            val pageSize = pageLimit.coerceAtLeast(1).coerceAtMost(64)
            while (currentOffset < endExclusive) {
                val currentLimit = (endExclusive - currentOffset).coerceAtMost(pageSize)
                val page = try {
                    messageNodeDAO.getNodesOfConversationPaged(conversationId, currentLimit, currentOffset)
                } catch (e: SQLiteBlobTooBigException) {
                    e.printStackTrace()
                    currentOffset += currentLimit
                    continue
                }
                if (page.isEmpty()) break
                page.forEach { entity ->
                    val messages = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages)
                    val nodeId = Uuid.parse(entity.id)
                    nodes.add(
                        MessageNode(
                            id = nodeId,
                            messages = messages,
                            selectIndex = entity.selectIndex,
                            isFavorite = favoriteNodeIds.contains(nodeId)
                        )
                    )
                }
                currentOffset += page.size
            }
            nodes
        }
    }

    private fun decodeMessageNodeEntities(entities: List<MessageNodeEntity>): List<MessageNode> {
        return entities.mapNotNull { entity ->
            runCatching {
                MessageNode(
                    id = Uuid.parse(entity.id),
                    messages = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages),
                    selectIndex = entity.selectIndex,
                )
            }.getOrNull()
        }
    }

    private suspend fun saveMessageNodes(
        conversationId: String,
        nodes: List<MessageNode>,
        firstNodeIndex: Int = 0,
    ) {
        val entities = nodes.mapIndexed { index, node ->
            MessageNodeEntity(
                id = node.id.toString(),
                conversationId = conversationId,
                nodeIndex = firstNodeIndex + index,
                messages = JsonInstant.encodeToString(node.messages),
                selectIndex = node.selectIndex
            )
        }
        messageNodeDAO.insertAll(entities)
        messageStatsDAO.insertNodeStats(
            nodes.map { node ->
                node.toNodeStat(
                    conversationId = conversationId,
                )
            }
        )
        messageStatsDAO.insertDayStats(
            nodes.flatMap { node ->
                node.toDayStats()
            }
        )
    }

    private fun MessageNode.toNodeStat(conversationId: String): MessageNodeStatEntity {
        return MessageNodeStatEntity(
            nodeId = id.toString(),
            conversationId = conversationId,
            totalMessages = messages.size,
            promptTokens = messages.sumOf { it.usage?.promptTokens?.toLong() ?: 0L },
            completionTokens = messages.sumOf { it.usage?.completionTokens?.toLong() ?: 0L },
            cachedTokens = messages.sumOf { it.usage?.cachedTokens?.toLong() ?: 0L },
        )
    }

    private fun MessageNode.toDayStats(): List<MessageDayStatEntity> {
        return messages
            .asSequence()
            .filter { it.role == MessageRole.USER }
            .groupingBy { it.createdAt.date.toString() }
            .eachCount()
            .map { (day, count) ->
                MessageDayStatEntity(
                    nodeId = id.toString(),
                    day = day,
                    count = count,
                )
            }
    }
}

/**
 * 轻量级的会话查询结果，不包含 nodes 和 suggestions 字段
 */
data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
)

data class ConversationPageResult(
    val items: List<Conversation>,
    val nextOffset: Int?,
)

data class ConversationWindow(
    val conversation: Conversation,
    val totalNodeCount: Int,
    val oldestLoadedIndex: Int,
)
