import type { NovelModelPolicy, NovelModelTarget } from './models.ts';
import type { NovelModelRole } from './standalone_defaults.ts';
// ghostwrite_runner — C6 单一前台 durable executor。
// 只编排 repository 的 durable stage/checkpoint/atomic commit；模型流、工具循环、重试与
// stop 继续由 NovelModelRunning(Chat adapter)拥有。

import type { UIMessage } from '../agent/message.ts';
import { latestAssistantText } from '../agent/message.ts';
import { invalidInput } from './error.ts';
import type {
  DurableGhostwriteJob, GhostwriteCandidate, GhostwriteClaimRef,
  GhostwriteFinding, GhostwriteFindingKind, GhostwriteReview,
} from './ghostwrite.ts';
import {
  assertGhostwriteClaim, ghostwriteReceipt, makeGhostwriteCandidate,
  validateGhostwriteReview,
} from './ghostwrite.ts';
import type {
  NovelModelEvent, NovelModelRequest, NovelModelRunning, NovelModelStream,
} from './model_running.ts';
import type { NovelProjectRepository } from './repository.ts';

export type GhostwriteRunnerRepository = Pick<NovelProjectRepository,
  'loadGhostwriteJob' | 'claimGhostwriteJob' | 'checkpointGhostwriteStage' |
  'checkpointGhostwriteCandidate' | 'checkpointGhostwriteReview' |
  'failGhostwriteJob' | 'commitGhostwriteChapter'>;

export interface GhostwriteRunnerDeps {
  repository: GhostwriteRunnerRepository;
  modelRunning: NovelModelRunning;
  resolveModelTarget?: (policy: NovelModelPolicy, role: NovelModelRole) => Promise<NovelModelTarget>;
  nowMs?: () => number;
  makeId?: (prefix: string) => string;
}

export interface GhostwriteRunner {
  run(
    projectId: string, jobId: string, ownerToken: string, leaseMs?: number,
  ): Promise<DurableGhostwriteJob>;
  cancel(projectId: string, jobId: string): void;
}

interface ModelCallResult {
  kind: 'completed' | 'waiting_user';
  text: string;
}

interface RawCandidate {
  title?: unknown;
  content?: unknown;
}

interface RawFinding {
  kind?: unknown;
  code?: unknown;
  message?: unknown;
  location?: unknown;
}

interface RawStateDelta {
  plotState?: unknown;
  chapterHighlight?: unknown;
}

interface RawReview {
  candidateId?: unknown;
  candidateDigest?: unknown;
  planId?: unknown;
  planDigest?: unknown;
  findings?: unknown;
  blocking?: unknown;
  rewriteRequired?: unknown;
  rewriteInstructions?: unknown;
  nextPlan?: unknown;
  stateDelta?: unknown;
}

interface RawPlan {
  nextPlan?: unknown;
}

const DEFAULT_LEASE_MS: number = 30 * 60 * 1000;
const TERMINAL_OR_IDLE: string[] = [
  'paused', 'waiting_user', 'failed', 'cancelled', 'completed',
];

const errorText = (error: unknown): string => error instanceof Error ? error.message : String(error);

const isFindingKind = (value: unknown): value is GhostwriteFindingKind =>
  value === 'missing_required' || value === 'forbidden_violation' ||
  value === 'hard_continuity' || value === 'non_blocking';

const requiredString = (value: unknown, field: string): string => {
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw invalidInput(`${field} 无效`);
  }
  return value.trim();
};

const requiredBoolean = (value: unknown, field: string): boolean => {
  if (typeof value !== 'boolean') throw invalidInput(`${field} 无效`);
  return value;
};

const parseObject = <T>(text: string, label: string): T => {
  const trimmed: string = text.trim();
  if (!trimmed.startsWith('{') || !trimmed.endsWith('}')) {
    throw invalidInput(`${label}必须是严格 JSON 对象`);
  }
  try {
    return JSON.parse(trimmed) as T;
  } catch {
    throw invalidInput(`${label} JSON 无效`);
  }
};

const parseCandidate = (
  text: string, job: DurableGhostwriteJob, candidateId: string,
): GhostwriteCandidate => {
  const raw: RawCandidate = parseObject<RawCandidate>(text, '写作结果');
  const plan = job.frozenPlan;
  if (plan === null) throw invalidInput('ghostwrite job 缺少冻结计划');
  return makeGhostwriteCandidate({
    candidateId,
    chapterOrdinal: job.currentChapterOrdinal,
    title: requiredString(raw.title, 'candidate title'),
    content: requiredString(raw.content, 'candidate content'),
    planId: plan.planId,
    planDigest: plan.digest,
    attempt: job.rewriteCount,
  });
};

