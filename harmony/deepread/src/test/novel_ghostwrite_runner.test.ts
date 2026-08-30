import { test } from 'node:test';
import assert from 'node:assert/strict';
import type {
  NovelModelEvent, NovelModelRequest, NovelModelRunning, NovelModelStream,
} from '../main/ets/novel/model_running.ts';
import { makeAssistantMessage } from '../main/ets/agent/message.ts';
import type { NovelModelTarget, NovelModelPolicy } from '../main/ets/novel/models.ts';
import type {
  DurableGhostwriteJob, FrozenPlan, GhostwriteCandidate, GhostwriteClaimRef,
  GhostwriteProgress, GhostwriteReview, GhostwriteStage,
} from '../main/ets/novel/ghostwrite.ts';
import {
  applyGhostwriteReview, assertGhostwriteClaim, claimGhostwriteJob,
  freezeGhostwritePlan, ghostwriteReceipt, makeGhostwriteCandidate, makeGhostwriteJob,
  transitionGhostwriteJob, validateGhostwriteReview, withGhostwriteCandidate,
} from '../main/ets/novel/ghostwrite.ts';
import {
  createGhostwriteRunner,
} from '../main/ets/novel/ghostwrite_runner.ts';
import type {
  GhostwriteRunner, GhostwriteRunnerRepository,
} from '../main/ets/novel/ghostwrite_runner.ts';

const NOW: number = 1_700_000_000_000;
const WRITER_TARGET: NovelModelTarget = {
  kind: 'fixed', providerId: 'writer-provider', modelId: 'writer-model',
};
const REVIEW_TARGET: NovelModelTarget = {
  kind: 'fixed', providerId: 'review-provider', modelId: 'review-model',
};
const STATE_SYNC_TARGET: NovelModelTarget = {
  kind: 'fixed', providerId: 'state-provider', modelId: 'state-model',
};
const MODEL_POLICY: NovelModelPolicy = {
  writing: WRITER_TARGET,
  review: REVIEW_TARGET,
  stateSync: null,
};

const makeJob = (targetChapterCount: number = 1): DurableGhostwriteJob => makeGhostwriteJob({
  jobId: 'job-1',
  projectId: 'project-1',
  branchId: 'main',
  now: NOW,
  targetChapterCount,
  startChapterOrdinal: 1,
  modelPolicyAtStart: MODEL_POLICY,
  frozenPlan: freezeGhostwritePlan({
    planId: 'plan-1', content: '第 1 章计划',
    expectedCas: { branchId: 'main', head: 'head-0', treeDigest: 'tree-0' },
  }),
});

const currentClaim = (job: DurableGhostwriteJob): GhostwriteClaimRef => {
  if (job.claim === null) throw new Error('missing claim');
  return { token: job.claim.token, epoch: job.claim.epoch };
};

class FakeRepository implements GhostwriteRunnerRepository {
  job: DurableGhostwriteJob;
  commitCount: number = 0;
  plannerCheckpointCount: number = 0;
  failCount: number = 0;
  afterCommit: ((repository: FakeRepository) => void) | null = null;

  constructor(job: DurableGhostwriteJob) {
    this.job = job;
  }

  loadGhostwriteJob(_projectId: string, _jobId: string): Promise<DurableGhostwriteJob> {
    return Promise.resolve(this.job);
  }

  claimGhostwriteJob(
    _projectId: string, _jobId: string, token: string, now: number, leaseMs: number,
  ): Promise<DurableGhostwriteJob> {
    this.job = claimGhostwriteJob(this.job, token, now, leaseMs);
    return Promise.resolve(this.job);
  }

  checkpointGhostwriteStage(
    _projectId: string, _jobId: string, claim: GhostwriteClaimRef,
    nextStage: GhostwriteStage, now: number,
  ): Promise<DurableGhostwriteJob> {
    assertGhostwriteClaim(this.job, claim, now);
    this.job = transitionGhostwriteJob(this.job, nextStage, now);
    return Promise.resolve(this.job);
  }

  checkpointGhostwriteCandidate(
    _projectId: string, _jobId: string, claim: GhostwriteClaimRef,
    candidate: GhostwriteCandidate, now: number,
  ): Promise<DurableGhostwriteJob> {
    assertGhostwriteClaim(this.job, claim, now);
    this.job = withGhostwriteCandidate(this.job, candidate, now);
    return Promise.resolve(this.job);
  }

