package app.amber.core.memory

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import app.amber.core.memory.extraction.MemoryExtractor
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryRecord
import app.amber.core.memory.model.MemoryScope
import app.amber.core.memory.store.MemoryStaleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryExtractionReconcileTest {

    private val json = Json

    @Test
    fun `update action parses update memory id`() {
        val parsed = MemoryExtractor.parseCandidates(
            json = json,
            raw = """
                {
                  "candidates": [
                    {
                      "content": "用户现在偏好英文详细回复",
                      "scope": "long_term",
                      "kind": "feedback",
                      "confidence": 0.9,
                      "reason": "更正旧偏好",
                      "action": "update",
                      "update_memory_id": 42
                    }
                  ]
                }
            """.trimIndent(),
            conversationId = "conv-1",
            sourceMessageIds = listOf("m1"),
        )

        assertEquals(1, parsed.size)
        assertEquals(42, parsed.single().updateMemoryId)
        assertTrue(parsed.single().explicitScope)
        assertTrue(parsed.single().explicitKind)
    }

    @Test
    fun `missing action stays a plain add`() {
        val parsed = MemoryExtractor.parseCandidates(
            json = json,
            raw = """
                {
                  "candidates": [
                    {
                      "content": "用户喜欢中文简洁回复",
                      "scope": "long_term",
                      "kind": "feedback",
                      "confidence": 0.9,
                      "reason": "稳定偏好"
                    }
                  ]
                }
            """.trimIndent(),
            conversationId = "conv-1",
            sourceMessageIds = listOf("m1"),
        )

        assertEquals(1, parsed.size)
        assertNull(parsed.single().updateMemoryId)
    }

    @Test
    fun `update without a target id falls back to add`() {
        val parsed = MemoryExtractor.parseCandidates(
            json = json,
            raw = """
                {
                  "candidates": [
                    {
                      "content": "用户现在偏好英文详细回复",
                      "scope": "long_term",
                      "kind": "feedback",
                      "confidence": 0.9,
                      "reason": "更正旧偏好",
                      "action": "update"
                    }
                  ]
                }
            """.trimIndent(),
            conversationId = "conv-1",
            sourceMessageIds = listOf("m1"),
        )

        assertNull(parsed.single().updateMemoryId)
    }

    @Test
    fun `extraction update applies content with bound revision`() = runBlocking {
        val target = MemoryRecord(
            id = 7,
            content = "用户偏好中文简洁回复。",
            scope = MemoryScope.LONG_TERM,
            kind = MemoryKind.USER,
            assistantId = "__long_term__",
            revision = 5,
        )
        var written: Triple<Int, String, Long>? = null
        val applied = MemoryExtractor.applyExtractionUpdate(
            target = target,
            newContent = "用户现在偏好英文详细回复。",
        ) { id, content, revision ->
            written = Triple(id, content, revision)
        }

        assertTrue(applied)
        assertEquals(Triple(7, "用户现在偏好英文详细回复。", 5L), written)
    }

    @Test
    fun `extraction update returns false on stale revision`() = runBlocking {
        val target = MemoryRecord(
            id = 7,
            content = "用户偏好中文简洁回复。",
            scope = MemoryScope.LONG_TERM,
            kind = MemoryKind.USER,
            assistantId = "__long_term__",
            revision = 5,
        )
        val applied = MemoryExtractor.applyExtractionUpdate(
            target = target,
            newContent = "用户现在偏好英文详细回复。",
        ) { id, _, revision ->
            throw MemoryStaleException(id, revision, actualRevision = revision + 1)
        }

        assertFalse(applied)
    }
}
