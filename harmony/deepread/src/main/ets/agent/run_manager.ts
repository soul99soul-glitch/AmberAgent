// DeepReadRunManager — 照搬 Android DeepReadAgentRunManager.kt
// run / runSection / generateStages / createRunContext / finishIfPossible
//
// 纯 node 可测:依赖全部注入(prefetcher / researchHarness generateArticlePlan /
// AiClient / repository mock)。scheduler / topicMutex 由 scheduler.ts 调度层处理。
//
// 设计 0.1:isComplete 不依赖 verificationState。stage 顺序 if(OVERVIEW→NARRATIVE→
// ANALYSIS→EXTENDED_READING),不是循环。空 prefetch → hard-fail(P1-9)。

import type { SectionWriterTools } from './section_writer_tools.ts';
import { createSectionWriterTools } from './section_writer_tools.ts';
import { runStageSupervisorLoop } from './supervisor_loop.ts';
import type { CollectRunFn } from './supervisor_loop.ts';
import { tryFallbackAfterStageFailure } from './try_fallback.ts';
import type { SourcePrefetcher, DeepReadSource } from '../research/source_prefetcher.ts';
import { buildEvidencePack } from '../research/evidence_pack.ts';
import type { DeepReadEvidencePack, DeepReadArticlePlan } from '../research/evidence_pack.ts';
import { synthesisTemplate, parseSynthesisPick, synthesisPickPrompt, synthesisPrompt, parseSynthesisArticle } from '../domain/synthesis_templates.ts';
import { makeSystemMessage, makeUserMessage, latestAssistantText } from './message.ts';
import { generateArticlePlan, fallbackPlan } from '../research/article_plan.ts';
import type { ScoredImageCandidate } from '../research/image_scorer.ts';
import type { AiClient } from '../platform/ai_client.ts';
import type { AbortSignalLike } from '../platform/runtime_api.ts';
import type { DeepReadTemplateSnapshot, DeepReadOutput, ReadingLink } from '../domain/models.ts';
import { sourceInputs, mergeCollectedSources, generationSources } from '../domain/input_sources.ts';
import type { DeepReadCollectionIssue, DeepReadInputSource } from '../domain/input_sources.ts';
import { makeEmptyDeepReadOutput } from '../domain/models.ts';
import {
  statusOf, sectionsReady, isComplete, withInferredSectionStates, hasReadableArticle, hasDisplayableDeepReadOutput, withSectionStatus,
} from '../domain/helpers.ts';
import type { DeepReadGenerationStage, DeepReadGenerationPhase } from '../domain/enums.ts';
import { STAGE_ORDER, STAGE_LABELS } from '../domain/enums.ts';

export interface RunResult {
  ok: boolean;
  output: DeepReadOutput;
  error: string | null;     // ok=false 时填
}

// createRunContext 产物 — 对应 Android DeepReadRunContext
export interface RunContext {
  writerMode: 'tools' | 'structured';
  writer: SectionWriterTools;
  evidencePack: DeepReadEvidencePack;
  articlePlan: DeepReadArticlePlan;
  topicTitle: string;
  seedUrl: string | null;
  playbookMarkdown: string;
  scrapeWebAvailable: boolean;
  collectRun: CollectRunFn;
  todayIso: string;
}

// 依赖注入(测试 mock)
export interface RunManagerDeps {
  onProgress?: (stage: DeepReadGenerationPhase, label: string) => void;
  writerMode?: 'tools' | 'structured';
  templateSnapshot?: DeepReadTemplateSnapshot;
  prefetcher: SourcePrefetcher;
  collectRun: CollectRunFn;              // 真实由 AiClient.generateText 装配
  aiClient: AiClient;                    // 用于 generateArticlePlan
  model: string;
  playbookMarkdown: string;
  /** 可选:持久化 save/get(真实由 Repository;测试可注入内存) */
  repository?: {
    get: (topicId: string, title: string) => DeepReadOutput | null;
    save: (topicId: string, title: string, output: DeepReadOutput) => Promise<void> | void;
    clear: (topicId: string) => void;
  };
  /** 注入 clock 避免测试不稳定 */
  nowIso: () => string;
  /** 模型是否暴露 scrape_web(本端口 search provider 恒 false;留接口) */
  scrapeWebAvailable?: boolean;
  /**
   * 可选:writer 创建回调(测试用 — 让 collectRun 能访问 writer 触发写入)。
   * createRunContext 创建 writer 后调此回调。生产代码不传。
   */
  onWriterCreated?: (writer: SectionWriterTools) => void;
}

