package app.amber.feature.ui.pages.sessionhome

import androidx.paging.PagingData
import app.amber.core.model.Conversation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHomeConversationStreamTest {

    @Test
    fun `paging stream keeps the emitted page and reports the error`() = runTest {
        val conversation = Conversation.ofId(kotlin.uuid.Uuid.random()).copy(title = "Saved")
        var error: Throwable? = null
        val pages = mutableListOf<PagingData<Conversation>>()
        observeConversationPaging(
            source = {
                flow {
                    emit(PagingData.from(listOf(conversation)))
                    throw IllegalStateException("database unavailable")
                }
            },
            onError = { error = it },
        ).collect { pages += it }

        assertEquals(1, pages.size)
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun `paging construction error reports without emitting an empty replacement`() = runTest {
        var error: Throwable? = null
        val pages = mutableListOf<PagingData<Conversation>>()
        observeConversationPaging(
            source = { throw IllegalStateException("database unavailable") },
            onError = { error = it },
        ).collect { pages += it }

        assertTrue(error is IllegalStateException)
        assertTrue(pages.isEmpty())
    }
}
