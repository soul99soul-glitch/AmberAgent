package app.amber.feature.novel.domain

import app.amber.feature.novel.model.NovelAppliedOperationRecord
import app.amber.feature.novel.model.NovelBranchCheckpointRecord
import app.amber.feature.novel.model.NovelBranchSyncStatus
import app.amber.feature.novel.model.NovelCandidateId
import app.amber.feature.novel.model.NovelCandidateStatus
import app.amber.feature.novel.model.NovelChapterId
import app.amber.feature.novel.model.NovelChapterRecord
import app.amber.feature.novel.model.NovelChapterSelection
import app.amber.feature.novel.model.NovelChapterVersionId
import app.amber.feature.novel.model.NovelChapterVersionKind
import app.amber.feature.novel.model.NovelChapterVersionRecord
import app.amber.feature.novel.model.NovelCheckpointId
import app.amber.feature.novel.model.NovelCheckpointKind
import app.amber.feature.novel.model.NovelCollectionTarget
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
import java.time.Instant
import java.util.UUID

private object NovelChapterText {
    fun appending(addition: String, to: String): String = when {
        to.isEmpty() -> addition
        addition.isEmpty() -> to
        else -> "$to\n\n$addition"
    }
}

data class NovelCollectCommand(
    val projectId: app.amber.feature.novel.model.NovelProjectId,
    val branchId: app.amber.feature.novel.model.NovelBranchId,
    val candidateId: NovelCandidateId,
    val selectedText: String,
    val target: NovelCollectionTarget,
    val operationId: NovelOperationId = NovelOperationId.generate(),
    val expectedProjectRevision: Long,
    val expectedBranchHeadRevision: Long,
    val newChapterVersionId: NovelChapterVersionId = NovelChapterVersionId.generate(),
    val newCheckpointId: NovelCheckpointId = NovelCheckpointId.generate(),
    val newStateSnapshotId: NovelStateSnapshotId = NovelStateSnapshotId.generate(),
    val stateDelta: NovelStateDeltaV1? = null,
)

