package app.amber.feature.novelworkspace

import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Install a validated file list as a brand-new workspace project.
 *
 * Import always creates a fresh project (locked decision): book files are written as-is,
 * one initial commit pins the tree, branch heads come from the parsed branch ids.
 */
object NovelWorkspaceInstaller {

    data class Result(
        val projectDirectory: File,
        val initialCommitId: String,
        val mainBranchId: String,
        val plotMissing: Boolean,
    )

    fun install(
        files: List<NovelWorkspaceFile>,
        projectDirectory: File,
        mainBranchId: String = UUID.randomUUID().toString().uppercase(),
        now: Instant = Instant.now(),
    ): Result {
        val parsed = NovelWorkspaceParsed.parse(files)
        if (!parsed.hasKnownFormat) {
            throw NovelWorkspaceFormatError(
                "Unrecognized workspace format: ${parsed.format} v${parsed.formatVersion}",
            )
        }
        if (projectDirectory.exists() && projectDirectory.listFiles()?.isNotEmpty() == true) {
            // 完整目标（已有 manifest = 已安装过的书）保持既有短路，绝不覆盖。
            if (File(projectDirectory, NovelWorkspacePaths.MANIFEST).exists()) {
                throw NovelWorkspaceFormatError("Install target is not empty: $projectDirectory")
            }
            // 不完整目标 = 上一次 install 中途被杀的半成品（写入中途的 .tmp、部分书
            // 文件；manifest 尚未落盘）。不清场时每次重试都撞「not empty」，失败永久
            // 累积（迁移重入/重复导入卡死）。清空重建；原稿（来源文件列表）不动。
            if (!projectDirectory.deleteRecursively()) {
                throw NovelWorkspaceIoError("Cannot clear incomplete install target: $projectDirectory")
            }
        }
        val store = NovelWorkspaceStore(projectDirectory)
        for (file in files) {
            NovelWorkspacePaths.validate(file.path)
            store.write(file.path, file.content)
        }

        val branchId = parsed.mainBranchID ?: mainBranchId
        val commitId = UUID.randomUUID().toString().uppercase()
        val commit = NovelWorkspaceLedger.makeCommit(
            id = commitId,
            parentId = null,
            files = store.fileTree(),
            message = NovelWorkspaceLedger.Message.INITIAL,
            createdAt = now,
        )
        // Register a head for EVERY branch that ships a branch.md with an id (multi-branch
        // legacy books): all their files are part of the initial tree, so each head starts
        // at the initial commit — exactly the fork-from-nothing shape. Trees without
        // branch.md fall back to the caller-provided id (single anonymous branch).
        val branchIds = buildSet {
            add(branchId)
            for (file in files) {
                val segments = file.path.split('/')
                if (segments.size == 3 && segments[0] == NovelWorkspacePaths.BRANCHES_DIR &&
                    segments[2] == "branch.md"
                ) {
                    NovelWorkspaceMarkdown.parseFile(file.content).fields["id"]
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }
        }
        NovelWorkspaceLedger.save(
            NovelWorkspaceLedgerStore(
                head = commitId,
                heads = branchIds.associateWith { commitId },
                commits = listOf(commit),
            ),
            projectDirectory,
        )
        store.materializeCheckout()
        return Result(
            projectDirectory = projectDirectory,
            initialCommitId = commitId,
            mainBranchId = branchId,
            plotMissing = parsed.plotMissing,
        )
    }
}
