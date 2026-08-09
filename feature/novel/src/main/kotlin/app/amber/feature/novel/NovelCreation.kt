package app.amber.feature.novel

import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelCandidateId
import app.amber.feature.novel.model.NovelChapterPlanId
import app.amber.feature.novel.model.NovelChapterPlanStatus
import app.amber.feature.novel.model.NovelChapterId
import app.amber.feature.novel.model.NovelCheckpointId
import app.amber.feature.novel.model.NovelCollectionTarget
import app.amber.feature.novel.model.NovelCollectionSource
import app.amber.feature.novel.model.NovelCollaborationMode
import app.amber.feature.novel.model.NovelGenerationGranularity
import app.amber.feature.novel.model.NovelMaterialId
import app.amber.feature.novel.model.NovelOutcome
import app.amber.feature.novel.model.NovelProjectCreationMode
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelProjectLoadAccess
import app.amber.feature.novel.model.NovelProjectModelPolicy
import app.amber.feature.novel.model.NovelProjectSummary
import app.amber.feature.novel.model.NovelProposalId
import app.amber.feature.novel.model.NovelQuickStartSeed
import app.amber.feature.novel.model.NovelRunId
import app.amber.feature.novel.model.NovelSessionId
import app.amber.feature.novel.model.NovelSessionMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory draft from LLM distill — not persisted until [NovelIntent.ArchiveDiscussion].
 * Mirrors iOS NovelDiscussionArchiveDraft.
 */
data class NovelDiscussionArchiveDraftDecision(
    val topic: String,
    val decision: String,
    val relatedMaterialId: NovelMaterialId? = null,
)

data class NovelDiscussionArchiveDraft(
    val projectId: NovelProjectId,
    val branchId: NovelBranchId,
    val sessionId: NovelSessionId,
    val throughSequence: Long,
    val chapterId: NovelChapterId? = null,
    val summary: String,
    val decisions: List<NovelDiscussionArchiveDraftDecision>,
)

data class NovelContinuityAuditReport(
    /** Issues backed by at least one exact reference to the uncollected candidate. */
    val issues: List<app.amber.feature.novel.domain.NovelContinuityIssueV1>,
    val failedChunkCount: Int,
    /** Blocking defects whose references are entirely in already-collected canon. */
    val canonicalOnlyBlockingIssues: List<app.amber.feature.novel.domain.NovelContinuityIssueV1> = emptyList(),
)

/** Confirmable decision row for [NovelIntent.ArchiveDiscussion] (optional related material link). */
data class NovelArchiveDecisionInput(
    val topic: String,
    val decision: String,
    val relatedMaterialId: NovelMaterialId? = null,
)

interface NovelCreation {
    val projectList: StateFlow<List<NovelProjectSummary>>

    suspend fun refreshProjects()

    suspend fun snapshot(query: NovelQuery): NovelSnapshot

    suspend fun perform(intent: NovelIntent): NovelOutcome

    fun start(request: NovelRunRequest): NovelRun

    fun interrupt(request: NovelInterruptRequest)

    /**
     * Run a one-shot continuity audit over the branch manuscript (not a durable run).
     * Throws [app.amber.feature.novel.domain.NovelError] / decode failures.
     */
    suspend fun continuityAudit(
        projectId: NovelProjectId,
        branchId: NovelBranchId,
    ): app.amber.feature.novel.domain.NovelContinuityAuditV1

    /** Audit the committed manuscript plus an available candidate as the next chapter. */
    suspend fun continuityAuditIncludingCandidate(
        projectId: NovelProjectId,
        branchId: NovelBranchId,
        candidateId: NovelCandidateId,
        maxCanonicalChapters: Int? = null,
    ): NovelContinuityAuditReport {
        throw app.amber.feature.novel.domain.NovelError.InvalidInput(
            "This novel runtime cannot audit candidate continuity.",
        )
    }

    /** Review an available whole-chapter candidate against the currently confirmed plan. */
    suspend fun acceptChapterPlan(
        projectId: NovelProjectId,
        branchId: NovelBranchId,
        candidateId: NovelCandidateId,
    ): app.amber.feature.novel.domain.NovelChapterPlanAcceptanceV1 {
        throw app.amber.feature.novel.domain.NovelError.InvalidInput(
            "This novel runtime cannot accept chapter-plan candidates.",
        )
    }

