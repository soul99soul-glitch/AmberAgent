package app.amber.core.ai.tools

import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.core.memory.store.MemoryCasDeleteResult
import app.amber.core.memory.store.MemoryCasUpdateResult
import app.amber.core.memory.store.MemoryStaleException
import app.amber.core.model.AssistantMemory
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryScope
import app.amber.feature.runtime.ApprovalHistoryEntry
import app.amber.feature.runtime.ContentDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-06 memory CAS (parity plan §P2-06): edit/delete must be bound to the
 * exact revision the user saw.
 *
 * Acceptance covered:
 *  - a missing revision is rejected (revision_required) on both memory_delete
 *    and memory_tool edit/delete — never silently resolved to the latest;
 *  - a stale revision (record changed since approval) returns a structured
 *    conflict with expected/actual revisions;
 *  - a valid revision is passed through to the CAS callbacks.
 */
class MemoryToolsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private open class Recorder {
        val updates = mutableListOf<Triple<Int, String, Long?>>()
        val deletes = mutableListOf<Pair<Int, Long?>>()
        val audits = mutableListOf<ApprovalHistoryEntry>()

        open suspend fun update(id: Int, content: String, expectedRevision: Long?): MemoryCasUpdateResult {
            updates += Triple(id, content, expectedRevision)
            if (expectedRevision == 2L) {
                throw MemoryStaleException(memoryId = id, expectedRevision = 2L, actualRevision = 3L)
            }
            return MemoryCasUpdateResult(
                memory = AssistantMemory(id = id, content = content, revision = expectedRevision ?: 1L),
                oldDigest = "old",
                newDigest = "new",
            )
        }