// ===== abort 语义(取消贯穿 R20) =====
// 判定只认 signal.aborted 与 AbortError.name,不再用 /abort|cancel/i 宽判 message:
// message 可能来自业务文案/网络错误,宽判会把普通失败误归一成取消。

export const isAborted = (signal?: AbortSignalLike): boolean =>
  signal !== undefined && signal.aborted;

export const makeAbortedError = (): Error => {
  const e = new Error('aborted');
  e.name = 'AbortError';
  return e;
};

const isAbortErrorName = (e: unknown): boolean =>
  e instanceof Error && e.name === 'AbortError';

// ===== finishIfPossible(照搬 Android :612-616) =====

const finishIfPossible = (writer: SectionWriterTools): DeepReadOutput => {
  writer.executeFinish();
  return writer.current();
};

// ===== createRunContext(照搬 Android :291-411) =====
// 注意:简化 — 不解析 model abilities / 不持久化 COLLECTING 中间态(测试不依赖;
// 真实 Repository 持久化由 scheduler 层做)。空 prefetch → Result.failure(P1-9)。

const sourceUrlsForRun = (seedUrl: string | null, seedUrls: string[] | undefined, cachedOutput: DeepReadOutput | null): string[] =>
  Array.from(new Set([seedUrl ?? '', ...(seedUrls ?? cachedOutput?.inputSourceUrls ?? [])]
    .map(url => url.trim()).filter(url => url.length > 0)));

const numberedSourceLinks = (sources: DeepReadSource[]): ReadingLink[] => sources.map(source => ({
  sourceId: source.sourceId, title: source.title, url: source.url, source: source.source, publishedAt: source.publishedAt,
}));

const stableGenerationSources = (inputs: DeepReadInputSource[]): DeepReadSource[] => {
  const usableInputs = inputs.filter(source => source.status === 'ready' && source.content.trim().length > 0);
  return generationSources(inputs).map((source, index) => {
    const input = usableInputs[index];
    const sourceId = (input.kind === 'web' || input.kind === 'search') && source.url.length > 0
      ? `url:${source.url}` : `input:${input.id}`;
    return { ...source, sourceId };
  });
};

const sameNumberedSource = (left: ReadingLink, right: ReadingLink): boolean => {
  const localIdentity = left.sourceId?.startsWith('input:') || right.sourceId?.startsWith('input:');
  // The old prefetch counter restarts on cold launch: src-1 is not a web identity.
  if (!localIdentity && left.url.length > 0 && right.url.length > 0) return left.url === right.url;
  if (left.sourceId !== undefined && right.sourceId !== undefined) {
    return left.sourceId === right.sourceId || (left.url === right.url &&
      (`input:${left.sourceId}` === right.sourceId || left.sourceId === `input:${right.sourceId}`));
  }
  return left.url === right.url && left.title === right.title && left.source === right.source;
};

// A continuation can collect/reorder inputs, but existing citations keep their
// article-owned numbers. Only a forced replacement starts a new source list.
const stableSourceLinks = (existing: ReadingLink[], incoming: ReadingLink[]): ReadingLink[] => {
  const result = existing.slice();
  for (const source of incoming) {
    if (!result.some(saved => sameNumberedSource(saved, source))) result.push(source);
  }
  return result;
};

