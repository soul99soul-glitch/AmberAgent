package app.amber.feature.ui.components.message

import app.amber.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

class ChatMessageActionRowTest {
    @Test
    fun actionRowModeReservesOnlyStreamingAssistantTailWithContent() {
        assertEquals(
            AssistantActionRowMode.Hidden,
            resolveAssistantActionRowMode(
                role = MessageRole.USER,
                lastMessage = true,
                loading = true,
                hasRenderableContent = true,
            ),
        )
        assertEquals(
            AssistantActionRowMode.Hidden,
            resolveAssistantActionRowMode(
                role = MessageRole.ASSISTANT,
                lastMessage = true,
                loading = true,
                hasRenderableContent = false,
            ),
        )
        assertEquals(
            AssistantActionRowMode.Reserved,
            resolveAssistantActionRowMode(
                role = MessageRole.ASSISTANT,
                lastMessage = true,
                loading = true,
                hasRenderableContent = true,
            ),
        )
        assertEquals(
            AssistantActionRowMode.Visible,
            resolveAssistantActionRowMode(
                role = MessageRole.ASSISTANT,
                lastMessage = true,
                loading = false,
                hasRenderableContent = true,
            ),
        )
        assertEquals(
            AssistantActionRowMode.Hidden,
            resolveAssistantActionRowMode(
                role = MessageRole.ASSISTANT,
                lastMessage = false,
                loading = true,
                hasRenderableContent = true,
            ),
        )
    }

    @Test
    fun actionRowModeTreatsStoppedAssistantLikeFinishedContent() {
        assertEquals(
            AssistantActionRowMode.Visible,
            resolveAssistantActionRowMode(
                role = MessageRole.ASSISTANT,
                lastMessage = true,
                loading = false,
                hasRenderableContent = true,
            ),
        )
        assertEquals(
            AssistantActionRowMode.Hidden,
            resolveAssistantActionRowMode(
                role = MessageRole.ASSISTANT,
                lastMessage = true,
                loading = false,
                hasRenderableContent = false,
            ),
        )
    }

    @Test
    fun actionRowHeightCacheIsScopedByMessageAndWidth() {
        val cache = ActionRowHeightCache()
        val firstMessage = Uuid.random()
        val secondMessage = Uuid.random()

        cache.record(messageId = firstMessage, widthPx = 320, heightPx = 34)
        cache.record(messageId = firstMessage, widthPx = 0, heightPx = 40)

        assertEquals(34, cache.heightFor(firstMessage, widthPx = 320))
        assertNull(cache.heightFor(firstMessage, widthPx = 321))
        assertNull(cache.heightFor(secondMessage, widthPx = 320))
        assertNull(cache.heightFor(firstMessage, widthPx = 0))
    }

    @Test
    fun actionRowSlotUsesSameRowForReservedAndVisibleWithoutSlideVisibility() {
        val slot = repoFile("src/main/java/app/amber/feature/ui/components/message/ChatMessageActionRowSlot.kt")
            .readText()
        val chatMessage = repoFile("src/main/java/app/amber/feature/ui/components/message/ChatMessage.kt").readText()
        val virtualItems = repoFile("src/main/java/app/amber/feature/ui/components/message/ChatMessageVirtualItems.kt")
            .readText()
        val actions = repoFile("src/main/java/app/amber/feature/ui/components/message/ChatMessageActions.kt").readText()
        val branch = repoFile("src/main/java/app/amber/feature/ui/components/message/ChatMessageBranch.kt").readText()

        assertTrue(slot.contains("clearAndSetSemantics {}"))
        assertTrue(slot.contains("enabled = !reserved"))
        assertTrue(slot.contains("heightIn(min = cachedHeightDp)"))
        assertTrue(slot.contains("heightFor(message.id, widthPx)"))
        assertTrue(chatMessage.contains("AssistantActionRowSlot("))
        assertTrue(virtualItems.contains("AssistantActionRowSlot("))
        assertFalse(chatMessage.contains("slideInVertically"))
        assertFalse(virtualItems.contains("AnimatedVisibility("))
        assertTrue(actions.contains("enabled: Boolean = true"))
        assertTrue(actions.contains("enabled = enabled && isAvailable"))
        assertTrue(actions.contains("ChatMessageBranchSelector(\n            node = node,\n            enabled = enabled"))
        assertTrue(branch.contains("enabled: Boolean = true"))
        assertTrue(branch.contains(".clickable(\n                        enabled = enabled && node.selectIndex > 0"))
    }

    private fun repoFile(pathInAppModule: String): File {
        return listOf(
            File(pathInAppModule),
            File("app/$pathInAppModule"),
        ).firstOrNull { it.isFile }
            ?: error("Cannot locate $pathInAppModule")
    }
}
