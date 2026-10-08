package app.amber.feature.novel.workspace

import app.amber.feature.novel.domain.NovelError
import app.amber.feature.novel.model.NovelBranchLifecycle
import app.amber.feature.novel.serialization.NovelPackageCodec
import app.amber.feature.novel.serialization.NovelSwiftCompatibleJson
import app.amber.feature.novelworkspace.NovelWorkspaceExchange
import app.amber.feature.novelworkspace.NovelWorkspaceFile
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceParsed
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreCancelled
import app.amber.feature.novelworkspace.NovelWorkspaceSessions
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceProjectImportTest {
    @get:Rule val temporary = TemporaryFolder()

    private val now = Instant.parse("2026-10-01T00:00:00Z")

    @Test
    fun projectPackageImportsChaptersMaterialsBranchesAndDiscussions() {
        val bytes = packageBytes("full-two-branch")
        val document = NovelPackageCodec.decode(bytes)
        val repository = NovelWorkspaceProjectRepository(temporary.newFolder("workspace"))

        val result = NovelWorkspaceProjectImport.importProject(bytes, repository, now)

        assertNotEquals(document.project.id.rawValue.lowercase(), result.projectDirectory.name)
        val store = NovelWorkspaceStore(result.projectDirectory)
        val parsed = NovelWorkspaceParsed.parse(store.list().map { path -> NovelWorkspaceFile(path, store.read(path)!!) })
        assertEquals(document.project.name, parsed.projectTitle)
        assertEquals(document.project.id.rawValue, parsed.sourceProjectID)
        val main = document.branches.first { it.id == document.project.mainBranchID }
        assertEquals(
            main.workingChapterSelections.map { selection ->
                document.chapterVersions.first { it.id == selection.versionID }.content
            },
            parsed.workingChapters.map { it.content },
        )
        assertTrue(parsed.workingChapters.isNotEmpty())
        assertEquals(
            document.materials.filter { !it.isDeleted }.map { material ->
                document.materialRevisions.first { it.id == material.currentRevisionID }.content
            }.sorted(),
            parsed.materials.map { it.content }.sorted(),
        )
        val ledger = NovelWorkspaceLedger.load(result.projectDirectory)
        assertEquals(
            document.branches.filter { it.lifecycle == NovelBranchLifecycle.Active }.map { it.id.rawValue }.toSet(),
            ledger.heads.keys,
        )
        assertEquals(1, ledger.commits.size)
        val sessions = NovelWorkspaceSessions.load(result.projectDirectory)
        assertEquals(
            document.sessions.flatMap { it.messages }.map { it.content }.sorted(),
            sessions.sessions.values.flatten().map { it.content }.sorted(),
        )
        assertTrue(sessions.sessions.values.flatten().isNotEmpty())
        assertEquals(document.project.name, repository.listProjects().single().name)
    }

    @Test
    fun importingTheSamePackageTwiceCreatesIndependentProjects() {
        val bytes = packageBytes("minimal-blank")
        val repository = NovelWorkspaceProjectRepository(temporary.newFolder("workspace"))

        val first = NovelWorkspaceProjectImport.importProject(bytes, repository, now)
        val second = NovelWorkspaceProjectImport.importProject(bytes, repository, now)

        assertNotEquals(first.projectDirectory, second.projectDirectory)
        assertNotEquals(first.initialCommitId, second.initialCommitId)
        assertEquals(2, repository.listProjects().size)
    }

    @Test
    fun packageWithAdditionalIosFieldsCanStillBeImported() {
        val bytes = packageBytes("ios-export-with-future-fields")
        val repository = NovelWorkspaceProjectRepository(temporary.newFolder("workspace"))

        val result = NovelWorkspaceProjectImport.importProject(bytes, repository, now)

        assertEquals(NovelPackageCodec.decode(bytes).project.name, repository.listProjects().single().name)
        assertTrue(NovelWorkspaceStore(result.projectDirectory).list().any { "/chapters/" in it })
    }

    @Test
    fun materialTitlesWithoutExtensionsBecomeVisibleSettingFiles() {
        val document = NovelSwiftCompatibleJson.decodeProjectDocument(
            requireNotNull(javaClass.classLoader!!.getResourceAsStream("novel-v1/projects/legacy-missing-v1-defaults.project.json"))
                .use { it.readBytes() },
        )
        val bytes = NovelSwiftCompatibleJson.encodePackageEnvelope(
            NovelSwiftCompatibleJson.encodePackageFromDocument(document),
        )
        val repository = NovelWorkspaceProjectRepository(temporary.newFolder("workspace"))

        val imported = NovelWorkspaceProjectImport.importProject(bytes, repository, now)

        val store = NovelWorkspaceStore(imported.projectDirectory)
        val parsed = NovelWorkspaceParsed.parse(store.list().map { NovelWorkspaceFile(it, store.read(it)!!) })
        assertEquals(document.materials.count { !it.isDeleted }, parsed.materials.size)
        assertTrue(parsed.materials.isNotEmpty())
    }

    @Test
    fun iosRuntimeHistoryDoesNotBlockBookImportAndNativeCodecRemainsStrict() {
        val original = packageBytes("full-two-branch")
        val envelope = NovelSwiftCompatibleJson.decodePackageEnvelope(original)
        val project = NovelSwiftCompatibleJson.json.parseToJsonElement(
            Base64.getDecoder().decode(envelope.projectJsonBase64).decodeToString(),
        ).jsonObject
        val payload = JsonObject(project + mapOf(
            "appliedOperations" to JsonArray(listOf(JsonObject(mapOf("kind" to JsonPrimitive("workspacePlot"))))),
        )).toString().toByteArray()
        val bytes = NovelSwiftCompatibleJson.encodePackageEnvelope(envelope.copy(
            projectByteCount = payload.size,
            projectSha256 = NovelSwiftCompatibleJson.sha256Hex(payload),
            projectJsonBase64 = Base64.getEncoder().encodeToString(payload),
        ))
        val repository = NovelWorkspaceProjectRepository(temporary.newFolder("workspace"))

        assertThrows(NovelError::class.java) { NovelPackageCodec.decode(bytes) }
        val imported = NovelWorkspaceProjectImport.importProject(bytes, repository, now)

        assertTrue(repository.exists(imported.projectDirectory.name))
        assertTrue(NovelWorkspaceStore(imported.projectDirectory).list().any { "/chapters/" in it })
    }

    @Test
    fun missingSelectedChapterVersionIsRejectedBeforeInstallation() {
        val document = NovelPackageCodec.decode(packageBytes("full-two-branch"))
        val bytes = NovelSwiftCompatibleJson.encodePackageEnvelope(
            NovelSwiftCompatibleJson.encodePackageFromDocument(document.copy(chapterVersions = emptyList())),
        )
        val root = temporary.newFolder("workspace")

        assertThrows(NovelError::class.java) {
            NovelWorkspaceProjectImport.importProject(bytes, NovelWorkspaceProjectRepository(root), now)
        }
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun workspaceZipStillImportsWithoutChangingBookFiles() {
        val repository = NovelWorkspaceProjectRepository(temporary.newFolder("workspace"))
        val original = repository.createBlank("ZIP 小说", now = now)
        val originalTree = NovelWorkspaceStore(original.projectDirectory).fileTree()
        val bytes = NovelWorkspaceExchange.exportZipBytes(original.projectDirectory)

        val imported = NovelWorkspaceProjectImport.importProject(bytes, repository, now)

        assertNotEquals(original.projectDirectory, imported.projectDirectory)
        assertEquals(originalTree, NovelWorkspaceStore(imported.projectDirectory).fileTree())
        assertEquals(2, repository.listProjects().size)
    }

    @Test
    fun unsupportedSchemaAndCorruptPayloadDoNotCreateProjects() {
        val root = temporary.newFolder("workspace")
        val repository = NovelWorkspaceProjectRepository(root)
        val corrupt = packageBytes("minimal-blank").decodeToString().replace(
            Regex("\"projectSHA256\"\\s*:\\s*\"[a-fA-F0-9]+\""),
            "\"projectSHA256\":\"${"0".repeat(64)}\"",
        ).toByteArray()
        assertFalse(corrupt.contentEquals(packageBytes("minimal-blank")))

        for (bytes in listOf(packageBytes("higher-schema-reject"), corrupt)) {
            assertThrows(NovelError::class.java) {
                NovelWorkspaceProjectImport.importProject(bytes, repository, now)
            }
            assertTrue(root.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun restoreEpochCapturedBeforeDispatchPreventsPackageAndZipInstallation() {
        val repository = NovelWorkspaceProjectRepository(temporary.newFolder("workspace"))
        val original = repository.createBlank("恢复后的书", now = now)
        val zip = NovelWorkspaceExchange.exportZipBytes(original.projectDirectory)
        val before = NovelWorkspaceStore(original.projectDirectory).fileTree()
        val callerEpoch = NovelWorkspaceRestoreBoundary.currentEpoch()
        NovelWorkspaceRestoreBoundary.beginRestore()
        NovelWorkspaceRestoreBoundary.finishRestore()

        for (bytes in listOf(packageBytes("minimal-blank"), zip)) {
            assertThrows(NovelWorkspaceRestoreCancelled::class.java) {
                NovelWorkspaceProjectImport.importProject(bytes, repository, now, restoreEpoch = callerEpoch)
            }
        }
        assertEquals(1, repository.listProjects().size)
        assertEquals(before, NovelWorkspaceStore(original.projectDirectory).fileTree())
    }

    private fun packageBytes(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("novel-v1/packages/$name.ambernovel.json"))
            .use { it.readBytes() }
}
