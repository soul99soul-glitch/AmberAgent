package app.amber.feature.novel.workspace

import app.amber.feature.novel.model.NovelLoadedProject
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelProjectSummary
import app.amber.feature.novel.persistence.NovelFileProjectRepository
import app.amber.feature.novel.persistence.NovelProjectPersisting
import app.amber.feature.novel.serialization.NovelSwiftCompatibleJson
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreCancelled
import app.amber.feature.novelworkspace.NovelWorkspaceSessionMessage
import app.amber.feature.novelworkspace.NovelWorkspaceSessions
import app.amber.feature.novelworkspace.NovelWorkspaceSessionsFile
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceMigrationServiceTest {
    @get:Rule val temporary = TemporaryFolder()

    private val now = Instant.parse("2026-09-30T00:00:00Z")

    @Test
    fun restoreAfterLegacyLoadPreventsOldProjectFromBeingInstalled() = runTest {
        val document = fixtureDocument()
        val legacy = legacyRepository(document)
        val root = temporary.newFolder("workspace")
        val workspace = NovelWorkspaceProjectRepository(root)
        val loaded = CompletableDeferred<Unit>()
        val resumeLoad = CompletableDeferred<Unit>()
        val delayedLegacy = delayedLoad(legacy, loaded, resumeLoad)
        val service = NovelWorkspaceMigrationService(delayedLegacy, workspace)

        val migration = async { runCatching { service.migrate(document.project.id, now) } }
        loaded.await()
        restoreRoot(root)
        resumeLoad.complete(Unit)

        assertTrue(migration.await().exceptionOrNull() is NovelWorkspaceRestoreCancelled)
        assertFalse(workspace.exists(document.project.id.rawValue))
        assertTrue(root.listFiles().orEmpty().isEmpty())
        assertEquals(document.project.name, legacy.loadProject(document.project.id).document.project.name)
    }

    @Test
    fun restoreAfterLegacyLoadPreservesRestoredProjectAndSessionsWithTheSameId() = runTest {
        val document = fixtureDocument()
        val legacy = legacyRepository(document)
        val root = temporary.newFolder("workspace")
        val workspace = NovelWorkspaceProjectRepository(root)
        val restoredRoot = temporary.newFolder("restored-workspace")
        val restoredProject = NovelWorkspaceProjectRepository(restoredRoot).install(
            document.project.id.rawValue,
            NovelLegacyWorkspaceMigrator.workspaceFiles(
                document.copy(project = document.project.copy(name = "恢复后的新稿")),
                exportedAt = now,
            ),
            now = now,
        )
        NovelWorkspaceSessions.save(
            NovelWorkspaceSessionsFile(
                sessions = mapOf(
                    restoredProject.mainBranchId to listOf(
                        NovelWorkspaceSessionMessage(
                            id = "restored-message",
                            role = "user",
                            kind = "discussion",
                            content = "仅属于恢复副本的讨论",
                            createdAt = now,
                        ),
                    ),
                ),
            ),
            restoredProject.projectDirectory,
        )
        val restoredBytes = fileBytes(restoredRoot)
        val loaded = CompletableDeferred<Unit>()
        val resumeLoad = CompletableDeferred<Unit>()
        val service = NovelWorkspaceMigrationService(delayedLoad(legacy, loaded, resumeLoad), workspace)

        val migration = async { runCatching { service.migrate(document.project.id, now) } }
        loaded.await()
        restoreRoot(root, restoredRoot)
        resumeLoad.complete(Unit)

        assertTrue(migration.await().exceptionOrNull() is NovelWorkspaceRestoreCancelled)
        assertEquals(restoredBytes, fileBytes(root))
        assertEquals("恢复后的新稿", workspace.listProjects().single().name)
        assertEquals(document.project.name, legacy.loadProject(document.project.id).document.project.name)
    }

    @Test
    fun restoreAfterLegacyListPreventsMigrateAllFromInstallingTheOldList() = runTest {
        val document = fixtureDocument()
        val legacy = legacyRepository(document)
        val root = temporary.newFolder("workspace")
        val workspace = NovelWorkspaceProjectRepository(root)
        val listed = CompletableDeferred<Unit>()
        val resumeList = CompletableDeferred<Unit>()
        val delayedLegacy = object : NovelProjectPersisting by legacy {
            override suspend fun listProjects(): List<NovelProjectSummary> {
                val snapshot = legacy.listProjects()
                listed.complete(Unit)
                resumeList.await()
                return snapshot
            }
        }
        val service = NovelWorkspaceMigrationService(delayedLegacy, workspace)

        val migration = async { runCatching { service.migrateAll(now) } }
        listed.await()
        restoreRoot(root)
        resumeList.complete(Unit)

        assertTrue(migration.await().exceptionOrNull() is NovelWorkspaceRestoreCancelled)
        assertFalse(workspace.exists(document.project.id.rawValue))
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun migrateAllKeepsTheCallerEpochCapturedBeforeDispatch() = runTest {
        val document = fixtureDocument()
        val legacy = legacyRepository(document)
        val root = temporary.newFolder("workspace")
        val workspace = NovelWorkspaceProjectRepository(root)
        val service = NovelWorkspaceMigrationService(legacy, workspace)
        val callerEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        restoreRoot(root)

        val result = runCatching { service.migrateAll(now, restoreEpoch = callerEpoch) }

        assertTrue(result.exceptionOrNull() is NovelWorkspaceRestoreCancelled)
        assertFalse(workspace.exists(document.project.id.rawValue))
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun restoredEmptyNativeSnapshotDisablesAutomaticMigrationButAllowsExplicitMigration() = runTest {
        val document = fixtureDocument()
        val legacy = legacyRepository(document)
        val sourceBefore = legacy.loadProject(document.project.id).document
        val root = temporary.newFolder("workspace")
        val workspace = NovelWorkspaceProjectRepository(root)

        assertEquals(
            NovelWorkspaceMigrationService.MigrateAllResult(migrated = 1, skipped = 0, failed = 0),
            NovelWorkspaceMigrationService(legacy, workspace).migrateAll(now),
        )
        assertEquals(document.project.id.rawValue, workspace.listProjects().single().id)

        restoreRoot(root)
        workspace.recordNativeRestore()
        val restoredBytes = fileBytes(root)
        var legacyListCalls = 0
        val guardedLegacy = object : NovelProjectPersisting by legacy {
            override suspend fun listProjects(): List<NovelProjectSummary> {
                legacyListCalls += 1
                throw AssertionError("A native restore must not reopen automatic legacy migration")
            }
        }
        val reopenedWorkspace = NovelWorkspaceProjectRepository(root)
        val service = NovelWorkspaceMigrationService(guardedLegacy, reopenedWorkspace)

        assertFalse(reopenedWorkspace.allowsAutomaticMigration())
        assertEquals(
            NovelWorkspaceMigrationService.MigrateAllResult(migrated = 0, skipped = 0, failed = 0),
            service.migrateAll(now),
        )
        assertEquals(0, legacyListCalls)
        assertTrue(reopenedWorkspace.listProjects().isEmpty())
        assertEquals(restoredBytes, fileBytes(root))
        assertEquals(sourceBefore, legacy.loadProject(document.project.id).document)

        assertTrue(service.migrate(document.project.id, now) is NovelWorkspaceMigrationService.Result.Completed)
        assertTrue(reopenedWorkspace.exists(document.project.id.rawValue))
        assertEquals(document.project.id.rawValue, reopenedWorkspace.listProjects().single().id)
        assertFalse(reopenedWorkspace.allowsAutomaticMigration())
        assertEquals(0, legacyListCalls)
        assertEquals(sourceBefore, legacy.loadProject(document.project.id).document)
    }

    private fun fixtureDocument(): NovelProjectDocumentV1 = NovelSwiftCompatibleJson.decodeProjectDocument(
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("novel-v1/projects/minimal-blank.project.json"))
            .use { it.readBytes() },
    )

    private suspend fun legacyRepository(document: NovelProjectDocumentV1): NovelFileProjectRepository =
        NovelFileProjectRepository(temporary.newFolder("legacy")).also { it.createProject(document) }

    private fun delayedLoad(
        legacy: NovelProjectPersisting,
        loaded: CompletableDeferred<Unit>,
        resume: CompletableDeferred<Unit>,
    ): NovelProjectPersisting = object : NovelProjectPersisting by legacy {
        override suspend fun loadProject(id: NovelProjectId): NovelLoadedProject {
            val snapshot = legacy.loadProject(id)
            loaded.complete(Unit)
            resume.await()
            return snapshot
        }
    }

    private fun restoreRoot(root: File, restored: File? = null) {
        NovelWorkspaceRestoreBoundary.beginRestore()
        try {
            assertTrue(root.deleteRecursively())
            if (restored == null) {
                assertTrue(root.mkdirs())
            } else {
                assertTrue(restored.copyRecursively(root, overwrite = true))
            }
        } finally {
            NovelWorkspaceRestoreBoundary.finishRestore()
        }
    }

    private fun fileBytes(root: File): Map<String, List<Byte>> = root.walkTopDown()
        .filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList() }
}
