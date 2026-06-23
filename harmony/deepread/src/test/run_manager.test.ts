// Phase 5 集成测试:run() 全流程 generateStages → prefetch → plan → supervisor → finish
// 用 mock prefetcher + mock collectRun(通过 onWriterCreated 拿到 writer 并写入)验证完整闭环。

import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { run, runSection, generateStages, createRunContext } from '../main/ets/agent/run_manager.ts';
import type { RunManagerDeps } from '../main/ets/agent/run_manager.ts';
import { createScheduler, isInterruptedPhase } from '../main/ets/agent/scheduler.ts';
import { createSectionWriterTools } from '../main/ets/agent/section_writer_tools.ts';
import type { SectionWriterTools } from '../main/ets/agent/section_writer_tools.ts';
import type { CollectRunFn } from '../main/ets/agent/supervisor_loop.ts';
import type { UIMessage } from '../main/ets/agent/message.ts';
import { makeAssistantMessage } from '../main/ets/agent/message.ts';
import type { DeepReadSource, SourcePrefetcher } from '../main/ets/research/source_prefetcher.ts';
import type { AiClient } from '../main/ets/platform/ai_client.ts';
import type { DeepReadOutput } from '../main/ets/domain/models.ts';
import { makeEmptyDeepReadOutput } from '../main/ets/domain/models.ts';
import { isComplete, sectionsReady, statusOf } from '../main/ets/domain/helpers.ts';
import { WRITER_TOOL_NAMES } from '../main/ets/domain/enums.ts';
import type { Observable } from '../main/ets/platform/observable.ts';

// ===== fixtures =====

const makeSource = (id: string): DeepReadSource => ({
  sourceId: id, url: `https://src.example.com/${id}`, title: `来源标题 ${id} 足够长`,
  source: 'tavily', evidenceText: '证据正文内容'.repeat(50), credibility: 'medium',
  freshness: 'unknown', publishedAt: null, imageCandidates: [],
});

const sources: DeepReadSource[] = ['s1', 's2', 's3', 's4'].map(makeSource);

const mockPrefetcher: SourcePrefetcher = {
  collect: async () => sources,
  cacheSize: () => 0,
};

const mockAi: AiClient = { generateText: async () => [] };  // plan 走 fallback

// collectRun:通过 onWriterCreated 捕获的 writer,按 prompt 里的 stage 调对应 writer tool
const makeWritingCollectRun = (writerRef: { w: SectionWriterTools | null }): CollectRunFn =>
  async (messages: UIMessage[]): Promise<UIMessage[]> => {
    const w = writerRef.w;
    if (w === null) return [...messages, makeAssistantMessage([{ kind: 'text', text: 'no writer' }])];
    const lastUser = [...messages].reverse().find(m => m.role === 'user');
    const promptPart = lastUser?.parts.find((p): p is { kind: 'text'; text: string } => p.kind === 'text');
    const prompt = promptPart?.text ?? '';

    if (prompt.includes(WRITER_TOOL_NAMES.OVERVIEW)) {
      const t = w.tools(new Set(['OVERVIEW'])).find(x => x.name === WRITER_TOOL_NAMES.OVERVIEW)!;
      await t.execute(JSON.stringify({ summary: '这是量子计算重大突破的概览内容,充分满足最小字符阈值的长度要求。' }));
    } else if (prompt.includes(WRITER_TOOL_NAMES.NARRATIVE)) {
      const t = w.tools(new Set(['NARRATIVE'])).find(x => x.name === WRITER_TOOL_NAMES.NARRATIVE)!;
      await t.execute(JSON.stringify({ timeline: [{ date: '2026-01', event: '量子计算实现重大突破的关键事件描述内容详' }] }));
    } else if (prompt.includes(WRITER_TOOL_NAMES.ANALYSIS)) {
      const t = w.tools(new Set(['ANALYSIS'])).find(x => x.name === WRITER_TOOL_NAMES.ANALYSIS)!;
      await t.execute(JSON.stringify({ core_dispute: '核心争议是关于量子优越性的可持续性的描述内容' }));
    } else if (prompt.includes(WRITER_TOOL_NAMES.EXTENDED_READING)) {
      const t = w.tools(new Set(['EXTENDED_READING'])).find(x => x.name === WRITER_TOOL_NAMES.EXTENDED_READING)!;
      await t.execute(JSON.stringify({ links: [{ title: '扩展阅读', url: 'https://ext.example.com/1' }] }));
    }
    return [...messages, makeAssistantMessage([{ kind: 'text', text: 'written' }])];
  };

