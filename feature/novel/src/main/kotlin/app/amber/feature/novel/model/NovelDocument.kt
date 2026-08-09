package app.amber.feature.novel.model

import kotlinx.serialization.Serializable

@Serializable
data class NovelProjectDocumentV1(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val project: NovelProjectRecord,
    val materials: List<NovelMaterialRecord> = emptyList(),
    val materialRevisions: List<NovelMaterialRevisionRecord> = emptyList(),
    val branches: List<NovelBranchRecord> = emptyList(),
    val sessions: List<NovelSessionRecord> = emptyList(),
    val chapters: List<NovelChapterRecord> = emptyList(),
    val chapterVersions: List<NovelChapterVersionRecord> = emptyList(),
    val events: List<NovelStoryEventRecord> = emptyList(),
    val stateSnapshots: List<NovelStateSnapshotRecord> = emptyList(),
    val checkpoints: List<NovelBranchCheckpointRecord> = emptyList(),
    val candidates: List<NovelCandidateRecord> = emptyList(),
    val injectionReceipts: List<NovelInjectionReceiptRecord> = emptyList(),
    val generationReceipts: List<NovelGenerationReceiptRecord> = emptyList(),
    val factAttempts: List<NovelFactAttemptRecord> = emptyList(),
    val polishTransactions: List<NovelPendingPolishTransactionRecord> = emptyList(),
    val polishAttempts: List<NovelPolishAttemptRecord> = emptyList(),
    val polishAssessments: List<NovelPolishAssessmentRecord> = emptyList(),
    val pendingOperations: List<NovelPendingOperationRecord> = emptyList(),
    val activeRuns: List<NovelActiveRunRecord> = emptyList(),
    val settingProposals: List<NovelSettingProposalRecord> = emptyList(),
    val chapterPlans: List<NovelChapterPlanRecord> = emptyList(),
    val upcomingArcs: List<NovelUpcomingArcRecord> = emptyList(),
    val appliedOperations: List<NovelAppliedOperationRecord> = emptyList(),
) {
    fun chapterPlan(branchId: NovelBranchId): NovelChapterPlanRecord? =
        chapterPlans.firstOrNull { it.branchID == branchId }

    fun confirmedChapterPlan(branchId: NovelBranchId): NovelChapterPlanRecord? =
        chapterPlan(branchId)?.takeIf { it.isConfirmed }

    fun upcomingArc(branchId: NovelBranchId): NovelUpcomingArcRecord? =
        upcomingArcs.firstOrNull { it.branchID == branchId }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

@Serializable
data class NovelPackageEnvelopeV1(
    val format: String,
    val envelopeVersion: Int,
    val projectSchemaVersion: Int,
    val projectID: NovelProjectId,
    val projectByteCount: Int,
    val projectSHA256: String,
    val projectJSONBase64: String,
)

@Serializable
data class NovelProjectSummary(
    val id: NovelProjectId,
    val name: String,
    val mainBranchID: NovelBranchId? = null,
    @kotlinx.serialization.Serializable(with = app.amber.feature.novel.serialization.NovelSwiftDateSerializer::class)
    val updatedAt: java.time.Instant,
    val revision: Long,
    val isDegraded: Boolean = false,
    val loadError: String? = null,
)

enum class NovelProjectLoadAccess {
    ReadWrite,
    DegradedPrevious,
}

data class NovelLoadedProject(
    val document: NovelProjectDocumentV1,
    val access: NovelProjectLoadAccess,
    val primaryFailure: String? = null,
)
