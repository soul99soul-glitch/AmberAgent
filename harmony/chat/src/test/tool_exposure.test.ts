// tool_exposure.test.ts — D-062 ToolExposureState 懒暴露
// Android 基准: ToolSearch.kt:270-380(ToolExposureState + expandedToolNames)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { JsonValue } from '../main/ets/chat/json.ts';
import type { UIMessagePart, UIMessagePartTool } from '../main/ets/chat/message.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import { makeAgentTool } from '../main/ets/chat/tool.ts';
import type { ToolExposureState } from '../main/ets/chat/tool_exposure.ts';
import { createToolExposureState } from '../main/ets/chat/tool_exposure.ts';
import { createToolSearchTool } from '../main/ets/chat/builtin_introspection_tools.ts';
import { createToolRegistry } from '../main/ets/chat/tool_registry.ts';

const dummy = (name: string): AgentTool => makeAgentTool({
  name,
  description: `desc ${name}`,
  execute: (_input: JsonValue): Promise<UIMessagePart[]> =>
    Promise.resolve([{ type: 'text', text: '{}', metadata: null }]),
});

const namesOf = (tools: AgentTool[]): string[] => tools.map((t: AgentTool): string => t.name);

const executedToolSearchPart = (expanded: string[]): UIMessagePartTool => ({
  type: 'tool',
  toolCallId: 'call_1',
  toolName: 'tool_search',
  input: '{}',
  output: [
    { type: 'text', text: JSON.stringify({ expanded_tools: expanded }), metadata: null },
  ],
  approvalState: { type: 'approved' },
  metadata: null,
});

test('from:>40 但无 tool_search → 懒模式关', () => {
  const tools: AgentTool[] = [];
  for (let i = 0; i < 45; i++) tools.push(dummy(`hidden_tool_${i}`));
  const st: ToolExposureState = createToolExposureState(tools);
  assert.equal(st.enabled, false);
  assert.equal(st.toolsForStep().length, 45);
});

test('from:toolCount 不计 DISCOVERY_UTILITY 三件(41 非发现才开)', () => {
  // 40 非发现 + tool_search + tools_list → 关
  const tools: AgentTool[] = [];
  for (let i = 0; i < 40; i++) tools.push(dummy(`t_${i}`));
  const reg = createToolRegistry(tools);
  tools.push(createToolSearchTool(reg));
  tools.push(dummy('tools_list'));
  const st: ToolExposureState = createToolExposureState(tools);
  assert.equal(st.enabled, false);
});

// ===== exposeToolNames(:283-289) =====

test('exposeToolNames:懒模式 — 已知名加入可见集,未知名忽略', () => {
  const tools: AgentTool[] = [];
  for (let i = 0; i < 44; i++) tools.push(dummy(`hidden_tool_${i}`));
  const reg = createToolRegistry(tools);
  tools.push(createToolSearchTool(reg));
  const st: ToolExposureState = createToolExposureState(tools);
  assert.equal(st.enabled, true);
  st.exposeToolNames(['hidden_tool_3', 'ghost_tool', 'hidden_tool_7']);
  const visible: string[] = namesOf(st.toolsForStep());
  assert.ok(visible.includes('hidden_tool_3'));
  assert.ok(visible.includes('hidden_tool_7'));
  assert.equal(visible.includes('ghost_tool'), false);
});

// ===== observeExecutedTools + expandedToolNames(:291-301/:373-380) =====

test('observeExecutedTools:tool_search 输出 expanded_tools → 暴露;非 tool_search 忽略', () => {
  const tools: AgentTool[] = [];
  for (let i = 0; i < 44; i++) tools.push(dummy(`hidden_tool_${i}`));
  const reg = createToolRegistry(tools);
  tools.push(createToolSearchTool(reg));
  const st: ToolExposureState = createToolExposureState(tools);
  st.observeExecutedTools([executedToolSearchPart(['hidden_tool_5', 'hidden_tool_9'])]);
  const visible: string[] = namesOf(st.toolsForStep());
  assert.ok(visible.includes('hidden_tool_5'));
  assert.ok(visible.includes('hidden_tool_9'));
  // 非 tool_search 工具输出不触发暴露
  const other: UIMessagePartTool = {
    type: 'tool', toolCallId: 'c2', toolName: 'hidden_tool_1',
    input: '{}',
    output: [
      { type: 'text', text: JSON.stringify({ expanded_tools: ['hidden_tool_2'] }), metadata: null },
    ],
    approvalState: { type: 'approved' }, metadata: null,
  };
  st.observeExecutedTools([other]);
  assert.equal(namesOf(st.toolsForStep()).includes('hidden_tool_2'), false);
});

test('observeExecutedTools:非法 JSON / 缺 expanded_tools / 非字符串项 → 静默忽略', () => {
  const tools: AgentTool[] = [];
  for (let i = 0; i < 44; i++) tools.push(dummy(`hidden_tool_${i}`));
  const reg = createToolRegistry(tools);
  tools.push(createToolSearchTool(reg));
  const st: ToolExposureState = createToolExposureState(tools);
  const malformed: UIMessagePartTool = {
    type: 'tool', toolCallId: 'c3', toolName: 'tool_search',
    input: '{}',
    output: [
      { type: 'text', text: '{broken', metadata: null },
      { type: 'text', text: JSON.stringify({ other: 1 }), metadata: null },
      { type: 'text', text: JSON.stringify({ expanded_tools: [1, 'hidden_tool_11', null] }), metadata: null },
    ],
    approvalState: { type: 'approved' }, metadata: null,
  };
  st.observeExecutedTools([malformed]);
  const visible: string[] = namesOf(st.toolsForStep());
  assert.ok(visible.includes('hidden_tool_11'));
  assert.equal(visible.filter((n: string): boolean => n.startsWith('hidden_')).length, 1);
});

test('toolsForStep:保持原工具顺序(过滤不重排)', () => {
  const tools: AgentTool[] = [dummy('z_last'), dummy('ask_user'), dummy('a_first')];
  const st: ToolExposureState = createToolExposureState(tools);
  assert.deepEqual(namesOf(st.toolsForStep()), ['z_last', 'ask_user', 'a_first']);
});
