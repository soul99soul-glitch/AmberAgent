import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { run, runSection, createRunContext } from '../main/ets/agent/run_manager.ts';
import type { RunManagerDeps } from '../main/ets/agent/run_manager.ts';
import { createScheduler, isInterruptedPhase } from '../main/ets/agent/scheduler.ts';
import type { SectionWriterTools } from '../main/ets/agent/section_writer_tools.ts';
import { makeAssistantMessage } from '../main/ets/agent/message.ts';
import type { UIMessagePartText } from '../main/ets/agent/message.ts';
import type { DeepReadOutput } from '../main/ets/domain/models.ts';
import { makeEmptyDeepReadOutput } from '../main/ets/domain/models.ts';
import { WRITER_TOOL_NAMES } from '../main/ets/domain/enums.ts';
import type { DeepReadSource } from '../main/ets/research/source_prefetcher.ts';

const source: DeepReadSource = {
  sourceId: 'real-source', url: 'https://source.example.com/article', title: '研究资料', source: 'seed',
  evidenceText: '量子计算研究提供了可供分析和交叉检查的真实材料。'.repeat(30),
  credibility: 'medium', freshness: 'unknown', publishedAt: null, imageCandidates: [],
};

const completeOutput = (): DeepReadOutput => ({
  ...makeEmptyDeepReadOutput(), summary: '原文章概览内容', generationComplete: true, generationPhase: 'COMPLETE',
  sectionStates: {
    OVERVIEW: { status: 'READY', errorMessage: null }, NARRATIVE: { status: 'READY', errorMessage: null },
    ANALYSIS: { status: 'READY', errorMessage: null }, EXTENDED_READING: { status: 'READY', errorMessage: null },
  },
});

const makeRepo = (initial: DeepReadOutput | null = null) => {
  let saved = initial;
  return {
    get: (): DeepReadOutput | null => saved,
    save: (_id: string, _title: string, out: DeepReadOutput): void => { saved = out; },
    clear: (): void => { saved = null; },
  };
};

const makeDeps = (repository = makeRepo()): RunManagerDeps => ({
  prefetcher: { collect: async () => [source], cacheSize: () => 0 },
  aiClient: { generateText: async () => [] },
  collectRun: async messages => [...messages, makeAssistantMessage('没有结构化写入')],
  model: 'model-A', playbookMarkdown: 'rules-A', nowIso: () => '2026-09-30', repository,
});

const makeWritingDeps = (repository: RunManagerDeps['repository']): RunManagerDeps => {
  let writer: SectionWriterTools | null = null;
  const deps = makeDeps();
  deps.repository = repository;
  deps.onWriterCreated = value => { writer = value; };
  deps.collectRun = async messages => {
    const currentWriter = writer;
    assert.ok(currentWriter);
    const prompt = [...messages].reverse().find(message => message.role === 'user')?.parts
      .find((part): part is UIMessagePartText => part.type === 'text')?.text ?? '';
    const payloads = [
      { name: WRITER_TOOL_NAMES.OVERVIEW, args: { summary: '新文章的概览内容能够清楚解释研究结果及其具体背景，满足分段长度要求。' } },
      { name: WRITER_TOOL_NAMES.NARRATIVE, args: { timeline: [{ date: '2026-09', event: '量子计算出现可验证的最新进展，研究者完成关键步骤并公布结果' }] } },
      { name: WRITER_TOOL_NAMES.ANALYSIS, args: { core_dispute: '核心争议围绕实验结果是否足够可靠以及其实际应用价值展开讨论' } },
      { name: WRITER_TOOL_NAMES.EXTENDED_READING, args: { links: [{ title: '研究资料', url: source.url }] } },
    ];
    const payload = payloads.find(item => prompt.includes(item.name));
    assert.ok(payload);
    const tool = currentWriter.tools(new Set(['OVERVIEW', 'NARRATIVE', 'ANALYSIS', 'EXTENDED_READING']))
      .find(item => item.name === payload.name);
    assert.ok(tool);
    await tool.execute(JSON.stringify(payload.args));
    return [...messages, makeAssistantMessage('written')];
  };
  return deps;
};

test('P1: unavailable or empty evidence never enters article planning', async () => {
  for (const evidence of [[], [{ ...source, evidenceText: '  ' }]]) {
    const deps = makeDeps();
    let planCalls = 0;
    deps.prefetcher = { collect: async () => evidence, cacheSize: () => 0 };
    deps.aiClient = { generateText: async () => { planCalls++; return []; } };
    const result = await createRunContext(deps, 't1', '研究话题', null, false);
    assert.equal(result.ok, false);
    assert.equal(planCalls, 0);
  }
});

