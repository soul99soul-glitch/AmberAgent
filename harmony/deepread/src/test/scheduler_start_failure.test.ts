import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createScheduler, decideReentryAction, isInterruptedPhase } from '../main/ets/agent/scheduler.ts';
import { makeEmptyDeepReadOutput } from '../main/ets/domain/models.ts';
import type { DeepReadOutput } from '../main/ets/domain/models.ts';
import type { RunManagerDeps } from '../main/ets/agent/run_manager.ts';
import { firstFailureMessage } from '../main/ets/domain/helpers.ts';
import { deepReadLibraryStatus } from '../main/ets/domain/library.ts';

const makeHarness = (initial: DeepReadOutput | null, resolveManager?: () => Promise<RunManagerDeps>) => {
  let stored = initial;
  let saves = 0;
  const deps: RunManagerDeps = {
    prefetcher: { collect: async () => [], cacheSize: () => 0 },
    collectRun: async () => [], aiClient: { generateText: async () => [] },
    model: '', playbookMarkdown: '', nowIso: () => '2026-10-03',
    repository: {
      get: () => stored,
      save: (_topicId, _title, output) => { saves++; stored = output; },
      clear: () => { stored = null; },
    },
  };
  const scheduler = createScheduler({
    runManager: deps,
    createRunManager: resolveManager ?? (async () => { throw new Error('请先配置深读模型'); }),
    observeOutput: () => ({ getCurrent: () => stored ?? undefined, subscribe: () => () => {} }),
    isInterruptedPhase, createAbortController: () => new AbortController(),
  });
  return { scheduler, deps, current: () => stored, saveCount: () => saves };
};

test('scheduler: first model configuration failure persists source input and explicit retry state', async () => {
  const initial: DeepReadOutput = {
    ...makeEmptyDeepReadOutput(), inputText: '完整粘贴资料正文', inputUrlsText: 'https://example.org',
    inputSourceUrls: ['https://example.org'],
    inputSources: [{ id: 'paste-1', kind: 'text', title: '粘贴文本', content: '完整粘贴资料正文',
      status: 'ready', error: null, url: null, truncated: false, note: null }],
  };
  const harness = makeHarness(initial);
  await assert.rejects(harness.scheduler.run('new-article', '资料文章'), /请先配置深读模型/);
  const saved = harness.current();
  assert.ok(saved);
  assert.equal(firstFailureMessage(saved), '请先配置深读模型');
  assert.equal(deepReadLibraryStatus({ topicId: 'new-article', title: '资料文章', sourceUrl: null,
    output: saved, phase: saved.generationPhase, attemptCount: 0, lastError: null,
    createdAt: 0, updatedAt: 0, expiresAt: 0 }), 'failed');
  assert.equal(decideReentryAction(false, saved), 'subscribe', 'cold reentry requires explicit retry');
  assert.equal(saved.inputText, initial.inputText);
  assert.equal(saved.inputUrlsText, initial.inputUrlsText);
  assert.deepEqual(saved.inputSources, initial.inputSources);
  assert.deepEqual(saved.inputSourceUrls, initial.inputSourceUrls);
  assert.equal(harness.saveCount(), 1);
  assert.equal(harness.scheduler.isRunning('new-article'), false);
});

test('scheduler activity start and cleanup retain the admitted article title', async () => {
  const harness = makeHarness(null);
  const activities: Array<{ topicId: string; token: number; active: boolean; title: string }> = [];
  const scheduler = createScheduler({
    runManager: harness.deps,
    createRunManager: async () => { throw new Error('未配置模型'); },
    observeOutput: () => ({ getCurrent: () => undefined, subscribe: () => () => {} }),
    isInterruptedPhase, createAbortController: () => new AbortController(),
    onRunActivity: (topicId, token, active, title) => { activities.push({ topicId, token, active, title }); },
  });
  await assert.rejects(scheduler.run('activity-topic', '实际文章标题'), /未配置模型/);
  assert.equal(activities.length, 2);
  assert.deepEqual(activities.map(activity => [activity.topicId, activity.active, activity.title]), [
    ['activity-topic', true, '实际文章标题'], ['activity-topic', false, '实际文章标题'],
  ]);
  assert.equal(activities[0].token, activities[1].token);
});

