package app.amber.feature.ui.components.message

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageVirtualItemsTest {
    @Test
    fun virtualContentKeysDoNotUseOriginalPartIndex() {
        val items = buildChatMessageVirtualItems(
            node = MessageNode.of(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Reasoning("thinking", finishedAt = null),
                        UIMessagePart.Text(longMarkdown()),
                    ),
                )
            ),
            assistant = null,
            showAssistantBubble = true,
            loading = false,
            lastMessage = false,
        ).orEmpty()

        val markdownKeys = items
            .filterIsInstance<ChatMessageVirtualItem.MarkdownChild>()
            .map { it.keySuffix }

        assertTrue(markdownKeys.isNotEmpty())
        assertTrue(markdownKeys.all { it.startsWith("content-Text-0-markdown-") })
        assertFalse(markdownKeys.any { it.startsWith("content-1-markdown-") })
    }

    @Test
    fun virtualContentKeysUseSameTypeOrdinal() {
        val items = buildChatMessageVirtualItems(
            node = MessageNode.of(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Text("first"),
                        UIMessagePart.Image("file://image.png"),
                        UIMessagePart.Text("second"),
                        UIMessagePart.Document("file://doc.pdf", "doc.pdf"),
                        UIMessagePart.Text("third"),
                    ),
                )
            ),
            assistant = null,
            showAssistantBubble = true,
            loading = false,
            lastMessage = false,
        ).orEmpty()

        val contentKeys = items
            .filterIsInstance<ChatMessageVirtualItem.Content>()
            .map { it.keySuffix }

        assertTrue("content-Text-0" in contentKeys)
        assertTrue("content-Image-0" in contentKeys)
        assertTrue("content-Text-1" in contentKeys)
        assertTrue("content-Document-0" in contentKeys)
        assertTrue("content-Text-2" in contentKeys)
    }

    @Test
    fun timelinePrewarmCoversShortUserAndAssistantTextsButNotTheStreamingTail() {
        val user = MessageNode.of(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("short **user** ask"))))
        val assistant = MessageNode.of(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text("1. short\n2. list"),
                    UIMessagePart.Text("   "),
                    UIMessagePart.Text(longMarkdown()),
                ),
            )
        )

        assertEquals(listOf("short **user** ask"), user.chatMessageTimelinePrewarmTexts(emptyList(), loading = false, lastMessage = false))
        assertEquals(
            listOf("1. short\n2. list", longMarkdown()),
            assistant.chatMessageTimelinePrewarmTexts(emptyList(), loading = false, lastMessage = false),
        )
        assertTrue(assistant.chatMessageTimelinePrewarmTexts(emptyList(), loading = true, lastMessage = true).isEmpty())
        assertEquals(2, assistant.chatMessageTimelinePrewarmTexts(emptyList(), loading = true, lastMessage = false).size)
    }

    @Test
    fun timelinePrewarmSkipsAssistantWidgetPayloads() {
        val widget = MessageNode.of(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("```widget\n{\"type\":\"card\"}\n```")),
            )
        )

        assertTrue(widget.chatMessageTimelinePrewarmTexts(emptyList(), loading = false, lastMessage = false).isEmpty())
    }

    private fun longMarkdown(): String = buildString {
        append("# title\n\n")
        repeat(120) { append("markdown-key-stability ") }
    }
}
