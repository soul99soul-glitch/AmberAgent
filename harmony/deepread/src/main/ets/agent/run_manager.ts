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
import { generateArticlePlan } from '../research/article_plan.ts';
import type { ScoredImageCandidate } from '../research/image_scorer.ts';
import type { AiClient } from '../platform/ai_client.ts';
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
): Promise<{ ok: true; context: RunContext } | { ok: false; error: string }> => {
  // 1. prefetch(空 → hard fail, P1-9)
  const prefetchedSources = await deps.prefetcher.collect(topicId, topicTitle, seedUrl, force);
  if (prefetchedSources.length === 0) {
    return { ok: false, error: '没有抓到足够的来源，无法生成深度阅读。请检查搜索源或稍后重试。' };
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

  // 5. article plan(1 次 LLM;失败 → fallbackPlan)
  const articlePlan = await generateArticlePlan(deps.aiClient, deps.model, topicTitle, evidencePack, deps.playbookMarkdown);

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
): Promise<RunResult> => {
  // createRunContext(空 prefetch → failure)
  const ctxResult = await createRunContext(deps, topicId, topicTitle, seedUrl, force);
  if (!ctxResult.ok) {
    return { ok: false, output: makeEmptyDeepReadOutput(), error: ctxResult.error };
  }
  const context = ctxResult.context;

  // markCollecting → 写 COLLECTING phase(真实持久化)
  if (markCollecting && deps.repository) {
    deps.repository.save(topicId, topicTitle, { ...context.writer.current(), generationPhase: 'COLLECTING', generationComplete: false });
  }

  try {
    context.writer.markRunning(stages);

    // 顺序 if(OVERVIEW → NARRATIVE → ANALYSIS → EXTENDED_READING)
    if (stages.includes('OVERVIEW')) {
      await runSupervisor(deps, context, 'OVERVIEW');
    }
    if (stages.includes('NARRATIVE')) {
      await runSupervisor(deps, context, 'NARRATIVE');
    }
    if (stages.includes('ANALYSIS')) {
      await runSupervisor(deps, context, 'ANALYSIS');
    }
    if (stages.includes('EXTENDED_READING')) {
      await runSupervisor(deps, context, 'EXTENDED_READING');
    }

    // finish 判定
    const allTargetedReady = stages.every(s => statusOf(context.writer.current(), s) === 'READY');
    const allSectionsReady = sectionsReady(context.writer.current());
    if (allTargetedReady && allSectionsReady) {
      const finished = finishIfPossible(context.writer);
      persist(deps, topicId, topicTitle, finished);
      return { ok: true, output: finished, error: null };
    }
    if (allTargetedReady) {
      const out = context.writer.markPhase('IDLE');
      persist(deps, topicId, topicTitle, out);
      return { ok: true, output: out, error: null };
    }
    // 未达 READY 的 stage → markFailed
    const missing = stages.filter(s => statusOf(context.writer.current(), s) !== 'READY');
    for (const stage of missing) {
      context.writer.markFailed(stage, 'Agent 未按约定调用分段写入工具，该段未写入。');
    }
    const out = context.writer.markPhase('IDLE');
    persist(deps, topicId, topicTitle, out);
    return { ok: out.extendedReading.length > 0 || hasAnyReady(out), output: out, error: hasAnyReady(out) ? null : '深度阅读生成失败' };
  } catch (error) {
    // 取消错误重抛;其他 → markMissingFailed
    if (isAbortError(error)) throw error;
    const msg = error instanceof Error ? error.message : String(error);
    for (const stage of stages) {
      if (statusOf(context.writer.current(), stage) !== 'READY') {
        context.writer.markFailed(stage, msg);
      }
    }
    const out = context.writer.markPhase('IDLE');
    persist(deps, topicId, topicTitle, out);
    return { ok: hasAnyReady(out), output: out, error: hasAnyReady(out) ? null : msg };
  }
};

// runSupervisor helper:组装 tryFallback 注入
const runSupervisor = async (
  _deps: RunManagerDeps,
  context: RunContext,
  stage: DeepReadGenerationStage,
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

const isAbortError = (e: unknown): boolean =>
  e instanceof Error && (e.name === 'AbortError' || /abort|cancel/i.test(e.message));

// ===== run / runSection(照搬 Android :88-149) =====
// 简化:不做 missingStages 后台调度(那是 scheduler 职责),不做 deferMissing。
// run = force ? clear : 取 cache → 若 isComplete → 返回;否则 generateStages(全 4 stages)。

export const run = async (
  deps: RunManagerDeps,
  topicId: string,
  topicTitle: string,
  opts: { force?: boolean; seedUrl?: string | null } = {},
): Promise<RunResult> => {
  const force = opts.force ?? false;
  const seedUrl = opts.seedUrl ?? null;
  if (force && deps.repository) {
    deps.repository.clear(topicId);
  }
  // cache 检查(非 force)
  if (!force) {
    const cached = deps.repository?.get(topicId, topicTitle) ?? null;
    if (cached !== null) {
      const inferred = withInferredSectionStates(cached);
      if (isComplete(inferred)) {
        return { ok: true, output: inferred, error: null };
      }
      if (sectionsReady(inferred)) {
        const completed = { ...inferred, generationPhase: 'COMPLETE' as const, generationComplete: true };
        if (deps.repository) deps.repository.save(topicId, topicTitle, completed);
        return { ok: true, output: completed, error: null };
      }
    }
  }
  // 全 4 stages 生成
  return generateStages(deps, topicId, topicTitle, STAGE_ORDER, seedUrl, force);
};

export const runSection = async (
  deps: RunManagerDeps,
  topicId: string,
  topicTitle: string,
  stage: DeepReadGenerationStage,
  seedUrl: string | null = null,
): Promise<RunResult> => {
  // markSectionRunning(真实持久化)
  if (deps.repository) {
    const current = deps.repository.get(topicId, topicTitle) ?? makeEmptyDeepReadOutput();
    const inferred = withInferredSectionStates(current);
    if (statusOf(inferred, stage) !== 'READY') {
      const next = { ...inferred, sectionStates: { ...inferred.sectionStates, [stage]: { status: 'RUNNING' as const, errorMessage: null } } };
      deps.repository.save(topicId, topicTitle, next);
    }
  }
  return generateStages(
    deps, topicId, topicTitle, [stage], seedUrl, false,
    'WRITING',   // planningPhase
    false,       // markCollecting
  );
};

// 重新导出供 scheduler 使用
export { createSectionWriterTools };
