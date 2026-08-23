package app.amber.core.ai.tools

import app.amber.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepReadOpenRequestTest {
    @Test
    fun parsesSlashTopic() {
        val event = parseDeepReadSlashCommand(listOf(UIMessagePart.Text("/deepread 小米17首发口碑")))

        assertNotNull(event)
        requireNotNull(event)
        assertEquals("小米17首发口碑", event.title)
        assertNull(event.sourceUrl)
        assertTrue(event.topicId.startsWith("chat_deep_read_"))
    }

    @Test
    fun parsesSlashUrlAndKeepsUrlBasedCacheIdentity() {
        val url = "https://example.com/news/xiaomi-17"
        val first = parseDeepReadSlashCommand(listOf(UIMessagePart.Text("/deepread 小米17 $url")))
        val second = parseDeepReadSlashCommand(listOf(UIMessagePart.Text("/deepread 另一种标题 $url")))

        assertNotNull(first)
        assertNotNull(second)
        requireNotNull(first)
        requireNotNull(second)
        assertEquals(url, first.sourceUrl)
        assertEquals(first.topicId, second.topicId)
        assertNotEquals(first.title, second.title)
    }

    @Test
    fun ignoresPartialCommandName() {
        val event = parseDeepReadSlashCommand(listOf(UIMessagePart.Text("/deepreading 小米17")))

        assertNull(event)
    }
}
