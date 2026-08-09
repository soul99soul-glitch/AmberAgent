package app.amber.feature.novel.background

import app.amber.feature.novel.NovelBackgroundRunRegistry
import app.amber.feature.novel.NovelContinuityAuditReport
import app.amber.feature.novel.NovelIntent
import app.amber.feature.novel.NovelSnapshot
import app.amber.feature.novel.domain.NovelChapterPlanAcceptanceV1
import app.amber.feature.novel.domain.NovelChapterPlanProposalV1
import app.amber.feature.novel.domain.NovelClearChapterPlanCommand
import app.amber.feature.novel.domain.NovelCollectCommand
import app.amber.feature.novel.domain.NovelCollectionReducer
import app.amber.feature.novel.domain.NovelCreateProjectCommand
import app.amber.feature.novel.domain.NovelGenerationReducer
import app.amber.feature.novel.domain.NovelGenerationStartArtifacts
import app.amber.feature.novel.domain.NovelGhostwriteJobReducer
import app.amber.feature.novel.domain.NovelInternalRunRequest
import app.amber.feature.novel.domain.NovelManualSyncReducer
import app.amber.feature.novel.domain.NovelMutationContext
import app.amber.feature.novel.domain.NovelReducer
import app.amber.feature.novel.domain.NovelUpsertChapterPlanCommand
import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelCandidateId
import app.amber.feature.novel.model.NovelChapterPlanId
import app.amber.feature.novel.model.NovelChapterPlanRecord
import app.amber.feature.novel.model.NovelChapterPlanStatus
import app.amber.feature.novel.model.NovelCheckpointId
import app.amber.feature.novel.model.NovelCollaborationMode
import app.amber.feature.novel.model.NovelCollectionSource
import app.amber.feature.novel.model.NovelGenerationGranularity
import app.amber.feature.novel.model.NovelGenerationReceiptRecord
import app.amber.feature.novel.model.NovelGhostwriteChapterCursorV1
import app.amber.feature.novel.model.NovelGhostwriteJobId
import app.amber.feature.novel.model.NovelGhostwriteJobStatus
import app.amber.feature.novel.model.NovelInjectionReceiptRecord
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
import app.amber.feature.novel.model.NovelReceiptId
import app.amber.feature.novel.model.NovelRunId
import app.amber.feature.novel.model.NovelRunInterruptionReason
import app.amber.feature.novel.model.NovelRunKind
import app.amber.feature.novel.model.NovelSessionId
import app.amber.feature.novel.model.NovelSessionMode
import app.amber.feature.novel.model.NovelStateSnapshotId
import app.amber.feature.novel.persistence.NovelGhostwriteJobStore
import app.amber.feature.novel.serialization.sha256HexOfUtf8
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelGhostwriteBatchExecutorTest {
    @Test
    fun happyPath_commitsExactReceiptBeforeReportingCompleted() = runTest {
        val fixture = fixture(target = 1)
        val result = fixture.executor.run(fixture.jobId, WORK_1)

        assertEquals(BatchRunResult.Completed, result)
        val completed = fixture.store.loadJob(fixture.jobId).job
        assertEquals(NovelGhostwriteJobStatus.Completed, completed.status)
        assertEquals(1, completed.completedChapterCount)
        assertEquals(1, completed.chapterReceipts.size)
        assertEquals(1, fixture.effects.generationRequests.size)
        assertEquals(1, fixture.effects.countIntents(NovelIntent.CollectCandidate::class.java))
        assertEquals(1, fixture.effects.countIntents(NovelIntent.SyncManualEdits::class.java))
        assertEquals(1, fixture.effects.countIntents(NovelIntent.ClearChapterPlan::class.java))
    }

    @Test
    fun qualityFailure_usesCleanSecondAttemptAndNeverInjectsRejectedDraft() = runTest {
        val fixture = fixture(target = 1)
        fixture.effects.acceptances += rejected("Recover the token")
        fixture.effects.acceptances += accepted()

        assertEquals(BatchRunResult.Completed, fixture.executor.run(fixture.jobId, WORK_1))

        assertEquals(2, fixture.effects.generationRequests.size)
        val second = fixture.effects.generationRequests[1]
        assertEquals(1, fixture.store.loadJob(fixture.jobId).job.chapterReceipts.single().chapterIndex)
        assertEquals(listOf("Recover the token"), second.correctionPacket?.missingMustHappen)
        assertFalse(novelGhostwriteUserText(second).contains(FIRST_REJECTED_MARKER))
        assertNotEquals(
            fixture.effects.generationRequests[0].runId,
            fixture.effects.generationRequests[1].runId,
        )
    }

    @Test
    fun reviewerFreeText_pausesWithoutCreatingCorrectionContext() = runTest {
        val fixture = fixture(target = 1)
        fixture.effects.acceptances += rejected("The pacing feels weak")

        assertEquals(BatchRunResult.Paused, fixture.executor.run(fixture.jobId, WORK_1))

        val paused = fixture.store.loadJob(fixture.jobId).job
        assertEquals("chapter_review_incomplete", paused.statusReasonCode)
        assertEquals(null, paused.currentCursor.correctionPacket)
        assertEquals(1, fixture.effects.generationRequests.size)
    }

    @Test
    fun legacyAcceptanceSchema_pausesBatchInsteadOfEnteringCorrectionLoop() = runTest {
        val fixture = fixture(target = 1)
        fixture.effects.acceptances += accepted().copy(schemaVersion = 1)

        assertEquals(BatchRunResult.Paused, fixture.executor.run(fixture.jobId, WORK_1))
        assertEquals("chapter_review_incomplete", fixture.store.loadJob(fixture.jobId).job.statusReasonCode)
        assertEquals(1, fixture.effects.generationRequests.size)
    }

    @Test
    fun collectSyncAndClearCrashWindows_reconcileWithoutRewriting() = runTest {
        for (crash in CrashPoint.entries) {
            val fixture = fixture(target = 1)
            fixture.effects.crashAfter = crash

            try {
                fixture.executor.run(fixture.jobId, WORK_1)
            } catch (_: CancellationException) {
                // Simulated process death after the exact domain side effect committed.
            }
            fixture.effects.crashAfter = null

            assertEquals(BatchRunResult.Completed, fixture.executor.run(fixture.jobId, WORK_1))
            assertEquals(1, fixture.effects.generationRequests.size)
            assertEquals(1, fixture.store.loadJob(fixture.jobId).job.chapterReceipts.size)
        }
    }

    @Test
    fun orphanGeneratingRun_pausesRecoveryAndNeverPostsAgain() = runTest {
        val fixture = fixture(target = 1)
        fixture.effects.orphanFirstGeneration = true

        try {
            fixture.executor.run(fixture.jobId, WORK_1)
        } catch (_: CancellationException) {
            // Simulated process death while the provider stream was in flight.
        }
        fixture.effects.orphanFirstGeneration = false

        assertEquals(BatchRunResult.Paused, fixture.executor.run(fixture.jobId, WORK_1))
        val paused = fixture.store.loadJob(fixture.jobId).job
        assertEquals(NovelGhostwriteJobStatus.Paused, paused.status)
        assertEquals("orphan_prose_requires_recovery", paused.statusReasonCode)
        assertEquals(1, fixture.effects.generationRequests.size)
        assertTrue(fixture.effects.interruptedRuns.isNotEmpty())
    }

    @Test
    fun cancellingWorkerAfterDurablePause_stillInterruptsItsExactAppScopeRun() = runTest {
        val fixture = fixture(target = 1)
        fixture.effects.generationGate = CompletableDeferred()
        val execution = async { fixture.executor.run(fixture.jobId, WORK_1) }
        runCurrent()
        val runId = fixture.effects.generationRequests.single().runId

        val running = fixture.store.loadJob(fixture.jobId).job
        val paused = NovelGhostwriteJobReducer.pause(
            running,
            running.ledgerRevision,
            running.executionEpoch,
            "user_paused",
            NOW.plusSeconds(20),
        )
        fixture.store.commitJob(paused, running.ledgerRevision, running.executionEpoch)
        execution.cancelAndJoin()

        assertEquals(listOf(runId), fixture.effects.interruptedRuns)
    }

    @Test
    fun userPausedGeneration_resumeUsesFreshRunAndCompletes() = runTest {
        val fixture = fixture(target = 1)
        fixture.effects.generationGate = CompletableDeferred()
        val execution = async { fixture.executor.run(fixture.jobId, WORK_1) }
        runCurrent()
        val firstRunId = fixture.effects.generationRequests.single().runId

        val running = fixture.store.loadJob(fixture.jobId).job
        val paused = NovelGhostwriteJobReducer.pause(
            running,
            running.ledgerRevision,
            running.executionEpoch,
            "user_paused",
            NOW.plusSeconds(20),
        )
        fixture.store.commitJob(paused, running.ledgerRevision, running.executionEpoch)
        execution.cancelAndJoin()
        fixture.effects.generationGate = null

        val resumedResult = fixture.executor.run(fixture.jobId, WORK_2)
        val resumedJob = fixture.store.loadJob(fixture.jobId).job
        assertEquals(
            "${resumedJob.statusReasonCode} at ${resumedJob.phase}",
            BatchRunResult.Completed,
            resumedResult,
        )
        assertEquals(2, fixture.effects.generationRequests.size)
        assertNotEquals(firstRunId, fixture.effects.generationRequests.last().runId)
        assertEquals(NovelGhostwriteJobStatus.Completed, fixture.store.loadJob(fixture.jobId).job.status)
    }

    @Test
    fun transientStorageFailure_pausesAndCanResume() = runTest {
        val fixture = fixture(target = 1)
        fixture.effects.loadFailures += IOException("temporary read failure")

        assertEquals(BatchRunResult.Paused, fixture.executor.run(fixture.jobId, WORK_1))
        assertEquals("batch_executor_failed", fixture.store.loadJob(fixture.jobId).job.statusReasonCode)

        assertEquals(BatchRunResult.Completed, fixture.executor.run(fixture.jobId, WORK_2))
        assertEquals(NovelGhostwriteJobStatus.Completed, fixture.store.loadJob(fixture.jobId).job.status)
    }

    @Test
    fun staleEpochValidationCallback_cannotCollectOrAdvanceNewOwner() = runTest {
        val fixture = fixture(target = 1)
        fixture.effects.acceptanceEntered = CompletableDeferred()
        fixture.effects.releaseAcceptance = CompletableDeferred()
        val old = async { fixture.executor.run(fixture.jobId, WORK_1) }
        fixture.effects.acceptanceEntered!!.await()

        var stored = fixture.store.loadJob(fixture.jobId).job
        val paused = NovelGhostwriteJobReducer.pause(
            stored,
            stored.ledgerRevision,
            stored.executionEpoch,
            "user_paused",
            NOW.plusSeconds(20),
        )
        fixture.store.commitJob(paused, stored.ledgerRevision, stored.executionEpoch)
        stored = fixture.store.loadJob(fixture.jobId).job
        val claimed = NovelGhostwriteJobReducer.claimLease(
            stored,
            stored.ledgerRevision,
            stored.executionEpoch,
            WORK_2,
            NOW.plusSeconds(4_000),
            NOW.plusSeconds(21),
        )
        fixture.store.commitJob(claimed, stored.ledgerRevision, stored.executionEpoch)
        fixture.effects.releaseAcceptance!!.complete(Unit)

        assertEquals(BatchRunResult.Paused, old.await())
        val newOwner = fixture.store.loadJob(fixture.jobId).job
        assertEquals(WORK_2, newOwner.leaseOwnerWorkID)
        assertEquals(claimed.executionEpoch, newOwner.executionEpoch)
        assertEquals(0, fixture.effects.countIntents(NovelIntent.CollectCandidate::class.java))
    }

    @Test
    fun singletonExecutor_keepsTwoConcurrentRunClaimsExact() = runTest {
        val first = readyProject()
        val second = readyProject()
        val store = NovelGhostwriteJobStore(Files.createTempDirectory("batch-two-jobs").toFile())
        val effects = FakeEffects(mapOf(first.projectId to first.document, second.projectId to second.document))
        effects.generationGate = CompletableDeferred()
        effects.twoGenerationsEntered = CompletableDeferred()
        val registry = NovelBackgroundRunRegistry()
        val executor = NovelGhostwriteBatchExecutor(store, effects, registry, now = { NOW })
        val job1 = createJob(store, first, 1)
        val job2 = createJob(store, second, 1)

        val run1 = async { executor.run(job1, "work-a") }
        val run2 = async { executor.run(job2, "work-b") }
        effects.twoGenerationsEntered!!.await()

        assertEquals(1, registry.ownedRunIds(first.projectId).size)
        assertEquals(1, registry.ownedRunIds(second.projectId).size)
        effects.generationGate!!.complete(Unit)
        assertEquals(BatchRunResult.Completed, run1.await())
        assertEquals(BatchRunResult.Completed, run2.await())
        assertTrue(registry.ownedRunIds(first.projectId).isEmpty())
        assertTrue(registry.ownedRunIds(second.projectId).isEmpty())
    }

    private suspend fun fixture(target: Int): Fixture {
        val project = readyProject()
        val store = NovelGhostwriteJobStore(Files.createTempDirectory("batch-executor").toFile())
        val effects = FakeEffects(mapOf(project.projectId to project.document))
        val executor = NovelGhostwriteBatchExecutor(
            store = store,
            effects = effects,
            runRegistry = NovelBackgroundRunRegistry(),
            now = { NOW },
        )
        val jobId = createJob(store, project, target)
        return Fixture(store, effects, executor, jobId)
    }

    private suspend fun createJob(
        store: NovelGhostwriteJobStore,
        project: ReadyProject,
        target: Int,
    ): NovelGhostwriteJobId {
        val branch = project.document.branches.single { it.id == project.branchId }
        val job = NovelGhostwriteJobReducer.create(
            id = NovelGhostwriteJobId.generate(),
            projectID = project.projectId,
            branchID = project.branchId,
            targetChapterCount = target,
            initialCursor = NovelGhostwriteChapterCursorV1(
                chapterIndex = 1,
                baseProjectRevision = project.document.project.revision,
                baseCheckpointID = branch.headCheckpointID,
                baseHeadRevision = branch.headRevision,
                baseStateSnapshotID = branch.currentStateSnapshotID,
                baseConfigRevision = project.document.project.configRevision,
            ),
            now = NOW,
        )
        store.createJob(job)
        return job.id
    }

    private fun readyProject(): ReadyProject {
        val projectId = NovelProjectId.generate()
        val branchId = NovelBranchId.generate()
        val created = NovelReducer.createProject(
            NovelCreateProjectCommand(
                context = NovelMutationContext(NovelOperationId.generate()),
                projectID = projectId,
                branchID = branchId,
                sessionID = NovelSessionId.generate(),
                initialStateSnapshotID = NovelStateSnapshotId.generate(),
                initialCheckpointID = NovelCheckpointId.generate(),
                name = "Batch fixture",
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
                title = "Material $index",
                content = "Canonical material $index",
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
            outlinePlacement = "Chapter 1",
            goalAndConflict = "Recover the token",
            mustHappen = listOf("Recover the token"),
            mustNotHappen = listOf("Kill the protagonist"),
            endingHook = "The token fractures",
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
            chapterPlans = listOf(plan),
        )
        return ReadyProject(document, projectId, branchId)
    }

    private fun accepted(): NovelChapterPlanAcceptanceV1 = NovelChapterPlanAcceptanceV1(
        schemaVersion = NovelChapterPlanAcceptanceV1.CURRENT_SCHEMA_VERSION,
        accepted = true,
        missingMustHappen = emptyList(),
        forbiddenViolations = emptyList(),
        obviousRepetition = emptyList(),
        summary = "accepted",
    )

    private fun rejected(reason: String): NovelChapterPlanAcceptanceV1 = NovelChapterPlanAcceptanceV1(
        schemaVersion = NovelChapterPlanAcceptanceV1.CURRENT_SCHEMA_VERSION,
        accepted = false,
        missingMustHappen = listOf(reason),
        forbiddenViolations = emptyList(),
        obviousRepetition = emptyList(),
        summary = "rejected",
    )

    private data class Fixture(
        val store: NovelGhostwriteJobStore,
        val effects: FakeEffects,
        val executor: NovelGhostwriteBatchExecutor,
        val jobId: NovelGhostwriteJobId,
    )

    private data class ReadyProject(
        val document: NovelProjectDocumentV1,
        val projectId: NovelProjectId,
        val branchId: NovelBranchId,
    )

    private enum class CrashPoint {
        Collect,
        Sync,
        Clear,
    }

    private class FakeEffects(
        initialDocuments: Map<NovelProjectId, NovelProjectDocumentV1>,
    ) : NovelGhostwriteBatchEffects {
        private val documents = initialDocuments.toMutableMap()
        val generationRequests = mutableListOf<NovelGhostwriteProseRequest>()
        val performedIntents = mutableListOf<NovelIntent>()
        val interruptedRuns = mutableListOf<NovelRunId>()
        val acceptances = ArrayDeque<NovelChapterPlanAcceptanceV1>()
        val loadFailures = ArrayDeque<Exception>()
        var crashAfter: CrashPoint? = null
        var orphanFirstGeneration = false
        var acceptanceEntered: CompletableDeferred<Unit>? = null
        var releaseAcceptance: CompletableDeferred<Unit>? = null
        var generationGate: CompletableDeferred<Unit>? = null
        var twoGenerationsEntered: CompletableDeferred<Unit>? = null
        private var generationEnterCount = 0

        override suspend fun loadProject(projectId: NovelProjectId): NovelSnapshot.Project {
            if (loadFailures.isNotEmpty()) throw loadFailures.removeFirst()
            return NovelSnapshot.Project(documents.getValue(projectId))
        }

        override suspend fun proposePlan(
            projectId: NovelProjectId,
            branchId: NovelBranchId,
            previousPlanSummary: String?,
        ): NovelChapterPlanProposalV1 {
            val chapter = documents.getValue(projectId).chapters.size + 1
            return NovelChapterPlanProposalV1(
                outlinePlacement = "Chapter $chapter",
                goalAndConflict = "Advance the canonical conflict",
                mustHappen = listOf("Advance chapter $chapter"),
                mustNotHappen = emptyList(),
                endingHook = "Hook $chapter",
                visibleFacts = emptyList(),
            )
        }

        override suspend fun perform(intent: NovelIntent): NovelOutcome {
            performedIntents += intent
            val (projectId, reduced) = when (intent) {
                is NovelIntent.CollectCandidate -> intent.projectId to NovelCollectionReducer.collect(
                    NovelCollectCommand(
                        projectId = intent.projectId,
                        branchId = intent.branchId,
                        candidateId = intent.candidateId,
                        selectedText = intent.selectedText,
                        target = intent.target,
                        operationId = required(intent.operationId, "collect operation"),
                        expectedProjectRevision = required(
                            intent.expectedProjectRevision,
                            "collect project revision",
                        ),
                        expectedConfigRevision = required(intent.expectedConfigRevision, "collect config revision"),
                        expectedBranchHeadRevision = required(
                            intent.expectedBranchHeadRevision,
                            "collect head revision",
                        ),
                        newChapterVersionId = required(intent.newChapterVersionId, "chapter version"),
                        newCheckpointId = required(intent.newCheckpointId, "collect checkpoint"),
                        newStateSnapshotId = required(intent.newStateSnapshotId, "collect state"),
                        source = intent.source,
                        markNeedsSync = intent.source == NovelCollectionSource.SystemAutoCollect,
                    ),
                    documents.getValue(intent.projectId),
                    effectNow(intent.projectId),
                )

                is NovelIntent.SyncManualEdits -> intent.projectId to NovelManualSyncReducer.sync(
                    projectId = intent.projectId,
                    branchId = intent.branchId,
                    operationId = required(intent.operationId, "sync operation"),
                    newCheckpointId = required(intent.newCheckpointId, "sync checkpoint"),
                    newStateSnapshotId = required(intent.newStateSnapshotId, "sync state"),
                    expectedProjectRevision = required(intent.expectedProjectRevision, "sync project revision"),
                    expectedConfigRevision = required(intent.expectedConfigRevision, "sync config revision"),
                    expectedBranchHeadRevision = required(
                        intent.expectedBranchHeadRevision,
                        "sync head revision",
                    ),
                    stateDelta = null,
                    document = documents.getValue(intent.projectId),
                    now = effectNow(intent.projectId),
                )

                is NovelIntent.UpsertChapterPlan -> intent.projectId to NovelReducer.upsertChapterPlan(
                    NovelUpsertChapterPlanCommand(
                        context = NovelMutationContext(
                            operationID = required(intent.operationId, "plan upsert operation"),
                            expectedProjectRevision = required(
                                intent.expectedProjectRevision,
                                "upsert project revision",
                            ),
                            expectedConfigRevision = required(
                                intent.expectedConfigRevision,
                                "upsert config revision",
                            ),
                            expectedBranchHeadRevision = required(
                                intent.expectedBranchHeadRevision,
                                "upsert head revision",
                            ),
                        ),
                        projectID = intent.projectId,
                        branchID = intent.branchId,
                        planID = intent.planId,
                        status = intent.status,
                        outlinePlacement = intent.outlinePlacement,
                        goalAndConflict = intent.goalAndConflict,
                        mustHappen = intent.mustHappen,
                        mustNotHappen = intent.mustNotHappen,
                        endingHook = intent.endingHook,
                        visibleFacts = intent.visibleFacts,
                    ),
                    documents.getValue(intent.projectId),
                    effectNow(intent.projectId),
                )

                is NovelIntent.ClearChapterPlan -> intent.projectId to NovelReducer.clearChapterPlan(
                    NovelClearChapterPlanCommand(
                        context = NovelMutationContext(
                            operationID = required(intent.operationId, "plan clear operation"),
                            expectedProjectRevision = required(
                                intent.expectedProjectRevision,
                                "clear project revision",
                            ),
                            expectedConfigRevision = required(
                                intent.expectedConfigRevision,
                                "clear config revision",
                            ),
                            expectedBranchHeadRevision = required(
                                intent.expectedBranchHeadRevision,
                                "clear head revision",
                            ),
                        ),
                        projectID = intent.projectId,
                        branchID = intent.branchId,
                        expectedPlanID = required(intent.expectedPlanId, "clear plan ID"),
                        expectedPlanDigest = required(intent.expectedPlanDigest, "clear plan digest"),
                    ),
                    documents.getValue(intent.projectId),
                    effectNow(intent.projectId),
                )

                else -> error("Unexpected batch intent: $intent")
            }
            documents[projectId] = reduced.document
            crashAfter(intent)?.let { throw CancellationException("crash after $it") }
            return reduced.outcome
        }

        override suspend fun generateWholeChapter(
            request: NovelGhostwriteProseRequest,
        ): NovelGhostwriteGenerationTerminal {
            generationRequests += request
            val document = documents.getValue(request.projectId)
            val internal = NovelInternalRunRequest(
                id = request.runId,
                operationID = NovelOperationId.generate(),
                projectID = request.projectId,
                branchID = request.branchId,
                kind = NovelRunKind.Prose,
                mode = NovelSessionMode.WriteProse,
                granularity = NovelGenerationGranularity.WholeChapter,
                userText = novelGhostwriteUserText(request),
                userMessageID = NovelMessageId.generate(),
                assistantMessageID = NovelMessageId.generate(),
                candidateID = NovelCandidateId.generate(),
                generationReceiptID = NovelReceiptId.generate(),
                injectionReceiptID = NovelReceiptId.generate(),
                sourceChapterVersionID = null,
                ghostwritePlanID = request.planId,
                expectedProjectRevision = request.expectedProjectRevision,
                expectedConfigRevision = request.expectedConfigRevision,
                expectedBranchHeadRevision = request.expectedBranchHeadRevision,
                requestPayloadSHA256 = sha256HexOfUtf8("batch-run:${request.runId.rawValue}"),
            )
            val started = NovelGenerationReducer.begin(
                internal,
                generationArtifacts(internal),
                document,
                effectNow(request.projectId),
            ).document
            documents[request.projectId] = started
            generationEnterCount += 1
            if (generationEnterCount == 2) twoGenerationsEntered?.complete(Unit)
            generationGate?.await()
            if (orphanFirstGeneration && generationRequests.size == 1) {
                throw CancellationException("process died with prose in flight")
            }
            val content = if (generationRequests.size == 1) {
                FIRST_REJECTED_MARKER
            } else {
                "Accepted chapter ${generationRequests.size}"
            }
            documents[request.projectId] = NovelGenerationReducer.complete(
                request.runId,
                content,
                documents.getValue(request.projectId),
                effectNow(request.projectId),
            ).first
            return NovelGhostwriteGenerationTerminal.Completed
        }

        override suspend fun acceptPlan(
            projectId: NovelProjectId,
            branchId: NovelBranchId,
            candidateId: NovelCandidateId,
        ): NovelChapterPlanAcceptanceV1 {
            acceptanceEntered?.complete(Unit)
            releaseAcceptance?.await()
            return if (acceptances.isEmpty()) defaultAccepted() else acceptances.removeFirst()
        }

        override suspend fun auditContinuity(
            projectId: NovelProjectId,
            branchId: NovelBranchId,
            candidateId: NovelCandidateId,
            maxCanonicalChapters: Int?,
        ): NovelContinuityAuditReport = NovelContinuityAuditReport(emptyList(), 0)

        override fun interruptExact(projectId: NovelProjectId, runId: NovelRunId) {
            interruptedRuns += runId
            val document = documents.getValue(projectId)
            documents[projectId] = NovelGenerationReducer.interrupt(
                runId,
                NovelRunInterruptionReason.Recovery,
                "",
                document,
                effectNow(projectId),
            ).first
        }

        fun countIntents(type: Class<out NovelIntent>): Int = performedIntents.count(type::isInstance)

        private fun crashAfter(intent: NovelIntent): CrashPoint? {
            val point = when (intent) {
                is NovelIntent.CollectCandidate -> CrashPoint.Collect
                is NovelIntent.SyncManualEdits -> CrashPoint.Sync
                is NovelIntent.ClearChapterPlan -> CrashPoint.Clear
                else -> null
            }
            return point.takeIf { it == crashAfter }
        }

        private fun effectNow(projectId: NovelProjectId): Instant =
            NOW.plusSeconds(documents.getValue(projectId).project.revision + performedIntents.size)

        private fun generationArtifacts(request: NovelInternalRunRequest): NovelGenerationStartArtifacts {
            val injection = NovelInjectionReceiptRecord(
                id = request.injectionReceiptID,
                runID = request.id,
                projectID = request.projectID,
                branchID = request.branchID,
                promptVersion = "test",
                providerID = "provider",
                ownerProviderID = "provider",
                modelID = "model",
                wireModelID = "model",
                requestedInputBudgetTokens = 1,
                maxEstimatedInputTokens = 1,
                estimatedInputTokens = 1,
                canonicalInputSHA256 = "d".repeat(64),
                createdAt = effectNow(request.projectID),
            )
            val generation = NovelGenerationReceiptRecord(
                id = request.generationReceiptID,
                runID = request.id,
                providerID = "provider",
                ownerProviderID = "provider",
                modelID = "model",
                wireModelID = "model",
                promptVersion = "test",
                injectionReceiptID = injection.id,
                requestSHA256 = "e".repeat(64),
                createdAt = effectNow(request.projectID),
            )
            return NovelGenerationStartArtifacts(injection, generation)
        }

        private fun defaultAccepted(): NovelChapterPlanAcceptanceV1 = NovelChapterPlanAcceptanceV1(
            schemaVersion = NovelChapterPlanAcceptanceV1.CURRENT_SCHEMA_VERSION,
            accepted = true,
            missingMustHappen = emptyList(),
            forbiddenViolations = emptyList(),
            obviousRepetition = emptyList(),
            summary = "accepted",
        )

        private fun <T : Any> required(value: T?, label: String): T = requireNotNull(value) { label }
    }

    companion object {
        private val NOW: Instant = Instant.parse("2026-08-09T00:00:00Z")
        private const val WORK_1 = "work-1"
        private const val WORK_2 = "work-2"
        private const val FIRST_REJECTED_MARKER = "CURRENT_REJECTED_DRAFT_MARKER"
    }
}