export const createRunContext = async (
  deps: RunManagerDeps,
  topicId: string,
  topicTitle: string,
  seedUrl: string | null,
  force: boolean,
  signal?: AbortSignalLike,
  seedUrls?: string[],
  templateSnapshot?: DeepReadTemplateSnapshot,
  deferPlanning: boolean = false,
): Promise<{ ok: true; context: RunContext } | { ok: false; error: string }> => {
  // 0. 取消一旦发生,不再进入任何抓取/规划(避免取消后仍工作)
  if (isAborted(signal)) return { ok: false, error: 'aborted' };
  const cachedOutput = deps.repository?.get(topicId, topicTitle) ?? null;
  const inputSourceUrls = sourceUrlsForRun(seedUrl, seedUrls, cachedOutput);
  const primarySeedUrl = inputSourceUrls[0] ?? null;
  // 1. prefetch:没有正文资料时停止,不能把话题标题当作研究来源。
  // signal 贯穿:provider search / HTTP fetch 真实取消;prefetch 内部也会提前返回
  let prefetchedSources: DeepReadSource[] = [];
  let allCollected: DeepReadSource[] = [];
  let collectionIssues: DeepReadCollectionIssue[] = [];
  // Standalone composer inputs are explicit. Legacy forced replacement still
  // requires fresh research; automatic cached collection must not make an empty
  // replacement succeed when an old owner commits during admission.
  const hasUserInputs = cachedOutput?.inputText !== undefined || cachedOutput?.inputUrlsText !== undefined;
  const savedInputs = (!force || hasUserInputs ? cachedOutput?.inputSources : undefined)
    ?? sourceInputs('', inputSourceUrls.join('\n'));
  const pendingUrls = force ? inputSourceUrls : inputSourceUrls.filter(url => !savedInputs.some(source => source.url === url
    && source.status === 'ready' && (source.kind === 'web' || source.kind === 'search')));
  const canSaveInputCollection = cachedOutput !== null && cachedOutput.inputSources !== undefined
    && !hasDisplayableDeepReadOutput(cachedOutput) && !hasAnyReady(cachedOutput);
  // Composer inputs already exist on disk. Mark collection before network work so
  // a cold launch offers explicit continuation instead of silently creating a new run.
  if (deps.repository !== undefined && canSaveInputCollection && cachedOutput !== null) {
    await deps.repository.save(topicId, topicTitle, { ...cachedOutput,
      generationPhase: 'COLLECTING', generationComplete: false });
    if (isAborted(signal)) return { ok: false, error: 'aborted' };
  }
  try {
    // Successful original text is reused even when a website is temporarily unavailable.
    // An empty search registry still allows user text/files to generate.
    deps.onProgress?.('COLLECTING', '收集来源');
    prefetchedSources = await deps.prefetcher.collect(topicId, topicTitle, pendingUrls[0] ?? null,
      force, signal, pendingUrls, (sources, issues): void => { allCollected = sources; collectionIssues = issues; });
  } catch (_e) {
    // 抓取失败与空资料使用同一个失败出口;取消由下方 signal 复查。
  }
  if (isAborted(signal)) return { ok: false, error: 'aborted' };
  const inputSources = mergeCollectedSources(savedInputs,
    allCollected.length > 0 ? allCollected : prefetchedSources, collectionIssues, force);
  const capturedTemplate = !force && cachedOutput?.templateSnapshot !== undefined
    ? cachedOutput.templateSnapshot : templateSnapshot ?? deps.templateSnapshot ?? cachedOutput?.templateSnapshot;
  const inputFields = { templateSnapshot: capturedTemplate, templateId: capturedTemplate?.id ?? cachedOutput?.templateId,
    inputSourceUrls, inputSources,
    inputText: cachedOutput?.inputText, inputUrlsText: cachedOutput?.inputUrlsText };
  // Persist the collection outcome before planning: failed runs retain their inputs too.
  if (deps.repository !== undefined && canSaveInputCollection) {
    await deps.repository.save(topicId, topicTitle, { ...(cachedOutput ?? makeEmptyDeepReadOutput()), ...inputFields });
  }
  prefetchedSources = deps.writerMode === 'structured' ? stableGenerationSources(inputSources) : generationSources(inputSources);
  if (prefetchedSources.length === 0) {
    return { ok: false, error: '未获取到可用于深度阅读的资料，请检查来源或搜索配置后重试。' };
  }

  // 2. evidence pack
  const evidencePack = buildEvidencePack(prefetchedSources);
  const sourceLinks = deps.writerMode === 'structured'
    ? stableSourceLinks(force ? [] : cachedOutput?.sources ?? [], numberedSourceLinks(prefetchedSources)) : [];
  if (deps.writerMode === 'structured') {
    const sourceNumbers: Record<string, number> = {};
    for (const source of prefetchedSources) {
      const link = numberedSourceLinks([source])[0];
      sourceNumbers[source.sourceId] = sourceLinks.findIndex(saved => sameNumberedSource(saved, link)) + 1;
    }
    evidencePack.sourceNumbers = sourceNumbers;
  }

  // 3. image candidates 池(传给 writer)
  const imageCandidates: ScoredImageCandidate[] = prefetchedSources.flatMap(s => s.imageCandidates);

  // 4. writer(初始 output 从 repository 取或空)
  const initialOutput: DeepReadOutput = {
    ...(force ? makeEmptyDeepReadOutput() : withInferredSectionStates(cachedOutput ?? makeEmptyDeepReadOutput())), ...inputFields,
    ...(deps.writerMode === 'structured' ? { sources: sourceLinks } : {}),
  };
  const writer = createSectionWriterTools({
    topicId, topicTitle, imageCandidates, initialOutput,
  });
  if (deps.onWriterCreated) deps.onWriterCreated(writer);

  // 5. article plan(1 次 LLM;失败 → fallbackPlan;signal 贯穿真实取消)
  if (isAborted(signal)) return { ok: false, error: 'aborted' };
  if (!deferPlanning) deps.onProgress?.('PLANNING', '规划结构');
  const articlePlan = deferPlanning ? fallbackPlan(topicTitle, evidencePack)
    : await generateArticlePlan(deps.aiClient, deps.model, topicTitle, evidencePack, deps.playbookMarkdown, signal);
  if (isAborted(signal)) return { ok: false, error: 'aborted' };

  return {
    ok: true,
    context: {
      writerMode: deps.writerMode ?? 'tools',
      writer,
      evidencePack,
      articlePlan,
      topicTitle,
      seedUrl: primarySeedUrl,
      playbookMarkdown: deps.playbookMarkdown,
      scrapeWebAvailable: deps.scrapeWebAvailable ?? false,
      collectRun: deps.collectRun,
      todayIso: deps.nowIso(),
    },
  };
};