    /** Propose the next confirmed-plan payload from canonical state only; does not persist it. */
    suspend fun proposeNextChapterPlan(
        projectId: NovelProjectId,
        branchId: NovelBranchId,
        previousPlanSummary: String? = null,
    ): app.amber.feature.novel.domain.NovelChapterPlanProposalV1 {
        throw app.amber.feature.novel.domain.NovelError.InvalidInput(
            "This novel runtime cannot propose the next chapter plan.",
        )
    }

    /**
     * Distill recent discuss-plan messages into a confirmable archive draft.
     * Does **not** write DecisionLog / archiveCursor — user must confirm via
     * [NovelIntent.ArchiveDiscussion]. Throws on empty discussion, busy branch, decode failure.
     */
    suspend fun distillDiscussionArchive(
        projectId: NovelProjectId,
        branchId: NovelBranchId,
        chapterId: NovelChapterId? = null,
    ): NovelDiscussionArchiveDraft
}

sealed interface NovelQuery {
    data object Projects : NovelQuery
    data class Project(val projectId: NovelProjectId) : NovelQuery
    data class BranchMarkdown(val projectId: NovelProjectId, val branchId: NovelBranchId) : NovelQuery
    data class ProjectPackage(val projectId: NovelProjectId) : NovelQuery
}

sealed interface NovelSnapshot {
    data class Projects(val items: List<NovelProjectSummary>) : NovelSnapshot
    data class Project(
        val document: NovelProjectDocumentV1,
        val access: NovelProjectLoadAccess = NovelProjectLoadAccess.ReadWrite,
        val primaryFailure: String? = null,
    ) : NovelSnapshot

    data class Markdown(val fileName: String, val content: String) : NovelSnapshot
    data class PackageBytes(val fileName: String, val bytes: ByteArray) : NovelSnapshot {
        override fun equals(other: Any?): Boolean =
            other is PackageBytes && fileName == other.fileName && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * fileName.hashCode() + bytes.contentHashCode()
    }
}

sealed interface NovelIntent {
    data class CreateProject(
        val name: String,
        val mode: NovelProjectCreationMode,
        val quickStartSeed: NovelQuickStartSeed? = null,
        val branchName: String = "Main",
    ) : NovelIntent

    data class RenameProject(val projectId: NovelProjectId, val name: String) : NovelIntent
    data class DeleteProject(val projectId: NovelProjectId) : NovelIntent
    data class RestorePrevious(val projectId: NovelProjectId) : NovelIntent
    data class SetModelPolicy(
        val projectId: NovelProjectId,
        val policy: NovelProjectModelPolicy,
        val purpose: app.amber.feature.novel.domain.NovelModelPolicyPurpose =
            app.amber.feature.novel.domain.NovelModelPolicyPurpose.Creation,
    ) : NovelIntent

    /** Clear project state-sync model so resolve falls back to writing model. */
    data class ClearStateSyncModelPolicy(
        val projectId: NovelProjectId,
    ) : NovelIntent

    data class SetPolishPreference(val projectId: NovelProjectId, val polishPreference: String) : NovelIntent

    data class CollectCandidate(
        val projectId: NovelProjectId,
        val branchId: NovelBranchId,
        val candidateId: NovelCandidateId,
        val selectedText: String,
        val target: NovelCollectionTarget,
        val runStateDelta: Boolean = true,
        val source: NovelCollectionSource = NovelCollectionSource.User,
        /** Optional write-ahead identities used by the durable ghostwrite job ledger. */
        val operationId: app.amber.feature.novel.model.NovelOperationId? = null,
        val newChapterVersionId: app.amber.feature.novel.model.NovelChapterVersionId? = null,
        val newCheckpointId: NovelCheckpointId? = null,
        val newStateSnapshotId: app.amber.feature.novel.model.NovelStateSnapshotId? = null,
        /** Optional caller-owned compare-and-swap pins for durable background orchestration. */
        val expectedProjectRevision: Long? = null,
        val expectedConfigRevision: Long? = null,
        val expectedBranchHeadRevision: Long? = null,
    ) : NovelIntent

    data class SetCollaborationMode(
        val projectId: NovelProjectId,
        val branchId: NovelBranchId,
        val mode: NovelCollaborationMode,
    ) : NovelIntent

