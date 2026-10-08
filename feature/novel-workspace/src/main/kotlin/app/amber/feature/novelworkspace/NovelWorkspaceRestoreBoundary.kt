package app.amber.feature.novelworkspace

import java.util.concurrent.CancellationException

/** Short local writes share the job-owner monitor; providers and restore IO stay outside it. */
object NovelWorkspaceRestoreBoundary {
    private var epoch = 0L
    private var restoring = false

    fun currentEpoch(): Long = synchronized(NovelWorkspaceGhostwriteJobs) { epoch }

    fun isCurrent(expected: Long): Boolean = synchronized(NovelWorkspaceGhostwriteJobs) {
        !restoring && expected == epoch
    }

    fun <T> write(expectedEpoch: Long? = null, block: () -> T): T = synchronized(NovelWorkspaceGhostwriteJobs) {
        if (restoring || expectedEpoch != null && expectedEpoch != epoch) {
            throw NovelWorkspaceRestoreCancelled()
        }
        block()
    }

    fun beginRestore() = synchronized(NovelWorkspaceGhostwriteJobs) {
        check(!restoring) { "Novel restore is already active" }
        epoch += 1
        restoring = true
    }

    fun finishRestore() = synchronized(NovelWorkspaceGhostwriteJobs) {
        check(restoring) { "Novel restore is not active" }
        epoch += 1
        restoring = false
    }
}

class NovelWorkspaceRestoreCancelled : CancellationException("Novel workspace changed during restore")
