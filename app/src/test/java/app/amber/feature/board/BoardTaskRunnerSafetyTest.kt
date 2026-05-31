package app.amber.feature.board

import app.amber.agent.data.db.entity.BoardTaskEventEntity
import app.amber.agent.data.db.entity.BoardTaskEventType
import app.amber.feature.webmount.adapters.feishudocs.FeishuDocRefs
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardTaskRunnerSafetyTest {
    @Test
    fun safe_tool_surface_is_exactly_the_v1_allowlist() {
        assertEquals(
            linkedSetOf(
                "get_time_info",
                "board_task_record",
                "feishu_docs_resolve",
                "feishu_docs_read",
                "feishu_docs_blocks",
                "feishu_docs_markdown_pack",
                "search_web",
                "scrape_web",
            ),
            BOARD_TASK_RUNNER_SAFE_TOOL_NAMES,
        )

        val bannedExact = setOf(
            "feishu_docs_list",
            "feishu_docs_search",
            "feishu_docs_snapshot",
            "feishu_docs_network_summary",
            "feishu_docs_create",
            "feishu_docs_append_block",
            "feishu_docs_append_heading",
            "feishu_docs_append_list_item",
            "feishu_docs_append_callout",
            "tool_search",
            "tools_list",
            "mcp_call_tool",
            "memory_tool",
        )
        val bannedPrefixes = listOf("wm_", "screen_", "terminal_")
        BOARD_TASK_RUNNER_SAFE_TOOL_NAMES.forEach { name ->
            assertFalse("unexpected banned tool name $name", name in bannedExact)
            assertFalse("unexpected banned tool prefix in $name", bannedPrefixes.any { name.startsWith(it) })
        }
    }

    @Test
    fun allowed_docs_are_extracted_from_urls_doc_refs_and_document_id_labels() {
        val docRef = FeishuDocRefs.encode("doc_ref_token_123")
        val docs = BoardTaskAllowedDocs.fromText(
            listOf(
                """{"doc_links":["https://example.feishu.cn/docx/abcDEF_123"]}""",
                """用户补充指令：请也看 doc_ref=$docRef""",
                """"document_id":"labeled_doc_456"""",
            )
        )

        assertTrue(docs.allows(buildJsonObject { put("url", "https://example.feishu.cn/docx/abcDEF_123") }))
        assertTrue(docs.allows(buildJsonObject { put("doc_ref", docRef) }))
        assertTrue(docs.allows(buildJsonObject { put("document_id", "labeled_doc_456") }))
    }

    @Test
    fun allowed_docs_reject_out_of_scope_documents() {
        val docs = BoardTaskAllowedDocs.fromText(
            listOf("""{"doc_links":["https://example.feishu.cn/docx/allowed_123"]}""")
        )

        assertFalse(docs.allows(buildJsonObject { put("url", "https://example.feishu.cn/docx/other_456") }))
        assertFalse(docs.allows(buildJsonObject { put("document_id", "other_456") }))
        assertFalse(docs.allows(buildJsonObject { put("doc_ref", FeishuDocRefs.encode("other_456")) }))
    }

    @Test
    fun allowed_doc_scope_ignores_model_written_events() {
        val evidenceUrl = "https://example.feishu.cn/docx/evidence_doc_123"
        val userUrl = "https://example.feishu.cn/docx/user_doc_123"
        val modelUrl = "https://example.feishu.cn/docx/model_doc_123"
        val docs = BoardTaskAllowedDocs.fromText(
            boardTaskAllowedDocScopeParts(
                opportunityEvidenceJson = """{"doc":"$evidenceUrl"}""",
                events = listOf(
                    event(BoardTaskEventType.PROGRESS, "模型进展里提到 $modelUrl"),
                    event(BoardTaskEventType.WAITING_USER, "模型等待确认里提到 $modelUrl"),
                    event(BoardTaskEventType.USER_REPLIED, "用户补充指令：也看 $userUrl"),
                ),
            )
        )

        assertTrue(docs.allows(buildJsonObject { put("url", evidenceUrl) }))
        assertTrue(docs.allows(buildJsonObject { put("url", userUrl) }))
        assertFalse(docs.allows(buildJsonObject { put("url", modelUrl) }))
    }

    @Test
    fun board_task_record_model_actions_are_lifecycle_limited() {
        assertEquals(setOf("progress", "waiting_user", "blocked"), BOARD_TASK_RECORD_ALLOWED_ACTIONS)
        assertFalse("done" in BOARD_TASK_RECORD_ALLOWED_ACTIONS)
        assertFalse("dismissed" in BOARD_TASK_RECORD_ALLOWED_ACTIONS)
        assertFalse("cancelled" in BOARD_TASK_RECORD_ALLOWED_ACTIONS)
    }

    private fun event(type: String, message: String): BoardTaskEventEntity =
        BoardTaskEventEntity(
            id = "$type-${message.hashCode()}",
            taskId = "task",
            type = type,
            message = message,
            ts = 1L,
        )
}