// ===== isInterruptedPhase =====

test('isInterruptedPhase: COLLECTING/PLANNING/WRITING → true; IDLE/COMPLETE → false', () => {
  assert.equal(isInterruptedPhase({ ...makeEmptyDeepReadOutput(), generationPhase: 'COLLECTING' }), true);
  assert.equal(isInterruptedPhase({ ...makeEmptyDeepReadOutput(), generationPhase: 'PLANNING' }), true);
  assert.equal(isInterruptedPhase({ ...makeEmptyDeepReadOutput(), generationPhase: 'WRITING' }), true);
  assert.equal(isInterruptedPhase({ ...makeEmptyDeepReadOutput(), generationPhase: 'IDLE' }), false);
  assert.equal(isInterruptedPhase({ ...makeEmptyDeepReadOutput(), generationPhase: 'COMPLETE' }), false);
});

// ===== createRunContext: 空 prefetch → hard fail (P1-9) =====

test('createRunContext: empty prefetch → ok=false with error message', async () => {
  const emptyPrefetcher: SourcePrefetcher = {
    collect: async () => [],
    cacheSize: () => 0,
  };
  const deps: RunManagerDeps = {
    prefetcher: emptyPrefetcher, collectRun: async (m) => m, aiClient: mockAi,
    model: 'm', playbookMarkdown: '', nowIso: () => '2026-06-23',
  };
  const result = await createRunContext(deps, 't1', '标题', null, false);
  assert.equal(result.ok, false);
  if (!result.ok) assert.ok(result.error.includes('没有抓到足够的来源'));
});

// ===== createRunContext: 有 sources → ok=true =====

test('createRunContext: non-empty prefetch → ok=true with writer + pack + plan', async () => {
  const writerRef: { w: SectionWriterTools | null } = { w: null };
  const deps: RunManagerDeps = {
    prefetcher: mockPrefetcher, collectRun: async (m) => m, aiClient: mockAi,
    model: 'm', playbookMarkdown: '', nowIso: () => '2026-06-23',
    onWriterCreated: (w) => { writerRef.w = w; },
  };
  const result = await createRunContext(deps, 't1', '量子计算', null, false);
  assert.equal(result.ok, true);
  if (result.ok) {
    assert.ok(result.context.writer !== undefined);
    assert.equal(result.context.evidencePack.allSources.length, sources.length);
    assert.ok(result.context.articlePlan.overviewAngle.length > 0);
    assert.ok(writerRef.w !== null, 'onWriterCreated captured writer');
  }
});

// ===== 全流程:run() 产出 complete DeepReadOutput(核心验收) =====

test('run(): full pipeline → complete DeepReadOutput (all 4 stages READY)', async () => {
  const writerRef: { w: SectionWriterTools | null } = { w: null };
  const repo = makeMemoryRepo();
  const deps: RunManagerDeps = {
    prefetcher: mockPrefetcher,
    collectRun: makeWritingCollectRun(writerRef),
    aiClient: mockAi,
    model: 'm', playbookMarkdown: '', nowIso: () => '2026-06-23',
    repository: repo,
    onWriterCreated: (w) => { writerRef.w = w; },
  };
  const result = await run(deps, 't1', '量子计算', { force: true });
  assert.equal(result.ok, true, `expected ok, error=${result.error}`);
  assert.equal(isComplete(result.output), true, 'output should be complete');
  assert.equal(sectionsReady(result.output), true);
  assert.equal(statusOf(result.output, 'OVERVIEW'), 'READY');
  assert.equal(statusOf(result.output, 'NARRATIVE'), 'READY');
  assert.equal(statusOf(result.output, 'ANALYSIS'), 'READY');
  assert.equal(statusOf(result.output, 'EXTENDED_READING'), 'READY');
  assert.equal(result.output.generationComplete, true);
  assert.equal(result.output.generationPhase, 'COMPLETE');
  assert.ok(result.output.summary.length > 0);
  assert.ok((result.output.timeline?.length ?? 0) > 0);
});