object NovelCollectionReducer {
    fun collect(
        command: NovelCollectCommand,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): NovelReduceResult {
        if (command.projectId != document.project.id) throw NovelError.ProjectNotFound(command.projectId)
        if (command.expectedProjectRevision != document.project.revision) {
            throw NovelError.StaleProjectRevision(command.expectedProjectRevision, document.project.revision)
        }
        val branchIndex = document.branches.indexOfFirst { it.id == command.branchId }
        if (branchIndex < 0) throw NovelError.BranchNotFound(command.branchId)
        val branch = document.branches[branchIndex]
        if (branch.headRevision != command.expectedBranchHeadRevision) {
            throw NovelError.StaleBranchHeadRevision(command.expectedBranchHeadRevision, branch.headRevision)
        }
        if (branch.syncStatus == NovelBranchSyncStatus.NeedsSync) {
            throw NovelError.InvalidInput("Branch needs sync before collection.")
        }
        if (branch.activeRunID != null) throw NovelError.ProjectBusy(document.project.id)
        val candidateIndex = document.candidates.indexOfFirst { it.id == command.candidateId }
        if (candidateIndex < 0) throw NovelError.InvalidInput("Candidate not found")
        val candidate = document.candidates[candidateIndex]
        if (candidate.status != NovelCandidateStatus.Available) {
            throw NovelError.InvalidInput("Candidate is not available for collection")
        }
        if (command.selectedText.isBlank()) throw NovelError.InvalidInput("Selected text is empty")

        val text = command.selectedText
        val (chapters, versions, selections, chapterVersionId) = when (val target = command.target) {
            is NovelCollectionTarget.AppendToChapter -> {
                val existingSel = branch.workingChapterSelections.firstOrNull { it.chapterID == target.chapterID }
                    ?: throw NovelError.InvalidInput("Chapter not in working selections")
                val existingVersion = document.chapterVersions.first { it.id == existingSel.versionID }
                val newVersion = NovelChapterVersionRecord(
                    id = command.newChapterVersionId,
                    chapterID = target.chapterID,
                    kind = NovelChapterVersionKind.Collected,
                    title = existingVersion.title,
                    content = NovelChapterText.appending(text, existingVersion.content),
                    factCompatibilityID = UUID.randomUUID(),
                    sourceCandidateID = candidate.id,
                    createdAt = now,
                    operationID = command.operationId,
                )
                val newSelections = branch.workingChapterSelections.map {
                    if (it.chapterID == target.chapterID) {
                        NovelChapterSelection(target.chapterID, newVersion.id)
                    } else it
                }
                Tuple4(document.chapters, document.chapterVersions + newVersion, newSelections, newVersion.id)
            }
            is NovelCollectionTarget.CreateNextChapter -> {
                val chapter = NovelChapterRecord(id = target.chapterID, createdAt = now)
                val newVersion = NovelChapterVersionRecord(
                    id = command.newChapterVersionId,
                    chapterID = target.chapterID,
                    kind = NovelChapterVersionKind.Collected,
                    title = target.title,
                    content = text,
                    factCompatibilityID = UUID.randomUUID(),
                    sourceCandidateID = candidate.id,
                    createdAt = now,
                    operationID = command.operationId,
                )
                val newSelections = branch.workingChapterSelections +
                    NovelChapterSelection(target.chapterID, newVersion.id)
                Tuple4(
                    document.chapters + chapter,
                    document.chapterVersions + newVersion,
                    newSelections,
                    newVersion.id,
                )
            }
        }

        val baseState = document.stateSnapshots.first { it.id == branch.currentStateSnapshotID }
        val delta = command.stateDelta
        val newEvents = delta?.events?.mapIndexed { index, e ->
            NovelStoryEventRecord(
                id = NovelEventId.generate(),
                sequence = (baseState.eventIDs.size + index + 1).toLong(),
                kind = e.kind,
                summary = e.summary,
                entityReferences = e.entityReferences,
                createdAt = now,
            )
        }.orEmpty()
        val eventIds = baseState.eventIDs + newEvents.map { it.id }
        val proposals = delta?.settingProposals?.map { p ->
            NovelSettingProposalRecord(
                id = NovelProposalId.generate(),
                branchID = command.branchId,
                title = p.title,
                content = p.content,
                createdAt = now,
                isResolved = false,
                origin = NovelSettingProposalOrigin.DerivedState,
            )
        }.orEmpty()
        val newState = NovelStateSnapshotRecord(
            id = command.newStateSnapshotId,
            eventIDs = eventIds,
            summary = delta?.stateSummary ?: baseState.summary,
            branchOutline = delta?.branchOutlinePatch ?: baseState.branchOutline,
            unresolvedEntityNames = delta?.unresolvedEntityNames ?: baseState.unresolvedEntityNames,
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
            id = command.newCheckpointId,
            kind = NovelCheckpointKind.Collection,
            createdOnBranchID = command.branchId,
            parentCheckpointID = branch.headCheckpointID,
            chapterSelections = selections,
            stateSnapshotID = newState.id,
            sessionCursor = cursor,
            branchOverrideRevisionIDs = branch.overrideRevisionIDs,
            sourceCandidateID = candidate.id,
            baseHeadRevision = branch.headRevision,
            operationID = command.operationId,
            createdAt = now,
        )

        if (candidate.branchID != command.branchId) {
            throw NovelError.InvalidInput("Candidate is not on this branch")
        }
        if (candidate.baseHeadRevision != branch.headRevision) {
            throw NovelError.InvalidInput("Candidate is stale relative to branch head")
        }
        val candidates = document.candidates.mapIndexed { index, c ->
            when {
                index == candidateIndex -> c.copy(
                    status = NovelCandidateStatus.Collected,
                    collectedCheckpointID = checkpoint.id,
                )
                c.branchID == command.branchId && c.status == NovelCandidateStatus.Available ->
                    c.copy(status = NovelCandidateStatus.Superseded)
                else -> c
            }
        }
        val branches = document.branches.toMutableList()
        branches[branchIndex] = branch.copy(
            headCheckpointID = checkpoint.id,
            currentStateSnapshotID = newState.id,
            headRevision = branch.headRevision + 1,
            workingRevision = branch.workingRevision + 1,
            syncStatus = NovelBranchSyncStatus.Synchronized,
            workingChapterSelections = selections,
            updatedAt = now,
        )
        val project = document.project.copy(
            revision = document.project.revision + 1,
            updatedAt = now,
        )
        val outcome = NovelOutcome.CandidateCollected(
            projectID = command.projectId,
            branchID = command.branchId,
            candidateID = command.candidateId,
            checkpointID = checkpoint.id,
            chapterVersionID = chapterVersionId,
            revision = project.revision,
        )
        val next = document.copy(
            project = project,
            branches = branches,
            chapters = chapters,
            chapterVersions = versions,
            events = document.events + newEvents,
            stateSnapshots = document.stateSnapshots + newState,
            checkpoints = document.checkpoints + checkpoint,
            candidates = candidates,
            settingProposals = document.settingProposals + proposals,
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = command.operationId,
                kind = NovelOperationKind.CollectCandidate,
                payloadSHA256 = app.amber.feature.novel.serialization.sha256HexOfUtf8(
                    "${command.candidateId.rawValue}|${command.selectedText}",
                ),
                outcome = outcome,
                appliedProjectRevision = project.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }

    private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
