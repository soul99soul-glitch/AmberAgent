package app.amber.feature.novelworkspace

import java.time.Instant

/**
 * 设定/伏笔管理 tab 的数据组装（纯函数，UI 薄）：
 * - 设定文件区：`setting/` 树按子目录分组，附每文件最近一次 commit 时间；
 * - 伏笔区：本分支 foreshadowing 节点按 未回收/已回收 分组；
 * - 决定区：本分支 decisionLog 节点（只读展示）。
 *
 * 更新时间来自 ledger（最近一次实际改动该路径的 commit 的 createdAt）；
 * 从未提交过的自由写文件为 null（UI 显示「未提交」）。
 */
object NovelWorkspaceCatalog {

    data class NovelWorkspaceSettingEntry(
        val path: String,
        val title: String,
        /** Latest commit covering this file; null = never committed. */
        val updatedAt: Instant?,
    )

    data class NovelWorkspaceSettingGroup(
        /** Directory name (`characters` / `outline` / …); root-level files group under "根目录". */
        val directory: String,
        val entries: List<NovelWorkspaceSettingEntry>,
    )

    data class NovelWorkspaceForeshadowingEntry(
        val path: String,
        val title: String,
        val resolved: Boolean,
    )

    data class NovelWorkspaceDecisionEntry(
        val path: String,
        val title: String,
        val status: String?,
    )

    data class NovelWorkspaceCatalogData(
        val settingGroups: List<NovelWorkspaceSettingGroup>,
        val foreshadowing: List<NovelWorkspaceForeshadowingEntry>,
        val decisions: List<NovelWorkspaceDecisionEntry>,
    )

    fun load(store: NovelWorkspaceStore, ledger: NovelWorkspaceLedgerStore, branchSlug: String): NovelWorkspaceCatalogData {
        val lastCommitTimes = lastCommitTimes(ledger)
        val groups = settingGroups(store, lastCommitTimes)
        val nodes = NovelWorkspaceNodes.collect(store, branchSlug)
        val foreshadowing = nodes
            .filter { it.nodeKind == NovelWorkspaceNodes.KIND_FORESHADOWING }
            .map { node ->
                NovelWorkspaceForeshadowingEntry(
                    path = node.path,
                    title = node.title,
                    // Same boundary as the injected brief: only explicit resolved is resolved.
                    resolved = node.status.equals(
                        NovelWorkspaceNodes.STATUS_RESOLVED,
                        ignoreCase = true,
                    ),
                )
            }
            .sortedBy { it.title }
        val decisions = nodes
            .filter { it.nodeKind == NovelWorkspaceNodes.KIND_DECISION_LOG }
            .map { node -> NovelWorkspaceDecisionEntry(node.path, node.title, node.status) }
            .sortedBy { it.title }
        return NovelWorkspaceCatalogData(groups, foreshadowing, decisions)
    }

    private fun settingGroups(
        store: NovelWorkspaceStore,
        lastCommitTimes: Map<String, Instant>,
    ): List<NovelWorkspaceSettingGroup> {
        val paths = store.list(NovelWorkspacePaths.SETTING_DIR)
        val grouped = paths.groupBy { path ->
            val rest = path.removePrefix("${NovelWorkspacePaths.SETTING_DIR}/")
            if (rest.contains('/')) rest.substringBefore('/') else ROOT_GROUP
        }
        return grouped.map { (directory, groupPaths) ->
            NovelWorkspaceSettingGroup(
                directory = directory,
                entries = groupPaths.map { path ->
                    NovelWorkspaceSettingEntry(
                        path = path,
                        title = NovelWorkspaceMarkdown.parseFile(store.read(path) ?: "").fields["title"]
                            ?.takeIf { it.isNotBlank() }
                            ?: NovelWorkspacePaths.fileNameTitle(path),
                        updatedAt = lastCommitTimes[path],
                    )
                }.sortedBy { it.title },
            )
        }.sortedBy { it.directory }
    }

    /** path → newest commit time that actually changed the path. */
    private fun lastCommitTimes(ledger: NovelWorkspaceLedgerStore): Map<String, Instant> {
        val result = mutableMapOf<String, Instant>()
        for (commit in ledger.commits) {
            for (path in NovelWorkspaceLedger.changedPaths(commit, ledger.commits)) {
                val current = result[path]
                if (current == null || commit.createdAt.isAfter(current)) {
                    result[path] = commit.createdAt
                }
            }
        }
        return result
    }

    /** Locale-neutral sentinel; the UI supplies the localized label. */
    const val ROOT_GROUP = ""
}
