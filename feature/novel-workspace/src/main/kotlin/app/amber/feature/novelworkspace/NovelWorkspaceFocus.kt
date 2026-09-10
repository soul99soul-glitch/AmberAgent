package app.amber.feature.novelworkspace

/** Optional navigation identity; resolving it never starts or resumes generation. */
data class NovelWorkspaceFocus(val branchSlug: String? = null, val jobId: String? = null) {
    data class Resolved(val branchSlug: String, val branchId: String, val jobId: String?)

    fun resolve(store: NovelWorkspaceStore): Resolved {
        val job = jobId?.let { id ->
            NovelWorkspaceGhostwriteJobs.load(store.rootDirectory, id)
                ?: throw NovelWorkspaceIoError("代笔任务不存在或记录无法读取")
        }
        if (job != null && branchSlug != null && branchSlug != job.branchSlug) {
            throw NovelWorkspaceIoError("代笔任务不属于指定分支")
        }
        val slug = branchSlug ?: job?.branchSlug ?: NovelWorkspaceManifest.parse(
            store.read(NovelWorkspacePaths.MANIFEST) ?: "",
        ).mainBranch
        require(slug.isNotBlank() && slug != ".." && slug.none { it == '/' || it == '\\' }) {
            "工作区分支无效"
        }
        if (store.read("${NovelWorkspacePaths.branchPrefix(slug)}/branch.md") == null) {
            throw NovelWorkspaceIoError("指定分支不存在")
        }
        val branchId = NovelWorkspaceLedger.branchId(store, NovelWorkspaceLedger.load(store.rootDirectory), slug)
            ?: throw NovelWorkspaceIoError("指定分支没有有效版本记录")
        return Resolved(slug, branchId, job?.id)
    }
}
