package app.amber.core.storage

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.entity.ConversationDraftEntity
import app.amber.agent.data.db.entity.ConversationEntity
import app.amber.agent.data.db.entity.ManagedFileEntity
import app.amber.agent.data.db.entity.MessageNodeEntity
import app.amber.core.files.FileFolders
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P7-03 会话存储占用与按时间清理。
 *
 * 覆盖计划测试清单：分类统计正确、cutoff 与非 pinned 过滤、dry run 数字与
 * 实际删除一致、删除后附件无孤儿、失败重试不重复删。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StorageCleanupTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var analyzer: StorageAnalyzer
    private lateinit var cleanup: SessionCleanupManager
    private lateinit var testRoot: File

    private val now = 1_700_000_000_000L
    private val oldCutoff = now - 90L * 24 * 60 * 60 * 1000

    @Before
    fun setUp() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        testRoot = File(context.cacheDir, "storage-cleanup-${System.nanoTime()}").apply { mkdirs() }
        listOf(FileFolders.UPLOAD, FileFolders.CHAT_IMAGES, FileFolders.IMAGES, FileFolders.SKILLS)
            .forEach { File(context.filesDir, it).deleteRecursively() }

        database = Room.databaseBuilder(context, AppDatabase::class.java, "amber_agent")
            .allowMainThreadQueries()
            .build()
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TABLE IF NOT EXISTS message_fts(
                text TEXT,
                node_id TEXT,
                message_id TEXT,
                conversation_id TEXT,
                title TEXT,
                update_at TEXT
            )
            """.trimIndent()
        )
        analyzer = StorageAnalyzer(context, database)
        cleanup = SessionCleanupManager(context, database)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::testRoot.isInitialized) testRoot.deleteRecursively()
        if (::context.isInitialized) {
            listOf(FileFolders.UPLOAD, FileFolders.CHAT_IMAGES, FileFolders.IMAGES, FileFolders.SKILLS)
                .forEach { File(context.filesDir, it).deleteRecursively() }
        }
    }

    // ---------------- 分类统计正确 ----------------

    @Test
    fun analyzeReportsCategoriesWithSizesAndCounts() = runBlocking {
        seedConversation("conv-a", updatedAt = now - 10, pinned = false, messageCount = 2)
        seedAttachment("conv-a", "upload/a.txt", "hello".toByteArray())

        // 缓存目录放一个文件。
        File(context.cacheDir, "cache-test.txt").writeText("cache")

        val breakdown = analyzer.analyze()

        assertEquals(1, breakdown.conversationCount)
        assertEquals(3, breakdown.messageNodeCount)
        // 消息正文 = 两条 messages JSON 的 LENGTH 之和（>0）。
        assertTrue(breakdown.messageBodyBytes > 0)
        assertEquals(1, breakdown.attachmentCount)
        assertEquals(5, breakdown.attachmentBytes)
        assertTrue(breakdown.databaseBytes > 0)
        assertTrue(breakdown.cacheBytes >= 5)
    }
    // ---------------- cutoff 与非 pinned 过滤 ----------------

    @Test
    fun dryRunFiltersByCutoffAndExcludesPinned() = runBlocking {
        seedConversation("old-unpinned", updatedAt = now - 100L * 24 * 60 * 60 * 1000, pinned = false, messageCount = 1)
        seedConversation("old-pinned", updatedAt = now - 100L * 24 * 60 * 60 * 1000, pinned = true, messageCount = 1)
        seedConversation("recent-unpinned", updatedAt = now - 1L * 24 * 60 * 60 * 1000, pinned = false, messageCount = 1)

        val plan = cleanup.dryRun(cutoffAt = oldCutoff)

        assertEquals(listOf("old-unpinned"), plan.targets.map { it.conversationId })
        assertEquals(1, plan.conversationCount)
        assertEquals(1, plan.messageNodeCount)
        // 旧的已置顶会话被默认排除。
        assertFalse(plan.targets.any { it.conversationId == "old-pinned" })
    }

    // ---------------- dry run 数字与实际删除一致 ----------------

    @Test
    fun dryRunNumbersMatchActualDeletion() = runBlocking {
        seedConversation("conv-a", updatedAt = now - 100L * 24 * 60 * 60 * 1000, pinned = false, messageCount = 3)
        seedAttachment("conv-a", "upload/a.txt", "hello".toByteArray())
        seedAttachment("conv-a", "upload/b.txt", "world!".toByteArray())
        seedConversation("conv-b", updatedAt = now - 100L * 24 * 60 * 60 * 1000, pinned = false, messageCount = 2)
        seedAttachment("conv-b", "upload/c.txt", "12345".toByteArray())
        // 置顶 + 近期会话不在清理范围。
        seedConversation("conv-pinned", updatedAt = now - 100L * 24 * 60 * 60 * 1000, pinned = true, messageCount = 9)
        seedConversation("conv-recent", updatedAt = now - 1L * 24 * 60 * 60 * 1000, pinned = false, messageCount = 9)

        val plan = cleanup.dryRun(cutoffAt = oldCutoff)
        // conv-a：3 个种子消息节点 + 2 个附件引用节点；conv-b：2 + 1。
        assertEquals(2, plan.conversationCount)
        assertEquals(8, plan.messageNodeCount)
        assertEquals(3, plan.attachmentCount)
        // 附件字节：5 + 6 + 5。
        assertEquals(16L, plan.attachmentBytes)

        val result = cleanup.execute(plan)
        assertEquals(plan.conversationCount, result.conversationCount)
        assertEquals(plan.messageNodeCount, result.messageNodeCount)
        assertEquals(plan.attachmentCount, result.attachmentCount)
        assertEquals(plan.estimatedBytes, result.deletedBytes)
    }

    // ---------------- 删除后附件无孤儿 ----------------

    @Test
    fun deletionLeavesNoOrphanAttachmentsOrRows() = runBlocking {
        seedConversation("conv-a", updatedAt = now - 100L * 24 * 60 * 60 * 1000, pinned = false, messageCount = 1)
        seedAttachment("conv-a", "upload/a.txt", "hello".toByteArray())
        // 会话相关但无 FK 的表：FTS / 草稿 / 收藏。
        val ftsDb = database.openHelper.writableDatabase
        ftsDb.execSQL(
            "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES ('x','n','m','conv-a','t','1')"
        )
        database.conversationDraftDao().upsert(
            ConversationDraftEntity(
                conversationId = "conv-a",
                draftId = "draft-1",
                text = "draft",
                attachmentsJson = "[]",
                updatedAtMs = 1L,
            )
        )
        database.favoriteDao().upsert(
            app.amber.agent.data.db.entity.FavoriteEntity(
                id = "fav-1",
                type = "message",
                refKey = "node:conv-a:node-1",
                refJson = "{}",
                snapshotJson = "{}",
                createdAt = 1L,
                updatedAt = 1L,
            )
        )

        cleanup.execute(cleanup.dryRun(cutoffAt = oldCutoff))

        // 会话与消息行已删。
        assertEquals(0, database.conversationDao().getAllIds().size)
        assertEquals(0, database.messageNodeDao().getNodesOfConversation("conv-a").size)
        // 附件引用与物理文件已删。
        assertEquals(0, database.managedFileDao().listByFolder(FileFolders.UPLOAD).first().size)
        assertFalse(File(context.filesDir, "upload/a.txt").exists())
        // FTS / 草稿 / 收藏一并清理。
        assertEquals(0, ftsDb.query("SELECT COUNT(*) FROM message_fts WHERE conversation_id = 'conv-a'").use { c ->
            if (c.moveToFirst()) c.getInt(0) else -1
        })
        assertEquals(null, database.conversationDraftDao().get("conv-a"))
        assertEquals(0, database.favoriteDao().deleteByConversation("conv-a"))
    }

    // ---------------- 失败重试不重复删 ----------------

    @Test
    fun failureAbortsBeforeDbAndRetryIsIdempotent() = runBlocking {
        seedConversation("conv-a", updatedAt = now - 100L * 24 * 60 * 60 * 1000, pinned = false, messageCount = 1)
        seedAttachment("conv-a", "upload/a.txt", "hello".toByteArray())
        // 让物理删除失败：把第二个“附件”变成一个非空目录（delete() 返回 false）。
        val blocking = File(context.filesDir, "upload/blocking.txt")
        blocking.mkdirs()
        File(blocking, "inner").writeText("blocked")
        database.managedFileDao().insert(
            ManagedFileEntity(
                folder = FileFolders.UPLOAD,
                relativePath = "upload/blocking.txt",
                displayName = "blocking.txt",
                mimeType = "text/plain",
                sizeBytes = 0,
                createdAt = 1L,
                updatedAt = 1L,
            )
        )
        // 消息引用两个附件，让它们都进入清理目标。
        database.messageNodeDao().insert(
            MessageNodeEntity(
                id = "node-a",
                conversationId = "conv-a",
                nodeIndex = 0,
                messages = messageJsonWithAttachments("upload/a.txt", "upload/blocking.txt"),
                selectIndex = 0,
            )
        )

        val plan = cleanup.dryRun(cutoffAt = oldCutoff)
        assertEquals(2, plan.attachmentCount)

        // 第一次执行：物理删除失败 → 中止，DB 未动。
        val firstError = runCatching { cleanup.execute(plan) }.exceptionOrNull()
        assertTrue(firstError != null)
        assertEquals(1, database.conversationDao().getAllIds().size)
        assertEquals(2, database.managedFileDao().listByFolder(FileFolders.UPLOAD).first().size)

        // 修复阻塞（删掉目录里的文件，使目录可删除）。
        File(blocking, "inner").delete()
        assertTrue(blocking.delete())

        // 重试成功。
        val result = cleanup.execute(cleanup.dryRun(cutoffAt = oldCutoff))
        assertEquals(1, result.conversationCount)
        assertEquals(2, result.attachmentCount)
        assertEquals(0, database.conversationDao().getAllIds().size)

        // 再次执行：没有可删内容，不重复删。
        val second = cleanup.execute(cleanup.dryRun(cutoffAt = oldCutoff))
        assertEquals(0, second.conversationCount)
        assertEquals(0, second.attachmentCount)
    }

    // ---------------- fixtures ----------------

    private suspend fun seedConversation(
        id: String,
        updatedAt: Long,
        pinned: Boolean,
        messageCount: Int,
    ) {
        database.conversationDao().insert(
            ConversationEntity(
                id = id,
                assistantId = "assistant-1",
                title = "conversation $id",
                nodes = "[]",
                createAt = updatedAt,
                updateAt = updatedAt,
                chatSuggestions = "[]",
                isPinned = pinned,
            )
        )
        repeat(messageCount) { index ->
            database.messageNodeDao().insert(
                MessageNodeEntity(
                    id = "node-$id-$index",
                    conversationId = id,
                    nodeIndex = index,
                    messages = messageJsonWithAttachments(),
                    selectIndex = 0,
                )
            )
        }
    }

    private suspend fun seedAttachment(conversationId: String, path: String, bytes: ByteArray) {
        val file = File(context.filesDir, path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        database.managedFileDao().insert(
            ManagedFileEntity(
                folder = FileFolders.UPLOAD,
                relativePath = path,
                displayName = file.name,
                mimeType = "text/plain",
                sizeBytes = bytes.size.toLong(),
                createdAt = 1L,
                updatedAt = 1L,
            )
        )
        // 消息节点引用该附件（清理只删除被消息引用的附件）。
        val nodeIndex = database.messageNodeDao().getNodesOfConversation(conversationId).size
        database.messageNodeDao().insert(
            MessageNodeEntity(
                id = "node-$conversationId-att-$nodeIndex",
                conversationId = conversationId,
                nodeIndex = nodeIndex,
                messages = messageJsonWithAttachments(path),
                selectIndex = 0,
            )
        )
    }

    private fun messageJsonWithAttachments(vararg paths: String): String {
        val docs = paths.joinToString(",") { path ->
            """{"type":"document","url":"file:///data/user/0/app.amber.agent/files/$path","fileName":"${path.substringAfterLast('/')}"}"""
        }
        return """[{"role":"user","content":[{"type":"text","text":"hello"}${
            if (docs.isNotBlank()) ",$docs" else ""
        }]}]"""
    }
}
