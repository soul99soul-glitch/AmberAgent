// D-082 测试 — ToolProfileFilter.kt + ToolRegistry.kt:424-457 逐字锁定
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  PROFILE_MINIMAL_TOOLS, filterToolProfile,
} from '../main/ets/chat/tool_profile_filter.ts';
import { toolCategory } from '../main/ets/chat/tool_policy.ts';
import { makeAgentTool } from '../main/ets/chat/tool.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import type { UIMessagePart } from '../main/ets/chat/message.ts';

const tool = (name: string): AgentTool => makeAgentTool({
  name,
  description: `desc ${name}`,
  parameters: () => ({ type: 'object', properties: {}, required: [] }),
  needsApproval: false,
  allowsAutoApproval: true,
  execute: (): Promise<UIMessagePart[]> => Promise.resolve([]),
});

test('toolCategory: when 链逐字(ToolRegistry.kt:424-457)', () => {
  assert.equal(toolCategory('external_file_list'), 'external_file');
  assert.equal(toolCategory('file_read'), 'workspace');
  assert.equal(toolCategory('archive_list'), 'workspace');
  assert.equal(toolCategory('pdf_read'), 'workspace'); // 集合命中(非前缀)
  assert.equal(toolCategory('ocr_image'), 'workspace');
  assert.equal(toolCategory('icloud_read'), 'cloud');
  assert.equal(toolCategory('officepro_x'), 'office');
  assert.equal(toolCategory('terminal_run'), 'terminal');
  assert.equal(toolCategory('search_web'), 'web'); // 集合命中
  assert.equal(toolCategory('http_request'), 'web');
  assert.equal(toolCategory('webview_open'), 'webview');
  assert.equal(toolCategory('wm_observe'), 'webmount');
  assert.equal(toolCategory('hn_top'), 'webmount_hackernews');
  assert.equal(toolCategory('reddit_search'), 'webmount_reddit');
  assert.equal(toolCategory('juejin_feed'), 'webmount_juejin');
  assert.equal(toolCategory('feishu_docs_read'), 'webmount_feishu_docs');
  assert.equal(toolCategory('github_repo_read'), 'webmount_github');
  assert.equal(toolCategory('bilibili_search'), 'webmount_bilibili');
  assert.equal(toolCategory('zhihu_feed'), 'webmount_zhihu');
  assert.equal(toolCategory('screen_tap'), 'screen');
  assert.equal(toolCategory('vlm_task'), 'screen');
  assert.equal(toolCategory('sms_send'), 'system');
  assert.equal(toolCategory('app_launch'), 'system');
  assert.equal(toolCategory('battery_status'), 'system'); // 集合命中
  assert.equal(toolCategory('memory_create'), 'memory');
  assert.equal(toolCategory('agent_prompt_config'), 'prompt_config');
  assert.equal(toolCategory('conversation_context_status'), 'context');
  assert.equal(toolCategory('session_grant'), 'context');
  assert.equal(toolCategory('deep_read_open'), 'deep_read');
  assert.equal(toolCategory('cron_task_list'), 'cron');
  assert.equal(toolCategory('agent_task_list'), 'task');
  assert.equal(toolCategory('agent_runtime_status'), 'task'); // 集合命中(在 utility 前)
  assert.equal(toolCategory('tool_search'), 'utility');
  assert.equal(toolCategory('tools_list'), 'utility');
  assert.equal(toolCategory('subagent_run'), 'subagent');
  assert.equal(toolCategory('model_council_vote'), 'model_council');
  assert.equal(toolCategory('skill_list'), 'skill'); // startsWith('skill')
  assert.equal(toolCategory('use_skill'), 'skill');
  assert.equal(toolCategory('mcp_call'), 'mcp');
  assert.equal(toolCategory('mcp__github__get'), 'mcp');
  assert.equal(toolCategory('get_time_info'), 'utility'); // else 分支
  assert.equal(toolCategory('ask_user'), 'utility');
});

