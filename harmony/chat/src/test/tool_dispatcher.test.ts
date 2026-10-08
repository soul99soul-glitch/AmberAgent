// tool_dispatcher.test.ts — 工具执行调度器 + hooks + 失败归一(D-056 TDD)
//
// Android 基准: AgentToolDispatcher.kt(372)/ToolInvocationHooks.kt(118)/ToolFailure.kt(33)
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  AgentToolDispatcher,
  defaultToolInvocationHooks,
  sanitizedToolFailureMessage,
  createToolArgumentValidationHook,
} from '../main/ets/chat/tool_dispatcher.ts';
import type {
  ToolInvocationHook,
} from '../main/ets/chat/tool_dispatcher.ts';
import { PermissionDecisionResolver } from '../main/ets/chat/tool_permission.ts';
import { makeGenerationRetrySetting } from '../main/ets/chat/generation_retry.ts';
import { makeAgentTool, makeInputSchemaObj } from '../main/ets/chat/tool.ts';
import type { AgentTool, InputSchemaObj } from '../main/ets/chat/tool.ts';
import type { UIMessagePart, UIMessagePartText, UIMessagePartTool, ToolApprovalState } from '../main/ets/chat/message.ts';
import type { JsonValue } from '../main/ets/chat/json.ts';

let seq = 0;
const makeDispatcher = (hooks: ToolInvocationHook[] = []) => new AgentToolDispatcher({
  resolver: new PermissionDecisionResolver({ newId: () => `t-${++seq}` }),
  hooks,
  sleep: () => Promise.resolve(),
});

const toolCall = (
  name: string, input: string = '{}', state: ToolApprovalState = { type: 'auto' },
  callId: string = `call-${name}`,
): UIMessagePartTool => ({
  type: 'tool', toolCallId: callId, toolName: name, input,
  output: [], approvalState: state, metadata: null,
});

const execDef = (
  name: string, run: (input: JsonValue) => Promise<UIMessagePart[]>,
): AgentTool => makeAgentTool({ name, description: 'd', execute: run });

const okOutput = (text: string): UIMessagePart[] => [{ type: 'text', text, metadata: null }];

const outputText = (t: UIMessagePartTool): string =>
  (t.output[0] as { text: string }).text;

describe('ToolFailure(ToolFailure.kt)', () => {
  it('sanitizedToolFailureMessage:首个非空行/去栈帧/压空白/360 截断/空回退', () => {
    assert.equal(sanitizedToolFailureMessage(new Error('  \n boom at a.b.c(X.kt:1)  \nsecond')), 'boom');
    assert.equal(sanitizedToolFailureMessage(new Error('')), 'Error');
    const long = new Error('x'.repeat(400));
    assert.equal(sanitizedToolFailureMessage(long).length, 360);
  });
});