// ===== generateStages: collectRun 不写 → 不崩溃,返回 IDLE =====

test('generateStages: collectRun writes nothing → returns without throw, phase IDLE', async () => {
  const noWriteCollectRun: CollectRunFn = async (m) => [...m, makeAssistantMessage([{ kind: 'text', text: '...' }])];
  const deps: RunManagerDeps = {
    prefetcher: mockPrefetcher, collectRun: noWriteCollectRun, aiClient: mockAi,
    model: 'm', playbookMarkdown: '', nowIso: () => '2026-06-23',
  };
  const result = await generateStages(deps, 't1', '量子计算',
    ['OVERVIEW', 'NARRATIVE', 'ANALYSIS', 'EXTENDED_READING'], null, false, 'PLANNING', false);
  assert.ok(result.output !== undefined);
  assert.equal(result.output.generationPhase, 'IDLE');
});

// ===== run(): cache isComplete → 短路返回(不重跑 prefetch) =====

test('run(): cached complete output → short-circuit, no prefetch', async () => {
  let prefetchCalls = 0;
  const countingPrefetcher: SourcePrefetcher = {
    collect: async () => { prefetchCalls++; return sources; },
    cacheSize: () => 0,
  };
  const repo = makeMemoryRepo();
  const completeOutput: DeepReadOutput = {
    ...makeEmptyDeepReadOutput(),
    summary: '完整的概览内容'.repeat(10),
    generationComplete: true,
    generationPhase: 'COMPLETE',
    sectionStates: {
      OVERVIEW: { status: 'READY', errorMessage: null },
      NARRATIVE: { status: 'READY', errorMessage: null },
      ANALYSIS: { status: 'READY', errorMessage: null },
      EXTENDED_READING: { status: 'READY', errorMessage: null },
    },
  };
  repo.save('t1', '标题', completeOutput);
  const deps: RunManagerDeps = {
    prefetcher: countingPrefetcher, collectRun: async (m) => m, aiClient: mockAi,
    model: 'm', playbookMarkdown: '', nowIso: () => '2026-06-23', repository: repo,
  };
  const result = await run(deps, 't1', '标题');
  assert.equal(prefetchCalls, 0, 'short-circuit');
  assert.equal(result.ok, true);
  assert.equal(result.output.generationComplete, true);
});

// ===== run(): force=true → clear cache =====

test('run(): force=true clears cache before run', async () => {
  const repo = makeMemoryRepo();
  repo.save('t1', '标题', makeEmptyDeepReadOutput());
  let cleared = false;
  const trackingRepo = {
    get: repo.get, save: repo.save,
    clear: (id: string) => { cleared = true; repo.clear(id); },
  };
  const writerRef: { w: SectionWriterTools | null } = { w: null };
  const deps: RunManagerDeps = {
    prefetcher: mockPrefetcher,
    collectRun: makeWritingCollectRun(writerRef),
    aiClient: mockAi, model: 'm', playbookMarkdown: '', nowIso: () => '2026-06-23',
    repository: trackingRepo,
    onWriterCreated: (w) => { writerRef.w = w; },
  };
  await run(deps, 't1', '标题', { force: true });
  assert.equal(cleared, true);
});

// ===== runSection(): 单 stage 重试 =====

test('runSection(): runs single stage to READY', async () => {
  const writerRef: { w: SectionWriterTools | null } = { w: null };
  const repo = makeMemoryRepo();
  const deps: RunManagerDeps = {
    prefetcher: mockPrefetcher,
    collectRun: makeWritingCollectRun(writerRef),
    aiClient: mockAi, model: 'm', playbookMarkdown: '', nowIso: () => '2026-06-23',
    repository: repo,
    onWriterCreated: (w) => { writerRef.w = w; },
  };
  const result = await runSection(deps, 't1', '标题', 'OVERVIEW');
  assert.equal(statusOf(result.output, 'OVERVIEW'), 'READY');
});

