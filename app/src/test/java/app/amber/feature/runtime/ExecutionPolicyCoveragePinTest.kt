package app.amber.feature.runtime

import app.amber.core.ai.tools.TOOL_PROVIDER_CONFIG_APPLY
import app.amber.core.ai.tools.TOOL_PROVIDER_CONFIG_STATUS
import app.amber.core.ai.tools.TOOL_PROVIDER_REFRESH_MODELS
import app.amber.core.ai.tools.TOOL_SETTINGS_SET_MODEL_SLOT
import app.amber.feature.tools.Capability
import app.amber.feature.tools.capabilityForTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Coverage pin for the ExecutionPolicyGate sandbox tables. The gate is a
 * dormant seam in production (every run uses [ExecutionPolicy.permissive]),
 * and its per-dimension coverage is opt-in: a tool absent from
 * [ExecutionPolicyGate.FILE_PATH_ARGS], [ExecutionPolicyGate.NETWORK_URL_ARGS]
 * and `capabilityForTool` (and outside the `terminal_` shell prefix) is not
 * constrained by any narrowed-policy dimension at all — silently.
 *
 * These tests make the silent case loud, in both directions:
 *  1. each table's key set is pinned exactly (no silent removal, no silent
 *     addition — an edit here forces a conscious pin update);
 *  2. every production `Tool(name = "...")` literal under the app/feature
 *     source roots (same scan as ToolApprovalPolicyLintTest) must be covered
 *     by some dimension OR by [EXPECTED_KNOWN_UNMAPPED_TOOLS] — so a newly
 *     added tool that skips all tables fails here until it is mapped or its
 *     exemption is written down.
 *
 * Known INTENTIONAL exemptions, kept explicit here instead of in the tables:
 *  - `wm_eval`: capability-mapped to network.connect, but it takes a script,
 *    not a URL, so its logged-in-session fetches stay invisible to the domain
 *    dimension (ExecutionPolicy.kt known-bypasses entry).
 *  - MiniApp workspace bridge: not an agent `Tool` at all (never scanned).
 *  - JsCell nested calls: the nested resolver admits `get_time_info` only.
 *  - `webview_open_link` index-only calls: fail closed under an active domain
 *    dimension (denial boundary, not a bypass).
 *
 * The scanner sees tool-name literals only; dynamic names (the
 * `mcp__server__tool` family) are covered by capabilityForTool's prefix rule
 * and intentionally absent from these sets.
 */
class ExecutionPolicyCoveragePinTest {

    // ── exact key-set pins (PolicyGate tables + the fail-closed exemption list) ──

    @Test
    fun `filePathArgs key set is pinned`() {
        assertEquals(EXPECTED_FILE_PATH_ARGS, ExecutionPolicyGate.FILE_PATH_ARGS.keys)
    }

    @Test
    fun `networkUrlArgs key set is pinned`() {
        assertEquals(EXPECTED_NETWORK_URL_ARGS, ExecutionPolicyGate.NETWORK_URL_ARGS.keys)
    }

    @Test
    fun `unmodeled root path tools exemption set is pinned`() {
        assertEquals(EXPECTED_UNMODELED_ROOT_PATH_TOOLS, ExecutionPolicyGate.UNMODELED_ROOT_PATH_TOOLS.keys)
    }

    // ── capabilityForTool: per-capability pinned name sets ──────────────────

    @Test
    fun `capabilityForTool mapping per capability is pinned`() {
        val names = productionToolNames()
        assertTrue("scanner found no Tool( blocks; the pin would be a no-op", names.isNotEmpty())
        Capability.entries.forEach { capability ->
            assertEquals(
                "capabilityForTool → ${capability.id}",
                EXPECTED_CAPABILITY_TOOLS[capability].orEmpty(),
                names.filter { capabilityForTool(it) == capability }.toSet(),
            )
        }
        // Constant-named tools (name = TOOL_* constants) are invisible to the
        // scanner; pin their capability mapping explicitly instead.
        assertEquals(Capability.PROVIDER_CONFIG, capabilityForTool(TOOL_PROVIDER_CONFIG_APPLY))
        assertEquals(Capability.PROVIDER_CONFIG, capabilityForTool(TOOL_PROVIDER_REFRESH_MODELS))
        assertEquals(Capability.PROVIDER_CONFIG, capabilityForTool(TOOL_SETTINGS_SET_MODEL_SLOT))
        // The read-only status inventory stays intentionally unmapped.
        assertEquals(null, capabilityForTool(TOOL_PROVIDER_CONFIG_STATUS))
    }

    // ── the differential: production tools vs all tables + exemptions ───────

