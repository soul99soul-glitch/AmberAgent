package app.amber.feature.novelworkspace

import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceJobRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun project(): File = NovelWorkspaceProjectRepository(temporary.newFolder())
        .createBlank("Recovery fixture").projectDirectory

    private fun job(directory: File) = NovelWorkspaceGhostwriteJob(
        id = "job-1", executionId = "execution-1", branchSlug = "主线", targetChapterCount = 1,
        startOrdinal = 0, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    ).also { NovelWorkspaceGhostwriteJobs.save(it, directory) }

    @Test fun unfinishedWorkKeepsItsDurableOwner() {
        val directory = project()
        val before = job(directory)
        assertNull(NovelWorkspaceGhostwriteJobs.recoverUnscheduled(directory, before, true))
        assertEquals(before, NovelWorkspaceGhostwriteJobs.load(directory, before.id))
    }

    @Test fun missingWorkBecomesRetryableWithoutChangingCursor() {
        val directory = project()
        val before = job(directory)
        val recovered = NovelWorkspaceGhostwriteJobs.recoverUnscheduled(directory, before, false)!!
        assertEquals(NovelWorkspaceGhostwriteJob.STATUS_FAILED, recovered.status)
        assertEquals(before.startOrdinal, recovered.startOrdinal)
        assertEquals(recovered.copy(updatedAt = recovered.updatedAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)),
            NovelWorkspaceGhostwriteJobs.load(directory, before.id))
        val restarted = NovelWorkspaceGhostwriteJobs.restartFailed(directory, before.id, before.executionKey)!!
        assertNotEquals(before.executionKey, restarted.executionKey)
        assertNull(NovelWorkspaceGhostwriteJobs.withRunningOwner(directory, before.id, before.executionKey) { true })
    }

    @Test fun committedTargetIsCompletedInsteadOfGeneratedAgain() {
        val directory = project()
        val before = job(directory)
        val store = NovelWorkspaceStore(directory)
        store.write("branches/主线/chapters/001-ready.md", NovelWorkspaceMarkdown.render(
            fields = listOf("id" to "chapter-1", "kind" to "chapter", "ordinal" to "1"), body = "Committed text",
        ))
        val ledger = NovelWorkspaceLedger.load(directory)
        val commit = NovelWorkspaceLedger.makeCommit("commit-1", ledger.head, store.fileTree(), "收录", Instant.now())
        NovelWorkspaceLedger.save(NovelWorkspaceLedger.appending(commit, ledger).copy(
            heads = ledger.heads + (ledger.heads.keys.single() to commit.id),
        ), directory)
        val recovered = NovelWorkspaceGhostwriteJobs.recoverUnscheduled(directory, before, false)!!
        assertEquals(NovelWorkspaceGhostwriteJob.STATUS_COMPLETED, recovered.status)
        assertNull(recovered.reason)
        assertEquals(1, NovelWorkspaceGhostwriteJobs.progress(recovered, store))
    }

    @Test fun oldSnapshotCannotFailResumedExecutionOrPausedJob() {
        val directory = project()
        val before = job(directory)
        NovelWorkspaceGhostwriteJobs.transition(directory, before.id, setOf(before.status), "paused")
        assertNull(NovelWorkspaceGhostwriteJobs.recoverUnscheduled(directory, before, false))
        val resumed = NovelWorkspaceGhostwriteJobs.restartPaused(directory, before.id)!!
        assertNull(NovelWorkspaceGhostwriteJobs.recoverUnscheduled(directory, before, false))
        assertEquals(resumed.copy(updatedAt = resumed.updatedAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)),
            NovelWorkspaceGhostwriteJobs.load(directory, before.id))
    }

    @Test fun corruptRecordsAreVisibleRetainedAndCannotBecomeOwners() {
        val directory = project()
        val before = job(directory)
        val jobsDirectory = directory.resolve(".amber/jobs")
        val corrupt = jobsDirectory.resolve("corrupt.json").apply { writeText("{truncated") }
        NovelWorkspaceGhostwriteJobs.save(before.copy(id = "invalid", targetChapterCount = -1), directory)
        val snapshot = NovelWorkspaceGhostwriteJobs.snapshot(directory)
        assertEquals(listOf(before), snapshot.jobs)
        assertEquals(listOf("corrupt.json", "invalid.json"), snapshot.unreadableFiles)
        assertEquals("{truncated", corrupt.readText())
        assertNull(NovelWorkspaceGhostwriteJobs.load(directory, "invalid"))
        assertNull(NovelWorkspaceGhostwriteJobs.withRunningOwner(directory, "invalid", before.executionKey) { true })
    }

    @Test fun dismissingAnOldFailureCannotDeleteARetriedBatch() {
        val directory = project()
        val before = job(directory)
        NovelWorkspaceGhostwriteJobs.recoverUnscheduled(directory, before, false)
        val resumed = NovelWorkspaceGhostwriteJobs.restartFailed(directory, before.id, before.executionKey)!!
        assertFalse(NovelWorkspaceGhostwriteJobs.dismissFailed(directory, before.id, before.executionKey))
        assertNotNull(NovelWorkspaceGhostwriteJobs.load(directory, before.id))
        NovelWorkspaceGhostwriteJobs.recoverUnscheduled(directory, resumed, false)
        assertTrue(NovelWorkspaceGhostwriteJobs.dismissFailed(directory, resumed.id, resumed.executionKey))
        assertNull(NovelWorkspaceGhostwriteJobs.load(directory, before.id))
    }

    @Test fun navigationResolvesExactJobAndRejectsMissingOrMismatchedIdentity() {
        val directory = project()
        val before = job(directory)
        val store = NovelWorkspaceStore(directory)
        val focus = NovelWorkspaceFocus(jobId = before.id).resolve(store)
        assertEquals("主线", focus.branchSlug)
        assertEquals(before.id, focus.jobId)
        assertTrue(runCatching { NovelWorkspaceFocus("other", before.id).resolve(store) }.isFailure)
        assertTrue(runCatching { NovelWorkspaceFocus(jobId = "missing").resolve(store) }.isFailure)
        assertTrue(runCatching { NovelWorkspaceFocus(branchSlug = "../other").resolve(store) }.isFailure)
        assertEquals("主线", NovelWorkspaceFocus().resolve(store).branchSlug)
    }
}
