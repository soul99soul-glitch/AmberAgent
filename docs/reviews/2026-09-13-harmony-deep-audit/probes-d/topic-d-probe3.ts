// topic-d-probe3.ts — 专题 D 补充 probe:D-11 文本宽批准 + AgentTaskTools 六件
// 只调用仓库真实 chat-domain .ts 逻辑 + 内存假端口;无网络/模型/文件副作用。
// 运行:cd harmony/chat && npx tsx <此文件绝对路径>

import assert from 'node:assert/strict';
import type { JsonObject, JsonValue } from '../../../../harmony/chat/src/main/ets/chat/json.ts';
import type { UIMessage, UIMessagePart, UIMessagePartTool } from '../../../../harmony/chat/src/main/ets/chat/message.ts';
import { makeUIMessage, makeUserMessage, canToolResumeExecution } from '../../../../harmony/chat/src/main/ets/chat/message.ts';
import { makeConversation, toMessageNode } from '../../../../harmony/chat/src/main/ets/chat/conversation.ts';
import { resolveIdleToolBlocker } from '../../../../harmony/chat/src/main/ets/chat/tool_approval.ts';
import { PermissionDecisionResolver } from '../../../../harmony/chat/src/main/ets/chat/tool_permission.ts';
import { makeAgentTool } from '../../../../harmony/chat/src/main/ets/chat/tool.ts';
import type { AgentTool } from '../../../../harmony/chat/src/main/ets/chat/tool.ts';
import { toolInvocationPolicy } from '../../../../harmony/chat/src/main/ets/chat/tool_policy.ts';
import { AgentTaskStore } from '../../../../harmony/chat/src/main/ets/chat/agent_task.ts';
import type { AgentTaskFilePort, AgentTaskSnapshot } from '../../../../harmony/chat/src/main/ets/chat/agent_task.ts';
import { AgentTaskScheduler } from '../../../../harmony/chat/src/main/ets/chat/agent_task_scheduler.ts';
import { AgentTaskTools } from '../../../../harmony/chat/src/main/ets/chat/agent_task_tools.ts';
import { filterToolProfile } from '../../../../harmony/chat/src/main/ets/chat/tool_profile_filter.ts';

let passCount = 0;
let failCount = 0;
const probe = async (name: string, body: () => Promise<void>): Promise<void> => {
  try {
    await body();
    passCount++;
    console.log(`PASS  ${name}`);
  } catch (e) {
    failCount++;
    console.log(`FAIL  ${name}`);
    console.log(`      ${e instanceof Error ? e.message : String(e)}`);
  }
};

const textPart = (text: string): UIMessagePart => ({ type: 'text', text, metadata: null });
const pendingTool = (name: string, input: string, id: string): UIMessagePartTool => ({
  type: 'tool', toolName: name, toolCallId: id, input, output: [],
  approvalState: { type: 'pending' }, metadata: null,
});
const makeConvWithPending = (tool: UIMessagePartTool): ReturnType<typeof makeConversation> =>
  makeConversation('c1', [toMessageNode(makeUIMessage('assistant', [tool]))]);

// wm_eval: mandatoryApproval + high; sms_send: alwaysAsk + high
const wmEval = makeAgentTool({
  name: 'wm_eval', description: 'eval',
  needsApproval: true, allowsAutoApproval: false, mandatoryApproval: true,
  execute: async (): Promise<UIMessagePart[]> => [textPart('{}')],
});
const smsSend = makeAgentTool({
  name: 'sms_send', description: 'send sms',
  needsApproval: true, allowsAutoApproval: false,
  execute: async (): Promise<UIMessagePart[]> => [textPart('{}')],
});
const defs = new Map<string, AgentTool>([['wm_eval', wmEval], ['sms_send', smsSend]]);

// ===== D-11:UI 呈现 vs 文本 "继续" 批准 =====
await probe('D11a pending wm_eval is not resumable before any user input', async () => {
  const conv = makeConvWithPending(pendingTool('wm_eval', '{"code":"x"}', 't1'));
  assert.equal(canToolResumeExecution(conv.messageNodes[0].messages[0].parts[0] as UIMessagePartTool), false);
});

