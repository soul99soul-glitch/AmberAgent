package app.amber.feature.novelworkspace

import java.io.File
import java.time.Instant
import java.util.UUID

data class NovelWorkspaceProjectSummary(
    val id: String,
    val name: String,
    val updatedAt: Instant,
    val mainBranchSlug: String,
)

/**
 * Project-level registry for workspace-format novels. Each project is one self-contained
 * directory (book tree + `.amber` ledger) under [rootDirectory]; no cross-project index.
 * The legacy JSON engine keeps its own root — the two coexist until the migration completes.
 */
class NovelWorkspaceProjectRepository(private val rootDirectory: File) {

    fun projectDirectory(id: String): File {
        require(isValidProjectId(id)) { "Invalid workspace project id: $id" }
        return File(rootDirectory, id.lowercase())
    }

    fun exists(id: String): Boolean = NovelWorkspaceStore(projectDirectory(id)).exists()

    /** Install a validated file list as a new project (import semantics: always a fresh id). */
    fun install(
        projectId: String,
        files: List<NovelWorkspaceFile>,
        now: Instant = Instant.now(),
    ): NovelWorkspaceInstaller.Result {
        if (!rootDirectory.exists() && !rootDirectory.mkdirs()) {
            throw NovelWorkspaceIoError("Cannot create workspace root: $rootDirectory")
        }
        val directory = projectDirectory(projectId)
        // 只拒绝完整目标（已有 manifest = 已安装过的书）。上次 install 中途被杀留下的
        // 半成品目录（无 manifest）放行，由 installer 清空重建——否则迁移重入每次都
        // 撞「already exists」，失败永久累积。
        if (directory.exists() && NovelWorkspaceStore(directory).exists()) {
            throw NovelWorkspaceIoError("Workspace project already exists: $projectId")
        }
        return NovelWorkspaceInstaller.install(files, directory, now = now)
    }

    /**
     * Create a brand-new blank book (manifest + project.md + one main branch + initial
     * commit + empty session ledger). No chapters yet — the agent fills those in.
     */
    fun createBlank(
        name: String,
        mainBranchName: String = "主线",
        now: Instant = Instant.now(),
    ): NovelWorkspaceInstaller.Result {
        val projectId = UUID.randomUUID().toString().uppercase()
        val branchId = UUID.randomUUID().toString().uppercase()
        val slug = NovelWorkspaceSlug.slug(mainBranchName).ifEmpty { "main" }
        val files = listOf(
            NovelWorkspaceFile(
                NovelWorkspacePaths.MANIFEST,
                NovelWorkspaceManifestRenderer.render(
                    exportedAt = now,
                    sourceProjectID = projectId,
                    sourceProjectRevision = 1,
                    sourceSchemaVersion = 1,
                    mainBranch = slug,
                ),
            ),
            NovelWorkspaceFile(
                NovelWorkspacePaths.PROJECT_FILE,
                NovelWorkspaceMarkdown.render(
                    fields = listOf(
                        "id" to projectId,
                        "kind" to "project",
                        "title" to name,
                        "collaborationMode" to "cocreation",
                        "polishPreference" to "",
                    ),
                    body = "",
                ),
            ),
            NovelWorkspaceFile(
                NovelWorkspacePaths.branchPrefix(slug) + "/branch.md",
                NovelWorkspaceMarkdown.render(
                    fields = listOf(
                        "id" to branchId,
                        "kind" to "branch",
                        "title" to mainBranchName,
                        "syncStatus" to "synchronized",
                    ),
                    body = "",
                ),
            ),
            NovelWorkspaceFile(
                NovelWorkspacePaths.branchPrefix(slug) + "/plot/current.md",
                NovelWorkspaceMarkdown.render(
                    fields = listOf(
                        "id" to UUID.randomUUID().toString().uppercase(),
                        "kind" to "plot",
                        "title" to "当前状态",
                    ),
                    body = "",
                ),
            ),
        )
        val result = install(projectId, files, now = now)
        // The sessions ledger starts empty (locked decision A).
        NovelWorkspaceSessions.save(
            NovelWorkspaceSessionsFile(),
            result.projectDirectory,
        )
        return result
    }

