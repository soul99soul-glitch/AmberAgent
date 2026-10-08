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
import app.amber.feature.novelworkspace.NovelWorkspaceInstaller
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceManifestRenderer
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreCancelled
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceRuntimeRestoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val chapterPath = "branches/主线/chapters/001-第一章.md"
    private val settingPath = "setting/custom/source.md"
    private val noKernel = object : RunKernel {
        override fun run(session: GenerationRunSession): Flow<GenerationChunk> = emptyFlow()
    }

    private fun project(): File {
        val directory = temporaryFolder.root.resolve("project")
        val now = Instant.parse("2026-09-30T00:00:00Z")
        NovelWorkspaceInstaller.install(listOf(
            NovelWorkspaceFile("manifest.yaml", NovelWorkspaceManifestRenderer.render(now, "P-1", 1, 1, "主线")),
            NovelWorkspaceFile("project.md", NovelWorkspaceMarkdown.render(listOf("id" to "P-1", "kind" to "project", "title" to "测试"), body = "")),
            NovelWorkspaceFile("branches/主线/branch.md", NovelWorkspaceMarkdown.render(listOf("id" to "B-1", "kind" to "branch", "title" to "主线"), body = "")),
            NovelWorkspaceFile(chapterPath, NovelWorkspaceMarkdown.render(listOf("id" to "C-1", "kind" to "chapter", "title" to "第一章", "ordinal" to "1"), body = "原始正文。")),
            NovelWorkspaceFile(settingPath, "原始资料。"),
        ), directory, now = now)
        return directory
    }

    private fun request(directory: File) = NovelWorkspaceRuntime.TurnRequest(directory, "B-1", "主线", "请修改。", "", Settings(), Model())

    private fun restoreFile(directory: File, path: String, content: String) {
        NovelWorkspaceRestoreBoundary.beginRestore()
        try {
            // Native restore copies bytes directly while every novel writer is excluded.
            File(directory, path).writeText(content)
        } finally {
            NovelWorkspaceRestoreBoundary.finishRestore()
        }
    }

    @Test
    fun `old free write rollback cannot overwrite a file recovered by restore`() = runTest {
        val directory = project()
        val head = NovelWorkspaceLedger.load(directory).head
        val runtime = NovelWorkspaceRuntime(object : RunKernel {
            override fun run(session: GenerationRunSession): Flow<GenerationChunk> = flow {
                session.tools.first { it.name == "novel_workspace_write" }.execute(buildJsonObject {
                    put("path", settingPath)
                    put("content", "旧回合已经写入的资料。")
                })
                restoreFile(directory, settingPath, "完整备份恢复的资料。")
                emit(GenerationChunk.Messages(session.messages + UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("旧回合完成。")))))
            }
        })
        val error = runCatching { runtime.runTurn(request(directory)).toList() }.exceptionOrNull()

        assertTrue(error is NovelWorkspaceRestoreCancelled)
        assertEquals("完整备份恢复的资料。", NovelWorkspaceStore(directory).read(settingPath))
        assertEquals(head, NovelWorkspaceLedger.load(directory).head)
        assertTrue(runtime.pendingProposals.value.isEmpty())
    }

    @Test
    fun `a restore after the last streamed chunk prevents old proposal registration`() = runTest {
        val directory = project()
        val runtime = NovelWorkspaceRuntime(object : RunKernel {
            override fun run(session: GenerationRunSession): Flow<GenerationChunk> = flow {
                session.tools.first { it.name == "novel_workspace_write" }.execute(buildJsonObject {
                    put("path", chapterPath)
                    put("content", "恢复前产生的旧提案。")
                })
                emit(GenerationChunk.Messages(session.messages + UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("旧提案已准备。")))))
                restoreFile(directory, chapterPath, "恢复后的正文。")
            }
        })
        val error = runCatching { runtime.runTurn(request(directory)).toList() }.exceptionOrNull()

        assertTrue(error is NovelWorkspaceRestoreCancelled)
        assertEquals("恢复后的正文。", NovelWorkspaceStore(directory).read(chapterPath))
        assertTrue(NovelWorkspaceProposalStore.load(directory).isEmpty())
        assertTrue(runtime.pendingProposals.value.isEmpty())
    }

    @Test
    fun `tool instances and direct request created before restore cannot mutate the recovered workspace`() = runTest {
        val directory = project()
        val store = NovelWorkspaceStore(directory)
        val oldRequest = request(directory)
        val tool = NovelWorkspaceToolSession(store, "主线", "测试", NovelWorkspaceWriteBatch()).tools()
            .first { it.name == "novel_workspace_write" }
        restoreFile(directory, settingPath, "恢复后的资料。")
        val toolError = runCatching { tool.execute(buildJsonObject {
            put("path", "drafts/stale.md")
            put("content", "不允许落盘。")
        }) }.exceptionOrNull()
        assertTrue(toolError is NovelWorkspaceRestoreCancelled)
        assertFalse(store.exists("drafts/stale.md"))
        assertTrue(runCatching { NovelWorkspaceRuntime(noKernel).runTurn(oldRequest).toList() }.exceptionOrNull() is NovelWorkspaceRestoreCancelled)
    }

    @Test
    fun `direct manual writes are blocked for the whole active restore`() {
        val directory = project()
        val runtime = NovelWorkspaceRuntime(noKernel)
        val original = NovelWorkspaceStore(directory).read(chapterPath)
        NovelWorkspaceRestoreBoundary.beginRestore()
        try {
            assertTrue(runCatching { runtime.saveChapterEdit(directory, "B-1", "主线", chapterPath, "旧标题", "不允许保存。") }.exceptionOrNull() is NovelWorkspaceRestoreCancelled)
            assertTrue(runCatching { runtime.saveFileEdit(directory, "B-1", "主线", settingPath, "不允许保存资料。") }.exceptionOrNull() is NovelWorkspaceRestoreCancelled)
            assertTrue(runCatching { runtime.undoLast(directory, "主线") }.exceptionOrNull() is NovelWorkspaceRestoreCancelled)
            assertEquals(original, NovelWorkspaceStore(directory).read(chapterPath))
        } finally {
            NovelWorkspaceRestoreBoundary.finishRestore()
        }
    }
}