await probe('D11b pure-text "继续写首诗" approves pending wm_eval (mandatory bypass)', async () => {
  const conv = makeConvWithPending(pendingTool('wm_eval', '{"code":"x"}', 't1'));
  const r = resolveIdleToolBlocker(conv, '继续写首诗', true);
  assert.equal(r.changed, true, 'blocker must change');
  assert.equal(r.shouldResume, true, 'resume without user touching approve button');
  assert.equal(r.event, 'pending_tool_resume');
  const approved = r.conversation.messageNodes[0].messages[0].parts[0] as UIMessagePartTool;
  assert.equal(approved.approvalState.type, 'approved',
    `approvalState=${approved.approvalState.type} (user typed a poem continuation, not approval)`);
  assert.equal(canToolResumeExecution(approved), true);
});

await probe('D11c approved-by-text wm_eval resolves to allow despite mandatory+high', async () => {
  const conv = makeConvWithPending(pendingTool('wm_eval', '{"code":"x"}', 't1'));
  const r = resolveIdleToolBlocker(conv, '继续写首诗', true);
  const approved = r.conversation.messageNodes[0].messages[0].parts[0] as UIMessagePartTool;
  const resolver = new PermissionDecisionResolver({ newId: () => 't' });
  // 用户没有开任何自动批准开关
  const d = resolver.resolve(wmEval, approved, false, false, [], 'normal');
  assert.equal(d.action, 'allow', `action=${d.action} source=${d.source}`);
  assert.equal(d.source, 'approval_state');
  // 对照:初次(approvalState=auto)单开关对 mandatory+high 仍是 ask
  const fresh: UIMessagePartTool = {
    type: 'tool', toolName: 'wm_eval', toolCallId: 't2', input: '{"code":"x"}',
    output: [], approvalState: { type: 'auto' }, metadata: null,
  };
  const d2 = resolver.resolve(wmEval, fresh, true, false, [], 'normal');
  assert.equal(d2.action, 'ask', `fresh action=${d2.action} source=${d2.source}`);
});

await probe('D11c2 resolver treats any non-auto state (incl. pending) as allow', async () => {
  // tool_permission.ts:182-184 approvalState!=='auto' → allow('User already decided.')
  // dispatcher.execute 对 pending 返回 null 兜底,但 resolveDecision 单独调用会放行
  const resolver = new PermissionDecisionResolver({ newId: () => 't' });
  const d = resolver.resolve(wmEval, pendingTool('wm_eval', '{"code":"x"}', 't9'), false, false, [], 'normal');
  assert.equal(d.action, 'allow', `action=${d.action} source=${d.source}`);
});

await probe('D11d pure-text "继续" also approves pending sms_send (alwaysAsk bypass)', async () => {
  const conv = makeConvWithPending(pendingTool('sms_send', '{"to":"x","text":"y"}', 't3'));
  const p = toolInvocationPolicy(smsSend, { to: 'x', text: 'y' });
  assert.equal(p.alwaysAsk, true, 'sms_send must be alwaysAsk');
  assert.equal(p.risk, 'high');
  const r = resolveIdleToolBlocker(conv, '继续吧', true);
  const approved = r.conversation.messageNodes[0].messages[0].parts[0] as UIMessagePartTool;
  const resolver = new PermissionDecisionResolver({ newId: () => 't' });
  const d = resolver.resolve(smsSend, approved, false, false, [], 'normal');
  assert.equal(d.action, 'allow', `action=${d.action} source=${d.source}`);
});

await probe('D11e unrelated message does NOT approve; instead cancels stale tool', async () => {
  const conv = makeConvWithPending(pendingTool('wm_eval', '{"code":"x"}', 't1'));
  const r = resolveIdleToolBlocker(conv, '今天天气不错', true);
  assert.equal(r.changed, true);
  assert.equal(r.shouldResume, false);
  const after = r.conversation.messageNodes[0].messages[0].parts[0] as UIMessagePartTool;
  assert.equal(after.approvalState.type, 'denied', `state=${after.approvalState.type}`);
});

await probe('D11f non-pure-text message with 继续 does NOT approve', async () => {
  const conv = makeConvWithPending(pendingTool('wm_eval', '{"code":"x"}', 't1'));
  const r = resolveIdleToolBlocker(conv, '继续写首诗', false);
  assert.equal(r.shouldResume, false, 'image+text message must not count as approval');
});

