package app.amber.feature.novel

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.amber.feature.novel.domain.NovelChapterPlanAcceptanceV1
import app.amber.feature.novel.domain.NovelContinuityAuditV1
import app.amber.feature.novel.domain.NovelContinuityIssueCategoryV1
import app.amber.feature.novel.domain.NovelContinuityIssueSeverityV1
import app.amber.feature.novel.domain.NovelContinuityIssueV1
import app.amber.feature.novel.domain.NovelContinuityReferenceV1
import app.amber.feature.novel.domain.NovelGhostwriteReadinessIssue
import app.amber.feature.novel.domain.NovelMutationContext
import app.amber.feature.novel.domain.NovelCreateProjectCommand
import app.amber.feature.novel.domain.NovelReducer
import app.amber.feature.novel.model.NovelBranchSyncStatus
import app.amber.feature.novel.model.NovelActiveRunRecord
import app.amber.feature.novel.model.NovelCandidateId
import app.amber.feature.novel.model.NovelCandidateKind
import app.amber.feature.novel.model.NovelCandidateRecord
import app.amber.feature.novel.model.NovelCandidateStatus
import app.amber.feature.novel.model.NovelChapterPlanId
import app.amber.feature.novel.model.NovelChapterPlanRecord
import app.amber.feature.novel.model.NovelChapterPlanStatus
import app.amber.feature.novel.model.NovelChapterVersionId
import app.amber.feature.novel.model.NovelCheckpointId
import app.amber.feature.novel.model.NovelCollectionSource
import app.amber.feature.novel.model.NovelCollectionTarget
import app.amber.feature.novel.model.NovelCollaborationMode
import app.amber.feature.novel.model.NovelGenerationGranularity
import app.amber.feature.novel.model.NovelInjectionMode
import app.amber.feature.novel.model.NovelMaterialId
import app.amber.feature.novel.model.NovelMaterialKind
import app.amber.feature.novel.model.NovelMaterialRecord
import app.amber.feature.novel.model.NovelMaterialRevisionId
import app.amber.feature.novel.model.NovelMaterialRevisionRecord
import app.amber.feature.novel.model.NovelMessageId
import app.amber.feature.novel.model.NovelOperationId
import app.amber.feature.novel.model.NovelOutcome
import app.amber.feature.novel.model.NovelProjectCreationMode
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelProjectSummary
import app.amber.feature.novel.model.NovelReceiptId
import app.amber.feature.novel.model.NovelRunId
import app.amber.feature.novel.model.NovelRunKind
import app.amber.feature.novel.model.NovelRunStatus
import app.amber.feature.novel.model.NovelSessionId
import app.amber.feature.novel.model.NovelSessionMessageKind
import app.amber.feature.novel.model.NovelSessionMessageRecord
import app.amber.feature.novel.model.NovelSessionMode
import app.amber.feature.novel.model.NovelSessionRole
import app.amber.feature.novel.model.NovelStateSnapshotId
import java.time.Instant
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NovelGhostwriteCoordinatorTest {
    @Test
    fun startRejectsMissingConfirmedPlanWithoutStartingAnything() = runTest {
        val fixture = readyFixture().let { it.copy(document = it.document.copy(chapterPlans = emptyList())) }
        val fake = FakeNovelCreation(fixture.document)
        val coordinator = coordinator(fake, backgroundScope)

        val result = coordinator.start(fixture.projectId, fixture.branchId)

        val rejected = expectType<NovelGhostwriteStartResult.Rejected>(result)
        assertEquals(NovelGhostwriteFailureReason.ReadinessBlocked, rejected.reason)
        assertEquals(listOf(NovelGhostwriteReadinessIssue.MissingChapterPlan), rejected.readinessIssues)
        assertTrue(fake.startedRequests.isEmpty())
        assertTrue(fake.performedIntents.isEmpty())
        assertTrue(fake.interruptRequests.isEmpty())
        assertEquals(NovelGhostwritePhase.Failed, coordinator.progress.value.phase)
        assertNull(coordinator.progress.value.runId)
    }

    @Test
    fun notificationPreflightRejectsBeforeNovelRunStarts() = runTest {
        val fixture = readyFixture()
        val fake = FakeNovelCreation(fixture.document).apply {
            notificationPermissionGranted = false
        }
        val coordinator = coordinator(fake, backgroundScope)

        val rejected = expectType<NovelGhostwriteStartResult.Rejected>(
            coordinator.start(fixture.projectId, fixture.branchId),
        )

        assertEquals(NovelGhostwriteFailureReason.NotificationPermissionRequired, rejected.reason)
        assertTrue(fake.startedRequests.isEmpty())
        assertTrue(fake.foregroundStarts.isEmpty())
        assertTrue(fake.interruptRequests.isEmpty())
    }

    @Test
    fun notificationBecomingUnavailablePausesTheExactOwnedRun() = runTest {
        val fixture = readyFixture()
        val fake = FakeNovelCreation(fixture.document).apply { enqueueRun(RUN_A) }
        val coordinator = coordinator(fake, backgroundScope)

        assertEquals(
            NovelGhostwriteStartResult.Started,
            coordinator.start(fixture.projectId, fixture.branchId),
        )
        fake.notificationPermissionGranted = false
        advanceTimeBy(1_500)
        runCurrent()

        assertEquals(NovelGhostwritePhase.Paused, coordinator.progress.value.phase)
        assertEquals(
            NovelGhostwritePauseReason.NotificationUnavailable,
            coordinator.progress.value.pauseReason,
        )
        assertEquals(
            listOf(NovelInterruptRequest(fixture.projectId, RUN_A, NovelInterruptReason.User)),
            fake.interruptRequests,
        )
        assertFalse(coordinator.owns(fixture.projectId))
    }

    @Test
    fun foregroundStartFailureInterruptsTheNewRunExactly() = runTest {
        val fixture = readyFixture()
        val fake = FakeNovelCreation(fixture.document).apply {
            enqueueRun(RUN_A)
            foregroundStartAllowed = false
        }
        val coordinator = coordinator(fake, backgroundScope)

        val rejected = expectType<NovelGhostwriteStartResult.Rejected>(
            coordinator.start(fixture.projectId, fixture.branchId),
        )

        assertEquals(NovelGhostwriteFailureReason.ForegroundServiceUnavailable, rejected.reason)
        assertEquals(1, fake.startedRequests.size)
        assertEquals(
            listOf(NovelInterruptRequest(fixture.projectId, RUN_A, NovelInterruptReason.User)),
            fake.interruptRequests,
        )
    }

    @Test
    fun callerOwnedRunIsClaimedBeforeNovelCreationStart() = runTest {
        val fixture = readyFixture()
        val fake = FakeNovelCreation(fixture.document).apply { enqueueRun(RUN_A) }
        val registry = NovelBackgroundRunRegistry()
        fake.startAction = { request ->
            assertEquals(RUN_A, request.runId)
            assertEquals(setOf(RUN_A), registry.ownedRunIds(fixture.projectId))
        }
        val coordinator = coordinator(fake, backgroundScope, registry)

        assertEquals(
            NovelGhostwriteStartResult.Started,
            coordinator.start(fixture.projectId, fixture.branchId),
        )

        assertTrue(coordinator.pause(RUN_A))
        assertTrue(registry.ownedRunIds(fixture.projectId).isEmpty())
    }

    @Test
    fun startFailureReleasesTheExactBackgroundClaim() = runTest {
        val fixture = readyFixture()
        val fake = FakeNovelCreation(fixture.document).apply {
            enqueueRun(RUN_A)
            startAction = { error("start failed") }
        }
        val registry = NovelBackgroundRunRegistry()
        val coordinator = coordinator(fake, backgroundScope, registry)

        val result = coordinator.start(fixture.projectId, fixture.branchId)

        assertEquals(
            NovelGhostwriteFailureReason.GenerationFailed,
            expectType<NovelGhostwriteStartResult.Rejected>(result).reason,
        )
        assertFalse(coordinator.owns(fixture.projectId))
        assertTrue(registry.ownedRunIds(fixture.projectId).isEmpty())
    }

    @Test
    fun pauseDuringForegroundAcquireDoesNotPublishStartedOrLeakTheLease() = runTest {
        val fixture = readyFixture()
        val fake = FakeNovelCreation(fixture.document).apply { enqueueRun(RUN_A) }
        lateinit var coordinator: NovelGhostwriteCoordinator
        fake.foregroundStartAction = { runId ->
            assertTrue(coordinator.pause(runId))
            true
        }
        coordinator = coordinator(fake, backgroundScope)

        val result = coordinator.start(fixture.projectId, fixture.branchId)
        runCurrent()

        expectType<NovelGhostwriteStartResult.Rejected>(result)
        assertEquals(NovelGhostwritePhase.Paused, coordinator.progress.value.phase)
        assertFalse(coordinator.owns(fixture.projectId))
        assertTrue(fake.foregroundStops.contains(RUN_A))
    }

    @Test
    fun staleFinallyDoesNotStopANewerLeaseThatReusesTheSameRunId() = runTest {
        val fixture = readyFixture()
        val fake = FakeNovelCreation(fixture.document)
        val candidate = fake.addCandidate(fixture, RUN_A)
        val oldAcceptanceBarrier = CompletableDeferred<Unit>()
        fake.acceptanceBarrierCandidateId = candidate.id
        fake.acceptanceBarrier = oldAcceptanceBarrier
        val coordinator = coordinator(fake, backgroundScope)

        assertEquals(
            NovelGhostwriteStartResult.Started,
            coordinator.start(fixture.projectId, fixture.branchId),
        )
        runCurrent()
        assertEquals(NovelGhostwritePhase.Accepting, coordinator.progress.value.phase)
        assertTrue(coordinator.pause(RUN_A))
        assertEquals(1, fake.foregroundStops.count { it == RUN_A })

        fake.acceptanceBarrierCandidateId = null
        assertEquals(
            NovelGhostwriteStartResult.Started,
            coordinator.start(fixture.projectId, fixture.branchId),
        )
        runCurrent()
        assertEquals(listOf(RUN_A, RUN_A), fake.foregroundStarts)
        assertEquals(2, fake.foregroundStops.count { it == RUN_A })

        oldAcceptanceBarrier.complete(Unit)
        runCurrent()

        assertEquals(2, fake.foregroundStops.count { it == RUN_A })
        assertEquals(NovelGhostwritePhase.WaitingUser, coordinator.progress.value.phase)
    }

    @Test
    fun pauseInterruptsOnlyTheExactOwnedRunAndBackgroundDoesNotCancelIt() = runTest {
        val fixture = readyFixture()
        val fake = FakeNovelCreation(fixture.document).apply { enqueueRun(RUN_A) }
        val registry = NovelBackgroundRunRegistry()
        val coordinator = coordinator(fake, backgroundScope, registry)
        assertEquals(
            NovelGhostwriteStartResult.Started,
            coordinator.start(fixture.projectId, fixture.branchId),
        )
        assertEquals(RUN_A, coordinator.progress.value.runId)
        val duplicate = expectType<NovelGhostwriteStartResult.Rejected>(
            coordinator.start(fixture.projectId, fixture.branchId),
        )
        assertEquals(NovelGhostwriteFailureReason.AlreadyRunning, duplicate.reason)
        assertEquals(1, fake.startedRequests.size)

        val lifecycleBridge = NovelLifecycleBridge(fake, registry, backgroundScope)
        lifecycleBridge.onStop(TestLifecycleOwner())
        runCurrent()
        assertEquals(
            listOf(
                NovelInterruptRequest(
                    projectId = fixture.projectId,
                    reason = NovelInterruptReason.Background,
                    excludedRunIds = setOf(RUN_A),
                ),
            ),
            fake.interruptRequests,
        )

        assertFalse(coordinator.pause(RUN_B))
        assertEquals(1, fake.interruptRequests.size)
        assertTrue(coordinator.pause(RUN_A))
        runCurrent()

        assertEquals(
            listOf(
                NovelInterruptRequest(
                    projectId = fixture.projectId,
                    reason = NovelInterruptReason.Background,
                    excludedRunIds = setOf(RUN_A),
                ),
                NovelInterruptRequest(fixture.projectId, RUN_A, NovelInterruptReason.User),
            ),
            fake.interruptRequests,
        )
        assertEquals(NovelGhostwritePhase.Paused, coordinator.progress.value.phase)
        assertEquals(NovelGhostwritePauseReason.UserPaused, coordinator.progress.value.pauseReason)
        assertNull(coordinator.progress.value.runId)
        assertTrue(fake.foregroundStops.all { it == RUN_A })
    }

    @Test
    fun backgroundUsesOneProjectWideInterruptAndExcludesOnlyOwnedGhostwriteRuns() = runTest {
        val fixture = readyFixture()
        val branch = fixture.document.branches.single()
        val running = NovelActiveRunRecord(
            id = RUN_A,
            operationID = NovelOperationId.generate(),
            requestPayloadSHA256 = "a".repeat(64),
            branchID = fixture.branchId,
            sessionID = branch.sessionID,
            kind = NovelRunKind.Prose,
            mode = NovelSessionMode.WriteProse,
            granularity = NovelGenerationGranularity.WholeChapter,
            userMessageID = NovelMessageId.generate(),
            messageID = NovelMessageId.generate(),
            candidateID = NovelCandidateId.generate(),
            baseCheckpointID = branch.headCheckpointID,
            baseHeadRevision = branch.headRevision,
            status = NovelRunStatus.Running,
            receiptID = NovelReceiptId.generate(),
            startedAt = NOW,
        )
        val fake = FakeNovelCreation(
            fixture.document.copy(
                activeRuns = listOf(running),
                branches = fixture.document.branches.map { it.copy(activeRunID = RUN_A) },
            ),
        )
        val registry = NovelBackgroundRunRegistry()
        val coordinator = coordinator(fake, backgroundScope, registry)
        val bridge = NovelLifecycleBridge(fake, registry, backgroundScope)

        bridge.onStop(TestLifecycleOwner())
        runCurrent()
        assertEquals(
            listOf(NovelInterruptRequest(fixture.projectId, reason = NovelInterruptReason.Background)),
            fake.interruptRequests,
        )

        fake.interruptRequests.clear()
        fake.document = fake.document.copy(activeRuns = emptyList())
        bridge.onStop(TestLifecycleOwner())
        runCurrent()
        assertEquals(
            listOf(NovelInterruptRequest(fixture.projectId, reason = NovelInterruptReason.Background)),
            fake.interruptRequests,
        )
    }

    @Test
    fun backgroundPreservesAnExactRunClaimedByTheSharedRegistry() = runTest {
        val fixture = readyFixture()
        val fake = FakeNovelCreation(fixture.document)
        val registry = NovelBackgroundRunRegistry()
        assertTrue(registry.claim("batch:job:7:work", fixture.projectId, RUN_A))
        val bridge = NovelLifecycleBridge(fake, registry, backgroundScope)

        bridge.onStop(TestLifecycleOwner())
        runCurrent()

        assertEquals(
            listOf(
                NovelInterruptRequest(
                    projectId = fixture.projectId,
                    reason = NovelInterruptReason.Background,
                    excludedRunIds = setOf(RUN_A),
                ),
            ),
            fake.interruptRequests,
        )
    }

    @Test
    fun staleAcceptanceFromRunADoesNotAdvanceOrReleaseRunB() = runTest {
        val fixture = readyFixture()
        val fake = FakeNovelCreation(fixture.document)
        val candidateA = fake.addCandidate(fixture, RUN_A)
        val barrier = CompletableDeferred<Unit>()
        fake.acceptanceBarrierCandidateId = candidateA.id
        fake.acceptanceBarrier = barrier
        val coordinator = coordinator(fake, backgroundScope)

        assertEquals(
            NovelGhostwriteStartResult.Started,
            coordinator.start(fixture.projectId, fixture.branchId),
        )
        runCurrent()
        assertEquals(NovelGhostwritePhase.Accepting, coordinator.progress.value.phase)
        assertTrue(coordinator.pause(RUN_A))

        fake.updateCandidate(candidateA.id, NovelCandidateStatus.Superseded)
        fake.enqueueRun(RUN_B)
        assertEquals(
            NovelGhostwriteStartResult.Started,
            coordinator.start(fixture.projectId, fixture.branchId),
        )
        assertEquals(RUN_B, coordinator.progress.value.runId)
        assertEquals(NovelGhostwritePhase.Writing, coordinator.progress.value.phase)

        barrier.complete(Unit)
        runCurrent()

        assertEquals(RUN_B, coordinator.progress.value.runId)
        assertEquals(NovelGhostwritePhase.Writing, coordinator.progress.value.phase)
        assertTrue(fake.performedIntents.isEmpty())
        assertFalse(fake.foregroundStops.contains(RUN_B))
        assertTrue(coordinator.pause(RUN_B))
    }

    @Test
    fun obviousRepetitionResumeGeneratesANewCandidate() = runTest {
        val fixture = readyFixture()
        val fake = FakeNovelCreation(fixture.document)
        fake.addCandidate(fixture, RUN_A)
        fake.acceptance = accepted(obviousRepetition = listOf("重复夺回同一信物"))
        val coordinator = coordinator(fake, backgroundScope)

        assertEquals(
            NovelGhostwriteStartResult.Started,
            coordinator.start(fixture.projectId, fixture.branchId),
        )
        runCurrent()
        assertEquals(NovelGhostwritePauseReason.ObviousRepetition, coordinator.progress.value.pauseReason)
        assertTrue(fake.startedRequests.isEmpty())

        fake.acceptance = accepted()
        fake.enqueueRun(RUN_B)
        assertEquals(
            NovelGhostwriteStartResult.Started,
            coordinator.start(fixture.projectId, fixture.branchId),
        )

        assertEquals(1, fake.startedRequests.size)
        assertEquals(RUN_B, coordinator.progress.value.runId)
        assertEquals(NovelGhostwritePhase.Writing, coordinator.progress.value.phase)
        assertTrue(coordinator.pause(RUN_B))
    }

    @Test
    fun acceptanceFailureResumeGeneratesANewCandidate() = runTest {
        val fixture = readyFixture()
        val fake = FakeNovelCreation(fixture.document)
        fake.addCandidate(fixture, RUN_A)
        fake.acceptance = NovelChapterPlanAcceptanceV1(
            schemaVersion = NovelChapterPlanAcceptanceV1.CURRENT_SCHEMA_VERSION,
            accepted = false,
            missingMustHappen = listOf("夺回信物"),
            forbiddenViolations = emptyList(),
            summary = "缺少必发生情节",
        )
        val coordinator = coordinator(fake, backgroundScope)

        assertEquals(
            NovelGhostwriteStartResult.Started,
            coordinator.start(fixture.projectId, fixture.branchId),
        )
        runCurrent()
        assertEquals(NovelGhostwritePauseReason.AcceptanceFailed, coordinator.progress.value.pauseReason)

        fake.acceptance = accepted()
        fake.enqueueRun(RUN_B)
        assertEquals(
            NovelGhostwriteStartResult.Started,
            coordinator.start(fixture.projectId, fixture.branchId),
        )

        assertEquals(1, fake.startedRequests.size)
        assertEquals(RUN_B, coordinator.progress.value.runId)
        assertTrue(coordinator.pause(RUN_B))
    }

    @Test
    fun incompleteContinuityResumeGeneratesANewCandidate() = runTest {
        val fixture = readyFixture()
        val fake = FakeNovelCreation(fixture.document)
        fake.addCandidate(fixture, RUN_A)
        fake.continuityReport = NovelContinuityAuditReport(issues = emptyList(), failedChunkCount = 1)
        val coordinator = coordinator(fake, backgroundScope)

        assertEquals(
            NovelGhostwriteStartResult.Started,
            coordinator.start(fixture.projectId, fixture.branchId),
        )
        runCurrent()
        assertEquals(
            NovelGhostwritePauseReason.ContinuityAuditIncomplete,
            coordinator.progress.value.pauseReason,
        )

        fake.continuityReport = NovelContinuityAuditReport(issues = emptyList(), failedChunkCount = 0)
        fake.enqueueRun(RUN_B)
        assertEquals(
            NovelGhostwriteStartResult.Started,
            coordinator.start(fixture.projectId, fixture.branchId),
        )

        assertEquals(1, fake.startedRequests.size)
        assertEquals(RUN_B, coordinator.progress.value.runId)
        assertTrue(coordinator.pause(RUN_B))
    }

    @Test
    fun canonicalOnlyBlockingContinuityPausesWithoutCollectingOrRewritingCandidate() = runTest {
        val fixture = readyFixture()
        val fake = FakeNovelCreation(fixture.document)
        fake.addCandidate(fixture, RUN_A)
        fake.continuityReport = NovelContinuityAuditReport(
            issues = emptyList(),
            failedChunkCount = 0,
            canonicalOnlyBlockingIssues = listOf(
                NovelContinuityIssueV1(
                    id = "canon-conflict",
                    category = NovelContinuityIssueCategoryV1.Contradiction,
                    severity = NovelContinuityIssueSeverityV1.Blocking,
                    summary = "既有两章互相矛盾",
                    references = listOf(
                        NovelContinuityReferenceV1(1, "第一章", "证据甲"),
                        NovelContinuityReferenceV1(2, "第二章", "证据乙"),
                    ),
                ),
            ),
        )
        val coordinator = coordinator(fake, backgroundScope)

        assertEquals(
            NovelGhostwriteStartResult.Started,
            coordinator.start(fixture.projectId, fixture.branchId),
        )
        runCurrent()

        assertEquals(
            NovelGhostwritePauseReason.CanonicalContinuityConflict,
            coordinator.progress.value.pauseReason,
        )
        assertTrue(fake.performedIntents.isEmpty())
        assertTrue(fake.startedRequests.isEmpty())
    }

    @Test
    fun incompleteCandidateResumeGeneratesANewRunEvenIfTheOldRunLaterPublishesACandidate() = runTest {
        val fixture = readyFixture()
        val fake = FakeNovelCreation(fixture.document).apply { enqueueRun(RUN_A) }
        val coordinator = coordinator(fake, backgroundScope)

        assertEquals(
            NovelGhostwriteStartResult.Started,
            coordinator.start(fixture.projectId, fixture.branchId),
        )
        fake.events(RUN_A).emit(NovelRunEvent.Interrupted("未完成正文"))
        runCurrent()
        assertEquals(NovelGhostwritePauseReason.IncompleteCandidate, coordinator.progress.value.pauseReason)

        fake.addCandidate(fixture, RUN_A)
        fake.enqueueRun(RUN_B)
        assertEquals(
            NovelGhostwriteStartResult.Started,
            coordinator.start(fixture.projectId, fixture.branchId),
        )

        assertEquals(2, fake.startedRequests.size)
        assertEquals(RUN_B, coordinator.progress.value.runId)
        assertTrue(coordinator.pause(RUN_B))
    }

    @Test
    fun blankAvailableCandidateIsNeverReused() = runTest {
        val fixture = readyFixture()
        val fake = FakeNovelCreation(fixture.document)
        fake.addCandidate(fixture, RUN_A, content = "   ")
        fake.enqueueRun(RUN_B)
        val coordinator = coordinator(fake, backgroundScope)

        assertEquals(
            NovelGhostwriteStartResult.Started,
            coordinator.start(fixture.projectId, fixture.branchId),
        )

        assertEquals(1, fake.startedRequests.size)
        assertEquals(RUN_B, coordinator.progress.value.runId)
        assertTrue(coordinator.pause(RUN_B))
    }

    @Test
    fun completedRunAutoCollectsClearsPlanFailClosedSyncsThenWaitsForUser() = runTest {
        val fixture = readyFixture()
        val fake = FakeNovelCreation(fixture.document).apply { enqueueRun(RUN_A) }
        val coordinator = coordinator(fake, backgroundScope)

        assertEquals(
            NovelGhostwriteStartResult.Started,
            coordinator.start(fixture.projectId, fixture.branchId),
        )
        val request = fake.startedRequests.single()
        assertEquals(NovelSessionModeRequest.WriteProse, request.mode)
        assertEquals(NovelGenerationGranularityRequest.WholeChapter, request.granularity)
        assertEquals(NovelRunKindRequest.Prose, request.kind)
        assertEquals(fixture.plan.id, request.ghostwritePlanId)

        val candidate = fake.addCandidate(fixture, RUN_A, content = "第一段。\n\n第二段。")
        fake.events(RUN_A).emit(NovelRunEvent.Completed(candidate.content))
        runCurrent()

        val collect = expectType<NovelIntent.CollectCandidate>(fake.performedIntents[0])
        assertEquals(candidate.id, collect.candidateId)
        assertEquals(candidate.content, collect.selectedText)
        assertEquals(NovelCollectionSource.SystemAutoCollect, collect.source)
        expectType<NovelCollectionTarget.CreateNextChapter>(collect.target)
        expectType<NovelIntent.ClearChapterPlan>(fake.performedIntents[1])
        val sync = expectType<NovelIntent.SyncManualEdits>(fake.performedIntents[2])
        assertTrue(sync.failClosed)
        assertEquals(NovelGhostwritePhase.WaitingUser, coordinator.progress.value.phase)
        assertEquals(NovelGhostwritePauseReason.ChapterCompleted, coordinator.progress.value.pauseReason)
        assertEquals(candidate.id, coordinator.progress.value.candidateId)
        assertNull(coordinator.progress.value.runId)
        assertNull(coordinator.progress.value.chapterPlanDigest)
        assertFalse(coordinator.owns(fixture.projectId))
        assertTrue(fake.foregroundStops.contains(RUN_A))
    }

    @Test
    fun collectedCandidateRecoverySkipsGenerationAndCollectionThenCompletesRemainingWork() = runTest {
        val fixture = readyFixture()
        val fake = FakeNovelCreation(fixture.document)
        val candidate = fake.addCandidate(fixture, RUN_A, status = NovelCandidateStatus.Collected)
        fake.document = fake.document.copy(
            branches = fake.document.branches.map {
                if (it.id == fixture.branchId) it.copy(syncStatus = NovelBranchSyncStatus.NeedsSync) else it
            },
        )
        val coordinator = coordinator(fake, backgroundScope)

        assertEquals(
            NovelGhostwriteStartResult.Started,
            coordinator.start(fixture.projectId, fixture.branchId),
        )
        runCurrent()

        assertTrue(fake.startedRequests.isEmpty())
        assertTrue(fake.performedIntents.none { it is NovelIntent.CollectCandidate })
        expectType<NovelIntent.ClearChapterPlan>(fake.performedIntents[0])
        val sync = expectType<NovelIntent.SyncManualEdits>(fake.performedIntents[1])
        assertTrue(sync.failClosed)
        assertEquals(candidate.id, coordinator.progress.value.candidateId)
        assertEquals(NovelGhostwritePhase.WaitingUser, coordinator.progress.value.phase)
    }

    @Test
    fun collectedRecoveryClearsRemainingPlanButSkipsAlreadyCompletedSync() = runTest {
        val fixture = readyFixture()
        val fake = FakeNovelCreation(fixture.document)
        fake.addCandidate(fixture, RUN_A, status = NovelCandidateStatus.Collected)
        val coordinator = coordinator(fake, backgroundScope)

        assertEquals(
            NovelGhostwriteStartResult.Started,
            coordinator.start(fixture.projectId, fixture.branchId),
        )
        runCurrent()

        assertEquals(1, fake.performedIntents.count { it is NovelIntent.ClearChapterPlan })
        assertTrue(fake.performedIntents.none { it is NovelIntent.SyncManualEdits })
        assertTrue(fake.performedIntents.none { it is NovelIntent.CollectCandidate })
        assertEquals(NovelGhostwritePhase.WaitingUser, coordinator.progress.value.phase)
    }

    @Test
    fun collectedRecoverySurvivesForegroundRejectionAndStillDoesNotRecollect() = runTest {
        val fixture = readyFixture()
        val fake = FakeNovelCreation(fixture.document).apply {
            addCandidate(fixture, RUN_A)
            syncFailuresRemaining = 1
        }
        val coordinator = coordinator(fake, backgroundScope)

        assertEquals(
            NovelGhostwriteStartResult.Started,
            coordinator.start(fixture.projectId, fixture.branchId),
        )
        runCurrent()
        assertEquals(NovelGhostwritePauseReason.SyncFailed, coordinator.progress.value.pauseReason)
        assertTrue(fake.document.chapterPlans.isEmpty())
        assertEquals(1, fake.performedIntents.count { it is NovelIntent.CollectCandidate })

        fake.foregroundStartResults.addLast(false)
        val rejected = expectType<NovelGhostwriteStartResult.Rejected>(
            coordinator.start(fixture.projectId, fixture.branchId),
        )
        assertEquals(NovelGhostwriteFailureReason.ForegroundServiceUnavailable, rejected.reason)

        assertEquals(
            NovelGhostwriteStartResult.Started,
            coordinator.start(fixture.projectId, fixture.branchId),
        )
        runCurrent()

        assertEquals(1, fake.performedIntents.count { it is NovelIntent.CollectCandidate })
        assertEquals(1, fake.performedIntents.count { it is NovelIntent.ClearChapterPlan })
        assertEquals(2, fake.performedIntents.count { it is NovelIntent.SyncManualEdits })
        assertEquals(NovelGhostwritePhase.WaitingUser, coordinator.progress.value.phase)
    }

    private fun coordinator(
        fake: FakeNovelCreation,
        scope: CoroutineScope,
        registry: NovelBackgroundRunRegistry = NovelBackgroundRunRegistry(),
    ): NovelGhostwriteCoordinator =
        NovelGhostwriteCoordinator(
            context = Application(),
            novelCreation = fake,
            appScope = scope,
            backgroundRunRegistry = registry,
            hasNotificationPermission = { fake.notificationPermissionGranted },
            startForeground = { _, _, runId, _, _, _ ->
                fake.foregroundStarts += runId
                fake.foregroundStartAction?.invoke(runId) ?: if (fake.foregroundStartResults.isEmpty()) {
                    fake.foregroundStartAllowed
                } else {
                    fake.foregroundStartResults.removeFirst()
                }
            },
            stopForeground = { _, runId, _ -> fake.foregroundStops += runId },
            newRunId = fake::nextRunId,
            now = { NOW },
        )

    private inline fun <reified T> expectType(value: Any?): T {
        assertTrue("Expected ${T::class.java.simpleName}, got ${value?.javaClass?.simpleName}", value is T)
        return value as T
    }

    private data class Fixture(
        val document: NovelProjectDocumentV1,
        val projectId: NovelProjectId,
        val branchId: app.amber.feature.novel.model.NovelBranchId,
        val plan: NovelChapterPlanRecord,
    )

    private fun readyFixture(): Fixture {
        val projectId = NovelProjectId.generate()
        val branchId = app.amber.feature.novel.model.NovelBranchId.generate()
        val created = NovelReducer.createProject(
            NovelCreateProjectCommand(
                context = NovelMutationContext(NovelOperationId.generate()),
                projectID = projectId,
                branchID = branchId,
                sessionID = NovelSessionId.generate(),
                initialStateSnapshotID = NovelStateSnapshotId.generate(),
                initialCheckpointID = NovelCheckpointId.generate(),
                name = "测试小说",
                creationMode = NovelProjectCreationMode.Blank,
            ),
            NOW,
        ).document
        val materials = listOf(
            NovelMaterialKind.MasterOutline,
            NovelMaterialKind.Character,
            NovelMaterialKind.WritingRequirements,
        ).mapIndexed { index, kind ->
            val materialId = NovelMaterialId.generate()
            val revisionId = NovelMaterialRevisionId.generate()
            NovelMaterialRecord(
                id = materialId,
                kind = kind,
                currentRevisionID = revisionId,
                revisionIDs = listOf(revisionId),
            ) to NovelMaterialRevisionRecord(
                id = revisionId,
                materialID = materialId,
                revision = 1,
                title = "资料$index",
                content = "非空内容$index",
                tags = emptyList(),
                injectionMode = NovelInjectionMode.Always,
                createdAt = NOW,
                operationID = NovelOperationId.generate(),
            )
        }
        val draftPlan = NovelChapterPlanRecord(
            id = NovelChapterPlanId.generate(),
            branchID = branchId,
            status = NovelChapterPlanStatus.Confirmed,
            outlinePlacement = "第 1 章",
            goalAndConflict = "夺回信物",
            mustHappen = listOf("夺回信物"),
            mustNotHappen = listOf("主角死亡"),
            endingHook = "信物碎裂",
            visibleFacts = emptyList(),
            contentDigest = "",
            updatedAt = NOW,
            confirmedAt = NOW,
        )
        val plan = draftPlan.copy(
            contentDigest = NovelChapterPlanRecord.digest(draftPlan.canonicalDigestPayload()),
        )
        val document = created.copy(
            project = created.project.copy(collaborationMode = NovelCollaborationMode.Ghostwrite),
            materials = materials.map { it.first },
            materialRevisions = materials.map { it.second },
            branches = created.branches.map { it.copy(syncStatus = NovelBranchSyncStatus.Synchronized) },
            chapterPlans = listOf(plan),
        )
        return Fixture(document, projectId, branchId, plan)
    }

    private fun accepted(obviousRepetition: List<String> = emptyList()) = NovelChapterPlanAcceptanceV1(
        schemaVersion = NovelChapterPlanAcceptanceV1.CURRENT_SCHEMA_VERSION,
        accepted = true,
        missingMustHappen = emptyList(),
        forbiddenViolations = emptyList(),
        obviousRepetition = obviousRepetition,
        summary = "通过",
    )

    private class FakeNovelCreation(initialDocument: NovelProjectDocumentV1) : NovelCreation {
        var document = initialDocument
        var notificationPermissionGranted = true
        var foregroundStartAllowed = true
        var foregroundStartAction: ((NovelRunId) -> Boolean)? = null
        var startAction: ((NovelRunRequest) -> Unit)? = null
        val foregroundStartResults = ArrayDeque<Boolean>()
        var syncFailuresRemaining = 0
        val startedRequests = mutableListOf<NovelRunRequest>()
        val performedIntents = mutableListOf<NovelIntent>()
        val interruptRequests = mutableListOf<NovelInterruptRequest>()
        val foregroundStarts = mutableListOf<NovelRunId>()
        val foregroundStops = mutableListOf<NovelRunId>()
        var acceptance = NovelChapterPlanAcceptanceV1(
            schemaVersion = NovelChapterPlanAcceptanceV1.CURRENT_SCHEMA_VERSION,
            accepted = true,
            missingMustHappen = emptyList(),
            forbiddenViolations = emptyList(),
            summary = "通过",
        )
        var acceptanceBarrierCandidateId: NovelCandidateId? = null
        var acceptanceBarrier: CompletableDeferred<Unit>? = null
        var continuityReport = NovelContinuityAuditReport(issues = emptyList(), failedChunkCount = 0)
        private val queuedRunIds = ArrayDeque<NovelRunId>()
        private val runEvents = mutableMapOf<NovelRunId, MutableSharedFlow<NovelRunEvent>>()
        private var candidateSequence = 0L

        override val projectList: StateFlow<List<NovelProjectSummary>> = MutableStateFlow(
            listOf(
                NovelProjectSummary(
                    id = document.project.id,
                    name = document.project.name,
                    mainBranchID = document.project.mainBranchID,
                    updatedAt = document.project.updatedAt,
                    revision = document.project.revision,
                ),
            ),
        )

        fun enqueueRun(runId: NovelRunId) {
            queuedRunIds.addLast(runId)
        }

        fun nextRunId(): NovelRunId = queuedRunIds.removeFirst()

        fun events(runId: NovelRunId): MutableSharedFlow<NovelRunEvent> = runEvents.getValue(runId)

        fun addCandidate(
            fixture: Fixture,
            runId: NovelRunId,
            status: NovelCandidateStatus = NovelCandidateStatus.Available,
            content: String = "完整正文",
        ): NovelCandidateRecord {
            candidateSequence += 1
            val messageId = NovelMessageId.generate()
            val candidate = NovelCandidateRecord(
                id = NovelCandidateId.generate(),
                kind = NovelCandidateKind.Prose,
                branchID = fixture.branchId,
                sessionID = document.branches.first { it.id == fixture.branchId }.sessionID,
                sourceMessageID = messageId,
                baseCheckpointID = document.branches.first { it.id == fixture.branchId }.headCheckpointID,
                baseHeadRevision = document.branches.first { it.id == fixture.branchId }.headRevision,
                status = status,
                content = content,
                chapterPlanDigest = fixture.plan.contentDigest,
                ghostwritePlanID = fixture.plan.id,
                createdAt = NOW.plusSeconds(candidateSequence),
            )
            document = document.copy(
                candidates = document.candidates + candidate,
                sessions = document.sessions.map { session ->
                    if (session.id != candidate.sessionID) return@map session
                    session.copy(
                        messages = session.messages + NovelSessionMessageRecord(
                            id = messageId,
                            sequence = session.messages.size.toLong(),
                            role = NovelSessionRole.Assistant,
                            mode = NovelSessionMode.WriteProse,
                            kind = NovelSessionMessageKind.ProseCandidate,
                            content = content,
                            createdAt = candidate.createdAt,
                            runID = runId,
                            candidateID = candidate.id,
                        ),
                        revision = session.revision + 1,
                    )
                },
            )
            return candidate
        }

        fun updateCandidate(candidateId: NovelCandidateId, status: NovelCandidateStatus) {
            document = document.copy(
                candidates = document.candidates.map {
                    if (it.id == candidateId) it.copy(status = status) else it
                },
            )
        }

        override suspend fun refreshProjects() = Unit

        override suspend fun snapshot(query: NovelQuery): NovelSnapshot = NovelSnapshot.Project(document)

        override suspend fun perform(intent: NovelIntent): NovelOutcome {
            performedIntents += intent
            return when (intent) {
                is NovelIntent.CollectCandidate -> {
                    updateCandidate(intent.candidateId, NovelCandidateStatus.Collected)
                    document = document.copy(
                        branches = document.branches.map {
                            if (it.id == intent.branchId) it.copy(syncStatus = NovelBranchSyncStatus.NeedsSync) else it
                        },
                    )
                    NovelOutcome.CandidateCollected(
                        projectID = intent.projectId,
                        branchID = intent.branchId,
                        candidateID = intent.candidateId,
                        checkpointID = NovelCheckpointId.generate(),
                        chapterVersionID = NovelChapterVersionId.generate(),
                        revision = document.project.revision,
                    )
                }
                is NovelIntent.ClearChapterPlan -> {
                    document = document.copy(
                        chapterPlans = document.chapterPlans.filterNot { it.branchID == intent.branchId },
                    )
                    NovelOutcome.ChapterPlanCleared(
                        projectID = intent.projectId,
                        branchID = intent.branchId,
                        projectRevision = document.project.revision,
                        configRevision = document.project.configRevision,
                    )
                }
                is NovelIntent.SyncManualEdits -> {
                    if (syncFailuresRemaining > 0) {
                        syncFailuresRemaining -= 1
                        error("同步失败")
                    }
                    document = document.copy(
                        branches = document.branches.map {
                            if (it.id == intent.branchId) {
                                it.copy(syncStatus = NovelBranchSyncStatus.Synchronized)
                            } else {
                                it
                            }
                        },
                        pendingOperations = emptyList(),
                    )
                    NovelOutcome.ManualSyncCommitted(
                        projectID = intent.projectId,
                        branchID = intent.branchId,
                        checkpointID = NovelCheckpointId.generate(),
                        revision = document.project.revision,
                    )
                }
                else -> error("Unexpected intent: $intent")
            }
        }

        override fun start(request: NovelRunRequest): NovelRun {
            startedRequests += request
            val runId = requireNotNull(request.runId)
            val events = MutableSharedFlow<NovelRunEvent>(replay = 16, extraBufferCapacity = 32)
            runEvents[runId] = events
            startAction?.invoke(request)
            return NovelRun(runId, events)
        }

        override fun interrupt(request: NovelInterruptRequest) {
            interruptRequests += request
        }

        override suspend fun continuityAudit(
            projectId: NovelProjectId,
            branchId: app.amber.feature.novel.model.NovelBranchId,
        ) = NovelContinuityAuditV1(schemaVersion = 1, consistent = true)

        override suspend fun continuityAuditIncludingCandidate(
            projectId: NovelProjectId,
            branchId: app.amber.feature.novel.model.NovelBranchId,
            candidateId: NovelCandidateId,
            maxCanonicalChapters: Int?,
        ) = continuityReport

        override suspend fun acceptChapterPlan(
            projectId: NovelProjectId,
            branchId: app.amber.feature.novel.model.NovelBranchId,
            candidateId: NovelCandidateId,
        ): NovelChapterPlanAcceptanceV1 {
            if (candidateId == acceptanceBarrierCandidateId) {
                withContext(NonCancellable) { acceptanceBarrier?.await() }
            }
            return acceptance
        }

        override suspend fun distillDiscussionArchive(
            projectId: NovelProjectId,
            branchId: app.amber.feature.novel.model.NovelBranchId,
            chapterId: app.amber.feature.novel.model.NovelChapterId?,
        ): NovelDiscussionArchiveDraft = error("Not used")
    }

    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-09T00:00:00Z")
        val RUN_A = NovelRunId.parse("00000000-0000-0000-0000-00000000000A")
        val RUN_B = NovelRunId.parse("00000000-0000-0000-0000-00000000000B")
    }
}
