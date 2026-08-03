package app.amber.feature.novel.domain

import app.amber.feature.novel.model.NovelAppliedOperationRecord
import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelBranchLifecycle
import app.amber.feature.novel.model.NovelBranchRecord
import app.amber.feature.novel.model.NovelBranchSyncStatus
import app.amber.feature.novel.model.NovelCandidateId
import app.amber.feature.novel.model.NovelCandidateRecord
import app.amber.feature.novel.model.NovelCandidateStatus
import app.amber.feature.novel.model.NovelCheckpointId
import app.amber.feature.novel.model.NovelForkOrigin
import app.amber.feature.novel.model.NovelInjectionMode
import app.amber.feature.novel.model.NovelMaterialId
import app.amber.feature.novel.model.NovelMaterialKind
import app.amber.feature.novel.model.NovelMaterialRecord
import app.amber.feature.novel.model.NovelMaterialRevisionId
import app.amber.feature.novel.model.NovelMaterialRevisionRecord
import app.amber.feature.novel.model.NovelMessageId
import app.amber.feature.novel.model.NovelOperationId
import app.amber.feature.novel.model.NovelOperationKind
import app.amber.feature.novel.model.NovelOutcome
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelProposalId
import app.amber.feature.novel.model.NovelSessionId
import app.amber.feature.novel.model.NovelSessionRecord
import app.amber.feature.novel.model.NovelSessionCursor
import java.time.Instant

