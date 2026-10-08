package app.amber.feature.novel.workspace

import app.amber.feature.novelworkspace.NovelWorkspaceCommit
import app.amber.feature.novelworkspace.NovelWorkspaceBranches
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceIoError
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary
import app.amber.feature.novelworkspace.NovelWorkspaceUndo
import app.amber.feature.novelworkspace.NovelWorkspaceUndoRecord
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import app.amber.feature.novelworkspace.sha256Hex
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns author approval state; the runtime supplies its existing commit/render operations. */
internal class NovelWorkspaceProposalOwner(
    private val commit: (NovelWorkspaceWriteProposal, () -> Unit) -> NovelWorkspaceCommit,
    private val mergedContent: (NovelWorkspaceStore, NovelWorkspaceWriteEntry) -> String,
) {
    private val pending = MutableStateFlow<List<NovelWorkspaceWriteProposal>>(emptyList())
    val proposals: StateFlow<List<NovelWorkspaceWriteProposal>> = pending.asStateFlow()

    fun load(directory: File) = update(directory, NovelWorkspaceProposalStore.load(directory))

    fun register(
        request: NovelWorkspaceRuntime.TurnRequest,
        entries: List<NovelWorkspaceWriteEntry>,
        baseHeadId: String?,
        baseTreeDigest: String?,
    ): NovelWorkspaceWriteProposal = NovelWorkspaceRestoreBoundary.write(request.restoreEpoch) {
        val store = NovelWorkspaceStore(request.projectDirectory)
        val ledger = NovelWorkspaceLedger.load(request.projectDirectory)
        val plan = store.read(NovelWorkspacePaths.branchPrefix(request.branchSlug) + "/plan/this-chapter.md")
            ?.let(NovelWorkspaceMarkdown::parseFile)
        val planBody = plan?.body?.trim().orEmpty()
        val proposal = NovelWorkspaceWriteProposal(
            id = UUID.randomUUID().toString().uppercase(),
            projectDirectory = request.projectDirectory,
            branchId = request.branchId,
            branchSlug = request.branchSlug,
            baseHeadId = if (baseTreeDigest != null) baseHeadId else ledger.headOf(request.branchId)?.id,
            baseTreeDigest = baseTreeDigest ?: NovelWorkspaceLedger.treeSHA256(store.fileTree()),
            planId = plan?.fields?.get("id")?.takeIf { it.isNotBlank() },
            planDigest = planBody.takeIf { it.isNotBlank() }?.let(::sha256Hex),
            entries = entries,
            createdAt = Instant.now(),
        )
        update(request.projectDirectory, NovelWorkspaceProposalStore.add(proposal))
        proposal
    }

    fun edit(id: String, entries: List<NovelWorkspaceWriteEntry>) = NovelWorkspaceRestoreBoundary.write {
        val proposal = pending.value.firstOrNull { it.id == id } ?: return@write
        update(proposal.projectDirectory, NovelWorkspaceProposalStore.edit(proposal.projectDirectory, id, entries))
    }

    fun reject(id: String) = NovelWorkspaceRestoreBoundary.write {
        val proposal = pending.value.firstOrNull { it.id == id } ?: return@write
        update(proposal.projectDirectory, NovelWorkspaceProposalStore.remove(proposal.projectDirectory, id))
    }

    fun approve(id: String) {
        val displayed = pending.value.firstOrNull { it.id == id } ?: return
        NovelWorkspaceGhostwriteJobs.withNoActiveBranch(displayed.projectDirectory, displayed.branchSlug) {
            val proposal = NovelWorkspaceProposalStore.load(displayed.projectDirectory).firstOrNull { it.id == id }
                ?: throw NovelWorkspaceIoError("提案不存在，请重新打开工作区")
            val store = NovelWorkspaceStore(proposal.projectDirectory)
            val ledger = NovelWorkspaceLedger.load(proposal.projectDirectory)
            if (NovelWorkspaceBranches.activeSlug(proposal.projectDirectory) != proposal.branchSlug ||
                NovelWorkspaceLedger.branchId(store, ledger, proposal.branchSlug) != proposal.branchId
            ) throw NovelWorkspaceIoError("提案属于其他分支，请切回对应分支后确认")
            if (ledger.headOf(proposal.branchId)?.id != proposal.baseHeadId ||
                NovelWorkspaceLedger.treeSHA256(store.fileTree()) != proposal.baseTreeDigest
            ) throw NovelWorkspaceIoError("提案已过期：正文或计划在确认前发生了变化，请重新生成")
            val unresolvedBefore = NovelWorkspaceUnresolvedStore.load(proposal.projectDirectory)
            val previous = proposal.entries.associate { it.path to store.read(it.path) }
            var persisted = false
            val committed = try {
                proposal.entries.forEach { store.write(it.path, mergedContent(store, it)) }
                commit(proposal) { persisted = true }
            } catch (error: Exception) {
                if (!persisted) previous.forEach { (path, content) ->
                    if (content == null) store.delete(path) else store.write(path, content)
                }
                throw error
            }
            update(proposal.projectDirectory, NovelWorkspaceProposalStore.remove(proposal.projectDirectory, id))
            NovelWorkspaceUndo.save(
                NovelWorkspaceUndoRecord(
                    commitId = committed.id,
                    parentCommitId = committed.parentId,
                    files = previous,
                    unresolvedBefore = unresolvedBefore,
                    branchSlug = proposal.branchSlug,
                ),
                proposal.projectDirectory,
            )
            committed
        } ?: throw NovelWorkspaceIoError("当前分支仍被代笔批次占用，请先让批次完成或取消后再批准提案")
    }

    private fun update(directory: File, proposals: List<NovelWorkspaceWriteProposal>) {
        pending.value = pending.value.filterNot { it.projectDirectory.absoluteFile == directory.absoluteFile } + proposals
    }
}

data class NovelWorkspaceWriteProposal(
    val id: String,
    val projectDirectory: File,
    val branchId: String,
    val branchSlug: String,
    val baseHeadId: String?,
    val baseTreeDigest: String,
    val planId: String? = null,
    val planDigest: String? = null,
    val entries: List<NovelWorkspaceWriteEntry>,
    val createdAt: Instant,
)
