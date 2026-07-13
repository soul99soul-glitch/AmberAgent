package app.amber.feature.novel.domain

import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelCheckpointId
import app.amber.feature.novel.model.NovelMaterialId
import app.amber.feature.novel.model.NovelMaterialKind
import app.amber.feature.novel.model.NovelMaterialRevisionId
import app.amber.feature.novel.model.NovelOperationId
import app.amber.feature.novel.model.NovelOutcome
import app.amber.feature.novel.model.NovelProjectCreationMode
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelProjectLoadAccess
import app.amber.feature.novel.model.NovelProjectModelPolicy
import app.amber.feature.novel.model.NovelSessionId
import app.amber.feature.novel.model.NovelStateSnapshotId
import app.amber.feature.novel.persistence.NovelFileProjectRepository
import app.amber.feature.novel.serialization.NovelSwiftCompatibleJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class NovelReducerRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun createProject_validatesAndPersists() = runBlocking {
        val command = createCommand(name = "My Novel")
        val now = Instant.parse("2023-11-15T00:00:00Z")
        val result = NovelReducer.createProject(command, now)
        assertTrue(result.outcome is NovelOutcome.ProjectCreated)
        assertEquals(1, result.document.project.revision)
        assertEquals("My Novel", result.document.project.name)

        val repo = NovelFileProjectRepository(tempFolder.newFolder("novel-root"))
        val loaded = repo.createProject(result.document)
        assertEquals(NovelProjectLoadAccess.ReadWrite, loaded.access)
        val listed = repo.listProjects()
        assertEquals(1, listed.size)
        assertEquals("My Novel", listed.first().name)

        val reloaded = repo.loadProject(command.projectID)
        // Wire-normalized dates may drop sub-millisecond precision from Instant.now().
        assertEquals(result.document.project.id, reloaded.document.project.id)
        assertEquals(result.document.project.name, reloaded.document.project.name)
        assertEquals(result.document.project.revision, reloaded.document.project.revision)
        assertEquals(result.document.branches.size, reloaded.document.branches.size)
    }

    @Test
    fun renameAndSetModelPolicy_roundTripThroughRepository() = runBlocking {
        val create = NovelReducer.createProject(createCommand(name = "Original"))
        val repo = NovelFileProjectRepository(tempFolder.newFolder("novel-root-2"))
        val stored = repo.createProject(create.document).document

        val rename = NovelReducer.renameProject(
            NovelRenameProjectCommand(
                context = NovelMutationContext(
                    operationID = NovelOperationId.generate(),
                    expectedProjectRevision = 1,
                ),
                projectID = stored.project.id,
                name = "Renamed",
            ),
            stored,
        )
        val afterRename = repo.commitProject(rename.document, expectedRevision = 1)
        assertEquals("Renamed", afterRename.document.project.name)
        assertEquals(2, afterRename.document.project.revision)

        val policy = NovelReducer.setModelPolicy(
            NovelSetModelPolicyCommand(
                context = NovelMutationContext(
                    operationID = NovelOperationId.generate(),
                    expectedProjectRevision = 2,
                    expectedConfigRevision = 1,
                ),
                projectID = create.document.project.id,
                policy = NovelProjectModelPolicy.Fixed("provider-1", "model-1"),
            ),
            afterRename.document,
        )
        val afterPolicy = repo.commitProject(policy.document, expectedRevision = 2)
        assertEquals(
            NovelProjectModelPolicy.Fixed("provider-1", "model-1"),
            afterPolicy.document.project.modelPolicy,
        )
        assertEquals(3, afterPolicy.document.project.revision)
        assertEquals(2, afterPolicy.document.project.configRevision)
    }

    @Test
    fun reviseMaterial_appendsRevision() {
        val create = NovelReducer.createProject(createCommand(name = "Materials"))
        val revise = NovelReducer.reviseMaterial(
            NovelReviseMaterialCommand(
                context = NovelMutationContext(
                    operationID = NovelOperationId.generate(),
                    expectedProjectRevision = 1,
                    expectedConfigRevision = 1,
                ),
                projectID = create.document.project.id,
                materialID = NovelMaterialId.generate(),
                revisionID = NovelMaterialRevisionId.generate(),
                kind = NovelMaterialKind.World,
                title = "World",
                content = "A floating city.",
            ),
            create.document,
        )
        assertEquals(1, revise.document.materials.size)
        assertEquals(1, revise.document.materialRevisions.size)
        assertEquals("World", revise.document.materialRevisions.first().title)
        assertTrue(revise.outcome is NovelOutcome.MaterialRevised)
    }

    @Test
    fun fixtureDocumentsPassStructuralValidation() {
        listOf(
            "novel-v1/projects/minimal-blank.project.json",
            "novel-v1/projects/full-two-branch.project.json",
            "novel-v1/projects/interrupted-pending.project.json",
            "novel-v1/projects/legacy-missing-v1-defaults.project.json",
        ).forEach { path ->
            val bytes = requireNotNull(javaClass.classLoader!!.getResourceAsStream(path)).readBytes()
            val document = NovelSwiftCompatibleJson.decodeProjectDocument(bytes)
            NovelDocumentValidator.validate(document)
        }
    }

    @Test
    fun idempotentRename_replaysOutcome() {
        val create = NovelReducer.createProject(createCommand(name = "Idem"))
        val op = NovelOperationId.generate()
        val first = NovelReducer.renameProject(
            NovelRenameProjectCommand(
                context = NovelMutationContext(op, expectedProjectRevision = 1),
                projectID = create.document.project.id,
                name = "Once",
            ),
            create.document,
        )
        val second = NovelReducer.renameProject(
            NovelRenameProjectCommand(
                context = NovelMutationContext(op, expectedProjectRevision = 1),
                projectID = create.document.project.id,
                name = "Once",
            ),
            first.document,
        )
        assertEquals(first.outcome, second.outcome)
        assertEquals(first.document.project.revision, second.document.project.revision)
    }

    private fun createCommand(name: String) = NovelCreateProjectCommand(
        context = NovelMutationContext(operationID = NovelOperationId.generate()),
        projectID = NovelProjectId.generate(),
        branchID = NovelBranchId.generate(),
        sessionID = NovelSessionId.generate(),
        initialStateSnapshotID = NovelStateSnapshotId.generate(),
        initialCheckpointID = NovelCheckpointId.generate(),
        name = name,
        creationMode = NovelProjectCreationMode.Blank,
    )
}
