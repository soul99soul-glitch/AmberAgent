// D-082 测试 — ToolProfileFilter.kt + ToolRegistry.kt:424-457 逐字锁定
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  filterToolProfile,
} from '../main/ets/chat/tool_profile_filter.ts';

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
