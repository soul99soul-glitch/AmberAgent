package app.amber.feature.miniapp

import android.app.Application
import androidx.room.Room
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.entity.MiniAppEntity
import app.amber.core.utils.JsonInstant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MiniAppSourceVersionConcurrencyTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: MiniAppRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = MiniAppRepository(context, db, db.miniAppDao(), db.miniAppGrantDao(),
            db.miniAppVersionDao(), db.miniAppAuditLogDao(), db.miniAppSharedDataDao(), JsonInstant)
    }

    @After
    fun tearDown() { db.close() }

    private suspend fun savedApp(): MiniAppEntity {
        val app = MiniAppEntity("source-app", "initial", "description", "<html><body>v1</body></html>",
            permissionsJson = "[\"storage\"]", createdAt = 1, updatedAt = 1)
        repository.upsert(app)
        return app
    }

    @Test
    fun savingSourcePreservesMetadataChangedAfterEditorOpened() = runBlocking {
        val editorSnapshot = savedApp()
        repository.rename(editorSnapshot.id, "renamed", "new description")
        repository.setPinned(editorSnapshot.id, true)
        repository.markRun(editorSnapshot.id)
        repository.updateBoardSummary(editorSnapshot.id, "new summary")
        val before = repository.getById(editorSnapshot.id)!!
        repository.saveNewVersion(editorSnapshot, "<html><body>edited</body></html>")
        val after = repository.getById(editorSnapshot.id)!!
        assertEquals(before.title, after.title)
        assertEquals(before.description, after.description)
        assertEquals(before.pinned, after.pinned)
        assertEquals(before.runCount, after.runCount)
        assertEquals(before.lastRunAt, after.lastRunAt)
        assertEquals(before.boardSummary, after.boardSummary)
        assertEquals(before.permissionsJson, after.permissionsJson)
    }

    @Test
    fun savingCapturedSourceCannotResurrectDeletedApp() = runBlocking {
        val editorSnapshot = savedApp()
        repository.delete(editorSnapshot.id)
        try {
            repository.saveNewVersion(editorSnapshot, "<html><body>edited</body></html>")
            fail("Missing MiniApp must be rejected")
        } catch (_: IllegalArgumentException) {
            assertNull(repository.getById(editorSnapshot.id))
        }
    }

    @Test
    fun staleSourceEditCannotOverwriteANewerHtmlVersion() = runBlocking {
        val editorSnapshot = savedApp()
        val newer = repository.saveNewVersion(editorSnapshot, "<html><body>v2</body></html>")
        try {
            repository.saveNewVersion(editorSnapshot, "<html><body>stale edit</body></html>")
            fail("Stale editor version must be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals(newer, repository.getById(editorSnapshot.id))
        }
    }
}