// Existing magazine prose always continues as magazine; an auto preference is not permission to replace it.
const requestedSynthesis = (deps: RunManagerDeps, cached: DeepReadOutput | null, force: boolean,
  snapshot?: DeepReadTemplateSnapshot): boolean => {
  if (!force && cached?.templateArticle !== undefined) return true;
  if (!force && cached !== null && (cached.summary.trim().length > 0 || (cached.bottomLine?.trim().length ?? 0) > 0 || (cached.timeline?.length ?? 0) > 0
    || (cached.corePoints?.length ?? 0) > 0 || (cached.analysis.coreDispute?.trim().length ?? 0) > 0
    || (cached.analysis.implications?.trim().length ?? 0) > 0 || cached.analysis.perspectives.length > 0
    || cached.analysis.quotes.length > 0 || (cached.impacts?.length ?? 0) > 0 || (cached.watch?.length ?? 0) > 0
    || hasAnyReady(cached))) return false;
  const selected = !force && cached?.templateSnapshot !== undefined ? cached.templateSnapshot
    : snapshot ?? deps.templateSnapshot ?? cached?.templateSnapshot;
  return synthesisTemplate(selected?.id ?? cached?.templateId) !== null;
};

const generateSynthesis = async (deps: RunManagerDeps, context: RunContext, topicId: string, topicTitle: string,
  force: boolean, signal?: AbortSignalLike): Promise<RunResult | null> => {
  const retained = deps.repository?.get(topicId, topicTitle) ?? null;
  const preserve = retained !== null && (hasReadableArticle(retained) || hasAnyReady(retained));
  let output = context.writer.current();
  let selected = synthesisTemplate(!force && retained?.templateArticle !== undefined
    ? retained.templateArticle.template : output.templateSnapshot?.id ?? output.templateId);
  const aborted = (): RunResult => ({ ok: false, output: deps.repository?.get(topicId, topicTitle) ?? output, error: 'aborted' });
  let persistenceFailed = false;
  const save = async (next: DeepReadOutput): Promise<void> => {
    if (isAborted(signal)) throw makeAbortedError();
    try { await persist(deps, topicId, topicTitle, next); } catch (error) { persistenceFailed = true; throw error; }
    output = next;
  };
  try {
    if (selected?.id === 'deepread_auto') {
      deps.onProgress?.('PLANNING', '选择生成模板');
      if (!preserve) await save({ ...output, generationPhase: 'PLANNING', generationComplete: false });
      const messages = await deps.collectRun([makeSystemMessage('你是深度阅读编辑。只返回合法 JSON。'),
        makeUserMessage(synthesisPickPrompt(topicTitle, context.evidencePack.allSources))], '选择生成模板', signal, []);
      if (isAborted(signal)) return aborted();
      selected = parseSynthesisPick(latestAssistantText(messages));
    }
    if (selected === null) {
      // Persist the auto decision before classic planning, so a retry never picks a second time.
      const snapshot: DeepReadTemplateSnapshot = { id: 'none', name: '默认排版', kind: 'native', html: null,
        capturedAt: output.templateSnapshot?.capturedAt ?? Date.now() };
      output = { ...output, templateSnapshot: snapshot, templateId: snapshot.id };
      if (!preserve) await save(output);
      context.writer = createSectionWriterTools({ topicId, topicTitle, imageCandidates: context.evidencePack.allSources.flatMap(source => source.imageCandidates), initialOutput: output });
      if (deps.onWriterCreated) deps.onWriterCreated(context.writer);
      deps.onProgress?.('PLANNING', '规划结构');
      context.articlePlan = await generateArticlePlan(deps.aiClient, deps.model, topicTitle, context.evidencePack, deps.playbookMarkdown, signal);
      return null;
    }
    const snapshot: DeepReadTemplateSnapshot = { id: selected.id, name: selected.name, kind: 'synthesis', html: null,
      capturedAt: output.templateSnapshot?.capturedAt ?? Date.now() };
    output = { ...output, templateSnapshot: snapshot, templateId: selected.id,
      generationPhase: 'WRITING', generationComplete: false, sectionStates: {}, sectionQualities: {} };
    deps.onProgress?.('WRITING', `生成${selected.name}`);
    if (!preserve) await save(output);
    const messages = await deps.collectRun([makeSystemMessage('你是深度阅读编辑，只基于来源事实，返回指定结构的中文 JSON。'),
      makeUserMessage(synthesisPrompt(selected, topicTitle, context.evidencePack.allSources))], `生成${selected.name}`, signal, []);
    if (isAborted(signal)) return aborted();
    const article = parseSynthesisArticle(latestAssistantText(messages), selected, topicTitle, context.evidencePack.allSources);
    if (article === null) throw new Error(`模型没有按「${selected.name}」模板返回完整内容，请重试。`);
    const completed: DeepReadOutput = { ...output, templateArticle: article, summary: article.lede,
      sources: article.sources.map(source => ({ title: source.title, url: source.url ?? '', source: source.site, publishedAt: null })),
      generationPhase: 'COMPLETE', generationComplete: true, sectionStates: {}, sectionQualities: {} };
    if (isAborted(signal)) return aborted();
    deps.onProgress?.('VERIFYING', '校验并保存');
    await save(completed);
    deps.onProgress?.('COMPLETE', '已完成');
    return { ok: true, output: completed, error: null };
  } catch (error) {
    if (persistenceFailed) throw error;
    if (isAborted(signal) || isAbortErrorName(error)) return aborted();
    const message = error instanceof Error ? error.message : String(error);
    if (preserve && retained !== null) return { ok: false, output: retained, error: message };
    const failed = withSectionStatus({ ...output, templateArticle: undefined, generationPhase: 'IDLE', generationComplete: false }, 'OVERVIEW', 'FAILED', message);
    await save(failed);
    return { ok: false, output: failed, error: message };
  }
};

