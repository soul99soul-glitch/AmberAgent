package app.amber.agent.data.db.fts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.agent.data.db.AppDatabase
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.utils.JsonInstant
import java.time.Instant

/**
 * P8-04: 命中来源。标题命中（只有会话 title 匹配）没有具体消息，
 * nodeId/messageId 为 null，点击应打开会话而非跳转消息。
 */
enum class SearchHitSource { TITLE, BODY }

data class MessageSearchResult(
    val nodeId: String?,
    val messageId: String?,
    val conversationId: String,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
    val hitSource: SearchHitSource = SearchHitSource.BODY,
    /** True when the conversation title matched, including a body/title merge. */
    val titleMatched: Boolean = hitSource == SearchHitSource.TITLE,
)

/**
 * P8-04: 合并正文命中与标题命中。同一会话同时命中时保留正文命中
 * （可跳转具体消息，信息量更大），但保留标题命中元数据供过滤使用。
 */
internal fun mergeMessageSearchResults(
    bodyHits: List<MessageSearchResult>,
    titleHits: List<MessageSearchResult>,
): List<MessageSearchResult> {
    val bodyConversationIds = HashSet<String>(bodyHits.size)
    bodyHits.forEach { bodyConversationIds.add(it.conversationId) }
    val titleConversationIds = titleHits.mapTo(HashSet(titleHits.size)) { it.conversationId }
    return bodyHits.map { body ->
        if (body.conversationId in titleConversationIds) body.copy(titleMatched = true) else body
    } + titleHits
        .filter { it.conversationId !in bodyConversationIds }
        .map { it.copy(titleMatched = true) }
}

