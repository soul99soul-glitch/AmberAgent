import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  DEFAULT_GHOSTWRITE_CHAPTER_COUNT,
  MAX_GHOSTWRITE_CHAPTER_COUNT,
  MIN_GHOSTWRITE_CHAPTER_COUNT,
  applyGhostwriteReview,
  assertGhostwriteClaim,
  claimGhostwriteJob,
  freezeGhostwritePlan,
  ghostwriteReceipt,
  makeGhostwriteCandidate,
  makeGhostwriteJob,
  pauseGhostwriteJob,
  projectGhostwriteProgress,
  resumeGhostwriteJob,
  transitionGhostwriteJob,
  validateGhostwriteReview,
  withGhostwriteCandidate,
} from '../main/ets/novel/ghostwrite.ts';

const NOW = 1_700_000_000_000;
const CAS = { branchId: 'main', head: 'c1', treeDigest: 'tree-1' };
const POLICY = {
  writing: { kind: 'fixed' as const, providerId: 'writer-provider', modelId: 'writer-model' },
  review: null,
  stateSync: null,
};

const plan = () => freezeGhostwritePlan({
  planId: 'plan-1', content: '本章必须让主角抵达渡口。', expectedCas: CAS,
});

const job = (startChapterOrdinal: number = 1) => makeGhostwriteJob({
  jobId: 'job-1', projectId: 'project-1', branchId: 'main', now: NOW, frozenPlan: plan(),
  modelPolicyAtStart: POLICY, startChapterOrdinal,
});

test('ghostwrite bounds default to five and reject values outside one through ten', () => {
  assert.equal(MIN_GHOSTWRITE_CHAPTER_COUNT, 1);
  assert.equal(MAX_GHOSTWRITE_CHAPTER_COUNT, 10);
  assert.equal(DEFAULT_GHOSTWRITE_CHAPTER_COUNT, 5);
  assert.equal(job().targetChapterCount, 5);
  assert.throws(() => makeGhostwriteJob({
    jobId: 'small', projectId: 'p', branchId: 'main', now: NOW, frozenPlan: plan(),
    targetChapterCount: 0, modelPolicyAtStart: POLICY, startChapterOrdinal: 1,
  }));
  assert.throws(() => makeGhostwriteJob({
    jobId: 'large', projectId: 'p', branchId: 'main', now: NOW, frozenPlan: plan(),
    targetChapterCount: 11, modelPolicyAtStart: POLICY, startChapterOrdinal: 1,
  }));
  const continuation = job(11);
  assert.equal(continuation.currentChapterOrdinal, 11);
  assert.equal(continuation.endChapterOrdinal, 15);
  assert.equal(continuation.modelPolicyAtStart.writing.kind, 'fixed');
});

test('candidate and review are bound to the exact frozen plan', () => {
  const frozen = plan();
  const candidate = makeGhostwriteCandidate({
    candidateId: 'candidate-1', chapterOrdinal: 11, title: '渡口', content: '正文',
    planId: frozen.planId, planDigest: frozen.digest, attempt: 0,
  });
  const writing = { ...job(11), stage: 'writing' as const };
  const reviewing = withGhostwriteCandidate(writing, candidate, NOW + 1);
  assert.equal(reviewing.stage, 'reviewing');
  assert.throws(() => withGhostwriteCandidate(writing, {
    ...candidate, planDigest: 'other-plan',
  }, NOW + 1));

  const review = validateGhostwriteReview({
    candidateId: candidate.candidateId, candidateDigest: candidate.digest,
    planId: frozen.planId, planDigest: frozen.digest,
    findings: [{ kind: 'non_blocking', code: 'style', message: '可优化节奏', location: '第 1 段' }],
    blocking: false, rewriteRequired: false, rewriteInstructions: '', nextPlan: null,
    stateDelta: { plotState: '渡口已抵达', chapterHighlight: '主角首次见到船夫' },
  }, candidate, frozen);
  assert.equal(review.findings.length, 1);
  assert.throws(() => validateGhostwriteReview({
    ...review, blocking: true,
  }, candidate, frozen));
  assert.throws(() => validateGhostwriteReview({
    ...review, blocking: true, rewriteRequired: true,
  }, candidate, frozen));
});

