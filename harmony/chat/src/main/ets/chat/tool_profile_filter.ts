// tool_profile_filter — ToolProfileFilter.kt 全文逐字移植
// Android 锚点:
//   feature/tools/api/.../ToolProfileFilter.kt(filter/isAllowed/四组常量)
//   feature/tools/api/.../ToolRegistry.kt:424-457 internal fun Tool.category()
//     — 已由 tool_policy.ts:179 先行逐字移植,本模块直接复用
// 用途:ChatService.kt:2302/:2324 按 assistant.toolProfile 过滤原始工具集;
//   filteredByCategory 供 tool_policy_explain/诊断输出。

import type { MainAgentToolProfile } from './assistant.ts';
import type { AgentTool } from './tool.ts';
import { toolCategory } from './tool_policy.ts';

export interface ToolProfileFilterResult {
  profile: MainAgentToolProfile;
  tools: AgentTool[];
  filteredCount: number;
  // groupingBy { category() }.eachCount() — 插入序 = 源遍历序(Kotlin LinkedHashMap)
  filteredByCategory: Record<string, number>;
}

export const PROFILE_MINIMAL_TOOLS: readonly string[] = Object.freeze([
  'get_time_info',
  'ask_user',
  'permissions_status',
  'agent_runtime_status',
  'agent_task_list',
  'agent_task_read',
  'conversation_context_status',
]);

export const PROFILE_WEB_READ_TOOLS: readonly string[] = Object.freeze([
  'search_web',
  'scrape_web',
  'search_sources_status',
  'search_strategy_explain',
  'webview_open',
  'webview_search_open',
  'webview_read',
  'webview_wait_for_load',
  'webview_find_text',
  'webview_links',
  'webview_open_link',
  'wm_tab_list',
  'wm_observe',
  'wm_screenshot',
  'wm_visual_snapshot',
  'wm_visual_read',
  'wm_wait',
  'wm_scroll',
  'wm_find',
  'wm_stations',
  'wm_open',
  'wm_state',
  'wm_extract',
  'wm_get',
  'wm_back',
  'wm_forward',
  'wm_network_inspect',
  'wm_recipe_candidates',
  'hn_top',
  'hn_item_read',
  'hn_user_read',
  'hn_search',
  'reddit_top',
  'reddit_subreddit_read',
  'reddit_post_read',
  'reddit_search',
  'juejin_feed',
  'juejin_pins',
  'juejin_article_read',
  'juejin_search',
  'feishu_docs_resolve',
  'feishu_docs_snapshot',
  'feishu_docs_network_summary',
  'feishu_docs_markdown_pack',
  'feishu_docs_list',
  'feishu_docs_read',
  'feishu_docs_blocks',
  'feishu_docs_search',
  'github_repo_search',
  'github_repo_read',
  'github_issue_list',
  'github_pr_list',
  'github_file_read',
  'github_user_read',
  'bilibili_hot_videos',
  'bilibili_video_info',
  'bilibili_search',
  'zhihu_feed',
  'zhihu_question_read',
  'zhihu_answer_read',
  'zhihu_search',
]);

export const PROFILE_WORKSPACE_READ_TOOLS: readonly string[] = Object.freeze([
  'file_list',
  'file_read',
  'file_search',
  'archive_list',
  'pdf_read',
  'pdf_render_page',
  'office_read',
  'image_info',
  'ocr_image',
  'external_file_list',
  'external_file_read',
  'icloud_status',
  'icloud_list',
  'icloud_stat',
  'icloud_read',
  'icloud_search',
]);

export const PROFILE_CODING_CATEGORIES: readonly string[] = Object.freeze([
  'workspace',
  'terminal',
  'python',
  'mcp',
  'skill',
]);

export const PROFILE_MOBILE_CONTROL_CATEGORIES: readonly string[] = Object.freeze([
  'screen',
  'system',
  'office',
  'webview',
]);

// isAllowed(ToolProfileFilter.kt:41-55)— when(profile) 逐字
const isAllowed = (tool: AgentTool, profile: MainAgentToolProfile): boolean => {
  const name: string = tool.name;
  const category: string = toolCategory(name);
  switch (profile) {
    case 'full':
      return true;
    case 'minimal':
      return PROFILE_MINIMAL_TOOLS.indexOf(name) >= 0;
    case 'web_read':
      return PROFILE_MINIMAL_TOOLS.indexOf(name) >= 0 ||
        PROFILE_WEB_READ_TOOLS.indexOf(name) >= 0;
    case 'workspace_read':
      return PROFILE_MINIMAL_TOOLS.indexOf(name) >= 0 ||
        PROFILE_WORKSPACE_READ_TOOLS.indexOf(name) >= 0;
    case 'coding':
      return PROFILE_MINIMAL_TOOLS.indexOf(name) >= 0 ||
        PROFILE_CODING_CATEGORIES.indexOf(category) >= 0;
    case 'mobile_control':
      return PROFILE_MINIMAL_TOOLS.indexOf(name) >= 0 ||
        PROFILE_MOBILE_CONTROL_CATEGORIES.indexOf(category) >= 0;
  }
};

// filter(ToolProfileFilter.kt:15-39)— FULL 直通;filteredByCategory 插入序保持
export const filterToolProfile = (
  tools: AgentTool[], profile: MainAgentToolProfile,
): ToolProfileFilterResult => {
  if (profile === 'full') {
    return {
      profile,
      tools,
      filteredCount: 0,
      filteredByCategory: {},
    };
  }
  const kept: AgentTool[] = tools.filter((tool: AgentTool): boolean => isAllowed(tool, profile));
  const keptNames: Set<string> = new Set<string>(kept.map((t: AgentTool): string => t.name));
  const filtered: AgentTool[] =
    tools.filter((tool: AgentTool): boolean => !keptNames.has(tool.name));
  const byCategory: Record<string, number> = {};
  for (const tool of filtered) {
    const category: string = toolCategory(tool.name);
    byCategory[category] = (byCategory[category] ?? 0) + 1;
  }
  return {
    profile,
    tools: kept,
    filteredCount: filtered.length,
    filteredByCategory: byCategory,
  };
};
