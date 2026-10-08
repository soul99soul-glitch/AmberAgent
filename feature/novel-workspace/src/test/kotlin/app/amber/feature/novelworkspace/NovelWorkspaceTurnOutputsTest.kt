package app.amber.feature.novelworkspace

import java.io.File
import java.time.Instant
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceTurnOutputsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun output(directory: File, id: String = "run-1", branch: String = "branch-1", completed: Boolean = false) =
        NovelWorkspaceTurnOutput(
            runId = id,
            projectPath = directory.absolutePath,
            branchId = branch,
            branchSlug = "主线",
            userText = "继续",
            content = "未写完的正文不会自动进入正史。",
            completed = completed,
            createdAt = Instant.EPOCH,
        )

    private fun restoreEpoch() {
        NovelWorkspaceRestoreBoundary.beginRestore()
        NovelWorkspaceRestoreBoundary.finishRestore()
    }

    private fun assertCancelled(block: () -> Unit) {
        try {
            block()
            fail("Expected stale restore epoch to cancel")
        } catch (_: CancellationException) {
            // An old callback must not mutate the restored project.
        }
    }

    @Test fun staleSaveAcknowledgeAndRecoveryPreserveTheRestoredSnapshot() {
        val directory = temporary.newFolder()
        val oldEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        val snapshot = output(directory)
        NovelWorkspaceTurnOutputs.save(directory, snapshot, oldEpoch)
        restoreEpoch()
        val restored = snapshot.copy(content = "恢复后的输出")
        NovelWorkspaceTurnOutputs.save(directory, restored)

        assertCancelled { NovelWorkspaceTurnOutputs.save(directory, snapshot, oldEpoch) }
        assertCancelled { NovelWorkspaceTurnOutputs.acknowledge(directory, snapshot.runId, oldEpoch) }
        assertCancelled { NovelWorkspaceTurnOutputs.recoverToSessions(directory, snapshot.branchId, oldEpoch) }

        assertEquals(listOf(restored), NovelWorkspaceTurnOutputs.loadPending(directory, snapshot.branchId))
        assertTrue(NovelWorkspaceSessions.load(directory).sessions.isEmpty())
    }

    @Test fun restoredSnapshotIsRecoverableWhenTheOldRunIsStillInMemory() {
        val directory = temporary.newFolder()
        val oldEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        val snapshot = output(directory)
        NovelWorkspaceTurnOutputs.begin(directory, snapshot.runId, oldEpoch)
        try {
            restoreEpoch()
            val restored = snapshot.copy(content = "恢复后的输出")
            NovelWorkspaceTurnOutputs.save(directory, restored)
            NovelWorkspaceTurnOutputs.recoverToSessions(directory, snapshot.branchId)

            assertEquals(restored.content, NovelWorkspaceSessions.load(directory).sessions.getValue(snapshot.branchId).single().content)
            assertTrue(NovelWorkspaceTurnOutputs.loadPending(directory, snapshot.branchId).isEmpty())
        } finally {
            NovelWorkspaceTurnOutputs.end(directory, snapshot.runId, oldEpoch)
        }
    }

    @Test fun oldCleanupDoesNotEndANewRunWithTheSameIdentity() {
        val directory = temporary.newFolder()
        val oldEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        val snapshot = output(directory)
        NovelWorkspaceTurnOutputs.begin(directory, snapshot.runId, oldEpoch)
        restoreEpoch()
        val newEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        NovelWorkspaceTurnOutputs.begin(directory, snapshot.runId, newEpoch)
        try {
            NovelWorkspaceTurnOutputs.save(directory, snapshot, newEpoch)
            NovelWorkspaceTurnOutputs.end(directory, snapshot.runId, oldEpoch)
            NovelWorkspaceTurnOutputs.recoverToSessions(directory, snapshot.branchId)

            assertTrue(NovelWorkspaceSessions.load(directory).sessions.isEmpty())
            assertEquals(listOf(snapshot), NovelWorkspaceTurnOutputs.loadPending(directory, snapshot.branchId))
        } finally {
            NovelWorkspaceTurnOutputs.end(directory, snapshot.runId, newEpoch)
        }
    }

    @Test fun coldSnapshotRecoversOnceIntoTheBoundBranch() {
        val directory = temporary.newFolder()
        val snapshot = output(directory)
        NovelWorkspaceTurnOutputs.save(directory, snapshot)
        assertEquals(listOf(snapshot), NovelWorkspaceTurnOutputs.loadPending(File(directory.path), "branch-1"))
        NovelWorkspaceTurnOutputs.recoverToSessions(directory, "other-branch")
        assertTrue(NovelWorkspaceSessions.load(directory).sessions.isEmpty())
        NovelWorkspaceTurnOutputs.recoverToSessions(directory, "branch-1")
        NovelWorkspaceTurnOutputs.recoverToSessions(directory, "branch-1")
        val message = NovelWorkspaceSessions.load(directory).sessions.getValue("branch-1").single()
        assertEquals(snapshot.runId, message.id)
        assertEquals("interrupted", message.kind)
        assertEquals(snapshot.content, message.content)
        assertTrue(NovelWorkspaceTurnOutputs.loadPending(directory, "branch-1").isEmpty())
        assertFalse(directory.resolve("branches").exists())
    }

    @Test fun liveRunIsNotRecoveredByAnOrdinaryHistoryRefresh() {
        val directory = temporary.newFolder()
        val snapshot = output(directory)
        NovelWorkspaceTurnOutputs.begin(directory, snapshot.runId)
        try {
            NovelWorkspaceTurnOutputs.save(directory, snapshot)
            NovelWorkspaceTurnOutputs.recoverToSessions(directory, "branch-1")
            assertTrue(NovelWorkspaceSessions.load(directory).sessions.isEmpty())
            assertEquals(listOf(snapshot), NovelWorkspaceTurnOutputs.loadPending(directory, "branch-1"))
        } finally {
            NovelWorkspaceTurnOutputs.end(directory, snapshot.runId)
        }
        NovelWorkspaceTurnOutputs.recoverToSessions(directory, "branch-1")
        assertEquals("interrupted", NovelWorkspaceSessions.load(directory).sessions.getValue("branch-1").single().kind)
    }

    @Test fun completedSnapshotBridgesTheGapBeforeSessionPersistence() {
        val directory = temporary.newFolder()
        val snapshot = output(directory, completed = true)
        NovelWorkspaceTurnOutputs.save(directory, snapshot)
        NovelWorkspaceTurnOutputs.recoverToSessions(directory, "branch-1")
        val message = NovelWorkspaceSessions.load(directory).sessions.getValue("branch-1").single()
        assertEquals("discussion", message.kind)
        // Simulate death after saving the session and before deleting its snapshot.
        NovelWorkspaceTurnOutputs.save(directory, snapshot)
        NovelWorkspaceTurnOutputs.recoverToSessions(directory, "branch-1")
        assertEquals(listOf(message), NovelWorkspaceSessions.load(directory).sessions.getValue("branch-1"))
    }

    @Test fun successfulSessionAcknowledgementLeavesNoRecoveryBubble() {
        val directory = temporary.newFolder()
        val snapshot = output(directory, completed = true)
        NovelWorkspaceTurnOutputs.save(directory, snapshot)
        NovelWorkspaceTurnOutputs.acknowledge(directory, snapshot.runId)
        NovelWorkspaceTurnOutputs.recoverToSessions(directory, "branch-1")
        assertTrue(NovelWorkspaceSessions.load(directory).sessions.isEmpty())
    }
}
