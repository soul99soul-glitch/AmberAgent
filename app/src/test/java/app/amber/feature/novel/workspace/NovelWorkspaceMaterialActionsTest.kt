package app.amber.feature.novel.workspace

import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.RunKernel
import app.amber.feature.novelworkspace.NovelWorkspaceBranches
import app.amber.feature.novelworkspace.NovelWorkspaceCatalog
import app.amber.feature.novelworkspace.NovelWorkspaceContextAssembler
import app.amber.feature.novelworkspace.NovelWorkspaceEffectiveMaterials
import app.amber.feature.novelworkspace.NovelWorkspaceFile
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceInstaller
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceManifestRenderer
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceMaterialActionsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val now = Instant.parse("2026-09-30T00:00:00Z")
    private val runtime = NovelWorkspaceRuntime(object : RunKernel {
        override fun run(session: GenerationRunSession): Flow<GenerationChunk> = emptyFlow()
    })

    private fun project(): File {
        val directory = temporaryFolder.root.resolve("project")
        NovelWorkspaceInstaller.install(listOf(
            NovelWorkspaceFile("manifest.yaml", NovelWorkspaceManifestRenderer.render(now, "P-1", 1, 1, "主线")),
            NovelWorkspaceFile("project.md", NovelWorkspaceMarkdown.render(listOf("id" to "P-1", "kind" to "project", "title" to "测试"), body = "")),
            NovelWorkspaceFile("branches/主线/branch.md", NovelWorkspaceMarkdown.render(listOf("id" to "B-1", "kind" to "branch", "title" to "主线"), body = "")),
            NovelWorkspaceFile("branches/主线/plan/this-chapter.md", "赵大抵达汴京。"),
        ), directory, now = now)
        return directory
    }

    @Test
    fun `created material appears in catalog and real context and delete remains undoable`() {
        val directory = project()
        NovelWorkspaceMaterialActions.create(runtime, directory, "B-1", "主线", "world", "汴京", "后周都城的详细设定。")
        val store = NovelWorkspaceStore(directory)
        val entry = NovelWorkspaceEffectiveMaterials.collect(store, "主线").single()
        assertEquals("world", entry.parsed.fields["materialKind"])
        assertTrue(NovelWorkspaceCatalog.load(store, NovelWorkspaceLedger.load(directory), "主线").settingGroups.single().entries.any { it.path == entry.path })
        assertTrue(NovelWorkspaceContextAssembler.assemble(store, "主线").contains("后周都城的详细设定。"))

        val original = store.read(entry.path)!!
        NovelWorkspaceMaterialActions.delete(runtime, directory, "B-1", "主线", entry.path, original)
        assertNull(store.read(entry.path))
        assertTrue(NovelWorkspaceEffectiveMaterials.collect(store, "主线").isEmpty())
        assertFalse(NovelWorkspaceContextAssembler.assemble(store, "主线").contains("后周都城的详细设定。"))
        assertTrue(runtime.undoLast(directory, "主线"))
        assertEquals(original, store.read(entry.path))
    }

    @Test
    fun `branch material edits retain identity aliases relations and injection and expose global after override deletion`() {
        val directory = project()
        val store = NovelWorkspaceStore(directory)
        val global = "setting/characters/赵大.md"
        val path = "branches/主线/setting/characters/赵大.md"
        val header = """
            ---
            id: M-1
            kind: material
            materialKind: character
            title: 赵大
            injection: always
            override: true
            aliases:
              - 老赵
            relations:
              - {with: 赵匡胤, type: 兄弟}
            imported:
              custom: 保留
            ---
        """.trimIndent()
        store.write(global, "$header\n\n全局人物设定。\n")
        store.write(path, "$header\n\n分支人物设定。\n")
        runtime.saveFileEdit(directory, "B-1", "主线", path, "先编辑分支正文。")
        assertEquals("$header\n\n先编辑分支正文。\n", store.read(path))
        val original = store.read(path)!!
        assertNotNull(runCatching {
            NovelWorkspaceMaterialActions.save(runtime, directory, "B-1", "主线", global, "全局新标题", "不应覆盖隐藏全局。", store.read(global)!!)
        }.exceptionOrNull())
        NovelWorkspaceMaterialActions.save(runtime, directory, "B-1", "主线", path, "赵大新称呼", "作者修订后的分支人物设定。", original)
        val edited = store.read(path)!!
        assertEquals("${header.replace("title: 赵大", "title: 赵大新称呼")}\n\n作者修订后的分支人物设定。\n", edited)
        assertTrue(NovelWorkspaceContextAssembler.assemble(store, "主线").contains("作者修订后的分支人物设定。"))
        assertFalse(NovelWorkspaceContextAssembler.assemble(store, "主线").contains("全局人物设定。"))

        NovelWorkspaceMaterialActions.delete(runtime, directory, "B-1", "主线", path, edited)
        assertEquals(global, NovelWorkspaceEffectiveMaterials.collect(store, "主线").single().path)
        assertTrue(NovelWorkspaceContextAssembler.assemble(store, "主线").contains("全局人物设定。"))
    }

    @Test
    fun `decision archive is stable per message scoped to branch and uses ordinary edit delete`() {
        val directory = project()
        val fork = NovelWorkspaceBranches.createBranch(directory, "主线", "番外")
        val messageId = "session/message-1"
        NovelWorkspaceMaterialActions.archiveDecision(runtime, directory, "B-1", "主线", messageId, "结尾决定", "赵大选择留下。")
        val store = NovelWorkspaceStore(directory)
        val first = NovelWorkspaceEffectiveMaterials.collect(store, "主线").single()
        assertEquals(messageId, first.parsed.fields["sourceMessageId"])
        assertTrue(first.path.startsWith("branches/主线/setting/decisions/"))
        assertTrue(NovelWorkspaceContextAssembler.assemble(store, "主线").contains("赵大选择留下。"))
        assertFalse(NovelWorkspaceContextAssembler.assemble(store, fork.slug).contains("赵大选择留下。"))
        NovelWorkspaceMaterialActions.archiveDecision(runtime, directory, "B-1", "主线", messageId, "更新决定", "赵大选择入城。")
        val updated = NovelWorkspaceEffectiveMaterials.collect(store, "主线").single()
        assertEquals(first.path, updated.path)
        assertEquals(first.parsed.fields["id"], updated.parsed.fields["id"])
        assertEquals(1, NovelWorkspaceCatalog.load(store, NovelWorkspaceLedger.load(directory), "主线").decisions.size)
        NovelWorkspaceMaterialActions.save(runtime, directory, "B-1", "主线", updated.path, "最终决定", "赵大与同伴入城。", store.read(updated.path)!!)
        assertEquals(messageId, NovelWorkspaceMarkdown.parseFile(store.read(updated.path)!!).fields["sourceMessageId"])
        NovelWorkspaceMaterialActions.delete(runtime, directory, "B-1", "主线", updated.path, store.read(updated.path)!!)
        assertTrue(NovelWorkspaceCatalog.load(store, NovelWorkspaceLedger.load(directory), "主线").decisions.isEmpty())
    }

    @Test
    fun `stale material source active job and author CAS reject without changing files`() {
        val directory = project()
        NovelWorkspaceMaterialActions.create(runtime, directory, "B-1", "主线", "custom", "测试资料", "原始资料。")
        val store = NovelWorkspaceStore(directory)
        val path = NovelWorkspaceEffectiveMaterials.collect(store, "主线").single().path
        val original = store.read(path)!!
        runtime.saveFileEdit(directory, "B-1", "主线", path, "后来的资料修改。")
        val current = store.read(path)
        assertNotNull(runCatching { NovelWorkspaceMaterialActions.save(runtime, directory, "B-1", "主线", path, "旧标题修改", "旧编辑器正文", original) }.exceptionOrNull())
        assertNotNull(runCatching { NovelWorkspaceMaterialActions.delete(runtime, directory, "B-1", "主线", path, original) }.exceptionOrNull())
        assertEquals(current, store.read(path))
        val ledger = NovelWorkspaceLedger.load(directory)
        val tree = NovelWorkspaceLedger.treeSHA256(store.fileTree())
        store.write("setting/custom/other.md", "其他未提交写入。")
        assertNotNull(runCatching { runtime.commitAuthorChanges(directory, "B-1", "主线", mapOf(path to null), ledger.headOf("B-1")?.id, tree) }.exceptionOrNull())
        assertEquals(current, store.read(path))
        val job = NovelWorkspaceGhostwriteJob(id = "job-1", branchSlug = "主线", targetChapterCount = 1, startOrdinal = 0, createdAt = now, updatedAt = now)
        NovelWorkspaceGhostwriteJobs.save(job, directory)
        assertNotNull(runCatching { NovelWorkspaceMaterialActions.create(runtime, directory, "B-1", "主线", "world", "新资料", "任务期间不得写入。") }.exceptionOrNull())
    }

    @Test
    fun `author transaction restores raw files if committing fails before ledger persistence`() {
        val directory = project()
        val store = NovelWorkspaceStore(directory)
        val path = "setting/custom/original.md"
        store.write(path, "原始资料。")
        runtime.saveFileEdit(directory, "B-1", "主线", path, "原始资料。")
        val ledger = NovelWorkspaceLedger.load(directory)
        val owner = NovelWorkspaceAuthorEdits { _, _, _, _, _ -> throw IllegalStateException("commit failed") }
        assertNotNull(runCatching {
            owner.apply(directory, "B-1", "主线", linkedMapOf(path to null, "setting/custom/new.md" to "新资料。"),
                ledger.headOf("B-1")?.id, NovelWorkspaceLedger.treeSHA256(store.fileTree()), NovelWorkspaceLedger.Message.MANUAL_EDIT)
        }.exceptionOrNull())
        assertEquals("原始资料。", store.read(path))
        assertNull(store.read("setting/custom/new.md"))
        assertEquals(ledger.heads, NovelWorkspaceLedger.load(directory).heads)
        assertNull(NovelWorkspaceUnresolvedStore.entryFor(directory, "主线"))
    }

    @Test
    fun `multiline author titles survive create rename and decision reload as one scalar`() {
        val directory = project()
        val store = NovelWorkspaceStore(directory)
        NovelWorkspaceMaterialActions.create(runtime, directory, "B-1", "主线", "custom", "资料\n第一行", "资料正文。")
        val material = NovelWorkspaceEffectiveMaterials.collect(store, "主线").single()
        assertEquals("资料 第一行", material.parsed.fields["title"])
        assertEquals("资料正文。", material.parsed.body)
        NovelWorkspaceMaterialActions.save(runtime, directory, "B-1", "主线", material.path, "改名\r\n第二行", "新版资料正文。", store.read(material.path)!!)
        val renamed = NovelWorkspaceMarkdown.parseFile(store.read(material.path)!!)
        assertEquals("改名 第二行", renamed.fields["title"])
        assertEquals("新版资料正文。", renamed.body)
        NovelWorkspaceMaterialActions.archiveDecision(runtime, directory, "B-1", "主线", "message-1", "决定\n后半", "决定正文。")
        val decision = NovelWorkspaceEffectiveMaterials.collect(store, "主线").single { it.parsed.fields["materialKind"] == "decisionLog" }
        assertEquals("决定 后半", decision.parsed.fields["title"])
        assertEquals("决定正文。", decision.parsed.body)
    }

    @Test
    fun `rollback reports a blocked restore and still restores the remaining preimages`() {
        val directory = project()
        val store = NovelWorkspaceStore(directory)
        val path = "setting/custom/original.md"
        val blockedPath = "setting/custom/blocked.md"
        store.write(path, "原始资料。")
        store.write(blockedPath, "被阻塞的原资料。")
        runtime.saveFileEdit(directory, "B-1", "主线", path, "原始资料。")
        val ledger = NovelWorkspaceLedger.load(directory)
        val originalError = IllegalStateException("commit failed")
        val owner = NovelWorkspaceAuthorEdits { _, _, _, _, _ ->
            File(directory, blockedPath).delete()
            File(directory, blockedPath).mkdir()
            File(directory, "$blockedPath/child").writeText("阻止原稿恢复")
            throw originalError
        }
        val error = runCatching {
            owner.apply(directory, "B-1", "主线", linkedMapOf(blockedPath to "新资料。", path to null),
                ledger.headOf("B-1")?.id, NovelWorkspaceLedger.treeSHA256(store.fileTree()), NovelWorkspaceLedger.Message.MANUAL_EDIT)
        }.exceptionOrNull()
        assertEquals(originalError, error)
        assertEquals(1, error!!.suppressed.size)
        assertTrue(File(directory, blockedPath).isDirectory)
        assertEquals("原始资料。", store.read(path))
        assertEquals(ledger.heads, NovelWorkspaceLedger.load(directory).heads)
    }
}
