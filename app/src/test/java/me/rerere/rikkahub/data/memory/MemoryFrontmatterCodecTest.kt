package me.rerere.rikkahub.data.memory

import me.rerere.rikkahub.data.memory.export.MemoryFrontmatterCodec
import me.rerere.rikkahub.data.memory.model.MemoryKind
import me.rerere.rikkahub.data.memory.model.MemoryRecord
import me.rerere.rikkahub.data.memory.model.MemoryScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryFrontmatterCodecTest {
    @Test
    fun roundTripsBasicMemoryFields() {
        val codec = MemoryFrontmatterCodec()
        val record = MemoryRecord(
            id = 42,
            content = "用户正在推进 AmberAgent 的记忆系统。",
            scope = MemoryScope.LONG_TERM,
            kind = MemoryKind.PROJECT,
            assistantId = "__long_term__",
            confidence = 0.82f,
            pinned = true,
            archived = true,
            sourceConversationId = "conversation-1",
            sourceMessageIds = listOf("message-1", "message-2"),
            createdAt = 1_700_000_000_000,
            updatedAt = 1_700_000_100_000,
        )

        val decoded = codec.decode(codec.encode(record))

        assertEquals(record.id, decoded.id)
        assertEquals(record.content, decoded.content)
        assertEquals(record.scope, decoded.scope)
        assertEquals(record.kind, decoded.kind)
        assertEquals(record.confidence, decoded.confidence)
        assertEquals(record.sourceConversationId, decoded.sourceConversationId)
        assertEquals(record.sourceMessageIds, decoded.sourceMessageIds)
        assertTrue(decoded.pinned)
        assertTrue(decoded.archived)
    }
}
