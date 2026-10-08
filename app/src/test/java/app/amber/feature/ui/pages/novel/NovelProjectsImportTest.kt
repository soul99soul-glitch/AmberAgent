package app.amber.feature.ui.pages.novel

import android.app.Application
import androidx.lifecycle.viewModelScope
import app.amber.feature.novel.persistence.NovelFileProjectRepository
import app.amber.feature.novel.workspace.NovelWorkspaceMigrationService
import app.amber.feature.novelworkspace.NovelWorkspaceExchange
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class NovelProjectsImportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun importEntryInstallsProjectPackageAndOpensTheNewWorkspace() = runBlocking {
        val repository = NovelWorkspaceProjectRepository(temporary.newFolder("workspace"))
        val existing = repository.createBlank("已有小说")
        val existingBefore = bookBytes(existing.projectDirectory)
        val viewModel = viewModel(repository)
        try {
            withTimeout(10_000) { viewModel.state.first { !it.loading && !it.busy } }
            val opened = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(10_000) { viewModel.openWorkspaceProjectId.first() }
            }
            var successCalls = 0

            viewModel.importProject(packageBytes("full-two-branch")) { successCalls += 1 }

            val importedId = opened.await()
            withTimeout(10_000) { viewModel.state.first { !it.busy } }
            assertNotEquals(existing.projectDirectory.name.uppercase(), importedId)
            assertTrue(repository.exists(importedId))
            assertEquals(2, repository.listProjects().size)
            assertEquals(existingBefore, bookBytes(existing.projectDirectory))
            assertEquals(1, successCalls)
            assertEquals(null, viewModel.state.value.errorMessage)
            assertTrue(viewModel.state.value.statusMessage.orEmpty().isNotBlank())
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun importEntryStillAcceptsWorkspaceZip() = runBlocking {
        val repository = NovelWorkspaceProjectRepository(temporary.newFolder("workspace"))
        val original = repository.createBlank("ZIP 旧稿")
        val bytes = NovelWorkspaceExchange.exportZipBytes(original.projectDirectory)
        val viewModel = viewModel(repository)
        try {
            withTimeout(10_000) { viewModel.state.first { !it.loading && !it.busy } }
            val opened = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(10_000) { viewModel.openWorkspaceProjectId.first() }
            }

            viewModel.importProject(bytes) { }

            assertTrue(repository.exists(opened.await()))
            assertEquals(2, repository.listProjects().size)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun rejectedPackageShowsImportErrorWithoutInstallingOrCallingSuccess() = runBlocking {
        val root = temporary.newFolder("workspace")
        val repository = NovelWorkspaceProjectRepository(root)
        val viewModel = viewModel(repository)
        try {
            withTimeout(10_000) { viewModel.state.first { !it.loading && !it.busy } }
            var successCalled = false

            viewModel.importProject(packageBytes("higher-schema-reject")) { successCalled = true }

            withTimeout(10_000) { viewModel.state.first { it.errorMessage != null && !it.busy } }
            assertFalse(successCalled)
            assertTrue(root.listFiles().orEmpty().isEmpty())
            assertTrue(viewModel.state.value.errorMessage.orEmpty().isNotBlank())
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    private fun viewModel(repository: NovelWorkspaceProjectRepository): NovelProjectsViewModel {
        val legacy = NovelFileProjectRepository(temporary.newFolder("legacy"))
        return NovelProjectsViewModel(
            repository,
            NovelWorkspaceMigrationService(legacy, repository),
            legacy,
            RuntimeEnvironment.getApplication(),
        )
    }

    private fun packageBytes(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("novel-v1/packages/$name.ambernovel.json"))
            .use { it.readBytes() }

    private fun bookBytes(directory: File): Map<String, List<Byte>> = directory.walkTopDown()
        .filter { it.isFile }
        .associate { it.relativeTo(directory).invariantSeparatorsPath to it.readBytes().toList() }
}
