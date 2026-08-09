package app.amber.feature.novel.domain

import app.amber.feature.novel.model.NovelGhostwriteChapterCursorV1
import app.amber.feature.novel.model.NovelGhostwriteChapterReceiptV1
import app.amber.feature.novel.model.NovelGhostwriteCorrectionPacketV1
import app.amber.feature.novel.model.NovelGhostwritePendingCollectIdentityV1
import app.amber.feature.novel.model.NovelGhostwritePendingPlanClearV1
import app.amber.feature.novel.model.NovelGhostwritePendingPlanUpsertV1
import app.amber.feature.novel.model.NovelGhostwritePendingSyncIdentityV1
import app.amber.feature.novel.model.NovelGhostwriteJobId
import app.amber.feature.novel.model.NovelGhostwriteJobPhase
import app.amber.feature.novel.model.NovelGhostwriteJobStatus
import app.amber.feature.novel.model.NovelGhostwriteJobV1
import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelChapterPlanId
import app.amber.feature.novel.model.NovelProjectId
import java.time.Instant

sealed class NovelGhostwriteJobError(message: String) : Exception(message) {
    data class InvalidJob(val issues: List<String>) :
        NovelGhostwriteJobError(issues.joinToString("; "))

    data class UnsupportedSchema(val version: Int) :
        NovelGhostwriteJobError("Unsupported ghostwrite job schema $version")

    data class JobNotFound(val id: NovelGhostwriteJobId) :
        NovelGhostwriteJobError("Ghostwrite job not found: ${id.rawValue}")

    data class JobAlreadyExists(val id: NovelGhostwriteJobId) :
        NovelGhostwriteJobError("Ghostwrite job already exists: ${id.rawValue}")

    data class ActiveJobAlreadyExists(val id: NovelGhostwriteJobId) :
        NovelGhostwriteJobError("An active ghostwrite job already exists: ${id.rawValue}")

    data class DuplicateActiveBinding(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
        val jobIDs: List<NovelGhostwriteJobId>,
    ) : NovelGhostwriteJobError(
        "Multiple active ghostwrite jobs bind ${projectID.rawValue}/${branchID.rawValue}: " +
            jobIDs.joinToString { it.rawValue },
    )

    data class ScanFailed(val details: List<String>) :
        NovelGhostwriteJobError(details.joinToString("; "))

    data class StaleLedgerRevision(val expected: Long, val actual: Long) :
        NovelGhostwriteJobError("Stale ledger revision: expected $expected actual $actual")

    data class StaleExecutionEpoch(val expected: Long, val actual: Long) :
        NovelGhostwriteJobError("Stale execution epoch: expected $expected actual $actual")

    data class LeaseHeld(val ownerWorkID: String) :
        NovelGhostwriteJobError("Ghostwrite job lease is held by $ownerWorkID")

    data class CorruptedJob(val id: NovelGhostwriteJobId, val detail: String) :
        NovelGhostwriteJobError("Corrupted ghostwrite job ${id.rawValue}: $detail")

    data class DegradedReadOnly(val id: NovelGhostwriteJobId) :
        NovelGhostwriteJobError("Ghostwrite job is degraded read-only: ${id.rawValue}")

    data class StorageFailure(val detail: String) : NovelGhostwriteJobError(detail)
}

object NovelGhostwriteJobValidator {
    fun validate(job: NovelGhostwriteJobV1) {
        if (job.schemaVersion != NovelGhostwriteJobV1.CURRENT_SCHEMA_VERSION) {
            throw NovelGhostwriteJobError.UnsupportedSchema(job.schemaVersion)
        }
        val issues = mutableListOf<String>()
        if (job.targetChapterCount !in
            NovelGhostwriteJobV1.MIN_TARGET_CHAPTER_COUNT..NovelGhostwriteJobV1.MAX_TARGET_CHAPTER_COUNT
        ) {
            issues += "Target chapter count must be in 1..50."
        }
        if (job.ledgerRevision < 0) issues += "Ledger revision must be non-negative."
        if (job.executionEpoch < 0) issues += "Execution epoch must be non-negative."
        if (job.updatedAt < job.createdAt) issues += "Job updatedAt precedes createdAt."
        validateLease(job, issues)
        validateReceipts(job, issues)
        validateCursor(job, issues)
        validateStatus(job, issues)
        if (issues.isNotEmpty()) {
            throw NovelGhostwriteJobError.InvalidJob(issues.distinct().sorted())
        }
    }

    fun validateTransition(from: NovelGhostwriteJobV1, to: NovelGhostwriteJobV1) {
        validate(from)
        validate(to)
        val issues = mutableListOf<String>()
        if (to.id != from.id || to.projectID != from.projectID || to.branchID != from.branchID) {
            issues += "A job transition changed durable identity."
        }
        if (to.targetChapterCount != from.targetChapterCount || to.createdAt != from.createdAt) {
            issues += "A job transition changed immutable batch metadata."
        }
        if (to.ledgerRevision != from.ledgerRevision + 1) {
            issues += "A job transition must advance ledger revision exactly once."
        }
        if (to.executionEpoch !in from.executionEpoch..(from.executionEpoch + 1)) {
            issues += "Execution epoch may stay unchanged or advance exactly once."
        }
        if (to.executionEpoch == from.executionEpoch + 1 &&
            (to.status != NovelGhostwriteJobStatus.Running || to.leaseOwnerWorkID == null)
        ) {
            issues += "Advancing execution epoch requires a running leased job."
        }
        if (to.updatedAt < from.updatedAt) {
            issues += "A job transition moved updatedAt backwards."
        }
        if (to.executionEpoch == from.executionEpoch &&
            from.leaseOwnerWorkID != null && to.leaseOwnerWorkID != null &&
            from.leaseOwnerWorkID != to.leaseOwnerWorkID
        ) {
            issues += "Lease ownership may only change when execution epoch advances."
        }
        if (to.status == NovelGhostwriteJobStatus.Running &&
            from.status != NovelGhostwriteJobStatus.Running &&
            to.executionEpoch != from.executionEpoch + 1
        ) {
            issues += "Starting or resuming a job must advance execution epoch."
        }
        if (to.phase != from.phase && to.phase !in phaseTransitions.getValue(from.phase)) {
            issues += "Invalid durable phase transition ${from.phase} -> ${to.phase}."
        }
        if (!isPrefixUnchanged(from.chapterReceipts, to.chapterReceipts)) {
            issues += "Chapter receipts must only append."
        }
        val appendedReceiptCount = to.chapterReceipts.size - from.chapterReceipts.size
        if (appendedReceiptCount !in 0..1) {
            issues += "A job transition may append at most one chapter receipt."
        }
        if (to.completedChapterCount - from.completedChapterCount != appendedReceiptCount) {
            issues += "Completed chapter count must track appended receipts exactly."
        }
        if (from.isTerminal && to != from) {
            issues += "Terminal ghostwrite jobs are immutable."
        }
        if (to.currentCursor.chapterIndex < from.currentCursor.chapterIndex ||
            to.currentCursor.chapterIndex > from.currentCursor.chapterIndex + 1
        ) {
            issues += "Current chapter index may only stay unchanged or advance once."
        }
        validateCursorTransition(from, to, issues)
        if (issues.isNotEmpty()) {
            throw NovelGhostwriteJobError.InvalidJob(issues.distinct().sorted())
        }
    }

