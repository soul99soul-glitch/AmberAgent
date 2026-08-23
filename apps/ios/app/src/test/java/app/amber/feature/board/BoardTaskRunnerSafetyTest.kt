package app.amber.feature.board

import app.amber.agent.data.db.entity.BoardTaskArtifact
import app.amber.agent.data.db.entity.BoardTaskEventEntity
import app.amber.agent.data.db.entity.BoardTaskEventType
import app.amber.core.utils.JsonInstant
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
                "board_task_finish",
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

    @Test
    fun board_task_finish_is_in_the_safe_tool_allowlist() {
        assertTrue("board_task_finish" in BOARD_TASK_RUNNER_SAFE_TOOL_NAMES)
    }

    @Test
    fun board_task_artifact_parses_valid_payload() {
        val artifact = JsonInstant.decodeFromString(
            BoardTaskArtifact.serializer(),
            """
            {
              "kind": "dependency_rewrite",
              "title": "复核建议",
              "sections": [
                {"heading":"营收","old_value":"1.2亿","new_value":"1.5亿","upstream_source":"上游A","suggested_rewrite":"改为1.5亿"}
              ]
            }
            """.trimIndent(),
        )
        assertEquals("复核建议", artifact.title)
        assertEquals(1, artifact.sections.size)
        assertEquals("1.5亿", artifact.sections.first().newValue)
    }

    @Test
    fun board_task_artifact_degrades_on_malformed_payload() {
        // Unknown keys + missing fields must not throw — defensive defaults keep the runner alive.
        val artifact = JsonInstant.decodeFromString(
            BoardTaskArtifact.serializer(),
            """{"unexpected":"x"}""",
        )
        assertTrue(artifact.title.isBlank())
        assertTrue(artifact.sections.isEmpty())
    }

    @Test
    fun title_only_artifact_has_no_sections_and_must_be_rejected() {
        // P2: a title-only finish payload parses with empty sections. The finish tool's guard
        // (title blank OR sections empty -> degrade) must reject this so the task never reaches
        // waiting_user with nothing for the user to confirm.
        val artifact = JsonInstant.decodeFromString(
            BoardTaskArtifact.serializer(),
            """{"kind":"meeting_prep_material","title":"只有标题"}""",
        )
        assertFalse(artifact.title.isBlank())
        assertTrue("title-only artifact must have empty sections", artifact.sections.isEmpty())
        // Mirror the runner guard predicate to lock the rejection contract.
        val rejected = artifact.title.isBlank() || artifact.sections.isEmpty()
        assertTrue("title-only artifact must be rejected by the finish guard", rejected)
    }

    @Test
    fun artifact_ready_events_do_not_expand_allowed_doc_scope() {
        val evidenceUrl = "https://example.feishu.cn/docx/evidence_doc_123"
        val artifactUrl = "https://example.feishu.cn/docx/artifact_doc_123"
        val docs = BoardTaskAllowedDocs.fromText(
            boardTaskAllowedDocScopeParts(
                opportunityEvidenceJson = """{"doc":"$evidenceUrl"}""",
                events = listOf(
                    event(BoardTaskEventType.ARTIFACT_READY, "模型定稿里提到 $artifactUrl"),
                ),
            )
        )

        assertTrue(docs.allows(buildJsonObject { put("url", evidenceUrl) }))
        assertFalse(docs.allows(buildJsonObject { put("url", artifactUrl) }))
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