test('P1: force prefetch failure preserves and returns the old article', async () => {
  const old = completeOutput();
  const repository = makeRepo(old);
  const deps = makeDeps(repository);
  deps.prefetcher = { collect: async () => { throw new Error('search failed'); }, cacheSize: () => 0 };
  const result = await run(deps, 't1', '话题', { force: true });
  assert.equal(result.ok, false);
  assert.ok(result.error);
  assert.equal(repository.get(), old);
  assert.equal(result.output, old);
});

test('P1: force cancellation while writing leaves the old article intact', async () => {
  const old = completeOutput();
  const repository = makeRepo(old);
  const deps = makeDeps(repository);
  const controller = new AbortController();
  deps.collectRun = async messages => { controller.abort(); return messages; };
  const result = await run(deps, 't1', '话题', { force: true, signal: controller.signal });
  assert.equal(result.error, 'aborted');
  assert.equal(repository.get(), old);
  assert.equal(result.output, old);
});

test('P1: incomplete replacement leaves the old article and returns a visible failure', async () => {
  const old = completeOutput();
  const repository = makeRepo(old);
  const deps = makeDeps(repository);
  deps.collectRun = async () => { throw new Error('provider unavailable'); };
  const result = await run(deps, 't1', '话题', { force: true });
  assert.equal(result.ok, false);
  assert.ok(result.error);
  assert.equal(repository.get(), old);
  assert.equal(result.output, old);
});

test('P1: section retry without evidence does not persist a false RUNNING state', async () => {
  const partial: DeepReadOutput = {
    ...makeEmptyDeepReadOutput(), summary: '已有概览', sectionStates: { OVERVIEW: { status: 'READY', errorMessage: null } },
  };
  const repository = makeRepo(partial);
  const deps = makeDeps(repository);
  deps.prefetcher = { collect: async () => [], cacheSize: () => 0 };
  const result = await runSection(deps, 't1', '话题', 'NARRATIVE');
  assert.equal(result.ok, false);
  assert.equal(repository.get(), partial);
  assert.equal(result.output, partial);
});

test('P1: replacement waits for its final save and never publishes intermediate draft over old article', async () => {
  const old = completeOutput();
  const backing = makeRepo(old);
  let releaseSave: () => void = () => {};
  let saveStarted: () => void = () => {};
  const finalSave = new Promise<void>(resolve => { releaseSave = resolve; });
  const started = new Promise<void>(resolve => { saveStarted = resolve; });
  const saved: DeepReadOutput[] = [];
  const repository = {
    get: backing.get, clear: backing.clear,
    save: async (id: string, title: string, output: DeepReadOutput): Promise<void> => {
      saved.push(output); saveStarted(); await finalSave; backing.save(id, title, output);
    },
  };
  const pending = run(makeWritingDeps(repository), 't1', '话题', { force: true });
  let settled = false;
  pending.then(() => { settled = true; });
  await started;
  assert.equal(backing.get(), old);
  assert.equal(settled, false);
  assert.equal(saved.length, 1);
  assert.equal(saved[0].generationComplete, true);
  releaseSave();
  const result = await pending;
  assert.equal(result.ok, true);
  assert.equal(backing.get(), result.output);
  assert.notEqual(result.output.summary, old.summary);
});

test('P1: rejected save propagates, preserves old article and is not retried as success', async () => {
  const old = completeOutput();
  const backing = makeRepo(old);
  const writeError = new Error('disk full');
  let saveCalls = 0;
  const repository = {
    get: backing.get, clear: backing.clear,
    save: async (): Promise<void> => { saveCalls++; throw writeError; },
  };
  await assert.rejects(run(makeWritingDeps(repository), 't1', '话题', { force: true }), writeError);
  assert.equal(backing.get(), old);
  assert.equal(saveCalls, 1);
});

test('P1: cancellation during a successful final commit reports the actually saved completed article', async () => {
  const old = completeOutput();
  const backing = makeRepo(old);
  const controller = new AbortController();
  const repository = {
    get: backing.get, clear: backing.clear,
    save: async (id: string, title: string, output: DeepReadOutput): Promise<void> => {
      controller.abort();
      backing.save(id, title, output);
    },
  };
  const result = await run(makeWritingDeps(repository), 't1', '话题', { force: true, signal: controller.signal });
  assert.equal(result.ok, true);
  assert.equal(result.error, null);
  assert.equal(backing.get(), result.output);
  assert.equal(result.output.generationComplete, true);
});

