package app.amber.feature.board.agent

import app.amber.feature.board.BoardSignalSourceType
import app.amber.feature.board.aggregator.ScoredSignal
import app.amber.agent.data.db.entity.BoardSignalEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardOutputValidatorTest {
    @Test
    fun keepsOnlyRealSourcesAndForcesTodoCategory() {
        val signals = (1..6).map { index -> ScoredSignal(signal("ref-$index", index.toLong()), score = 10) }
        val raw = BoardAgentOutput(
            summary = "summary",
            items = listOf(
                item("ref-1", category = "action", urgency = "high"),
                item("forged", category = "todo", urgency = "high"),
                item("ref-2", category = "attention", urgency = "strange", sourceType = "wrong_type"),
            ),
        )

        val result = BoardOutputValidator.validate(raw, signals)

        assertEquals(listOf("ref-1", "ref-2"), result.output.items.map { it.source_ref })
        assertTrue(result.output.items.all { it.category == "todo" })
        assertEquals(BoardSignalSourceType.NOTIFICATION, result.output.items.first { it.source_ref == "ref-2" }.source_type)
        assertEquals("medium", result.output.items.first { it.source_ref == "ref-2" }.urgency)
        assertEquals("reason", result.output.items.first { it.source_ref == "ref-2" }.reason)
        assertEquals("suggestion", result.output.items.first { it.source_ref == "ref-2" }.suggestion)
        assertEquals(2L, result.output.items.first { it.source_ref == "ref-2" }.signal_time)
        assertTrue(result.warnings.any { it.contains("source_ref not in input") })
        assertTrue(result.warnings.any { it.contains("coerce source_type") })
    }

    @Test
    fun capsTodoAtFiveByUrgencyAndRecency() {
        val signals = (1..8).map { index -> ScoredSignal(signal("ref-$index", index.toLong()), score = 10) }
        val raw = BoardAgentOutput(
            items = (1..8).map { index ->
                item(
                    ref = "ref-$index",
                    urgency = if (index <= 2) "high" else "medium",
                    signalTime = index.toLong(),
                )
            },
        )

        val result = BoardOutputValidator.validate(raw, signals)

        assertEquals(5, result.output.items.size)
        assertEquals(listOf("ref-2", "ref-1", "ref-8", "ref-7", "ref-6"), result.output.items.map { it.source_ref })
        assertTrue(result.warnings.any { it.contains("cap todo") })
    }

    @Test
    fun dropsAmbiguousDuplicateRefsWhenSourceTypeDoesNotMatch() {
        val signals = listOf(
            ScoredSignal(signal("same-ref", 1L, BoardSignalSourceType.NOTIFICATION), score = 10),
            ScoredSignal(signal("same-ref", 2L, BoardSignalSourceType.CALENDAR), score = 10),
        )
        val raw = BoardAgentOutput(items = listOf(item("same-ref", sourceType = "wrong")))

        val result = BoardOutputValidator.validate(raw, signals)

        assertTrue(result.output.items.isEmpty())
        assertTrue(result.warnings.any { it.contains("ambiguous source_ref/source_type") })
    }

    @Test
    fun keepsExactSourceTypeMatchesWhenRefsOverlap() {
        val signals = listOf(
            ScoredSignal(signal("same-ref", 1L, BoardSignalSourceType.NOTIFICATION), score = 10),
            ScoredSignal(signal("same-ref", 2L, BoardSignalSourceType.CALENDAR), score = 10),
        )
        val raw = BoardAgentOutput(
            items = listOf(
                item("same-ref", sourceType = BoardSignalSourceType.NOTIFICATION),
                item("same-ref", sourceType = BoardSignalSourceType.CALENDAR, signalTime = 2L),
            )
        )

        val result = BoardOutputValidator.validate(raw, signals)

        assertEquals(
            listOf(BoardSignalSourceType.CALENDAR, BoardSignalSourceType.NOTIFICATION),
            result.output.items.map { it.source_type },
        )
        assertEquals(2, result.output.items.size)
    }

    private fun item(
        ref: String,
        category: String = "todo",
        urgency: String = "medium",
        signalTime: Long = 1L,
        sourceType: String = BoardSignalSourceType.NOTIFICATION,
    ) = BoardAgentItem(
        title = "待办 $ref",
        source_type = sourceType,
        source_ref = ref,
        urgency = urgency,
        category = category,
        reason = "reason",
        suggestion = "suggestion",
        signal_time = signalTime,
    )

    private fun signal(
        ref: String,
        time: Long,
        sourceType: String = BoardSignalSourceType.NOTIFICATION,
    ): BoardSignalEntity = BoardSignalEntity(
        id = "$ref-id",
        sourceType = sourceType,
        sourceRef = ref,
        title = "title",
        content = "content",
        contentHash = "$ref-hash",
        signalTime = time,
        createdAt = time,
    )
}