    @Test
    fun `every production tool is dimension-covered or in the known-unmapped pin`() {
        val names = productionToolNames()
        assertTrue("scanner found no Tool( blocks; the pin would be a no-op", names.isNotEmpty())
        val covered = names.filterTo(mutableSetOf()) { name ->
            ExecutionPolicyGate.FILE_PATH_ARGS.containsKey(name) ||
                ExecutionPolicyGate.UNMODELED_ROOT_PATH_TOOLS.containsKey(name) ||
                ExecutionPolicyGate.NETWORK_URL_ARGS.containsKey(name) ||
                // Shell dimension is name-prefix based, not table based.
                name.startsWith("terminal_") ||
                capabilityForTool(name) != null
        }
        val unmapped = names - covered
        assertEquals(
            buildString {
                appendLine("Production tools outside every policy dimension changed.")
                appendLine("New tool without mapping? Add it to a PolicyGate table / capabilityForTool,")
                appendLine("or (if intentionally unconstrained) to EXPECTED_KNOWN_UNMAPPED_TOOLS with a rationale.")
                appendLine("missing-from-pin=${(unmapped - EXPECTED_KNOWN_UNMAPPED_TOOLS).sorted()}")
                append("now-covered=${(EXPECTED_KNOWN_UNMAPPED_TOOLS - unmapped).sorted()}")
            },
            EXPECTED_KNOWN_UNMAPPED_TOOLS,
            unmapped,
        )
    }

    // ── source scanner (same technique as ToolApprovalPolicyLintTest) ────────

    private fun sourceRoots(): List<File> = listOf(
        File("src/main/java"),
        File("src/main/kotlin"),
        File("../feature/tools/impl/src/main/kotlin"),
        File("../feature/tools/access/src/main/kotlin"),
        File("../feature/tools/api/src/main/kotlin"),
        File("../feature/subagent/src/main/kotlin"),
        File("app/src/main/java"),
        File("app/src/main/kotlin"),
        File("feature/tools/impl/src/main/kotlin"),
        File("feature/tools/access/src/main/kotlin"),
        File("feature/tools/api/src/main/kotlin"),
        File("feature/subagent/src/main/kotlin"),
    )
        .filter { it.isDirectory }
        .map { it.canonicalFile }
        .distinct()