    private fun validateCursorTransition(
        from: NovelGhostwriteJobV1,
        to: NovelGhostwriteJobV1,
        issues: MutableList<String>,
    ) {
        val previous = from.currentCursor
        val next = to.currentCursor
        if (next.chapterIndex != previous.chapterIndex) {
            if (from.phase != NovelGhostwriteJobPhase.ChapterCommitted ||
                to.phase != NovelGhostwriteJobPhase.AwaitingPlan
            ) {
                issues += "Only a committed chapter may advance the cursor."
            }
            if (!isFreshChapterCursor(next)) {
                issues += "A new chapter cursor must clear every prior plan/run/side-effect identity."
            }
            return
        }
        if (next.baseProjectRevision != previous.baseProjectRevision ||
            next.baseCheckpointID != previous.baseCheckpointID ||
            next.baseHeadRevision != previous.baseHeadRevision ||
            next.baseStateSnapshotID != previous.baseStateSnapshotID ||
            next.baseConfigRevision != previous.baseConfigRevision
        ) {
            issues += "A chapter transition changed its frozen canonical base."
        }
        if (previous.planID != null &&
            (next.planID != previous.planID || next.planDigest != previous.planDigest)
        ) {
            issues += "A chapter transition changed its accepted plan binding."
        }
        val isCorrectionRestart = from.phase == NovelGhostwriteJobPhase.CorrectionReady &&
            to.phase == NovelGhostwriteJobPhase.GenerationPrepared
        val isUserPausedGenerationRestart = from.phase == NovelGhostwriteJobPhase.Generating &&
            to.phase == NovelGhostwriteJobPhase.GenerationPrepared
        val isGenerationRestart = isCorrectionRestart || isUserPausedGenerationRestart
        if (!isGenerationRestart && previous.runID != null && next.runID != previous.runID) {
            issues += "Prepared run identity changed outside a generation restart."
        }
        if (!isGenerationRestart && previous.candidateID != null &&
            next.candidateID != previous.candidateID
        ) {
            issues += "Candidate identity changed outside a generation restart."
        }
        if (isCorrectionRestart &&
            (next.runID == previous.runID || next.candidateID != null ||
                next.candidateContentSHA256 != null ||
                next.attemptNumber != previous.attemptNumber + 1)
        ) {
            issues += "A correction restart requires the next attempt, a fresh run, and no candidate identity."
        }
        if (isUserPausedGenerationRestart &&
            (next.runID == previous.runID || next.candidateID != null ||
                next.candidateContentSHA256 != null ||
                next.attemptNumber != previous.attemptNumber)
        ) {
            issues += "A paused generation restart requires the same attempt, a fresh run, and no candidate identity."
        }
        validateWriteAheadTransition(previous.pendingPlanUpsert, next.pendingPlanUpsert, "plan upsert", issues)
        validateWriteAheadTransition(
            previous.pendingCollectIdentity,
            next.pendingCollectIdentity,
            "collection",
            issues,
        )
        validateWriteAheadTransition(previous.pendingSyncIdentity, next.pendingSyncIdentity, "sync", issues)
        validateWriteAheadTransition(previous.pendingPlanClear, next.pendingPlanClear, "plan clear", issues)
    }

    internal fun isFreshChapterCursor(cursor: NovelGhostwriteChapterCursorV1): Boolean =
        cursor.planID == null && cursor.planDigest == null && cursor.pendingPlanUpsert == null &&
            cursor.runID == null && cursor.candidateID == null && cursor.candidateContentSHA256 == null &&
            cursor.pendingCollectIdentity == null && cursor.pendingSyncIdentity == null &&
            cursor.pendingPlanClear == null && cursor.attemptNumber == 0 && cursor.infraRetryCount == 0 &&
            cursor.correctionPacket == null && cursor.lastFailureFingerprint == null &&
            cursor.sameFailureCount == 0

    private fun validateWriteAheadTransition(
        previous: NovelGhostwritePendingPlanUpsertV1?,
        next: NovelGhostwritePendingPlanUpsertV1?,
        label: String,
        issues: MutableList<String>,
    ) {
        if (previous == null) return
        if (next == null || previous.copy(
                upsertedProjectRevision = next.upsertedProjectRevision,
                upsertedConfigRevision = next.upsertedConfigRevision,
            ) != next ||
            (previous.upsertedProjectRevision != null && previous != next)
        ) {
            issues += "Prepared $label identity changed or lost its applied result."
        }
    }

    private fun validateWriteAheadTransition(
        previous: NovelGhostwritePendingCollectIdentityV1?,
        next: NovelGhostwritePendingCollectIdentityV1?,
        label: String,
        issues: MutableList<String>,
    ) {
        if (previous == null) return
        if (next == null || previous.copy(
                collectedProjectRevision = next.collectedProjectRevision,
                collectedHeadRevision = next.collectedHeadRevision,
                collectedConfigRevision = next.collectedConfigRevision,
            ) != next ||
            (previous.collectedProjectRevision != null && previous != next)
        ) {
            issues += "Prepared $label identity changed or lost its applied result."
        }
    }

    private fun validateWriteAheadTransition(
        previous: NovelGhostwritePendingSyncIdentityV1?,
        next: NovelGhostwritePendingSyncIdentityV1?,
        label: String,
        issues: MutableList<String>,
    ) {
        if (previous == null) return
        if (next == null || previous.copy(
                synchronizedProjectRevision = next.synchronizedProjectRevision,
                synchronizedHeadRevision = next.synchronizedHeadRevision,
                synchronizedConfigRevision = next.synchronizedConfigRevision,
            ) != next ||
            (previous.synchronizedProjectRevision != null && previous != next)
        ) {
            issues += "Prepared $label identity changed or lost its applied result."
        }
    }

    private fun validateWriteAheadTransition(
        previous: NovelGhostwritePendingPlanClearV1?,
        next: NovelGhostwritePendingPlanClearV1?,
        label: String,
        issues: MutableList<String>,
    ) {
        if (previous == null) return
        if (next == null || previous.copy(
                clearedProjectRevision = next.clearedProjectRevision,
                clearedConfigRevision = next.clearedConfigRevision,
            ) != next ||
            (previous.clearedProjectRevision != null && previous != next)
        ) {
            issues += "Prepared $label identity changed or lost its applied result."
        }
    }

    private fun validateLease(job: NovelGhostwriteJobV1, issues: MutableList<String>) {
        val owner = job.leaseOwnerWorkID
        val until = job.leaseUntil
        if ((owner == null) != (until == null)) {
            issues += "Lease owner and leaseUntil must be present together."
        }
        if (owner != null && (owner.isBlank() || owner.length > NovelGhostwriteJobV1.MAX_LEASE_OWNER_CHARACTERS)) {
            issues += "Lease owner is blank or too long."
        }
        if (until != null && until <= job.updatedAt) {
            issues += "Lease must expire after updatedAt."
        }
        if (job.status == NovelGhostwriteJobStatus.Running && owner == null) {
            issues += "A running job requires a lease."
        }
        if (job.status != NovelGhostwriteJobStatus.Running && owner != null) {
            issues += "Only a running job may retain a lease."
        }
    }

