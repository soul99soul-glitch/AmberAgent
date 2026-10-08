// runStageSupervisorLoop + collectRun + buildPrompt + buildWriterReminder
// 照搬 Android DeepReadAgentRunManager.kt:412-527 (supervisor loop) + 529-570 (collectRun)
//              + 661-855 (buildPrompt + reminder)
//
// 设计 0.1:无 coverageReport 参数(supplementWritten 永远 true)。
// collectRun 是 AiClient.generateText 的薄封装;supervisor loop 用可注入的 collectRunFn
// 方便测试(mock AiClient)。timeout 用 Promise.race + setTimeout 模拟 withTimeout。

import type { UIMessage } from './message.ts';
import { makeUserMessage, latestAssistantText } from './message.ts';
import { structuredStageSchema, structuredArticleBody, structuredStageFailureReason, writeStructuredStage } from './structured_stage.ts';
import type { AbortControllerLike, AbortSignalLike } from '../platform/runtime_api.ts';
import type { StructuredStageFailure } from './structured_stage.ts';
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
  writerMode?: 'tools' | 'structured';
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
    lines.push('该 URL 已在预抓阶段尝试读取，只使用下面实际提供的正文；未读取的内容不得补写。');
  }
  lines.push('');
  appendArticlePlan(lines, p.articlePlan);
  lines.push('');
  appendEvidenceCards(lines, p.stageEvidence, p.evidencePack, `本段证据包（全局共 ${p.evidencePack.allSources.length} 条，本轮只给最相关 ${p.stageEvidence.length} 条）`, STAGE_EXCERPT_LIMIT[p.stage], p.writerMode === 'structured');
  lines.push('');
  appendArticleContext(lines, p.existingOutput);
  lines.push('');
  appendDeadlineGuidance(lines, p.stage, p.stageTimeoutMs, p.writerMode);
  lines.push('');
  lines.push('## 研究顺序');
  lines.push('1. 本地 harness 已经完成来源扩展、去重、分桶和结构规划。你只处理当前小目标。');
  lines.push('2. 研究资料以本段证据包为准，不补充外部搜索或抓取。');
  lines.push('3. 关键事实缺失、来源互相矛盾或缺少反方证据时，明确标注不确定；不得把未读取内容写成事实。');
  lines.push('4. 本轮只生成目标段落，不要改写或重写未列入目标的段落。');
  if (p.writerMode === 'structured') {
    lines.push('5. 只返回当前段落 JSON，不调用工具，不输出解释、Markdown 长文或其他段落。');
    lines.push(`本段 JSON 格式：${structuredStageSchema(p.stage)}`);
    if (p.stage === 'EXTENDED_READING') lines.push('没有真实外部链接时不要编造。可提供来源支撑的 diagram：3-6个节点，type/title/nodes[{id,label}]/edges[{from,to}]；没有候选图时 hero_image_url 留空。');
  } else {
    lines.push('5. 完成研究后立即调用对应 writer tool：');
    lines.push(`   - ${WRITER_TOOL_NAMES[p.stage]}：${STAGE_LABELS[p.stage]}`);
    lines.push('6. 本轮只暴露目标段 writer tool；如果你输出自由文本但没调工具，系统会尝试把自由文本转换成基础稿。');
    lines.push('7. 图片只能从 image_candidates 中选择；本轮如果没有视觉 writer，就在目标段 references/links 中保留可用来源。');
    lines.push('8. 全部 writer 完成后，直接调用 deep_read_finish。');
  }
  if (p.writerMode === 'structured') {
    lines.push('## 已完成段落（只读，不重新输出）');
    lines.push(JSON.stringify(structuredArticleBody(p.existingOutput)));
  }
  lines.push('');
  lines.push('## 段落要求');
  appendStageRequirement(lines, p.stage);
  if (p.writerMode === 'structured') appendEditorialRequirement(lines, p.stage);
  lines.push('- 视觉：头图必须来自候选池且 confidence=hero；inline 候选只能作为正文图。不得提交任意 URL、站点 logo、favicon、媒体图标或头像。');
  if (p.writerMode !== 'structured') {
  lines.push('- 图解：只提交 3-6 个短节点的 diagram spec，节点 label 控制在约 30 字内；流程/因果可保留少量关键跨节点关系，但避免网状交叉。禁止 raw SVG/HTML/JS/外链资源。不需要就隐藏。');
  }
  lines.push('');
  lines.push(p.writerMode === 'structured' ? '只输出本段的 JSON 对象，系统会核验并合并当前段落。'
    : '正文输出不会被 UI 消费。不要输出完整 JSON，不要写 Markdown 长文作为最终答案。');
  return lines.join('\n');
};

