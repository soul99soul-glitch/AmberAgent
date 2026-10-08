// builtin_introspection_tools.test.ts — D-060 自省/发现工具组
// Android 基准: ToolsListTool.kt + ToolPolicyExplainTool.kt + ToolSearch.kt(:1-380)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { JsonObject, JsonValue } from '../main/ets/chat/json.ts';
import type { UIMessagePart } from '../main/ets/chat/message.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import { makeAgentTool, makeInputSchemaObj } from '../main/ets/chat/tool.ts';
import type { ToolRegistry } from '../main/ets/chat/tool_registry.ts';
import { createToolRegistry } from '../main/ets/chat/tool_registry.ts';
import {
  isResidentTool, createToolsListTool, createToolPolicyExplainTool, createToolSearchTool
} from '../main/ets/chat/builtin_introspection_tools.ts';

const textOf = (parts: UIMessagePart[]): JsonObject =>
  JSON.parse((parts[0] as { text: string }).text) as JsonObject;

const sampleTool = (name: string, description: string, needsApproval: boolean = false): AgentTool =>
  makeAgentTool({
    name,
    description,
    needsApproval,
    parameters: () => makeInputSchemaObj({
      query: { type: 'string', description: 'q' },
    }, ['query']),
    execute: (_input: JsonValue): Promise<UIMessagePart[]> =>
      Promise.resolve([{ type: 'text', text: '{}', metadata: null }]),
  });

const makeRegistry = (): ToolRegistry => createToolRegistry([
  sampleTool('get_time_info', 'Get current time info'),
  // 与真实 AskUserTool 同:needsApproval=true
  sampleTool('ask_user', 'Ask the user questions', true),
  sampleTool('conversation_search', 'Search conversation transcript'),
]);

// ===== isResidentTool(ToolSearch.kt:313-321 + 四集合 :323-370) =====

test('isResidentTool:discovery utility 三件恒常驻', () => {
  assert.equal(isResidentTool('tool_search', null), true);
  assert.equal(isResidentTool('tools_list', null), true);
  assert.equal(isResidentTool('tool_policy_explain', null), true);
});

test('isResidentTool:RESIDENT_EXACT 精确表', () => {
  assert.equal(isResidentTool('ask_user', null), true);
  assert.equal(isResidentTool('file_read', null), true);
  assert.equal(isResidentTool('mcp_call_tool', null), true);
  assert.equal(isResidentTool('generate_image', null), true);
});

test('isResidentTool:前缀规则(subagent_ 恒真;agent_task_ 白名单两件)', () => {
  assert.equal(isResidentTool('subagent_start', null), true);
  assert.equal(isResidentTool('agent_task_list', null), true);
  assert.equal(isResidentTool('agent_task_read', null), true);
  assert.equal(isResidentTool('agent_task_create', null), false);
});

test('isResidentTool:model_council 集合 + context 类目门', () => {
  assert.equal(isResidentTool('model_council_start', null), true);
  assert.equal(isResidentTool('conversation_search', 'context'), true);
  assert.equal(isResidentTool('conversation_compact', 'context'), false);
  assert.equal(isResidentTool('conversation_search', 'utility'), false);
  assert.equal(isResidentTool('http_request', null), false);
});

// ===== tool_policy_explain(ToolPolicyExplainTool.kt 全文) =====

test('tool_policy_explain:命中 → 键序/取值逐字(risk 小写)', async () => {
  const reg: ToolRegistry = makeRegistry();
  const t: AgentTool = createToolPolicyExplainTool(reg);
  assert.equal(t.name, 'tool_policy_explain');
  assert.equal(t.description,
    'Explain how AmberAgent would evaluate one tool invocation without executing it.');
  const payload: JsonObject = textOf(await t.execute({ tool_name: 'ask_user' }));
  assert.equal(payload['status'], 'ok');
  assert.equal(payload['tool_name'], 'ask_user');
  assert.equal(payload['category'], 'utility'); // 无专属分支 → else 'utility'
  assert.equal(payload['risk'], 'normal');
  assert.equal(payload['mutates'], false);
  assert.equal(payload['needs_approval'], true);
  assert.equal(payload['always_ask'], false);
  // ask_user policy:parallel_group='utility'(只读常规);needsApproval →
  //   speculative_block_reason 出现(approval 阻塞);前台包名恒缺省
  assert.equal(payload['parallel_group'], 'utility');
  assert.equal(typeof payload['speculative_block_reason'], 'string');
  assert.equal('requires_foreground_app_package' in payload, false);
});