// ===== generateStages(照搬 Android :194-289) =====

export const generateStages = async (
  deps: RunManagerDeps,
  topicId: string,
  topicTitle: string,
  stages: DeepReadGenerationStage[],
  seedUrl: string | null,
  force: boolean,
  planningPhase: DeepReadGenerationPhase = 'PLANNING',
  markCollecting: boolean = true,
  signal?: AbortSignalLike,
  seedUrls?: string[],
  templateSnapshot?: DeepReadTemplateSnapshot,
): Promise<RunResult> => {
  // 重生成只在完整新稿保存成功时替换旧稿;阶段缓存仍用于首次生成和续跑。
  const cachedOutput = force ? deps.repository?.get(topicId, topicTitle) ?? null : null;
  const previousOutput = cachedOutput !== null && (hasReadableArticle(cachedOutput) || hasAnyReady(withInferredSectionStates(cachedOutput))) ? cachedOutput : null;
  // 统一 aborted 结果(返回取消前已持久化的当前态,不写新状态)
  const abortedResult = (): RunResult => ({
    ok: false,
    output: deps.repository?.get(topicId, topicTitle) ?? makeEmptyDeepReadOutput(),
    error: 'aborted',
  });
  if (isAborted(signal)) return abortedResult();

  // createRunContext(空 prefetch → failure)
  const synthesis = requestedSynthesis(deps, deps.repository?.get(topicId, topicTitle) ?? null, force, templateSnapshot);
  const ctxResult = await createRunContext(deps, topicId, topicTitle, seedUrl, force, signal, seedUrls, templateSnapshot, synthesis);
  if (ctxResult.ok === false) {
    // ArkTS 对 discriminated union 的 narrow 较弱,显式提取 error
    const failMsg: string = (ctxResult as { ok: false; error: string }).error;
    if (failMsg === 'aborted' || isAborted(signal)) return abortedResult();
    const oldOutput = previousOutput ?? deps.repository?.get(topicId, topicTitle) ?? null;
    // 已有可读内容或 READY 分段时保持原稿;首次无资料失败需要留真实历史入口。
    if (oldOutput !== null && (hasReadableArticle(oldOutput) || hasAnyReady(withInferredSectionStates(oldOutput)))) {
      return { ok: false, output: oldOutput, error: failMsg };
    }
    const failedTemplate = !force && oldOutput?.templateSnapshot !== undefined ? oldOutput.templateSnapshot
      : templateSnapshot ?? deps.templateSnapshot ?? oldOutput?.templateSnapshot;
    const failedOutput = withSectionStatus({ ...makeEmptyDeepReadOutput(),
      templateSnapshot: failedTemplate,
      templateId: failedTemplate?.id ?? oldOutput?.templateId,
      inputSources: oldOutput?.inputSources, inputText: oldOutput?.inputText, inputUrlsText: oldOutput?.inputUrlsText,
      inputSourceUrls: sourceUrlsForRun(seedUrl, seedUrls, oldOutput),
    }, 'OVERVIEW', 'FAILED', failMsg);
    if (isAborted(signal)) return abortedResult();
    // 位于生成 catch 外:首次失败记录写入被拒绝时也必须真实上抛。
    await persist(deps, topicId, topicTitle, failedOutput);
    if (isAborted(signal)) return abortedResult();
    return { ok: false, output: failedOutput, error: failMsg };
  }
  const context = (ctxResult as { ok: true; context: RunContext }).context;
  if (synthesis) {
    const result = await generateSynthesis(deps, context, topicId, topicTitle, force, signal);
    if (result !== null) return result;
    if (isAborted(signal)) return abortedResult();
  }

  // 持久化守卫:signal 已取消时绝不落盘(避免取消后仍写 RUNNING/COLLECTING)
  let persistenceFailed = false;
  const saveGuarded = async (out: DeepReadOutput): Promise<void> => {
    if (isAborted(signal)) throw makeAbortedError();
    if (previousOutput !== null && !isComplete(out)) return;
    try {
      await persist(deps, topicId, topicTitle, out);
    } catch (error) {
      persistenceFailed = true;
      throw error;
    }
    // 完整稿已经成功提交即算完成;该提交期间的取消不能把真实成功降为中断。
    if (!isComplete(out) && isAborted(signal)) throw makeAbortedError();
  };

  try {
    // markCollecting → 写 COLLECTING phase(真实持久化)
    // 放在 try 内:取消恰好发生在此窗口时由 catch 归一为 abortedResult(P3-1),
    // 直调 run_manager.run 也不以 rejection 出栈。
    if (markCollecting && deps.repository) {
      await saveGuarded({ ...context.writer.current(), generationPhase: 'COLLECTING', generationComplete: false });
    }
    // 顺序 if(OVERVIEW → NARRATIVE → ANALYSIS → EXTENDED_READING)
    if (stages.includes('OVERVIEW')) {
      await runStage(deps, context, topicId, topicTitle, 'OVERVIEW', signal, saveGuarded);
    }
    if (stages.includes('NARRATIVE')) {
      await runStage(deps, context, topicId, topicTitle, 'NARRATIVE', signal, saveGuarded);
    }
    if (stages.includes('ANALYSIS')) {
      await runStage(deps, context, topicId, topicTitle, 'ANALYSIS', signal, saveGuarded);
    }
    if (stages.includes('EXTENDED_READING')) {
      if (context.writerMode === 'structured') {
        if (isAborted(signal)) throw makeAbortedError();
        await saveGuarded(context.writer.writeSources(context.writer.current().sources ?? numberedSourceLinks(context.evidencePack.allSources)));
      } else {
        await runStage(deps, context, topicId, topicTitle, 'EXTENDED_READING', signal, saveGuarded);
      }
    }

    // finish 判定
    const allTargetedReady = stages.every(s => statusOf(context.writer.current(), s) === 'READY');
    const allSectionsReady = sectionsReady(context.writer.current());
    if (allTargetedReady && allSectionsReady) {
      if (isAborted(signal)) return abortedResult();
      deps.onProgress?.('VERIFYING', '校验并保存');
      const finished = finishIfPossible(context.writer);
      await saveGuarded(finished);
      if (isComplete(finished)) deps.onProgress?.('COMPLETE', '已完成');
      return { ok: true, output: finished, error: null };
    }
    if (allTargetedReady) {
      const out = context.writer.markPhase('IDLE');
      if (previousOutput !== null) {
        return { ok: false, output: previousOutput, error: '新文章未完整生成，已保留原文章，请重试。' };
      }
      await saveGuarded(out);
      return { ok: true, output: out, error: null };
    }
    // 未达 READY 的 stage → markFailed
    const missing = stages.filter(s => statusOf(context.writer.current(), s) !== 'READY');
    for (const stage of missing) {
      if (statusOf(context.writer.current(), stage) !== 'FAILED') {
        context.writer.markFailed(stage, context.writerMode === 'structured'
          ? '模型未返回合法且完整的段落 JSON，该段未写入。' : 'Agent 未按约定调用分段写入工具，该段未写入。');
      }
    }
    const out = context.writer.markPhase('IDLE');
    if (previousOutput !== null) {
      return { ok: false, output: previousOutput, error: '新文章未完整生成，已保留原文章，请重试。' };
    }
    await saveGuarded(out);
    return { ok: out.extendedReading.length > 0 || hasAnyReady(out), output: out, error: hasAnyReady(out) ? null : '深度阅读生成失败' };
  } catch (error) {
    // 写入失败必须由调用者呈现,不能当作生成失败再保存一次并宣称成功。
    if (persistenceFailed) throw error;
    // 取消 → 统一 aborted 结果(以 signal 状态判定,不宽判 error.message)
    if (isAborted(signal) || isAbortErrorName(error)) return abortedResult();
    const msg = error instanceof Error ? error.message : String(error);
    if (previousOutput !== null) return { ok: false, output: previousOutput, error: msg };
    for (const stage of stages) {
      if (statusOf(context.writer.current(), stage) !== 'READY') {
        context.writer.markFailed(stage, msg);
      }
    }
    try {
      const out = context.writer.markPhase('IDLE');
      await saveGuarded(out);
      return { ok: hasAnyReady(out), output: out, error: hasAnyReady(out) ? null : msg };
    } catch (persistError) {
      // 落盘守卫因取消抛出 → 统一 aborted;其余落盘错误按原样上抛(不误报取消)
      if (persistenceFailed) throw persistError;
      if (isAborted(signal) || isAbortErrorName(persistError)) return abortedResult();
      throw persistError;
    }
  }
};

