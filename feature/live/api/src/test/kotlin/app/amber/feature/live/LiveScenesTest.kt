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

    @Test
    fun `用户覆盖优先于内置表`() {
        val overrides = mapOf("com.tencent.mm" to "other")
        assertEquals(LiveScene.OTHER, LiveScenes.classify("com.tencent.mm", overrides))
        assertEquals(LiveScene.CHAT, LiveScenes.classify("org.telegram.messenger", overrides))
    }

    @Test
    fun `未知包名可被覆盖为 CHAT`() {
        val overrides = mapOf("com.example.myapp" to "chat")
        assertEquals(LiveScene.CHAT, LiveScenes.classify("com.example.myapp", overrides))
        assertEquals(LiveScene.OTHER, LiveScenes.classify("com.example.myapp"))
    }

    @Test
    fun `非法 wire 名回退内置表`() {
        val overrides = mapOf("com.tencent.mm" to "bogus")
        assertEquals(LiveScene.CHAT, LiveScenes.classify("com.tencent.mm", overrides))
    }
}