    private fun productionToolNames(): Set<String> {
        val names = mutableSetOf<String>()
        sourceRoots().forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val source = file.readText()
                    NAME_REGEX.findAll(source).forEach { match ->
                        // A quoted `name = "..."` only counts inside a Tool( call.
                        if (source.findToolCallStart(match.range.first) != null) {
                            names += match.groupValues[1]
                        }
                    }
                }
        }
        return names
    }

    private fun String.findToolCallStart(before: Int): Int? {
        var index = lastIndexOf("Tool(", before)
        while (index >= 0) {
            val previous = getOrNull(index - 1)
            if (previous == null || (!previous.isLetterOrDigit() && previous != '_')) return index
            index = lastIndexOf("Tool(", index - 1)
        }
        return null
    }

    private companion object {
        private val NAME_REGEX = Regex("""name\s*=\s*"([a-zA-Z0-9_]+)"""")

        /** Pinned snapshot of [ExecutionPolicyGate.FILE_PATH_ARGS] (path dimension). */
        private val EXPECTED_FILE_PATH_ARGS = setOf(
            "file_list", "file_read", "file_write", "file_edit", "file_search", "file_move",
            "external_file_list", "external_file_read", "external_file_write", "external_file_delete",
            "archive_list", "archive_extract", "archive_create",
            "pdf_read", "pdf_render_page", "office_read",
            "image_info", "image_convert", "ocr_image",
            "download_file",
            "skill_import", "skill_preview", "skill_validate",
            "soul_preview", "soul_import",
            "officepro_capture_context", "officepro_daily_radar", "officepro_project_briefing",
            "officepro_document_warroom", "officepro_open_items_radar", "officepro_meeting_closure",
            "officepro_create_task_draft", "officepro_create_base_record_draft", "officepro_reply_draft",
            "officepro_context_digest", "officepro_project_context", "officepro_project_report",
            "officepro_make_report",
            "model_council_make_report",
            "share_file",
        )

        /** Pinned snapshot of [ExecutionPolicyGate.NETWORK_URL_ARGS] (domain dimension). */
        private val EXPECTED_NETWORK_URL_ARGS = setOf(
            "http_request", "download_file", "scrape_web",
            "webview_open", "webview_open_link",
            "wm_open", "wm_signed_fetch", "wm_site_add",
            "screen_open_url",
            "intent_open", "officepro_open", "deep_read_open",
        )

        /** Pinned snapshot of [ExecutionPolicyGate.UNMODELED_ROOT_PATH_TOOLS] (fail-closed). */
        private val EXPECTED_UNMODELED_ROOT_PATH_TOOLS = setOf(
            "icloud_list", "icloud_stat", "icloud_read", "icloud_write", "icloud_search",
            "novel_workspace_read", "novel_workspace_write",
            "use_skill",
        )

        /**
         * Pinned snapshot of capabilityForTool, grouped by capability. Derived
         * from feature/tools/api Capability.kt; dynamic `mcp__*` names are not
         * pinned here (prefix rule).
         */
        private val EXPECTED_CAPABILITY_TOOLS: Map<Capability, Set<String>> = mapOf(
            Capability.FILESYSTEM_READ to setOf(
                "file_list", "file_read", "file_search", "pdf_read", "office_read",
                "image_info", "ocr_image", "archive_list", "external_file_read",
                "deep_read_playbook_read",
            ),
            Capability.FILESYSTEM_WRITE to setOf(
                "file_write", "file_edit", "file_move", "archive_create", "archive_extract",
                "image_convert", "external_file_write", "download_file", "pdf_render_page",
                "deep_read_write_analysis", "deep_read_write_diagram", "deep_read_write_extended_reading",
                "deep_read_write_narrative", "deep_read_write_overview", "deep_read_write_visuals",
                "deep_read_playbook_update", "deep_read_playbook_restore_default",
                "deep_read_playbook_restore_previous",
            ),
            Capability.WORKSPACE_DELETE to setOf("external_file_delete"),
            Capability.NETWORK_CONNECT to setOf(
                "http_request", "search_web", "scrape_web",
                "webview_open", "webview_search_open", "webview_read", "webview_wait_for_load",
                "webview_find_text", "webview_links", "webview_open_link",
                "wm_open", "wm_get", "wm_state", "wm_observe", "wm_screenshot", "wm_visual_read",
                "wm_visual_snapshot", "wm_fetch_replay", "wm_wait", "wm_tab_list", "wm_tab_new",
                "wm_tab_close", "wm_back", "wm_forward", "wm_scroll", "wm_find", "wm_network_inspect",
                "wm_stations", "wm_recipe_candidates", "wm_signed_fetch", "wm_extract", "wm_site_add",
                "wm_site_remove", "wm_profile_synthesize", "wm_click", "wm_tap", "wm_type", "wm_keys",
                "wm_select", "wm_eval",
            ),
            Capability.MCP_IMPORT to setOf("mcp_import_from_skill", "mcp_test"),
            Capability.MCP_TOOL to setOf("mcp_call_tool"),
            Capability.SKILL_PROMOTE to setOf("skill_import", "skill_rollback"),
            Capability.SOUL_UPDATE to setOf("agent_prompt_config", "soul_import", "soul_rollback"),
            Capability.RECIPE_IMPORT to setOf("recipe_import", "recipe_rollback", "recipe_delete"),
            // provider_config_apply / provider_refresh_models /
            // settings_set_model_slot are named via TOOL_* constants, so the
            // scanner never sees their literals; they are pinned explicitly in
            // the test body above instead of here.
            Capability.PROVIDER_CONFIG to emptySet(),
            Capability.SMS_READ to setOf("sms_list", "sms_read"),
            Capability.SMS_SEND to setOf("sms_send"),
            Capability.CALL_LOG_READ to setOf("call_log_list"),
            Capability.CALL_PHONE to setOf("call_phone"),
            Capability.CONTACTS_READ to setOf("contacts_search"),
            Capability.CONTACTS_WRITE to setOf("contacts_write"),
            Capability.LOCATION_CURRENT to setOf("location_current"),
            Capability.AUDIO_RECORD to setOf("audio_record_once"),
            Capability.SCREEN_CAPTURE to setOf("screen_screenshot"),
            Capability.CLIPBOARD_ACCESS to setOf("clipboard_tool"),
        )

        /**
         * The pinned known-unmapped set: production tools covered by NO policy
         * dimension (path, domain, shell prefix, capability). Inventory taken
         * from the real scan at pin time; every group is a deliberate
         * exemption, not an oversight to replicate for new tools.
         */
        private val EXPECTED_KNOWN_UNMAPPED_TOOLS: Set<String> = setOf(
            // Host-loop plumbing: no file/network/device surface. ask_user is
            // the HITL channel (intercepted, never executed, in the council
            // host); run_plan_update / permissions_status / tools_list /
            // tool_policy_explain are agent-loop introspection.
            "ask_user", "get_time_info", "run_plan_update", "permissions_status",
            "tools_list", "tool_policy_explain",
            // Provider-connection probe Tool inside settings UI; never
            // registered in the agent catalog (ProviderConnectionTester.kt).
            "get_current_time",
            // In-app JS engine (sandboxed runtime, no tool-call surface).
            "eval_javascript",
            // Fixed conversation-scoped output dir; no user-supplied path arg.
            "generate_image",
            // Search orchestrator introspection (read-only).
            "search_sources_status", "search_strategy_explain",
            // Conversation/context tools (repository-scoped, not FS paths).
            "conversation_compact", "conversation_context_status", "conversation_expand",
            "conversation_queue_cancel", "conversation_queue_status", "conversation_search",
            // Session/history reads (approval-gated at the Tool factory).
            "session_expand", "session_list", "session_read", "session_search",
            // Cron / agent-task schedulers (durable owner + approval side).
            "cron_task_create", "cron_task_delete", "cron_task_list", "cron_task_update",
            "agent_runtime_status",
            "agent_task_cancel", "agent_task_cleanup", "agent_task_list", "agent_task_read",
            "agent_task_retry",
            // Memory store (its own persistence domain).
            "memory_delete", "memory_list", "memory_tool", "memory_write",
            // Deep-read finish (playbook reads/writes are capability-mapped).
            "deep_read_finish",
            // Recipe runtime preview (import/rollback/delete are mapped).
            "recipe_preview",
            // js_cell_*: sandboxed cell-private state (P4-03).
            "js_cell_create", "js_cell_load", "js_cell_run", "js_cell_store",
            "js_cell_terminate", "js_cell_wait",
            // WebMount site adapters: session-scoped reads through the logged-in
            // wm_* session; no direct URL argument (domain dimension rides the
            // wm_open/wm_signed_fetch primitives instead).
            "feishu_docs_append_block", "feishu_docs_append_callout", "feishu_docs_append_heading",
            "feishu_docs_append_list_item", "feishu_docs_blocks", "feishu_docs_create",
            "feishu_docs_list", "feishu_docs_markdown_pack", "feishu_docs_network_summary",
            "feishu_docs_read", "feishu_docs_resolve", "feishu_docs_search", "feishu_docs_snapshot",
            "github_file_read", "github_issue_list", "github_pr_list", "github_repo_read",
            "github_repo_search", "github_user_read",
            "hn_item_read", "hn_search", "hn_top", "hn_user_read",
            "bilibili_hot_videos", "bilibili_search", "bilibili_user_history", "bilibili_video_info",
            "juejin_article_read", "juejin_feed", "juejin_my_posts", "juejin_pins", "juejin_search",
            "reddit_post_read", "reddit_search", "reddit_subreddit_read", "reddit_top",
            "zhihu_answer_read", "zhihu_feed", "zhihu_question_read", "zhihu_search",
            // Model-council room control (its report writer IS path-mapped).
            "model_council_cancel", "model_council_read", "model_council_start",
            "model_council_status", "model_council_wait",
            // officepro tools with no /workspace path surface (verified against
            // FeishuOfficeTools.kt schemas); the workspace_paths family IS mapped.
            "officepro_dashboard", "officepro_project_list", "officepro_project_update",
            "officepro_read_screen", "officepro_search", "officepro_status",
            // Novel-tree prefix-scoped reads; the raw-path read/write pair is
            // in UNMODELED_ROOT_PATH_TOOLS (fail-closed) instead.
            "novel_workspace_grep", "novel_workspace_list", "novel_workspace_status",
            // iCloud status probe; the path-bearing icloud_* tools are in
            // UNMODELED_ROOT_PATH_TOOLS (fail-closed) instead.
            "icloud_status",
            // Screen automation / device surfaces: Android runtime permissions
            // and the approval side are the enforcement; only screen_open_url
            // (domain), screen_screenshot / clipboard_tool and the sms / call /
            // contacts / location / audio device tools are capability-mapped.
            "screen_back", "screen_click", "screen_find_text", "screen_home", "screen_input_text",
            "screen_long_click", "screen_open_app", "screen_read_ui", "screen_scroll_until",
            "screen_swipe", "screen_tap_text", "screen_wait_for_text", "vlm_task",
            // Device / system info + settings surfaces (feature/tools/access):
            // read-mostly inventory and Android-setting launches; intent_open
            // (domain) and share_file (path) are mapped instead.
            "app_info", "app_open", "apps_installed_list", "apps_list",
            "battery_status", "calendar_create", "calendar_list", "device_info",
            "device_phone_state", "media_search", "network_status", "settings_open",
            "usage_stats_list", "wifi_status",
            // Notifications and text share (share_file IS path-mapped).
            "notification_list", "notification_post", "share_text",
            // Skill inventory (import/preview/validate/use_skill are mapped).
            "skills_list",
            // Subagent control (SubAgentTools; scope re-uses the parent policy).
            "subagent_cancel", "subagent_followup", "subagent_interrupt", "subagent_list",
            "subagent_read", "subagent_send_message", "subagent_start", "subagent_wait",
            // MCP inventory/preview (call/import are capability-mapped).
            "mcp_list", "mcp_preview_import_from_skill",
        )
    }
}
