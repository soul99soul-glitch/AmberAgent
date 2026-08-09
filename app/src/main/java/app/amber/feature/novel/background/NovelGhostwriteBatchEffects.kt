package app.amber.feature.novel.background

import app.amber.feature.novel.NovelContinuityAuditReport
import app.amber.feature.novel.NovelCreation
import app.amber.feature.novel.NovelGenerationGranularityRequest
import app.amber.feature.novel.NovelIntent
import app.amber.feature.novel.NovelInterruptReason
import app.amber.feature.novel.NovelInterruptRequest
import app.amber.feature.novel.NovelRunEvent
import app.amber.feature.novel.NovelRunKindRequest
import app.amber.feature.novel.NovelRunRequest
import app.amber.feature.novel.NovelSessionModeRequest
import app.amber.feature.novel.NovelSnapshot
import app.amber.feature.novel.domain.NovelChapterPlanAcceptanceV1
import app.amber.feature.novel.domain.NovelChapterPlanProposalV1
import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelCandidateId
import app.amber.feature.novel.model.NovelChapterPlanId
import app.amber.feature.novel.model.NovelGhostwriteCorrectionPacketV1
import app.amber.feature.novel.model.NovelOutcome
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelRunId
import kotlinx.coroutines.flow.first

/** Narrow, injectable novel side-effect port used by the durable batch interpreter. */
internal interface NovelGhostwriteBatchEffects {
    suspend fun loadProject(projectId: NovelProjectId): NovelSnapshot.Project

    suspend fun proposePlan(
        projectId: NovelProjectId,
        branchId: NovelBranchId,
        previousPlanSummary: String?,
    ): NovelChapterPlanProposalV1

    suspend fun perform(intent: NovelIntent): NovelOutcome

    suspend fun generateWholeChapter(request: NovelGhostwriteProseRequest): NovelGhostwriteGenerationTerminal

    suspend fun acceptPlan(
        projectId: NovelProjectId,
        branchId: NovelBranchId,
        candidateId: NovelCandidateId,
    ): NovelChapterPlanAcceptanceV1

    suspend fun auditContinuity(
        projectId: NovelProjectId,
        branchId: NovelBranchId,
        candidateId: NovelCandidateId,
        maxCanonicalChapters: Int?,
    ): NovelContinuityAuditReport

    fun interruptExact(projectId: NovelProjectId, runId: NovelRunId)
}

internal data class NovelGhostwriteProseRequest(
    val projectId: NovelProjectId,
    val branchId: NovelBranchId,
    val runId: NovelRunId,
    val planId: NovelChapterPlanId,
    val expectedProjectRevision: Long,
    val expectedConfigRevision: Long,
    val expectedBranchHeadRevision: Long,
    val correctionPacket: NovelGhostwriteCorrectionPacketV1? = null,
)

internal sealed interface NovelGhostwriteGenerationTerminal {
    data object Completed : NovelGhostwriteGenerationTerminal

    data class Interrupted(val partial: String) : NovelGhostwriteGenerationTerminal

    data class Failed(val code: String, val message: String) : NovelGhostwriteGenerationTerminal
}