const runStage = async (
  deps: RunManagerDeps,
  context: RunContext,
  topicId: string,
  topicTitle: string,
  stage: DeepReadGenerationStage,
  signal?: AbortSignalLike,
  saveGuarded?: (out: DeepReadOutput) => Promise<void>,
): Promise<void> => {
  if (isAborted(signal)) throw makeAbortedError();
  deps.onProgress?.('WRITING', STAGE_LABELS[stage]);
  const running: DeepReadOutput = context.writer.markRunning([stage]);
  if (saveGuarded !== undefined) {
    await saveGuarded({ ...running, generationPhase: 'WRITING', generationComplete: false });
  } else {
    await persist(deps, topicId, topicTitle, { ...running, generationPhase: 'WRITING', generationComplete: false });
  }
  await runSupervisor(deps, context, stage, signal);
};

// runSupervisor helper:组装 tryFallback 注入
const runSupervisor = async (
  _deps: RunManagerDeps,
  context: RunContext,
  stage: DeepReadGenerationStage,
  signal?: AbortSignalLike,
): Promise<void> => {
  await runStageSupervisorLoop({
    writerMode: context.writerMode,
    writer: context.writer,
    evidencePack: context.evidencePack,
    articlePlan: context.articlePlan,
    topicTitle: context.topicTitle,
    seedUrl: context.seedUrl,
    playbookMarkdown: context.playbookMarkdown,
    scrapeWebAvailable: context.scrapeWebAvailable,
    collectRun: context.collectRun,
    todayIso: context.todayIso,
    signal,
    tryFallback: async (s, messages, sources, reason) => {
      const r = await tryFallbackAfterStageFailure({
        writer: context.writer, stage: s, messages, sources, reason, allowReadyRewrite: false,
      });
      return r;
    },
  }, stage);
};

