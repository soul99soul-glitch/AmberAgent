package app.amber.feature.novelworkspace

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 多分支的宿主侧操作：创建（从当前分支分叉）、切换、枚举、活跃分支持久化。
 *
 * 三处既有状态的查明结论（多分支接入时的依据）：
 * - `.amber/undo.json`：书级单条记录（原 [NovelWorkspaceUndoRecord] 无分支信息）。
 *   切分支后旧 undo 若继续生效，会把上一分支的文件内容恢复进当前分支工作树——数据级
 *   损坏。处理：记录写入时绑定 branchSlug（[NovelWorkspaceUndoRecord.branchSlug]），
 *   读取时分支不匹配视为无 undo（更保守的一方，见 Runtime 的 canUndo/undoLast）；
 *   历史 null 记录按 manifest.mainBranch 解释（引入分支绑定前运行时只会往主线提交，
 *   推断安全，且保住既有书的单级撤销）。
 * - `.amber/unresolved.json`：本就是分支级（`branches: Map<slug, entry>`，见
 *   [NovelWorkspaceUnresolvedFile]），切分支无需额外处理。
 * - `.amber/checkout/`「作者视图镜像」：由 [NovelWorkspaceStore.materializeCheckout]
 *   全量重建（installer / renameProject / 每次 commitTree / undoLast 之后调用），且
 *   镜像的是整棵书树（含全部分支目录），不是活跃分支的视图——切分支不产生陈旧镜像，
 *   无需在 switch 时重建；但 createBranch 在 commitTree 之外新增了文件，故创建后重建一次。
 */
object NovelWorkspaceBranches {

    /** One row of the branch sheet: slug + display title + fork/main flags. */
    data class NovelWorkspaceBranchInfo(
        val slug: String,
        val id: String?,
        val title: String,
        val isMain: Boolean,
        val isCurrent: Boolean,
    )

    /** 活跃分支标记：宿主私有 `.amber/branch.json`（auto-review.json 同款惯例）。 */
    @Serializable
    data class NovelWorkspaceActiveBranch(
        val branchSlug: String,
    )

    object ActiveBranchStore {
        private const val FILE_NAME = "branch.json"

        private val json = Json { ignoreUnknownKeys = true }

        private fun file(projectDirectory: File): File =
            File(File(projectDirectory, NovelWorkspaceLedger.DIRECTORY_NAME), FILE_NAME)

        fun load(projectDirectory: File): NovelWorkspaceActiveBranch? {
            val f = file(projectDirectory)
            if (!f.exists()) return null
            return try {
                json.decodeFromString(NovelWorkspaceActiveBranch.serializer(), f.readText(Charsets.UTF_8))
            } catch (error: Exception) {
                null
            }
        }

        fun save(active: NovelWorkspaceActiveBranch, projectDirectory: File) {
            val ledgerDir = File(projectDirectory, NovelWorkspaceLedger.DIRECTORY_NAME)
            if (!ledgerDir.exists() && !ledgerDir.mkdirs()) {
                throw NovelWorkspaceIoError("Cannot create ledger directory: $ledgerDir")
            }
            val destination = file(projectDirectory)
            val temp = File.createTempFile("novel-branch-", ".tmp", ledgerDir)
            try {
                temp.writeText(
                    json.encodeToString(NovelWorkspaceActiveBranch.serializer(), active),
                    Charsets.UTF_8,
                )
                java.io.RandomAccessFile(temp, "rw").use { it.fd.sync() }
                NovelWorkspaceLedger.atomicMove(temp, destination)
            } finally {
                temp.delete()
            }
        }
    }

