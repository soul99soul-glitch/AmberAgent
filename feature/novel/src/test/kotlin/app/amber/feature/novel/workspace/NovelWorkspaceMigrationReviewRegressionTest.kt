package app.amber.feature.novel.workspace

import app.amber.feature.novel.model.*
import app.amber.feature.novel.serialization.NovelSwiftCompatibleJson
import app.amber.feature.novelworkspace.*
import java.io.ByteArrayInputStream
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceMigrationReviewRegressionTest {
    @get:Rule val tempFolder = TemporaryFolder()
    private val now = Instant.parse("2026-10-02T00:00:00Z")

    private fun fixture() = NovelSwiftCompatibleJson.decodeProjectDocument(
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("novel-v1/projects/full-two-branch.project.json")).readBytes(),
    )

    @Test
    fun mainBranchKeepsIdentityWhenEarlierSideBranchHasSameSlug() {
        val source = fixture()
        val main = source.branches.first { it.id == source.project.mainBranchID }.copy(name = "main")
        val side = source.branches.first { it.id != source.project.mainBranchID }.copy(name = "Main")
        val files = NovelLegacyWorkspaceMigrator.workspaceFiles(source.copy(branches = listOf(side, main)), now)
        assertEquals(main.id.rawValue, NovelWorkspaceParsed.parse(files).mainBranchID)
        val installed = NovelWorkspaceInstaller.install(files, tempFolder.newFolder("collision"))
        assertEquals(main.id.rawValue, installed.mainBranchId)
        val store = NovelWorkspaceStore(installed.projectDirectory)
        val mainSlug = NovelWorkspaceManifest.parse(store.read("manifest.yaml")!!).mainBranch
        assertEquals(main.id.rawValue, NovelWorkspaceLedger.branchId(store, NovelWorkspaceLedger.load(installed.projectDirectory), mainSlug))
    }

    @Test
    fun dotLeadingSourceNamesRetainMaterialsBranchesAndDiscardedTextInExport() {
        val source = fixture()
        val main = source.branches.first { it.id == source.project.mainBranchID }
        val side = source.branches.first { it.id != source.project.mainBranchID }.copy(name = ".番外")
        val materialId = NovelMaterialId.parse("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA")
        val revisionId = NovelMaterialRevisionId.parse("BBBBBBBB-BBBB-4BBB-8BBB-BBBBBBBBBBBB")
        val revision = NovelMaterialRevisionRecord(revisionId, materialId, 1, ".规则", "不可遗漏的资料正文", emptyList(), injectionMode = NovelInjectionMode.Always, createdAt = now, operationID = source.appliedOperations.first().operationID)
        val document = source.copy(
            branches = listOf(main, side.copy(overrideRevisionIDs = listOf(revisionId))),
            materials = listOf(NovelMaterialRecord(materialId, NovelMaterialKind.World, revisionId, listOf(revisionId))),
            materialRevisions = listOf(revision),
            chapters = source.chapters.map { it.copy(discardedAt = now) },
            chapterVersions = source.chapterVersions.map { it.copy(title = ".废稿") },
        )
        val files = NovelLegacyWorkspaceMigrator.workspaceFiles(document, now)
        val installed = NovelWorkspaceInstaller.install(files, tempFolder.newFolder("visible"))
        val visible = NovelWorkspaceStore(installed.projectDirectory).list()
        assertEquals("Every converted public file must stay visible", files.map { it.path }.sorted(), visible)
        val exported = NovelWorkspaceExchange.readZipFiles(ByteArrayInputStream(NovelWorkspaceExchange.exportZipBytes(installed.projectDirectory)))
        assertEquals(files.associate { it.path to it.content }, exported.associate { it.path to it.content })
        assertTrue(NovelWorkspaceParsed.parse(exported).materials.any { it.title == ".规则" && it.content == revision.content })
        assertTrue(exported.any { it.path.endsWith("/branch.md") && NovelWorkspaceMarkdown.parseFile(it.content).fields["title"] == ".番外" })
        assertTrue(exported.any { "/discarded/" in it.path && NovelWorkspaceMarkdown.parseFile(it.content).fields["title"] == ".废稿" })
    }

    @Test
    fun sourceNeedsSyncRemainsStaleUntilRealPlotChangeIsCommitted() {
        val source = fixture()
        val document = source.copy(branches = source.branches.map {
            if (it.id == source.project.mainBranchID) it.copy(syncStatus = NovelBranchSyncStatus.NeedsSync) else it
        })
        val files = NovelLegacyWorkspaceMigrator.workspaceFiles(document, now)
        val installed = NovelWorkspaceInstaller.install(files, tempFolder.newFolder("needs-sync"))
        val store = NovelWorkspaceStore(installed.projectDirectory)
        val slug = NovelWorkspaceManifest.parse(store.read("manifest.yaml")!!).mainBranch
        val before = NovelWorkspaceLedger.load(installed.projectDirectory)
        assertTrue("A copied old plot is not synchronized with changed manuscript", NovelWorkspaceLedger.isPlotStale(store, before, slug))
        store.write("branches/$slug/plot/current.md", "Actually synchronized plot")
        val synchronized = NovelWorkspaceLedger.makeCommit("sync", before.heads[installed.mainBranchId], store.fileTree(), NovelWorkspaceLedger.Message.GENERIC, now.plusSeconds(1))
        val after = NovelWorkspaceLedger.appending(synchronized, before).copy(heads = before.heads + (installed.mainBranchId to synchronized.id))
        NovelWorkspaceLedger.save(after, installed.projectDirectory)
        org.junit.Assert.assertFalse(NovelWorkspaceLedger.isPlotStale(store, after, slug))
    }

    @Test
    fun deletingLastSourceChapterStillRequiresPlotSynchronizationAfterImport() {
        val source = fixture()
        val document = source.copy(branches = source.branches.map {
            if (it.id == source.project.mainBranchID) it.copy(
                syncStatus = NovelBranchSyncStatus.NeedsSync,
                workingChapterSelections = emptyList(),
            ) else it
        })
        val files = NovelLegacyWorkspaceMigrator.workspaceFiles(document, now)
        val installed = NovelWorkspaceInstaller.install(files, tempFolder.newFolder("empty-needs-sync"))
        val store = NovelWorkspaceStore(installed.projectDirectory)
        val slug = NovelWorkspaceManifest.parse(store.read("manifest.yaml")!!).mainBranch
        assertTrue(NovelWorkspaceLedger.workingChapterOrdinals(store, slug).isEmpty())
        assertTrue("Old plot must be repaired after removal of the last manuscript chapter", NovelWorkspaceLedger.isPlotStale(store, NovelWorkspaceLedger.load(installed.projectDirectory), slug))
    }

    @Test
    fun explicitSameBodyPlotApprovalClearsImportedNeedsSyncBaseline() {
        val source = fixture()
        val document = source.copy(branches = source.branches.map {
            if (it.id == source.project.mainBranchID) it.copy(syncStatus = NovelBranchSyncStatus.NeedsSync) else it
        })
        val installed = NovelWorkspaceInstaller.install(NovelLegacyWorkspaceMigrator.workspaceFiles(document, now), tempFolder.newFolder("same-plot"))
        val store = NovelWorkspaceStore(installed.projectDirectory)
        val slug = NovelWorkspaceManifest.parse(store.read("manifest.yaml")!!).mainBranch
        val before = NovelWorkspaceLedger.load(installed.projectDirectory)
        assertTrue(NovelWorkspaceLedger.isPlotStale(store, before, slug))
        val acknowledged = NovelWorkspaceLedger.makeCommit("same-plot-sync", before.heads[installed.mainBranchId], store.fileTree(), NovelWorkspaceLedger.Message.PLOT_POINTER, now.plusSeconds(1))
        val after = NovelWorkspaceLedger.appending(acknowledged, before).copy(heads = before.heads + (installed.mainBranchId to acknowledged.id))
        assertEquals(before.headCommit!!.files, acknowledged.files)
        org.junit.Assert.assertFalse(NovelWorkspaceLedger.isPlotStale(store, after, slug))
    }

    @Test
    fun synchronizedImportedBranchDoesNotRearmOldNeedsSyncOnExchangeRoundtrip() {
        val source = fixture()
        val document = source.copy(branches = source.branches.map {
            if (it.id == source.project.mainBranchID) it.copy(syncStatus = NovelBranchSyncStatus.NeedsSync) else it
        })
        val installed = NovelWorkspaceInstaller.install(NovelLegacyWorkspaceMigrator.workspaceFiles(document, now), tempFolder.newFolder("fixed-plot-source"))
        val store = NovelWorkspaceStore(installed.projectDirectory)
        val slug = NovelWorkspaceManifest.parse(store.read("manifest.yaml")!!).mainBranch
        val branchPath = "branches/$slug/branch.md"
        val originalBranch = store.read(branchPath)
        store.write("branches/$slug/plot/current.md", "Actual new synchronized plot")
        val before = NovelWorkspaceLedger.load(installed.projectDirectory)
        val synchronized = NovelWorkspaceLedger.makeCommit("plot-sync", before.heads[installed.mainBranchId], store.fileTree(), NovelWorkspaceLedger.Message.PLOT_POINTER, now.plusSeconds(1))
        NovelWorkspaceLedger.save(NovelWorkspaceLedger.appending(synchronized, before).copy(heads = before.heads + (installed.mainBranchId to synchronized.id)), installed.projectDirectory)
        val exported = NovelWorkspaceExchange.readZipFiles(ByteArrayInputStream(NovelWorkspaceExchange.exportZipBytes(installed.projectDirectory)))
        assertEquals("Export projects freshness without rewriting the native book", originalBranch, store.read(branchPath))
        val reimported = NovelWorkspaceInstaller.install(exported, tempFolder.newFolder("fixed-plot-target"))
        org.junit.Assert.assertFalse("Synced manuscript must remain fresh on another import", NovelWorkspaceLedger.isPlotStale(NovelWorkspaceStore(reimported.projectDirectory), NovelWorkspaceLedger.load(reimported.projectDirectory), slug))
    }

    @Test
    fun nativeChapterEditRetainsPlotStalenessAcrossExchangeRoundtrip() {
        val installed = NovelWorkspaceInstaller.install(NovelLegacyWorkspaceMigrator.workspaceFiles(fixture(), now), tempFolder.newFolder("edited-source"))
        val store = NovelWorkspaceStore(installed.projectDirectory)
        val slug = NovelWorkspaceManifest.parse(store.read("manifest.yaml")!!).mainBranch
        val branchPath = "branches/$slug/branch.md"
        val originalBranch = store.read(branchPath)
        val chapterPath = store.list("branches/$slug/chapters").first()
        store.write(chapterPath, NovelWorkspaceMarkdown.withBody(store.read(chapterPath)!!, "Story facts changed since the old plot"))
        val before = NovelWorkspaceLedger.load(installed.projectDirectory)
        val edited = NovelWorkspaceLedger.makeCommit("chapter-edit", before.heads[installed.mainBranchId], store.fileTree(), NovelWorkspaceLedger.Message.MANUAL_EDIT, now.plusSeconds(1))
        val after = NovelWorkspaceLedger.appending(edited, before).copy(heads = before.heads + (installed.mainBranchId to edited.id))
        NovelWorkspaceLedger.save(after, installed.projectDirectory)
        assertTrue(NovelWorkspaceLedger.isPlotStale(store, after, slug))
        val exported = NovelWorkspaceExchange.readZipFiles(ByteArrayInputStream(NovelWorkspaceExchange.exportZipBytes(installed.projectDirectory)))
        assertEquals("Export must not mutate native branch metadata", originalBranch, store.read(branchPath))
        val reimported = NovelWorkspaceInstaller.install(exported, tempFolder.newFolder("edited-target"))
        assertTrue("Unsynchronized manuscript must remain stale on another import", NovelWorkspaceLedger.isPlotStale(NovelWorkspaceStore(reimported.projectDirectory), NovelWorkspaceLedger.load(reimported.projectDirectory), slug))
    }
}
