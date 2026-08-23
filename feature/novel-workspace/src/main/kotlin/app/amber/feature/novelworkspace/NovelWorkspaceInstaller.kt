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
            throw NovelWorkspaceFormatError("Install target is not empty: $projectDirectory")
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
        NovelWorkspaceLedger.save(
            NovelWorkspaceLedgerStore(
                head = commitId,
                heads = mapOf(branchId to commitId),
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
