// ghostwrite — C6 durable ghostwrite pure domain.
// Persistence and atomic workspace commits stay in NovelProjectRepository; this file owns only
// validated job facts and state transitions.

import type { WorkspaceCas } from './workspace_history.ts';
import type { NovelModelPolicy, NovelModelTarget } from './models.ts';
import { invalidInput } from './error.ts';

export const MIN_GHOSTWRITE_CHAPTER_COUNT: number = 1;
export const MAX_GHOSTWRITE_CHAPTER_COUNT: number = 10;
export const DEFAULT_GHOSTWRITE_CHAPTER_COUNT: number = 5;
export const MAX_GHOSTWRITE_REWRITE_COUNT: number = 2;
export const MIN_GHOSTWRITE_CHAPTER_ORDINAL: number = 1;
export const MAX_GHOSTWRITE_CHAPTER_ORDINAL: number = 999;

export type GhostwriteStage =
  | 'planning'
  | 'writing'
  | 'reviewing'
  | 'rewriting_1'
  | 'rewriting_2'
  | 'committing'
  | 'paused'
  | 'waiting_user'
  | 'failed'
  | 'cancelled'
  | 'completed';

export type GhostwriteFindingKind =
  | 'missing_required'
  | 'forbidden_violation'
  | 'hard_continuity'
  | 'non_blocking';

export interface GhostwriteFinding {
  kind: GhostwriteFindingKind;
  code: string;
  message: string;
  location: string;
}

export interface FrozenPlan {
  upcomingArc?: string[];
  path: 'plan/this-chapter.md';
  planId: string;
  content: string;
  digest: string;
  expectedCas: WorkspaceCas;
}

export interface GhostwriteCandidate {
  candidateId: string;
  chapterOrdinal: number;
  title: string;
  content: string;
  digest: string;
  planId: string;
  planDigest: string;
  attempt: number;
}

export interface GhostwriteReview {
  candidateId: string;
  candidateDigest: string;
  planId: string;
  planDigest: string;
  findings: GhostwriteFinding[];
  blocking: boolean;
  rewriteRequired: boolean;
  rewriteInstructions: string;
  nextPlan: string | null;
  stateDelta: GhostwriteStateDelta;
}

export interface GhostwriteStateDelta {
  plotState: string;
  chapterHighlight: string;
}

export interface GhostwriteClaim {
  token: string;
  epoch: number;
  leaseUntil: number;
}

export interface GhostwriteClaimRef {
  token: string;
  epoch: number;
}

export interface GhostwriteProgress {
  chapterOrdinal: number;
  planId: string;
  planDigest: string;
  candidateId: string;
  receipt: string;
  commitId: string;
  branchId: string;
}

export interface DurableGhostwriteJob {
  version: 1;
  kind: 'ghostwrite';
  jobId: string;
  projectId: string;
  branchId: string;
  startChapterOrdinal: number;
  currentChapterOrdinal: number;
  endChapterOrdinal: number;
  targetChapterCount: number;
  stage: GhostwriteStage;
  resumeStage: GhostwriteStage | null;
  expectedCas: WorkspaceCas;
  frozenPlan: FrozenPlan | null;
  candidate: GhostwriteCandidate | null;
  review: GhostwriteReview | null;
  rewriteCount: number;
  claim: GhostwriteClaim | null;
  claimEpoch: number;
  modelPolicyAtStart: NovelModelPolicy;
  progress: GhostwriteProgress[];
  failure: string | null;
  // 作者下一次写稿的一次性要求，成功保存新候选后消费。
  authorBrief?: string;
  createdAt: number;
  updatedAt: number;
}

export interface FreezeGhostwritePlanInput {
  upcomingArc?: string[];
  planId: string;
  content: string;
  expectedCas: WorkspaceCas;
  digest?: (content: string) => string;
}

export interface MakeGhostwriteCandidateInput {
  candidateId: string;
  chapterOrdinal: number;
  title: string;
  content: string;
  planId: string;
  planDigest: string;
  attempt: number;
  digest?: (content: string) => string;
}

