// speculative_tool_runner.test.ts — D-063 推测执行
// Android 基准: feature/runtime/SpeculativeToolRunner.kt 全文 146 行
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { JsonValue } from '../main/ets/chat/json.ts';
import type { UIMessagePart, UIMessagePartTool } from '../main/ets/chat/message.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import { makeAgentTool } from '../main/ets/chat/tool.ts';
import { AgentToolDispatcher } from '../main/ets/chat/tool_dispatcher.ts';
import type {
  SpeculativeToolRunner, SpeculativeToolState,
} from '../main/ets/chat/speculative_tool_runner.ts';
import { createSpeculativeToolRunner } from '../main/ets/chat/speculative_tool_runner.ts';

const toolPart = (
  callId: string, name: string, input: string, executed: boolean = false,
): UIMessagePartTool => ({
  type: 'tool',
  toolCallId: callId,
  toolName: name,
  input,
  output: executed
    ? [{ type: 'text', text: 'done', metadata: null }]
    : [],
  approvalState: { type: 'auto' },
  metadata: null,
});

// 只读常规工具(名称无变更启发 → speculativeEligible=true)
const readOnlyTool = (
  name: string,
  run: (input: JsonValue) => Promise<UIMessagePart[]>,
): AgentTool => makeAgentTool({
  name,
  description: `ro ${name}`,
  execute: run,
});

const okRun = (input: JsonValue): Promise<UIMessagePart[]> =>
  Promise.resolve([{ type: 'text', text: `ok:${JSON.stringify(input)}`, metadata: null }]);

const defsOf = (tools: AgentTool[]): Map<string, AgentTool> =>
  new Map<string, AgentTool>(tools.map((t: AgentTool): [string, AgentTool] => [t.name, t]));

const stateOf = (st: SpeculativeToolState[], id: string): SpeculativeToolState | undefined =>
  st.find((s: SpeculativeToolState): boolean => s.toolCallId === id);

// 微任务排空(dispatcher.execute 内部 await 链)
const flushMicro = async (rounds: number = 30): Promise<void> => {
  for (let i = 0; i < rounds; i++) await Promise.resolve();
};

test('observe:eligible + 完整 JSON → pending → completed;execute autoApprove 双 false', async () => {
  const seen: boolean[][] = [];
  const tool: AgentTool = makeAgentTool({
    name: 'spec_alpha',
    description: 'ro',
    execute: (input: JsonValue): Promise<UIMessagePart[]> => {
      seen.push([true]);
      return okRun(input);
    },
  });
  const runner: SpeculativeToolRunner = createSpeculativeToolRunner({
    dispatcher: new AgentToolDispatcher(),
  });
  runner.observe([toolPart('c1', 'spec_alpha', '{"q":1}')], defsOf([tool]));
  let snap: SpeculativeToolState[] = runner.snapshot();
  assert.equal(snap.length, 1);
  assert.equal(snap[0].status, 'pending');
  await flushMicro();
  snap = runner.snapshot();
  assert.equal(snap[0].status, 'completed');
  assert.ok(snap[0].result !== null);
  assert.equal(snap[0].result?.output.length, 1);
});

test('observe 过滤:blank id/已执行/不完整 JSON/不 eligible → 不产生状态', () => {
  const tool: AgentTool = readOnlyTool('spec_beta', okRun);
  const gated: AgentTool = makeAgentTool({
    name: 'spec_gated', description: 'needs approval', needsApproval: true, execute: okRun,
  });
  const runner: SpeculativeToolRunner = createSpeculativeToolRunner({
    dispatcher: new AgentToolDispatcher(),
  });
  runner.observe([
    toolPart('', 'spec_beta', '{}'),            // blank id
    toolPart('c2', 'spec_beta', '{}', true),    // 已执行
    toolPart('c3', 'spec_beta', '{"q":'),       // 不完整 JSON
    toolPart('c4', 'spec_gated', '{}'),         // needsApproval → 不 eligible
    toolPart('c5', 'ghost', '{}'),              // 无定义
  ], defsOf([tool, gated]));
  assert.equal(runner.snapshot().length, 0);
});

