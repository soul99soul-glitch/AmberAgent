import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { buildPrompt, runStageSupervisorLoop, TimeoutError, CollectRunFn, SupervisorContext } from '../main/ets/agent/supervisor_loop.ts';

import { createSectionWriterTools } from '../main/ets/agent/section_writer_tools.ts';
import type { UIMessage, UIMessagePart, UIMessagePartText } from '../main/ets/agent/message.ts';
import { makeAssistantMessage, makeUIMessage } from '../main/ets/agent/message.ts';
import type { DeepReadEvidencePack, EvidenceCard, DeepReadArticlePlan } from '../main/ets/research/evidence_pack.ts';

import { makeEmptyDeepReadOutput } from '../main/ets/domain/models.ts';
import { statusOf } from '../main/ets/domain/helpers.ts';
import type { DeepReadGenerationStage } from '../main/ets/domain/enums.ts';
import { WRITER_TOOL_NAMES } from '../main/ets/domain/enums.ts';

// ===== fixtures =====

const emptyPlan = (): DeepReadArticlePlan => ({
  overviewAngle: '从已核查来源解释事件',
  narrativeSlots: [],
  analysisQuestions: [],
  stakeholders: [],
  riskOrUncertainty: [],
  requiredSourceIds: [],
  stageSourceIds: {},
  coverageChecks: [],
});

const makeCard = (id: string): EvidenceCard => ({
  sourceId: id, url: `https://src.example.com/${id}`, title: `来源 ${id}`,
  source: 'tavily', credibility: 'medium', freshness: 'unknown', publishedAt: null,
  evidenceExcerpt: '证据正文摘录内容'.repeat(10), imageCandidates: [],
});

const makePack = (cards: EvidenceCard[] = [makeCard('src-1')]): DeepReadEvidencePack => ({
  allSources: [],
  cardsByStage: { OVERVIEW: cards, NARRATIVE: [], ANALYSIS: [], EXTENDED_READING: cards },
  requiredSourceIds: ['src-1'],
});

const assistantWithOverviewToolCall = (): UIMessage => {
  // 模拟 assistant 调用 overview writer tool(带有效 summary)
  const parts: UIMessagePart[] = [
    { type: 'text', text: '正在写入概览。', metadata: null },
    {
      type: 'tool', toolName: WRITER_TOOL_NAMES.OVERVIEW, toolCallId: 'tc1',
      input: JSON.stringify({ summary: '这是有效的概览内容,充分满足最小字符阈值长度要求。', topic_type: 'event' }),
      approvalState: { type: 'approved' }, output: [], metadata: null,
    },
  ];
  return makeUIMessage('assistant', parts);
};

test('buildPrompt: legacy scrape flag cannot advertise tools absent from the writer set', () => {
  const base = (scrape: boolean) => buildPrompt({
    topicTitle: 't', stage: 'OVERVIEW', existingOutput: makeEmptyDeepReadOutput(),
    seedUrl: null, scrapeWebAvailable: scrape, evidencePack: makePack(),
    articlePlan: emptyPlan(), stageEvidence: [makeCard('s1')],
    stageTimeoutMs: 90_000, playbookMarkdown: '', todayIso: '2026-06-23',
  });
  assert.ok(!base(true).includes('scrape_web'));
  assert.ok(!base(false).includes('scrape_web'));
  assert.ok(base(true).includes('不确定'));
});

test('buildPrompt: includes evidence card with excerpt + image candidates', () => {
  const cardWithImg: EvidenceCard = {
    ...makeCard('s1'),
    evidenceExcerpt: '这是证据摘录内容'.repeat(5),
    imageCandidates: [{
      url: 'https://img.example.com/1.jpg', width: 800, height: 600,
      altText: '描述', sourceUrl: 'src', confidence: 'hero', score: 75,
      riskFlags: [], selectionReason: '匹配',
    }],
  };
  const prompt = buildPrompt({
    topicTitle: 't', stage: 'OVERVIEW', existingOutput: makeEmptyDeepReadOutput(),
    seedUrl: null, scrapeWebAvailable: false, evidencePack: makePack([cardWithImg]),
    articlePlan: emptyPlan(), stageEvidence: [cardWithImg], stageTimeoutMs: 90_000,
    playbookMarkdown: '', todayIso: '2026-06-23',
  });
  assert.ok(prompt.includes('### s1. 来源 s1'));
  assert.ok(prompt.includes('evidence_excerpt:'));
  assert.ok(prompt.includes('image_candidates:'));
  assert.ok(prompt.includes('hero'));
});

// ===== runStageSupervisorLoop: happy path (LLM calls writer tool) =====

