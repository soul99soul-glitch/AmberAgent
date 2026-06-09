package app.amber.agent.data.db.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardTaskEntityTest {
    @Test
    fun stable_id_ignores_board_date() {
        val first = stableBoardTaskId("notification", "same-ref")
        val second = stableBoardTaskId("notification", "same-ref")

        assertEquals(first, second)
        assertEquals(32, first.length)
    }

    @Test
    fun stable_id_changes_with_source_identity() {
        val first = stableBoardTaskId("notification", "same-ref")
        val second = stableBoardTaskId("calendar", "same-ref")

        assertNotEquals(first, second)
    }

    @Test
    fun task_state_labels_cover_dispatch_flow() {
        assertEquals("已经派发", boardTaskChipForState(BoardTaskState.IN_PROGRESS))
        assertEquals("等待确认", boardTaskChipForState(BoardTaskState.WAITING_USER))
        assertEquals("遇到阻碍", boardTaskChipForState(BoardTaskState.BLOCKED))
        assertTrue(BoardTaskState.rolling.contains(BoardTaskState.IN_PROGRESS))
    }
}
