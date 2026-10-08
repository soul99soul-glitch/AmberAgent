// tool_policy.test.ts — 工具调用策略(D-056 TDD)
//
// Android 基准: feature/tools/api ToolRegistry.kt(:167-707 策略全文)
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  toolMutatesState,
  toolRiskProfile,
  toolAlwaysAsk,
  requiresFailClosedAutoApproval,
  toolConcurrencySafe,
  toolSensitiveRead,
  toolForegroundPackageRequirement,
  toolSpeculativeBlockReason,
  toolInvocationPolicy,
  toolInvocationPolicyFromText,
  isPrivateNetworkTarget,
  containsExternalCliCouncilSeat,
  allowsExternalCliCouncil,
  enforceOutputBudget,
  withDisplayTitleHint,
} from '../main/ets/chat/tool_policy.ts';
import { makeAgentTool, makeInputSchemaObj } from '../main/ets/chat/tool.ts';
import type { UIMessagePart } from '../main/ets/chat/message.ts';

const toolNamed = (name: string) => makeAgentTool({
  name,
  description: 'd',
  execute: () => Promise.resolve([]),
});

// ===== mutatesState(:459-483) =====

describe('toolMutatesState', () => {
  it('名称启发:_write/_edit/_move/_delete/create/append/send/update → mutates', () => {
    assert.equal(toolMutatesState('file_write'), true);
    assert.equal(toolMutatesState('note_append'), true);
    assert.equal(toolMutatesState('send_message'), true);
    assert.equal(toolMutatesState('task_update'), true);
    assert.equal(toolMutatesState('conversation_search'), false);
    assert.equal(toolMutatesState('session_list'), false);
  });
  it('只读 token 抑制 comment/post:read_comment 不变,post_comment 变', () => {
    assert.equal(toolMutatesState('read_comment'), false);
    assert.equal(toolMutatesState('post_comment'), true);
  });
  it('显式表:memory_tool/deep_read_open 变,run_plan_update 不变,memory_list 例外', () => {
    assert.equal(toolMutatesState('memory_tool'), true);
    assert.equal(toolMutatesState('deep_read_open'), true);
    assert.equal(toolMutatesState('run_plan_update'), false);
    assert.equal(toolMutatesState('memory_list'), false);
    assert.equal(toolMutatesState('memory_write'), true);
    assert.equal(toolMutatesState('conversation_compact'), true);
    assert.equal(toolMutatesState('subagent_start'), true);
  });
});

// ===== riskProfile(:501-528) =====

describe('toolRiskProfile', () => {
  it('显式风险表', () => {
    assert.deepEqual(toolRiskProfile('http_request'), { risk: 'high', explicit: true });
    assert.deepEqual(toolRiskProfile('memory_tool'), { risk: 'high', explicit: true });
    assert.deepEqual(toolRiskProfile('mcp_call_tool'), { risk: 'sensitive', explicit: true });
    assert.deepEqual(toolRiskProfile('wm_eval'), { risk: 'high', explicit: true });
    assert.deepEqual(toolRiskProfile('session_read'), { risk: 'sensitive', explicit: true });
    assert.deepEqual(toolRiskProfile('subagent_start'), { risk: 'normal', explicit: true });
    assert.deepEqual(toolRiskProfile('sms_send'), { risk: 'high', explicit: true });
    assert.deepEqual(toolRiskProfile('screen_read_ui'), { risk: 'sensitive', explicit: true });
    assert.deepEqual(toolRiskProfile('terminal_execute'), { risk: 'sensitive', explicit: true });
    assert.deepEqual(toolRiskProfile('external_file_write'), { risk: 'high', explicit: true });
    assert.deepEqual(toolRiskProfile('external_file_read'), { risk: 'normal', explicit: false });
    assert.deepEqual(toolRiskProfile('get_time_info'), { risk: 'normal', explicit: false });
  });
});

// ===== alwaysAsk(:530-534) =====

