package app.amber.feature.novel.domain

import app.amber.feature.novel.model.NovelAppliedOperationRecord
import app.amber.feature.novel.model.NovelBranchSyncStatus
import app.amber.feature.novel.model.NovelChapterId
import app.amber.feature.novel.model.NovelChapterSelection
import app.amber.feature.novel.model.NovelChapterVersionId
import app.amber.feature.novel.model.NovelChapterVersionKind
import app.amber.feature.novel.model.NovelChapterVersionRecord
import app.amber.feature.novel.model.NovelOperationId
import app.amber.feature.novel.model.NovelOperationKind
import app.amber.feature.novel.model.NovelOutcome
import app.amber.feature.novel.model.NovelProjectDocumentV1
import java.time.Instant
import java.util.UUID

object NovelManualEditReducer {
    fun saveEdit(
        projectId: app.amber.feature.novel.model.NovelProjectId,
        branchId: app.amber.feature.novel.model.NovelBranchId,
        chapterId: NovelChapterId,
        title: String,
        content: String,
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
        val branch = document.branches[branchIndex]
        if (branch.activeRunID != null) throw NovelError.ProjectBusy(projectId)
        val currentSel = branch.workingChapterSelections.firstOrNull { it.chapterID == chapterId }
            ?: throw NovelError.InvalidInput("Chapter not on branch")
        val currentVersion = document.chapterVersions.first { it.id == currentSel.versionID }
        val newVersion = NovelChapterVersionRecord(
            id = NovelChapterVersionId.generate(),
            chapterID = chapterId,
            kind = NovelChapterVersionKind.ManualEdit,
            title = title.trim().ifEmpty { currentVersion.title },
            content = content,
            factCompatibilityID = UUID.randomUUID(),
            sourceChapterVersionID = currentVersion.id,
            sourceCandidateID = null,
            createdAt = now,
            operationID = operationId,
        )
        val selections = branch.workingChapterSelections.map {
            if (it.chapterID == chapterId) NovelChapterSelection(chapterId, newVersion.id) else it
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
            appliedOperations = document.appliedOperations + NovelAppliedOperationRecord(
                operationID = operationId,
                kind = NovelOperationKind.SaveManualEdit,
                payloadSHA256 = app.amber.feature.novel.serialization.sha256HexOfUtf8(
                    "edit:${chapterId.rawValue}:${newVersion.id.rawValue}",
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
