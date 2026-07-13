package app.amber.feature.novel.runtime

import app.amber.feature.novel.DefaultNovelCreation
import app.amber.feature.novel.NovelIntent
import app.amber.feature.novel.NovelQuery
import app.amber.feature.novel.NovelRunEvent
import app.amber.feature.novel.NovelRunKindRequest
import app.amber.feature.novel.NovelRunRequest
import app.amber.feature.novel.NovelSessionModeRequest
import app.amber.feature.novel.NovelSnapshot
import app.amber.feature.novel.model.NovelCollectionTarget
import app.amber.feature.novel.model.NovelOutcome
import app.amber.feature.novel.model.NovelProjectCreationMode
import app.amber.feature.novel.model.NovelChapterId
import app.amber.feature.novel.model.NovelCandidateStatus
import app.amber.feature.novel.persistence.NovelFileProjectRepository
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelGenerationLifecycleTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun creation(scripts: Map<NovelModelPurpose, String> = emptyMap()): DefaultNovelCreation {
        val repo = NovelFileProjectRepository(temp.newFolder("novel"))
        val model = ScriptedNovelModelAdapter(
            scripts = scripts,
            defaultText = "Once upon a time.",
            chunkSize = 8,
        )
        return DefaultNovelCreation(repo, model, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    @Test
    fun proseRun_producesCandidate_withoutChangingManuscript() = runBlocking {
        val nc = creation()
        val created = nc.perform(
            NovelIntent.CreateProject("T", NovelProjectCreationMode.Blank),
        ) as NovelOutcome.ProjectCreated
        val run = nc.start(
            NovelRunRequest(
                projectId = created.projectID,
                branchId = created.branchID,
                userText = "Write",
                mode = NovelSessionModeRequest.WriteProse,
                granularity = app.amber.feature.novel.NovelGenerationGranularityRequest.WholeChapter,
                kind = NovelRunKindRequest.Prose,
            ),
        )
        val completed = run.events.filterIsInstance<NovelRunEvent.Completed>().first()
        assertTrue(completed.content.isNotBlank())
        val snap = nc.snapshot(NovelQuery.Project(created.projectID)) as NovelSnapshot.Project
        assertEquals(0, snap.document.chapters.size)
        assertEquals(1, snap.document.candidates.size)
        assertEquals(NovelCandidateStatus.Available, snap.document.candidates.first().status)
    }

    @Test
    fun collectCandidate_createsChapterAndCheckpoint() = runBlocking {
        val nc = creation()
        val created = nc.perform(
            NovelIntent.CreateProject("T2", NovelProjectCreationMode.Blank),
        ) as NovelOutcome.ProjectCreated
        val run = nc.start(
            NovelRunRequest(
                projectId = created.projectID,
                branchId = created.branchID,
                userText = "Write",
                mode = NovelSessionModeRequest.WriteProse,
                kind = NovelRunKindRequest.Prose,
            ),
        )
        run.events.filterIsInstance<NovelRunEvent.Completed>().first()
        val snap1 = nc.snapshot(NovelQuery.Project(created.projectID)) as NovelSnapshot.Project
        val candidate = snap1.document.candidates.first()
        val outcome = nc.perform(
            NovelIntent.CollectCandidate(
                projectId = created.projectID,
                branchId = created.branchID,
                candidateId = candidate.id,
                selectedText = candidate.content,
                target = NovelCollectionTarget.CreateNextChapter(
                    chapterID = NovelChapterId.generate(),
                    title = "第1章",
                ),
                runStateDelta = false,
            ),
        )
        assertTrue(outcome is NovelOutcome.CandidateCollected)
        val snap2 = nc.snapshot(NovelQuery.Project(created.projectID)) as NovelSnapshot.Project
        assertEquals(1, snap2.document.chapters.size)
        assertEquals(NovelCandidateStatus.Collected, snap2.document.candidates.first().status)
    }

    @Test
    fun quickStart_emitsExactlyFourProposals() = runBlocking {
        val json = """
            {"schemaVersion":1,"overview":"O","world":{"title":"W","content":"Wc"},
            "characters":{"title":"C","content":"Cc"},"masterOutline":{"title":"M","content":"Mc"},
            "writingRequirements":{"title":"R","content":"Rc"}}
        """.trimIndent()
        val nc = creation(mapOf(NovelModelPurpose.QuickStart to json))
        val created = nc.perform(
            NovelIntent.CreateProject(
                name = "QS",
                mode = NovelProjectCreationMode.QuickStart,
                quickStartSeed = app.amber.feature.novel.model.NovelQuickStartSeed("fantasy", "hero"),
            ),
        ) as NovelOutcome.ProjectCreated
        val run = nc.start(
            NovelRunRequest(
                projectId = created.projectID,
                branchId = created.branchID,
                userText = "start",
                mode = NovelSessionModeRequest.DiscussPlan,
                kind = NovelRunKindRequest.QuickStart,
            ),
        )
        run.events.filterIsInstance<NovelRunEvent.Completed>().first()
        val snap = nc.snapshot(NovelQuery.Project(created.projectID)) as NovelSnapshot.Project
        val proposals = snap.document.settingProposals.filter { !it.isResolved }
        assertEquals(4, proposals.size)
        assertEquals(1, proposals.count {
            it.origin is app.amber.feature.novel.model.NovelSettingProposalOrigin.QuickStart &&
                (it.origin as app.amber.feature.novel.model.NovelSettingProposalOrigin.QuickStart)
                    .suggestedKind is app.amber.feature.novel.model.NovelMaterialKind.Character
        })
    }
}