describe('execute 分支(:137-184)', () => {
  it('Denied → status denied JSON + permission_trace 注入 metadata', async () => {
    const d = makeDispatcher();
    const out = await d.execute(
      toolCall('file_write', '{}', { type: 'denied', reason: '  ' }), execDef('file_write', () => Promise.resolve(okOutput('ran'))));
    assert.ok(out !== null);
    const payload = JSON.parse(outputText(out as UIMessagePartTool));
    assert.equal(payload.status, 'denied');
    assert.equal(payload.message, 'Tool execution denied by user. Reason: No reason provided');
    assert.ok(payload.permission_trace !== undefined);
    const meta = (out as UIMessagePartTool).metadata;
    assert.ok(meta !== null && meta['permission_trace'] !== undefined);
  });
  it('Answered → output = 答案文本', async () => {
    const d = makeDispatcher();
    const out = await d.execute(
      toolCall('ask_user', '{}', { type: 'answered', answer: '用户答复' }), null);
    assert.equal(outputText(out as UIMessagePartTool), '用户答复');
  });
  it('Pending → null(不执行)', async () => {
    const d = makeDispatcher();
    const out = await d.execute(
      toolCall('file_read', '{}', { type: 'pending' }), execDef('file_read', () => Promise.resolve(okOutput('x'))));
    assert.equal(out, null);
  });
  it('resolver DENY(toolDef null)→ status failed recoverable=false + reason', async () => {
    const d = makeDispatcher();
    const out = await d.execute(toolCall('ghost'), null);
    const payload = JSON.parse(outputText(out as UIMessagePartTool));
    assert.equal(payload.status, 'failed');
    assert.equal(payload.recoverable, false);
    assert.ok(String(payload.message).includes('Tool not found or not exposed'));
  });
  it('正常执行:display_title 被剥离后传入 execute', async () => {
    let seen: JsonValue | null = null;
    const d = makeDispatcher();
    const def = execDef('file_read', (input: JsonValue): Promise<UIMessagePart[]> => {
      seen = input;
      return Promise.resolve(okOutput('content'));
    });
    const out = await d.execute(toolCall('file_read', '{"path":"/a","display_title":"读文件"}'), def);
    assert.equal(outputText(out as UIMessagePartTool), 'content');
    assert.deepEqual(seen, { path: '/a' });
  });
  it('execute 抛错 → failure payload(sanitized + permission_trace)', async () => {
    const d = makeDispatcher();
    const def = execDef('file_read', (): Promise<UIMessagePart[]> => {
      throw new Error('disk gone at a.b(C.kt:1)');
    });
    const out = await d.execute(toolCall('file_read'), def);
    const payload = JSON.parse(outputText(out as UIMessagePartTool));
    assert.equal(payload.status, 'failed');
    assert.equal(payload.message, 'disk gone');
    assert.equal(payload.recoverable, true);
    assert.ok(payload.permission_trace !== undefined);
  });
  it('AbortError 原样上抛(不归一为失败输出)', async () => {
    const d = makeDispatcher();
    const abort = new Error('aborted');
    abort.name = 'AbortError';
    const def = execDef('file_read', (): Promise<UIMessagePart[]> => {
      throw abort;
    });
    await assert.rejects(() => d.execute(toolCall('file_read'), def), (e: Error) => e.name === 'AbortError');
  });
});

describe('hooks(ToolInvocationHooks.kt)', () => {
  it('validation:非对象参数 → failed 短路(不触达 execute)', async () => {
    let ran = false;
    const d = makeDispatcher([createToolArgumentValidationHook()]);
    const def = execDef('file_read', (): Promise<UIMessagePart[]> => {
      ran = true;
      return Promise.resolve(okOutput('x'));
    });
    const out = await d.execute(toolCall('file_read', '[1,2]'), def);
    assert.equal(ran, false);
    const payload = JSON.parse(outputText(out as UIMessagePartTool));
    assert.equal(payload.status, 'failed');
    assert.equal(payload.message, 'Tool arguments must be a JSON object');
    assert.equal(payload.recoverable, false);
  });
  it('before 短路结果带 hook metadata 合并', async () => {
    const hook: ToolInvocationHook = {
      before: () => Promise.resolve({
        output: okOutput('short'),
        metadata: { hook_tag: 'yes' },
      }),
    };
    const d = makeDispatcher([hook]);
    const out = await d.execute(toolCall('file_read'), execDef('file_read', () => Promise.resolve(okOutput('x'))));
    assert.equal(outputText(out as UIMessagePartTool), 'short');
    assert.equal((out as UIMessagePartTool).metadata?.['hook_tag'], 'yes');
  });
  it('after 可改写输出;onError 归一(ToolFailureNormalizeHook)', async () => {
    const d = makeDispatcher(defaultToolInvocationHooks());
    const def = execDef('file_read', (): Promise<UIMessagePart[]> => {
      throw new Error('kaboom');
    });
    const out = await d.execute(toolCall('file_read'), def);
    const payload = JSON.parse(outputText(out as UIMessagePartTool));
    assert.equal(payload.status, 'failed');
    assert.equal(payload.message, 'kaboom');
  });
  it('hook 自身抛错被吞(执行继续)', async () => {
    const bad: ToolInvocationHook = {
      before: () => Promise.reject(new Error('hook broke')),
    };
    const d = makeDispatcher([bad]);
    const out = await d.execute(toolCall('file_read'), execDef('file_read', () => Promise.resolve(okOutput('fine'))));
    assert.equal(outputText(out as UIMessagePartTool), 'fine');
  });
});

