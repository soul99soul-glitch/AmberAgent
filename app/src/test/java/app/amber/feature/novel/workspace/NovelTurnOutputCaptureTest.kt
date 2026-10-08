package app.amber.feature.novel.workspace

import app.amber.ai.provider.Model
import app.amber.core.settings.Settings
import app.amber.feature.novelworkspace.NovelWorkspaceSessions
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreCancelled
import app.amber.feature.novelworkspace.NovelWorkspaceTurnOutput
import app.amber.feature.novelworkspace.NovelWorkspaceTurnOutputs
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NovelTurnOutputCaptureTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun request(directory: File) = NovelWorkspaceRuntime.TurnRequest(
        projectDirectory = directory,
        branchId = "branch-1",
        branchSlug = "主线",
        userText = "继续写下去",
        systemPrompt = "",
        settings = Settings(),
        model = Model(),
    )

    private fun restoreEpoch() {
        NovelWorkspaceRestoreBoundary.beginRestore()
        NovelWorkspaceRestoreBoundary.finishRestore()
    }

    private fun restoredOutput(directory: File, runId: String) = NovelWorkspaceTurnOutput(
        runId = runId,
        projectPath = directory.absolutePath,
        branchId = "branch-1",
        branchSlug = "主线",
        userText = "恢复后的问题",
        content = "恢复后的输出",
        createdAt = Instant.EPOCH,
    )

    @Test fun finalCleanupDropsOldPayloadAndPreservesTheOriginalCancellation() = runTest {
        val directory = temporary.newFolder()
        val oldRequest = request(directory)
        val emitted = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val originalCancellation = CancellationException("author stopped the old turn")
        val turn = async {
            try {
                flow<NovelWorkspaceRuntime.TurnEvent> {
                    emit(NovelWorkspaceRuntime.TurnEvent.Delta("旧任务的未保存输出"))
                    emitted.complete(Unit)
                    resume.await()
                    throw originalCancellation
                }.captureAuthorOutput(oldRequest, "same-run", StandardTestDispatcher(testScheduler)).collect {}
                null
            } catch (error: CancellationException) {
                error
            }
        }
        emitted.await()
        restoreEpoch()
        val restored = restoredOutput(directory, "same-run")
        NovelWorkspaceTurnOutputs.save(directory, restored)
        resume.complete(Unit)

        val cancellation = checkNotNull(turn.await())
        assertEquals(originalCancellation.javaClass, cancellation.javaClass)
        assertEquals(originalCancellation.message, cancellation.message)
        // Coroutine stacktrace recovery may copy the exception, retaining the original as its cause.
        assertTrue(generateSequence<Throwable>(cancellation) { it.cause }.any { it === originalCancellation })
        assertEquals(listOf(restored), NovelWorkspaceTurnOutputs.loadPending(directory, "branch-1"))
    }

    @Test fun queuedSnapshotCannotWriteAfterRestoreWhileWaitingForIo() = runTest {
        val directory = temporary.newFolder()
        val oldRequest = request(directory)
        val turn = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                flow<NovelWorkspaceRuntime.TurnEvent> {
                    emit(NovelWorkspaceRuntime.TurnEvent.Delta("汉".repeat(3_000)))
                }.captureAuthorOutput(oldRequest, "same-run", StandardTestDispatcher(testScheduler)).toList()
                null
            } catch (error: CancellationException) {
                error
            }
        }
        assertFalse(turn.isCompleted)
        restoreEpoch()
        val restored = restoredOutput(directory, "same-run")
        NovelWorkspaceTurnOutputs.save(directory, restored)

        assertTrue(turn.await() is NovelWorkspaceRestoreCancelled)
        assertEquals(listOf(restored), NovelWorkspaceTurnOutputs.loadPending(directory, "branch-1"))
    }

    @Test fun completedCallbackFromAnOldTurnIsNotDeliveredAfterRestore() = runTest {
        val directory = temporary.newFolder()
        val oldRequest = request(directory)
        val emitted = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val received = mutableListOf<NovelWorkspaceRuntime.TurnEvent>()
        val turn = async {
            try {
                flow<NovelWorkspaceRuntime.TurnEvent> {
                    emit(NovelWorkspaceRuntime.TurnEvent.Delta("旧任务的部分输出"))
                    emitted.complete(Unit)
                    resume.await()
                    emit(NovelWorkspaceRuntime.TurnEvent.Completed("旧任务的完成输出", proposal = null))
                }.captureAuthorOutput(oldRequest, "same-run", StandardTestDispatcher(testScheduler)).toList(received)
                null
            } catch (error: CancellationException) {
                error
            }
        }
        emitted.await()
        restoreEpoch()
        val restored = restoredOutput(directory, "same-run")
        NovelWorkspaceTurnOutputs.save(directory, restored)
        resume.complete(Unit)

        assertTrue(turn.await() is NovelWorkspaceRestoreCancelled)
        assertEquals(listOf(NovelWorkspaceRuntime.TurnEvent.Delta("旧任务的部分输出")), received)
        assertEquals(listOf(restored), NovelWorkspaceTurnOutputs.loadPending(directory, "branch-1"))
    }

    @Test fun stoppingBeforeTheSnapshotIntervalStillSavesEveryReceivedDelta() = runTest {
        val directory = temporary.newFolder()
        val emitted = CompletableDeferred<Unit>()
        val turn = launch {
            flow<NovelWorkspaceRuntime.TurnEvent> {
                emit(NovelWorkspaceRuntime.TurnEvent.Delta("尚未写完"))
                emitted.complete(Unit)
                awaitCancellation()
            }.captureAuthorOutput(request(directory), "run-stop", StandardTestDispatcher(testScheduler)).collect {}
        }
        emitted.await()
        turn.cancelAndJoin()
        assertEquals("尚未写完", NovelWorkspaceTurnOutputs.loadPending(directory, "branch-1").single().content)
        NovelWorkspaceTurnOutputs.recoverToSessions(File(directory.path), "branch-1")
        assertEquals("interrupted", NovelWorkspaceSessions.load(directory).sessions.getValue("branch-1").single().kind)
    }

    @Test fun aShortPausedStreamHasADurableSnapshotAfterTwoSeconds() = runTest {
        val directory = temporary.newFolder()
        val turn = launch {
            flow<NovelWorkspaceRuntime.TurnEvent> {
                emit(NovelWorkspaceRuntime.TurnEvent.Delta("很短的输出"))
                awaitCancellation()
            }.captureAuthorOutput(request(directory), "run-time", StandardTestDispatcher(testScheduler)).collect {}
        }
        runCurrent()
        assertTrue(NovelWorkspaceTurnOutputs.loadPending(directory, "branch-1").isEmpty())
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals("很短的输出", NovelWorkspaceTurnOutputs.loadPending(directory, "branch-1").single().content)
        turn.cancelAndJoin()
    }

    @Test fun largeUnicodeOutputIsSavedBeforeTheNextStreamEvent() = runTest {
        val directory = temporary.newFolder()
        val content = "汉".repeat(3_000)
        val turn = launch {
            flow<NovelWorkspaceRuntime.TurnEvent> {
                emit(NovelWorkspaceRuntime.TurnEvent.Delta(content))
                assertEquals(content, NovelWorkspaceTurnOutputs.loadPending(directory, "branch-1").single().content)
                awaitCancellation()
            }.captureAuthorOutput(request(directory), "run-size", StandardTestDispatcher(testScheduler)).collect {}
        }
        runCurrent()
        turn.cancelAndJoin()
    }

    @Test fun providerFailureKeepsPartialTextAndCompletionHasAStableOutputIdentity() = runTest {
        val directory = temporary.newFolder()
        val events = flow<NovelWorkspaceRuntime.TurnEvent> {
            emit(NovelWorkspaceRuntime.TurnEvent.Delta("已有输出"))
            emit(NovelWorkspaceRuntime.TurnEvent.Failed("provider cut"))
        }.captureAuthorOutput(request(directory), "run-failure", StandardTestDispatcher(testScheduler)).toList()
        assertTrue(events.last() is NovelWorkspaceRuntime.TurnEvent.Failed)
        assertFalse(NovelWorkspaceTurnOutputs.loadPending(directory, "branch-1").single().completed)
        NovelWorkspaceTurnOutputs.recoverToSessions(directory, "branch-1")
        val completed = flow<NovelWorkspaceRuntime.TurnEvent> {
            emit(NovelWorkspaceRuntime.TurnEvent.Delta("草稿"))
            emit(NovelWorkspaceRuntime.TurnEvent.Completed("最后的完整回答", proposal = null))
        }.captureAuthorOutput(request(directory), "run-complete", StandardTestDispatcher(testScheduler)).toList()
        assertEquals("run-complete", (completed.last() as NovelWorkspaceRuntime.TurnEvent.Completed).outputId)
        val pending = NovelWorkspaceTurnOutputs.loadPending(directory, "branch-1").single()
        assertTrue(pending.completed)
        assertEquals("最后的完整回答", pending.content)
    }

    @Test fun failureCallbackCanImmediatelyDisplayItsDurablePartialOutput() = runTest {
        val directory = temporary.newFolder()
        flow<NovelWorkspaceRuntime.TurnEvent> {
            emit(NovelWorkspaceRuntime.TurnEvent.Delta("中断前的回答"))
            emit(NovelWorkspaceRuntime.TurnEvent.Failed("provider cut"))
        }.captureAuthorOutput(request(directory), "run-failure-ui", StandardTestDispatcher(testScheduler)).collect { event ->
            if (event is NovelWorkspaceRuntime.TurnEvent.Failed) {
                NovelWorkspaceTurnOutputs.recoverToSessions(directory, "branch-1")
                assertEquals("中断前的回答", NovelWorkspaceSessions.load(directory).sessions.getValue("branch-1").single().content)
            }
        }
    }

    @Test fun auditAndUnattendedTurnsDoNotCreateAuthorChatRecovery() = runTest {
        val directory = temporary.newFolder()
        listOf(
            request(directory).copy(readOnlyTools = true),
            request(directory).copy(autoApproveCanon = true),
            request(directory).copy(ownerJobId = "batch-owner"),
        ).forEachIndexed { index, request ->
            val events = flow<NovelWorkspaceRuntime.TurnEvent> {
                emit(NovelWorkspaceRuntime.TurnEvent.Delta("内部审核或批次候选"))
                emit(NovelWorkspaceRuntime.TurnEvent.Completed("内部审核或批次候选", proposal = null))
            }.captureAuthorOutput(request, "excluded-$index", StandardTestDispatcher(testScheduler)).toList()
            assertNull((events.last() as NovelWorkspaceRuntime.TurnEvent.Completed).outputId)
        }
        assertTrue(NovelWorkspaceTurnOutputs.loadPending(directory, "branch-1").isEmpty())
    }
}