  checkpointGhostwriteReview(
    _projectId: string, _jobId: string, claim: GhostwriteClaimRef,
    review: GhostwriteReview, now: number,
  ): Promise<DurableGhostwriteJob> {
    assertGhostwriteClaim(this.job, claim, now);
    if (this.job.stage === 'planning') {
      if (this.job.candidate === null || this.job.frozenPlan === null || review.nextPlan === null) {
        throw new Error('planner checkpoint missing binding');
      }
      validateGhostwriteReview(review, this.job.candidate, this.job.frozenPlan);
      this.plannerCheckpointCount += 1;
      this.job = { ...this.job, stage: 'committing', review, updatedAt: now };
    } else {
      this.job = applyGhostwriteReview(this.job, review, now);
    }
    return Promise.resolve(this.job);
  }

  failGhostwriteJob(
    _projectId: string, _jobId: string, claim: GhostwriteClaimRef,
    reason: string, now: number,
  ): Promise<DurableGhostwriteJob> {
    assertGhostwriteClaim(this.job, claim, now);
    this.failCount += 1;
    this.job = transitionGhostwriteJob(this.job, 'failed', now);
    this.job = { ...this.job, failure: reason };
    return Promise.resolve(this.job);
  }

  commitGhostwriteChapter(
    _projectId: string, _jobId: string, claim: GhostwriteClaimRef,
    commandId: string, now: number,
  ): Promise<DurableGhostwriteJob> {
    assertGhostwriteClaim(this.job, claim, now);
    if (this.job.stage !== 'committing' || this.job.candidate === null ||
      this.job.review === null || this.job.frozenPlan === null) {
      return Promise.reject(new Error('invalid commit state'));
    }
    const candidate: GhostwriteCandidate = this.job.candidate;
    const plan: FrozenPlan = this.job.frozenPlan;
    const review: GhostwriteReview = this.job.review;
    const job: DurableGhostwriteJob = this.job;
    const expectedReceipt: string = ghostwriteReceipt(
      job.jobId, job.currentChapterOrdinal, plan.planId, plan.digest, candidate.candidateId);
    assert.equal(commandId, expectedReceipt);
    const nextHead: string = `head-${job.currentChapterOrdinal}`;
    const nextTree: string = `tree-${job.currentChapterOrdinal}`;
    const progress: GhostwriteProgress = {
      chapterOrdinal: job.currentChapterOrdinal,
      planId: plan.planId,
      planDigest: plan.digest,
      candidateId: candidate.candidateId,
      receipt: commandId,
      commitId: nextHead,
      branchId: job.branchId,
    };
    this.commitCount += 1;
    if (job.currentChapterOrdinal === job.endChapterOrdinal) {
      this.job = {
        ...job,
        stage: 'completed',
        expectedCas: { branchId: job.branchId, head: nextHead, treeDigest: nextTree },
        frozenPlan: null,
        candidate: null,
        review: null,
        progress: job.progress.concat([progress]),
        updatedAt: now,
      };
    } else {
      if (review.nextPlan === null) return Promise.reject(new Error('missing next plan'));
      const expectedCas = { branchId: job.branchId, head: nextHead, treeDigest: nextTree };
      const nextOrdinal: number = job.currentChapterOrdinal + 1;
      this.job = {
        ...job,
        stage: 'planning',
        expectedCas,
        frozenPlan: freezeGhostwritePlan({
          planId: `plan-${nextOrdinal}`, content: review.nextPlan, expectedCas,
        }),
        currentChapterOrdinal: nextOrdinal,
        candidate: null,
        review: null,
        rewriteCount: 0,
        progress: job.progress.concat([progress]),
        updatedAt: now,
      };
    }
    if (this.afterCommit !== null) this.afterCommit(this);
    return Promise.resolve(this.job);
  }
}

type ModelResponder = (request: NovelModelRequest, index: number) => string | 'waiting_user';

class FakeModel implements NovelModelRunning {
  readonly requests: NovelModelRequest[] = [];
  readonly validated: NovelModelTarget[] = [];
  private readonly responder: ModelResponder;

  constructor(responder: ModelResponder) {
    this.responder = responder;
  }

  validate(target: NovelModelTarget, projectId: string): Promise<void> {
    assert.equal(projectId, 'project-1');
    this.validated.push(target);
    return Promise.resolve();
  }

  start(request: NovelModelRequest): NovelModelStream {
    const index: number = this.requests.length;
    this.requests.push(request);
    return {
      subscribe: (callback: (event: NovelModelEvent) => void): (() => void) => {
        const timer = setTimeout((): void => {
          const response: string | 'waiting_user' = this.responder(request, index);
          if (response === 'waiting_user') {
            callback({ kind: 'waiting_user' });
          } else {
            callback({
              kind: 'snapshot', messages: [makeAssistantMessage(response)], generationActive: false,
              textDeltasLive: false, transport: 'unavailable',
            });
            callback({ kind: 'completed' });
          }
        }, 0);
        return (): void => { clearTimeout(timer); };
      },
    };
  }

