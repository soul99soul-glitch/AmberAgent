package app.amber.agent.data.workspace

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.entity.ConversationEntity
import app.amber.agent.data.db.entity.MessageNodeEntity
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.utils.JsonInstant
import app.amber.feature.workspace.WorkspaceManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * P3-01 / P3-02 — Workspace Artifact Registry tests (plan §P3-01 测试):
 *
 * - 创建、重启、打开 (file-backed DB reopen).
 * - parser 失败后重新解析 (source message deleted → FAILED, restored → PARSED).
 * - 删除被引用 Artifact (reference count surfaced, cascade cleanup).
 * - 大文件和重复 digest (file locator verified, digest dedup semantics).
 * - 来源会话已删除 (artifact still readable, source marked unavailable).
 * - 重复保存 (update vs copy semantics, P3-02).
 * - v10 → v11 migration is a pure add-table migration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ArtifactRepositoryTest {

    private lateinit var context: Context
    private lateinit var workspaceManager: WorkspaceManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        workspaceManager = WorkspaceManager(context)
    }

    @After
    fun tearDown() {
        // WorkspaceManager holds no closeable state; nothing to tear down.
    }

    private fun inMemoryRepository(): Pair<AppDatabase, ArtifactRepository> {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        return db to repositoryOf(db)
    }

    private fun repositoryOf(db: AppDatabase) = ArtifactRepository(
        dao = db.artifactDao(),
        workspaceManager = workspaceManager,
        messageNodeDao = db.messageNodeDao(),
        conversationDao = db.conversationDao(),
    )

    private suspend fun insertChatSource(
        db: AppDatabase,
        conversationId: String,
        message: UIMessage,
    ) {
        db.conversationDao().insert(
            ConversationEntity(
                id = conversationId,
                assistantId = "assistant-test",
                title = "Test conversation",
                nodes = "[]",
                createAt = System.currentTimeMillis(),
                updateAt = System.currentTimeMillis(),
                chatSuggestions = "[]",
                isPinned = false,
            )
        )
        db.messageNodeDao().insert(
            MessageNodeEntity(
                id = Uuid.random().toString(),
                conversationId = conversationId,
                nodeIndex = 0,
                messages = JsonInstant.encodeToString(listOf(message)),
                selectIndex = 0,
            )
        )
    }

    private fun chatMessage(text: String): UIMessage = UIMessage(
        id = Uuid.random(),
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    // ---- 创建 → 重启 → 打开 ----

    @Test
    fun createRestartOpenArtifact() = runBlocking {
        val dbName = "artifact-restart-${System.nanoTime()}.db"
        val message = chatMessage("第一条保存的消息正文")
        val conversationId = "conv-restart"
        val artifactId = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build().let { db ->
            insertChatSource(db, conversationId, message)
            val repo = repositoryOf(db)
            val created = repo.saveChatMessage(
                message = message,
                conversationId = conversationId,
                workspaceId = ArtifactRepository.DEFAULT_WORKSPACE_ID,
                includeReasoning = false,
            )
            assertEquals(ArtifactParseStatus.PARSED, created.parseStatus)
            assertEquals(ArtifactSourceKind.CHAT, created.sourceKind)
            assertEquals(message.id.toString(), created.sourceMessageId)
            assertNotNull("content file must exist at locator", repo.contentFile(created))
            db.close()
            created.artifactId
        }
        // Simulate process restart: reopen the same database file.
        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
        val repository = repositoryOf(reopened)
        val artifact = repository.get(artifactId)
        assertNotNull("artifact must survive restart", artifact)
        assertEquals("第一条保存的消息正文", artifact!!.title)
        assertEquals("chat-v1", artifact.parserVersion)
        assertEquals(ArtifactParseStatus.PARSED, artifact.parseStatus)
        assertEquals("第一条保存的消息正文", repository.readContent(artifact))
        assertTrue("content file must be readable after restart", repository.contentFile(artifact)!!.isFile)
        reopened.close()
    }

    // ---- parser 失败后重新解析 ----

    @Test
    fun reparseFailsWhenSourceDeletedThenSucceedsAfterRestore() = runBlocking {
        val (db, repository) = inMemoryRepository()
        val conversationId = "conv-reparse"
        val message = chatMessage("可重新解析的内容")
        insertChatSource(db, conversationId, message)
        val created = repository.saveChatMessage(message, conversationId, ArtifactRepository.DEFAULT_WORKSPACE_ID, false)
        assertEquals(ArtifactParseStatus.PARSED, created.parseStatus)

        // Source message deleted → reparse must FAIL with a distinguishable error,
        // and the artifact must stay readable.
        db.messageNodeDao().deleteByConversation(conversationId)
        db.conversationDao().deleteById(conversationId)
        val failed = repository.reparse(created.artifactId)
        assertTrue(failed is ReparseResult.Failed)
        assertEquals("source_unavailable", (failed as ReparseResult.Failed).reason)
        val afterFailure = repository.get(created.artifactId)!!
        assertEquals(ArtifactParseStatus.FAILED, afterFailure.parseStatus)
        assertEquals("source_unavailable", afterFailure.parseError)
        assertEquals("可重新解析的内容", repository.readContent(afterFailure))

        // Source restored (same message id) → reparse succeeds and refreshes content.
        insertChatSource(db, conversationId, message.copy(parts = listOf(UIMessagePart.Text("重新解析后的新内容"))))
        val success = repository.reparse(created.artifactId)
        assertTrue(success is ReparseResult.Success)
        val afterSuccess = repository.get(created.artifactId)!!
        assertEquals(ArtifactParseStatus.PARSED, afterSuccess.parseStatus)
        assertNull(afterSuccess.parseError)
        assertEquals("重新解析后的新内容", repository.readContent(afterSuccess))
        db.close()
    }

    // ---- 删除被引用 Artifact ----

    @Test
    fun deleteReferencedArtifactCleansReferencesAndContent() = runBlocking {
        val (db, repository) = inMemoryRepository()
        val conversationId = "conv-ref"
        val message = chatMessage("被引用的内容")
        insertChatSource(db, conversationId, message)
        val created = repository.saveChatMessage(message, conversationId, ArtifactRepository.DEFAULT_WORKSPACE_ID, false)

        assertEquals(0, repository.referenceCount(created.artifactId))
        repository.registerReference(created.artifactId, "miniapp", "app-1")
        repository.registerReference(created.artifactId, "deepread", "item-1")
        assertEquals(2, repository.referenceCount(created.artifactId))

        val contentFile = repository.contentFile(created)!!
        assertTrue(contentFile.isFile)
        assertTrue(repository.delete(created.artifactId))
        assertNull("artifact row must be gone", repository.get(created.artifactId))
        assertEquals(0, repository.referenceCount(created.artifactId))
        assertFalse("content file must be removed", contentFile.exists())
        db.close()
    }

    // ---- 大文件与重复 digest ----

    @Test
    fun largeContentStoredAsFileAndDuplicateDigestKeepsBoth() = runBlocking {
        val (db, repository) = inMemoryRepository()
        val conversationId = "conv-large"
        val bigContent = "大文件正文".repeat(200_000) // ~2MB
        val message = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(bigContent)),
        )
        insertChatSource(db, conversationId, message)
        val first = repository.saveChatMessage(message, conversationId, ArtifactRepository.DEFAULT_WORKSPACE_ID, false)
        val file = repository.contentFile(first)!!
        assertTrue("large content must be a real file at the locator", file.isFile)
        assertEquals("file size must match artifact size", first.sizeBytes, file.length())
        assertEquals(bigContent, repository.readContent(first))

        val secondMessage = chatMessage(bigContent)
        insertChatSource(db, "conv-large-2", secondMessage)
        val second = repository.saveChatMessage(
            secondMessage,
            "conv-large-2",
            ArtifactRepository.DEFAULT_WORKSPACE_ID,
            false,
        )
        assertEquals("identical content must produce the same digest", first.contentDigest, second.contentDigest)
        assertTrue("duplicate digest keeps both artifacts", first.artifactId != second.artifactId)
        assertEquals(2, repository.list().size)
        db.close()
    }

    // ---- 来源会话已删除：Artifact 仍可读、来源标记不可用 ----

    @Test
    fun sourceConversationDeletedArtifactStillReadableButSourceUnavailable() = runBlocking {
        val (db, repository) = inMemoryRepository()
        val conversationId = "conv-gone"
        val message = chatMessage("会话删除后仍可读")
        insertChatSource(db, conversationId, message)
        val created = repository.saveChatMessage(message, conversationId, ArtifactRepository.DEFAULT_WORKSPACE_ID, false)
        assertTrue(repository.sourceAvailable(created))

        db.conversationDao().deleteById(conversationId)
        val artifact = repository.get(created.artifactId)
        assertNotNull("artifact must survive source conversation deletion", artifact)
        assertEquals("会话删除后仍可读", repository.readContent(artifact!!))
        assertFalse("source must be marked unavailable", repository.sourceAvailable(artifact))
        db.close()
    }

    // ---- 重复保存：更新 vs 创建副本 (P3-02) ----

    @Test
    fun duplicateSaveUpdatesInPlaceOrCreatesCopy() = runBlocking {
        val (db, repository) = inMemoryRepository()
        val conversationId = "conv-dup"
        val message = chatMessage("同一消息的正文")
        insertChatSource(db, conversationId, message)
        val first = repository.saveChatMessage(message, conversationId, ArtifactRepository.DEFAULT_WORKSPACE_ID, false)
        val duplicate = repository.findBySourceMessage(ArtifactSourceKind.CHAT, message.id.toString())
        assertEquals(first.artifactId, duplicate!!.artifactId)

        // 更新: same artifactId, refreshed content.
        val updated = repository.saveChatMessage(
            message,
            conversationId,
            "research",
            false,
            existingArtifactId = duplicate.artifactId,
        )
        assertEquals(first.artifactId, updated.artifactId)
        assertEquals("research", updated.workspaceId)
        assertEquals(1, repository.list().size)

        // 创建副本: new artifactId, both rows remain.
        val copy = repository.saveChatMessage(
            message,
            conversationId,
            ArtifactRepository.DEFAULT_WORKSPACE_ID,
            false,
            existingArtifactId = null,
        )
        assertTrue(copy.artifactId != first.artifactId)
        assertEquals(2, repository.list().size)
        db.close()
    }

    @Test
    fun completedDeepReadUpdatesRegistryRow() = runBlocking {
        val (db, repository) = inMemoryRepository()

        val first = repository.saveDeepRead(
            topicId = "topic-1",
            title = "深度阅读一",
            content = "{\"summary\":\"第一版\"}",
        )
        val second = repository.saveDeepRead(
            topicId = "topic-1",
            title = "深度阅读二",
            content = "{\"summary\":\"第二版\"}",
        )

        assertEquals(first.artifactId, second.artifactId)
        assertEquals("深度阅读二", second.title)
        assertTrue("new DeepRead artifacts use the JSON locator", second.contentLocator.endsWith(".json"))
        assertEquals("{\"summary\":\"第二版\"}", repository.readContent(second))
        assertEquals(1, repository.list().count { it.sourceId == "topic-1" })
        assertEquals(1, repository.referenceCount(second.artifactId))
        db.close()
    }

    // ---- v10 → v11 migration: pure add-table ----

    @Test
    fun migration10to11AddsArtifactTablesOnly() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("mig-10-${System.nanoTime()}.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `conversationentity` " +
                                "(`id` TEXT NOT NULL, PRIMARY KEY(`id`))"
                        )
                        db.execSQL("INSERT INTO `conversationentity` (`id`) VALUES ('conv-kept')")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        val db = helper.writableDatabase
        AppDatabase.MIGRATION_10_11.migrate(db)

        val tables = mutableListOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        assertTrue("artifact table must exist", "artifact" in tables)
        assertTrue("artifact_reference table must exist", "artifact_reference" in tables)

        val artifactColumns = mutableListOf<String>()
        db.query("PRAGMA table_info(`artifact`)").use { cursor ->
            while (cursor.moveToNext()) artifactColumns += cursor.getString(1)
        }
        for (expected in listOf(
            "artifact_id", "workspace_id", "type", "mime_type", "title",
            "source_kind", "source_id", "source_run_id", "source_message_id",
            "content_locator", "content_digest", "size_bytes", "parser_version",
            "parse_status", "parse_error", "metadata_json",
            "created_at_ms", "updated_at_ms",
        )) {
            assertTrue("missing column $expected", expected in artifactColumns)
        }

        // Existing data untouched by the migration.
        db.query("SELECT COUNT(*) FROM `conversationentity`").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        helper.close()
    }
}
