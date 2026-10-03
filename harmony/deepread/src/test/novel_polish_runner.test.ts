import { test } from 'node:test';
import assert from 'node:assert/strict';
import type {
  NovelModelEvent, NovelModelRequest, NovelModelRunning, NovelModelStream,
} from '../main/ets/novel/model_running.ts';
import { makeAssistantMessage } from '../main/ets/agent/message.ts';
import type { NovelModelPolicy, NovelModelTarget } from '../main/ets/novel/models.ts';
import type { GhostwriteClaimRef } from '../main/ets/novel/ghostwrite.ts';
import type {
  DurablePolishJob, PolishCandidate, PolishReview, PolishStage,
} from '../main/ets/novel/polish.ts';
import {
  applyPolishReview, assertPolishClaim, claimPolishJob, commitPolishChapter,
  makePolishCandidate, makePolishChapterTarget, makePolishContextSnapshotItem, makePolishJob,
  transitionPolishJob, withPolishCandidate, yieldPolishJobToSystem, recordPolishChapterResult,
} from '../main/ets/novel/polish.ts';
import { createPolishRunner } from '../main/ets/novel/polish_runner.ts';
import type { PolishRunner, PolishRunnerRepository } from '../main/ets/novel/polish_runner.ts';

const NOW: number = 1_700_000_000_000;
const WRITER: NovelModelTarget = { kind: 'fixed', providerId: 'writer', modelId: 'writer-model' };
const REVIEWER: NovelModelTarget = { kind: 'fixed', providerId: 'reviewer', modelId: 'review-model' };
const POLICY: NovelModelPolicy = { writing: WRITER, review: REVIEWER, stateSync: null };

const makeJob = (count: number = 1): DurablePolishJob => {
  const targets = [];
  for (let ordinal: number = 1; ordinal <= count; ordinal++) {
    targets.push(makePolishChapterTarget({
      id: `chapter-${ordinal}`, ordinal, title: `第 ${ordinal} 章`, sourceContent: `冻结正文 ${ordinal}`,
    }));
  }
  return makePolishJob({
    jobId: 'polish-1', projectId: 'project-1', branchId: 'main', now: NOW,
    expectedCas: { branchId: 'main', head: 'head-0', treeDigest: 'tree-0' },
    targets,
    contextOptions: {
      includePlot: true, includeForeshadows: true, includeCharacters: false, includeDecisions: true,
    },
    contextSnapshot: [makePolishContextSnapshotItem({
      kind: 'plot', sourcePath: 'branches/main/plan/plot.md', content: '已冻结剧情摘要',
    })],
    warnings: [{ kind: 'plot_stale', message: '剧情摘要可能落后于正文。' }],
    modelPolicyAtStart: POLICY,
  });
};

class FakeRepository implements PolishRunnerRepository {
  job: DurablePolishJob;
  commitCount: number = 0;
  failCount: number = 0;
  yieldCount: number = 0;

  constructor(job: DurablePolishJob) { this.job = job; }

  loadPolishJob(_projectId: string, _jobId: string): Promise<DurablePolishJob> {
    return Promise.resolve(this.job);
  }

  claimPolishJob(
    _projectId: string, _jobId: string, token: string, now: number, leaseMs: number,
  ): Promise<DurablePolishJob> {
    this.job = claimPolishJob(this.job, token, now, leaseMs);
    return Promise.resolve(this.job);
  }

  checkpointPolishStage(
    _projectId: string, _jobId: string, claim: GhostwriteClaimRef, stage: PolishStage, now: number,
  ): Promise<DurablePolishJob> {
    assertPolishClaim(this.job, claim, now);
    this.job = transitionPolishJob(this.job, stage, now);
    return Promise.resolve(this.job);
  }

  checkpointPolishCandidate(
    _projectId: string, _jobId: string, claim: GhostwriteClaimRef,
    candidate: PolishCandidate, now: number,
  ): Promise<DurablePolishJob> {
    assertPolishClaim(this.job, claim, now);
    this.job = withPolishCandidate(this.job, candidate, now);
    return Promise.resolve(this.job);
  }

  checkpointPolishReview(
    _projectId: string, _jobId: string, claim: GhostwriteClaimRef,
    review: PolishReview, now: number,
  ): Promise<DurablePolishJob> {
    assertPolishClaim(this.job, claim, now);
    this.job = applyPolishReview(this.job, review, now);
    return Promise.resolve(this.job);
  }

  recordPolishChapterResult(_projectId: string, _jobId: string, claim: GhostwriteClaimRef,
    status: 'failed' | 'driftSkipped', reason: string, now: number): Promise<DurablePolishJob> {
    this.job = recordPolishChapterResult(this.job, claim, status, reason, now);
    return Promise.resolve(this.job);
  }

