package app.amber.feature.novel.workspace

import app.amber.feature.novelworkspace.NovelWorkspaceBranches
import app.amber.feature.novelworkspace.NovelWorkspaceCommit
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceIoError
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceManifest
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceUndo
import app.amber.feature.novelworkspace.NovelWorkspaceUndoRecord
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import java.io.File

/** Explicit author-confirmed file changes; model tools cannot invoke this owner. */
internal class NovelWorkspaceAuthorEdits(
    private val commit: (File, String, String, String, () -> Unit) -> NovelWorkspaceCommit,
) {
    fun apply(
        directory: File,
        branchId: String,
        branchSlug: String,
        changes: Map<String, String?>,
        expectedHeadId: String?,
        expectedTreeDigest: String,
        message: String,
    ): NovelWorkspaceCommit = NovelWorkspaceGhostwriteJobs.withNoActiveBranch(directory, branchSlug) {
        require(changes.isNotEmpty()) { "没有需要保存的改动" }
        val store = NovelWorkspaceStore(directory)
        val ledger = NovelWorkspaceLedger.load(directory)
        if (NovelWorkspaceBranches.activeSlug(directory) != branchSlug ||
            NovelWorkspaceLedger.branchId(store, ledger, branchSlug) != branchId
        ) throw NovelWorkspaceIoError("当前分支已变化，请重新打开后再操作")
        if (ledger.headOf(branchId)?.id != expectedHeadId || NovelWorkspaceLedger.treeSHA256(store.fileTree()) != expectedTreeDigest) {
            throw NovelWorkspaceIoError("工作区版本已变化，请重新打开后再操作")
        }
        val mainBranch = NovelWorkspaceManifest.parse(store.read(NovelWorkspacePaths.MANIFEST).orEmpty()).mainBranch
        changes.forEach { (path, content) ->
            NovelWorkspacePaths.validate(path)
            val currentPrefix = NovelWorkspacePaths.branchPrefix(branchSlug)
            val allowed = path.endsWith(".md") && (
                path.startsWith("setting/") || path.startsWith("$currentPrefix/setting/") ||
                    path.startsWith("$currentPrefix/chapters/") || path.startsWith("$currentPrefix/discarded/") ||
                    path.startsWith("discarded/$branchSlug/") ||
                    (content == null && branchSlug == mainBranch && path.split('/').size == 2 && path.startsWith("discarded/"))
                )
            if (!allowed) throw NovelWorkspaceIoError("该文件由宿主管理，不能通过作者资料操作修改：$path")
        }
        val previous = changes.keys.associateWith(store::read)
        val unresolvedBefore = NovelWorkspaceUnresolvedStore.load(directory)
        var persisted = false
        val committed = try {
            changes.forEach { (path, content) ->
                if (content == null) {
                    if (previous[path] != null && !store.delete(path)) throw NovelWorkspaceIoError("无法删除文件：$path")
                } else {
                    store.write(path, content)
                }
            }
            commit(directory, branchId, branchSlug, message) { persisted = true }
        } catch (error: Exception) {
            if (!persisted) previous.forEach { (path, content) ->
                try {
                    if (content == null) {
                        if (store.exists(path) && !store.delete(path)) throw NovelWorkspaceIoError("无法撤回新文件：$path")
                    } else {
                        store.write(path, content)
                    }
                } catch (rollbackError: Exception) {
                    error.addSuppressed(rollbackError)
                }
            }
            throw error
        }
        NovelWorkspaceUndo.save(
            NovelWorkspaceUndoRecord(committed.id, committed.parentId, previous, unresolvedBefore, branchSlug), directory,
        )
        committed
    } ?: throw NovelWorkspaceIoError("当前分支仍被代笔批次占用，请先让批次完成或取消后再修改")
}