const hasAnyReady = (output: DeepReadOutput): boolean =>
  STAGE_ORDER.some(s => statusOf(output, s) === 'READY');

const persist = async (deps: RunManagerDeps, topicId: string, title: string, output: DeepReadOutput): Promise<void> => {
  if (deps.repository) await deps.repository.save(topicId, title, output);
};

// ===== run / runSection(照搬 Android :88-149) =====
// 简化:不做 missingStages 后台调度(那是 scheduler 职责),不做 deferMissing。
// run = 非 force 取 cache → 若 isComplete → 返回;force 用全新 writer,不清旧稿。

export const run = async (
  deps: RunManagerDeps,
  topicId: string,
  topicTitle: string,
  opts: { force?: boolean; seedUrl?: string | null; seedUrls?: string[]; templateSnapshot?: DeepReadTemplateSnapshot; signal?: AbortSignalLike } = {},
): Promise<RunResult> => {
  const force = opts.force ?? false;
  const seedUrl = opts.seedUrl ?? null;
  // 取消已发生 → 不 clear、不读缓存直接返回 aborted
  if (isAborted(opts.signal)) {
    return { ok: false, output: deps.repository?.get(topicId, topicTitle) ?? makeEmptyDeepReadOutput(), error: 'aborted' };
  }
  // 续跑只补非 READY 段(P2-2):部分/取消缓存「继续」不重跑、不改写已 READY 段。
  // force/首次(无缓存或全未完成)仍为全 4 段。
  let stagesToRun: DeepReadGenerationStage[] = STAGE_ORDER;
  // cache 检查(非 force)
  if (!force) {
    const cached = deps.repository?.get(topicId, topicTitle) ?? null;
    if (cached !== null) {
      const inferred = withInferredSectionStates(cached);
      if (isComplete(inferred)) {
        return { ok: true, output: inferred, error: null };
      }
      if (sectionsReady(inferred)) {
        if (isAborted(opts.signal)) {
          return { ok: false, output: inferred, error: 'aborted' };
        }
        const completed = { ...inferred, generationPhase: 'COMPLETE' as const, generationComplete: true };
        if (deps.repository) await deps.repository.save(topicId, topicTitle, completed);
        return { ok: true, output: completed, error: null };
      }
      const missing = STAGE_ORDER.filter(s => statusOf(inferred, s) !== 'READY');
      if (missing.length > 0) stagesToRun = missing;
    }
  }
  if (!force && deps.writerMode === 'structured' && stagesToRun.length === 1 && stagesToRun[0] === 'EXTENDED_READING') {
    return runSection(deps, topicId, topicTitle, 'EXTENDED_READING', seedUrl, opts.signal, opts.seedUrls);
  }
  return generateStages(deps, topicId, topicTitle, stagesToRun, seedUrl, force, 'PLANNING', true, opts.signal, opts.seedUrls, opts.templateSnapshot);
};