    private fun validateReceipts(job: NovelGhostwriteJobV1, issues: MutableList<String>) {
        val receipts = job.chapterReceipts
        if (job.completedChapterCount != receipts.size) {
            issues += "Completed chapter count must equal receipt count."
        }
        if (receipts.size > job.targetChapterCount ||
            receipts.size > NovelGhostwriteJobV1.MAX_TARGET_CHAPTER_COUNT
        ) {
            issues += "Chapter receipts exceed the batch target."
        }
        if (receipts.map { it.chapterIndex } != (1..receipts.size).toList()) {
            issues += "Chapter receipt indexes must be contiguous and one-based."
        }
        appendDuplicateIssue(receipts.map { it.planID }, "plan", issues)
        appendDuplicateIssue(receipts.map { it.runID }, "run", issues)
        appendDuplicateIssue(receipts.map { it.candidateID }, "candidate", issues)
        appendDuplicateIssue(receipts.map { it.chapterID }, "chapter", issues)
        appendDuplicateIssue(receipts.map { it.chapterVersionID }, "chapter version", issues)
        appendDuplicateIssue(
            receipts.flatMap { listOf(it.collectedCheckpointID, it.synchronizedCheckpointID) },
            "generated checkpoint",
            issues,
        )
        appendDuplicateIssue(
            receipts.flatMap { listOf(it.collectedStateSnapshotID, it.synchronizedStateSnapshotID) },
            "generated state snapshot",
            issues,
        )
        appendDuplicateIssue(
            receipts.flatMap { receipt ->
                listOfNotNull(
                    receipt.planUpsertOperationID,
                    receipt.collectOperationID,
                    receipt.syncOperationID,
                    receipt.clearPlanOperationID,
                )
            },
            "operation",
            issues,
        )
        receipts.forEachIndexed { index, receipt ->
            if (!NovelDocumentValidator.isSHA256(receipt.planDigest)) {
                issues += "Receipt ${receipt.chapterIndex} has an invalid plan digest."
            }
            if (!NovelDocumentValidator.isSHA256(receipt.candidateContentSHA256)) {
                issues += "Receipt ${receipt.chapterIndex} has an invalid candidate content digest."
            }
            if (receipt.baseProjectRevision < 0 || receipt.baseHeadRevision < 0 ||
                receipt.baseConfigRevision < 1
            ) {
                issues += "Receipt ${receipt.chapterIndex} has invalid revisions."
            }
            val planParts = listOf(
                receipt.planUpsertOperationID,
                receipt.planUpsertedProjectRevision,
                receipt.planUpsertedConfigRevision,
            ).count { it != null }
            if (planParts !in setOf(0, 3) || (index == 0 && planParts != 0) ||
                (index > 0 && planParts != 3)
            ) {
                issues += "Receipt ${receipt.chapterIndex} has an invalid plan-upsert proof."
            }
            val planProjectRevision = if (planParts == 3) {
                receipt.planUpsertedProjectRevision!!
            } else {
                receipt.baseProjectRevision
            }
            val planConfigRevision = if (planParts == 3) {
                receipt.planUpsertedConfigRevision!!
            } else {
                receipt.baseConfigRevision
            }
            if (planParts == 3 &&
                (planProjectRevision != receipt.baseProjectRevision + 1 ||
                    planConfigRevision != receipt.baseConfigRevision + 1)
            ) {
                issues += "Receipt ${receipt.chapterIndex} plan upsert did not advance project/config exactly once."
            }
            if (receipt.collectExpectedProjectRevision <= planProjectRevision ||
                receipt.collectedProjectRevision != receipt.collectExpectedProjectRevision + 1 ||
                receipt.collectedHeadRevision != receipt.baseHeadRevision + 1 ||
                receipt.collectedConfigRevision != planConfigRevision ||
                receipt.synchronizedProjectRevision != receipt.collectedProjectRevision + 1 ||
                receipt.synchronizedHeadRevision != receipt.collectedHeadRevision + 1 ||
                receipt.synchronizedConfigRevision != receipt.collectedConfigRevision ||
                receipt.clearedProjectRevision != receipt.synchronizedProjectRevision + 1 ||
                receipt.clearedConfigRevision != receipt.synchronizedConfigRevision + 1 ||
                receipt.collectedCheckpointID == receipt.baseCheckpointID ||
                receipt.collectedStateSnapshotID == receipt.baseStateSnapshotID ||
                receipt.synchronizedCheckpointID == receipt.collectedCheckpointID ||
                receipt.synchronizedStateSnapshotID == receipt.collectedStateSnapshotID
            ) {
                issues += "Receipt ${receipt.chapterIndex} has an invalid plan/collect/sync/clear revision chain."
            }
            if (index > 0) {
                val previous = receipts[index - 1]
                if (receipt.baseProjectRevision != previous.clearedProjectRevision ||
                    receipt.baseCheckpointID != previous.synchronizedCheckpointID ||
                    receipt.baseHeadRevision != previous.synchronizedHeadRevision ||
                    receipt.baseStateSnapshotID != previous.synchronizedStateSnapshotID ||
                    receipt.baseConfigRevision != previous.clearedConfigRevision
                ) {
                    issues += "Receipt ${receipt.chapterIndex} does not continue the prior cleared-plan base."
                }
                if (receipt.completedAt < previous.completedAt) {
                    issues += "Chapter receipt timestamps are not ordered."
                }
            }
        }
    }

    private fun validateCursor(job: NovelGhostwriteJobV1, issues: MutableList<String>) {
        val cursor = job.currentCursor
        val expectedIndex = if (job.phase == NovelGhostwriteJobPhase.ChapterCommitted) {
            job.completedChapterCount
        } else if (job.completedChapterCount >= job.targetChapterCount) {
            job.targetChapterCount
        } else {
            job.completedChapterCount + 1
        }
        if (cursor.chapterIndex != expectedIndex) {
            issues += "Current cursor does not match completed chapter count."
        }
        if (cursor.baseProjectRevision < 0 || cursor.baseHeadRevision < 0 ||
            cursor.baseConfigRevision < 1
        ) {
            issues += "Current cursor has invalid base revisions."
        }
        if ((cursor.planID == null) != (cursor.planDigest == null)) {
            issues += "Plan ID and plan digest must be present together."
        }
        cursor.planDigest?.let {
            if (!NovelDocumentValidator.isSHA256(it)) issues += "Current plan digest is invalid."
        }
        if (cursor.candidateID != null && cursor.runID == null) {
            issues += "A candidate ID requires its owning run ID."
        }
        if (cursor.candidateContentSHA256 != null && cursor.candidateID == null) {
            issues += "A candidate content digest requires a candidate ID."
        }
        cursor.candidateContentSHA256?.let {
            if (!NovelDocumentValidator.isSHA256(it)) issues += "Candidate content digest is invalid."
        }
        if (cursor.attemptNumber !in 0..NovelGhostwriteJobV1.MAX_QUALITY_ATTEMPTS_PER_CHAPTER) {
            issues += "Quality attempt number exceeds its bound."
        }
        if (cursor.infraRetryCount !in 0..NovelGhostwriteJobV1.MAX_INFRA_RETRIES_PER_PHASE) {
            issues += "Infrastructure retry count exceeds its bound."
        }
        validatePendingPlan(job, issues)
        validatePendingCollect(cursor, issues)
        validateCorrection(job.phase, cursor, issues)
        validatePendingSync(cursor, issues)
        validatePendingPlanClear(cursor, issues)
        validatePhaseIdentity(job.phase, cursor, issues)

        job.chapterReceipts.lastOrNull()?.let { previous ->
            if (job.completedChapterCount < job.targetChapterCount &&
                job.phase != NovelGhostwriteJobPhase.ChapterCommitted &&
                (cursor.baseProjectRevision != previous.clearedProjectRevision ||
                    cursor.baseCheckpointID != previous.synchronizedCheckpointID ||
                    cursor.baseHeadRevision != previous.synchronizedHeadRevision ||
                    cursor.baseStateSnapshotID != previous.synchronizedStateSnapshotID ||
                    cursor.baseConfigRevision != previous.clearedConfigRevision)
            ) {
                issues += "Next chapter cursor does not use the last cleared-plan receipt base."
            }
        }
        if (job.phase != NovelGhostwriteJobPhase.ChapterCommitted) {
            validateCurrentIdentityUniqueness(job, issues)
        }
    }

    private fun validateCurrentIdentityUniqueness(
        job: NovelGhostwriteJobV1,
        issues: MutableList<String>,
    ) {
        val receipts = job.chapterReceipts
        val cursor = job.currentCursor
        if (cursor.planID != null && receipts.any { it.planID == cursor.planID }) {
            issues += "Current plan ID was already used by a completed chapter."
        }
        if (cursor.runID != null && receipts.any { it.runID == cursor.runID }) {
            issues += "Current run ID was already used by a completed chapter."
        }
        if (cursor.candidateID != null && receipts.any { it.candidateID == cursor.candidateID }) {
            issues += "Current candidate ID was already used by a completed chapter."
        }
        val collect = cursor.pendingCollectIdentity
        if (collect != null && receipts.any {
                it.chapterID == collect.chapterID || it.chapterVersionID == collect.chapterVersionID ||
                    it.collectedCheckpointID == collect.checkpointID ||
                    it.synchronizedCheckpointID == collect.checkpointID ||
                    it.collectedStateSnapshotID == collect.stateSnapshotID ||
                    it.synchronizedStateSnapshotID == collect.stateSnapshotID
            }
        ) {
            issues += "Current collection reused a completed chapter identity."
        }
        val operationIDs = buildList {
            cursor.pendingPlanUpsert?.upsertOperationID?.let(::add)
            collect?.collectOperationID?.let(::add)
            cursor.pendingSyncIdentity?.syncOperationID?.let(::add)
            cursor.pendingPlanClear?.clearOperationID?.let(::add)
        }
        val receiptOperationIDs = receipts.flatMap {
            listOfNotNull(
                it.planUpsertOperationID,
                it.collectOperationID,
                it.syncOperationID,
                it.clearPlanOperationID,
            )
        }.toSet()
        if (operationIDs.size != operationIDs.toSet().size || operationIDs.any { it in receiptOperationIDs }) {
            issues += "Current chapter reused a durable operation ID."
        }
        val sync = cursor.pendingSyncIdentity
        if (sync != null && receipts.any {
                it.collectedCheckpointID == sync.checkpointID ||
                    it.synchronizedCheckpointID == sync.checkpointID ||
                    it.collectedStateSnapshotID == sync.stateSnapshotID ||
                    it.synchronizedStateSnapshotID == sync.stateSnapshotID
            }
        ) {
            issues += "Current sync reused a completed checkpoint or state identity."
        }
    }