  cancel(_runId: string): void {}
}

const candidateJson = (ordinal: number, suffix: string = ''): string => JSON.stringify({
  title: `第 ${ordinal} 章`, content: `第 ${ordinal} 章正文${suffix}`,
});

const reviewJson = (
  job: DurableGhostwriteJob,
  opts: { rewrite?: boolean; blocking?: boolean; nextPlan?: string | null } = {},
): string => {
  if (job.candidate === null || job.frozenPlan === null) throw new Error('missing review binding');
  const rewrite: boolean = opts.rewrite ?? false;
  const blocking: boolean = opts.blocking ?? false;
  return JSON.stringify({
    candidateId: job.candidate.candidateId,
    candidateDigest: job.candidate.digest,
    planId: job.frozenPlan.planId,
    planDigest: job.frozenPlan.digest,
    findings: blocking ? [{
      kind: 'hard_continuity', code: 'timeline', message: '时间线冲突', location: '第 2 段',
    }] : rewrite ? [{
      kind: 'non_blocking', code: 'pace', message: '节奏需修复', location: '第 1 段',
    }] : [],
    blocking,
    rewriteRequired: rewrite,
    rewriteInstructions: rewrite ? '按审核意见定向修复' : '',
    nextPlan: opts.nextPlan === undefined ? null : opts.nextPlan,
    stateDelta: { plotState: '剧情状态', chapterHighlight: '本章要点' },
  });
};

const makeRunner = (
  repository: FakeRepository, model: FakeModel,
): GhostwriteRunner => {
  let id: number = 0;
  return createGhostwriteRunner({
    repository,
    modelRunning: model,
    nowMs: (): number => NOW + 100,
    makeId: (prefix: string): string => {
      id += 1;
      return `${prefix}-${id}`;
    },
  });
};

const roleOf = (request: NovelModelRequest): 'writer' | 'reviewer' | 'planner' => {
  if (request.systemPrompt.includes('正文写作者')) return 'writer';
  if (request.systemPrompt.includes('联合审核员')) return 'reviewer';
  return 'planner';
};

for (const count of [1, 5, 10]) {
  test(`ghostwrite runner: ${count} 章顺序完成且所有模型调用只读`, async () => {
    const repository = new FakeRepository(makeJob(count));
    const model = new FakeModel((request: NovelModelRequest): string => {
      const role = roleOf(request);
      if (role === 'writer') return candidateJson(repository.job.currentChapterOrdinal);
      if (role === 'reviewer') {
        const final: boolean = repository.job.currentChapterOrdinal === repository.job.endChapterOrdinal;
        return reviewJson(repository.job, {
          nextPlan: final ? null : `第 ${repository.job.currentChapterOrdinal + 1} 章计划`,
        });
      }
      throw new Error('planner should not run when review supplied nextPlan');
    });
    const result: DurableGhostwriteJob = await makeRunner(repository, model).run(
      'project-1', 'job-1', 'owner-1');
    assert.equal(result.stage, 'completed');
    assert.equal(repository.commitCount, count);
    assert.equal(result.progress.length, count);
    assert.equal(result.frozenPlan, null);
    assert.equal(model.requests.every((request: NovelModelRequest): boolean =>
      request.toolProfile === 'read_only'), true);
    assert.equal(model.requests.filter((request: NovelModelRequest): boolean =>
      roleOf(request) === 'planner').length, 0);
  });
}

test('ghostwrite runner: writer/review/planner 分别使用 writing、review、stateSync 职责', async () => {
  const repository = new FakeRepository(makeJob(2));
  repository.job = {
    ...repository.job,
    modelPolicyAtStart: { ...repository.job.modelPolicyAtStart, stateSync: STATE_SYNC_TARGET },
  };
  const model = new FakeModel((request: NovelModelRequest): string => {
    const role = roleOf(request);
    if (role === 'writer') return candidateJson(repository.job.currentChapterOrdinal);
    if (role === 'reviewer') return reviewJson(repository.job, { nextPlan: null });
    return JSON.stringify({ nextPlan: '第二章计划' });
  });
  const result = await makeRunner(repository, model).run('project-1', 'job-1', 'owner-1');
  assert.equal(result.stage, 'completed');
  for (let i: number = 0; i < model.requests.length; i++) {
    const request: NovelModelRequest = model.requests[i];
    const role = roleOf(request);
    assert.deepEqual(request.modelTarget,
      role === 'writer' ? WRITER_TARGET : role === 'reviewer' ? REVIEW_TARGET : STATE_SYNC_TARGET);
  }
  assert.equal(repository.plannerCheckpointCount, 1);
});

