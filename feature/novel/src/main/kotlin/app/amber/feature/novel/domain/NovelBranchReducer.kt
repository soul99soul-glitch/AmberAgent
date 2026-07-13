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
        val project = document.project.copy(revision = document.project.revision + 1, updatedAt = now)
        val outcome = NovelOutcome.BranchHeadMoved(
            projectID = projectId,
            branchID = branchId,
            fromCheckpointID = head.id,
            toCheckpointID = parent.id,
            headRevision = branches[branchIndex].headRevision,
            revision = project.revision,
        )
        val next = document.copy(
            project = project,
            branches = branches,
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
}