test('scheduler: model configuration failure preserves any existing article without replacing it', async () => {
  for (const initial of [
    { ...makeEmptyDeepReadOutput(), summary: '已有部分文章' },
    { ...makeEmptyDeepReadOutput(), sectionStates: { ANALYSIS: { status: 'READY' as const, errorMessage: null } } },
    { ...makeEmptyDeepReadOutput(), generationPhase: 'COMPLETE' as const, generationComplete: true,
      summary: '已完成文章', sectionStates: {
        OVERVIEW: { status: 'READY' as const, errorMessage: null },
        NARRATIVE: { status: 'READY' as const, errorMessage: null },
        ANALYSIS: { status: 'READY' as const, errorMessage: null },
        EXTENDED_READING: { status: 'READY' as const, errorMessage: null },
      } },
  ]) {
    const harness = makeHarness(initial);
    await assert.rejects(harness.scheduler.run('existing', '旧文', { force: true }), /请先配置深读模型/);
    assert.equal(harness.current(), initial, 'same old object remains authoritative');
    assert.equal(harness.saveCount(), 0);
  }
});

test('scheduler: explicit configuration AbortError is not saved as a generation failure', async () => {
  const initial = makeEmptyDeepReadOutput();
  const error = new Error('读取取消');
  error.name = 'AbortError';
  const harness = makeHarness(initial, async () => { throw error; });
  await assert.rejects(harness.scheduler.run('abort-error', '配置读取取消'), error);
  assert.equal(harness.current(), initial);
  assert.equal(harness.saveCount(), 0);
});

test('scheduler: configuration failure without a composer entry still creates visible failure history', async () => {
  const harness = makeHarness(null);
  await assert.rejects(harness.scheduler.run('no-entry', '首次直接打开'), /请先配置深读模型/);
  assert.equal(firstFailureMessage(harness.current()!), '请先配置深读模型');
  assert.equal(decideReentryAction(false, harness.current()), 'subscribe');
  assert.equal(harness.saveCount(), 1);
});

test('scheduler: cancelled configuration resolution cannot persist a failure', async () => {
  let rejectManager!: (error: Error) => void;
  let entered!: () => void;
  const started = new Promise<void>(resolve => { entered = resolve; });
  const manager = new Promise<RunManagerDeps>((_resolve, reject) => { rejectManager = reject; });
  const initial = makeEmptyDeepReadOutput();
  const harness = makeHarness(initial, async () => { entered(); return manager; });
  const pending = harness.scheduler.run('cancelled', '取消配置解析');
  await started;
  harness.scheduler.abort('cancelled');
  rejectManager(new Error('配置读取已失败'));
  const result = await pending;
  assert.equal(result.error, 'aborted');
  assert.equal(harness.current(), initial);
  assert.equal(harness.saveCount(), 0);
});

test('scheduler: late failed configuration owner cannot overwrite replacement state', { timeout: 15_000 }, async () => {
  let calls = 0;
  let rejectA!: (error: Error) => void;
  let rejectB!: (error: Error) => void;
  let enteredA!: () => void;
  let enteredB!: () => void;
  const startedA = new Promise<void>(resolve => { enteredA = resolve; });
  const startedB = new Promise<void>(resolve => { enteredB = resolve; });
  const configA = new Promise<RunManagerDeps>((_resolve, reject) => { rejectA = reject; });
  const configB = new Promise<RunManagerDeps>((_resolve, reject) => { rejectB = reject; });
  const initial = { ...makeEmptyDeepReadOutput(), inputText: '后轮保留的资料' };
  const harness = makeHarness(initial, () => {
    calls++;
    if (calls === 1) { enteredA(); return configA; }
    enteredB(); return configB;
  });
  const runA = harness.scheduler.run('replace', 'A');
  await startedA;
  const runB = harness.scheduler.run('replace', 'B');
  // B registers after the existing bounded abort wait, while A still owns the topic lock.
  await new Promise(resolve => setTimeout(resolve, 5_200));
  rejectA(new Error('迟到 A 的配置错误'));
  assert.equal((await runA).error, 'aborted');
  await startedB;
  assert.equal(harness.current(), initial);
  assert.equal(harness.saveCount(), 0);
  assert.equal(harness.scheduler.isRunning('replace'), true);
  rejectB(new Error('B 的配置错误'));
  await assert.rejects(runB, /B 的配置错误/);
  assert.equal(firstFailureMessage(harness.current()!), 'B 的配置错误');
  assert.equal(harness.current()?.inputText, initial.inputText);
  assert.equal(harness.saveCount(), 1);
});

test('scheduler: rejected failure persistence surfaces storage error', async () => {
  const initial = makeEmptyDeepReadOutput();
  const harness = makeHarness(initial);
  harness.deps.repository!.save = async () => { throw new Error('RDB unavailable'); };
  await assert.rejects(harness.scheduler.run('storage', '保存失败'), /RDB unavailable/);
  assert.equal(harness.current(), initial);
  assert.equal(harness.scheduler.isRunning('storage'), false);
});
