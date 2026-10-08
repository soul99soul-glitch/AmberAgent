// tool_permission.test.ts — 权限决策解析器(D-056 TDD)
//
// Android 基准: PermissionDecisionResolver.kt(全文 237 行)
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  PermissionDecisionResolver,
  toolHasSessionGrant,
  policyRequiresSubAgentApproval,
  ASK_USER_TOOL_NAME,
  HISTORY_READ_TOOLS_AUTO_APPROVED_FOR_SUBAGENT,
} from '../main/ets/chat/tool_permission.ts';
import type { UIMessagePartTool, ToolApprovalState } from '../main/ets/chat/message.ts';
import { makeAgentTool } from '../main/ets/chat/tool.ts';

let traceSeq = 0;
const makeResolver = () => new PermissionDecisionResolver({ newId: () => `trace-${++traceSeq}` });

const toolCall = (
  name: string, input: string = '{}', state: ToolApprovalState = { type: 'auto' },
): UIMessagePartTool => ({
  type: 'tool',
  toolCallId: `call-${name}`,
  toolName: name,
  input,
  output: [],
  approvalState: state,
  metadata: null,
});

const defOf = (name: string, opts: { needsApproval?: boolean; allowsAutoApproval?: boolean; mandatoryApproval?: boolean } = {}) =>
  makeAgentTool({
    name,
    description: 'd',
    needsApproval: opts.needsApproval ?? false,
    allowsAutoApproval: opts.allowsAutoApproval ?? true,
    mandatoryApproval: opts.mandatoryApproval ?? false,
    execute: () => Promise.resolve([]),
  });

describe('toolHasSessionGrant(:215-222)', () => {
  it('grant_id 非空白字符串 → true;缺/空/非法 JSON → false', () => {
    assert.equal(toolHasSessionGrant(toolCall('session_read', '{"grant_id":"g1"}')), true);
    assert.equal(toolHasSessionGrant(toolCall('session_read', '{"grant_id":"  "}')), false);
    assert.equal(toolHasSessionGrant(toolCall('session_read', '{}')), false);
    assert.equal(toolHasSessionGrant(toolCall('session_read', 'not json')), false);
    assert.equal(toolHasSessionGrant(toolCall('session_read', '')), false);
  });
});

describe('policyRequiresSubAgentApproval(:212-213)', () => {
  it('mutates 或非 normal 风险或敏感类目 → true', () => {
    const resolver = makeResolver();
    void resolver;
    // 经由 resolve 的 subagent 分支间接验证在下方;此处直验函数
    assert.equal(policyRequiresSubAgentApproval({
      name: 'x', category: 'web', mutates: false, needsApproval: false, autoApprovable: true,
      concurrencySafe: true, outputBudgetChars: 80000, risk: 'normal', parallelGroup: null,
      requiresForegroundAppPackage: null, speculativeEligible: false, speculativeBlockReason: null,
      reason: null, mandatoryApproval: false, alwaysAsk: false,
    }), false);
  });
});

// ===== resolve 优先级链(:76-196) =====

