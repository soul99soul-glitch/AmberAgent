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
import type { SourcePrefetcher, DeepReadSource, SourceCredibility, SourceFreshness } from '../research/source_prefetcher.ts';
import { buildEvidencePack } from '../research/evidence_pack.ts';
import type { DeepReadEvidencePack, DeepReadArticlePlan } from '../research/evidence_pack.ts';
import { generateArticlePlan } from '../research/article_plan.ts';
import type { ScoredImageCandidate } from '../research/image_scorer.ts';
import type { AiClient } from '../platform/ai_client.ts';
import type { AbortSignalLike } from '../platform/runtime_api.ts';
import type { DeepReadOutput } from '../domain/models.ts';
import { makeEmptyDeepReadOutput } from '../domain/models.ts';
import {
  statusOf, sectionsReady, isComplete, withInferredSectionStates,
} from '../domain/helpers.ts';
import type { DeepReadGenerationStage, DeepReadGenerationPhase } from '../domain/enums.ts';
import { STAGE_ORDER } from '../domain/enums.ts';

export interface RunResult {
  ok: boolean;
  output: DeepReadOutput;
  error: string | null;     // ok=false 时填
}

// createRunContext 产物 — 对应 Android DeepReadRunContext
export interface RunContext {
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
  prefetcher: SourcePrefetcher;
  collectRun: CollectRunFn;              // 真实由 AiClient.generateText 装配
  aiClient: AiClient;                    // 用于 generateArticlePlan
  model: string;
  playbookMarkdown: string;
  /** 可选:持久化 save/get(真实由 Repository;测试可注入内存) */
  repository?: {
    get: (topicId: string, title: string) => DeepReadOutput | null;
    save: (topicId: string, title: string, output: DeepReadOutput) => void;
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

export const createRunContext = async (
  deps: RunManagerDeps,
  topicId: string,
  topicTitle: string,
  seedUrl: string | null,
  force: boolean,
  signal?: AbortSignalLike,
): Promise<{ ok: true; context: RunContext } | { ok: false; error: string }> => {
  // 0. 取消一旦发生,不再进入任何抓取/规划(避免取消后仍工作)
  if (isAborted(signal)) return { ok: false, error: 'aborted' };
  // 1. prefetch(空/异常 → 用 topicTitle 构造降级 evidence,而非 hard fail)
  // signal 贯穿:provider search / HTTP fetch 真实取消;prefetch 内部也会提前返回
  let prefetchedSources: DeepReadSource[] = [];
  try {
    prefetchedSources = await deps.prefetcher.collect(topicId, topicTitle, seedUrl, force, signal);
  } catch (_e) {
    // prefetch 超时或异常 → 降级(取消由下方 signal 复查兜住)
  }
  if (isAborted(signal)) return { ok: false, error: 'aborted' };
  if (prefetchedSources.length === 0) {
    // 降级:搜索源全部失败时,用标题构造一个最小来源,让流程能走 fallbackPlan
    prefetchedSources = [{
      sourceId: 'fallback-title',
      url: seedUrl ?? `topic://${topicId}`,
      title: topicTitle,
      source: 'fallback',
      evidenceText: topicTitle,
      credibility: 'medium' as SourceCredibility,
      freshness: 'unknown' as SourceFreshness,
      publishedAt: null,
      imageCandidates: [],
    }];
  }

  // 2. evidence pack
  const evidencePack = buildEvidencePack(prefetchedSources);

  // 3. image candidates 池(传给 writer)
  const imageCandidates: ScoredImageCandidate[] = prefetchedSources.flatMap(s => s.imageCandidates);

  // 4. writer(初始 output 从 repository 取或空)
  const initialOutput = deps.repository?.get(topicId, topicTitle) ?? makeEmptyDeepReadOutput();
  const writer = createSectionWriterTools({
    topicId, topicTitle, imageCandidates, initialOutput,
  });
  if (deps.onWriterCreated) deps.onWriterCreated(writer);

  // 5. article plan(1 次 LLM;失败 → fallbackPlan;signal 贯穿真实取消)
  const articlePlan = await generateArticlePlan(deps.aiClient, deps.model, topicTitle, evidencePack, deps.playbookMarkdown, signal);
  if (isAborted(signal)) return { ok: false, error: 'aborted' };

  return {
    ok: true,
    context: {
      writer,
      evidencePack,
      articlePlan,
      topicTitle,
      seedUrl,
      playbookMarkdown: deps.playbookMarkdown,
      scrapeWebAvailable: deps.scrapeWebAvailable ?? false,
      collectRun: deps.collectRun,
      todayIso: deps.nowIso(),
    },
  };
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
): Promise<RunResult> => {
  // 统一 aborted 结果(返回取消前已持久化的当前态,不写新状态)
  const abortedResult = (): RunResult => ({
    ok: false,
    output: deps.repository?.get(topicId, topicTitle) ?? makeEmptyDeepReadOutput(),
    error: 'aborted',
  });
  if (isAborted(signal)) return abortedResult();

  // createRunContext(空 prefetch → failure)
  const ctxResult = await createRunContext(deps, topicId, topicTitle, seedUrl, force, signal);
  if (ctxResult.ok === false) {
    // ArkTS 对 discriminated union 的 narrow 较弱,显式提取 error
    const failMsg: string = (ctxResult as { ok: false; error: string }).error;
    if (failMsg === 'aborted') return abortedResult();
    return { ok: false, output: makeEmptyDeepReadOutput(), error: failMsg };
  }
  const context = (ctxResult as { ok: true; context: RunContext }).context;

  // 持久化守卫:signal 已取消时绝不落盘(避免取消后仍写 RUNNING/COLLECTING)
  const saveGuarded = (out: DeepReadOutput): void => {
    if (isAborted(signal)) throw makeAbortedError();
    persist(deps, topicId, topicTitle, out);
  };

  try {
    // markCollecting → 写 COLLECTING phase(真实持久化)
    // 放在 try 内:取消恰好发生在此窗口时由 catch 归一为 abortedResult(P3-1),
    // 直调 run_manager.run 也不以 rejection 出栈。
    if (markCollecting && deps.repository) {
      saveGuarded({ ...context.writer.current(), generationPhase: 'COLLECTING', generationComplete: false });
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
      await runStage(deps, context, topicId, topicTitle, 'EXTENDED_READING', signal, saveGuarded);
    }

    // finish 判定
    const allTargetedReady = stages.every(s => statusOf(context.writer.current(), s) === 'READY');
    const allSectionsReady = sectionsReady(context.writer.current());
    if (allTargetedReady && allSectionsReady) {
      const finished = finishIfPossible(context.writer);
      saveGuarded(finished);
      return { ok: true, output: finished, error: null };
    }
    if (allTargetedReady) {
      const out = context.writer.markPhase('IDLE');
      saveGuarded(out);
      return { ok: true, output: out, error: null };
    }
    // 未达 READY 的 stage → markFailed
    const missing = stages.filter(s => statusOf(context.writer.current(), s) !== 'READY');
    for (const stage of missing) {
      context.writer.markFailed(stage, 'Agent 未按约定调用分段写入工具，该段未写入。');
    }
    const out = context.writer.markPhase('IDLE');
    saveGuarded(out);
    return { ok: out.extendedReading.length > 0 || hasAnyReady(out), output: out, error: hasAnyReady(out) ? null : '深度阅读生成失败' };
  } catch (error) {
    // 取消 → 统一 aborted 结果(以 signal 状态判定,不宽判 error.message)
    if (isAborted(signal) || isAbortErrorName(error)) return abortedResult();
    const msg = error instanceof Error ? error.message : String(error);
    for (const stage of stages) {
      if (statusOf(context.writer.current(), stage) !== 'READY') {
        context.writer.markFailed(stage, msg);
      }
    }
    try {
      const out = context.writer.markPhase('IDLE');
      saveGuarded(out);
      return { ok: hasAnyReady(out), output: out, error: hasAnyReady(out) ? null : msg };
    } catch (persistError) {
      // 落盘守卫因取消抛出 → 统一 aborted;其余落盘错误按原样上抛(不误报取消)
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
  saveGuarded?: (out: DeepReadOutput) => void,
): Promise<void> => {
  if (isAborted(signal)) throw makeAbortedError();
  const running: DeepReadOutput = context.writer.markRunning([stage]);
  if (saveGuarded !== undefined) {
    saveGuarded({ ...running, generationPhase: 'WRITING', generationComplete: false });
  } else {
    persist(deps, topicId, topicTitle, { ...running, generationPhase: 'WRITING', generationComplete: false });
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

const persist = (deps: RunManagerDeps, topicId: string, title: string, output: DeepReadOutput): void => {
  if (deps.repository) deps.repository.save(topicId, title, output);
};

// ===== run / runSection(照搬 Android :88-149) =====
// 简化:不做 missingStages 后台调度(那是 scheduler 职责),不做 deferMissing。
// run = force ? clear : 取 cache → 若 isComplete → 返回;否则 generateStages(全 4 stages)。

export const run = async (
  deps: RunManagerDeps,
  topicId: string,
  topicTitle: string,
  opts: { force?: boolean; seedUrl?: string | null; signal?: AbortSignalLike } = {},
): Promise<RunResult> => {
  const force = opts.force ?? false;
  const seedUrl = opts.seedUrl ?? null;
  // 取消已发生 → 不 clear、不读缓存直接返回 aborted
  if (isAborted(opts.signal)) {
    return { ok: false, output: deps.repository?.get(topicId, topicTitle) ?? makeEmptyDeepReadOutput(), error: 'aborted' };
  }
  if (force && deps.repository) {
    deps.repository.clear(topicId);
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
        if (deps.repository) deps.repository.save(topicId, topicTitle, completed);
        return { ok: true, output: completed, error: null };
      }
      const missing = STAGE_ORDER.filter(s => statusOf(inferred, s) !== 'READY');
      if (missing.length > 0) stagesToRun = missing;
    }
  }
  return generateStages(deps, topicId, topicTitle, stagesToRun, seedUrl, force, 'PLANNING', true, opts.signal);
};

export const runSection = async (
  deps: RunManagerDeps,
  topicId: string,
  topicTitle: string,
  stage: DeepReadGenerationStage,
  seedUrl: string | null = null,
  signal?: AbortSignalLike,
): Promise<RunResult> => {
  if (isAborted(signal)) {
    return { ok: false, output: deps.repository?.get(topicId, topicTitle) ?? makeEmptyDeepReadOutput(), error: 'aborted' };
  }
  // markSectionRunning(真实持久化;取消后不落 RUNNING)
  if (deps.repository) {
    const current = deps.repository.get(topicId, topicTitle) ?? makeEmptyDeepReadOutput();
    const inferred = withInferredSectionStates(current);
    if (statusOf(inferred, stage) !== 'READY') {
      if (isAborted(signal)) {
        return { ok: false, output: inferred, error: 'aborted' };
      }
      const next = { ...inferred, sectionStates: { ...inferred.sectionStates, [stage]: { status: 'RUNNING' as const, errorMessage: null } } };
      deps.repository.save(topicId, topicTitle, next);
    }
  }
  return generateStages(
    deps, topicId, topicTitle, [stage], seedUrl, false,
    'WRITING',   // planningPhase
    false,       // markCollecting
    signal,
  );
};

// 重新导出供 scheduler 使用
export { createSectionWriterTools };