describe('toolAlwaysAsk', () => {
  it('sms_send/contacts_write 恒 true;call_phone 看 direct_call;其余 false', () => {
    assert.equal(toolAlwaysAsk('sms_send', null), true);
    assert.equal(toolAlwaysAsk('contacts_write', null), true);
    assert.equal(toolAlwaysAsk('call_phone', { direct_call: 'true' }), true);
    assert.equal(toolAlwaysAsk('call_phone', { direct_call: 'false' }), false);
    assert.equal(toolAlwaysAsk('call_phone', {}), false);
    assert.equal(toolAlwaysAsk('file_read', null), false);
  });
});

// ===== fail-closed / concurrencySafe / sensitiveRead / foreground =====

describe('分类门', () => {
  it('requiresFailClosedAutoApproval(:536-541)', () => {
    assert.equal(requiresFailClosedAutoApproval(true, 'web', false), true);
    assert.equal(requiresFailClosedAutoApproval(false, 'system', false), true);
    assert.equal(requiresFailClosedAutoApproval(false, 'web', false), false);
    assert.equal(requiresFailClosedAutoApproval(true, 'system', true), false);
  });
  it('toolConcurrencySafe(:552-559)', () => {
    assert.equal(toolConcurrencySafe('terminal_execute'), false);
    assert.equal(toolConcurrencySafe('cron_task_create'), false);
    assert.equal(toolConcurrencySafe('cron_task_list'), true);
    assert.equal(toolConcurrencySafe('agent_task_list'), true);
    assert.equal(toolConcurrencySafe('tool_search'), true);
    assert.equal(toolConcurrencySafe('subagent_start'), false);
    assert.equal(toolConcurrencySafe('file_write'), false);
    assert.equal(toolConcurrencySafe('file_read'), true);
  });
  it('toolSensitiveRead(:560-566)', () => {
    assert.equal(toolSensitiveRead('session_read'), true);
    assert.equal(toolSensitiveRead('screen_read_ui'), true);
    assert.equal(toolSensitiveRead('file_read'), false);
  });
  it('toolForegroundPackageRequirement(:568-577)', () => {
    assert.equal(toolForegroundPackageRequirement('officepro_read_screen'), 'configured_officepro_target');
    assert.equal(toolForegroundPackageRequirement('file_read'), null);
  });
});

// ===== speculativeBlockReason(:579-600) =====

describe('toolSpeculativeBlockReason', () => {
  it('判定序逐字:risk→mutates→needsApproval→concurrency→parallelGroup→foreground→category', () => {
    assert.equal(toolSpeculativeBlockReason('x', 'web', false, false, true, 'high', 'web', null), 'risk_not_normal');
    assert.equal(toolSpeculativeBlockReason('x', 'web', true, false, true, 'normal', 'web', null), 'mutates_state');
    assert.equal(toolSpeculativeBlockReason('x', 'web', false, true, true, 'normal', 'web', null), 'needs_approval');
    assert.equal(toolSpeculativeBlockReason('x', 'web', false, false, false, 'normal', 'web', null), 'not_concurrency_safe');
    assert.equal(toolSpeculativeBlockReason('x', 'web', false, false, true, 'normal', null, null), 'no_parallel_group');
    assert.equal(toolSpeculativeBlockReason('x', 'web', false, false, true, 'normal', 'web', 'pkg'), 'requires_foreground_app');
    assert.equal(toolSpeculativeBlockReason('x', 'terminal', false, false, true, 'normal', 'terminal', null), 'category_blocked');
    assert.equal(toolSpeculativeBlockReason('cron_task_create', 'cron', false, false, true, 'normal', 'cron', null), 'cron_mutation_blocked');
    assert.equal(toolSpeculativeBlockReason('file_read', 'workspace', false, false, true, 'normal', 'workspace', null), null);
  });
});

// ===== isPrivateNetworkTarget(:175-201) =====