describe('重试(:289-342)', () => {
  it('只读安全工具网络错误可重试(成功后返回)', async () => {
    let attempts = 0;
    const d = makeDispatcher();
    const def = execDef('file_read', (): Promise<UIMessagePart[]> => {
      attempts++;
      if (attempts < 3) throw new Error('network timeout');
      return Promise.resolve(okOutput('recovered'));
    });
    const out = await d.execute(
      toolCall('file_read'), def, false, false, [], 'normal',
      makeGenerationRetrySetting({ enabled: true, maxRetries: 5 }));
    assert.equal(outputText(out as UIMessagePartTool), 'recovered');
    assert.equal(attempts, 3);
  });
  it('mutating 工具不重试(canRetrySafely=false)', async () => {
    let attempts = 0;
    const d = makeDispatcher();
    const def = execDef('file_write', (): Promise<UIMessagePart[]> => {
      attempts++;
      throw new Error('network timeout');
    });
    const out = await d.execute(
      toolCall('file_write', '{}', { type: 'approved' }), def, false, false, [], 'normal',
      makeGenerationRetrySetting({ enabled: true, maxRetries: 5 }));
    assert.equal(attempts, 1);
    const payload = JSON.parse(outputText(out as UIMessagePartTool));
    assert.equal(payload.status, 'failed');
  });
  it('retrySetting.enabled=false → 不重试', async () => {
    let attempts = 0;
    const d = makeDispatcher();
    const def = execDef('file_read', (): Promise<UIMessagePart[]> => {
      attempts++;
      throw new Error('network timeout');
    });
    await d.execute(toolCall('file_read'), def);
    assert.equal(attempts, 1);
  });
});

describe('executeBatch(:63-117)', () => {
  it('prefetch 复用:name+input 匹配且 output 非空 → 直接复用不重跑', async () => {
    let ran = 0;
    const d = makeDispatcher();
    const defs = new Map<string, AgentTool>([
      ['file_read', execDef('file_read', (): Promise<UIMessagePart[]> => {
        ran++;
        return Promise.resolve(okOutput('fresh'));
      })],
    ]);
    const t = toolCall('file_read', '{"p":1}', { type: 'auto' }, 'c1');
    const prefetched = new Map<string, UIMessagePartTool>([
      ['c1', { ...t, output: okOutput('cached') }],
    ]);
    const out = await d.executeBatch([t], defs, false, false, [], 'normal', prefetched);
    assert.equal(ran, 0);
    assert.equal(outputText(out[0]), 'cached');
  });
  it('input 漂移的 prefetch 不复用', async () => {
    let ran = 0;
    const d = makeDispatcher();
    const defs = new Map<string, AgentTool>([
      ['file_read', execDef('file_read', (): Promise<UIMessagePart[]> => {
        ran++;
        return Promise.resolve(okOutput('fresh'));
      })],
    ]);
    const t = toolCall('file_read', '{"p":2}', { type: 'auto' }, 'c1');
    const prefetched = new Map<string, UIMessagePartTool>([
      ['c1', { ...toolCall('file_read', '{"p":1}', { type: 'auto' }, 'c1'), output: okOutput('cached') }],
    ]);
    const out = await d.executeBatch([t], defs, false, false, [], 'normal', prefetched);
    assert.equal(ran, 1);
    assert.equal(outputText(out[0]), 'fresh');
  });
  it('结果按原 toolCallId 序重排(与执行完成序无关)', async () => {
    const d = makeDispatcher();
    const defs = new Map<string, AgentTool>([
      ['file_read', execDef('file_read', (input: JsonValue): Promise<UIMessagePart[]> => {
        const p = (input as { p: string }).p;
        // p=slow 的延迟更高,若按完成序会乱 — Promise.all 保序,此处验证重排
        return new Promise((resolve): void => {
          setTimeout(() => resolve(okOutput(`out-${p}`)), p === 'slow' ? 20 : 1);
        });
      })],
    ]);
    const t1 = toolCall('file_read', '{"p":"slow"}', { type: 'auto' }, 'c1');
    const t2 = toolCall('file_read', '{"p":"fast"}', { type: 'auto' }, 'c2');
    const out = await d.executeBatch([t1, t2], defs, false);
    assert.equal(out.length, 2);
    assert.equal(out[0].toolCallId, 'c1');
    assert.equal(outputText(out[0]), 'out-slow');
    assert.equal(out[1].toolCallId, 'c2');
  });
  it('含非并行资格工具 → 顺序执行路径(结果仍全量)', async () => {
    const order: string[] = [];
    const d = makeDispatcher();
    const defs = new Map<string, AgentTool>([
      ['file_read', execDef('file_read', (): Promise<UIMessagePart[]> => {
        order.push('read');
        return Promise.resolve(okOutput('r'));
      })],
      ['file_write', execDef('file_write', (): Promise<UIMessagePart[]> => {
        order.push('write');
        return Promise.resolve(okOutput('w'));
      })],
    ]);
    const out = await d.executeBatch([
      toolCall('file_read', '{}', { type: 'auto' }, 'c1'),
      toolCall('file_write', '{}', { type: 'approved' }, 'c2'),
    ], defs, true); // Explicitly approved write still follows sequential batch ordering.
    assert.equal(out.length, 2);
    assert.deepEqual(order, ['read', 'write']);
  });
});

