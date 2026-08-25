// runStageSupervisorLoop + collectRun + buildPrompt + buildWriterReminder
// 照搬 Android DeepReadAgentRunManager.kt:412-527 (supervisor loop) + 529-570 (collectRun)
//              + 661-855 (buildPrompt + reminder)
//
// 设计 0.1:无 coverageReport 参数(supplementWritten 永远 true)。
// collectRun 是 AiClient.generateText 的薄封装;supervisor loop 用可注入的 collectRunFn
// 方便测试(mock AiClient)。timeout 用 Promise.race + setTimeout 模拟 withTimeout。

import type { UIMessage } from './message.ts';
import { makeUserMessage } from './message.ts';
import type { AbortControllerLike, AbortSignalLike } from '../platform/runtime_api.ts';
import type { SectionWriterTools } from './section_writer_tools.ts';
import type { ToolDefinition } from './tool_execution.ts';
import type { DeepReadEvidencePack, EvidenceCard, DeepReadArticlePlan } from '../research/evidence_pack.ts';
import { cardsFor } from '../research/evidence_pack.ts';
import type { DeepReadGenerationStage } from '../domain/enums.ts';
import {
  STAGE_LABELS, WRITER_TOOL_NAMES, STAGE_TIMEOUT_MS, STAGE_EXCERPT_LIMIT,
  MAX_SUPERVISOR_PASSES, MAX_GENERATION_STEPS, PLAYBOOK_PROMPT_LIMIT,
} from '../domain/enums.ts';
import { statusOf, withInferredSectionStates } from '../domain/helpers.ts';
import type { DeepReadOutput } from '../domain/models.ts';

// ===== collectRun:AiClient.generateText 薄封装(可注入便于测试) =====
// 真实实现由 RunManager 注入 aiClient.generateText;测试用 mock
// signal:调度层 abort 贯穿(scheduler.abort → run opts.signal → 此处);
//   实现侧应在等待中轮询/监听 signal 并以 AbortError 结束
export type CollectRunFn = (
  messages: UIMessage[],
  statusLabel: string,
  signal?: AbortSignalLike,
  tools?: ToolDefinition[],
) => Promise<UIMessage[]>;

// ===== buildPrompt(照搬 Android :661-739) =====

export interface BuildPromptParams {
  topicTitle: string;
  stage: DeepReadGenerationStage;
  existingOutput: DeepReadOutput;
  seedUrl: string | null;
  scrapeWebAvailable: boolean;
  evidencePack: DeepReadEvidencePack;
  articlePlan: DeepReadArticlePlan;
  stageEvidence: EvidenceCard[];
  stageTimeoutMs: number;
  playbookMarkdown: string;
  todayIso: string;   // 注入避免 Date.now() 不稳定
}