test('tool_policy_explain:未命中 → 仅 status/tool_name', async () => {
  const reg: ToolRegistry = makeRegistry();
  const t: AgentTool = createToolPolicyExplainTool(reg);
  const payload: JsonObject = textOf(await t.execute({ tool_name: 'nope' }));
  assert.deepEqual(Object.keys(payload), ['status', 'tool_name']);
  assert.equal(payload['status'], 'not_found');
  assert.equal(payload['tool_name'], 'nope');
});

test('tool_policy_explain:input 非法 JSON → 按 null 输入评估(runCatching getOrNull)', async () => {
  const reg: ToolRegistry = makeRegistry();
  const t: AgentTool = createToolPolicyExplainTool(reg);
  const payload: JsonObject = textOf(
    await t.execute({ tool_name: 'ask_user', input: '{broken' }));
  assert.equal(payload['status'], 'ok');
});

// ===== tools_list(ToolsListTool.kt 全文) =====

test('tools_list:默认 — 顶层键序 + 逐字文案 + 条目键序', async () => {
  const reg: ToolRegistry = makeRegistry();
  const t: AgentTool = createToolsListTool(reg);
  assert.equal(t.description,
    "Debug/catalog view of AmberAgent's full tool catalog. In lazy mode, hidden tools listed here are not callable until exposed by tool_search.");
  const payload: JsonObject = textOf(await t.execute({}));
  assert.deepEqual(Object.keys(payload),
    ['enabled_count', 'include_disabled_supported', 'catalog_mode',
      'callability_note', 'next_action', 'tools']);
  assert.equal(payload['enabled_count'], 3);
  assert.equal(payload['include_disabled_supported'], false);
  assert.equal(payload['catalog_mode'], 'debug');
  assert.equal(payload['callability_note'],
    'This is a full catalog/debug view. In lazy tool mode, hidden tools listed here are not callable until tool_search exposes their schemas.');
  assert.equal(payload['next_action'],
    'To call a non-resident tool from this list, call tool_search with query set to the exact tool name, then call it on the next model step.');
  const tools: JsonValue = payload['tools'];
  assert.ok(Array.isArray(tools));
  const askUser: JsonObject = (tools as JsonValue[])[1] as JsonObject;
  // ask_user:concurrencySafe && !mutates && risk==Normal → parallel_group=category(:390);
  //   needsApproval=true → speculative_blocked(approval_required)
  assert.deepEqual(Object.keys(askUser), [
    'name', 'category', 'description', 'enabled', 'resident', 'mutates',
    'sensitive_read', 'needs_approval', 'allows_auto_approval',
    'output_budget_chars', 'dynamic_policy_supported', 'concurrency_safe',
    'parallel_group', 'speculative_eligible', 'speculative_block_reason',
    'risk', 'required_permissions',
  ]);
  assert.equal(askUser['name'], 'ask_user');
  assert.equal(askUser['enabled'], true);
  assert.equal(askUser['resident'], true);
  assert.equal(askUser['risk'], 'Normal'); // metadata.risk.name(大写,无 lowercase)
  assert.deepEqual(askUser['required_permissions'], []);
});

test('tools_list:include_disabled=true → note 键逐字', async () => {
  const t: AgentTool = createToolsListTool(makeRegistry());
  const payload: JsonObject = textOf(await t.execute({ include_disabled: true }));
  assert.equal(payload['note'],
    'Disabled tool enumeration is not available in stage1 because tools are generated from the current agent configuration.');
});

