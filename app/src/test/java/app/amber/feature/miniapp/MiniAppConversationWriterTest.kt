package app.amber.feature.miniapp

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.entity.ConversationEntity
import app.amber.ai.ui.UIMessagePart
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P3-03 tests (plan §P3-03 测试):
 * - 写入 composer 成功 receipt (conversationId + draft itemId + status).
 * - 会话不存在 → 结构化 conversation_not_found 失败.
 * - 未授权发送被拒 (writer keeps the draft and reports send_rejected).
 * - 附件仅允许 sandbox 边界内的 data:/https URL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MiniAppConversationWriterTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() = Unit

    private fun inMemoryDb(): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private fun storeOf(db: AppDatabase) = ConversationDraftStore(
        dao = db.conversationDraftDao(),
        conversationDao = db.conversationDao(),
    )

    private suspend fun insertConversation(db: AppDatabase, id: String) {
        db.conversationDao().insert(
            ConversationEntity(
                id = id,
                assistantId = "assistant-test",
                title = "Target conversation",
                nodes = "[]",
                createAt = System.currentTimeMillis(),
                updateAt = System.currentTimeMillis(),
                chatSuggestions = "[]",
                isPinned = false,
            )
        )
    }

    private fun writerOf(db: AppDatabase, sendResult: Boolean) = MiniAppConversationWriter(
        draftStore = storeOf(db),
        sendMessage = { _, _ -> sendResult },
    )

    @Test
    fun writeDraftReturnsReceiptAndPersistsComposerDraft() = runBlocking {
        val db = inMemoryDb()
        insertConversation(db, "conv-1")
        val store = storeOf(db)
        val writer = MiniAppConversationWriter(store) { _, _ -> true }

        val receipt = writer.writeDraft("conv-1", "小应用写回的内容", emptyList())

        assertEquals("conv-1", receipt.conversationId)
        assertTrue(receipt.itemId.isNotBlank())
        assertEquals("drafted", receipt.status)

        val draft = store.load("conv-1")
        assertNotNull(draft)
        assertEquals("小应用写回的内容", draft!!.text)
        assertEquals(receipt.itemId, draft.draftId)
        val parts = draft.toParts()
        assertEquals(listOf(UIMessagePart.Text("小应用写回的内容")), parts)
    }

    @Test
    fun writeDraftToMissingConversationFailsStructured() = runBlocking {
        val db = inMemoryDb()
        val writer = writerOf(db, sendResult = true)

        try {
            writer.writeDraft("conv-missing", "hi", emptyList())
            fail("Expected MiniAppBridgeException")
        } catch (e: MiniAppBridgeException) {
            assertEquals("conversation_not_found", e.code)
        }
    }

    @Test
    fun writeAndSendAcceptedClearsDraft() = runBlocking {
        val db = inMemoryDb()
        insertConversation(db, "conv-2")
        val store = storeOf(db)
        val writer = MiniAppConversationWriter(store) { _, _ -> true }

        val receipt = writer.writeAndSend("conv-2", "发送内容", emptyList())

        assertEquals("sent", receipt.status)
        assertEquals(receipt.itemId, receipt.itemId)
        // Draft consumed after a real send.
        assertNull(store.load("conv-2"))
    }

    @Test
    fun writeAndSendRejectedKeepsDraft() = runBlocking {
        val db = inMemoryDb()
        insertConversation(db, "conv-3")
        val store = storeOf(db)
        val writer = MiniAppConversationWriter(store) { _, _ -> false }

        val receipt = writer.writeAndSend("conv-3", "未能发送的内容", emptyList())

        assertEquals("send_rejected", receipt.status)
        assertNotNull(store.load("conv-3"))
    }

    @Test
    fun rejectedSendIsDistinguishableFromPlainDraft() = runBlocking {
        val db = inMemoryDb()
        insertConversation(db, "conv-4")
        val store = storeOf(db)
        val writer = MiniAppConversationWriter(store) { _, _ -> false }

        val draftReceipt = writer.writeDraft("conv-4", "草稿内容", emptyList())
        val sendReceipt = writer.writeAndSend("conv-4", "未能发送的内容", emptyList())

        // A rejected send must not masquerade as a successful draft write —
        // the MiniApp can tell "drafted" (user requested a draft) from
        // "send_rejected" (user requested a send that was blocked).
        assertEquals("drafted", draftReceipt.status)
        assertEquals("send_rejected", sendReceipt.status)
        assertNotNull(store.load("conv-4"))
    }

    @Test
    fun attachmentsOnlyAllowSandboxBoundaryUrls() {
        val attachments = MiniAppConversationWriter.parseAttachments(
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("kind", "image")
                        put("url", "data:image/png;base64,AAAA")
                    }
                )
                add(
                    buildJsonObject {
                        put("kind", "document")
                        put("url", "https://example.com/doc.pdf")
                        put("name", "doc.pdf")
                        put("mime", "application/pdf")
                    }
                )
                add(
                    buildJsonObject {
                        put("kind", "image")
                        put("url", "file:///etc/passwd")
                    }
                )
                add(
                    buildJsonObject {
                        put("kind", "image")
                        put("url", "content://media/1")
                    }
                )
                add(JsonPrimitive("not-an-object"))
            }
        )

        assertEquals(2, attachments.size)
        assertTrue(attachments[0] is UIMessagePart.Image)
        assertEquals("https://example.com/doc.pdf", (attachments[1] as UIMessagePart.Document).url)
        assertEquals("application/pdf", (attachments[1] as UIMessagePart.Document).mime)
    }
}
