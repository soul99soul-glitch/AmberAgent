package app.amber.feature.ui.pages.sessionhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSessionIconsTest {
    @Test
    fun sessionTitlesSelectTheirTopicInsteadOfAColoredChatBubble() {
        assertEquals(HomeSessionIcon.CPU, homeSessionIcon("迷你主机高负载排查"))
        assertEquals(HomeSessionIcon.ROBOT, homeSessionIcon("四子代理并发测试"))
        assertEquals(HomeSessionIcon.FLOWER, homeSessionIcon("竹子开花原因"))
        assertEquals(HomeSessionIcon.MUSIC_NOTES, homeSessionIcon("一剪梅歌词讨论"))
        assertEquals(HomeSessionIcon.CHAT_CIRCLE, homeSessionIcon("新消息"))
        assertEquals(HomeSessionIcon.GHOST, homeSessionIcon("未命中主题🧭"))
    }

    @Test
    fun importedGlyphPathsCanAllBeParsedByCompose() {
        // These are imported SVG data, not hand-written Compose paths. Validate the
        // complete bundled asset set once so an unfamiliar title cannot break a row.
        HomeSessionIcon.entries.forEach { icon ->
            assertTrue(icon.name, icon.imageVector.root.size > 0)
        }
    }
}