export const buildPrompt = (p: BuildPromptParams): string => {
  const lines: string[] = [];
  const today = p.todayIso;
  lines.push(`今天日期：${today}`);
  lines.push(`话题标题：${p.topicTitle}`);
  lines.push(`目标段落：${STAGE_LABELS[p.stage]}`);
  lines.push('');
  lines.push('## Deep Read Playbook（本地规则，只读）');
  lines.push(p.playbookMarkdown.slice(0, PLAYBOOK_PROMPT_LIMIT));
  if (p.seedUrl !== null && p.seedUrl.trim().length > 0) {
    lines.push(`用户指定来源 URL：${p.seedUrl}`);
    lines.push('该 URL 已在预抓阶段尝试读取，优先使用预抓正文；如下面预抓正文为空再 search_web/scrape_web 补充。');
  }
  lines.push('');
  appendArticlePlan(lines, p.articlePlan);
  lines.push('');
  appendEvidenceCards(lines, p.stageEvidence, p.evidencePack, `本段证据包（全局共 ${p.evidencePack.allSources.length} 条，本轮只给最相关 ${p.stageEvidence.length} 条）`, STAGE_EXCERPT_LIMIT[p.stage]);
  lines.push('');
  appendArticleContext(lines, p.existingOutput);
  lines.push('');
  appendDeadlineGuidance(lines, p.stage, p.stageTimeoutMs);
  lines.push('');
  lines.push('## 研究顺序');
  lines.push('1. 本地 harness 已经完成来源扩展、去重、分桶和结构规划。你只处理当前小目标。');
  lines.push('2. 仅在以下情况补充 search_web：');
  lines.push('   - 关键事实（发布时间、价格、官方表态、版本号等）在预抓来源中找不到或互相矛盾');
  lines.push('   - 用户指定 URL 的预抓正文为空，需要别的 query 命中该话题');
  lines.push('   - 你需要反面证据时（例如「辟谣」「不实」「未确认」）');
  if (p.scrapeWebAvailable) {
    lines.push('3. 仅在确认某个 URL 比预抓正文更详细时再 scrape_web；不要把预抓已有正文重抓一次。');
  } else {
    lines.push('3. 当前未暴露 scrape_web；只能基于预抓正文 + 必要时的 search_web 摘要写入。');
  }
  lines.push('4. 本轮只生成目标段落，不要改写或重写未列入目标的段落。');
  lines.push('5. 完成研究后立即调用对应 writer tool：');
  lines.push(`   - ${WRITER_TOOL_NAMES[p.stage]}：${STAGE_LABELS[p.stage]}`);
  lines.push('6. 本轮只暴露目标段 writer tool；如果你输出自由文本但没调工具，系统会尝试把自由文本转换成基础稿。');
  lines.push('7. 图片只能从 image_candidates 中选择；本轮如果没有视觉 writer，就在目标段 references/links 中保留可用来源。');
  lines.push('8. 全部 writer 完成后，直接调用 deep_read_finish。');
  lines.push('');
  lines.push('## 段落要求');
  appendStageRequirement(lines, p.stage);
  lines.push('- 视觉：头图必须来自候选池且 confidence=hero；inline 候选只能作为正文图。不得提交任意 URL、站点 logo、favicon、媒体图标或头像。');
  lines.push('- 图解：只提交 3-6 个短节点的 diagram spec，节点 label 控制在约 30 字内；流程/因果可保留少量关键跨节点关系，但避免网状交叉。禁止 raw SVG/HTML/JS/外链资源。图解不参与段落完成状态，不需要就隐藏。');
  lines.push('');
  lines.push('正文输出不会被 UI 消费。不要输出完整 JSON，不要写 Markdown 长文作为最终答案。');
  return lines.join('\n');
};

const appendStageRequirement = (lines: string[], stage: DeepReadGenerationStage): void => {
  switch (stage) {
    case 'OVERVIEW':
      lines.push('- 概览：约 120-250 字中文杂志导语，说明事件是什么、为什么值得读、哪些事实已核查；完整句子优先，略超可以接受。');
      break;
    case 'NARRATIVE':
      lines.push('- 时间轴叙事：事件型写 timeline；观点/产品/人物型可写 core_points，但要有故事性和演化脉络。');
      break;
    case 'ANALYSIS':
      lines.push('- 深度分析：围绕核心分歧、各方立场、影响分析；这一段需要充分 reasoning，但不要输出 reasoning 给 UI。');
      break;
    case 'EXTENDED_READING':
      lines.push('- 扩展阅读：只放真实来源链接和真实图片资产。');
      break;
  }
};

const appendDeadlineGuidance = (lines: string[], stage: DeepReadGenerationStage, stageTimeoutMs: number): void => {
  const stageSeconds = Math.max(1, Math.floor(stageTimeoutMs / 1000));
  lines.push('## 时间预算（硬约束）');
  lines.push(`- 本段运行预算约 ${stageSeconds} 秒。`);
  lines.push(`- 你必须把第一优先级放在调用 ${WRITER_TOOL_NAMES[stage]}；不要先输出长文、完整 JSON 或 Markdown 草稿。`);
  lines.push('- 预抓证据包是主材料。除非关键事实缺失或互相矛盾，不要连续 search_web / scrape_web。');
  lines.push('- 如果证据不够完整，先基于现有证据写保守版本；不要为了补全而耗尽本段预算。');
  if (stage === 'EXTENDED_READING') {
    lines.push('- 扩展阅读不是长文写作：从本段证据包挑选 4-8 条真实来源链接，必要时带 image_assets，然后立即调用 writer tool。');
  }
};

