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

    @Test
    fun shortPlainAssistantTextStaysInOneLazyItem() {
        val short = MessageNode.of(UIMessage.assistant("short **plain** reply"))
        val medium = MessageNode.of(UIMessage.assistant("m".repeat(ChatMessageSingleItemTextMaxChars)))

        assertEquals(
            null,
            buildChatMessageVirtualItems(
                node = short,
                assistant = null,
                showAssistantBubble = true,
                loading = false,
                lastMessage = false,
            ),
        )
        assertEquals(
            null,
            buildChatMessageVirtualItems(
                node = medium,
                assistant = null,
                showAssistantBubble = true,
                loading = false,
                lastMessage = false,
            ),
        )
    }

    @Test
    fun virtualizedSingleVariantMessageOmitsTheEmptyFooterItem() {
        val items = buildChatMessageVirtualItems(
            node = MessageNode.of(UIMessage.assistant(longMarkdown())),
            assistant = null,
            showAssistantBubble = true,
            loading = false,
            lastMessage = false,
        ).orEmpty()

        assertTrue(items.any { it is ChatMessageVirtualItem.Header })
        assertTrue(items.any { it is ChatMessageVirtualItem.MarkdownChild })
        assertFalse(items.any { it is ChatMessageVirtualItem.Footer })
    }

    @Test
    fun adjacentMarkdownBlocksShareABoundedLazyItem() {
        val paragraph = "long paragraph ".repeat(40)
        val text = buildString {
            append("# title\n\n")
            repeat(10) { append(paragraph).append("\n\n") }
        }
        val items = buildChatMessageVirtualItems(
            node = MessageNode.of(UIMessage.assistant(text)),
            assistant = null,
            showAssistantBubble = true,
            loading = false,
            lastMessage = false,
        ).orEmpty()
        val markdown = items.filterIsInstance<ChatMessageVirtualItem.MarkdownChild>()
        val totalBlocks = markdown.last().blockIndex + markdown.last().blockCount
        assertTrue(markdown.size > 1)
        assertTrue(markdown.all { it.blockCount in 1..ChatMessageVirtualMarkdownBlocksPerItem })
        assertEquals(0, markdown.first().blockIndex)
        assertEquals(totalBlocks - 1, markdown.last().blockIndex + markdown.last().blockCount - 1)
        markdown.zipWithNext().forEach { (left, right) ->
            assertEquals(left.blockIndex + left.blockCount, right.blockIndex)
        }
    }

    @Test
    fun markdownGroupingStaysBoundedForLargeParagraphs() {
        assertEquals(
            listOf(0..1, 2..2, 3..6, 7..7),
            markdownBlockGroups(listOf(100, 100, 900, 100, 100, 100, 100, 100)),
        )
        // No group exceeds the char budget unless it is a single oversized block.
        val groups = markdownBlockGroups(listOf(700, 700, 700, 700, 700))
        groups.forEach { group ->
            val chars = (group.first..group.last).sumOf { 700 }
            assertTrue(group.first == group.last || chars <= ChatMessageVirtualMarkdownGroupMaxChars)
        }
    }

    private fun longMarkdown(): String = buildString {
        append("# title\n\n")
        repeat(120) { append("markdown-key-stability ") }
    }
}
