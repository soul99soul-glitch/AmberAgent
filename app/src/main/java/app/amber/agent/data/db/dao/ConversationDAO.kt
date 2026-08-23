package app.amber.agent.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import app.amber.agent.data.db.entity.ConversationEntity
import app.amber.core.repository.LightConversationEntity

/**
 * 每个会话的可见消息数：message_node_stat 每节点一行，COUNT(*) 即节点数。
 * 当前可见消息 = 每节点选中分支一条（Conversation.currentMessages 口径），故用 COUNT(*)
 * 而非 SUM(total_messages)——后者把重新生成的替代分支也计入，会让计数虚增。
 */
private const val MESSAGE_COUNT_COLUMN =
    "(SELECT COUNT(*) FROM message_node_stat " +
        "WHERE conversation_id = conversationentity.id) AS messageCount"

/**
 * node_index 最大节点（最后节点）的 messages JSON 中「最后一条消息」的文本预览。
 * - 不用 `$[#-1]` 负索引：SQLite 的 `#` 路径语法 3.31+ 才有，且仅能出现在路径末尾；
 *   且负索引在旧版本会直接抛 "bad JSON path" 错误，空数组无法兜底。
 * - 改用 json_array_length 拼索引，并对空数组/缺失 parts 用 CASE 钳制到 0（正越界返回 NULL，安全）。
 * - 兜底链路：最后一条消息的最后一个 part → 第一个 part → 空串。
 */
private const val LAST_MESSAGE_INDEX =
    "(CASE WHEN json_array_length(messages) > 0 THEN json_array_length(messages) - 1 ELSE 0 END)"
private const val LAST_PART_INDEX =
    "(CASE WHEN json_array_length(json_extract(messages, '$[' || " + LAST_MESSAGE_INDEX + " || '].parts')) > 0 " +
        "THEN json_array_length(json_extract(messages, '$[' || " + LAST_MESSAGE_INDEX + " || '].parts')) - 1 " +
        "ELSE 0 END)"
private const val LAST_MESSAGE_PREVIEW_COLUMN =
    // 外层 COALESCE 必须保留：会话没有任何 message_node 行时标量子查询返回 NULL，
    // Room 对非空 String 字段 getString 会直接 NPE 炸掉整个分页。
    "COALESCE((SELECT substr(COALESCE(" +
        "NULLIF(json_extract(messages, '$[' || " + LAST_MESSAGE_INDEX + " || '].parts[' || " + LAST_PART_INDEX + " || '].text'), ''), " +
        "NULLIF(json_extract(messages, '$[' || " + LAST_MESSAGE_INDEX + " || '].parts[0].text'), ''), " +
        "''), 1, 200) " +
        "FROM message_node WHERE conversation_id = conversationentity.id " +
        "ORDER BY node_index DESC LIMIT 1), '') AS lastMessagePreview"

private const val CONVERSATION_SUMMARY_EXTRA_COLUMNS = ", " + MESSAGE_COUNT_COLUMN + ", " + LAST_MESSAGE_PREVIEW_COLUMN