describe('isPrivateNetworkTarget', () => {
  it('localhost/后缀域名/IPv4 私网段/IPv6 本地', () => {
    assert.equal(isPrivateNetworkTarget('http://localhost:8080/x'), true);
    assert.equal(isPrivateNetworkTarget('http://a.localhost/'), true);
    assert.equal(isPrivateNetworkTarget('http://nas.lan/'), true);
    assert.equal(isPrivateNetworkTarget('http://10.0.0.1/'), true);
    assert.equal(isPrivateNetworkTarget('http://192.168.1.1/'), true);
    assert.equal(isPrivateNetworkTarget('http://172.16.0.1/'), true);
    assert.equal(isPrivateNetworkTarget('http://172.31.255.255/'), true);
    assert.equal(isPrivateNetworkTarget('http://172.32.0.1/'), false);
    assert.equal(isPrivateNetworkTarget('http://169.254.1.1/'), true);
    assert.equal(isPrivateNetworkTarget('http://100.64.0.1/'), true);
    assert.equal(isPrivateNetworkTarget('http://127.0.0.1/'), true);
    assert.equal(isPrivateNetworkTarget('http://0.0.0.0/'), true);
    assert.equal(isPrivateNetworkTarget('http://[::1]/'), true);
    assert.equal(isPrivateNetworkTarget('http://[fe80::1]/'), true);
    assert.equal(isPrivateNetworkTarget('http://[fd00::1]/'), true);
    assert.equal(isPrivateNetworkTarget('https://example.com/'), false);
    assert.equal(isPrivateNetworkTarget('not a url'), false);
    assert.equal(isPrivateNetworkTarget(null), false);
  });
});

// ===== input helper(:659-670) + council 判定(:662-697) =====

describe('input 取值与 council 判定', () => {
  it('containsExternalCliCouncilSeat:planned_seats/seats 数组 runner_type/external_tool', () => {
    assert.equal(containsExternalCliCouncilSeat({ planned_seats: [{ runner_type: 'external_cli' }] }), true);
    assert.equal(containsExternalCliCouncilSeat({ task: { seats: [{ runner_type: 'CODEX_CLI' }] } }), true);
    assert.equal(containsExternalCliCouncilSeat({ seats: [{ external_tool: 'gemini' }] }), true);
    assert.equal(containsExternalCliCouncilSeat({ seats: [{ runner_type: 'api' }] }), false);
    assert.equal(containsExternalCliCouncilSeat(null), false);
  });
  it('allowsExternalCliCouncil:root 或 task 的 allow_external_cli', () => {
    assert.equal(allowsExternalCliCouncil({ allow_external_cli: 'true' }), true);
    assert.equal(allowsExternalCliCouncil({ task: { allow_external_cli: 'true' } }), true);
    assert.equal(allowsExternalCliCouncil({}), false);
  });
});

// ===== invocationPolicy(:204-422) =====

