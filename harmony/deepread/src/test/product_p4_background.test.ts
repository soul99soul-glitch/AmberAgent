import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createScheduler, isInterruptedPhase } from '../main/ets/agent/scheduler.ts';
import type { SchedulerDeps } from '../main/ets/agent/scheduler.ts';
import type { RunManagerDeps } from '../main/ets/agent/run_manager.ts';
import { makeEmptyDeepReadOutput } from '../main/ets/domain/models.ts';
import { makeAssistantMessage } from '../main/ets/agent/message.ts';
import type { SectionWriterTools } from '../main/ets/agent/section_writer_tools.ts';
import { WRITER_TOOL_NAMES } from '../main/ets/domain/enums.ts';

const manager = (): RunManagerDeps => ({
  aiClient: { generateText: async () => [] },
  collectRun: async messages => messages,
  prefetcher: { collect: async () => [], cacheSize: () => 0 },
  model: 'model', playbookMarkdown: '', nowIso: () => '2026-09-30',
  repository: { get: () => null, save: () => {}, clear: () => {} },
});

const fixture = () => {
  const activity: Array<{ token: number; active: boolean }> = [];
  const events: string[] = [];
  const deps: SchedulerDeps = {
    runManager: manager(),
    observeOutput: () => ({ subscribe: () => () => {}, getCurrent: () => makeEmptyDeepReadOutput() }),
    createAbortController: () => new AbortController(), isInterruptedPhase,
    onRunActivity: (_topic, token, active) => { activity.push({ token, active }); events.push(`activity:${active}`); },
    notifier: {
      notifyRunning: async () => { events.push('running'); },
      notifyCompleted: async () => { events.push('completed'); },
      notifyFailed: async () => { events.push('failed'); },
      cancelRunning: async () => { events.push('cancel'); },
    },
  };
  return { deps, activity, events };
};

test('P4: zero-byte activity starts before configuration and ends after factory failure', async () => {
  const { deps, activity, events } = fixture();
  const failure = new Error('fixed model unavailable');
  deps.createRunManager = async (_topic, _title, token) => {
    events.push('config');
    assert.equal(activity[0]?.active, true);
    assert.equal(activity[0]?.token, token);
    throw failure;
  };
  const scheduler = createScheduler(deps);
  await assert.rejects(scheduler.run('topic', 'title'), failure);
  assert.deepEqual(events, ['activity:true', 'running', 'config', 'failed', 'activity:false']);
  assert.deepEqual(activity, [{ token: 1, active: true }, { token: 1, active: false }]);
  assert.equal(scheduler.isRunning('topic'), false);
});

test('P4: real no-source failure keeps its failed notification instead of cancelling it', async () => {
  const { deps, events } = fixture();
  const result = await createScheduler(deps).run('topic', 'title');
  assert.equal(result.ok, false);
  assert.match(result.error ?? '', /资料/);
  assert.deepEqual(events, ['activity:true', 'running', 'failed', 'activity:false']);
});

test('P4: section failures use the same running, failed and exact activity lifecycle', async () => {
  const { deps, activity, events } = fixture();
  const result = await createScheduler(deps).runSection('topic', 'title', 'ANALYSIS');
  assert.equal(result.ok, false);
  assert.deepEqual(events, ['activity:true', 'running', 'failed', 'activity:false']);
  assert.deepEqual(activity, [{ token: 1, active: true }, { token: 1, active: false }]);
});

test('P4: a successfully saved section publishes partial completion and retains that terminal notification', async () => {
  const { deps, events } = fixture();
  let writer: SectionWriterTools | null = null;
  deps.runManager.prefetcher = {
    collect: async () => [{
      sourceId: 'source', url: 'https://example.com/article', title: '研究资料', source: 'seed',
      evidenceText: '真实研究资料为深度阅读提供可查证的具体背景与事实。'.repeat(20),
      credibility: 'medium', freshness: 'unknown', publishedAt: null, imageCandidates: [],
    }], cacheSize: () => 0,
  };
  deps.runManager.onWriterCreated = value => { writer = value; };
  deps.runManager.collectRun = async messages => {
    assert.ok(writer);
    const overview = writer.tools(new Set(['OVERVIEW'])).find(tool => tool.name === WRITER_TOOL_NAMES.OVERVIEW);
    assert.ok(overview);
    await overview.execute(JSON.stringify({ summary: '此研究概览包含足够完整的事件背景、具体事实和影响范围，能够用于文章阅读。' }));
    return [...messages, makeAssistantMessage('完成概览')];
  };
  let complete: boolean | null = null;
  deps.notifier!.notifyCompleted = async (_topic, _title, value) => { complete = value; events.push('completed'); };
  const result = await createScheduler(deps).runSection('topic', 'title', 'OVERVIEW');
  assert.equal(result.ok, true);
  assert.equal(complete, false);
  assert.deepEqual(events, ['activity:true', 'running', 'completed', 'activity:false']);
});

