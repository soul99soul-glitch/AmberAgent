package app.amber.feature.miniapp

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.amber.agent.data.db.AppDatabase
import app.amber.core.utils.JsonInstant
import kotlinx.coroutines.runBlocking
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

/**
 * P3-05 tests (plan §P3-05 测试 "编辑→校验→保存→revert 回 previous"):
 * the editor's save path persists a NEW version while the previous version
 * stays intact, and the explicit revert (restoreVersion) brings the previous
 * content back as a new version — the existing AI-edit/export/rollback
 * capabilities are untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MiniAppSourceEditorVersionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private fun repositoryOf(db: AppDatabase) = MiniAppRepository(
        context = context,
        database = db,
        dao = db.miniAppDao(),
        grantDao = db.miniAppGrantDao(),
        versionDao = db.miniAppVersionDao(),
        auditLogDao = db.miniAppAuditLogDao(),
        sharedDataDao = db.miniAppSharedDataDao(),
        json = JsonInstant,
    )

    private fun generated(html: String) = MiniAppGeneratedOutput(
        title = "编辑测试小应用",
        description = "desc",
        permissions = listOf("toast"),
        html = html,
    )

    @Test
    fun editSaveKeepsPreviousAndRevertRestoresIt() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = repositoryOf(db)

        val originalHtml = "<html><body><h1>v1</h1></body></html>"
        val editedHtml = "<html><body><h1>v2 edited</h1></body></html>"
        val revertedHtml = "<html><body><h1>v1</h1></body></html>"

        val app = repository.saveGenerated(generated(originalHtml))
        assertEquals(1, app.version)

        // Editor save: validation (structural checks) passes, new version kept.
        assertTrue(MiniAppSourceChecks.isSavable(editedHtml))
        val saved = repository.saveNewVersion(app, editedHtml, changeNote = "Edited in source editor")
        assertNotNull(saved)
        assertEquals(2, saved!!.version)
        assertEquals(editedHtml, saved.htmlContent)
        assertEquals(1, repository.getVersion(app.id, 1)!!.versionNumber)

        // Previous version content preserved for the revert.
        assertEquals(originalHtml, repository.getVersion(app.id, 1)!!.htmlContent)

        // Explicit revert → previous content restored as a new version.
        val reverted = repository.restoreVersion(app.id, 1)
        assertNotNull(reverted)
        assertEquals(3, reverted!!.version)
        assertEquals(revertedHtml, reverted.htmlContent)

        // Version history intact (1 and 2 still present).
        assertNotNull(repository.getVersion(app.id, 1))
        assertNotNull(repository.getVersion(app.id, 2))
    }

    @Test
    fun invalidEditedSourceIsRejectedOnSavePath() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = repositoryOf(db)
        val app = repository.saveGenerated(generated("<html><body>ok</body></html>"))

        // Structural issues (unbalanced tags) block the editor's pre-save
        // validation — the editor refuses to call the repository.
        val unbalanced = "<html><body><div>oops</span></body></html>"
        assertTrue(MiniAppSourceChecks.issues(unbalanced).isNotEmpty())
        assertFalse(MiniAppSourceChecks.isSavable(unbalanced))

        // Security-invalid content is rejected by the repository save path too.
        val insecure = "<html><body><script>eval('x')</script></body></html>"
        var thrown: Exception? = null
        try {
            repository.saveNewVersion(app, insecure)
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue("expected MiniAppValidationException, got $thrown", thrown is MiniAppValidationException)

        // No version row was created for the rejected edit.
        assertEquals(1, repository.getVersion(app.id, 1)!!.versionNumber)
        assertNull(repository.getVersion(app.id, 2))
    }
}
