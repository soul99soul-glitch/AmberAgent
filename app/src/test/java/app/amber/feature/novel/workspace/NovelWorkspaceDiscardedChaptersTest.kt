package app.amber.feature.novel.workspace

import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.RunKernel
import app.amber.feature.novelworkspace.NovelWorkspaceBranches
import app.amber.feature.novelworkspace.NovelWorkspaceChapterHistory
import app.amber.feature.novelworkspace.NovelWorkspaceFile
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceInstaller
import app.amber.feature.novelworkspace.NovelWorkspaceIoError
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceManifestRenderer
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import app.amber.feature.novelworkspace.sha256Hex
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

class NovelWorkspaceDiscardedChaptersTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val time = Instant.parse("2026-09-30T00:00:00Z")
    private val chapterPath = "branches/主线/chapters/002-第二章.md"
    private val discardedPath = "discarded/主线/002-第二章.md"
    private val rawChapter = """
        ---
        id: C-2
        kind: chapter
        ordinal: 2
        title: 第二章
        aliases:
          - 原有别名
        relations:
          - {with: 赵大, type: 盟友}
        customField: 不应丢失
        # 原始注释也保留
        ---

        原有正文第一行。

        原有正文第二行。
    """.trimIndent() + "\n\n"

    private fun runtime() = NovelWorkspaceRuntime(object : RunKernel {
        override fun run(session: GenerationRunSession): Flow<GenerationChunk> = emptyFlow()
    })

    private fun chapter(ordinal: Int, title: String) = NovelWorkspaceMarkdown.render(
        listOf("id" to "C-$ordinal", "kind" to "chapter", "ordinal" to ordinal.toString(), "title" to title),
        body = "第 $ordinal 章正文。",
    )

    private fun project(name: String, extraFiles: List<NovelWorkspaceFile> = emptyList()): File {
        val directory = temporaryFolder.root.resolve(name)
        NovelWorkspaceInstaller.install(
            listOf(
                NovelWorkspaceFile("manifest.yaml", NovelWorkspaceManifestRenderer.render(time, "P-1", 1, 1, "主线")),
                NovelWorkspaceFile("project.md", NovelWorkspaceMarkdown.render(listOf("id" to "P-1", "kind" to "project", "title" to "测试"), body = "")),
                NovelWorkspaceFile("branches/主线/branch.md", NovelWorkspaceMarkdown.render(listOf("id" to "B-1", "kind" to "branch", "title" to "主线"), body = "")),
                NovelWorkspaceFile("branches/主线/chapters/001-第一章.md", chapter(1, "第一章")),
                NovelWorkspaceFile(chapterPath, rawChapter),
                NovelWorkspaceFile("branches/主线/chapters/003-第三章.md", chapter(3, "第三章")),
                NovelWorkspaceFile("branches/主线/plot/current.md", "已写到第三章的剧情。"),
            ) + extraFiles,
            directory, now = time,
        )
        return directory
    }

    private fun snapshot(directory: File) = checkNotNull(NovelWorkspaceDiscardedChapters.snapshot(directory, "B-1", "主线"))

    private fun discard(directory: File, snapshot: NovelWorkspaceDiscardedChapters.Snapshot = snapshot(directory)) =
        NovelWorkspaceDiscardedChapters.discard(runtime(), directory, "B-1", "主线", chapterPath, snapshot.headId, snapshot.treeDigest)

    private fun restore(directory: File, snapshot: NovelWorkspaceDiscardedChapters.Snapshot = snapshot(directory)) =
        NovelWorkspaceDiscardedChapters.restore(runtime(), directory, "B-1", "主线", discardedPath, snapshot.headId, snapshot.treeDigest)

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected rejected author change")
        } catch (_: NovelWorkspaceIoError) {
        }
    }

    @Test
    fun `discard and restore after restart keep exact raw chapter and history`() {
        val directory = project("restart")
        discard(directory)
        val restartedStore = NovelWorkspaceStore(directory)
        assertNull(restartedStore.read(chapterPath))
        assertEquals(rawChapter, restartedStore.read(discardedPath))
        val pending = snapshot(directory)
        assertEquals(chapterPath, pending.entries.single().sourcePath)
        assertEquals(2, pending.entries.single().ordinal)
        assertEquals("第二章", pending.entries.single().title)
        assertNull(pending.entries.single().restoreBlockedReason)
        assertEquals(rawChapter, NovelWorkspaceDiscardedChapters.read(directory, "主线", discardedPath))

        restore(directory, pending)
        assertEquals(rawChapter, NovelWorkspaceStore(directory).read(chapterPath))
        assertNull(NovelWorkspaceStore(directory).read(discardedPath))
        val history = NovelWorkspaceChapterHistory.snapshot(directory, "B-1", "主线", chapterPath)
        assertNotNull(history)
        assertTrue(checkNotNull(history).versions.any { it.contentHash == sha256Hex(rawChapter) })
    }

    @Test
    fun `removing middle chapter opens continuity gates and undo restores both paths`() {
        val directory = project("gates")
        val store = NovelWorkspaceStore(directory)
        assertFalse(NovelWorkspaceLedger.isPlotStale(store, NovelWorkspaceLedger.load(directory), "主线"))
        val commit = discard(directory)
        assertTrue(chapterPath in NovelWorkspaceLedger.changedPaths(commit, NovelWorkspaceLedger.load(directory).commits))
        assertTrue(NovelWorkspaceLedger.isPlotStale(store, NovelWorkspaceLedger.load(directory), "主线"))
        assertEquals(3, NovelWorkspaceUnresolvedStore.entryFor(directory, "主线")?.fromOrdinal)

        assertTrue(runtime().undoLast(directory, "主线"))
        assertEquals(rawChapter, store.read(chapterPath))
        assertNull(store.read(discardedPath))
        assertFalse(NovelWorkspaceLedger.isPlotStale(store, NovelWorkspaceLedger.load(directory), "主线"))
        assertNull(NovelWorkspaceUnresolvedStore.entryFor(directory, "主线"))
    }

    @Test
    fun `owner stale head and stale tree cannot remove or restore chapters`() {
        for (operation in listOf("discard", "restore")) {
            for (cause in listOf("owner", "head", "tree")) {
                val directory = project("$operation-$cause")
                if (operation == "restore") discard(directory)
                val before = snapshot(directory)
                val store = NovelWorkspaceStore(directory)
                var expectedHead = before.headId
                when (cause) {
                    "owner" -> NovelWorkspaceGhostwriteJobs.save(
                        NovelWorkspaceGhostwriteJob("job", branchSlug = "主线", targetChapterCount = 1, startOrdinal = 3, createdAt = time, updatedAt = time),
                        directory,
                    )
                    "head" -> expectedHead = "stale-head"
                    "tree" -> store.write("branches/主线/plot/current.md", "外部改过的剧情。")
                }
                val currentTree = store.fileTree()
                assertRejected {
                    if (operation == "discard") {
                        NovelWorkspaceDiscardedChapters.discard(runtime(), directory, "B-1", "主线", chapterPath, expectedHead, before.treeDigest)
                    } else {
                        NovelWorkspaceDiscardedChapters.restore(runtime(), directory, "B-1", "主线", discardedPath, expectedHead, before.treeDigest)
                    }
                }
                assertEquals(currentTree, store.fileTree())
            }
        }
    }

    @Test
    fun `restoration refuses any existing chapter with the original ordinal`() {
        val directory = project("occupied")
        discard(directory)
        val store = NovelWorkspaceStore(directory)
        val occupiedPath = "branches/主线/chapters/002-另一个标题.md"
        val occupied = chapter(2, "另一个标题")
        store.write(occupiedPath, occupied)
        val before = snapshot(directory)
        assertTrue(checkNotNull(before.entries.single().restoreBlockedReason).contains("第 2 章"))

        assertRejected { restore(directory, before) }
        assertEquals(occupied, store.read(occupiedPath))
        assertEquals(rawChapter, store.read(discardedPath))
        assertNull(store.read(chapterPath))
    }

    @Test
    fun `discarded chapters stay on their source branch and legacy flat files belong only to main`() {
        val legacyPath = "discarded/004-旧废稿.md"
        val legacyRaw = chapter(4, "旧废稿")
        val branchLegacyPath = "branches/主线/discarded/006-旧分支废稿.md"
        val branchLegacyRaw = chapter(6, "旧分支废稿")
        val directory = project("legacy", listOf(
            NovelWorkspaceFile(legacyPath, legacyRaw),
            NovelWorkspaceFile(branchLegacyPath, branchLegacyRaw),
            NovelWorkspaceFile("branches/支线/branch.md", NovelWorkspaceMarkdown.render(listOf("id" to "B-2", "kind" to "branch", "title" to "支线"), body = "")),
            NovelWorkspaceFile("discarded/支线/005-支线废稿.md", chapter(5, "支线废稿")),
        ))
        assertEquals(listOf(legacyPath, branchLegacyPath), snapshot(directory).entries.map { it.path })
        NovelWorkspaceBranches.ActiveBranchStore.save(NovelWorkspaceBranches.NovelWorkspaceActiveBranch("支线"), directory)
        assertNull(NovelWorkspaceDiscardedChapters.snapshot(directory, "B-1", "主线"))
        val other = checkNotNull(NovelWorkspaceDiscardedChapters.snapshot(directory, "B-2", "支线"))
        assertEquals(listOf("discarded/支线/005-支线废稿.md"), other.entries.map { it.path })
        assertNull(NovelWorkspaceDiscardedChapters.read(directory, "支线", legacyPath))
        assertNull(NovelWorkspaceDiscardedChapters.read(directory, "支线", branchLegacyPath))
        assertRejected {
            NovelWorkspaceDiscardedChapters.restore(runtime(), directory, "B-2", "支线", legacyPath, other.headId, other.treeDigest)
        }
        assertRejected {
            NovelWorkspaceDiscardedChapters.discard(runtime(), directory, "B-2", "支线", chapterPath, other.headId, other.treeDigest)
        }
        assertEquals(rawChapter, NovelWorkspaceStore(directory).read(chapterPath))

        NovelWorkspaceBranches.ActiveBranchStore.save(NovelWorkspaceBranches.NovelWorkspaceActiveBranch("主线"), directory)
        val main = snapshot(directory)
        NovelWorkspaceDiscardedChapters.restore(runtime(), directory, "B-1", "主线", legacyPath, main.headId, main.treeDigest)
        assertEquals(legacyRaw, NovelWorkspaceStore(directory).read("branches/主线/chapters/004-旧废稿.md"))
        assertNull(NovelWorkspaceStore(directory).read(legacyPath))
        val branchLegacy = snapshot(directory)
        NovelWorkspaceDiscardedChapters.restore(runtime(), directory, "B-1", "主线", branchLegacyPath, branchLegacy.headId, branchLegacy.treeDigest)
        assertEquals(branchLegacyRaw, NovelWorkspaceStore(directory).read("branches/主线/chapters/006-旧分支废稿.md"))
        assertNull(NovelWorkspaceStore(directory).read(branchLegacyPath))
    }

    @Test
    fun `nested imported chapter keeps its relative source path through discard and restore`() {
        val nestedPath = "branches/主线/chapters/imported/004-嵌套.md"
        val nestedDiscardedPath = "discarded/主线/imported/004-嵌套.md"
        val raw = chapter(4, "嵌套")
        val directory = project("nested", listOf(NovelWorkspaceFile(nestedPath, raw)))
        val before = snapshot(directory)
        NovelWorkspaceDiscardedChapters.discard(runtime(), directory, "B-1", "主线", nestedPath, before.headId, before.treeDigest)
        val discarded = snapshot(directory)
        assertEquals(nestedPath, discarded.entries.single().sourcePath)
        assertEquals(raw, NovelWorkspaceStore(directory).read(nestedDiscardedPath))

        NovelWorkspaceDiscardedChapters.restore(runtime(), directory, "B-1", "主线", nestedDiscardedPath, discarded.headId, discarded.treeDigest)
        assertEquals(raw, NovelWorkspaceStore(directory).read(nestedPath))
        assertNull(NovelWorkspaceStore(directory).read(nestedDiscardedPath))
    }

    @Test
    fun `destination write failure keeps the original chapter and continuity gate`() {
        val directory = project("rollback")
        val before = snapshot(directory)
        File(directory, "discarded").writeText("这里不是文件夹")
        val store = NovelWorkspaceStore(directory)
        val treeBefore = store.fileTree()

        runCatching { discard(directory, before) }.onSuccess { throw AssertionError("Expected destination write failure") }

        assertEquals(treeBefore, store.fileTree())
        assertEquals(rawChapter, store.read(chapterPath))
        assertNull(store.read(discardedPath))
        assertEquals(before.headId, NovelWorkspaceLedger.load(directory).heads["B-1"])
        assertNull(NovelWorkspaceUnresolvedStore.entryFor(directory, "主线"))
    }
}
