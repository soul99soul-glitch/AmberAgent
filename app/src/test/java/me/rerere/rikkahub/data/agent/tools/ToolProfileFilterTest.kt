package me.rerere.rikkahub.data.agent.tools

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MainAgentToolProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolProfileFilterTest {
    @Test
    fun fullProfilePreservesAllTools() {
        val tools = listOf(tool("file_read"), tool("terminal_execute"), tool("wm_eval"))

        val result = ToolProfileFilter.filter(tools, MainAgentToolProfile.FULL)

        assertEquals(tools.map { it.name }, result.tools.map { it.name })
        assertEquals(0, result.filteredCount)
    }

    @Test
    fun minimalProfileKeepsOnlyCoreUtilityTools() {
        val result = ToolProfileFilter.filter(
            listOf(
                tool("get_time_info"),
                tool("permissions_status"),
                tool("conversation_context_status"),
                tool("agent_runtime_status"),
                tool("file_read"),
                tool("search_web"),
            ),
            MainAgentToolProfile.MINIMAL,
        )
        val names = result.tools.map { it.name }.toSet()

        assertTrue("get_time_info" in names)
        assertTrue("permissions_status" in names)
        assertTrue("conversation_context_status" in names)
        assertTrue("agent_runtime_status" in names)
        assertFalse("file_read" in names)
        assertFalse("search_web" in names)
    }

    @Test
    fun webReadProfileExcludesInteractionAndEvalTools() {
        val result = ToolProfileFilter.filter(
            listOf(
                tool("search_web"),
                tool("scrape_web"),
                tool("webview_read"),
                tool("wm_observe"),
                tool("feishu_docs_read"),
                tool("feishu_docs_create"),
                tool("http_request"),
                tool("wm_signed_fetch"),
                tool("wm_click"),
                tool("wm_type"),
                tool("wm_keys"),
                tool("wm_select"),
                tool("wm_eval"),
            ),
            MainAgentToolProfile.WEB_READ,
        )
        val names = result.tools.map { it.name }.toSet()

        assertTrue("search_web" in names)
        assertTrue("webview_read" in names)
        assertTrue("wm_observe" in names)
        assertTrue("feishu_docs_read" in names)
        assertFalse("feishu_docs_create" in names)
        assertFalse("http_request" in names)
        assertFalse("wm_signed_fetch" in names)
        assertFalse("wm_click" in names)
        assertFalse("wm_type" in names)
        assertFalse("wm_keys" in names)
        assertFalse("wm_select" in names)
        assertFalse("wm_eval" in names)
    }

    @Test
    fun workspaceReadProfileExcludesWriteEditMoveDeleteAndTerminal() {
        val result = ToolProfileFilter.filter(
            listOf(
                tool("file_list"),
                tool("file_read"),
                tool("file_search"),
                tool("archive_list"),
                tool("archive_extract"),
                tool("image_info"),
                tool("image_convert"),
                tool("external_file_read"),
                tool("file_write"),
                tool("file_edit"),
                tool("file_move"),
                tool("external_file_delete"),
                tool("terminal_execute"),
            ),
            MainAgentToolProfile.WORKSPACE_READ,
        )
        val names = result.tools.map { it.name }.toSet()

        assertTrue("file_read" in names)
        assertTrue("file_search" in names)
        assertTrue("archive_list" in names)
        assertTrue("image_info" in names)
        assertTrue("external_file_read" in names)
        assertFalse("archive_extract" in names)
        assertFalse("image_convert" in names)
        assertFalse("file_write" in names)
        assertFalse("file_edit" in names)
        assertFalse("file_move" in names)
        assertFalse("external_file_delete" in names)
        assertFalse("terminal_execute" in names)
    }

    @Test
    fun codingProfileKeepsWorkspaceTerminalMcpAndSkillTools() {
        val result = ToolProfileFilter.filter(
            listOf(
                tool("file_write"),
                tool("terminal_execute"),
                tool("mcp__repo_search"),
                tool("skill_enable_writer"),
                tool("screen_screenshot"),
            ),
            MainAgentToolProfile.CODING,
        )
        val names = result.tools.map { it.name }.toSet()

        assertTrue("file_write" in names)
        assertTrue("terminal_execute" in names)
        assertTrue("mcp__repo_search" in names)
        assertTrue("skill_enable_writer" in names)
        assertFalse("screen_screenshot" in names)
    }

    @Test
    fun nonFullProfilesFilterLateAddedOrchestrationTools() {
        val tools = listOf(
            tool("get_time_info"),
            tool("subagent_start"),
            tool("model_council_start"),
        )

        val minimalNames = ToolProfileFilter.filter(tools, MainAgentToolProfile.MINIMAL)
            .tools
            .map { it.name }
            .toSet()
        val codingNames = ToolProfileFilter.filter(tools, MainAgentToolProfile.CODING)
            .tools
            .map { it.name }
            .toSet()

        assertEquals(setOf("get_time_info"), minimalNames)
        assertEquals(setOf("get_time_info"), codingNames)
    }

    private fun tool(name: String) = Tool(
        name = name,
        description = "test tool",
        execute = { listOf(UIMessagePart.Text("ok")) },
    )
}