    private fun validatePendingPlan(job: NovelGhostwriteJobV1, issues: MutableList<String>) {
        val cursor = job.currentCursor
        val pending = cursor.pendingPlanUpsert ?: return
        val normalizedLines = listOf(
            pending.mustHappen,
            pending.mustNotHappen,
            pending.visibleFacts,
        ).all { it == app.amber.feature.novel.model.NovelChapterPlanRecord.normalizedLines(it) }
        val digestPayload = listOf(
            pending.outlinePlacement.trim(),
            pending.goalAndConflict.trim(),
            pending.mustHappen.joinToString("\n"),
            pending.mustNotHappen.joinToString("\n"),
            pending.endingHook.trim(),
            pending.visibleFacts.joinToString("\n"),
        ).joinToString("\n---\n")
        if (!normalizedLines || pending.outlinePlacement != pending.outlinePlacement.trim() ||
            pending.goalAndConflict != pending.goalAndConflict.trim() ||
            pending.endingHook != pending.endingHook.trim() ||
            pending.goalAndConflict.isBlank() || pending.mustHappen.isEmpty() ||
            pending.outlinePlacement.length > 500 || pending.goalAndConflict.length > 8_000 ||
            pending.endingHook.length > 4_000 || pending.mustHappen.size > 32 ||
            pending.mustNotHappen.size > 32 || pending.visibleFacts.size > 32 ||
            cursor.planDigest != app.amber.feature.novel.model.NovelChapterPlanRecord.digest(digestPayload)
        ) {
            issues += "Pending plan upsert payload is not normalized or does not match its digest."
        }
        if (pending.expectedProjectRevision != cursor.baseProjectRevision ||
            pending.expectedConfigRevision != cursor.baseConfigRevision
        ) {
            issues += "Pending plan upsert does not start at the frozen canonical base."
        }
        val resultParts = listOf(
            pending.upsertedProjectRevision,
            pending.upsertedConfigRevision,
        ).count { it != null }
        if (resultParts !in setOf(0, 2)) {
            issues += "Plan-upsert result identity must be absent or complete."
        }
        if (resultParts == 2 &&
            (pending.upsertedProjectRevision != pending.expectedProjectRevision + 1 ||
                pending.upsertedConfigRevision != pending.expectedConfigRevision + 1)
        ) {
            issues += "Plan upsert must advance project/config revisions exactly once."
        }
        if (job.completedChapterCount == 0) {
            issues += "The first batch chapter must use the user-confirmed plan, not an automatic upsert."
        }
    }

    private fun validatePendingCollect(
        cursor: NovelGhostwriteChapterCursorV1,
        issues: MutableList<String>,
    ) {
        val pending = cursor.pendingCollectIdentity ?: return
        val planProjectRevision = cursor.pendingPlanUpsert?.upsertedProjectRevision
            ?: cursor.baseProjectRevision
        val planConfigRevision = cursor.pendingPlanUpsert?.upsertedConfigRevision
            ?: cursor.baseConfigRevision
        if (pending.expectedProjectRevision <= planProjectRevision ||
            pending.expectedHeadRevision != cursor.baseHeadRevision ||
            pending.expectedConfigRevision != planConfigRevision ||
            pending.checkpointID == cursor.baseCheckpointID ||
            pending.stateSnapshotID == cursor.baseStateSnapshotID
        ) {
            issues += "Pending collection does not start at the confirmed-plan canonical base."
        }
        val resultParts = listOf(
            pending.collectedProjectRevision,
            pending.collectedHeadRevision,
            pending.collectedConfigRevision,
        ).count { it != null }
        if (resultParts !in setOf(0, 3)) {
            issues += "Collection result identity must be absent or complete."
        }
        if (resultParts == 3 &&
            (pending.collectedProjectRevision != pending.expectedProjectRevision + 1 ||
                pending.collectedHeadRevision != pending.expectedHeadRevision + 1 ||
                pending.collectedConfigRevision != pending.expectedConfigRevision)
        ) {
            issues += "Collection must advance project/head once without changing config."
        }
    }

    private fun validateCorrection(
        phase: NovelGhostwriteJobPhase,
        cursor: NovelGhostwriteChapterCursorV1,
        issues: MutableList<String>,
    ) {
        val packet = cursor.correctionPacket
        val fingerprint = cursor.lastFailureFingerprint
        if ((fingerprint == null) != (cursor.sameFailureCount == 0)) {
            issues += "Failure fingerprint and same-failure count are inconsistent."
        }
        if (cursor.sameFailureCount !in 0..NovelGhostwriteJobV1.SAME_FAILURE_LIMIT) {
            issues += "Same-failure count exceeds its bound."
        }
        if (fingerprint != null && !NovelDocumentValidator.isSHA256(fingerprint)) {
            issues += "Last failure fingerprint is invalid."
        }
        if (packet == null) return
        if (packet.reasonCode.isBlank() ||
            packet.reasonCode.length > NovelGhostwriteCorrectionPacketV1.MAX_REASON_CODE_CHARACTERS ||
            packet.summary.length > NovelGhostwriteCorrectionPacketV1.MAX_SUMMARY_CHARACTERS
        ) {
            issues += "Correction packet text exceeds its bound."
        }
        if (packet.missingMustHappen.size > NovelGhostwriteCorrectionPacketV1.MAX_MISSING_MUST_HAPPEN ||
            packet.forbiddenViolations.size > NovelGhostwriteCorrectionPacketV1.MAX_FORBIDDEN_VIOLATIONS ||
            packet.repetitionBeats.size > NovelGhostwriteCorrectionPacketV1.MAX_REPETITION_BEATS ||
            packet.continuityNotes.size > NovelGhostwriteCorrectionPacketV1.MAX_CONTINUITY_NOTES
        ) {
            issues += "Correction packet lists exceed their bounds."
        }
        val allItems = packet.missingMustHappen + packet.forbiddenViolations +
            packet.repetitionBeats + packet.continuityNotes
        if (allItems.any { it.isBlank() || it.length > NovelGhostwriteCorrectionPacketV1.MAX_ITEM_CHARACTERS }) {
            issues += "Correction packet contains a blank or oversized item."
        }
        if (!NovelDocumentValidator.isSHA256(packet.planDigest) ||
            !NovelDocumentValidator.isSHA256(packet.fingerprint)
        ) {
            issues += "Correction packet digest or fingerprint is invalid."
        }
        if (packet.planDigest != cursor.planDigest || packet.fingerprint != fingerprint) {
            issues += "Correction packet is not bound to the current plan and failure fingerprint."
        }
        if (packet.sourceCandidateID == null) {
            issues += "Correction packet is not bound to a source candidate."
        } else if (phase == NovelGhostwriteJobPhase.CorrectionReady &&
            packet.sourceCandidateID != cursor.candidateID
        ) {
            issues += "Correction-ready packet is not bound to the rejected candidate."
        } else if (phase != NovelGhostwriteJobPhase.CorrectionReady &&
            packet.sourceCandidateID == cursor.candidateID
        ) {
            issues += "A correction packet cannot describe the active retry candidate."
        }
        val normalized = runCatching {
            NovelGhostwriteCorrectionPacketV1.bounded(
                reasonCode = packet.reasonCode,
                summary = packet.summary,
                missingMustHappen = packet.missingMustHappen,
                forbiddenViolations = packet.forbiddenViolations,
                repetitionBeats = packet.repetitionBeats,
                continuityNotes = packet.continuityNotes,
                sourceCandidateID = packet.sourceCandidateID,
                planDigest = packet.planDigest,
            )
        }.getOrNull()
        if (normalized != packet) {
            issues += "Correction packet is not normalized or its fingerprint was altered."
        }
    }