test('only two rewrite attempts are permitted and a third requirement fails the job', () => {
  const frozen = plan();
  const candidate = makeGhostwriteCandidate({
    candidateId: 'candidate-1', chapterOrdinal: 1, title: '渡口', content: '正文',
    planId: frozen.planId, planDigest: frozen.digest, attempt: 0,
  });
  const review = {
    candidateId: candidate.candidateId, candidateDigest: candidate.digest,
    planId: frozen.planId, planDigest: frozen.digest,
    findings: [{ kind: 'hard_continuity' as const, code: 'timeline', message: '时间线冲突', location: '第 2 段' }],
    blocking: false, rewriteRequired: true, rewriteInstructions: '修复时间线', nextPlan: null,
    stateDelta: { plotState: '时间线待修复', chapterHighlight: '抵达渡口的时点' },
  };
  const reviewing = { ...job(), stage: 'reviewing' as const, candidate };
  const first = applyGhostwriteReview(reviewing, review, NOW + 1);
  assert.equal(first.stage, 'rewriting_1');
  const second = applyGhostwriteReview({ ...first, stage: 'reviewing' as const }, review, NOW + 2);
  assert.equal(second.stage, 'rewriting_2');
  const third = applyGhostwriteReview({ ...second, stage: 'reviewing' as const }, review, NOW + 3);
  assert.equal(third.stage, 'failed');
  assert.match(third.failure ?? '', /两次/);
});

test('claim, pause and resume preserve only legal execution ownership', () => {
  const claimed = claimGhostwriteJob({ ...job(), stage: 'writing' as const }, 'owner-a', NOW, 60_000);
  assert.equal(claimed.claim?.epoch, 1);
  assert.throws(() => claimGhostwriteJob(claimed, 'owner-b', NOW + 1, 60_000));
  assert.throws(() => assertGhostwriteClaim(claimed, { token: 'owner-b', epoch: 1 }, NOW + 2));
  assert.doesNotThrow(() => assertGhostwriteClaim(claimed, { token: 'owner-a', epoch: 1 }, NOW + 2));

  const paused = pauseGhostwriteJob(claimed, { token: 'owner-a', epoch: 1 }, NOW + 3);
  assert.equal(paused.stage, 'paused');
  assert.equal(paused.resumeStage, 'writing');
  const resumed = resumeGhostwriteJob(paused, NOW + 4);
  assert.equal(resumed.stage, 'writing');
  assert.equal(resumed.claim, null);

  const waiting = transitionGhostwriteJob({ ...job(), stage: 'writing' as const }, 'waiting_user', NOW + 5);
  assert.equal(waiting.resumeStage, 'writing');
  assert.equal(resumeGhostwriteJob(waiting, NOW + 6).stage, 'writing');
});

test('progress counts only matching receipts in the active branch ancestry', () => {
  const current = job();
  const withProgress = {
    ...current,
    progress: [
      {
        chapterOrdinal: 1, planId: 'plan-1', planDigest: current.frozenPlan?.digest ?? '', candidateId: 'candidate-1',
        receipt: ghostwriteReceipt(current.jobId, 1, 'plan-1', current.frozenPlan?.digest ?? '', 'candidate-1'),
        commitId: 'c2', branchId: 'main',
      },
      {
        chapterOrdinal: 2, planId: 'plan-2', planDigest: 'digest-2', candidateId: 'candidate-2',
        receipt: ghostwriteReceipt(current.jobId, 2, 'plan-2', 'digest-2', 'candidate-2'),
        commitId: 'c3', branchId: 'side',
      },
      {
        chapterOrdinal: 3, planId: 'plan-3', planDigest: 'digest-3', candidateId: 'candidate-3',
        receipt: 'wrong-receipt', commitId: 'c4', branchId: 'main',
      },
    ],
  };
  const projected = projectGhostwriteProgress(withProgress, new Set<string>(['c1', 'c2', 'c3', 'c4']));
  assert.deepEqual(projected.map(entry => entry.chapterOrdinal), [1]);
});
