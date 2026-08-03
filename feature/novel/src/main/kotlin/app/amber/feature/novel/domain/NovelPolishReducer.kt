package app.amber.feature.novel.domain

import app.amber.feature.novel.model.NovelAppliedOperationRecord
import app.amber.feature.novel.model.NovelBranchCheckpointRecord
import app.amber.feature.novel.model.NovelBranchSyncStatus
import app.amber.feature.novel.model.NovelCandidateId
import app.amber.feature.novel.model.NovelCandidateStatus
import app.amber.feature.novel.model.NovelChapterSelection
import app.amber.feature.novel.model.NovelChapterVersionId
import app.amber.feature.novel.model.NovelChapterVersionKind
import app.amber.feature.novel.model.NovelChapterVersionRecord
import app.amber.feature.novel.model.NovelCheckpointId
import app.amber.feature.novel.model.NovelCheckpointKind
import app.amber.feature.novel.model.NovelOperationId
import app.amber.feature.novel.model.NovelOperationKind
import app.amber.feature.novel.model.NovelOutcome
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelSessionCursor
import app.amber.feature.novel.runtime.NovelPromptCatalog
import app.amber.feature.novel.serialization.sha256HexOfUtf8
import java.time.Instant

/**
 * Safe polish adopt: same factCompatibility lineage, reuses current state snapshot.
 * Drift / save-as-rewrite: creates manualEdit version and needsSync.
 */
object NovelPolishReducer {
    fun adoptSafe(
        projectId: app.amber.feature.novel.model.NovelProjectId,
        branchId: app.amber.feature.novel.model.NovelBranchId,
        candidateId: NovelCandidateId,
        operationId: NovelOperationId = NovelOperationId.generate(),
        expectedProjectRevision: Long,
        expectedBranchHeadRevision: Long,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): NovelReduceResult {
        val (branchIndex, branch, candidate, sourceVersion) =
            validatePolishCandidate(projectId, branchId, candidateId, expectedProjectRevision, expectedBranchHeadRevision, document)

        val polished = NovelPromptCatalog.completedPolishContent(candidate.content)
            ?: candidate.content.trim().takeIf { it.isNotEmpty() && !it.contains(NovelPromptCatalog.POLISH_COMPLETION_SENTINEL) }
            ?: throw NovelError.InvalidInput("Polish candidate content is empty or incomplete.")

        val newVersion = NovelChapterVersionRecord(
            id = NovelChapterVersionId.generate(),
            chapterID = sourceVersion.chapterID,
            kind = NovelChapterVersionKind.Polish,
            title = sourceVersion.title,
            content = polished,
            factCompatibilityID = sourceVersion.factCompatibilityID,
            sourceChapterVersionID = sourceVersion.id,
            sourceCandidateID = candidateId,
            createdAt = now,
            operationID = operationId,
        )
        return commitAdopt(
            document = document,
            branchIndex = branchIndex,
            branch = branch,
            candidateId = candidateId,
            newVersion = newVersion,
            reuseStateSnapshotId = branch.currentStateSnapshotID,
            needsSync = false,
            operationId = operationId,
            projectId = projectId,
            now = now,
            kind = NovelOperationKind.AdoptPolishCandidate,
        )
    }