const parseFinding = (value: unknown): GhostwriteFinding => {
  const raw: RawFinding = value as RawFinding;
  if (!isFindingKind(raw.kind)) throw invalidInput('review finding kind 无效');
  return {
    kind: raw.kind,
    code: requiredString(raw.code, 'review finding code'),
    message: requiredString(raw.message, 'review finding message'),
    location: requiredString(raw.location, 'review finding location'),
  };
};

const parseReview = (text: string, job: DurableGhostwriteJob): GhostwriteReview => {
  const raw: RawReview = parseObject<RawReview>(text, '审核结果');
  const candidate: GhostwriteCandidate | null = job.candidate;
  const plan = job.frozenPlan;
  if (candidate === null || plan === null) throw invalidInput('审核缺少候选或冻结计划');
  if (!Array.isArray(raw.findings)) throw invalidInput('review findings 无效');
  const findings: GhostwriteFinding[] = raw.findings.map(
    (finding: unknown): GhostwriteFinding => parseFinding(finding));
  const stateDelta: RawStateDelta = raw.stateDelta as RawStateDelta;
  const nextPlan: string | null = raw.nextPlan === null
    ? null
    : requiredString(raw.nextPlan, 'review nextPlan');
  return validateGhostwriteReview({
    candidateId: requiredString(raw.candidateId, 'review candidateId'),
    candidateDigest: requiredString(raw.candidateDigest, 'review candidateDigest'),
    planId: requiredString(raw.planId, 'review planId'),
    planDigest: requiredString(raw.planDigest, 'review planDigest'),
    findings,
    blocking: requiredBoolean(raw.blocking, 'review blocking'),
    rewriteRequired: requiredBoolean(raw.rewriteRequired, 'review rewriteRequired'),
    rewriteInstructions: typeof raw.rewriteInstructions === 'string'
      ? raw.rewriteInstructions.trim() : '',
    nextPlan,
    stateDelta: {
      plotState: requiredString(stateDelta.plotState, 'review plotState'),
      chapterHighlight: requiredString(stateDelta.chapterHighlight, 'review chapterHighlight'),
    },
  }, candidate, plan);
};

const parseNextPlan = (text: string): string => {
  const raw: RawPlan = parseObject<RawPlan>(text, '规划结果');
  return requiredString(raw.nextPlan, 'nextPlan');
};

const writerSystemPrompt = (): string =>
  '你是小说正文写作者。只能读取工作区，不得写入任何文件。严格只返回一个 JSON 对象：' +
  '{"title":"章节标题","content":"完整章节正文"}。不要使用 Markdown 代码围栏。';

const writerUserPrompt = (job: DurableGhostwriteJob): string => {
  const plan = job.frozenPlan;
  if (plan === null) throw invalidInput('ghostwrite job 缺少冻结计划');
  const arc: string = plan.upcomingArc === undefined ? '' : '\n后续走向（不替代本章合同）：\n' + plan.upcomingArc.map(beat => '- ' + beat).join('\n');
  if (job.authorBrief !== undefined) {
    return `按作者修订要求改写第 ${job.currentChapterOrdinal} 章，返回完整章节。冻结计划:${plan.content}${arc}\n` +
      `作者修订要求:${job.authorBrief}\n` +
      (job.candidate === null ? '' : `上一稿:${job.candidate.content.slice(0, 12000)}\n`);
  }
  if (job.stage === 'rewriting_1' || job.stage === 'rewriting_2') {
    if (job.candidate === null || job.review === null) throw invalidInput('重写缺少候选或审核说明');
    return `重写第 ${job.currentChapterOrdinal} 章。冻结计划:${plan.content}${arc}\n` +
      `原候选:${job.candidate.content}\n定向修复:${job.review.rewriteInstructions}`;
  }
  return `写第 ${job.currentChapterOrdinal} 章。冻结计划:${plan.content}${arc}`;
};

const reviewSystemPrompt = (): string =>
  '你是小说联合审核员。只能读取工作区，不得写入。严格只返回 JSON 对象，字段必须包括：' +
  'candidateId,candidateDigest,planId,planDigest,findings,blocking,rewriteRequired,' +
  'rewriteInstructions,nextPlan,stateDelta。findings.kind 仅允许 missing_required、' +
  'forbidden_violation、hard_continuity、non_blocking；只有前三类可 blocking。' +
  'stateDelta 必须含非空 plotState 与 chapterHighlight。不要使用 Markdown 代码围栏。';

const reviewUserPrompt = (job: DurableGhostwriteJob): string => {
  if (job.candidate === null || job.frozenPlan === null) throw invalidInput('审核缺少候选或冻结计划');
  return JSON.stringify({
    chapterOrdinal: job.currentChapterOrdinal,
    candidateId: job.candidate.candidateId,
    candidateDigest: job.candidate.digest,
    planId: job.frozenPlan.planId,
    planDigest: job.frozenPlan.digest,
    plan: job.frozenPlan.content,
    upcomingArc: job.frozenPlan.upcomingArc ?? [],
    candidate: job.candidate.content,
    isFinalChapter: job.currentChapterOrdinal === job.endChapterOrdinal,
  });
};