test('observe:take(maxConcurrentTools) 截断(默认 4)', () => {
  const tools: AgentTool[] = [];
  const parts: UIMessagePartTool[] = [];
  for (let i = 0; i < 6; i++) {
    tools.push(readOnlyTool(`spec_t${i}`, okRun));
    parts.push(toolPart(`c${i}`, `spec_t${i}`, '{}'));
  }
  const runner: SpeculativeToolRunner = createSpeculativeToolRunner({
    dispatcher: new AgentToolDispatcher(),
  });
  runner.observe(parts, defsOf(tools));
  assert.equal(runner.snapshot().length, 4);
});

test('observe:同 id 同 name+input 重复观察 → 不重启;输入变化 → 旧 job cancel + 新 job', async () => {
  let calls: number = 0;
  const tool: AgentTool = readOnlyTool('spec_gamma', (input: JsonValue): Promise<UIMessagePart[]> => {
    calls += 1;
    return okRun(input);
  });
  const runner: SpeculativeToolRunner = createSpeculativeToolRunner({
    dispatcher: new AgentToolDispatcher(),
  });
  const defs: Map<string, AgentTool> = defsOf([tool]);
  runner.observe([toolPart('c1', 'spec_gamma', '{"v":1}')], defs);
  runner.observe([toolPart('c1', 'spec_gamma', '{"v":1}')], defs);
  await flushMicro();
  assert.equal(calls, 1);
  // 输入变化 → 取消重来
  runner.observe([toolPart('c1', 'spec_gamma', '{"v":2}')], defs);
  await flushMicro();
  assert.equal(calls, 2);
  const st: SpeculativeToolState | undefined = stateOf(runner.snapshot(), 'c1');
  assert.equal(st?.input, '{"v":2}');
  assert.equal(st?.status, 'completed');
});

test('execute 失败 → dispatcher 归一为失败载荷结果(executeWithHooks catch-all),状态 completed 且可复用', async () => {
  const tool: AgentTool = readOnlyTool('spec_delta', (): Promise<UIMessagePart[]> =>
    Promise.reject(new Error('boom')));
  const runner: SpeculativeToolRunner = createSpeculativeToolRunner({
    dispatcher: new AgentToolDispatcher(),
  });
  runner.observe([toolPart('c1', 'spec_delta', '{}')], defsOf([tool]));
  await flushMicro();
  const st: SpeculativeToolState | undefined = stateOf(runner.snapshot(), 'c1');
  // Android 同:工具异常被 executeWithHooks 归一为 output 失败载荷 →
  //   Deferred 正常返回 → COMPLETED(runner 的 catch 仅兜 dispatcher 级异常)
  assert.equal(st?.status, 'completed');
  const payload = JSON.parse(
    (st?.result?.output[0] as { text: string }).text) as { status: string; message: string };
  assert.equal(payload.status, 'failed');
  assert.ok(payload.message.includes('boom'));
  const reused: Map<string, UIMessagePartTool> = await runner.reusableResults(
    [toolPart('c1', 'spec_delta', '{}')]);
  assert.equal(reused.size, 1);
});

test('reusableResults:completed → 复用;final 缺失 → cancel+discarded 不复用', async () => {
  const tool: AgentTool = readOnlyTool('spec_eps', okRun);
  const runner: SpeculativeToolRunner = createSpeculativeToolRunner({
    dispatcher: new AgentToolDispatcher(),
  });
  const defs: Map<string, AgentTool> = defsOf([tool]);
  runner.observe([
    toolPart('c1', 'spec_eps', '{"a":1}'),
    toolPart('c2', 'spec_eps', '{"a":2}'),
  ], defs);
  await flushMicro();
  // final 只含 c1 → c2 被 discard
  const reused: Map<string, UIMessagePartTool> = await runner.reusableResults(
    [toolPart('c1', 'spec_eps', '{"a":1}')]);
  assert.equal(reused.size, 1);
  assert.ok(reused.has('c1'));
  const st2: SpeculativeToolState | undefined = stateOf(runner.snapshot(), 'c2');
  assert.equal(st2?.status, 'discarded');
});

