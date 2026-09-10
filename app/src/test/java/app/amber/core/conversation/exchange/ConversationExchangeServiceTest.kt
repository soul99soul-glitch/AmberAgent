package app.amber.core.conversation.exchange

import android.content.ContentValues
import app.amber.ai.ui.UIMessage
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.feature.runtime.DurableRuntimeTestBase
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ConversationExchangeServiceTest : DurableRuntimeTestBase() {
    private val conversationId = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val assistantId = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")

    @Test
    fun `preview and import replace messages while preserving council checkpoint`() = runBlocking {
        val repository = conversationRepository()
        val existing = conversation(title = "old", prompt = "old message")
        repository.insertConversation(existing)
        database.conversationDao().updateCouncilState(
            id = conversationId.toString(),
            councilState = "checkpoint",
            updatedAt = existing.updateAt.toEpochMilli(),
        )

        val incoming = existing.copy(
            title = "imported",
            messageNodes = listOf(MessageNode.of(UIMessage.user("new message"))),
        )
        val service = ConversationExchangeService(repository, SyncRestoreWriteGate())
        val bytes = ConversationExchangeCodec.encode(listOf(incoming))
        val preview = service.inspect(bytes)

        assertEquals(1, preview.conversationCount)
        assertEquals(0, preview.newConversationCount)
        assertEquals(setOf(conversationId), preview.conflictIds)

        val result = service.import(
            bytes = bytes,
            overwriteExisting = true,
            expectedConflictIds = preview.conflictIds,
        )

        assertEquals(1, result.importedCount)
        assertEquals(1, result.overwrittenCount)
        val reloaded = repository.getConversationById(conversationId)!!
        assertEquals("imported", reloaded.title)
        assertEquals("new message", reloaded.currentMessages.single().toText())
        assertEquals("checkpoint", database.conversationDao().getCouncilState(conversationId.toString()))
    }

    @Test
    fun `import rejects a row created after preview before gate check`() = runBlocking {
        val repository = conversationRepository()
        val incoming = conversation(title = "incoming", prompt = "incoming message")
        val gate = SyncRestoreWriteGate()
        val service = ConversationExchangeService(repository, gate)
        val bytes = ConversationExchangeCodec.encode(listOf(incoming))
        val preview = service.inspect(bytes)
        assertTrue(preview.conflicts.isEmpty())

        val registration = gate.addRestoreLifecycleListener(
            onStarted = {
                database.openHelper.writableDatabase.insert(
                    "conversationentity",
                    android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                    ContentValues().apply {
                        put("id", conversationId.toString())
                        put("assistant_id", assistantId.toString())
                        put("title", "created during preview")
                        put("nodes", "[]")
                        put("create_at", 0L)
                        put("update_at", 0L)
                        put("suggestions", "[]")
                        put("is_pinned", 0)
                        put("auto_approve_tools", 0)
                        putNull("council_state")
                    },
                )
            },
            onFinished = { _, _ -> },
        )
        val failure = try {
            runCatching {
                service.import(
                    bytes = bytes,
                    overwriteExisting = true,
                    expectedConflictIds = preview.conflictIds,
                )
            }.exceptionOrNull()
        } finally {
            registration.close()
        }

        assertTrue(failure is ConversationExchangeException)
        assertTrue(
            (failure as ConversationExchangeException).message.orEmpty().contains("预览后发生变化"),
        )
        assertEquals(
            "created during preview",
            repository.getConversationById(conversationId)?.title,
        )
    }

    @Test
    fun `thread sidecar is rejected before repository write`() = runBlocking {
        val repository = conversationRepository()
        val conversation = conversation(title = "threaded", prompt = "threaded message")
        val edge = ConversationExchangeThreadEdge(
            childThreadId = conversation.id.toString(),
            parentThreadId = "33333333-3333-3333-3333-333333333333",
            agentPath = "root/child",
            forkTurns = "all",
            status = "active",
            createdAt = 0,
        )
        val service = ConversationExchangeService(repository, SyncRestoreWriteGate())
        val failure = runCatching {
            service.inspect(ConversationExchangeCodec.encode(listOf(conversation), listOf(edge)))
        }.exceptionOrNull()

        assertTrue(failure is ConversationExchangeException)
        assertTrue((failure as ConversationExchangeException).message.orEmpty().contains("线程关系"))
        assertTrue(!repository.existsConversationById(conversation.id))
    }

    private fun conversation(title: String, prompt: String) = Conversation(
        id = conversationId,
        assistantId = assistantId,
        title = title,
        messageNodes = listOf(MessageNode.of(UIMessage.user(prompt))),
    )
}