const plannerSystemPrompt = (): string =>
  '你是小说下一章规划员。只能读取工作区，不得写入。严格只返回 JSON：' +
  '{"nextPlan":"下一章可执行计划"}。不要使用 Markdown 代码围栏。';

const plannerUserPrompt = (job: DurableGhostwriteJob): string => {
  if (job.candidate === null || job.review === null) throw invalidInput('规划缺少候选或审核');
  return JSON.stringify({
    nextChapterOrdinal: job.currentChapterOrdinal + 1,
    upcomingArc: job.frozenPlan?.upcomingArc ?? [],
    chapterHighlight: job.review.stateDelta.chapterHighlight,
    plotState: job.review.stateDelta.plotState,
    committedCandidate: job.candidate.content,
  });
};

class DefaultGhostwriteRunner implements GhostwriteRunner {
  private readonly deps: GhostwriteRunnerDeps;
  private readonly nowMs: () => number;
  private readonly makeId: (prefix: string) => string;
  private readonly activeModelRuns: Map<string, string> = new Map();

  constructor(deps: GhostwriteRunnerDeps) {
    this.deps = deps;
    this.nowMs = deps.nowMs ?? ((): number => Date.now());
    this.makeId = deps.makeId ?? ((prefix: string): string =>
      `${prefix}-${this.nowMs()}-${Math.random().toString(36).slice(2, 10)}`);
  }

  async run(
    projectId: string, jobId: string, ownerToken: string,
    leaseMs: number = DEFAULT_LEASE_MS,
  ): Promise<DurableGhostwriteJob> {
    const claimed: DurableGhostwriteJob = await this.deps.repository.claimGhostwriteJob(
      projectId, jobId, ownerToken, this.nowMs(), leaseMs);
    if (claimed.claim === null) throw invalidInput('repository 未返回 ghostwrite claim');
    const claim: GhostwriteClaimRef = { token: claimed.claim.token, epoch: claimed.claim.epoch };
    try {
      while (true) {
        const job: DurableGhostwriteJob = await this.safePoint(projectId, jobId, claim);
        if (TERMINAL_OR_IDLE.includes(job.stage)) return job;
        if (job.stage === 'planning') {
          if (job.review !== null && job.review.nextPlan === null &&
            job.currentChapterOrdinal < job.endChapterOrdinal) {
            const planned: ModelCallResult = await this.callModel(
              job, 'planner', plannerSystemPrompt(), plannerUserPrompt(job));
            if (planned.kind === 'waiting_user') {
              return await this.waitForUser(projectId, jobId, claim, job);
            }
            const review: GhostwriteReview = { ...job.review, nextPlan: parseNextPlan(planned.text) };
            await this.deps.repository.checkpointGhostwriteReview(
              projectId, jobId, claim, review, this.nowMs());
          } else {
            await this.deps.repository.checkpointGhostwriteStage(
              projectId, jobId, claim, 'writing', this.nowMs());
          }
          continue;
        }
        if (job.stage === 'writing' || job.stage === 'rewriting_1' || job.stage === 'rewriting_2') {
          const generated: ModelCallResult = await this.callModel(
            job, 'writer', writerSystemPrompt(), writerUserPrompt(job));
          if (generated.kind === 'waiting_user') {
            return await this.waitForUser(projectId, jobId, claim, job);
          }
          const candidateId: string = job.candidate?.candidateId ?? this.makeId('candidate');
          const candidate: GhostwriteCandidate = parseCandidate(generated.text, job, candidateId);
          await this.deps.repository.checkpointGhostwriteCandidate(
            projectId, jobId, claim, candidate, this.nowMs());
          continue;
        }
        if (job.stage === 'reviewing') {
          const reviewed: ModelCallResult = await this.callModel(
            job, 'reviewer', reviewSystemPrompt(), reviewUserPrompt(job));
          if (reviewed.kind === 'waiting_user') {
            return await this.waitForUser(projectId, jobId, claim, job);
          }
          const review: GhostwriteReview = parseReview(reviewed.text, job);
          await this.deps.repository.checkpointGhostwriteReview(
            projectId, jobId, claim, review, this.nowMs());
          continue;
        }
        if (job.stage === 'committing') {
          if (job.review === null || job.candidate === null || job.frozenPlan === null) {
            throw invalidInput('commit 缺少审核、候选或计划');
          }
          if (job.currentChapterOrdinal < job.endChapterOrdinal && job.review.nextPlan === null) {
            await this.deps.repository.checkpointGhostwriteStage(
              projectId, jobId, claim, 'planning', this.nowMs());
            continue;
          }
          const commandId: string = ghostwriteReceipt(
            job.jobId, job.currentChapterOrdinal, job.frozenPlan.planId,
            job.frozenPlan.digest, job.candidate.candidateId);
          await this.deps.repository.commitGhostwriteChapter(
            projectId, jobId, claim, commandId, this.nowMs());
          continue;
        }
        throw invalidInput(`未处理的 ghostwrite stage:${job.stage}`);
      }
    } catch (error) {
      const reason: string = errorText(error);
      try {
        const durable: DurableGhostwriteJob =
          await this.deps.repository.loadGhostwriteJob(projectId, jobId);
        if (TERMINAL_OR_IDLE.includes(durable.stage)) return durable;
        return await this.deps.repository.failGhostwriteJob(
          projectId, jobId, claim, reason, this.nowMs());
      } catch {
        throw error;
      }
    }
  }

