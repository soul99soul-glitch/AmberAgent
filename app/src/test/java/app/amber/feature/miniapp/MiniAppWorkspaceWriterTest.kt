package app.amber.feature.miniapp

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.workspace.ArtifactRepository
import app.amber.agent.data.workspace.ArtifactSourceKind
import app.amber.feature.workspace.WorkspaceManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import app.amber.agent.data.db.dao.ArtifactDAO
import app.amber.agent.data.db.entity.ArtifactEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P3-04 tests (plan §P3-04 测试):
 * - 落盘后可在 Workspace 打开 (registry row + readable content file).
 * - 重复 effectId 不重复建行 (same artifactId, single row).
 * - receipt 含 artifactId + 可打开路由.
 * - sourceKind=miniapp + registerReference 反向关联.
 * - v11 → v12 migration is a pure add-table migration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MiniAppWorkspaceWriterTest {

    private lateinit var context: Context
    private lateinit var workspaceManager: WorkspaceManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        workspaceManager = WorkspaceManager(context)
    }

    private fun repositoryOf(db: AppDatabase) = ArtifactRepository(
        dao = db.artifactDao(),
        workspaceManager = workspaceManager,
        messageNodeDao = db.messageNodeDao(),
        conversationDao = db.conversationDao(),
    )

    @Test
    fun createArtifactPersistsAndReturnsOpenableRoute() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = repositoryOf(db)
        val writer = MiniAppWorkspaceWriter(repository)

        val receipt = writer.createArtifact(
            appId = "app-1",
            effectId = "effect-1",
            title = "小应用生成的内容",
            content = "<html><body>hello</body></html>",
            type = "note",
            mimeType = "text/html",
        )

        // Receipt carries artifactId + openable route.
        assertTrue(receipt.artifactId.isNotBlank())
        assertEquals("created", receipt.status)
        assertEquals("workspace://artifact/${receipt.artifactId}", receipt.route)

        // Persisted: registry row readable, content file readable.
        val artifact = repository.get(receipt.artifactId)
        assertNotNull(artifact)
        assertEquals(ArtifactSourceKind.MINIAPP, artifact!!.sourceKind)
        assertEquals("app-1", artifact.sourceId)
        assertEquals("小应用生成的内容", artifact.title)
        assertEquals("miniapp-v1", artifact.parserVersion)
        assertEquals("<html><body>hello</body></html>", repository.readContent(artifact))

        // Reverse reference registered (delete-confirmation surface).
        assertEquals(1, repository.referenceCount(receipt.artifactId))

        // Visible in the Workspace Artifacts tab projection.
        assertTrue(repository.list().any { it.artifactId == receipt.artifactId })
    }

    @Test
    fun duplicateEffectIdReusesExistingArtifact() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = repositoryOf(db)
        val writer = MiniAppWorkspaceWriter(repository)

        val first = writer.createArtifact(
            appId = "app-2",
            effectId = "effect-dup",
            title = "内容 A",
            content = "body A",
            type = "note",
            mimeType = "text/plain",
        )
        val second = writer.createArtifact(
            appId = "app-2",
            effectId = "effect-dup",
            title = "内容 A",
            content = "body A",
            type = "note",
            mimeType = "text/plain",
        )

        // Same artifactId, no duplicate row.
        assertEquals(first.artifactId, second.artifactId)
        assertEquals("existing", second.status)
        assertEquals(1, repository.list().count { it.sourceId == "app-2" })

        // A different effectId creates a new row.
        val third = writer.createArtifact(
            appId = "app-2",
            effectId = "effect-other",
            title = "内容 B",
            content = "body B",
            type = "note",
            mimeType = "text/plain",
        )
        assertNotSame(first.artifactId, third.artifactId)
        assertEquals(2, repository.list().count { it.sourceId == "app-2" })
    }

    @Test
    fun concurrentDuplicateEffectCallsCreateExactlyOneArtifact() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val firstLookup = CompletableDeferred<Unit>()
            val resumeCreation = CompletableDeferred<Unit>()
            var inserted = false
            var first = true
            val realDao = db.artifactDao()
            val controlledDao = object : ArtifactDAO by realDao {
                override suspend fun listBySourceKindAndSourceId(sourceKind: String, sourceId: String): List<ArtifactEntity> {
                    if (first) {
                        first = false
                        firstLookup.complete(Unit)
                        resumeCreation.await()
                        return emptyList()
                    }
                    return if (inserted) realDao.listBySourceKindAndSourceId(sourceKind, sourceId) else emptyList()
                }

                override suspend fun insert(artifact: ArtifactEntity) {
                    resumeCreation.await()
                    realDao.insert(artifact)
                    inserted = true
                }
            }
            val repository = ArtifactRepository(controlledDao, workspaceManager,
                db.messageNodeDao(), db.conversationDao())
            val writer = MiniAppWorkspaceWriter(repository)
            suspend fun create() = writer.createArtifact("concurrent-app", "same-effect",
                "one title", "one body", "note", "text/plain")
            val a = async { create() }
            firstLookup.await()
            // Runs until it reaches insert (old code) or waits for the writer lock (fixed code).
            val b = async(start = CoroutineStart.UNDISPATCHED) { create() }
            resumeCreation.complete(Unit)
            val receipts = listOf(a.await(), b.await())
            assertEquals(1, receipts.map { it.artifactId }.distinct().size)
            assertEquals(setOf("created", "existing"), receipts.map { it.status }.toSet())
            assertEquals(1, repository.list().count { it.sourceId == "concurrent-app" })
            assertEquals(1, repository.referenceCount(receipts.first().artifactId))
        } finally {
            db.close()
        }
    }

    @Test
    fun migration11to12AddsConversationDraftTableOnly() = runBlocking {
        val dbName = "miniapp-migration-${System.nanoTime()}.db"
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12)
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase
        // Migration chain applies cleanly; the draft table exists.
        db.conversationDraftDao().run {
            upsert(
                app.amber.agent.data.db.entity.ConversationDraftEntity(
                    conversationId = "c1",
                    draftId = "d1",
                    text = "hi",
                    attachmentsJson = "[]",
                    updatedAtMs = 1L,
                )
            )
            val loaded = get("c1")
            assertNotNull(loaded)
            assertEquals("hi", loaded!!.text)
        }
        db.close()
    }
}
