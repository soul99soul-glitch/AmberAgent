package app.amber.feature.novel.workspace

import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.RunKernel
import app.amber.feature.novelworkspace.NovelWorkspaceChapterHistory
import app.amber.feature.novelworkspace.NovelWorkspaceChapterHistorySnapshot
import app.amber.feature.novelworkspace.NovelWorkspaceFile
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJob
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceInstaller
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

class NovelWorkspaceChapterRestoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val path = "branches/主线/chapters/001-第一章.md"
    private val time = Instant.parse("2026-09-30T00:00:00Z")
    private val runtime = NovelWorkspaceRuntime(object : RunKernel {
        override fun run(session: GenerationRunSession): Flow<GenerationChunk> = emptyFlow()
    })

    private fun chapter(id: String, ordinal: Int, title: String, body: String) = NovelWorkspaceMarkdown.render(
        listOf("id" to id, "kind" to "chapter", "ordinal" to ordinal.toString(), "title" to title), body = body,
    )

    private fun project(name: String): File {
        val directory = temporaryFolder.root.resolve(name)
        NovelWorkspaceInstaller.install(
            listOf(
                NovelWorkspaceFile("manifest.yaml", NovelWorkspaceManifestRenderer.render(time, "P-1", 1, 1, "主线")),
                NovelWorkspaceFile("project.md", NovelWorkspaceMarkdown.render(listOf("id" to "P-1", "kind" to "project", "title" to "测试"), body = "")),
                NovelWorkspaceFile("branches/主线/branch.md", NovelWorkspaceMarkdown.render(listOf("id" to "B-1", "kind" to "branch", "title" to "主线"), body = "")),
                NovelWorkspaceFile(path, chapter("C-1", 1, "第一章", "原始正文。")),
                NovelWorkspaceFile("branches/主线/chapters/002-第二章.md", chapter("C-2", 2, "第二章", "后续正文。")),
                NovelWorkspaceFile("branches/主线/plot/current.md", "原始剧情。"),
            ), directory, now = time,
        )
        runtime.saveChapterEdit(directory, "B-1", "主线", path, "改后标题", "改后正文。")
        return directory
    }

    private fun snapshot(directory: File) = checkNotNull(NovelWorkspaceChapterHistory.snapshot(directory, "B-1", "主线", path))

    private fun restore(directory: File, snapshot: NovelWorkspaceChapterHistorySnapshot) = NovelWorkspaceChapterRestore.restore(
        runtime, directory, "B-1", "主线", path,
        snapshot.versions.last().contentHash, snapshot.headId, snapshot.currentContentHash,
    )

    @Test
    fun `restore preserves identity and opens normal continuity gates for middle chapter`() {
        val directory = project("restore")
        runtime.saveFileEdit(directory, "B-1", "主线", "branches/主线/plot/current.md", "改后剧情。")
        NovelWorkspaceUnresolvedStore.clear(directory, "主线")
        assertFalse(NovelWorkspaceLedger.isPlotStale(NovelWorkspaceStore(directory), NovelWorkspaceLedger.load(directory), "主线"))
        val snapshot = snapshot(directory)

        val commit = restore(directory, snapshot)
        assertNotNull(commit)
        val restored = NovelWorkspaceMarkdown.parseFile(checkNotNull(NovelWorkspaceStore(directory).read(path)))
        assertEquals("C-1", restored.fields["id"])
        assertEquals("1", restored.fields["ordinal"])
        assertEquals("第一章", restored.fields["title"])
        assertEquals("原始正文。", restored.body)
        assertEquals(2, NovelWorkspaceUnresolvedStore.entryFor(directory, "主线")?.fromOrdinal)
        assertTrue(NovelWorkspaceLedger.isPlotStale(NovelWorkspaceStore(directory), NovelWorkspaceLedger.load(directory), "主线"))
    }

    @Test
    fun `restore rejects active owner and stale head or current text without changing chapter`() {
        for (cause in listOf("owner", "head", "text")) {
            val directory = project(cause)
            val snapshot = snapshot(directory)
            val store = NovelWorkspaceStore(directory)
            when (cause) {
                "owner" -> NovelWorkspaceGhostwriteJobs.save(
                    NovelWorkspaceGhostwriteJob("job", branchSlug = "主线", targetChapterCount = 1, startOrdinal = 2, createdAt = time, updatedAt = time), directory,
                )
                "head" -> runtime.saveFileEdit(directory, "B-1", "主线", "branches/主线/plot/current.md", "其他已提交改动。")
                "text" -> store.write(path, chapter("C-1", 1, "外部标题", "外部正文。"))
            }
            val before = checkNotNull(store.read(path))
            assertNull(restore(directory, snapshot))
            assertEquals(sha256Hex(before), sha256Hex(checkNotNull(store.read(path))))
        }
    }
}