const appendArticlePlan = (lines: string[], plan: DeepReadArticlePlan): void => {
  lines.push('## Article Plan（本地 harness 规划，只读）');
  lines.push(`- angle: ${plan.overviewAngle}`);
  if (plan.narrativeSlots.length > 0) lines.push(`- narrative_slots: ${plan.narrativeSlots.join(' / ')}`);
  if (plan.analysisQuestions.length > 0) {
    lines.push('- analysis_questions:');
    for (const q of plan.analysisQuestions) lines.push(`  - ${q}`);
  }
  if (plan.stakeholders.length > 0) lines.push(`- stakeholders: ${plan.stakeholders.join(' / ')}`);
  if (plan.riskOrUncertainty.length > 0) {
    lines.push('- risk_or_uncertainty:');
    for (const r of plan.riskOrUncertainty) lines.push(`  - ${r}`);
  }
  lines.push(`- required_source_ids: ${plan.requiredSourceIds.join(', ')}`);
};

// 照搬 Android appendEvidenceCards(:776-812),适配本端口 EvidenceCard 扁平结构
const appendEvidenceCards = (
  lines: string[],
  cards: EvidenceCard[],
  _pack: DeepReadEvidencePack,
  title: string,
  excerptLimit: number,
): void => {
  lines.push(`## ${title}`);
  if (cards.length === 0) {
    lines.push('- 本段没有可用证据（理论上不会到这里）。');
    return;
  }
  for (const card of cards) {
    lines.push(`### ${card.sourceId}. ${card.title}`);
    if (card.url.trim().length > 0) lines.push(`- url: ${card.url}`);
    lines.push(`- source: ${card.source ?? '-'}`);
    lines.push(`- credibility: ${card.credibility}; freshness: ${card.freshness}`);
    if (card.publishedAt !== null && card.publishedAt.trim().length > 0) lines.push(`- published_at: ${card.publishedAt}`);
    const candidates = card.imageCandidates.filter(c => c.confidence !== 'reject').slice(0, 4);
    if (candidates.length > 0) {
      lines.push('- image_candidates:');
      for (const candidate of candidates) {
        const risks = candidate.riskFlags.length > 0 ? candidate.riskFlags.join('|') : '-';
        lines.push(`  - ${candidate.confidence} score=${candidate.score} risk=${risks} url=${candidate.url} alt=${candidate.altText.slice(0, 80)}`);
      }
    }
    const excerpt = card.evidenceExcerpt.slice(0, excerptLimit).replace(/\n/g, ' ').trim();
    if (excerpt.length > 0) lines.push(`- evidence_excerpt: ${excerpt}`);
    lines.push('');
  }
};

// 照搬 Android appendArticleContext(:869-)
const appendArticleContext = (lines: string[], output: DeepReadOutput): void => {
  const current = withInferredSectionStates(output);
  lines.push('## 当前稿件正文（补段时必须覆盖）');
  const states = (['OVERVIEW', 'NARRATIVE', 'ANALYSIS', 'EXTENDED_READING'] as const)
    .map(s => `${s.toLowerCase()}=${statusOf(current, s).toLowerCase()}`);
  lines.push(`section_states: ${states.join(', ')}`);
  const hasAnyReady = (['OVERVIEW', 'NARRATIVE', 'ANALYSIS', 'EXTENDED_READING'] as const)
    .some(s => statusOf(current, s) === 'READY');
  if (!hasAnyReady && current.summary.trim().length === 0) {
    lines.push('- 暂无已写入段落。');
    return;
  }
  if (current.summary.trim().length > 0) lines.push(`overview.summary: ${current.summary}`);
  if (current.keyEntities.length > 0) lines.push(`overview.key_entities: ${current.keyEntities.slice(0, 12).join(' / ')}`);
};

// ===== buildWriterReminder(照搬 Android :849-855) =====

export const buildWriterReminder = (stage: DeepReadGenerationStage, pass: number): string => {
  const lines: string[] = [];
  lines.push(`Supervisor reminder #${pass + 1}: 上一轮没有任何 deep_read_write_* 写入。`);
  lines.push('时间提醒：现在请直接调用 writer tool。');
  lines.push('UI 不会消费你的自由文本。请立刻继续研究缺口，然后调用以下 writer tool 中至少一个：');
  lines.push(`- ${WRITER_TOOL_NAMES[stage]} for ${STAGE_LABELS[stage]}`);
  lines.push('如果来源不足，只把当前段落写为 FAILED 的决定留给系统；不要用自由文本交差。');
  return lines.join('\n');
};