// ===== AgentTaskTools 六件 =====
class MemFiles implements AgentTaskFilePort {
  files = new Map<string, string>();
  async mkdirs(): Promise<void> {}
  async listJsonFileNames(dir: string): Promise<string[]> {
    const out: string[] = [];
    this.files.forEach((_v, k) => { if (k.startsWith(`${dir}/`) && k.endsWith('.json')) out.push(k.slice(dir.length + 1)); });
    return out;
  }
  async readText(p: string): Promise<string | null> { return this.files.has(p) ? this.files.get(p) as string : null; }
  async writeText(p: string, t: string): Promise<void> { this.files.set(p, t); }
  async delete(p: string): Promise<void> { this.files.delete(p); }
  async exists(p: string): Promise<boolean> { return this.files.has(p); }
  async isPathInside(root: string, p: string): Promise<boolean> { return p.startsWith(`${root}/`); }
}

const snap = (init: Partial<AgentTaskSnapshot> & { taskId: string }): AgentTaskSnapshot => ({
  schemaVersion: 2, type: 'subagent', title: 't', spec: null, runtime: 'mem',
  queueState: 'QUEUED', recoveryState: 'ACTIVE',
  retryPolicy: { retryable: false, requiresApproval: true, maxRetries: 0, retryCount: 0, reason: null },
  outputRef: { type: 'transcript', path: '/mem/out.jsonl', tailOffset: 0, exists: true },
  sourceToolName: 'subagent_start', sourceConversationId: 'c', status: 'RUNNING',
  outputPath: '/mem/out.jsonl', outputOffset: 0, createdAtMs: 1, updatedAtMs: 1,
  lastHeartbeatMs: null, notified: false, cancelCapability: true, permissionTraceId: null,
  summary: 's', lastErrorCode: null, error: null, ...init,
});

const parse = async (parts: UIMessagePart[]): Promise<JsonObject> =>
  JSON.parse((parts[0] as { text: string }).text) as JsonObject;

await probe('D12a six tools present; list/read/runtime not approval-gated', async () => {
  const store = await AgentTaskStore.create({ taskDir: '/mem/tasks', appFilesDir: '/mem', files: new MemFiles() });
  const scheduler = new AgentTaskScheduler(store);
  const tools = new AgentTaskTools(scheduler, { outputExists: async (): Promise<boolean> => true }).getTools();
  assert.deepEqual(tools.map((t: AgentTool): string => t.name),
    ['agent_task_list', 'agent_task_read', 'agent_task_cancel', 'agent_task_retry', 'agent_task_cleanup', 'agent_runtime_status']);
  const byName = new Map<string, AgentTool>(tools.map((t: AgentTool): [string, AgentTool] => [t.name, t]));
  const listP = toolInvocationPolicy(byName.get('agent_task_list') as AgentTool, {});
  const readP = toolInvocationPolicy(byName.get('agent_task_read') as AgentTool, { task_id: 'x' });
  const cancelP = toolInvocationPolicy(byName.get('agent_task_cancel') as AgentTool, { task_id: 'x' });
  const cleanupP = toolInvocationPolicy(byName.get('agent_task_cleanup') as AgentTool, { task_id: 'x' });
  assert.equal(listP.needsApproval, false);
  assert.equal(readP.needsApproval, false);
  assert.equal(cancelP.needsApproval, true, 'cancel must be approval-gated');
  assert.equal(cancelP.risk, 'sensitive');
  assert.equal(cleanupP.needsApproval, true, 'cleanup must be approval-gated');
});

await probe('D12b agent_task_retry is a no-op for subagent/cron (retryable=false)', async () => {
  const files = new MemFiles();
  const store = await AgentTaskStore.create({ taskDir: '/mem/tasks', appFilesDir: '/mem', files });
  const scheduler = new AgentTaskScheduler(store);
  await store.register(snap({ taskId: 'r1', status: 'FAILED' }));
  const tools = new AgentTaskTools(scheduler).getTools();
  const retry = (tools.find((t: AgentTool): boolean => t.name === 'agent_task_retry') as AgentTool);
  const payload = await parse(await retry.execute({ task_id: 'r1' } as JsonValue));
  assert.equal(payload['status'], 'failed', `status=${payload['status']}`);
  const task = payload['task'] as JsonObject;
  assert.equal(task['retryable'], false);
  assert.equal(task['last_error_code'], 'retry_not_allowed');
});

