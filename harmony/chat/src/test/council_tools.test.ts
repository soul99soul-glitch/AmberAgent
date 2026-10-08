import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createModelCouncilManager, makeModelConfig, makeRuntimeSetting, createMemoryFileStore } from '@amber/deepread-domain';
import { createCouncilTools } from '../main/ets/chat/council_tools.ts';
import { councilRunFromTools } from '../main/ets/chat/council_display.ts';
import type { UIMessagePartTool } from '../main/ets/chat/message.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';

test('stopping the parent council wait preserves the independent council run', async (context) => {
  context.mock.timers.enable({ apis: ['setTimeout'] });
  let release: () => void = (): void => {};
  const pending = new Promise<void>(resolve => { release = resolve; });
  const manager = createModelCouncilManager({ fileStore: createMemoryFileStore(),
    modelPool: [makeModelConfig({ id: 'm', model: 'fixture' })], setting: makeRuntimeSetting({ enabled: true }),
    runner: { generate: async () => { await pending; return { text: 'done', warnings: [] }; } },
  });
  const child = manager.start({ mode: 'compare', objective: 'wait cancellation' });
  const tools = createCouncilTools({ manager: async () => manager, read: async id => manager.read(id) });
  const controller = new AbortController();
  const run = tools.find(tool => tool.name === 'model_council_wait')!.execute(
    { run_id: child.runId, wait_timeout_ms: 40 }, controller.signal);
  await new Promise<void>(resolve => setImmediate(resolve));
  controller.abort();
  try {
    await assert.rejects(run, { name: 'AbortError' });
    assert.equal(manager.snapshot(child.runId)?.status, 'running');
  } finally {
    release();
    await manager.wait(child.runId, 2000);
  }
});

test('council waits release parent listeners after timeout and completion, and reject an already stopped parent', async () => {
  let release: () => void = (): void => {};
  const pending = new Promise<void>(resolve => { release = resolve; });
  const listeners = new Set<() => void>();
  const signal: AbortSignalLike = { aborted: false,
    addEventListener: (_type, listener) => { listeners.add(listener); },
    removeEventListener: (_type, listener) => { listeners.delete(listener); } };
  const manager = createModelCouncilManager({ fileStore: createMemoryFileStore(),
    modelPool: [makeModelConfig({ id: 'm', model: 'fixture' })], setting: makeRuntimeSetting({ enabled: true }),
    runner: { generate: async () => { await pending; return { text: 'done', warnings: [] }; } },
  });
  const child = manager.start({ mode: 'compare', objective: 'listener lifecycle' });
  try {
    assert.equal((await manager.wait(child.runId, 0, signal))?.status, 'running');
    assert.equal(listeners.size, 0);
    release();
    assert.equal((await manager.wait(child.runId, 2000, signal))?.status, 'completed');
    assert.equal(listeners.size, 0);
    await assert.rejects(manager.wait('missing', 180000, { aborted: true }), { name: 'AbortError' });
  } finally { release(); }
});

test('Council工具 start/wait/read 连到同一manager，聊天历史输出可呈现每席与最终结果', async () => {
  const manager = createModelCouncilManager({ fileStore: createMemoryFileStore(),
    modelPool: [makeModelConfig({ id: 'm', model: 'fixture' })], setting: makeRuntimeSetting({ enabled: true }),
    runner: { generate: async req => { req.onChunk('live'); return { text: 'verdict', warnings: [] }; } },
  });
  const tools = createCouncilTools({ manager: async () => manager, read: async id => manager.read(id) });
  const start = await tools.find(tool => tool.name === 'model_council_start')!.execute({ objective: 'fixture',
    planned_seats: ['a', 'b'].map(name => ({ name, role: name, system_prompt: 'compare', model_ref: 'm' })) });
  const started = JSON.parse(start[0].type === 'text' ? start[0].text : '{}');
  const waited = await tools.find(tool => tool.name === 'model_council_wait')!.execute({ run_id: started.run_id, wait_timeout_ms: 1000 });
  const part: UIMessagePartTool = { type: 'tool', toolCallId: 'fixture', toolName: 'model_council_wait',
    input: JSON.stringify({ run_id: started.run_id }), output: waited, approvalState: { type: 'auto' }, metadata: null };
  const display = councilRunFromTools(started.run_id, [part]);
  assert.equal(display?.seats.length, 2); assert.equal(display?.turns.length, 2);
  assert.equal(display?.result?.finalRecommendation, 'verdict'); assert.equal(display?.status, 'completed');
});
