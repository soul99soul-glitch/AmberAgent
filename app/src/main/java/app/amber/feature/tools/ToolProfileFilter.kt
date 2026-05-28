package app.amber.feature.tools

import me.rerere.ai.core.Tool
import app.amber.core.model.MainAgentToolProfile

data class ToolProfileFilterResult(
    val profile: MainAgentToolProfile,
    val tools: List<Tool>,
    val filteredCount: Int,
    val filteredByCategory: Map<String, Int>,
)

object ToolProfileFilter {
    fun filter(
        tools: List<Tool>,
        profile: MainAgentToolProfile,
    ): ToolProfileFilterResult {
        if (profile == MainAgentToolProfile.FULL) {
            return ToolProfileFilterResult(
                profile = profile,
                tools = tools,
                filteredCount = 0,
                filteredByCategory = emptyMap(),
            )
        }
        val kept = tools.filter { tool -> isAllowed(tool, profile) }
        val keptNames = kept.mapTo(HashSet()) { it.name }
        val filtered = tools.filterNot { tool -> tool.name in keptNames }
        return ToolProfileFilterResult(
            profile = profile,
            tools = kept,
            filteredCount = filtered.size,
            filteredByCategory = filtered.groupingBy { it.category() }.eachCount(),
        )
    }

    private fun isAllowed(tool: Tool, profile: MainAgentToolProfile): Boolean {
        val name = tool.name
        val category = tool.category()
        return when (profile) {
            MainAgentToolProfile.FULL -> true
            MainAgentToolProfile.MINIMAL -> name in MINIMAL_TOOLS
            MainAgentToolProfile.WEB_READ -> name in MINIMAL_TOOLS ||
                name in WEB_READ_TOOLS
            MainAgentToolProfile.WORKSPACE_READ -> name in MINIMAL_TOOLS ||
                name in WORKSPACE_READ_TOOLS
            MainAgentToolProfile.CODING -> name in MINIMAL_TOOLS ||
                category in CODING_CATEGORIES
            MainAgentToolProfile.MOBILE_CONTROL -> name in MINIMAL_TOOLS ||
                category in MOBILE_CONTROL_CATEGORIES
        }
    }

    private val MINIMAL_TOOLS = setOf(
        "get_time_info",
        "ask_user",
        "permissions_status",
        "agent_runtime_status",
        "agent_task_list",
        "agent_task_read",
        "conversation_context_status",
    )

    private val WEB_READ_TOOLS = setOf(
        "search_web",
        "scrape_web",
        "search_sources_status",
        "search_strategy_explain",
        "webview_open",
        "webview_search_open",
        "webview_read",
        "webview_wait_for_load",
        "webview_find_text",
        "webview_links",
        "webview_open_link",
        "wm_tab_list",
        "wm_observe",
        "wm_screenshot",
        "wm_visual_snapshot",
        "wm_visual_read",
        "wm_wait",
        "wm_scroll",
        "wm_find",
        "wm_stations",
        "wm_open",
        "wm_state",
        "wm_extract",
        "wm_get",
        "wm_back",
        "wm_forward",
        "wm_network_inspect",
        "wm_recipe_candidates",
        "hn_top",
        "hn_item_read",
        "hn_user_read",
        "hn_search",
        "reddit_top",
        "reddit_subreddit_read",
        "reddit_post_read",
        "reddit_search",
        "juejin_feed",
        "juejin_pins",
        "juejin_article_read",
        "juejin_search",
        "feishu_docs_resolve",
        "feishu_docs_snapshot",
        "feishu_docs_network_summary",
        "feishu_docs_markdown_pack",
        "feishu_docs_list",
        "feishu_docs_read",
        "feishu_docs_blocks",
        "feishu_docs_search",
        "github_repo_search",
        "github_repo_read",
        "github_issue_list",
        "github_pr_list",
        "github_file_read",
        "github_user_read",
        "bilibili_hot_videos",
        "bilibili_video_info",
        "bilibili_search",
        "zhihu_feed",
        "zhihu_question_read",
        "zhihu_answer_read",
        "zhihu_search",
    )

    private val WORKSPACE_READ_TOOLS = setOf(
        "file_list",
        "file_read",
        "file_search",
        "archive_list",
        "pdf_read",
        "pdf_render_page",
        "office_read",
        "image_info",
        "ocr_image",
        "external_file_list",
        "external_file_read",
        "icloud_status",
        "icloud_list",
        "icloud_stat",
        "icloud_read",
        "icloud_search",
    )

    private val CODING_CATEGORIES = setOf(
        "workspace",
        "terminal",
        "mcp",
        "skill",
    )

    private val MOBILE_CONTROL_CATEGORIES = setOf(
        "screen",
        "system",
        "office",
        "webview",
    )

}