await probe('D12c agent_task_cancel works only when cancelCapability + callback', async () => {
  const files = new MemFiles();
  const store = await AgentTaskStore.create({ taskDir: '/mem/tasks', appFilesDir: '/mem', files });
  const scheduler = new AgentTaskScheduler(store);
  let called = 0;
  await store.register(snap({ taskId: 'c1', status: 'RUNNING', cancelCapability: true }),
    async (): Promise<boolean> => { called++; return true; });
  const tools = new AgentTaskTools(scheduler).getTools();
  const cancel = (tools.find((t: AgentTool): boolean => t.name === 'agent_task_cancel') as AgentTool);
  const payload = await parse(await cancel.execute({ task_id: 'c1' } as JsonValue));
  assert.equal(called, 1);
  assert.equal(payload['status'], 'cancelled', `status=${payload['status']}`);
});

await probe('D12d cron tasks register cancelCapability=false => cannot be cancelled from tools', async () => {
  const files = new MemFiles();
  const store = await AgentTaskStore.create({ taskDir: '/mem/tasks', appFilesDir: '/mem', files });
  const scheduler = new AgentTaskScheduler(store);
  // cron.ts toAgentTaskSnapshot: cancelCapability=false（与 status 无关）
  await store.register(snap({ taskId: 'cron1', type: 'cron', status: 'RUNNING', cancelCapability: false }));
  const tools = new AgentTaskTools(scheduler).getTools();
  const cancel = (tools.find((t: AgentTool): boolean => t.name === 'agent_task_cancel') as AgentTool);
  const payload = await parse(await cancel.execute({ task_id: 'cron1' } as JsonValue));
  assert.equal(payload['status'], 'running', `status=${payload['status']} (cancel refused)`);
  const task = payload['task'] as JsonObject;
  assert.match(String(task['error']), /cannot be cancelled/);
});

await probe('D12e agent_task_cleanup deletes output only when inside appFilesDir', async () => {
  const files = new MemFiles();
  files.files.set('/mem/out.jsonl', '{}');
  files.files.set('/etc/passwd', 'root');
  const store = await AgentTaskStore.create({ taskDir: '/mem/tasks', appFilesDir: '/mem', files });
  const scheduler = new AgentTaskScheduler(store);
  await store.register(snap({ taskId: 'k1', status: 'COMPLETED' }));
  await store.register(snap({ taskId: 'k2', status: 'COMPLETED', outputPath: '/etc/passwd', outputRef: null }));
  const tools = new AgentTaskTools(scheduler).getTools();
  const cleanup = (tools.find((t: AgentTool): boolean => t.name === 'agent_task_cleanup') as AgentTool);
  await cleanup.execute({ task_id: 'k1', delete_private_output: true } as JsonValue);
  assert.equal(files.files.has('/mem/out.jsonl'), false, 'inside path deleted');
  await cleanup.execute({ task_id: 'k2', delete_private_output: true } as JsonValue);
  assert.equal(files.files.has('/etc/passwd'), true, 'outside path must NOT be deleted');
});

await probe('D12f agent_task_cleanup is autoApprovable under single toggle (deletes private output)', async () => {
  const store = await AgentTaskStore.create({ taskDir: '/mem/tasks', appFilesDir: '/mem', files: new MemFiles() });
  const scheduler = new AgentTaskScheduler(store);
  const tools = new AgentTaskTools(scheduler).getTools();
  const cleanup = (tools.find((t: AgentTool): boolean => t.name === 'agent_task_cleanup') as AgentTool);
  const p = toolInvocationPolicy(cleanup, { task_id: 'x', delete_private_output: true });
  assert.equal(p.mutates, true);
  assert.equal(p.risk, 'sensitive');
  assert.equal(p.needsApproval, true);
  assert.equal(p.autoApprovable, true, 'single auto-approve toggle can silently delete transcript output');
  assert.equal(p.mandatoryApproval, false);
});

await probe('D12g minimal tool profile drops cancel/retry/cleanup (counter-evidence)', async () => {
  const store = await AgentTaskStore.create({ taskDir: '/mem/tasks', appFilesDir: '/mem', files: new MemFiles() });
  const scheduler = new AgentTaskScheduler(store);
  const tools = new AgentTaskTools(scheduler).getTools();
  const filtered = filterToolProfile(tools, 'minimal');
  const names = filtered.tools.map((t: AgentTool): string => t.name);
  assert.deepEqual(names.sort(), ['agent_runtime_status', 'agent_task_list', 'agent_task_read']);
  assert.equal(filtered.filteredCount, 3);
});

console.log(`\n=== probe3 summary: pass=${passCount} fail=${failCount} ===`);
if (failCount > 0) process.exitCode = 1;
