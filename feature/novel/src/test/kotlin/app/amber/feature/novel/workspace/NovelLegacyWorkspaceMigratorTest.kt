package app.amber.feature.novel.workspace

import app.amber.feature.novel.serialization.NovelSwiftCompatibleJson
import app.amber.feature.novelworkspace.NovelWorkspaceInstaller
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspaceParsed
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelLegacyWorkspaceMigratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun twoBranchDocument() = NovelSwiftCompatibleJson.decodeProjectDocument(
        requireNotNull(
            javaClass.classLoader!!.getResourceAsStream("novel-v1/projects/full-two-branch.project.json"),
        ).readBytes(),
    )

    @Test
    fun `legacy document becomes an installable workspace`() {
        val document = twoBranchDocument()
        val files = NovelLegacyWorkspaceMigrator.workspaceFiles(
            document,
            exportedAt = Instant.parse("2026-08-19T00:00:00Z"),
        )

        val parsed = NovelWorkspaceParsed.parse(files)
        assertTrue(parsed.hasKnownFormat)
        assertEquals(document.project.name, parsed.projectTitle)
        assertEquals(document.project.id.rawValue, parsed.sourceProjectID)
        // The full fixture has chapters on its main branch; they survive ordered by ordinal.
        val mainBranch = document.branches.first { it.id == document.project.mainBranchID }
        val liveSelections = mainBranch.workingChapterSelections.filter { selection ->
            document.chapters.first { it.id == selection.chapterID }.discardedAt == null
        }
        assertEquals(liveSelections.size, parsed.workingChapters.size)
        assertEquals(
            liveSelections.mapNotNull { selection ->
                document.chapterVersions.firstOrNull {
                    it.id == selection.versionID && it.chapterID == selection.chapterID
                }?.content
            },
            parsed.workingChapters.map { it.content },
        )
        // Live materials survive as setting cards.
        val liveMaterialCount = document.materials.count { !it.isDeleted }
        assertEquals(liveMaterialCount, parsed.materials.size)
    }

    @Test
    fun `installed migration carries one initial commit and sessions`() {
        val document = twoBranchDocument()
        val files = NovelLegacyWorkspaceMigrator.workspaceFiles(
            document,
            exportedAt = Instant.parse("2026-08-19T00:00:00Z"),
        )
        val projectDir = tempFolder.newFolder("migrated")
        val result = NovelWorkspaceInstaller.install(files, projectDir)

        val ledger = NovelWorkspaceLedger.load(projectDir)
        assertEquals(1, ledger.commits.size)
        assertEquals(result.initialCommitId, ledger.head)
        // Main branch head points at the initial commit.
        val mainBranchId = document.project.mainBranchID.rawValue
        assertEquals(result.initialCommitId, ledger.heads[mainBranchId])

        // Decision A: chat bubbles travel inside the ledger, not the book.
        val sessions = NovelLegacyWorkspaceMigrator.sessionsFile(document)
        val totalLegacyMessages = document.sessions.sumOf { it.messages.size }
        assertEquals(totalLegacyMessages, sessions.sessions.values.sumOf { it.size })
        assertTrue(NovelWorkspaceStore(projectDir).list().none { it.startsWith(".amber") })
    }

    /**
     * 多分支迁移保真：legacy 双分支书迁完后，每条 active 分支都在 ledger 注册了指向
     * 初始提交的 head，且分支目录（章节/剧情）完整落在各自 slug 下——侧分支可以直接
     * 被切为活跃分支并继续写作，而不是只迁主线丢弃支线。
     */
    @Test
    fun `two-branch legacy document migrates with a registered head per active branch`() {
        val document = twoBranchDocument()
        val files = NovelLegacyWorkspaceMigrator.workspaceFiles(
            document,
            exportedAt = Instant.parse("2026-08-19T00:00:00Z"),
        )
        val projectDir = tempFolder.newFolder("migrated-two-branch")
        val result = NovelWorkspaceInstaller.install(files, projectDir)

        val store = NovelWorkspaceStore(projectDir)
        val ledger = NovelWorkspaceLedger.load(projectDir)
        val activeBranches = document.branches.filter { it.lifecycle == app.amber.feature.novel.model.NovelBranchLifecycle.Active }
        assertEquals(activeBranches.size, ledger.heads.size)
        for (branch in activeBranches) {
            val slug = files.map { it.path }
                .filter { it.startsWith("branches/") && it.endsWith("/branch.md") }
                .first { path ->
                    NovelWorkspaceMarkdown.parseFile(
                        files.first { it.path == path }.content,
                    ).fields["id"] == branch.id.rawValue
                }
                .removePrefix("branches/").removeSuffix("/branch.md")
            assertEquals(result.initialCommitId, ledger.heads[branch.id.rawValue])
            // 分支元数据可解析（branchId 命中已注册 head），章节归位到各自分支目录。
            assertEquals(branch.id.rawValue, NovelWorkspaceLedger.branchId(store, ledger, slug))
            val chapterCount = NovelWorkspaceLedger.workingChapterOrdinals(store, slug).size
            val liveSelections = branch.workingChapterSelections.count { selection ->
                document.chapters.first { it.id == selection.chapterID }.discardedAt == null
            }
            assertEquals(liveSelections, chapterCount)
        }
        // 主线 slug 与 manifest 一致（切回主线语义不变）。
        assertEquals(
            app.amber.feature.novelworkspace.NovelWorkspaceManifest.parse(
                store.read(app.amber.feature.novelworkspace.NovelWorkspacePaths.MANIFEST) ?: "",
            ).mainBranch,
            files.map { it.path }
                .filter { it.startsWith("branches/") && it.endsWith("/branch.md") }
                .first { path ->
                    NovelWorkspaceMarkdown.parseFile(files.first { it.path == path }.content)
                        .fields["id"] == document.project.mainBranchID.rawValue
                }
                .removePrefix("branches/").removeSuffix("/branch.md"),
        )
    }

    @Test
    fun `minimal blank project migrates to an empty book`() {
        val document = NovelSwiftCompatibleJson.decodeProjectDocument(
            requireNotNull(
                javaClass.classLoader!!.getResourceAsStream("novel-v1/projects/minimal-blank.project.json"),
            ).readBytes(),
        )
        val files = NovelLegacyWorkspaceMigrator.workspaceFiles(
            document,
            exportedAt = Instant.parse("2026-08-19T00:00:00Z"),
        )
        val parsed = NovelWorkspaceParsed.parse(files)
        assertTrue(parsed.hasKnownFormat)
        assertEquals(0, parsed.workingChapters.size)
        // The blank fixture still has the initial empty snapshot, so plot/current.md exists.
        assertFalse(parsed.plotMissing)
        val projectDir = tempFolder.newFolder("blank")
        val result = NovelWorkspaceInstaller.install(files, projectDir)
        assertFalse(result.plotMissing)
    }
}