@Dao
interface ConversationDAO {
    @Query("SELECT * FROM conversationentity ORDER BY is_pinned DESC, update_at DESC")
    fun getAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversationentity ORDER BY is_pinned DESC, update_at DESC")
    fun getAllPaging(): PagingSource<Int, ConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationsOfAssistant(assistantId: String): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt" +
        CONVERSATION_SUMMARY_EXTRA_COLUMNS +
        " FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationsOfAssistantPaging(assistantId: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC LIMIT :limit")
    suspend fun getRecentConversationsOfAssistant(assistantId: String, limit: Int): List<ConversationEntity>

    @Query("SELECT * FROM conversationentity ORDER BY is_pinned DESC, update_at DESC LIMIT :limit")
    suspend fun getRecentConversations(limit: Int): List<ConversationEntity>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt" +
        CONVERSATION_SUMMARY_EXTRA_COLUMNS +
        " FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC LIMIT :limit")
    suspend fun getRecentConversationSummariesOfAssistant(assistantId: String, limit: Int): List<LightConversationEntity>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt" +
        CONVERSATION_SUMMARY_EXTRA_COLUMNS +
        " FROM conversationentity ORDER BY is_pinned DESC, update_at DESC LIMIT :limit")
    suspend fun getRecentConversationSummaries(limit: Int): List<LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE title LIKE '%' || :searchText || '%' ESCAPE '\\' ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversations(searchText: String): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt" +
        CONVERSATION_SUMMARY_EXTRA_COLUMNS +
        " FROM conversationentity WHERE title LIKE '%' || :searchText || '%' ESCAPE '\\' ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversationsPaging(searchText: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId AND title LIKE '%' || :searchText || '%' ESCAPE '\\' ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversationsOfAssistant(assistantId: String, searchText: String): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt" +
        CONVERSATION_SUMMARY_EXTRA_COLUMNS +
        " FROM conversationentity WHERE assistant_id = :assistantId AND title LIKE '%' || :searchText || '%' ESCAPE '\\' ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversationsOfAssistantPaging(assistantId: String, searchText: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE id = :id")
    fun getConversationFlowById(id: String): Flow<ConversationEntity?>

    @Query("SELECT id FROM conversationentity")
    suspend fun getAllIds(): List<String>

    @Query("SELECT * FROM conversationentity WHERE id = :id")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt" +
        CONVERSATION_SUMMARY_EXTRA_COLUMNS +
        " FROM conversationentity WHERE id = :id")
    suspend fun getConversationSummaryById(id: String): LightConversationEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM conversationentity WHERE id = :id)")
    suspend fun existsById(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(conversation: ConversationEntity): Long

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query("UPDATE conversationentity SET title = :title, update_at = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

    @Query("UPDATE conversationentity SET suggestions = :chatSuggestions, update_at = :updatedAt WHERE id = :id")
    suspend fun updateChatSuggestions(id: String, chatSuggestions: String, updatedAt: Long)

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    @Query("UPDATE conversationentity SET nodes = '[]' WHERE id = :id")
    suspend fun resetConversationNodes(id: String)

    @Query("DELETE FROM conversationentity WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM conversationentity")
    suspend fun deleteAll()

    @Query("SELECT * FROM conversationentity WHERE is_pinned = 1 ORDER BY update_at DESC")
    fun getPinnedConversations(): Flow<List<ConversationEntity>>

    @Query("UPDATE conversationentity SET is_pinned = :isPinned WHERE id = :id")
    suspend fun updatePinStatus(id: String, isPinned: Boolean)

    /** 原子翻转置顶位：避免为翻转一个布尔加载整条会话 + 读-改-写竞态。 */
    @Query("UPDATE conversationentity SET is_pinned = NOT is_pinned WHERE id = :id")
    suspend fun togglePinStatus(id: String)

    /**
     * Lightweight update of just the council_state column. Avoids rewriting the
     * whole ConversationEntity (with its node blob) on every council state change.
     */
    @Query("UPDATE conversationentity SET council_state = :councilState, update_at = :updatedAt WHERE id = :id")
    suspend fun updateCouncilState(id: String, councilState: String?, updatedAt: Long)

    @Query("SELECT council_state FROM conversationentity WHERE id = :id")
    suspend fun getCouncilState(id: String): String?

    @Query("SELECT council_state FROM conversationentity WHERE id = :id")
    fun observeCouncilState(id: String): Flow<String?>

    /** P8-08：聚合所有持久化 Council Room 的轻量投影（id + council_state JSON）。 */
    @Query(
        "SELECT id, council_state AS councilState FROM conversationentity " +
            "WHERE council_state IS NOT NULL AND council_state != ''"
    )
    fun observeCouncilStates(): Flow<List<ConversationCouncilRow>>

    @Query("SELECT COUNT(*) FROM conversationentity")
    suspend fun countAll(): Int

    @Query(
        "SELECT strftime('%Y-%m-%d', create_at/1000, 'unixepoch', 'localtime') AS day, " +
            "COUNT(*) AS count " +
            "FROM conversationentity " +
            "WHERE create_at >= :startMillis " +
            "GROUP BY day"
    )
    suspend fun getConversationCountPerDay(startMillis: Long): List<ConversationDayCount>
}

data class ConversationDayCount(val day: String, val count: Int)

/** P8-08：conversationentity 上 Council Room 持久化的轻量投影。 */
data class ConversationCouncilRow(
    val id: String,
    val councilState: String,
)
