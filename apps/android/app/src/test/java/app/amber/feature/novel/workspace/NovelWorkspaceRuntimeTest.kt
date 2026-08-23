package app.amber.feature.novel.workspace

import app.amber.ai.core.MessageRole
import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.Generator
import app.amber.core.ai.transformers.InputMessageTransformer
import app.amber.core.ai.transformers.OutputMessageTransformer
import app.amber.core.model.Assistant
import app.amber.core.settings.Settings
import app.amber.feature.novelworkspace.NovelWorkspaceFile
import app.amber.feature.novelworkspace.NovelWorkspaceInstaller
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceManifestRenderer
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
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

class NovelWorkspaceRuntimeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val exportedAt = Instant.parse("2026-08-19T00:00:00Z")

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
                    fields = listOf(
                        "id" to "C-1",
                        "kind" to "chapter",
                        "title" to "山呼",
                        "ordinal" to "1",
                    ),
                    body = "陈桥驿的风先到。",
                ),
            ),
        )
        NovelWorkspaceInstaller.install(files, dir)
        return dir
    }

    /** Mimics GenerationHandler: executes tool calls itself, then produces the final answer. */
    private class LoopingFakeGenerator(
        private val toolCalls: List<Pair<String, JsonElement>>,
        private val finalText: String,
        /** First N generateText calls throw, simulating transient provider failures. */
        private val failFirstNCalls: Int = 0,
        /** Throw after executing the tools but before the final answer (mid-stream cut). */
        private val failAfterTools: Boolean = false,
    ) : Generator {
        var calls = 0

        override fun generateText(
            settings: Settings,
            model: Model,
            messages: List<UIMessage>,
            inputTransformers: List<InputMessageTransformer>,
            outputTransformers: List<OutputMessageTransformer>,
            assistant: Assistant,
            memories: List<app.amber.core.model.AssistantMemory>?,
            tools: List<Tool>,
            maxSteps: Int,
            processingStatus: MutableStateFlow<String?>,
            autoApproveTools: Boolean,
            autoApproveHighRiskTools: Boolean,
            autoApprovedToolNames: Set<String>,
            invocationContext: app.amber.feature.runtime.ToolInvocationContext,
            conversation: app.amber.core.model.Conversation?,
            consumeSteerMessages: suspend () -> List<UIMessage>,
            runId: String?,
            onTerminal: (suspend (app.amber.core.ai.GenerationTerminal) -> Unit)?,
            responsesResume: app.amber.ai.provider.ResponsesResumeRequest?,
        ): Flow<GenerationChunk> = flow {
            calls += 1
            if (calls <= failFirstNCalls) throw RuntimeException("provider 429")
            val executed = mutableListOf<UIMessagePart.Tool>()
            for ((name, input) in toolCalls) {
                val tool = tools.first { it.name == name }
                val output = tool.execute(input)
                executed.add(
                    UIMessagePart.Tool(
                        toolCallId = "call-${executed.size}",
                        toolName = name,
                        input = input.toString(),
                        output = output,
                    ),
                )
            }
            if (failAfterTools) throw RuntimeException("stream cut mid-answer")
            val assistant = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = executed + UIMessagePart.Text(finalText),
            )
            emit(GenerationChunk.Messages(messages + assistant))
        }
    }

    private fun request(dir: File, userText: String = "继续写") = NovelWorkspaceRuntime.TurnRequest(
        projectDirectory = dir,
        branchId = "B-1",
        branchSlug = "主线",
        userText = userText,
        systemPrompt = "",
        settings = Settings(),
        model = Model(),
        assistant = Assistant(),
    )

    @Test
    fun `free writes land immediately, canon writes wait for the author`() = runTest {
        val dir = installProject()
        val runtime = NovelWorkspaceRuntime(
            LoopingFakeGenerator(
                toolCalls = listOf(
                    // drafts/ is free: must land during the turn.
                    "novel_workspace_write" to buildJsonObject {
                        put("path", "drafts/abc12345.md")
                        put("content", "草稿正文")
                    },
                    // chapters/ is canon: must become a proposal, not a direct write.
                    "novel_workspace_write" to buildJsonObject {
                        put("path", "branches/主线/chapters/001-山呼.md")
                        put("content", "陈桥驿的风先到。驿丞赵大在门口张望。")
                        put("reason", "补一句人物出场")
                    },
                ),
                finalText = "我已经写好草稿，并提交了第一章的修改提案。",
            ),
        )

        val events = runtime.runTurn(request(dir)).toList()
        val completed = events.filterIsInstance<NovelWorkspaceRuntime.TurnEvent.Completed>().single()
        assertEquals("我已经写好草稿，并提交了第一章的修改提案。", completed.finalText)

        // Free write applied; canon write did not touch the manuscript yet.
        val store = NovelWorkspaceStore(dir)
        assertEquals("草稿正文", NovelWorkspaceMarkdown.parseFile(store.read("drafts/abc12345.md") ?: "").body)
        assertEquals(
            "陈桥驿的风先到。",
            NovelWorkspaceMarkdown.parseFile(store.read("branches/主线/chapters/001-山呼.md") ?: "").body,
        )

        val proposal = requireNotNull(completed.proposal)
        assertEquals(1, runtime.pendingProposals.value.size)
        assertEquals("branches/主线/chapters/001-山呼.md", proposal.entries.single().path)

        // Approve: the write lands with host identity preserved, one canon commit appended.
        val headBefore = NovelWorkspaceLedger.load(dir).head
        runtime.approve(proposal.id)
        val chapter = NovelWorkspaceMarkdown.parseFile(store.read("branches/主线/chapters/001-山呼.md") ?: "")
        assertEquals("陈桥驿的风先到。驿丞赵大在门口张望。", chapter.body)
        assertEquals("1", chapter.fields["ordinal"])
        assertEquals("C-1", chapter.fields["id"])
        val ledger = NovelWorkspaceLedger.load(dir)
        assertEquals(2, ledger.commits.size)
        assertEquals(NovelWorkspaceLedger.Message.COLLECTION, ledger.commits.last().message)
        assertEquals(headBefore, ledger.commits.last().parentId)
        assertEquals("B-1", ledger.heads.keys.single())
        assertTrue(File(dir, ".amber/checkout").exists())
        assertTrue(runtime.pendingProposals.value.isEmpty())
    }

    @Test
    fun `reject drops the proposal without touching the book`() = runTest {
        val dir = installProject()
        val runtime = NovelWorkspaceRuntime(
            LoopingFakeGenerator(
                toolCalls = listOf(
                    "novel_workspace_write" to buildJsonObject {
                        put("path", "branches/主线/plot/current.md")
                        put("content", "赵大已在陈桥。")
                    },
                ),
                finalText = "剧情更新提案已提交。",
            ),
        )
        val completed = runtime.runTurn(request(dir)).toList()
            .filterIsInstance<NovelWorkspaceRuntime.TurnEvent.Completed>().single()
        val proposal = completed.proposal!!
        runtime.reject(proposal.id)
        assertTrue(runtime.pendingProposals.value.isEmpty())
        val ledger = NovelWorkspaceLedger.load(dir)
        assertEquals(1, ledger.commits.size)
        assertNull(NovelWorkspaceStore(dir).read("branches/主线/plot/current.md"))
    }

    @Test
    fun `workspace tools never pause the generic approval pipeline`() = runTest {
        // needsApproval=true would make GenerationHandler stop at WaitingUser before
        // execute() runs; the novel runtime drives its own author gate instead.
        val dir = installProject()
        val session = NovelWorkspaceToolSession(
            store = NovelWorkspaceStore(dir),
            branchSlug = "主线",
            projectTitle = "测试",
            batch = NovelWorkspaceWriteBatch(),
        )
        session.tools().forEach { tool ->
            assertFalse("${tool.name} must not pause the generic approval pipeline", tool.needsApproval)
        }
    }

    @Test
    fun `model front matter never replaces host identity and host files refuse writes`() = runTest {
        val dir = installProject()
        val runtime = NovelWorkspaceRuntime(
            LoopingFakeGenerator(
                toolCalls = listOf(
                    "novel_workspace_write" to buildJsonObject {
                        put("path", "branches/主线/chapters/001-山呼.md")
                        put("content", "---\nid: FORGED\nkind: chapter\nordinal: 99\ntitle: 伪标题\n---\n新正文。")
                    },
                    "novel_workspace_write" to buildJsonObject {
                        put("path", "manifest.yaml")
                        put("content", "format: hacked\n")
                    },
                ),
                finalText = "完成。",
            ),
        )
        val completed = runtime.runTurn(request(dir)).toList()
            .filterIsInstance<NovelWorkspaceRuntime.TurnEvent.Completed>().single()
        val proposal = requireNotNull(completed.proposal)
        // The manifest write was refused by the tool, not buffered into the proposal.
        assertEquals(1, proposal.entries.size)
        runtime.approve(proposal.id)
        val store = NovelWorkspaceStore(dir)
        val chapter = NovelWorkspaceMarkdown.parseFile(store.read("branches/主线/chapters/001-山呼.md") ?: "")
        assertEquals("新正文。", chapter.body)
        assertEquals("C-1", chapter.fields["id"])
        assertEquals("1", chapter.fields["ordinal"])
        assertEquals("山呼", chapter.fields["title"])
        assertTrue((store.read("manifest.yaml") ?: "").contains("amber.novel.workspace"))
    }

    @Test
    fun `read list grep status tools answer from the store`() = runTest {
        val dir = installProject()
        val runtime = NovelWorkspaceRuntime(
            LoopingFakeGenerator(
                toolCalls = listOf(
                    "novel_workspace_list" to buildJsonObject { put("prefix", "branches/主线/chapters") },
                    "novel_workspace_read" to buildJsonObject { put("path", "branches/主线/chapters/001-山呼.md") },
                    "novel_workspace_grep" to buildJsonObject { put("query", "陈桥驿") },
                    "novel_workspace_status" to buildJsonObject { },
                ),
                finalText = "读完了。",
            ),
        )
        val events = runtime.runTurn(request(dir)).toList()
        val toolNames = events.filterIsInstance<NovelWorkspaceRuntime.TurnEvent.ToolActivity>().map { it.toolName }
        assertEquals(
            listOf(
                "novel_workspace_list",
                "novel_workspace_read",
                "novel_workspace_grep",
                "novel_workspace_status",
            ),
            toolNames,
        )
        val completed = events.filterIsInstance<NovelWorkspaceRuntime.TurnEvent.Completed>().single()
        assertNull(completed.proposal)
        assertFalse(NovelWorkspaceStore(dir).exists("drafts"))
    }

    @Test
    fun `collect draft as new chapter commits and consumes the draft`() {
        val dir = installProject()
        val runtime = NovelWorkspaceRuntime(LoopingFakeGenerator(emptyList(), ""))
        val store = NovelWorkspaceStore(dir)
        store.write(
            "drafts/abc12345.md",
            NovelWorkspaceMarkdown.render(
                fields = listOf("id" to "d-1", "kind" to "chapter", "title" to "未收录草稿"),
                body = "新写的正文段落。",
            ),
        )
        val headBefore = NovelWorkspaceLedger.load(dir).head
        val commit = runtime.collectDraft(
            projectDirectory = dir,
            branchId = "B-1",
            branchSlug = "主线",
            draftPath = "drafts/abc12345.md",
            target = NovelWorkspaceCollectTarget.NewChapter,
            chapterTitle = "入汴",
        )
        val newChapter = NovelWorkspaceMarkdown.parseFile(
            store.read("branches/主线/chapters/002-入汴.md")!!,
        )
        assertEquals("入汴", newChapter.fields["title"])
        assertEquals("2", newChapter.fields["ordinal"])
        assertEquals("新写的正文段落。", newChapter.body)
        assertFalse(store.exists("drafts/abc12345.md"))
        assertEquals(NovelWorkspaceLedger.Message.COLLECTION, commit.message)
        val ledger = NovelWorkspaceLedger.load(dir)
        assertEquals(commit.id, ledger.head)
        assertEquals(commit.id, ledger.heads["B-1"])
        assertEquals(headBefore, commit.parentId)
    }

    @Test
    fun `collect append keeps identity and replacing a middle chapter records the unresolved gate`() {
        val dir = installProject()
        val runtime = NovelWorkspaceRuntime(LoopingFakeGenerator(emptyList(), ""))
        val store = NovelWorkspaceStore(dir)

        // Append keeps the chapter's host identity and joins the draft.
        // Agent-written drafts are bare text (no front matter).
        store.write("drafts/d1.md", "第二段。")
        runtime.collectDraft(
            dir, "B-1", "主线", "drafts/d1.md",
            NovelWorkspaceCollectTarget.AppendToChapter("branches/主线/chapters/001-山呼.md"),
        )
        val appended = NovelWorkspaceMarkdown.parseFile(store.read("branches/主线/chapters/001-山呼.md")!!)
        assertEquals("陈桥驿的风先到。\n\n第二段。", appended.body)
        assertEquals("1", appended.fields["ordinal"])
        assertEquals("山呼", appended.fields["title"])

        // Give the book a second chapter so chapter 1 is no longer the newest.
        store.write("drafts/d2.md", "第二章正文。")
        runtime.collectDraft(dir, "B-1", "主线", "drafts/d2.md", NovelWorkspaceCollectTarget.NewChapter, chapterTitle = "入汴")

        // Replacing the middle chapter triggers the unresolved gate starting at ordinal 2.
        store.write("drafts/d3.md", "改写后的第一章。")
        runtime.collectDraft(
            dir, "B-1", "主线", "drafts/d3.md",
            NovelWorkspaceCollectTarget.ReplaceChapter("branches/主线/chapters/001-山呼.md"),
        )
        val unresolved = NovelWorkspaceUnresolvedStore.entryFor(dir, "主线")
        assertNotNull(unresolved)
        assertEquals(2, unresolved?.fromOrdinal)
    }

    @Test
    fun `manual edit preserves identity, commits, and gates middle edits`() {
        val dir = installProject()
        val runtime = NovelWorkspaceRuntime(LoopingFakeGenerator(emptyList(), ""))
        val store = NovelWorkspaceStore(dir)

        // Edit the only chapter's title + body: identity preserved, one manual commit.
        runtime.saveChapterEdit(
            projectDirectory = dir,
            branchId = "B-1",
            branchSlug = "主线",
            chapterPath = "branches/主线/chapters/001-山呼.md",
            title = "山呼（改）",
            body = "改写后的正文。",
        )
        val edited = NovelWorkspaceMarkdown.parseFile(store.read("branches/主线/chapters/001-山呼.md")!!)
        assertEquals("山呼（改）", edited.fields["title"])
        assertEquals("改写后的正文。", edited.body)
        assertEquals("C-1", edited.fields["id"])
        assertEquals("1", edited.fields["ordinal"])
        assertEquals(
            NovelWorkspaceLedger.Message.MANUAL_EDIT,
            NovelWorkspaceLedger.load(dir).commits.last().message,
        )
        // Editing the newest chapter is fast-forward-safe: no gate.
        assertNull(NovelWorkspaceUnresolvedStore.entryFor(dir, "主线"))

        // Add a second chapter, then edit chapter 1 (now middle) -> unresolved at ordinal 2.
        store.write("drafts/d2.md", "第二章正文。")
        runtime.collectDraft(dir, "B-1", "主线", "drafts/d2.md", NovelWorkspaceCollectTarget.NewChapter, chapterTitle = "入汴")
        runtime.saveChapterEdit(dir, "B-1", "主线", "branches/主线/chapters/001-山呼.md", "山呼", "再改一次。")
        assertEquals(2, NovelWorkspaceUnresolvedStore.entryFor(dir, "主线")?.fromOrdinal)
    }

    @Test
    fun `autoApproveCanon writes canon directly and commits at turn end`() = runTest {
        val dir = installProject()
        val runtime = NovelWorkspaceRuntime(
            LoopingFakeGenerator(
                toolCalls = listOf(
                    "novel_workspace_write" to buildJsonObject {
                        put("path", "branches/主线/chapters/002-入汴.md")
                        put("content", "第二章正文。")
                    },
                    "novel_workspace_write" to buildJsonObject {
                        put("path", "branches/主线/plot/current.md")
                        put("content", "赵大已到陈桥。")
                    },
                ),
                finalText = "第二章已完成。",
            ),
        )
        val events = runtime.runTurn(
            NovelWorkspaceRuntime.TurnRequest(
                projectDirectory = dir,
                branchId = "B-1",
                branchSlug = "主线",
                userText = "请写第 2 章。",
                systemPrompt = "",
                settings = Settings(),
                model = Model(),
                assistant = Assistant(),
                autoApproveCanon = true,
                autoCommitMessage = "代笔收录",
            ),
        ).toList()
        val completed = events.filterIsInstance<NovelWorkspaceRuntime.TurnEvent.Completed>().single()
        // Canon writes landed on disk WITHOUT an approval card, committed as one transaction.
        assertNull(completed.proposal)
        assertTrue(runtime.pendingProposals.value.isEmpty())
        val store = NovelWorkspaceStore(dir)
        assertEquals("第二章正文。", NovelWorkspaceMarkdown.parseFile(store.read("branches/主线/chapters/002-入汴.md")!!).body)
        assertEquals("赵大已到陈桥。", NovelWorkspaceMarkdown.parseFile(store.read("branches/主线/plot/current.md")!!).body)
        val ledger = NovelWorkspaceLedger.load(dir)
        assertEquals("代笔收录", ledger.commits.last().message)
        // chapter + plot landed in the SAME commit (single-commit standard).
        val lastCommitPaths = ledger.commits.last().files.keys
        assertTrue(lastCommitPaths.any { it.endsWith("002-入汴.md") })
        assertTrue(lastCommitPaths.any { it.endsWith("plot/current.md") })
    }

    @Test
    fun `ghostwrite batch derives progress from the ledger, not a counter`() = runTest {
        val dir = installProject()
        val runtime = NovelWorkspaceRuntime(LoopingFakeGenerator(emptyList(), ""))
        val coordinator = NovelWorkspaceGhostwriteCoordinator(runtime)
        val store = NovelWorkspaceStore(dir)

        val job = coordinator.newJob(dir, "主线", targetChapterCount = 2)
        assertEquals(1, job.startOrdinal) // the book already has chapter 1

        // Simulate two committed chapters (each ghostwrite turn commits).
        store.write("drafts/d1.md", "第二章。")
        runtime.collectDraft(dir, "B-1", "主线", "drafts/d1.md", NovelWorkspaceCollectTarget.NewChapter, chapterTitle = "入汴")
        assertEquals(1, NovelWorkspaceGhostwriteJobs.progress(job, store))

        store.write("drafts/d2.md", "第三章。")
        runtime.collectDraft(dir, "B-1", "主线", "drafts/d2.md", NovelWorkspaceCollectTarget.NewChapter, chapterTitle = "陈桥")
        assertEquals(2, NovelWorkspaceGhostwriteJobs.progress(job, store))

        // Progress is ledger-derived: re-creating the job's view from disk agrees.
        val reloaded = NovelWorkspaceGhostwriteJobs.load(dir, job.id)!!
        assertEquals(2, NovelWorkspaceGhostwriteJobs.progress(reloaded, NovelWorkspaceStore(dir)))
    }

    @Test
    fun `undo last collect restores files, moves head back, and is single-level`() {
        val dir = installProject()
        val runtime = NovelWorkspaceRuntime(LoopingFakeGenerator(emptyList(), ""))
        val store = NovelWorkspaceStore(dir)
        val headBefore = NovelWorkspaceLedger.load(dir).head

        store.write("drafts/d1.md", "第二章正文。")
        runtime.collectDraft(dir, "B-1", "主线", "drafts/d1.md", NovelWorkspaceCollectTarget.NewChapter, chapterTitle = "入汴")
        assertTrue(store.exists("branches/主线/chapters/002-入汴.md"))
        assertTrue(runtime.canUndo(dir))

        assertTrue(runtime.undoLast(dir))
        // Chapter file is gone (it didn't exist before), head back at the previous commit.
        assertFalse(store.exists("branches/主线/chapters/002-入汴.md"))
        assertEquals(headBefore, NovelWorkspaceLedger.load(dir).head)
        // Single level: the undo record is consumed.
        assertFalse(runtime.canUndo(dir))
        assertFalse(runtime.undoLast(dir))
    }

    @Test
    fun `ghostwrite batch retries a transient chapter failure exactly once`() = runTest {
        val dir = installProject()
        val generator = LoopingFakeGenerator(
            toolCalls = listOf(
                "novel_workspace_write" to buildJsonObject {
                    put("path", "branches/主线/chapters/002-入汴.md")
                    put("content", "第二章正文。")
                },
            ),
            finalText = "第二章完成。",
            failFirstNCalls = 1,
        )
        val coordinator = NovelWorkspaceGhostwriteCoordinator(NovelWorkspaceRuntime(generator))
        val job = coordinator.newJob(dir, "主线", targetChapterCount = 1)

        val result = coordinator.runBatch(
            job = job,
            projectDirectory = dir,
            branchId = "B-1",
            settings = Settings(),
            model = Model(),
            assistant = Assistant(),
            isPaused = { false },
        ) { }

        assertEquals(2, generator.calls) // first attempt + single retry, no more
        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Completed)
        assertEquals(1, (result as NovelWorkspaceGhostwriteCoordinator.BatchResult.Completed).chaptersWritten)
    }

    @Test
    fun `ghostwrite batch fails with the surfaced error when the retry also fails`() = runTest {
        val dir = installProject()
        val generator = LoopingFakeGenerator(
            toolCalls = emptyList(),
            finalText = "",
            failFirstNCalls = 5, // more failures than attempts: both must fail
        )
        val coordinator = NovelWorkspaceGhostwriteCoordinator(NovelWorkspaceRuntime(generator))
        val job = coordinator.newJob(dir, "主线", targetChapterCount = 1)

        val result = coordinator.runBatch(
            job = job,
            projectDirectory = dir,
            branchId = "B-1",
            settings = Settings(),
            model = Model(),
            assistant = Assistant(),
            isPaused = { false },
        ) { }

        assertEquals(2, generator.calls) // exactly one retry, then the batch stops
        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Failed)
        assertTrue((result as NovelWorkspaceGhostwriteCoordinator.BatchResult.Failed).error.contains("provider 429"))
    }

    @Test
    fun `ghostwrite canon writes are locked to one target chapter`() = runTest {
        val dir = installProject()
        val runtime = NovelWorkspaceRuntime(LoopingFakeGenerator(emptyList(), ""))
        val store = NovelWorkspaceStore(dir)

        // Give the book a second chapter so chapter 1 is a middle chapter (newest = 2).
        store.write("drafts/d1.md", "第二章。")
        runtime.collectDraft(dir, "B-1", "主线", "drafts/d1.md", NovelWorkspaceCollectTarget.NewChapter, chapterTitle = "入汴")

        val gw = NovelWorkspaceRuntime(
            LoopingFakeGenerator(
                toolCalls = listOf(
                    // Older chapter: refused (would trip the D-D gate in this turn's commit).
                    "novel_workspace_write" to buildJsonObject {
                        put("path", "branches/主线/chapters/001-山呼.md")
                        put("content", "被拒的改写。")
                    },
                    // Other branch: refused.
                    "novel_workspace_write" to buildJsonObject {
                        put("path", "branches/别的/chapters/003-x.md")
                        put("content", "越界。")
                    },
                    // No ordinal prefix: refused.
                    "novel_workspace_write" to buildJsonObject {
                        put("path", "branches/主线/chapters/new-chapter.md")
                        put("content", "无序号。")
                    },
                    // Refining the previous tail (002) is NOT this turn's target: refused.
                    "novel_workspace_write" to buildJsonObject {
                        put("path", "branches/主线/chapters/002-入汴.md")
                        put("content", "第二章改定。")
                    },
                    // The next chapter (003) is the target: allowed.
                    "novel_workspace_write" to buildJsonObject {
                        put("path", "branches/主线/chapters/003-陈桥.md")
                        put("content", "第三章正文。")
                    },
                    // A second new chapter (004) in the same turn: refused.
                    "novel_workspace_write" to buildJsonObject {
                        put("path", "branches/主线/chapters/004-多写.md")
                        put("content", "第四章越权。")
                    },
                    // Refining the target chapter itself: allowed.
                    "novel_workspace_write" to buildJsonObject {
                        put("path", "branches/主线/chapters/003-陈桥.md")
                        put("content", "第三章定稿。")
                    },
                    // Plot in the same turn stays free (D-C single-commit standard).
                    "novel_workspace_write" to buildJsonObject {
                        put("path", "branches/主线/plot/current.md")
                        put("content", "第三章已写。")
                    },
                ),
                finalText = "第三章完成。",
            ),
        )
        gw.runTurn(
            request(dir, "请写第 3 章。").copy(autoApproveCanon = true, autoCommitMessage = "代笔收录"),
        ).toList()

        assertEquals("陈桥驿的风先到。", NovelWorkspaceMarkdown.parseFile(store.read("branches/主线/chapters/001-山呼.md")!!).body)
        assertEquals("第二章。", NovelWorkspaceMarkdown.parseFile(store.read("branches/主线/chapters/002-入汴.md")!!).body)
        assertFalse(store.exists("branches/别的/chapters/003-x.md"))
        assertFalse(store.exists("branches/主线/chapters/new-chapter.md"))
        assertFalse(store.exists("branches/主线/chapters/004-多写.md"))
        assertEquals("第三章定稿。", NovelWorkspaceMarkdown.parseFile(store.read("branches/主线/chapters/003-陈桥.md")!!).body)
        assertEquals("第三章已写。", NovelWorkspaceMarkdown.parseFile(store.read("branches/主线/plot/current.md")!!).body)
        // The refusals kept the commit clean: no self-inflicted unresolved gate.
        assertNull(NovelWorkspaceUnresolvedStore.entryFor(dir, "主线"))
    }

    @Test
    fun `failed ghostwrite turn rolls back uncommitted canon writes`() = runTest {
        val dir = installProject()
        val runtime = NovelWorkspaceRuntime(
            LoopingFakeGenerator(
                toolCalls = listOf(
                    "novel_workspace_write" to buildJsonObject {
                        put("path", "branches/主线/chapters/002-入汴.md")
                        put("content", "第二章正文。")
                    },
                    "novel_workspace_write" to buildJsonObject {
                        put("path", "branches/主线/plot/current.md")
                        put("content", "赵大已到陈桥。")
                    },
                ),
                finalText = "",
                failAfterTools = true,
            ),
        )
        val events = runtime.runTurn(
            request(dir, "请写第 2 章。").copy(autoApproveCanon = true, autoCommitMessage = "代笔收录"),
        ).toList()

        assertTrue(events.filterIsInstance<NovelWorkspaceRuntime.TurnEvent.Failed>().isNotEmpty())
        val store = NovelWorkspaceStore(dir)
        // Orphan canon writes rolled back to the last committed tree — otherwise the
        // next chapter's commit would sweep them in and trip the D-D gate.
        assertFalse(store.exists("branches/主线/chapters/002-入汴.md"))
        assertFalse(store.exists("branches/主线/plot/current.md"))
        assertEquals(1, NovelWorkspaceLedger.load(dir).commits.size)
        // The next turn still targets chapter 2: no orphan distorts the ordinals.
        assertEquals(1, NovelWorkspaceLedger.workingChapterOrdinals(store, "主线").maxOrNull())
    }

    @Test
    fun `narrated chapter answer is filed host-side when the model skips the write tool`() = runTest {
        val dir = installProject()
        val chapter = "赵大摸黑进了柴房。\n\n" + ("外头风声更紧了，他数着自己的心跳等天亮。" ).repeat(60)
        val generator = LoopingFakeGenerator(
            toolCalls = emptyList(),
            finalText = "# 火起\n\n$chapter",
        )
        val coordinator = NovelWorkspaceGhostwriteCoordinator(NovelWorkspaceRuntime(generator))
        val job = coordinator.newJob(dir, "主线", targetChapterCount = 1)

        val result = coordinator.runBatch(
            job = job,
            projectDirectory = dir,
            branchId = "B-1",
            settings = Settings(),
            model = Model(),
            assistant = Assistant(),
            isPaused = { false },
        ) { }

        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Completed)
        val store = NovelWorkspaceStore(dir)
        val paths = store.list("branches/主线/chapters")
        assertTrue(paths.any { it.endsWith("002-火起.md") })
        val filed = NovelWorkspaceMarkdown.parseFile(store.read(paths.first { it.endsWith("002-火起.md") })!!)
        assertEquals("火起", filed.fields["title"])
        assertEquals("2", filed.fields["ordinal"])
        assertTrue(filed.body.startsWith("赵大摸黑进了柴房"))
        assertEquals(NovelWorkspaceLedger.Message.COLLECTION, NovelWorkspaceLedger.load(dir).commits.last().message)
    }

    @Test
    fun `short filler answer is not filed as a chapter`() = runTest {
        val dir = installProject()
        val generator = LoopingFakeGenerator(
            toolCalls = emptyList(),
            finalText = "我先看一下现状。", // 72 chars of filler → below threshold
        )
        val coordinator = NovelWorkspaceGhostwriteCoordinator(NovelWorkspaceRuntime(generator))
        val job = coordinator.newJob(dir, "主线", targetChapterCount = 1)

        val result = coordinator.runBatch(
            job = job,
            projectDirectory = dir,
            branchId = "B-1",
            settings = Settings(),
            model = Model(),
            assistant = Assistant(),
            isPaused = { false },
        ) { }

        // Filler twice → no-progress guard fails the batch; nothing got filed.
        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Failed)
        assertFalse(NovelWorkspaceStore(dir).list("branches/主线/chapters").any { it.endsWith("002-") })
    }

    @Test
    fun `latestFailed surfaces the newest failed job while listActive stays terminal-free`() {
        val dir = installProject()
        val old = NovelWorkspaceGhostwriteJob(
            id = "J-OLD", branchSlug = "主线", targetChapterCount = 5, startOrdinal = 1,
            status = NovelWorkspaceGhostwriteJob.STATUS_FAILED, reason = "provider 500",
            createdAt = Instant.parse("2026-08-22T00:00:00Z"), updatedAt = Instant.parse("2026-08-22T01:00:00Z"),
        )
        val recent = NovelWorkspaceGhostwriteJob(
            id = "J-NEW", branchSlug = "主线", targetChapterCount = 3, startOrdinal = 2,
            status = NovelWorkspaceGhostwriteJob.STATUS_FAILED, reason = "provider 429",
            createdAt = Instant.parse("2026-08-22T02:00:00Z"), updatedAt = Instant.parse("2026-08-22T03:00:00Z"),
        )
        val running = NovelWorkspaceGhostwriteJob(
            id = "J-RUN", branchSlug = "主线", targetChapterCount = 2, startOrdinal = 2,
            status = NovelWorkspaceGhostwriteJob.STATUS_RUNNING,
            createdAt = Instant.parse("2026-08-22T04:00:00Z"), updatedAt = Instant.parse("2026-08-22T04:00:00Z"),
        )
        NovelWorkspaceGhostwriteJobs.save(old, dir)
        NovelWorkspaceGhostwriteJobs.save(recent, dir)
        NovelWorkspaceGhostwriteJobs.save(running, dir)

        assertEquals("J-NEW", NovelWorkspaceGhostwriteJobs.latestFailed(dir)?.id)
        assertEquals(listOf("J-RUN"), NovelWorkspaceGhostwriteJobs.listActive(dir).map { it.id })
        // An empty book directory has no failures to surface.
        assertNull(NovelWorkspaceGhostwriteJobs.latestFailed(tempFolder.newFolder("empty")))
    }
}