    /**
     * 当前应使用的分支 slug：标记优先；缺失/损坏/形状非法/指向不存在的分支目录时回退
     * manifest.mainBranch（与跨端约定的默认主线一致）。
     *
     * 回退链（J6）：主线目录也必须存在才可回退——legacy 书的 manifest.mainBranchID 可能
     * 指向已归档/未迁出的分支，直接回退会让 branchId 解析为 null、页面全锁。主线目录
     * 缺失时取磁盘上第一个存在的分支目录（按 branch.md 枚举、slug 排序取首个，稳定且
     * 与分支 sheet 的枚举口径一致）；分支树全空才维持返回 mainBranch 的现状。
     *
     * 读取侧防御（与 createBranch 的名字校验同一规则，见 [isWellFormedBranchSlug]）：
     * branch.json 是宿主私产但位于可被手改的文件系统上，一个 `.` 开头的标记值会经
     * `branchPrefix` 拼出 `branches/..` 之类越出分支根的路径——形状校验不过即视为
     * 无标记，回退主线（兑现 KDoc 的回退承诺）。
     */
    fun activeSlug(projectDirectory: File): String {
        val store = NovelWorkspaceStore(projectDirectory)
        val main = NovelWorkspaceManifest.parse(store.read(NovelWorkspacePaths.MANIFEST) ?: "").mainBranch
        val marked = ActiveBranchStore.load(projectDirectory)?.branchSlug
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { isWellFormedBranchSlug(it) }
        if (marked != null && store.list(NovelWorkspacePaths.branchPrefix(marked)).isNotEmpty()) {
            return marked
        }
        if (store.list(NovelWorkspacePaths.branchPrefix(main)).isNotEmpty()) {
            return main
        }
        val existing = store.list(NovelWorkspacePaths.BRANCHES_DIR)
            .filter { it.endsWith("/branch.md") }
            .map { path ->
                path.removePrefix("${NovelWorkspacePaths.BRANCHES_DIR}/").removeSuffix("/branch.md")
            }
            .filter(::isWellFormedBranchSlug)
            .sorted()
        return existing.firstOrNull() ?: main
    }

    /**
     * 分支 slug 的形状校验（createBranch 写入侧与 activeSlug 读取侧共用）：非空、不以
     * 点号开头（同时拒绝 `..`）、不含路径分隔符。返回 false 的值对分支树不可见或会
     * 越出分支根，任何一侧都不允许它成为活跃/新建分支。
     */
    internal fun isWellFormedBranchSlug(slug: String): Boolean =
        slug.isNotEmpty() &&
            !slug.startsWith(".") &&
            !slug.contains('/') &&
            !slug.contains('\\')

    /** Branch rows for the sheet: ledger heads + on-disk `branches/<slug>/branch.md`. */
    fun list(projectDirectory: File, currentSlug: String): List<NovelWorkspaceBranchInfo> {
        val store = NovelWorkspaceStore(projectDirectory)
        val manifest = NovelWorkspaceManifest.parse(store.read(NovelWorkspacePaths.MANIFEST) ?: "")
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        return store.list(NovelWorkspacePaths.BRANCHES_DIR)
            .filter { it.endsWith("/branch.md") }
            .map { path ->
                val slug = path.removePrefix("${NovelWorkspacePaths.BRANCHES_DIR}/").removeSuffix("/branch.md")
                val parsed = NovelWorkspaceMarkdown.parseFile(store.read(path) ?: "")
                NovelWorkspaceBranchInfo(
                    slug = slug,
                    id = parsed.fields["id"],
                    title = parsed.fields["title"]?.takeIf { it.isNotBlank() } ?: slug,
                    isMain = slug == manifest.mainBranch,
                    isCurrent = slug == currentSlug,
                )
            }
            .sortedWith(compareByDescending<NovelWorkspaceBranchInfo> { it.isMain }.thenBy { it.slug })
    }