    fun saveAsRewrite(
        projectId: app.amber.feature.novel.model.NovelProjectId,
        branchId: app.amber.feature.novel.model.NovelBranchId,
        candidateId: NovelCandidateId,
        operationId: NovelOperationId = NovelOperationId.generate(),
        expectedProjectRevision: Long,
        expectedBranchHeadRevision: Long,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): NovelReduceResult {
        val (branchIndex, branch, _, sourceVersion) =
            validatePolishCandidate(projectId, branchId, candidateId, expectedProjectRevision, expectedBranchHeadRevision, document)
        val text = NovelPromptCatalog.completedPolishContent(candidateContent(document, candidateId))
            ?: candidateContent(document, candidateId).trim()
        val newVersion = NovelChapterVersionRecord(
            id = NovelChapterVersionId.generate(),
            chapterID = sourceVersion.chapterID,
            kind = NovelChapterVersionKind.ManualEdit,
            title = sourceVersion.title,
            content = text,
            factCompatibilityID = java.util.UUID.randomUUID(),
            sourceChapterVersionID = sourceVersion.id,
            sourceCandidateID = candidateId,
            createdAt = now,
            operationID = operationId,
        )
        // Rewrite does not advance head checkpoint; working selection + needsSync only.
        val candidates = document.candidates.map {
            if (it.id == candidateId) it.copy(status = NovelCandidateStatus.Adopted) else it
        }
        val selections = branch.workingChapterSelections.map {
            if (it.chapterID == sourceVersion.chapterID) {
                NovelChapterSelection(sourceVersion.chapterID, newVersion.id)
            } else it
        }
        val branches = document.branches.toMutableList()
        branches[branchIndex] = branch.copy(
            workingChapterSelections = selections,
            workingRevision = branch.workingRevision + 1,
            syncStatus = NovelBranchSyncStatus.NeedsSync,
            updatedAt = now,
        )
        val project = document.project.copy(revision = document.project.revision + 1, updatedAt = now)
        val outcome = NovelOutcome.ManualEditSaved(
            projectID = projectId,
            branchID = branchId,
            chapterVersionID = newVersion.id,
            workingRevision = branches[branchIndex].workingRevision,
            revision = project.revision,
        )
        val next = document.copy(
            project = project,
            branches = branches,
            chapterVersions = document.chapterVersions + newVersion,
            candidates = candidates,
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = operationId,
                kind = NovelOperationKind.SaveManualEdit,
                payloadSHA256 = sha256HexOfUtf8("polishRewrite:${candidateId.rawValue}"),
                outcome = outcome,
                appliedProjectRevision = project.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }

    private fun candidateContent(document: NovelProjectDocumentV1, candidateId: NovelCandidateId): String =
        document.candidates.first { it.id == candidateId }.content

    private data class Validated(
        val branchIndex: Int,
        val branch: app.amber.feature.novel.model.NovelBranchRecord,
        val candidate: app.amber.feature.novel.model.NovelCandidateRecord,
        val sourceVersion: NovelChapterVersionRecord,
    )

    private fun validatePolishCandidate(
        projectId: app.amber.feature.novel.model.NovelProjectId,
        branchId: app.amber.feature.novel.model.NovelBranchId,
        candidateId: NovelCandidateId,
        expectedProjectRevision: Long,
        expectedBranchHeadRevision: Long,
        document: NovelProjectDocumentV1,
    ): Validated {
        if (projectId != document.project.id) throw NovelError.ProjectNotFound(projectId)
        if (expectedProjectRevision != document.project.revision) {
            throw NovelError.StaleProjectRevision(expectedProjectRevision, document.project.revision)
        }
        val branchIndex = document.branches.indexOfFirst { it.id == branchId }
        if (branchIndex < 0) throw NovelError.BranchNotFound(branchId)
        val branch = document.branches[branchIndex]
        if (branch.headRevision != expectedBranchHeadRevision) {
            throw NovelError.StaleBranchHeadRevision(expectedBranchHeadRevision, branch.headRevision)
        }
        if (branch.syncStatus == NovelBranchSyncStatus.NeedsSync) {
            throw NovelError.InvalidInput("Sync required before polish adopt.")
        }
        val candidate = document.candidates.firstOrNull { it.id == candidateId }
            ?: throw NovelError.InvalidInput("Candidate not found")
        if (candidate.kind != app.amber.feature.novel.model.NovelCandidateKind.Polish) {
            throw NovelError.InvalidInput("Not a polish candidate")
        }
        if (candidate.status != NovelCandidateStatus.Available) {
            throw NovelError.InvalidInput("Candidate not available")
        }
        if (candidate.branchID != branchId) throw NovelError.InvalidInput("Wrong branch")
        val sourceId = candidate.sourceChapterVersionID
            ?: throw NovelError.InvalidInput("Polish candidate missing source chapter")
        val sourceVersion = document.chapterVersions.firstOrNull { it.id == sourceId }
            ?: throw NovelError.InvalidInput("Source chapter version missing")
        val currentSel = branch.workingChapterSelections.firstOrNull { it.chapterID == sourceVersion.chapterID }
            ?: throw NovelError.InvalidInput("Source chapter not on working head")
        if (currentSel.versionID != sourceVersion.id) {
            throw NovelError.InvalidInput("Source chapter version is stale")
        }
        return Validated(branchIndex, branch, candidate, sourceVersion)
    }

    private fun commitAdopt(
        document: NovelProjectDocumentV1,
        branchIndex: Int,
        branch: app.amber.feature.novel.model.NovelBranchRecord,
        candidateId: NovelCandidateId,
        newVersion: NovelChapterVersionRecord,
        reuseStateSnapshotId: app.amber.feature.novel.model.NovelStateSnapshotId,
        needsSync: Boolean,
        operationId: NovelOperationId,
        projectId: app.amber.feature.novel.model.NovelProjectId,
        now: Instant,
        kind: NovelOperationKind,
    ): NovelReduceResult {
        val selections = branch.workingChapterSelections.map {
            if (it.chapterID == newVersion.chapterID) {
                NovelChapterSelection(newVersion.chapterID, newVersion.id)
            } else it
        }
        val session = document.sessions.first { it.id == branch.sessionID }
        val cursor = if (session.messages.isEmpty()) {
            NovelSessionCursor.Empty
        } else {
            NovelSessionCursor.Through(session.messages.maxOf { it.sequence })
        }
        val checkpoint = NovelBranchCheckpointRecord(
            id = NovelCheckpointId.generate(),
            kind = NovelCheckpointKind.Polish,
            createdOnBranchID = branch.id,
            parentCheckpointID = branch.headCheckpointID,
            chapterSelections = selections,
            stateSnapshotID = reuseStateSnapshotId,
            sessionCursor = cursor,
            branchOverrideRevisionIDs = branch.overrideRevisionIDs,
            sourceCandidateID = candidateId,
            baseHeadRevision = branch.headRevision,
            operationID = operationId,
            createdAt = now,
        )
        val candidates = document.candidates.map {
            if (it.id == candidateId) {
                it.copy(status = NovelCandidateStatus.Adopted, collectedCheckpointID = checkpoint.id)
            } else it
        }
        val branches = document.branches.toMutableList()
        branches[branchIndex] = branch.copy(
            headCheckpointID = checkpoint.id,
            currentStateSnapshotID = reuseStateSnapshotId,
            headRevision = branch.headRevision + 1,
            workingRevision = branch.workingRevision + 1,
            workingChapterSelections = selections,
            syncStatus = if (needsSync) NovelBranchSyncStatus.NeedsSync else NovelBranchSyncStatus.Synchronized,
            updatedAt = now,
        )
        val project = document.project.copy(revision = document.project.revision + 1, updatedAt = now)
        val outcome = NovelOutcome.PolishCandidateAdopted(
            projectID = projectId,
            branchID = branch.id,
            candidateID = candidateId,
            checkpointID = checkpoint.id,
            chapterVersionID = newVersion.id,
            revision = project.revision,
        )
        val next = document.copy(
            project = project,
            branches = branches,
            chapterVersions = document.chapterVersions + newVersion,
            checkpoints = document.checkpoints + checkpoint,
            candidates = candidates,
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = operationId,
                kind = kind,
                payloadSHA256 = sha256HexOfUtf8("polishAdopt:${candidateId.rawValue}"),
                outcome = outcome,
                appliedProjectRevision = project.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }

    /**
     * Restore a prior chapter version as the working head (compatible fact lineage only).
     * Creates a new [NovelChapterVersionKind.Restore] version + restore checkpoint; state snapshot unchanged.
     */
    fun restoreChapterVersion(
        projectId: app.amber.feature.novel.model.NovelProjectId,
        branchId: app.amber.feature.novel.model.NovelBranchId,
        targetChapterVersionId: NovelChapterVersionId,
        expectedProjectRevision: Long,
        expectedBranchHeadRevision: Long,
        document: NovelProjectDocumentV1,
        operationId: NovelOperationId = NovelOperationId.generate(),
        proposedChapterVersionId: NovelChapterVersionId = NovelChapterVersionId.generate(),
        checkpointId: NovelCheckpointId = NovelCheckpointId.generate(),
        now: Instant = Instant.now(),
    ): NovelReduceResult {
        if (projectId != document.project.id) throw NovelError.ProjectNotFound(projectId)
        if (expectedProjectRevision != document.project.revision) {
            throw NovelError.StaleProjectRevision(expectedProjectRevision, document.project.revision)
        }
        val branchIndex = document.branches.indexOfFirst { it.id == branchId }
        if (branchIndex < 0) throw NovelError.BranchNotFound(branchId)
        val branch = document.branches[branchIndex]
        if (branch.headRevision != expectedBranchHeadRevision) {
            throw NovelError.StaleBranchHeadRevision(expectedBranchHeadRevision, branch.headRevision)
        }
        if (branch.activeRunID != null) throw NovelError.ProjectBusy(projectId)
        if (branch.syncStatus == NovelBranchSyncStatus.NeedsSync) {
            throw NovelError.InvalidInput("Sync required before restoring a chapter version.")
        }
        val target = document.chapterVersions.firstOrNull { it.id == targetChapterVersionId }
            ?: throw NovelError.InvalidInput("The chapter version cannot be restored on this branch.")
        val currentSelection = branch.workingChapterSelections.firstOrNull {
            it.chapterID == target.chapterID
        } ?: throw NovelError.InvalidInput("The chapter version cannot be restored on this branch.")
        val current = document.chapterVersions.firstOrNull { it.id == currentSelection.versionID }
            ?: throw NovelError.InvalidInput("The chapter version cannot be restored on this branch.")
        if (current.id == target.id) {
            throw NovelError.InvalidInput("This version is already the current head.")
        }
        if (current.factCompatibilityID != target.factCompatibilityID) {
            throw NovelError.InvalidInput(
                "This chapter version may change story facts and must be restored as a manual edit.",
            )
        }
        if (document.chapterVersions.any { it.id == proposedChapterVersionId }) {
            throw NovelError.ImmutableRecordConflict("chapter version $proposedChapterVersionId")
        }
        if (document.checkpoints.any { it.id == checkpointId }) {
            throw NovelError.ImmutableRecordConflict("checkpoint $checkpointId")
        }
        val session = document.sessions.firstOrNull { it.id == branch.sessionID }
            ?: throw NovelError.SessionNotFound(branch.sessionID)
        val restored = NovelChapterVersionRecord(
            id = proposedChapterVersionId,
            chapterID = target.chapterID,
            kind = NovelChapterVersionKind.Restore,
            title = target.title,
            content = target.content,
            factCompatibilityID = target.factCompatibilityID,
            sourceChapterVersionID = target.id,
            sourceCandidateID = null,
            createdAt = now,
            operationID = operationId,
        )
        val selections = branch.workingChapterSelections.map {
            if (it.chapterID == target.chapterID) {
                NovelChapterSelection(target.chapterID, restored.id)
            } else it
        }
        val cursor = if (session.messages.isEmpty()) {
            NovelSessionCursor.Empty
        } else {
            NovelSessionCursor.Through(session.messages.maxOf { it.sequence })
        }
        val checkpoint = NovelBranchCheckpointRecord(
            id = checkpointId,
            kind = NovelCheckpointKind.Restore,
            createdOnBranchID = branch.id,
            parentCheckpointID = branch.headCheckpointID,
            chapterSelections = selections,
            stateSnapshotID = branch.currentStateSnapshotID,
            sessionCursor = cursor,
            branchOverrideRevisionIDs = branch.overrideRevisionIDs,
            sourceCandidateID = null,
            baseHeadRevision = branch.headRevision,
            operationID = operationId,
            createdAt = now,
        )
        val branches = document.branches.toMutableList()
        branches[branchIndex] = branch.copy(
            headCheckpointID = checkpoint.id,
            headRevision = branch.headRevision + 1,
            workingRevision = branch.workingRevision + 1,
            workingChapterSelections = selections,
            syncStatus = NovelBranchSyncStatus.Synchronized,
            updatedAt = now,
        )
        val project = document.project.copy(
            revision = document.project.revision + 1,
            updatedAt = now,
        )
        val outcome = NovelOutcome.ChapterVersionRestored(
            projectID = projectId,
            branchID = branchId,
            checkpointID = checkpoint.id,
            chapterVersionID = restored.id,
            revision = project.revision,
        )
        val next = document.copy(
            project = project,
            branches = branches,
            chapterVersions = document.chapterVersions + restored,
            checkpoints = document.checkpoints + checkpoint,
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = operationId,
                kind = NovelOperationKind.RestoreChapterVersion,
                payloadSHA256 = sha256HexOfUtf8(
                    "restoreChapter:${targetChapterVersionId.rawValue}:${proposedChapterVersionId.rawValue}",
                ),
                outcome = outcome,
                appliedProjectRevision = project.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }
}
