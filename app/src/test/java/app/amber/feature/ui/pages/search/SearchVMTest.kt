package app.amber.feature.ui.pages.search

import app.amber.agent.data.db.fts.MessageSearchResult
import app.amber.agent.data.db.fts.SearchHitSource
import app.amber.core.model.Conversation
import app.amber.feature.ui.pages.sessionhome.filterHomeConversations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SearchVMTest {

    private fun result(
        id: String,
        source: SearchHitSource,
        titleMatched: Boolean = source == SearchHitSource.TITLE,
    ) = MessageSearchResult(
        nodeId = if (source == SearchHitSource.BODY) "node-$id" else null,
        messageId = if (source == SearchHitSource.BODY) "message-$id" else null,
        conversationId = "conversation-$id",
        title = "Title $id",
        updateAt = Instant.EPOCH,
        snippet = "[$id]",
        hitSource = source,
        titleMatched = titleMatched,
    )

    @Test
    fun `all filter keeps title and body FTS hits`() {
        val results = listOf(
            result("title", SearchHitSource.TITLE),
            result("body", SearchHitSource.BODY),
            result("both", SearchHitSource.BODY, titleMatched = true),
        )

        assertEquals(results, filterSearchResults(results, SearchFilter.ALL))
    }

    @Test
    fun `conversation filter keeps title hits including body title merges`() {
        val results = listOf(
            result("title", SearchHitSource.TITLE),
            result("body", SearchHitSource.BODY),
            result("both", SearchHitSource.BODY, titleMatched = true),
        )

        assertEquals(
            listOf("conversation-title", "conversation-both"),
            filterSearchResults(results, SearchFilter.CONVERSATIONS).map { it.conversationId },
        )
    }

    @Test
    fun `message filter keeps body hits including body title merges`() {
        val results = listOf(
            result("title", SearchHitSource.TITLE),
            result("body", SearchHitSource.BODY),
            result("both", SearchHitSource.BODY, titleMatched = true),
        )

        assertEquals(
            listOf("conversation-body", "conversation-both"),
            filterSearchResults(results, SearchFilter.MESSAGES).map { it.conversationId },
        )
    }

    @Test
    fun `blank query shows recent sessions for all and conversation filters only`() {
        assertTrue(shouldShowRecentConversations("", SearchFilter.ALL))
        assertTrue(shouldShowRecentConversations("  ", SearchFilter.CONVERSATIONS))
        assertFalse(shouldShowRecentConversations("", SearchFilter.MESSAGES))
        assertFalse(shouldShowRecentConversations("topic", SearchFilter.ALL))
    }

    @Test
    fun `home filter is local title filtering and keeps source order`() {
        val conversations = listOf(
            Conversation.ofId(kotlin.uuid.Uuid.random()).copy(title = "Alpha notes"),
            Conversation.ofId(kotlin.uuid.Uuid.random()).copy(title = "Beta plan"),
            Conversation.ofId(kotlin.uuid.Uuid.random()).copy(title = "ALPHA follow-up"),
        )

        assertEquals(
            listOf("Alpha notes", "ALPHA follow-up"),
            filterHomeConversations(conversations, " alpha ", "New conversation").map { it.title },
        )
    }

    @Test
    fun `home filter can find an untitled conversation by its placeholder`() {
        val conversations = listOf(
            Conversation.ofId(kotlin.uuid.Uuid.random()),
            Conversation.ofId(kotlin.uuid.Uuid.random()).copy(title = "A named session"),
        )

        assertEquals(
            listOf(conversations.first().id),
            filterHomeConversations(conversations, "new conversation", "New conversation")
                .map { it.id },
        )
    }
}
