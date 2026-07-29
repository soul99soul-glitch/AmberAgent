// tool_activity.test.ts — AgentToolActivityStore + 沙盒时间线(D-122)
// 锚点:AgentToolActivityStore.kt/AgentRuntimeModels.kt/ToolFailure.kt/
//   ChatPage.kt:1166-1470(行号见实现头注)

import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  ACTIVITY_MAX_INPUT_PREVIEW_CHARS, ACTIVITY_MAX_OUTPUT_TAIL_CHARS,
  AgentToolActivityStore, compactSandboxText, currentRunMessages,
  deriveSandboxActivities, idleSandboxActivity, indicatesFailure,
  isActiveOperation, isSandboxActivityTool, makeSandboxActivityUiState,
  mergeSandboxTimeline,
  toolActivityOutputTail, toolActivityStatus, toolDefaultRuntime,
  toolDefaultWorkspace, toolOutputJson, toolOutputText, toolSandboxInputPreview,
  toolSandboxTitle, withStepProgress,
} from '../main/ets/chat/tool_activity.ts';
import {
  sanitizedToolFailureMessage, toAgentToolFailureJson,
} from '../main/ets/chat/tool_dispatcher.ts';
import type { SandboxActivityUiState } from '../main/ets/chat/tool_activity.ts';
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

// ===== ToolFailure =====

test('sanitizedToolFailureMessage:首个非空行 + 栈帧正则 + 空白折叠', () => {
  const err: Error = new Error('\n  first line at com.foo.Bar(Baz.kt:12)   spaced  \nsecond');
  assert.equal(sanitizedToolFailureMessage(err), 'first line spaced');
});

test('sanitizedToolFailureMessage:空白消息回退类名;清理后空回退固定串', () => {
  assert.equal(sanitizedToolFailureMessage(new Error('')), 'Error');
  const onlyFrames: Error = new Error('at com.foo.Bar(Baz.kt:12)');
  assert.equal(sanitizedToolFailureMessage(onlyFrames), 'Tool execution failed');
});

test('sanitizedToolFailureMessage:首行 360 截断', () => {
  const long: string = 'x'.repeat(400);
  assert.equal(sanitizedToolFailureMessage(new Error(long)).length, 360);
});

test('toAgentToolFailureJson:键序 status/message/recoverable + recoverable 恒 true', () => {
  const json: string = toAgentToolFailureJson(new Error('boom'));
  assert.equal(json, '{"status":"failed","message":"boom","recoverable":true}');
});

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

// ===== 时间线纯函数 =====

test('isSandboxActivityTool:mcp__ 前缀 + 名表命中/未命中', () => {
  assert.ok(isSandboxActivityTool('mcp__anything'));
  assert.ok(isSandboxActivityTool('search_web'));
  assert.ok(isSandboxActivityTool('vlm_task'));
  assert.ok(!isSandboxActivityTool('memory_recall'));
  assert.ok(!isSandboxActivityTool('mcp_'));
});

test('currentRunMessages:末条 user 之后;无 user → 全部', () => {
  const conv: Conversation = convWith('c1', [
    makeUserMessage('u1'),
    makeUIMessage('assistant', [textPart('a1')]),
    makeUIMessage('assistant', [textPart('a2')]),
  ]);
  assert.equal(currentRunMessages(conv).length, 2);
  const noUser: Conversation = convWith('c2', [makeUIMessage('assistant', [textPart('x')])]);
  assert.equal(currentRunMessages(noUser).length, 1);
});