    /**
     * 从 [sourceSlug] 分叉出新分支 [newName]：复制源分支整个工作子树（branch.md 除外，
     * 新分支获得自己的 id/标题；chapters/plot/plan/discarded/setting 覆盖全部随行），
     * ledger 记一笔「分支」fork commit（parent = 源分支 head、树 = 当前全书快照）并把
     * `heads[newId]` 钉到该提交——两分支自此从同一内容分叉、各自独立推进。
     *
     * 守卫（与 [switchBranch] 同一条整书规则）：存在 running/paused 批次时拒绝。批次
     * Worker 会按 job 绑定的分支继续写盘并提交，而 createBranch 复制文件、落 fork
     * commit 不与 Worker 互斥——竞态会造出 committed-but-missing（fork commit 树里有、
     * 磁盘上没有/内容截半）的死分支，故分叉前整书禁止（比按分支检查更保守，与切换
     * 一致）。
     *
     * 名字校验：slug 非空（既有）且不得以点号开头——`slug` 不过滤 `.`，`.foo` 这类
     * 名字会创建出对 fileTree/分支枚举不可见的僵尸目录，`..` 更会在路径拼接时越出
     * 分支根。拒绝点号开头即同时拒绝 `..`。
     *
     * 复制逐文件流式进行（[Files.copy] 流式实现），不整树 readBytes，章节多时不放大内存。
     */
    fun createBranch(
        projectDirectory: File,
        sourceSlug: String,
        newName: String,
        now: Instant = Instant.now(),
        locale: Locale = Locale.CHINESE,
    ): NovelWorkspaceBranchInfo {
        val store = NovelWorkspaceStore(projectDirectory)
        val newSlug = NovelWorkspaceSlug.slug(newName)
        if (newSlug.isEmpty()) {
            throw NovelWorkspaceIoError(localized(locale, "分支名称无效：不能只包含空格或特殊字符", "Invalid branch name: it cannot contain only spaces or special characters."))
        }
        if (newSlug.startsWith(".")) {
            throw NovelWorkspaceIoError(localized(locale, "分支名称无效：不能以点号开头（「$newName」）", "Invalid branch name: it cannot start with a dot (\"$newName\")."))
        }
        if (newSlug == sourceSlug) {
            throw NovelWorkspaceIoError(localized(locale, "新分支名称与当前分支相同", "The new branch name is the same as the current branch."))
        }
        NovelWorkspaceGhostwriteJobs.listActive(projectDirectory).firstOrNull()?.let { activeJob ->
            throw NovelWorkspaceIoError(
                localized(
                    locale,
                    "分支「${activeJob.branchSlug}」上有进行中的批次，完成或取消后才能新建分支",
                    "A batch is in progress on branch \"${activeJob.branchSlug}\". Complete or cancel it before creating a branch.",
                ),
            )
        }
        val sourcePrefix = NovelWorkspacePaths.branchPrefix(sourceSlug)
        if (store.list(sourcePrefix).isEmpty()) {
            throw NovelWorkspaceIoError(localized(locale, "源分支不存在：$sourceSlug", "Source branch does not exist: $sourceSlug"))
        }
        val targetPrefix = NovelWorkspacePaths.branchPrefix(newSlug)
        if (store.list(targetPrefix).isNotEmpty()) {
            throw NovelWorkspaceIoError(localized(locale, "分支「$newName」已存在", "Branch \"$newName\" already exists."))
        }
        val ledger = NovelWorkspaceLedger.load(projectDirectory)
        val sourceId = NovelWorkspaceLedger.branchId(store, ledger, sourceSlug)
            ?: throw NovelWorkspaceIoError(localized(locale, "源分支缺少可用的分支标识（branch.md）", "The source branch has no usable branch identifier (branch.md)."))
        val sourceHead = ledger.heads[sourceId]
            ?: throw NovelWorkspaceIoError(localized(locale, "源分支还没有任何提交，无法分叉", "The source branch has no commits to fork."))

        // Streamed copy of the whole source subtree except branch.md.
        val sourceDirectory = File(store.rootDirectory, sourcePrefix)
        val targetDirectory = File(store.rootDirectory, targetPrefix)
        copyRecursively(sourceDirectory, targetDirectory, skipName = "branch.md")

        val newId = UUID.randomUUID().toString().uppercase()
        store.write(
            "$targetPrefix/branch.md",
            NovelWorkspaceMarkdown.render(
                fields = listOf(
                    "id" to newId,
                    "kind" to "branch",
                    "title" to newName.trim(),
                    "syncStatus" to "synchronized",
                ),
                body = "",
            ),
        )
        // 分叉提交：heads[newId] 指向一棵包含分支副本的完整树（parent = 源分支 head）。
        // 若只把 head 钉在源提交上，ledger 树快照里没有新分支目录，committedChapterOrdinals
        // 等按树取数的门会视分支为空（代笔从第 1 章重写、进度恒 0）。
        val forkCommitId = UUID.randomUUID().toString().uppercase()
        val forkCommit = NovelWorkspaceLedger.makeCommit(
            id = forkCommitId,
            parentId = sourceHead,
            files = store.fileTree(),
            message = NovelWorkspaceLedger.Message.FORK,
            createdAt = now,
        )
        val withCommit = NovelWorkspaceLedger.appending(forkCommit, ledger).copy(
            heads = ledger.heads + (newId to forkCommitId),
        )
        // 与 commitTree 的镜像规则一致：全局 head 只在原本镜像源分支时随动。
        val mirrorsSource = ledger.head == null || ledger.heads[sourceId] == ledger.head
        val updated = if (mirrorsSource) withCommit else withCommit.copy(head = ledger.head)
        NovelWorkspaceLedger.save(updated, projectDirectory)
        // 新增文件发生在 commitTree 之外：重建整树镜像，作者视图不缺新分支目录。
        store.materializeCheckout()
        return NovelWorkspaceBranchInfo(
            slug = newSlug,
            id = newId,
            title = newName.trim(),
            isMain = false,
            isCurrent = false,
        )
    }

