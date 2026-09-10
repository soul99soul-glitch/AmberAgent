package app.amber.feature.ui.pages.sessionhome

import app.amber.core.model.Conversation
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHomeConversationStreamTest {

    @Test
    fun `stream error keeps the last conversation list and reports the error`() = runTest {
        val conversation = Conversation.ofId(kotlin.uuid.Uuid.random()).copy(title = "Saved")
        var error: Throwable? = null
        val state = observeConversationStream(
            source = {
                flow {
                    emit(listOf(conversation))
                    throw IllegalStateException("database unavailable")
                }
            },
            onValue = {},
            onError = { error = it },
        ).stateIn(this, SharingStarted.Eagerly, emptyList())

        advanceUntilIdle()

        assertEquals(listOf(conversation), state.value)
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun `stream construction error reports without emitting an empty replacement`() = runTest {
        var error: Throwable? = null
        val state = observeConversationStream(
            source = { throw IllegalStateException("database unavailable") },
            onValue = {},
            onError = { error = it },
        ).stateIn(this, SharingStarted.Eagerly, listOf(Conversation.ofId(kotlin.uuid.Uuid.random())))

        advanceUntilIdle()

        assertTrue(error is IllegalStateException)
        assertEquals(1, state.value.size)
    }
}
