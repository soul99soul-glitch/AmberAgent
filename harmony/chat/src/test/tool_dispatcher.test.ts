// tool_dispatcher.test.ts — 工具执行调度器 + hooks + 失败归一(D-056 TDD)
//
// Android 基准: AgentToolDispatcher.kt(372)/ToolInvocationHooks.kt(118)/ToolFailure.kt(33)
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  AgentToolDispatcher, defaultToolInvocationHooks,
  sanitizedToolFailureMessage, isRecoverableToolFailure, toAgentToolFailurePayload,
  withPermissionTrace, withHookMetadata, createToolArgumentValidationHook,
} from '../main/ets/chat/tool_dispatcher.ts';
import type { ToolInvocationHook, ToolInvocationResult } from '../main/ets/chat/tool_dispatcher.ts';
import { PermissionDecisionResolver } from '../main/ets/chat/tool_permission.ts';
import { makeGenerationRetrySetting } from '../main/ets/chat/generation_retry.ts';
import { makeAgentTool } from '../main/ets/chat/tool.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import type { UIMessagePart, UIMessagePartTool, ToolApprovalState } from '../main/ets/chat/message.ts';
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
  it('isRecoverableToolFailure:JS 恒 true(VirtualMachineError/ThreadDeath 无对应物)', () => {
    assert.equal(isRecoverableToolFailure(new Error('x')), true);
  });
  it('toAgentToolFailurePayload 键集', () => {
    const p = toAgentToolFailurePayload(new Error('boom'));
    assert.equal(p['status'], 'failed');
    assert.equal(p['message'], 'boom');
    assert.equal(p['recoverable'], true);
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
      toolCall('file_write'), def, false, false, [], 'normal',
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
      toolCall('file_write', '{}', { type: 'auto' }, 'c2'),
    ], defs, true); // autoApprove 让 write 过审批(file_write fail-closed → ASK… 用 ALLOW? 见下)
    // file_write:autoApproveTools=true 但 fail-closed → resolver ASK?不 — execute 分支只在 DENY 时短路;
    // ASK 在 execute 中不阻断(暂停决策在生成循环层),故照常执行
    assert.equal(out.length, 2);
    assert.deepEqual(order, ['read', 'write']);
  });
});

describe('metadata 合并(:344-355)', () => {
  it('withPermissionTrace 合并既有 metadata', () => {
    const t = { ...toolCall('x'), metadata: { keep: 'v' } };
    const out = withPermissionTrace(t, { a: 'b' });
    assert.equal(out.metadata?.['keep'], 'v');
    assert.deepEqual(out.metadata?.['permission_trace'], { a: 'b' });
  });
  it('withHookMetadata 空对象原样返回', () => {
    const t = toolCall('x');
    assert.equal(withHookMetadata(t, {}), t);
    const merged = withHookMetadata(t, { k: 'v' });
    assert.equal(merged.metadata?.['k'], 'v');
  });
});