internal class NovelCreationGhostwriteBatchEffects(
    private val novelCreation: NovelCreation,
) : NovelGhostwriteBatchEffects {
    override suspend fun loadProject(projectId: NovelProjectId): NovelSnapshot.Project =
        novelCreation.snapshot(app.amber.feature.novel.NovelQuery.Project(projectId)) as NovelSnapshot.Project

    override suspend fun proposePlan(
        projectId: NovelProjectId,
        branchId: NovelBranchId,
        previousPlanSummary: String?,
    ): NovelChapterPlanProposalV1 = novelCreation.proposeNextChapterPlan(
        projectId = projectId,
        branchId = branchId,
        previousPlanSummary = previousPlanSummary,
    )

    override suspend fun perform(intent: NovelIntent): NovelOutcome = novelCreation.perform(intent)

    override suspend fun generateWholeChapter(
        request: NovelGhostwriteProseRequest,
    ): NovelGhostwriteGenerationTerminal {
        val run = novelCreation.start(
            NovelRunRequest(
                runId = request.runId,
                projectId = request.projectId,
                branchId = request.branchId,
                userText = novelGhostwriteUserText(request),
                mode = NovelSessionModeRequest.WriteProse,
                granularity = NovelGenerationGranularityRequest.WholeChapter,
                kind = NovelRunKindRequest.Prose,
                ghostwritePlanId = request.planId,
                expectedProjectRevision = request.expectedProjectRevision,
                expectedConfigRevision = request.expectedConfigRevision,
                expectedBranchHeadRevision = request.expectedBranchHeadRevision,
            ),
        )
        if (run.id != request.runId) {
            return NovelGhostwriteGenerationTerminal.Failed(
                code = "run_identity_mismatch",
                message = "NovelCreation did not honor the caller-owned run ID.",
            )
        }
        return when (val terminal = run.events.first { it.isTerminal }) {
            is NovelRunEvent.Completed -> NovelGhostwriteGenerationTerminal.Completed
            is NovelRunEvent.Interrupted -> NovelGhostwriteGenerationTerminal.Interrupted(terminal.partial)
            is NovelRunEvent.Failed -> NovelGhostwriteGenerationTerminal.Failed(
                code = terminal.code,
                message = terminal.message,
            )
            else -> error("Unexpected non-terminal novel event")
        }
    }

    override suspend fun acceptPlan(
        projectId: NovelProjectId,
        branchId: NovelBranchId,
        candidateId: NovelCandidateId,
    ): NovelChapterPlanAcceptanceV1 = novelCreation.acceptChapterPlan(
        projectId = projectId,
        branchId = branchId,
        candidateId = candidateId,
    )

    override suspend fun auditContinuity(
        projectId: NovelProjectId,
        branchId: NovelBranchId,
        candidateId: NovelCandidateId,
        maxCanonicalChapters: Int?,
    ): NovelContinuityAuditReport = novelCreation.continuityAuditIncludingCandidate(
        projectId = projectId,
        branchId = branchId,
        candidateId = candidateId,
        maxCanonicalChapters = maxCanonicalChapters,
    )

    override fun interruptExact(projectId: NovelProjectId, runId: NovelRunId) {
        novelCreation.interrupt(
            NovelInterruptRequest(
                projectId = projectId,
                runId = runId,
                reason = NovelInterruptReason.Background,
            ),
        )
    }

    private val NovelRunEvent.isTerminal: Boolean
        get() = this is NovelRunEvent.Completed ||
            this is NovelRunEvent.Interrupted ||
            this is NovelRunEvent.Failed

}

internal fun novelGhostwriteUserText(request: NovelGhostwriteProseRequest): String {
    val packet = request.correctionPacket ?: return INITIAL_GHOSTWRITE_INSTRUCTION
    require(packet.sourceCandidateID != null) {
        "A correction packet must be bound to its rejected candidate."
    }
    return buildString {
        appendLine("Rewrite the current whole chapter from a clean canonical context.")
        appendLine("The confirmed chapter plan in the system context remains authoritative.")
        appendLine("Do not continue beyond this chapter and do not discuss the review.")
        appendLine("Return only the replacement chapter prose.")
        appendLine()
        appendLine("STRUCTURED CORRECTION")
        appendLine("reasonCode: ${packet.reasonCode}")
        appendBoundedList("missingMustHappen", packet.missingMustHappen)
        appendBoundedList("forbiddenViolations", packet.forbiddenViolations)
        appendBoundedList("repetitionBeats", packet.repetitionBeats)
        appendBoundedList("continuityNotes", packet.continuityNotes)
    }
}

private fun StringBuilder.appendBoundedList(label: String, items: List<String>) {
    appendLine("$label:")
    if (items.isEmpty()) {
        appendLine("- none")
    } else {
        items.forEach { appendLine("- $it") }
    }
}

private const val INITIAL_GHOSTWRITE_INSTRUCTION =
    "Write the complete current chapter according to the confirmed chapter plan. " +
        "Return only chapter prose and do not continue into the next chapter."
