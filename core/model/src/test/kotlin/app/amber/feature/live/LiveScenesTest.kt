package app.amber.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveScenesTest {
    @Test
    fun `聊天类包名归为 CHAT`() {
        assertEquals(LiveScene.CHAT, LiveScenes.classify("com.tencent.mm"))
        assertEquals(LiveScene.CHAT, LiveScenes.classify("org.telegram.messenger"))
    }

    @Test
    fun `阅读类支持前缀匹配`() {
        assertEquals(LiveScene.READING, LiveScenes.classify("com.android.chrome"))
        assertEquals(LiveScene.READING, LiveScenes.classify("com.ss.android.article.news"))
    }

    @Test
    fun `未知包名归为 OTHER`() {
        assertEquals(LiveScene.OTHER, LiveScenes.classify("com.example.unknown"))
        assertEquals(LiveScene.OTHER, LiveScenes.classify(""))
    }

    @Test
    fun `场景默认动作映射`() {
        assertEquals("写回复", LiveScenes.defaultActionLabel(LiveScene.CHAT))
        assertEquals("找重点", LiveScenes.defaultActionLabel(LiveScene.READING))
        assertNull(LiveScenes.defaultActionLabel(LiveScene.OTHER))
    }
}
