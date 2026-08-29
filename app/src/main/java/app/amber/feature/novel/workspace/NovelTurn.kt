package app.amber.feature.novel.workspace

import app.amber.core.agent.runtime.AgentArtifact
import app.amber.core.agent.runtime.AgentCapability
import app.amber.core.agent.runtime.AgentDescriptor
import app.amber.core.agent.runtime.AgentDescriptorId
import app.amber.core.agent.runtime.AgentInput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Kernel descriptor for one novel-workspace generation turn. Each turn runs
 * as an [app.amber.core.agent.runtime.AgentRunner] run so workspace turns
 * land in the unified run registry (run row + terminal CAS + artifact).
 */
object NovelTurnDescriptor {
    val ID = AgentDescriptorId("novel_turn")

    val value = AgentDescriptor(
        id = ID,
        version = "1.0.0",
        displayName = "Novel Workspace Turn",
        capabilities = setOf(AgentCapability.TOOL_USE),
    )
}

/**
 * Serializable identity of one workspace turn. The runtime objects a turn
 * needs (Settings/Model snapshots, the caller's [NovelWorkspaceRuntime]
 * instance whose pendingProposals the UI reads, the live event channel)
 * are not serializable and travel through the process-local
 * [NovelTurnPayloads] mailbox keyed by the runner run id.
 */
@Serializable
data class NovelTurnInput(
    @SerialName("project_path") val projectPath: String,
    @SerialName("branch_id") val branchId: String,
    @SerialName("branch_slug") val branchSlug: String,
    @SerialName("user_text") val userText: String,
    @SerialName("max_steps") val maxSteps: Int = 16,
    @SerialName("auto_approve_canon") val autoApproveCanon: Boolean = false,
    @SerialName("owner_job_id") val ownerJobId: String? = null,
    @SerialName("owner_execution_id") val ownerExecutionId: String? = null,
) : AgentInput

/**
 * In-process turn outcome. The full event stream (deltas, tool activity)
 * already went through the payload's channel; the artifact is the compact
 * terminal record on the run snapshot.
 */
@Serializable
data class NovelTurnArtifact(
    val success: Boolean,
    @SerialName("final_text") val finalText: String = "",
    @SerialName("proposal_id") val proposalId: String? = null,
    val error: String = "",
) : AgentArtifact
