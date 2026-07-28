import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createModelCouncilManager } from '../main/ets/council/manager.ts';
import type { ModelCouncilDeps } from '../main/ets/council/manager.ts';
import type { CouncilGenerateRequest, ModelCouncilTextRunner } from '../main/ets/council/runner.ts';
import { makeRuntimeSetting, makeSeat, SYNTHESIZER_SEAT_KEY } from '../main/ets/council/models.ts';
import type { ModelCouncilRun } from '../main/ets/council/models.ts';
import { makeModelConfig } from '../main/ets/domain/model_config.ts';
import type { ModelConfig } from '../main/ets/domain/model_config.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';

const pool: ModelConfig[] = [
  makeModelConfig({ id: 'mA', label: 'A', baseUrl: 'http://a', apiKey: 'k', model: 'ma' }),
  makeModelConfig({ id: 'mB', label: 'B', baseUrl: 'http://b', apiKey: 'k', model: 'mb' }),
];

const delay = (ms: number): Promise<void> => new Promise(res => setTimeout(res, ms));

interface MockRunner extends ModelCouncilTextRunner {
  calls: CouncilGenerateRequest[];
}

const createMockRunner = (fn?: (req: CouncilGenerateRequest) => string): MockRunner => {
  const calls: CouncilGenerateRequest[] = [];
  return {
    calls: calls,
    async generate(req: CouncilGenerateRequest) {
      calls.push(req);
      const text: string = fn !== undefined ? fn(req) : `answer:${req.model.id}`;
      req.onChunk(text);
      return { text: text, warnings: [] };
    },
  };
};

const mgr = (runner: ModelCouncilTextRunner, over: Partial<ModelCouncilDeps> = {}) =>
  createModelCouncilManager({
    runner: runner,
    fileStore: null,
    modelPool: pool,
    setting: makeRuntimeSetting({}),
    ...over,
  });

test('compare mode: single round, 3 core seats, completed', async () => {
  const runner = createMockRunner();
  const m = mgr(runner);
  const run: ModelCouncilRun = m.start({ mode: 'compare', objective: '要不要做?' });
  assert.equal(run.status, 'running');
  const done = await m.wait(run.runId, 2000);
  assert.ok(done !== null);
  assert.equal(done.status, 'completed');
  assert.equal(done.turns.length, 3);
  assert.ok(done.turns.every(t => t.round === 1 && t.status === 'completed'));
  assert.ok(done.result !== null && done.result.finalRecommendation.length > 0);
  // 3 席 + 1 裁判调用
  assert.equal(runner.calls.length, 4);
});

test('debate mode: 3 rounds × 3 seats = 9 turns', async () => {
  const m = mgr(createMockRunner(), { setting: makeRuntimeSetting({ defaultRounds: 3 }) });
  const run = m.start({ mode: 'debate', objective: 'q', rounds: 3 });
  const done = await m.wait(run.runId, 2000);
  assert.ok(done !== null);
  assert.equal(done.status, 'completed');
  assert.equal(done.turns.length, 9);
  assert.deepEqual([...new Set(done.turns.map(t => t.round))].sort(), [1, 2, 3]);
});

test('partial failure: one seat throws → partial_failed', async () => {
  const runner = createMockRunner((req) => {
    if (req.systemPrompt.includes('支持者')) throw new Error('seat boom');
    return 'ok';
  });
  const m = mgr(runner);
  const run = m.start({ mode: 'compare', objective: 'q' });
  const done = await m.wait(run.runId, 2000);
  assert.ok(done !== null);
  assert.equal(done.status, 'partial_failed');
  assert.ok(done.turns.some(t => t.status === 'failed'));
  assert.ok(done.turns.some(t => t.status === 'completed'));
});

test('cancel: hanging runner → cancelled', async () => {
  const calls: CouncilGenerateRequest[] = [];
  const hanging: ModelCouncilTextRunner = {
    generate(req: CouncilGenerateRequest) {
      calls.push(req);
      return new Promise((_resolve, reject) => {
        if (req.signal !== null) {
          req.signal.addEventListener!('abort', () => { reject(new Error('aborted')); });
        }
      });
    },
  };
  const m = mgr(hanging, { setting: makeRuntimeSetting({ seatTimeoutMs: 5000 }) });
  const run = m.start({ mode: 'compare', objective: 'q' });
  m.cancel(run.runId);
  const done = await m.wait(run.runId, 2000);
  assert.ok(done !== null);
  assert.equal(done.status, 'cancelled');
  assert.ok(done.turns.length > 0);
  assert.ok(done.turns.every(t => t.status === 'cancelled'));
});

test('live subscription: receives incremental cumulative text', async () => {
  const calls: CouncilGenerateRequest[] = [];
  const streaming: ModelCouncilTextRunner = {
    async generate(req: CouncilGenerateRequest) {
      calls.push(req);
      req.onChunk('Hel');
      await delay(5);
      req.onChunk('Hello');
      await delay(5);
      req.onChunk('Hello world');
      return { text: 'Hello world', warnings: [] };
    },
  };
  const m = mgr(streaming);
  const run = m.start({ mode: 'compare', objective: 'q' });
  const seatId: string = run.seats[0].seatId;
  const seen: string[] = [];
  const unsub = m.subscribeLive(run.runId, seatId, (t: string) => { seen.push(t); });
  const done = await m.wait(run.runId, 2000);
  unsub();
  assert.ok(done !== null);
  assert.ok(seen.length >= 2, `expected incremental updates, got ${seen.length}`);
  assert.ok(seen[seen.length - 1].includes('Hello world'));
});