test('supervisor loop: LLM calls writer tool on pass 0 → stage READY, outcome ready', async () => {
  const writer = createSectionWriterTools({ topicId: 't', topicTitle: '量子计算', imageCandidates: [] });
  // collectRun mock:返回带 overview tool call 的 assistant message,
  // 然后用 writer 工具真实执行(模拟 GenerationHandler tool loop 结果)
  const collectRun: CollectRunFn = async (): Promise<UIMessage[]> => {
    // 模拟:assistant 调 overview tool → tool 被执行 → section 写入
    const overviewTool = writer.tools(new Set(['OVERVIEW'])).find(t => t.name === WRITER_TOOL_NAMES.OVERVIEW)!;
    await overviewTool.execute(JSON.stringify({ summary: '这是有效的概览内容,充分满足最小字符阈值长度要求。' }));
    return [assistantWithOverviewToolCall()];
  };
  const tryFallback = async (): Promise<boolean> => { throw new Error('should not reach fallback'); };
  const ctx: SupervisorContext = {
    writer, evidencePack: makePack(), articlePlan: emptyPlan(),
    topicTitle: '量子计算', seedUrl: null, playbookMarkdown: '',
    scrapeWebAvailable: false, collectRun, tryFallback, todayIso: '2026-06-23',
  };
  const result = await runStageSupervisorLoop(ctx, 'OVERVIEW');
  assert.equal(result.ready, true);
  assert.equal(result.outcome, 'ready');
  assert.equal(statusOf(result.finalOutput, 'OVERVIEW'), 'READY');
});

// ===== supervisor loop: pass 0 no write → reminder added → pass 1 writes =====

test('supervisor loop: no write on pass 0, writes on pass 1 → ready', async () => {
  const writer = createSectionWriterTools({ topicId: 't', topicTitle: '量子计算', imageCandidates: [] });
  let callCount = 0;
  const collectRun: CollectRunFn = async (messages): Promise<UIMessage[]> => {
    callCount++;
    if (callCount === 1) {
      // pass 0: 不调工具,只返回自由文本 → 无写入
      return [...messages, makeAssistantMessage('我还在思考。')];
    }
    // pass 1: 调 overview tool
    const tool = writer.tools(new Set(['OVERVIEW'])).find(t => t.name === WRITER_TOOL_NAMES.OVERVIEW)!;
    await tool.execute(JSON.stringify({ summary: '这是有效的概览内容,充分满足最小字符阈值长度要求。' }));
    return [...messages, assistantWithOverviewToolCall()];
  };
  const tryFallback = async (): Promise<boolean> => { throw new Error('should not reach'); };
  const ctx: SupervisorContext = {
    writer, evidencePack: makePack(), articlePlan: emptyPlan(),
    topicTitle: '量子计算', seedUrl: null, playbookMarkdown: '',
    scrapeWebAvailable: false, collectRun, tryFallback, todayIso: '2026-06-23',
  };
  const result = await runStageSupervisorLoop(ctx, 'OVERVIEW');
  assert.equal(result.ready, true);
  assert.equal(result.outcome, 'ready');
  assert.equal(callCount, 2, 'two passes executed');
});

// ===== supervisor loop: 2 passes no write → fallback =====

test('supervisor loop: 2 passes no write → tryFallback recovers', async () => {
  const writer = createSectionWriterTools({ topicId: 't', topicTitle: 'x', imageCandidates: [] });
  const collectRun: CollectRunFn = async (messages): Promise<UIMessage[]> => {
    return [...messages, makeAssistantMessage('思考中...')];
  };
  let fallbackCalled = false;
  const tryFallback = async (): Promise<boolean> => {
    fallbackCalled = true;
    // 模拟 fallback 成功:直接写 READY
    writer.writeFallbackSection('OVERVIEW',
      '这是足够长的中文概览文本内容以满足有效性阈值检查要求。',
      [], false);
    return true;
  };
  const ctx: SupervisorContext = {
    writer, evidencePack: makePack(), articlePlan: emptyPlan(),
    topicTitle: 'x', seedUrl: null, playbookMarkdown: '',
    scrapeWebAvailable: false, collectRun, tryFallback, todayIso: '2026-06-23',
  };
  const result = await runStageSupervisorLoop(ctx, 'OVERVIEW');
  assert.equal(fallbackCalled, true);
  assert.equal(result.outcome, 'fallback_recovered');
  assert.equal(result.ready, true);
});

test('supervisor loop: 2 passes no write + fallback fails → markFailed', async () => {
  const writer = createSectionWriterTools({ topicId: 't', topicTitle: 'x', imageCandidates: [] });
  const collectRun: CollectRunFn = async (messages) => [...messages, makeAssistantMessage('...')];
  const tryFallback = async (): Promise<boolean> => false;  // fallback 失败
  const ctx: SupervisorContext = {
    writer, evidencePack: makePack(), articlePlan: emptyPlan(),
    topicTitle: 'x', seedUrl: null, playbookMarkdown: '',
    scrapeWebAvailable: false, collectRun, tryFallback, todayIso: '2026-06-23',
  };
  const result = await runStageSupervisorLoop(ctx, 'OVERVIEW');
  assert.equal(result.ready, false);
  assert.equal(result.outcome, 'failed');
  assert.equal(statusOf(result.finalOutput, 'OVERVIEW'), 'FAILED');
  assert.ok((result.finalOutput.sectionStates.OVERVIEW?.errorMessage ?? '').length > 0);
});