export interface MakeGhostwriteJobInput {
  jobId: string;
  projectId: string;
  branchId: string;
  now: number;
  frozenPlan: FrozenPlan;
  targetChapterCount?: number;
  startChapterOrdinal?: number;
  modelPolicyAtStart: NovelModelPolicy;
}

const TERMINAL_STAGES: GhostwriteStage[] = ['failed', 'cancelled', 'completed'];

const isTerminal = (stage: GhostwriteStage): boolean => TERMINAL_STAGES.includes(stage);

const requireId = (value: string, field: string): string => {
  if (value.trim().length === 0 || value !== value.trim() || value.indexOf('\n') >= 0) {
    throw invalidInput(`${field} 无效`);
  }
  return value;
};

const requireTimestamp = (value: number, field: string): number => {
  if (!Number.isInteger(value) || value < 0) throw invalidInput(`${field} 无效`);
  return value;
};

const validateChapterCount = (count: number): number => {
  if (!Number.isInteger(count) || count < MIN_GHOSTWRITE_CHAPTER_COUNT ||
    count > MAX_GHOSTWRITE_CHAPTER_COUNT) {
    throw invalidInput(`代笔章节数必须在 ${MIN_GHOSTWRITE_CHAPTER_COUNT}-${MAX_GHOSTWRITE_CHAPTER_COUNT}`);
  }
  return count;
};

const validateChapterOrdinal = (ordinal: number): number => {
  if (!Number.isInteger(ordinal) || ordinal < MIN_GHOSTWRITE_CHAPTER_ORDINAL ||
    ordinal > MAX_GHOSTWRITE_CHAPTER_ORDINAL) {
    throw invalidInput(`章节序号必须在 ${MIN_GHOSTWRITE_CHAPTER_ORDINAL}-${MAX_GHOSTWRITE_CHAPTER_ORDINAL}`);
  }
  return ordinal;
};

const validateModelTarget = (target: NovelModelTarget, field: string): NovelModelTarget => {
  if (target.kind === 'global') return target;
  requireId(target.providerId, `${field} providerId`);
  requireId(target.modelId, `${field} modelId`);
  return target;
};

const validateModelPolicy = (policy: NovelModelPolicy): NovelModelPolicy => {
  validateModelTarget(policy.writing, 'writing');
  if (policy.review !== null) validateModelTarget(policy.review, 'review');
  if (policy.stateSync !== null) validateModelTarget(policy.stateSync, 'stateSync');
  return policy;
};

const validateCas = (cas: WorkspaceCas): WorkspaceCas => {
  requireId(cas.branchId, 'CAS branchId');
  requireId(cas.head, 'CAS head');
  requireId(cas.treeDigest, 'CAS treeDigest');
  return cas;
};

const sameCas = (left: WorkspaceCas, right: WorkspaceCas): boolean =>
  left.branchId === right.branchId && left.head === right.head && left.treeDigest === right.treeDigest;

// SDK-free stable digest. Repository/Entry may inject SHA-256 through the optional digest seam.
export const defaultGhostwriteDigest = (content: string): string => {
  let hash: number = 0x811c9dc5;
  for (let i: number = 0; i < content.length; i++) {
    hash ^= content.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193);
  }
  return (hash >>> 0).toString(16).padStart(8, '0');
};

export const freezeGhostwritePlan = (input: FreezeGhostwritePlanInput): FrozenPlan => {
  const content: string = input.content.trim();
  if (content.length === 0) throw invalidInput('代笔前必须存在本章计划');
  if (input.upcomingArc !== undefined && (!Array.isArray(input.upcomingArc) || input.upcomingArc.length > 8
    || input.upcomingArc.some(beat => typeof beat !== 'string' || !beat.trim() || beat.length > 160)))
    throw invalidInput('冻结后续走向无效');
  const digestText: string = input.upcomingArc === undefined ? content : content + '\n--upcoming-arc--\n' + JSON.stringify(input.upcomingArc);
  const digest: string = (input.digest ?? defaultGhostwriteDigest)(digestText);
  requireId(digest, 'plan digest');
  return {
    path: 'plan/this-chapter.md',
    planId: requireId(input.planId, 'planId'),
    content,
    digest,
    expectedCas: validateCas(input.expectedCas),
    ...(input.upcomingArc === undefined ? {} : { upcomingArc: input.upcomingArc.slice() }),
  };
};

