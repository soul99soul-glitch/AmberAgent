package me.rerere.rikkahub.data.agent.board

import me.rerere.rikkahub.data.db.entity.BoardItemEntity
import me.rerere.rikkahub.ui.pages.board.visibleTodayReviewTodoItems
import org.junit.Assert.assertEquals
import org.junit.Test

class BoardPageTodoCompatibilityTest {
    @Test
    fun todayReviewKeepsLegacyActionItemsVisible() {
        val visible = visibleTodayReviewTodoItems(
            listOf(
                item("todo", category = "todo"),
                item("legacy", category = "action"),
                item("info", category = "info"),
                item("dismissed", category = "action", status = "dismissed"),
            )
        )

        assertEquals(listOf("todo", "legacy"), visible.map { it.id })
    }

    private fun item(
        id: String,
        category: String,
        status: String = "active",
    ): BoardItemEntity = BoardItemEntity(
        id = id,
        title = id,
        sourceType = BoardSignalSourceType.NOTIFICATION,
        sourceRef = id,
        sourceContent = "content",
        urgency = "medium",
        category = category,
        reason = "reason",
        suggestion = "suggestion",
        signalTime = 1L,
        status = status,
        boardDate = "2026-05-19",
        createdAt = 1L,
    )
}
