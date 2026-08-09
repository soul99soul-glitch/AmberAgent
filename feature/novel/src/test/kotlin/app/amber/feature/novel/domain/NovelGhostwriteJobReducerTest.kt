package app.amber.feature.novel.domain

import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelCandidateId
import app.amber.feature.novel.model.NovelChapterId
import app.amber.feature.novel.model.NovelChapterPlanId
import app.amber.feature.novel.model.NovelChapterVersionId
import app.amber.feature.novel.model.NovelCheckpointId
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
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelRunId
import app.amber.feature.novel.model.NovelStateSnapshotId
import app.amber.feature.novel.serialization.NovelSwiftCompatibleJson
import app.amber.feature.novel.serialization.sha256HexOfUtf8
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class NovelGhostwriteJobReducerTest {
    @Test
    fun serializationDefaultsAndTargetRange_areFailClosed() {
        val original = newJob(target = 50)
        val json = NovelSwiftCompatibleJson.json
        val encoded = json.encodeToJsonElement(NovelGhostwriteJobV1.serializer(), original)
        assertEquals(original, json.decodeFromJsonElement(NovelGhostwriteJobV1.serializer(), encoded))

        val root = encoded.jsonObject.toMutableMap().apply {
            remove("schemaVersion")
            remove("status")
            remove("phase")
            remove("ledgerRevision")
            remove("executionEpoch")
            remove("completedChapterCount")
            remove("chapterReceipts")
            val cursor = getValue("currentCursor").jsonObject.toMutableMap().apply {
                remove("baseProjectRevision")
                remove("attemptNumber")
                remove("infraRetryCount")
                remove("sameFailureCount")
            }
            this["currentCursor"] = JsonObject(cursor)
        }
        val decoded = json.decodeFromJsonElement(
            NovelGhostwriteJobV1.serializer(),
            JsonObject(root),
        )
        assertEquals(0, decoded.currentCursor.baseProjectRevision)
        NovelGhostwriteJobValidator.validate(decoded)

        expectError<NovelGhostwriteJobError.InvalidJob> { newJob(target = 0) }
        expectError<NovelGhostwriteJobError.InvalidJob> { newJob(target = 51) }
    }

    @Test
    fun qualityBudget_allowsThreeGenerations_butRepeatedFingerprintStopsAfterTwo() {
        var repeated = validatingJob()
        val firstPacket = packetFor(repeated, "acceptance_failed", "missing gate")
        expectError<NovelGhostwriteJobError.InvalidJob> {
            NovelGhostwriteJobReducer.recordQualityFailure(
                repeated,
                repeated.ledgerRevision,
                repeated.executionEpoch,
                firstPacket.copy(sourceCandidateID = null),
                nextTime(repeated),
            )
        }
        expectError<NovelGhostwriteJobError.InvalidJob> {
            NovelGhostwriteJobReducer.recordQualityFailure(
                repeated,
                repeated.ledgerRevision,
                repeated.executionEpoch,
                firstPacket.copy(sourceCandidateID = NovelCandidateId.generate()),
                nextTime(repeated),
            )
        }
        repeated = NovelGhostwriteJobReducer.recordQualityFailure(
            repeated,
            repeated.ledgerRevision,
            repeated.executionEpoch,
            firstPacket,
            nextTime(repeated),
        )
        assertEquals(1, repeated.currentCursor.attemptNumber)
        assertEquals(NovelGhostwriteJobStatus.Running, repeated.status)

        repeated = restartAndValidate(repeated)
        repeated = NovelGhostwriteJobReducer.recordQualityFailure(
            repeated,
            repeated.ledgerRevision,
            repeated.executionEpoch,
            firstPacket.copy(sourceCandidateID = repeated.currentCursor.candidateID),
            nextTime(repeated),
        )
        assertEquals(2, repeated.currentCursor.attemptNumber)
        assertEquals(2, repeated.currentCursor.sameFailureCount)
        assertEquals(NovelGhostwriteJobStatus.Paused, repeated.status)
        assertNull(repeated.leaseOwnerWorkID)
        expectQualityResumeRejected(repeated)

        var budget = validatingJob()
        repeat(2) { failureIndex ->
            budget = NovelGhostwriteJobReducer.recordQualityFailure(
                budget,
                budget.ledgerRevision,
                budget.executionEpoch,
                packetFor(budget, "failure-$failureIndex", "summary-$failureIndex"),
                nextTime(budget),
            )
            assertEquals(NovelGhostwriteJobStatus.Running, budget.status)
            budget = restartAndValidate(budget)
        }
        assertEquals(3, budget.currentCursor.attemptNumber)
        budget = NovelGhostwriteJobReducer.recordQualityFailure(
            budget,
            budget.ledgerRevision,
            budget.executionEpoch,
            packetFor(budget, "failure-3", "summary-3"),
            nextTime(budget),
        )
        assertEquals(NovelGhostwriteJobStatus.Paused, budget.status)
        assertEquals(3, budget.currentCursor.attemptNumber)
        assertEquals("quality_circuit_breaker", budget.statusReasonCode)
        expectQualityResumeRejected(budget)
    }

    @Test
    fun explicitResume_resetsOnlyPausedInfrastructureRetryBudget() {
        var job = validatingJob()
        repeat(NovelGhostwriteJobV1.MAX_INFRA_RETRIES_PER_PHASE) {
            job = NovelGhostwriteJobReducer.recordInfraRetry(
                job,
                job.ledgerRevision,
                job.executionEpoch,
                nextTime(job),
            )
        }
        assertEquals(NovelGhostwriteJobStatus.Paused, job.status)
        assertEquals(3, job.currentCursor.infraRetryCount)
        job = NovelGhostwriteJobReducer.claimLease(
            job,
            job.ledgerRevision,
            job.executionEpoch,
            "worker-resume",
            LEASE_UNTIL,
            nextTime(job),
        )
        assertEquals(0, job.currentCursor.infraRetryCount)
        assertEquals(NovelGhostwriteJobStatus.Running, job.status)
    }

    @Test
    fun receiptAndNextChapter_requireExactWriteAheadChainAndCleanCursor() {
        val prepared = chapterCommitPreparedJob(target = 2)
        val receipt = receiptFor(prepared.currentCursor, nextTime(prepared))
        expectError<NovelGhostwriteJobError.InvalidJob> {
            NovelGhostwriteJobReducer.completeChapter(
                prepared,
                prepared.ledgerRevision,
                prepared.executionEpoch,
                receipt.copy(clearedConfigRevision = receipt.clearedConfigRevision + 1),
                nextTime(prepared),
            )
        }
        val committed = NovelGhostwriteJobReducer.completeChapter(
            prepared,
            prepared.ledgerRevision,
            prepared.executionEpoch,
            receipt,
            nextTime(prepared),
        )
        val nextCursor = freshCursorFrom(receipt)
        expectError<NovelGhostwriteJobError.InvalidJob> {
            NovelGhostwriteJobReducer.startNextChapter(
                committed,
                committed.ledgerRevision,
                committed.executionEpoch,
                nextCursor.copy(planID = NovelChapterPlanId.generate(), planDigest = sha256HexOfUtf8("dirty")),
                nextTime(committed),
            )
        }
        var next = NovelGhostwriteJobReducer.startNextChapter(
            committed,
            committed.ledgerRevision,
            committed.executionEpoch,
            nextCursor,
            nextTime(committed),
        )
        next = advance(next, NovelGhostwriteJobPhase.Planning)
        val pendingPlan = pendingPlan(next.currentCursor, 2)
        next = NovelGhostwriteJobReducer.preparePlan(
            next,
            next.ledgerRevision,
            next.executionEpoch,
            NovelChapterPlanId.generate(),
            planDigest(2),
            pendingPlan,
            nextTime(next),
        )
        next = NovelGhostwriteJobReducer.advancePhase(
            next,
            next.ledgerRevision,
            next.executionEpoch,
            NovelGhostwriteJobPhase.GenerationPrepared,
            next.currentCursor.copy(
                pendingPlanUpsert = pendingPlan.copy(
                    upsertedProjectRevision = pendingPlan.expectedProjectRevision + 1,
                    upsertedConfigRevision = pendingPlan.expectedConfigRevision + 1,
                ),
                runID = NovelRunId.generate(),
                attemptNumber = 1,
            ),
            nextTime(next),
        )
        assertEquals(NovelGhostwriteJobPhase.GenerationPrepared, next.phase)
        NovelGhostwriteJobValidator.validate(next)
    }

    @Test
    fun fiftyReceipts_areContiguousUniqueAndRevisionLinked() {
        val receipts = linkedReceipts(50)
        val last = receipts.last()
        val completed = newJob(target = 50).copy(
            status = NovelGhostwriteJobStatus.Completed,
            phase = NovelGhostwriteJobPhase.ChapterCommitted,
            completedChapterCount = 50,
            chapterReceipts = receipts,
            currentCursor = cursorFor(last),
            updatedAt = last.completedAt,
        )
        NovelGhostwriteJobValidator.validate(completed)
        expectError<NovelGhostwriteJobError.InvalidJob> {
            NovelGhostwriteJobValidator.validate(
                completed.copy(
                    chapterReceipts = receipts.toMutableList().apply {
                        this[lastIndex] = last.copy(planID = first().planID)
                    },
                ),
            )
        }
    }

    private fun newJob(target: Int): NovelGhostwriteJobV1 = NovelGhostwriteJobReducer.create(
        id = NovelGhostwriteJobId.generate(),
        projectID = NovelProjectId.generate(),
        branchID = NovelBranchId.generate(),
        targetChapterCount = target,
        initialCursor = baseCursor(),
        now = BASE_TIME,
    )

    private fun validatingJob(): NovelGhostwriteJobV1 {
        var job = newJob(target = 2)
        job = NovelGhostwriteJobReducer.claimLease(
            job,
            job.ledgerRevision,
            job.executionEpoch,
            OWNER,
            LEASE_UNTIL,
            nextTime(job),
        )
        job = NovelGhostwriteJobReducer.advancePhase(
            job,
            job.ledgerRevision,
            job.executionEpoch,
            NovelGhostwriteJobPhase.GenerationPrepared,
            job.currentCursor.copy(
                planID = NovelChapterPlanId.generate(),
                planDigest = planDigest(1),
                runID = NovelRunId.generate(),
                attemptNumber = 1,
            ),
            nextTime(job),
        )
        job = advance(job, NovelGhostwriteJobPhase.Generating)
        job = NovelGhostwriteJobReducer.advancePhase(
            job,
            job.ledgerRevision,
            job.executionEpoch,
            NovelGhostwriteJobPhase.CandidateReady,
            job.currentCursor.copy(
                candidateID = NovelCandidateId.generate(),
                candidateContentSHA256 = sha256HexOfUtf8("candidate-${job.ledgerRevision}"),
            ),
            nextTime(job),
        )
        return advance(job, NovelGhostwriteJobPhase.Validating)
    }

    private fun restartAndValidate(job: NovelGhostwriteJobV1): NovelGhostwriteJobV1 {
        var next = NovelGhostwriteJobReducer.advancePhase(
            job,
            job.ledgerRevision,
            job.executionEpoch,
            NovelGhostwriteJobPhase.GenerationPrepared,
            job.currentCursor.copy(
                runID = NovelRunId.generate(),
                candidateID = null,
                candidateContentSHA256 = null,
                attemptNumber = job.currentCursor.attemptNumber + 1,
            ),
            nextTime(job),
        )
        next = advance(next, NovelGhostwriteJobPhase.Generating)
        next = NovelGhostwriteJobReducer.advancePhase(
            next,
            next.ledgerRevision,
            next.executionEpoch,
            NovelGhostwriteJobPhase.CandidateReady,
            next.currentCursor.copy(
                candidateID = NovelCandidateId.generate(),
                candidateContentSHA256 = sha256HexOfUtf8("candidate-${next.ledgerRevision}"),
            ),
            nextTime(next),
        )
        return advance(next, NovelGhostwriteJobPhase.Validating)
    }

    private fun chapterCommitPreparedJob(target: Int): NovelGhostwriteJobV1 {
        var job = validatingJob().copy(targetChapterCount = target)
        val collect = NovelGhostwritePendingCollectIdentityV1(
            chapterID = NovelChapterId.generate(),
            chapterVersionID = NovelChapterVersionId.generate(),
            collectOperationID = NovelOperationId.generate(),
            checkpointID = NovelCheckpointId.generate(),
            stateSnapshotID = NovelStateSnapshotId.generate(),
            expectedProjectRevision = job.currentCursor.baseProjectRevision + 2,
            expectedHeadRevision = job.currentCursor.baseHeadRevision,
            expectedConfigRevision = job.currentCursor.baseConfigRevision,
        )
        job = NovelGhostwriteJobReducer.advancePhase(
            job,
            job.ledgerRevision,
            job.executionEpoch,
            NovelGhostwriteJobPhase.CollectPrepared,
            job.currentCursor.copy(pendingCollectIdentity = collect),
            nextTime(job),
        )
        val collected = collect.copy(
            collectedProjectRevision = collect.expectedProjectRevision + 1,
            collectedHeadRevision = collect.expectedHeadRevision + 1,
            collectedConfigRevision = collect.expectedConfigRevision,
        )
        val sync = NovelGhostwritePendingSyncIdentityV1(
            syncOperationID = NovelOperationId.generate(),
            checkpointID = NovelCheckpointId.generate(),
            stateSnapshotID = NovelStateSnapshotId.generate(),
            expectedProjectRevision = collected.collectedProjectRevision!!,
            expectedCheckpointID = collected.checkpointID,
            expectedHeadRevision = collected.collectedHeadRevision!!,
            expectedStateSnapshotID = collected.stateSnapshotID,
            expectedConfigRevision = collected.collectedConfigRevision!!,
        )
        job = NovelGhostwriteJobReducer.advancePhase(
            job,
            job.ledgerRevision,
            job.executionEpoch,
            NovelGhostwriteJobPhase.CollectedNeedsSync,
            job.currentCursor.copy(
                pendingCollectIdentity = collected,
                pendingSyncIdentity = sync,
            ),
            nextTime(job),
        )
        job = advance(job, NovelGhostwriteJobPhase.Syncing)
        val synchronized = sync.copy(
            synchronizedProjectRevision = sync.expectedProjectRevision + 1,
            synchronizedHeadRevision = sync.expectedHeadRevision + 1,
            synchronizedConfigRevision = sync.expectedConfigRevision,
        )
        val clear = NovelGhostwritePendingPlanClearV1(
            clearOperationID = NovelOperationId.generate(),
            expectedProjectRevision = synchronized.synchronizedProjectRevision!!,
            expectedConfigRevision = synchronized.synchronizedConfigRevision!!,
        )
        job = NovelGhostwriteJobReducer.preparePlanClear(
            job,
            job.ledgerRevision,
            job.executionEpoch,
            clear,
            job.currentCursor.copy(pendingSyncIdentity = synchronized),
            nextTime(job),
        )
        return NovelGhostwriteJobReducer.advancePhase(
            job,
            job.ledgerRevision,
            job.executionEpoch,
            NovelGhostwriteJobPhase.ChapterCommitPrepared,
            job.currentCursor.copy(
                pendingPlanClear = clear.copy(
                    clearedProjectRevision = clear.expectedProjectRevision + 1,
                    clearedConfigRevision = clear.expectedConfigRevision + 1,
                ),
            ),
            nextTime(job),
        )
    }

    private fun packetFor(
        job: NovelGhostwriteJobV1,
        reason: String,
        summary: String,
    ): NovelGhostwriteCorrectionPacketV1 = NovelGhostwriteCorrectionPacketV1.bounded(
        reasonCode = reason,
        summary = summary,
        sourceCandidateID = job.currentCursor.candidateID,
        planDigest = job.currentCursor.planDigest!!,
    )

    private fun expectQualityResumeRejected(job: NovelGhostwriteJobV1) {
        expectError<NovelGhostwriteJobError.InvalidJob> {
            NovelGhostwriteJobReducer.claimLease(
                job,
                job.ledgerRevision,
                job.executionEpoch,
                "quality-resume",
                LEASE_UNTIL,
                nextTime(job),
            )
        }
    }

    private fun pendingPlan(
        cursor: NovelGhostwriteChapterCursorV1,
        index: Int,
    ): NovelGhostwritePendingPlanUpsertV1 = NovelGhostwritePendingPlanUpsertV1(
        upsertOperationID = NovelOperationId.generate(),
        expectedProjectRevision = cursor.baseProjectRevision,
        expectedConfigRevision = cursor.baseConfigRevision,
        outlinePlacement = "chapter $index",
        goalAndConflict = "goal-$index",
        mustHappen = listOf("beat-$index"),
        mustNotHappen = emptyList(),
        endingHook = "hook-$index",
        visibleFacts = emptyList(),
    )

    private fun planDigest(index: Int): String = sha256HexOfUtf8(
        listOf(
            "chapter $index",
            "goal-$index",
            "beat-$index",
            "",
            "hook-$index",
            "",
        ).joinToString("\n---\n"),
    )

    private fun receiptFor(
        cursor: NovelGhostwriteChapterCursorV1,
        completedAt: Instant,
    ): NovelGhostwriteChapterReceiptV1 {
        val plan = cursor.pendingPlanUpsert
        val collect = cursor.pendingCollectIdentity!!
        val sync = cursor.pendingSyncIdentity!!
        val clear = cursor.pendingPlanClear!!
        return NovelGhostwriteChapterReceiptV1(
            chapterIndex = cursor.chapterIndex,
            baseProjectRevision = cursor.baseProjectRevision,
            baseCheckpointID = cursor.baseCheckpointID,
            baseHeadRevision = cursor.baseHeadRevision,
            baseStateSnapshotID = cursor.baseStateSnapshotID,
            baseConfigRevision = cursor.baseConfigRevision,
            planID = cursor.planID!!,
            planDigest = cursor.planDigest!!,
            planUpsertOperationID = plan?.upsertOperationID,
            planUpsertedProjectRevision = plan?.upsertedProjectRevision,
            planUpsertedConfigRevision = plan?.upsertedConfigRevision,
            runID = cursor.runID!!,
            candidateID = cursor.candidateID!!,
            candidateContentSHA256 = cursor.candidateContentSHA256!!,
            chapterID = collect.chapterID,
            chapterVersionID = collect.chapterVersionID,
            collectOperationID = collect.collectOperationID,
            collectedCheckpointID = collect.checkpointID,
            collectedStateSnapshotID = collect.stateSnapshotID,
            collectExpectedProjectRevision = collect.expectedProjectRevision,
            collectedProjectRevision = collect.collectedProjectRevision!!,
            collectedHeadRevision = collect.collectedHeadRevision!!,
            collectedConfigRevision = collect.collectedConfigRevision!!,
            syncOperationID = sync.syncOperationID,
            synchronizedCheckpointID = sync.checkpointID,
            synchronizedProjectRevision = sync.synchronizedProjectRevision!!,
            synchronizedHeadRevision = sync.synchronizedHeadRevision!!,
            synchronizedStateSnapshotID = sync.stateSnapshotID,
            synchronizedConfigRevision = sync.synchronizedConfigRevision!!,
            clearPlanOperationID = clear.clearOperationID,
            clearedProjectRevision = clear.clearedProjectRevision!!,
            clearedConfigRevision = clear.clearedConfigRevision!!,
            completedAt = completedAt,
        )
    }

    private fun linkedReceipts(count: Int): List<NovelGhostwriteChapterReceiptV1> {
        val receipts = mutableListOf<NovelGhostwriteChapterReceiptV1>()
        repeat(count) { index ->
            val chapterIndex = index + 1
            val previous = receipts.lastOrNull()
            val baseProject = previous?.clearedProjectRevision ?: 10L
            val baseConfig = previous?.clearedConfigRevision ?: 1L
            val planProject = if (chapterIndex == 1) baseProject else baseProject + 1
            val planConfig = if (chapterIndex == 1) baseConfig else baseConfig + 1
            val collectExpected = planProject + 2
            receipts += NovelGhostwriteChapterReceiptV1(
                chapterIndex = chapterIndex,
                baseProjectRevision = baseProject,
                baseCheckpointID = previous?.synchronizedCheckpointID ?: NovelCheckpointId.generate(),
                baseHeadRevision = previous?.synchronizedHeadRevision ?: 0,
                baseStateSnapshotID = previous?.synchronizedStateSnapshotID ?: NovelStateSnapshotId.generate(),
                baseConfigRevision = baseConfig,
                planID = NovelChapterPlanId.generate(),
                planDigest = planDigest(chapterIndex),
                planUpsertOperationID = NovelOperationId.generate().takeIf { chapterIndex > 1 },
                planUpsertedProjectRevision = planProject.takeIf { chapterIndex > 1 },
                planUpsertedConfigRevision = planConfig.takeIf { chapterIndex > 1 },
                runID = NovelRunId.generate(),
                candidateID = NovelCandidateId.generate(),
                candidateContentSHA256 = sha256HexOfUtf8("candidate-$chapterIndex"),
                chapterID = NovelChapterId.generate(),
                chapterVersionID = NovelChapterVersionId.generate(),
                collectOperationID = NovelOperationId.generate(),
                collectedCheckpointID = NovelCheckpointId.generate(),
                collectedStateSnapshotID = NovelStateSnapshotId.generate(),
                collectExpectedProjectRevision = collectExpected,
                collectedProjectRevision = collectExpected + 1,
                collectedHeadRevision = (previous?.synchronizedHeadRevision ?: 0) + 1,
                collectedConfigRevision = planConfig,
                syncOperationID = NovelOperationId.generate(),
                synchronizedCheckpointID = NovelCheckpointId.generate(),
                synchronizedProjectRevision = collectExpected + 2,
                synchronizedHeadRevision = (previous?.synchronizedHeadRevision ?: 0) + 2,
                synchronizedStateSnapshotID = NovelStateSnapshotId.generate(),
                synchronizedConfigRevision = planConfig,
                clearPlanOperationID = NovelOperationId.generate(),
                clearedProjectRevision = collectExpected + 3,
                clearedConfigRevision = planConfig + 1,
                completedAt = BASE_TIME.plusSeconds(chapterIndex.toLong()),
            )
        }
        return receipts
    }

    private fun cursorFor(receipt: NovelGhostwriteChapterReceiptV1): NovelGhostwriteChapterCursorV1 {
        val plan = if (receipt.planUpsertOperationID == null) null else pendingPlan(
            NovelGhostwriteChapterCursorV1(
                chapterIndex = receipt.chapterIndex,
                baseProjectRevision = receipt.baseProjectRevision,
                baseCheckpointID = receipt.baseCheckpointID,
                baseHeadRevision = receipt.baseHeadRevision,
                baseStateSnapshotID = receipt.baseStateSnapshotID,
                baseConfigRevision = receipt.baseConfigRevision,
            ),
            receipt.chapterIndex,
        ).copy(
            upsertOperationID = receipt.planUpsertOperationID,
            upsertedProjectRevision = receipt.planUpsertedProjectRevision,
            upsertedConfigRevision = receipt.planUpsertedConfigRevision,
        )
        val collect = NovelGhostwritePendingCollectIdentityV1(
            chapterID = receipt.chapterID,
            chapterVersionID = receipt.chapterVersionID,
            collectOperationID = receipt.collectOperationID,
            checkpointID = receipt.collectedCheckpointID,
            stateSnapshotID = receipt.collectedStateSnapshotID,
            expectedProjectRevision = receipt.collectExpectedProjectRevision,
            expectedHeadRevision = receipt.baseHeadRevision,
            expectedConfigRevision = receipt.collectedConfigRevision,
            collectedProjectRevision = receipt.collectedProjectRevision,
            collectedHeadRevision = receipt.collectedHeadRevision,
            collectedConfigRevision = receipt.collectedConfigRevision,
        )
        val sync = NovelGhostwritePendingSyncIdentityV1(
            syncOperationID = receipt.syncOperationID,
            checkpointID = receipt.synchronizedCheckpointID,
            stateSnapshotID = receipt.synchronizedStateSnapshotID,
            expectedProjectRevision = receipt.collectedProjectRevision,
            expectedCheckpointID = receipt.collectedCheckpointID,
            expectedHeadRevision = receipt.collectedHeadRevision,
            expectedStateSnapshotID = receipt.collectedStateSnapshotID,
            expectedConfigRevision = receipt.collectedConfigRevision,
            synchronizedProjectRevision = receipt.synchronizedProjectRevision,
            synchronizedHeadRevision = receipt.synchronizedHeadRevision,
            synchronizedConfigRevision = receipt.synchronizedConfigRevision,
        )
        return NovelGhostwriteChapterCursorV1(
            chapterIndex = receipt.chapterIndex,
            baseProjectRevision = receipt.baseProjectRevision,
            baseCheckpointID = receipt.baseCheckpointID,
            baseHeadRevision = receipt.baseHeadRevision,
            baseStateSnapshotID = receipt.baseStateSnapshotID,
            baseConfigRevision = receipt.baseConfigRevision,
            planID = receipt.planID,
            planDigest = receipt.planDigest,
            pendingPlanUpsert = plan,
            runID = receipt.runID,
            candidateID = receipt.candidateID,
            candidateContentSHA256 = receipt.candidateContentSHA256,
            pendingCollectIdentity = collect,
            attemptNumber = 1,
            pendingSyncIdentity = sync,
            pendingPlanClear = NovelGhostwritePendingPlanClearV1(
                clearOperationID = receipt.clearPlanOperationID,
                expectedProjectRevision = receipt.synchronizedProjectRevision,
                expectedConfigRevision = receipt.synchronizedConfigRevision,
                clearedProjectRevision = receipt.clearedProjectRevision,
                clearedConfigRevision = receipt.clearedConfigRevision,
            ),
        )
    }

    private fun freshCursorFrom(receipt: NovelGhostwriteChapterReceiptV1) = NovelGhostwriteChapterCursorV1(
        chapterIndex = receipt.chapterIndex + 1,
        baseProjectRevision = receipt.clearedProjectRevision,
        baseCheckpointID = receipt.synchronizedCheckpointID,
        baseHeadRevision = receipt.synchronizedHeadRevision,
        baseStateSnapshotID = receipt.synchronizedStateSnapshotID,
        baseConfigRevision = receipt.clearedConfigRevision,
    )

    private fun baseCursor() = NovelGhostwriteChapterCursorV1(
        chapterIndex = 1,
        baseProjectRevision = 10,
        baseCheckpointID = NovelCheckpointId.generate(),
        baseHeadRevision = 0,
        baseStateSnapshotID = NovelStateSnapshotId.generate(),
        baseConfigRevision = 1,
    )

    private fun advance(job: NovelGhostwriteJobV1, phase: NovelGhostwriteJobPhase) =
        NovelGhostwriteJobReducer.advancePhase(
            job,
            job.ledgerRevision,
            job.executionEpoch,
            phase,
            job.currentCursor,
            nextTime(job),
        )

    private fun nextTime(job: NovelGhostwriteJobV1): Instant = job.updatedAt.plusSeconds(1)

    private inline fun <reified T : Throwable> expectError(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            assertTrue("Expected ${T::class.java.name}, got ${error::class.java.name}", error is T)
            @Suppress("UNCHECKED_CAST")
            return error as T
        }
        fail("Expected ${T::class.java.name}")
        throw AssertionError("unreachable")
    }

    companion object {
        private val BASE_TIME = Instant.parse("2026-08-09T00:00:00Z")
        private val LEASE_UNTIL = BASE_TIME.plusSeconds(100_000)
        private const val OWNER = "worker-1"
    }
}
