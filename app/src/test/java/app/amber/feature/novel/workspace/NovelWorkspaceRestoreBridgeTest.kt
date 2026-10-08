package app.amber.feature.novel.workspace

import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceRestoreBridgeTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun job(id: String) = NovelWorkspaceGhostwriteJob(
        id = id, executionId = "before-$id", branchSlug = "main", targetChapterCount = 2,
        startOrdinal = 1, createdAt = Instant.now(), updatedAt = Instant.now(),
    )

    @Test fun `restored jobs stay paused and prior callbacks cannot write the restored tree`() = runBlocking {
        val root = temporary.newFolder("workspace")
        val project = File(root, "book").apply { mkdirs() }
        val original = job("old")
        NovelWorkspaceGhostwriteJobs.save(original, project)
        val source = File(temporary.root, "source").apply { mkdirs() }
        NovelWorkspaceGhostwriteJobs.save(job("imported"), source)
        val encoded = File(source, ".amber/jobs/imported.json").readText()
        val oldEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        val gate = SyncRestoreWriteGate()
        var cancelled = 0
        NovelWorkspaceRestoreBridge(gate, root) { cancelled += 1 }.use { bridge ->
            gate.withRestore {
                assertTrue(bridge.state.value.restoring)
                assertEquals(NovelWorkspaceGhostwriteJob.STATUS_PAUSED, NovelWorkspaceGhostwriteJobs.load(project, original.id)?.status)
                val rejected = runCatching { NovelWorkspaceStore(project).write("project.md", "old callback") }.exceptionOrNull()
                assertTrue(rejected is java.util.concurrent.CancellationException)
                project.deleteRecursively()
                project.mkdirs()
                // Archive restore directly replaces the tree; this is outside novel writers.
                File(project, "project.md").writeText("restored manuscript")
                File(project, ".amber/jobs").mkdirs()
                File(project, ".amber/jobs/imported.json").writeText(encoded)
                gate.markDataCommitted()
            }
            val imported = NovelWorkspaceGhostwriteJobs.load(project, "imported")!!
            assertEquals(NovelWorkspaceGhostwriteJob.STATUS_PAUSED, imported.status)
            assertNotEquals("before-imported", imported.executionKey)
            assertFalse(bridge.state.value.restoring)
            assertEquals(1, cancelled)
            assertTrue(runCatching { NovelWorkspaceRestoreBoundary.write(oldEpoch) {
                NovelWorkspaceStore(project).write("project.md", "late provider")
            } }.exceptionOrNull() is java.util.concurrent.CancellationException)
            assertEquals("restored manuscript", File(project, "project.md").readText())
        }
    }

    @Test fun `failed restore still invalidates the cancelled execution`() = runBlocking {
        val root = temporary.newFolder("failed")
        val project = File(root, "book").apply { mkdirs() }
        val original = job("old")
        NovelWorkspaceGhostwriteJobs.save(original, project)
        val gate = SyncRestoreWriteGate()
        NovelWorkspaceRestoreBridge(gate, root) {}.use { bridge ->
            runCatching { gate.withRestore { error("restore failed before commit") } }
            assertFalse(bridge.state.value.restoring)
            val paused = NovelWorkspaceGhostwriteJobs.load(project, original.id)!!
            assertEquals(NovelWorkspaceGhostwriteJob.STATUS_PAUSED, paused.status)
            assertNotEquals(original.executionKey, paused.executionKey)
            assertNull(NovelWorkspaceGhostwriteJobs.withRunningOwner(project, original.id, original.executionKey) { "stale commit" })
        }
    }

    @Test fun `configuration restore preserves novel execution and private bytes`() = runBlocking {
        val root = temporary.newFolder("config")
        val project = File(root, "book").apply { mkdirs() }
        val original = job("running")
        NovelWorkspaceGhostwriteJobs.save(original, project)
        val before = File(project, ".amber/jobs/running.json").readBytes()
        val gate = SyncRestoreWriteGate()
        val epoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        NovelWorkspaceRestoreBridge(gate, root) { error("Novel work should stay active") }.use { bridge ->
            gate.withRestore(affectedFileRoots = emptySet()) { assertFalse(bridge.state.value.restoring) }
            assertArrayEquals(before, File(project, ".amber/jobs/running.json").readBytes())
            assertEquals(epoch, NovelWorkspaceRestoreBoundary.currentEpoch())
        }
    }

    @Test fun `rolling back a native journal preserves the previous automatic migration policy`() = runBlocking {
        val root = temporary.newFolder("journal-rollback")
        val gate = SyncRestoreWriteGate()
        val repository = app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository(root)
        val nativeRoot = app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository.RELATIVE_ROOT
        NovelWorkspaceRestoreBridge(gate, root) {}.use {
            gate.withRestore(affectedFileRoots = setOf(nativeRoot), adoptedFileRoots = emptySet()) {
                assertTrue(repository.allowsAutomaticMigration())
            }
            assertTrue(repository.allowsAutomaticMigration())
        }
    }
}