export const makeGhostwriteCandidate = (input: MakeGhostwriteCandidateInput): GhostwriteCandidate => {
  const ordinal: number = validateChapterOrdinal(input.chapterOrdinal);
  if (!Number.isInteger(input.attempt) || input.attempt < 0 || input.attempt > MAX_GHOSTWRITE_REWRITE_COUNT) {
    throw invalidInput('candidate attempt 无效');
  }
  const title: string = input.title.trim();
  const content: string = input.content.trim();
  if (title.length === 0 || content.length === 0) throw invalidInput('candidate 标题或正文为空');
  const digest: string = (input.digest ?? defaultGhostwriteDigest)(content);
  return {
    candidateId: requireId(input.candidateId, 'candidateId'),
    chapterOrdinal: ordinal,
    title,
    content,
    digest: requireId(digest, 'candidate digest'),
    planId: requireId(input.planId, 'candidate planId'),
    planDigest: requireId(input.planDigest, 'candidate planDigest'),
    attempt: input.attempt,
  };
};

const validateFinding = (finding: GhostwriteFinding): GhostwriteFinding => {
  requireId(finding.code, 'finding code');
  if (finding.message.trim().length === 0 || finding.location.trim().length === 0) {
    throw invalidInput('review finding 无效');
  }
  return finding;
};

const hasBlockingFinding = (findings: GhostwriteFinding[]): boolean => findings.some(
  (finding: GhostwriteFinding): boolean => finding.kind === 'missing_required' ||
    finding.kind === 'forbidden_violation' || finding.kind === 'hard_continuity',
);

export const validateGhostwriteReview = (
  review: GhostwriteReview, candidate: GhostwriteCandidate, plan: FrozenPlan,
): GhostwriteReview => {
  if (review.candidateId !== candidate.candidateId || review.candidateDigest !== candidate.digest ||
    review.planId !== plan.planId || review.planDigest !== plan.digest ||
    candidate.planId !== plan.planId || candidate.planDigest !== plan.digest) {
    throw invalidInput('审核结果与候选或计划不匹配');
  }
  for (let i: number = 0; i < review.findings.length; i++) validateFinding(review.findings[i]);
  if (review.blocking && !hasBlockingFinding(review.findings)) {
    throw invalidInput('non_blocking finding 不可阻塞收录');
  }
  if (review.blocking && review.rewriteRequired) {
    throw invalidInput('blocking 与 rewriteRequired 不可同时成立');
  }
  if (review.rewriteRequired && review.rewriteInstructions.trim().length === 0) {
    throw invalidInput('定向重写必须提供说明');
  }
  if (review.nextPlan !== null && review.nextPlan.trim().length === 0) {
    throw invalidInput('下一章计划不可为空白');
  }
  if (review.stateDelta.plotState.trim().length === 0 || review.stateDelta.chapterHighlight.trim().length === 0) {
    throw invalidInput('审核状态增量必须包含剧情与章节要点');
  }
  return review;
};

export const makeGhostwriteJob = (input: MakeGhostwriteJobInput): DurableGhostwriteJob => {
  const plan: FrozenPlan = freezeGhostwritePlan({
    planId: input.frozenPlan.planId,
    content: input.frozenPlan.content,
    upcomingArc: input.frozenPlan.upcomingArc,
    expectedCas: input.frozenPlan.expectedCas,
    digest: (): string => input.frozenPlan.digest,
  });
  if (plan.expectedCas.branchId !== input.branchId) throw invalidInput('计划分支与 job 分支不一致');
  const now: number = requireTimestamp(input.now, 'createdAt');
  const targetChapterCount: number = validateChapterCount(
    input.targetChapterCount ?? DEFAULT_GHOSTWRITE_CHAPTER_COUNT,
  );
  const startChapterOrdinal: number = validateChapterOrdinal(input.startChapterOrdinal ?? 1);
  const endChapterOrdinal: number = startChapterOrdinal + targetChapterCount - 1;
  validateChapterOrdinal(endChapterOrdinal);
  return {
    version: 1,
    kind: 'ghostwrite',
    jobId: requireId(input.jobId, 'jobId'),
    projectId: requireId(input.projectId, 'projectId'),
    branchId: requireId(input.branchId, 'branchId'),
    startChapterOrdinal,
    currentChapterOrdinal: startChapterOrdinal,
    endChapterOrdinal,
    targetChapterCount,
    stage: 'planning',
    resumeStage: null,
    expectedCas: plan.expectedCas,
    frozenPlan: plan,
    candidate: null,
    review: null,
    rewriteCount: 0,
    claim: null,
    claimEpoch: 0,
    modelPolicyAtStart: validateModelPolicy(input.modelPolicyAtStart),
    progress: [],
    failure: null,
    createdAt: now,
    updatedAt: now,
  };
};

