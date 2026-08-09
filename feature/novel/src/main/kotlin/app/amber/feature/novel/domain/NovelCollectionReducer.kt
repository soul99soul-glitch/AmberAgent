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
import app.amber.feature.novel.model.NovelCollectionSource
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
    val expectedConfigRevision: Long,
    val expectedBranchHeadRevision: Long,
    val newChapterVersionId: NovelChapterVersionId = NovelChapterVersionId.generate(),
    val newCheckpointId: NovelCheckpointId = NovelCheckpointId.generate(),
    val newStateSnapshotId: NovelStateSnapshotId = NovelStateSnapshotId.generate(),
    val stateDelta: NovelStateDeltaV1? = null,
    val source: NovelCollectionSource = NovelCollectionSource.User,
    /**
     * When true, manuscript is committed but branch stays [NovelBranchSyncStatus.NeedsSync]
     * so the user can retry state extraction via the existing「同步状态」path (P0-A soft-fail).
     */
    val markNeedsSync: Boolean = false,
)

object NovelCollectionReducer {
    fun collect(
        command: NovelCollectCommand,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): NovelReduceResult {
        if (command.projectId != document.project.id) throw NovelError.ProjectNotFound(command.projectId)
        val payloadSHA256 = app.amber.feature.novel.serialization.sha256HexOfUtf8(
            "${command.candidateId.rawValue}|${command.selectedText}|${command.source.wireValue}",
        )
        document.appliedOperations.firstOrNull { it.operationID == command.operationId }?.let { applied ->
            if (applied.kind != NovelOperationKind.CollectCandidate ||
                applied.payloadSHA256 != payloadSHA256
            ) {
                throw NovelError.IdempotencyConflict(command.operationId)
            }
            return NovelReduceResult(document, applied.outcome)
        }
        if (command.expectedProjectRevision != document.project.revision) {
            throw NovelError.StaleProjectRevision(command.expectedProjectRevision, document.project.revision)
        }
        if (command.expectedConfigRevision != document.project.configRevision) {
            throw NovelError.StaleConfigRevision(command.expectedConfigRevision, document.project.configRevision)
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
        // Available = normal complete; Interrupted = user stopped mid-stream but partial is usable.
        val collectable = candidate.status == NovelCandidateStatus.Available ||
            candidate.status == NovelCandidateStatus.Interrupted
        if (!collectable) {
            throw NovelError.InvalidInput("Candidate is not available for collection")
        }
        if (candidate.kind != app.amber.feature.novel.model.NovelCandidateKind.Prose) {
            throw NovelError.InvalidInput("Only prose candidates can be collected into the manuscript")
        }
        val boundPlanDigest = candidate.chapterPlanDigest
        val confirmedPlan = document.confirmedChapterPlan(branch.id)
        if (boundPlanDigest != null) {
            if (confirmedPlan?.contentDigest != boundPlanDigest) {
                throw NovelError.InvalidInput(
                    "The candidate no longer matches the confirmed chapter plan.",
                )
            }
        } else if (command.source == NovelCollectionSource.SystemAutoCollect) {
            throw NovelError.InvalidInput(
                "Automatic collection requires a candidate bound to a confirmed chapter plan.",
            )
        }
        if (command.source == NovelCollectionSource.SystemAutoCollect) {
            if (candidate.status != NovelCandidateStatus.Available) {
                throw NovelError.InvalidInput("Automatic collection requires a complete candidate.")
            }
            if (command.target !is NovelCollectionTarget.CreateNextChapter) {
                throw NovelError.InvalidInput("Automatic collection must create the next chapter.")
            }
            if (command.selectedText != candidate.content) {
                throw NovelError.InvalidInput("Automatic collection must collect the complete candidate.")
            }
            if (confirmedPlan == null || candidate.ghostwritePlanID != confirmedPlan.id) {
                throw NovelError.InvalidInput(
                    "Automatic collection requires a candidate owned by the confirmed chapter plan.",
                )
            }
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
                    sourceChapterVersionID = existingVersion.id,
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
            is NovelCollectionTarget.ReplaceChapter -> {
                val existingSel = branch.workingChapterSelections.firstOrNull { it.chapterID == target.chapterID }
                    ?: throw NovelError.InvalidInput("The replace target is not in the current manuscript.")
                val existingVersion = document.chapterVersions.firstOrNull {
                    it.id == existingSel.versionID && it.chapterID == target.chapterID
                } ?: throw NovelError.InvalidInput("The replace target is not in the current manuscript.")
                // Replace is only valid for a candidate that rewrote this chapter.
                val sourceVersionId = candidate.sourceChapterVersionID
                    ?: throw NovelError.InvalidInput("The candidate did not rewrite this chapter.")
                val sourceVersion = document.chapterVersions.firstOrNull { it.id == sourceVersionId }
                    ?: throw NovelError.InvalidInput("The candidate did not rewrite this chapter.")
                if (sourceVersion.chapterID != target.chapterID) {
                    throw NovelError.InvalidInput("The candidate did not rewrite this chapter.")
                }
                val newVersion = NovelChapterVersionRecord(
                    id = command.newChapterVersionId,
                    chapterID = target.chapterID,
                    kind = NovelChapterVersionKind.Collected,
                    title = existingVersion.title,
                    content = text,
                    // New fact compatibility: regenerate may change story facts.
                    factCompatibilityID = UUID.randomUUID(),
                    sourceChapterVersionID = existingVersion.id,
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
            recentWrittenHighlights = NovelStateSnapshotRecord.mergedHighlights(
                prior = baseState.recentWrittenHighlights,
                newEventSummaries = newEvents.map { it.summary },
            ),
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
                c.branchID == command.branchId &&
                    (c.status == NovelCandidateStatus.Available ||
                        c.status == NovelCandidateStatus.Interrupted) ->
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
            syncStatus = if (
                command.markNeedsSync || command.source == NovelCollectionSource.SystemAutoCollect
            ) {
                NovelBranchSyncStatus.NeedsSync
            } else {
                NovelBranchSyncStatus.Synchronized
            },
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
                payloadSHA256 = payloadSHA256,
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