test('ghostwrite runner: review/stateSync 未设置时两者均精确回退 writing', async () => {
  const repository = new FakeRepository(makeJob(2));
  repository.job = {
    ...repository.job,
    modelPolicyAtStart: { writing: WRITER_TARGET, review: null, stateSync: null },
  };
  const model = new FakeModel((request: NovelModelRequest): string => {
    const role = roleOf(request);
    if (role === 'writer') return candidateJson(repository.job.currentChapterOrdinal);
    if (role === 'reviewer') return reviewJson(repository.job, { nextPlan: null });
    return JSON.stringify({ nextPlan: '第二章计划' });
  });
  const result = await makeRunner(repository, model).run('project-1', 'job-1', 'owner-1');
  assert.equal(result.stage, 'completed');
  assert.equal(model.requests.every((request: NovelModelRequest): boolean =>
    JSON.stringify(request.modelTarget) === JSON.stringify(WRITER_TARGET)), true);
});

test('ghostwrite runner: 两次定向 rewrite 后通过，复用同一 candidateId', async () => {
  const repository = new FakeRepository(makeJob());
  let reviews: number = 0;
  const candidateIds: string[] = [];
  const model = new FakeModel((request: NovelModelRequest): string => {
    if (roleOf(request) === 'writer') return candidateJson(1, `-attempt-${repository.job.rewriteCount}`);
    reviews += 1;
    if (repository.job.candidate !== null) candidateIds.push(repository.job.candidate.candidateId);
    return reviewJson(repository.job, { rewrite: reviews <= 2 });
  });
  const result = await makeRunner(repository, model).run('project-1', 'job-1', 'owner-1');
  assert.equal(result.stage, 'completed');
  assert.equal(reviews, 3);
  assert.equal(new Set<string>(candidateIds).size, 1);
  assert.equal(repository.commitCount, 1);
});

test('ghostwrite runner: 第三次仍要求 rewrite 时 durable failed，不 commit', async () => {
  const repository = new FakeRepository(makeJob());
  const model = new FakeModel((request: NovelModelRequest): string =>
    roleOf(request) === 'writer' ? candidateJson(1) : reviewJson(repository.job, { rewrite: true }));
  const result = await makeRunner(repository, model).run('project-1', 'job-1', 'owner-1');
  assert.equal(result.stage, 'failed');
  assert.match(result.failure ?? '', /两次/);
  assert.equal(repository.commitCount, 0);
});

test('ghostwrite runner: review nextPlan 优先；缺失时 planner 仅调用一次并先 checkpoint', async () => {
  const withReviewPlanRepo = new FakeRepository(makeJob(2));
  const withReviewPlanModel = new FakeModel((request: NovelModelRequest): string => {
    const role = roleOf(request);
    if (role === 'writer') return candidateJson(withReviewPlanRepo.job.currentChapterOrdinal);
    if (role === 'reviewer') {
      const final: boolean = withReviewPlanRepo.job.currentChapterOrdinal === 2;
      return reviewJson(withReviewPlanRepo.job, { nextPlan: final ? null : '审核给出的第二章计划' });
    }
    throw new Error('unexpected planner');
  });
  await makeRunner(withReviewPlanRepo, withReviewPlanModel).run('project-1', 'job-1', 'owner-1');
  assert.equal(withReviewPlanRepo.plannerCheckpointCount, 0);

  const plannedRepo = new FakeRepository(makeJob(2));
  const plannedModel = new FakeModel((request: NovelModelRequest): string => {
    const role = roleOf(request);
    if (role === 'writer') return candidateJson(plannedRepo.job.currentChapterOrdinal);
    if (role === 'reviewer') return reviewJson(plannedRepo.job, { nextPlan: null });
    return JSON.stringify({ nextPlan: 'planner 给出的第二章计划' });
  });
  await makeRunner(plannedRepo, plannedModel).run('project-1', 'job-1', 'owner-1');
  assert.equal(plannedRepo.plannerCheckpointCount, 1);
  assert.equal(plannedModel.requests.filter((request: NovelModelRequest): boolean =>
    roleOf(request) === 'planner').length, 1);
});