test('P1: deferred commit cancellation settles success and publishes the saved completion', async () => {
  const old = completeOutput();
  const backing = makeRepo(old);
  let started: () => void = () => {};
  let commit: () => void = () => {};
  const entered = new Promise<void>(resolve => { started = resolve; });
  const gate = new Promise<void>(resolve => { commit = resolve; });
  const repository = {
    get: backing.get, clear: backing.clear,
    save: async (id: string, title: string, output: DeepReadOutput): Promise<void> => {
      started(); await gate; backing.save(id, title, output);
    },
  };
  const notices: boolean[] = [];
  const states: boolean[] = [];
  const scheduler = createScheduler({
    runManager: makeWritingDeps(repository), createAbortController: () => new AbortController(), isInterruptedPhase,
    observeOutput: () => ({ subscribe: () => () => {}, getCurrent: () => backing.get() ?? undefined }),
    notifier: {
      notifyRunning: async () => {},
      notifyCompleted: async (_id, _title, complete) => { notices.push(complete); },
      cancelRunning: async () => {},
    },
  });
  const observer = scheduler.observeRunning('t1');
  const unsubscribe = observer.subscribe(value => { states.push(value); });
  const pending = scheduler.run('t1', '话题', { force: true });
  await entered;
  assert.equal(backing.get(), old);
  assert.equal(observer.getCurrent(), true, 'retained COMPLETE cache does not hide the active job');
  assert.deepEqual(notices, []);
  scheduler.abort('t1');
  commit();
  const result = await pending;
  assert.equal(result.ok, true);
  assert.equal(backing.get(), result.output);
  assert.deepEqual(notices, [true]);
  assert.deepEqual(states, [false, true, false]);
  assert.equal(observer.getCurrent(), false);
  unsubscribe();
});

test('P1: cancellation during rejected storage does not hide its real save error', async () => {
  const old = completeOutput();
  const backing = makeRepo(old);
  const failure = new Error('disk full');
  let scheduler: ReturnType<typeof createScheduler>;
  const repository = {
    get: backing.get, clear: backing.clear,
    save: async (): Promise<void> => { scheduler.abort('t1'); throw failure; },
  };
  let completed = 0;
  scheduler = createScheduler({
    runManager: makeWritingDeps(repository), createAbortController: () => new AbortController(), isInterruptedPhase,
    observeOutput: () => ({ subscribe: () => () => {}, getCurrent: () => old }),
    notifier: {
      notifyRunning: async () => {}, notifyCompleted: async () => { completed++; }, cancelRunning: async () => {},
    },
  });
  await assert.rejects(scheduler.run('t1', '话题', { force: true }), failure);
  assert.equal(backing.get(), old);
  assert.equal(completed, 0);
  assert.equal(scheduler.isRunning('t1'), false);
});

test('P1: late successful commit from replaced owner cannot publish completion for the newer run', { timeout: 15_000 }, async () => {
  const old = completeOutput();
  const backing = makeRepo(old);
  let started: () => void = () => {};
  let commit: () => void = () => {};
  let replacementRegistered: () => void = () => {};
  const entered = new Promise<void>(resolve => { started = resolve; });
  const gate = new Promise<void>(resolve => { commit = resolve; });
  const replacement = new Promise<void>(resolve => { replacementRegistered = resolve; });
  const repository = {
    get: backing.get, clear: backing.clear,
    save: async (id: string, title: string, output: DeepReadOutput): Promise<void> => {
      started(); await gate; backing.save(id, title, output);
    },
  };
  const deps = makeWritingDeps(repository);
  let collections = 0;
  deps.prefetcher = { collect: async () => ++collections === 1 ? [source] : [], cacheSize: () => 0 };
  const events: string[] = [];
  const scheduler = createScheduler({
    runManager: deps, createAbortController: () => new AbortController(), isInterruptedPhase,
    observeOutput: () => ({ subscribe: () => () => {}, getCurrent: () => old }),
    notifier: {
      notifyRunning: async (_id, _title, token) => {
        events.push(`running:${token}`);
        if (token === 2) replacementRegistered();
      },
      notifyCompleted: async (_id, _title, _complete, token) => { events.push(`completed:${token}`); },
      cancelRunning: async (_id, token) => { events.push(`cancel:${token}`); },
    },
  });
  const first = scheduler.run('t1', '话题', { force: true });
  await entered;
  const second = scheduler.run('t1', '话题', { force: true });
  await replacement;
  commit();
  assert.equal((await first).ok, true);
  assert.equal((await second).ok, false);
  assert.deepEqual(events, ['running:1', 'running:2', 'cancel:2']);
});

