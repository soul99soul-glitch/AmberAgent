package app.amber.feature.home

import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceContinueSourceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun jobRouteSurvivesRepositoryRecreationAndDisappearsAfterProjectDeletion() = runTest {
        val root = temporary.newFolder()
        val repository = NovelWorkspaceProjectRepository(root)
        val project = repository.createBlank("Same title")
        repository.createBlank("Same title")
        val job = NovelWorkspaceGhostwriteJob("job-a", branchSlug = "主线", targetChapterCount = 3,
            startOrdinal = 0, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, status = "paused")
        NovelWorkspaceGhostwriteJobs.save(job, project.projectDirectory)
        val source = NovelWorkspaceContinueSource(NovelWorkspaceProjectRepository(root))
        val candidate = source.observe().first().single()
        assertEquals(ContinueRoute.NovelWorkspace(project.projectDirectory.name.uppercase(), "主线", "job-a"), candidate.route)
        assertEquals(ContinueStatus.PAUSED, candidate.status)
        assertNotNull(
            NovelWorkspaceGhostwriteJobs.transition(
                project.projectDirectory,
                job.id,
                expectedStatuses = setOf(NovelWorkspaceGhostwriteJob.STATUS_PAUSED),
                newStatus = NovelWorkspaceGhostwriteJob.STATUS_CANCELLED,
                expectedExecutionId = job.executionKey,
            )
        )
        repository.delete(project.projectDirectory.name)
        assertTrue(source.observe().first().isEmpty())
    }

    @Test fun invalidJobProducesVisibleProjectAttentionInsteadOfVanishing() = runTest {
        val repository = NovelWorkspaceProjectRepository(temporary.newFolder())
        val project = repository.createBlank("Corrupt fixture")
        val file = project.projectDirectory.resolve(".amber/jobs/bad.json")
        file.parentFile.mkdirs()
        file.writeText("{broken")
        val candidate = NovelWorkspaceContinueSource(repository).observe().first().single()
        assertEquals(ContinueStatus.WAITING_USER, candidate.status)
        assertEquals(ContinueRoute.NovelWorkspace(project.projectDirectory.name.uppercase(), null, null), candidate.route)
        assertEquals("{broken", file.readText())
    }
}