        open suspend fun delete(id: Int, expectedRevision: Long?): MemoryCasDeleteResult {
            deletes += id to expectedRevision
            return MemoryCasDeleteResult(memoryId = id, oldDigest = "old")
        }
    }

    private fun tools(
        recorder: Recorder,
        onList: suspend (String) -> List<AssistantMemory> = { emptyList() },
    ): List<Tool> = buildMemoryTools(
        json = json,
        onList = onList,
        onCreation = {
            AssistantMemory(id = 1, content = it.content, scope = it.scope, kind = it.kind)
        },
        onUpdateCas = recorder::update,
        onDeleteCas = recorder::delete,
        onAudit = { recorder.audits += it },
        runIdProvider = { "run-1" },
    )

    private fun tool(tools: List<Tool>, name: String): Tool =
        tools.first { it.name == name }

    private fun execute(tool: Tool, input: String): String = runBlocking {
        val output = tool.execute(json.parseToJsonElement(input))
        (output.single() as UIMessagePart.Text).text
    }

    // ---- memory_tool edit/delete without revision ----

    @Test
    fun memoryToolEditWithoutRevisionIsRejected() {
        val recorder = Recorder()
        val result = execute(tool(tools(recorder), "memory_tool"), """{"action":"edit","id":7,"content":"new text"}""")

        assertTrue("expected revision_required but was: $result", result.contains("\"status\":\"revision_required\""))
        assertTrue(result.contains("memory_list"))
        assertTrue("CAS callback must not be called without a revision", recorder.updates.isEmpty())
    }

    @Test
    fun memoryToolDeleteWithoutRevisionIsRejected() {
        val recorder = Recorder()
        val result = execute(tool(tools(recorder), "memory_tool"), """{"action":"delete","id":7}""")

        assertTrue("expected revision_required but was: $result", result.contains("\"status\":\"revision_required\""))
        assertTrue(recorder.deletes.isEmpty())
    }

    @Test
    fun memoryDeleteToolWithoutRevisionIsRejected() {
        val recorder = Recorder()
        val result = execute(tool(tools(recorder), "memory_delete"), """{"id":7}""")

        assertTrue("expected revision_required but was: $result", result.contains("\"status\":\"revision_required\""))
        assertTrue(recorder.deletes.isEmpty())
    }

    // ---- stale revision -> conflict ----

    @Test
    fun staleRevisionEditReturnsConflictWithExpectedAndActual() {
        val recorder = Recorder()
        val result = execute(
            tool(tools(recorder), "memory_tool"),
            """{"action":"edit","id":7,"revision":2,"content":"new text"}""",
        )

        assertTrue("expected conflict but was: $result", result.contains("\"status\":\"conflict\""))
        assertTrue(result.contains("\"expected_revision\":2"))
        assertTrue(result.contains("\"actual_revision\":3"))
        assertTrue(result.contains("NOT applied"))
    }

    @Test
    fun staleRevisionDeleteReturnsConflict() {
        val recorder = object : Recorder() {
            override suspend fun delete(id: Int, expectedRevision: Long?): MemoryCasDeleteResult {
                deletes += id to expectedRevision
                throw MemoryStaleException(memoryId = id, expectedRevision = 1L, actualRevision = 4L)
            }
        }
        val result = execute(
            tool(tools(recorder), "memory_delete"),
            """{"id":7,"revision":1}""",
        )

        assertTrue("expected conflict but was: $result", result.contains("\"status\":\"conflict\""))
        assertTrue(result.contains("\"expected_revision\":1"))
        assertTrue(result.contains("\"actual_revision\":4"))
    }

    // ---- valid revision passes through ----

    @Test
    fun editWithRevisionPassesTheRevisionToTheCasCallback() {
        val recorder = Recorder()
        val result = execute(
            tool(tools(recorder), "memory_tool"),
            """{"action":"edit","id":7,"revision":5,"content":"new text"}""",
        )

        assertFalse("must not be rejected: $result", result.contains("revision_required"))
        assertEquals(listOf(Triple(7, "new text", 5L)), recorder.updates)
    }

    @Test
    fun deleteWithRevisionPassesTheRevisionToTheCasCallback() {
        val recorder = Recorder()
        val result = execute(
            tool(tools(recorder), "memory_delete"),
            """{"id":7,"revision":5}""",
        )

        assertTrue(result.contains("\"deleted\":true"))
        assertEquals(listOf(7 to 5L), recorder.deletes)
    }

    @Test
    fun memoryWriteCreateRecordsAuditAfterSuccessfulCreate() {
        val recorder = Recorder()
        val result = execute(
            tool(tools(recorder), "memory_write"),
            """{"type":"long_term","content":"prefers concise replies"}""",
        )

        assertTrue(result.contains("prefers concise replies"))
        val audit = recorder.audits.single()
        assertEquals("memory_create", audit.toolName)
        assertEquals("run-1", audit.runId)
        assertNull(audit.oldDigest)
        assertEquals(ContentDigest.sha256("prefers concise replies"), audit.newDigest)
        assertEquals("applied", audit.outcome)
    }

    @Test
    fun memoryToolCreateRecordsAuditAfterSuccessfulCreate() {
        val recorder = Recorder()
        val result = execute(
            tool(tools(recorder), "memory_tool"),
            """{"action":"create","scope":"core","content":"user name is Amber"}""",
        )

        assertTrue(result.contains("user name is Amber"))
        val audit = recorder.audits.single()
        assertEquals("memory_create", audit.toolName)
        assertEquals(ContentDigest.sha256("user name is Amber"), audit.newDigest)
        assertNull(audit.oldDigest)
    }

    @Test
    fun memoryListUsesDefaultAndMaximumLimitsAndTruncatesContent() {
        val longContent = "x".repeat(MEMORY_LIST_SUMMARY_MAX_CHARS + 20)
        val memories = (1..120).map {
            AssistantMemory(
                id = it,
                content = longContent,
                scope = MemoryScope.LONG_TERM,
                kind = MemoryKind.NOTE,
            )
        }
        val recorder = Recorder()

        val defaultPayload = json.parseToJsonElement(
            execute(tool(tools(recorder) { memories }, "memory_list"), """{"type":"long_term"}"""),
        ).jsonObject
        assertEquals(DEFAULT_MEMORY_LIST_LIMIT, defaultPayload.getValue("count").jsonPrimitive.int)
        assertEquals(120, defaultPayload.getValue("total_count").jsonPrimitive.int)
        assertEquals(DEFAULT_MEMORY_LIST_LIMIT, defaultPayload.getValue("limit").jsonPrimitive.int)
        assertTrue(defaultPayload.getValue("truncated").jsonPrimitive.content == "true")

        val maximumPayload = json.parseToJsonElement(
            execute(
                tool(tools(recorder) { memories }, "memory_list"),
                """{"type":"long_term","limit":120}""",
            ),
        ).jsonObject
        assertEquals(MAX_MEMORY_LIST_LIMIT, maximumPayload.getValue("count").jsonPrimitive.int)
        assertEquals(MAX_MEMORY_LIST_LIMIT, maximumPayload.getValue("limit").jsonPrimitive.int)
        assertEquals(MAX_MEMORY_LIST_LIMIT, maximumPayload.getValue("memories").jsonArray.size)
        val first = maximumPayload.getValue("memories").jsonArray.first().jsonObject
        assertEquals(MEMORY_LIST_SUMMARY_MAX_CHARS, first.getValue("content").jsonPrimitive.content.length)
        assertTrue(first.getValue("content_truncated").jsonPrimitive.content == "true")
    }
}