  cancel(projectId: string, jobId: string): void {
    const runId: string | undefined = this.activeModelRuns.get(this.jobKey(projectId, jobId));
    if (runId !== undefined) this.deps.modelRunning.cancel(runId);
  }

  private async safePoint(
    projectId: string, jobId: string, claim: GhostwriteClaimRef,
  ): Promise<DurableGhostwriteJob> {
    const job: DurableGhostwriteJob = await this.deps.repository.loadGhostwriteJob(projectId, jobId);
    if (TERMINAL_OR_IDLE.includes(job.stage)) return job;
    assertGhostwriteClaim(job, claim, this.nowMs());
    return job;
  }

  private waitForUser(
    projectId: string, jobId: string, claim: GhostwriteClaimRef, job: DurableGhostwriteJob,
  ): Promise<DurableGhostwriteJob> {
    return this.deps.repository.checkpointGhostwriteStage(
      projectId, jobId, claim, 'waiting_user', Math.max(this.nowMs(), job.updatedAt));
  }

  private async callModel(
    job: DurableGhostwriteJob, role: 'writer' | 'reviewer' | 'planner',
    systemPrompt: string, userPrompt: string,
  ): Promise<ModelCallResult> {
    const modelTarget = this.deps.resolveModelTarget !== undefined
      ? await this.deps.resolveModelTarget(job.modelPolicyAtStart, role === 'writer' ? 'writing' : role === 'reviewer' ? 'review' : 'stateSync')
      : role === 'writer'
      ? job.modelPolicyAtStart.writing
      : role === 'reviewer'
        ? (job.modelPolicyAtStart.review ?? job.modelPolicyAtStart.writing)
        : (job.modelPolicyAtStart.stateSync ?? job.modelPolicyAtStart.writing);
    await this.deps.modelRunning.validate(modelTarget, job.projectId);
    const runId: string = this.makeId(`ghostwrite-${role}`);
    const request: NovelModelRequest = {
      runId,
      projectId: job.projectId,
      systemPrompt,
      maxOutputTokens: null,
      modelTarget,
      toolProfile: 'read_only',
      history: [],
      operation: { kind: 'turn', userPrompt },
      checkpoint: async (_messages: UIMessage[]): Promise<void> => {},
    };
    const key: string = this.jobKey(job.projectId, job.jobId);
    this.activeModelRuns.set(key, runId);
    try {
      return await this.collectModel(request);
    } finally {
      if (this.activeModelRuns.get(key) === runId) this.activeModelRuns.delete(key);
    }
  }

  private jobKey(projectId: string, jobId: string): string {
    return `${projectId}:${jobId}`;
  }

  private collectModel(request: NovelModelRequest): Promise<ModelCallResult> {
    return new Promise<ModelCallResult>((resolve, reject): void => {
      let latestText: string = '';
      let settled: boolean = false;
      let unsubscribe: () => void = (): void => {};
      const finish = (run: () => void): void => {
        if (settled) return;
        settled = true;
        unsubscribe();
        run();
      };
      let stream: NovelModelStream;
      try {
        stream = this.deps.modelRunning.start(request);
      } catch (error) {
        reject(error);
        return;
      }
      unsubscribe = stream.subscribe((event: NovelModelEvent): void => {
        if (settled) return;
        if (event.kind === 'snapshot') {
          latestText = latestAssistantText(event.messages);
        } else if (event.kind === 'waiting_user') {
          finish((): void => { resolve({ kind: 'waiting_user', text: latestText }); });
        } else if (event.kind === 'failed') {
          finish((): void => { reject(new Error(event.message)); });
        } else if (event.kind === 'completed') {
          finish((): void => {
            if (latestText.trim().length === 0) {
              reject(invalidInput('模型返回空内容'));
            } else {
              resolve({ kind: 'completed', text: latestText });
            }
          });
        }
      });
    });
  }
}

export const createGhostwriteRunner = (deps: GhostwriteRunnerDeps): GhostwriteRunner =>
  new DefaultGhostwriteRunner(deps);
