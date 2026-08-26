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
import app.amber.core.settings.Settings
import app.amber.feature.novelworkspace.NovelWorkspaceFile
import app.amber.feature.novelworkspace.NovelWorkspaceInstaller
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Temporary scratch E2E verification — do not commit. */
class ScratchVerifyE2ETest {

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
                NovelWorkspaceMarkdown.render(fields = listOf("id" to "B-1", "kind" to "branch", "title" to "主线"), body = ""),
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

    private class LoopingFakeGenerator(
        private val toolCalls: List<Pair<String, JsonElement>>,
        private val finalText: String,
    ) : Generator {
        override fun generateText(
            settings: Settings,
            model: Model,
            messages: List<UIMessage>,
            inputTransformers: List<InputMessageTransformer>,
            outputTransformers: List<OutputMessageTransformer>,
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
            val assistant = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = executed + UIMessagePart.Text(finalText),
            )
            emit(GenerationChunk.Messages(messages + assistant))
        }
    }

    @Test
    fun `rewrite approve preserves the original unresolved boundary until author confirms`() = runTest {
        val dir = installProject()
        val runtime = NovelWorkspaceRuntime(LoopingFakeGenerator(emptyList(), ""))
        val store = NovelWorkspaceStore(dir)

        // Add chapters 2 and 3 so chapter 1 is a middle chapter.
        store.write("drafts/d2.md", "第二章正文。")
        runtime.collectDraft(dir, "B-1", "主线", "drafts/d2.md", NovelWorkspaceCollectTarget.NewChapter, chapterTitle = "入汴")
        store.write("drafts/d3.md", "第三章正文。")
        runtime.collectDraft(dir, "B-1", "主线", "drafts/d3.md", NovelWorkspaceCollectTarget.NewChapter, chapterTitle = "陈桥")

        // Middle edit on chapter 1 -> author-edit commit arms the gate at ordinal 2.
        runtime.saveChapterEdit(dir, "B-1", "主线", "branches/主线/chapters/001-山呼.md", "山呼", "改写后的第一章。")
        assertEquals(2, NovelWorkspaceUnresolvedStore.entryFor(dir, "主线")?.fromOrdinal)

        // The 重写后章 turn: agent rewrites chapters 2 and 3 -> a canon proposal.
        // We drive the proposal through a tool-driven turn whose fake generator writes
        // the two rewritten chapters; the gate arithmetic is what we assert below.
        val runtime2 = NovelWorkspaceRuntime(
            LoopingFakeGenerator(
                toolCalls = listOf(
                    "novel_workspace_write" to buildJsonObject {
                        put("path", "branches/主线/chapters/002-入汴.md")
                        put("content", "重写后的第二章正文。")
                    },
                    "novel_workspace_write" to buildJsonObject {
                        put("path", "branches/主线/chapters/003-陈桥.md")
                        put("content", "重写后的第三章正文。")
                    },
                ),
                finalText = "重写完成。",
            ),
        )
        val completed = runtime2.runTurn(
            NovelWorkspaceRuntime.TurnRequest(
                projectDirectory = dir,
                branchId = "B-1",
                branchSlug = "主线",
                userText = "请重写第 2 章起的受影响章节",
                systemPrompt = "",
                settings = Settings(),
                model = Model(),
            ),
        ).toList().filterIsInstance<NovelWorkspaceRuntime.TurnEvent.Completed>().single()
        val proposal = requireNotNull(completed.proposal)
        assertEquals(2, proposal.entries.size)

        // Approve the rewrite: one commit rewriting ch2 and ch3.
        runtime2.approve(proposal.id)

        // Approval and author confirmation are separate: keep the original earliest
        // boundary instead of shrinking the affected range to chapter 3.
        val gate = NovelWorkspaceUnresolvedStore.entryFor(dir, "主线")
        assertNotNull(gate)
        if (gate != null) {
            assertEquals(2, gate.fromOrdinal)
        }
    }

    @Test
    fun `a non-undoable commit hides a stale undo record`() = runTest {
        val dir = installProject()
        val runtime = NovelWorkspaceRuntime(LoopingFakeGenerator(emptyList(), ""))
        val store = NovelWorkspaceStore(dir)

        // Collect a draft -> canon commit with an undo record.
        store.write("drafts/d2.md", "第二章正文。")
        runtime.collectDraft(dir, "B-1", "主线", "drafts/d2.md", NovelWorkspaceCollectTarget.NewChapter, chapterTitle = "入汴")
        assertTrue(runtime.canUndo(dir))

        // A subsequent discussion turn that only does a free write (setting/) commits a
        // GENERIC commit WITHOUT an undo record, advancing the head past the undo record.
        val turnRuntime = NovelWorkspaceRuntime(
            LoopingFakeGenerator(
                toolCalls = listOf(
                    "novel_workspace_write" to buildJsonObject {
                        put("path", "setting/characters/赵大.md")
                        put("content", "—\nkind: material\nmaterialKind: character\ntitle: 赵大\n—\n游历归来。")
                    },
                ),
                finalText = "已更新设定。",
            ),
        )
        turnRuntime.runTurn(
            NovelWorkspaceRuntime.TurnRequest(
                projectDirectory = dir,
                branchId = "B-1",
                branchSlug = "主线",
                userText = "更新一下人物设定",
                systemPrompt = "",
                settings = Settings(),
                model = Model(),
            ),
        ).toList()

        // A stale record must not keep an enabled button that can only fail.
        assertFalse(runtime.canUndo(dir))
        assertFalse("undo should not be possible when head moved past the undo record", runtime.undoLast(dir))
    }
}
