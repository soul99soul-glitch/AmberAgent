package app.amber.feature.tools

import app.amber.ai.core.Tool

/**
 * P2-01 Capability model (docs/plans/2026-08-13-android-ios-capability-
 * parity-closure-plan.md §8 P2-01).
 *
 * A capability is the security domain a tool operates in. Tools are mapped to
 * capabilities in [capabilityForTool]; each capability declares a **risk
 * floor** — the minimum risk level its tools are evaluated at, so a low-level
 * global switch (plain "auto approve all tools") can never bypass a
 * high-risk capability (e.g. mcp.import). Per-capability policies
 * (disabled / ask / auto) are layered on top by the permission resolver.
 *
 * Domains below are the ones that actually exist in this repository — every
 * capability maps to real registered tools. `soul.update` currently maps the
 * agent prompt-config tool (the repo's persistent agent-config equivalent of
 * a SOUL file); P2-07 adds the SOUL.md-style update flow on top of it.
 */
enum class Capability(
    val id: String,
    /** Minimum risk level; tools of this capability are never treated lower. */
    val riskFloor: ToolRisk,
    /** Short user-facing group label (not localized, per repo convention). */
    val label: String,
) {
    FILESYSTEM_READ("filesystem.read", ToolRisk.Normal, "文件系统读取"),
    FILESYSTEM_WRITE("filesystem.write", ToolRisk.High, "文件系统写入"),
    WORKSPACE_DELETE("workspace.delete", ToolRisk.High, "Workspace 删除"),
    NETWORK_CONNECT("network.connect", ToolRisk.Sensitive, "网络连接"),
    MCP_IMPORT("mcp.import", ToolRisk.High, "MCP 导入"),
    MCP_TOOL("mcp.tool", ToolRisk.Sensitive, "MCP 工具调用"),
    SKILL_PROMOTE("skill.promote", ToolRisk.High, "Skill 晋级"),
    SOUL_UPDATE("soul.update", ToolRisk.High, "Agent 配置更新"),

    /**
     * P3-03: MiniApp "write and send" — a MiniApp can only trigger a real
     * send into a conversation when the user explicitly authorized it. The
     * risk floor is High so the plain (non-high-risk) auto-approve toggle
     * can never silently let a MiniApp send messages unattended.
     */
    MINIAPP_SEND("miniapp.send", ToolRisk.High, "MiniApp 写入并发送"),

    /**
     * P4-01: import / roll back / delete declarative recipes. High floor —
     * a recipe can orchestrate write primitives, so importing one must never
     * be auto-approved by the plain auto-approve toggle.
     */
    RECIPE_IMPORT("recipe.import", ToolRisk.High, "Recipe 导入"),

    /**
     * Provider 配置写入（apply / refresh_models / set_model_slot）。High floor —
     * 写凭据、端点、默认模型槽位属于高风险配置变更，普通 auto-approve 开关
     * 永远不能静默放行；只读的 provider_config_status 不映射到此 capability
     * （保持免审批）。
     */
    PROVIDER_CONFIG("provider.config", ToolRisk.High, "Provider 配置"),
    ;

    companion object {
        fun byId(id: String): Capability? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Per-capability user policy (P2-01). The stored value is nullable — a
 * capability without an explicit policy keeps the risk-floor behavior only
 * (no group-level override).
 */
enum class CapabilityPolicy {
    /** All tools of the capability are denied, regardless of global switches. */
    DISABLED,

    /** Every tool of the capability requires explicit per-call approval. */
    ASK,

    /** Tools of the capability are auto-approved (subject to the risk floor). */
    AUTO,
}

/**
 * Static tool → capability mapping. One capability per tool; unmapped tools
 * keep the pre-P2-01 permission behavior entirely (flag-off compatibility).
 *
 * Groups follow the plan §P2-01 list (filesystem.read, filesystem.write,
 * network.connect, mcp.import, skill.promote, soul.update, workspace.delete)
 * plus mcp.tool for `mcp_call_tool` — a real, distinct MCP domain.
 */
fun capabilityForTool(name: String): Capability? = when (name) {
    // ---- filesystem.read: workspace / external file reads ----
    "file_list", "file_read", "file_search", "pdf_read", "office_read",
    "image_info", "ocr_image", "archive_list", "external_file_read",
    "deep_read_playbook_read",
    -> Capability.FILESYSTEM_READ

    // ---- filesystem.write: file mutations (incl. downloads that write) ----
    "file_write", "file_edit", "file_move", "archive_create", "archive_extract",
    "image_convert", "external_file_write", "download_file", "pdf_render_page",
    "deep_read_write_analysis", "deep_read_write_diagram", "deep_read_write_extended_reading",
    "deep_read_write_narrative", "deep_read_write_overview", "deep_read_write_visuals",
    "deep_read_playbook_update", "deep_read_playbook_restore_default", "deep_read_playbook_restore_previous",
    -> Capability.FILESYSTEM_WRITE

    // ---- workspace.delete: destructive file removal ----
    "external_file_delete",
    -> Capability.WORKSPACE_DELETE

    // ---- network.connect: outbound network / logged-in web sessions ----
    "http_request", "search_web", "scrape_web",
    "webview_open", "webview_search_open", "webview_read", "webview_wait_for_load",
    "webview_find_text", "webview_links", "webview_open_link",
    "wm_open", "wm_get", "wm_state", "wm_observe", "wm_screenshot", "wm_visual_read",
    "wm_visual_snapshot", "wm_fetch_replay", "wm_wait", "wm_tab_list", "wm_tab_new",
    "wm_tab_close", "wm_back", "wm_forward", "wm_scroll", "wm_find", "wm_network_inspect",
    "wm_stations", "wm_recipe_candidates", "wm_signed_fetch", "wm_extract", "wm_site_add",
    "wm_site_remove", "wm_profile_synthesize", "wm_click", "wm_tap", "wm_type", "wm_keys",
    "wm_select", "wm_eval",
    -> Capability.NETWORK_CONNECT

    // ---- mcp.import: configure / import / test MCP server connections ----
    "mcp_import_from_skill", "mcp_test",
    -> Capability.MCP_IMPORT

    // ---- mcp.tool: invoke tools on an already-configured MCP server ----
    "mcp_call_tool" -> Capability.MCP_TOOL
    // ---- skill.promote: bring a skill into the installed library / replace or roll it back ----
    "skill_import", "skill_rollback",
    -> Capability.SKILL_PROMOTE

    // ---- soul.update: agent-driven changes to persistent agent config ----
    "agent_prompt_config", "soul_import", "soul_rollback",
    -> Capability.SOUL_UPDATE

    // ---- recipe.import: import / roll back / delete declarative recipes (P4-01) ----
    "recipe_import", "recipe_rollback", "recipe_delete",
    -> Capability.RECIPE_IMPORT

    // ---- provider.config: agent-driven provider / model-slot writes (High floor).
    // provider_config_status is intentionally unmapped so the read-only inventory
    // stays approval-free. ----
    "provider_config_apply", "provider_refresh_models", "settings_set_model_slot",
    -> Capability.PROVIDER_CONFIG

    // Expanded MCP entries keep the `mcp__server__tool` namespace at the
    // permission boundary; they must not become unclassified tools merely
    // because their provider-safe name is generated dynamically.
    else -> if (name.startsWith("mcp__")) Capability.MCP_TOOL else null
}

/** Capability of a tool definition (by stable tool name). */
fun Tool.capability(): Capability? = capabilityForTool(name)
