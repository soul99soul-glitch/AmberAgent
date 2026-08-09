package app.amber.feature.novel.runtime

import app.amber.ai.core.MessageRole
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Model
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageChoice
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.novel.DefaultNovelCreation
import app.amber.feature.novel.NovelIntent
import app.amber.feature.novel.NovelQuery
import app.amber.feature.novel.NovelRunEvent
import app.amber.feature.novel.NovelRunKindRequest
import app.amber.feature.novel.NovelRunRequest
import app.amber.feature.novel.NovelSessionModeRequest
import app.amber.feature.novel.NovelSnapshot
import app.amber.feature.novel.model.NovelCandidateStatus
import app.amber.feature.novel.model.NovelOutcome
import app.amber.feature.novel.model.NovelProjectCreationMode
import app.amber.feature.novel.model.NovelProjectModelPolicy
import app.amber.feature.novel.model.NovelRunId
import app.amber.feature.novel.model.NovelRunStatus
import app.amber.feature.novel.persistence.NovelFileProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

class AndroidNovelModelAdapterTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun resolvedModelPropagatesContextWindowForGlobalAndFixedPolicies() {
        val model = Model(
            id = Uuid.random(),
            modelId = "wire-model",
            displayName = "Display model",
            contextWindowTokens = 32_768,
        )

        val global = resolvedNovelModel(model, providerID = "provider", ownerProviderID = "provider")
        val fixed = resolvedNovelModel(model, providerID = "provider", ownerProviderID = "fixed-owner")

        assertEquals(32_768, global.contextWindowTokens)
        assertEquals(32_768, fixed.contextWindowTokens)
        assertEquals("fixed-owner", fixed.ownerProviderID)
    }

    @Test
    fun textGenerationParamsCaptureEverySupportedReasoningLevelAndRejectUnknownValues() {
        val model = Model(modelId = "wire-model")

        ReasoningLevel.entries.forEach { expected ->
            val captured = novelTextGenerationParams(
                model = model,
                parameters = NovelModelParameters(reasoningLevel = expected.name.lowercase()),
            )
            assertEquals(expected, captured.reasoningLevel)
        }
        assertTrue(
            runCatching {
                novelTextGenerationParams(
                    model = model,
                    parameters = NovelModelParameters(reasoningLevel = "unsupported"),
                )
            }.isFailure,
        )
    }

    @Test
    fun partialThenLengthEmitsFailedAndNeverCompleted() = runBlocking {
        val events = novelModelEvents(
            flowOf(
                chunk(text = "partial chapter"),
                chunk(finishReason = "length"),
            ),
        ).toList()

        assertEquals(NovelModelEvent.TextDelta("partial chapter"), events.first())
        val failed = events.filterIsInstance<NovelModelEvent.Failed>().single()
        assertEquals("output_truncated", failed.code)
        assertFalse(events.any { it == NovelModelEvent.Completed })
    }

    @Test
    fun abnormalProviderFinishReasonsAllFailClosed() = runBlocking {
        listOf("max_tokens", "max_output_tokens", "content_filter", "SAFETY", "tool_calls")
            .forEach { reason ->
                val events = novelModelEvents(flowOf(chunk(finishReason = reason))).toList()
                assertTrue("Expected Failed for $reason", events.last() is NovelModelEvent.Failed)
                assertFalse(events.any { it == NovelModelEvent.Completed })
            }

        val normal = novelModelEvents(flowOf(chunk(text = "complete", finishReason = "STOP"))).toList()
        assertEquals(NovelModelEvent.Completed, normal.last())
    }

    @Test
    fun partialLengthFailureCannotCreateAnAvailableCandidate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = object : NovelModelRunning {
            override suspend fun resolveModel(policy: NovelProjectModelPolicy): NovelResolvedModel =
                NovelResolvedModel(
                    providerID = "provider",
                    ownerProviderID = "provider",
                    modelID = "model",
                    wireModelID = "model",
                    displayName = "Model",
                )

            override fun start(request: NovelModelRequest): Flow<NovelModelEvent> =
                novelModelEvents(
                    flowOf(
                        chunk(text = "partial chapter"),
                        chunk(finishReason = "length"),
                    ),
                )

            override fun cancel(runId: NovelRunId) = Unit
        }
        val creation = DefaultNovelCreation(
            repository = NovelFileProjectRepository(temp.newFolder("adapter-finish-reason")),
            modelRunning = model,
            appScope = scope,
        )
        val created = creation.perform(
            NovelIntent.CreateProject("Finish reason", NovelProjectCreationMode.Blank),
        ) as NovelOutcome.ProjectCreated
        val run = creation.start(
            NovelRunRequest(
                projectId = created.projectID,
                branchId = created.branchID,
                userText = "Write",
                mode = NovelSessionModeRequest.WriteProse,
                kind = NovelRunKindRequest.Prose,
            ),
        )

        run.events.filterIsInstance<NovelRunEvent.Failed>().first()
        val snapshot = creation.snapshot(NovelQuery.Project(created.projectID)) as NovelSnapshot.Project
        assertFalse(snapshot.document.candidates.any { it.status == NovelCandidateStatus.Available })
        assertEquals(NovelRunStatus.Failed, snapshot.document.activeRuns.single().status)
        assertEquals("partial chapter", snapshot.document.activeRuns.single().partialContent)
        scope.cancel()
    }

    private fun chunk(
        text: String = "",
        finishReason: String? = null,
    ): MessageChunk = MessageChunk(
        id = "chunk",
        model = "model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = if (text.isEmpty()) emptyList() else listOf(UIMessagePart.Text(text)),
                ),
                message = null,
                finishReason = finishReason,
            ),
        ),
    )
}