    private fun validatePendingSync(
        cursor: NovelGhostwriteChapterCursorV1,
        issues: MutableList<String>,
    ) {
        val pending = cursor.pendingSyncIdentity ?: return
        val collected = cursor.pendingCollectIdentity
        if (collected?.collectedProjectRevision == null ||
            collected.collectedHeadRevision == null ||
            collected.collectedConfigRevision == null ||
            pending.expectedProjectRevision != collected.collectedProjectRevision ||
            pending.expectedCheckpointID != collected.checkpointID ||
            pending.expectedHeadRevision != collected.collectedHeadRevision ||
            pending.expectedStateSnapshotID != collected.stateSnapshotID ||
            pending.expectedConfigRevision != collected.collectedConfigRevision
        ) {
            issues += "Pending sync identity does not start at the exact collected checkpoint."
        }
        val resultParts = listOf(
            pending.synchronizedProjectRevision,
            pending.synchronizedHeadRevision,
            pending.synchronizedConfigRevision,
        ).count { it != null }
        if (resultParts !in setOf(0, 3)) {
            issues += "Synchronization result identity must be absent or complete."
        }
        if (resultParts == 3 &&
            (pending.synchronizedProjectRevision != pending.expectedProjectRevision + 1 ||
                pending.synchronizedHeadRevision != pending.expectedHeadRevision + 1 ||
                pending.synchronizedConfigRevision != pending.expectedConfigRevision ||
                pending.checkpointID == pending.expectedCheckpointID ||
                pending.stateSnapshotID == pending.expectedStateSnapshotID)
        ) {
            issues += "Sync must advance project/head once without changing config or reusing IDs."
        }
    }

    private fun validatePendingPlanClear(
        cursor: NovelGhostwriteChapterCursorV1,
        issues: MutableList<String>,
    ) {
        val pending = cursor.pendingPlanClear ?: return
        val synchronized = cursor.pendingSyncIdentity
        if (synchronized?.synchronizedProjectRevision == null ||
            synchronized.synchronizedConfigRevision == null ||
            pending.expectedProjectRevision != synchronized.synchronizedProjectRevision ||
            pending.expectedConfigRevision != synchronized.synchronizedConfigRevision
        ) {
            issues += "Pending plan clear does not start at the exact synchronized project/config revision."
        }
        val resultParts = listOf(
            pending.clearedProjectRevision,
            pending.clearedConfigRevision,
        ).count { it != null }
        if (resultParts !in setOf(0, 2)) {
            issues += "Plan-clear result identity must be absent or complete."
        }
        if (resultParts == 2 &&
            (pending.clearedProjectRevision != pending.expectedProjectRevision + 1 ||
                pending.clearedConfigRevision != pending.expectedConfigRevision + 1)
        ) {
            issues += "Plan clear must advance project/config revisions exactly once."
        }
    }

    private fun validatePhaseIdentity(
        phase: NovelGhostwriteJobPhase,
        cursor: NovelGhostwriteChapterCursorV1,
        issues: MutableList<String>,
    ) {
        if (phase == NovelGhostwriteJobPhase.PlanPrepared) {
            val pending = cursor.pendingPlanUpsert
            if (cursor.planID == null || cursor.planDigest == null || pending == null ||
                pending.upsertedProjectRevision != null || cursor.runID != null ||
                cursor.attemptNumber != 0
            ) {
                issues += "PlanPrepared requires a write-ahead plan payload with no applied result or run."
            }
        }
        val requiresGeneration = phase !in setOf(
            NovelGhostwriteJobPhase.AwaitingPlan,
            NovelGhostwriteJobPhase.Planning,
            NovelGhostwriteJobPhase.PlanPrepared,
            NovelGhostwriteJobPhase.ChapterCommitted,
        )
        if (requiresGeneration &&
            (cursor.planID == null || cursor.runID == null || cursor.attemptNumber < 1)
        ) {
            issues += "Phase $phase requires prepared plan, run, and attempt identity."
        }
        if (requiresGeneration && cursor.chapterIndex > 1 &&
            (cursor.pendingPlanUpsert?.upsertedProjectRevision == null ||
                cursor.pendingPlanUpsert.upsertedConfigRevision == null)
        ) {
            issues += "Automatic chapters require a completely reconciled plan upsert before generation."
        }
        val requiresCandidateContent = phase in setOf(
            NovelGhostwriteJobPhase.CandidateReady,
            NovelGhostwriteJobPhase.Validating,
            NovelGhostwriteJobPhase.CorrectionReady,
            NovelGhostwriteJobPhase.CollectPrepared,
            NovelGhostwriteJobPhase.CollectedNeedsSync,
            NovelGhostwriteJobPhase.Syncing,
            NovelGhostwriteJobPhase.ClearPlanPrepared,
            NovelGhostwriteJobPhase.ChapterCommitPrepared,
            NovelGhostwriteJobPhase.ChapterCommitted,
        )
        if (requiresCandidateContent &&
            (cursor.candidateID == null || cursor.candidateContentSHA256 == null)
        ) {
            issues += "Phase $phase requires exact candidate identity and content digest."
        }
        val requiresCollection = phase in setOf(
            NovelGhostwriteJobPhase.CollectPrepared,
            NovelGhostwriteJobPhase.CollectedNeedsSync,
            NovelGhostwriteJobPhase.Syncing,
            NovelGhostwriteJobPhase.ClearPlanPrepared,
            NovelGhostwriteJobPhase.ChapterCommitPrepared,
            NovelGhostwriteJobPhase.ChapterCommitted,
        )
        if (requiresCollection && cursor.pendingCollectIdentity == null) {
            issues += "Phase $phase requires prepared collection identity."
        }
        if (phase == NovelGhostwriteJobPhase.CollectPrepared &&
            cursor.pendingCollectIdentity?.collectedProjectRevision != null
        ) {
            issues += "CollectPrepared cannot already contain a collection result."
        }
        val requiresPendingSync = phase in setOf(
            NovelGhostwriteJobPhase.CollectedNeedsSync,
            NovelGhostwriteJobPhase.Syncing,
            NovelGhostwriteJobPhase.ClearPlanPrepared,
            NovelGhostwriteJobPhase.ChapterCommitPrepared,
            NovelGhostwriteJobPhase.ChapterCommitted,
        )
        if (requiresPendingSync && cursor.pendingSyncIdentity == null) {
            issues += "Phase $phase requires exact pending sync identity."
        }
        if (phase in setOf(
                NovelGhostwriteJobPhase.ClearPlanPrepared,
                NovelGhostwriteJobPhase.ChapterCommitPrepared,
                NovelGhostwriteJobPhase.ChapterCommitted,
            )
        ) {
            val sync = cursor.pendingSyncIdentity
            if (sync?.synchronizedProjectRevision == null ||
                sync.synchronizedHeadRevision == null ||
                sync.synchronizedConfigRevision == null
            ) {
                issues += "Phase $phase requires a complete synchronization result."
            }
        }
        if (phase == NovelGhostwriteJobPhase.ClearPlanPrepared &&
            (cursor.pendingPlanClear == null ||
                cursor.pendingPlanClear.clearedProjectRevision != null)
        ) {
            issues += "ClearPlanPrepared requires a write-ahead clear with no applied result."
        }
        if (phase in setOf(
                NovelGhostwriteJobPhase.ChapterCommitPrepared,
                NovelGhostwriteJobPhase.ChapterCommitted,
            ) &&
            (cursor.pendingPlanClear?.clearedProjectRevision == null ||
                cursor.pendingPlanClear.clearedConfigRevision == null)
        ) {
            issues += "Phase $phase requires a complete plan-clear result."
        }
    }

    private fun validateStatus(job: NovelGhostwriteJobV1, issues: MutableList<String>) {
        val reason = job.statusReasonCode
        if (reason != null &&
            (reason.isBlank() || reason.length > NovelGhostwriteJobV1.MAX_STATUS_REASON_CODE_CHARACTERS)
        ) {
            issues += "Status reason code is blank or too long."
        }
        if (job.status in setOf(
                NovelGhostwriteJobStatus.Paused,
                NovelGhostwriteJobStatus.Failed,
                NovelGhostwriteJobStatus.Cancelled,
            ) && reason == null
        ) {
            issues += "Paused, failed, and cancelled jobs require a reason code."
        }
        if (job.status in setOf(
                NovelGhostwriteJobStatus.Pending,
                NovelGhostwriteJobStatus.Running,
                NovelGhostwriteJobStatus.Completed,
            ) && reason != null
        ) {
            issues += "Pending, running, and completed jobs cannot retain a reason code."
        }
        if (job.completedChapterCount == job.targetChapterCount &&
            job.status != NovelGhostwriteJobStatus.Completed
        ) {
            issues += "A fully receipted batch must be completed."
        }
        if (job.status == NovelGhostwriteJobStatus.Completed &&
            (job.completedChapterCount != job.targetChapterCount ||
                job.phase != NovelGhostwriteJobPhase.ChapterCommitted)
        ) {
            issues += "A completed job requires all receipts and a committed final chapter."
        }
    }