    /**
     * 切换活跃分支：写 `.amber/branch.json` 标记（读取方各自回退重建视图）。
     * 守卫：书内存在 running/paused 批次（任何分支）时拒绝——批次 Worker 按 job 绑定的
     * 分支写盘，虽不会写错分支，但切走后进度/审批/撤销全部错位，保守起见整书禁止。
     * checkout 为全树镜像（见本文件头注释），无需重建。
     */
    fun switchBranch(
        projectDirectory: File,
        targetSlug: String,
        locale: Locale = Locale.CHINESE,
    ) {
        val store = NovelWorkspaceStore(projectDirectory)
        if (store.list(NovelWorkspacePaths.branchPrefix(targetSlug)).isEmpty()) {
            throw NovelWorkspaceIoError(localized(locale, "分支不存在：$targetSlug", "Branch does not exist: $targetSlug"))
        }
        val activeJob = NovelWorkspaceGhostwriteJobs.listActive(projectDirectory).firstOrNull()
        if (activeJob != null) {
            throw NovelWorkspaceIoError(
                localized(
                    locale,
                    "分支「${activeJob.branchSlug}」上有进行中的批次，完成或取消后才能切换分支",
                    "A batch is in progress on branch \"${activeJob.branchSlug}\". Complete or cancel it before switching branches.",
                ),
            )
        }
        ActiveBranchStore.save(NovelWorkspaceActiveBranch(branchSlug = targetSlug), projectDirectory)
    }

    /** Streamed directory copy; [skipName] omits one direct child (branch.md). */
    private fun copyRecursively(source: File, target: File, skipName: String? = null) {
        if (!target.exists() && !target.mkdirs()) {
            throw NovelWorkspaceIoError("Cannot create branch directory: $target")
        }
        val children = source.listFiles() ?: return
        for (child in children) {
            if (skipName != null && child.name == skipName) continue
            val destination = File(target, child.name)
            if (child.isDirectory) {
                copyRecursively(child, destination)
            } else {
                Files.copy(child.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun localized(locale: Locale, chinese: String, english: String): String =
        if (locale.language.equals("zh", ignoreCase = true)) chinese else english
}
