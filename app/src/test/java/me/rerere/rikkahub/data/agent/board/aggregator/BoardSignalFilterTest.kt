package me.rerere.rikkahub.data.agent.board.aggregator

import me.rerere.rikkahub.data.agent.board.BoardSignalSourceType
import me.rerere.rikkahub.data.db.entity.BoardSignalEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardSignalFilterTest {
    @Test
    fun timeSignalsNeverSurfaceEvenWithHighScore() {
        val scored = ScoredSignal(signal(BoardSignalSourceType.TIME), score = 99)

        assertFalse(shouldSurfaceBoardSignal(scored))
    }

    @Test
    fun chatHistoryNeedsMinimumScore() {
        assertFalse(shouldSurfaceBoardSignal(ScoredSignal(signal(BoardSignalSourceType.CHAT_HISTORY), score = 3)))
        assertTrue(shouldSurfaceBoardSignal(ScoredSignal(signal(BoardSignalSourceType.CHAT_HISTORY), score = 4)))
    }

    @Test
    fun hardMutedSignalsAreFiltered() {
        val scored = ScoredSignal(signal(BoardSignalSourceType.NOTIFICATION), score = -10)

        assertFalse(shouldSurfaceBoardSignal(scored))
    }

    @Test
    fun filterBatchKeepsConsideredIdsForDroppedSignals() {
        val time = ScoredSignal(signal(BoardSignalSourceType.TIME), score = 99)
        val notification = ScoredSignal(signal(BoardSignalSourceType.NOTIFICATION), score = 6)

        val batch = filterBoardSignals(listOf(time, notification))

        assertTrue(batch.surfaced.map { it.signal.id } == listOf(notification.signal.id))
        assertTrue(batch.consideredSignalIds.contains(time.signal.id))
        assertTrue(batch.consideredSignalIds.contains(notification.signal.id))
    }

    private fun signal(sourceType: String): BoardSignalEntity = BoardSignalEntity(
        id = "$sourceType-id",
        sourceType = sourceType,
        sourceRef = "$sourceType-ref",
        title = "title",
        content = "content",
        contentHash = "$sourceType-hash",
        signalTime = 1L,
        createdAt = 1L,
    )
}