test('ghostwrite runner: final chapter 不调用 planner，atomic commit 清空计划', async () => {
  const repository = new FakeRepository(makeJob());
  const model = new FakeModel((request: NovelModelRequest): string =>
    roleOf(request) === 'writer' ? candidateJson(1) : reviewJson(repository.job, { nextPlan: null }));
  const result = await makeRunner(repository, model).run('project-1', 'job-1', 'owner-1');
  assert.equal(result.stage, 'completed');
  assert.equal(result.frozenPlan, null);
  assert.equal(model.requests.some((request: NovelModelRequest): boolean =>
    roleOf(request) === 'planner'), false);
});

for (const stoppedStage of ['paused', 'cancelled'] as const) {
  test(`ghostwrite runner: commit 后 ${stoppedStage} 不启动下一章`, async () => {
    const repository = new FakeRepository(makeJob(5));
    repository.afterCommit = (repo: FakeRepository): void => {
      const resumeStage: GhostwriteStage | null = stoppedStage === 'paused' ? repo.job.stage : null;
      repo.job = {
        ...repo.job, stage: stoppedStage, resumeStage,
        claim: null, updatedAt: NOW + 101,
      };
    };
    const model = new FakeModel((request: NovelModelRequest): string =>
      roleOf(request) === 'writer'
        ? candidateJson(repository.job.currentChapterOrdinal)
        : reviewJson(repository.job, { nextPlan: '下一章计划' }));
    const result = await makeRunner(repository, model).run('project-1', 'job-1', 'owner-1');
    assert.equal(result.stage, stoppedStage);
    assert.equal(repository.commitCount, 1);
    assert.equal(model.requests.filter((request: NovelModelRequest): boolean =>
      roleOf(request) === 'writer').length, 1);
  });
}

test('ghostwrite runner: 未过期旧 owner 阻止新 owner，且不篡改 durable failed', async () => {
  const repository = new FakeRepository(claimGhostwriteJob(makeJob(), 'old-owner', NOW, 60_000));
  const model = new FakeModel((): string => candidateJson(1));
  await assert.rejects(
    makeRunner(repository, model).run('project-1', 'job-1', 'new-owner'), /其他 owner/);
  assert.equal(repository.job.stage, 'planning');
  assert.equal(repository.failCount, 0);
  assert.equal(model.requests.length, 0);
});

test('ghostwrite runner: cold resume 从 durable reviewing 继续，不重复 writer', async () => {
  let job: DurableGhostwriteJob = transitionGhostwriteJob(makeJob(), 'writing', NOW + 1);
  const plan: FrozenPlan = job.frozenPlan as FrozenPlan;
  const candidate: GhostwriteCandidate = makeGhostwriteCandidate({
    candidateId: 'candidate-cold', chapterOrdinal: 1, title: '冷恢复', content: '已持久候选',
    planId: plan.planId, planDigest: plan.digest, attempt: 0,
  });
  job = withGhostwriteCandidate(job, candidate, NOW + 2);
  const repository = new FakeRepository(job);
  const model = new FakeModel((request: NovelModelRequest): string => {
    assert.equal(roleOf(request), 'reviewer');
    return reviewJson(repository.job, { nextPlan: null });
  });
  const result = await makeRunner(repository, model).run('project-1', 'job-1', 'new-owner');
  assert.equal(result.stage, 'completed');
  assert.equal(model.requests.length, 1);
  assert.equal(roleOf(model.requests[0]), 'reviewer');
});

test('ghostwrite runner: 模型 waiting_user durable 收口，不标 failed', async () => {
  const repository = new FakeRepository(makeJob());
  const model = new FakeModel((): 'waiting_user' => 'waiting_user');
  const result = await makeRunner(repository, model).run('project-1', 'job-1', 'owner-1');
  assert.equal(result.stage, 'waiting_user');
  assert.equal(result.resumeStage, 'writing');
  assert.equal(repository.failCount, 0);
});

test('ghostwrite runner: strict review 绑定错误写入 durable failed', async () => {
  const repository = new FakeRepository(makeJob());
  const model = new FakeModel((request: NovelModelRequest): string => {
    if (roleOf(request) === 'writer') return candidateJson(1);
    const raw = JSON.parse(reviewJson(repository.job, { nextPlan: null })) as {
      candidateId: string;
    };
    raw.candidateId = 'wrong-candidate';
    return JSON.stringify(raw);
  });
  const result = await makeRunner(repository, model).run('project-1', 'job-1', 'owner-1');
  assert.equal(result.stage, 'failed');
  assert.equal(repository.failCount, 1);
  assert.match(result.failure ?? '', /不匹配/);
  assert.equal(repository.commitCount, 0);
});
