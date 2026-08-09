package app.amber.feature.tools

import app.amber.ai.core.InputSchema
import app.amber.ai.core.McpDiscoveredToolSpec
import app.amber.ai.core.Tool
import app.amber.ai.core.iosToolDeclarations
import app.amber.ai.core.mcpExpandedToolDeclarations
import app.amber.ai.ui.UIMessagePart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P0-a: iOS tool_search exposure bridge contract. The bridge wraps the shared
 * KMP ToolExposureState with the iOS resident policy and an ObjC-friendly API,
 * so Swift only ever sees String/Boolean/List<Tool>/List<String> shapes.
 */
class IosToolExposureBridgeTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Every tool name `iosToolDeclaration` can materialize (56 names). */
    private val fullIosToolNames: List<String> = listOf(
        "ask_user", "search_web", "scrape_web", "memory_tool",
        "workspace_file_read", "workspace_file_write", "workspace_file_edit",
        "workspace_file_list", "workspace_file_search", "workspace_file_move",
        "workspace_artifact_read", "workspace_artifact_delete",
        "generate_image",
        "wm_stations", "wm_tab_list", "wm_tab_new", "wm_tab_close", "wm_open",
        "wm_state", "wm_observe", "wm_extract", "wm_get", "wm_visual_snapshot",
        "wm_screenshot", "wm_back", "wm_forward", "wm_clear_session", "wm_site_add",
        "wm_site_remove", "wm_click", "wm_tap", "wm_type", "wm_keys", "wm_scroll",
        "wm_select", "wm_find", "wm_wait",
        "mcp_call", "mcp_list", "mcp_test", "mcp_describe_tool", "mcp_import_from_skill",
        "skills_list", "use_skill", "skill_validate", "skill_import", "skill_enable", "skill_disable",
        "subagent_dispatch", "model_council_run", "file_read_selected",
        "ish_handoff", "ios_ish_execute",
        "permissions_status", "tools_list", "subagent_report",
    )

    /** The pinned iOS resident policy set (see IosToolExposureBridge.kt). */
    private val iosResidentNames = setOf(
        "tool_search", "tools_list", "ask_user", "permissions_status", "memory_tool",
        "search_web", "scrape_web", "generate_image",
        "workspace_file_read", "workspace_file_write", "workspace_file_edit",
        "workspace_file_list", "workspace_file_search", "workspace_file_move",
        "workspace_artifact_read", "workspace_artifact_delete",
        "mcp_list", "mcp_call", "mcp_describe_tool",
        "skills_list", "use_skill",
        "subagent_dispatch", "model_council_run",
        "file_read_selected",
    )

    private val deferredNames = setOf(
        "wm_stations", "wm_click", "wm_type", "wm_screenshot",
        "ish_handoff", "ios_ish_execute",
        "mcp_test", "mcp_import_from_skill",
        "skill_validate", "skill_import", "skill_enable", "skill_disable",
        "subagent_report",
    )

    private fun fullIosTools(): List<Tool> = iosToolDeclarations(fullIosToolNames)

    private fun parseObject(text: String): JsonObject =
        json.parseToJsonElement(text).jsonObject

    @Test
    fun fullCatalogEnablesLazyModeAndExposesOnlyResidentTools() {
        val bridge = IosToolExposureBridge(tools = fullIosTools())

        assertTrue(bridge.lazyModeEnabled(), "56 declared tools must exceed the 40-tool lazy threshold")

        val visible = bridge.visibleTools().map { it.name }.toSet()
        assertEquals(iosResidentNames, visible, "first round must expose exactly the resident set (incl. tool_search)")
        deferredNames.forEach { assertFalse(it in visible, "$it must be deferred until tool_search exposes it") }
    }

    @Test
    fun smallCatalogBypassesLazyMode() {
        val lightTools = iosToolDeclarations(
            listOf(
                "search_web", "scrape_web", "memory_tool", "ask_user",
                "workspace_file_read", "workspace_file_list",
                "mcp_call", "mcp_list", "skills_list", "use_skill",
                "subagent_dispatch", "model_council_run",
            )
        )
        val bridge = IosToolExposureBridge(tools = lightTools)

        assertFalse(bridge.lazyModeEnabled())
        val visible = bridge.visibleTools().map { it.name }.toSet()
        assertEquals(
            lightTools.map { it.name }.toSet() + TOOL_SEARCH_TOOL_NAME,
            visible,
            "below threshold every declared tool plus tool_search must be visible",
        )
    }

    @Test
    fun executeToolSearchExpandsDeferredToolForNextStep() {
        val bridge = IosToolExposureBridge(tools = fullIosTools())
        assertFalse("wm_type" in bridge.visibleTools().map { it.name })

        val payload = parseObject(
            bridge.executeToolSearch("""{"query":"wm_type","limit":1}""")
        )

        assertEquals("ok", payload["status"]?.jsonPrimitive?.contentOrNull)
        val expanded = payload["expanded_tools"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull }
        assertTrue("wm_type" in expanded, "search must return the deferred wm_type tool")
        assertTrue("wm_type" in bridge.visibleTools().map { it.name }, "hit must be exposed for the next model step")
    }

    @Test
    fun executeToolSearchReturnsErrorPayloadOnInvalidArguments() {
        val bridge = IosToolExposureBridge(tools = fullIosTools())

        val errorPayload = parseObject(bridge.executeToolSearch("not-json"))

        assertEquals("error", errorPayload["status"]?.jsonPrimitive?.contentOrNull)
        val visibleBefore = bridge.visibleTools().map { it.name }.toSet()
        assertEquals(iosResidentNames, visibleBefore, "failed search must not alter exposure")
    }

    // MARK: - M5: tools_list 本地执行（discovery 引导引用了它，iOS 必须声明+可执行）

    @Test
    fun toolsListDeclarationExistsInCatalog() {
        val declared = iosToolDeclarations(listOf("tools_list"))
        assertEquals(listOf("tools_list"), declared.map { it.name })
    }

    @Test
    fun executeToolsListReturnsFullCatalogNameDescriptionList() {
        val bridge = IosToolExposureBridge(tools = fullIosTools())

        val payload = parseObject(bridge.executeToolsList())

        assertEquals("ok", payload["status"]?.jsonPrimitive?.contentOrNull)
        val tools = payload["tools"]!!.jsonArray
        val fullNames = (fullIosTools().map { it.name } + TOOL_SEARCH_TOOL_NAME).toSet()
        assertEquals(fullNames.size, tools.size, "tools_list 必须返回桥全目录（含 tool_search），不裁剪")
        val names = tools.map { it.jsonObject["name"]!!.jsonPrimitive.contentOrNull }.toSet()
        assertEquals(fullNames, names, "返回清单必须覆盖全目录")
        val sample = tools.first { it.jsonObject["name"]!!.jsonPrimitive.contentOrNull == "wm_type" }.jsonObject
        assertTrue(
            sample["description"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true,
            "每项必须携带非空 description（模型靠它识别工具）",
        )
        // 目录/调试视图不暴露 schema——与 tool_search 的 callability 契约一致。
        assertTrue(payload["tools"]!!.jsonArray.all { it.jsonObject["schema"] == null })
    }

    @Test
    fun defaultResidentPolicyKeepsAndroidBehavior() {
        // Android-style names with the DEFAULT policy (no residentPolicy passed):
        // file_read / terminal_execute / mcp_call_tool stay resident, synthetic
        // hidden tools stay hidden — identical to the pre-P0-a contract.
        val hiddenTools = (0 until 45).map { tool("hidden_tool_$it", "Hidden capability $it") }
        val registry = ToolRegistry.from(
            hiddenTools + tool("file_read", "Read workspace file.") +
                tool("terminal_execute", "Run a terminal command.") +
                tool("mcp_call_tool", "Call an MCP server tool.")
        )
        val searchTool = createToolSearchTool(registry)
        val exposure = ToolExposureState.from(
            hiddenTools + tool("file_read", "Read workspace file.") +
                tool("terminal_execute", "Run a terminal command.") +
                tool("mcp_call_tool", "Call an MCP server tool.") + searchTool
        )

        assertTrue(exposure.enabled)
        val visible = exposure.toolsForStep().map { it.name }.toSet()
        assertTrue("file_read" in visible)
        assertTrue("terminal_execute" in visible)
        assertTrue("mcp_call_tool" in visible)
        assertFalse("hidden_tool_7" in visible)
    }

    @Test
    fun lazyBridgeDiscoveryGuidanceTellsModelToSearchBeforeHiddenToolsAreCallable() {
        val bridge = IosToolExposureBridge(tools = fullIosTools())
        assertTrue(bridge.lazyModeEnabled())

        val guidance = bridge.discoveryGuidance()

        assertTrue(
            "not callable until" in guidance,
            "lazy guidance must carry the tool_search discovery contract, got: $guidance",
        )
        assertTrue(TOOL_SEARCH_TOOL_NAME in guidance)
        // 管线闭环：必须含行为规则——声称做不到之前先搜索（真机反馈的断链点）。
        assertTrue(
            "never claim inability" in guidance,
            "guidance must tell the model to search before claiming inability, got: $guidance",
        )
    }

    @Test
    fun bypassBridgeDiscoveryGuidanceIsEmpty() {
        val lightTools = iosToolDeclarations(
            listOf(
                "search_web", "scrape_web", "memory_tool", "ask_user",
                "workspace_file_read", "workspace_file_list",
                "mcp_call", "mcp_list", "skills_list", "use_skill",
                "subagent_dispatch", "model_council_run",
            )
        )
        val bridge = IosToolExposureBridge(tools = lightTools)
        assertFalse(bridge.lazyModeEnabled())

        assertEquals("", bridge.discoveryGuidance(), "non-lazy runs need no discovery guidance")
    }

    @Test
    fun savingsSummaryReportsShapeAndCounts() {
        val bridge = IosToolExposureBridge(tools = fullIosTools())

        val summary = parseObject(bridge.savingsSummary())

        assertTrue(summary["lazy"]!!.jsonPrimitive.contentOrNull == "true")
        assertEquals(fullIosTools().size + 1, summary["total_tools"]?.jsonPrimitive?.intOrNull)
        assertEquals(iosResidentNames.size, summary["visible_tools"]?.jsonPrimitive?.intOrNull)
        assertTrue(summary["estimated_full_schema_chars"]?.jsonPrimitive?.intOrNull!! > 0)
        assertTrue(summary["estimated_visible_schema_chars"]?.jsonPrimitive?.intOrNull!! > 0)
        assertTrue(
            summary["estimated_visible_schema_chars"]!!.jsonPrimitive.intOrNull!! <
                summary["estimated_full_schema_chars"]!!.jsonPrimitive.intOrNull!!,
        )
    }

    @Test
    fun bridgeCanBeRebuiltFromAnotherBridgesVisibleTools() {
        // The run coordinator and the background job both rebuild bridges from
        // already-filtered lists that already contain tool_search. Appending a
        // second tool_search must not throw (ToolRegistry.from rejects
        // duplicates), and the rebuild must keep every visible tool visible.
        val first = IosToolExposureBridge(tools = fullIosTools())
        val visible = first.visibleTools()

        val rebuilt = IosToolExposureBridge(tools = visible)

        assertEquals(visible.map { it.name }.toSet(), rebuilt.visibleTools().map { it.name }.toSet())
    }

    // MARK: - P0-b/P0-c: expanded MCP tools are deferred and cost nothing visible

    /** Two servers x ten tools each, with real per-tool schemas. */
    private fun mcpExpandedToolsFixture(): List<Tool> {
        val schemas = listOf(
            """{"type":"object","properties":{"query":{"type":"string","description":"search query"}},"required":["query"]}""",
            """{"type":"object","properties":{"path":{"type":"string","description":"path to operate on"}},"required":["path"]}""",
        )
        return listOf("alpha", "beta").flatMap { server ->
            mcpExpandedToolDeclarations(
                server,
                (0 until 10).map { index ->
                    McpDiscoveredToolSpec(
                        "tool_$index",
                        "MCP tool $index on server $server operates on ${schemas[index % 2]}",
                        schemas[index % 2],
                    )
                },
            )
        }
    }

    @Test
    fun expandedMcpToolsStayDeferredUntilToolSearchAndDoNotGrowVisibleSchema() {
        val base = IosToolExposureBridge(tools = fullIosTools())
        val withMcp = IosToolExposureBridge(tools = fullIosTools() + mcpExpandedToolsFixture())

        assertTrue(withMcp.lazyModeEnabled(), "20 extra declarations must keep the run in lazy mode")
        assertEquals(
            base.fullToolDeclarations().size + 20,
            withMcp.fullToolDeclarations().size,
            "every expanded MCP tool must reach the bridge's full catalog",
        )

        // First round: no expanded MCP tool is visible.
        val visibleNames = withMcp.visibleTools().map { it.name }.toSet()
        assertTrue(
            visibleNames.none { it.startsWith("mcp__") },
            "expanded MCP tools must be deferred behind tool_search on the first round",
        )
        assertEquals(
            base.visibleTools().map { it.name }.toSet(),
            visibleNames,
            "deferred additions must not change the first-round visible set",
        )

        // Savings metric: adding deferred tools must not grow the visible
        // schema footprint; the full footprint grows by their schemas.
        val baseSummary = parseObject(base.savingsSummary())
        val withMcpSummary = parseObject(withMcp.savingsSummary())
        assertEquals(
            baseSummary["estimated_visible_schema_chars"],
            withMcpSummary["estimated_visible_schema_chars"],
            "deferred expanded tools must not grow the visible schema chars",
        )
        assertTrue(
            withMcpSummary["estimated_full_schema_chars"]!!.jsonPrimitive.intOrNull!! >
                baseSummary["estimated_full_schema_chars"]!!.jsonPrimitive.intOrNull!!,
            "the full catalog footprint must include the expanded MCP schemas",
        )

        // tool_search with an exact expanded name exposes it for the next step.
        val payload = parseObject(withMcp.executeToolSearch("""{"query":"mcp__alpha__tool_3","limit":5}"""))
        val expanded = payload["expanded_tools"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull }
        assertTrue("mcp__alpha__tool_3" in expanded, "exact-name tool_search must hit a deferred MCP tool")
        assertTrue(
            "mcp__alpha__tool_3" in withMcp.visibleTools().map { it.name },
            "the hit must be callable on the next model step",
        )
    }

    private fun tool(
        name: String,
        description: String = "test tool",
    ) = Tool(
        name = name,
        description = description,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "query")
                    })
                }
            )
        },
        execute = { listOf(UIMessagePart.Text("ok")) },
    )
}
