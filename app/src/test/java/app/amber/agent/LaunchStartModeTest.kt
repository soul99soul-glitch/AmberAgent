package app.amber.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchStartModeTest {
    @Test
    fun `legacy launch boolean migrates to PersonalAgent or LastSession`() {
        // 旧版 create_new_conversation_on_start=true 是持久化的默认值，
        // 不代表用户主动选择 → 迁移到新的 Personal Agent 主舞台。
        assertEquals(
            LaunchStartMode.PERSONAL_AGENT,
            migrateLaunchStartMode(storedMode = null, legacyCreateNewConversationOnStart = true),
        )
        // 显式关掉 = 主动选择恢复上次会话。
        assertEquals(
            LaunchStartMode.LAST_SESSION,
            migrateLaunchStartMode(storedMode = null, legacyCreateNewConversationOnStart = false),
        )
        // 从未设置过 → 默认 Personal Agent 主舞台。
        assertEquals(
            LaunchStartMode.PERSONAL_AGENT,
            migrateLaunchStartMode(storedMode = null, legacyCreateNewConversationOnStart = null),
        )
    }

    @Test
    fun `explicit stored launch modes are preserved verbatim over legacy boolean`() {
        for (mode in LaunchStartMode.entries) {
            assertEquals(
                mode,
                migrateLaunchStartMode(storedMode = mode.name, legacyCreateNewConversationOnStart = true),
            )
            assertEquals(
                mode,
                migrateLaunchStartMode(storedMode = mode.name.lowercase(), legacyCreateNewConversationOnStart = false),
            )
            assertEquals(
                mode,
                migrateLaunchStartMode(storedMode = mode.name, legacyCreateNewConversationOnStart = null),
            )
        }
    }

    @Test
    fun `invalid stored launch mode falls back to legacy boolean migration rules`() {
        for (invalid in listOf("", "   ", "UNKNOWN", "personal-agent", "session_home")) {
            assertEquals(
                LaunchStartMode.LAST_SESSION,
                migrateLaunchStartMode(storedMode = invalid, legacyCreateNewConversationOnStart = false),
            )
            assertEquals(
                LaunchStartMode.PERSONAL_AGENT,
                migrateLaunchStartMode(storedMode = invalid, legacyCreateNewConversationOnStart = true),
            )
            assertEquals(
                LaunchStartMode.PERSONAL_AGENT,
                migrateLaunchStartMode(storedMode = invalid, legacyCreateNewConversationOnStart = null),
            )
        }
    }

    @Test
    fun `resolver handles all launch modes including PersonalAgent`() {
        val last = "11111111-1111-1111-1111-111111111111"
        val fresh = "22222222-2222-2222-2222-222222222222"

        assertEquals(Screen.Chat(last), resolveLaunchStartScreen(LaunchStartMode.AUTO, last, fresh))
        assertEquals(Screen.Chat(last), resolveLaunchStartScreen(LaunchStartMode.LAST_SESSION, last, fresh))
        assertEquals(Screen.Chat(fresh), resolveLaunchStartScreen(LaunchStartMode.NEW_CHAT, last, fresh))
        assertTrue(resolveLaunchStartScreen(LaunchStartMode.HOME, last, fresh) is Screen.SessionHome)
        assertEquals(Screen.PersonalAgent, resolveLaunchStartScreen(LaunchStartMode.PERSONAL_AGENT, last, fresh))
    }

    @Test
    fun `auto and last session fall back to new chat when last id is unusable`() {
        val fresh = "22222222-2222-2222-2222-222222222222"

        assertEquals(Screen.Chat(fresh), resolveLaunchStartScreen(LaunchStartMode.AUTO, "bad-id", fresh))
        assertEquals(Screen.Chat(fresh), resolveLaunchStartScreen(LaunchStartMode.LAST_SESSION, null, fresh))
    }

    @Test
    fun `homeScreenFor maps HOME to SessionHome and all others to PersonalAgent`() {
        assertEquals(Screen.SessionHome, homeScreenFor(LaunchStartMode.HOME))
        assertEquals(Screen.PersonalAgent, homeScreenFor(LaunchStartMode.PERSONAL_AGENT))
        assertEquals(Screen.PersonalAgent, homeScreenFor(LaunchStartMode.AUTO))
        assertEquals(Screen.PersonalAgent, homeScreenFor(LaunchStartMode.LAST_SESSION))
        assertEquals(Screen.PersonalAgent, homeScreenFor(LaunchStartMode.NEW_CHAT))
    }
}