    data class SetPauseGhostwriteOnBlockingContinuity(
        val projectId: NovelProjectId,
        val enabled: Boolean,
    ) : NovelIntent

    data class UpsertChapterPlan(
        val projectId: NovelProjectId,
        val branchId: NovelBranchId,
        val planId: NovelChapterPlanId,
        val status: NovelChapterPlanStatus,
        val outlinePlacement: String,
        val goalAndConflict: String,
        val mustHappen: List<String>,
        val mustNotHappen: List<String>,
        val endingHook: String,
        val visibleFacts: List<String>,
        val operationId: app.amber.feature.novel.model.NovelOperationId? = null,
        /** Optional caller-owned compare-and-swap pins for durable background orchestration. */
        val expectedProjectRevision: Long? = null,
        val expectedConfigRevision: Long? = null,
        val expectedBranchHeadRevision: Long? = null,
    ) : NovelIntent

    data class ClearChapterPlan(
        val projectId: NovelProjectId,
        val branchId: NovelBranchId,
        val operationId: app.amber.feature.novel.model.NovelOperationId? = null,
        /** When present, only this exact confirmed plan may be cleared. */
        val expectedPlanId: NovelChapterPlanId? = null,
        val expectedPlanDigest: String? = null,
        /** Optional caller-owned compare-and-swap pins for durable background orchestration. */
        val expectedProjectRevision: Long? = null,
        val expectedConfigRevision: Long? = null,
        val expectedBranchHeadRevision: Long? = null,
    ) : NovelIntent

    data class UpsertUpcomingArc(
        val projectId: NovelProjectId,
        val branchId: NovelBranchId,
        val beats: List<String>,
    ) : NovelIntent

    data class ClearUpcomingArc(
        val projectId: NovelProjectId,
        val branchId: NovelBranchId,
    ) : NovelIntent

    data class ResolveProposal(
        val projectId: NovelProjectId,
        val proposalId: NovelProposalId,
        val accept: Boolean,
    ) : NovelIntent

    data class ForkBranch(
        val projectId: NovelProjectId,
        val sourceBranchId: NovelBranchId,
        val checkpointId: NovelCheckpointId,
        val name: String,
    ) : NovelIntent

    data class UndoHead(
        val projectId: NovelProjectId,
        val branchId: NovelBranchId,
    ) : NovelIntent

    data class RestoreChapterVersion(
        val projectId: NovelProjectId,
        val branchId: NovelBranchId,
        val targetChapterVersionId: app.amber.feature.novel.model.NovelChapterVersionId,
    ) : NovelIntent

    data class ArchiveDiscussion(
        val projectId: NovelProjectId,
        val branchId: NovelBranchId,
        val summary: String,
        val decisions: List<NovelArchiveDecisionInput>,
        val throughSequence: Long,
        val chapterId: app.amber.feature.novel.model.NovelChapterId? = null,
    ) : NovelIntent

    data class SaveManualEdit(
        val projectId: NovelProjectId,
        val branchId: NovelBranchId,
        val chapterId: NovelChapterId,
        val title: String,
        val content: String,
    ) : NovelIntent

    data class SyncManualEdits(
        val projectId: NovelProjectId,
        val branchId: NovelBranchId,
        val runStateDelta: Boolean = true,
        /** Ghostwrite uses strict mode: an incomplete rebuild must leave NeedsSync intact. */
        val failClosed: Boolean = false,
        /** Optional write-ahead identities used by the durable ghostwrite job ledger. */
        val operationId: app.amber.feature.novel.model.NovelOperationId? = null,
        val newCheckpointId: NovelCheckpointId? = null,
        val newStateSnapshotId: app.amber.feature.novel.model.NovelStateSnapshotId? = null,
        /** Optional caller-pinned source revisions; checked after exact replay reconciliation. */
        val expectedProjectRevision: Long? = null,
        val expectedConfigRevision: Long? = null,
        val expectedBranchHeadRevision: Long? = null,
    ) : NovelIntent

    data class AdoptPolishCandidate(
        val projectId: NovelProjectId,
        val branchId: NovelBranchId,
        val candidateId: NovelCandidateId,
        val asRewrite: Boolean = false,
    ) : NovelIntent

