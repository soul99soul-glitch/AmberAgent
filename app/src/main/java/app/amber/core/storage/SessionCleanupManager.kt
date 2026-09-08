package app.amber.core.storage

import android.content.Context
import androidx.room.withTransaction
import app.amber.agent.data.db.AppDatabase
import app.amber.core.files.FileFolders
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一个待清理会话的统计与附件集合。 */
data class ConversationCleanupTarget(
    val conversationId: String,
    val messageNodeCount: Int,
    /** managed_files.relative_path（`upload/...`），只含该会话消息实际引用的附件。 */
    val attachmentPaths: List<String>,
    /** 附件在 managed_files 中登记的字节数。 */
    val attachmentBytes: Long,
    /** 会话绑定的生成图目录（filesDir/chat_images/{conversationId}）字节。 */
    val chatImageBytes: Long,
)

/**
 * 清理 dry run 结果：**只统计不删除**。UI 展示后将同一对象交给
 * [SessionCleanupManager.execute]。
 */
data class CleanupDryRun(
    val cutoffAt: Long,
    val targets: List<ConversationCleanupTarget>,
) {
    val conversationCount: Int get() = targets.size
    val messageNodeCount: Int get() = targets.sumOf { it.messageNodeCount }
    val attachmentCount: Int get() = targets.sumOf { it.attachmentPaths.size }
    val attachmentBytes: Long get() = targets.sumOf { it.attachmentBytes }
    val estimatedBytes: Long get() = targets.sumOf { it.attachmentBytes + it.chatImageBytes }
}

data class CleanupResult(
    val conversationCount: Int,
    val messageNodeCount: Int,
    val attachmentCount: Int,
    val deletedBytes: Long,
)

/**
 * P7-03 按时间清理会话：条件 `update_at < cutoffAt` 且非 pinned（默认排除
 * pinned）。执行顺序保证可重试、不重复删、不留不可追踪状态：
 *
 * 1. 在一个 Room transaction 内重新选择并删除符合条件的 DB 会话记录；
 *    managed_files 暂留，避免物理清理失败时丢掉 Files 页面索引。
 * 2. 删除物理附件文件（含会话生成图目录）。
 * 3. 另一个 DB transaction 只删除已经成功删掉的 managed_files 路径；失败的
 *    路径保留，用户仍可从 Files 页面重试。
 *
 * DB 事务失败时不会发生物理删除；物理删除失败时会话已删，但对应文件记录
 * 保留，避免把失败的文件删除伪装成完整成功。
 */
