// tool_activity.test.ts — AgentToolActivityStore + 沙盒时间线(D-122)
// 锚点:AgentToolActivityStore.kt/AgentRuntimeModels.kt/ToolFailure.kt/
//   ChatPage.kt:1166-1470(行号见实现头注)

import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  ACTIVITY_MAX_INPUT_PREVIEW_CHARS,
  ACTIVITY_MAX_OUTPUT_TAIL_CHARS,
  AgentToolActivityStore,
  deriveSandboxActivities,
  indicatesFailure,
  makeSandboxActivityUiState,
  mergeSandboxTimeline,
  toolActivityStatus,
  toolDefaultRuntime,
  toolDefaultWorkspace,
  toolOutputJson,
  toolSandboxTitle,
  withStepProgress,
} from '../main/ets/chat/tool_activity.ts';

import type {
  SandboxActivityUiState,
} from '../main/ets/chat/tool_activity.ts';
import { makeConversation, makeMessageNode } from '../main/ets/chat/conversation.ts';
import type { Conversation } from '../main/ets/chat/conversation.ts';
import { makeUIMessage, makeUserMessage } from '../main/ets/chat/message.ts';
import type { UIMessage, UIMessagePart, UIMessagePartTool } from '../main/ets/chat/message.ts';

const makeTool = (
  toolCallId: string,
  toolName: string,
  input: string,
  output: UIMessagePart[] = [],
  approvalState: UIMessagePartTool['approvalState'] = { type: 'auto' },
): UIMessagePartTool => ({
  type: 'tool', toolCallId, toolName, input, output, approvalState, metadata: null,
});

const textPart = (text: string): UIMessagePart => ({ type: 'text', text, metadata: null });

const convWith = (id: string, messages: UIMessage[]): Conversation =>
  makeConversation(id, messages.map((m: UIMessage) => makeMessageNode([m])));

// ===== Store =====

test('startTool:id 前缀/inputPreview 截 800/初态 RUNNING/订阅首发射', () => {
  const store: AgentToolActivityStore = new AgentToolActivityStore();
  const seen: Array<SandboxActivityUiState | null> = [];
  store.subscribe((a: SandboxActivityUiState | null): void => { seen.push(a); });
  assert.equal(seen.length, 1);
  assert.equal(seen[0], null);
  const id: string = store.startTool('t', '标题', 'x'.repeat(1000), 'MCP');
  assert.ok(id.startsWith('t_'));
  const cur: SandboxActivityUiState | null = store.sandboxActivity;
  assert.ok(cur !== null);
  assert.equal(cur.status, 'running');
  assert.equal(cur.inputPreview.length, ACTIVITY_MAX_INPUT_PREVIEW_CHARS);
  assert.equal(cur.runtime, 'MCP');
  assert.equal(seen.length, 2);
});

test('appendOutput:仅匹配 id 生效;换行拼接 + 1600 截尾', () => {
  const store: AgentToolActivityStore = new AgentToolActivityStore();
  const id: string = store.startTool('t', 't');
  store.appendOutput('other', 'nope');
  assert.equal(store.sandboxActivity?.outputTail, '');
  store.appendOutput(id, 'l1');
  store.appendOutput(id, 'l2');
  assert.equal(store.sandboxActivity?.outputTail, 'l1\nl2');
  store.appendOutput(id, 'y'.repeat(ACTIVITY_MAX_OUTPUT_TAIL_CHARS));
  const tailAfter: string = store.sandboxActivity?.outputTail ?? '';
  assert.equal(tailAfter.length, ACTIVITY_MAX_OUTPUT_TAIL_CHARS);
});

test('complete(output):SUCCEEDED + trim 截尾 + canCancel 复位', () => {
  const store: AgentToolActivityStore = new AgentToolActivityStore();
  const id: string = store.startTool('t', 't', '', '', '', true);
  store.complete(id, '  out  ');
  const cur: SandboxActivityUiState | null = store.sandboxActivity;
  assert.equal(cur?.status, 'succeeded');
  assert.equal(cur?.outputTail, 'out');
  assert.equal(cur?.canCancel, false);
  assert.ok(cur?.endedAtEpochMillis !== null);
  store.complete('other', 'x'); // 不匹配 → 不变
  assert.equal(store.sandboxActivity?.outputTail, 'out');
});