test('tools_list:category/query 过滤(ignoreCase 名称与描述)', async () => {
  const t: AgentTool = createToolsListTool(makeRegistry());
  const byCategory: JsonObject = textOf(await t.execute({ category: 'context' }));
  assert.equal(byCategory['enabled_count'], 1);
  const byQuery: JsonObject = textOf(await t.execute({ query: 'TIME' }));
  assert.equal(byQuery['enabled_count'], 1);
  const noHit: JsonObject = textOf(await t.execute({ query: 'zzzzz' }));
  assert.equal(noHit['enabled_count'], 0);
});

test('tools_list:include_schema → Kotlin data class toString 形态', async () => {
  const t: AgentTool = createToolsListTool(makeRegistry());
  const payload: JsonObject = textOf(
    await t.execute({ include_schema: true, category: 'utility' }));
  const tools: JsonValue = payload['tools'];
  const first: JsonObject = (tools as JsonValue[])[0] as JsonObject;
  // registry.tools() 包装注入 display_title(ToolRegistry.kt:66-75 忠实)
  assert.equal(first['schema'],
    'Obj(properties={"query":{"type":"string","description":"q"},"display_title":{"type":"string","description":"Optional short user-facing action title in Chinese, 4-14 chars, describing this specific step (e.g. 写入第一卷, 合并最终文件)."}}, required=[query])');
});

// ===== tool_search(ToolSearch.kt:30-268) =====

test('tool_search:精确名命中 → 最高分置顶 + 排除自身', async () => {
  const reg: ToolRegistry = makeRegistry();
  const t: AgentTool = createToolSearchTool(reg);
  const payload: JsonObject = textOf(await t.execute({ query: 'ask_user' }));
  assert.equal(payload['status'], 'ok');
  assert.equal(payload['query'], 'ask_user');
  assert.equal(payload['limit'], 5);
  assert.equal(payload['total_tools'], 3);
  const expanded: JsonValue = payload['expanded_tools'];
  assert.ok(Array.isArray(expanded));
  assert.equal((expanded as JsonValue[])[0], 'ask_user');
  assert.ok(!(expanded as JsonValue[]).includes('tool_search'));
  assert.equal(payload['callability_note'],
    'Only tools in expanded_tools are newly callable on the next model step. tools_list is catalog/debug only and does not expose hidden schemas.');
  assert.equal(payload['next_step'],
    'On the next model step, call one of expanded_tools exactly. Do not call tools only seen in tools_list unless you first expose them with tool_search(query="<exact_tool_name>"). Permissions still apply.');
  const trace: JsonObject = payload['trace'] as JsonObject;
  assert.equal(trace['mode'], 'bypass'); // 3 <= 40
  assert.equal(typeof trace['estimated_full_schema_chars'], 'number');
});

test('tool_search:无命中 → category_candidates + debug_hint 逐字', async () => {
  const reg: ToolRegistry = makeRegistry();
  const t: AgentTool = createToolSearchTool(reg);
  const payload: JsonObject = textOf(await t.execute({ query: 'zzzzzz' }));
  assert.equal(payload['matches_count'], 0);
  assert.equal(payload['debug_hint'],
    'No matching tool was expanded. Try a more concrete Chinese/English query, or use tools_list only to identify an exact tool name and then call tool_search(query="<exact_tool_name>").');
  assert.ok(Array.isArray(payload['category_candidates']));
  assert.equal('next_step' in payload, false);
});

