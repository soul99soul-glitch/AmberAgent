package app.amber.core.storage

import android.content.Context
import androidx.room.withTransaction
import app.amber.agent.data.db.AppDatabase
import app.amber.core.files.FileFolders
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    val restoreEpoch: Long = 0L,
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
 * 1. 校验预览的恢复 epoch，并重新 dry run；保留其它会话引用的附件。
 * 2. **先删物理附件文件**（含会话生成图目录），任一失败立即中止 —— 数据库
 *    尚未动，状态完全可追踪，重试安全。
 * 3. 单事务删除 DB 记录：附件引用（managed_files）→ FTS → 草稿 → 收藏 →
 *    会话（message_node 及其统计、compact、context_event 由外键级联）。
 *    事务失败则 DB 原样，重试即可。
 *
 * 共享附件保留文件和登记行；专属附件在删会话前完成物理清理。
 */
class SessionCleanupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val restoreWriteGate: SyncRestoreWriteGate = SyncRestoreWriteGate(),
) {
    suspend fun dryRun(cutoffAt: Long): CleanupDryRun = withContext(Dispatchers.IO) {
        val restoreEpoch = restoreWriteGate.currentEpoch()
        val db = database.openHelper.readableDatabase
        val targets = db.query(
            "SELECT id FROM conversationentity WHERE is_pinned = 0 AND update_at < ?",
            arrayOf(cutoffAt.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(buildTarget(db, cursor.getString(0)))
                }
            }
        }
        val targetIds = targets.mapTo(hashSetOf()) { it.conversationId }
        val retainedPaths = hashSetOf<String>()
        if (targets.any { it.attachmentPaths.isNotEmpty() }) {
            db.query("SELECT conversation_id, messages FROM message_node").use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(0) !in targetIds) collectUploadPaths(cursor.getString(1), retainedPaths)
                }
            }
        }
        val countedPaths = hashSetOf<String>()
        val deletableTargets = targets.map { target ->
            val paths = target.attachmentPaths.filter { it !in retainedPaths && countedPaths.add(it) }
            target.copy(attachmentPaths = paths, attachmentBytes = managedBytes(db, paths))
        }
        CleanupDryRun(cutoffAt = cutoffAt, targets = deletableTargets, restoreEpoch = restoreEpoch)
    }

    /** 校验预览所属的数据集；删除目标以同一 gate 内重新 dry run 的当前状态为准。 */
    suspend fun execute(plan: CleanupDryRun): CleanupResult =
        withContext(Dispatchers.IO + SyncRestoreWriteEpoch(plan.restoreEpoch)) {
            restoreWriteGate.withCurrentWriterOrCancel {
                // Once deletion starts, leaving the page must not cancel the
                // database cleanup after the physical files have been removed.
                withContext(NonCancellable) { executeCurrent(plan) }
            }
        }

    private suspend fun executeCurrent(plan: CleanupDryRun): CleanupResult {
        val current = dryRun(plan.cutoffAt)
        if (current.targets.isEmpty()) {
            return CleanupResult(0, 0, 0, 0L)
        }
        deletePhysicalAttachments(current)
        val db = database.openHelper.writableDatabase
        database.withTransaction {
            current.targets.forEach { target ->
                // 先删附件引用记录，再删会话（同事务）。
                target.attachmentPaths.forEach { path ->
                    db.execSQL(
                        "DELETE FROM managed_files WHERE relative_path = ?",
                        arrayOf(path),
                    )
                }
                db.execSQL(
                    "DELETE FROM message_fts WHERE conversation_id = ?",
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
        return CleanupResult(
            conversationCount = current.conversationCount,
            messageNodeCount = current.messageNodeCount,
            attachmentCount = current.attachmentCount,
            deletedBytes = current.estimatedBytes,
        )
    }

    private fun buildTarget(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        conversationId: String,
    ): ConversationCleanupTarget {
        val messageNodeCount = db.query(
            "SELECT COUNT(*) FROM message_node WHERE conversation_id = ?",
            arrayOf(conversationId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        val referencedPaths = db.query(
            "SELECT messages FROM message_node WHERE conversation_id = ?",
            arrayOf(conversationId),
        ).use { cursor ->
            val found = linkedSetOf<String>()
            while (cursor.moveToNext()) {
                collectUploadPaths(cursor.getString(0), found)
            }
            found.toList()
        }
        val paths = if (referencedPaths.isEmpty()) emptyList() else db.query(
            "SELECT relative_path FROM managed_files WHERE relative_path IN (" +
                referencedPaths.joinToString(",") { "?" } + ")",
            referencedPaths.toTypedArray(),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        return ConversationCleanupTarget(
            conversationId = conversationId,
            messageNodeCount = messageNodeCount,
            attachmentPaths = paths,
            attachmentBytes = managedBytes(db, paths),
            chatImageBytes = directoryBytes(File(context.filesDir, "${FileFolders.CHAT_IMAGES}/$conversationId")),
        )
    }

    private fun managedBytes(db: androidx.sqlite.db.SupportSQLiteDatabase, paths: List<String>): Long =
        if (paths.isEmpty()) 0L else db.query(
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

    private fun deletePhysicalAttachments(plan: CleanupDryRun) {
        val filesDir = context.filesDir
        plan.targets.forEach { target ->
            target.attachmentPaths.forEach { path ->
                val file = File(filesDir, path)
                if (file.exists() && !file.delete()) {
                    throw IllegalStateException("附件删除失败（${file.name}），已中止清理，可重试")
                }
            }
            val chatImagesDir = File(filesDir, "${FileFolders.CHAT_IMAGES}/${target.conversationId}")
            if (chatImagesDir.exists() && !chatImagesDir.deleteRecursively()) {
                throw IllegalStateException("生成图目录删除失败，已中止清理，可重试")
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