export const validateDurableGhostwriteJob = (job: DurableGhostwriteJob): DurableGhostwriteJob => {
  if (job.version !== 1) throw invalidInput('ghostwrite job 版本不受支持');
  if (job.kind !== 'ghostwrite') throw invalidInput('ghostwrite job 类型无效');
  requireId(job.jobId, 'jobId');
  requireId(job.projectId, 'projectId');
  requireId(job.branchId, 'branchId');
  validateChapterCount(job.targetChapterCount);
  const startOrdinal: number = validateChapterOrdinal(job.startChapterOrdinal);
  const currentOrdinal: number = validateChapterOrdinal(job.currentChapterOrdinal);
  const endOrdinal: number = validateChapterOrdinal(job.endChapterOrdinal);
  if (endOrdinal !== startOrdinal + job.targetChapterCount - 1 || currentOrdinal < startOrdinal ||
    currentOrdinal > endOrdinal) throw invalidInput('ghostwrite job 章节范围无效');
  validateCas(job.expectedCas);
  validateModelPolicy(job.modelPolicyAtStart);
  if (!Number.isInteger(job.rewriteCount) || job.rewriteCount < 0 ||
    job.rewriteCount > MAX_GHOSTWRITE_REWRITE_COUNT) throw invalidInput('rewriteCount 无效');
  if (!Number.isInteger(job.claimEpoch) || job.claimEpoch < 0) throw invalidInput('claim epoch 无效');
  requireTimestamp(job.createdAt, 'createdAt');
  requireTimestamp(job.updatedAt, 'updatedAt');
  if (job.authorBrief !== undefined && (job.authorBrief.trim().length === 0 ||
    job.authorBrief !== job.authorBrief.trim() || job.authorBrief.length > 2400)) {
    throw invalidInput('作者修订要求无效');
  }
  if (job.frozenPlan !== null) {
    const plan: FrozenPlan = freezeGhostwritePlan({
      planId: job.frozenPlan.planId,
      content: job.frozenPlan.content,
      upcomingArc: job.frozenPlan.upcomingArc,
      expectedCas: job.frozenPlan.expectedCas,
      digest: (): string => job.frozenPlan?.digest ?? '',
    });
    if (!sameCas(plan.expectedCas, job.expectedCas)) throw invalidInput('job 与计划 CAS 不一致');
  }
  if (job.candidate !== null && job.frozenPlan !== null) {
    makeGhostwriteCandidate({ ...job.candidate, digest: (): string => job.candidate?.digest ?? '' });
    if (job.candidate.planId !== job.frozenPlan.planId || job.candidate.planDigest !== job.frozenPlan.digest) {
      throw invalidInput('candidate 与冻结计划不匹配');
    }
    const executionStage: GhostwriteStage = job.resumeStage ?? job.stage;
    const expectedCandidateAttempt: number = executionStage === 'rewriting_1' || executionStage === 'rewriting_2'
      ? job.rewriteCount - 1 : job.rewriteCount;
    if (job.candidate.chapterOrdinal !== job.currentChapterOrdinal ||
      (job.stage !== 'cancelled' && job.candidate.attempt !== expectedCandidateAttempt)) {
      throw invalidInput('candidate 与当前代笔进度不匹配');
    }
  }
  if (job.review !== null) {
    if (job.candidate === null || job.frozenPlan === null) throw invalidInput('review 缺少候选或计划');
    validateGhostwriteReview(job.review, job.candidate, job.frozenPlan);
  }
  if (job.claim !== null) {
    requireId(job.claim.token, 'claim token');
    if (!Number.isInteger(job.claim.epoch) || job.claim.epoch !== job.claimEpoch ||
      !Number.isInteger(job.claim.leaseUntil) || job.claim.leaseUntil < 0) {
      throw invalidInput('claim 无效');
    }
  }
  for (let i: number = 0; i < job.progress.length; i++) {
    const entry: GhostwriteProgress = job.progress[i];
    if (entry.branchId !== job.branchId || entry.chapterOrdinal < job.startChapterOrdinal ||
      entry.chapterOrdinal > job.endChapterOrdinal || entry.receipt !== ghostwriteReceipt(
        job.jobId, entry.chapterOrdinal, entry.planId, entry.planDigest, entry.candidateId,
      )) {
      throw invalidInput('ghostwrite progress 无效');
    }
    requireId(entry.commitId, 'progress commitId');
  }
  return job;
};

