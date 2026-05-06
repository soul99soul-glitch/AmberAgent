package me.rerere.rikkahub.data.agent.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {
    @Test
    fun duplicateToolNamesAreRejected() {
        val tools = listOf(stubTool("same"), stubTool("same"))

        val error = runCatching { ToolRegistry.from(tools) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("Duplicate tool names"))
    }

    @Test
    fun registryEnforcesOutputBudget() = runBlocking {
        val registry = ToolRegistry.from(
            listOf(
                stubTool("large_output") {
                    listOf(UIMessagePart.Text("x".repeat(90_000)))
                }
            )
        )

        val output = registry.tools().single().execute(JsonObject(emptyMap()))
        val text = (output.single() as UIMessagePart.Text).text

        assertTrue(text.length < 90_000)
        val payload = Json.parseToJsonElement(text).jsonObject
        assertEquals("truncated", payload["status"]!!.jsonPrimitive.content)
        assertEquals("true", payload["truncated"]!!.jsonPrimitive.content)
    }

    @Test
    fun mutatingToolsArePromotedToApproval() {
        val registry = ToolRegistry.from(
            listOf(
                stubTool("http_request"),
                stubTool("memory_tool"),
                stubTool("pdf_render_page"),
            )
        )

        assertTrue(!registry.metadata.single { it.name == "http_request" }.mutates)
        assertTrue(registry.metadata.single { it.name == "memory_tool" }.mutates)
        assertTrue(registry.metadata.single { it.name == "pdf_render_page" }.mutates)
        registry.metadata.forEach { metadata ->
            assertTrue(metadata.needsApproval)
            assertTrue(!metadata.autoApprovable)
        }
        registry.tools().forEach { tool ->
            assertTrue(tool.needsApproval)
            assertTrue(!tool.allowsAutoApproval)
        }
    }

    @Test
    fun registryMetadataIncludesCategoryAndBudget() {
        val registry = ToolRegistry.from(listOf(stubTool("file_read")))

        val metadata = registry.metadata.single()

        assertEquals("workspace", metadata.category)
        assertEquals(FILE_READ_HARD_MAX_CHARS + 2_048, metadata.outputBudgetChars)
    }

    @Test
    fun dynamicPolicyDistinguishesHttpMethods() {
        val registry = ToolRegistry.from(listOf(stubTool("http_request")))

        val getPolicy = registry.evaluateInvocation(
            "http_request",
            Json.parseToJsonElement("""{"method":"GET","url":"https://example.com"}""")
        )!!
        val postPolicy = registry.evaluateInvocation(
            "http_request",
            Json.parseToJsonElement("""{"method":"POST","url":"https://example.com"}""")
        )!!

        assertTrue(!getPolicy.needsApproval)
        assertTrue(!getPolicy.mutates)
        assertEquals(ToolRisk.Normal, getPolicy.risk)
        assertTrue(postPolicy.needsApproval)
        assertTrue(postPolicy.mutates)
        assertEquals(ToolRisk.High, postPolicy.risk)
    }

    @Test
    fun dynamicPolicyDistinguishesMemoryOperations() {
        val registry = ToolRegistry.from(listOf(stubTool("memory_tool")))

        val readPolicy = registry.evaluateInvocation(
            "memory_tool",
            Json.parseToJsonElement("""{"operation":"search","query":"Q代"}""")
        )!!
        val writePolicy = registry.evaluateInvocation(
            "memory_tool",
            Json.parseToJsonElement("""{"operation":"delete","id":"memory-1"}""")
        )!!

        assertTrue(!readPolicy.needsApproval)
        assertTrue(!readPolicy.mutates)
        assertEquals(ToolRisk.Normal, readPolicy.risk)
        assertTrue(writePolicy.needsApproval)
        assertTrue(writePolicy.mutates)
        assertEquals(ToolRisk.High, writePolicy.risk)
    }

    @Test
    fun cronTaskToolsUseCronCategoryAndApprovalForMutations() {
        val registry = ToolRegistry.from(
            listOf(
                stubTool("cron_task_list"),
                stubTool("cron_task_create"),
                stubTool("cron_task_update"),
                stubTool("cron_task_delete"),
            )
        )

        assertEquals(List(4) { "cron" }, registry.metadata.map { it.category })
        assertTrue(!registry.metadata.single { it.name == "cron_task_list" }.needsApproval)
        listOf("cron_task_create", "cron_task_update", "cron_task_delete").forEach { name ->
            val metadata = registry.metadata.single { it.name == name }
            assertTrue(metadata.mutates)
            assertTrue(metadata.needsApproval)
            assertTrue(metadata.autoApprovable)
        }
    }

    @Test
    fun agentTaskToolsUseTaskCategory() {
        val registry = ToolRegistry.from(
            listOf(
                stubTool("agent_task_list"),
                stubTool("agent_task_read"),
                stubTool("agent_task_cancel", needsApproval = true),
                stubTool("agent_task_retry", needsApproval = true),
                stubTool("agent_task_cleanup", needsApproval = true),
                stubTool("agent_runtime_status"),
                stubTool("tool_policy_explain"),
            )
        )

        assertEquals("task", registry.metadata.single { it.name == "agent_runtime_status" }.category)
        assertEquals("utility", registry.metadata.single { it.name == "tool_policy_explain" }.category)
        assertTrue(!registry.metadata.single { it.name == "agent_task_list" }.mutates)
        assertTrue(registry.metadata.single { it.name == "agent_task_cancel" }.mutates)
        assertTrue(registry.metadata.single { it.name == "agent_task_retry" }.mutates)
        assertTrue(registry.metadata.single { it.name == "agent_task_cleanup" }.needsApproval)
        assertTrue(registry.evaluateInvocation("agent_task_cancel")!!.concurrencySafe)
        assertTrue(registry.evaluateInvocation("agent_runtime_status")!!.concurrencySafe)
        assertTrue(!registry.evaluateInvocation("agent_task_retry")!!.speculativeEligible)
    }

    @Test
    fun policyIncludesParallelAndForegroundHints() {
        val registry = ToolRegistry.from(
            listOf(
                stubTool("file_read"),
                stubTool("officepro_read_screen"),
            )
        )

        val fileRead = registry.evaluateInvocation("file_read")!!
        val officeRead = registry.evaluateInvocation("officepro_read_screen")!!

        assertEquals("workspace", fileRead.parallelGroup)
        assertTrue(fileRead.requiresForegroundAppPackage == null)
        assertTrue(fileRead.speculativeEligible)
        assertEquals("configured_officepro_target", officeRead.requiresForegroundAppPackage)
        assertTrue(officeRead.parallelGroup == null)
        assertTrue(!officeRead.speculativeEligible)
        assertEquals("risk_not_normal", officeRead.speculativeBlockReason)
    }

    @Test
    fun officeProToolsUseOfficeCategory() {
        val registry = ToolRegistry.from(
            listOf(
                stubTool("officepro_dashboard"),
                stubTool("officepro_capture_context"),
                stubTool("officepro_make_report", needsApproval = true),
                stubTool("officepro_daily_radar"),
                stubTool("officepro_project_briefing"),
                stubTool("officepro_document_warroom"),
                stubTool("officepro_open_items_radar"),
                stubTool("officepro_meeting_closure"),
                stubTool("officepro_create_task_draft"),
                stubTool("officepro_create_base_record_draft"),
                stubTool("officepro_reply_draft"),
                stubTool("officepro_project_list"),
                stubTool("officepro_project_update", needsApproval = true),
                stubTool("officepro_project_context"),
                stubTool("officepro_project_report"),
            )
        )

        assertEquals(List(15) { "office" }, registry.metadata.map { it.category })
        assertTrue(!registry.metadata.single { it.name == "officepro_capture_context" }.mutates)
        assertTrue(registry.metadata.single { it.name == "officepro_capture_context" }.needsApproval)
        assertTrue(!registry.metadata.single { it.name == "officepro_capture_context" }.autoApprovable)
        assertTrue(registry.metadata.single { it.name == "officepro_make_report" }.needsApproval)
        assertTrue(registry.metadata.single { it.name == "officepro_make_report" }.mutates)
        assertTrue(!registry.metadata.single { it.name == "officepro_daily_radar" }.mutates)
        assertTrue(registry.metadata.single { it.name == "officepro_daily_radar" }.needsApproval)
        assertTrue(!registry.metadata.single { it.name == "officepro_document_warroom" }.autoApprovable)
        assertTrue(registry.metadata.single { it.name == "officepro_project_update" }.mutates)
        assertTrue(registry.metadata.single { it.name == "officepro_project_update" }.needsApproval)
        assertTrue(registry.metadata.single { it.name == "officepro_project_context" }.needsApproval)
        assertTrue(!registry.metadata.single { it.name == "officepro_project_report" }.mutates)
        assertTrue(registry.metadata.single { it.name == "officepro_open_items_radar" }.needsApproval)
        assertTrue(registry.metadata.single { it.name == "officepro_meeting_closure" }.needsApproval)
        assertTrue(registry.metadata.single { it.name == "officepro_create_task_draft" }.needsApproval)
        assertTrue(registry.metadata.single { it.name == "officepro_create_base_record_draft" }.needsApproval)
        assertTrue(registry.metadata.single { it.name == "officepro_reply_draft" }.needsApproval)
        assertTrue(!registry.metadata.single { it.name == "officepro_create_task_draft" }.mutates)
        assertTrue(!registry.metadata.single { it.name == "officepro_reply_draft" }.autoApprovable)
    }

    @Test
    fun conversationContextToolsUseContextCategory() {
        val registry = ToolRegistry.from(
            listOf(
                stubTool("conversation_context_status"),
                stubTool("conversation_search"),
                stubTool("conversation_expand"),
                stubTool("conversation_compact"),
                stubTool("session_list"),
                stubTool("session_search"),
                stubTool("session_read", needsApproval = true),
                stubTool("session_expand", needsApproval = true),
            )
        )

        assertEquals(List(8) { "context" }, registry.metadata.map { it.category })
        assertTrue(registry.metadata.single { it.name == "conversation_compact" }.mutates)
        assertTrue(!registry.metadata.single { it.name == "session_search" }.needsApproval)
        assertTrue(registry.metadata.single { it.name == "session_read" }.needsApproval)
        assertTrue(registry.metadata.single { it.name == "session_read" }.autoApprovable)
        assertTrue(registry.metadata.single { it.name == "session_read" }.sensitiveRead)
        assertEquals(ToolRisk.Sensitive, registry.metadata.single { it.name == "session_expand" }.risk)
    }

    @Test
    fun subAgentToolsUseSubAgentCategory() {
        val registry = ToolRegistry.from(
            listOf(
                stubTool("subagent_list"),
                stubTool("subagent_start"),
                stubTool("subagent_read"),
                stubTool("subagent_wait"),
                stubTool("subagent_cancel"),
            )
        )

        assertEquals(List(5) { "subagent" }, registry.metadata.map { it.category })
        assertTrue(registry.metadata.single { it.name == "subagent_start" }.mutates)
        assertTrue(registry.metadata.single { it.name == "subagent_cancel" }.mutates)
        assertTrue(!registry.metadata.single { it.name == "subagent_read" }.mutates)
    }

    @Test
    fun modelCouncilToolsUseCouncilCategoryAndReportApproval() {
        val registry = ToolRegistry.from(
            listOf(
                stubTool("model_council_status"),
                stubTool("model_council_start"),
                stubTool("model_council_read"),
                stubTool("model_council_wait"),
                stubTool("model_council_cancel"),
                stubTool("model_council_make_report", needsApproval = true, allowsAutoApproval = false),
            )
        )

        assertEquals(List(6) { "model_council" }, registry.metadata.map { it.category })
        assertTrue(!registry.metadata.single { it.name == "model_council_start" }.mutates)
        assertTrue(registry.metadata.single { it.name == "model_council_make_report" }.mutates)
        assertTrue(registry.metadata.single { it.name == "model_council_make_report" }.needsApproval)
        assertTrue(!registry.metadata.single { it.name == "model_council_make_report" }.autoApprovable)
    }

    @Test
    fun externalFileToolsUseExternalCategoryAndApproval() {
        val registry = ToolRegistry.from(
            listOf(
                stubTool("external_file_list"),
                stubTool("external_file_read"),
                stubTool("external_file_write", needsApproval = true),
                stubTool("external_file_delete", needsApproval = true),
            )
        )

        assertEquals(List(4) { "external_file" }, registry.metadata.map { it.category })
        assertTrue(!registry.metadata.single { it.name == "external_file_read" }.mutates)
        assertTrue(registry.metadata.single { it.name == "external_file_write" }.needsApproval)
        assertTrue(!registry.metadata.single { it.name == "external_file_write" }.autoApprovable)
    }

    @Test
    fun mcpCallToolUsesMcpCategoryAndAutoApprovableApproval() {
        val registry = ToolRegistry.from(
            listOf(
                stubTool("mcp_list"),
                stubTool("mcp_call_tool", needsApproval = true),
            )
        )

        val listMetadata = registry.metadata.single { it.name == "mcp_list" }
        val callMetadata = registry.metadata.single { it.name == "mcp_call_tool" }
        val policy = registry.evaluateInvocation("mcp_call_tool")!!

        assertEquals("mcp", listMetadata.category)
        assertTrue(!listMetadata.needsApproval)
        assertEquals("mcp", callMetadata.category)
        assertTrue(callMetadata.mutates)
        assertTrue(callMetadata.needsApproval)
        assertTrue(callMetadata.autoApprovable)
        assertEquals(ToolRisk.Sensitive, callMetadata.risk)
        assertTrue(policy.needsApproval)
        assertTrue(policy.mutates)
        assertTrue(!policy.concurrencySafe)
    }

    private fun stubTool(
        name: String,
        needsApproval: Boolean = false,
        allowsAutoApproval: Boolean = true,
        execute: suspend (kotlinx.serialization.json.JsonElement) -> List<UIMessagePart> = {
            listOf(UIMessagePart.Text("ok"))
        },
    ) = Tool(
        name = name,
        description = "test tool",
        needsApproval = needsApproval,
        allowsAutoApproval = allowsAutoApproval,
        execute = execute,
    )
}
