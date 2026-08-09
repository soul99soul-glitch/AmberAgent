package app.amber.feature.novel.background

import app.amber.feature.novel.NovelBackgroundRunRegistry
import app.amber.feature.novel.NovelCreation
import app.amber.feature.novel.NovelIntent
import app.amber.feature.novel.domain.NovelChapterPlanAcceptanceV1
import app.amber.feature.novel.domain.NovelContinuityIssueSeverityV1
import app.amber.feature.novel.domain.NovelError
import app.amber.feature.novel.domain.NovelGhostwriteJobError
import app.amber.feature.novel.domain.NovelGhostwriteJobReducer
import app.amber.feature.novel.domain.NovelGhostwriteReadiness
import app.amber.feature.novel.model.NovelAppliedOperationRecord
import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelBranchLifecycle
import app.amber.feature.novel.model.NovelBranchSyncStatus
import app.amber.feature.novel.model.NovelCandidateId
import app.amber.feature.novel.model.NovelCandidateKind
import app.amber.feature.novel.model.NovelCandidateRecord
import app.amber.feature.novel.model.NovelCandidateStatus
import app.amber.feature.novel.model.NovelChapterId
import app.amber.feature.novel.model.NovelChapterPlanId
import app.amber.feature.novel.model.NovelChapterPlanRecord
import app.amber.feature.novel.model.NovelChapterPlanStatus
import app.amber.feature.novel.model.NovelChapterSelection
import app.amber.feature.novel.model.NovelChapterVersionId
import app.amber.feature.novel.model.NovelChapterVersionKind
import app.amber.feature.novel.model.NovelCheckpointId
import app.amber.feature.novel.model.NovelCheckpointKind
import app.amber.feature.novel.model.NovelCollectionSource
import app.amber.feature.novel.model.NovelCollectionTarget
import app.amber.feature.novel.model.NovelCollaborationMode
import app.amber.feature.novel.model.NovelGhostwriteChapterCursorV1
import app.amber.feature.novel.model.NovelGhostwriteChapterReceiptV1
import app.amber.feature.novel.model.NovelGhostwriteCorrectionPacketV1
import app.amber.feature.novel.model.NovelGhostwriteJobId
import app.amber.feature.novel.model.NovelGhostwriteJobPhase
import app.amber.feature.novel.model.NovelGhostwriteJobStatus
import app.amber.feature.novel.model.NovelGhostwriteJobV1
import app.amber.feature.novel.model.NovelGhostwritePendingCollectIdentityV1
import app.amber.feature.novel.model.NovelGhostwritePendingPlanClearV1
import app.amber.feature.novel.model.NovelGhostwritePendingPlanUpsertV1
import app.amber.feature.novel.model.NovelGhostwritePendingSyncIdentityV1
import app.amber.feature.novel.model.NovelOperationId
import app.amber.feature.novel.model.NovelOperationKind
import app.amber.feature.novel.model.NovelOutcome
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelProjectLoadAccess
import app.amber.feature.novel.model.NovelRunId
import app.amber.feature.novel.model.NovelRunKind
import app.amber.feature.novel.model.NovelRunStatus
import app.amber.feature.novel.model.NovelSessionMessageKind
import app.amber.feature.novel.model.NovelSessionMode
import app.amber.feature.novel.model.NovelSessionRole
import app.amber.feature.novel.model.NovelStateSnapshotId
import app.amber.feature.novel.persistence.NovelGhostwriteJobLoadAccess
import app.amber.feature.novel.persistence.NovelGhostwriteJobStore
import app.amber.feature.novel.serialization.sha256HexOfUtf8
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

