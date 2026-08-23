package app.amber.feature.novelworkspace

import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceProjectRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val exportedAt = Instant.parse("2026-08-19T00:00:00Z")
    private val projectId = "3F2A0C1A-9B4E-4E4E-9E9E-000000000001"

    private fun bookFiles(title: String): List<NovelWorkspaceFile> = listOf(
        NovelWorkspaceFile(
            "manifest.yaml",
            NovelWorkspaceManifestRenderer.render(
                exportedAt = exportedAt,
                sourceProjectID = projectId,
                sourceProjectRevision = 1,
                sourceSchemaVersion = 1,
                mainBranch = "主线",
            ),
        ),
        NovelWorkspaceFile(
            "project.md",
            NovelWorkspaceMarkdown.render(
                fields = listOf("id" to projectId, "kind" to "project", "title" to title),
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
    )

    private fun repository(): NovelWorkspaceProjectRepository =
        NovelWorkspaceProjectRepository(tempFolder.newFolder("root"))

    @Test
    fun `install then list returns the project summary`() {
        val repo = repository()
        val result = repo.install(projectId, bookFiles("赵大来了"), now = exportedAt)
        assertTrue(repo.exists(projectId))
        assertEquals("B-1", result.mainBranchId)

        val summaries = repo.listProjects()
        assertEquals(1, summaries.size)
        assertEquals(projectId, summaries.single().id)
        assertEquals("赵大来了", summaries.single().name)
        assertEquals("主线", summaries.single().mainBranchSlug)
        assertEquals(exportedAt, summaries.single().updatedAt)
    }

    @Test
    fun `list skips non-workspace directories`() {
        val repo = repository()
        repo.install(projectId, bookFiles("正经项目"))
        File(repo.projectDirectory(projectId).parentFile, "stray-dir").mkdirs()
        val halfBaked = File(repo.projectDirectory(projectId).parentFile, "half-baked")
        halfBaked.mkdirs()
        halfBaked.resolve("notes.md").writeText("no manifest here")
        assertEquals(listOf(projectId), repo.listProjects().map { it.id })
    }

    @Test
    fun `install refuses a duplicate project id`() {
        val repo = repository()
        repo.install(projectId, bookFiles("第一本"))
        try {
            repo.install(projectId, bookFiles("第二本"))
            fail("expected duplicate rejection")
        } catch (expected: NovelWorkspaceIoError) {
            // ok
        }
        assertEquals("第一本", repo.listProjects().single().name)
    }

    @Test
    fun `delete removes the project entirely`() {
        val repo = repository()
        repo.install(projectId, bookFiles("待删"))
        repo.delete(projectId)
        assertFalse(repo.exists(projectId))
        assertEquals(emptyList<NovelWorkspaceProjectSummary>(), repo.listProjects())
    }

    @Test
    fun `createBlank installs a minimal self-consistent book`() {
        val repo = repository()
        val result = repo.createBlank(name = "新书", mainBranchName = "主线", now = exportedAt)

        val dir = result.projectDirectory
        val store = NovelWorkspaceStore(dir)
        // manifest gate + project + branch are all present and readable.
        assertTrue(store.exists())
        val parsed = NovelWorkspaceParsed.parse(
            store.list().map { NovelWorkspaceFile(it, store.read(it) ?: "") },
        )
        assertTrue(parsed.hasKnownFormat)
        assertEquals("新书", parsed.projectTitle)
        assertEquals("主线", parsed.mainBranchSlug)
        assertEquals(result.mainBranchId, parsed.mainBranchID)

        // Initial commit pins the tree; head mirrors the (only) branch.
        val ledger = NovelWorkspaceLedger.load(dir)
        assertEquals(1, ledger.commits.size)
        assertEquals(result.initialCommitId, ledger.head)
        assertEquals(result.initialCommitId, ledger.heads[result.mainBranchId])

        // Sessions ledger exists and is empty (locked decision A).
        val sessions = NovelWorkspaceSessions.load(dir)
        assertEquals(emptyMap<String, List<NovelWorkspaceSessionMessage>>(), sessions.sessions)

        // No chapters yet; the assembler yields an empty brief for a blank book.
        assertEquals("", NovelWorkspaceContextAssembler.assemble(store, "主线"))
        // And it shows up in the project list.
        assertEquals(listOf("新书"), repo.listProjects().map { it.name })
    }

    @Test
    fun `project ids are validated and directories lowercased`() {
        val repo = repository()
        repo.install(projectId, bookFiles("大小写"))
        assertEquals(projectId.lowercase(), repo.projectDirectory(projectId).name)
        assertFalse(NovelWorkspaceProjectRepository.isValidProjectId("not-a-uuid"))
        assertFalse(NovelWorkspaceProjectRepository.isValidProjectId("../escape"))
        assertNull(repo.listProjects().firstOrNull { it.id != projectId })
    }
}