object NovelBranchReducer {
    fun fork(
        projectId: app.amber.feature.novel.model.NovelProjectId,
        sourceBranchId: NovelBranchId,
        checkpointId: NovelCheckpointId,
        newBranchId: NovelBranchId = NovelBranchId.generate(),
        newSessionId: NovelSessionId = NovelSessionId.generate(),
        name: String,
        operationId: NovelOperationId = NovelOperationId.generate(),
        expectedProjectRevision: Long,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): NovelReduceResult {
        if (projectId != document.project.id) throw NovelError.ProjectNotFound(projectId)
        if (expectedProjectRevision != document.project.revision) {
            throw NovelError.StaleProjectRevision(expectedProjectRevision, document.project.revision)
        }
        val source = document.branches.firstOrNull { it.id == sourceBranchId }
            ?: throw NovelError.BranchNotFound(sourceBranchId)
        val checkpoint = document.checkpoints.firstOrNull { it.id == checkpointId }
            ?: throw NovelError.CheckpointNotFound(checkpointId)
        val sourceSession = document.sessions.first { it.id == source.sessionID }
        val inheritedMessages = when (val cursor = checkpoint.sessionCursor) {
            NovelSessionCursor.Empty -> emptyList()
            is NovelSessionCursor.Through -> sourceSession.messages.filter { it.sequence <= cursor.sequence }
        }
        val newSession = NovelSessionRecord(
            id = newSessionId,
            branchID = newBranchId,
            revision = inheritedMessages.size.toLong(),
            messages = inheritedMessages,
        )
        val inheritedCandidates = document.candidates
            .filter { it.branchID == sourceBranchId && it.status == NovelCandidateStatus.Available }
            .map {
                it.copy(
                    id = NovelCandidateId.generate(),
                    branchID = newBranchId,
                    sessionID = newSessionId,
                    status = NovelCandidateStatus.InheritedReadOnly,
                    clonedFromCandidateID = it.id,
                )
            }
        val newBranch = NovelBranchRecord(
            id = newBranchId,
            name = name.trim().ifEmpty { "Fork" },
            sessionID = newSessionId,
            createdAt = now,
            updatedAt = now,
            forkOrigin = NovelForkOrigin(sourceBranchId, checkpointId),
            headCheckpointID = checkpointId,
            currentStateSnapshotID = checkpoint.stateSnapshotID,
            headRevision = 0,
            workingRevision = 0,
            syncStatus = NovelBranchSyncStatus.Synchronized,
            lifecycle = NovelBranchLifecycle.Active,
            overrideRevisionIDs = checkpoint.branchOverrideRevisionIDs,
            workingChapterSelections = checkpoint.chapterSelections,
            activeRunID = null,
        )
        val project = document.project.copy(revision = document.project.revision + 1, updatedAt = now)
        val outcome = NovelOutcome.BranchForked(
            projectID = projectId,
            sourceBranchID = sourceBranchId,
            branchID = newBranchId,
            checkpointID = checkpointId,
            revision = project.revision,
        )
        val next = document.copy(
            project = project,
            branches = document.branches + newBranch,
            sessions = document.sessions + newSession,
            candidates = document.candidates + inheritedCandidates,
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = operationId,
                kind = NovelOperationKind.ForkBranch,
                payloadSHA256 = app.amber.feature.novel.serialization.sha256HexOfUtf8(
                    "fork:${sourceBranchId.rawValue}:${checkpointId.rawValue}",
                ),
                outcome = outcome,
                appliedProjectRevision = project.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }

    fun undoHead(
        projectId: app.amber.feature.novel.model.NovelProjectId,
        branchId: NovelBranchId,
        operationId: NovelOperationId = NovelOperationId.generate(),
        expectedProjectRevision: Long,
        expectedBranchHeadRevision: Long,
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
        if (branch.syncStatus == NovelBranchSyncStatus.NeedsSync) {
            throw NovelError.InvalidInput("Synchronize the working manuscript before undoing its head.")
        }
        if (branch.activeRunID != null) throw NovelError.ProjectBusy(projectId)
        val head = document.checkpoints.firstOrNull { it.id == branch.headCheckpointID }
            ?: throw NovelError.CheckpointNotFound(branch.headCheckpointID)
        val parentId = head.parentCheckpointID
            ?: throw NovelError.InvalidInput("Cannot undo the initial checkpoint")
        val parent = document.checkpoints.firstOrNull { it.id == parentId }
            ?: throw NovelError.CheckpointNotFound(parentId)
        val branches = document.branches.toMutableList()
        branches[branchIndex] = branch.copy(
            headCheckpointID = parent.id,
            currentStateSnapshotID = parent.stateSnapshotID,
            headRevision = branch.headRevision + 1,
            workingRevision = branch.workingRevision + 1,
            workingChapterSelections = parent.chapterSelections,
            overrideRevisionIDs = parent.branchOverrideRevisionIDs,
            syncStatus = NovelBranchSyncStatus.Synchronized,
            updatedAt = now,
        )
        // iOS parity: when undoing a DiscussionArchive head (or any head), recompute
        // archiveCursor from discussionArchives still reachable via the new head lineage.
        val sessions = document.sessions.toMutableList()
        val sessionIndex = sessions.indexOfFirst {
            it.id == branch.sessionID && it.branchID == branch.id
        }
        if (sessionIndex >= 0) {
            val session = sessions[sessionIndex]
            val lineage = mutableSetOf<NovelCheckpointId>()
            var cursor: app.amber.feature.novel.model.NovelBranchCheckpointRecord? = parent
            while (cursor != null && lineage.add(cursor.id)) {
                val parentCheckpointId = cursor.parentCheckpointID ?: break
                cursor = document.checkpoints.firstOrNull { it.id == parentCheckpointId }
            }
            val latestArchive = session.discussionArchives
                .filter { it.checkpointID in lineage }
                .maxByOrNull { it.throughSequence }
            val nextArchiveCursor = latestArchive?.let { NovelSessionCursor.Through(it.throughSequence) }
            if (nextArchiveCursor != session.archiveCursor) {
                sessions[sessionIndex] = session.copy(
                    revision = session.revision + 1,
                    archiveCursor = nextArchiveCursor,
                )
            }
        }
        // Restore source candidate (collect / polish) so re-collect/re-adopt can pass CAS.
        // headRevision always advances on undo, so baseHeadRevision must track the new head.
        val newHeadRevision = branches[branchIndex].headRevision
        val restoredCandidates = head.sourceCandidateID?.let { sourceId ->
            document.candidates.map { candidate ->
                if (candidate.id == sourceId &&
                    (
                        candidate.status == NovelCandidateStatus.Collected ||
                            candidate.status == NovelCandidateStatus.Adopted
                        )
                ) {
                    candidate.copy(
                        status = NovelCandidateStatus.Available,
                        collectedCheckpointID = null,
                        baseCheckpointID = parent.id,
                        baseHeadRevision = newHeadRevision,
                    )
                } else {
                    candidate
                }
            }
        } ?: document.candidates

        // DiscussionArchive appends DecisionLog materials (Always inject). Soft-delete those
        // created only by the undone head so undoing does not leave double context
        // (rewound messages + orphan DecisionLogs) and re-archive does not pile duplicates.
        var materials = document.materials
        var configBump = 0L
        if (head.kind == app.amber.feature.novel.model.NovelCheckpointKind.DiscussionArchive) {
            val orphanedRevisionIds =
                head.branchOverrideRevisionIDs.toSet() - parent.branchOverrideRevisionIDs.toSet()
            if (orphanedRevisionIds.isNotEmpty()) {
                var deleted = 0
                materials = materials.map { material ->
                    if (!material.isDeleted &&
                        material.kind is NovelMaterialKind.DecisionLog &&
                        material.currentRevisionID in orphanedRevisionIds
                    ) {
                        deleted++
                        material.copy(isDeleted = true)
                    } else {
                        material
                    }
                }
                if (deleted > 0) configBump = 1L
            }
        }

        val project = document.project.copy(
            revision = document.project.revision + 1,
            configRevision = document.project.configRevision + configBump,
            updatedAt = now,
        )
        val outcome = NovelOutcome.BranchHeadMoved(
            projectID = projectId,
            branchID = branchId,
            fromCheckpointID = head.id,
            toCheckpointID = parent.id,
            headRevision = newHeadRevision,
            revision = project.revision,
        )
        val next = document.copy(
            project = project,
            branches = branches,
            sessions = sessions,
            candidates = restoredCandidates,
            materials = materials,
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = operationId,
                kind = NovelOperationKind.UndoBranchHead,
                payloadSHA256 = app.amber.feature.novel.serialization.sha256HexOfUtf8(
                    "undo:${branchId.rawValue}:${head.id.rawValue}",
                ),
                outcome = outcome,
                appliedProjectRevision = project.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }

    fun renameBranch(
        projectId: app.amber.feature.novel.model.NovelProjectId,
        branchId: NovelBranchId,
        name: String,
        operationId: NovelOperationId = NovelOperationId.generate(),
        expectedProjectRevision: Long,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): NovelReduceResult {
        if (projectId != document.project.id) throw NovelError.ProjectNotFound(projectId)
        if (expectedProjectRevision != document.project.revision) {
            throw NovelError.StaleProjectRevision(expectedProjectRevision, document.project.revision)
        }
        val branchIndex = document.branches.indexOfFirst { it.id == branchId }
        if (branchIndex < 0) throw NovelError.BranchNotFound(branchId)
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw NovelError.InvalidInput("Branch name is required.")
        val branches = document.branches.toMutableList()
        branches[branchIndex] = branches[branchIndex].copy(name = trimmed, updatedAt = now)
        val project = document.project.copy(revision = document.project.revision + 1, updatedAt = now)
        val outcome = NovelOutcome.BranchRenamed(projectId, branchId, project.revision)
        val next = document.copy(
            project = project,
            branches = branches,
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = operationId,
                kind = NovelOperationKind.RenameBranch,
                payloadSHA256 = app.amber.feature.novel.serialization.sha256HexOfUtf8("renameBranch:$trimmed"),
                outcome = outcome,
                appliedProjectRevision = project.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }

    fun setMainBranch(
        projectId: app.amber.feature.novel.model.NovelProjectId,
        branchId: NovelBranchId,
        operationId: NovelOperationId = NovelOperationId.generate(),
        expectedProjectRevision: Long,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): NovelReduceResult {
        if (projectId != document.project.id) throw NovelError.ProjectNotFound(projectId)
        if (expectedProjectRevision != document.project.revision) {
            throw NovelError.StaleProjectRevision(expectedProjectRevision, document.project.revision)
        }
        val branch = document.branches.firstOrNull { it.id == branchId }
            ?: throw NovelError.BranchNotFound(branchId)
        if (branch.lifecycle != NovelBranchLifecycle.Active) {
            throw NovelError.InvalidInput("Only active branches can be main.")
        }
        val project = document.project.copy(
            mainBranchID = branchId,
            revision = document.project.revision + 1,
            updatedAt = now,
        )
        val outcome = NovelOutcome.MainBranchChanged(projectId, branchId, project.revision)
        val next = document.copy(
            project = project,
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = operationId,
                kind = NovelOperationKind.SetMainBranch,
                payloadSHA256 = app.amber.feature.novel.serialization.sha256HexOfUtf8("main:${branchId.rawValue}"),
                outcome = outcome,
                appliedProjectRevision = project.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }

    fun resolveProposal(
        projectId: app.amber.feature.novel.model.NovelProjectId,
        proposalId: NovelProposalId,
        accept: Boolean,
        materialId: NovelMaterialId = NovelMaterialId.generate(),
        revisionId: NovelMaterialRevisionId = NovelMaterialRevisionId.generate(),
        kind: NovelMaterialKind? = null,
        operationId: NovelOperationId = NovelOperationId.generate(),
        expectedProjectRevision: Long,
        expectedConfigRevision: Long,
        document: NovelProjectDocumentV1,
        now: Instant = Instant.now(),
    ): NovelReduceResult {
        if (projectId != document.project.id) throw NovelError.ProjectNotFound(projectId)
        if (expectedProjectRevision != document.project.revision ||
            expectedConfigRevision != document.project.configRevision
        ) {
            throw NovelError.StaleProjectRevision(expectedProjectRevision, document.project.revision)
        }
        val proposalIndex = document.settingProposals.indexOfFirst { it.id == proposalId }
        if (proposalIndex < 0) throw NovelError.InvalidInput("Proposal not found")
        val proposal = document.settingProposals[proposalIndex]
        if (proposal.isResolved) throw NovelError.InvalidInput("Proposal already resolved")

        val proposals = document.settingProposals.toMutableList()
        proposals[proposalIndex] = proposal.copy(isResolved = true)

        var materials = document.materials
        var revisions = document.materialRevisions
        val outcome: NovelOutcome
        if (accept) {
            val materialKind = kind
                ?: (proposal.origin as? app.amber.feature.novel.model.NovelSettingProposalOrigin.QuickStart)?.suggestedKind
                ?: NovelMaterialKind.Custom("proposal")
            val revision = NovelMaterialRevisionRecord(
                id = revisionId,
                materialID = materialId,
                revision = 1,
                title = proposal.title,
                content = proposal.content,
                tags = emptyList(),
                injectionMode = NovelInjectionMode.Smart,
                createdAt = now,
                operationID = operationId,
            )
            materials = materials + NovelMaterialRecord(
                id = materialId,
                kind = materialKind,
                currentRevisionID = revisionId,
                revisionIDs = listOf(revisionId),
            )
            revisions = revisions + revision
            outcome = NovelOutcome.SettingProposalAccepted(
                projectID = projectId,
                proposalID = proposalId,
                materialID = materialId,
                revisionID = revisionId,
                projectRevision = document.project.revision + 1,
                configRevision = document.project.configRevision + 1,
            )
        } else {
            outcome = NovelOutcome.SettingProposalRejected(
                projectID = projectId,
                proposalID = proposalId,
                projectRevision = document.project.revision + 1,
                configRevision = document.project.configRevision + 1,
            )
        }
        val project = document.project.copy(
            revision = document.project.revision + 1,
            configRevision = document.project.configRevision + 1,
            updatedAt = now,
        )
        val next = document.copy(
            project = project,
            materials = materials,
            materialRevisions = revisions,
            settingProposals = proposals,
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = operationId,
                kind = NovelOperationKind.ResolveSettingProposal,
                payloadSHA256 = app.amber.feature.novel.serialization.sha256HexOfUtf8(
                    "resolve:${proposalId.rawValue}:$accept",
                ),
                outcome = outcome,
                appliedProjectRevision = project.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }

    data class ArchiveDecision(
        val topic: String,
        val decision: String,
        val materialId: NovelMaterialId = NovelMaterialId.generate(),
        val revisionId: NovelMaterialRevisionId = NovelMaterialRevisionId.generate(),
        /** Optional link to an existing material (iOS relatedMaterialID → tags). */
        val relatedMaterialId: NovelMaterialId? = null,
    )

    fun setChapterDiscarded(
        projectId: app.amber.feature.novel.model.NovelProjectId,
        branchId: NovelBranchId,
        chapterId: app.amber.feature.novel.model.NovelChapterId,
        discarded: Boolean,
        expectedProjectRevision: Long,
        expectedBranchHeadRevision: Long,
        document: NovelProjectDocumentV1,
        operationId: NovelOperationId = NovelOperationId.generate(),
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
        val chapterIndex = document.chapters.indexOfFirst { it.id == chapterId }
        if (chapterIndex < 0) throw NovelError.InvalidInput("Chapter not found")
        val chapter = document.chapters[chapterIndex]
        val already = chapter.discardedAt != null
        if (already == discarded) {
            throw NovelError.InvalidInput(
                if (discarded) "Chapter is already discarded." else "Chapter is not discarded.",
            )
        }
        val chapters = document.chapters.toMutableList()
        chapters[chapterIndex] = chapter.copy(discardedAt = if (discarded) now else null)
        val lastVersionId = document.chapterVersions
            .filter { it.chapterID == chapterId }
            .maxByOrNull { it.createdAt }
            ?.id
            ?: throw NovelError.InvalidInput("Chapter has no versions to discard/restore.")
        val finalSelections = if (discarded) {
            branch.workingChapterSelections.filter { it.chapterID != chapterId }
        } else if (branch.workingChapterSelections.none { it.chapterID == chapterId }) {
            branch.workingChapterSelections +
                app.amber.feature.novel.model.NovelChapterSelection(chapterId, lastVersionId)
        } else {
            branch.workingChapterSelections
        }
        val branches = document.branches.toMutableList()
        branches[branchIndex] = branch.copy(
            workingChapterSelections = finalSelections,
            workingRevision = branch.workingRevision + 1,
            syncStatus = NovelBranchSyncStatus.NeedsSync,
            updatedAt = now,
        )
        val project = document.project.copy(
            revision = document.project.revision + 1,
            updatedAt = now,
        )
        val outcome = NovelOutcome.ManualEditSaved(
            projectID = projectId,
            branchID = branchId,
            chapterVersionID = lastVersionId,
            workingRevision = branches[branchIndex].workingRevision,
            revision = project.revision,
        )
        val next = document.copy(
            project = project,
            branches = branches,
            chapters = chapters,
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = operationId,
                kind = NovelOperationKind.SaveManualEdit,
                payloadSHA256 = app.amber.feature.novel.serialization.sha256HexOfUtf8(
                    "setChapterDiscarded:${chapterId.rawValue}:$discarded",
                ),
                outcome = outcome,
                appliedProjectRevision = project.revision,
                appliedAt = now,
            ),
        )
        NovelDocumentValidator.validateTransition(document, next)
        return NovelReduceResult(next, outcome)
    }

    /**
     * Archive discussion messages up to [throughSequence] into decisionLog materials
     * and advance the session archive cursor (iOS-aligned MVP).
     */
    fun archiveDiscussion(
        projectId: app.amber.feature.novel.model.NovelProjectId,
        branchId: NovelBranchId,
        summary: String,
        decisions: List<ArchiveDecision>,
        throughSequence: Long,
        chapterId: app.amber.feature.novel.model.NovelChapterId? = null,
        expectedProjectRevision: Long,
        expectedBranchHeadRevision: Long,
        document: NovelProjectDocumentV1,
        operationId: NovelOperationId = NovelOperationId.generate(),
        archiveId: NovelMessageId = NovelMessageId.generate(),
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
        if (branch.lifecycle != NovelBranchLifecycle.Active) throw NovelError.BranchNotFound(branchId)
        if (branch.headRevision != expectedBranchHeadRevision) {
            throw NovelError.StaleBranchHeadRevision(expectedBranchHeadRevision, branch.headRevision)
        }
        if (branch.activeRunID != null) throw NovelError.ProjectBusy(projectId)
        if (branch.syncStatus == NovelBranchSyncStatus.NeedsSync) {
            throw NovelError.InvalidInput("Synchronize the working manuscript before archiving discussion.")
        }
        val sessionIndex = document.sessions.indexOfFirst {
            it.id == branch.sessionID && it.branchID == branch.id
        }
        if (sessionIndex < 0) throw NovelError.SessionNotFound(branch.sessionID)
        val session = document.sessions[sessionIndex]
        val previousSequence = when (val c = session.archiveCursor) {
            is NovelSessionCursor.Through -> c.sequence
            else -> -1L
        }
        if (throughSequence <= previousSequence ||
            session.messages.none { it.sequence == throughSequence }
        ) {
            throw NovelError.InvalidInput("Discussion archive cursor must advance within the Session history.")
        }
        val archivedMessages = session.messages.filter {
            it.sequence > previousSequence && it.sequence <= throughSequence
        }
        if (archivedMessages.isEmpty()) {
            throw NovelError.InvalidInput("Discussion archive has no new messages.")
        }
        val summaryNorm = summary.trim()
        if (summaryNorm.isEmpty()) throw NovelError.InvalidInput("Discussion archive summary is required.")
        if (summaryNorm.length > 300) {
            throw NovelError.InvalidInput("Discussion archive summary exceeds 300 characters.")
        }
        if (decisions.isEmpty()) {
            throw NovelError.InvalidInput("Discussion archive requires at least one confirmed decision.")
        }
        if (document.sessions.any { s ->
                s.messages.any { it.id == archiveId } ||
                    s.discussionArchives.any { it.id == archiveId }
            }
        ) {
            throw NovelError.ImmutableRecordConflict("discussion archive $archiveId")
        }
        if (chapterId != null && document.chapters.none { it.id == chapterId }) {
            throw NovelError.InvalidInput("Discussion archive references a missing chapter.")
        }
        if (decisions.map { it.materialId }.toSet().size != decisions.size ||
            decisions.map { it.revisionId }.toSet().size != decisions.size
        ) {
            throw NovelError.InvalidInput("Discussion archive repeats a decision identifier.")
        }

        var materials = document.materials.toList()
        var revisions = document.materialRevisions.toList()
        val decisionRevisionIds = mutableListOf<NovelMaterialRevisionId>()
        for (decision in decisions) {
            val topic = decision.topic.trim()
            val content = decision.decision.trim()
            if (topic.isEmpty()) throw NovelError.InvalidInput("Discussion decision topic is required.")
            if (content.isEmpty()) throw NovelError.InvalidInput("Discussion decision content is required.")
            if (materials.any { it.id == decision.materialId } ||
                revisions.any { it.id == decision.revisionId }
            ) {
                throw NovelError.ImmutableRecordConflict("discussion decision ${decision.materialId}")
            }
            decision.relatedMaterialId?.let { relatedId ->
                if (materials.none { it.id == relatedId && !it.isDeleted }) {
                    throw NovelError.InvalidInput("Discussion decision references a missing material.")
                }
            }
            val tags = decision.relatedMaterialId?.let { relatedId ->
                listOf("related-material:${relatedId}")
            }.orEmpty()
            val revision = NovelMaterialRevisionRecord(
                id = decision.revisionId,
                materialID = decision.materialId,
                revision = 1,
                title = topic,
                content = content,
                tags = tags,
                injectionMode = NovelInjectionMode.Always,
                createdAt = now,
                operationID = operationId,
            )
            revisions = revisions + revision
            materials = materials + NovelMaterialRecord(
                id = decision.materialId,
                kind = NovelMaterialKind.DecisionLog,
                currentRevisionID = revision.id,
                revisionIDs = listOf(revision.id),
                isDeleted = false,
            )
            decisionRevisionIds += revision.id
        }

        val archive = app.amber.feature.novel.model.NovelDiscussionArchiveRecord(
            id = archiveId,
            checkpointID = checkpointId,
            throughSequence = throughSequence,
            messageCount = archivedMessages.size,
            chapterID = chapterId,
            summary = summaryNorm,
            createdAt = now,
        )
        val sessions = document.sessions.toMutableList()
        sessions[sessionIndex] = session.copy(
            revision = session.revision + 1,
            archiveCursor = NovelSessionCursor.Through(throughSequence),
            discussionArchives = session.discussionArchives + archive,
        )
        val overrideIds = branch.overrideRevisionIDs + decisionRevisionIds
        val cursor = if (session.messages.isEmpty()) {
            NovelSessionCursor.Empty
        } else {
            NovelSessionCursor.Through(session.messages.maxOf { it.sequence })
        }
        val checkpoint = app.amber.feature.novel.model.NovelBranchCheckpointRecord(
            id = checkpointId,
            kind = app.amber.feature.novel.model.NovelCheckpointKind.DiscussionArchive,
            createdOnBranchID = branch.id,
            parentCheckpointID = branch.headCheckpointID,
            chapterSelections = branch.workingChapterSelections,
            stateSnapshotID = branch.currentStateSnapshotID,
            sessionCursor = cursor,
            branchOverrideRevisionIDs = overrideIds,
            sourceCandidateID = null,
            baseHeadRevision = branch.headRevision,
            operationID = operationId,
            createdAt = now,
        )
        val branches = document.branches.toMutableList()
        // Discussion archive advances head checkpoint like iOS (working manuscript unchanged).
        branches[branchIndex] = branch.copy(
            headCheckpointID = checkpoint.id,
            headRevision = branch.headRevision + 1,
            overrideRevisionIDs = overrideIds,
            updatedAt = now,
        )
        val project = document.project.copy(
            revision = document.project.revision + 1,
            configRevision = document.project.configRevision + 1,
            updatedAt = now,
        )
        val outcome = NovelOutcome.DiscussionArchived(
            projectID = projectId,
            branchID = branchId,
            archiveID = archiveId,
            checkpointID = checkpoint.id,
            decisionRevisionIDs = decisionRevisionIds,
            projectRevision = project.revision,
            configRevision = project.configRevision,
        )
        val next = document.copy(
            project = project,
            branches = branches,
            sessions = sessions,
            materials = materials,
            materialRevisions = revisions,
            checkpoints = document.checkpoints + checkpoint,
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = operationId,
                kind = NovelOperationKind.ArchiveDiscussion,
                payloadSHA256 = app.amber.feature.novel.serialization.sha256HexOfUtf8(
                    "archiveDiscussion:${branchId.rawValue}:$throughSequence:${decisions.size}",
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
