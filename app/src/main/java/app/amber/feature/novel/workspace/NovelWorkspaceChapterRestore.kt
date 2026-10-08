package app.amber.feature.novel.workspace

import app.amber.feature.novelworkspace.NovelWorkspaceBranches
import app.amber.feature.novelworkspace.NovelWorkspaceChapterHistory
import app.amber.feature.novelworkspace.NovelWorkspaceCommit
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import java.io.File

/** Restore through the existing author-edit transaction, including its plot and following-chapter gates. */
object NovelWorkspaceChapterRestore {
    fun restore(
        runtime: NovelWorkspaceRuntime,
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        chapterPath: String,
        contentHash: String,
        expectedHeadId: String?,
        expectedCurrentHash: String,
    ): NovelWorkspaceCommit? = NovelWorkspaceGhostwriteJobs.withNoActiveBranch(projectDirectory, branchSlug) {
        if (NovelWorkspaceBranches.activeSlug(projectDirectory) != branchSlug) return@withNoActiveBranch null
        val branch = NovelWorkspaceStore(projectDirectory).read("${NovelWorkspacePaths.branchPrefix(branchSlug)}/branch.md")
            ?: return@withNoActiveBranch null
        if (NovelWorkspaceMarkdown.parseFile(branch).fields["id"] != branchId) return@withNoActiveBranch null
        val snapshot = NovelWorkspaceChapterHistory.snapshot(projectDirectory, branchId, branchSlug, chapterPath)
            ?: return@withNoActiveBranch null
        if (snapshot.headId != expectedHeadId || snapshot.currentContentHash != expectedCurrentHash) {
            return@withNoActiveBranch null
        }
        if (contentHash == expectedCurrentHash || snapshot.versions.none { it.contentHash == contentHash }) {
            return@withNoActiveBranch null
        }
        val content = NovelWorkspaceChapterHistory.read(projectDirectory, contentHash)
            ?: return@withNoActiveBranch null
        val parsed = NovelWorkspaceMarkdown.parseFile(content)
        runtime.saveChapterEdit(
            projectDirectory,
            branchId,
            branchSlug,
            chapterPath,
            parsed.fields["title"] ?: NovelWorkspacePaths.fileNameTitle(chapterPath),
            parsed.body,
        )
    }
}