// ===== runStageSupervisorLoop(照搬 Android :412-527, 0.1 无 coverageReport) =====

export interface SupervisorContext {
  writer: SectionWriterTools;
  evidencePack: DeepReadEvidencePack;
  articlePlan: DeepReadArticlePlan;
  topicTitle: string;
  seedUrl: string | null;
  playbookMarkdown: string;
  scrapeWebAvailable: boolean;
  collectRun: CollectRunFn;
  todayIso: string;
  /** 调度层 abort 信号:每 pass 开始时检查,并传给 collectRun 供模型调用取消 */
  signal?: AbortSignalLike;
  /** 注入:tryFallbackAfterStageFailure(Phase 4 Task C 实现) */
  tryFallback: (stage: DeepReadGenerationStage, messages: UIMessage[], sources: EvidenceCard[], reason: string) => Promise<boolean>;
}

export interface SupervisorResult {
  stage: DeepReadGenerationStage;
  ready: boolean;
  outcome: 'ready' | 'fallback_recovered' | 'failed' | 'timeout' | 'error';
  finalOutput: DeepReadOutput;
}

export const runStageSupervisorLoop = async (
  ctx: SupervisorContext,
  stage: DeepReadGenerationStage,
): Promise<SupervisorResult> => {
  const writer = ctx.writer;
  const stageTimeoutMs = STAGE_TIMEOUT_MS[stage];
  const stageEvidence = cardsFor(ctx.evidencePack, stage, ctx.articlePlan);
  const stageSources = stageEvidence;

  let messages: UIMessage[] = [makeUserMessage(buildPrompt({
    topicTitle: ctx.topicTitle,
    stage,
    existingOutput: writer.current(),
    seedUrl: ctx.seedUrl,
    scrapeWebAvailable: ctx.scrapeWebAvailable,
    evidencePack: ctx.evidencePack,
    articlePlan: ctx.articlePlan,
    stageEvidence,
    stageTimeoutMs,
    playbookMarkdown: ctx.playbookMarkdown,
    todayIso: ctx.todayIso,
  }))];

  const initialRequiredWrites = writer.requiredWriteCount;

  try {
    for (let pass = 0; pass < MAX_SUPERVISOR_PASSES; pass++) {
      if (ctx.signal !== undefined && ctx.signal.aborted) {
        // 调度层 abort:以 abort 语义上抛(generateStates 的 isAbortError 重抛路径)
        throw new Error('aborted');
      }
      const beforeWrites = writer.requiredWriteCount;
      messages = await withTimeout(
        stageTimeoutMs,
        (stageSignal: AbortSignalLike): Promise<UIMessage[]> => ctx.collectRun(
          messages,
          `深度阅读 ${STAGE_LABELS[stage]}`,
          stageSignal,
          writer.tools(new Set<DeepReadGenerationStage>([stage])),
        ),
        ctx.signal,
      );
      const stageReady = statusOf(writer.current(), stage) === 'READY';
      // 0.1: 无 coverageReport,supplementWritten 永远 true
      const supplementWritten = writer.requiredWriteCount > initialRequiredWrites;
      if (stageReady && supplementWritten) {
        return { stage, ready: true, outcome: 'ready', finalOutput: writer.current() };
      }
      // 该 pass 没新写入 → 加 reminder
      if (writer.requiredWriteCount === beforeWrites) {
        messages = [...messages, makeUserMessage(buildWriterReminder(stage, pass))];
      }
    }
    // 2 pass 都没成功
    if (statusOf(writer.current(), stage) !== 'READY') {
      const recovered = await ctx.tryFallback(stage, messages, stageSources, 'missing writer tool');
      if (recovered) return { stage, ready: true, outcome: 'fallback_recovered', finalOutput: writer.current() };
      const failMsg = stageFailureMessage(stage, new Error('missing writer tool'), stageTimeoutMs);
      writer.markFailed(stage, failMsg);
      return { stage, ready: false, outcome: 'failed', finalOutput: writer.current() };
    }
    return { stage, ready: true, outcome: 'ready', finalOutput: writer.current() };
  } catch (error) {
    if (isAbortError(error)) throw error;
    const isTimeout = isTimeoutError(error);
    const reason = isTimeout ? 'timeout' : isErrorLike(error) && isDeepReadTimeoutLike(error) ? 'provider timeout' : 'failure';
    const recovered = await ctx.tryFallback(stage, messages, stageSources, reason);
    if (!recovered && statusOf(writer.current(), stage) !== 'READY') {
      const failMsg = isTimeout
        ? timeoutFailureMessage(stage, stageTimeoutMs)
        : stageFailureMessage(stage, error instanceof Error ? error : new Error(String(error)), stageTimeoutMs);
      writer.markFailed(stage, failMsg);
    }
    return {
      stage,
      ready: statusOf(writer.current(), stage) === 'READY',
      outcome: recovered ? 'fallback_recovered' : (isTimeout ? 'timeout' : 'error'),
      finalOutput: writer.current(),
    };
  }
};

