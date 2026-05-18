package me.rerere.rikkahub.data.db.fts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant

data class MessageSearchResult(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val assistantId: String,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
)

private const val TAG = "MessageFtsManager"

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        val conversationId = conversation.id.toString()
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
        insertNodes(
            conversationId = conversationId,
            title = conversation.title,
            updateAt = conversation.updateAt.toEpochMilli().toString(),
            nodes = conversation.messageNodes,
        )
    }

    suspend fun indexConversationNodes(conversation: Conversation) = withContext(Dispatchers.IO) {
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
        db.execSQL(
            "UPDATE message_fts SET title = ?, update_at = ? WHERE conversation_id = ?",
            arrayOf(title, updateAt.toEpochMilli().toString(), conversationId)
        )
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts")
    }

    suspend fun rebuildAllFromDatabase() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts")
        val cursor = db.query(
            """
            SELECT n.id, n.messages, n.conversation_id, c.title, c.update_at
            FROM message_node n
            JOIN conversationentity c ON c.id = n.conversation_id
            """.trimIndent()
        )
        cursor.use {
            while (it.moveToNext()) {
                val nodeId = it.getString(0)
                val messagesJson = it.getString(1)
                val conversationId = it.getString(2)
                val title = it.getString(3)
                val updateAt = it.getLong(4).toString()
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

    suspend fun search(keyword: String): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MessageSearchResult>()
        val cursor = db.query(
            """
            SELECT message_fts.node_id, message_fts.message_id, message_fts.conversation_id,
                   c.assistant_id, message_fts.title, message_fts.update_at,
                   simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
            FROM message_fts
            JOIN ConversationEntity c ON c.id = message_fts.conversation_id
            WHERE text MATCH jieba_query(?)
            ORDER BY rank, message_fts.update_at DESC
            LIMIT 50
            """.trimIndent(),
            arrayOf(keyword)
        )
        Log.i(TAG, "search: $keyword")
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    MessageSearchResult(
                        nodeId = it.getString(0),
                        messageId = it.getString(1),
                        conversationId = it.getString(2),
                        assistantId = it.getString(3),
                        title = it.getString(4),
                        updateAt = Instant.ofEpochMilli(it.getLong(5)),
                        snippet = it.getString(6),
                    )
                )
            }
        }
        results
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