  failPolishJob(
    _projectId: string, _jobId: string, claim: GhostwriteClaimRef, reason: string, now: number,
  ): Promise<DurablePolishJob> {
    assertPolishClaim(this.job, claim, now);
    this.failCount += 1;
    this.job = {
      ...this.job, stage: 'failed', resumeStage: this.job.stage, claim: null,
      failure: reason, updatedAt: now,
    };
    return Promise.resolve(this.job);
  }

  yieldPolishJob(
    _projectId: string, _jobId: string, claim: GhostwriteClaimRef, now: number,
  ): Promise<DurablePolishJob> {
    this.yieldCount += 1;
    this.job = yieldPolishJobToSystem(this.job, claim, now);
    return Promise.resolve(this.job);
  }

  commitPolishChapter(
    _projectId: string, _jobId: string, claim: GhostwriteClaimRef, receipt: string, now: number,
  ): Promise<DurablePolishJob> {
    assertPolishClaim(this.job, claim, now);
    const nextHead: string = `head-${this.job.cursor + 1}`;
    this.job = commitPolishChapter(this.job, claim, {
      branchId: this.job.branchId,
      commitId: nextHead,
      receipt,
      nextCas: { branchId: this.job.branchId, head: nextHead, treeDigest: `tree-${this.job.cursor + 1}` },
    }, now);
    this.commitCount += 1;
    return Promise.resolve(this.job);
  }
}

type Response = string | 'waiting_user' | 'pending';
type Responder = (request: NovelModelRequest, index: number) => Response;

class FakeModel implements NovelModelRunning {
  readonly requests: NovelModelRequest[] = [];
  readonly validated: NovelModelTarget[] = [];
  readonly cancelled: string[] = [];
  private readonly responder: Responder;
  private readonly callbacks: Map<string, (event: NovelModelEvent) => void> = new Map();

  constructor(responder: Responder) { this.responder = responder; }

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
        this.callbacks.set(request.runId, callback);
        const response: Response = this.responder(request, index);
        if (response !== 'pending') {
          setTimeout((): void => {
            if (response === 'waiting_user') callback({ kind: 'waiting_user' });
            else {
              callback({
                kind: 'snapshot', messages: [makeAssistantMessage(response)], generationActive: false,
                textDeltasLive: false, transport: 'unavailable',
              });
              callback({ kind: 'completed' });
            }
          }, 0);
        }
        return (): void => { this.callbacks.delete(request.runId); };
      },
    };
  }

  cancel(runId: string): void {
    this.cancelled.push(runId);
    const callback = this.callbacks.get(runId);
    if (callback !== undefined) callback({ kind: 'failed', message: 'cancelled' });
  }
}

const candidateJson = (
  ordinal: number, attempt: number = 0, jobId: string = 'polish-1',
): string => JSON.stringify({
  jobId,
  content: `第 ${ordinal} 章完整润色正文 attempt-${attempt}`,
});

const reviewJson = (
  job: DurablePolishJob, rewrite: boolean = false, blocking: boolean = false,
): string => {
  if (job.candidate === null) throw new Error('review without candidate');
  return JSON.stringify({
    jobId: job.jobId,
    chapterId: job.candidate.chapterId,
    chapterOrdinal: job.candidate.chapterOrdinal,
    sourceDigest: job.candidate.sourceDigest,
    candidateId: job.candidate.candidateId,
    candidateDigest: job.candidate.digest,
    findings: blocking ? [{
      kind: 'hard_continuity', code: 'timeline', message: '时间线冲突', location: '第 2 段',
    }] : rewrite ? [{
      kind: 'non_blocking', code: 'wording', message: '措辞需要收紧', location: '第 1 段',
    }] : [],
    blocking,
    rewriteRequired: rewrite,
    rewriteInstructions: rewrite ? '仅修正措辞，不改变剧情事实。' : '',
  });
};

const roleOf = (request: NovelModelRequest): 'writer' | 'reviewer' =>
  request.systemPrompt.includes('章节润色者') ? 'writer' : 'reviewer';

const turnPrompt = (request: NovelModelRequest): string => {
  if (request.operation.kind !== 'turn') throw new Error('polish runner must issue turn operations');
  return request.operation.userPrompt;
};

const makeRunner = (repository: FakeRepository, model: FakeModel): PolishRunner => {
  let id: number = 0;
  return createPolishRunner({
    repository, modelRunning: model, nowMs: (): number => NOW + 100,
    makeId: (prefix: string): string => { id += 1; return `${prefix}-${id}`; },
  });
};

