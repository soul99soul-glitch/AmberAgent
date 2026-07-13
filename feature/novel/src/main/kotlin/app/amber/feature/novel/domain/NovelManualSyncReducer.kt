package app.amber.feature.novel.domain

import app.amber.feature.novel.model.NovelAppliedOperationRecord
import app.amber.feature.novel.model.NovelBranchCheckpointRecord
import app.amber.feature.novel.model.NovelBranchSyncStatus
import app.amber.feature.novel.model.NovelCheckpointId
import app.amber.feature.novel.model.NovelCheckpointKind
import app.amber.feature.novel.model.NovelEventId
import app.amber.feature.novel.model.NovelOperationId
import app.amber.feature.novel.model.NovelOperationKind
import app.amber.feature.novel.model.NovelOutcome
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelProposalId
import app.amber.feature.novel.model.NovelSessionCursor
import app.amber.feature.novel.model.NovelSettingProposalOrigin
import app.amber.feature.novel.model.NovelSettingProposalRecord
import app.amber.feature.novel.model.NovelStateSnapshotId
import app.amber.feature.novel.model.NovelStateSnapshotRecord
import app.amber.feature.novel.model.NovelStoryEventRecord
import app.amber.feature.novel.serialization.sha256HexOfUtf8
import java.time.Instant

/**
 * Commit a manualSync checkpoint after needsSync.
 * Rebuild uses optional model-produced state delta over concatenated working manuscript;
 * if delta is null, reuses current summary and clears only via empty event append.
 */
object NovelManualSyncReducer {
    fun sync(
        projectId: app.amber.feature.novel.model.NovelProjectId,
        branchId: app.amber.feature.novel.model.NovelBranchId,
        operationId: NovelOperationId = NovelOperationId.generate(),
        expectedProjectRevision: Long,
        expectedBranchHeadRevision: Long,
        stateDelta: NovelStateDeltaV1?,
        document: NovelProjectDocumentV1,
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
        if (branch.syncStatus != NovelBranchSyncStatus.NeedsSync) {
            throw NovelError.InvalidInput("Branch does not need sync.")
        }
        if (branch.activeRunID != null) throw NovelError.ProjectBusy(projectId)

        val baseState = document.stateSnapshots.first { it.id == branch.currentStateSnapshotID }
        val newEvents = stateDelta?.events?.mapIndexed { index, e ->
            NovelStoryEventRecord(
                id = NovelEventId.generate(),
                sequence = (baseState.eventIDs.size + index + 1).toLong(),
                kind = e.kind,
                summary = e.summary,
                entityReferences = e.entityReferences,
                createdAt = now,
            )
        }.orEmpty()
        val proposals = stateDelta?.settingProposals?.map { p ->
            NovelSettingProposalRecord(
                id = NovelProposalId.generate(),
                branchID = branchId,
                title = p.title,
                content = p.content,
                createdAt = now,
                isResolved = false,
                origin = NovelSettingProposalOrigin.DerivedState,
            )
        }.orEmpty()
        val newState = NovelStateSnapshotRecord(
            id = NovelStateSnapshotId.generate(),
            eventIDs = baseState.eventIDs + newEvents.map { it.id },
            summary = stateDelta?.stateSummary ?: baseState.summary.ifBlank { "Synced after manual edit." },
            branchOutline = stateDelta?.branchOutlinePatch ?: baseState.branchOutline,
            unresolvedEntityNames = stateDelta?.unresolvedEntityNames ?: baseState.unresolvedEntityNames,
            settingProposalIDs = baseState.settingProposalIDs + proposals.map { it.id },
            createdAt = now,
        )
        val session = document.sessions.first { it.id == branch.sessionID }
        val cursor = if (session.messages.isEmpty()) {
            NovelSessionCursor.Empty
        } else {
            NovelSessionCursor.Through(session.messages.maxOf { it.sequence })
        }
        val checkpoint = NovelBranchCheckpointRecord(
            id = NovelCheckpointId.generate(),
            kind = NovelCheckpointKind.ManualSync,
            createdOnBranchID = branchId,
            parentCheckpointID = branch.headCheckpointID,
            chapterSelections = branch.workingChapterSelections,
            stateSnapshotID = newState.id,
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
            currentStateSnapshotID = newState.id,
            headRevision = branch.headRevision + 1,
            workingRevision = branch.workingRevision + 1,
            syncStatus = NovelBranchSyncStatus.Synchronized,
            updatedAt = now,
        )
        val project = document.project.copy(revision = document.project.revision + 1, updatedAt = now)
        val outcome = NovelOutcome.ManualSyncCommitted(
            projectID = projectId,
            branchID = branchId,
            checkpointID = checkpoint.id,
            revision = project.revision,
        )
        val next = document.copy(
            project = project,
            branches = branches,
            events = document.events + newEvents,
            stateSnapshots = document.stateSnapshots + newState,
            checkpoints = document.checkpoints + checkpoint,
            settingProposals = document.settingProposals + proposals,
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = operationId,
                kind = NovelOperationKind.SyncManualEdits,
                payloadSHA256 = sha256HexOfUtf8("manualSync:${branchId.rawValue}:${checkpoint.id.rawValue}"),
                outcome = outcome,
                appliedProjectRevision = project.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }

    fun workingManuscript(document: NovelProjectDocumentV1, branchId: app.amber.feature.novel.model.NovelBranchId): String {
        val branch = document.branches.first { it.id == branchId }
        return branch.workingChapterSelections.joinToString("\n\n") { sel ->
            val version = document.chapterVersions.firstOrNull { it.id == sel.versionID }
            "## ${version?.title.orEmpty()}\n\n${version?.content.orEmpty()}"
        }
    }
}
