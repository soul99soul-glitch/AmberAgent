import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  buildPrompt, buildWriterReminder, runStageSupervisorLoop, TimeoutError,
  CollectRunFn, SupervisorContext,
} from '../main/ets/agent/supervisor_loop.ts';
import { tryFallbackAfterStageFailure } from '../main/ets/agent/try_fallback.ts';
import { createSectionWriterTools } from '../main/ets/agent/section_writer_tools.ts';
import type { UIMessage, UIMessagePart, UIMessagePartText } from '../main/ets/agent/message.ts';
import { makeUserMessage, makeAssistantMessage } from '../main/ets/agent/message.ts';
import type { DeepReadEvidencePack, EvidenceCard, DeepReadArticlePlan } from '../main/ets/research/evidence_pack.ts';
import type { DeepReadOutput } from '../main/ets/domain/models.ts';
import { makeEmptyDeepReadOutput } from '../main/ets/domain/models.ts';
import { statusOf } from '../main/ets/domain/helpers.ts';
import type { DeepReadGenerationStage } from '../main/ets/domain/enums.ts';
import { STAGE_LABELS, WRITER_TOOL_NAMES } from '../main/ets/domain/enums.ts';

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
    { kind: 'text', text: '正在写入概览。' },
    {
      kind: 'tool', toolName: WRITER_TOOL_NAMES.OVERVIEW, toolCallId: 'tc1',
      input: JSON.stringify({ summary: '这是有效的概览内容,充分满足最小字符阈值长度要求。', topic_type: 'event' }),
      approvalState: 'approved', output: [],
    },
  ];
  return makeAssistantMessage(parts);
};

// ===== buildPrompt =====

test('buildPrompt: includes date, title, stage label, playbook', () => {
  const prompt = buildPrompt({
    topicTitle: '量子计算', stage: 'OVERVIEW',
    existingOutput: makeEmptyDeepReadOutput(), seedUrl: null,
    scrapeWebAvailable: false, evidencePack: makePack(), articlePlan: emptyPlan(),
    stageEvidence: [makeCard('src-1')], stageTimeoutMs: 90_000,
    playbookMarkdown: '## 规则\n要点', todayIso: '2026-06-23',
  });
  assert.ok(prompt.includes('今天日期：2026-06-23'));
  assert.ok(prompt.includes('话题标题：量子计算'));
  assert.ok(prompt.includes(`目标段落：${STAGE_LABELS.OVERVIEW}`));
  assert.ok(prompt.includes('## 规则'));
  assert.ok(prompt.includes('要点'));
});

test('buildPrompt: includes seedUrl section when provided', () => {
  const prompt = buildPrompt({
    topicTitle: 't', stage: 'OVERVIEW', existingOutput: makeEmptyDeepReadOutput(),
    seedUrl: 'https://seed.example.com/x', scrapeWebAvailable: true,
    evidencePack: makePack(), articlePlan: emptyPlan(),
    stageEvidence: [makeCard('s1')], stageTimeoutMs: 90_000,
    playbookMarkdown: '', todayIso: '2026-06-23',
  });
  assert.ok(prompt.includes('用户指定来源 URL：https://seed.example.com/x'));
});

test('buildPrompt: omits seedUrl section when null', () => {
  const prompt = buildPrompt({
    topicTitle: 't', stage: 'OVERVIEW', existingOutput: makeEmptyDeepReadOutput(),
    seedUrl: null, scrapeWebAvailable: false, evidencePack: makePack(),
    articlePlan: emptyPlan(), stageEvidence: [makeCard('s1')],
    stageTimeoutMs: 90_000, playbookMarkdown: '', todayIso: '2026-06-23',
  });
  assert.ok(!prompt.includes('用户指定来源 URL'));
});

test('buildPrompt: scrape_web available vs not (different research order line 3)', () => {
  const base = (scrape: boolean) => buildPrompt({
    topicTitle: 't', stage: 'OVERVIEW', existingOutput: makeEmptyDeepReadOutput(),
    seedUrl: null, scrapeWebAvailable: scrape, evidencePack: makePack(),
    articlePlan: emptyPlan(), stageEvidence: [makeCard('s1')],
    stageTimeoutMs: 90_000, playbookMarkdown: '', todayIso: '2026-06-23',
  });
  assert.ok(base(true).includes('再 scrape_web'));
  assert.ok(base(false).includes('当前未暴露 scrape_web'));
});

test('buildPrompt: includes stage-specific requirement', () => {
  for (const stage of ['OVERVIEW', 'NARRATIVE', 'ANALYSIS', 'EXTENDED_READING'] as DeepReadGenerationStage[]) {
    const prompt = buildPrompt({
      topicTitle: 't', stage, existingOutput: makeEmptyDeepReadOutput(),
      seedUrl: null, scrapeWebAvailable: false, evidencePack: makePack(),
      articlePlan: emptyPlan(), stageEvidence: [makeCard('s1')],
      stageTimeoutMs: 90_000, playbookMarkdown: '', todayIso: '2026-06-23',
    });
    assert.ok(prompt.includes(WRITER_TOOL_NAMES[stage]), `stage ${stage} mentions its writer tool`);
  }
});

