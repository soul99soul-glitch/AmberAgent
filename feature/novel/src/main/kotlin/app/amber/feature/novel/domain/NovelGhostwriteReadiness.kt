package app.amber.feature.novel.domain

import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelBranchSyncStatus
import app.amber.feature.novel.model.NovelMaterialKind
import app.amber.feature.novel.model.NovelPolishTransactionStatus
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelRunStatus

enum class NovelGhostwriteReadinessIssue(val displayName: String) {
    MainBranchRequired("代笔仅支持当前主分支"),
    MissingMasterOutline("缺少总剧情大纲"),
    MissingCharacter("至少需要一名人物档案"),
    MissingWritingRequirements("缺少写作要求"),
    BranchNeedsSync("当前分支资料待同步"),
    PendingOperations("仍有未完成的正文或同步操作"),
    ActiveRun("当前还有进行中的生成"),
    MissingChapterPlan("还没有确认的本章计划"),
}

object NovelGhostwriteReadiness {
    fun issues(
        document: NovelProjectDocumentV1,
        branchId: NovelBranchId,
        requireChapterPlan: Boolean,
    ): List<NovelGhostwriteReadinessIssue> = buildList {
        if (branchId != document.project.mainBranchID) {
            add(NovelGhostwriteReadinessIssue.MainBranchRequired)
        }

        val revisionsById = document.materialRevisions.associateBy { it.id }
        fun hasNonEmptyMaterial(kind: NovelMaterialKind): Boolean = document.materials.any { material ->
            !material.isDeleted &&
                material.kind == kind &&
                !revisionsById[material.currentRevisionID]?.content.isNullOrBlank()
        }
        if (!hasNonEmptyMaterial(NovelMaterialKind.MasterOutline)) {
            add(NovelGhostwriteReadinessIssue.MissingMasterOutline)
        }
        if (!hasNonEmptyMaterial(NovelMaterialKind.Character)) {
            add(NovelGhostwriteReadinessIssue.MissingCharacter)
        }
        if (!hasNonEmptyMaterial(NovelMaterialKind.WritingRequirements)) {
            add(NovelGhostwriteReadinessIssue.MissingWritingRequirements)
        }

        val branch = document.branches.firstOrNull { it.id == branchId }
        if (branch == null || branch.syncStatus != NovelBranchSyncStatus.Synchronized) {
            add(NovelGhostwriteReadinessIssue.BranchNeedsSync)
            return@buildList
        }
        if (document.pendingOperations.any { it.branchID == branchId } ||
            document.polishTransactions.any {
                it.branchID == branchId &&
                    it.status in setOf(
                        NovelPolishTransactionStatus.Pending,
                        NovelPolishTransactionStatus.Retryable,
                        NovelPolishTransactionStatus.Blocked,
                    )
            }
        ) {
            add(NovelGhostwriteReadinessIssue.PendingOperations)
        }
        if (branch.activeRunID != null ||
            document.activeRuns.any { it.branchID == branchId && it.status == NovelRunStatus.Running }
        ) {
            add(NovelGhostwriteReadinessIssue.ActiveRun)
        }
        if (requireChapterPlan && document.confirmedChapterPlan(branchId) == null) {
            add(NovelGhostwriteReadinessIssue.MissingChapterPlan)
        }
    }
}