test('toolOutputText:无限 join;限量尾块截取(金样)', () => {
  const tool: UIMessagePartTool = makeTool('1', 'file_read', '{}', [
    textPart('abcd'), textPart('ef'),
  ]);
  assert.equal(toolOutputText(tool), 'abcd\nef');
  assert.equal(toolOutputText(tool, 5), 'bcd\nef');
  assert.equal(toolOutputText(tool, 2), 'ef');
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

test('compactSandboxText:空白折叠 + 超长截断带 …', () => {
  assert.equal(compactSandboxText('  a   b\n c ', 20), 'a b c');
  assert.equal(compactSandboxText('x'.repeat(30), 20), `${'x'.repeat(19)}…`);
});

test('toolSandboxTitle/inputPreview/defaultRuntime 金样', () => {
  const search: UIMessagePartTool = makeTool('1', 'search_web', '{"query":"如何  使用 MCP"}');
  assert.equal(toolSandboxTitle(search), '网页搜索 如何 使用 MCP');
  assert.equal(toolSandboxInputPreview(search), '如何 使用 MCP');
  const longQuery: UIMessagePartTool = makeTool('1', 'search_web',
    `{"query":"${'q'.repeat(30)}"}`);
  assert.equal(toolSandboxTitle(longQuery), `网页搜索 ${'q'.repeat(19)}…`);
  assert.equal(toolSandboxTitle(makeTool('2', 'mcp__echo_tool', '{}')), '调用 MCP echo_tool');
  assert.equal(toolSandboxTitle(makeTool('3', 'memory_recall', '{}')), 'memory_recall');
  assert.equal(toolSandboxTitle(makeTool('4', 'file_read', '{"path":"/a/b"}')), '读取文件 /a/b');
  // inputPreview 键缺失 → input 全串 compact 180
  const noKey: UIMessagePartTool = makeTool('5', 'search_web', '{"other":"v"}');
  assert.equal(toolSandboxInputPreview(noKey), '{"other":"v"}');
  assert.equal(toolDefaultRuntime('mcp__x'), 'mcp');
  assert.equal(toolDefaultRuntime('file_write'), 'saf-workspace');
  assert.equal(toolDefaultRuntime('search_web'), 'web-search');
  assert.equal(toolDefaultRuntime('unknown'), '');
  assert.equal(toolDefaultWorkspace('file_read'), '/workspace');
  assert.equal(toolDefaultWorkspace('mcp__x'), '');
});

test('deriveSandboxActivities:空 + processingStatus 门', () => {
  const empty: Conversation = convWith('c1', []);
  assert.deepEqual(deriveSandboxActivities(empty, true, null), []);
  assert.deepEqual(deriveSandboxActivities(empty, false, '重试中'), []);
  const withStatus: SandboxActivityUiState[] =
    deriveSandboxActivities(empty, true, '重试中(1/5)');
  assert.equal(withStatus.length, 1);
  assert.equal(withStatus[0].toolCallId, 'processing-status');
  assert.equal(withStatus[0].title, '重试中(1/5)');
  assert.equal(withStatus[0].status, 'running');
  assert.equal(withStatus[0].canCancel, true);
  assert.equal(withStatus[0].conversationId, 'c1');
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

test('idleSandboxActivity 字段逐字 + isActiveOperation', () => {
  const idle: SandboxActivityUiState = idleSandboxActivity(convWith('c9', []));
  assert.equal(idle.toolCallId, 'agent-idle-c9');
  assert.equal(idle.toolName, 'agent_idle');
  assert.equal(idle.title, 'Agent 操作预览');
  assert.equal(idle.inputPreview, '等待下一次工具调用');
  assert.equal(idle.runtime, 'standby');
  assert.equal(idle.stepIndex, null);
  assert.ok(isActiveOperation(makeSandboxActivityUiState({
    toolCallId: '1', toolName: 't', title: 't', status: 'running',
  })));
  assert.ok(isActiveOperation(makeSandboxActivityUiState({
    toolCallId: '1', toolName: 't', title: 't', status: 'waiting_for_permission',
  })));
  assert.ok(!isActiveOperation(makeSandboxActivityUiState({
    toolCallId: '1', toolName: 't', title: 't', status: 'succeeded',
  })));
});

test('toolActivityOutputTail:output > error > outputText 回退;trim 截尾', () => {
  const tool: UIMessagePartTool = makeTool('1', 't', '{}', [textPart('{"output":"  tail  "}')]);
  assert.equal(toolActivityOutputTail(tool, { output: '  tail  ' }), 'tail');
  assert.equal(toolActivityOutputTail(tool, { error: ' e ' }), 'e');
  // outputJson 无 output/error 键 → outputText 原始串(:1419-1424 回退语义)
  assert.equal(toolActivityOutputTail(tool, {}), '{"output":"  tail  "}');
});