class SessionCleanupManager(
    private val context: Context,
    private val database: AppDatabase,
) {
    suspend fun dryRun(cutoffAt: Long): CleanupDryRun = withContext(Dispatchers.IO) {
        buildPlan(database.openHelper.readableDatabase, cutoffAt)
    }

    /** 从当前数据库快照选择清理目标；调用方负责提供读或写连接。 */
    private fun buildPlan(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        cutoffAt: Long,
    ): CleanupDryRun {
        val targetIds = db.query(
            "SELECT id FROM conversationentity WHERE is_pinned = 0 AND update_at < ?",
            arrayOf(cutoffAt.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }
        val targetIdSet = targetIds.toSet()
        // A file can be referenced by more than one conversation (for example
        // after a fork or an imported history). Keep it alive when any
        // conversation outside this cleanup batch still references it.
        val retainedAttachmentPaths = db.query(
            "SELECT conversation_id, messages FROM message_node",
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    if (cursor.getString(0) !in targetIdSet) {
                        collectUploadPaths(cursor.getString(1), this)
                    }
                }
            }
        }
        val claimedPaths = mutableSetOf<String>()
        val targets = targetIds.map { conversationId ->
            val raw = buildTarget(db, conversationId)
            val paths = raw.attachmentPaths.filter { path ->
                path !in retainedAttachmentPaths && claimedPaths.add(path)
            }
            raw.copy(
                attachmentPaths = paths,
                attachmentBytes = managedBytes(db, paths),
            )
        }
        return CleanupDryRun(cutoffAt = cutoffAt, targets = targets)
    }

    /** 执行清理。入参仅作展示用途；实际删除以重新 dry run 的当前状态为准。 */
    suspend fun execute(plan: CleanupDryRun): CleanupResult = withContext(Dispatchers.IO) {
        // 选择和删除会话使用同一 DB transaction，避免在选择后又删掉/更新一部分
        // 会话。managed_files 留到物理删除成功后再清掉，失败时保留 Files 索引。
        val current = database.withTransaction {
            val db = database.openHelper.writableDatabase
            val selected = buildPlan(db, plan.cutoffAt)
            if (selected.targets.isEmpty()) {
                return@withTransaction selected
            }
            deleteDatabaseRows(db, selected)
            selected
        }
        if (current.targets.isEmpty()) {
            return@withContext CleanupResult(0, 0, 0, 0L)
        }
        val physical = deletePhysicalAttachments(current)
        forgetDeletedManagedFiles(physical.deletedUploadPaths)
        CleanupResult(
            conversationCount = current.conversationCount,
            messageNodeCount = current.messageNodeCount,
            attachmentCount = physical.deletedUploadPaths.size,
            deletedBytes = physical.deletedBytes,
        )
    }

    private fun deleteDatabaseRows(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        plan: CleanupDryRun,
    ) {
        plan.targets.forEach { target ->
            db.execSQL(
                "DELETE FROM message_fts WHERE conversation_id = ?",
                arrayOf(target.conversationId),
            )
            db.execSQL(
                "DELETE FROM conversation_title_fts WHERE conversation_id = ?",
                arrayOf(target.conversationId),
            )
            db.execSQL(
                "DELETE FROM conversation_draft WHERE conversation_id = ?",
                arrayOf(target.conversationId),
            )
            db.execSQL(
                "DELETE FROM favorites WHERE ref_key LIKE 'node:' || ? || ':%'",
                arrayOf(target.conversationId),
            )
            // message_node / message_node_stat / message_day_stat /
            // conversation_compact / conversation_context_event 由外键级联。
            db.execSQL(
                "DELETE FROM conversationentity WHERE id = ?",
                arrayOf(target.conversationId),
            )
        }
    }

    private fun buildTarget(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        conversationId: String,
    ): ConversationCleanupTarget {
        val messageNodeCount = db.query(
            "SELECT COUNT(*) FROM message_node WHERE conversation_id = ?",
            arrayOf(conversationId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        val paths = db.query(
            "SELECT messages FROM message_node WHERE conversation_id = ?",
            arrayOf(conversationId),
        ).use { cursor ->
            val found = linkedSetOf<String>()
            while (cursor.moveToNext()) {
                collectUploadPaths(cursor.getString(0), found)
            }
            found.toList()
        }
        return ConversationCleanupTarget(
            conversationId = conversationId,
            messageNodeCount = messageNodeCount,
            attachmentPaths = paths,
            attachmentBytes = managedBytes(db, paths),
            chatImageBytes = directoryBytes(File(context.filesDir, "${FileFolders.CHAT_IMAGES}/$conversationId")),
        )
    }

    private fun managedBytes(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        paths: List<String>,
    ): Long = if (paths.isEmpty()) 0L else db.query(
            "SELECT COALESCE(SUM(size_bytes), 0) FROM managed_files WHERE relative_path IN (" +
                paths.joinToString(",") { "?" } + ")",
            paths.toTypedArray(),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    /**
     * 从 message_node.messages JSON 提取 `upload/...` 附件相对路径。
     * 消息内附件 URL 为 `file:///…/files/upload/xxx`；正则只取 upload 段，
     * 最终只删除在 managed_files 有登记的路径（跨会话共享文件不在此列，
     * 保持最小清理边界）。
     */
    private fun collectUploadPaths(messagesJson: String, out: MutableSet<String>) {
        UPLOAD_PATH_REGEX.findAll(messagesJson).forEach { match ->
            out.add(match.groupValues[1])
        }
    }

    private data class PhysicalCleanupResult(
        val deletedUploadPaths: Set<String>,
        val deletedBytes: Long,
    )

    private fun deletePhysicalAttachments(plan: CleanupDryRun): PhysicalCleanupResult {
        val filesDir = context.filesDir
        val deletedUploadPaths = linkedSetOf<String>()
        var deletedBytes = 0L
        plan.targets.forEach { target ->
            target.attachmentPaths.forEach { path ->
                val file = File(filesDir, path)
                val size = if (file.isFile) file.length() else 0L
                if (!file.exists() || file.delete()) {
                    deletedUploadPaths += path
                    deletedBytes += size
                }
            }
            val chatImagesDir = File(filesDir, "${FileFolders.CHAT_IMAGES}/${target.conversationId}")
            val chatImageBytes = directoryBytes(chatImagesDir)
            if (!chatImagesDir.exists() || chatImagesDir.deleteRecursively()) {
                deletedBytes += chatImageBytes
            }
        }
        return PhysicalCleanupResult(
            deletedUploadPaths = deletedUploadPaths,
            deletedBytes = deletedBytes,
        )
    }

    private suspend fun forgetDeletedManagedFiles(paths: Set<String>) {
        if (paths.isEmpty()) return
        database.withTransaction {
            val db = database.openHelper.writableDatabase
            paths.forEach { path ->
                db.execSQL(
                    "DELETE FROM managed_files WHERE relative_path = ?",
                    arrayOf(path),
                )
            }
        }
    }

    private fun directoryBytes(dir: File): Long =
        if (!dir.isDirectory) 0L
        else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        private val UPLOAD_PATH_REGEX = Regex("files/(upload/[^\"\\\\]+)")
    }
}