test('synthesizer live text keyed by SYNTHESIZER_SEAT_KEY', async () => {
  const m = mgr(createMockRunner(() => 'final verdict'));
  const run = m.start({ mode: 'compare', objective: 'q' });
  const seen: string[] = [];
  const unsub = m.subscribeLive(run.runId, SYNTHESIZER_SEAT_KEY, (t: string) => { seen.push(t); });
  const done = await m.wait(run.runId, 2000);
  unsub();
  assert.ok(done !== null);
  assert.ok(seen.length > 0);
  assert.ok(seen[seen.length - 1].includes('final verdict'));
});

test('provider parallelism capped at PROVIDER_PARALLELISM (4)', async () => {
  let active = 0;
  let max = 0;
  const calls: CouncilGenerateRequest[] = [];
  const runner: ModelCouncilTextRunner = {
    async generate(req: CouncilGenerateRequest) {
      calls.push(req);
      active += 1;
      if (active > max) max = active;
      await delay(15);
      active -= 1;
      return { text: 'x', warnings: [] };
    },
  };
  const seats = [];
  for (let i = 0; i < 6; i++) {
    seats.push(makeSeat({ name: 'S' + i, role: 'r' + i, modelId: 'mA' }));
  }
  const m = mgr(runner, { setting: makeRuntimeSetting({ maxSeats: 8 }) });
  const run = m.start({ mode: 'compare', objective: 'q', seats: seats });
  await m.wait(run.runId, 3000);
  assert.ok(max <= 4, `max concurrent ${max} should be <= 4`);
  assert.ok(max >= 2, `expected real concurrency, got ${max}`);
});

test('transcript: writes started/turn/finished JSONL to fileStore', async () => {
  const fs = createMemoryFileStore();
  const m = mgr(createMockRunner(), { fileStore: fs });
  const run = m.start({ mode: 'compare', objective: 'q' });
  await m.wait(run.runId, 2000);
  const content = await fs.readText(run.transcriptPath);
  assert.ok(content !== null);
  assert.ok(content.includes('"event":"started"'));
  assert.ok(content.includes('"event":"turn"'));
  assert.ok(content.includes('"event":"finished"'));
});

test('reportMarkdown: includes title, seats, synthesis', async () => {
  const m = mgr(createMockRunner(() => '裁决内容XYZ'));
  const run = m.start({ mode: 'compare', objective: 'q' });
  await m.wait(run.runId, 2000);
  const md = m.reportMarkdown(run.runId, '测试报告');
  assert.ok(md.includes('# 测试报告'));
  assert.ok(md.includes('综合裁决'));
  assert.ok(md.includes('裁决内容XYZ'));
});

test('snapshot: returns null for unknown run', () => {
  const m = mgr(createMockRunner());
  assert.equal(m.snapshot('nope'), null);
});

// ===== 回归测试(针对 review 修复) =====

test('regression: all seats failed → run.status = failed (not partial_failed)', async () => {
  // 所有席位抛错(模拟 API key 失效 / 模型全不可用)
  const runner = createMockRunner(() => { throw new Error('all down'); });
  const m = mgr(runner);
  const run = m.start({ mode: 'compare', objective: 'q' });
  const done = await m.wait(run.runId, 2000);
  assert.ok(done !== null);
  assert.equal(done.status, 'failed', '灾难性失败应为 failed 而非 partial_failed');
  assert.ok(done.turns.every(t => t.status === 'failed'));
  assert.ok(done.result !== null && done.result.error.length > 0, 'result.error 非空');
});

test('regression: all seats timed_out → synthesize skipped, no garbage verdict', async () => {
  // 模拟超时:hang 然后被 seatTimeoutMs 短超时杀掉
  const calls: CouncilGenerateRequest[] = [];
  const hanging: ModelCouncilTextRunner = {
    generate(req: CouncilGenerateRequest) {
      calls.push(req);
      return new Promise((_resolve, reject) => {
        if (req.signal !== null) {
          req.signal.addEventListener!('abort', () => { reject(new Error('aborted')); });
        }
      });
    },
  };
  const m = mgr(hanging, { setting: makeRuntimeSetting({ seatTimeoutMs: 50 }) });
  const run = m.start({ mode: 'debate', objective: 'q', rounds: 3 });
  const done = await m.wait(run.runId, 2000);
  assert.ok(done !== null);
  // 全 timeout → failed(无任何 completed turn)
  assert.equal(done.status, 'failed');
  assert.ok(done.turns.every(t => t.status === 'timed_out'));
  assert.ok(done.result !== null && done.result.error.length > 0, '不应产出空输入的垃圾裁决');
  assert.equal(done.result!.finalRecommendation, '', '无 finalRecommendation');
});

test('regression: synthesize skipped when all turns failed (compare mode)', async () => {
  const runner = createMockRunner((req) => {
    if (req.systemPrompt.includes('裁判')) throw new Error('synth down');
    throw new Error('seat down');
  });
  const m = mgr(runner);
  const run = m.start({ mode: 'compare', objective: 'q' });
  const done = await m.wait(run.runId, 2000);
  assert.ok(done !== null);
  assert.equal(done.status, 'failed');
});