test('reusableResults:final name/input 与 state 不符 → 跳过', async () => {
  const tool: AgentTool = readOnlyTool('spec_zeta', okRun);
  const runner: SpeculativeToolRunner = createSpeculativeToolRunner({
    dispatcher: new AgentToolDispatcher(),
  });
  runner.observe([toolPart('c1', 'spec_zeta', '{"a":1}')], defsOf([tool]));
  await flushMicro();
  const reused: Map<string, UIMessagePartTool> = await runner.reusableResults(
    [toolPart('c1', 'spec_zeta', '{"a":9}')]);
  assert.equal(reused.size, 0);
});

test('reusableResults:job 未完结 → cancel + discarded + 跳过(JS Promise 仅标志位)', async () => {
  let resolveGate: ((parts: UIMessagePart[]) => void) | null = null;
  let markStarted: () => void = (): void => {};
  const started = new Promise<void>((resolve): void => { markStarted = resolve; });
  const tool: AgentTool = readOnlyTool('spec_eta', (input: JsonValue): Promise<UIMessagePart[]> =>
    new Promise((resolve): void => {
      resolveGate = (): void => {
        resolve([{ type: 'text', text: 'late', metadata: null }]);
      };
      markStarted();
    }));
  const runner: SpeculativeToolRunner = createSpeculativeToolRunner({
    dispatcher: new AgentToolDispatcher(),
  });
  runner.observe([toolPart('c1', 'spec_eta', '{}')], defsOf([tool]));
  await started; // The late-completion scenario requires a primitive already in flight.
  const reused: Map<string, UIMessagePartTool> = await runner.reusableResults(
    [toolPart('c1', 'spec_eta', '{}')]);
  assert.equal(reused.size, 0);
  const st: SpeculativeToolState | undefined = stateOf(runner.snapshot(), 'c1');
  assert.equal(st?.status, 'discarded');
  // 迟到完成不得覆盖 discarded 状态
  assert.ok(resolveGate !== null);
  (resolveGate as unknown as () => void)();
  await flushMicro();
  assert.equal(stateOf(runner.snapshot(), 'c1')?.status, 'discarded');
});

test('updateStateIfCurrent:观察后状态已变(name/input) → 完成不覆盖', async () => {
  const tool: AgentTool = readOnlyTool('spec_theta', okRun);
  const runner: SpeculativeToolRunner = createSpeculativeToolRunner({
    dispatcher: new AgentToolDispatcher(),
  });
  const defs: Map<string, AgentTool> = defsOf([tool]);
  runner.observe([toolPart('c1', 'spec_theta', '{"v":1}')], defs);
  // 完成前改输入 → 旧 job 被 cancel,新 state pending
  runner.observe([toolPart('c1', 'spec_theta', '{"v":2}')], defs);
  await flushMicro();
  const st: SpeculativeToolState | undefined = stateOf(runner.snapshot(), 'c1');
  // 旧 job(v1)完成不得覆盖 v2 的 completed 结果
  assert.equal(st?.input, '{"v":2}');
  assert.equal(st?.status, 'completed');
  const r1: UIMessagePartTool | null = st?.result ?? null;
  assert.ok(r1 !== null);
  assert.equal((r1?.output[0] as { text: string }).text, 'ok:{"v":2}');
});

test('snapshot:按 toolCallId 排序', async () => {
  const tool: AgentTool = readOnlyTool('spec_iota', okRun);
  const runner: SpeculativeToolRunner = createSpeculativeToolRunner({
    dispatcher: new AgentToolDispatcher(),
  });
  const defs: Map<string, AgentTool> = defsOf([tool]);
  runner.observe([
    toolPart('c9', 'spec_iota', '{}'),
    toolPart('c1', 'spec_iota', '{}'),
    toolPart('c5', 'spec_iota', '{}'),
  ], defs);
  const ids: string[] = runner.snapshot().map(
    (s: SpeculativeToolState): string => s.toolCallId);
  assert.deepEqual(ids, ['c1', 'c5', 'c9']);
});