export const ghostwriteReceipt = (
  jobId: string, chapterOrdinal: number, planId: string, planDigest: string, candidateId: string,
): string => {
  requireId(jobId, 'jobId');
  return `ghostwrite:${jobId}:chapter:${validateChapterOrdinal(chapterOrdinal)}` +
    `:plan:${requireId(planId, 'planId')}:${requireId(planDigest, 'planDigest')}` +
    `:candidate:${requireId(candidateId, 'candidateId')}`;
};

const legalTransition = (from: GhostwriteStage, to: GhostwriteStage): boolean => {
  switch (from) {
    case 'planning': return to === 'writing' || to === 'paused' || to === 'waiting_user' ||
      to === 'failed' || to === 'cancelled';
    case 'writing': return to === 'reviewing' || to === 'paused' || to === 'waiting_user' ||
      to === 'failed' || to === 'cancelled';
    case 'reviewing': return to === 'rewriting_1' || to === 'rewriting_2' || to === 'committing' ||
      to === 'paused' || to === 'waiting_user' || to === 'failed' || to === 'cancelled';
    case 'rewriting_1':
    case 'rewriting_2': return to === 'reviewing' || to === 'paused' || to === 'waiting_user' ||
      to === 'failed' || to === 'cancelled';
    case 'committing': return to === 'planning' || to === 'completed' || to === 'failed';
    default: return false;
  }
};

export const canTransitionGhostwrite = (from: GhostwriteStage, to: GhostwriteStage): boolean =>
  legalTransition(from, to);

export const transitionGhostwriteJob = (
  job: DurableGhostwriteJob, nextStage: GhostwriteStage, now: number,
): DurableGhostwriteJob => {
  validateDurableGhostwriteJob(job);
  requireTimestamp(now, 'updatedAt');
  if (!legalTransition(job.stage, nextStage)) {
    throw invalidInput(`ghostwrite 阶段不可从 ${job.stage} 迁移到 ${nextStage}`);
  }
  if (nextStage === 'paused' || nextStage === 'waiting_user') {
    return { ...job, stage: nextStage, resumeStage: job.stage, updatedAt: now };
  }
  return { ...job, stage: nextStage, resumeStage: null, updatedAt: now };
};

export const withGhostwriteCandidate = (
  job: DurableGhostwriteJob, candidate: GhostwriteCandidate, now: number,
): DurableGhostwriteJob => {
  if (job.stage !== 'writing' && job.stage !== 'rewriting_1' && job.stage !== 'rewriting_2') {
    throw invalidInput('当前阶段不可保存候选');
  }
  validateDurableGhostwriteJob(job);
  const checked: GhostwriteCandidate = makeGhostwriteCandidate({
    ...candidate,
    digest: (): string => candidate.digest,
  });
  const plan: FrozenPlan | null = job.frozenPlan;
  if (plan === null || checked.planId !== plan.planId || checked.planDigest !== plan.digest ||
    checked.attempt !== job.rewriteCount || checked.chapterOrdinal !== job.currentChapterOrdinal) {
    throw invalidInput('候选与当前 job 绑定不匹配');
  }
  return {
    ...job,
    stage: 'reviewing',
    candidate: checked,
    review: null,
    authorBrief: undefined,
    resumeStage: null,
    updatedAt: requireTimestamp(now, 'updatedAt'),
  };
};

