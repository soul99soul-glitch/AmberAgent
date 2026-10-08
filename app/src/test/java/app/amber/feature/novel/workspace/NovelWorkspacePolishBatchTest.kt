package app.amber.feature.novel.workspace

import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.RunKernel
import app.amber.core.settings.Settings
import app.amber.feature.novelworkspace.NovelWorkspaceFile
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteMode
import app.amber.feature.novelworkspace.NovelWorkspaceInstaller
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceManifestRenderer
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 批量润色（Polish mode）协调器生命周期测试：与 NovelWorkspaceRuntimeTest 的代笔测试
 * 同一套搭建（真实 kernel 链 + 脚本化 fake kernel），不改动那份文件。重点钉住：
 * 逐章「润色 → 剧情指针」提交序列、批次结束后 isPlotStale == false、D-D 未决门不被
 * 润色提交触发、暂停后旧 execution token 失效、取消、单章失败重试一次、连续无产出终止。
 */
class NovelWorkspacePolishBatchTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val exportedAt = Instant.parse("2026-08-19T00:00:00Z")

    /** Coordinator wired through the real kernel chain (same shape as RuntimeTest). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun polishCoordinator(
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
        } ?: kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
        )
        val runner = app.amber.core.agent.runtime.impl.InProcessAgentRunner(
            registry,
            app.amber.core.agent.runtime.InMemoryAgentEventStore(),
            runScopeFactory = { id, _ ->
                app.amber.core.agent.runtime.adapter.LegacyRunScope(runId = id)
            },
            scope = scope,
        )
        return NovelWorkspaceGhostwriteCoordinator(
            runtime,
            NovelTurnLauncher(runner, payloads, scope),
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
        )
        NovelWorkspaceInstaller.install(files, dir)
        return dir
    }

    /** Chapters 2/3 plus one committed plot file, so the fixture book is NOT plot-stale. */
    private fun installChaptersAndPlot(dir: File): NovelWorkspaceRuntime {
        val runtime = NovelWorkspaceRuntime(NoopKernel)
        val store = NovelWorkspaceStore(dir)
        store.write("drafts/d2.md", "第二章正文。")
        runtime.collectDraft(dir, "B-1", "主线", "drafts/d2.md", NovelWorkspaceCollectTarget.NewChapter, chapterTitle = "入汴")
        store.write("drafts/d3.md", "第三章正文。")
        runtime.collectDraft(dir, "B-1", "主线", "drafts/d3.md", NovelWorkspaceCollectTarget.NewChapter, chapterTitle = "陈桥")
        // A real 剧情指针 commit gives the book its plot freshness baseline.
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

    /**
     * Scripted polish turn: reads the host's user text (「请润色第 N 章。」) and answers
     * with a read-only prose candidate, then a hash-bound factual review.
     */
    private class PolishingFakeKernel(
        private val chapterPaths: Map<Int, String>,
        private val polishedBody: (Int) -> String,
        /** First N provider calls throw, simulating transient failures. */
        private val failFirstNCalls: Int = 0,
    ) : RunKernel {
        var calls = 0
        var reviewCalls = 0
        var reviewFactsUnchanged = true
        var malformedReview = false
        var beforeReviewFinal: (() -> Unit)? = null
        val systemPrompts = mutableListOf<String>()
        val userTexts = mutableListOf<String>()

        /** Test hook invoked before the candidate's final answer. */
        var beforeFinal: (() -> Unit)? = null

        override fun run(session: GenerationRunSession): Flow<GenerationChunk> = flow {
            val systemText = session.messages
                .filter { it.role == MessageRole.SYSTEM }
                .flatMap { it.parts.filterIsInstance<UIMessagePart.Text>() }
                .joinToString("\n") { it.text }
            if (systemText.contains("## Original")) {
                reviewCalls += 1
                beforeReviewFinal?.invoke()
                val reply = if (malformedReview) "审核通过" else reviewReply(systemText, reviewFactsUnchanged)
                emit(GenerationChunk.Messages(session.messages + UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(reply)),
                )))
                return@flow
            }
            calls += 1
            if (calls <= failFirstNCalls) throw RuntimeException("provider 429")
            systemPrompts += systemText
            val userText = session.messages
                .filter { it.role == MessageRole.USER }
                .flatMap { it.parts.filterIsInstance<UIMessagePart.Text>() }
                .joinToString("\n") { it.text }
            userTexts += userText
            val ordinal = Regex("第 (\\d+) 章").find(userText)?.groupValues?.get(1)?.toIntOrNull()
            checkNotNull(chapterPaths[ordinal]) { "no scripted chapter for $ordinal" }
            beforeFinal?.invoke()
            val assistant = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text(polishedBody(checkNotNull(ordinal)))),
            )
            emit(GenerationChunk.Messages(session.messages + assistant))
        }
    }

    private val chapterPaths = mapOf(
        1 to "branches/主线/chapters/001-山呼.md",
        2 to "branches/主线/chapters/002-入汴.md",
        3 to "branches/主线/chapters/003-陈桥.md",
    )

    private fun bodyOf(store: NovelWorkspaceStore, path: String): String =
        NovelWorkspaceMarkdown.parseFile(store.read(path)!!).body

    private fun fieldsOf(store: NovelWorkspaceStore, path: String) =
        NovelWorkspaceMarkdown.parseFile(store.read(path)!!).fields

    /**
     * Mirror the worker path: the job always reaches runBatch reloaded from disk, which
     * second-truncates createdAt (NovelWorkspaceInstantSerializer). The exact-boundary
     * progress comparison depends on that persisted form — an in-memory job keeps
     * sub-second precision and would exclude its own same-second polish commits.
     */
    private fun persistedJob(
        dir: File,
        job: NovelWorkspaceGhostwriteJob,
    ): NovelWorkspaceGhostwriteJob {
        NovelWorkspaceGhostwriteJobs.save(job, dir)
        return checkNotNull(NovelWorkspaceGhostwriteJobs.load(dir, job.id))
    }

    @Test
    fun `polish batch commits 润色 then 剧情指针 per chapter and never leaves the plot stale`() = runTest {
        val dir = installProject()
        installChaptersAndPlot(dir)
        val generator = PolishingFakeKernel(chapterPaths, { ordinal ->
            "润色后的第 $ordinal 章正文，文字更凝练，情节一字未动。"
        })
        val coordinator = polishCoordinator(NovelWorkspaceRuntime(generator), testScheduler)
        val job = persistedJob(dir, coordinator.newPolishJob(dir, "主线", fromOrdinal = 1, toOrdinal = 2))
        assertEquals(NovelWorkspaceGhostwriteMode.Polish, job.mode)
        assertEquals(2, job.targetChapterCount)

        val result = coordinator.runBatch(
            job = job,
            projectDirectory = dir,
            branchId = "B-1",
            settings = Settings(),
            model = Model(),
            isPaused = { false },
        ) { }

        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Completed)
        assertEquals(2, (result as NovelWorkspaceGhostwriteCoordinator.BatchResult.Completed).chaptersWritten)

        val store = NovelWorkspaceStore(dir)
        assertEquals("润色后的第 1 章正文，文字更凝练，情节一字未动。", bodyOf(store, chapterPaths.getValue(1)))
        assertEquals("润色后的第 2 章正文，文字更凝练，情节一字未动。", bodyOf(store, chapterPaths.getValue(2)))
        // Chapter identity is host-owned: front matter survives the polish byte-for-byte.
        assertEquals("C-1", fieldsOf(store, chapterPaths.getValue(1))["id"])
        assertEquals("山呼", fieldsOf(store, chapterPaths.getValue(1))["title"])
        assertEquals("1", fieldsOf(store, chapterPaths.getValue(1))["ordinal"])

        // Commit sequence per chapter: 润色 → 剧情指针 (the freshness pairing).
        val messages = NovelWorkspaceLedger.load(dir).commits.map { it.message }
        assertEquals(
            listOf(
                NovelWorkspaceLedger.Message.INITIAL,
                NovelWorkspaceLedger.Message.COLLECTION,
                NovelWorkspaceLedger.Message.COLLECTION,
                NovelWorkspaceLedger.Message.PLOT_POINTER, // fixture baseline
                NovelWorkspaceLedger.Message.POLISH,
                NovelWorkspaceLedger.Message.PLOT_POINTER,
                NovelWorkspaceLedger.Message.POLISH,
                NovelWorkspaceLedger.Message.PLOT_POINTER,
            ),
            messages,
        )

        // THE PIN: polish moved prose only — the batch must NOT flip the plot stale.
        assertFalse(NovelWorkspaceLedger.isPlotStale(NovelWorkspaceStore(dir), NovelWorkspaceLedger.load(dir), "主线"))
        // Polishing middle chapters must not arm the D-D unresolved gate.
        assertNull(NovelWorkspaceUnresolvedStore.entryFor(dir, "主线"))
        // Ledger-derived progress agrees.
        assertEquals(2, NovelWorkspaceGhostwriteJobs.progress(job, store))
        assertEquals(2, generator.calls)
        assertEquals(2, generator.reviewCalls)
        // The polish turn prompt carried the original body + discipline.
        assertTrue(generator.systemPrompts.all { it.contains(NovelWorkspacePrompts.WORKSPACE_DISCIPLINE.take(20)) })
        assertTrue(generator.systemPrompts[0].contains("陈桥驿的风先到。"))
    }

    @Test
    fun `polish batch pauses mid-turn and the old execution cannot commit after resume`() = runTest {
        val dir = installProject()
        installChaptersAndPlot(dir)
        lateinit var job: NovelWorkspaceGhostwriteJob
        val generator = PolishingFakeKernel(chapterPaths, { ordinal ->
            "这段润色正文不得由旧执行写入：第 $ordinal 章。"
        })
        generator.beforeFinal = {
            NovelWorkspaceGhostwriteJobs.transition(
                projectDirectory = dir,
                jobId = job.id,
                expectedStatuses = setOf(NovelWorkspaceGhostwriteJob.STATUS_RUNNING),
                newStatus = NovelWorkspaceGhostwriteJob.STATUS_PAUSED,
            )
        }
        val coordinator = polishCoordinator(NovelWorkspaceRuntime(generator), testScheduler)
        job = persistedJob(dir, coordinator.newPolishJob(dir, "主线", fromOrdinal = 1, toOrdinal = 2))

        val result = coordinator.runBatch(
            job = job,
            projectDirectory = dir,
            branchId = "B-1",
            settings = Settings(),
            model = Model(),
            isPaused = {
                NovelWorkspaceGhostwriteJobs.load(dir, job.id)?.let {
                    it.status != NovelWorkspaceGhostwriteJob.STATUS_RUNNING ||
                        it.executionKey != job.executionKey
                } != false
            },
        ) { }

        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Stopped)
        val store = NovelWorkspaceStore(dir)
        // The paused execution left nothing behind: no 润色 commit, no chapter change.
        assertFalse(
            NovelWorkspaceLedger.load(dir).commits.any { it.message == NovelWorkspaceLedger.Message.POLISH },
        )
        assertEquals("陈桥驿的风先到。", bodyOf(store, chapterPaths.getValue(1)))

        // Resume rotates the token; the same batch then completes from the ledger cursor.
        val resumed = NovelWorkspaceGhostwriteJobs.restartPaused(dir, job.id, job.executionKey)
        assertNotNull(resumed)
        val resumedJob = checkNotNull(resumed)
        generator.beforeFinal = null
        val secondResult = coordinator.runBatch(
            job = resumedJob,
            projectDirectory = dir,
            branchId = "B-1",
            settings = Settings(),
            model = Model(),
            isPaused = { false },
        ) { }
        assertTrue(secondResult is NovelWorkspaceGhostwriteCoordinator.BatchResult.Completed)
        assertEquals(2, (secondResult as NovelWorkspaceGhostwriteCoordinator.BatchResult.Completed).chaptersWritten)
        assertTrue(bodyOf(store, chapterPaths.getValue(1)).startsWith("这段润色正文"))
        // Old token can no longer act as the owner.
        assertNull(
            NovelWorkspaceGhostwriteJobs.withRunningOwner(dir, job.id, job.executionKey) { "stale" },
        )
        assertFalse(NovelWorkspaceLedger.isPlotStale(NovelWorkspaceStore(dir), NovelWorkspaceLedger.load(dir), "主线"))
    }

    @Test
    fun `polish batch stops when cancelled after a finished chapter`() = runTest {
        val dir = installProject()
        installChaptersAndPlot(dir)
        val generator = PolishingFakeKernel(chapterPaths, { ordinal -> "润色后的第 $ordinal 章。" })
        val coordinator = polishCoordinator(NovelWorkspaceRuntime(generator), testScheduler)
        val job = persistedJob(dir, coordinator.newPolishJob(dir, "主线", fromOrdinal = 1, toOrdinal = 3))

        val result = coordinator.runBatch(
            job = job,
            projectDirectory = dir,
            branchId = "B-1",
            settings = Settings(),
            model = Model(),
            isPaused = {
                NovelWorkspaceGhostwriteJobs.load(dir, job.id)?.status ==
                    NovelWorkspaceGhostwriteJob.STATUS_CANCELLED
            },
        ) { written ->
            if (written == 1) {
                NovelWorkspaceGhostwriteJobs.transition(
                    projectDirectory = dir,
                    jobId = job.id,
                    expectedStatuses = setOf(NovelWorkspaceGhostwriteJob.STATUS_RUNNING),
                    newStatus = NovelWorkspaceGhostwriteJob.STATUS_CANCELLED,
                )
            }
        }

        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Stopped)
        assertEquals(1, (result as NovelWorkspaceGhostwriteCoordinator.BatchResult.Stopped).chaptersWritten)
        val messages = NovelWorkspaceLedger.load(dir).commits.map { it.message }
        // Exactly one chapter pair (润色 + 剧情指针) landed before the cancel.
        assertEquals(1, messages.count { it == NovelWorkspaceLedger.Message.POLISH })
        assertEquals(2, messages.count { it == NovelWorkspaceLedger.Message.PLOT_POINTER }) // fixture + chapter 1
        assertEquals(
            NovelWorkspaceGhostwriteJob.STATUS_CANCELLED,
            NovelWorkspaceGhostwriteJobs.load(dir, job.id)?.status,
        )
        assertFalse(NovelWorkspaceLedger.isPlotStale(NovelWorkspaceStore(dir), NovelWorkspaceLedger.load(dir), "主线"))
    }

    @Test
    fun `polish chapter retries a transient provider failure exactly once`() = runTest {
        val dir = installProject()
        installChaptersAndPlot(dir)
        val generator = PolishingFakeKernel(
            chapterPaths,
            { ordinal -> "润色后的第 $ordinal 章。" },
            failFirstNCalls = 1,
        )
        val coordinator = polishCoordinator(NovelWorkspaceRuntime(generator), testScheduler)
        val job = persistedJob(dir, coordinator.newPolishJob(dir, "主线", fromOrdinal = 1, toOrdinal = 1))

        val result = coordinator.runBatch(
            job = job,
            projectDirectory = dir,
            branchId = "B-1",
            settings = Settings(),
            model = Model(),
            isPaused = { false },
        ) { }

        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Completed)
        assertEquals(1, (result as NovelWorkspaceGhostwriteCoordinator.BatchResult.Completed).chaptersWritten)
        // 1 failed attempt + 1 successful retry for chapter 1; the range ends there.
        assertEquals(2, generator.calls)
        assertFalse(NovelWorkspaceLedger.isPlotStale(NovelWorkspaceStore(dir), NovelWorkspaceLedger.load(dir), "主线"))
    }

    @Test
    fun `polish batch fails after consecutive no-output turns instead of spinning`() = runTest {
        val dir = installProject()
        installChaptersAndPlot(dir)
        // Filler kernel: short final answer, no tool calls — nothing is committed.
        val filler = object : RunKernel {
            var calls = 0
            override fun run(session: GenerationRunSession): Flow<GenerationChunk> = flow {
                calls += 1
                val assistant = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("")),
                )
                emit(GenerationChunk.Messages(session.messages + assistant))
            }
        }
        val coordinator = polishCoordinator(NovelWorkspaceRuntime(filler), testScheduler)
        val job = persistedJob(dir, coordinator.newPolishJob(dir, "主线", fromOrdinal = 1, toOrdinal = 3))

        val result = coordinator.runBatch(
            job = job,
            projectDirectory = dir,
            branchId = "B-1",
            settings = Settings(),
            model = Model(),
            isPaused = { false },
        ) { }

        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Failed)
        assertTrue(
            (result as NovelWorkspaceGhostwriteCoordinator.BatchResult.Failed).error.contains("未完成章节润色"),
        )
        assertEquals(2, filler.calls)
        val store = NovelWorkspaceStore(dir)
        assertEquals("陈桥驿的风先到。", bodyOf(store, chapterPaths.getValue(1)))
        assertEquals(0, NovelWorkspaceGhostwriteJobs.progress(job, store))
    }

    /**
     * 无产出轮不补剧情指针（J3 pin）：第 1 章正常润色（润色→指针成对落地），随后模型
     * 寒暄的空转轮（chapter.error == null 且无润色 commit）不得在账本里补出虚假的
     * 「第 N 章已润色」指针——chapters/ 未动，剧情并未落后。
     */
    @Test
    fun `polish no-output round does not commit a stray plot pointer`() = runTest {
        val dir = installProject()
        installChaptersAndPlot(dir)
        // Only the first generation returns prose; reviews never count as new output.
        val kernel = object : RunKernel {
            var calls = 0
            override fun run(session: GenerationRunSession): Flow<GenerationChunk> = flow {
                val systemText = systemText(session)
                val text = if (systemText.contains("## Original")) {
                    reviewReply(systemText)
                } else {
                    calls += 1
                    if (calls == 1) "润色后的第 1 章正文，文字更凝练。" else ""
                }
                emit(GenerationChunk.Messages(session.messages + UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(text)),
                )))
            }
        }
        val coordinator = polishCoordinator(NovelWorkspaceRuntime(kernel), testScheduler)
        val job = persistedJob(dir, coordinator.newPolishJob(dir, "主线", fromOrdinal = 1, toOrdinal = 3))

        val result = coordinator.runBatch(
            job = job,
            projectDirectory = dir,
            branchId = "B-1",
            settings = Settings(),
            model = Model(),
            isPaused = { false },
        ) { }

        // 两轮无产出后由 no-progress guard 终止，第 1 章的润色成果保留。
        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Failed)
        assertTrue(
            (result as NovelWorkspaceGhostwriteCoordinator.BatchResult.Failed).error.contains("未完成章节润色"),
        )
        assertEquals(3, kernel.calls)
        assertEquals("润色后的第 1 章正文，文字更凝练。", bodyOf(NovelWorkspaceStore(dir), chapterPaths.getValue(1)))
        assertEquals(1, NovelWorkspaceGhostwriteJobs.progress(job, NovelWorkspaceStore(dir)))

        // THE PIN: 指针只有基线 + 第 1 章的配对；空转轮没有补出多余指针。
        val messages = NovelWorkspaceLedger.load(dir).commits.map { it.message }
        assertEquals(1, messages.count { it == NovelWorkspaceLedger.Message.POLISH })
        assertEquals(2, messages.count { it == NovelWorkspaceLedger.Message.PLOT_POINTER })
        assertFalse(NovelWorkspaceLedger.isPlotStale(NovelWorkspaceStore(dir), NovelWorkspaceLedger.load(dir), "主线"))
    }

    @Test
    fun `narrated polish answer is filed host-side and keeps the chapter front matter`() = runTest {
        val dir = installProject()
        installChaptersAndPlot(dir)
        val narrated = "赵大在风里站了很久，数着更声等天亮。" + "营房的灯火一盏盏熄了，他把自己的名字咽回喉咙里。".repeat(40)
        val kernel = object : RunKernel {
            var calls = 0
            override fun run(session: GenerationRunSession): Flow<GenerationChunk> = flow {
                calls += 1
                val assistant = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(if (systemText(session).contains("## Original")) reviewReply(systemText(session)) else narrated)),
                )
                emit(GenerationChunk.Messages(session.messages + assistant))
            }
        }
        val coordinator = polishCoordinator(NovelWorkspaceRuntime(kernel), testScheduler)
        val job = persistedJob(dir, coordinator.newPolishJob(dir, "主线", fromOrdinal = 1, toOrdinal = 1))

        val result = coordinator.runBatch(
            job = job,
            projectDirectory = dir,
            branchId = "B-1",
            settings = Settings(),
            model = Model(),
            isPaused = { false },
        ) { }

        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Completed)
        val store = NovelWorkspaceStore(dir)
        val fields = fieldsOf(store, chapterPaths.getValue(1))
        assertEquals("C-1", fields["id"])
        assertEquals("山呼", fields["title"])
        assertEquals("1", fields["ordinal"])
        assertEquals(narrated, bodyOf(store, chapterPaths.getValue(1)))
        val last = NovelWorkspaceLedger.load(dir).commits.last()
        assertEquals(NovelWorkspaceLedger.Message.PLOT_POINTER, last.message)
        assertEquals(
            NovelWorkspaceLedger.Message.POLISH,
            NovelWorkspaceLedger.load(dir).commits[NovelWorkspaceLedger.load(dir).commits.size - 2].message,
        )
        assertFalse(NovelWorkspaceLedger.isPlotStale(NovelWorkspaceStore(dir), NovelWorkspaceLedger.load(dir), "主线"))
    }

    /**
     * G3 ①：runBatch 入口补发分支。崩溃窗口（润色 commit 已落地、配对剧情指针 commit
     * 未落地）留下 written>0 且 plot-stale 的 job；直接调 runBatch 必须先补发指针、
     * 且不重润已完成的首章（kernel 只应被叫去润 2、3 章）。
     */
    @Test
    fun `runBatch entry repairs a dangling polish pointer and resumes without re-polishing`() = runTest {
        val dir = installProject()
        installChaptersAndPlot(dir)
        val generator = PolishingFakeKernel(chapterPaths, { ordinal -> "润色后的第 $ordinal 章。" })
        val coordinator = polishCoordinator(NovelWorkspaceRuntime(generator), testScheduler)
        val job = persistedJob(dir, coordinator.newPolishJob(dir, "主线", fromOrdinal = 1, toOrdinal = 3))
        // Crash window: chapter 1's 润色 commit landed, its pairing pointer never did.
        NovelWorkspaceRuntime(NoopKernel).commitPolishedChapter(
            dir, "B-1", "主线", chapterPaths.getValue(1), "第 1 章已润色但指针悬挂。",
            expectedHeadId = NovelWorkspaceLedger.load(dir).headOf("B-1")?.id,
            expectedTreeDigest = NovelWorkspaceLedger.treeSHA256(NovelWorkspaceStore(dir).fileTree()),
            expectedChapterContent = checkNotNull(NovelWorkspaceStore(dir).read(chapterPaths.getValue(1))),
            ownerJobId = job.id,
            ownerExecutionId = job.executionKey,
        )
        assertTrue(NovelWorkspaceLedger.isPlotStale(NovelWorkspaceStore(dir), NovelWorkspaceLedger.load(dir), "主线"))
        assertEquals(1, NovelWorkspaceGhostwriteJobs.progress(job, NovelWorkspaceStore(dir)))

        val result = coordinator.runBatch(
            job = job,
            projectDirectory = dir,
            branchId = "B-1",
            settings = Settings(),
            model = Model(),
            isPaused = { false },
        ) { }

        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Completed)
        assertEquals(3, (result as NovelWorkspaceGhostwriteCoordinator.BatchResult.Completed).chaptersWritten)
        // 入口补发只补指针：第 1 章没有被重新润色（turn 只落在第 2、3 章）。
        assertEquals(listOf("请润色第 2 章。", "请润色第 3 章。"), generator.userTexts)
        val messages = NovelWorkspaceLedger.load(dir).commits.map { it.message }
        assertEquals(3, messages.count { it == NovelWorkspaceLedger.Message.POLISH }) // 悬挂 1 + 2 + 3
        assertEquals(4, messages.count { it == NovelWorkspaceLedger.Message.PLOT_POINTER }) // 基线 + 补发 + 2 + 3
        assertFalse(NovelWorkspaceLedger.isPlotStale(NovelWorkspaceStore(dir), NovelWorkspaceLedger.load(dir), "主线"))
    }

    /**
     * G3 ②：startPolishBatch（经 preparePolishBatch）对「悬挂润色 commit」自愈后可
     * 启动 —— 旧逻辑在这里被永久拒绝且文案误导（实际只缺一条配对 commit）。
     */
    @Test
    fun `startPolishBatch self-heals a dangling polish commit instead of refusing`() = runTest {
        val dir = installProject()
        installChaptersAndPlot(dir)
        val coordinator = polishCoordinator(NovelWorkspaceRuntime(NoopKernel), testScheduler)
        NovelWorkspaceRuntime(NoopKernel).commitPolishedChapter(
            dir, "B-1", "主线", chapterPaths.getValue(1), "悬挂润色的第 1 章。",
            expectedHeadId = NovelWorkspaceLedger.load(dir).headOf("B-1")?.id,
            expectedTreeDigest = NovelWorkspaceLedger.treeSHA256(NovelWorkspaceStore(dir).fileTree()),
            expectedChapterContent = checkNotNull(NovelWorkspaceStore(dir).read(chapterPaths.getValue(1))),
        )
        assertTrue(NovelWorkspaceLedger.isPlotStale(NovelWorkspaceStore(dir), NovelWorkspaceLedger.load(dir), "主线"))

        val job = coordinator.preparePolishBatch(dir, "主线", fromOrdinal = 1, toOrdinal = 2)

        // 自愈 = 恰好追加一条配对指针；润色正文与历史原样保留。
        assertFalse(NovelWorkspaceLedger.isPlotStale(NovelWorkspaceStore(dir), NovelWorkspaceLedger.load(dir), "主线"))
        assertEquals(
            listOf(
                NovelWorkspaceLedger.Message.INITIAL,
                NovelWorkspaceLedger.Message.COLLECTION,
                NovelWorkspaceLedger.Message.COLLECTION,
                NovelWorkspaceLedger.Message.PLOT_POINTER, // fixture 基线
                NovelWorkspaceLedger.Message.POLISH,       // 悬挂的润色
                NovelWorkspaceLedger.Message.PLOT_POINTER, // 自愈补发
            ),
            NovelWorkspaceLedger.load(dir).commits.map { it.message },
        )
        assertEquals("悬挂润色的第 1 章。", bodyOf(NovelWorkspaceStore(dir), chapterPaths.getValue(1)))
        assertEquals(NovelWorkspaceGhostwriteMode.Polish, job.mode)
        assertEquals(1, job.startOrdinal)
        assertEquals(2, job.endOrdinal)
    }

    /** 无法判定可修复（真实剧情缺口，如收录新章）时维持现有拒绝。 */
    @Test
    fun `non-repairable plot staleness still refuses to start a polish batch`() = runTest {
        val dir = installProject()
        installChaptersAndPlot(dir)
        val coordinator = polishCoordinator(NovelWorkspaceRuntime(NoopKernel), testScheduler)
        val store = NovelWorkspaceStore(dir)
        store.write("drafts/d4.md", "第四章正文。")
        NovelWorkspaceRuntime(NoopKernel).collectDraft(
            dir, "B-1", "主线", "drafts/d4.md", NovelWorkspaceCollectTarget.NewChapter, chapterTitle = "渡口",
        )
        assertTrue(NovelWorkspaceLedger.isPlotStale(NovelWorkspaceStore(dir), NovelWorkspaceLedger.load(dir), "主线"))

        val error = runCatching { coordinator.preparePolishBatch(dir, "主线", 1, 2) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("剧情落后于正文"))
        // 拒绝路径不占用分支。
        assertEquals(0, NovelWorkspaceGhostwriteJobs.listActive(dir).size)
    }

    @Test
    fun `facts-changing polish review stops without writing or rerolling the candidate`() = runTest {
        val dir = installProject()
        installChaptersAndPlot(dir)
        val before = NovelWorkspaceStore(dir).read(chapterPaths.getValue(1))
        val generator = PolishingFakeKernel(chapterPaths, { "赵大死在了陈桥驿。" }).apply {
            reviewFactsUnchanged = false
        }
        val coordinator = polishCoordinator(NovelWorkspaceRuntime(generator), testScheduler)
        val job = persistedJob(dir, coordinator.newPolishJob(dir, "主线", 1, 1))

        val result = coordinator.runBatch(job, dir, "B-1", Settings(), Model(), isPaused = { false }) { }

        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Failed)
        assertTrue((result as NovelWorkspaceGhostwriteCoordinator.BatchResult.Failed).error.contains("人物结局被改变"))
        assertEquals(1, generator.calls)
        assertEquals(1, generator.reviewCalls)
        assertEquals(before, NovelWorkspaceStore(dir).read(chapterPaths.getValue(1)))
        assertEquals(0, NovelWorkspaceLedger.load(dir).commits.count { it.message == NovelWorkspaceLedger.Message.POLISH })
    }

    @Test
    fun `unparseable polish review refuses to collect`() = runTest {
        val dir = installProject()
        installChaptersAndPlot(dir)
        val before = NovelWorkspaceStore(dir).read(chapterPaths.getValue(1))
        val generator = PolishingFakeKernel(chapterPaths, { "陈桥驿，风先到了。" }).apply { malformedReview = true }
        val coordinator = polishCoordinator(NovelWorkspaceRuntime(generator), testScheduler)
        val job = persistedJob(dir, coordinator.newPolishJob(dir, "主线", 1, 1))

        val result = coordinator.runBatch(job, dir, "B-1", Settings(), Model(), isPaused = { false }) { }

        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Failed)
        assertEquals(before, NovelWorkspaceStore(dir).read(chapterPaths.getValue(1)))
        assertEquals(1, generator.calls)
        assertEquals(1, generator.reviewCalls)
    }

    @Test
    fun `a reviewed candidate cannot overwrite a manuscript changed during review`() = runTest {
        val dir = installProject()
        installChaptersAndPlot(dir)
        val path = chapterPaths.getValue(1)
        val generator = PolishingFakeKernel(chapterPaths, { "陈桥驿，风先到了。" }).apply {
            beforeReviewFinal = { NovelWorkspaceStore(dir).write(path, "其他写入的新正文。") }
        }
        val coordinator = polishCoordinator(NovelWorkspaceRuntime(generator), testScheduler)
        val job = persistedJob(dir, coordinator.newPolishJob(dir, "主线", 1, 1))

        val result = coordinator.runBatch(job, dir, "B-1", Settings(), Model(), isPaused = { false }) { }

        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Failed)
        assertEquals("其他写入的新正文。", NovelWorkspaceStore(dir).read(path))
        assertEquals(0, NovelWorkspaceLedger.load(dir).commits.count { it.message == NovelWorkspaceLedger.Message.POLISH })
    }

    @Test
    fun `polish write target rejects free paths and other canon paths in both approval modes`() = runTest {
        val dir = installProject()
        val store = NovelWorkspaceStore(dir)
        for (autoApprove in listOf(false, true)) {
            val batch = NovelWorkspaceWriteBatch()
            val tool = NovelWorkspaceToolSession(
                store, "主线", "赵大来了", batch,
                autoApproveCanon = autoApprove,
                polishTargetPath = chapterPaths.getValue(1),
            ).tools().first { it.name == "novel_workspace_write" }
            for (path in listOf("setting/writing/偏好.md", "drafts/越界.md", "inbox/越界.md", "branches/主线/plan/this-chapter.md", "branches/主线/plot/current.md", chapterPaths.getValue(2))) {
                val output = tool.execute(buildJsonObject { put("path", path); put("content", "越界修改") })
                assertTrue(output.filterIsInstance<UIMessagePart.Text>().joinToString { it.text }.contains("润色轮只能修改"))
                assertNull(store.read(path))
            }
            assertTrue(batch.snapshot().isEmpty())
        }
    }

    @Test
    fun `workspace status reports actual plot staleness and unresolved edits`() = runTest {
        val dir = installProject()
        installChaptersAndPlot(dir)
        val runtime = NovelWorkspaceRuntime(NoopKernel)
        runtime.saveChapterEdit(dir, "B-1", "主线", chapterPaths.getValue(1), "山呼", "人物发生变化的全新情节。")
        val tool = NovelWorkspaceToolSession(NovelWorkspaceStore(dir), "主线", "赵大来了", NovelWorkspaceWriteBatch())
            .tools().first { it.name == "novel_workspace_status" }

        val output = tool.execute(buildJsonObject { }).filterIsInstance<UIMessagePart.Text>().joinToString { it.text }

        assertTrue(output.contains("plot stale: true"))
        assertTrue(output.contains("unresolved: true"))
    }

    @Test
    fun `unchanged reviewed chapter completes and projects reviewing and committing stages`() = runTest {
        val dir = installProject()
        installChaptersAndPlot(dir)
        val original = bodyOf(NovelWorkspaceStore(dir), chapterPaths.getValue(1))
        val generator = PolishingFakeKernel(chapterPaths, { original })
        val coordinator = polishCoordinator(NovelWorkspaceRuntime(generator), testScheduler)
        val job = persistedJob(dir, coordinator.newPolishJob(dir, "主线", 1, 1))
        var reviewing = false
        var committing = false
        generator.beforeReviewFinal = {
            reviewing = NovelWorkspaceGhostwriteJobs.load(dir, job.id)?.stage == app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteStage.Reviewing
        }

        val result = coordinator.runBatch(job, dir, "B-1", Settings(), Model(), isPaused = { false }) {
            committing = NovelWorkspaceGhostwriteJobs.load(dir, job.id)?.stage == app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteStage.Committing
        }

        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Completed)
        assertEquals(1, generator.calls)
        assertEquals(1, generator.reviewCalls)
        assertTrue(reviewing)
        assertTrue(committing)
        assertEquals(1, NovelWorkspaceGhostwriteJobs.progress(job, NovelWorkspaceStore(dir)))
        assertEquals(original, bodyOf(NovelWorkspaceStore(dir), chapterPaths.getValue(1)))
    }

    @Test
    fun `retry refuses real story staleness after a failed batch without manufacturing a polish pointer`() = runTest {
        val dir = installProject()
        installChaptersAndPlot(dir)
        val generator = PolishingFakeKernel(chapterPaths, { ordinal ->
            if (ordinal == 2) throw RuntimeException("第二章生成失败")
            "陈桥驿，风先到了。"
        })
        val coordinator = polishCoordinator(NovelWorkspaceRuntime(generator), testScheduler)
        val job = persistedJob(dir, coordinator.newPolishJob(dir, "主线", 1, 2))
        val first = coordinator.runBatch(job, dir, "B-1", Settings(), Model(), isPaused = { false }) { }
        assertTrue(first is NovelWorkspaceGhostwriteCoordinator.BatchResult.Failed)
        NovelWorkspaceGhostwriteJobs.transition(dir, job.id, setOf(NovelWorkspaceGhostwriteJob.STATUS_RUNNING), NovelWorkspaceGhostwriteJob.STATUS_FAILED)
        NovelWorkspaceRuntime(NoopKernel).saveChapterEdit(dir, "B-1", "主线", chapterPaths.getValue(3), "陈桥", "最后一章发生新的故事事件。")
        val beforePointerCount = NovelWorkspaceLedger.load(dir).commits.count { it.message == NovelWorkspaceLedger.Message.PLOT_POINTER }
        val callsBeforeRetry = generator.calls
        val restarted = checkNotNull(NovelWorkspaceGhostwriteJobs.restartFailed(dir, job.id, job.executionKey))

        val result = coordinator.runBatch(restarted, dir, "B-1", Settings(), Model(), isPaused = { false }) { }

        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Failed)
        assertTrue((result as NovelWorkspaceGhostwriteCoordinator.BatchResult.Failed).error.contains("剧情落后"))
        assertEquals(callsBeforeRetry, generator.calls)
        assertTrue(NovelWorkspaceLedger.isPlotStale(NovelWorkspaceStore(dir), NovelWorkspaceLedger.load(dir), "主线"))
        assertEquals(beforePointerCount, NovelWorkspaceLedger.load(dir).commits.count { it.message == NovelWorkspaceLedger.Message.PLOT_POINTER })
    }

    @Test
    fun `starting another polish batch cannot repair a pointer owned by an active batch`() = runTest {
        val dir = installProject()
        installChaptersAndPlot(dir)
        val runtime = NovelWorkspaceRuntime(NoopKernel)
        val coordinator = polishCoordinator(runtime, testScheduler)
        val job = persistedJob(dir, coordinator.newPolishJob(dir, "主线", 1, 2))
        val path = chapterPaths.getValue(1)
        val store = NovelWorkspaceStore(dir)
        runtime.commitPolishedChapter(
            dir, "B-1", "主线", path, "陈桥驿，风先到了。",
            expectedHeadId = NovelWorkspaceLedger.load(dir).headOf("B-1")?.id,
            expectedTreeDigest = NovelWorkspaceLedger.treeSHA256(store.fileTree()),
            expectedChapterContent = checkNotNull(store.read(path)),
            ownerJobId = job.id,
            ownerExecutionId = job.executionKey,
        )
        val headBefore = NovelWorkspaceLedger.load(dir).headOf("B-1")?.id

        val error = runCatching { coordinator.preparePolishBatch(dir, "主线", 1, 2) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(headBefore, NovelWorkspaceLedger.load(dir).headOf("B-1")?.id)
        assertTrue(NovelWorkspaceLedger.isPlotStale(store, NovelWorkspaceLedger.load(dir), "主线"))
    }

    companion object {
        private fun systemText(session: GenerationRunSession): String = session.messages
            .filter { it.role == MessageRole.SYSTEM }
            .flatMap { it.parts.filterIsInstance<UIMessagePart.Text>() }
            .joinToString("\n") { it.text }

        private fun reviewReply(prompt: String, factsUnchanged: Boolean = true): String {
            fun hash(field: String) = checkNotNull(Regex("\"$field\":\"([0-9a-f]{64})\"").find(prompt)).groupValues[1]
            return buildJsonObject {
                put("originalSHA256", hash("originalSHA256"))
                put("candidateSHA256", hash("candidateSHA256"))
                put("factsUnchanged", factsUnchanged)
                put("issues", JsonArray(if (factsUnchanged) emptyList() else listOf(JsonPrimitive("人物结局被改变"))))
            }.toString()
        }
    }
}
