package app.amber.feature.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * W16-B: shortcut routing contract. Publisher side needs a real launcher
 * surface; these regressions pin the pure routing rules — kind → typed route,
 * malformed/missing ids never resolve to a wrong conversation, prompts are
 * carried verbatim (the chat send gate stays downstream).
 */
class DynamicShortcutRouteTest {

    @Test
    fun `new chat shortcut resolves to NewChat route`() {
        assertEquals(DynamicShortcutPublisher.Route.NewChat, DynamicShortcutPublisher.routeFrom("new_chat", null, null))
    }

    @Test
    fun `conversation shortcut resolves exact conversation id`() {
        val id = Uuid.random()
        assertEquals(
            DynamicShortcutPublisher.Route.Conversation(id),
            DynamicShortcutPublisher.routeFrom("conversation", id.toString(), null),
        )
    }

    @Test
    fun `malformed or missing conversation id never resolves`() {
        assertNull(DynamicShortcutPublisher.routeFrom("conversation", "not-a-uuid", null))
        assertNull(DynamicShortcutPublisher.routeFrom("conversation", null, null))
        assertNull(DynamicShortcutPublisher.routeFrom("conversation", "", null))
    }

    @Test
    fun `quick message shortcut carries prompt verbatim`() {
        val route = DynamicShortcutPublisher.routeFrom("quick_message", null, "每日简报")
        assertEquals(DynamicShortcutPublisher.Route.QuickPrompt("每日简报"), route)
    }

    @Test
    fun `blank prompt or unknown kind never resolves`() {
        assertNull(DynamicShortcutPublisher.routeFrom("quick_message", null, "  "))
        assertNull(DynamicShortcutPublisher.routeFrom("quick_message", null, null))
        assertNull(DynamicShortcutPublisher.routeFrom("unknown_kind", null, null))
        assertNull(DynamicShortcutPublisher.routeFrom(null, null, null))
    }

    @Test
    fun `quick message model keeps stable identity for shortcuts`() {
        val message = app.amber.core.model.QuickMessage(title = "简报", content = "生成今日简报")
        assertTrue(message.id.toString().isNotBlank())
        assertEquals("简报", message.title)
        assertEquals("生成今日简报", message.content)
    }
}
