package app.amber.feature.novel.workspace

import app.amber.feature.novelworkspace.NovelWorkspaceBranches
import app.amber.feature.novelworkspace.NovelWorkspaceCommit
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceIoError
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceManifest
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.sha256Hex
import java.io.File

/** Author-owned removal keeps the complete chapter file, so restoration preserves its identity. */
object NovelWorkspaceDiscardedChapters {
    data class Entry(
        val path: String,
        val sourcePath: String,
        val title: String,
        val ordinal: Int,
        val contentHash: String,
        val body: String,
        val restoreBlockedReason: String?,
    )

    data class Snapshot(
        val headId: String?,
        val treeDigest: String,
        val entries: List<Entry>,
    )

    fun snapshot(projectDirectory: File, branchId: String, branchSlug: String): Snapshot? {
        val store = NovelWorkspaceStore(projectDirectory)
        if (!hasCurrentBinding(projectDirectory, store, branchId, branchSlug)) return null
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        val entries = store.list().mapNotNull { path ->
            val sourcePath = sourcePath(store, branchSlug, path) ?: return@mapNotNull null
            val raw = store.read(path) ?: return@mapNotNull null
            val parsed = NovelWorkspaceMarkdown.parseFile(raw)
            val ordinal = NovelWorkspacePaths.chapterOrdinalFromPath(sourcePath)
                ?: parsed.fields["ordinal"]?.toIntOrNull()
                ?: 0
            Entry(
                path = path,
                sourcePath = sourcePath,
                title = parsed.fields["title"] ?: NovelWorkspacePaths.fileNameTitle(path),
                ordinal = ordinal,
                contentHash = sha256Hex(raw),
                body = parsed.body,
                restoreBlockedReason = restoreBlockedReason(store, branchSlug, sourcePath),
            )
        }.sortedWith(compareBy<Entry> { it.ordinal }.thenBy { it.path })
        return Snapshot(ledger.heads[branchId], NovelWorkspaceLedger.treeSHA256(store.fileTree()), entries)
    }

    /** Read the complete file for a preview; discarded chapters from another branch are excluded. */
    fun read(projectDirectory: File, branchSlug: String, discardedPath: String): String? {
        val store = NovelWorkspaceStore(projectDirectory)
        if (NovelWorkspaceBranches.activeSlug(projectDirectory) != branchSlug) return null
        if (sourcePath(store, branchSlug, discardedPath) == null) return null
        return store.read(discardedPath)
    }

    fun discard(
        runtime: NovelWorkspaceRuntime,
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        chapterPath: String,
        expectedHeadId: String?,
        expectedTreeDigest: String,
    ): NovelWorkspaceCommit = NovelWorkspaceGhostwriteJobs.withNoActiveBranch(projectDirectory, branchSlug) {
        val store = NovelWorkspaceStore(projectDirectory)
        requireCurrentBinding(projectDirectory, store, branchId, branchSlug)
        NovelWorkspacePaths.validate(chapterPath)
        val chapterPrefix = "${NovelWorkspacePaths.branchPrefix(branchSlug)}/chapters/"
        if (!chapterPath.startsWith(chapterPrefix) || !chapterPath.endsWith(".md")) {
            throw NovelWorkspaceIoError("只能移出当前分支的正文章节")
        }
        if ((NovelWorkspacePaths.chapterOrdinalFromPath(chapterPath) ?: 0) <= 0) {
            throw NovelWorkspaceIoError("该章节缺少可确认的章节序号，请先整理章节文件名")
        }
        val raw = store.read(chapterPath) ?: throw NovelWorkspaceIoError("章节已不存在，请刷新后重试")
        val discardedPath = "discarded/$branchSlug/${chapterPath.removePrefix(chapterPrefix)}"
        if (store.exists(discardedPath)) {
            throw NovelWorkspaceIoError("废稿中已有同一章节，请先恢复该废稿后再操作")
        }
        runtime.commitAuthorChanges(
            projectDirectory, branchId, branchSlug,
            changes = linkedMapOf(discardedPath to raw, chapterPath to null),
            expectedHeadId = expectedHeadId,
            expectedTreeDigest = expectedTreeDigest,
        )
    } ?: throw NovelWorkspaceIoError("当前分支仍被批次占用，请等待批次完成或取消批次")

