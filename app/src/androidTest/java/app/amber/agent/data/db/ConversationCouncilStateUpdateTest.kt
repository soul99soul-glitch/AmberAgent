package app.amber.agent.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.agent.data.db.entity.ConversationEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationCouncilStateUpdateTest {
    @Test
    fun chat_metadata_update_preserves_council_checkpoint() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
        try {
            val councilState = """{"host_assistant_id":"profile","objective":"keep-me"}"""
            database.conversationDao().insert(
                ConversationEntity(
                    id = "conversation-1",
                    assistantId = "7def1f55-3dd9-4a09-a95a-7d0c2554b346",
                    title = "History",
                    nodes = "[]",
                    createAt = 1L,
                    updateAt = 2L,
                    chatSuggestions = "[]",
                    isPinned = false,
                    councilState = councilState,
                )
            )

            database.conversationDao().updatePreservingCouncilState(
                id = "conversation-1",
                assistantId = "7def1f55-3dd9-4a09-a95a-7d0c2554b346",
                title = "Updated history",
                nodes = "[]",
                createAt = 1L,
                updateAt = 3L,
                chatSuggestions = "[]",
                isPinned = true,
                autoApproveToolCalls = false,
            )

            val updated = database.conversationDao().getConversationById("conversation-1")!!
            assertEquals("Updated history", updated.title)
            assertTrue(updated.isPinned)
            assertEquals(councilState, updated.councilState)
        } finally {
            database.close()
        }
    }
}