// ===== scheduler 测试 =====

test('scheduler: isRunning false initially; abort no-op when nothing running', () => {
  const deps: RunManagerDeps = {
    prefetcher: mockPrefetcher, collectRun: async (m) => m, aiClient: mockAi,
    model: 'm', playbookMarkdown: '', nowIso: () => '2026-06-23',
  };
  const scheduler = createScheduler({
    runManager: deps,
    observeOutput: () => makeMemoryObservable(makeEmptyDeepReadOutput()),
    isInterruptedPhase, createAbortController: () => new AbortController(),
  });
  assert.equal(scheduler.isRunning('t1'), false);
  scheduler.abort('t1');
  assert.equal(scheduler.isRunning('t1'), false);
});

test('scheduler: detectInterrupted false when IDLE', async () => {
  const deps: RunManagerDeps = {
    prefetcher: mockPrefetcher, collectRun: async (m) => m, aiClient: mockAi,
    model: 'm', playbookMarkdown: '', nowIso: () => '2026-06-23',
  };
  const scheduler = createScheduler({
    runManager: deps, observeOutput: () => makeMemoryObservable(makeEmptyDeepReadOutput()), isInterruptedPhase, createAbortController: () => new AbortController(),
  });
  assert.equal(await scheduler.detectInterrupted('t1'), false);
});

test('scheduler: detectInterrupted true when WRITING + not running', async () => {
  const deps: RunManagerDeps = {
    prefetcher: mockPrefetcher, collectRun: async (m) => m, aiClient: mockAi,
    model: 'm', playbookMarkdown: '', nowIso: () => '2026-06-23',
  };
  const writingOutput = { ...makeEmptyDeepReadOutput(), generationPhase: 'WRITING' as const };
  const scheduler = createScheduler({
    runManager: deps, observeOutput: () => makeMemoryObservable(writingOutput), isInterruptedPhase, createAbortController: () => new AbortController(),
  });
  assert.equal(await scheduler.detectInterrupted('t1'), true);
});

test('scheduler: setBackgrounded toggle (no throw)', () => {
  const deps: RunManagerDeps = {
    prefetcher: mockPrefetcher, collectRun: async (m) => m, aiClient: mockAi,
    model: 'm', playbookMarkdown: '', nowIso: () => '2026-06-23',
  };
  const scheduler = createScheduler({
    runManager: deps, observeOutput: () => makeMemoryObservable(makeEmptyDeepReadOutput()), isInterruptedPhase, createAbortController: () => new AbortController(),
  });
  scheduler.setBackgrounded('t1', true);
  scheduler.setBackgrounded('t1', false);
});

test('scheduler: run() drives full pipeline via runManager', async () => {
  const writerRef: { w: SectionWriterTools | null } = { w: null };
  const repo = makeMemoryRepo();
  const deps: RunManagerDeps = {
    prefetcher: mockPrefetcher,
    collectRun: makeWritingCollectRun(writerRef),
    aiClient: mockAi, model: 'm', playbookMarkdown: '', nowIso: () => '2026-06-23',
    repository: repo,
    onWriterCreated: (w) => { writerRef.w = w; },
  };
  const scheduler = createScheduler({
    runManager: deps, observeOutput: () => makeMemoryObservable(makeEmptyDeepReadOutput()), isInterruptedPhase, createAbortController: () => new AbortController(),
  });
  const result = await scheduler.run('t1', '量子计算', { force: true });
  assert.equal(result.ok, true);
  assert.equal(isComplete(result.output), true);
});

// ===== helpers =====

const makeMemoryRepo = () => {
  const store = new Map<string, DeepReadOutput>();
  return {
    get: (topicId: string): DeepReadOutput | null => store.get(topicId) ?? null,
    save: (topicId: string, _title: string, output: DeepReadOutput): void => { store.set(topicId, output); },
    clear: (topicId: string): void => { store.delete(topicId); },
  };
};

const makeMemoryObservable = <T>(initial: T): Observable<T> => {
  let current: T = initial;
  return {
    subscribe: (): (() => void) => () => {},
    getCurrent: (): T | undefined => current,
  };
};