test('P4: cancellation during configuration ends activity and cancels without a failure notification', async () => {
  const { deps, events } = fixture();
  let entered: () => void = () => {};
  let release: () => void = () => {};
  const started = new Promise<void>(resolve => { entered = resolve; });
  const gate = new Promise<void>(resolve => { release = resolve; });
  deps.createRunManager = async () => { entered(); await gate; return manager(); };
  const scheduler = createScheduler(deps);
  const pending = scheduler.runSection('topic', 'title', 'ANALYSIS');
  await started;
  scheduler.abort('topic');
  release();
  assert.equal((await pending).error, 'aborted');
  assert.deepEqual(events, ['activity:true', 'running', 'activity:false', 'cancel']);
});

test('P4: terminal publish rejection cleans the ongoing notification without changing the article failure', async () => {
  const { deps, events } = fixture();
  deps.notifier!.notifyFailed = async () => { events.push('failed'); throw new Error('notifications unavailable'); };
  const result = await createScheduler(deps).run('topic', 'title');
  assert.equal(result.ok, false);
  assert.match(result.error ?? '', /资料/);
  assert.deepEqual(events, ['activity:true', 'running', 'failed', 'activity:false', 'cancel']);
});

test('P4: failed notification cleanup does not replace the original article error', async () => {
  const { deps } = fixture();
  deps.notifier!.notifyFailed = async () => { throw new Error('terminal unavailable'); };
  deps.notifier!.cancelRunning = async () => { throw new Error('cancel unavailable'); };
  const result = await createScheduler(deps).run('topic', 'title');
  assert.equal(result.ok, false);
  assert.match(result.error ?? '', /资料/);
});

test('P4: rejected database save is a failed notification even when cancellation arrives together', async () => {
  const { deps, events } = fixture();
  const scheduler = createScheduler(deps);
  const failure = new Error('database write rejected');
  deps.runManager.repository!.save = async () => { scheduler.abort('topic'); throw failure; };
  await assert.rejects(scheduler.run('topic', 'title'), failure);
  assert.deepEqual(events, ['activity:true', 'running', 'failed', 'activity:false']);
});

test('P4: late finally ends only its exact activity after replacement, while the new registration remains', { timeout: 15_000 }, async () => {
  const { deps, activity } = fixture();
  const running: number[] = [];
  let entered: () => void = () => {};
  let release: () => void = () => {};
  const started = new Promise<void>(resolve => { entered = resolve; });
  const gate = new Promise<void>(resolve => { release = resolve; });
  let releaseSecond: () => void = () => {};
  const secondGate = new Promise<void>(resolve => { releaseSecond = resolve; });
  deps.createRunManager = async (_topic, _title, token) => {
    if (token === 1) { entered(); await gate; }
    if (token === 2) await secondGate;
    return manager();
  };
  deps.notifier!.notifyRunning = async (_topic, _title, token) => { running.push(token!); };
  const scheduler = createScheduler(deps);
  const first = scheduler.run('topic', 'first');
  await started;
  const second = scheduler.run('topic', 'second');
  second.catch(() => {});
  const deadline = Date.now() + 7_000;
  while (!running.includes(2) && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 10));
  assert.ok(running.includes(2));
  release();
  await first;
  assert.deepEqual(activity.slice(0, 3), [
    { token: 1, active: true }, { token: 2, active: true }, { token: 1, active: false },
  ]);
  assert.equal(scheduler.isRunning('topic'), true);
  scheduler.abort('topic');
  releaseSecond();
  assert.equal((await second).error, 'aborted');
  assert.deepEqual(activity[3], { token: 2, active: false });
});
