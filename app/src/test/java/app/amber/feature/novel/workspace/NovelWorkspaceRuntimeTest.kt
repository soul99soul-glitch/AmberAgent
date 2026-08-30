package app.amber.feature.novel.workspace

import app.amber.ai.core.MessageRole
import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.RunKernel
import app.amber.core.ai.transformers.InputMessageTransformer
import app.amber.core.ai.transformers.OutputMessageTransformer
import app.amber.core.settings.Settings
import app.amber.feature.novelworkspace.NovelWorkspaceFile
import app.amber.feature.novelworkspace.NovelWorkspaceInstaller
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceManifestRenderer
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceUndo
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
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

    /**
     * Coordinator wired through the real kernel chain (registry + runner +
     * payload mailbox) with the scripted fake kernel behind the runtime —
     * ghostwrite chapters now execute as AgentRunner runs.
     */
    /**
     * Coordinator wired through the real kernel chain (registry + runner +
     * payload mailbox) with the scripted fake kernel behind the runtime —
     * ghostwrite chapters now execute as AgentRunner runs. The runner shares
     * the test scheduler so `withTimeout` in the coordinator and the handler
     * run on the same (virtual) clock.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun ghostwriteCoordinator(
        runtime: NovelWorkspaceRuntime,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler? = null,
        eventStore: app.amber.core.agent.runtime.InMemoryAgentEventStore? = null,
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
        // Step 3: when the test shares the store, scopes get the real
        // persisting writer so the turn's domain events land in agent_event.
        val scopeFactory: ((app.amber.core.agent.runtime.AgentRunId, app.amber.core.agent.runtime.AgentInput) -> app.amber.core.agent.runtime.RunScope)? =
            eventStore?.let { store ->
                val codecs = mapOf(
                    NovelTurnEventPayload.ToolActivity::class.qualifiedName!! to
                        app.amber.core.agent.runtime.AgentEventPayloadCodec(
                            NovelTurnEventPayload.TYPE_TOOL_ACTIVITY,
                            NovelTurnEventPayload.ToolActivity.serializer(),
                        ),
                    NovelTurnEventPayload.TurnCompleted::class.qualifiedName!! to
                        app.amber.core.agent.runtime.AgentEventPayloadCodec(
                            NovelTurnEventPayload.TYPE_TURN_COMPLETED,
                            NovelTurnEventPayload.TurnCompleted.serializer(),
                        ),
                    NovelTurnEventPayload.TurnFailed::class.qualifiedName!! to
                        app.amber.core.agent.runtime.AgentEventPayloadCodec(
                            NovelTurnEventPayload.TYPE_TURN_FAILED,
                            NovelTurnEventPayload.TurnFailed.serializer(),
                        ),
                )
                ({ runId, _ ->
                    app.amber.core.agent.runtime.adapter.LegacyRunScope(
                        runId = runId,
                        events = app.amber.core.agent.runtime.impl.PersistingEventWriter(
                            runId = runId,
                            parentRunId = null,
                            agentDescriptorId = NovelTurnDescriptor.ID.value,
                            store = store,
                            json = kotlinx.serialization.json.Json,
                            codecs = codecs,
                        ),
                    )
                })
            }
        val runner = app.amber.core.agent.runtime.impl.InProcessAgentRunner(
            registry,
            eventStore ?: app.amber.core.agent.runtime.InMemoryAgentEventStore(),
            runScopeFactory = scopeFactory ?: { id, _ ->
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

    private fun installProject(
        projectName: String = "project",
        includePlanInInstall: Boolean = true,
    ): File {
        val dir = tempFolder.root.resolve(projectName)
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
        ) + if (includePlanInInstall) {
            listOf(
                NovelWorkspaceFile(
                    "branches/主线/plan/this-chapter.md",
                    "第二章推进陈桥兵变，结尾留下入汴钩子。",
                ),
            )
        } else {
            emptyList()
        }
        NovelWorkspaceInstaller.install(files, dir)
        return dir
    }

    /** Mimics GenerationHandler: executes tool calls itself, then produces the final answer. */
    private class LoopingFakeKernel(
        private val toolCalls: List<Pair<String, JsonElement>>,
        private val finalText: String,
        /** First N generateText calls throw, simulating transient provider failures. */
        private val failFirstNCalls: Int = 0,
        /** Throw after executing the tools but before the final answer (mid-stream cut). */
        private val failAfterTools: Boolean = false,
        /** Test hook for a durable owner change while the provider turn is in flight. */
        private val beforeFinal: (() -> Unit)? = null,
    ) : RunKernel {
        var calls = 0
        var lastAutoApprovedToolNames: Set<String> = emptySet()

        override fun run(session: GenerationRunSession): Flow<GenerationChunk> = flow {
            calls += 1
            lastAutoApprovedToolNames = session.autoApprovedToolNames
            if (calls <= failFirstNCalls) throw RuntimeException("provider 429")
            val executed = mutableListOf<UIMessagePart.Tool>()
            for ((name, input) in toolCalls) {
                val tool = session.tools.first { it.name == name }
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
            beforeFinal?.invoke()
            val assistant = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = executed + UIMessagePart.Text(finalText),
            )
            emit(GenerationChunk.Messages(session.messages + assistant))
        }
    }

    private enum class ReviewDecision { Pass, Rewrite, Blocking }

    /** Two-channel fake: normal-budget calls return prose; 10k-budget calls return bound review JSON. */
    private class ReviewedBatchFakeKernel(
        private val chapters: List<String>,
        private val decisions: List<ReviewDecision>,
        private val failFirstNWritingCalls: Int = 0,
        private val reviewNextPlans: List<String?> = emptyList(),
        private val plannedNextPlans: List<String> = emptyList(),
    ) : RunKernel {
        var calls = 0
        var reviewCalls = 0
        var planningCalls = 0
        var minimumReviewBudget = Int.MAX_VALUE
        private var chapterIndex = 0
        private var reviewIndex = 0
        private var planningIndex = 0
        private var writingCalls = 0

        override fun run(session: GenerationRunSession): Flow<GenerationChunk> = flow {
            calls += 1
            val prompt = session.messages
                .flatMap { it.parts }
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
            val isPlanning = prompt.contains("下一章规划轮") ||
                prompt.contains("next-chapter planning turn")
            val isReview = !isPlanning && (session.settings.maxTokens ?: 0) >=
                NovelWorkspaceGhostwriteCoordinator.MIN_REVIEW_OUTPUT_TOKENS
            val output = if (isPlanning) {
                planningCalls += 1
                val body = plannedNextPlans.getOrElse(planningIndex++) {
                    "补充计划：推进新的冲突，保留既有事实，并在结尾留下下一章钩子。"
                }
                buildJsonObject { put("nextPlan", body) }.toString()
            } else if (isReview) {
                reviewCalls += 1
                minimumReviewBudget = minOf(minimumReviewBudget, session.settings.maxTokens ?: 0)
                val candidateId = prompt.lineSequence()
                    .first { it.startsWith("candidateId: ") }
                    .substringAfter(": ")
                val chapterOrdinal = prompt.lineSequence()
                    .first { it.startsWith("chapterOrdinal: ") }
                    .substringAfter(": ")
                    .toInt()
                val planId = prompt.lineSequence()
                    .first { it.startsWith("planId: ") }
                    .substringAfter(": ")
                val planDigest = prompt.lineSequence()
                    .first { it.startsWith("planDigest: ") }
                    .substringAfter(": ")
                val currentReview = reviewIndex++
                val decision = decisions.getOrElse(currentReview) { ReviewDecision.Pass }
                val nextPlan = reviewNextPlans.getOrElse(currentReview) {
                    "第 ${chapterOrdinal + 1} 章推进随军入汴，并留下新的局势钩子。"
                }
                buildJsonObject {
                    put("candidateId", candidateId)
                    put("chapterOrdinal", chapterOrdinal)
                    put("planId", planId)
                    put("planDigest", planDigest)
                    put("blocking", decision == ReviewDecision.Blocking)
                    put("rewriteRequired", decision == ReviewDecision.Rewrite)
                    put("repairInstructions", buildJsonArray {
                        if (decision == ReviewDecision.Rewrite) add("补足赵大进入柴房的明确动机")
                    })
                    put("findings", buildJsonArray {
                        if (decision != ReviewDecision.Pass) add(buildJsonObject {
                            put("kind", "hard_continuity")
                            put("message", "赵大的行动缺少当前候选可定位的动机")
                            put("candidateEvidence", "赵大直接进入柴房")
                            put("planEvidence", JsonNull)
                        })
                    })
                    put("plotState", "赵大进入柴房并发现兵变线索，准备随军入汴。")
                    put("chapterHighlight", "第 $chapterOrdinal 章：赵大发现兵变线索。")
                    if (nextPlan == null) put("nextPlan", JsonNull) else put("nextPlan", nextPlan)
                }.toString()
            } else {
                writingCalls += 1
                if (writingCalls <= failFirstNWritingCalls) throw RuntimeException("provider 429")
                chapters.getOrElse(chapterIndex++) { chapters.last() }
            }
            emit(
                GenerationChunk.Messages(
                    session.messages + UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text(output)),
                    ),
                ),
            )
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
    )

    @Test
    fun `free writes land immediately, canon writes wait for the author`() = runTest {
        val dir = installProject()
        val runtime = NovelWorkspaceRuntime(
            LoopingFakeKernel(
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
            LoopingFakeKernel(
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
        val generator = LoopingFakeKernel(emptyList(), "完成。")
        NovelWorkspaceRuntime(generator).runTurn(request(dir)).toList()
        assertEquals(setOf("novel_workspace_write"), generator.lastAutoApprovedToolNames)
    }

    @Test
    fun `model front matter never replaces host identity and host files refuse writes`() = runTest {
        val dir = installProject()
        val runtime = NovelWorkspaceRuntime(
            LoopingFakeKernel(
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
            LoopingFakeKernel(
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
        val runtime = NovelWorkspaceRuntime(LoopingFakeKernel(emptyList(), ""))
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
        val runtime = NovelWorkspaceRuntime(LoopingFakeKernel(emptyList(), ""))
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
        val runtime = NovelWorkspaceRuntime(LoopingFakeKernel(emptyList(), ""))
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

        // Undoing the exact commit that opened the gate must release that gate too.
        assertTrue(runtime.undoLast(dir))
        assertNull(NovelWorkspaceUnresolvedStore.entryFor(dir, "主线"))
    }

    @Test
    fun `autoApproveCanon writes canon directly and commits at turn end`() = runTest {
        val dir = installProject()
        val runtime = NovelWorkspaceRuntime(
            LoopingFakeKernel(
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
        assertTrue(runtime.canUndo(dir))
    }

    @Test
    fun `legacy running job auto commit does not require phase zero plan binding`() = runTest {
        val dir = installProject()
        val runtime = NovelWorkspaceRuntime(
            LoopingFakeKernel(
                toolCalls = listOf(
                    "novel_workspace_write" to buildJsonObject {
                        put("path", "branches/主线/chapters/002-旧批次.md")
                        put("content", "旧批次完成的第二章。")
                    },
                ),
                finalText = "第二章已完成。",
            ),
        )
        val legacyJob = NovelWorkspaceGhostwriteJob(
            id = "LEGACY-JOB",
            branchSlug = "主线",
            targetChapterCount = 1,
            startOrdinal = 1,
            status = NovelWorkspaceGhostwriteJob.STATUS_RUNNING,
            createdAt = exportedAt,
            updatedAt = exportedAt,
        )
        NovelWorkspaceGhostwriteJobs.save(legacyJob, dir)

        val events = runtime.runTurn(
            request(dir, "请写第 2 章。").copy(
                autoApproveCanon = true,
                autoCommitMessage = "代笔收录",
                ownerJobId = legacyJob.id,
                ownerExecutionId = legacyJob.executionKey,
                ghostwriteChapterOrdinal = 2,
            ),
        ).toList()

        assertEquals(1, events.filterIsInstance<NovelWorkspaceRuntime.TurnEvent.Completed>().size)
        assertTrue(events.none { it is NovelWorkspaceRuntime.TurnEvent.Failed })
        assertEquals(
            "旧批次完成的第二章。",
            NovelWorkspaceMarkdown.parseFile(
                NovelWorkspaceStore(dir).read("branches/主线/chapters/002-旧批次.md")!!,
            ).body,
        )
        assertTrue(runtime.canUndo(dir))
    }

    @Test
    fun `legacy running job host fallback commits and keeps undo accounting`() = runTest {
        val dir = installProject()
        val chapter = "赵大摸黑进了柴房。\n\n" + "外头风声更紧了，他数着自己的心跳等天亮。".repeat(60)
        val runtime = NovelWorkspaceRuntime(
            LoopingFakeKernel(toolCalls = emptyList(), finalText = "# 火起\n\n$chapter"),
        )
        val coordinator = ghostwriteCoordinator(runtime, testScheduler)
        val legacyJob = NovelWorkspaceGhostwriteJob(
            id = "LEGACY-HOST-JOB",
            branchSlug = "主线",
            targetChapterCount = 1,
            startOrdinal = 1,
            status = NovelWorkspaceGhostwriteJob.STATUS_RUNNING,
            createdAt = exportedAt,
            updatedAt = exportedAt,
        )
        NovelWorkspaceGhostwriteJobs.save(legacyJob, dir)

        val result = coordinator.runBatch(
            job = legacyJob,
            projectDirectory = dir,
            branchId = "B-1",
            settings = Settings(),
            model = Model(),
            isPaused = { false },
        ) { }

        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Completed)
        assertTrue(NovelWorkspaceStore(dir).list("branches/主线/chapters").any { it.endsWith("002-火起.md") })
        assertTrue(runtime.canUndo(dir))
    }

    @Test
    fun `bound ghostwrite tools are read only and only a private candidate is persisted`() = runTest {
        val dir = installProject()
        val chapter = "赵大摸黑进了柴房。\n\n" + "外头风声更紧了，他数着自己的心跳等天亮。".repeat(60)
        val generator = LoopingFakeKernel(
            toolCalls = listOf(
                "novel_workspace_write" to buildJsonObject {
                    put("path", "branches/主线/plan/this-chapter.md")
                    put("content", "模型试图替换确认计划。")
                },
                "novel_workspace_write" to buildJsonObject {
                    put("path", "branches/主线/chapters/002-入汴.md")
                    put("content", "第二章正文。")
                },
                "novel_workspace_write" to buildJsonObject {
                    put("path", "branches/主线/plot/current.md")
                    put("content", "模型试图修改剧情。")
                },
                "novel_workspace_write" to buildJsonObject {
                    put("path", "setting/characters/赵大.md")
                    put("content", "模型试图修改设定。")
                },
                "novel_workspace_write" to buildJsonObject {
                    put("path", "drafts/候选.md")
                    put("content", "模型试图创建普通草稿。")
                },
            ),
            finalText = "# 火起\n\n$chapter",
        )
        val runtime = NovelWorkspaceRuntime(generator)
        val coordinator = ghostwriteCoordinator(runtime, testScheduler)
        val job = coordinator.newJob(dir, "主线", targetChapterCount = 1)
        val ledgerBefore = NovelWorkspaceLedger.load(dir)
        val treeBefore = NovelWorkspaceStore(dir).fileTree()

        val result = coordinator.ghostwriteOneChapter(
            dir, "B-1", "主线", Settings(), Model(), 2,
            ownerJobId = job.id, ownerExecutionId = job.executionKey,
        )

        assertNotNull(result.candidate)
        val store = NovelWorkspaceStore(dir)
        assertEquals(
            "第二章推进陈桥兵变，结尾留下入汴钩子。",
            store.read("branches/主线/plan/this-chapter.md"),
        )
        assertEquals(treeBefore, store.fileTree())
        assertEquals(ledgerBefore, NovelWorkspaceLedger.load(dir))
        assertTrue(store.list("branches/主线/chapters").none { it.contains("002-") })
        assertFalse(store.exists("drafts/候选.md"))
        assertTrue(runtime.pendingProposals.value.isEmpty())
        val persisted = checkNotNull(NovelWorkspaceGhostwriteJobs.load(dir, job.id))
        assertEquals(0, persisted.pendingCandidate?.attempt)
        assertEquals(job.planId, persisted.pendingCandidate?.planId)
        assertEquals(job.planDigest, persisted.pendingCandidate?.planDigest)
        assertEquals("火起", persisted.pendingCandidate?.title)
        assertEquals(0, NovelWorkspaceGhostwriteJobs.progress(persisted, store))

        val recovered = coordinator.ghostwriteOneChapter(
            dir, "B-1", "主线", Settings(), Model(), 2,
            ownerJobId = job.id, ownerExecutionId = job.executionKey,
        )
        assertNotNull(recovered.candidate)
        assertEquals(1, generator.calls)
    }

    @Test
    fun `candidate keeps one identity and plan across at most two targeted rewrites`() = runTest {
        val dir = installProject()
        val chapter = "赵大摸黑进了柴房。\n\n" + "外头风声更紧了，他数着自己的心跳等天亮。".repeat(60)
        val generator = LoopingFakeKernel(emptyList(), "# 火起\n\n$chapter")
        val coordinator = ghostwriteCoordinator(NovelWorkspaceRuntime(generator), testScheduler)
        val job = coordinator.newJob(dir, "主线", targetChapterCount = 1)

        val initial = coordinator.ghostwriteOneChapter(
            dir, "B-1", "主线", Settings(), Model(), 2,
            ownerJobId = job.id, ownerExecutionId = job.executionKey,
        ).candidate!!
        val firstRewrite = coordinator.ghostwriteOneChapter(
            dir, "B-1", "主线", Settings(), Model(), 2,
            ownerJobId = job.id, ownerExecutionId = job.executionKey,
            repairInstructions = listOf("补足赵大进入柴房的动机"),
        ).candidate!!
        val secondRewrite = coordinator.ghostwriteOneChapter(
            dir, "B-1", "主线", Settings(), Model(), 2,
            ownerJobId = job.id, ownerExecutionId = job.executionKey,
            repairInstructions = listOf("修复结尾入汴钩子"),
        ).candidate!!
        val rejected = coordinator.ghostwriteOneChapter(
            dir, "B-1", "主线", Settings(), Model(), 2,
            ownerJobId = job.id, ownerExecutionId = job.executionKey,
            repairInstructions = listOf("第三次重写不应执行"),
        )

        assertEquals(3, generator.calls)
        assertEquals(initial.id, firstRewrite.id)
        assertEquals(initial.id, secondRewrite.id)
        assertEquals(listOf(0, 1, 2), listOf(initial.attempt, firstRewrite.attempt, secondRewrite.attempt))
        assertTrue(listOf(initial, firstRewrite, secondRewrite).all { it.planId == job.planId })
        assertTrue(listOf(initial, firstRewrite, secondRewrite).all { it.planDigest == job.planDigest })
        assertTrue(rejected.error?.contains("两次") == true)
        assertEquals(1, NovelWorkspaceLedger.load(dir).commits.size)
    }

    @Test
    fun `joint review parser is strict about the complete JSON object`() {
        val coordinator = ghostwriteCoordinator(NovelWorkspaceRuntime(LoopingFakeKernel(emptyList(), "")))
        val valid = """{"candidateId":"C","chapterOrdinal":2,"planId":"P","planDigest":"D","blocking":false,"rewriteRequired":false,"repairInstructions":[],"findings":[],"plotState":"state","chapterHighlight":"event","nextPlan":null}"""

        assertNotNull(coordinator.parseJointReview(valid))
        assertNull(coordinator.parseJointReview("```json\n$valid\n```"))
        assertNull(coordinator.parseJointReview(valid.dropLast(1) + ",\"unknown\":true}"))
        assertNull(coordinator.parseJointReview(valid.replace("\"plotState\":\"state\",", "")))
        assertEquals("下一章计划", coordinator.parseNextPlan("""{"nextPlan":"下一章计划"}"""))
        assertNull(coordinator.parseNextPlan("""```json
            {"nextPlan":"下一章计划"}
            ```""".trimIndent()))
        assertNull(coordinator.parseNextPlan("""{"nextPlan":"","unknown":true}"""))
    }

    @Test
    fun `healthy reviewed chapter uses two calls and commits chapter plus plot atomically`() = runTest {
        val dir = installProject()
        val chapter = "# 火起\n\n" + "赵大摸黑进了柴房，终于看清兵变的旗号。".repeat(70)
        val kernel = ReviewedBatchFakeKernel(listOf(chapter), listOf(ReviewDecision.Pass))
        val runtime = NovelWorkspaceRuntime(kernel)
        val coordinator = ghostwriteCoordinator(runtime, testScheduler)
        val job = coordinator.newJob(dir, "主线", targetChapterCount = 1)

        val result = coordinator.runBatch(
            job = job,
            projectDirectory = dir,
            branchId = "B-1",
            settings = Settings(maxTokens = 2_048),
            model = Model(),
            isPaused = { false },
        ) { }

        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Completed)
        assertEquals(2, kernel.calls)
        assertEquals(1, kernel.reviewCalls)
        assertTrue(kernel.minimumReviewBudget >= 10_000)
        val store = NovelWorkspaceStore(dir)
        val ledger = NovelWorkspaceLedger.load(dir)
        val committed = ledger.headOf("B-1")!!
        assertEquals(NovelWorkspaceLedger.Message.GHOSTWRITE_REVIEWED, committed.message)
        val changed = NovelWorkspaceLedger.changedPaths(committed, ledger.commits)
        assertTrue(changed.any { it.contains("/chapters/002-") })
        assertTrue("branches/主线/plot/current.md" in changed)
        assertFalse("branches/主线/plan/this-chapter.md" in committed.files)
        assertFalse(NovelWorkspaceLedger.isPlotStale(store, ledger, "主线"))
        val persisted = NovelWorkspaceGhostwriteJobs.load(dir, job.id)!!
        assertEquals(1, NovelWorkspaceGhostwriteJobs.progress(persisted, store))
        assertTrue(persisted.isVersionBound)
        assertTrue(persisted.planId.isBlank())
        assertTrue(persisted.planDigest.isBlank())
        assertTrue(persisted.confirmedPlan.isBlank())
        assertNull(persisted.pendingCandidate)
        assertNotNull(persisted.receipts.single().candidateId)
        assertTrue(runtime.pendingProposals.value.isEmpty())
        assertTrue(store.list("drafts").isEmpty())
        assertEquals(
            setOf(
                changed.first { it.contains("/chapters/002-") },
                "branches/主线/plot/current.md",
                "branches/主线/plan/this-chapter.md",
            ),
            NovelWorkspaceUndo.load(dir)!!.files.keys,
        )
    }

    @Test
    fun `two reviewed chapters rotate plan atomically without a planning call`() = runTest {
        val dir = installProject(includePlanInInstall = false)
        val store = NovelWorkspaceStore(dir)
        store.write(
            "branches/主线/plan/this-chapter.md",
            "第二章推进陈桥兵变，结尾留下入汴钩子。",
        )
        val upcomingPath = "branches/主线/plan/upcoming.md"
        val upcoming = "远期弧线保持独立，不应被本批次消费。"
        store.write(upcomingPath, upcoming)
        val chapters = listOf(2, 3).map { ordinal ->
            "# 第 $ordinal 章\n\n" + "赵大沿着兵变线索推进局势。".repeat(70)
        }
        val kernel = ReviewedBatchFakeKernel(chapters, List(2) { ReviewDecision.Pass })
        val coordinator = ghostwriteCoordinator(NovelWorkspaceRuntime(kernel), testScheduler)
        val job = coordinator.newJob(dir, "主线", targetChapterCount = 2)

        val result = coordinator.runBatch(
            job = job,
            projectDirectory = dir,
            branchId = "B-1",
            settings = Settings(),
            model = Model(),
            isPaused = { false },
        ) { }

        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Completed)
        assertEquals(4, kernel.calls)
        assertEquals(2, kernel.reviewCalls)
        assertEquals(0, kernel.planningCalls)
        val persisted = NovelWorkspaceGhostwriteJobs.load(dir, job.id)!!
        assertEquals(2, persisted.receipts.size)
        assertEquals(2, persisted.receipts.map { it.planId }.distinct().size)
        assertEquals(2, persisted.receipts.map { it.planDigest }.distinct().size)
        assertTrue(persisted.planId.isBlank())
        assertNull(store.read("branches/主线/plan/this-chapter.md"))
        assertEquals(upcoming, store.read(upcomingPath))
        val reviewedCommits = NovelWorkspaceLedger.load(dir).commits.filter {
            it.message == NovelWorkspaceLedger.Message.GHOSTWRITE_REVIEWED
        }
        assertEquals(2, reviewedCommits.size)
        val planPath = "branches/主线/plan/this-chapter.md"
        assertNotNull(reviewedCommits.first().files[planPath])
        assertNull(reviewedCommits.last().files[planPath])
    }

    @Test
    fun `missing reviewed next plan adds exactly one read-only planning turn`() = runTest {
        val dir = installProject()
        val chapters = listOf(2, 3).map { ordinal ->
            "# 第 $ordinal 章\n\n" + "赵大沿着兵变线索推进局势。".repeat(70)
        }
        val kernel = ReviewedBatchFakeKernel(
            chapters = chapters,
            decisions = List(2) { ReviewDecision.Pass },
            reviewNextPlans = listOf(null, null),
            plannedNextPlans = listOf("第三章承接兵变线索，推进随军入汴。"),
        )
        val coordinator = ghostwriteCoordinator(NovelWorkspaceRuntime(kernel), testScheduler)
        val job = coordinator.newJob(dir, "主线", targetChapterCount = 2)

        val result = coordinator.runBatch(
            job = job,
            projectDirectory = dir,
            branchId = "B-1",
            settings = Settings(),
            model = Model(),
            isPaused = { false },
        ) { }

        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Completed)
        assertEquals(5, kernel.calls)
        assertEquals(2, kernel.reviewCalls)
        assertEquals(1, kernel.planningCalls)
        assertNull(NovelWorkspaceStore(dir).read("branches/主线/plan/this-chapter.md"))
    }

    @Test
    fun `five and ten chapter healthy batches stay at two calls per chapter`() = runTest {
        for (target in listOf(5, 10)) {
            val dir = installProject("healthy-$target")
            val store = NovelWorkspaceStore(dir)
            val upcomingPath = "branches/主线/plan/upcoming.md"
            val upcoming = "第 $target 章批次的远期弧线保持不变。"
            store.write(upcomingPath, upcoming)
            val chapters = (2..target + 1).map { ordinal ->
                "# 第 $ordinal 章\n\n" + "赵大依照当前计划推进故事，并保持人物状态连续。".repeat(60)
            }
            val kernel = ReviewedBatchFakeKernel(chapters, List(target) { ReviewDecision.Pass })
            val coordinator = ghostwriteCoordinator(NovelWorkspaceRuntime(kernel), testScheduler)
            val job = coordinator.newJob(dir, "主线", targetChapterCount = target)

            val result = coordinator.runBatch(
                job = job,
                projectDirectory = dir,
                branchId = "B-1",
                settings = Settings(),
                model = Model(),
                isPaused = { false },
            ) { }

            assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Completed)
            assertEquals(target * 2, kernel.calls)
            assertEquals(target, kernel.reviewCalls)
            assertEquals(0, kernel.planningCalls)
            val persisted = NovelWorkspaceGhostwriteJobs.load(dir, job.id)!!
            assertEquals(target, persisted.receipts.size)
            assertEquals(target, persisted.receipts.map { it.planId }.distinct().size)
            assertEquals(target, persisted.receipts.map { it.planDigest }.distinct().size)
            assertNull(store.read("branches/主线/plan/this-chapter.md"))
            assertEquals(upcoming, store.read(upcomingPath))
        }
    }

    @Test
    fun `blocking joint review keeps the private candidate out of canon`() = runTest {
        val dir = installProject()
        val chapter = "# 火起\n\n" + "赵大没有缘由便闯入柴房。".repeat(90)
        val kernel = ReviewedBatchFakeKernel(listOf(chapter), listOf(ReviewDecision.Blocking))
        val coordinator = ghostwriteCoordinator(NovelWorkspaceRuntime(kernel), testScheduler)
        val job = coordinator.newJob(dir, "主线", targetChapterCount = 1)

        val result = coordinator.runBatch(
            job = job,
            projectDirectory = dir,
            branchId = "B-1",
            settings = Settings(),
            model = Model(),
            isPaused = { false },
        ) { }

        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Failed)
        assertEquals(2, kernel.calls)
        assertEquals(1, NovelWorkspaceLedger.load(dir).commits.size)
        assertTrue(NovelWorkspaceStore(dir).list("branches/主线/chapters").none { it.contains("002-") })
        val persisted = NovelWorkspaceGhostwriteJobs.load(dir, job.id)!!
        assertNotNull(persisted.pendingCandidate)
        assertTrue(persisted.receipts.isEmpty())
    }

    @Test
    fun `repairable review rewrites the same candidate then commits once`() = runTest {
        val dir = installProject()
        val first = "# 火起\n\n" + "赵大直接进入柴房。".repeat(100)
        val repaired = "# 火起\n\n" + "赵大为追查失踪军械摸黑进入柴房。".repeat(80)
        val kernel = ReviewedBatchFakeKernel(
            chapters = listOf(first, repaired),
            decisions = listOf(ReviewDecision.Rewrite, ReviewDecision.Pass),
        )
        val coordinator = ghostwriteCoordinator(NovelWorkspaceRuntime(kernel), testScheduler)
        val job = coordinator.newJob(dir, "主线", targetChapterCount = 1)

        val result = coordinator.runBatch(
            job = job,
            projectDirectory = dir,
            branchId = "B-1",
            settings = Settings(),
            model = Model(),
            isPaused = { false },
        ) { }

        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Completed)
        assertEquals(4, kernel.calls)
        assertEquals(2, kernel.reviewCalls)
        val persisted = NovelWorkspaceGhostwriteJobs.load(dir, job.id)!!
        assertEquals(1, persisted.receipts.size)
        assertNull(persisted.pendingCandidate)
        assertEquals(2, NovelWorkspaceLedger.load(dir).commits.size)
    }

    @Test
    fun `cold recovery repairs reviewed commit accounting without another model call`() = runTest {
        val dir = installProject()
        val chapter = "# 火起\n\n" + "赵大摸黑进了柴房，发现兵变线索。".repeat(80)
        val kernel = ReviewedBatchFakeKernel(listOf(chapter), listOf(ReviewDecision.Pass))
        val runtime = NovelWorkspaceRuntime(kernel)
        val coordinator = ghostwriteCoordinator(runtime, testScheduler)
        val job = coordinator.newJob(dir, "主线", targetChapterCount = 1)
        coordinator.ghostwriteOneChapter(
            dir, "B-1", "主线", Settings(), Model(), 2,
            ownerJobId = job.id, ownerExecutionId = job.executionKey,
        )
        coordinator.reviewCandidate(
            dir, "B-1", "主线", Settings(), Model(), job.id, job.executionKey,
        )
        val beforeCommit = NovelWorkspaceGhostwriteJobs.load(dir, job.id)!!
        runtime.commitReviewedChapter(dir, "B-1", "主线", job.id, job.executionKey)
        NovelWorkspaceGhostwriteJobs.save(beforeCommit, dir)
        val callsBeforeRecovery = kernel.calls

        assertTrue(runtime.reconcileReviewedChapter(dir, job.id, job.executionKey))
        assertEquals(callsBeforeRecovery, kernel.calls)
        val recovered = NovelWorkspaceGhostwriteJobs.load(dir, job.id)!!
        assertEquals(1, NovelWorkspaceGhostwriteJobs.progress(recovered, NovelWorkspaceStore(dir)))
        assertTrue(recovered.isVersionBound)
        assertTrue(recovered.planId.isBlank())
        assertNull(recovered.pendingCandidate)
        assertNull(NovelWorkspaceStore(dir).read("branches/主线/plan/this-chapter.md"))
    }

    @Test
    fun `cold recovery restores nonfinal plan rotation without another model call`() = runTest {
        val dir = installProject()
        val chapter = "# 火起\n\n" + "赵大摸黑进了柴房，发现兵变线索。".repeat(80)
        val kernel = ReviewedBatchFakeKernel(listOf(chapter), listOf(ReviewDecision.Pass))
        val runtime = NovelWorkspaceRuntime(kernel)
        val coordinator = ghostwriteCoordinator(runtime, testScheduler)
        val job = coordinator.newJob(dir, "主线", targetChapterCount = 2)
        coordinator.ghostwriteOneChapter(
            dir, "B-1", "主线", Settings(), Model(), 2,
            ownerJobId = job.id, ownerExecutionId = job.executionKey,
        )
        coordinator.reviewCandidate(
            dir, "B-1", "主线", Settings(), Model(), job.id, job.executionKey,
        )
        val beforeCommit = NovelWorkspaceGhostwriteJobs.load(dir, job.id)!!
        val candidate = beforeCommit.pendingCandidate!!
        runtime.commitReviewedChapter(dir, "B-1", "主线", job.id, job.executionKey)
        NovelWorkspaceGhostwriteJobs.save(beforeCommit, dir)
        val callsBeforeRecovery = kernel.calls

        assertTrue(runtime.reconcileReviewedChapter(dir, job.id, job.executionKey))
        assertEquals(callsBeforeRecovery, kernel.calls)
        val recovered = NovelWorkspaceGhostwriteJobs.load(dir, job.id)!!
        assertEquals(1, NovelWorkspaceGhostwriteJobs.progress(recovered, NovelWorkspaceStore(dir)))
        assertEquals(NovelWorkspaceGhostwriteJobs.reviewedNextPlanId(candidate.id), recovered.planId)
        assertEquals(
            recovered.confirmedPlan,
            NovelWorkspaceMarkdown.parseFile(
                NovelWorkspaceStore(dir).read("branches/主线/plan/this-chapter.md")!!,
            ).body,
        )
        assertNull(recovered.pendingCandidate)
    }

    @Test
    fun `cold recovery restores partial reviewed writes before ledger commit`() = runTest {
        val dir = installProject()
        val chapter = "# 火起\n\n" + "赵大摸黑进了柴房，发现兵变线索。".repeat(80)
        val kernel = ReviewedBatchFakeKernel(listOf(chapter), listOf(ReviewDecision.Pass))
        val runtime = NovelWorkspaceRuntime(kernel)
        val coordinator = ghostwriteCoordinator(runtime, testScheduler)
        val job = coordinator.newJob(dir, "主线", targetChapterCount = 2)
        coordinator.ghostwriteOneChapter(
            dir, "B-1", "主线", Settings(), Model(), 2,
            ownerJobId = job.id, ownerExecutionId = job.executionKey,
        )
        coordinator.reviewCandidate(
            dir, "B-1", "主线", Settings(), Model(), job.id, job.executionKey,
        )
        val jobBeforeCommit = NovelWorkspaceGhostwriteJobs.load(dir, job.id)!!
        val ledgerBeforeCommit = NovelWorkspaceLedger.load(dir)
        runtime.commitReviewedChapter(dir, "B-1", "主线", job.id, job.executionKey)
        val store = NovelWorkspaceStore(dir)
        val undo = NovelWorkspaceUndo.load(dir)!!
        val chapterPath = undo.files.keys.single { "/chapters/" in it }
        val writtenChapter = store.read(chapterPath)!!

        NovelWorkspaceLedger.save(ledgerBeforeCommit, dir)
        NovelWorkspaceGhostwriteJobs.save(jobBeforeCommit, dir)
        undo.files.forEach { (path, content) ->
            if (content == null) store.delete(path) else store.write(path, content)
        }
        assertEquals(
            jobBeforeCommit.expectedTreeDigest,
            NovelWorkspaceLedger.treeSHA256(store.fileTree()),
        )
        store.write(chapterPath, writtenChapter)
        val callsBeforeRecovery = kernel.calls

        assertFalse(runtime.reconcileReviewedChapter(dir, job.id, job.executionKey))
        assertEquals(callsBeforeRecovery, kernel.calls)
        undo.files.forEach { (path, content) -> assertEquals(content, store.read(path)) }
        assertNull(NovelWorkspaceUndo.load(dir))
        assertEquals(ledgerBeforeCommit, NovelWorkspaceLedger.load(dir))
        assertEquals(jobBeforeCommit, NovelWorkspaceGhostwriteJobs.load(dir, job.id))
    }

    @Test
    fun `cold recovery refuses a working tree that no longer matches the reviewed commit`() = runTest {
        val dir = installProject()
        val chapter = "# 火起\n\n" + "赵大摸黑进了柴房，发现兵变线索。".repeat(80)
        val kernel = ReviewedBatchFakeKernel(listOf(chapter), listOf(ReviewDecision.Pass))
        val runtime = NovelWorkspaceRuntime(kernel)
        val coordinator = ghostwriteCoordinator(runtime, testScheduler)
        val job = coordinator.newJob(dir, "主线", targetChapterCount = 1)
        coordinator.ghostwriteOneChapter(
            dir, "B-1", "主线", Settings(), Model(), 2,
            ownerJobId = job.id, ownerExecutionId = job.executionKey,
        )
        coordinator.reviewCandidate(
            dir, "B-1", "主线", Settings(), Model(), job.id, job.executionKey,
        )
        val beforeCommit = NovelWorkspaceGhostwriteJobs.load(dir, job.id)!!
        runtime.commitReviewedChapter(dir, "B-1", "主线", job.id, job.executionKey)
        NovelWorkspaceGhostwriteJobs.save(beforeCommit, dir)
        NovelWorkspaceStore(dir).write("drafts/unrelated.md", "未收录草稿")

        val recovery = runCatching {
            runtime.reconcileReviewedChapter(dir, job.id, job.executionKey)
        }

        assertTrue(recovery.isFailure)
        val persisted = NovelWorkspaceGhostwriteJobs.load(dir, job.id)!!
        assertNotNull(persisted.pendingCandidate)
        assertTrue(persisted.receipts.isEmpty())
    }

    @Test
    fun `ghostwrite batch derives progress from owned receipts, not chapter ordinals`() = runTest {
        val dir = installProject()
        val runtime = NovelWorkspaceRuntime(LoopingFakeKernel(emptyList(), ""))
        val coordinator = ghostwriteCoordinator(runtime, testScheduler)
        val store = NovelWorkspaceStore(dir)

        val job = coordinator.newJob(dir, "主线", targetChapterCount = 2)
        assertEquals(1, job.startOrdinal) // the book already has chapter 1

        // A file written before its ledger commit is not durable progress.
        store.write("branches/主线/chapters/002-orphan.md", "未提交正文。")
        assertEquals(0, NovelWorkspaceGhostwriteJobs.progress(job, store))
        store.delete("branches/主线/chapters/002-orphan.md")

        // Simulate two committed chapters through the running batch owner's path.
        runtime.commitGhostwrittenChapter(
            dir, "B-1", "主线", 2, "入汴", "第二章。",
            ownerJobId = job.id, ownerExecutionId = job.executionKey,
        )
        assertEquals(1, NovelWorkspaceGhostwriteJobs.progress(job, store))

        runtime.commitGhostwrittenChapter(
            dir, "B-1", "主线", 3, "陈桥", "第三章。",
            ownerJobId = job.id, ownerExecutionId = job.executionKey,
        )
        assertEquals(2, NovelWorkspaceGhostwriteJobs.progress(job, store))

        // Progress is receipt + ancestry-derived: reloading the durable job agrees.
        val reloaded = NovelWorkspaceGhostwriteJobs.load(dir, job.id)!!
        assertEquals(2, NovelWorkspaceGhostwriteJobs.progress(reloaded, NovelWorkspaceStore(dir)))
    }

    @Test
    fun `new ghostwrite job freezes the confirmed plan and rejects a changed tree`() {
        val dir = installProject()
        val coordinator = ghostwriteCoordinator(NovelWorkspaceRuntime(LoopingFakeKernel(emptyList(), "")))
        val store = NovelWorkspaceStore(dir)
        val job = coordinator.newJob(dir, "主线", targetChapterCount = 1)

        assertTrue(job.isVersionBound)
        assertEquals("B-1", job.branchId)
        assertEquals("第二章推进陈桥兵变，结尾留下入汴钩子。", job.confirmedPlan)
        assertEquals(job.baseHeadId, job.expectedHeadId)
        assertEquals(job.baseTreeDigest, job.expectedTreeDigest)

        store.write("branches/主线/plan/this-chapter.md", "计划已被外部修改。")
        val entered = NovelWorkspaceGhostwriteJobs.withRunningOwner(
            dir,
            job.id,
            job.executionKey,
        ) { true }
        assertNull(entered)
        assertEquals("第二章推进陈桥兵变，结尾留下入汴钩子。", job.confirmedPlan)
    }

    @Test
    fun `undo last collect restores files, moves head back, and is single-level`() {
        val dir = installProject()
        val runtime = NovelWorkspaceRuntime(LoopingFakeKernel(emptyList(), ""))
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
        val finalText = "# 入汴\n\n" + "赵大踏上官道，天边刚露出一点白。".repeat(60)
        val generator = ReviewedBatchFakeKernel(
            chapters = listOf(finalText),
            decisions = listOf(ReviewDecision.Pass),
            failFirstNWritingCalls = 1,
        )
        val coordinator = ghostwriteCoordinator(NovelWorkspaceRuntime(generator), testScheduler)
        val job = coordinator.newJob(dir, "主线", targetChapterCount = 1)

        val result = coordinator.runBatch(
            job = job,
            projectDirectory = dir,
            branchId = "B-1",
            settings = Settings(),
            model = Model(),
            isPaused = { false },
        ) { }

        assertEquals(3, generator.calls) // failed write + one retry + one joint review
        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Completed)
        assertEquals(1, NovelWorkspaceGhostwriteJobs.load(dir, job.id)?.receipts?.size)
    }

    @Test
    fun `ghostwrite batch fails with the surfaced error when the retry also fails`() = runTest {
        val dir = installProject()
        val generator = LoopingFakeKernel(
            toolCalls = emptyList(),
            finalText = "",
            failFirstNCalls = 5, // more failures than attempts: both must fail
        )
        val coordinator = ghostwriteCoordinator(NovelWorkspaceRuntime(generator), testScheduler)
        val job = coordinator.newJob(dir, "主线", targetChapterCount = 1)

        val result = coordinator.runBatch(
            job = job,
            projectDirectory = dir,
            branchId = "B-1",
            settings = Settings(),
            model = Model(),
            isPaused = { false },
        ) { }

        assertEquals(2, generator.calls) // exactly one retry, then the batch stops
        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Failed)
        assertTrue((result as NovelWorkspaceGhostwriteCoordinator.BatchResult.Failed).error.contains("provider 429"))
    }

    @Test
    fun `ghostwrite chapter writes its domain events into the run event stream`() = runTest {
        val dir = installProject()
        val finalText = "# 入汴\n\n" + "赵大踏上官道，天边刚露出一点白。".repeat(60)
        val generator = LoopingFakeKernel(
            toolCalls = listOf(
                "novel_workspace_write" to buildJsonObject {
                    put("path", "branches/主线/chapters/002-入汴.md")
                    put("content", "第二章正文。")
                },
            ),
            finalText = finalText,
        )
        val eventStore = app.amber.core.agent.runtime.InMemoryAgentEventStore()
        val coordinator = ghostwriteCoordinator(
            NovelWorkspaceRuntime(generator),
            testScheduler,
            eventStore = eventStore,
        )
        val job = coordinator.newJob(dir, "主线", targetChapterCount = 1)

        val result = coordinator.ghostwriteOneChapter(
            dir, "B-1", "主线", Settings(), Model(), 2,
            ownerJobId = job.id, ownerExecutionId = job.executionKey,
        )
        assertNotNull(result.candidate)

        // Step 3: one runner run, carrying the turn's domain trail —
        // tool activity first, then the terminal completion.
        val runIds = eventStore.events.map { it.runId }.distinct()
        assertEquals(1, runIds.size)
        val types = eventStore.events.map { it.type }
        assertEquals(
            listOf(NovelTurnEventPayload.TYPE_TOOL_ACTIVITY, NovelTurnEventPayload.TYPE_TURN_COMPLETED),
            types,
        )
        assertTrue(eventStore.events.all { it.agentDescriptorId == NovelTurnDescriptor.ID.value })
        assertTrue(eventStore.events.all { it.isFinal })
        val completed = eventStore.events.last()
        assertTrue(completed.payload.contains("\"finalTextLength\":${finalText.length}"))
    }

    @Test
    fun `ghostwrite canon writes are locked to one target chapter`() = runTest {
        val dir = installProject()
        val runtime = NovelWorkspaceRuntime(LoopingFakeKernel(emptyList(), ""))
        val store = NovelWorkspaceStore(dir)

        // Give the book a second chapter so chapter 1 is a middle chapter (newest = 2).
        store.write("drafts/d1.md", "第二章。")
        runtime.collectDraft(dir, "B-1", "主线", "drafts/d1.md", NovelWorkspaceCollectTarget.NewChapter, chapterTitle = "入汴")

        val gw = NovelWorkspaceRuntime(
            LoopingFakeKernel(
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
                    "novel_workspace_write" to buildJsonObject {
                        put("path", "branches/别的/plot/current.md")
                        put("content", "越界剧情。")
                    },
                    "novel_workspace_write" to buildJsonObject {
                        put("path", "branches/别的/plan/this-chapter.md")
                        put("content", "越界计划。")
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
        assertFalse(store.exists("branches/别的/plot/current.md"))
        assertFalse(store.exists("branches/别的/plan/this-chapter.md"))
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
            LoopingFakeKernel(
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
    fun `narrated bound chapter is stored as a candidate before canon`() = runTest {
        val dir = installProject()
        val chapter = "赵大摸黑进了柴房。\n\n" + ("外头风声更紧了，他数着自己的心跳等天亮。" ).repeat(60)
        val generator = LoopingFakeKernel(
            toolCalls = emptyList(),
            finalText = "# 火起\n\n$chapter",
        )
        val coordinator = ghostwriteCoordinator(NovelWorkspaceRuntime(generator), testScheduler)
        val job = coordinator.newJob(dir, "主线", targetChapterCount = 1)

        val result = coordinator.ghostwriteOneChapter(
            dir, "B-1", "主线", Settings(), Model(), 2,
            ownerJobId = job.id, ownerExecutionId = job.executionKey,
        )

        assertNotNull(result.candidate)
        val store = NovelWorkspaceStore(dir)
        val paths = store.list("branches/主线/chapters")
        assertTrue(paths.none { it.contains("002-") })
        val candidate = NovelWorkspaceGhostwriteJobs.load(dir, job.id)?.pendingCandidate!!
        assertEquals("火起", candidate.title)
        assertEquals(2, candidate.chapterOrdinal)
        assertTrue(candidate.body.startsWith("赵大摸黑进了柴房"))
        assertEquals(1, NovelWorkspaceLedger.load(dir).commits.size)
    }

    @Test
    fun `bound candidate turn refuses plan writes and does not create a chapter`() = runTest {
        val dir = installProject()
        val chapter = "赵大摸黑进了柴房。\n\n" + "外头风声更紧了，他数着自己的心跳等天亮。".repeat(60)
        val generator = LoopingFakeKernel(
            toolCalls = listOf(
                "novel_workspace_write" to buildJsonObject {
                    put("path", "branches/主线/plan/this-chapter.md")
                    put("content", "赵大潜入柴房，等候天亮。")
                },
            ),
            finalText = "# 火起\n\n$chapter",
        )
        val coordinator = ghostwriteCoordinator(NovelWorkspaceRuntime(generator), testScheduler)
        val job = coordinator.newJob(dir, "主线", targetChapterCount = 1)

        val result = coordinator.ghostwriteOneChapter(
            dir, "B-1", "主线", Settings(), Model(), 2,
            ownerJobId = job.id, ownerExecutionId = job.executionKey,
        )

        assertNotNull(result.candidate)
        assertEquals(
            "第二章推进陈桥兵变，结尾留下入汴钩子。",
            NovelWorkspaceStore(dir).read("branches/主线/plan/this-chapter.md"),
        )
        assertTrue(NovelWorkspaceStore(dir).list("branches/主线/chapters").none { it.contains("002-") })
    }

    @Test
    fun `short filler answer is not filed as a chapter`() = runTest {
        val dir = installProject()
        val generator = LoopingFakeKernel(
            toolCalls = emptyList(),
            finalText = "我先看一下现状。", // 72 chars of filler → below threshold
        )
        val coordinator = ghostwriteCoordinator(NovelWorkspaceRuntime(generator), testScheduler)
        val job = coordinator.newJob(dir, "主线", targetChapterCount = 1)

        val result = coordinator.runBatch(
            job = job,
            projectDirectory = dir,
            branchId = "B-1",
            settings = Settings(),
            model = Model(),
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

    @Test
    fun `job owner claim and conditional transitions preserve the latest user state`() {
        val dir = installProject()
        val coordinator = ghostwriteCoordinator(
            NovelWorkspaceRuntime(LoopingFakeKernel(emptyList(), "")),
        )
        val first = coordinator.newJob(dir, "主线", targetChapterCount = 1)
        val duplicate = runCatching { coordinator.newJob(dir, "主线", targetChapterCount = 1) }
        assertTrue(duplicate.isFailure)

        NovelWorkspaceGhostwriteJobs.transition(
            projectDirectory = dir,
            jobId = first.id,
            expectedStatuses = setOf(NovelWorkspaceGhostwriteJob.STATUS_RUNNING),
            newStatus = NovelWorkspaceGhostwriteJob.STATUS_PAUSED,
        )
        NovelWorkspaceGhostwriteJobs.transition(
            projectDirectory = dir,
            jobId = first.id,
            expectedStatuses = setOf(NovelWorkspaceGhostwriteJob.STATUS_RUNNING),
            newStatus = NovelWorkspaceGhostwriteJob.STATUS_COMPLETED,
        )
        assertEquals(
            NovelWorkspaceGhostwriteJob.STATUS_PAUSED,
            NovelWorkspaceGhostwriteJobs.load(dir, first.id)?.status,
        )
        val resumed = NovelWorkspaceGhostwriteJobs.restartPaused(dir, first.id)
        assertNotNull(resumed)
        val restarted = checkNotNull(resumed)
        assertFalse(first.executionKey == restarted.executionKey)
        assertNull(NovelWorkspaceGhostwriteJobs.restartPaused(dir, first.id))
        assertNull(
            NovelWorkspaceGhostwriteJobs.withRunningOwner(dir, first.id, first.executionKey) { "old" },
        )
        assertNull(
            NovelWorkspaceGhostwriteJobs.transition(
                projectDirectory = dir,
                jobId = first.id,
                expectedStatuses = setOf(NovelWorkspaceGhostwriteJob.STATUS_RUNNING),
                newStatus = NovelWorkspaceGhostwriteJob.STATUS_PAUSED,
                expectedExecutionId = first.executionKey,
            ),
        )
        assertEquals(
            "current",
            NovelWorkspaceGhostwriteJobs.withRunningOwner(dir, first.id, restarted.executionKey) { "current" },
        )
    }

    @Test
    fun `pause during provider turn prevents the chapter commit`() = runTest {
        val dir = installProject()
        lateinit var job: NovelWorkspaceGhostwriteJob
        val generator = LoopingFakeKernel(
            toolCalls = listOf(
                "novel_workspace_write" to buildJsonObject {
                    put("path", "branches/主线/plan/this-chapter.md")
                    put("content", "旧执行不得留下的计划。")
                },
                "novel_workspace_write" to buildJsonObject {
                    put("path", "branches/主线/chapters/002-暂停.md")
                    put("content", "这段正文不得由旧执行写入。")
                },
            ),
            finalText = ("这是一段本应被暂停丢弃的章节正文。".repeat(80)),
            beforeFinal = {
                NovelWorkspaceGhostwriteJobs.transition(
                    projectDirectory = dir,
                    jobId = job.id,
                    expectedStatuses = setOf(NovelWorkspaceGhostwriteJob.STATUS_RUNNING),
                    newStatus = NovelWorkspaceGhostwriteJob.STATUS_PAUSED,
                )
            },
        )
        val coordinator = ghostwriteCoordinator(NovelWorkspaceRuntime(generator), testScheduler)
        job = coordinator.newJob(dir, "主线", targetChapterCount = 1)

        val result = coordinator.runBatch(
            job = job,
            projectDirectory = dir,
            branchId = "B-1",
            settings = Settings(),
            model = Model(),
            isPaused = {
                NovelWorkspaceGhostwriteJobs.load(dir, job.id)?.status !=
                    NovelWorkspaceGhostwriteJob.STATUS_RUNNING
            },
        ) { }

        assertTrue(result is NovelWorkspaceGhostwriteCoordinator.BatchResult.Stopped)
        assertEquals(1, NovelWorkspaceLedger.load(dir).commits.size)
        assertEquals(
            "第二章推进陈桥兵变，结尾留下入汴钩子。",
            NovelWorkspaceStore(dir).read("branches/主线/plan/this-chapter.md"),
        )
        assertFalse(NovelWorkspaceStore(dir).list("branches/主线/chapters").any { it.contains("002-") })
        assertEquals(
            NovelWorkspaceGhostwriteJob.STATUS_PAUSED,
            NovelWorkspaceGhostwriteJobs.load(dir, job.id)?.status,
        )
    }
}
