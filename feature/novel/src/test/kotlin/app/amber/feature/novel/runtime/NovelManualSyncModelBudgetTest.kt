package app.amber.feature.novel.runtime

import app.amber.feature.novel.DefaultNovelCreation
import app.amber.feature.novel.NovelIntent
import app.amber.feature.novel.NovelQuery
import app.amber.feature.novel.NovelRunEvent
import app.amber.feature.novel.NovelRunKindRequest
import app.amber.feature.novel.NovelRunRequest
import app.amber.feature.novel.NovelSessionModeRequest
import app.amber.feature.novel.NovelSnapshot
import app.amber.feature.novel.manualSyncInputBudget
import app.amber.feature.novel.model.NovelChapterId
import app.amber.feature.novel.model.NovelCheckpointId
import app.amber.feature.novel.model.NovelCollectionTarget
import app.amber.feature.novel.model.NovelOperationId
import app.amber.feature.novel.model.NovelOutcome
import app.amber.feature.novel.model.NovelProjectCreationMode
import app.amber.feature.novel.model.NovelProjectModelPolicy
import app.amber.feature.novel.model.NovelRunId
import app.amber.feature.novel.model.NovelStateSnapshotId
import app.amber.feature.novel.persistence.NovelFileProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelManualSyncModelBudgetTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun inputBudgetReservesOutputAndMarginFromKnownModelWindow() {
        assertEquals(16_000, manualSyncInputBudget(null))
        assertEquals(16_000, manualSyncInputBudget(128_000))
        assertEquals(10_784, manualSyncInputBudget(20_000))
        assertTrue(runCatching { manualSyncInputBudget(9_216) }.isFailure)
    }

    @Test
    fun strictManualSyncSendsChunkWithDeterministicBoundedRequest() = runBlocking {
        val requests = mutableListOf<NovelModelRequest>()
        val model = object : NovelModelRunning {
            override suspend fun resolveModel(policy: NovelProjectModelPolicy): NovelResolvedModel =
                NovelResolvedModel(
                    providerID = "provider",
                    ownerProviderID = "provider",
                    modelID = "model",
                    wireModelID = "model",
                    displayName = "Model",
                    contextWindowTokens = 20_000,
                )

            override fun start(request: NovelModelRequest): Flow<NovelModelEvent> = flow {
                requests += request
                val text = when (request.purpose) {
                    NovelModelPurpose.Prose -> "主角在祭坛下找到失落的信物。"
                    NovelModelPurpose.StateExtraction -> "not-json"
                    NovelModelPurpose.StateRebuild -> STATE_REBUILD_JSON
                    else -> "unused"
                }
                emit(NovelModelEvent.TextDelta(text))
                emit(NovelModelEvent.Completed)
            }

            override fun cancel(runId: NovelRunId) = Unit
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val creation = DefaultNovelCreation(
            repository = NovelFileProjectRepository(temp.newFolder("manual-sync-budget")),
            modelRunning = model,
            appScope = scope,
        )
        try {
            val created = creation.perform(
                NovelIntent.CreateProject("Manual sync budget", NovelProjectCreationMode.Blank),
            ) as NovelOutcome.ProjectCreated
            val run = creation.start(
                NovelRunRequest(
                    projectId = created.projectID,
                    branchId = created.branchID,
                    userText = "写第一章",
                    mode = NovelSessionModeRequest.WriteProse,
                    kind = NovelRunKindRequest.Prose,
                ),
            )
            run.events.filterIsInstance<NovelRunEvent.Completed>().first()
            val candidate = (creation.snapshot(
                NovelQuery.Project(created.projectID),
            ) as NovelSnapshot.Project).document.candidates.single()
            creation.perform(
                NovelIntent.CollectCandidate(
                    projectId = created.projectID,
                    branchId = created.branchID,
                    candidateId = candidate.id,
                    selectedText = candidate.content,
                    target = NovelCollectionTarget.CreateNextChapter(
                        chapterID = NovelChapterId.generate(),
                        title = "第一章",
                    ),
                    runStateDelta = true,
                ),
            )

            val beforeSync = (creation.snapshot(
                NovelQuery.Project(created.projectID),
            ) as NovelSnapshot.Project).document
            val beforeSyncBranch = beforeSync.branches.first { it.id == created.branchID }
            val operationID = NovelOperationId.generate()
            val checkpointID = NovelCheckpointId.generate()
            val stateSnapshotID = NovelStateSnapshotId.generate()
            val syncIntent = NovelIntent.SyncManualEdits(
                projectId = created.projectID,
                branchId = created.branchID,
                failClosed = true,
                operationId = operationID,
                newCheckpointId = checkpointID,
                newStateSnapshotId = stateSnapshotID,
                expectedProjectRevision = beforeSync.project.revision,
                expectedConfigRevision = beforeSync.project.configRevision,
                expectedBranchHeadRevision = beforeSyncBranch.headRevision,
            )
            val outcome = creation.perform(syncIntent)
            val afterFirst = (creation.snapshot(
                NovelQuery.Project(created.projectID),
            ) as NovelSnapshot.Project).document
            val replayedOutcome = creation.perform(syncIntent)
            val afterReplay = (creation.snapshot(
                NovelQuery.Project(created.projectID),
            ) as NovelSnapshot.Project).document

            assertTrue(outcome is NovelOutcome.ManualSyncCommitted)
            assertEquals(outcome, replayedOutcome)
            assertEquals(afterFirst, afterReplay)
            assertEquals(1, afterReplay.appliedOperations.count { it.operationID == operationID })
            assertEquals(1, afterReplay.checkpoints.count { it.id == checkpointID })
            assertEquals(1, afterReplay.stateSnapshots.count { it.id == stateSnapshotID })
            val request = requests.single { it.purpose == NovelModelPurpose.StateRebuild }
            assertEquals(0.0, request.parameters.temperature)
            assertEquals(1.0, request.parameters.topP)
            assertEquals(8_192, request.parameters.maxOutputTokens)
            assertEquals("auto", request.parameters.reasoningLevel)
            assertTrue(request.messages.single { it.role == NovelModelMessage.Role.System }
                .content.contains("主角在祭坛下找到失落的信物。"))
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        val STATE_REBUILD_JSON = """
            {
              "schemaVersion":1,
              "stateSummary":"主角找回了失落的信物",
              "events":[{
                "id":"event-1",
                "kind":"fact",
                "summary":"主角找回信物",
                "entityReferences":[],
                "evidence":"主角在祭坛下找到失落的信物"
              }],
              "characterChanges":[],
              "relationshipChanges":[],
              "foreshadowingChanges":[],
              "unresolvedEntityNames":[],
              "branchOutlinePatch":null,
              "settingProposals":[]
            }
        """.trimIndent()
    }
}
