import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createScheduler, isInterruptedPhase } from '../main/ets/agent/scheduler.ts';
import type { DeepReadActiveRun, SchedulerRunOptions } from '../main/ets/agent/scheduler.ts';
import type { RunManagerDeps } from '../main/ets/agent/run_manager.ts';
import { makeEmptyDeepReadOutput } from '../main/ets/domain/models.ts';
import type { DeepReadOutput } from '../main/ets/domain/models.ts';
import { makeAssistantMessage } from '../main/ets/agent/message.ts';
import { isComplete } from '../main/ets/domain/helpers.ts';
const deferred = () => { let resolve!: () => void; const promise = new Promise<void>(done => { resolve = done; }); return { promise, resolve }; };
const fixture = () => {
  const old: DeepReadOutput = { ...makeEmptyDeepReadOutput(), summary: '原来的完整文章保持可阅读。', generationComplete: true, generationPhase: 'COMPLETE',
    sectionStates: Object.fromEntries(['OVERVIEW', 'NARRATIVE', 'ANALYSIS', 'EXTENDED_READING'].map(stage => [stage, { status: 'READY', errorMessage: null }])) };
  let saved = old;
  const deps: RunManagerDeps = { writerMode: 'structured', model: 'plain', playbookMarkdown: '', nowIso: () => '2026-10-03',
    prefetcher: { collect: async () => [{ sourceId: 'one', title: '来源', url: 'https://source.example/report', source: '用户', evidenceText: '产品发布后进入用户反馈阶段。'.repeat(30), credibility: 'medium', freshness: 'unknown', publishedAt: null, imageCandidates: [] }], cacheSize: () => 0 },
    aiClient: { generateText: async () => [] },
    collectRun: async messages => {
      const prompt = messages[0].parts.map(part => part.type === 'text' ? part.text : '').join('');
      const reply = prompt.includes('目标段落：概览') ? { summary: '产品发布后开始收集用户反馈；现有资料展示了发布与反馈的过程，需要更多验证实际效果。' }
        : prompt.includes('目标段落：时间轴叙事') ? { timeline: [{ date: '今天', event: '产品发布之后开始收集真实用户反馈，并继续核对实际使用过程。' }] }
        : prompt.includes('目标段落：深度分析') ? { analysis: { core_dispute: '核心分歧在于产品功能能否解决真实使用场景的长期问题，需要继续核对来源。' } }
        : { extended_reading: [{ title: '真实来源', url: 'https://source.example/report' }] };
      return [...messages, makeAssistantMessage(JSON.stringify(reply))];
    }, repository: { get: () => saved, save: (_id, _title, output) => { saved = structuredClone(output); }, clear: () => {} },
  };
  const scheduler = createScheduler({ runManager: deps, observeOutput: () => ({ getCurrent: () => saved, subscribe: () => () => {} }),
    isInterruptedPhase, createAbortController: () => new AbortController() });
  return { deps, scheduler, old, saved: () => saved };
};

test('force generation publishes actual collection, planning, section and final-save progress while the old article stays complete', { timeout: 3000 }, async () => {
  const f = fixture(); const snapshots: DeepReadActiveRun[][] = [];
  f.scheduler.observeActiveRuns().subscribe(runs => snapshots.push(runs));
  const collectionStarted = deferred(); const releaseCollection = deferred(); const planningStarted = deferred(); const releasePlanning = deferred();
  const collected = f.deps.prefetcher.collect;
  f.deps.prefetcher.collect = async (...args) => { collectionStarted.resolve(); await releaseCollection.promise; return collected(...args); };
  f.deps.aiClient.generateText = async () => { planningStarted.resolve(); await releasePlanning.promise; return []; };
  const saveStarted = deferred(); const releaseSave = deferred(); const originalSave = f.deps.repository!.save;
  f.deps.repository!.save = async (...args) => { if (isComplete(args[2])) { saveStarted.resolve(); await releaseSave.promise; } await originalSave(...args); };
  const task = f.scheduler.run('topic', '重生成', { force: true });
  await collectionStarted.promise;
  assert.equal(snapshots.at(-1)?.[0].stage, 'COLLECTING'); assert.deepEqual(f.saved(), f.old);
  releaseCollection.resolve(); await planningStarted.promise;
  assert.equal(snapshots.at(-1)?.[0].stage, 'PLANNING'); assert.deepEqual(f.saved(), f.old);
  releasePlanning.resolve(); await saveStarted.promise;
  assert.equal(snapshots.at(-1)?.[0].stage, 'VERIFYING'); assert.deepEqual(f.saved(), f.old);
  const writing = snapshots.flat().filter(run => run.stage === 'WRITING').map(run => run.label);
  assert.deepEqual(writing, ['概览', '时间轴叙事', '深度分析']);
  assert.equal(snapshots.flat().some(run => run.stage === 'COMPLETE'), false);
  releaseSave.resolve(); const result = await task;
  assert.equal(isComplete(result.output), true); assert.equal(snapshots.flat().some(run => run.stage === 'COMPLETE'), true);
  assert.deepEqual(snapshots.at(-1), []);
});

test('factory and old callbacks cannot publish progress after cancel or into a newer owner', { timeout: 3000 }, async () => {
  const f = fixture(); const entered = deferred(); const release = deferred(); const snapshots: DeepReadActiveRun[][] = [];
  let factoryOptions: SchedulerRunOptions | undefined;
  const scheduler = createScheduler({ runManager: f.deps, createRunManager: async (_id, _title, _token, options) => {
    factoryOptions = options; entered.resolve(); await release.promise; return f.deps;
  }, observeOutput: () => ({ getCurrent: f.saved, subscribe: () => () => {} }), isInterruptedPhase, createAbortController: () => new AbortController() });
  scheduler.observeActiveRuns().subscribe(runs => snapshots.push(runs));
  const first = scheduler.run('topic', '第一轮', { force: true }); await entered.promise;
  factoryOptions!.onProgress!('PLANNING', '旧规划');
  assert.equal(snapshots.at(-1)?.[0].label, '旧规划');
  scheduler.abort('topic'); const count = snapshots.length;
  factoryOptions!.onProgress!('WRITING', '取消后的旧回复'); assert.equal(snapshots.length, count);
  release.resolve(); assert.equal((await first).error, 'aborted');
  const oldCallback = factoryOptions!.onProgress!;
  const nextStarted = deferred(); const releaseNext = deferred(); const collect = f.deps.prefetcher.collect;
  f.deps.prefetcher.collect = async (...args) => { nextStarted.resolve(); await releaseNext.promise; return collect(...args); };
  const next = scheduler.run('topic', '第二轮', { force: true }); await nextStarted.promise;
  const activeCount = snapshots.length; oldCallback('WRITING', '污染新任务的旧回复');
  assert.equal(snapshots.length, activeCount); assert.equal(snapshots.at(-1)?.[0].title, '第二轮');
  assert.equal(snapshots.at(-1)?.[0].stage, 'COLLECTING');
  releaseNext.resolve(); await next; const endedCount = snapshots.length;
  oldCallback('WRITING', '迟到旧回复'); assert.equal(snapshots.length, endedCount); assert.deepEqual(snapshots.at(-1), []);
});