// D-082:profile 透传(ToolSearch.kt:115-118)— 非 null 时 trace 出
//   profile(name.lowercase)/profile_filtered(!= FULL);键序在 query 后 hit_tools 前
test('tool_search:profile 非 null → trace profile/profile_filtered', async () => {
  const reg: ToolRegistry = makeRegistry();
  const t: AgentTool = createToolSearchTool(reg, 'web_read');
  const payload: JsonObject = textOf(await t.execute({ query: 'ask_user' }));
  const trace: JsonObject = payload['trace'] as JsonObject;
  assert.equal(trace['profile'], 'web_read');
  assert.equal(trace['profile_filtered'], true);
  // 键序:mode, query, profile, profile_filtered, hit_tools ...
  const keys: string[] = Object.keys(trace);
  assert.ok(keys.indexOf('profile') > keys.indexOf('query'));
  assert.ok(keys.indexOf('profile') < keys.indexOf('hit_tools'));
  assert.ok(keys.indexOf('profile_filtered') > keys.indexOf('profile'));
  assert.ok(keys.indexOf('profile_filtered') < keys.indexOf('hit_tools'));
});

test('tool_search:profile=full → profile_filtered=false;缺省 → 无键', async () => {
  const reg: ToolRegistry = makeRegistry();
  const full: AgentTool = createToolSearchTool(reg, 'full');
  const traceFull: JsonObject =
    (textOf(await full.execute({ query: 'ask_user' })))['trace'] as JsonObject;
  assert.equal(traceFull['profile'], 'full');
  assert.equal(traceFull['profile_filtered'], false);
  const bare: AgentTool = createToolSearchTool(reg);
  const traceBare: JsonObject =
    (textOf(await bare.execute({ query: 'ask_user' })))['trace'] as JsonObject;
  assert.equal('profile' in traceBare, false);
  assert.equal('profile_filtered' in traceBare, false);
});

test('tool_search:limit 越界 coerce 1..20 + category 过滤小写化', async () => {
  const reg: ToolRegistry = makeRegistry();
  const t: AgentTool = createToolSearchTool(reg);
  const payload: JsonObject = textOf(
    await t.execute({ query: '', category: 'CONTEXT', limit: 99 }));
  assert.equal(payload['limit'], 20);
  assert.equal(payload['category'], 'context');
  const expanded: JsonValue = payload['expanded_tools'];
  assert.deepEqual(expanded, ['conversation_search']);
});

test('tool_search:命中条目 toJson 键序 + risk 小写 + schema Kotlin 形态', async () => {
  const reg: ToolRegistry = makeRegistry();
  const t: AgentTool = createToolSearchTool(reg);
  const payload: JsonObject = textOf(await t.execute({ query: 'ask_user' }));
  const tools: JsonValue = payload['tools'];
  const first: JsonObject = (tools as JsonValue[])[0] as JsonObject;
  assert.deepEqual(Object.keys(first), [
    'name', 'category', 'description', 'score', 'mutates', 'sensitive_read',
    'needs_approval', 'allows_auto_approval', 'risk', 'output_budget_chars',
    'schema',
  ]);
  assert.equal(first['risk'], 'normal');
  // registry.tools() 包装注入 display_title(ToolRegistry.kt:66-75 忠实)
  assert.equal(first['schema'],
    'Obj(properties={"query":{"type":"string","description":"q"},"display_title":{"type":"string","description":"Optional short user-facing action title in Chinese, 4-14 chars, describing this specific step (e.g. 写入第一卷, 合并最终文件)."}}, required=[query])');
  assert.ok((first['score'] as number) >= 240);
});

test('tool_search:systemPrompt — 类目计数 + resident 计数动态拼入', () => {
  const reg: ToolRegistry = makeRegistry();
  const t: AgentTool = createToolSearchTool(reg);
  const sp: string = t.systemPrompt(
    { id: 'm', name: 'm', contextWindowTokens: null } as unknown as never, []);
  assert.ok(sp.includes('This run has 3 generated tools across categories:'));
  assert.ok(sp.includes('utility:2'));
  assert.ok(sp.includes('context:1'));
  assert.ok(sp.includes('Resident tools currently stay visible without search:'));
  assert.ok(sp.startsWith('Tool discovery:'));
});
