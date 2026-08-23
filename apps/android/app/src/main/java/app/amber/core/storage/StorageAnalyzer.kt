package app.amber.core.storage

import android.content.Context
import app.amber.agent.data.db.AppDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * P7-03 存储占用分类统计：会话数据库、消息正文、附件、缓存的实际占用
 * （分类大小 + 数量）。只读分析，不做任何删除。
 */
data class StorageBreakdown(
    /** 会话数据库文件（amber_agent.db + wal + shm）占用。 */
    val databaseBytes: Long,
    /** 会话数量。 */
    val conversationCount: Int,
    /** 消息节点数量。 */
    val messageNodeCount: Int,
    /** 消息正文（message_node.messages JSON 列）实际字节。 */
    val messageBodyBytes: Long,
    /** 附件数量（managed_files 行数）。 */
    val attachmentCount: Int,
    /** 附件实际占用（managed_files.size_bytes 合计 + 磁盘附件目录）。 */
    val attachmentBytes: Long,
    /** 缓存目录（cacheDir）实际占用。 */
    val cacheBytes: Long,
)

class StorageAnalyzer(
    private val context: Context,
    private val database: AppDatabase,
) {
    suspend fun analyze(): StorageBreakdown = withContext(Dispatchers.IO) {
        val db = database.openHelper.readableDatabase
        val conversationCount = db.query("SELECT COUNT(*) FROM conversationentity").use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        val (messageNodeCount, messageBodyBytes) = db.query(
            "SELECT COUNT(*), COALESCE(SUM(LENGTH(messages)), 0) FROM message_node"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) to cursor.getLong(1) else 0 to 0L
        }
        val (attachmentCount, attachmentBytes) = db.query(
            "SELECT COUNT(*), COALESCE(SUM(size_bytes), 0) FROM managed_files"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) to cursor.getLong(1) else 0 to 0L
        }
        StorageBreakdown(
            databaseBytes = databaseFileBytes(),
            conversationCount = conversationCount,
            messageNodeCount = messageNodeCount,
            messageBodyBytes = messageBodyBytes,
            attachmentCount = attachmentCount,
            attachmentBytes = attachmentBytes,
            cacheBytes = directoryBytes(context.cacheDir),
        )
    }

    private fun databaseFileBytes(): Long {
        val dbFile = context.getDatabasePath(DB_NAME)
        return listOf(dbFile, File(dbFile.parentFile, "${DB_NAME}-wal"), File(dbFile.parentFile, "${DB_NAME}-shm"))
            .sumOf { if (it.isFile) it.length() else 0L }
    }

    private fun directoryBytes(dir: File): Long =
        if (!dir.isDirectory) 0L
        else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        private const val DB_NAME = "amber_agent"
    }
}
