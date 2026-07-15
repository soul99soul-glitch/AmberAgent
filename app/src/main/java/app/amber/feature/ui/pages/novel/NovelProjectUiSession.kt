package app.amber.feature.ui.pages.novel

import app.amber.feature.novel.model.NovelBranchId
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local selected-branch memory shared by Workspace and Settings VMs
 * for the same projectId (avoids dual-VM branch desync without sharing ViewModels).
 */
class NovelProjectUiSession {
    private val selectedBranchByProject = ConcurrentHashMap<String, NovelBranchId>()

    fun selectedBranch(projectId: String): NovelBranchId? =
        selectedBranchByProject[projectId]

    fun setSelectedBranch(projectId: String, branchId: NovelBranchId) {
        selectedBranchByProject[projectId] = branchId
    }
}