    private fun appendDuplicateIssue(values: List<Any>, label: String, issues: MutableList<String>) {
        if (values.toSet().size != values.size) issues += "Duplicate $label IDs in chapter receipts."
    }

    private fun <T> isPrefixUnchanged(previous: List<T>, next: List<T>): Boolean =
        next.size >= previous.size && previous.indices.all { previous[it] == next[it] }

    private val phaseTransitions = mapOf(
        NovelGhostwriteJobPhase.AwaitingPlan to setOf(
            NovelGhostwriteJobPhase.Planning,
            NovelGhostwriteJobPhase.GenerationPrepared,
        ),
        NovelGhostwriteJobPhase.Planning to setOf(NovelGhostwriteJobPhase.PlanPrepared),
        NovelGhostwriteJobPhase.PlanPrepared to setOf(NovelGhostwriteJobPhase.GenerationPrepared),
        NovelGhostwriteJobPhase.GenerationPrepared to setOf(NovelGhostwriteJobPhase.Generating),
        NovelGhostwriteJobPhase.Generating to setOf(
            NovelGhostwriteJobPhase.GenerationPrepared,
            NovelGhostwriteJobPhase.CandidateReady,
        ),
        NovelGhostwriteJobPhase.CandidateReady to setOf(NovelGhostwriteJobPhase.Validating),
        NovelGhostwriteJobPhase.Validating to setOf(
            NovelGhostwriteJobPhase.CorrectionReady,
            NovelGhostwriteJobPhase.CollectPrepared,
        ),
        NovelGhostwriteJobPhase.CorrectionReady to setOf(NovelGhostwriteJobPhase.GenerationPrepared),
        NovelGhostwriteJobPhase.CollectPrepared to setOf(NovelGhostwriteJobPhase.CollectedNeedsSync),
        NovelGhostwriteJobPhase.CollectedNeedsSync to setOf(NovelGhostwriteJobPhase.Syncing),
        NovelGhostwriteJobPhase.Syncing to setOf(NovelGhostwriteJobPhase.ClearPlanPrepared),
        NovelGhostwriteJobPhase.ClearPlanPrepared to setOf(NovelGhostwriteJobPhase.ChapterCommitPrepared),
        NovelGhostwriteJobPhase.ChapterCommitPrepared to setOf(NovelGhostwriteJobPhase.ChapterCommitted),
        NovelGhostwriteJobPhase.ChapterCommitted to setOf(NovelGhostwriteJobPhase.AwaitingPlan),
    )
}

object NovelGhostwriteJobReducer {
    fun create(
        id: NovelGhostwriteJobId,
        projectID: app.amber.feature.novel.model.NovelProjectId,
        branchID: app.amber.feature.novel.model.NovelBranchId,
        targetChapterCount: Int,
        initialCursor: NovelGhostwriteChapterCursorV1,
        now: Instant = Instant.now(),
    ): NovelGhostwriteJobV1 {
        val job = NovelGhostwriteJobV1(
            id = id,
            projectID = projectID,
            branchID = branchID,
            targetChapterCount = targetChapterCount,
            currentCursor = initialCursor,
            createdAt = now,
            updatedAt = now,
        )
        NovelGhostwriteJobValidator.validate(job)
        return job
    }

    fun claimLease(
        job: NovelGhostwriteJobV1,
        expectedLedgerRevision: Long,
        expectedExecutionEpoch: Long,
        ownerWorkID: String,
        leaseUntil: Instant,
        now: Instant = Instant.now(),
    ): NovelGhostwriteJobV1 {
        requireExpected(job, expectedLedgerRevision, expectedExecutionEpoch)
        requireNotTerminal(job)
        if (job.status == NovelGhostwriteJobStatus.Paused &&
            job.statusReasonCode == "quality_circuit_breaker"
        ) {
            throw NovelGhostwriteJobError.InvalidJob(
                listOf("Quality circuit-breaker pauses require an explicit plan or quality reset."),
            )
        }
        val owner = ownerWorkID.trim()
        if (owner.isEmpty() || owner.length > NovelGhostwriteJobV1.MAX_LEASE_OWNER_CHARACTERS) {
            throw NovelGhostwriteJobError.InvalidJob(listOf("Lease owner is blank or too long."))
        }
        if (leaseUntil <= now) {
            throw NovelGhostwriteJobError.InvalidJob(listOf("Lease must expire in the future."))
        }
        if (job.leaseOwnerWorkID != null && job.leaseUntil != null && job.leaseUntil > now &&
            job.leaseOwnerWorkID != owner
        ) {
            throw NovelGhostwriteJobError.LeaseHeld(job.leaseOwnerWorkID)
        }
        return validatedTransition(
            job,
            job.copy(
                status = NovelGhostwriteJobStatus.Running,
                ledgerRevision = job.ledgerRevision + 1,
                executionEpoch = job.executionEpoch + 1,
                leaseOwnerWorkID = owner,
                leaseUntil = leaseUntil,
                currentCursor = if (job.status == NovelGhostwriteJobStatus.Paused) {
                    job.currentCursor.copy(infraRetryCount = 0)
                } else {
                    job.currentCursor
                },
                statusReasonCode = null,
                updatedAt = now,
            ),
        )
    }

    fun renewLease(
        job: NovelGhostwriteJobV1,
        expectedLedgerRevision: Long,
        expectedExecutionEpoch: Long,
        ownerWorkID: String,
        leaseUntil: Instant,
        now: Instant = Instant.now(),
    ): NovelGhostwriteJobV1 {
        requireExpected(job, expectedLedgerRevision, expectedExecutionEpoch)
        requireRunning(job)
        if (job.leaseOwnerWorkID != ownerWorkID) {
            throw NovelGhostwriteJobError.LeaseHeld(job.leaseOwnerWorkID.orEmpty())
        }
        if (leaseUntil <= now) {
            throw NovelGhostwriteJobError.InvalidJob(listOf("Lease must expire in the future."))
        }
        return validatedTransition(
            job,
            job.copy(
                ledgerRevision = job.ledgerRevision + 1,
                leaseUntil = leaseUntil,
                updatedAt = now,
            ),
        )
    }

    fun advancePhase(
        job: NovelGhostwriteJobV1,
        expectedLedgerRevision: Long,
        expectedExecutionEpoch: Long,
        phase: NovelGhostwriteJobPhase,
        cursor: NovelGhostwriteChapterCursorV1 = job.currentCursor,
        now: Instant = Instant.now(),
    ): NovelGhostwriteJobV1 {
        requireExpected(job, expectedLedgerRevision, expectedExecutionEpoch)
        requireRunning(job)
        if (phase !in allowedNextPhases.getValue(job.phase)) {
            throw NovelGhostwriteJobError.InvalidJob(
                listOf("Invalid ghostwrite phase transition ${job.phase} -> $phase."),
            )
        }
        return validatedTransition(
            job,
            job.copy(
                phase = phase,
                ledgerRevision = job.ledgerRevision + 1,
                currentCursor = cursor.copy(infraRetryCount = 0),
                updatedAt = now,
            ),
        )
    }

    /** Restart only a generation explicitly interrupted by the user's pause action. */
    fun restartUserPausedGeneration(
        job: NovelGhostwriteJobV1,
        expectedLedgerRevision: Long,
        expectedExecutionEpoch: Long,
        newRunID: app.amber.feature.novel.model.NovelRunId,
        now: Instant = Instant.now(),
    ): NovelGhostwriteJobV1 {
        requireExpected(job, expectedLedgerRevision, expectedExecutionEpoch)
        requireRunning(job)
        if (job.phase != NovelGhostwriteJobPhase.Generating || job.currentCursor.runID == newRunID) {
            throw NovelGhostwriteJobError.InvalidJob(
                listOf("Only an interrupted generating phase can restart with a fresh run."),
            )
        }
        return validatedTransition(
            job,
            job.copy(
                phase = NovelGhostwriteJobPhase.GenerationPrepared,
                ledgerRevision = job.ledgerRevision + 1,
                currentCursor = job.currentCursor.copy(
                    runID = newRunID,
                    candidateID = null,
                    candidateContentSHA256 = null,
                    infraRetryCount = 0,
                ),
                updatedAt = now,
            ),
        )
    }

