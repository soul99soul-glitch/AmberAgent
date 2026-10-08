package app.amber.feature.novel.workspace

import app.amber.feature.novelworkspace.NovelWorkspaceTurnOutput
import app.amber.feature.novelworkspace.NovelWorkspaceTurnOutputs
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreCancelled
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Bounded local output snapshots for interactive author turns; no provider resume or tool replay. */
internal fun Flow<NovelWorkspaceRuntime.TurnEvent>.captureAuthorOutput(
    request: NovelWorkspaceRuntime.TurnRequest,
    runId: String,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): Flow<NovelWorkspaceRuntime.TurnEvent> {
    if (request.readOnlyTools || request.autoApproveCanon || request.ownerJobId != null) return this
    val source = this
    return flow {
        val content = StringBuilder()
        val createdAt = Instant.now()
        val mutex = Mutex()
        var unsavedBytes = 0
        var dirty = false
        var completed = false

        suspend fun flush(dropStale: Boolean = false) = mutex.withLock {
            if (!dirty || content.isBlank()) return@withLock
            val output = NovelWorkspaceTurnOutput(
                runId = runId,
                projectPath = request.projectDirectory.absolutePath,
                branchId = request.branchId,
                branchSlug = request.branchSlug,
                userText = request.userText,
                content = content.toString(),
                completed = completed,
                createdAt = createdAt,
            )
            try {
                withContext(ioDispatcher) {
                    NovelWorkspaceTurnOutputs.save(request.projectDirectory, output, request.restoreEpoch)
                }
            } catch (stale: NovelWorkspaceRestoreCancelled) {
                if (!dropStale) throw stale
                // Cancellation cleanup may run after restore replaced the project.
                // Discard this old payload without replacing the original terminal cause.
                content.clear()
            }
            dirty = false
            unsavedBytes = 0
        }

        NovelWorkspaceTurnOutputs.begin(request.projectDirectory, runId, request.restoreEpoch)
        try {
            coroutineScope {
                val periodicSnapshot = launch {
                    while (isActive) {
                        delay(2_000)
                        flush()
                    }
                }
                try {
                    source.collect { event ->
                        NovelWorkspaceRestoreBoundary.write(request.restoreEpoch) { Unit }
                        val flushNow = mutex.withLock {
                            when (event) {
                                is NovelWorkspaceRuntime.TurnEvent.Delta -> {
                                    content.append(event.text)
                                    unsavedBytes += event.text.toByteArray(Charsets.UTF_8).size
                                    dirty = true
                                }
                                is NovelWorkspaceRuntime.TurnEvent.Completed -> {
                                    if (event.finalText.isNotBlank()) {
                                        content.clear().append(event.finalText)
                                        completed = true
                                    }
                                    dirty = true
                                }
                                else -> Unit
                            }
                            unsavedBytes >= 8 * 1024 ||
                                event is NovelWorkspaceRuntime.TurnEvent.Completed ||
                                event is NovelWorkspaceRuntime.TurnEvent.Failed
                        }
                        if (flushNow) flush()
                        if (event is NovelWorkspaceRuntime.TurnEvent.Completed || event is NovelWorkspaceRuntime.TurnEvent.Failed) {
                            // Terminal callbacks may reload history immediately.
                            NovelWorkspaceTurnOutputs.end(request.projectDirectory, runId, request.restoreEpoch)
                        }
                        emit(if (event is NovelWorkspaceRuntime.TurnEvent.Completed) event.copy(outputId = runId) else event)
                    }
                } finally {
                    withContext(NonCancellable) {
                        periodicSnapshot.cancelAndJoin()
                        flush(dropStale = true)
                    }
                }
            }
        } finally {
            NovelWorkspaceTurnOutputs.end(request.projectDirectory, runId, request.restoreEpoch)
        }
    }
}