test('P1: every phase waits for storage before its AI stage starts', async () => {
  const backing = makeRepo();
  const order: string[] = [];
  const repository = {
    get: backing.get, clear: backing.clear,
    save: async (id: string, title: string, output: DeepReadOutput): Promise<void> => {
      await new Promise<void>(resolve => { setTimeout(resolve, 5); });
      order.push(`saved:${output.generationPhase}`); backing.save(id, title, output);
    },
  };
  const deps = makeWritingDeps(repository);
  const write = deps.collectRun;
  deps.collectRun = async (messages, label, signal) => {
    order.push('ai');
    assert.equal(order[order.length - 2], 'saved:WRITING');
    return write(messages, label, signal);
  };
  const result = await run(deps, 't1', '话题');
  assert.equal(result.ok, true);
  assert.equal(order[0], 'saved:COLLECTING');
  assert.equal(order[order.length - 1], 'saved:COMPLETE');
});

test('P1: failed research cancels ongoing notification and never publishes partial completion', async () => {
  const deps = makeDeps();
  deps.prefetcher = { collect: async () => [], cacheSize: () => 0 };
  let completed = 0;
  let cancelled = 0;
  const scheduler = createScheduler({
    runManager: deps, createAbortController: () => new AbortController(), isInterruptedPhase,
    observeOutput: () => ({ subscribe: () => () => {}, getCurrent: () => makeEmptyDeepReadOutput() }),
    notifier: {
      notifyRunning: async () => {},
      notifyCompleted: async () => { completed++; },
      cancelRunning: async () => { cancelled++; },
    },
  });
  const result = await scheduler.run('t1', '话题', { force: true });
  assert.equal(result.ok, false);
  assert.equal(completed, 0);
  assert.equal(cancelled, 1);
});

test('P1: genuinely partial research publishes partial completion', async () => {
  const deps = makeDeps();
  const completed: boolean[] = [];
  const scheduler = createScheduler({
    runManager: deps, createAbortController: () => new AbortController(), isInterruptedPhase,
    observeOutput: () => ({ subscribe: () => () => {}, getCurrent: () => makeEmptyDeepReadOutput() }),
    notifier: {
      notifyRunning: async () => {},
      notifyCompleted: async (_id, _title, complete) => { completed.push(complete); },
      cancelRunning: async () => {},
    },
  });
  const result = await scheduler.run('t1', '话题');
  assert.equal(result.ok, true);
  assert.equal(result.output.generationComplete, false);
  assert.deepEqual(completed, [false]);
});

test('P1: scheduler creates one isolated configuration snapshot per admitted run', async () => {
  let setting = 'A';
  let snapshots = 0;
  const models: string[] = [];
  const playbooks: string[] = [];
  const scheduler = createScheduler({
    runManager: makeDeps(), createAbortController: () => new AbortController(), isInterruptedPhase,
    observeOutput: () => ({ subscribe: () => () => {}, getCurrent: () => makeEmptyDeepReadOutput() }),
    createRunManager: async () => {
      snapshots++;
      const deps = makeWritingDeps(makeRepo());
      deps.model = `model-${setting}`;
      deps.playbookMarkdown = `rules-${setting}`;
      deps.aiClient = { generateText: async request => { models.push(request.model); setting = 'B'; return []; } };
      const write = deps.collectRun;
      deps.collectRun = async (messages, label, signal) => {
        playbooks.push(deps.playbookMarkdown);
        return write(messages, label, signal);
      };
      return deps;
    },
  });
  assert.equal((await scheduler.run('t1', '话题', { force: true })).ok, true);
  assert.equal((await scheduler.run('t1', '话题', { force: true })).ok, true);
  assert.equal(snapshots, 2);
  assert.deepEqual(models, ['model-A', 'model-B']);
  assert.deepEqual(playbooks.slice(0, 4), Array(4).fill('rules-A'));
  assert.deepEqual(playbooks.slice(4), Array(4).fill('rules-B'));
});