// ===== supervisor loop: collectRun timeout =====

test('supervisor loop: collectRun throws TimeoutError → fallback path', async () => {
  const writer = createSectionWriterTools({ topicId: 't', topicTitle: 'x', imageCandidates: [] });
  const collectRun: CollectRunFn = async (): Promise<UIMessage[]> => { throw new TimeoutError(); };
  let fallbackReason = '';
  const tryFallback = async (
    _stage: DeepReadGenerationStage, _msgs: UIMessage[], _sources: EvidenceCard[], reason: string,
  ): Promise<boolean> => {
    fallbackReason = reason;
    return false;
  };
  const ctx: SupervisorContext = {
    writer, evidencePack: makePack(), articlePlan: emptyPlan(),
    topicTitle: 'x', seedUrl: null, playbookMarkdown: '',
    scrapeWebAvailable: false, collectRun, tryFallback, todayIso: '2026-06-23',
  };
  const result = await runStageSupervisorLoop(ctx, 'OVERVIEW');
  assert.equal(fallbackReason, 'timeout');
  assert.equal(result.outcome, 'timeout');
  assert.equal(result.ready, false);
  // 超时失败消息
  assert.ok((result.finalOutput.sectionStates.OVERVIEW?.errorMessage ?? '').includes('超时'));
});

test('supervisor loop: collectRun throws generic error → fallback reason failure', async () => {
  const writer = createSectionWriterTools({ topicId: 't', topicTitle: 'x', imageCandidates: [] });
  const collectRun: CollectRunFn = async (): Promise<UIMessage[]> => { throw new Error('provider 500'); };
  let fallbackReason = '';
  const tryFallback = async (
    _stage: DeepReadGenerationStage, _msgs: UIMessage[], _sources: EvidenceCard[], reason: string,
  ): Promise<boolean> => {
    fallbackReason = reason; return false;
  };
  const ctx: SupervisorContext = {
    writer, evidencePack: makePack(), articlePlan: emptyPlan(),
    topicTitle: 'x', seedUrl: null, playbookMarkdown: '',
    scrapeWebAvailable: false, collectRun, tryFallback, todayIso: '2026-06-23',
  };
  const result = await runStageSupervisorLoop(ctx, 'OVERVIEW');
  assert.equal(fallbackReason, 'failure');
  assert.equal(result.outcome, 'error');
  assert.equal(statusOf(result.finalOutput, 'OVERVIEW'), 'FAILED');
});

// ===== AbortError 重新抛出(不进 fallback) =====

test('supervisor loop: AbortError rethrown (not caught)', async () => {
  const writer = createSectionWriterTools({ topicId: 't', topicTitle: 'x', imageCandidates: [] });
  const abortErr = new Error('cancelled');
  abortErr.name = 'AbortError';
  const collectRun: CollectRunFn = async (): Promise<UIMessage[]> => { throw abortErr; };
  const tryFallback = async (): Promise<boolean> => { throw new Error('fallback should not run'); };
  const ctx: SupervisorContext = {
    writer, evidencePack: makePack(), articlePlan: emptyPlan(),
    topicTitle: 'x', seedUrl: null, playbookMarkdown: '',
    scrapeWebAvailable: false, collectRun, tryFallback, todayIso: '2026-06-23',
  };
  await assert.rejects(() => runStageSupervisorLoop(ctx, 'OVERVIEW'), /cancelled/);
});

// ===== prompt 传入 collectRun 的 message 是 user message =====

test('supervisor loop: first collectRun receives user prompt with buildPrompt content', async () => {
  const writer = createSectionWriterTools({ topicId: 't', topicTitle: '独特话题标题', imageCandidates: [] });
  const received: UIMessage[] = [];
  const collectRun: CollectRunFn = async (messages): Promise<UIMessage[]> => {
    if (received.length === 0) received.push(messages[0]);
    // 立即写入避免多 pass
    const tool = writer.tools(new Set(['OVERVIEW'])).find(t => t.name === WRITER_TOOL_NAMES.OVERVIEW)!;
    await tool.execute(JSON.stringify({ summary: '这是有效的概览内容,充分满足最小字符阈值长度要求。' }));
    return [...messages, assistantWithOverviewToolCall()];
  };
  const ctx: SupervisorContext = {
    writer, evidencePack: makePack(), articlePlan: emptyPlan(),
    topicTitle: '独特话题标题', seedUrl: null, playbookMarkdown: '',
    scrapeWebAvailable: false, collectRun, tryFallback: async () => false, todayIso: '2026-06-23',
  };
  await runStageSupervisorLoop(ctx, 'OVERVIEW');
  assert.ok(received.length > 0);
  // user message 应包含 buildPrompt 生成的标题
  const textPart = received[0].parts.find((p): p is UIMessagePartText => p.type === 'text');
  assert.ok(textPart !== undefined);
  if (textPart !== undefined) {
    assert.ok(textPart.text.includes('独特话题标题'));
  }
});
