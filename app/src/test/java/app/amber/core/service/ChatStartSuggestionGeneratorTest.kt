package app.amber.core.service

import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryRecord
import app.amber.core.memory.model.MemoryScope
import app.amber.core.memory.store.MemoryProfile
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.Settings
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStartSuggestionGeneratorTest {
    private val now = 1_700_000_000_000L
    private fun record(id: Int, scope: MemoryScope, text: String) = MemoryRecord(
        id = id, scope = scope, content = text, kind = MemoryKind.USER,
        assistantId = "__global__", updatedAt = now,
    )

    @Test
    fun `disabled memory scopes and their profile facts stay out of starters`() {
        val settings = Settings(agentRuntime = AgentRuntimeSetting(enableLongTermMemory = false))
        val context = ChatStartSuggestionGenerator.buildContext(
            settings,
            listOf(record(1, MemoryScope.CORE, "Likes Kotlin"), record(2, MemoryScope.LONG_TERM, "Private hobby")),
            MemoryProfile("Private profile", now, listOf(1, 2)), Locale.ENGLISH, now,
        )
        assertTrue(context.contains("Likes Kotlin"))
        assertFalse(context.contains("Private hobby"))
        assertFalse(context.contains("Private profile"))
    }

    @Test
    fun `all memories disabled also disables profile injection`() {
        val settings = Settings(agentRuntime = AgentRuntimeSetting(
            enableCoreMemory = false, enableShortTermMemory = false, enableLongTermMemory = false,
        ))
        assertEquals("", ChatStartSuggestionGenerator.buildContext(
            settings, emptyList(), MemoryProfile("Profile", now), Locale.ENGLISH, now,
        ))
    }

    @Test
    fun `archived expired and stale profile data are excluded`() {
        val records = listOf(
            record(1, MemoryScope.CORE, "Archived").copy(archived = true),
            record(2, MemoryScope.SHORT_TERM, "Expired").copy(expiresAt = now),
        )
        assertEquals("", ChatStartSuggestionGenerator.buildContext(
            Settings(), records,
            MemoryProfile("Stale", now - MemoryProfile.PROFILE_TTL_MS - 1), Locale.ENGLISH, now,
        ))
    }

    @Test
    fun `fresh allowed profile and recent project can personalize an empty chat`() {
        val records = listOf(record(1, MemoryScope.LONG_TERM, "Android project").copy(kind = MemoryKind.PROJECT))
        val context = ChatStartSuggestionGenerator.buildContext(
            Settings(), records, MemoryProfile("Prefers concise Chinese", now, listOf(1)), Locale.CHINESE, now,
        )
        assertTrue(context.contains("Android project"))
        assertTrue(context.contains("Prefers concise Chinese"))
    }

    @Test
    fun `numbered output is normalized and duplicates cannot fill three slots`() {
        assertEquals(listOf("整理小说大纲", "优化 Android 流式渲染", "复盘本周计划"),
            ChatStartSuggestionGenerator.parseSuggestions("1. 整理小说大纲\n- 优化 Android 流式渲染\n3、复盘本周计划"))
        assertTrue(ChatStartSuggestionGenerator.parseSuggestions("Write Kotlin\nwrite kotlin\nAnother task").isEmpty())
        assertTrue(ChatStartSuggestionGenerator.parseSuggestions("A\nB\n" + "x".repeat(25)).isEmpty())
    }
}