    data class RenameBranch(
        val projectId: NovelProjectId,
        val branchId: NovelBranchId,
        val name: String,
    ) : NovelIntent

    data class SetMainBranch(
        val projectId: NovelProjectId,
        val branchId: NovelBranchId,
    ) : NovelIntent

    data class ReviseMaterial(
        val projectId: NovelProjectId,
        val materialId: app.amber.feature.novel.model.NovelMaterialId? = null,
        val kind: app.amber.feature.novel.model.NovelMaterialKind,
        val title: String,
        val content: String,
    ) : NovelIntent

    data class DeleteMaterial(
        val projectId: NovelProjectId,
        val materialId: app.amber.feature.novel.model.NovelMaterialId,
    ) : NovelIntent

    data class ImportPackage(
        val bytes: ByteArray,
        val replaceProjectId: NovelProjectId? = null,
        /** When true and package id exists, remap to a new project id (keep both). */
        val keepBoth: Boolean = false,
    ) : NovelIntent {
        override fun equals(other: Any?): Boolean =
            other is ImportPackage &&
                bytes.contentEquals(other.bytes) &&
                replaceProjectId == other.replaceProjectId &&
                keepBoth == other.keepBoth

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + (replaceProjectId?.hashCode() ?: 0)
            result = 31 * result + keepBoth.hashCode()
            return result
        }
    }

    data class SetChapterDiscarded(
        val projectId: NovelProjectId,
        val branchId: NovelBranchId,
        val chapterId: NovelChapterId,
        val discarded: Boolean,
    ) : NovelIntent

    data class RetryTerminal(val projectId: NovelProjectId, val runId: NovelRunId) : NovelIntent
}

data class NovelRunRequest(
    /** Optional caller-owned identity for durable write-ahead orchestration. */
    val runId: NovelRunId? = null,
    val projectId: NovelProjectId,
    val branchId: NovelBranchId? = null,
    val userText: String,
    val mode: NovelSessionModeRequest = NovelSessionModeRequest.DiscussPlan,
    val granularity: NovelGenerationGranularityRequest? = null,
    val kind: NovelRunKindRequest? = null,
    val sourceChapterVersionId: app.amber.feature.novel.model.NovelChapterVersionId? = null,
    val ghostwritePlanId: NovelChapterPlanId? = null,
    /** Optional caller-owned compare-and-swap pins for durable background orchestration. */
    val expectedProjectRevision: Long? = null,
    val expectedConfigRevision: Long? = null,
    val expectedBranchHeadRevision: Long? = null,
)

enum class NovelSessionModeRequest { WriteProse, DiscussPlan }

enum class NovelGenerationGranularityRequest { Continuation, WholeChapter }

enum class NovelRunKindRequest { QuickStart, Discussion, Prose, Polish, Regenerate }

data class NovelInterruptRequest(
    val projectId: NovelProjectId,
    val runId: NovelRunId? = null,
    val reason: NovelInterruptReason = NovelInterruptReason.User,
    /** Project-wide interruption can preserve app-owned background runs exactly. */
    val excludedRunIds: Set<NovelRunId> = emptySet(),
)

enum class NovelInterruptReason { User, Background, RouteExit }

data class NovelRun(
    val id: NovelRunId,
    val events: Flow<NovelRunEvent>,
)

sealed interface NovelRunEvent {
    data object Started : NovelRunEvent
    data class Delta(val text: String) : NovelRunEvent
    /** Full-stream replacement (maps from [NovelModelEvent.TextReplacement]). */
    data class Replace(val text: String) : NovelRunEvent
    data class Completed(val content: String) : NovelRunEvent
    data class Interrupted(val partial: String) : NovelRunEvent
    data class Failed(val code: String, val message: String) : NovelRunEvent
}

internal fun NovelSessionModeRequest.toModel(): NovelSessionMode = when (this) {
    NovelSessionModeRequest.WriteProse -> NovelSessionMode.WriteProse
    NovelSessionModeRequest.DiscussPlan -> NovelSessionMode.DiscussPlan
}

internal fun NovelGenerationGranularityRequest.toModel(): NovelGenerationGranularity = when (this) {
    NovelGenerationGranularityRequest.Continuation -> NovelGenerationGranularity.Continuation
    NovelGenerationGranularityRequest.WholeChapter -> NovelGenerationGranularity.WholeChapter
}