    fun restore(
        runtime: NovelWorkspaceRuntime,
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        discardedPath: String,
        expectedHeadId: String?,
        expectedTreeDigest: String,
    ): NovelWorkspaceCommit = NovelWorkspaceGhostwriteJobs.withNoActiveBranch(projectDirectory, branchSlug) {
        val store = NovelWorkspaceStore(projectDirectory)
        requireCurrentBinding(projectDirectory, store, branchId, branchSlug)
        NovelWorkspacePaths.validate(discardedPath)
        val chapterPath = sourcePath(store, branchSlug, discardedPath)
            ?: throw NovelWorkspaceIoError("该废稿不属于当前分支")
        val raw = store.read(discardedPath) ?: throw NovelWorkspaceIoError("废稿已不存在，请刷新后重试")
        restoreBlockedReason(store, branchSlug, chapterPath)?.let { throw NovelWorkspaceIoError(it) }
        runtime.commitAuthorChanges(
            projectDirectory, branchId, branchSlug,
            changes = linkedMapOf(chapterPath to raw, discardedPath to null),
            expectedHeadId = expectedHeadId,
            expectedTreeDigest = expectedTreeDigest,
        )
    } ?: throw NovelWorkspaceIoError("当前分支仍被批次占用，请等待批次完成或取消批次")

    private fun hasCurrentBinding(
        projectDirectory: File,
        store: NovelWorkspaceStore,
        branchId: String,
        branchSlug: String,
    ): Boolean = NovelWorkspaceBranches.activeSlug(projectDirectory) == branchSlug &&
        NovelWorkspaceLedger.branchId(store, NovelWorkspaceLedger.load(projectDirectory), branchSlug) == branchId

    private fun requireCurrentBinding(
        projectDirectory: File,
        store: NovelWorkspaceStore,
        branchId: String,
        branchSlug: String,
    ) {
        if (!hasCurrentBinding(projectDirectory, store, branchId, branchSlug)) {
            throw NovelWorkspaceIoError("当前分支已变化，请关闭章节后重新打开")
        }
    }

    private fun sourcePath(store: NovelWorkspaceStore, branchSlug: String, discardedPath: String): String? {
        val globalPrefix = "discarded/$branchSlug/"
        val oldBranchPrefix = "${NovelWorkspacePaths.branchPrefix(branchSlug)}/discarded/"
        val relative = when {
            discardedPath.startsWith(globalPrefix) -> discardedPath.removePrefix(globalPrefix)
            discardedPath.startsWith(oldBranchPrefix) -> discardedPath.removePrefix(oldBranchPrefix)
            discardedPath.startsWith("discarded/") && discardedPath.count { it == '/' } == 1 &&
                NovelWorkspaceManifest.parse(store.read(NovelWorkspacePaths.MANIFEST) ?: "").mainBranch == branchSlug ->
                discardedPath.removePrefix("discarded/")
            else -> return null
        }
        if (relative.isEmpty() || !relative.endsWith(".md")) return null
        return "${NovelWorkspacePaths.branchPrefix(branchSlug)}/chapters/$relative"
    }

    private fun restoreBlockedReason(store: NovelWorkspaceStore, branchSlug: String, chapterPath: String): String? {
        val ordinal = NovelWorkspacePaths.chapterOrdinalFromPath(chapterPath)?.takeIf { it > 0 }
            ?: return "该废稿缺少可确认的原章节序号，可以复制正文后手动收录"
        if (store.exists(chapterPath) || ordinal in NovelWorkspaceLedger.workingChapterOrdinals(store, branchSlug)) {
            return "第 $ordinal 章已有正文，请先移出该章节后再恢复废稿"
        }
        return null
    }
}