for (const count of [1, 3]) {
  test(`polish runner completes ${count} frozen chapters serially and read-only`, async () => {
    const repository = new FakeRepository(makeJob(count));
    const model = new FakeModel((request: NovelModelRequest): string =>
      roleOf(request) === 'writer'
        ? candidateJson(repository.job.cursor + 1, repository.job.rewriteCount)
        : reviewJson(repository.job));
    const result = await makeRunner(repository, model).run('project-1', 'polish-1', 'owner-1');
    assert.equal(result.stage, 'completed');
    assert.equal(repository.commitCount, count);
    assert.equal(result.progress.length, count);
    assert.equal(model.requests.every((request: NovelModelRequest): boolean =>
      request.toolProfile === 'read_only'), true);
  });
}

test('polish runner writer/reviewer select writing/review roles and prompts retain frozen boundaries', async () => {
  const repository = new FakeRepository(makeJob());
  const model = new FakeModel((request: NovelModelRequest): string =>
    roleOf(request) === 'writer' ? candidateJson(1) : reviewJson(repository.job));
  await makeRunner(repository, model).run('project-1', 'polish-1', 'owner-1');
  assert.deepEqual(model.validated, [WRITER, REVIEWER]);
  const writer: NovelModelRequest = model.requests[0];
  const reviewer: NovelModelRequest = model.requests[1];
  assert.equal(roleOf(writer), 'writer');
  assert.equal(roleOf(reviewer), 'reviewer');
  assert.match(turnPrompt(writer), /冻结正文 1/);
  assert.match(turnPrompt(writer), /branches\/main\/plan\/plot\.md/);
  assert.match(turnPrompt(writer), /剧情摘要可能落后于正文/);
  assert.match(turnPrompt(writer), /"jobId":"polish-1"/);
  assert.match(turnPrompt(reviewer), /冻结正文 1/);
  assert.match(turnPrompt(reviewer), /只能使用列出的 sourcePath/);
});

test('polish runner falls reviewer back to writing model when review is absent', async () => {
  const repository = new FakeRepository(makeJob());
  repository.job = { ...repository.job, modelPolicyAtStart: { writing: WRITER, review: null, stateSync: null } };
  const model = new FakeModel((request: NovelModelRequest): string =>
    roleOf(request) === 'writer' ? candidateJson(1) : reviewJson(repository.job));
  await makeRunner(repository, model).run('project-1', 'polish-1', 'owner-1');
  assert.deepEqual(model.validated, [WRITER, WRITER]);
});

test('polish runner performs two bounded rewrites with one candidate binding', async () => {
  const repository = new FakeRepository(makeJob());
  let reviewCount: number = 0;
  const candidateIds: string[] = [];
  const model = new FakeModel((request: NovelModelRequest): string => {
    if (roleOf(request) === 'writer') return candidateJson(1, repository.job.rewriteCount);
    reviewCount += 1;
    if (repository.job.candidate !== null) candidateIds.push(repository.job.candidate.candidateId);
    return reviewJson(repository.job, reviewCount <= 2);
  });
  const result = await makeRunner(repository, model).run('project-1', 'polish-1', 'owner-1');
  assert.equal(result.stage, 'completed');
  assert.equal(reviewCount, 3);
  assert.equal(new Set<string>(candidateIds).size, 1);
});

test('polish runner cold resumes reviewing and committing without duplicate model work', async () => {
  let reviewing: DurablePolishJob = transitionPolishJob(makeJob(), 'writing', NOW + 1);
  reviewing = withPolishCandidate(reviewing, makePolishCandidate({
    jobId: reviewing.jobId,
    candidateId: 'cold-candidate', chapterId: 'chapter-1', chapterOrdinal: 1,
    sourceDigest: reviewing.targets[0].sourceDigest, content: '已持久候选正文', attempt: 0,
  }), NOW + 2);
  const reviewingRepository = new FakeRepository(reviewing);
  const reviewingModel = new FakeModel((request: NovelModelRequest): string => {
    assert.equal(roleOf(request), 'reviewer');
    return reviewJson(reviewingRepository.job);
  });
  const reviewed = await makeRunner(reviewingRepository, reviewingModel).run(
    'project-1', 'polish-1', 'new-owner');
  assert.equal(reviewed.stage, 'completed');
  assert.equal(reviewingModel.requests.length, 1);

  let committing: DurablePolishJob = transitionPolishJob(makeJob(), 'writing', NOW + 1);
  committing = withPolishCandidate(committing, makePolishCandidate({
    jobId: committing.jobId,
    candidateId: 'commit-candidate', chapterId: 'chapter-1', chapterOrdinal: 1,
    sourceDigest: committing.targets[0].sourceDigest, content: '已持久候选正文', attempt: 0,
  }), NOW + 2);
  committing = applyPolishReview(committing, JSON.parse(reviewJson(committing)) as PolishReview, NOW + 3);
  const committingRepository = new FakeRepository(committing);
  const committed = await makeRunner(committingRepository, new FakeModel((): string => {
    throw new Error('committing recovery must not call model');
  })).run('project-1', 'polish-1', 'new-owner');
  assert.equal(committed.stage, 'completed');
  assert.equal(committingRepository.commitCount, 1);
});