    fun preparePlan(
        job: NovelGhostwriteJobV1,
        expectedLedgerRevision: Long,
        expectedExecutionEpoch: Long,
        planID: NovelChapterPlanId,
        planDigest: String,
        pendingPlanUpsert: NovelGhostwritePendingPlanUpsertV1,
        now: Instant = Instant.now(),
    ): NovelGhostwriteJobV1 = advancePhase(
        job = job,
        expectedLedgerRevision = expectedLedgerRevision,
        expectedExecutionEpoch = expectedExecutionEpoch,
        phase = NovelGhostwriteJobPhase.PlanPrepared,
        cursor = job.currentCursor.copy(
            planID = planID,
            planDigest = planDigest,
            pendingPlanUpsert = pendingPlanUpsert,
        ),
        now = now,
    )

    fun preparePlanClear(
        job: NovelGhostwriteJobV1,
        expectedLedgerRevision: Long,
        expectedExecutionEpoch: Long,
        pendingPlanClear: NovelGhostwritePendingPlanClearV1,
        synchronizedCursor: NovelGhostwriteChapterCursorV1 = job.currentCursor,
        now: Instant = Instant.now(),
    ): NovelGhostwriteJobV1 = advancePhase(
        job = job,
        expectedLedgerRevision = expectedLedgerRevision,
        expectedExecutionEpoch = expectedExecutionEpoch,
        phase = NovelGhostwriteJobPhase.ClearPlanPrepared,
        cursor = synchronizedCursor.copy(pendingPlanClear = pendingPlanClear),
        now = now,
    )

    fun recordQualityFailure(
        job: NovelGhostwriteJobV1,
        expectedLedgerRevision: Long,
        expectedExecutionEpoch: Long,
        packet: NovelGhostwriteCorrectionPacketV1,
        now: Instant = Instant.now(),
    ): NovelGhostwriteJobV1 {
        requireExpected(job, expectedLedgerRevision, expectedExecutionEpoch)
        requireRunning(job)
        if (job.phase != NovelGhostwriteJobPhase.Validating ||
            packet.planDigest != job.currentCursor.planDigest ||
            packet.sourceCandidateID != job.currentCursor.candidateID
        ) {
            throw NovelGhostwriteJobError.InvalidJob(
                listOf("Quality failure is not bound to the validating cursor."),
            )
        }
        val sameFailureCount = if (job.currentCursor.lastFailureFingerprint == packet.fingerprint) {
            job.currentCursor.sameFailureCount + 1
        } else {
            1
        }
        val exhausted = job.currentCursor.attemptNumber >=
            NovelGhostwriteJobV1.MAX_QUALITY_ATTEMPTS_PER_CHAPTER ||
            sameFailureCount >= NovelGhostwriteJobV1.SAME_FAILURE_LIMIT
        val cursor = job.currentCursor.copy(
            correctionPacket = packet,
            lastFailureFingerprint = packet.fingerprint,
            sameFailureCount = sameFailureCount,
        )
        return validatedTransition(
            job,
            job.copy(
                status = if (exhausted) {
                    NovelGhostwriteJobStatus.Paused
                } else {
                    NovelGhostwriteJobStatus.Running
                },
                phase = NovelGhostwriteJobPhase.CorrectionReady,
                ledgerRevision = job.ledgerRevision + 1,
                leaseOwnerWorkID = if (exhausted) null else job.leaseOwnerWorkID,
                leaseUntil = if (exhausted) null else job.leaseUntil,
                currentCursor = cursor,
                statusReasonCode = if (exhausted) "quality_circuit_breaker" else null,
                updatedAt = now,
            ),
        )
    }

    fun recordInfraRetry(
        job: NovelGhostwriteJobV1,
        expectedLedgerRevision: Long,
        expectedExecutionEpoch: Long,
        now: Instant = Instant.now(),
    ): NovelGhostwriteJobV1 {
        requireExpected(job, expectedLedgerRevision, expectedExecutionEpoch)
        requireRunning(job)
        val retryCount = job.currentCursor.infraRetryCount + 1
        if (retryCount > NovelGhostwriteJobV1.MAX_INFRA_RETRIES_PER_PHASE) {
            throw NovelGhostwriteJobError.InvalidJob(listOf("Infrastructure retry budget is exhausted."))
        }
        val exhausted = retryCount >= NovelGhostwriteJobV1.MAX_INFRA_RETRIES_PER_PHASE
        return validatedTransition(
            job,
            job.copy(
                status = if (exhausted) {
                    NovelGhostwriteJobStatus.Paused
                } else {
                    NovelGhostwriteJobStatus.Running
                },
                ledgerRevision = job.ledgerRevision + 1,
                leaseOwnerWorkID = if (exhausted) null else job.leaseOwnerWorkID,
                leaseUntil = if (exhausted) null else job.leaseUntil,
                currentCursor = job.currentCursor.copy(infraRetryCount = retryCount),
                statusReasonCode = if (exhausted) "infra_retry_exhausted" else null,
                updatedAt = now,
            ),
        )
    }

    fun completeChapter(
        job: NovelGhostwriteJobV1,
        expectedLedgerRevision: Long,
        expectedExecutionEpoch: Long,
        receipt: NovelGhostwriteChapterReceiptV1,
        now: Instant = Instant.now(),
    ): NovelGhostwriteJobV1 {
        requireExpected(job, expectedLedgerRevision, expectedExecutionEpoch)
        requireRunning(job)
        if (job.phase != NovelGhostwriteJobPhase.ChapterCommitPrepared) {
            throw NovelGhostwriteJobError.InvalidJob(listOf("Chapter is not ready to commit."))
        }
        requireReceiptMatchesCursor(receipt, job.currentCursor)
        if (job.completedChapterCount >= job.targetChapterCount) {
            throw NovelGhostwriteJobError.InvalidJob(listOf("Batch target is already complete."))
        }
        val receipts = job.chapterReceipts + receipt
        val completed = receipts.size == job.targetChapterCount
        return validatedTransition(
            job,
            job.copy(
                status = if (completed) {
                    NovelGhostwriteJobStatus.Completed
                } else {
                    NovelGhostwriteJobStatus.Running
                },
                phase = NovelGhostwriteJobPhase.ChapterCommitted,
                ledgerRevision = job.ledgerRevision + 1,
                leaseOwnerWorkID = if (completed) null else job.leaseOwnerWorkID,
                leaseUntil = if (completed) null else job.leaseUntil,
                completedChapterCount = receipts.size,
                chapterReceipts = receipts,
                currentCursor = job.currentCursor.copy(
                    infraRetryCount = 0,
                    correctionPacket = null,
                    lastFailureFingerprint = null,
                    sameFailureCount = 0,
                ),
                updatedAt = now,
            ),
        )
    }

    fun startNextChapter(
        job: NovelGhostwriteJobV1,
        expectedLedgerRevision: Long,
        expectedExecutionEpoch: Long,
        cursor: NovelGhostwriteChapterCursorV1,
        now: Instant = Instant.now(),
    ): NovelGhostwriteJobV1 {
        requireExpected(job, expectedLedgerRevision, expectedExecutionEpoch)
        requireRunning(job)
        val receipt = job.chapterReceipts.lastOrNull()
            ?: throw NovelGhostwriteJobError.InvalidJob(listOf("No committed chapter receipt exists."))
        if (job.phase != NovelGhostwriteJobPhase.ChapterCommitted ||
            job.completedChapterCount >= job.targetChapterCount ||
            cursor.chapterIndex != job.completedChapterCount + 1 ||
            cursor.baseProjectRevision != receipt.clearedProjectRevision ||
            cursor.baseCheckpointID != receipt.synchronizedCheckpointID ||
            cursor.baseHeadRevision != receipt.synchronizedHeadRevision ||
            cursor.baseStateSnapshotID != receipt.synchronizedStateSnapshotID ||
            cursor.baseConfigRevision != receipt.clearedConfigRevision ||
            !NovelGhostwriteJobValidator.isFreshChapterCursor(cursor)
        ) {
            throw NovelGhostwriteJobError.InvalidJob(
                listOf("Next chapter must be clean and start from the exact cleared-plan receipt."),
            )
        }
        return validatedTransition(
            job,
            job.copy(
                phase = NovelGhostwriteJobPhase.AwaitingPlan,
                ledgerRevision = job.ledgerRevision + 1,
                currentCursor = cursor,
                updatedAt = now,
            ),
        )
    }