private const val TAG = "MessageFtsManager"

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        indexConversationInTransaction(conversation)
    }

    /**
     * 不切换 dispatcher 的版本：可在 Room `withTransaction` 块内直接调用，
     * 使 FTS 写入与会话写入同属一个事务，避免崩溃窗口导致索引漂移。
     */
    fun indexConversationInTransaction(conversation: Conversation) {
        val conversationId = conversation.id.toString()
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
        insertNodes(
            conversationId = conversationId,
            title = conversation.title,
            updateAt = conversation.updateAt.toEpochMilli().toString(),
            nodes = conversation.messageNodes,
        )
        indexConversationTitleInTransaction(
            conversationId = conversationId,
            title = conversation.title,
            updateAt = conversation.updateAt.toEpochMilli().toString(),
        )
    }

    suspend fun indexConversationNodes(conversation: Conversation) = withContext(Dispatchers.IO) {
        indexConversationNodesInTransaction(conversation)
    }

    /** 见 [indexConversationInTransaction]。 */
    fun indexConversationNodesInTransaction(conversation: Conversation) {
        val conversationId = conversation.id.toString()
        conversation.messageNodes.forEach { node ->
            db.execSQL("DELETE FROM message_fts WHERE node_id = ?", arrayOf(node.id.toString()))
        }
        insertNodes(
            conversationId = conversationId,
            title = conversation.title,
            updateAt = conversation.updateAt.toEpochMilli().toString(),
            nodes = conversation.messageNodes,
        )
    }

    suspend fun deleteNodeIds(nodeIds: Collection<String>) = withContext(Dispatchers.IO) {
        deleteNodeIdsInTransaction(nodeIds)
    }

    /** 见 [indexConversationInTransaction]。 */
    fun deleteNodeIdsInTransaction(nodeIds: Collection<String>) {
        nodeIds.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .chunked(500)
            .forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                db.execSQL(
                    "DELETE FROM message_fts WHERE node_id IN ($placeholders)",
                    chunk.toTypedArray()
                )
            }
    }

    suspend fun updateConversationMetadata(conversationId: String, title: String, updateAt: Instant) = withContext(Dispatchers.IO) {
        updateConversationMetadataInTransaction(conversationId, title, updateAt)
    }

    fun updateConversationMetadataInTransaction(conversationId: String, title: String, updateAt: Instant) {
        val updateAtMillis = updateAt.toEpochMilli().toString()
        db.execSQL(
            "UPDATE message_fts SET title = ?, update_at = ? WHERE conversation_id = ?",
            arrayOf(title, updateAtMillis, conversationId)
        )
        // P8-04: 会话重命名后同步标题 FTS，避免旧标题继续命中。
        // 用 DELETE+INSERT 保证每个会话恰好一行（FTS5 无唯一约束，INSERT OR IGNORE 会重复）。
        db.execSQL("DELETE FROM conversation_title_fts WHERE conversation_id = ?", arrayOf(conversationId))
        if (title.isNotBlank()) {
            db.execSQL(
                "INSERT INTO conversation_title_fts(title, conversation_id, update_at) VALUES (?, ?, ?)",
                arrayOf(title, conversationId, updateAtMillis)
            )
        }
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        deleteConversationInTransaction(conversationId)
    }

    fun deleteConversationInTransaction(conversationId: String) {
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
        db.execSQL("DELETE FROM conversation_title_fts WHERE conversation_id = ?", arrayOf(conversationId))
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts")
        db.execSQL("DELETE FROM conversation_title_fts")
    }

    suspend fun rebuildAllFromDatabase() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts")
        db.execSQL("DELETE FROM conversation_title_fts")
        val cursor = db.query(
            """
            SELECT n.id, n.messages, n.conversation_id, c.title, c.update_at
            FROM message_node n
            JOIN conversationentity c ON c.id = n.conversation_id
            """.trimIndent()
        )
        cursor.use {
            val titleRows = HashMap<String, Pair<String, String>>()
            while (it.moveToNext()) {
                val nodeId = it.getString(0)
                val messagesJson = it.getString(1)
                val conversationId = it.getString(2)
                val title = it.getString(3)
                val updateAt = it.getLong(4).toString()
                titleRows[conversationId] = title to updateAt
                val messages = runCatching {
                    JsonInstant.decodeFromString<List<UIMessage>>(messagesJson)
                }.getOrElse { emptyList() }
                messages.forEach { message ->
                    val text = message.extractFtsText()
                    if (text.isNotBlank()) {
                        db.execSQL(
                            "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                            arrayOf(
                                text,
                                nodeId,
                                message.id.toString(),
                                conversationId,
                                title,
                                updateAt,
                            )
                        )
                    }
                }
            }
            titleRows.forEach { (conversationId, row) ->
                val (title, updateAt) = row
                if (title.isNotBlank()) {
                    db.execSQL(
                        "INSERT INTO conversation_title_fts(title, conversation_id, update_at) VALUES (?, ?, ?)",
                        arrayOf(title, conversationId, updateAt)
                    )
                }
            }
        }
    }

    private fun insertNodes(
        conversationId: String,
        title: String,
        updateAt: String,
        nodes: List<MessageNode>,
    ) {
        nodes.forEach { node ->
            node.messages.forEach { message ->
                val text = message.extractFtsText()
                if (text.isNotBlank()) {
                    db.execSQL(
                        "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                        arrayOf(
                            text,
                            node.id.toString(),
                            message.id.toString(),
                            conversationId,
                            title,
                            updateAt,
                        )
                    )
                }
            }
        }
    }

    /** 每个会话一行标题 FTS，与 message_fts 在同一事务/删除点同步维护。 */
    private fun indexConversationTitleInTransaction(
        conversationId: String,
        title: String,
        updateAt: String,
    ) {
        db.execSQL("DELETE FROM conversation_title_fts WHERE conversation_id = ?", arrayOf(conversationId))
        if (title.isNotBlank()) {
            db.execSQL(
                "INSERT INTO conversation_title_fts(title, conversation_id, update_at) VALUES (?, ?, ?)",
                arrayOf(title, conversationId, updateAt)
            )
        }
    }

    suspend fun search(keyword: String): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val bodyHits = queryBodyHits(keyword)
        val titleHits = queryTitleHits(keyword)
        mergeMessageSearchResults(bodyHits, titleHits)
    }

    private fun queryBodyHits(keyword: String): List<MessageSearchResult> {
        val results = mutableListOf<MessageSearchResult>()
        val cursor = try {
            db.query(
                """
            SELECT message_fts.node_id, message_fts.message_id, message_fts.conversation_id,
                   message_fts.title, message_fts.update_at,
                   simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
            FROM message_fts
            JOIN ConversationEntity c ON c.id = message_fts.conversation_id
            WHERE text MATCH jieba_query(?)
            ORDER BY rank, message_fts.update_at DESC
            LIMIT 50
            """.trimIndent(),
                arrayOf(keyword)
            )
        } catch (e: Exception) {
            Log.w(TAG, "FTS query failed for keyword '$keyword': ${e.message}")
            return results
        }
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    MessageSearchResult(
                        nodeId = it.getString(0),
                        messageId = it.getString(1),
                        conversationId = it.getString(2),
                        title = it.getString(3),
                        updateAt = Instant.ofEpochMilli(it.getLong(4)),
                        snippet = it.getString(5),
                        hitSource = SearchHitSource.BODY,
                    )
                )
            }
        }
        return results
    }

    /** P8-04: 标题命中——只返回会话级信息，nodeId/messageId 为 null。 */
    private fun queryTitleHits(keyword: String): List<MessageSearchResult> {
        val results = mutableListOf<MessageSearchResult>()
        val cursor = try {
            db.query(
                """
            SELECT conversation_title_fts.conversation_id,
                   conversation_title_fts.title, conversation_title_fts.update_at,
                   simple_snippet(conversation_title_fts, 0, '[', ']', '...', 30) AS snippet
            FROM conversation_title_fts
            JOIN ConversationEntity c ON c.id = conversation_title_fts.conversation_id
            WHERE title MATCH jieba_query(?)
            ORDER BY rank, conversation_title_fts.update_at DESC
            LIMIT 50
            """.trimIndent(),
                arrayOf(keyword)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Title FTS query failed for keyword '$keyword': ${e.message}")
            return results
        }
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    MessageSearchResult(
                        nodeId = null,
                        messageId = null,
                        conversationId = it.getString(0),
                        title = it.getString(1),
                        updateAt = Instant.ofEpochMilli(it.getLong(2)),
                        snippet = it.getString(3),
                        hitSource = SearchHitSource.TITLE,
                    )
                )
            }
        }
        return results
    }

    suspend fun findNodeIdForMessage(conversationId: String, messageId: String): String? = withContext(Dispatchers.IO) {
        val cursor = db.query(
            """
            SELECT node_id
            FROM message_fts
            WHERE conversation_id = ? AND message_id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(conversationId, messageId)
        )
        cursor.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }
}

private fun UIMessage.extractFtsText(): String =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .take(10_000)