test('filterToolProfile: FULL 直通(ToolProfileFilter.kt:17-24)', () => {
  const tools: AgentTool[] = [tool('get_time_info'), tool('terminal_run')];
  const r = filterToolProfile(tools, 'full');
  assert.equal(r.profile, 'full');
  assert.equal(r.tools, tools); // 同引用直通
  assert.equal(r.filteredCount, 0);
  assert.deepEqual(r.filteredByCategory, {});
});

test('filterToolProfile: minimal 仅 MINIMAL_TOOLS 名集合', () => {
  const tools: AgentTool[] = [
    tool('get_time_info'), // minimal 留
    tool('ask_user'), // minimal 留
    tool('agent_runtime_status'), // minimal 留(category=task 但名字在集合)
    tool('search_web'), // 滤
    tool('terminal_run'), // 滤
  ];
  const r = filterToolProfile(tools, 'minimal');
  assert.deepEqual(r.tools.map((t: AgentTool): string => t.name),
    ['get_time_info', 'ask_user', 'agent_runtime_status']);
  assert.equal(r.filteredCount, 2);
  // groupingBy eachCount 插入序:search_web(web) → terminal_run(terminal)
  assert.deepEqual(r.filteredByCategory, { web: 1, terminal: 1 });
});

test('filterToolProfile: web_read = minimal ∪ WEB_READ 名集合', () => {
  const tools: AgentTool[] = [
    tool('get_time_info'),
    tool('search_web'), // WEB_READ 留
    tool('webview_open'), // WEB_READ 留
    tool('hn_top'), // WEB_READ 留
    tool('zhihu_search'), // WEB_READ 留
    tool('file_read'), // 滤(workspace 名不在 web_read 集合)
    tool('terminal_run'), // 滤
  ];
  const r = filterToolProfile(tools, 'web_read');
  assert.deepEqual(r.tools.map((t: AgentTool): string => t.name),
    ['get_time_info', 'search_web', 'webview_open', 'hn_top', 'zhihu_search']);
  assert.equal(r.filteredCount, 2);
  assert.deepEqual(r.filteredByCategory, { workspace: 1, terminal: 1 });
});

test('filterToolProfile: workspace_read / coding / mobile_control', () => {
  const tools: AgentTool[] = [
    tool('get_time_info'), // 三画像都留(minimal)
    tool('file_read'), // workspace_read 留;coding 留(category=workspace)
    tool('terminal_run'), // coding 留(category=terminal)
    tool('screen_tap'), // mobile_control 留(category=screen)
    tool('webview_read'), // mobile_control 留(category=webview)
    tool('search_web'), // 三画像都滤
  ];
  const ws = filterToolProfile(tools, 'workspace_read');
  assert.deepEqual(ws.tools.map((t: AgentTool): string => t.name),
    ['get_time_info', 'file_read']);

  const coding = filterToolProfile(tools, 'coding');
  assert.deepEqual(coding.tools.map((t: AgentTool): string => t.name),
    ['get_time_info', 'file_read', 'terminal_run']);

  const mobile = filterToolProfile(tools, 'mobile_control');
  assert.deepEqual(mobile.tools.map((t: AgentTool): string => t.name),
    ['get_time_info', 'screen_tap', 'webview_read']);
});

test('filterToolProfile: 重名工具 keptNames 语义(filtered = 不在 kept 名集合)', () => {
  // Android keptNames = kept.mapTo(HashSet){name};filterNot 按名排除 —
  //   同名工具一个留一个也留(名在 keptNames)
  const tools: AgentTool[] = [
    tool('search_web'),
    tool('search_web'), // 同名重复
    tool('get_time_info'),
  ];
  const r = filterToolProfile(tools, 'web_read');
  assert.equal(r.tools.length, 3); // 同名两个都留
  assert.equal(r.filteredCount, 0);
});

test('常量集合形状锁定', () => {
  assert.equal(PROFILE_MINIMAL_TOOLS.length, 7);
  assert.deepEqual(PROFILE_MINIMAL_TOOLS[0], 'get_time_info');
  assert.deepEqual(PROFILE_MINIMAL_TOOLS[PROFILE_MINIMAL_TOOLS.length - 1],
    'conversation_context_status');
});
