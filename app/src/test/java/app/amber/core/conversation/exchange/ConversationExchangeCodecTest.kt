package app.amber.core.conversation.exchange

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.utils.JsonInstant
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class ConversationExchangeCodecTest {
    private val assistantId = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
    private val conversationId = Uuid.parse("11111111-1111-1111-1111-111111111111")

    @Test
    fun `stored zip keeps branches nested tool output and file references`() {
        val document = Conversation(
            id = conversationId,
            assistantId = assistantId,
            title = "cross-platform",
            messageNodes = listOf(
                MessageNode(
                    id = Uuid.parse("22222222-2222-2222-2222-222222222222"),
                    messages = listOf(
                        UIMessage.user("first"),
                        UIMessage.user("edited branch"),
                    ),
                    selectIndex = 1,
                ),
                MessageNode(
                    messages = listOf(
                        UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(
                                UIMessagePart.Tool(
                                    toolCallId = "call-1",
                                    toolName = "file_read",
                                    input = "{\"path\":\"notes.md\"}",
                                    output = listOf(
                                        UIMessagePart.Document(
                                            url = "file:///AmberAgent/attachments/notes.md",
                                            fileName = "notes.md",
                                        ),
                                        UIMessagePart.Text("loaded"),
                                    ),
                                    streamIndex = 2,
                                ),
                                UIMessagePart.Text("done"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val bytes = ConversationExchangeCodec.encode(listOf(document))
        ZipInputStream(bytes.inputStream()).use { zip ->
            val entry = zip.nextEntry ?: error("missing conversation entry")
            assertEquals(ZipEntry.STORED, entry.method)
        }

        val decoded = ConversationExchangeCodec.decode(bytes)
        assertEquals(listOf(conversationId), decoded.conversations.map { it.id })
        assertEquals(2, decoded.conversations.single().messageNodes.size)
        assertEquals(1, decoded.conversations.single().messageNodes.first().selectIndex)
        val tool = decoded.conversations.single().messageNodes[1]
            .messages.single().parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals(2, tool.streamIndex)
        assertEquals(1, tool.output.filterIsInstance<UIMessagePart.Document>().size)
        assertEquals(setOf("file:///AmberAgent/attachments/notes.md"), decoded.attachmentReferences)
    }

    @Test
    fun `ios thread sidecar is canonicalized and allows an existing parent`() {
        val edge = ConversationExchangeThreadEdge(
            childThreadId = conversationId.toString().uppercase(),
            parentThreadId = "33333333-3333-3333-3333-333333333333",
            agentPath = "root/child",
            forkTurns = "all",
            status = "active",
            createdAt = 123L,
        )

        val decoded = ConversationExchangeCodec.decode(
            ConversationExchangeCodec.encode(listOf(sampleConversation()), listOf(edge)),
        )
        assertEquals(1, decoded.threadEdges?.size)
        assertEquals(conversationId.toString(), decoded.threadEdges?.single()?.childThreadId)
        assertEquals("33333333-3333-3333-3333-333333333333", decoded.threadEdges?.single()?.parentThreadId)
    }

    @Test
    fun `inline attachment data is rejected before writing an archive`() {
        val conversation = sampleConversation().copy(
            messageNodes = listOf(
                MessageNode.of(
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Image("data:image/png;base64,AAAA")),
                    ),
                ),
            ),
        )
        assertFailsWith<ConversationExchangeException> {
            ConversationExchangeCodec.encode(listOf(conversation))
        }
    }

    @Test
    fun `non enabled ios memory mode is rejected instead of silently changing semantics`() {
        val fields = JsonInstant
            .parseToJsonElement(JsonInstant.encodeToString(Conversation.serializer(), sampleConversation()))
            .jsonObject
            .toMutableMap()
            .apply { put("memoryMode", JsonPrimitive("polluted")) }
        val bytes = zip(
            "$conversationId.json",
            JsonObject(fields).toString(),
        )
        assertFailsWith<ConversationExchangeException> { ConversationExchangeCodec.decode(bytes) }
    }

    @Test
    fun `unsafe and mismatched entries are rejected`() {
        val unsafe = zip("nested/conversation.json", "{}")
        assertFailsWith<ConversationExchangeException> { ConversationExchangeCodec.decode(unsafe) }

        val mismatched = zip(
            "44444444-4444-4444-4444-444444444444.json",
            JsonInstant.encodeToString(Conversation.serializer(), sampleConversation()),
        )
        assertFailsWith<ConversationExchangeException> { ConversationExchangeCodec.decode(mismatched) }
    }

    @Test
    fun `thread sidecar rejects cycles`() {
        val first = sampleConversation()
        val second = first.copy(id = Uuid.parse("33333333-3333-3333-3333-333333333333"))
        val edges = listOf(
            edge(first.id, second.id),
            edge(second.id, first.id),
        )
        assertFailsWith<ConversationExchangeException> {
            ConversationExchangeCodec.encode(listOf(first, second), edges)
        }
    }

    private fun sampleConversation() = Conversation(
        id = conversationId,
        assistantId = assistantId,
        title = "sample",
        messageNodes = listOf(MessageNode.of(UIMessage.user("hello"))),
    )

    private fun edge(child: Uuid, parent: Uuid) = ConversationExchangeThreadEdge(
        childThreadId = child.toString(),
        parentThreadId = parent.toString(),
        agentPath = "",
        forkTurns = "all",
        status = "active",
        createdAt = 0,
    )

    private fun zip(name: String, value: String): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(value.toByteArray())
            zip.closeEntry()
        }
        output.toByteArray()
    }
}