export const applyGhostwriteReview = (
  job: DurableGhostwriteJob, review: GhostwriteReview, now: number,
): DurableGhostwriteJob => {
  if (job.stage !== 'reviewing' || job.candidate === null || job.frozenPlan === null) {
    throw invalidInput('当前阶段不可应用审核');
  }
  const checked: GhostwriteReview = validateGhostwriteReview(review, job.candidate, job.frozenPlan);
  const updatedAt: number = requireTimestamp(now, 'updatedAt');
  if (checked.blocking) {
    return { ...job, stage: 'failed', review: checked, failure: '审核发现阻塞性问题', updatedAt };
  }
  if (!checked.rewriteRequired) {
    return { ...job, stage: 'committing', review: checked, updatedAt };
  }
  if (job.rewriteCount >= MAX_GHOSTWRITE_REWRITE_COUNT) {
    return { ...job, stage: 'failed', review: checked, failure: '定向重写已达两次上限', updatedAt };
  }
  const rewriteCount: number = job.rewriteCount + 1;
  return {
    ...job,
    stage: rewriteCount === 1 ? 'rewriting_1' : 'rewriting_2',
    review: checked,
    rewriteCount,
    updatedAt,
  };
};

export const claimGhostwriteJob = (
  job: DurableGhostwriteJob, token: string, now: number, leaseMs: number,
): DurableGhostwriteJob => {
  validateDurableGhostwriteJob(job);
  if (isTerminal(job.stage) || job.stage === 'paused' || job.stage === 'waiting_user') {
    throw invalidInput('当前 ghostwrite job 不可 claim');
  }
  requireId(token, 'owner token');
  requireTimestamp(now, 'claim time');
  if (!Number.isInteger(leaseMs) || leaseMs <= 0) throw invalidInput('lease 时长无效');
  if (job.claim !== null && now < job.claim.leaseUntil) throw invalidInput('ghostwrite job 已被其他 owner 占用');
  const epoch: number = job.claimEpoch + 1;
  return {
    ...job,
    claim: { token, epoch, leaseUntil: now + leaseMs },
    claimEpoch: epoch,
    updatedAt: now,
  };
};

export const assertGhostwriteClaim = (
  job: DurableGhostwriteJob, claim: GhostwriteClaimRef, now: number,
): void => {
  requireTimestamp(now, 'claim check time');
  if (job.claim === null || job.claim.token !== claim.token || job.claim.epoch !== claim.epoch ||
    now >= job.claim.leaseUntil) {
    throw invalidInput('ghostwrite owner lease 已失效');
  }
};

export const pauseGhostwriteJob = (
  job: DurableGhostwriteJob, claim: GhostwriteClaimRef, now: number,
): DurableGhostwriteJob => {
  assertGhostwriteClaim(job, claim, now);
  if (isTerminal(job.stage) || job.stage === 'paused') throw invalidInput('当前阶段不可暂停');
  return {
    ...job,
    stage: 'paused',
    resumeStage: job.stage,
    claim: null,
    updatedAt: requireTimestamp(now, 'updatedAt'),
  };
};

export const resumeGhostwriteJob = (job: DurableGhostwriteJob, now: number): DurableGhostwriteJob => {
  if ((job.stage !== 'paused' && job.stage !== 'waiting_user') || job.resumeStage === null ||
    job.resumeStage === 'paused' || job.resumeStage === 'waiting_user' || isTerminal(job.resumeStage)) {
    throw invalidInput('当前 ghostwrite job 不可恢复');
  }
  return {
    ...job,
    stage: job.resumeStage,
    resumeStage: null,
    claim: null,
    updatedAt: requireTimestamp(now, 'updatedAt'),
  };
};

export const projectGhostwriteProgress = (
  job: DurableGhostwriteJob, ancestryCommitIds: ReadonlySet<string>,
): GhostwriteProgress[] => job.progress.filter((entry: GhostwriteProgress): boolean =>
  entry.branchId === job.branchId && ancestryCommitIds.has(entry.commitId) &&
    entry.receipt === ghostwriteReceipt(
      job.jobId, entry.chapterOrdinal, entry.planId, entry.planDigest, entry.candidateId,
    ),
);
