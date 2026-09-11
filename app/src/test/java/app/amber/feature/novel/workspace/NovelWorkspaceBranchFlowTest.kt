package app.amber.feature.novel.workspace

import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.RunKernel
import app.amber.core.settings.Settings
import app.amber.feature.novelworkspace.NovelWorkspaceBranches
import app.amber.feature.novelworkspace.NovelWorkspaceFile
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceInstaller
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceManifestRenderer
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceUndo
import app.amber.feature.novelworkspace.NovelWorkspaceUndoRecord
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 多分支运行时链路测试（与 NovelWorkspacePolishBatchTest 同一套真实 kernel 链搭建，
 * 不改动那份文件与 1023 行的 NovelWorkspaceRuntimeTest）。钉住：
 * - 分支上跑批次（Write 模式）只影响本分支的章节与 ledger head，主线不动；
 * - 有活跃批次时切分支被拒（存储层守卫）；
 * - job 绑定分支：resume/retry 在分支不匹配时被拒（先切回原分支）；
 * - undo 分支绑定：跨分支视为无 undo，撤销只回退本分支 head；
 * - 历史 null 分支 undo 记录按主线解释（向后兼容，不跨分支生效）。
 */
class NovelWorkspaceBranchFlowTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val exportedAt = Instant.parse("2026-08-19T00:00:00Z")

    /** Coordinator wired through the real kernel chain (same shape as PolishBatchTest). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun branchCoordinator(
        runtime: NovelWorkspaceRuntime,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler? = null,
    ): NovelWorkspaceGhostwriteCoordinator {
        val payloads = NovelTurnPayloads()
        val registry = app.amber.core.agent.runtime.impl.InMemoryAgentRegistry().apply {
            register(
                descriptor = NovelTurnDescriptor.value,
                inputClass = NovelTurnInput::class,
                inputSerializer = NovelTurnInput.serializer(),
                artifactSerializer = NovelTurnArtifact.serializer(),
                factory = { NovelTurnAgent(payloads) },
            )
        }
        val scope = scheduler?.let {
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() +
                    kotlinx.coroutines.test.StandardTestDispatcher(it),
            )
        }
        val runner = app.amber.core.agent.runtime.impl.InProcessAgentRunner(
            registry,
            app.amber.core.agent.runtime.InMemoryAgentEventStore(),
            runScopeFactory = { id, _ ->
                app.amber.core.agent.runtime.adapter.LegacyRunScope(runId = id)
            },
            scope = scope ?: kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
            ),
        )
        return NovelWorkspaceGhostwriteCoordinator(
            runtime,
            NovelTurnLauncher(runner, payloads),
        )
    }

    private fun installProject(): File {
        val dir = tempFolder.root.resolve("project")
        val files = listOf(
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
                "branches/主线/plan/this-chapter.md",
                "下一章推进主角的选择，并保留结尾钩子。",
            ),
        )
        NovelWorkspaceInstaller.install(files, dir)
        return dir
    }

    /** Two more chapters + a committed pointer on the main branch → book is NOT plot-stale. */
    private fun growMainBranch(dir: File): NovelWorkspaceRuntime {
        val runtime = NovelWorkspaceRuntime(NoopKernel)
        val store = NovelWorkspaceStore(dir)
        store.write("drafts/d2.md", "第二章正文。")
        runtime.collectDraft(dir, "B-1", "主线", "drafts/d2.md", NovelWorkspaceCollectTarget.NewChapter, chapterTitle = "入汴")
        store.write("drafts/d3.md", "第三章正文。")
        runtime.collectDraft(dir, "B-1", "主线", "drafts/d3.md", NovelWorkspaceCollectTarget.NewChapter, chapterTitle = "陈桥")
        runtime.commitPolishPointer(dir, "B-1", "主线", 3)
        assertFalse(NovelWorkspaceLedger.isPlotStale(NovelWorkspaceStore(dir), NovelWorkspaceLedger.load(dir), "主线"))
        return runtime
    }

    private object NoopKernel : RunKernel {
        override fun run(session: GenerationRunSession): Flow<GenerationChunk> = flow {
            val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("")))
            emit(GenerationChunk.Messages(session.messages + assistant))
        }
    }

    /** Narrating provider: a chapter-length final answer, no tool calls (host-write path). */
    private class NarratingKernel(private val body: String) : RunKernel {
        var calls = 0
        override fun run(session: GenerationRunSession): Flow<GenerationChunk> = flow {
            calls += 1
            val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(body)))
            emit(GenerationChunk.Messages(session.messages + assistant))
        }
    }

    private fun persistedJob(dir: File, job: NovelWorkspaceGhostwriteJob): NovelWorkspaceGhostwriteJob {
        NovelWorkspaceGhostwriteJobs.save(job, dir)
        return checkNotNull(NovelWorkspaceGhostwriteJobs.load(dir, job.id))
    }

    private fun bodyOf(store: NovelWorkspaceStore, path: String): String =
        NovelWorkspaceMarkdown.parseFile(store.read(path)!!).body

    /**
     * THE PIN: a batch bound to the forked branch files its chapter under the fork's
     * tree and advances only the fork's head — the source branch is untouched.
     */
    @Test
    fun `ghostwrite candidate on a forked branch stays private to that branch job`() = runTest {
        val dir = installProject()
        growMainBranch(dir)
        val fork = NovelWorkspaceBranches.createBranch(dir, "主线", "番外线")
        // 叙述型 provider：无工具调用、章长终稿 → 产生 job 私有候选稿。
        val narrated = "赵大在河对岸勒住马。" + "夜色像水一样漫过来，他把令牌藏进袖中，沿小路疾行。".repeat(30)
        val runtime = NovelWorkspaceRuntime(NarratingKernel(narrated))
        val coordinator = branchCoordinator(runtime, testScheduler)

        val ledgerBefore = NovelWorkspaceLedger.load(dir)
        val mainHeadBefore = ledgerBefore.heads["B-1"]!!
        val forkHeadBefore = ledgerBefore.heads[fork.id]!!
        // fork commit 钉在分叉 head 上，其 parent 即主线 head。
        assertEquals(mainHeadBefore, ledgerBefore.commit(forkHeadBefore)!!.parentId)

        val job = persistedJob(dir, coordinator.newJob(dir, "番外线", targetChapterCount = 1))
        assertEquals("番外线", job.branchSlug)
        assertEquals(3, job.startOrdinal)

        val result = coordinator.ghostwriteOneChapter(
            projectDirectory = dir,
            branchId = checkNotNull(fork.id),
            branchSlug = job.branchSlug,
            settings = Settings(),
            model = Model(),
            chapterOrdinal = job.startOrdinal + 1,
            ownerJobId = job.id,
            ownerExecutionId = job.executionKey,
        )

        assertNotNull(result.candidate)
        assertNull(result.error)

        val store = NovelWorkspaceStore(dir)
        // 候选只在 job 私有状态，审核前两条分支正文都不动。
        assertEquals(listOf(1, 2, 3), NovelWorkspaceLedger.workingChapterOrdinals(store, "番外线"))
        assertEquals(listOf(1, 2, 3), NovelWorkspaceLedger.workingChapterOrdinals(store, "主线"))
        assertEquals("陈桥驿的风先到。", bodyOf(store, "branches/主线/chapters/001-山呼.md"))
        val candidate = NovelWorkspaceGhostwriteJobs.load(dir, job.id)?.pendingCandidate!!
        assertEquals(4, candidate.chapterOrdinal)
        assertTrue(candidate.body.startsWith("赵大在河对岸勒住马"))

        // 审核前没有 canonical commit，两条分支 head 都保持原位。
        val ledgerAfter = NovelWorkspaceLedger.load(dir)
        assertEquals(forkHeadBefore, ledgerAfter.heads[fork.id])
        assertEquals(mainHeadBefore, ledgerAfter.heads["B-1"])
        assertEquals(0, NovelWorkspaceGhostwriteJobs.progress(job, store))
    }

    @Test
    fun `switching branches is refused while a batch is alive`() {
        val dir = installProject()
        growMainBranch(dir)
        NovelWorkspaceBranches.createBranch(dir, "主线", "番外线")
        val coordinator = branchCoordinator(NovelWorkspaceRuntime(NoopKernel))
        val job = persistedJob(dir, coordinator.newJob(dir, "主线", targetChapterCount = 1))

        val error = runCatching { NovelWorkspaceBranches.switchBranch(dir, "番外线") }
            .exceptionOrNull()
        assertTrue(error is app.amber.feature.novelworkspace.NovelWorkspaceIoError)
        assertTrue(error!!.message!!.contains("进行中的批次"))
        // 被拒后活跃分支不变。
        assertEquals("主线", NovelWorkspaceBranches.activeSlug(dir))

        NovelWorkspaceGhostwriteJobs.transition(
            dir,
            job.id,
            setOf(NovelWorkspaceGhostwriteJob.STATUS_RUNNING),
            NovelWorkspaceGhostwriteJob.STATUS_CANCELLED,
        )
        NovelWorkspaceBranches.switchBranch(dir, "番外线")
        assertEquals("番外线", NovelWorkspaceBranches.activeSlug(dir))
    }

    @Test
    fun `job resume and retry are bound to the job's own branch`() {
        val dir = installProject()
        NovelWorkspaceBranches.createBranch(dir, "主线", "番外线")

        val paused = persistedJob(
            dir,
            NovelWorkspaceGhostwriteJob(
                id = "J-PAUSE",
                branchSlug = "主线",
                targetChapterCount = 2,
                startOrdinal = 1,
                status = NovelWorkspaceGhostwriteJob.STATUS_PAUSED,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ),
        )
        // 作者已切到番外线：主线批次的继续被拒。
        assertNull(
            NovelWorkspaceGhostwriteJobs.restartPaused(
                dir, paused.id, paused.executionKey, expectedBranchSlug = "番外线",
            ),
        )
        // 切回主线后放行（返回新 execution token）。
        val resumed = NovelWorkspaceGhostwriteJobs.restartPaused(
            dir, paused.id, paused.executionKey, expectedBranchSlug = "主线",
        )
        assertNotNull(resumed)
        assertEquals(NovelWorkspaceGhostwriteJob.STATUS_RUNNING, resumed!!.status)

        val failed = persistedJob(
            dir,
            NovelWorkspaceGhostwriteJob(
                id = "J-FAIL",
                branchSlug = "番外线",
                targetChapterCount = 2,
                startOrdinal = 1,
                status = NovelWorkspaceGhostwriteJob.STATUS_FAILED,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            ),
        )
        assertNull(
            NovelWorkspaceGhostwriteJobs.restartFailed(
                dir, failed.id, failed.executionKey, expectedBranchSlug = "主线",
            ),
        )
        val retried = NovelWorkspaceGhostwriteJobs.restartFailed(
            dir, failed.id, failed.executionKey, expectedBranchSlug = "番外线",
        )
        assertNotNull(retried)
        assertEquals(NovelWorkspaceGhostwriteJob.STATUS_RUNNING, retried!!.status)
    }

    /** undo.json 的分支绑定：跨分支视为无 undo，撤销只回退本分支 head。 */
    @Test
    fun `undo is branch-bound and never crosses branches`() {
        val dir = installProject()
        val runtime = growMainBranch(dir)
        val fork = NovelWorkspaceBranches.createBranch(dir, "主线", "番外线")

        // 分叉后在主线再写一章：产生一条挂在主线 head 上的 undo 记录。
        val store = NovelWorkspaceStore(dir)
        store.write("drafts/d4.md", "第四章正文。")
        runtime.collectDraft(dir, "B-1", "主线", "drafts/d4.md", NovelWorkspaceCollectTarget.NewChapter, chapterTitle = "渡口")

        // 主线上有一次可撤销的 commit（「渡口」的收录）。
        assertTrue(runtime.canUndo(dir, "主线"))
        // 切到番外线视角：书级 undo 属于主线 → 视为无 undo。
        assertFalse(runtime.canUndo(dir, "番外线"))
        assertFalse(runtime.undoLast(dir, "番外线"))

        val ledgerBefore = NovelWorkspaceLedger.load(dir)
        assertTrue(runtime.undoLast(dir, "主线"))

        val ledgerAfter = NovelWorkspaceLedger.load(dir)
        // 主线 head 回退到 undone 的 parent（分叉时的指针 commit）；番外线 head 原地不动。
        val undoneId = ledgerBefore.heads["B-1"]!!
        assertEquals(ledgerBefore.commit(undoneId)!!.parentId, ledgerAfter.heads["B-1"])
        assertEquals(ledgerBefore.heads[fork.id], ledgerAfter.heads[fork.id])
        // undone commit 不再被任何分支/后代引用 → 从链上删除。
        assertNull(ledgerAfter.commit(undoneId))
        // 工作树：主线失去第 4 章（undo 恢复草稿），番外线的分叉副本原样保留。
        assertEquals(listOf(1, 2, 3), NovelWorkspaceLedger.workingChapterOrdinals(store, "主线"))
        assertEquals(listOf(1, 2, 3), NovelWorkspaceLedger.workingChapterOrdinals(store, "番外线"))
        // 撤销后主线草稿被还原（collectDraft 的删除被回滚）。
        assertNotNull(store.read("drafts/d4.md"))
    }

    /**
     * H1 pin: after createBranch the GLOBAL head stays pinned at the fork commit, but
     * the status tool must resolve the head of the ACTIVE branch — otherwise every
     * legitimate commit the source branch makes after the fork shows up as dirty.
     */
    @Test
    fun `status tool resolves the active branch head after the source branch moves on`() = runTest {
        val dir = installProject()
        growMainBranch(dir)
        NovelWorkspaceBranches.createBranch(dir, "主线", "番外线")
        // 分叉后源分支（主线）再提交新章：全局 head 仍钉在 fork commit（消息「分支」）。
        val runtime = NovelWorkspaceRuntime(NoopKernel)
        val store = NovelWorkspaceStore(dir)
        store.write("drafts/d4.md", "第四章正文。")
        runtime.collectDraft(dir, "B-1", "主线", "drafts/d4.md", NovelWorkspaceCollectTarget.NewChapter, chapterTitle = "渡口")

        val session = NovelWorkspaceToolSession(
            store = store,
            branchSlug = "主线",
            projectTitle = "赵大来了",
            batch = NovelWorkspaceWriteBatch(),
        )
        val output = session.tools().first { it.name == "novel_workspace_status" }
            .execute(kotlinx.serialization.json.buildJsonObject { })
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }

        val ledger = NovelWorkspaceLedger.load(dir)
        val mainHead = ledger.commit(ledger.heads["B-1"]!!)!!
        // head 按主线自己的分支指针解析，而不是全局（fork）head。
        assertTrue("status 应报主线 head：\n$output", output.contains("head: ${mainHead.id}"))
        assertTrue(output.contains(NovelWorkspaceLedger.Message.COLLECTION))
        assertTrue(output.contains("branch: 主线"))
        // 主线刚提交的合法新章不再被当成脏文件。
        assertFalse("status 不应报 dirty：\n$output", output.contains("dirty:"))
    }

    /** 历史记录兼容：branchSlug == null 的旧 undo 按主线解释，不跨分支生效。 */
    @Test
    fun `legacy undo records without a branch are treated as main-branch records`() {
        val dir = installProject()
        val runtime = growMainBranch(dir)
        NovelWorkspaceBranches.createBranch(dir, "主线", "番外线")

        // 用旧格式（无 branchSlug）覆写 undo.json。
        val ledger = NovelWorkspaceLedger.load(dir)
        val head = ledger.heads["B-1"]!!
        val headCommit = ledger.commit(head)!!
        NovelWorkspaceUndo.save(
            NovelWorkspaceUndoRecord(
                commitId = head,
                parentCommitId = headCommit.parentId,
                files = emptyMap(),
                unresolvedBefore = NovelWorkspaceUnresolvedStore.load(dir),
            ),
            dir,
        )
        assertTrue(runtime.canUndo(dir, "主线"))
        assertFalse(runtime.canUndo(dir, "番外线"))
    }

    /**
     * 防御层 pin（切分支残留明细视图）：活跃分支已是番外线时，保存仍递着上一分支
     * （主线）的章节/设定路径必须被拒——否则主线文件写盘、却以番外线的 branchId 推进
     * 番外线 head 并记 undo（账本污染）。UI 层切分支时会重置明细视图，这里钉住 runtime
     * 的第二道防线。
     */
    @Test
    fun `saving an edit to a stale branch path is refused`() {
        val dir = installProject()
        growMainBranch(dir)
        val fork = NovelWorkspaceBranches.createBranch(dir, "主线", "番外线")
        NovelWorkspaceBranches.switchBranch(dir, "番外线")
        assertEquals("番外线", NovelWorkspaceBranches.activeSlug(dir))
        val runtime = NovelWorkspaceRuntime(NoopKernel)
        val ledgerBefore = NovelWorkspaceLedger.load(dir)

        val chapterError = runCatching {
            runtime.saveChapterEdit(
                projectDirectory = dir,
                branchId = checkNotNull(fork.id),
                branchSlug = "番外线",
                chapterPath = "branches/主线/chapters/001-山呼.md",
                title = "山呼",
                body = "跨分支篡改主线正文。",
            )
        }.exceptionOrNull()
        assertTrue(chapterError is app.amber.feature.novelworkspace.NovelWorkspaceIoError)
        assertTrue(chapterError!!.message!!.contains("分支"))
        // 主线章节原样保留。
        assertEquals(
            "陈桥驿的风先到。",
            bodyOf(NovelWorkspaceStore(dir), "branches/主线/chapters/001-山呼.md"),
        )

        val fileError = runCatching {
            runtime.saveFileEdit(
                projectDirectory = dir,
                branchId = checkNotNull(fork.id),
                branchSlug = "番外线",
                path = "branches/主线/plot/current.md",
                body = "跨分支污染主线剧情。",
            )
        }.exceptionOrNull()
        assertTrue(fileError is app.amber.feature.novelworkspace.NovelWorkspaceIoError)

        // 两次拒绝都无副作用：没有任何分支 head 前进，undo 记录也未产生。
        val ledgerAfter = NovelWorkspaceLedger.load(dir)
        assertEquals(ledgerBefore.heads, ledgerAfter.heads)
        assertEquals(ledgerBefore.commits.size, ledgerAfter.commits.size)
        assertFalse(runtime.canUndo(dir, "番外线"))

        val chapterViaGenericEditor = runCatching {
            runtime.saveFileEdit(
                projectDirectory = dir,
                branchId = checkNotNull(fork.id),
                branchSlug = "番外线",
                path = "branches/番外线/chapters/001-山呼.md",
                body = "绕过章节编辑入口。",
            )
        }.exceptionOrNull()
        assertTrue(chapterViaGenericEditor is app.amber.feature.novelworkspace.NovelWorkspaceIoError)

        // 当前活跃分支内的正常保存不受影响。
        runtime.saveFileEdit(
            projectDirectory = dir,
            branchId = checkNotNull(fork.id),
            branchSlug = "番外线",
            path = "branches/番外线/plan/this-chapter.md",
            body = "分支内的正常编辑。",
        )
        assertEquals(
            "分支内的正常编辑。",
            bodyOf(NovelWorkspaceStore(dir), "branches/番外线/plan/this-chapter.md"),
        )
    }
}