const appendEditorialRequirement = (lines: string[], stage: DeepReadGenerationStage): void => {
  if (stage === 'OVERVIEW') {
    lines.push('- bottom_line 用不超过40字的一句话讲清发生了什么及最重要的意义；summary 不重复它。hero_image_url 只选候选池真实头图，没有可靠图片留空。');
  } else if (stage === 'NARRATIVE') {
    lines.push('- core_points 给3-5条消化来源后的关键判断：supporting 解释依据及为什么重要，sources 填来源标题的 [n] 一基编号，不要写来源清单或重复时间轴。');
    lines.push('- timeline 给4-7条直接相关事件，覆盖早期背景、导火索、当前事件、后续影响；date 只写日期或时间，is_highlight 只标1-3个转折点，why 不超过60字说明转折原因，其他事件留空。');
    lines.push('- diagram 只在非线性当事方关系、系统结构或多方对比无法用时间轴讲清时输出，type 取 stakeholder_map|system_structure|comparison_matrix，3-6个节点；线性因果和流程由时间轴承担，不需要则省略整个字段。');
  } else if (stage === 'ANALYSIS') {
    lines.push('- core_dispute 用问句点明争议。perspectives 给3-5个不同当事方，holder 不超过12字，interest 不超过30字，viewpoint 写立场和理由，sources 填 [n] 一基编号。');
    lines.push('- quote 必须是来源中具名人物或机构的原话，文章标题、报道摘要及网友评论不算；quote_by 不超过20字写姓名或机构与身份，无可靠原话两项都留空。');
    lines.push('- impacts 给2-4条影响，target 写受影响对象，horizon 只取 short 或 long，effect 不超过80字；watch 给1-3项观察节点或指标，写清看什么及为什么，每项不超过50字。');
    lines.push('- uncertainties 给0-4条待确认说法，claim 不超过60字，status 取 single_source、conflicting 或 pending_official；都确认则留空数组。');
  }
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

const appendDeadlineGuidance = (lines: string[], stage: DeepReadGenerationStage, stageTimeoutMs: number, writerMode?: 'tools' | 'structured'): void => {
  const stageSeconds = Math.max(1, Math.floor(stageTimeoutMs / 1000));
  lines.push('## 时间预算（硬约束）');
  lines.push(`- 本段运行预算约 ${stageSeconds} 秒。`);
  lines.push(writerMode === 'structured' ? '- 第一优先级是返回合法的本段 JSON，不输出长文或 Markdown 草稿。'
    : `- 你必须把第一优先级放在调用 ${WRITER_TOOL_NAMES[stage]}；不要先输出长文、完整 JSON 或 Markdown 草稿。`);
  lines.push('- 预抓证据包是本轮材料。关键事实缺失或互相矛盾时保留不确定性，不补写未读取内容。');
  lines.push('- 如果证据不够完整，先基于现有证据写保守版本；不要为了补全而耗尽本段预算。');
  if (stage === 'EXTENDED_READING') {
    lines.push(writerMode === 'structured' ? '- 从证据包挑选真实来源链接与允许的视觉资料，返回扩展阅读 JSON。'
      : '- 扩展阅读不是长文写作：从本段证据包挑选 4-8 条真实来源链接，必要时带 image_assets，然后立即调用 writer tool。');
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
  pack: DeepReadEvidencePack,
  title: string,
  excerptLimit: number,
  numbered: boolean = false,
): void => {
  lines.push(`## ${title}`);
  if (cards.length === 0) {
    lines.push('- 本段没有可用证据（理论上不会到这里）。');
    return;
  }
  for (const card of cards) {
    const sourceNumber = pack.sourceNumbers?.[card.sourceId] ?? pack.allSources.findIndex(source => source.sourceId === card.sourceId) + 1;
    lines.push(numbered ? `### [${sourceNumber}] ${card.sourceId}. ${card.title}` : `### ${card.sourceId}. ${card.title}`);
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
  writerMode?: 'tools' | 'structured';
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
    writerMode: ctx.writerMode,
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
  let structuredFailure: StructuredStageFailure | null = null;

  try {
    for (let pass = 0; pass < MAX_SUPERVISOR_PASSES; pass++) {
      if (ctx.signal !== undefined && ctx.signal.aborted) {
        // 调度层 abort:以 abort 语义上抛(generateStates 的 isAbortError 重抛路径)
        throw new Error('aborted');
      }
      const beforeWrites = writer.requiredWriteCount;
      try {
        messages = await withTimeout(
          stageTimeoutMs,
          (stageSignal: AbortSignalLike): Promise<UIMessage[]> => ctx.collectRun(
            messages,
            `深度阅读 ${STAGE_LABELS[stage]}`,
            stageSignal,
            ctx.writerMode === 'structured' ? [] : writer.tools(new Set<DeepReadGenerationStage>([stage])),
          ),
          ctx.signal,
        );
      } catch (error) {
        if (ctx.writerMode !== 'structured' || pass + 1 >= MAX_SUPERVISOR_PASSES || ctx.signal?.aborted || isAbortError(error)) throw error;
        messages = [...messages, makeUserMessage(`上次调用失败：${String(error).slice(0, 160)}。请重新只输出本段 JSON：${structuredStageSchema(stage)}`)];
        continue;
      }
      if (ctx.writerMode === 'structured') {
        if (ctx.signal?.aborted) { const error = new Error('aborted'); error.name = 'AbortError'; throw error; }
        const text = latestAssistantText(messages);
        const written = await writeStructuredStage(writer, stage, text);
        structuredFailure = written ? null : structuredStageFailureReason(stage, text);
      }
      const stageReady = statusOf(writer.current(), stage) === 'READY';
      // 0.1: 无 coverageReport,supplementWritten 永远 true
      const supplementWritten = writer.requiredWriteCount > initialRequiredWrites;
      if (stageReady && supplementWritten) {
        return { stage, ready: true, outcome: 'ready', finalOutput: writer.current() };
      }
      // 该 pass 没新写入 → 加 reminder
      if (writer.requiredWriteCount === beforeWrites) {
        messages = [...messages, makeUserMessage(ctx.writerMode === 'structured'
          ? `${structuredFailure?.retryInstruction ?? ''} 请只返回此 JSON 对象，不要解释：${structuredStageSchema(stage)}` : buildWriterReminder(stage, pass))];
      }
    }
    // 2 pass 都没成功
    if (statusOf(writer.current(), stage) !== 'READY') {
      const recovered = ctx.writerMode !== 'structured' && await ctx.tryFallback(stage, messages, stageSources, 'missing writer tool');
      if (recovered) return { stage, ready: true, outcome: 'fallback_recovered', finalOutput: writer.current() };
      const failMsg = stageFailureMessage(stage, new Error(ctx.writerMode === 'structured'
        ? structuredFailure?.message ?? '模型没有返回本段的完整内容，请重试。' : 'missing writer tool'), stageTimeoutMs);
      writer.markFailed(stage, failMsg);
      return { stage, ready: false, outcome: 'failed', finalOutput: writer.current() };
    }
    return { stage, ready: true, outcome: 'ready', finalOutput: writer.current() };
  } catch (error) {
    if (isAbortError(error)) throw error;
    const isTimeout = isTimeoutError(error);
    const reason = isTimeout ? 'timeout' : isErrorLike(error) && isDeepReadTimeoutLike(error) ? 'provider timeout' : 'failure';
    const recovered = ctx.writerMode !== 'structured' && await ctx.tryFallback(stage, messages, stageSources, reason);
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
