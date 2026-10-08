package app.amber.feature.novelworkspace

import java.io.File
import java.io.FilterInputStream
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceRestoreBoundaryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val time = Instant.parse("2026-09-30T00:00:00Z")
    private val chapterPath = "branches/主线/chapters/001-第一章.md"

    private fun cancelled(block: () -> Unit) {
        try {
            block()
            fail("Expected restore cancellation")
        } catch (_: NovelWorkspaceRestoreCancelled) {
        }
    }

    private fun bytes(directory: File): Map<String, List<Byte>> = directory.walkTopDown()
        .filter { it.isFile }
        .associate { it.relativeTo(directory).invariantSeparatorsPath to it.readBytes().toList() }

    @Test
    fun `restore blocks canon private state and corrupt quarantine then rejects stale writer`() {
        val repository = NovelWorkspaceProjectRepository(temporaryFolder.newFolder("registry"))
        val project = repository.createBlank("测试")
        val directory = project.projectDirectory
        val store = NovelWorkspaceStore(directory)
        store.write(chapterPath, "恢复前正文")
        File(directory, ".amber/commits.json").writeText("corrupt ledger awaiting inspection")
        File(directory, ".amber/sessions.json").writeText("corrupt session awaiting inspection")
        val before = bytes(directory)
        val epoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        NovelWorkspaceRestoreBoundary.beginRestore()
        try {
            val mutations = listOf<() -> Unit>(
                { store.write(chapterPath, "旧回合正文") },
                { store.delete(chapterPath) },
                { store.materializeCheckout() },
                { NovelWorkspaceLedger.load(directory) },
                { NovelWorkspaceLedger.save(NovelWorkspaceLedgerStore(), directory) },
                { NovelWorkspaceSessions.load(directory) },
                { NovelWorkspaceSessions.save(NovelWorkspaceSessionsFile(), directory) },
                { NovelWorkspaceProjectSettingsStore.save(NovelWorkspaceProjectSettings(), directory) },
                { NovelWorkspaceUnresolvedStore.set(directory, "主线", 2, "old") },
                { NovelWorkspaceUndo.clear(directory) },
                { NovelWorkspaceChapterHistory.snapshot(directory, project.mainBranchId, "主线", chapterPath) },
                { NovelWorkspaceBranches.ActiveBranchStore.save(NovelWorkspaceBranches.NovelWorkspaceActiveBranch("主线"), directory) },
                { repository.renameProject(directory.name, "旧回合标题") },
                { repository.delete(directory.name) },
                { NovelWorkspaceGhostwriteJobs.withNoActiveBranch(directory, "主线") { store.write(chapterPath, "旧事务") } },
            )
            mutations.forEach(::cancelled)
            assertFalse(NovelWorkspaceRestoreBoundary.isCurrent(epoch))
            assertEquals(before, bytes(directory))
        } finally {
            NovelWorkspaceRestoreBoundary.finishRestore()
        }
        cancelled { NovelWorkspaceRestoreBoundary.write(epoch) { store.write(chapterPath, "恢复后的旧回合") } }
        assertEquals(before, bytes(directory))
        val fresh = NovelWorkspaceRestoreBoundary.currentEpoch()
        NovelWorkspaceRestoreBoundary.write(fresh) { store.write(chapterPath, "作者恢复后的新改动") }
        assertEquals("作者恢复后的新改动", store.read(chapterPath))
    }

    @Test
    fun `restore begins only after entire existing multi file writer leaves shared monitor`() {
        val store = NovelWorkspaceStore(temporaryFolder.newFolder("transaction"))
        val firstWrite = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val restoreRequested = CountDownLatch(1)
        val restoreBegan = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val writer = Thread {
            try {
                NovelWorkspaceRestoreBoundary.write {
                    store.write("setting/first.md", "first")
                    firstWrite.countDown()
                    check(releaseWriter.await(5, TimeUnit.SECONDS))
                    store.write("setting/second.md", "second")
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }
        val restore = Thread {
            restoreRequested.countDown()
            try {
                NovelWorkspaceRestoreBoundary.beginRestore()
                try {
                    assertEquals("second", store.read("setting/second.md"))
                    restoreBegan.countDown()
                } finally {
                    NovelWorkspaceRestoreBoundary.finishRestore()
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }
        writer.start()
        try {
            assertTrue(firstWrite.await(5, TimeUnit.SECONDS))
            restore.start()
            assertTrue(restoreRequested.await(5, TimeUnit.SECONDS))
            assertFalse(restoreBegan.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseWriter.countDown()
            writer.join(5000)
            if (restore.state != Thread.State.NEW) restore.join(5000)
        }
        assertNull(failure.get())
        assertEquals(0L, restoreBegan.count)
        assertFalse(writer.isAlive)
        assertFalse(restore.isAlive)
    }

    @Test
    fun `restore maintenance pauses running jobs preserving candidates receipts and terminal records`() {
        val directory = temporaryFolder.newFolder("jobs")
        val candidate = NovelWorkspaceGhostwriteCandidate("candidate", 3, "plan", "digest", 1, "标题", "候选正文", listOf("保留人物动机"), time, time)
        val review = NovelWorkspaceJointReviewResult("candidate", 3, "plan", "digest", false, false, emptyList(), emptyList(), "剧情", "进展", "下一章计划")
        val running = NovelWorkspaceGhostwriteJob(
            "running", executionId = "old-execution", branchSlug = "主线", targetChapterCount = 5, startOrdinal = 1,
            currentChapterOrdinal = 3, stage = NovelWorkspaceGhostwriteStage.Committing,
            pendingCandidate = candidate, pendingReview = review,
            receipts = listOf(NovelWorkspaceGhostwriteReceipt("commit", 2, "old-plan", "old-digest", "old-candidate")),
            createdAt = time, updatedAt = time,
        )
        val terminal = running.copy(id = "terminal", status = NovelWorkspaceGhostwriteJob.STATUS_COMPLETED)
        NovelWorkspaceGhostwriteJobs.save(running, directory)
        NovelWorkspaceGhostwriteJobs.save(terminal, directory)
        val bad = File(directory, ".amber/jobs/unreadable.json").apply { writeText("unreadable original") }
        val terminalBytes = File(directory, ".amber/jobs/terminal.json").readBytes().toList()
        NovelWorkspaceRestoreBoundary.beginRestore()
        try {
            cancelled { NovelWorkspaceGhostwriteJobs.save(running.copy(reason = "stale write"), directory) }
            NovelWorkspaceGhostwriteJobs.pauseRunningForRestore(directory)
            val paused = checkNotNull(NovelWorkspaceGhostwriteJobs.load(directory, running.id))
            assertEquals(NovelWorkspaceGhostwriteJob.STATUS_PAUSED, paused.status)
            assertNotEquals(running.executionKey, paused.executionKey)
            assertEquals(running.copy(status = paused.status, executionId = paused.executionId, updatedAt = paused.updatedAt), paused)
            assertEquals(terminalBytes, File(directory, ".amber/jobs/terminal.json").readBytes().toList())
            assertEquals("unreadable original", bad.readText())
        } finally {
            NovelWorkspaceRestoreBoundary.finishRestore()
        }
        assertNull(NovelWorkspaceGhostwriteJobs.withRunningOwner(directory, running.id, running.executionKey) { true })
        val paused = checkNotNull(NovelWorkspaceGhostwriteJobs.load(directory, running.id))
        val resumed = checkNotNull(NovelWorkspaceGhostwriteJobs.restartPaused(directory, running.id, paused.executionKey, "主线"))
        assertNotEquals(paused.executionKey, resumed.executionKey)
        assertEquals(true, NovelWorkspaceGhostwriteJobs.withRunningOwner(directory, running.id, resumed.executionKey) { true })
    }

    @Test
    fun `zip decoded across restore cannot install into the new epoch`() {
        val repository = NovelWorkspaceProjectRepository(temporaryFolder.newFolder("source"))
        val project = repository.createBlank("来源")
        val zip = NovelWorkspaceExchange.exportZipBytes(project.projectDirectory)
        val input = object : FilterInputStream(zip.inputStream()) {
            var firstRead = true
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (firstRead) {
                    firstRead = false
                    NovelWorkspaceRestoreBoundary.beginRestore()
                    NovelWorkspaceRestoreBoundary.finishRestore()
                }
                return super.read(buffer, offset, length)
            }
        }
        val target = temporaryFolder.root.resolve("rejected-import")
        input.use { cancelled { NovelWorkspaceExchange.importZip(it, target) } }
        assertFalse(target.exists())
    }
}
