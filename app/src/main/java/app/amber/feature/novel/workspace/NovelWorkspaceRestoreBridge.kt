package app.amber.feature.novel.workspace

import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NovelWorkspaceRestoreState(val epoch: Long, val restoring: Boolean)

/** Connect application restore to the existing novel transaction and execution owners. */
class NovelWorkspaceRestoreBridge(
    private val gate: SyncRestoreWriteGate,
    private val workspaceRoot: File,
    private val cancelBackgroundWork: () -> Unit,
) : AutoCloseable {
    private val mutableState = MutableStateFlow(NovelWorkspaceRestoreState(NovelWorkspaceRestoreBoundary.currentEpoch(), false))
    val state: StateFlow<NovelWorkspaceRestoreState> = mutableState.asStateFlow()
    private var active = false
    private val registration = gate.addRestoreLifecycleListener(
        onStarted = {
            active = gate.restoresFileRoot(NovelWorkspaceProjectRepository.RELATIVE_ROOT)
            if (active) {
                NovelWorkspaceRestoreBoundary.beginRestore()
                mutableState.value = NovelWorkspaceRestoreState(NovelWorkspaceRestoreBoundary.currentEpoch(), true)
                pauseRunningJobs()
                cancelBackgroundWork()
            }
        },
        onFinished = { succeeded, dataCommitted ->
            if (active) try {
                // This also runs after rollback: cancelled WorkManager executions stay paused.
                pauseRunningJobs()
            } finally {
                synchronized(NovelWorkspaceGhostwriteJobs) {
                    NovelWorkspaceRestoreBoundary.finishRestore()
                    if ((succeeded || dataCommitted) && gate.adoptsFileSnapshot(NovelWorkspaceProjectRepository.RELATIVE_ROOT)) {
                        NovelWorkspaceProjectRepository(workspaceRoot).recordNativeRestore()
                    }
                }
                mutableState.value = NovelWorkspaceRestoreState(NovelWorkspaceRestoreBoundary.currentEpoch(), false)
                active = false
            }
        },
    )

    private fun pauseRunningJobs() {
        workspaceRoot.listFiles().orEmpty().filter { it.isDirectory }.forEach {
            NovelWorkspaceGhostwriteJobs.pauseRunningForRestore(it)
        }
    }

    /** Legacy deletion suspends for local IO; use the existing restore mutex for that path. */
    suspend fun <T> withWriter(expectedEpoch: Long, block: suspend () -> T): T =
        gate.withCurrentWriterOrCancel {
            NovelWorkspaceRestoreBoundary.write(expectedEpoch) { Unit }
            block()
        }

    override fun close() = registration.close()
}