// ===== Phase 4 回归:空 toolCallId 并行工具的结果按输入序配对 =====

it('executeBatch: parallel blank-id tools return distinct results in input order', async () => {
  let calls = 0;
  const tool = makeAgentTool({
    name: 'search_web',
    description: 'search',
    parameters: (): InputSchemaObj => makeInputSchemaObj({}),
    execute: async (): Promise<UIMessagePart[]> => {
      calls++;
      return [{ type: 'text', text: `result-${calls}`, metadata: null }];
    },
  });
  const blank = (input: string): UIMessagePartTool => ({
    type: 'tool', toolCallId: '', toolName: 'search_web',
    input, output: [], approvalState: { type: 'auto' }, metadata: null,
  });
  const dispatcher = new AgentToolDispatcher();
  const results = await dispatcher.executeBatch(
    [blank('{"n":1}'), blank('{"n":2}')], new Map([[tool.name, tool]]),
    true, false, [], 'normal', new Map(), makeGenerationRetrySetting({ enabled: false }));
  assert.equal(results.length, 2);
  assert.equal((results[0].output[0] as UIMessagePartText).text, 'result-1');
  assert.equal((results[1].output[0] as UIMessagePartText).text, 'result-2');
});

it('executeBatch: abort signal skips not-yet-started tools (sequential)', async () => {
  let calls = 0;
  const tool = makeAgentTool({
    name: 'file_read',
    description: 'read',
    parameters: (): InputSchemaObj => makeInputSchemaObj({}),
    execute: async (): Promise<UIMessagePart[]> => {
      calls++;
      return [{ type: 'text', text: 'ok', metadata: null }];
    },
  });
  const controller = new AbortController();
  const mk = (id: string): UIMessagePartTool => ({
    type: 'tool', toolCallId: id, toolName: 'file_read',
    input: '{}', output: [], approvalState: { type: 'auto' }, metadata: null,
  });
  const dispatcher = new AgentToolDispatcher();
  // 先执行第一个成功,然后 abort — 第二个不得产生副作用
  const first = await dispatcher.execute(
    mk('t1'), tool, true, false, [], 'normal', makeGenerationRetrySetting({ enabled: false }));
  assert.ok(first !== null && first.output.length > 0);
  controller.abort();
  assert.equal(calls, 1);
  const results = await dispatcher.executeBatch(
    [mk('t2'), mk('t3')], new Map([[tool.name, tool]]),
    true, false, [], 'normal', new Map(),
    makeGenerationRetrySetting({ enabled: false }), controller.signal);
  assert.equal(results.length, 0, 'aborted batch must not start tool side effects');
  assert.equal(calls, 1);
});

it('executeWithRetry: abort during retry wait stops retrying', async () => {
  const controller = new AbortController();
  let attempts = 0;
  const tool = makeAgentTool({
    name: 'search_web',
    description: 'search',
    parameters: (): InputSchemaObj => makeInputSchemaObj({}),
    execute: async (): Promise<UIMessagePart[]> => {
      attempts++;
      throw new Error('transient network error');
    },
  });
  const dispatcher = new AgentToolDispatcher({
    sleep: (ms: number): Promise<void> => new Promise((resolve): void => {
      setTimeout(resolve, ms);
    }),
  });
  const mk = (): UIMessagePartTool => ({
    type: 'tool', toolCallId: 't1', toolName: 'search_web',
    input: '{}', output: [], approvalState: { type: 'auto' }, metadata: null,
  });
  const retry = makeGenerationRetrySetting({ enabled: true, initialDelayMs: 5_000, maxRetries: 10 });
  const pending = dispatcher.executeBatch(
    [mk()], new Map([[tool.name, tool]]), true, false, [], 'normal', new Map(), retry, controller.signal);
  await new Promise((r): void => { setTimeout(r, 30); });
  controller.abort();
  const results = await pending;
  assert.equal(attempts, 1, 'abort during retry wait must prevent further attempts');
  assert.equal(results.length, 0, 'aborted tool stays unexecuted (no result written back)');
});
