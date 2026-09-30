package app.amber.feature.ui.pages.chat

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageAnnotation
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ChatShelfItemsTest {
    @Test
    fun `collects current-branch artifacts newest first without duplicates`() {
        val first = MessageNode(
            messages = listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Document(url = "file:///a/spec.pdf", fileName = "spec.pdf")),
                ),
            ),
        )
        val tool = UIMessagePart.Tool(
            toolCallId = "t1",
            toolName = "generate_image",
            input = "{}",
            output = listOf(UIMessagePart.Image(url = "file:///img/cat.png")),
        )
        val second = MessageNode(
            messages = listOf(
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("old variant"), UIMessagePart.Image("file:///img/hidden.png"))),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(tool, UIMessagePart.Image(url = "file:///img/cat.png")),
                    annotations = listOf(UIMessageAnnotation.UrlCitation(title = "Docs", url = "https://example.com")),
                ),
            ),
            selectIndex = 1,
        )
        val items = Conversation(assistantId = Uuid.random(), messageNodes = listOf(first, second)).collectShelfItems()
        assertEquals(
            listOf(ShelfItemKind.IMAGE, ShelfItemKind.WEB, ShelfItemKind.FILE),
            items.map { it.kind },
        )
        assertEquals(listOf("cat.png", "Docs", "spec.pdf"), items.map { it.label })
        assertEquals(second.id, items[0].nodeId)
    }

    @Test
    fun `inline data images get no label and duplicates keep the newest message`() {
        val older = MessageNode(messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Image("file:///x/a.png?v=1")))))
        val newer = MessageNode(
            messages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Image("data:image/png;base64,AAAA"), UIMessagePart.Image("file:///x/a.png?v=1")),
                ),
            ),
        )
        val items = Conversation(assistantId = Uuid.random(), messageNodes = listOf(older, newer)).collectShelfItems()
        assertEquals(listOf("", "a.png"), items.map { it.label })
        assertEquals(listOf(newer.id, newer.id), items.map { it.nodeId })
        assertEquals(newer.messages[0].id.toString(), items[0].messageId)
    }
}