    fun pause(
        job: NovelGhostwriteJobV1,
        expectedLedgerRevision: Long,
        expectedExecutionEpoch: Long,
        reasonCode: String,
        now: Instant = Instant.now(),
    ): NovelGhostwriteJobV1 = stop(
        job,
        expectedLedgerRevision,
        expectedExecutionEpoch,
        NovelGhostwriteJobStatus.Paused,
        reasonCode,
        now,
    )

    fun fail(
        job: NovelGhostwriteJobV1,
        expectedLedgerRevision: Long,
        expectedExecutionEpoch: Long,
        reasonCode: String,
        now: Instant = Instant.now(),
    ): NovelGhostwriteJobV1 = stop(
        job,
        expectedLedgerRevision,
        expectedExecutionEpoch,
        NovelGhostwriteJobStatus.Failed,
        reasonCode,
        now,
    )

    fun cancel(
        job: NovelGhostwriteJobV1,
        expectedLedgerRevision: Long,
        expectedExecutionEpoch: Long,
        reasonCode: String = "cancelled",
        now: Instant = Instant.now(),
    ): NovelGhostwriteJobV1 = stop(
        job,
        expectedLedgerRevision,
        expectedExecutionEpoch,
        NovelGhostwriteJobStatus.Cancelled,
        reasonCode,
        now,
    )

    private fun stop(
        job: NovelGhostwriteJobV1,
        expectedLedgerRevision: Long,
        expectedExecutionEpoch: Long,
        status: NovelGhostwriteJobStatus,
        reasonCode: String,
        now: Instant,
    ): NovelGhostwriteJobV1 {
        requireExpected(job, expectedLedgerRevision, expectedExecutionEpoch)
        requireNotTerminal(job)
        val reason = reasonCode.trim().take(NovelGhostwriteJobV1.MAX_STATUS_REASON_CODE_CHARACTERS)
        if (reason.isEmpty()) {
            throw NovelGhostwriteJobError.InvalidJob(listOf("Status reason code is required."))
        }
        return validatedTransition(
            job,
            job.copy(
                status = status,
                ledgerRevision = job.ledgerRevision + 1,
                leaseOwnerWorkID = null,
                leaseUntil = null,
                statusReasonCode = reason,
                updatedAt = now,
            ),
        )
    }

    private fun requireReceiptMatchesCursor(
        receipt: NovelGhostwriteChapterReceiptV1,
        cursor: NovelGhostwriteChapterCursorV1,
    ) {
        val plan = cursor.pendingPlanUpsert
        val collect = cursor.pendingCollectIdentity
        val sync = cursor.pendingSyncIdentity
        val clear = cursor.pendingPlanClear
        val matches = receipt.chapterIndex == cursor.chapterIndex &&
            receipt.baseProjectRevision == cursor.baseProjectRevision &&
            receipt.baseCheckpointID == cursor.baseCheckpointID &&
            receipt.baseHeadRevision == cursor.baseHeadRevision &&
            receipt.baseStateSnapshotID == cursor.baseStateSnapshotID &&
            receipt.baseConfigRevision == cursor.baseConfigRevision &&
            receipt.planID == cursor.planID && receipt.planDigest == cursor.planDigest &&
            receipt.planUpsertOperationID == plan?.upsertOperationID &&
            receipt.planUpsertedProjectRevision == plan?.upsertedProjectRevision &&
            receipt.planUpsertedConfigRevision == plan?.upsertedConfigRevision &&
            receipt.runID == cursor.runID && receipt.candidateID == cursor.candidateID &&
            receipt.candidateContentSHA256 == cursor.candidateContentSHA256 &&
            collect != null && receipt.chapterID == collect.chapterID &&
            receipt.chapterVersionID == collect.chapterVersionID &&
            receipt.collectOperationID == collect.collectOperationID &&
            receipt.collectedCheckpointID == collect.checkpointID &&
            receipt.collectedStateSnapshotID == collect.stateSnapshotID &&
            receipt.collectExpectedProjectRevision == collect.expectedProjectRevision &&
            receipt.collectedProjectRevision == collect.collectedProjectRevision &&
            receipt.collectedHeadRevision == collect.collectedHeadRevision &&
            receipt.collectedConfigRevision == collect.collectedConfigRevision &&
            sync != null && receipt.syncOperationID == sync.syncOperationID &&
            receipt.synchronizedCheckpointID == sync.checkpointID &&
            receipt.synchronizedProjectRevision == sync.synchronizedProjectRevision &&
            receipt.synchronizedHeadRevision == sync.synchronizedHeadRevision &&
            receipt.synchronizedStateSnapshotID == sync.stateSnapshotID &&
            receipt.synchronizedConfigRevision == sync.synchronizedConfigRevision &&
            clear != null && receipt.clearPlanOperationID == clear.clearOperationID &&
            receipt.clearedProjectRevision == clear.clearedProjectRevision &&
            receipt.clearedConfigRevision == clear.clearedConfigRevision
        if (!matches) {
            throw NovelGhostwriteJobError.InvalidJob(
                listOf("Chapter receipt does not match the exact durable cursor identity."),
            )
        }
    }

    private fun requireExpected(
        job: NovelGhostwriteJobV1,
        expectedLedgerRevision: Long,
        expectedExecutionEpoch: Long,
    ) {
        if (job.ledgerRevision != expectedLedgerRevision) {
            throw NovelGhostwriteJobError.StaleLedgerRevision(
                expectedLedgerRevision,
                job.ledgerRevision,
            )
        }
        if (job.executionEpoch != expectedExecutionEpoch) {
            throw NovelGhostwriteJobError.StaleExecutionEpoch(
                expectedExecutionEpoch,
                job.executionEpoch,
            )
        }
    }

    private fun requireRunning(job: NovelGhostwriteJobV1) {
        if (job.status != NovelGhostwriteJobStatus.Running || job.leaseOwnerWorkID == null) {
            throw NovelGhostwriteJobError.InvalidJob(listOf("Ghostwrite job is not running under a lease."))
        }
    }

    private fun requireNotTerminal(job: NovelGhostwriteJobV1) {
        if (job.isTerminal) {
            throw NovelGhostwriteJobError.InvalidJob(listOf("Ghostwrite job is already terminal."))
        }
    }

    private fun validatedTransition(
        from: NovelGhostwriteJobV1,
        to: NovelGhostwriteJobV1,
    ): NovelGhostwriteJobV1 {
        NovelGhostwriteJobValidator.validateTransition(from, to)
        return to
    }

    private val allowedNextPhases = mapOf(
        NovelGhostwriteJobPhase.AwaitingPlan to setOf(
            NovelGhostwriteJobPhase.Planning,
            NovelGhostwriteJobPhase.GenerationPrepared,
        ),
        NovelGhostwriteJobPhase.Planning to setOf(NovelGhostwriteJobPhase.PlanPrepared),
        NovelGhostwriteJobPhase.PlanPrepared to setOf(NovelGhostwriteJobPhase.GenerationPrepared),
        NovelGhostwriteJobPhase.GenerationPrepared to setOf(NovelGhostwriteJobPhase.Generating),
        NovelGhostwriteJobPhase.Generating to setOf(NovelGhostwriteJobPhase.CandidateReady),
        NovelGhostwriteJobPhase.CandidateReady to setOf(NovelGhostwriteJobPhase.Validating),
        NovelGhostwriteJobPhase.Validating to setOf(NovelGhostwriteJobPhase.CollectPrepared),
        NovelGhostwriteJobPhase.CorrectionReady to setOf(NovelGhostwriteJobPhase.GenerationPrepared),
        NovelGhostwriteJobPhase.CollectPrepared to setOf(NovelGhostwriteJobPhase.CollectedNeedsSync),
        NovelGhostwriteJobPhase.CollectedNeedsSync to setOf(NovelGhostwriteJobPhase.Syncing),
        NovelGhostwriteJobPhase.Syncing to setOf(NovelGhostwriteJobPhase.ClearPlanPrepared),
        NovelGhostwriteJobPhase.ClearPlanPrepared to setOf(NovelGhostwriteJobPhase.ChapterCommitPrepared),
        NovelGhostwriteJobPhase.ChapterCommitPrepared to emptySet(),
        NovelGhostwriteJobPhase.ChapterCommitted to emptySet(),
    )
}
