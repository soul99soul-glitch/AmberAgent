package app.amber.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchStartModeTest {
    @Test
    fun `legacy launch boolean migrates to equivalent mode`() {
        // 旧版 create_new_conversation_on_start=true 是持久化的默认值，
        // 不代表用户主动选择 → 迁移到新的会话首页。
        assertEquals(
            LaunchStartMode.HOME,
            migrateLaunchStartMode(storedMode = null, legacyCreateNewConversationOnStart = true)
        )
        // 显式关掉 = 主动选择恢复上次会话。
        assertEquals(
            LaunchStartMode.LAST_SESSION,
            migrateLaunchStartMode(storedMode = null, legacyCreateNewConversationOnStart = false)
        )
        // 从未设置过 → 默认会话首页。
        assertEquals(
            LaunchStartMode.HOME,
            migrateLaunchStartMode(storedMode = null, legacyCreateNewConversationOnStart = null)
        )
    }

    @Test
    fun `stored launch mode wins over legacy boolean`() {
        assertEquals(
            LaunchStartMode.HOME,
            migrateLaunchStartMode(storedMode = "HOME", legacyCreateNewConversationOnStart = true)
        )
    }

    @Test
    fun `resolver handles all launch modes`() {
        val last = "11111111-1111-1111-1111-111111111111"
        val fresh = "22222222-2222-2222-2222-222222222222"

        assertEquals(Screen.Chat(last), resolveLaunchStartScreen(LaunchStartMode.AUTO, last, fresh))
        assertEquals(Screen.Chat(last), resolveLaunchStartScreen(LaunchStartMode.LAST_SESSION, last, fresh))
        assertEquals(Screen.Chat(fresh), resolveLaunchStartScreen(LaunchStartMode.NEW_CHAT, last, fresh))
        assertTrue(resolveLaunchStartScreen(LaunchStartMode.HOME, last, fresh) is Screen.SessionHome)
    }

    @Test
    fun `auto and last session fall back to new chat when last id is unusable`() {
        val fresh = "22222222-2222-2222-2222-222222222222"

        assertEquals(Screen.Chat(fresh), resolveLaunchStartScreen(LaunchStartMode.AUTO, "bad-id", fresh))
        assertEquals(Screen.Chat(fresh), resolveLaunchStartScreen(LaunchStartMode.LAST_SESSION, null, fresh))
    }
}
