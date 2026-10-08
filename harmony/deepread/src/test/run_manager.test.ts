// Phase 5 集成测试:run() 全流程 generateStages → prefetch → plan → supervisor → finish
// 用 mock prefetcher + mock collectRun(通过 onWriterCreated 拿到 writer 并写入)验证完整闭环。

import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { run, runSection, generateStages, createRunContext } from '../main/ets/agent/run_manager.ts';
import type { RunManagerDeps } from '../main/ets/agent/run_manager.ts';
import { createScheduler, isInterruptedPhase } from '../main/ets/agent/scheduler.ts';

import type { SectionWriterTools } from '../main/ets/agent/section_writer_tools.ts';
import type { CollectRunFn } from '../main/ets/agent/supervisor_loop.ts';
import type { UIMessage, UIMessagePartText } from '../main/ets/agent/message.ts';
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
    if (w === null) return [...messages, makeAssistantMessage('no writer')];
    const lastUser = [...messages].reverse().find(m => m.role === 'user');
    const promptPart = lastUser?.parts.find((p): p is UIMessagePartText => p.type === 'text');
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
    return [...messages, makeAssistantMessage('written')];
  };

// ===== createRunContext: 空 prefetch 不冒充有效研究资料 =====

test('createRunContext: empty prefetch fails without a fabricated title source', async () => {
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
});

// ===== R20:预抓窗口取消贯穿(不落中断态、返回 aborted) =====

test('R20: cancel during prefetch → aborted result and no RUNNING state persisted', async () => {
  const repo = makeMemoryRepo();
  const controller = new AbortController();
  let sawSignal = false;
  const prefetcher: SourcePrefetcher = {
    collect: async (_topicId, _title, _seedUrl, _force, signal) => {
      sawSignal = signal !== undefined;
      controller.abort();   // 模拟用户在预抓窗口取消
      return sources;
    },
    cacheSize: () => 0,
  };
  const deps: RunManagerDeps = {
    prefetcher, collectRun: async (m) => m, aiClient: mockAi,
    model: 'm', playbookMarkdown: '', nowIso: () => '2026-06-23', repository: repo,
  };
  const result = await run(deps, 'tc', '标题', { force: true, signal: controller.signal });
  assert.equal(sawSignal, true, 'prefetch.collect must receive the cancel signal');
  assert.equal(result.ok, false);
  assert.equal(result.error, 'aborted', 'cancel must normalize to aborted result');
  assert.equal(repo.get('tc'), null, 'cancel must not persist COLLECTING/WRITING RUNNING state');
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
  const noWriteCollectRun: CollectRunFn = async (m) => [...m, makeAssistantMessage('...')];
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

// ===== run(): force=true 保留旧稿至新结果保存成功 =====

test('run(): force=true never clears the old cache before generation', async () => {
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
  assert.equal(cleared, false);
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

// ===== P2-2:续跑只补非 READY 段,不重跑已 READY =====

test('P2-2: continue reuses READY sections and only generates non-READY stages', async () => {
  // 预置部分缓存:OVERVIEW READY,其余未完成
  const repo = makeMemoryRepo();
  const partial: DeepReadOutput = {
    ...makeEmptyDeepReadOutput(),
    generationPhase: 'WRITING',
    sectionStates: { OVERVIEW: { status: 'READY', errorMessage: null } },
  };
  repo.save('tp', '标题', partial);
  const writerRef: { w: SectionWriterTools | null } = { w: null };
  // 记录每段 collectRun 被调用的 prompt 标签
  const labels: string[] = [];
  const collectRun: CollectRunFn = async (messages, label) => {
    labels.push(label);
    return makeWritingCollectRun(writerRef)(messages, label);
  };
  const deps: RunManagerDeps = {
    prefetcher: mockPrefetcher, collectRun, aiClient: mockAi,
    model: 'm', playbookMarkdown: '', nowIso: () => '2026-06-23',
    repository: repo, onWriterCreated: (w) => { writerRef.w = w; },
  };
  const result = await run(deps, 'tp', '标题', { force: false });
  // 未触发任何段:collectRun 只应被非 READY 的 3 段调用(不含 OVERVIEW)
  assert.equal(labels.some(l => l.includes('概览')), false, `OVERVIEW must not rerun: ${labels}`);
  assert.ok(labels.some(l => l.includes('时间轴') || l.includes('NARRATIVE')), 'missing stages still generated');
  assert.equal(result.ok, true);
});

test('P2-2: force still regenerates all four stages', async () => {
  const repo = makeMemoryRepo();
  const partial: DeepReadOutput = {
    ...makeEmptyDeepReadOutput(),
    generationPhase: 'WRITING',
    sectionStates: { OVERVIEW: { status: 'READY', errorMessage: null } },
  };
  repo.save('tf', '标题', partial);
  const writerRef: { w: SectionWriterTools | null } = { w: null };
  const calls = { n: 0 };
  const collectRun: CollectRunFn = async (messages, label) => {
    calls.n++;
    return makeWritingCollectRun(writerRef)(messages, label);
  };
  const deps: RunManagerDeps = {
    prefetcher: mockPrefetcher, collectRun, aiClient: mockAi,
    model: 'm', playbookMarkdown: '', nowIso: () => '2026-06-23',
    repository: repo, onWriterCreated: (w) => { writerRef.w = w; },
  };
  const result = await run(deps, 'tf', '标题', { force: true });
  assert.ok(calls.n >= 4, `force must run all stages: ${calls.n}`);
  assert.equal(result.ok, true);
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
