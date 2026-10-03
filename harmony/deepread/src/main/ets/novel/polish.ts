import { advancePolishOutcome, projectPolishOutcomes, retryPolishOutcomes } from './polish_outcomes.ts';
// polish — C7 durable batch-polish pure domain.
// Repository owns persistence and atomic workspace writes; runner owns model execution.

import type { WorkspaceCas } from './workspace_history.ts';
import type { NovelModelPolicy, NovelModelTarget } from './models.ts';
import type { GhostwriteClaim, GhostwriteClaimRef, GhostwriteFinding } from './ghostwrite.ts';
import { MAX_GHOSTWRITE_REWRITE_COUNT, defaultGhostwriteDigest } from './ghostwrite.ts';
import { invalidInput } from './error.ts';

export const MAX_POLISH_CONTEXT_ITEMS: number = 32;
export const MAX_POLISH_CONTEXT_ITEM_CHARS: number = 8_000;
export const MAX_POLISH_CONTEXT_TOTAL_CHARS: number = 24_000;

export type PolishChapterOutcome = 'success' | 'driftSkipped' | 'failed' | 'unprocessed';
export interface PolishChapterResult { chapterId: string; chapterOrdinal: number; status: PolishChapterOutcome; message: string | null; updatedAt: number; }

export type PolishStage =
  | 'queued'
  | 'writing'
  | 'reviewing'
  | 'rewriting_1'
  | 'rewriting_2'
  | 'committing'
  | 'waiting_system'
  | 'paused'
  | 'failed'
  | 'cancelled'
  | 'completed';

export interface PolishContextOptions {
  includePlot: boolean;
  includeForeshadows: boolean;
  includeCharacters: boolean;
  includeDecisions: boolean;
}

export type PolishContextSnapshotKind = 'plot' | 'foreshadow' | 'character' | 'decision' | 'material';

export interface PolishContextSnapshotItem {
  kind: PolishContextSnapshotKind;
  sourcePath: string;
  digest: string;
  content: string;
}

export interface MakePolishContextSnapshotItemInput {
  kind: PolishContextSnapshotKind;
  sourcePath: string;
  content: string;
  digest?: (content: string) => string;
}

export interface PolishChapterTarget {
  id: string;
  ordinal: number;
  title: string;
  sourceContent: string;
  sourceDigest: string;
}

export interface MakePolishChapterTargetInput {
  id: string;
  ordinal: number;
  title: string;
  sourceContent: string;
  digest?: (content: string) => string;
}

export type PolishWarningKind = 'plot_stale' | 'unresolved';

export interface PolishWarning {
  kind: PolishWarningKind;
  message: string;
}

export interface PolishCandidate {
  jobId: string;
  candidateId: string;
  chapterId: string;
  chapterOrdinal: number;
  sourceDigest: string;
  content: string;
  digest: string;
  attempt: number;
}

export interface MakePolishCandidateInput {
  jobId: string;
  candidateId: string;
  chapterId: string;
  chapterOrdinal: number;
  sourceDigest: string;
  content: string;
  attempt: number;
  digest?: (content: string) => string;
}

export interface PolishReview {
  jobId: string;
  chapterId: string;
  chapterOrdinal: number;
  sourceDigest: string;
  candidateId: string;
  candidateDigest: string;
  findings: GhostwriteFinding[];
  blocking: boolean;
  rewriteRequired: boolean;
  rewriteInstructions: string;
}

export interface PolishProgress {
  chapterId: string;
  chapterOrdinal: number;
  sourceDigest: string;
  candidateId: string;
  candidateDigest: string;
  receipt: string;
  commitId: string;
  branchId: string;
}

export interface DurablePolishJob {
  version: 1;
  kind: 'batch_polish';
  jobId: string;
  projectId: string;
  branchId: string;
  startOrdinal: number;
  endOrdinal: number;
  targets: PolishChapterTarget[];
  cursor: number;
  contextOptions: PolishContextOptions;
  contextSnapshot: PolishContextSnapshotItem[];
  warnings: PolishWarning[];
  stage: PolishStage;
  resumeStage: PolishStage | null;
  expectedCas: WorkspaceCas;
  candidate: PolishCandidate | null;
  review: PolishReview | null;
  rewriteCount: number;
  claim: GhostwriteClaim | null;
  claimEpoch: number;
  modelPolicyAtStart: NovelModelPolicy;
  progress: PolishProgress[];
  polishPreferenceAtStart?: string;
  outcomes?: PolishChapterResult[];
  retryChapterIds?: string[];
  failure: string | null;
  createdAt: number;
  updatedAt: number;
}

export interface MakePolishJobInput {
  jobId: string;
  projectId: string;
  branchId: string;
  targets: PolishChapterTarget[];
  contextOptions: PolishContextOptions;
  contextSnapshot: PolishContextSnapshotItem[];
  warnings: PolishWarning[];
  expectedCas: WorkspaceCas;
  modelPolicyAtStart: NovelModelPolicy;
  polishPreferenceAtStart?: string;
  now: number;
}