test('polish runner maxChapters yields after exactly one committed chapter', async () => {
  const repository = new FakeRepository(makeJob(3));
  const model = new FakeModel((request: NovelModelRequest): string =>
    roleOf(request) === 'writer' ? candidateJson(repository.job.cursor + 1) : reviewJson(repository.job));
  const result = await makeRunner(repository, model).run('project-1', 'polish-1', 'owner-1', undefined, 1);
  assert.equal(result.stage, 'waiting_system');
  assert.equal(result.resumeStage, 'queued');
  assert.equal(repository.commitCount, 1);
  assert.equal(repository.yieldCount, 1);
});

test('polish runner cancellation reaches active model and persists failure', async () => {
  const repository = new FakeRepository(makeJob());
  const model = new FakeModel((): 'pending' => 'pending');
  const runner = makeRunner(repository, model);
  const running = runner.run('project-1', 'polish-1', 'owner-1');
  await new Promise<void>((resolve: () => void): void => { setTimeout(resolve, 0); });
  runner.cancel('project-1', 'polish-1');
  const result = await running;
  assert.equal(model.cancelled.length, 1);
  assert.equal(result.stage, 'completed');
  assert.equal(result.outcomes![0].status, 'failed');
  assert.equal(repository.failCount, 0);
});

test('polish runner old owner does not overwrite a newer durable state', async () => {
  const repository = new FakeRepository(claimPolishJob(makeJob(), 'old-owner', NOW, 60_000));
  const model = new FakeModel((): string => candidateJson(1));
  await assert.rejects(
    makeRunner(repository, model).run('project-1', 'polish-1', 'new-owner'), /其他 owner/);
  assert.equal(repository.job.stage, 'queued');
  assert.equal(repository.failCount, 0);
  assert.equal(model.requests.length, 0);
});

test('polish runner invalid candidate and waiting_user persist durable failure', async () => {
  const invalidRepository = new FakeRepository(makeJob());
  const invalidResult = await makeRunner(invalidRepository, new FakeModel((): string => 'not json')).run(
    'project-1', 'polish-1', 'owner-1');
  assert.equal(invalidResult.stage, 'completed');
  assert.equal(invalidResult.outcomes![0].status, 'failed');
  assert.equal(invalidRepository.failCount, 0);

  const waitingRepository = new FakeRepository(makeJob());
  const waitingResult = await makeRunner(waitingRepository, new FakeModel((): 'waiting_user' => 'waiting_user')).run(
    'project-1', 'polish-1', 'owner-1');
  assert.equal(waitingResult.stage, 'completed');
  assert.equal(waitingResult.outcomes![0].status, 'failed');
  assert.match(waitingResult.outcomes![0].message ?? '', /不支持等待用户/);
});

test('polish runner rejects candidate and review transplanted from another job', async () => {
  const candidateRepository = new FakeRepository(makeJob());
  const candidateResult = await makeRunner(
    candidateRepository, new FakeModel((): string => candidateJson(1, 0, 'polish-other')),
  ).run('project-1', 'polish-1', 'owner-1');
  assert.equal(candidateResult.stage, 'completed');
  assert.equal(candidateResult.outcomes![0].status, 'failed');
  assert.match(candidateResult.outcomes![0].message ?? '', /candidate .* job/);

  const reviewRepository = new FakeRepository(makeJob());
  const reviewModel = new FakeModel((request: NovelModelRequest): string => {
    if (roleOf(request) === 'writer') return candidateJson(1);
    const raw = JSON.parse(reviewJson(reviewRepository.job)) as Record<string, unknown>;
    raw.jobId = 'polish-other';
    return JSON.stringify(raw);
  });
  const reviewResult = await makeRunner(reviewRepository, reviewModel).run(
    'project-1', 'polish-1', 'owner-1');
  assert.equal(reviewResult.stage, 'completed');
  assert.equal(reviewResult.outcomes![0].status, 'failed');
  assert.match(reviewResult.outcomes![0].message ?? '', /review .*绑定/);
});
