package app.amber.feature.novelworkspace

import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 多分支存储层测试：createBranch 复制完整性（多章 + plot 流式复制）、heads 注册、
 * slug 冲突拒绝、activeBranch 标记读写与回退、undo 分支绑定语义（书级 undo.json 的
 * 分支安全）、switch 后 checkout 视图、installer 多分支 heads 注册、导出按分支。
 */
class NovelWorkspaceBranchesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val exportedAt = Instant.parse("2026-08-19T00:00:00Z")

    /** Main branch with two chapters + plot, installed with id B-1. */
    private fun installProject(): File {
        val dir = tempFolder.root.resolve("project")
        val files = mutableListOf(
            NovelWorkspaceFile(
                "manifest.yaml",
                NovelWorkspaceManifestRenderer.render(
                    exportedAt = exportedAt,
                    sourceProjectID = "p-1",
                    sourceProjectRevision = 1,
                    sourceSchemaVersion = 1,
                    mainBranch = "主线",
                ),
            ),
            NovelWorkspaceFile(
                "project.md",
                NovelWorkspaceMarkdown.render(
                    fields = listOf("id" to "p-1", "kind" to "project", "title" to "赵大来了"),
                    body = "",
                ),
            ),
            NovelWorkspaceFile(
                "branches/主线/branch.md",
                NovelWorkspaceMarkdown.render(
                    fields = listOf("id" to "B-1", "kind" to "branch", "title" to "主线"),
                    body = "",
                ),
            ),
            NovelWorkspaceFile(
                "branches/主线/chapters/001-山呼.md",
                NovelWorkspaceMarkdown.render(
                    fields = listOf("id" to "C-1", "kind" to "chapter", "title" to "山呼", "ordinal" to "1"),
                    body = "陈桥驿的风先到。",
                ),
            ),
            NovelWorkspaceFile(
                "branches/主线/chapters/002-入汴.md",
                NovelWorkspaceMarkdown.render(
                    fields = listOf("id" to "C-2", "kind" to "chapter", "title" to "入汴", "ordinal" to "2"),
                    body = "大军渡河。",
                ),
            ),
            NovelWorkspaceFile(
                "branches/主线/plot/current.md",
                NovelWorkspaceMarkdown.render(
                    fields = listOf("id" to "P-1", "kind" to "plot", "title" to "当前状态"),
                    body = "赵大夜行。",
                ),
            ),
            NovelWorkspaceFile(
                "branches/主线/plan/this-chapter.md",
                NovelWorkspaceMarkdown.render(
                    fields = listOf("id" to "PL-1", "kind" to "plan", "title" to "本章计划"),
                    body = "夜探军营。",
                ),
            ),
        )
        NovelWorkspaceInstaller.install(files, dir)
        return dir
    }

    // ── createBranch ────────────────────────────────────────────────

    @Test
    fun `createBranch copies the working subtree and lands a fork commit`() {
        val dir = installProject()
        val branch = NovelWorkspaceBranches.createBranch(dir, "主线", "番外线")
        assertEquals("番外线", branch.title)

        val store = NovelWorkspaceStore(dir)
        val ledger = NovelWorkspaceLedger.load(dir)
        val prefix = NovelWorkspacePaths.branchPrefix("番外线")
        // 章节 + 剧情 + 计划完整随行；branch.md 换成新分支自己的身份。
        assertEquals("陈桥驿的风先到。", bodyOf(store.read("$prefix/chapters/001-山呼.md")!!))
        assertEquals("大军渡河。", bodyOf(store.read("$prefix/chapters/002-入汴.md")!!))
        assertEquals("C-1", NovelWorkspaceMarkdown.parseFile(store.read("$prefix/chapters/001-山呼.md")!!).fields["id"])
        assertEquals("赵大夜行。", NovelWorkspaceMarkdown.parseFile(store.read("$prefix/plot/current.md")!!).body)
        assertEquals("夜探军营。", NovelWorkspaceMarkdown.parseFile(store.read("$prefix/plan/this-chapter.md")!!).body)
        val newMeta = NovelWorkspaceMarkdown.parseFile(store.read("$prefix/branch.md")!!)
        assertEquals(branch.id, newMeta.fields["id"])
        assertEquals("番外线", newMeta.fields["title"])
        assertNotEquals("B-1", branch.id)

        // fork commit：parent = 源分支 head，树快照已包含分支副本（committedChapterOrdinals
        // 由此立即非空——代笔进度/续写序号都按树取数）。
        val forkCommit = ledger.commit(ledger.heads[branch.id]!!)!!
        assertEquals(NovelWorkspaceLedger.Message.FORK, forkCommit.message)
        assertEquals(ledger.heads["B-1"], forkCommit.parentId)
        assertEquals(listOf(1, 2), NovelWorkspaceLedger.committedChapterOrdinals(store, ledger, "番外线"))
        // 新分支的 branchId 可解析、工作树章节序号可见。
        assertEquals(branch.id, NovelWorkspaceLedger.branchId(store, ledger, "番外线"))
        assertEquals(listOf(1, 2), NovelWorkspaceLedger.workingChapterOrdinals(store, "番外线"))
        // checkout 镜像重建后包含新分支目录。
        assertTrue(File(store.checkoutDirectory, "$prefix/chapters/001-山呼.md").exists())
    }

    @Test
    fun `createBranch refuses conflicts and blank names`() {
        val dir = installProject()
        NovelWorkspaceBranches.createBranch(dir, "主线", "番外线")

        val duplicate = runCatching { NovelWorkspaceBranches.createBranch(dir, "主线", "番外线") }
            .exceptionOrNull()
        assertTrue(duplicate is NovelWorkspaceIoError)
        assertTrue(duplicate!!.message!!.contains("已存在"))

        val sameSlug = runCatching { NovelWorkspaceBranches.createBranch(dir, "主线", "主线") }
            .exceptionOrNull()
        assertTrue(sameSlug is NovelWorkspaceIoError)

        val blank = runCatching { NovelWorkspaceBranches.createBranch(dir, "主线", " / : ") }
            .exceptionOrNull()
        assertTrue(blank is NovelWorkspaceIoError)

        val missingSource = runCatching { NovelWorkspaceBranches.createBranch(dir, "不存在", "x") }
            .exceptionOrNull()
        assertTrue(missingSource is NovelWorkspaceIoError)
    }

    // ── active branch marker ────────────────────────────────────────

    @Test
    fun `active marker round-trips and falls back to the main branch`() {
        val dir = installProject()
        // 缺失 → manifest.mainBranch。
        assertEquals("主线", NovelWorkspaceBranches.activeSlug(dir))

        NovelWorkspaceBranches.switchBranch(dir, "主线")
        assertEquals("主线", NovelWorkspaceBranches.activeSlug(dir))

        NovelWorkspaceBranches.createBranch(dir, "主线", "番外线")
        NovelWorkspaceBranches.switchBranch(dir, "番外线")
        assertEquals("番外线", NovelWorkspaceBranches.activeSlug(dir))
        val marker = NovelWorkspaceBranches.ActiveBranchStore.load(dir)
        assertEquals("番外线", marker?.branchSlug)

        // 标记指向已不存在的分支目录 → 回退主线（防御损坏/删除）。
        NovelWorkspaceBranches.ActiveBranchStore.save(
            NovelWorkspaceBranches.NovelWorkspaceActiveBranch(branchSlug = "幽灵分支"),
            dir,
        )
        assertEquals("主线", NovelWorkspaceBranches.activeSlug(dir))

        // 标记值形状非法（点号开头 / 「..」）→ 同样回退主线：这类值对分支树不可见，
        // 或会在 branchPrefix 拼接后越出分支根（读取侧防御，H5）。
        for (invalid in listOf(".foo", "..", "a/../b")) {
            NovelWorkspaceBranches.ActiveBranchStore.save(
                NovelWorkspaceBranches.NovelWorkspaceActiveBranch(branchSlug = invalid),
                dir,
            )
            assertEquals("标记「$invalid」应回退主线", "主线", NovelWorkspaceBranches.activeSlug(dir))
        }
    }

    /**
     * J6 回退链：标记缺失 + 主线目录也缺失（legacy 书 mainBranchID 指向已归档分支）时，
     * 取磁盘上第一个存在的分支（branch.md 枚举、slug 排序取首）——而不是返回一个
     * branchId 解析为 null 的幽灵主线把页面全锁；分支树全空才维持返回主线。
     */
    @Test
    fun `activeSlug falls back to the first existing branch when the main branch directory is missing`() {
        // legacy 双分支书：manifest 主线是 Main，但磁盘上只有 Alt-Path 有内容。
        val dir = tempFolder.root.resolve("legacy-main-missing")
        val files = listOf(
            NovelWorkspaceFile(
                "manifest.yaml",
                NovelWorkspaceManifestRenderer.render(
                    exportedAt = exportedAt,
                    sourceProjectID = "p-3",
                    sourceProjectRevision = 1,
                    sourceSchemaVersion = 1,
                    mainBranch = "Main",
                ),
            ),
            NovelWorkspaceFile(
                "project.md",
                NovelWorkspaceMarkdown.render(fields = listOf("id" to "p-3", "kind" to "project"), body = ""),
            ),
            NovelWorkspaceFile(
                "branches/Alt-Path/branch.md",
                NovelWorkspaceMarkdown.render(fields = listOf("id" to "B-ALT", "kind" to "branch"), body = ""),
            ),
            NovelWorkspaceFile(
                "branches/Alt-Path/chapters/001-a.md",
                NovelWorkspaceMarkdown.render(fields = listOf("id" to "C-1", "title" to "a"), body = "A"),
            ),
        )
        NovelWorkspaceInstaller.install(files, dir)
        // 无标记、主线目录缺失 → 回退到唯一存在的分支；其 branchId 可解析（页面不再全锁）。
        assertEquals("Alt-Path", NovelWorkspaceBranches.activeSlug(dir))
        val ledger = NovelWorkspaceLedger.load(dir)
        assertEquals(
            "B-ALT",
            NovelWorkspaceLedger.branchId(NovelWorkspaceStore(dir), ledger, NovelWorkspaceBranches.activeSlug(dir)),
        )

        // 多个存在的分支：slug 排序取首，结果稳定。
        NovelWorkspaceBranches.createBranch(dir, "Alt-Path", "番外线")
        assertEquals("Alt-Path", NovelWorkspaceBranches.activeSlug(dir))
        val sorted = listOf("Alt-Path", "番外线").sorted().first()
        assertEquals(sorted, NovelWorkspaceBranches.activeSlug(dir))

        // 标记指向存在的分支时优先级不变。
        NovelWorkspaceBranches.switchBranch(dir, "番外线")
        assertEquals("番外线", NovelWorkspaceBranches.activeSlug(dir))
    }

    /** 分支树全空（无任何 branch.md）时维持现状：返回 manifest 主线。 */
    @Test
    fun `activeSlug keeps the manifest main branch when no branch exists at all`() {
        val dir = tempFolder.root.resolve("no-branches")
        val files = listOf(
            NovelWorkspaceFile(
                "manifest.yaml",
                NovelWorkspaceManifestRenderer.render(
                    exportedAt = exportedAt,
                    sourceProjectID = "p-4",
                    sourceProjectRevision = 1,
                    sourceSchemaVersion = 1,
                    mainBranch = "主线",
                ),
            ),
            NovelWorkspaceFile(
                "project.md",
                NovelWorkspaceMarkdown.render(fields = listOf("id" to "p-4", "kind" to "project"), body = ""),
            ),
        )
        NovelWorkspaceInstaller.install(files, dir)
        assertEquals("主线", NovelWorkspaceBranches.activeSlug(dir))
    }

    @Test
    fun `switchBranch refuses while any batch job is alive`() {
        val dir = installProject()
        NovelWorkspaceBranches.createBranch(dir, "主线", "番外线")
        NovelWorkspaceGhostwriteJobs.save(
            NovelWorkspaceGhostwriteJob(
                id = "J-1",
                branchSlug = "主线",
                targetChapterCount = 3,
                startOrdinal = 2,
                status = NovelWorkspaceGhostwriteJob.STATUS_RUNNING,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ),
            dir,
        )
        val error = runCatching { NovelWorkspaceBranches.switchBranch(dir, "番外线") }
            .exceptionOrNull()
        assertTrue(error is NovelWorkspaceIoError)
        assertTrue(error!!.message!!.contains("切换分支"))

        // 批次终结后放行。
        NovelWorkspaceGhostwriteJobs.transition(dir, "J-1", setOf(NovelWorkspaceGhostwriteJob.STATUS_RUNNING),
            NovelWorkspaceGhostwriteJob.STATUS_COMPLETED)
        NovelWorkspaceBranches.switchBranch(dir, "番外线")
        assertEquals("番外线", NovelWorkspaceBranches.activeSlug(dir))
    }

    /** createBranch 与批次 Worker 的复制竞态守卫：有活跃批次（任何分支）时整书拒绝分叉。 */
    @Test
    fun `createBranch refuses while any batch job is alive`() {
        val dir = installProject()
        val store = NovelWorkspaceStore(dir)
        NovelWorkspaceGhostwriteJobs.save(
            NovelWorkspaceGhostwriteJob(
                id = "J-CREATE",
                branchSlug = "主线",
                targetChapterCount = 2,
                startOrdinal = 1,
                status = NovelWorkspaceGhostwriteJob.STATUS_RUNNING,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ),
            dir,
        )
        val error = runCatching { NovelWorkspaceBranches.createBranch(dir, "主线", "番外线") }
            .exceptionOrNull()
        assertTrue(error is NovelWorkspaceIoError)
        assertTrue(error!!.message!!.contains("新建分支"))
        // 拒绝先于任何落盘副作用：无分支目录、无 fork commit（否则会留下死分支）。
        assertTrue(store.list("branches").none { it.startsWith("branches/番外线") })
        assertEquals(1, NovelWorkspaceLedger.load(dir).commits.size)

        // 批次终结后放行。
        NovelWorkspaceGhostwriteJobs.transition(
            dir,
            "J-CREATE",
            setOf(NovelWorkspaceGhostwriteJob.STATUS_RUNNING),
            NovelWorkspaceGhostwriteJob.STATUS_COMPLETED,
        )
        NovelWorkspaceBranches.createBranch(dir, "主线", "番外线")
        assertTrue(store.list("branches").any { it.startsWith("branches/番外线") })
    }

    /** 点号开头 / 「..」的名字会产出对分支树不可见的僵尸目录或越出分支根，一律拒绝。 */
    @Test
    fun `createBranch rejects dot-leading and dot-only names`() {
        val dir = installProject()
        val store = NovelWorkspaceStore(dir)
        for (name in listOf(".foo", "..", "....", ".隐藏线")) {
            val error = runCatching { NovelWorkspaceBranches.createBranch(dir, "主线", name) }
                .exceptionOrNull()
            assertTrue("「$name」应被拒绝", error is NovelWorkspaceIoError)
            assertTrue(error!!.message!!.contains("点号"))
            // 无僵尸目录落盘、无 fork commit 落账。
            assertTrue(store.list("branches").none { it.startsWith("branches/.") })
            assertEquals(1, NovelWorkspaceLedger.load(dir).commits.size)
        }
    }

    // ── staleness 只沿本分支 ancestry（跨分支快照污染修复的 pin）─────────

    /**
     * 与 Runtime.commitTree 同构的最小提交助手：parent = 分支 head、树 = 全书快照、
     * 只前进该分支 head（全局 head 只在原本镜像该分支时随动）。
     */
    private fun commitOnBranch(
        dir: File,
        store: NovelWorkspaceStore,
        branchId: String,
        message: String,
        at: Instant,
    ) {
        val ledger = NovelWorkspaceLedger.load(dir)
        val commitId = "C-" + java.util.UUID.randomUUID().toString().take(12).uppercase()
        val commit = NovelWorkspaceLedger.makeCommit(
            id = commitId,
            parentId = ledger.heads[branchId],
            files = store.fileTree(),
            message = message,
            createdAt = at,
        )
        val mirrors = ledger.head == null || ledger.heads[branchId] == ledger.head
        val updated = NovelWorkspaceLedger.appending(commit, ledger).copy(
            heads = ledger.heads + (branchId to commitId),
        )
        NovelWorkspaceLedger.save(if (mirrors) updated else updated.copy(head = ledger.head), dir)
    }

    private fun writeChapter(store: NovelWorkspaceStore, path: String, body: String) {
        store.write(
            path,
            NovelWorkspaceMarkdown.render(
                fields = listOf(
                    "id" to "X-" + path.hashCode(),
                    "kind" to "chapter",
                    "title" to NovelWorkspacePaths.fileNameTitle(path),
                    "ordinal" to NovelWorkspacePaths.chapterOrdinalFromPath(path).toString(),
                ),
                body = body,
            ),
        )
    }

    @Test
    fun `branch plot staleness survives commits on other branches`() {
        val dir = installProject()
        val store = NovelWorkspaceStore(dir)
        val t0 = Instant.parse("2026-08-20T00:00:00Z")

        // 初始提交树含全书 → chapters/plot 同位置 → 主线 fresh（单分支书 ancestry=全链，
        // 与旧实现逐位一致）。
        val fresh = NovelWorkspaceLedger.load(dir)
        assertFalse(NovelWorkspaceLedger.isPlotStale(store, fresh, "主线"))

        // 主线新增一章（无剧情同步）→ 主线真实落后。
        writeChapter(store, "branches/主线/chapters/003-陈桥.md", "第三夜。")
        commitOnBranch(dir, store, "B-1", NovelWorkspaceLedger.Message.COLLECTION, t0)
        assertTrue(NovelWorkspaceLedger.isPlotStale(store, NovelWorkspaceLedger.load(dir), "主线"))

        val forkAt = NovelWorkspaceBranches.createBranch(dir, "主线", "番外线")

        // fork commit 中性：changedPaths 只含新分支目录（chapters 与 plot 同时变更），
        // 新分支 staleness 归零，不继承源分支的落后。
        assertTrue(NovelWorkspaceLedger.isPlotStale(store, NovelWorkspaceLedger.load(dir), "主线"))
        assertFalse(NovelWorkspaceLedger.isPlotStale(store, NovelWorkspaceLedger.load(dir), "番外线"))

        // 分支补上剧情指针 → fresh；再写一章不补 → 真实落后。
        store.write(
            "branches/番外线/plot/current.md",
            NovelWorkspaceMarkdown.render(
                fields = listOf("id" to "P-B", "kind" to "plot", "title" to "当前状态"),
                body = "赵大夜行。分支线推进。",
            ),
        )
        commitOnBranch(dir, store, checkNotNull(forkAt.id), NovelWorkspaceLedger.Message.PLOT_POINTER, t0.plusSeconds(60))
        assertFalse(NovelWorkspaceLedger.isPlotStale(store, NovelWorkspaceLedger.load(dir), "番外线"))

        writeChapter(store, "branches/番外线/chapters/003-歧路.md", "岔路。")
        commitOnBranch(dir, store, checkNotNull(forkAt.id), NovelWorkspaceLedger.Message.COLLECTION, t0.plusSeconds(120))
        assertTrue(NovelWorkspaceLedger.isPlotStale(store, NovelWorkspaceLedger.load(dir), "番外线"))

        // THE PIN：主线此后任意提交，其树 diff 必然包含番外线全部文件（parent 树不含
        // 该目录）——旧的全链扫描据此把番外线的真实落后洗成「不落后」。ancestry 推导
        // 下主线提交不在番外线的链上，staleness 不被遮蔽。
        writeChapter(store, "branches/主线/chapters/004-渡口.md", "渡口。")
        commitOnBranch(dir, store, "B-1", NovelWorkspaceLedger.Message.COLLECTION, t0.plusSeconds(180))
        assertTrue("主线提交后番外线仍应 stale", NovelWorkspaceLedger.isPlotStale(store, NovelWorkspaceLedger.load(dir), "番外线"))

        // 反方向同样隔离：主线补剧情指针后 fresh，番外线的提交不影响主线结论。
        store.write(
            "branches/主线/plot/current.md",
            NovelWorkspaceMarkdown.render(
                fields = listOf("id" to "P-1", "kind" to "plot", "title" to "当前状态"),
                body = "赵大夜行。主线推进到渡口。",
            ),
        )
        commitOnBranch(dir, store, "B-1", NovelWorkspaceLedger.Message.PLOT_POINTER, t0.plusSeconds(240))
        assertFalse(NovelWorkspaceLedger.isPlotStale(store, NovelWorkspaceLedger.load(dir), "主线"))
        assertTrue(NovelWorkspaceLedger.isPlotStale(store, NovelWorkspaceLedger.load(dir), "番外线"))
    }

    /** 悬挂润色自愈判定与 isPlotStale 同一枚 ancestry 镜头：跨分支提交不抹掉悬挂形状。 */
    @Test
    fun `dangling polish heal shape follows the branch ancestry too`() {
        val dir = installProject()
        val store = NovelWorkspaceStore(dir)
        val t0 = Instant.parse("2026-08-20T00:00:00Z")
        val fork = NovelWorkspaceBranches.createBranch(dir, "主线", "番外线")

        // 分支上「润色」第 2 章后崩溃（配对指针未落）→ 唯一可自愈的 staleness 形状。
        writeChapter(store, "branches/番外线/chapters/002-入汴.md", "润色后的大军渡河。")
        commitOnBranch(dir, store, checkNotNull(fork.id), NovelWorkspaceLedger.Message.POLISH, t0)
        assertEquals(
            2,
            NovelWorkspaceLedger.danglingPolishChapterOrdinal(store, NovelWorkspaceLedger.load(dir), "番外线"),
        )

        // 主线任意提交（旧全链扫描会刷新番外线的 chapters/plot 位置、抹掉悬挂形状）→ 仍可自愈。
        writeChapter(store, "branches/主线/chapters/003-陈桥.md", "第三夜。")
        commitOnBranch(dir, store, "B-1", NovelWorkspaceLedger.Message.COLLECTION, t0.plusSeconds(60))
        assertEquals(
            2,
            NovelWorkspaceLedger.danglingPolishChapterOrdinal(store, NovelWorkspaceLedger.load(dir), "番外线"),
        )
    }

    @Test
    fun `list marks the current and main branch rows`() {
        val dir = installProject()
        NovelWorkspaceBranches.createBranch(dir, "主线", "番外线")
        val rows = NovelWorkspaceBranches.list(dir, "番外线")
        assertEquals(2, rows.size)
        val main = rows.first { it.slug == "主线" }
        val side = rows.first { it.slug == "番外线" }
        assertTrue(main.isMain && !main.isCurrent)
        assertEquals("主线", main.title)
        assertTrue(side.isCurrent && !side.isMain)
        assertEquals("番外线", side.title)
    }

    // ── installer multi-head registration (legacy multi-branch books) ──

    @Test
    fun `installer registers a head for every branch shipped with branch md`() {
        val dir = tempFolder.root.resolve("multi")
        val files = listOf(
            NovelWorkspaceFile(
                "manifest.yaml",
                NovelWorkspaceManifestRenderer.render(
                    exportedAt = exportedAt,
                    sourceProjectID = "p-2",
                    sourceProjectRevision = 1,
                    sourceSchemaVersion = 1,
                    mainBranch = "Main",
                ),
            ),
            NovelWorkspaceFile(
                "project.md",
                NovelWorkspaceMarkdown.render(fields = listOf("id" to "p-2", "kind" to "project"), body = ""),
            ),
            NovelWorkspaceFile(
                "branches/Main/branch.md",
                NovelWorkspaceMarkdown.render(fields = listOf("id" to "B-MAIN", "kind" to "branch"), body = ""),
            ),
            NovelWorkspaceFile(
                "branches/Main/chapters/001-a.md",
                NovelWorkspaceMarkdown.render(fields = listOf("id" to "C-1", "title" to "a"), body = "A"),
            ),
            NovelWorkspaceFile(
                "branches/Alt-Path/branch.md",
                NovelWorkspaceMarkdown.render(fields = listOf("id" to "B-ALT", "kind" to "branch"), body = ""),
            ),
            NovelWorkspaceFile(
                "branches/Alt-Path/chapters/001-a.md",
                NovelWorkspaceMarkdown.render(fields = listOf("id" to "C-1", "title" to "a"), body = "A"),
            ),
        )
        NovelWorkspaceInstaller.install(files, dir)
        val ledger = NovelWorkspaceLedger.load(dir)
        val initial = ledger.commits.single().id
        assertEquals(initial, ledger.heads["B-MAIN"])
        assertEquals(initial, ledger.heads["B-ALT"])
        val store = NovelWorkspaceStore(dir)
        assertEquals(listOf(1), NovelWorkspaceLedger.workingChapterOrdinals(store, "Alt-Path"))
    }

    // ── book export follows the requested branch ────────────────────

    @Test
    fun `book export reads the requested branch and defaults to the main branch`() {
        val dir = installProject()
        NovelWorkspaceBranches.createBranch(dir, "主线", "番外线")
        // 分叉后侧分支追加一章（模拟两分支正文分岔）。
        val store = NovelWorkspaceStore(dir)
        store.write(
            "branches/番外线/chapters/003-歧路.md",
            NovelWorkspaceMarkdown.render(
                fields = listOf("id" to "C-3", "kind" to "chapter", "title" to "歧路", "ordinal" to "3"),
                body = "番外线独有的章节。",
            ),
        )

        val mainBook = NovelWorkspaceBookExport.read(dir)
        assertEquals(listOf("第1章 山呼", "第2章 入汴"), mainBook.chapters.map { it.heading })
        val sideBook = NovelWorkspaceBookExport.read(dir, "番外线")
        assertEquals(listOf("第1章 山呼", "第2章 入汴", "第3章 歧路"), sideBook.chapters.map { it.heading })
        // null / 空白回退主线，保持旧调用兼容。
        assertEquals(mainBook.chapters, NovelWorkspaceBookExport.read(dir, null).chapters)
        assertEquals(mainBook.chapters, NovelWorkspaceBookExport.read(dir, " ").chapters)
        val bytes = NovelWorkspaceBookExport.exportBytes(dir, NovelWorkspaceBookExport.Format.TXT, "番外线")
        assertTrue(String(bytes, Charsets.UTF_8).contains("番外线独有的章节。"))
    }

    // ── unresolved stays branch-scoped (regression pin) ─────────────

    @Test
    fun `unresolved gate stays isolated per branch`() {
        val dir = installProject()
        NovelWorkspaceBranches.createBranch(dir, "主线", "番外线")
        NovelWorkspaceUnresolvedStore.set(dir, "主线", fromOrdinal = 2, sinceCommitId = "X")
        assertEquals(2, NovelWorkspaceUnresolvedStore.entryFor(dir, "主线")?.fromOrdinal)
        assertNull(NovelWorkspaceUnresolvedStore.entryFor(dir, "番外线"))
        NovelWorkspaceUnresolvedStore.clear(dir, "主线")
        assertNull(NovelWorkspaceUnresolvedStore.entryFor(dir, "主线"))
        assertFalse(NovelWorkspaceUnresolvedStore.load(dir).branches.containsKey("番外线"))
    }

    /** Body of a rendered markdown file (front matter stripped). */
    private fun bodyOf(content: String): String = NovelWorkspaceMarkdown.parseFile(content).body
}
