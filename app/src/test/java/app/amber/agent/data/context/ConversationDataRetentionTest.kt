package app.amber.agent.data.context

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.agent.data.db.dao.ConversationDAO
import app.amber.agent.data.db.entity.ConversationCompactEntity
import app.amber.agent.data.db.fts.MessageFtsManager
import app.amber.core.context.CompactSummaryNormalizer
import app.amber.core.context.CompactSummaryPayloads
import app.amber.core.context.ConversationContextRepository
import app.amber.core.files.FilesManager
import app.amber.core.infra.AppScope
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.repository.ConversationRepository
import app.amber.core.repository.FilesRepository
import app.amber.core.repository.LightConversationEntity
import app.amber.core.sync.core.SyncArchiveManager
import app.amber.feature.runtime.DurableRuntimeTestBase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4 / A4.3 - data retention.
 *
 * Compaction must be a *projection*: the original transcript rows stay intact and expandable, the
 * compact row is additive, backup carries both, and deleting a conversation takes the compact with
 * it through the declared FK cascade (not by leaving an orphan summary behind).
 */
class ConversationDataRetentionTest : DurableRuntimeTestBase() {

    private fun repository(): ConversationRepository {
        val conversations = database.conversationDao()
        val summaries = object : ConversationDAO by conversations {
            override suspend fun getConversationSummaryById(id: String): LightConversationEntity? {
                val row = conversations.getConversationById(id) ?: return null
                return LightConversationEntity(
                    id = row.id,
                    assistantId = row.assistantId,
                    title = row.title,
                    isPinned = row.isPinned,
                    createAt = row.createAt,
                    updateAt = row.updateAt,
                    messageCount = database.messageNodeDao().countNodesOfConversation(id),
                    lastMessagePreview = "",
                )
            }
        }
        val files = FilesManager(context, FilesRepository(database.managedFileDao()), AppScope())
        return ConversationRepository(
            summaries, database.messageNodeDao(), database.messageStatsDao(), database.favoriteDao(),
            database, files, MessageFtsManager(database),
        )
    }

    private fun contextRepository(repo: ConversationRepository) = ConversationContextRepository(
        compactDAO = database.conversationCompactDao(),
        eventDAO = database.conversationContextEventDao(),
        conversationRepository = repo,
    )

    private suspend fun seed(repo: ConversationRepository, count: Int = 6): Conversation {
        val messages = (1..count).map { i ->
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("原始消息 $i 的完整内容")))
        }
        val conversation = Conversation(assistantId = AMBER_AGENT_ID, messageNodes = messages.map(MessageNode::of))
        repo.insertConversation(conversation)
        return repo.getConversationById(conversation.id)!!
    }

    private suspend fun insertCompact(
        repo: ConversationRepository,
        conversation: Conversation,
        id: String = "compact-1",
        status: String = "completed",
        sourceIds: List<String> = conversation.currentMessages.map { it.id.toString() },
    ) {
        contextRepository(repo).insertCompact(
            app.amber.core.context.ConversationCompact(
                id = id,
                conversationId = conversation.id.toString(),
                summary = CompactSummaryNormalizer.fallbackPlainTextSummaryJson(
                    summary = "已压缩",
                    sourceMessageIds = sourceIds,
                    createdAt = 1_000L,
                ),
                level = 1,
                sourceStartIndex = 0,
                sourceEndIndex = conversation.currentMessages.lastIndex.coerceAtLeast(0),
                sourceMessageIds = sourceIds,
                tokenEstimate = 42,
                createdAt = 1_000L,
                updatedAt = 1_000L,
                status = status,
            )
        )
    }

    @Test
    fun compactionIsAdditiveAndNeverOverwritesTheOriginalTranscript() = runBlocking {
        val repo = repository()
        val conversation = seed(repo, count = 6)
        val before = repo.getConversationById(conversation.id)!!
        val beforeIds = before.currentMessages.map { it.id.toString() }
        val beforeTexts = before.currentMessages.map { it.toText() }

        insertCompact(repo, before)

        val after = repo.getConversationById(conversation.id)!!
        assertEquals("message count must not shrink", beforeIds.size, after.currentMessages.size)
        assertEquals("message ids must not be rewritten", beforeIds, after.currentMessages.map { it.id.toString() })
        assertEquals("原始 transcript 内容被覆盖", beforeTexts, after.currentMessages.map { it.toText() })

        // The compact is an additional row, not a replacement.
        assertEquals(1, contextRepository(repo).getCompacts(conversation.id).size)
    }

    @Test
    fun expandStillReturnsTheOriginalTextAfterCompaction() = runBlocking {
        val repo = repository()
        val conversation = seed(repo, count = 4)
        val persisted = repo.getConversationById(conversation.id)!!
        insertCompact(repo, persisted)

        val expanded = contextRepository(repo).expandResult(conversation.id, "compact-1", radius = 0)
        assertEquals(
            "compaction must keep原始消息可展开",
            persisted.currentMessages.map { it.id.toString() },
            expanded.messages.map { it.id.toString() },
        )
        assertTrue(expanded.messages.first().toText().contains("原始消息 1"))
        assertTrue("sources are still present, so not stale", !expanded.stale)
    }

    @Test
    fun backupTablesCarryBothTheTranscriptAndTheCompact() {
        val tables = SyncArchiveManager.SYNC_TABLES.toSet()
        listOf(
            "conversationentity",
            "message_node",
            "conversation_compact",
            "conversation_context_event",
        ).forEach { table ->
            assertTrue("$table must be in the sync/backup table list", table in tables)
        }
        // Restoring while preserving local chats must preserve the compacts too: otherwise the
        // surviving messages would be summarized by summaries that no longer exist, or worse,
        // an imported archive's compacts could point at messages that were not restored.
        assertTrue("conversation_compact" in SyncArchiveManager.CONVERSATION_TABLES)
        assertTrue("conversation_context_event" in SyncArchiveManager.CONVERSATION_TABLES)
        assertTrue("message_node" in SyncArchiveManager.CONVERSATION_TABLES)
    }

    @Test
    fun deletingAConversationDeletesItsCompactRowsThroughTheCascade() = runBlocking {
        val repo = repository()
        val conversation = seed(repo, count = 3)
        insertCompact(repo, repo.getConversationById(conversation.id)!!)

        assertEquals(1, contextRepository(repo).getCompacts(conversation.id).size)
        repo.deleteConversation(conversation, deferCleanup = true)

        assertTrue(
            "compact rows must not outlive their conversation",
            contextRepository(repo).getCompacts(conversation.id).isEmpty(),
        )
        assertEquals(
            "context events must not outlive their conversation",
            0,
            database.conversationContextEventDao()
                .getRecentEvents(conversation.id.toString(), limit = 50)
                .size,
        )
    }

    @Test
    fun compactPayloadKeepsSourceMessageIdsSoTheOriginalCanAlwaysBeFound() = runBlocking {
        val repo = repository()
        val conversation = seed(repo, count = 5)
        val persisted = repo.getConversationById(conversation.id)!!
        val sourceIds = persisted.currentMessages.map { it.id.toString() }
        insertCompact(repo, persisted, sourceIds = sourceIds)

        val compact = contextRepository(repo).getCompacts(conversation.id).single()
        assertEquals(sourceIds, compact.sourceMessageIds)
        val payload = CompactSummaryPayloads.parse(compact.summary)
        assertNotNull(payload)
        assertEquals(sourceIds, payload?.sourceMessageIds)
        // Both the compact and the transcript survive, so a follow-up search can cite either.
        assertTrue(compact.summary.contains("已压缩"))
    }
}