describe('toolInvocationPolicy', () => {
  it('file_write:mutates → fail-closed 不可自动批准,speculative blocked', () => {
    const p = toolInvocationPolicy(toolNamed('file_write'), null);
    assert.equal(p.mutates, true);
    assert.equal(p.needsApproval, true);
    assert.equal(p.autoApprovable, false); // fail-closed(非显式风险)
    assert.equal(p.parallelGroup, null);
    assert.equal(p.speculativeBlockReason, 'mutates_state');
  });
  it('mandatoryApproval 工具:needsApproval 恒 true 且不可自动批准', () => {
    const t = makeAgentTool({ name: 'custom_tool', description: 'd', mandatoryApproval: true, execute: () => Promise.resolve([]) });
    const p = toolInvocationPolicy(t, null);
    assert.equal(p.mandatoryApproval, true);
    assert.equal(p.needsApproval, true);
    assert.equal(p.autoApprovable, false);
  });
  it('http_request:GET 公网安全/POST 高风险/私网 GET 高风险', () => {
    const t = toolNamed('http_request');
    const safe = toolInvocationPolicy(t, { method: 'GET', url: 'https://example.com' });
    assert.equal(safe.mutates, false);
    assert.equal(safe.risk, 'normal');
    assert.equal(safe.needsApproval, false);
    assert.equal(safe.concurrencySafe, true);
    const post = toolInvocationPolicy(t, { method: 'POST', url: 'https://example.com' });
    assert.equal(post.mutates, true);
    assert.equal(post.risk, 'high');
    assert.equal(post.needsApproval, true);
    assert.equal(post.autoApprovable, false);
    const lan = toolInvocationPolicy(t, { method: 'GET', url: 'http://192.168.1.1/admin' });
    assert.equal(lan.risk, 'high');
    assert.equal(lan.needsApproval, true);
  });
  it('memory_tool:read 系安全/write 系高风险', () => {
    const t = toolNamed('memory_tool');
    const read = toolInvocationPolicy(t, { action: 'search' });
    assert.equal(read.mutates, false);
    assert.equal(read.risk, 'normal');
    assert.equal(read.needsApproval, false);
    const write = toolInvocationPolicy(t, { action: 'write' });
    assert.equal(write.mutates, true);
    assert.equal(write.risk, 'high');
    assert.equal(write.needsApproval, true);
  });
  it('wm_*:eval 高风险/click sensitive/tab_close Normal/session parallel group', () => {
    const evalP = toolInvocationPolicy(toolNamed('wm_eval'), null);
    assert.equal(evalP.risk, 'high');
    assert.equal(evalP.autoApprovable, false);
    assert.equal(evalP.parallelGroup, 'webmount:unbound');
    const click = toolInvocationPolicy(toolNamed('wm_click'), { session_id: 's1' });
    assert.equal(click.risk, 'sensitive');
    assert.equal(click.parallelGroup, 'webmount:s1');
    const close = toolInvocationPolicy(toolNamed('wm_tab_close'), { session_id: 's1' });
    assert.equal(close.risk, 'normal');
    assert.equal(close.needsApproval, false);
    assert.equal(close.autoApprovable, true);
  });
  it('model_council_start:外部 CLI 席位 → mandatory + sensitive', () => {
    const plain = toolInvocationPolicy(toolNamed('model_council_start'), { seats: [{ runner_type: 'api' }] });
    assert.equal(plain.mandatoryApproval, false);
    const external = toolInvocationPolicy(toolNamed('model_council_start'), { seats: [{ runner_type: 'cli' }] });
    assert.equal(external.mutates, true);
    assert.equal(external.risk, 'sensitive');
    assert.equal(external.needsApproval, true);
    assert.equal(external.mandatoryApproval, true);
    assert.equal(external.autoApprovable, false);
  });
  it('toolInvocationPolicyFromText:非法 JSON → null input 按默认分支', () => {
    const p = toolInvocationPolicyFromText(toolNamed('http_request'), 'not json');
    assert.equal(p.risk, 'normal'); // 默认 method=GET,url null → 非公网私 → safe
    assert.equal(p.mutates, false);
  });
});

// ===== enforceOutputBudget + truncatedEnvelope(:672-707) =====

describe('enforceOutputBudget', () => {
  it('非 text part 直通;超限 text → truncated envelope 并截断列表', () => {
    const img: UIMessagePart = { type: 'image', url: 'data:image/png;base64,x', metadata: null } as UIMessagePart;
    const parts: UIMessagePart[] = [
      img,
      { type: 'text', text: 'a'.repeat(5000), metadata: null },
      { type: 'text', text: 'tail', metadata: null },
    ];
    const out = enforceOutputBudget(parts, 2000);
    assert.equal(out.length, 2);
    assert.equal(out[0], img);
    assert.equal(out[1].type, 'text');
    const env = JSON.parse((out[1] as { text: string }).text);
    assert.equal(env.status, 'truncated');
    assert.equal(env.truncated, true);
    assert.equal(env.total_chars, 5000);
    assert.equal(env.max_chars, 2000);
    assert.equal(env.content_tail.length, Math.max(2000 - 512, 1024));
    assert.equal(env.note, 'Tool output exceeded the registry output budget. The original tool result remains in the local transcript.');
  });
});

// ===== withDisplayTitleHint(:131-154) =====

describe('withDisplayTitleHint', () => {
  it('Obj schema 注入 display_title;subagent_start 豁免;null 直通', () => {
    const schema = makeInputSchemaObj({ q: { type: 'string' } }, ['q']);
    const hinted = withDisplayTitleHint(schema, 'search_web');
    assert.ok(hinted !== null);
    assert.ok('display_title' in (hinted?.properties ?? {}));
    assert.deepEqual(hinted?.required, ['q']);
    assert.equal(withDisplayTitleHint(schema, 'subagent_start'), schema);
    assert.equal(withDisplayTitleHint(null, 'x'), null);
  });
});
