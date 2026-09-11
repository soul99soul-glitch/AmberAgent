package app.amber.feature.home

import app.amber.feature.runtime.RunTerminal
import app.amber.feature.runtime.RunTerminalState
import app.amber.feature.runtime.ToolEffect
import app.amber.feature.runtime.ToolEffectStatus
import app.amber.feature.tools.ToolEffectClass
import app.amber.agent.data.db.dao.ToolEffectConversationRow
import app.amber.agent.data.db.entity.ToolEffectEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationContinueSourceTest {
    @Test
    fun `active image effect projects to its owning chat`() {
        val run = runTerminal(state = RunTerminalState.RUNNING)
        val candidate = imageGenerationContinueCandidates(
            runs = listOf(run),
            effectsByRun = mapOf(run.runId to listOf(effect(ToolEffectStatus.STARTED))),
            existingConversationIds = setOf(run.conversationId),
        ).single()

        assertEquals(ContinueSourceKind.IMAGE_GENERATION, candidate.sourceKind)
        assertEquals(
            ContinueRoute.ImageGeneration(
                conversationId = run.conversationId,
                messageId = "message-image",
                toolCallId = "call-image",
            ),
            candidate.route,
        )
        assertEquals(ContinueStatus.FAILED_RESUMABLE, candidate.status)
        assertTrue(candidate.isRunning)
        assertEquals("Generating", candidate.summary)
    }

    @Test
    fun `unknown image outcome becomes an approval candidate`() {
        val run = runTerminal(state = RunTerminalState.OUTCOME_UNKNOWN)
        val candidate = imageGenerationContinueCandidates(
            runs = listOf(run),
            effectsByRun = mapOf(run.runId to listOf(effect(ToolEffectStatus.OUTCOME_UNKNOWN))),
            existingConversationIds = setOf(run.conversationId),
        ).single()

        assertEquals(ContinueStatus.WAITING_USER, candidate.status)
        assertFalse(candidate.isRunning)
        assertEquals("Waiting for confirmation", candidate.summary)
    }

    @Test
    fun `non-image and terminal effects are not projected`() {
        val run = runTerminal(state = RunTerminalState.RUNNING)
        val effects = listOf(
            effect(ToolEffectStatus.FINISHED, toolName = "search_web"),
            effect(ToolEffectStatus.FINISHED),
        )
        assertTrue(
            imageGenerationContinueCandidates(
                runs = listOf(run),
                effectsByRun = mapOf(run.runId to effects),
                existingConversationIds = setOf(run.conversationId),
            ).isEmpty(),
        )
    }

    @Test
    fun `missing conversation is not projected`() {
        val run = runTerminal(state = RunTerminalState.RUNNING)

        assertTrue(
            imageGenerationContinueCandidates(
                runs = listOf(run),
                effectsByRun = mapOf(run.runId to listOf(effect(ToolEffectStatus.STARTED))),
                existingConversationIds = emptySet(),
            ).isEmpty(),
        )
    }

    @Test
    fun `recent completed image result keeps message and tool call anchors`() {
        val candidate = imageGenerationCompletedContinueCandidates(
            listOf(
                ToolEffectConversationRow(
                    effect = effectEntity(ToolEffectStatus.FINISHED),
                    conversationId = "conversation-image",
                )
            )
        ).single()

        assertEquals(ContinueStatus.DRAFT, candidate.status)
        assertEquals(
            ContinueRoute.ImageGeneration(
                conversationId = "conversation-image",
                messageId = "message-image",
                toolCallId = "call-image",
            ),
            candidate.route,
        )
        assertEquals("最近生成的图片", candidate.summary)
    }

    private fun runTerminal(state: RunTerminalState) = RunTerminal(
        runId = "run-image",
        conversationId = "11111111-1111-1111-1111-111111111111",
        assistantId = null,
        state = state,
        pauseReason = null,
        startedAtMs = 1_000L,
        updatedAtMs = 2_000L,
        finishedAtMs = null,
    )

    private fun effect(
        status: ToolEffectStatus,
        toolName: String = "generate_image",
    ) = ToolEffect(
        effectId = "effect-$toolName-$status",
        runId = "run-image",
        turnId = 0,
        toolCallId = "call-image",
        toolName = toolName,
        argsDigest = "digest",
        approvalDigest = null,
        effectClass = ToolEffectClass.NON_IDEMPOTENT_WRITE,
        status = status,
        startedAtMs = 1_000L,
        finishedAtMs = if (status == ToolEffectStatus.FINISHED) 2_000L else null,
        resultSummary = null,
        resultPayload = null,
        errorCategory = null,
        messagePersistenceCursor = "message-image",
    )

    private fun effectEntity(status: ToolEffectStatus) = ToolEffectEntity(
        effectId = "effect-finished",
        runId = "run-image",
        turnId = 0,
        toolCallId = "call-image",
        toolName = "generate_image",
        argsDigest = "digest",
        approvalDigest = null,
        effectClass = ToolEffectClass.NON_IDEMPOTENT_WRITE.name,
        status = status.name,
        startedAtMs = 1_000L,
        finishedAtMs = 2_000L,
        resultSummary = "{\"status\":\"ok\"}",
        resultPayload = null,
        errorCategory = null,
        messagePersistenceCursor = "message-image",
        createdAtMs = 1_000L,
        updatedAtMs = 2_000L,
    )
}