// ===== 失败消息构造(照搬 Android :1028-1042) =====

const timeoutFailureMessage = (stage: DeepReadGenerationStage, timeoutMs: number): string => {
  const seconds = Math.max(1, Math.floor(timeoutMs / 1000));
  return `${STAGE_LABELS[stage]}超时未完成（本段预算约 ${seconds} 秒）。`;
};

const stageFailureMessage = (stage: DeepReadGenerationStage, error: Error, timeoutMs: number): string => {
  if (isDeepReadTimeoutLike(error)) return timeoutFailureMessage(stage, timeoutMs);
  return `${STAGE_LABELS[stage]}生成失败：${error.message || error.constructor.name}`;
};

// ===== timeout / error 判定 =====

export class TimeoutError extends Error {
  constructor(message = 'stage timeout') { super(message); this.name = 'TimeoutError'; }
}

class StageAbortSignal implements AbortSignalLike {
  aborted: boolean = false;
  private listeners: Array<() => void> = [];

  addEventListener(type: string, listener: () => void): void {
    if (type !== 'abort') return;
    if (this.aborted) {
      listener();
      return;
    }
    this.listeners.push(listener);
  }

  removeEventListener(type: string, listener: () => void): void {
    if (type !== 'abort') return;
    const index: number = this.listeners.indexOf(listener);
    if (index >= 0) this.listeners.splice(index, 1);
  }

  abort(): void {
    if (this.aborted) return;
    this.aborted = true;
    const pending: Array<() => void> = this.listeners;
    this.listeners = [];
    for (const listener of pending) listener();
  }
}

class StageAbortController implements AbortControllerLike {
  readonly signal: StageAbortSignal = new StageAbortSignal();

  abort(): void {
    this.signal.abort();
  }
}

const withTimeout = async <T>(
  ms: number,
  operation: (signal: AbortSignalLike) => Promise<T>,
  parentSignal?: AbortSignalLike,
): Promise<T> => {
  const controller: StageAbortController = new StageAbortController();
  let timedOut: boolean = false;
  const cascade = (): void => { controller.abort(); };
  parentSignal?.addEventListener?.('abort', cascade);
  if (parentSignal?.aborted === true) controller.abort();
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeoutPromise: Promise<never> = new Promise<never>((_, reject) => {
    timer = setTimeout((): void => {
      timedOut = true;
      controller.abort();
      reject(new TimeoutError());
    }, Math.max(ms, 0));
  });
  try {
    if (ms <= 0) return await operation(controller.signal);
    return await Promise.race([operation(controller.signal), timeoutPromise]);
  } finally {
    if (timer !== undefined) clearTimeout(timer);
    parentSignal?.removeEventListener?.('abort', cascade);
  }
};

const isTimeoutError = (e: unknown): boolean =>
  e instanceof TimeoutError || (e instanceof Error && /timeout/i.test(e.message));

const isAbortError = (e: unknown): boolean =>
  e instanceof Error && (e.name === 'AbortError' || /abort|cancel/i.test(e.message));

const isErrorLike = (e: unknown): e is Error =>
  e instanceof Error;

// isDeepReadTimeoutLike — 照搬 Android:provider 超时(SocketTimeoutException 等)
const isDeepReadTimeoutLike = (e: Error): boolean =>
  /timeout|timed out|socket timeout/i.test(e.message);

// 暴露内部供 Task C 复用
export { timeoutFailureMessage, stageFailureMessage, isDeepReadTimeoutLike };

// 重新导出常量(测试可能用到)
export { MAX_GENERATION_STEPS };