test('completeWithExitCode:0 → succeeded;非 0 → failed', () => {
  const store: AgentToolActivityStore = new AgentToolActivityStore();
  const id: string = store.startTool('t', 't');
  store.completeWithExitCode(id, 2, 'err-out');
  assert.equal(store.sandboxActivity?.status, 'failed');
  assert.equal(store.sandboxActivity?.outputTail, 'err-out');
  const id2: string = store.startTool('t', 't');
  store.completeWithExitCode(id2, 0, 'ok');
  assert.equal(store.sandboxActivity?.status, 'succeeded');
});

test('fail:outputTail = 失败 JSON 截尾;cancel/clear 语义', () => {
  const store: AgentToolActivityStore = new AgentToolActivityStore();
  const id: string = store.startTool('t', 't');
  store.fail(id, new Error('kaboom'));
  assert.equal(store.sandboxActivity?.status, 'failed');
  assert.equal(store.sandboxActivity?.outputTail,
    '{"status":"failed","message":"kaboom","recoverable":true}');
  const id2: string = store.startTool('t', 't');
  store.cancel(id2, 'stopped');
  assert.equal(store.sandboxActivity?.status, 'cancelled');
  assert.equal(store.sandboxActivity?.outputTail, 'stopped');
  store.clear('other');
  assert.ok(store.sandboxActivity !== null);
  store.clear(id2);
  assert.equal(store.sandboxActivity, null);
});

test('withConversation:scope 内 startTool 继承 conversationId;退出恢复', async () => {
  const store: AgentToolActivityStore = new AgentToolActivityStore();
  await store.withConversation('c1', async (): Promise<void> => {
    store.startTool('t', 't');
  });
  assert.equal(store.sandboxActivity?.conversationId, 'c1');
  store.startTool('t2', 't2');
  assert.equal(store.sandboxActivity?.conversationId, null);
  // blank → 不设 scope(:21-22)
  await store.withConversation('  ', async (): Promise<void> => {
    store.startTool('t3', 't3');
  });
  assert.equal(store.sandboxActivity?.conversationId, null);
  // start(activity) 显式 conversationId 不被 scope 覆盖(:142-148)
  await store.withConversation('c2', async (): Promise<void> => {
    store.start(makeSandboxActivityUiState({
      toolCallId: 'x', toolName: 't', title: 't', status: 'running',
      conversationId: 'explicit',
    }));
  });
  assert.equal(store.sandboxActivity?.conversationId, 'explicit');
});

test('toolOutputJson:blank/超长/非 object/解析失败 → {};正常解析', () => {
  assert.deepEqual(toolOutputJson(makeTool('1', 't', '{}', [])), {});
  assert.deepEqual(toolOutputJson(makeTool('1', 't', '{}', [textPart(' [1,2] ')])), {});
  assert.deepEqual(toolOutputJson(makeTool('1', 't', '{}', [textPart('not json')])), {});
  assert.deepEqual(
    toolOutputJson(makeTool('1', 't', '{}', [textPart('{"output":"ok"}')])),
    { output: 'ok' });
  const huge: UIMessagePartTool = makeTool('1', 't', '{}', [textPart('x'.repeat(80001))]);
  assert.deepEqual(toolOutputJson(huge), {});
});

test('indicatesFailure 矩阵(error/exit_code/failed/status)', () => {
  assert.ok(indicatesFailure({ error: 'x' }));
  assert.ok(!indicatesFailure({ error: '  ' }));
  assert.ok(!indicatesFailure({ exit_code: 0 }));
  assert.ok(indicatesFailure({ exit_code: 2 }));
  assert.ok(indicatesFailure({ failed: true }));
  assert.ok(!indicatesFailure({ failed: 'yes' })); // 非严格布尔
  assert.ok(indicatesFailure({ status: 'FAILED' }));
  assert.ok(indicatesFailure({ status: 'denied' }));
  assert.ok(!indicatesFailure({ status: 'ok' }));
  assert.ok(!indicatesFailure({ output: 'fine' }));
});

test('toolActivityStatus 矩阵(含 !isExecuted 双 RUNNING quirk)', () => {
  const out: UIMessagePart[] = [textPart('{"output":"ok"}')];
  assert.equal(toolActivityStatus(
    makeTool('1', 't', '{}', out, { type: 'pending' }), true, {}), 'waiting_for_permission');
  assert.equal(toolActivityStatus(
    makeTool('1', 't', '{}', out, { type: 'denied', reason: 'r' }), true, {}), 'cancelled');
  assert.equal(toolActivityStatus(makeTool('1', 't', '{}'), true, {}), 'running');
  assert.equal(toolActivityStatus(makeTool('1', 't', '{}'), false, {}), 'running');
  assert.equal(toolActivityStatus(
    makeTool('1', 't', '{}', out), false, { error: 'e' }), 'failed');
  assert.equal(toolActivityStatus(makeTool('1', 't', '{}', out), false, {}), 'succeeded');
});