export const runSection = async (
  deps: RunManagerDeps,
  topicId: string,
  topicTitle: string,
  stage: DeepReadGenerationStage,
  seedUrl: string | null = null,
  signal?: AbortSignalLike,
  seedUrls?: string[],
): Promise<RunResult> => {
  if (isAborted(signal)) {
    return { ok: false, output: deps.repository?.get(topicId, topicTitle) ?? makeEmptyDeepReadOutput(), error: 'aborted' };
  }
  const cachedOutput = deps.repository?.get(topicId, topicTitle) ?? null;
  if (requestedSynthesis(deps, cachedOutput, false)) {
    return generateStages(deps, topicId, topicTitle, STAGE_ORDER, seedUrl, false, 'WRITING', false, signal, seedUrls);
  }
  // Repair the retained legacy source status from saved research without
  // collecting, planning or adding a model charge.
  if (stage === 'EXTENDED_READING' && deps.writerMode === 'structured') {
    const cached = deps.repository?.get(topicId, topicTitle) ?? makeEmptyDeepReadOutput();
    const sources = stableGenerationSources(cached.inputSources ?? []);
    const links = stableSourceLinks(cached.sources ?? [], numberedSourceLinks(sources));
    const retainedLinks = links.length > 0 ? links : [...cached.references, ...cached.extendedReading];
    const writer = createSectionWriterTools({ topicId, topicTitle, imageCandidates: [], initialOutput: withInferredSectionStates(cached) });
    const updated = writer.writeSources(retainedLinks);
    const output = sectionsReady(updated) ? finishIfPossible(writer) : updated;
    if (isAborted(signal)) return { ok: false, output: cached, error: 'aborted' };
    await persist(deps, topicId, topicTitle, output);
    return { ok: statusOf(output, stage) === 'READY', output, error: output.sectionStates[stage]?.errorMessage ?? null };
  }
  // 有有效资料后由 runStage 标记 RUNNING;预抓失败不留下假的运行状态。
  return generateStages(
    deps, topicId, topicTitle, [stage], seedUrl, false,
    'WRITING',   // planningPhase
    false,       // markCollecting
    signal,
    seedUrls,
  );
};

// 重新导出供 scheduler 使用
export { createSectionWriterTools };
