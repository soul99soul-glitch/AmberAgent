package app.amber.feature.novel.workspace

import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.RunKernel
import app.amber.core.settings.Settings
import app.amber.core.settings.ModelGroupSessionDefault
import app.amber.core.settings.resolveSessionDefaults
import app.amber.feature.novelworkspace.NovelWorkspaceFile
import app.amber.feature.novelworkspace.NovelWorkspaceInstaller
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceManifestRenderer
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NovelWorkspaceContinuityAuditTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val firstPath = "branches/主线/chapters/001-出发.md"
    private val secondPath = "branches/主线/chapters/009-抵达.md"
    private val firstBody = "背景景物。".repeat(1_600) + "第一章结尾：沈砚从未到过南城。"
    private val secondBody = "第九章：沈砚自称去年到过南城。"

    private fun project(firstOrdinal: Int = 1, secondOrdinal: Int = 9): File {
        val directory = temporaryFolder.newFolder("book")
        NovelWorkspaceInstaller.install(listOf(
            NovelWorkspaceFile("manifest.yaml", NovelWorkspaceManifestRenderer.render(
                exportedAt = Instant.parse("2026-09-30T00:00:00Z"), sourceProjectID = "book",
                sourceProjectRevision = 1, sourceSchemaVersion = 1, mainBranch = "主线",
            )),
            NovelWorkspaceFile("project.md", NovelWorkspaceMarkdown.render(listOf("id" to "book", "kind" to "project", "title" to "全书审稿"), body = "")),
            NovelWorkspaceFile("branches/主线/branch.md", NovelWorkspaceMarkdown.render(listOf("id" to "B-1", "kind" to "branch", "title" to "主线"), body = "")),
            NovelWorkspaceFile(firstPath, NovelWorkspaceMarkdown.render(listOf("id" to "C-1", "kind" to "chapter", "title" to "出发", "ordinal" to firstOrdinal.toString()), body = firstBody)),
            NovelWorkspaceFile(secondPath, NovelWorkspaceMarkdown.render(listOf("id" to "C-9", "kind" to "chapter", "title" to "抵达", "ordinal" to secondOrdinal.toString()), body = secondBody)),
            NovelWorkspaceFile("branches/番外/chapters/001-番外.md", "不属于当前分支的正文。"),
        ), directory)
        return directory
    }

    private fun request(directory: File) = NovelWorkspaceContinuityAudit.Request(
        directory, "B-1", "主线", Settings(), Model(), locale = Locale.ENGLISH,
    )

    private fun audit(kernel: RunKernel, scope: TestScope): NovelWorkspaceContinuityAudit {
        val payloads = NovelTurnPayloads()
        val registry = app.amber.core.agent.runtime.impl.InMemoryAgentRegistry().apply {
            register(NovelTurnDescriptor.value, NovelTurnInput::class, NovelTurnInput.serializer(), NovelTurnArtifact.serializer()) {
                NovelTurnAgent(payloads)
            }
        }
        val runner = app.amber.core.agent.runtime.impl.InProcessAgentRunner(
            registry, app.amber.core.agent.runtime.InMemoryAgentEventStore(),
            runScopeFactory = { id, _ -> app.amber.core.agent.runtime.adapter.LegacyRunScope(runId = id) },
            scope = scope.backgroundScope,
        )
        return NovelWorkspaceContinuityAudit(NovelWorkspaceRuntime(kernel), NovelTurnLauncher(runner, payloads, scope.backgroundScope))
    }

    private class ScriptKernel(val answer: suspend (GenerationRunSession, Int) -> String) : RunKernel {
        var calls = 0
        override fun run(session: GenerationRunSession): Flow<GenerationChunk> = flow {
            calls += 1
            emit(GenerationChunk.Messages(session.messages + UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text(answer(session, calls))),
            )))
        }
    }

    private fun systemPrompt(session: GenerationRunSession): String = session.messages
        .filter { it.role == MessageRole.SYSTEM }.flatMap { it.parts.filterIsInstance<UIMessagePart.Text>() }
        .joinToString("\n") { it.text }

    private fun reply(path: String, facts: JsonArray = JsonArray(emptyList()), issues: JsonArray = JsonArray(emptyList())): String = buildJsonObject {
        put("chapterPath", path); put("facts", facts); put("issues", issues)
    }.toString()

    private fun firstFacts() = JsonArray(listOf(buildJsonObject {
        put("text", "沈砚此前从未到过南城，需与后文核对")
        put("evidence", "第一章结尾：沈砚从未到过南城。")
    }))

    private fun secondIssues(relatedQuote: String = "第一章结尾：沈砚从未到过南城。") = JsonArray(listOf(buildJsonObject {
        put("summary", "人物对过往经历的陈述可能矛盾")
        put("evidence", secondBody)
        put("relatedPath", firstPath)
        put("relatedEvidence", relatedQuote)
        put("suggestion", "核实是否为人物撒谎或回忆时间错误，再决定是否修改。")
    }))

    @Test
    fun `every actual chapter body and earlier verified facts reach readonly review`() = runTest {
        val directory = project()
        val store = NovelWorkspaceStore(directory)
        val beforeTree = store.fileTree()
        val beforeLedger = NovelWorkspaceLedger.load(directory)
        val prompts = mutableListOf<String>()
        val kernel = ScriptKernel { session, call ->
            prompts += systemPrompt(session)
            for (path in listOf(firstPath, "setting/characters/越界.md", "drafts/越界.md")) {
                val output = session.tools.first { it.name == "novel_workspace_write" }.execute(buildJsonObject {
                    put("path", path); put("content", "不能写入的审稿修改")
                })
                assertTrue(output.filterIsInstance<UIMessagePart.Text>().joinToString { it.text }.contains("只读"))
            }
            if (call == 1) reply(firstPath, firstFacts()) else reply(secondPath, issues = secondIssues())
        }
        val progress = mutableListOf<NovelWorkspaceContinuityAudit.Result>()

        val result = audit(kernel, this).run(request(directory)) { progress += it }

        assertTrue(result.complete)
        assertEquals(2, result.checked)
        assertEquals(2, result.total)
        assertEquals(2, kernel.calls)
        assertTrue(prompts[0].contains(firstBody))
        assertTrue(prompts[1].contains(secondBody))
        assertTrue(prompts[1].contains("沈砚此前从未到过南城，需与后文核对"))
        assertTrue(prompts[1].contains(firstPath))
        assertTrue(result.report.contains("Related chapter 1"))
        assertTrue(result.report.contains("Chapter 9"))
        assertTrue(result.report.contains(secondBody))
        assertTrue(result.report.contains("核实是否为人物撒谎"))
        assertEquals(listOf(0, 1, 2), progress.map { it.checked })
        assertTrue(progress.all { !it.complete })
        assertEquals(beforeTree, store.fileTree())
        assertEquals(beforeLedger, NovelWorkspaceLedger.load(directory))
    }

    @Test
    fun `provider failure retains completed chapter reports without retry`() = runTest {
        val directory = project()
        val kernel = ScriptKernel { _, call ->
            if (call == 2) throw IllegalStateException("审稿连接断开")
            reply(firstPath, firstFacts())
        }

        val result = audit(kernel, this).run(request(directory))

        assertFalse(result.complete)
        assertEquals(1, result.checked)
        assertEquals(2, result.total)
        assertEquals(2, kernel.calls)
        assertTrue(result.report.contains(firstPath))
        assertTrue(result.report.contains("Review incomplete"))
        assertTrue(result.error.orEmpty().contains("审稿连接断开"))
    }

    @Test
    fun `chapter metadata ordinals determine review order and report numbers`() = runTest {
        val directory = project(firstOrdinal = 8, secondOrdinal = 2)
        val reviewedPaths = mutableListOf<String>()
        val kernel = ScriptKernel { session, _ ->
            val path = checkNotNull(Regex("(?m)^CHAPTER_PATH: (.+)$").find(systemPrompt(session))).groupValues[1]
            reviewedPaths += path
            reply(path)
        }

        val result = audit(kernel, this).run(request(directory))

        assertTrue(result.complete)
        assertEquals(listOf(secondPath, firstPath), reviewedPaths)
        assertTrue(result.report.contains("Chapter 2"))
        assertTrue(result.report.contains("Chapter 8"))
    }

    @Test
    fun `unverified evidence is rejected and not counted as a reviewed chapter`() = runTest {
        val directory = project()
        val kernel = ScriptKernel { _, call ->
            if (call == 1) reply(firstPath, firstFacts()) else reply(secondPath, issues = secondIssues("编造的原句"))
        }

        val result = audit(kernel, this).run(request(directory))

        assertFalse(result.complete)
        assertEquals(1, result.checked)
        assertTrue(result.error.orEmpty().contains("evidence"))
        assertFalse(result.report.contains("编造的原句"))
    }

    @Test
    fun `unparseable response returns an incomplete report with zero coverage`() = runTest {
        val directory = project()
        val kernel = ScriptKernel { _, _ -> "先给你一个审稿概述。" }

        val result = audit(kernel, this).run(request(directory))

        assertFalse(result.complete)
        assertEquals(0, result.checked)
        assertEquals(2, result.total)
        assertTrue(result.report.contains("0 / 2 chapters checked"))
        assertFalse(result.report.contains("先给你一个"))
        assertEquals(1, kernel.calls)
    }

    @Test
    fun `changed tree at the end invalidates completion even when every chapter was checked`() = runTest {
        val directory = project()
        val kernel = ScriptKernel { _, call ->
            if (call == 1) reply(firstPath) else {
                NovelWorkspaceStore(directory).write("setting/world/变化.md", "检查期间新增加的资料。")
                reply(secondPath)
            }
        }

        val result = audit(kernel, this).run(request(directory))

        assertFalse(result.complete)
        assertEquals(2, result.checked)
        assertEquals(2, result.total)
        assertTrue(result.error.orEmpty().contains("starting version"))
        assertTrue(result.report.contains("Review incomplete"))
    }

    @Test
    fun `normal cancellation publishes stopped partial report before propagating cancellation`() = runTest {
        val directory = project()
        val kernel = ScriptKernel { _, call -> if (call == 1) reply(firstPath) else awaitCancellation() }
        val progress = mutableListOf<NovelWorkspaceContinuityAudit.Result>()
        val firstChecked = CompletableDeferred<Unit>()
        val task = launch { audit(kernel, this@runTest).run(request(directory)) {
            progress += it
            if (it.checked == 1) firstChecked.complete(Unit)
        } }
        firstChecked.await()
        assertEquals(1, progress.last().checked)

        task.cancel()
        task.join()

        assertEquals(1, progress.last().checked)
        assertFalse(progress.last().complete)
        assertTrue(progress.last().report.contains("Stopped; review incomplete"))
        assertTrue(progress.last().report.contains(firstPath))
    }

    @Test
    fun `restore invalidation cancels without publishing stale completion or stopped callbacks`() = runTest {
        val directory = project()
        val kernel = ScriptKernel { _, call -> if (call == 1) reply(firstPath) else reply(secondPath) }
        val progress = mutableListOf<NovelWorkspaceContinuityAudit.Result>()
        val task = launch {
            audit(kernel, this@runTest).run(request(directory)) { value ->
                progress += value
                if (value.checked == 1) {
                    NovelWorkspaceRestoreBoundary.beginRestore()
                    NovelWorkspaceRestoreBoundary.finishRestore()
                }
            }
        }
        task.join()

        assertTrue(task.isCancelled)
        assertEquals(1, kernel.calls)
        assertEquals(listOf(0, 1), progress.map { it.checked })
        assertTrue(progress.all { !it.complete })
        assertFalse(progress.last().report.contains("Stopped"))
    }

    @Test
    fun `workspace review retains full context while global and group chat limits remain unchanged`() = runTest {
        val directory = project()
        val model = Model(modelId = "gpt-5.4")
        val settings = Settings(
            contextMessageSize = 1,
            modelGroupSessionDefaults = listOf(ModelGroupSessionDefault("openai_reasoning", contextMessageSize = 1)),
        )
        val kernel = ScriptKernel { session, _ ->
            assertEquals(Int.MAX_VALUE, session.settings.contextMessageSize)
            assertEquals(Int.MAX_VALUE, session.settings.resolveSessionDefaults(model).contextMessageSize)
            val path = checkNotNull(Regex("(?m)^CHAPTER_PATH: (.+)$").find(systemPrompt(session))).groupValues[1]
            reply(path)
        }

        val result = audit(kernel, this).run(request(directory).copy(settings = settings, model = model))

        assertTrue(result.complete)
        assertEquals(1, settings.contextMessageSize)
        assertEquals(1, settings.modelGroupSessionDefaults.single().contextMessageSize)
        assertEquals(1, settings.resolveSessionDefaults(model).contextMessageSize)
        assertEquals(1, settings.copy(contextMessageSize = 0).resolveSessionDefaults(model).contextMessageSize)
    }
}
