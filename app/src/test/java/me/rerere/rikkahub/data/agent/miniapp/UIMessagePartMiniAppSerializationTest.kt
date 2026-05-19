package me.rerere.rikkahub.data.agent.miniapp

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

class UIMessagePartMiniAppSerializationTest {
    @Test
    fun miniAppPartRoundTripsWithoutHtml() {
        val part = UIMessagePart.MiniApp(
            appId = "app-1",
            title = "喝水记录器",
            description = "记录每天喝水量",
            iconEmoji = "水",
            permissions = listOf("storage"),
            htmlHash = "abc",
        )

        val raw = JsonInstant.encodeToString<UIMessagePart>(part)
        val decoded = JsonInstant.decodeFromString<UIMessagePart>(raw) as UIMessagePart.MiniApp

        assertEquals("app-1", decoded.appId)
        assertEquals("喝水记录器", decoded.title)
        assertEquals(listOf("storage"), decoded.permissions)
        assertEquals(false, raw.contains("<html"))
    }
}