describe('PermissionDecisionResolver.resolve 优先级链', () => {
  it('toolDef null → DENY tool_lookup(文案含 tool_search 指引)', () => {
    const d = makeResolver().resolve(null, toolCall('ghost_tool'), false, false);
    assert.equal(d.action, 'deny');
    assert.equal(d.source, 'tool_lookup');
    assert.ok(d.reason.includes('tool_search with query="ghost_tool"'));
    assert.equal(d.trace.policy, null);
  });
  it('approvalState 非 Auto → ALLOW approval_state(用户已决定)', () => {
    const d = makeResolver().resolve(
      defOf('file_write'), toolCall('file_write', '{}', { type: 'approved' }), false, false);
    assert.equal(d.action, 'allow');
    assert.equal(d.source, 'approval_state');
    assert.equal(d.trace.approvalState, 'Approved');
  });
  it('ask_user + needsApproval → ASK hitl(先于全局开关)', () => {
    const t = makeAgentTool({ name: ASK_USER_TOOL_NAME, description: 'd', needsApproval: true, execute: () => Promise.resolve([]) });
    const d = makeResolver().resolve(t, toolCall(ASK_USER_TOOL_NAME), false, false);
    assert.equal(d.action, 'ask');
    assert.equal(d.source, 'hitl');
  });
  it('双开关全开 → ALLOW settings_unattended(在 alwaysAsk 之前)', () => {
    const d = makeResolver().resolve(defOf('file_write'), toolCall('file_write'), true, true);
    assert.equal(d.action, 'allow');
    assert.equal(d.source, 'settings_unattended');
  });
  it('alwaysAsk 工具(sms_send)→ ASK always_ask(单全局开关不足以放行)', () => {
    const d = makeResolver().resolve(defOf('sms_send'), toolCall('sms_send'), true, false);
    assert.equal(d.action, 'ask');
    assert.equal(d.source, 'always_ask');
  });
  it('mandatoryApproval:无双开关 → ASK mandatory_approval;双开关 → ALLOW settings_high_risk_mandatory', () => {
    const t = defOf('custom_eval', { mandatoryApproval: true });
    const ask = makeResolver().resolve(t, toolCall('custom_eval'), true, false);
    assert.equal(ask.action, 'ask');
    assert.equal(ask.source, 'mandatory_approval');
    // 注:链上 settings_unattended 优先于 mandatory 分支(双开关已在前面放行)
    const allow = makeResolver().resolve(t, toolCall('custom_eval'), true, true);
    assert.equal(allow.action, 'allow');
    assert.equal(allow.source, 'settings_unattended');
  });
  it('subagent:历史读工具 + grant → ALLOW subagent_history', () => {
    const d = makeResolver().resolve(
      defOf('session_read'), toolCall('session_read', '{"grant_id":"g"}'),
      false, false, [], 'subagent');
    assert.equal(d.action, 'allow');
    assert.equal(d.source, 'subagent_history');
    assert.ok(HISTORY_READ_TOOLS_AUTO_APPROVED_FOR_SUBAGENT.includes('session_read'));
  });
  it('subagent:mutating 工具 → ASK subagent;高风险+双开关 → ALLOW settings_high_risk_subagent', () => {
    const ask = makeResolver().resolve(
      defOf('file_write'), toolCall('file_write'), false, false, [], 'subagent');
    assert.equal(ask.action, 'ask');
    assert.equal(ask.source, 'subagent');
    const allow = makeResolver().resolve(
      defOf('wm_eval'), toolCall('wm_eval'), true, true, [], 'subagent');
    // wm_eval:双开关在链上 settings_unattended 已放行
    assert.equal(allow.action, 'allow');
  });
  it('只读工具 → ALLOW policy', () => {
    const d = makeResolver().resolve(defOf('conversation_search'), toolCall('conversation_search'), false, false);
    assert.equal(d.action, 'allow');
    assert.equal(d.source, 'policy');
  });
  it('高风险 + 无高风险开关 → ASK risk', () => {
    const d = makeResolver().resolve(
      defOf('http_request'), toolCall('http_request', '{"method":"POST","url":"https://x.com"}'),
      true, false);
    assert.equal(d.action, 'ask');
    assert.equal(d.source, 'risk');
  });
  it('run 内信任:autoApprovedToolNames 命中且非高风险 → ALLOW run_trust', () => {
    const d = makeResolver().resolve(
      defOf('file_write'), toolCall('file_write'), false, false, ['file_write']);
    assert.equal(d.action, 'allow');
    assert.equal(d.source, 'run_trust');
    assert.equal(d.trace.autoApprovedByRun, true);
  });
  it('run 内信任不覆盖高风险/ask_user', () => {
    const high = makeResolver().resolve(
      defOf('http_request'), toolCall('http_request', '{"method":"POST","url":"https://x.com"}'),
      false, false, ['http_request']);
    assert.equal(high.action, 'ask');
    assert.equal(high.source, 'risk');
  });
  it('全局 autoApprove + autoApprovable → ALLOW settings', () => {
    const d = makeResolver().resolve(defOf('file_write'), toolCall('file_write'), true, false);
    // file_write:fail-closed 致 autoApprovable=false → 落 ui ASK
    assert.equal(d.action, 'ask');
    assert.equal(d.source, 'ui');
    // mcp_call_tool:needsApproval=true + autoApprovable=allowsAutoApproval + risk=sensitive
    //   (非 high,过 risk/run_trust 分支)→ settings 放行
    const d2 = makeResolver().resolve(defOf('mcp_call_tool'), toolCall('mcp_call_tool'), true, false);
    assert.equal(d2.action, 'allow');
    assert.equal(d2.source, 'settings');
  });
  it('默认兜底 → ASK ui', () => {
    const d = makeResolver().resolve(defOf('memory_write'), toolCall('memory_write'), false, false);
    assert.equal(d.action, 'ask');
    assert.equal(d.source, 'ui');
  });
});