export interface CommitPolishChapterInput {
  branchId: string;
  commitId: string;
  receipt: string;
  nextCas: WorkspaceCas;
}

const ACTIVE_STAGES: PolishStage[] = [
  'queued', 'writing', 'reviewing', 'rewriting_1', 'rewriting_2', 'committing',
];
const SUSPENDED_STAGES: PolishStage[] = ['waiting_system', 'paused', 'failed'];
const TERMINAL_STAGES: PolishStage[] = ['cancelled', 'completed'];
const ALL_STAGES: PolishStage[] = [
  'queued', 'writing', 'reviewing', 'rewriting_1', 'rewriting_2', 'committing',
  'waiting_system', 'paused', 'failed', 'cancelled', 'completed',
];
const FINDING_KINDS: string[] = [
  'missing_required', 'forbidden_violation', 'hard_continuity', 'non_blocking',
];

const isActive = (stage: PolishStage): boolean => ACTIVE_STAGES.includes(stage);
const isTerminal = (stage: PolishStage): boolean => TERMINAL_STAGES.includes(stage);

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

const requireOrdinal = (value: number, field: string): number => {
  if (!Number.isInteger(value) || value < 1) throw invalidInput(`${field} 无效`);
  return value;
};

const validateCas = (cas: WorkspaceCas, branchId?: string): WorkspaceCas => {
  requireId(cas.branchId, 'CAS branchId');
  requireId(cas.head, 'CAS head');
  requireId(cas.treeDigest, 'CAS treeDigest');
  if (branchId !== undefined && cas.branchId !== branchId) throw invalidInput('CAS 分支与 job 分支不一致');
  return cas;
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

const requireSourcePath = (value: string): string => {
  requireId(value, 'context sourcePath');
  if (value.startsWith('/') || value.indexOf('\\') >= 0) throw invalidInput('context sourcePath 越界');
  const segments: string[] = value.split('/');
  if (segments.some((segment: string): boolean => segment.length === 0 || segment === '.' || segment === '..')) {
    throw invalidInput('context sourcePath 越界');
  }
  return value;
};

const contextKindEnabled = (kind: PolishContextSnapshotKind, options: PolishContextOptions): boolean => {
  switch (kind) {
    case 'plot': return options.includePlot;
    case 'foreshadow': return options.includeForeshadows;
    case 'character': return options.includeCharacters;
    case 'decision': return options.includeDecisions;
    case 'material': return true;
  }
};

const validateContextOptions = (options: PolishContextOptions): PolishContextOptions => {
  if ((options.includePlot !== true && options.includePlot !== false) ||
    (options.includeForeshadows !== true && options.includeForeshadows !== false) ||
    (options.includeCharacters !== true && options.includeCharacters !== false) ||
    (options.includeDecisions !== true && options.includeDecisions !== false)) {
    throw invalidInput('polish context options 无效');
  }
  return options;
};

const validateContextItem = (item: PolishContextSnapshotItem): PolishContextSnapshotItem => {
  if (item.kind !== 'plot' && item.kind !== 'foreshadow' && item.kind !== 'character' &&
    item.kind !== 'decision' && item.kind !== 'material') throw invalidInput('context kind 无效');
  requireSourcePath(item.sourcePath);
  requireId(item.digest, 'context digest');
  if (item.content.trim().length === 0 || item.content.length > MAX_POLISH_CONTEXT_ITEM_CHARS) {
    throw invalidInput('context content 无效或超限');
  }
  if (item.digest !== defaultGhostwriteDigest(item.content)) {
    throw invalidInput('context digest 与 content 不匹配');
  }
  return item;
};

const validateContextSnapshot = (
  items: PolishContextSnapshotItem[], options: PolishContextOptions,
): PolishContextSnapshotItem[] => {
  if (items.length > MAX_POLISH_CONTEXT_ITEMS) throw invalidInput('context snapshot 条目超限');
  const paths: Set<string> = new Set<string>();
  let totalChars: number = 0;
  for (let i: number = 0; i < items.length; i++) {
    const item: PolishContextSnapshotItem = validateContextItem(items[i]);
    if (!contextKindEnabled(item.kind, options)) throw invalidInput('context snapshot 包含未启用来源');
    if (paths.has(item.sourcePath)) throw invalidInput('context sourcePath 重复');
    paths.add(item.sourcePath);
    totalChars += item.content.length;
  }
  if (totalChars > MAX_POLISH_CONTEXT_TOTAL_CHARS) throw invalidInput('context snapshot 总量超限');
  return items;
};

export const makePolishContextSnapshotItem = (
  input: MakePolishContextSnapshotItemInput,
): PolishContextSnapshotItem => {
  const content: string = input.content;
  if (content.trim().length === 0 || content.length > MAX_POLISH_CONTEXT_ITEM_CHARS) {
    throw invalidInput('context content 无效或超限');
  }
  const digest: string = (input.digest ?? defaultGhostwriteDigest)(content);
  const item: PolishContextSnapshotItem = {
    kind: input.kind,
    sourcePath: requireSourcePath(input.sourcePath),
    digest: requireId(digest, 'context digest'),
    content,
  };
  return validateContextItem(item);
};

const validateTarget = (target: PolishChapterTarget): PolishChapterTarget => {
  requireId(target.id, 'chapter id');
  requireOrdinal(target.ordinal, 'chapter ordinal');
  if (target.title.trim().length === 0) throw invalidInput('chapter title 为空');
  if (target.sourceContent.trim().length === 0) throw invalidInput('chapter sourceContent 为空');
  requireId(target.sourceDigest, 'chapter sourceDigest');
  if (target.sourceDigest !== defaultGhostwriteDigest(target.sourceContent)) {
    throw invalidInput('chapter sourceDigest 与 sourceContent 不匹配');
  }
  return target;
};

export const makePolishChapterTarget = (input: MakePolishChapterTargetInput): PolishChapterTarget => {
  if (input.sourceContent.trim().length === 0) throw invalidInput('chapter sourceContent 为空');
  const target: PolishChapterTarget = {
    id: requireId(input.id, 'chapter id'),
    ordinal: requireOrdinal(input.ordinal, 'chapter ordinal'),
    title: input.title.trim(),
    sourceContent: input.sourceContent,
    sourceDigest: requireId(
      (input.digest ?? defaultGhostwriteDigest)(input.sourceContent), 'chapter sourceDigest',
    ),
  };
  return validateTarget(target);
};

const validateTargets = (targets: PolishChapterTarget[]): PolishChapterTarget[] => {
  if (targets.length === 0) throw invalidInput('至少选择一个润色章节');
  const ids: Set<string> = new Set<string>();
  const ordinals: Set<number> = new Set<number>();
  for (let i: number = 0; i < targets.length; i++) {
    const target: PolishChapterTarget = validateTarget(targets[i]);
    if (ids.has(target.id) || ordinals.has(target.ordinal)) throw invalidInput('润色章节目标重复');
    if (i > 0 && target.ordinal <= targets[i - 1].ordinal) {
      throw invalidInput('润色章节必须按 ordinal 升序排列');
    }
    ids.add(target.id);
    ordinals.add(target.ordinal);
  }
  return targets;
};

const validateWarnings = (warnings: PolishWarning[]): PolishWarning[] => {
  const kinds: Set<string> = new Set<string>();
  for (let i: number = 0; i < warnings.length; i++) {
    const warning: PolishWarning = warnings[i];
    if (warning.kind !== 'plot_stale' && warning.kind !== 'unresolved') {
      throw invalidInput('polish warning kind 无效');
    }
    if (warning.message.trim().length === 0 || kinds.has(warning.kind)) {
      throw invalidInput('polish warning 无效或重复');
    }
    kinds.add(warning.kind);
  }
  return warnings;
};

export const makePolishCandidate = (input: MakePolishCandidateInput): PolishCandidate => {
  if (!Number.isInteger(input.attempt) || input.attempt < 0 ||
    input.attempt > MAX_GHOSTWRITE_REWRITE_COUNT) throw invalidInput('candidate attempt 无效');
  if (input.content.trim().length === 0) throw invalidInput('candidate 正文为空');
  return {
    jobId: requireId(input.jobId, 'candidate jobId'),
    candidateId: requireId(input.candidateId, 'candidateId'),
    chapterId: requireId(input.chapterId, 'candidate chapterId'),
    chapterOrdinal: requireOrdinal(input.chapterOrdinal, 'candidate chapterOrdinal'),
    sourceDigest: requireId(input.sourceDigest, 'candidate sourceDigest'),
    content: input.content,
    digest: requireId((input.digest ?? defaultGhostwriteDigest)(input.content), 'candidate digest'),
    attempt: input.attempt,
  };
};

const validateCandidate = (candidate: PolishCandidate): PolishCandidate => {
  requireId(candidate.jobId, 'candidate jobId');
  requireId(candidate.candidateId, 'candidateId');
  requireId(candidate.chapterId, 'candidate chapterId');
  requireOrdinal(candidate.chapterOrdinal, 'candidate chapterOrdinal');
  requireId(candidate.sourceDigest, 'candidate sourceDigest');
  requireId(candidate.digest, 'candidate digest');
  if (!Number.isInteger(candidate.attempt) || candidate.attempt < 0 ||
    candidate.attempt > MAX_GHOSTWRITE_REWRITE_COUNT) throw invalidInput('candidate attempt 无效');
  if (candidate.content.trim().length === 0) throw invalidInput('candidate 正文为空');
  if (candidate.digest !== defaultGhostwriteDigest(candidate.content)) {
    throw invalidInput('candidate digest 与 content 不匹配');
  }
  return candidate;
};

const validateFinding = (finding: GhostwriteFinding): GhostwriteFinding => {
  if (!FINDING_KINDS.includes(finding.kind)) throw invalidInput('review finding kind 无效');
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

export const validatePolishReview = (
  review: PolishReview, candidate: PolishCandidate,
): PolishReview => {
  validateCandidate(candidate);
  if (review.jobId !== candidate.jobId || review.chapterId !== candidate.chapterId ||
    review.chapterOrdinal !== candidate.chapterOrdinal ||
    review.sourceDigest !== candidate.sourceDigest || review.candidateId !== candidate.candidateId ||
    review.candidateDigest !== candidate.digest) {
    throw invalidInput('review 与 chapter/source/candidate 绑定不匹配');
  }
  for (let i: number = 0; i < review.findings.length; i++) validateFinding(review.findings[i]);
  if (review.blocking && !hasBlockingFinding(review.findings)) {
    throw invalidInput('non_blocking finding 不可阻塞润色');
  }
  if (review.blocking && review.rewriteRequired) throw invalidInput('blocking 与 rewriteRequired 不可同时成立');
  if (review.rewriteRequired && review.rewriteInstructions.trim().length === 0) {
    throw invalidInput('定向重写必须提供说明');
  }
  return review;
};

const copyJob = (
  job: DurablePolishJob,
  stage: PolishStage,
  resumeStage: PolishStage | null,
  expectedCas: WorkspaceCas,
  cursor: number,
  candidate: PolishCandidate | null,
  review: PolishReview | null,
  rewriteCount: number,
  claim: GhostwriteClaim | null,
  progress: PolishProgress[],
  failure: string | null,
  updatedAt: number,
): DurablePolishJob => ({
  version: job.version,
  kind: job.kind,
  jobId: job.jobId,
  projectId: job.projectId,
  branchId: job.branchId,
  startOrdinal: job.startOrdinal,
  endOrdinal: job.endOrdinal,
  targets: job.targets,
  cursor,
  contextOptions: job.contextOptions,
  contextSnapshot: job.contextSnapshot,
  warnings: job.warnings,
  stage,
  resumeStage,
  expectedCas,
  candidate,
  review,
  rewriteCount,
  claim,
  claimEpoch: job.claimEpoch,
  modelPolicyAtStart: job.modelPolicyAtStart,
  progress,
  polishPreferenceAtStart: job.polishPreferenceAtStart,
  outcomes: job.outcomes,
  retryChapterIds: job.retryChapterIds,
  failure,
  createdAt: job.createdAt,
  updatedAt,
});

export const makePolishJob = (input: MakePolishJobInput): DurablePolishJob => {
  const targets: PolishChapterTarget[] = validateTargets(input.targets.slice());
  const now: number = requireTimestamp(input.now, 'createdAt');
  const branchId: string = requireId(input.branchId, 'branchId');
  const contextOptions: PolishContextOptions = validateContextOptions(input.contextOptions);
  const job: DurablePolishJob = {
    version: 1,
    kind: 'batch_polish',
    jobId: requireId(input.jobId, 'jobId'),
    projectId: requireId(input.projectId, 'projectId'),
    branchId,
    startOrdinal: targets[0].ordinal,
    endOrdinal: targets[targets.length - 1].ordinal,
    targets,
    cursor: 0,
    contextOptions,
    contextSnapshot: validateContextSnapshot(input.contextSnapshot.slice(), contextOptions),
    warnings: validateWarnings(input.warnings.slice()),
    stage: 'queued',
    resumeStage: null,
    expectedCas: validateCas(input.expectedCas, branchId),
    candidate: null,
    review: null,
    rewriteCount: 0,
    claim: null,
    claimEpoch: 0,
    modelPolicyAtStart: validateModelPolicy(input.modelPolicyAtStart),
    progress: [],
    polishPreferenceAtStart: input.polishPreferenceAtStart ?? '',
    outcomes: targets.map(target => ({ chapterId: target.id, chapterOrdinal: target.ordinal, status: 'unprocessed', message: null, updatedAt: now })),
    failure: null,
    createdAt: now,
    updatedAt: now,
  };
  return validateDurablePolishJob(job);
};

export const currentPolishTarget = (job: DurablePolishJob): PolishChapterTarget | null => {
  if (job.cursor < 0 || job.cursor >= job.targets.length) return null;
  return job.targets[job.cursor];
};

const effectiveExecutionStage = (job: DurablePolishJob): PolishStage => {
  if (SUSPENDED_STAGES.includes(job.stage) && job.resumeStage !== null) return job.resumeStage;
  return job.stage;
};

const validateProgress = (job: DurablePolishJob): void => {
  if (job.outcomes === undefined && job.progress.length !== job.cursor) throw invalidInput('polish progress 与 cursor 不一致');
  if (job.outcomes !== undefined && job.progress.length !== job.outcomes.filter(item => item.status === 'success').length) throw invalidInput('polish 成功结果与 receipt 不一致');
  const commits: Set<string> = new Set<string>();
  const chapters: Set<string> = new Set<string>();
  for (let i: number = 0; i < job.progress.length; i++) {
    const entry: PolishProgress = job.progress[i];
    const target: PolishChapterTarget | undefined = job.outcomes === undefined ? job.targets[i] : job.targets.find(item => item.id === entry.chapterId);
    if (target === undefined || entry.chapterId !== target.id || entry.chapterOrdinal !== target.ordinal ||
      entry.sourceDigest !== target.sourceDigest || entry.branchId !== job.branchId ||
      entry.receipt !== polishReceipt(
        job.jobId, entry.chapterId, entry.chapterOrdinal, entry.sourceDigest,
        entry.candidateId, entry.candidateDigest,
      )) throw invalidInput('polish progress 绑定或 receipt 无效');
    if (chapters.has(entry.chapterId) || (job.outcomes !== undefined
      && job.outcomes.find(item => item.chapterId === entry.chapterId)?.status !== 'success')) throw invalidInput('polish 成功 receipt 章节重复或结果不匹配');
    chapters.add(entry.chapterId);
    requireId(entry.commitId, 'progress commitId');
    if (commits.has(entry.commitId)) throw invalidInput('polish progress commitId 重复');
    commits.add(entry.commitId);
  }
};

export const validateDurablePolishJob = (job: DurablePolishJob): DurablePolishJob => {
  if (job.version !== 1 || job.kind !== 'batch_polish') throw invalidInput('polish job 版本或 kind 不受支持');
  requireId(job.jobId, 'jobId');
  requireId(job.projectId, 'projectId');
  requireId(job.branchId, 'branchId');
  validateTargets(job.targets);
  if (job.startOrdinal !== job.targets[0].ordinal ||
    job.endOrdinal !== job.targets[job.targets.length - 1].ordinal) {
    throw invalidInput('polish job 闭区间无效');
  }
  if (!Number.isInteger(job.cursor) || job.cursor < 0 || job.cursor > job.targets.length) {
    throw invalidInput('polish cursor 无效');
  }
  if (!ALL_STAGES.includes(job.stage)) throw invalidInput('polish stage 无效');
  validateContextOptions(job.contextOptions);
  validateContextSnapshot(job.contextSnapshot, job.contextOptions);
  validateWarnings(job.warnings);
  validateCas(job.expectedCas, job.branchId);
  validateModelPolicy(job.modelPolicyAtStart);
  requireTimestamp(job.createdAt, 'createdAt');
  requireTimestamp(job.updatedAt, 'updatedAt');
  if (job.updatedAt < job.createdAt) throw invalidInput('updatedAt 早于 createdAt');
  if (!Number.isInteger(job.rewriteCount) || job.rewriteCount < 0 ||
    job.rewriteCount > MAX_GHOSTWRITE_REWRITE_COUNT) throw invalidInput('rewriteCount 无效');
  if (!Number.isInteger(job.claimEpoch) || job.claimEpoch < 0) throw invalidInput('claim epoch 无效');
  if (job.polishPreferenceAtStart !== undefined && typeof job.polishPreferenceAtStart !== 'string') throw invalidInput('冻结润色偏好无效');
  if (job.outcomes !== undefined) {
    if (job.outcomes.length !== job.targets.length) throw invalidInput('润色逐章结果不完整');
    job.outcomes.forEach((item, index) => {
      const target = job.targets[index];
      if (item.chapterId !== target.id || item.chapterOrdinal !== target.ordinal || !['success', 'driftSkipped', 'failed', 'unprocessed'].includes(item.status)
        || (item.message !== null && typeof item.message !== 'string')) throw invalidInput('润色逐章结果无效');
      requireTimestamp(item.updatedAt, 'outcome time');
    });
    if (job.retryChapterIds !== undefined && (new Set(job.retryChapterIds).size !== job.retryChapterIds.length
      || job.retryChapterIds.some(id => !job.targets.some(target => target.id === id)))) throw invalidInput('润色重试集合无效');
    if (job.cursor < job.targets.length && job.stage !== 'cancelled' && job.stage !== 'failed'
      && job.outcomes[job.cursor].status !== 'unprocessed') throw invalidInput('润色游标不能指向已处理章节');
    if (job.stage === 'completed' && job.outcomes.some(item => item.status === 'unprocessed'
      && (job.retryChapterIds === undefined || job.retryChapterIds.includes(item.chapterId)))) throw invalidInput('完成的润色批次仍有待处理目标');
  }
  validateProgress(job);

  if (job.stage === 'completed') {
    if (job.cursor !== job.targets.length || job.candidate !== null || job.review !== null || job.claim !== null) {
      throw invalidInput('completed polish job 状态无效');
    }
  } else if (job.cursor >= job.targets.length) {
    throw invalidInput('未完成 polish job cursor 已越过范围');
  }

  if (SUSPENDED_STAGES.includes(job.stage)) {
    if (job.resumeStage === null || !isActive(job.resumeStage) || job.claim !== null) {
      throw invalidInput('polish job 暂停/失败恢复状态无效');
    }
  } else if (job.resumeStage !== null) {
    throw invalidInput('当前 polish stage 不应保存 resumeStage');
  }
  if (job.stage === 'failed') {
    if (job.failure === null || job.failure.trim().length === 0) throw invalidInput('failed job 缺少 failure');
  } else if (job.failure !== null) {
    throw invalidInput('非 failed job 不应保存 failure');
  }
  if (isTerminal(job.stage) && job.claim !== null) throw invalidInput('终态 polish job 不可持有 claim');

  const target: PolishChapterTarget | null = currentPolishTarget(job);
  if (job.candidate !== null) {
    const candidate: PolishCandidate = validateCandidate(job.candidate);
    if (target === null || candidate.jobId !== job.jobId || candidate.chapterId !== target.id ||
      candidate.chapterOrdinal !== target.ordinal ||
      candidate.sourceDigest !== target.sourceDigest) throw invalidInput('candidate 与当前章节快照不匹配');
    const executionStage: PolishStage = effectiveExecutionStage(job);
    const expectedAttempt: number = executionStage === 'rewriting_1' || executionStage === 'rewriting_2'
      ? job.rewriteCount - 1 : job.rewriteCount;
    if (job.stage !== 'cancelled' && candidate.attempt !== expectedAttempt) {
      throw invalidInput('candidate attempt 与 job 不匹配');
    }
  }
  if (job.review !== null) {
    if (job.candidate === null) throw invalidInput('review 缺少 candidate');
    validatePolishReview(job.review, job.candidate);
  }
  const executionStage: PolishStage = effectiveExecutionStage(job);
  if (job.stage !== 'cancelled' && job.stage !== 'completed') {
    if ((executionStage === 'queued' || executionStage === 'writing') &&
      (job.candidate !== null || job.review !== null)) {
      throw invalidInput('queued/writing job 不应保存 candidate 或 review');
    }
    if (executionStage === 'reviewing' && job.candidate === null) {
      throw invalidInput('reviewing job 缺少 candidate');
    }
    if ((executionStage === 'rewriting_1' || executionStage === 'rewriting_2') &&
      (job.candidate === null || job.review === null || !job.review.rewriteRequired)) {
      throw invalidInput('rewriting job 缺少定向重写依据');
    }
    if (executionStage === 'committing' &&
      (job.candidate === null || job.review === null || job.review.blocking || job.review.rewriteRequired)) {
      throw invalidInput('committing job 缺少可提交 review');
    }
  }
  if (job.claim !== null) {
    requireId(job.claim.token, 'claim token');
    if (!Number.isInteger(job.claim.epoch) || job.claim.epoch !== job.claimEpoch ||
      !Number.isInteger(job.claim.leaseUntil) || job.claim.leaseUntil < 0) throw invalidInput('claim 无效');
  }
  return job;
};

const legalTransition = (from: PolishStage, to: PolishStage): boolean => {
  switch (from) {
    case 'queued': return to === 'writing';
    case 'writing': return to === 'reviewing';
    case 'reviewing': return to === 'rewriting_1' || to === 'rewriting_2' || to === 'committing';
    case 'rewriting_1':
    case 'rewriting_2': return to === 'reviewing';
    case 'committing': return to === 'queued' || to === 'completed';
    default: return false;
  }
};

export const canTransitionPolish = (from: PolishStage, to: PolishStage): boolean =>
  legalTransition(from, to);

export const transitionPolishJob = (
  job: DurablePolishJob, nextStage: PolishStage, now: number,
): DurablePolishJob => {
  validateDurablePolishJob(job);
  if (!legalTransition(job.stage, nextStage)) {
    throw invalidInput(`polish 阶段不可从 ${job.stage} 迁移到 ${nextStage}`);
  }
  return copyJob(
    job, nextStage, null, job.expectedCas, job.cursor, job.candidate, job.review,
    job.rewriteCount, job.claim, job.progress, null, requireTimestamp(now, 'updatedAt'),
  );
};

export const withPolishCandidate = (
  job: DurablePolishJob, candidate: PolishCandidate, now: number,
): DurablePolishJob => {
  validateDurablePolishJob(job);
  if (job.stage !== 'writing' && job.stage !== 'rewriting_1' && job.stage !== 'rewriting_2') {
    throw invalidInput('当前阶段不可保存 polish candidate');
  }
  const checked: PolishCandidate = validateCandidate(candidate);
  const target: PolishChapterTarget | null = currentPolishTarget(job);
  if (target === null || checked.jobId !== job.jobId || checked.chapterId !== target.id ||
    checked.chapterOrdinal !== target.ordinal ||
    checked.sourceDigest !== target.sourceDigest || checked.attempt !== job.rewriteCount) {
    throw invalidInput('candidate 与当前章节/source/attempt 绑定不匹配');
  }
  return copyJob(
    job, 'reviewing', null, job.expectedCas, job.cursor, checked, null,
    job.rewriteCount, job.claim, job.progress, null, requireTimestamp(now, 'updatedAt'),
  );
};

export const applyPolishReview = (
  job: DurablePolishJob, review: PolishReview, now: number,
): DurablePolishJob => {
  validateDurablePolishJob(job);
  if (job.stage !== 'reviewing' || job.candidate === null) throw invalidInput('当前阶段不可应用 polish review');
  const checked: PolishReview = validatePolishReview(review, job.candidate);
  const updatedAt: number = requireTimestamp(now, 'updatedAt');
  if (checked.blocking) {
    return copyJob(
      job, 'failed', 'reviewing', job.expectedCas, job.cursor, job.candidate, checked,
      job.rewriteCount, null, job.progress, '审核发现阻塞性问题', updatedAt,
    );
  }
  if (!checked.rewriteRequired) {
    return copyJob(
      job, 'committing', null, job.expectedCas, job.cursor, job.candidate, checked,
      job.rewriteCount, job.claim, job.progress, null, updatedAt,
    );
  }
  if (job.rewriteCount >= MAX_GHOSTWRITE_REWRITE_COUNT) {
    return copyJob(
      job, 'failed', 'reviewing', job.expectedCas, job.cursor, job.candidate, checked,
      job.rewriteCount, null, job.progress, '定向重写已达两次上限', updatedAt,
    );
  }
  const rewriteCount: number = job.rewriteCount + 1;
  return copyJob(
    job, rewriteCount === 1 ? 'rewriting_1' : 'rewriting_2', null,
    job.expectedCas, job.cursor, job.candidate, checked, rewriteCount,
    job.claim, job.progress, null, updatedAt,
  );
};

export const claimPolishJob = (
  job: DurablePolishJob, token: string, now: number, leaseMs: number,
): DurablePolishJob => {
  validateDurablePolishJob(job);
  if (!isActive(job.stage)) throw invalidInput('当前 polish job 不可 claim');
  requireId(token, 'owner token');
  requireTimestamp(now, 'claim time');
  if (!Number.isInteger(leaseMs) || leaseMs <= 0) throw invalidInput('lease 时长无效');
  if (job.claim !== null && now < job.claim.leaseUntil) throw invalidInput('polish job 已被其他 owner 占用');
  const epoch: number = job.claimEpoch + 1;
  const claimed: DurablePolishJob = copyJob(
    job, job.stage, null, job.expectedCas, job.cursor, job.candidate, job.review,
    job.rewriteCount, { token, epoch, leaseUntil: now + leaseMs }, job.progress, null, now,
  );
  claimed.claimEpoch = epoch;
  return claimed;
};

export const assertPolishClaim = (
  job: DurablePolishJob, claim: GhostwriteClaimRef, now: number,
): void => {
  requireTimestamp(now, 'claim check time');
  if (job.claim === null || job.claim.token !== claim.token || job.claim.epoch !== claim.epoch ||
    now >= job.claim.leaseUntil) throw invalidInput('polish owner lease 已失效');
};

const suspendPolishJob = (
  job: DurablePolishJob, claim: GhostwriteClaimRef, stage: 'paused' | 'waiting_system', now: number,
): DurablePolishJob => {
  validateDurablePolishJob(job);
  assertPolishClaim(job, claim, now);
  if (!isActive(job.stage)) throw invalidInput('当前 polish job 不可暂停');
  return copyJob(
    job, stage, job.stage, job.expectedCas, job.cursor, job.candidate, job.review,
    job.rewriteCount, null, job.progress, null, requireTimestamp(now, 'updatedAt'),
  );
};

export const pausePolishJob = (
  job: DurablePolishJob, claim: GhostwriteClaimRef, now: number,
): DurablePolishJob => suspendPolishJob(job, claim, 'paused', now);

export const yieldPolishJobToSystem = (
  job: DurablePolishJob, claim: GhostwriteClaimRef, now: number,
): DurablePolishJob => suspendPolishJob(job, claim, 'waiting_system', now);

export const resumePolishJob = (job: DurablePolishJob, now: number): DurablePolishJob => {
  validateDurablePolishJob(job);
  if ((job.stage !== 'paused' && job.stage !== 'waiting_system') || job.resumeStage === null) {
    throw invalidInput('当前 polish job 不可恢复');
  }
  return copyJob(
    job, job.resumeStage, null, job.expectedCas, job.cursor, job.candidate, job.review,
    job.rewriteCount, null, job.progress, null, requireTimestamp(now, 'updatedAt'),
  );
};

export const failPolishJob = (
  job: DurablePolishJob, claim: GhostwriteClaimRef, failure: string, now: number,
): DurablePolishJob => {
  validateDurablePolishJob(job);
  assertPolishClaim(job, claim, now);
  if (!isActive(job.stage) || failure.trim().length === 0) throw invalidInput('当前 polish job 不可标记失败');
  const failed = copyJob(
    job, 'failed', job.stage, job.expectedCas, job.cursor, job.candidate, job.review,
    job.rewriteCount, null, job.progress, failure.trim(), requireTimestamp(now, 'updatedAt'),
  );
  return job.outcomes === undefined ? failed : { ...failed, outcomes: projectPolishOutcomes(job).map((item, index) =>
    index === job.cursor ? { ...item, status: 'failed', message: failure.trim(), updatedAt: now } : item) };
};

export const retryPolishJob = (job: DurablePolishJob, now: number, chapterIds?: string[]): DurablePolishJob => {
  validateDurablePolishJob(job);
  if (job.outcomes !== undefined || chapterIds !== undefined) return retryPolishOutcomes(job, requireTimestamp(now, 'updatedAt'), chapterIds);
  if (job.stage !== 'failed' || job.resumeStage === null) throw invalidInput('当前 polish job 不可重试');
  return copyJob(
    job, job.resumeStage, null, job.expectedCas, job.cursor, job.candidate, job.review,
    job.rewriteCount, null, job.progress, null, requireTimestamp(now, 'updatedAt'),
  );
};

export const cancelPolishJob = (job: DurablePolishJob, now: number): DurablePolishJob => {
  validateDurablePolishJob(job);
  if (isTerminal(job.stage)) throw invalidInput('当前 polish job 已是终态');
  return copyJob(
    job, 'cancelled', null, job.expectedCas, job.cursor, job.candidate, job.review,
    job.rewriteCount, null, job.progress, null, requireTimestamp(now, 'updatedAt'),
  );
};

export const polishReceipt = (
  jobId: string,
  chapterId: string,
  chapterOrdinal: number,
  sourceDigest: string,
  candidateId: string,
  candidateDigest: string,
): string => `batch-polish:${requireId(jobId, 'jobId')}` +
  `:chapter:${requireId(chapterId, 'chapterId')}:${requireOrdinal(chapterOrdinal, 'chapterOrdinal')}` +
  `:source:${requireId(sourceDigest, 'sourceDigest')}` +
  `:candidate:${requireId(candidateId, 'candidateId')}:${requireId(candidateDigest, 'candidateDigest')}`;

export const commitPolishChapter = (
  job: DurablePolishJob,
  claim: GhostwriteClaimRef,
  input: CommitPolishChapterInput,
  now: number,
): DurablePolishJob => {
  validateDurablePolishJob(job);
  assertPolishClaim(job, claim, now);
  if (job.stage !== 'committing' || job.candidate === null || job.review === null ||
    job.review.blocking || job.review.rewriteRequired) throw invalidInput('当前 polish job 不可提交章节');
  const candidate: PolishCandidate = job.candidate;
  const expectedReceipt: string = polishReceipt(
    job.jobId, candidate.chapterId, candidate.chapterOrdinal, candidate.sourceDigest,
    candidate.candidateId, candidate.digest,
  );
  requireId(input.commitId, 'commitId');
  if (input.branchId !== job.branchId || input.receipt !== expectedReceipt) {
    throw invalidInput('polish commit 分支或 receipt 不匹配');
  }
  const nextCas: WorkspaceCas = validateCas(input.nextCas, job.branchId);
  if (nextCas.head !== input.commitId) throw invalidInput('polish commitId 与 next CAS head 不匹配');
  const progress: PolishProgress[] = job.progress.slice();
  progress.push({
    chapterId: candidate.chapterId,
    chapterOrdinal: candidate.chapterOrdinal,
    sourceDigest: candidate.sourceDigest,
    candidateId: candidate.candidateId,
    candidateDigest: candidate.digest,
    receipt: input.receipt,
    commitId: input.commitId,
    branchId: input.branchId,
  });
  if (job.outcomes !== undefined) return advancePolishOutcome(job, 'success', null, requireTimestamp(now, 'updatedAt'), nextCas, progress);
  const cursor: number = job.cursor + 1;
  const completed: boolean = cursor === job.targets.length;
  return copyJob(
    job, completed ? 'completed' : 'queued', null, nextCas, cursor, null, null, 0,
    completed ? null : job.claim, progress, null, requireTimestamp(now, 'updatedAt'),
  );
};

export const projectPolishProgress = (
  job: DurablePolishJob, ancestryCommitIds: ReadonlySet<string>,
): PolishProgress[] => job.progress.filter((entry: PolishProgress): boolean =>
  entry.branchId === job.branchId && ancestryCommitIds.has(entry.commitId) &&
    entry.receipt === polishReceipt(
      job.jobId, entry.chapterId, entry.chapterOrdinal, entry.sourceDigest,
      entry.candidateId, entry.candidateDigest,
    ),
);

export { projectPolishOutcomes } from './polish_outcomes.ts';
export const recordPolishChapterResult = (job: DurablePolishJob, claim: GhostwriteClaimRef, status: 'failed' | 'driftSkipped', message: string, now: number): DurablePolishJob => {
  validateDurablePolishJob(job);
  assertPolishClaim(job, claim, now);
  if (!isActive(job.stage) || message.trim().length === 0) throw invalidInput('润色逐章结案状态无效');
  return validateDurablePolishJob(advancePolishOutcome(job, status, message.trim(), requireTimestamp(now, 'updatedAt')));
};