test('Remote SSH activities identify the remote runtime without claiming a local workspace', () => {
  const tool: UIMessagePartTool = makeTool('ssh-call', 'terminal_execute', '{"command":"pwd"}');
  assert.equal(toolSandboxTitle(tool), '执行 SSH 命令');
  assert.equal(toolDefaultRuntime(tool.toolName), 'remote_ssh');
  assert.equal(toolDefaultRuntime('terminal_job_start'), 'remote_ssh');
  assert.equal(toolDefaultRuntime('terminal_session_start'), 'remote_ssh');
  assert.equal(toolDefaultWorkspace(tool.toolName), '');
  const activities = deriveSandboxActivities(convWith('ssh-conversation', [makeUIMessage('assistant', [tool])]), true, null);
  assert.equal(activities[0].runtime, 'remote_ssh');
  assert.equal(activities[0].workspace, '');
});

test('deriveSandboxActivities:工具行(title/status/步骤/canCancel/runtime 兜底)', () => {
  const conv: Conversation = convWith('c1', [
    makeUserMessage('go'),
    makeUIMessage('assistant', [
      makeTool('call1', 'mcp__echo', '{"q":"x"}', [textPart('{"output":"done"}')]),
      makeTool('call2', 'file_read', '{"path":"/a"}', [], { type: 'pending' }),
    ]),
  ]);
  const out: SandboxActivityUiState[] = deriveSandboxActivities(conv, true, null);
  assert.equal(out.length, 2);
  assert.equal(out[0].title, '调用 MCP echo');
  assert.equal(out[0].status, 'succeeded');
  assert.equal(out[0].outputTail, 'done');
  assert.equal(out[0].runtime, 'mcp');
  assert.equal(out[0].stepIndex, 1);
  assert.equal(out[0].stepTotal, 2);
  assert.equal(out[0].canCancel, false);
  assert.equal(out[1].status, 'waiting_for_permission');
  assert.equal(out[1].canCancel, true);
});

test('mergeSandboxTimeline:替换/追加 + 步骤重编号', () => {
  const a: SandboxActivityUiState = makeSandboxActivityUiState({
    toolCallId: 'a', toolName: 't', title: 'a', status: 'succeeded', stepIndex: 9, stepTotal: 9,
  });
  const b: SandboxActivityUiState = makeSandboxActivityUiState({
    toolCallId: 'b', toolName: 't', title: 'b', status: 'running',
  });
  // 替换匹配
  const liveA: SandboxActivityUiState = { ...a, status: 'failed' };
  const replaced: SandboxActivityUiState[] = mergeSandboxTimeline([a, b], liveA);
  assert.equal(replaced[0].status, 'failed');
  assert.equal(replaced[0].stepIndex, 1);
  assert.equal(replaced[0].stepTotal, 2);
  // 追加未匹配
  const liveC: SandboxActivityUiState = makeSandboxActivityUiState({
    toolCallId: 'c', toolName: 't', title: 'c', status: 'running',
  });
  const appended: SandboxActivityUiState[] = mergeSandboxTimeline([a, b], liveC);
  assert.equal(appended.length, 3);
  assert.equal(appended[2].stepIndex, 3);
  // 无 live → 原样(步骤仍重编号)
  const plain: SandboxActivityUiState[] = mergeSandboxTimeline([a], null);
  assert.equal(plain[0].stepIndex, 1);
});

test('withStepProgress:命中取索引+1;未命中 size+1;已有值不覆盖', () => {
  const conv: Conversation = convWith('c1', [
    makeUserMessage('go'),
    makeUIMessage('assistant', [makeTool('call1', 'file_read', '{}', [textPart('x')])]),
  ]);
  const hit: SandboxActivityUiState = withStepProgress(
    makeSandboxActivityUiState({ toolCallId: 'call1', toolName: 't', title: 't', status: 'running' }),
    conv);
  assert.equal(hit.stepIndex, 1);
  assert.equal(hit.stepTotal, 1);
  const miss: SandboxActivityUiState = withStepProgress(
    makeSandboxActivityUiState({ toolCallId: 'nope', toolName: 't', title: 't', status: 'running' }),
    conv);
  assert.equal(miss.stepIndex, 2);
  const preset: SandboxActivityUiState = withStepProgress(
    makeSandboxActivityUiState({
      toolCallId: 'nope', toolName: 't', title: 't', status: 'running', stepIndex: 7,
    }),
    conv);
  assert.equal(preset.stepIndex, 7);
});