    /** Scan for readable workspace projects; unreadable directories are skipped, not fatal. */
    fun listProjects(): List<NovelWorkspaceProjectSummary> {
        val children = rootDirectory.listFiles() ?: return emptyList()
        return children
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { dir -> summarize(dir) }
            .sortedWith(compareByDescending<NovelWorkspaceProjectSummary> { it.updatedAt }.thenBy { it.name })
    }

    fun delete(id: String) {
        val directory = projectDirectory(id)
        if (directory.exists() && !directory.deleteRecursively()) {
            throw NovelWorkspaceIoError("Cannot delete workspace project: $id")
        }
    }

    /** Rename a project: update project.md title and commit (host-level, self-contained). */
    fun renameProject(id: String, newName: String, now: Instant = Instant.now()) {
        val directory = projectDirectory(id)
        val store = NovelWorkspaceStore(directory)
        val existing = store.read(NovelWorkspacePaths.PROJECT_FILE)
            ?: throw NovelWorkspaceIoError("项目不存在：$id")
        val parsed = NovelWorkspaceMarkdown.parseFile(existing)
        val fields = parsed.fields.toMutableMap()
        fields["title"] = newName.trim()
        store.write(
            NovelWorkspacePaths.PROJECT_FILE,
            NovelWorkspaceMarkdown.render(
                fields = fields.toList(),
                aliases = parsed.lists["aliases"].orEmpty(),
                body = parsed.body,
            ),
        )
        val ledger = NovelWorkspaceLedger.load(directory)
        val commitId = UUID.randomUUID().toString().uppercase()
        val commit = NovelWorkspaceLedger.makeCommit(
            id = commitId,
            parentId = ledger.head,
            files = store.fileTree(),
            message = NovelWorkspaceLedger.Message.GENERIC,
            createdAt = now,
        )
        NovelWorkspaceLedger.save(NovelWorkspaceLedger.appending(commit, ledger), directory)
        store.materializeCheckout()
    }

    private fun summarize(directory: File): NovelWorkspaceProjectSummary? = runCatching {
        val store = NovelWorkspaceStore(directory)
        if (!store.exists()) return@runCatching null
        val manifest = NovelWorkspaceManifest.parse(store.read(NovelWorkspacePaths.MANIFEST) ?: "")
        if (!manifest.isKnownFormat) return@runCatching null
        val ledger = NovelWorkspaceLedger.load(directory)
        val updatedAt = ledger.commits.maxOfOrNull { it.createdAt }
            ?: NovelWorkspaceTime.parse(manifest.exportedAt ?: "")
            ?: Instant.EPOCH
        NovelWorkspaceProjectSummary(
            id = directory.name.uppercase(),
            name = NovelWorkspaceProjectTitle.read(store),
            updatedAt = updatedAt,
            mainBranchSlug = manifest.mainBranch,
        )
    }.getOrNull()

    companion object {
        fun defaultRoot(filesDir: File): File = File(filesDir, "amberagent/novel-workspace")

        /** Uppercase UUID (NovelProjectId-compatible); directory names are lowercased. */
        fun isValidProjectId(id: String): Boolean {
            if (id.length != 36) return false
            return id.toCharArray().all { it.isDigit() || it in 'A'..'Z' || it in 'a'..'z' || it == '-' }
        }
    }
}

/** Title projection shared by the registry and any runtime layer. */
object NovelWorkspaceProjectTitle {
    fun read(store: NovelWorkspaceStore): String {
        val project = store.read(NovelWorkspacePaths.PROJECT_FILE) ?: return "未命名小说"
        return NovelWorkspaceMarkdown.parseFile(project).fields["title"]?.takeIf { it.isNotEmpty() }
            ?: "未命名小说"
    }
}