/** Durable, fenced phase interpreter for a 1..50 chapter ghostwrite batch. */
class NovelGhostwriteBatchExecutor internal constructor(
    private val store: NovelGhostwriteJobStore,
    private val effects: NovelGhostwriteBatchEffects,
    private val runRegistry: NovelBackgroundRunRegistry,
    private val ids: NovelGhostwriteBatchIds = RandomNovelGhostwriteBatchIds,
    private val now: () -> Instant = Instant::now,
) : NovelGhostwriteBatchRunning {
    constructor(
        store: NovelGhostwriteJobStore,
        novelCreation: NovelCreation,
        runRegistry: NovelBackgroundRunRegistry,
    ) : this(store, NovelCreationGhostwriteBatchEffects(novelCreation), runRegistry)

    private data class Fence(
        val jobId: NovelGhostwriteJobId,
        val workId: String,
        val executionEpoch: Long,
    )

    private data class ActiveRunClaim(
        val ownerToken: String,
        val projectId: app.amber.feature.novel.model.NovelProjectId,
        val runId: NovelRunId,
    )

    private data class LeaseAcquisition(
        val job: NovelGhostwriteJobV1,
        val resumedUserPausedGeneration: Boolean,
    )

    private val activeRunClaims = ConcurrentHashMap<String, ActiveRunClaim>()

    override suspend fun run(jobId: NovelGhostwriteJobId, workId: String): BatchRunResult {
        require(workId.isNotBlank()) { "Work ID is required." }
        val acquisition = acquireLease(jobId, workId) ?: return BatchRunResult.Paused
        val leased = acquisition.job
        when (leased.status) {
            NovelGhostwriteJobStatus.Completed,
            NovelGhostwriteJobStatus.Failed,
            NovelGhostwriteJobStatus.Cancelled,
            -> return BatchRunResult.Completed

            else -> Unit
        }
        val fence = Fence(jobId, workId, leased.executionEpoch)
        var resumeInterruptedGeneration = acquisition.resumedUserPausedGeneration
        try {
            while (true) {
                var job = loadOwned(fence) ?: return BatchRunResult.Paused
                when (job.status) {
                    NovelGhostwriteJobStatus.Paused -> return BatchRunResult.Paused
                    NovelGhostwriteJobStatus.Completed -> return BatchRunResult.Completed
                    NovelGhostwriteJobStatus.Failed,
                    NovelGhostwriteJobStatus.Cancelled,
                    -> return BatchRunResult.Completed

                    NovelGhostwriteJobStatus.Pending -> return failOwned(job, fence, "invalid_pending_owner")
                    NovelGhostwriteJobStatus.Running -> Unit
                }
                job = renewIfNeeded(job, fence) ?: return BatchRunResult.Paused
                val terminal = when (job.phase) {
                    NovelGhostwriteJobPhase.AwaitingPlan -> handleAwaitingPlan(job, fence)
                    NovelGhostwriteJobPhase.Planning -> handlePlanning(job, fence)
                    NovelGhostwriteJobPhase.PlanPrepared -> handlePlanPrepared(job, fence)
                    NovelGhostwriteJobPhase.GenerationPrepared -> handleGenerationPrepared(job, fence)
                    NovelGhostwriteJobPhase.Generating -> {
                        val shouldRestart = resumeInterruptedGeneration
                        resumeInterruptedGeneration = false
                        handleGeneratingRecovery(job, fence, shouldRestart)
                    }
                    NovelGhostwriteJobPhase.CandidateReady -> advanceCandidateReady(job, fence)
                    NovelGhostwriteJobPhase.Validating -> handleValidation(job, fence)
                    NovelGhostwriteJobPhase.CorrectionReady -> handleCorrection(job, fence)
                    NovelGhostwriteJobPhase.CollectPrepared -> handleCollect(job, fence)
                    NovelGhostwriteJobPhase.CollectedNeedsSync -> beginSync(job, fence)
                    NovelGhostwriteJobPhase.Syncing -> handleSync(job, fence)
                    NovelGhostwriteJobPhase.ClearPlanPrepared -> handleClearPlan(job, fence)
                    NovelGhostwriteJobPhase.ChapterCommitPrepared -> handleChapterCommit(job, fence)
                    NovelGhostwriteJobPhase.ChapterCommitted -> handleChapterCommitted(job, fence)
                }
                if (terminal != null) return terminal
            }
        } catch (error: CancellationException) {
            activeRunClaims[ownerToken(fence)]?.let { claim ->
                // pause/cancel persists the terminal ledger fence before WorkManager
                // cancels this worker, so loadOwned() is expected to fail here. The
                // fence-keyed claim itself is the exact proof that this execution
                // started the run; always stop that run and never a newer epoch's.
                effects.interruptExact(claim.projectId, claim.runId)
            }
            throw error
        } catch (error: BatchFactMismatch) {
            val current = runCatching { loadOwned(fence) }.getOrNull()
            return if (current == null) {
                BatchRunResult.Paused
            } else {
                pauseOwned(current, fence, error.reasonCode)
            }
        } catch (error: Exception) {
            val current = runCatching { loadOwned(fence) }.getOrNull()
            return if (current == null) {
                BatchRunResult.Paused
            } else if (error.isRecoverableInfrastructureFailure()) {
                pauseOwned(current, fence, "batch_executor_failed")
            } else {
                failOwned(current, fence, "batch_executor_invariant_failed")
            }
        } finally {
            releaseActiveRun(fence)
        }
    }

    private suspend fun handleAwaitingPlan(job: NovelGhostwriteJobV1, fence: Fence): BatchRunResult? {
        val document = loadDocument(job)
        requireCanonicalBase(document, job)
        return if (job.completedChapterCount == 0) {
            val plan = document.confirmedChapterPlan(job.branchID)
                ?: return pauseOwned(job, fence, "first_chapter_plan_missing")
            if (NovelGhostwriteReadiness.issues(document, job.branchID, true).isNotEmpty()) {
                return pauseOwned(job, fence, "ghostwrite_readiness_blocked")
            }
            val cursor = job.currentCursor.copy(
                planID = plan.id,
                planDigest = plan.contentDigest,
                runID = ids.runId(),
                attemptNumber = 1,
            )
            advance(job, fence, NovelGhostwriteJobPhase.GenerationPrepared, cursor)
        } else {
            if (document.chapterPlan(job.branchID) != null) {
                return pauseOwned(job, fence, "unexpected_next_chapter_plan")
            }
            advance(job, fence, NovelGhostwriteJobPhase.Planning, job.currentCursor)
        }
    }

    private suspend fun handlePlanning(job: NovelGhostwriteJobV1, fence: Fence): BatchRunResult? {
        val document = loadDocument(job)
        requireCanonicalBase(document, job)
        if (document.chapterPlan(job.branchID) != null) {
            return pauseOwned(job, fence, "automatic_plan_base_drift")
        }
        val proposal = try {
            effects.proposePlan(job.projectID, job.branchID, previousPlanSummary = null)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return retryOwned(job, fence)
        }
        if (loadOwned(fence)?.phase != NovelGhostwriteJobPhase.Planning) return BatchRunResult.Paused
        val planId = ids.planId()
        val normalized = normalizePlanProposal(proposal)
        val digest = planDigest(normalized)
        val pending = NovelGhostwritePendingPlanUpsertV1(
            upsertOperationID = ids.operationId(),
            expectedProjectRevision = job.currentCursor.baseProjectRevision,
            expectedConfigRevision = job.currentCursor.baseConfigRevision,
            outlinePlacement = normalized.outlinePlacement,
            goalAndConflict = normalized.goalAndConflict,
            mustHappen = normalized.mustHappen,
            mustNotHappen = normalized.mustNotHappen,
            endingHook = normalized.endingHook,
            visibleFacts = normalized.visibleFacts,
        )
        val next = NovelGhostwriteJobReducer.preparePlan(
            job,
            job.ledgerRevision,
            fence.executionEpoch,
            planId,
            digest,
            pending,
            transitionNow(job),
        )
        return commit(job, next, fence)
    }

    private suspend fun handlePlanPrepared(job: NovelGhostwriteJobV1, fence: Fence): BatchRunResult? {
        var document = loadDocument(job)
        var fact = reconcilePlanUpsert(document, job)
        if (fact == null) {
            val cursor = job.currentCursor
            val pending = checkNotNull(cursor.pendingPlanUpsert)
            requireCanonicalBase(document, job)
            if (document.chapterPlan(job.branchID) != null) {
                return pauseOwned(job, fence, "automatic_plan_conflict")
            }
            try {
                effects.perform(
                    NovelIntent.UpsertChapterPlan(
                        projectId = job.projectID,
                        branchId = job.branchID,
                        planId = checkNotNull(cursor.planID),
                        status = NovelChapterPlanStatus.Confirmed,
                        outlinePlacement = pending.outlinePlacement,
                        goalAndConflict = pending.goalAndConflict,
                        mustHappen = pending.mustHappen,
                        mustNotHappen = pending.mustNotHappen,
                        endingHook = pending.endingHook,
                        visibleFacts = pending.visibleFacts,
                        operationId = pending.upsertOperationID,
                        expectedProjectRevision = pending.expectedProjectRevision,
                        expectedConfigRevision = pending.expectedConfigRevision,
                        expectedBranchHeadRevision = cursor.baseHeadRevision,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                document = runCatching { loadDocument(job) }.getOrElse { return retryOwned(job, fence) }
                fact = reconcilePlanUpsert(document, job)
                if (fact == null) return retryOwned(job, fence)
            }
            if (fact == null) {
                document = loadDocument(job)
                fact = reconcilePlanUpsert(document, job)
            }
        }
        val applied = fact ?: return pauseOwned(job, fence, "plan_upsert_reconcile_failed")
        val pending = checkNotNull(job.currentCursor.pendingPlanUpsert).copy(
            upsertedProjectRevision = applied.projectRevision,
            upsertedConfigRevision = applied.configRevision,
        )
        val cursor = job.currentCursor.copy(
            pendingPlanUpsert = pending,
            runID = ids.runId(),
            attemptNumber = 1,
        )
        return advance(job, fence, NovelGhostwriteJobPhase.GenerationPrepared, cursor)
    }

    private suspend fun handleGenerationPrepared(job: NovelGhostwriteJobV1, fence: Fence): BatchRunResult? {
        val document = loadDocument(job)
        val cursor = job.currentCursor
        requireGenerationBase(document, job)
        cursor.correctionPacket?.sourceCandidateID?.let { rejectedId ->
            requireExactRejectedCandidate(document, job, rejectedId)
        }
        val runId = checkNotNull(cursor.runID)
        val claim = ActiveRunClaim(ownerToken(fence), job.projectID, runId)
        if (!runRegistry.claim(claim.ownerToken, claim.projectId, claim.runId)) {
            return pauseOwned(job, fence, "background_run_ownership_conflict")
        }
        activeRunClaims[claim.ownerToken] = claim
        val generating = NovelGhostwriteJobReducer.advancePhase(
            job,
            job.ledgerRevision,
            fence.executionEpoch,
            NovelGhostwriteJobPhase.Generating,
            cursor,
            transitionNow(job),
        )
        if (commit(job, generating, fence) != null) {
            releaseActiveRun(fence)
            return BatchRunResult.Paused
        }
        val terminal = try {
            effects.generateWholeChapter(
                NovelGhostwriteProseRequest(
                    projectId = job.projectID,
                    branchId = job.branchID,
                    runId = runId,
                    planId = checkNotNull(cursor.planID),
                    expectedProjectRevision = document.project.revision,
                    expectedConfigRevision = document.project.configRevision,
                    expectedBranchHeadRevision = document.branches.first { it.id == job.branchID }.headRevision,
                    correctionPacket = cursor.correctionPacket,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            releaseActiveRun(fence)
            val current = loadOwned(fence) ?: return BatchRunResult.Paused
            return pauseOwned(current, fence, "prose_generation_failed")
        }
        releaseActiveRun(fence)
        val current = loadOwned(fence) ?: return BatchRunResult.Paused
        if (current.phase != NovelGhostwriteJobPhase.Generating || current.currentCursor.runID != runId) {
            return BatchRunResult.Paused
        }
        return when (terminal) {
            NovelGhostwriteGenerationTerminal.Completed -> promoteCompletedCandidate(current, fence)
            is NovelGhostwriteGenerationTerminal.Interrupted ->
                pauseOwned(current, fence, "prose_generation_interrupted")
            is NovelGhostwriteGenerationTerminal.Failed ->
                pauseOwned(current, fence, "prose_generation_failed")
        }
    }

    private suspend fun handleGeneratingRecovery(
        job: NovelGhostwriteJobV1,
        fence: Fence,
        restartAfterUserPause: Boolean,
    ): BatchRunResult? {
        val document = loadDocument(job)
        val candidate = exactRunCandidate(document, job, setOf(NovelCandidateStatus.Available))
        if (candidate != null) return promoteCandidate(job, fence, candidate)
        val previousRunId = job.currentCursor.runID
        if (document.activeRuns.any { it.id == previousRunId && it.status == NovelRunStatus.Running }) {
            effects.interruptExact(job.projectID, checkNotNull(previousRunId))
        }
        if (restartAfterUserPause) {
            val restarted = NovelGhostwriteJobReducer.restartUserPausedGeneration(
                job,
                job.ledgerRevision,
                fence.executionEpoch,
                ids.runId(),
                transitionNow(job),
            )
            return commit(job, restarted, fence)
        }
        return pauseOwned(job, fence, "orphan_prose_requires_recovery")
    }

    private suspend fun promoteCompletedCandidate(job: NovelGhostwriteJobV1, fence: Fence): BatchRunResult? {
        val document = loadDocument(job)
        val candidate = exactRunCandidate(document, job, setOf(NovelCandidateStatus.Available))
            ?: return pauseOwned(job, fence, "complete_candidate_missing")
        return promoteCandidate(job, fence, candidate)
    }

    private suspend fun promoteCandidate(
        job: NovelGhostwriteJobV1,
        fence: Fence,
        candidate: NovelCandidateRecord,
    ): BatchRunResult? = advance(
        job,
        fence,
        NovelGhostwriteJobPhase.CandidateReady,
        job.currentCursor.copy(
            candidateID = candidate.id,
            candidateContentSHA256 = sha256HexOfUtf8(candidate.content),
        ),
    )

    private suspend fun advanceCandidateReady(job: NovelGhostwriteJobV1, fence: Fence): BatchRunResult? {
        val document = loadDocument(job)
        requireExactCandidate(document, job, NovelCandidateStatus.Available)
        requireGenerationBase(document, job)
        return advance(job, fence, NovelGhostwriteJobPhase.Validating, job.currentCursor)
    }

    private suspend fun handleValidation(job: NovelGhostwriteJobV1, fence: Fence): BatchRunResult? {
        var document = loadDocument(job)
        val candidate = requireExactCandidate(document, job, NovelCandidateStatus.Available)
        requireGenerationBase(document, job)
        val acceptance = try {
            effects.acceptPlan(job.projectID, job.branchID, candidate.id)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return pauseOwned(job, fence, "chapter_review_incomplete")
        }
        if (loadOwned(fence)?.phase != NovelGhostwriteJobPhase.Validating) return BatchRunResult.Paused
        document = loadDocument(job)
        requireExactCandidate(document, job, NovelCandidateStatus.Available)
        requireGenerationBase(document, job)
        if (acceptance.schemaVersion != NovelChapterPlanAcceptanceV1.CURRENT_SCHEMA_VERSION) {
            return pauseOwned(job, fence, "chapter_review_incomplete")
        }
        if (!acceptance.accepted || acceptance.obviousRepetition.isNotEmpty()) {
            val plan = document.confirmedChapterPlan(job.branchID)
                ?: return pauseOwned(job, fence, "plan_mismatch")
            val missing = canonicalItems(acceptance.missingMustHappen, plan.mustHappen)
                ?: return pauseOwned(job, fence, "chapter_review_incomplete")
            val forbidden = canonicalItems(acceptance.forbiddenViolations, plan.mustNotHappen)
                ?: return pauseOwned(job, fence, "chapter_review_incomplete")
            val packet = NovelGhostwriteCorrectionPacketV1.bounded(
                reasonCode = if (!acceptance.accepted) {
                    "plan_acceptance_failed"
                } else {
                    "obvious_repetition"
                },
                summary = "Structured chapter quality gate failed.",
                missingMustHappen = missing,
                forbiddenViolations = forbidden,
                // Recent canonical highlights are already in the prose context; reviewer prose stays out.
                repetitionBeats = emptyList(),
                sourceCandidateID = candidate.id,
                planDigest = checkNotNull(job.currentCursor.planDigest),
            )
            return recordQualityFailure(job, fence, packet)
        }
        if (document.project.pauseGhostwriteOnBlockingContinuity) {
            val report = try {
                effects.auditContinuity(
                    job.projectID,
                    job.branchID,
                    candidate.id,
                    canonicalChapterLimit(job.currentCursor.chapterIndex),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return pauseOwned(job, fence, "continuity_audit_incomplete")
            }
            if (loadOwned(fence)?.phase != NovelGhostwriteJobPhase.Validating) {
                return BatchRunResult.Paused
            }
            if (report.failedChunkCount > 0) {
                return pauseOwned(job, fence, "continuity_audit_incomplete")
            }
            if (report.canonicalOnlyBlockingIssues.isNotEmpty()) {
                return pauseOwned(job, fence, "canonical_continuity_conflict")
            }
            val blocking = report.issues.filter { it.severity == NovelContinuityIssueSeverityV1.Blocking }
            if (blocking.isNotEmpty()) {
                val notes = blocking.map { issue ->
                    buildString {
                        append(issue.category.name)
                        issue.references.take(2).forEach { reference ->
                            append(": chapter ")
                            append(reference.chapterOrdinal)
                            append(" ")
                            append(reference.evidence.trim())
                        }
                    }
                }
                val packet = NovelGhostwriteCorrectionPacketV1.bounded(
                    reasonCode = "blocking_continuity",
                    summary = "Anchored candidate continuity gate failed.",
                    continuityNotes = notes,
                    sourceCandidateID = candidate.id,
                    planDigest = checkNotNull(job.currentCursor.planDigest),
                )
                return recordQualityFailure(job, fence, packet)
            }
        }
        document = loadDocument(job)
        requireExactCandidate(document, job, NovelCandidateStatus.Available)
        requireGenerationBase(document, job)
        val branch = document.branches.first { it.id == job.branchID }
        val pending = NovelGhostwritePendingCollectIdentityV1(
            chapterID = ids.chapterId(),
            chapterVersionID = ids.chapterVersionId(),
            collectOperationID = ids.operationId(),
            checkpointID = ids.checkpointId(),
            stateSnapshotID = ids.stateSnapshotId(),
            expectedProjectRevision = document.project.revision,
            expectedHeadRevision = branch.headRevision,
            expectedConfigRevision = document.project.configRevision,
        )
        return advance(
            job,
            fence,
            NovelGhostwriteJobPhase.CollectPrepared,
            job.currentCursor.copy(pendingCollectIdentity = pending),
        )
    }

    private suspend fun handleCorrection(job: NovelGhostwriteJobV1, fence: Fence): BatchRunResult? {
        val cursor = job.currentCursor
        if (cursor.attemptNumber >= NovelGhostwriteJobV1.MAX_QUALITY_ATTEMPTS_PER_CHAPTER ||
            cursor.sameFailureCount >= NovelGhostwriteJobV1.SAME_FAILURE_LIMIT
        ) {
            return pauseOwned(job, fence, "quality_circuit_breaker")
        }
        val packet = cursor.correctionPacket
            ?: return failOwned(job, fence, "missing_correction_packet")
        val document = loadDocument(job)
        requireGenerationBase(document, job)
        requireExactRejectedCandidate(document, job, checkNotNull(packet.sourceCandidateID))
        return advance(
            job,
            fence,
            NovelGhostwriteJobPhase.GenerationPrepared,
            cursor.copy(
                runID = ids.runId(),
                candidateID = null,
                candidateContentSHA256 = null,
                attemptNumber = cursor.attemptNumber + 1,
            ),
        )
    }

    private suspend fun recordQualityFailure(
        job: NovelGhostwriteJobV1,
        fence: Fence,
        packet: NovelGhostwriteCorrectionPacketV1,
    ): BatchRunResult? {
        val next = NovelGhostwriteJobReducer.recordQualityFailure(
            job,
            job.ledgerRevision,
            fence.executionEpoch,
            packet,
            transitionNow(job),
        )
        if (!commitTransition(job, next, fence)) return BatchRunResult.Paused
        return if (next.status == NovelGhostwriteJobStatus.Paused) BatchRunResult.Paused else null
    }

    private suspend fun handleCollect(job: NovelGhostwriteJobV1, fence: Fence): BatchRunResult? {
        var document = loadDocument(job)
        var fact = reconcileCollection(document, job, requireCurrentHead = true)
        if (fact == null) {
            val cursor = job.currentCursor
            val pending = checkNotNull(cursor.pendingCollectIdentity)
            val candidate = requireExactCandidate(document, job, NovelCandidateStatus.Available)
            requireGenerationBase(document, job)
            if (document.project.revision != pending.expectedProjectRevision) {
                return pauseOwned(job, fence, "collection_project_drift")
            }
            val plan = document.confirmedChapterPlan(job.branchID)
                ?: return pauseOwned(job, fence, "collection_plan_missing")
            try {
                effects.perform(
                    NovelIntent.CollectCandidate(
                        projectId = job.projectID,
                        branchId = job.branchID,
                        candidateId = candidate.id,
                        selectedText = candidate.content,
                        target = NovelCollectionTarget.CreateNextChapter(
                            chapterID = pending.chapterID,
                            title = plan.outlinePlacement.ifBlank { "未命名章节" },
                        ),
                        source = NovelCollectionSource.SystemAutoCollect,
                        operationId = pending.collectOperationID,
                        newChapterVersionId = pending.chapterVersionID,
                        newCheckpointId = pending.checkpointID,
                        newStateSnapshotId = pending.stateSnapshotID,
                        expectedProjectRevision = pending.expectedProjectRevision,
                        expectedConfigRevision = pending.expectedConfigRevision,
                        expectedBranchHeadRevision = pending.expectedHeadRevision,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                document = runCatching { loadDocument(job) }.getOrElse { return retryOwned(job, fence) }
                fact = reconcileCollection(document, job, requireCurrentHead = true)
                if (fact == null) return retryOwned(job, fence)
            }
            if (fact == null) {
                document = loadDocument(job)
                fact = reconcileCollection(document, job, requireCurrentHead = true)
            }
        }
        val collected = fact ?: return pauseOwned(job, fence, "collection_reconcile_failed")
        val collect = checkNotNull(job.currentCursor.pendingCollectIdentity).copy(
            collectedProjectRevision = collected.projectRevision,
            collectedHeadRevision = collected.headRevision,
            collectedConfigRevision = collected.configRevision,
        )
        val sync = NovelGhostwritePendingSyncIdentityV1(
            syncOperationID = ids.operationId(),
            checkpointID = ids.checkpointId(),
            stateSnapshotID = ids.stateSnapshotId(),
            expectedProjectRevision = collected.projectRevision,
            expectedCheckpointID = collect.checkpointID,
            expectedHeadRevision = collected.headRevision,
            expectedStateSnapshotID = collect.stateSnapshotID,
            expectedConfigRevision = collected.configRevision,
        )
        return advance(
            job,
            fence,
            NovelGhostwriteJobPhase.CollectedNeedsSync,
            job.currentCursor.copy(
                pendingCollectIdentity = collect,
                pendingSyncIdentity = sync,
            ),
        )
    }

    private suspend fun beginSync(job: NovelGhostwriteJobV1, fence: Fence): BatchRunResult? =
        advance(job, fence, NovelGhostwriteJobPhase.Syncing, job.currentCursor)

    private suspend fun handleSync(job: NovelGhostwriteJobV1, fence: Fence): BatchRunResult? {
        var document = loadDocument(job)
        var fact = reconcileSync(document, job, requireCurrentRevision = true)
        if (fact == null) {
            val sync = checkNotNull(job.currentCursor.pendingSyncIdentity)
            val branch = document.branches.first { it.id == job.branchID }
            if (document.project.revision != sync.expectedProjectRevision ||
                document.project.configRevision != sync.expectedConfigRevision ||
                branch.headCheckpointID != sync.expectedCheckpointID ||
                branch.headRevision != sync.expectedHeadRevision ||
                branch.currentStateSnapshotID != sync.expectedStateSnapshotID ||
                branch.syncStatus != NovelBranchSyncStatus.NeedsSync
            ) {
                return pauseOwned(job, fence, "sync_source_drift")
            }
            try {
                effects.perform(
                    NovelIntent.SyncManualEdits(
                        projectId = job.projectID,
                        branchId = job.branchID,
                        failClosed = true,
                        operationId = sync.syncOperationID,
                        newCheckpointId = sync.checkpointID,
                        newStateSnapshotId = sync.stateSnapshotID,
                        expectedProjectRevision = sync.expectedProjectRevision,
                        expectedConfigRevision = sync.expectedConfigRevision,
                        expectedBranchHeadRevision = sync.expectedHeadRevision,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                document = runCatching { loadDocument(job) }.getOrElse { return retryOwned(job, fence) }
                fact = reconcileSync(document, job, requireCurrentRevision = true)
                if (fact == null) return retryOwned(job, fence)
            }
            if (fact == null) {
                document = loadDocument(job)
                fact = reconcileSync(document, job, requireCurrentRevision = true)
            }
        }
        val synchronized = fact ?: return pauseOwned(job, fence, "sync_reconcile_failed")
        val sync = checkNotNull(job.currentCursor.pendingSyncIdentity).copy(
            synchronizedProjectRevision = synchronized.projectRevision,
            synchronizedHeadRevision = synchronized.headRevision,
            synchronizedConfigRevision = synchronized.configRevision,
        )
        val clear = NovelGhostwritePendingPlanClearV1(
            clearOperationID = ids.operationId(),
            expectedProjectRevision = synchronized.projectRevision,
            expectedConfigRevision = synchronized.configRevision,
        )
        val next = NovelGhostwriteJobReducer.preparePlanClear(
            job,
            job.ledgerRevision,
            fence.executionEpoch,
            clear,
            job.currentCursor.copy(pendingSyncIdentity = sync),
            transitionNow(job),
        )
        return commit(job, next, fence)
    }

    private suspend fun handleClearPlan(job: NovelGhostwriteJobV1, fence: Fence): BatchRunResult? {
        var document = loadDocument(job)
        var fact = reconcilePlanClear(document, job)
        if (fact == null) {
            val cursor = job.currentCursor
            val clear = checkNotNull(cursor.pendingPlanClear)
            val sync = checkNotNull(cursor.pendingSyncIdentity)
            val branch = document.branches.first { it.id == job.branchID }
            val plan = document.confirmedChapterPlan(job.branchID)
            if (plan?.id != cursor.planID || plan?.contentDigest != cursor.planDigest ||
                document.project.revision != clear.expectedProjectRevision ||
                document.project.configRevision != clear.expectedConfigRevision ||
                branch.headCheckpointID != sync.checkpointID ||
                branch.headRevision != sync.synchronizedHeadRevision
            ) {
                return pauseOwned(job, fence, "clear_plan_source_drift")
            }
            try {
                effects.perform(
                    NovelIntent.ClearChapterPlan(
                        projectId = job.projectID,
                        branchId = job.branchID,
                        operationId = clear.clearOperationID,
                        expectedPlanId = cursor.planID,
                        expectedPlanDigest = cursor.planDigest,
                        expectedProjectRevision = clear.expectedProjectRevision,
                        expectedConfigRevision = clear.expectedConfigRevision,
                        expectedBranchHeadRevision = checkNotNull(sync.synchronizedHeadRevision),
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                document = runCatching { loadDocument(job) }.getOrElse { return retryOwned(job, fence) }
                fact = reconcilePlanClear(document, job)
                if (fact == null) return retryOwned(job, fence)
            }
            if (fact == null) {
                document = loadDocument(job)
                fact = reconcilePlanClear(document, job)
            }
        }
        val cleared = fact ?: return pauseOwned(job, fence, "clear_plan_reconcile_failed")
        val cursor = job.currentCursor.copy(
            pendingPlanClear = checkNotNull(job.currentCursor.pendingPlanClear).copy(
                clearedProjectRevision = cleared.projectRevision,
                clearedConfigRevision = cleared.configRevision,
            ),
        )
        return advance(job, fence, NovelGhostwriteJobPhase.ChapterCommitPrepared, cursor)
    }

    private suspend fun handleChapterCommit(job: NovelGhostwriteJobV1, fence: Fence): BatchRunResult? {
        val document = loadDocument(job)
        requireExactCandidate(document, job, NovelCandidateStatus.Collected)
        reconcileCollection(document, job, requireCurrentHead = false)
            ?: return pauseOwned(job, fence, "receipt_collection_fact_missing")
        reconcileSync(document, job, requireCurrentRevision = false)
            ?: return pauseOwned(job, fence, "receipt_sync_fact_missing")
        reconcilePlanClear(document, job)
            ?: return pauseOwned(job, fence, "receipt_clear_fact_missing")
        val receipt = receipt(job.currentCursor)
        val next = NovelGhostwriteJobReducer.completeChapter(
            job,
            job.ledgerRevision,
            fence.executionEpoch,
            receipt,
            transitionNow(job),
        )
        if (!commitTransition(job, next, fence)) return BatchRunResult.Paused
        return if (next.status == NovelGhostwriteJobStatus.Completed) BatchRunResult.Completed else null
    }

    private suspend fun handleChapterCommitted(job: NovelGhostwriteJobV1, fence: Fence): BatchRunResult? {
        if (job.completedChapterCount == job.targetChapterCount) return BatchRunResult.Completed
        val receipt = job.chapterReceipts.last()
        val document = loadDocument(job)
        val branch = document.branches.first { it.id == job.branchID }
        if (document.project.revision != receipt.clearedProjectRevision ||
            document.project.configRevision != receipt.clearedConfigRevision ||
            branch.headCheckpointID != receipt.synchronizedCheckpointID ||
            branch.headRevision != receipt.synchronizedHeadRevision ||
            branch.currentStateSnapshotID != receipt.synchronizedStateSnapshotID ||
            branch.syncStatus != NovelBranchSyncStatus.Synchronized ||
            document.chapterPlan(job.branchID) != null
        ) {
            return pauseOwned(job, fence, "next_chapter_base_drift")
        }
        val cursor = NovelGhostwriteChapterCursorV1(
            chapterIndex = job.completedChapterCount + 1,
            baseProjectRevision = receipt.clearedProjectRevision,
            baseCheckpointID = receipt.synchronizedCheckpointID,
            baseHeadRevision = receipt.synchronizedHeadRevision,
            baseStateSnapshotID = receipt.synchronizedStateSnapshotID,
            baseConfigRevision = receipt.clearedConfigRevision,
        )
        val next = NovelGhostwriteJobReducer.startNextChapter(
            job,
            job.ledgerRevision,
            fence.executionEpoch,
            cursor,
            transitionNow(job),
        )
        return commit(job, next, fence)
    }

    private suspend fun acquireLease(jobId: NovelGhostwriteJobId, workId: String): LeaseAcquisition? {
        val loaded = store.loadJob(jobId)
        if (loaded.access != NovelGhostwriteJobLoadAccess.ReadWrite) return null
        val job = loaded.job
        if (job.isTerminal) return LeaseAcquisition(job, resumedUserPausedGeneration = false)
        if (job.status == NovelGhostwriteJobStatus.Paused &&
            job.statusReasonCode == "quality_circuit_breaker"
        ) {
            return LeaseAcquisition(job, resumedUserPausedGeneration = false)
        }
        val resumedUserPausedGeneration = job.status == NovelGhostwriteJobStatus.Paused &&
            job.statusReasonCode == "user_paused" &&
            job.phase == NovelGhostwriteJobPhase.Generating
        val timestamp = transitionNow(job)
        val next = if (job.status == NovelGhostwriteJobStatus.Running &&
            job.leaseOwnerWorkID == workId
        ) {
            NovelGhostwriteJobReducer.renewLease(
                job,
                job.ledgerRevision,
                job.executionEpoch,
                workId,
                timestamp.plus(LEASE_DURATION),
                timestamp,
            )
        } else {
            if (job.leaseOwnerWorkID != null && job.leaseOwnerWorkID != workId &&
                job.leaseUntil?.isAfter(timestamp) == true
            ) {
                return null
            }
            NovelGhostwriteJobReducer.claimLease(
                job,
                job.ledgerRevision,
                job.executionEpoch,
                workId,
                timestamp.plus(LEASE_DURATION),
                timestamp,
            )
        }
        return try {
            LeaseAcquisition(
                job = store.commitJob(next, job.ledgerRevision, job.executionEpoch).job,
                resumedUserPausedGeneration = resumedUserPausedGeneration,
            )
        } catch (_: NovelGhostwriteJobError.StaleLedgerRevision) {
            null
        } catch (_: NovelGhostwriteJobError.StaleExecutionEpoch) {
            null
        } catch (_: NovelGhostwriteJobError.LeaseHeld) {
            null
        }
    }

    private suspend fun renewIfNeeded(job: NovelGhostwriteJobV1, fence: Fence): NovelGhostwriteJobV1? {
        val timestamp = transitionNow(job)
        if (job.leaseUntil?.isAfter(timestamp.plus(LEASE_RENEW_WINDOW)) == true) return job
        val next = NovelGhostwriteJobReducer.renewLease(
            job,
            job.ledgerRevision,
            fence.executionEpoch,
            fence.workId,
            timestamp.plus(LEASE_DURATION),
            timestamp,
        )
        return if (commitTransition(job, next, fence)) next else null
    }

    private suspend fun loadOwned(fence: Fence): NovelGhostwriteJobV1? {
        val loaded = store.loadJob(fence.jobId)
        if (loaded.access != NovelGhostwriteJobLoadAccess.ReadWrite) return null
        return loaded.job.takeIf {
            it.status == NovelGhostwriteJobStatus.Running &&
                it.executionEpoch == fence.executionEpoch &&
                it.leaseOwnerWorkID == fence.workId
        }
    }

    private suspend fun loadDocument(job: NovelGhostwriteJobV1): NovelProjectDocumentV1 {
        val snapshot = effects.loadProject(job.projectID)
        if (snapshot.access != NovelProjectLoadAccess.ReadWrite) {
            throw BatchFactMismatch("project_read_only")
        }
        if (snapshot.document.project.id != job.projectID) {
            throw BatchFactMismatch("project_identity_mismatch")
        }
        return snapshot.document
    }

    private fun Exception.isRecoverableInfrastructureFailure(): Boolean =
        this is IOException ||
            this is NovelGhostwriteJobError.StorageFailure ||
            this is NovelError.RepositoryFailure ||
            this is NovelError.StorageUnavailable ||
            this is NovelError.StorageIndeterminate

    private fun canonicalItems(reported: List<String>, canonical: List<String>): List<String>? {
        if (reported.isEmpty()) return emptyList()
        val reportedKeys = reported.map { it.trim().lowercase() }.toSet()
        val canonicalKeys = canonical.mapTo(mutableSetOf()) { it.trim().lowercase() }
        if (reportedKeys.any { it !in canonicalKeys }) return null
        return canonical.filter { it.trim().lowercase() in reportedKeys }
    }

    private suspend fun advance(
        job: NovelGhostwriteJobV1,
        fence: Fence,
        phase: NovelGhostwriteJobPhase,
        cursor: NovelGhostwriteChapterCursorV1,
    ): BatchRunResult? {
        val next = NovelGhostwriteJobReducer.advancePhase(
            job,
            job.ledgerRevision,
            fence.executionEpoch,
            phase,
            cursor,
            transitionNow(job),
        )
        return commit(job, next, fence)
    }

    private suspend fun commit(
        from: NovelGhostwriteJobV1,
        to: NovelGhostwriteJobV1,
        fence: Fence,
    ): BatchRunResult? = if (commitTransition(from, to, fence)) null else BatchRunResult.Paused

    private suspend fun commitTransition(
        from: NovelGhostwriteJobV1,
        to: NovelGhostwriteJobV1,
        fence: Fence,
    ): Boolean = try {
        val installed = store.commitJob(to, from.ledgerRevision, fence.executionEpoch).job
        installed.executionEpoch == fence.executionEpoch &&
            (installed.status != NovelGhostwriteJobStatus.Running ||
                installed.leaseOwnerWorkID == fence.workId)
    } catch (_: NovelGhostwriteJobError.StaleLedgerRevision) {
        false
    } catch (_: NovelGhostwriteJobError.StaleExecutionEpoch) {
        false
    }

    private suspend fun pauseOwned(
        job: NovelGhostwriteJobV1,
        fence: Fence,
        reason: String,
    ): BatchRunResult {
        val current = loadOwned(fence) ?: return BatchRunResult.Paused
        val next = NovelGhostwriteJobReducer.pause(
            current,
            current.ledgerRevision,
            fence.executionEpoch,
            reason,
            transitionNow(current),
        )
        commitTransition(current, next, fence)
        return BatchRunResult.Paused
    }

    private suspend fun failOwned(
        job: NovelGhostwriteJobV1,
        fence: Fence,
        reason: String,
    ): BatchRunResult {
        val current = loadOwned(fence) ?: return BatchRunResult.Paused
        val next = NovelGhostwriteJobReducer.fail(
            current,
            current.ledgerRevision,
            fence.executionEpoch,
            reason,
            transitionNow(current),
        )
        return if (commitTransition(current, next, fence)) {
            BatchRunResult.Failed
        } else {
            BatchRunResult.Paused
        }
    }

    private suspend fun retryOwned(job: NovelGhostwriteJobV1, fence: Fence): BatchRunResult {
        val current = loadOwned(fence) ?: return BatchRunResult.Paused
        if (current.currentCursor.infraRetryCount >= NovelGhostwriteJobV1.MAX_INFRA_RETRIES_PER_PHASE) {
            return pauseOwned(current, fence, "infra_retry_exhausted")
        }
        val next = NovelGhostwriteJobReducer.recordInfraRetry(
            current,
            current.ledgerRevision,
            fence.executionEpoch,
            transitionNow(current),
        )
        if (!commitTransition(current, next, fence)) return BatchRunResult.Paused
        return if (next.status == NovelGhostwriteJobStatus.Paused) {
            BatchRunResult.Paused
        } else {
            BatchRunResult.Retry
        }
    }

    private fun requireCanonicalBase(document: NovelProjectDocumentV1, job: NovelGhostwriteJobV1) {
        val cursor = job.currentCursor
        val branch = document.branches.singleOrNull { it.id == job.branchID }
            ?: throw BatchFactMismatch("branch_missing")
        if (document.project.revision != cursor.baseProjectRevision ||
            document.project.configRevision != cursor.baseConfigRevision ||
            branch.headCheckpointID != cursor.baseCheckpointID ||
            branch.headRevision != cursor.baseHeadRevision ||
            branch.currentStateSnapshotID != cursor.baseStateSnapshotID ||
            branch.syncStatus != NovelBranchSyncStatus.Synchronized ||
            branch.lifecycle != NovelBranchLifecycle.Active ||
            branch.activeRunID != null ||
            document.project.collaborationMode != NovelCollaborationMode.Ghostwrite
        ) {
            throw BatchFactMismatch("canonical_base_drift")
        }
    }

    private fun requireGenerationBase(document: NovelProjectDocumentV1, job: NovelGhostwriteJobV1) {
        val cursor = job.currentCursor
        val branch = document.branches.singleOrNull { it.id == job.branchID }
            ?: throw BatchFactMismatch("branch_missing")
        val expectedConfig = cursor.pendingPlanUpsert?.upsertedConfigRevision ?: cursor.baseConfigRevision
        val plan = document.confirmedChapterPlan(job.branchID)
        if (branch.headCheckpointID != cursor.baseCheckpointID ||
            branch.headRevision != cursor.baseHeadRevision ||
            branch.currentStateSnapshotID != cursor.baseStateSnapshotID ||
            branch.syncStatus != NovelBranchSyncStatus.Synchronized ||
            document.project.configRevision != expectedConfig ||
            plan?.id != cursor.planID || plan?.contentDigest != cursor.planDigest ||
            document.project.collaborationMode != NovelCollaborationMode.Ghostwrite
        ) {
            throw BatchFactMismatch("generation_base_drift")
        }
    }

    private fun exactRunCandidate(
        document: NovelProjectDocumentV1,
        job: NovelGhostwriteJobV1,
        statuses: Set<NovelCandidateStatus>,
    ): NovelCandidateRecord? {
        val cursor = job.currentCursor
        val run = document.activeRuns.singleOrNull { it.id == cursor.runID } ?: return null
        if (run.status != NovelRunStatus.Completed || run.terminalAt == null ||
            run.terminalFailure != null || run.interruptionReason != null ||
            run.kind != NovelRunKind.Prose || run.mode != NovelSessionMode.WriteProse ||
            run.granularity != app.amber.feature.novel.model.NovelGenerationGranularity.WholeChapter ||
            run.branchID != job.branchID || run.baseCheckpointID != cursor.baseCheckpointID ||
            run.baseHeadRevision != cursor.baseHeadRevision || run.ghostwritePlanID != cursor.planID ||
            run.chapterPlanDigest != cursor.planDigest || run.partialContent.isBlank()
        ) {
            return null
        }
        val candidate = document.candidates.singleOrNull { it.id == run.candidateID } ?: return null
        val message = document.sessions.singleOrNull { it.id == run.sessionID }
            ?.messages?.singleOrNull { it.id == run.messageID } ?: return null
        if (candidate.status !in statuses || candidate.kind != NovelCandidateKind.Prose ||
            candidate.branchID != run.branchID || candidate.sessionID != run.sessionID ||
            candidate.sourceMessageID != run.messageID || candidate.baseCheckpointID != run.baseCheckpointID ||
            candidate.baseHeadRevision != run.baseHeadRevision || candidate.ghostwritePlanID != cursor.planID ||
            candidate.chapterPlanDigest != cursor.planDigest || candidate.content != run.partialContent ||
            message.role != NovelSessionRole.Assistant || message.kind != NovelSessionMessageKind.ProseCandidate ||
            message.runID != run.id || message.candidateID != candidate.id || message.content != candidate.content
        ) {
            return null
        }
        if (cursor.candidateID != null && cursor.candidateID != candidate.id) return null
        if (cursor.candidateContentSHA256 != null &&
            cursor.candidateContentSHA256 != sha256HexOfUtf8(candidate.content)
        ) {
            return null
        }
        return candidate
    }

    private fun requireExactCandidate(
        document: NovelProjectDocumentV1,
        job: NovelGhostwriteJobV1,
        status: NovelCandidateStatus,
    ): NovelCandidateRecord = exactRunCandidate(document, job, setOf(status))
        ?: throw BatchFactMismatch("candidate_identity_mismatch")

    private fun requireExactRejectedCandidate(
        document: NovelProjectDocumentV1,
        job: NovelGhostwriteJobV1,
        candidateId: NovelCandidateId,
    ) {
        val candidate = document.candidates.singleOrNull { it.id == candidateId }
            ?: throw BatchFactMismatch("rejected_candidate_missing")
        if (candidate.status != NovelCandidateStatus.Available ||
            candidate.branchID != job.branchID || candidate.baseCheckpointID != job.currentCursor.baseCheckpointID ||
            candidate.baseHeadRevision != job.currentCursor.baseHeadRevision ||
            candidate.ghostwritePlanID != job.currentCursor.planID ||
            candidate.chapterPlanDigest != job.currentCursor.planDigest
        ) {
            throw BatchFactMismatch("rejected_candidate_drift")
        }
    }

    private fun reconcilePlanUpsert(document: NovelProjectDocumentV1, job: NovelGhostwriteJobV1): RevisionFact? {
        val cursor = job.currentCursor
        val pending = checkNotNull(cursor.pendingPlanUpsert)
        val applied = applied(document, pending.upsertOperationID) ?: return null
        val outcome = applied.outcome as? NovelOutcome.ChapterPlanUpserted
            ?: throw BatchFactMismatch("plan_upsert_outcome_mismatch")
        val plan = document.confirmedChapterPlan(job.branchID)
        if (applied.kind != NovelOperationKind.UpsertChapterPlan || outcome.projectID != job.projectID ||
            outcome.branchID != job.branchID || outcome.planID != cursor.planID ||
            outcome.contentDigest != cursor.planDigest || outcome.projectRevision != applied.appliedProjectRevision ||
            outcome.projectRevision != pending.expectedProjectRevision + 1 ||
            outcome.configRevision != pending.expectedConfigRevision + 1 ||
            plan?.id != cursor.planID || plan?.contentDigest != cursor.planDigest ||
            document.project.revision != outcome.projectRevision ||
            document.project.configRevision != outcome.configRevision
        ) {
            throw BatchFactMismatch("plan_upsert_fact_mismatch")
        }
        return RevisionFact(outcome.projectRevision, cursor.baseHeadRevision, outcome.configRevision)
    }

    private fun reconcileCollection(
        document: NovelProjectDocumentV1,
        job: NovelGhostwriteJobV1,
        requireCurrentHead: Boolean,
    ): RevisionFact? {
        val cursor = job.currentCursor
        val pending = checkNotNull(cursor.pendingCollectIdentity)
        val applied = applied(document, pending.collectOperationID) ?: return null
        val outcome = applied.outcome as? NovelOutcome.CandidateCollected
            ?: throw BatchFactMismatch("collection_outcome_mismatch")
        val candidate = document.candidates.singleOrNull { it.id == cursor.candidateID }
        val version = document.chapterVersions.singleOrNull { it.id == pending.chapterVersionID }
        val checkpoint = document.checkpoints.singleOrNull { it.id == pending.checkpointID }
        val chapter = document.chapters.singleOrNull { it.id == pending.chapterID }
        val base = document.checkpoints.singleOrNull { it.id == cursor.baseCheckpointID }
        val expectedSelections = base?.chapterSelections.orEmpty() +
            NovelChapterSelection(pending.chapterID, pending.chapterVersionID)
        if (applied.kind != NovelOperationKind.CollectCandidate || outcome.projectID != job.projectID ||
            outcome.branchID != job.branchID || outcome.candidateID != cursor.candidateID ||
            outcome.checkpointID != pending.checkpointID || outcome.chapterVersionID != pending.chapterVersionID ||
            outcome.revision != applied.appliedProjectRevision ||
            outcome.revision != pending.expectedProjectRevision + 1 ||
            candidate?.status != NovelCandidateStatus.Collected ||
            candidate.collectedCheckpointID != pending.checkpointID || version?.chapterID != pending.chapterID ||
            version.kind != NovelChapterVersionKind.Collected || version.sourceCandidateID != cursor.candidateID ||
            version.operationID != pending.collectOperationID ||
            version.content.let(::sha256HexOfUtf8) != cursor.candidateContentSHA256 ||
            checkpoint?.kind != NovelCheckpointKind.Collection ||
            checkpoint.operationID != pending.collectOperationID ||
            checkpoint.parentCheckpointID != cursor.baseCheckpointID ||
            checkpoint.baseHeadRevision != cursor.baseHeadRevision ||
            checkpoint.sourceCandidateID != cursor.candidateID ||
            checkpoint.stateSnapshotID != pending.stateSnapshotID ||
            checkpoint.chapterSelections != expectedSelections || chapter?.discardedAt != null ||
            document.stateSnapshots.none { it.id == pending.stateSnapshotID }
        ) {
            throw BatchFactMismatch("collection_fact_mismatch")
        }
        val fact = RevisionFact(outcome.revision, pending.expectedHeadRevision + 1, pending.expectedConfigRevision)
        if (requireCurrentHead) {
            val branch = document.branches.first { it.id == job.branchID }
            if (document.project.revision != fact.projectRevision ||
                document.project.configRevision != fact.configRevision ||
                branch.headCheckpointID != pending.checkpointID || branch.headRevision != fact.headRevision ||
                branch.currentStateSnapshotID != pending.stateSnapshotID ||
                branch.syncStatus != NovelBranchSyncStatus.NeedsSync
            ) {
                throw BatchFactMismatch("collection_current_head_mismatch")
            }
        }
        return fact
    }

    private fun reconcileSync(
        document: NovelProjectDocumentV1,
        job: NovelGhostwriteJobV1,
        requireCurrentRevision: Boolean,
    ): RevisionFact? {
        val sync = checkNotNull(job.currentCursor.pendingSyncIdentity)
        val applied = applied(document, sync.syncOperationID) ?: return null
        val outcome = applied.outcome as? NovelOutcome.ManualSyncCommitted
            ?: throw BatchFactMismatch("sync_outcome_mismatch")
        val checkpoint = document.checkpoints.singleOrNull { it.id == sync.checkpointID }
        val branch = document.branches.first { it.id == job.branchID }
        if (applied.kind != NovelOperationKind.SyncManualEdits || outcome.projectID != job.projectID ||
            outcome.branchID != job.branchID || outcome.checkpointID != sync.checkpointID ||
            outcome.revision != applied.appliedProjectRevision ||
            outcome.revision != sync.expectedProjectRevision + 1 ||
            checkpoint?.kind != NovelCheckpointKind.ManualSync || checkpoint.operationID != sync.syncOperationID ||
            checkpoint.parentCheckpointID != sync.expectedCheckpointID ||
            checkpoint.baseHeadRevision != sync.expectedHeadRevision ||
            checkpoint.stateSnapshotID != sync.stateSnapshotID ||
            document.stateSnapshots.none { it.id == sync.stateSnapshotID } ||
            branch.headCheckpointID != sync.checkpointID || branch.headRevision != sync.expectedHeadRevision + 1 ||
            branch.currentStateSnapshotID != sync.stateSnapshotID ||
            branch.syncStatus != NovelBranchSyncStatus.Synchronized
        ) {
            throw BatchFactMismatch("sync_fact_mismatch")
        }
        val fact = RevisionFact(outcome.revision, branch.headRevision, sync.expectedConfigRevision)
        if (requireCurrentRevision &&
            (document.project.revision != fact.projectRevision ||
                document.project.configRevision != fact.configRevision)
        ) {
            throw BatchFactMismatch("sync_current_revision_mismatch")
        }
        return fact
    }

    private fun reconcilePlanClear(document: NovelProjectDocumentV1, job: NovelGhostwriteJobV1): RevisionFact? {
        val clear = checkNotNull(job.currentCursor.pendingPlanClear)
        val applied = applied(document, clear.clearOperationID) ?: return null
        val outcome = applied.outcome as? NovelOutcome.ChapterPlanCleared
            ?: throw BatchFactMismatch("clear_outcome_mismatch")
        val sync = checkNotNull(job.currentCursor.pendingSyncIdentity)
        val branch = document.branches.first { it.id == job.branchID }
        if (applied.kind != NovelOperationKind.ClearChapterPlan || outcome.projectID != job.projectID ||
            outcome.branchID != job.branchID || outcome.projectRevision != applied.appliedProjectRevision ||
            outcome.projectRevision != clear.expectedProjectRevision + 1 ||
            outcome.configRevision != clear.expectedConfigRevision + 1 || document.chapterPlan(job.branchID) != null ||
            document.project.revision != outcome.projectRevision ||
            document.project.configRevision != outcome.configRevision ||
            branch.headCheckpointID != sync.checkpointID || branch.headRevision != sync.synchronizedHeadRevision ||
            branch.currentStateSnapshotID != sync.stateSnapshotID ||
            branch.syncStatus != NovelBranchSyncStatus.Synchronized
        ) {
            throw BatchFactMismatch("clear_fact_mismatch")
        }
        return RevisionFact(
            outcome.projectRevision,
            checkNotNull(sync.synchronizedHeadRevision),
            outcome.configRevision,
        )
    }

    private fun applied(document: NovelProjectDocumentV1, id: NovelOperationId): NovelAppliedOperationRecord? {
        val matches = document.appliedOperations.filter { it.operationID == id }
        if (matches.size > 1) throw BatchFactMismatch("duplicate_applied_operation")
        return matches.singleOrNull()
    }

    private fun receipt(cursor: NovelGhostwriteChapterCursorV1): NovelGhostwriteChapterReceiptV1 {
        val plan = cursor.pendingPlanUpsert
        val collect = checkNotNull(cursor.pendingCollectIdentity)
        val sync = checkNotNull(cursor.pendingSyncIdentity)
        val clear = checkNotNull(cursor.pendingPlanClear)
        return NovelGhostwriteChapterReceiptV1(
            chapterIndex = cursor.chapterIndex,
            baseProjectRevision = cursor.baseProjectRevision,
            baseCheckpointID = cursor.baseCheckpointID,
            baseHeadRevision = cursor.baseHeadRevision,
            baseStateSnapshotID = cursor.baseStateSnapshotID,
            baseConfigRevision = cursor.baseConfigRevision,
            planID = checkNotNull(cursor.planID),
            planDigest = checkNotNull(cursor.planDigest),
            planUpsertOperationID = plan?.upsertOperationID,
            planUpsertedProjectRevision = plan?.upsertedProjectRevision,
            planUpsertedConfigRevision = plan?.upsertedConfigRevision,
            runID = checkNotNull(cursor.runID),
            candidateID = checkNotNull(cursor.candidateID),
            candidateContentSHA256 = checkNotNull(cursor.candidateContentSHA256),
            chapterID = collect.chapterID,
            chapterVersionID = collect.chapterVersionID,
            collectOperationID = collect.collectOperationID,
            collectedCheckpointID = collect.checkpointID,
            collectedStateSnapshotID = collect.stateSnapshotID,
            collectExpectedProjectRevision = collect.expectedProjectRevision,
            collectedProjectRevision = checkNotNull(collect.collectedProjectRevision),
            collectedHeadRevision = checkNotNull(collect.collectedHeadRevision),
            collectedConfigRevision = checkNotNull(collect.collectedConfigRevision),
            syncOperationID = sync.syncOperationID,
            synchronizedCheckpointID = sync.checkpointID,
            synchronizedProjectRevision = checkNotNull(sync.synchronizedProjectRevision),
            synchronizedHeadRevision = checkNotNull(sync.synchronizedHeadRevision),
            synchronizedStateSnapshotID = sync.stateSnapshotID,
            synchronizedConfigRevision = checkNotNull(sync.synchronizedConfigRevision),
            clearPlanOperationID = clear.clearOperationID,
            clearedProjectRevision = checkNotNull(clear.clearedProjectRevision),
            clearedConfigRevision = checkNotNull(clear.clearedConfigRevision),
            completedAt = now(),
        )
    }

    private fun normalizePlanProposal(
        proposal: app.amber.feature.novel.domain.NovelChapterPlanProposalV1,
    ): PlanPayload = PlanPayload(
        outlinePlacement = proposal.outlinePlacement.trim(),
        goalAndConflict = proposal.goalAndConflict.trim(),
        mustHappen = NovelChapterPlanRecord.normalizedLines(proposal.mustHappen),
        mustNotHappen = NovelChapterPlanRecord.normalizedLines(proposal.mustNotHappen),
        endingHook = proposal.endingHook.trim(),
        visibleFacts = NovelChapterPlanRecord.normalizedLines(proposal.visibleFacts),
    ).also {
        require(it.goalAndConflict.isNotBlank() && it.mustHappen.isNotEmpty())
    }

    private fun planDigest(payload: PlanPayload): String = NovelChapterPlanRecord.digest(
        listOf(
            payload.outlinePlacement,
            payload.goalAndConflict,
            payload.mustHappen.joinToString("\n"),
            payload.mustNotHappen.joinToString("\n"),
            payload.endingHook,
            payload.visibleFacts.joinToString("\n"),
        ).joinToString("\n---\n"),
    )

    private fun ownerToken(fence: Fence): String =
        "batch:${fence.jobId.rawValue}:${fence.executionEpoch}:${fence.workId}"

    private fun releaseActiveRun(fence: Fence) {
        val claim = activeRunClaims.remove(ownerToken(fence)) ?: return
        runRegistry.release(claim.ownerToken, claim.projectId, claim.runId)
    }

    private fun transitionNow(job: NovelGhostwriteJobV1): Instant = maxOf(now(), job.updatedAt)

    private data class RevisionFact(val projectRevision: Long, val headRevision: Long, val configRevision: Long)

    private data class PlanPayload(
        val outlinePlacement: String,
        val goalAndConflict: String,
        val mustHappen: List<String>,
        val mustNotHappen: List<String>,
        val endingHook: String,
        val visibleFacts: List<String>,
    )

    private class BatchFactMismatch(val reasonCode: String) : Exception(reasonCode)

    companion object {
        private const val RECENT_CONTINUITY_CHAPTER_COUNT = 6
        private const val GLOBAL_CONTINUITY_INTERVAL = 10
        private val LEASE_DURATION: Duration = Duration.ofMinutes(30)
        private val LEASE_RENEW_WINDOW: Duration = Duration.ofMinutes(5)

        internal fun canonicalChapterLimit(chapterIndex: Int): Int? =
            if (chapterIndex % GLOBAL_CONTINUITY_INTERVAL == 0) null else RECENT_CONTINUITY_CHAPTER_COUNT
    }
}

internal interface NovelGhostwriteBatchIds {
    fun runId(): NovelRunId
    fun planId(): NovelChapterPlanId
    fun chapterId(): NovelChapterId
    fun chapterVersionId(): NovelChapterVersionId
    fun checkpointId(): NovelCheckpointId
    fun stateSnapshotId(): NovelStateSnapshotId
    fun operationId(): NovelOperationId
}

private object RandomNovelGhostwriteBatchIds : NovelGhostwriteBatchIds {
    override fun runId(): NovelRunId = NovelRunId.generate()
    override fun planId(): NovelChapterPlanId = NovelChapterPlanId.generate()
    override fun chapterId(): NovelChapterId = NovelChapterId.generate()
    override fun chapterVersionId(): NovelChapterVersionId = NovelChapterVersionId.generate()
    override fun checkpointId(): NovelCheckpointId = NovelCheckpointId.generate()
    override fun stateSnapshotId(): NovelStateSnapshotId = NovelStateSnapshotId.generate()
    override fun operationId(): NovelOperationId = NovelOperationId.generate()
}