test('buildPrompt: includes article plan fields', () => {
  const plan: DeepReadArticlePlan = {
    ...emptyPlan(),
    overviewAngle: '测试角度',
    narrativeSlots: ['slot1', 'slot2'],
    analysisQuestions: ['问题一'],
    riskOrUncertainty: ['风险点'],
    requiredSourceIds: ['src-1', 'src-2'],
  };
  const prompt = buildPrompt({
    topicTitle: 't', stage: 'OVERVIEW', existingOutput: makeEmptyDeepReadOutput(),
    seedUrl: null, scrapeWebAvailable: false, evidencePack: makePack(), articlePlan: plan,
    stageEvidence: [makeCard('s1')], stageTimeoutMs: 90_000, playbookMarkdown: '', todayIso: '2026-06-23',
  });
  assert.ok(prompt.includes('angle: 测试角度'));
  assert.ok(prompt.includes('narrative_slots: slot1 / slot2'));
  assert.ok(prompt.includes('问题一'));
  assert.ok(prompt.includes('风险点'));
  assert.ok(prompt.includes('required_source_ids: src-1, src-2'));
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

test('buildPrompt: empty evidence → "没有可用证据"', () => {
  const prompt = buildPrompt({
    topicTitle: 't', stage: 'OVERVIEW', existingOutput: makeEmptyDeepReadOutput(),
    seedUrl: null, scrapeWebAvailable: false, evidencePack: makePack([]),
    articlePlan: emptyPlan(), stageEvidence: [], stageTimeoutMs: 90_000,
    playbookMarkdown: '', todayIso: '2026-06-23',
  });
  assert.ok(prompt.includes('没有可用证据'));
});

test('buildPrompt: includes section_states from existing output', () => {
  const existing: DeepReadOutput = { ...makeEmptyDeepReadOutput(), sectionStates: { OVERVIEW: { status: 'READY', errorMessage: null } } };
  const prompt = buildPrompt({
    topicTitle: 't', stage: 'NARRATIVE', existingOutput: existing,
    seedUrl: null, scrapeWebAvailable: false, evidencePack: makePack(),
    articlePlan: emptyPlan(), stageEvidence: [makeCard('s1')], stageTimeoutMs: 90_000,
    playbookMarkdown: '', todayIso: '2026-06-23',
  });
  assert.ok(prompt.includes('section_states:'));
  assert.ok(prompt.includes('overview=ready'));
});

// ===== buildWriterReminder =====

test('buildWriterReminder: includes pass number + stage writer tool', () => {
  const r0 = buildWriterReminder('OVERVIEW', 0);
  assert.ok(r0.includes('reminder #1'));
  assert.ok(r0.includes(WRITER_TOOL_NAMES.OVERVIEW));
  const r1 = buildWriterReminder('ANALYSIS', 1);
  assert.ok(r1.includes('reminder #2'));
  assert.ok(r1.includes(WRITER_TOOL_NAMES.ANALYSIS));
});

// ===== tryFallbackAfterStageFailure =====

test('tryFallback: already READY → returns true without writing', async () => {
  const preset: DeepReadOutput = { ...makeEmptyDeepReadOutput(), sectionStates: { OVERVIEW: { status: 'READY', errorMessage: null } } };
  const writer = createSectionWriterTools({ topicId: 't', topicTitle: 'x', imageCandidates: [], initialOutput: preset });
  const result = await tryFallbackAfterStageFailure({
    writer, stage: 'OVERVIEW', messages: [], sources: [], reason: 'test', allowReadyRewrite: false,
  });
  assert.equal(result, true);
});

test('tryFallback: useful assistant text → recovered (READY BASIC)', async () => {
  const writer = createSectionWriterTools({ topicId: 't', topicTitle: 'x', imageCandidates: [] });
  const msgs: UIMessage[] = [
    makeUserMessage('研究'),
    makeAssistantMessage([{ kind: 'text', text: '这是模型生成的足够长的中文概览文本内容,包含足够汉字以通过有效性检查阈值要求。' }]),
  ];
  const result = await tryFallbackAfterStageFailure({
    writer, stage: 'OVERVIEW', messages: msgs, sources: [makeCard('s1')], reason: 'missing writer tool', allowReadyRewrite: false,
  });
  assert.equal(result, true);
  assert.equal(statusOf(writer.current(), 'OVERVIEW'), 'READY');
  assert.equal(writer.current().sectionQualities.OVERVIEW, 'BASIC');
});

test('tryFallback: low-quality text + no sources → not recovered (false)', async () => {
  const writer = createSectionWriterTools({ topicId: 't', topicTitle: 'x', imageCandidates: [] });
  const msgs: UIMessage[] = [
    makeAssistantMessage([{ kind: 'text', text: '短' }]),
  ];
  // EXTENDED_READING fallback 需要 http 链接;给空 sources → 不会 READY
  const result = await tryFallbackAfterStageFailure({
    writer, stage: 'EXTENDED_READING', messages: msgs, sources: [], reason: 'timeout', allowReadyRewrite: false,
  });
  assert.equal(result, false);
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
      return [...messages, makeAssistantMessage([{ kind: 'text', text: '我还在思考。' }])];
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
    return [...messages, makeAssistantMessage([{ kind: 'text', text: '思考中...' }])];
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
  const collectRun: CollectRunFn = async (messages) => [...messages, makeAssistantMessage([{ kind: 'text', text: '...' }])];
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
  const textPart = received[0].parts.find((p): p is UIMessagePartText => p.kind === 'text');
  assert.ok(textPart !== undefined);
  if (textPart !== undefined) {
    assert.ok(textPart.text.includes('独特话题标题'));
  }
});
