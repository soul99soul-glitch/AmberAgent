package app.amber.feature.novel.domain

import app.amber.feature.novel.model.NovelBranchCheckpointRecord
import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelChapterId
import app.amber.feature.novel.model.NovelChapterRecord
import app.amber.feature.novel.model.NovelChapterSelection
import app.amber.feature.novel.model.NovelChapterVersionId
import app.amber.feature.novel.model.NovelChapterVersionKind
import app.amber.feature.novel.model.NovelChapterVersionRecord
import app.amber.feature.novel.model.NovelCheckpointId
import app.amber.feature.novel.model.NovelCheckpointKind
import app.amber.feature.novel.model.NovelInjectionMode
import app.amber.feature.novel.model.NovelMaterialId
import app.amber.feature.novel.model.NovelMaterialKind
import app.amber.feature.novel.model.NovelMaterialRevisionId
import app.amber.feature.novel.model.NovelMessageId
import app.amber.feature.novel.model.NovelOperationId
import app.amber.feature.novel.model.NovelOutcome
import app.amber.feature.novel.model.NovelProjectCreationMode
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelProjectLoadAccess
import app.amber.feature.novel.model.NovelProjectModelPolicy
import app.amber.feature.novel.model.NovelSessionCursor
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
        assertEquals(null, afterPolicy.document.project.stateSyncModelPolicy)
        assertEquals(
            NovelProjectModelPolicy.Fixed("provider-1", "model-1"),
            afterPolicy.document.project.effectiveStateSyncModelPolicy(),
        )
    }

    @Test
    fun setStateSyncModelPolicy_independentOfWritingModel() {
        val create = NovelReducer.createProject(createCommand(name = "Dual Model"))
        val writing = NovelReducer.setModelPolicy(
            NovelSetModelPolicyCommand(
                context = NovelMutationContext(
                    operationID = NovelOperationId.generate(),
                    expectedProjectRevision = 1,
                    expectedConfigRevision = 1,
                ),
                projectID = create.document.project.id,
                policy = NovelProjectModelPolicy.Fixed("p-write", "m-write"),
                purpose = NovelModelPolicyPurpose.Creation,
            ),
            create.document,
        )
        val sync = NovelReducer.setModelPolicy(
            NovelSetModelPolicyCommand(
                context = NovelMutationContext(
                    operationID = NovelOperationId.generate(),
                    expectedProjectRevision = writing.document.project.revision,
                    expectedConfigRevision = writing.document.project.configRevision,
                ),
                projectID = create.document.project.id,
                policy = NovelProjectModelPolicy.Fixed("p-sync", "m-sync"),
                purpose = NovelModelPolicyPurpose.StateSync,
            ),
            writing.document,
        )
        assertEquals(
            NovelProjectModelPolicy.Fixed("p-write", "m-write"),
            sync.document.project.modelPolicy,
        )
        assertEquals(
            NovelProjectModelPolicy.Fixed("p-sync", "m-sync"),
            sync.document.project.stateSyncModelPolicy,
        )
        assertEquals(
            NovelProjectModelPolicy.Fixed("p-sync", "m-sync"),
            sync.document.project.effectiveStateSyncModelPolicy(),
        )

        val cleared = NovelReducer.clearStateSyncModelPolicy(
            NovelSetModelPolicyCommand(
                context = NovelMutationContext(
                    operationID = NovelOperationId.generate(),
                    expectedProjectRevision = sync.document.project.revision,
                    expectedConfigRevision = sync.document.project.configRevision,
                ),
                projectID = create.document.project.id,
                policy = NovelProjectModelPolicy.Global,
                purpose = NovelModelPolicyPurpose.StateSync,
            ),
            sync.document,
        )
        assertEquals(null, cleared.document.project.stateSyncModelPolicy)
        assertEquals(
            NovelProjectModelPolicy.Fixed("p-write", "m-write"),
            cleared.document.project.effectiveStateSyncModelPolicy(),
        )
    }

    @Test
    fun archiveDiscussion_writesDecisionLogAndAdvancesCursor() {
        val create = NovelReducer.createProject(createCommand(name = "Archive"))
        val projectId = create.document.project.id
        val branch = create.document.branches.first()
        val session = create.document.sessions.first()
        val now = Instant.now()
        val msgUser = app.amber.feature.novel.model.NovelSessionMessageRecord(
            id = NovelMessageId.generate(),
            sequence = 0,
            role = app.amber.feature.novel.model.NovelSessionRole.User,
            mode = app.amber.feature.novel.model.NovelSessionMode.DiscussPlan,
            kind = app.amber.feature.novel.model.NovelSessionMessageKind.UserInput,
            content = "主角动机是什么？",
            createdAt = now,
        )
        val msgAsst = app.amber.feature.novel.model.NovelSessionMessageRecord(
            id = NovelMessageId.generate(),
            sequence = 1,
            role = app.amber.feature.novel.model.NovelSessionRole.Assistant,
            mode = app.amber.feature.novel.model.NovelSessionMode.DiscussPlan,
            kind = app.amber.feature.novel.model.NovelSessionMessageKind.Discussion,
            content = "建议主角为了救赎而行动。",
            createdAt = now,
        )
        val withMsgs = create.document.copy(
            sessions = listOf(session.copy(messages = listOf(msgUser, msgAsst), revision = 2)),
            project = create.document.project.copy(revision = 2),
        )
        val archived = NovelBranchReducer.archiveDiscussion(
            projectId = projectId,
            branchId = branch.id,
            summary = "确定主角动机为救赎",
            decisions = listOf(
                NovelBranchReducer.ArchiveDecision(
                    topic = "主角动机",
                    decision = "为了救赎而行动",
                ),
            ),
            throughSequence = 1,
            expectedProjectRevision = 2,
            expectedBranchHeadRevision = 0,
            document = withMsgs,
        )
        assertTrue(archived.outcome is NovelOutcome.DiscussionArchived)
        val sessionAfter = archived.document.sessions.single()
        assertTrue(sessionAfter.archiveCursor is NovelSessionCursor.Through)
        assertEquals(1L, (sessionAfter.archiveCursor as NovelSessionCursor.Through).sequence)
        assertEquals(1, sessionAfter.discussionArchives.size)
        val decisionMats = archived.document.materials.filter {
            !it.isDeleted && it.kind is NovelMaterialKind.DecisionLog
        }
        assertEquals(1, decisionMats.size)
        val rev = archived.document.materialRevisions.single {
            it.id == decisionMats.single().currentRevisionID
        }
        assertEquals("主角动机", rev.title)
        assertEquals(NovelInjectionMode.Always, rev.injectionMode)

        // Injection planner must skip archived messages and keep DecisionLog.
        val plan = app.amber.feature.novel.runtime.NovelInjectionPlanner.plan(
            document = archived.document,
            branchId = branch.id,
            promptKind = app.amber.feature.novel.runtime.NovelPromptKind.Discussion,
            userText = "继续讨论",
        )
        val sessionSectionLabels = plan.sections
            .filter { it.kind is app.amber.feature.novel.model.NovelInjectionSectionKind.SessionMessage }
            .map { it.label }
        // Both archived messages (seq 0–1) should be below cursor and omitted.
        assertTrue(sessionSectionLabels.none { it.contains("Session") && plan.contextText.contains("主角动机是什么？") })
        assertTrue(!plan.contextText.contains("主角动机是什么？"))
        assertTrue(plan.contextText.contains("为了救赎而行动") || plan.materialDecisions.any { it.included })
        assertTrue(
            plan.materialDecisions.any {
                it.included && archived.document.materials.any { m ->
                    m.id == it.materialID && m.kind is NovelMaterialKind.DecisionLog
                }
            },
        )

        // Undo discussion-archive head rewinds archiveCursor (iOS undoBranchHead parity).
        val undid = NovelBranchReducer.undoHead(
            projectId = projectId,
            branchId = branch.id,
            expectedProjectRevision = archived.document.project.revision,
            expectedBranchHeadRevision = archived.document.branches.single().headRevision,
            document = archived.document,
        )
        val sessionUndone = undid.document.sessions.single()
        assertTrue(sessionUndone.archiveCursor == null)
        assertEquals(
            create.document.branches.single().headCheckpointID,
            undid.document.branches.single().headCheckpointID,
        )
        // DecisionLogs created by the undone archive are soft-deleted (no double inject / pile-up).
        assertTrue(
            undid.document.materials
                .filter { it.kind is NovelMaterialKind.DecisionLog }
                .all { it.isDeleted },
        )
        val planAfterUndo = app.amber.feature.novel.runtime.NovelInjectionPlanner.plan(
            document = undid.document,
            branchId = branch.id,
            promptKind = app.amber.feature.novel.runtime.NovelPromptKind.Discussion,
            userText = "继续讨论",
        )
        assertTrue(planAfterUndo.contextText.contains("主角动机是什么？"))
        assertTrue(
            planAfterUndo.materialDecisions.none {
                it.included && undid.document.materials.any { m ->
                    m.id == it.materialID && m.kind is NovelMaterialKind.DecisionLog && !m.isDeleted
                }
            },
        )
    }

    @Test
    fun restoreChapterVersion_sameFactLineage_succeeds() {
        val create = NovelReducer.createProject(createCommand(name = "Restore lineage"))
        val projectId = create.document.project.id
        val branchId = create.document.project.mainBranchID
        val chapterId = NovelChapterId.generate()
        val version1 = NovelChapterVersionId.generate()
        val factId = java.util.UUID.randomUUID()
        val op1 = NovelOperationId.generate()
        val now = Instant.now()
        val chapter = NovelChapterRecord(chapterId, now)
        val v1 = NovelChapterVersionRecord(
            id = version1,
            chapterID = chapterId,
            kind = NovelChapterVersionKind.Collected,
            title = "第1章",
            content = "v1 body",
            factCompatibilityID = factId,
            createdAt = now,
            operationID = op1,
        )
        val checkpoint1 = NovelBranchCheckpointRecord(
            id = NovelCheckpointId.generate(),
            kind = NovelCheckpointKind.Collection,
            createdOnBranchID = branchId,
            parentCheckpointID = create.document.branches.first().headCheckpointID,
            chapterSelections = listOf(NovelChapterSelection(chapterId, version1)),
            stateSnapshotID = create.document.branches.first().currentStateSnapshotID,
            sessionCursor = NovelSessionCursor.Empty,
            branchOverrideRevisionIDs = emptyList(),
            sourceCandidateID = null,
            baseHeadRevision = 0,
            operationID = op1,
            createdAt = now,
        )
        val branch0 = create.document.branches.first()
        val doc1 = create.document.copy(
            chapters = listOf(chapter),
            chapterVersions = listOf(v1),
            checkpoints = create.document.checkpoints + checkpoint1,
            branches = listOf(
                branch0.copy(
                    headCheckpointID = checkpoint1.id,
                    headRevision = 1,
                    workingRevision = 1,
                    workingChapterSelections = listOf(NovelChapterSelection(chapterId, version1)),
                ),
            ),
            project = create.document.project.copy(revision = 2),
        )
        val version2 = NovelChapterVersionId.generate()
        val op2 = NovelOperationId.generate()
        val v2 = NovelChapterVersionRecord(
            id = version2,
            chapterID = chapterId,
            kind = NovelChapterVersionKind.Polish,
            title = "第1章",
            content = "v2 polished",
            factCompatibilityID = factId,
            sourceChapterVersionID = version1,
            createdAt = now,
            operationID = op2,
        )
        val checkpoint2 = NovelBranchCheckpointRecord(
            id = NovelCheckpointId.generate(),
            kind = NovelCheckpointKind.Polish,
            createdOnBranchID = branchId,
            parentCheckpointID = checkpoint1.id,
            chapterSelections = listOf(NovelChapterSelection(chapterId, version2)),
            stateSnapshotID = branch0.currentStateSnapshotID,
            sessionCursor = NovelSessionCursor.Empty,
            branchOverrideRevisionIDs = emptyList(),
            sourceCandidateID = null,
            baseHeadRevision = 1,
            operationID = op2,
            createdAt = now,
        )
        val doc2 = doc1.copy(
            chapterVersions = doc1.chapterVersions + v2,
            checkpoints = doc1.checkpoints + checkpoint2,
            branches = listOf(
                doc1.branches.first().copy(
                    headCheckpointID = checkpoint2.id,
                    headRevision = 2,
                    workingRevision = 2,
                    workingChapterSelections = listOf(NovelChapterSelection(chapterId, version2)),
                ),
            ),
            project = doc1.project.copy(revision = 3),
        )
        val restored = NovelPolishReducer.restoreChapterVersion(
            projectId = projectId,
            branchId = branchId,
            targetChapterVersionId = version1,
            expectedProjectRevision = 3,
            expectedBranchHeadRevision = 2,
            document = doc2,
        )
        assertTrue(restored.outcome is NovelOutcome.ChapterVersionRestored)
        val head = restored.document.branches.first().workingChapterSelections.single()
        assertTrue(head.versionID != version2)
        val headVersion = restored.document.chapterVersions.first { it.id == head.versionID }
        assertEquals("v1 body", headVersion.content)
        assertEquals(NovelChapterVersionKind.Restore, headVersion.kind)
        assertEquals(version1, headVersion.sourceChapterVersionID)
        assertEquals(factId, headVersion.factCompatibilityID)
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
    fun deleteMaterial_softDeletesAndBlocksFurtherRevise() {
        val create = NovelReducer.createProject(createCommand(name = "Delete Mat"))
        val materialId = NovelMaterialId.generate()
        val revised = NovelReducer.reviseMaterial(
            NovelReviseMaterialCommand(
                context = NovelMutationContext(
                    operationID = NovelOperationId.generate(),
                    expectedProjectRevision = 1,
                    expectedConfigRevision = 1,
                ),
                projectID = create.document.project.id,
                materialID = materialId,
                revisionID = NovelMaterialRevisionId.generate(),
                kind = NovelMaterialKind.Character,
                title = "Hero",
                content = "Brave",
            ),
            create.document,
        )
        val deleted = NovelReducer.deleteMaterial(
            NovelDeleteMaterialCommand(
                context = NovelMutationContext(
                    operationID = NovelOperationId.generate(),
                    expectedProjectRevision = revised.document.project.revision,
                    expectedConfigRevision = revised.document.project.configRevision,
                ),
                projectID = create.document.project.id,
                materialID = materialId,
            ),
            revised.document,
        )
        assertTrue(deleted.outcome is NovelOutcome.MaterialDeleted)
        val material = deleted.document.materials.single { it.id == materialId }
        assertTrue(material.isDeleted)
        assertEquals(
            0,
            deleted.document.materials.count { !it.isDeleted },
        )

        val thrice = runCatching {
            NovelReducer.reviseMaterial(
                NovelReviseMaterialCommand(
                    context = NovelMutationContext(
                        operationID = NovelOperationId.generate(),
                        expectedProjectRevision = deleted.document.project.revision,
                        expectedConfigRevision = deleted.document.project.configRevision,
                    ),
                    projectID = create.document.project.id,
                    materialID = materialId,
                    revisionID = NovelMaterialRevisionId.generate(),
                    kind = NovelMaterialKind.Character,
                    title = "Hero 2",
                    content = "Gone",
                ),
                deleted.document,
            )
        }
        assertTrue(thrice.isFailure)

        val doubleDelete = runCatching {
            NovelReducer.deleteMaterial(
                NovelDeleteMaterialCommand(
                    context = NovelMutationContext(
                        operationID = NovelOperationId.generate(),
                        expectedProjectRevision = deleted.document.project.revision,
                        expectedConfigRevision = deleted.document.project.configRevision,
                    ),
                    projectID = create.document.project.id,
                    materialID = materialId,
                ),
                deleted.document,
            )
        }
        assertTrue(doubleDelete.isFailure)
    }

    @Test
    fun reviseMaterial_updateExisting_appendsSecondRevision() {
        val create = NovelReducer.createProject(createCommand(name = "Update Mat"))
        val materialId = NovelMaterialId.generate()
        val first = NovelReducer.reviseMaterial(
            NovelReviseMaterialCommand(
                context = NovelMutationContext(
                    operationID = NovelOperationId.generate(),
                    expectedProjectRevision = 1,
                    expectedConfigRevision = 1,
                ),
                projectID = create.document.project.id,
                materialID = materialId,
                revisionID = NovelMaterialRevisionId.generate(),
                kind = NovelMaterialKind.World,
                title = "World v1",
                content = "v1",
            ),
            create.document,
        )
        val second = NovelReducer.reviseMaterial(
            NovelReviseMaterialCommand(
                context = NovelMutationContext(
                    operationID = NovelOperationId.generate(),
                    expectedProjectRevision = first.document.project.revision,
                    expectedConfigRevision = first.document.project.configRevision,
                ),
                projectID = create.document.project.id,
                materialID = materialId,
                revisionID = NovelMaterialRevisionId.generate(),
                kind = NovelMaterialKind.World,
                title = "World v2",
                content = "v2",
            ),
            first.document,
        )
        assertEquals(1, second.document.materials.size)
        assertEquals(2, second.document.materialRevisions.size)
        val mat = second.document.materials.single()
        val head = second.document.materialRevisions.single { it.id == mat.currentRevisionID }
        assertEquals("World v2", head.title)
        assertEquals("v2", head.content)
        assertEquals(2L, head.revision)
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
