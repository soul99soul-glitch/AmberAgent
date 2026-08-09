package app.amber.feature.novel.runtime

import app.amber.feature.novel.DefaultNovelCreation
import app.amber.feature.novel.NovelGenerationGranularityRequest
import app.amber.feature.novel.NovelIntent
import app.amber.feature.novel.NovelQuery
import app.amber.feature.novel.NovelRunEvent
import app.amber.feature.novel.NovelRunKindRequest
import app.amber.feature.novel.NovelRunRequest
import app.amber.feature.novel.NovelSessionModeRequest
import app.amber.feature.novel.NovelSnapshot
import app.amber.feature.novel.domain.NovelContinuityAuditV1
import app.amber.feature.novel.domain.NovelContinuityIssueCategoryV1
import app.amber.feature.novel.domain.NovelContinuityIssueSeverityV1
import app.amber.feature.novel.domain.NovelContinuityIssueV1
import app.amber.feature.novel.domain.NovelContinuityReferenceV1
import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelCandidateId
import app.amber.feature.novel.model.NovelCandidateStatus
import app.amber.feature.novel.model.NovelChapterId
import app.amber.feature.novel.model.NovelCollectionTarget
import app.amber.feature.novel.model.NovelOutcome
import app.amber.feature.novel.model.NovelProjectCreationMode
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelProjectModelPolicy
import app.amber.feature.novel.model.NovelRunId
import app.amber.feature.novel.persistence.NovelFileProjectRepository
import java.util.ArrayDeque
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelCandidateContinuityAuditTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun chunkedAudit_countsProviderAndDecodeFailuresAndUsesDeterministicReviewParameters() = runBlocking {
        val candidateContent = "候选证据：银钥匙已经遗失。"
        val issue = issue(
            id = "candidate-key",
            references = listOf(
                reference(1, "第一章", "第一章证据"),
                reference(4, "候选下一章", "候选证据"),
            ),
        )
        val model = FakeModel(
            prose = listOf(
                "第一章证据。" + "甲".repeat(16_000),
                "第二章证据。" + "乙".repeat(16_000),
                "第三章证据。" + "丙".repeat(16_000),
                candidateContent,
            ),
            audits = listOf(
                AuditStep.Text(auditJson(issue)),
                AuditStep.Text("not-json"),
                AuditStep.ProviderFailure,
            ),
        )
        val fixture = prepare(
            model,
            canonicalTitles = listOf("第一章", "第二章", "第三章"),
            oneShotTimeoutMillis = 1_000,
        )

        val report = fixture.creation.continuityAuditIncludingCandidate(
            fixture.projectId,
            fixture.branchId,
            fixture.candidateId,
        )

        assertEquals(2, report.failedChunkCount)
        assertEquals(listOf(issue.copy(id = "chunk-1:candidate-key")), report.issues)
        assertEquals(3, model.auditRequests.size)
        assertTrue(
            model.auditRequests.all { request ->
                request.messages.last().content.contains("# Chapter 4: 候选下一章") &&
                    request.parameters.temperature == 0.0 &&
                    request.parameters.topP == 1.0
            },
        )
    }

    @Test
    fun hallucinatedEvidenceFailsTheWholeChunkClosed() = runBlocking {
        val model = FakeModel(
            prose = listOf("林舟把银钥匙放进抽屉。", "林舟说银钥匙已经遗失。"),
            audits = listOf(
                AuditStep.Text(
                    auditJson(
                        issue(
                            id = "fake-evidence",
                            references = listOf(
                                reference(1, "第一章", "银钥匙放进抽屉"),
                                reference(2, "候选下一章", "候选中不存在的句子"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val fixture = prepare(model, listOf("第一章"), oneShotTimeoutMillis = 1_000)

        val report = fixture.creation.continuityAuditIncludingCandidate(
            fixture.projectId,
            fixture.branchId,
            fixture.candidateId,
        )

        assertEquals(1, report.failedChunkCount)
        assertTrue(report.issues.isEmpty())
    }

    @Test
    fun canonicalOnlyBlockingIssueIsReportedSeparatelyFromCandidateRewriteIssues() = runBlocking {
        val candidateIssue = issue(
            id = "candidate-key",
            references = listOf(
                reference(1, "第一章", "钥匙在抽屉"),
                reference(3, "候选下一章", "钥匙沉在河底"),
            ),
        )
        val canonicalIssue = issue(
            id = "canonical-key",
            references = listOf(
                reference(1, "第一章", "钥匙在抽屉"),
                reference(2, "第二章", "钥匙已被烧毁"),
            ),
        )
        val model = FakeModel(
            prose = listOf("钥匙在抽屉。", "钥匙已被烧毁。", "钥匙沉在河底。"),
            audits = listOf(AuditStep.Text(auditJson(candidateIssue, canonicalIssue))),
        )
        val fixture = prepare(model, listOf("第一章", "第二章"), oneShotTimeoutMillis = 1_000)

        val report = fixture.creation.continuityAuditIncludingCandidate(
            fixture.projectId,
            fixture.branchId,
            fixture.candidateId,
        )

        assertEquals(0, report.failedChunkCount)
        assertEquals(listOf(candidateIssue.copy(id = "chunk-1:candidate-key")), report.issues)
        assertEquals(
            listOf(canonicalIssue.copy(id = "chunk-1:canonical-key")),
            report.canonicalOnlyBlockingIssues,
        )
    }

    @Test
    fun oversizedCanonicalChapterIsCountedAsFailedWithoutSendingAnUnsafeRequest() = runBlocking {
        val model = FakeModel(
            prose = listOf("超长章节。" + "长".repeat(60_000), "候选正文。"),
            audits = emptyList(),
        )
        val fixture = prepare(model, listOf("第一章"), oneShotTimeoutMillis = 1_000)

        val report = fixture.creation.continuityAuditIncludingCandidate(
            fixture.projectId,
            fixture.branchId,
            fixture.candidateId,
        )

        assertEquals(1, report.failedChunkCount)
        assertTrue(report.issues.isEmpty())
        assertTrue(model.auditRequests.isEmpty())
    }

    @Test
    fun neverEndingProviderFlowTimesOutAndFailsTheChunkClosed() = runBlocking {
        val model = FakeModel(
            prose = listOf("第一章正文。", "候选正文。"),
            audits = listOf(AuditStep.Never),
        )
        val fixture = prepare(model, listOf("第一章"), oneShotTimeoutMillis = 25)

        val report = fixture.creation.continuityAuditIncludingCandidate(
            fixture.projectId,
            fixture.branchId,
            fixture.candidateId,
        )

        assertEquals(1, report.failedChunkCount)
        assertTrue(report.issues.isEmpty())
    }

    private suspend fun prepare(
        model: FakeModel,
        canonicalTitles: List<String>,
        oneShotTimeoutMillis: Long,
    ): Fixture {
        val repository = NovelFileProjectRepository(temp.newFolder("novel"))
        val creation = DefaultNovelCreation(
            repository = repository,
            modelRunning = model,
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            oneShotTimeoutMillis = oneShotTimeoutMillis,
        )
        val created = creation.perform(
            NovelIntent.CreateProject("Continuity", NovelProjectCreationMode.Blank),
        ) as NovelOutcome.ProjectCreated

        canonicalTitles.forEach { title ->
            generateCandidate(creation, created.projectID, created.branchID)
            val snapshot = creation.snapshot(NovelQuery.Project(created.projectID)) as NovelSnapshot.Project
            val candidate = snapshot.document.candidates.last { it.status == NovelCandidateStatus.Available }
            creation.perform(
                NovelIntent.CollectCandidate(
                    projectId = created.projectID,
                    branchId = created.branchID,
                    candidateId = candidate.id,
                    selectedText = candidate.content,
                    target = NovelCollectionTarget.CreateNextChapter(NovelChapterId.generate(), title),
                    runStateDelta = false,
                ),
            )
        }
        generateCandidate(creation, created.projectID, created.branchID)
        val snapshot = creation.snapshot(NovelQuery.Project(created.projectID)) as NovelSnapshot.Project
        val candidate = snapshot.document.candidates.last { it.status == NovelCandidateStatus.Available }
        return Fixture(creation, created.projectID, created.branchID, candidate.id)
    }

    private suspend fun generateCandidate(
        creation: DefaultNovelCreation,
        projectId: NovelProjectId,
        branchId: NovelBranchId,
    ) {
        creation.start(
            NovelRunRequest(
                projectId = projectId,
                branchId = branchId,
                userText = "写下一章",
                mode = NovelSessionModeRequest.WriteProse,
                granularity = NovelGenerationGranularityRequest.WholeChapter,
                kind = NovelRunKindRequest.Prose,
            ),
        ).events.filterIsInstance<NovelRunEvent.Completed>().first()
    }

    private data class Fixture(
        val creation: DefaultNovelCreation,
        val projectId: NovelProjectId,
        val branchId: NovelBranchId,
        val candidateId: NovelCandidateId,
    )

    private sealed interface AuditStep {
        data class Text(val value: String) : AuditStep
        data object ProviderFailure : AuditStep
        data object Never : AuditStep
    }

    private class FakeModel(
        prose: List<String>,
        audits: List<AuditStep>,
        private val contextWindowTokens: Int? = null,
    ) : NovelModelRunning {
        private val proseQueue = ArrayDeque(prose)
        private val auditQueue = ArrayDeque(audits)
        val auditRequests: MutableList<NovelModelRequest> = Collections.synchronizedList(mutableListOf())

        override suspend fun resolveModel(policy: NovelProjectModelPolicy): NovelResolvedModel = NovelResolvedModel(
            providerID = "fake-provider",
            ownerProviderID = "fake-provider",
            modelID = "fake-model",
            wireModelID = "fake-model",
            displayName = "Fake",
            contextWindowTokens = contextWindowTokens,
        )

        override fun start(request: NovelModelRequest): Flow<NovelModelEvent> = flow {
            when (request.purpose) {
                NovelModelPurpose.Prose -> {
                    emit(NovelModelEvent.TextDelta(proseQueue.removeFirst()))
                    emit(NovelModelEvent.Completed)
                }
                NovelModelPurpose.ContinuityAudit -> {
                    auditRequests += request
                    when (val step = auditQueue.removeFirst()) {
                        is AuditStep.Text -> {
                            emit(NovelModelEvent.TextDelta(step.value))
                            emit(NovelModelEvent.Completed)
                        }
                        AuditStep.ProviderFailure -> emit(
                            NovelModelEvent.Failed("fake", "provider failed", isRetryable = true),
                        )
                        AuditStep.Never -> awaitCancellation()
                    }
                }
                else -> error("Unexpected model purpose: ${request.purpose}")
            }
        }

        override fun cancel(runId: NovelRunId) = Unit
    }

    companion object {
        private val JSON = Json { encodeDefaults = true }

        private fun auditJson(vararg issues: NovelContinuityIssueV1): String = JSON.encodeToString(
            NovelContinuityAuditV1(
                schemaVersion = 1,
                consistent = issues.isEmpty(),
                issues = issues.toList(),
            ),
        )

        private fun issue(
            id: String,
            references: List<NovelContinuityReferenceV1>,
        ) = NovelContinuityIssueV1(
            id = id,
            category = NovelContinuityIssueCategoryV1.Contradiction,
            severity = NovelContinuityIssueSeverityV1.Blocking,
            summary = "银钥匙状态冲突",
            references = references,
        )

        private fun reference(
            ordinal: Int,
            title: String,
            evidence: String,
        ) = NovelContinuityReferenceV1(ordinal, title, evidence)
    }
}
