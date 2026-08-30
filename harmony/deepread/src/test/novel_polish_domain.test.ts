import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  MAX_POLISH_CONTEXT_ITEMS,
  MAX_POLISH_CONTEXT_ITEM_CHARS,
  MAX_POLISH_CONTEXT_TOTAL_CHARS,
  applyPolishReview,
  assertPolishClaim,
  cancelPolishJob,
  claimPolishJob,
  commitPolishChapter,
  failPolishJob,
  makePolishCandidate,
  makePolishChapterTarget,
  makePolishContextSnapshotItem,
  makePolishJob,
  pausePolishJob,
  polishReceipt,
  projectPolishProgress,
  resumePolishJob,
  retryPolishJob,
  transitionPolishJob,
  validateDurablePolishJob,
  validatePolishReview,
  withPolishCandidate,
  yieldPolishJobToSystem,
  type DurablePolishJob,
  type PolishCandidate,
  type PolishContextOptions,
  type PolishReview,
} from '../main/ets/novel/polish.ts';

const NOW = 1_700_000_000_000;
const CAS = { branchId: 'main', head: 'c1', treeDigest: 'tree-1' };
const POLICY = {
  writing: { kind: 'fixed' as const, providerId: 'writer-provider', modelId: 'writer-model' },
  review: null,
  stateSync: null,
};
const OPTIONS: PolishContextOptions = {
  includePlot: true,
  includeForeshadows: true,
  includeCharacters: false,
  includeDecisions: true,
};

const targets = () => [
  makePolishChapterTarget({ id: 'chapter-3', ordinal: 3, title: '渡口', sourceContent: '主角抵达渡口。' }),
  makePolishChapterTarget({ id: 'chapter-4', ordinal: 4, title: '夜船', sourceContent: '夜船悄然启航。' }),
];

const contextSnapshot = () => [
  makePolishContextSnapshotItem({ kind: 'plot', sourcePath: 'plot/state.md', content: '主角正追查失踪船队。' }),
  makePolishContextSnapshotItem({
    kind: 'foreshadow', sourcePath: 'setting/foreshadows/ferry.md', content: '船夫知道旧案。',
  }),
  makePolishContextSnapshotItem({
    kind: 'decision', sourcePath: 'setting/decisions/tone.md', content: '保持克制叙事。',
  }),
];

const job = (): DurablePolishJob => makePolishJob({
  jobId: 'polish-1', projectId: 'project-1', branchId: 'main', expectedCas: CAS, now: NOW,
  targets: targets(), contextOptions: OPTIONS, contextSnapshot: contextSnapshot(),
  warnings: [
    { kind: 'plot_stale', message: '剧情摘要可能落后于正文。' },
    { kind: 'unresolved', message: '第 4 章后的影响尚未确认。' },
  ],
  modelPolicyAtStart: POLICY,
});

const writingJob = (): DurablePolishJob => transitionPolishJob(job(), 'writing', NOW + 1);

const candidateFor = (current: DurablePolishJob, attempt: number = 0, id: string = 'candidate-3'):
  PolishCandidate => makePolishCandidate({
    jobId: current.jobId,
    candidateId: id,
    chapterId: current.targets[current.cursor].id,
    chapterOrdinal: current.targets[current.cursor].ordinal,
    sourceDigest: current.targets[current.cursor].sourceDigest,
    content: '主角终于抵达渡口，潮声压过了脚步。',
    attempt,
  });

const reviewFor = (
  candidate: PolishCandidate, rewriteRequired: boolean = false,
): PolishReview => ({
  jobId: candidate.jobId,
  chapterId: candidate.chapterId,
  chapterOrdinal: candidate.chapterOrdinal,
  sourceDigest: candidate.sourceDigest,
  candidateId: candidate.candidateId,
  candidateDigest: candidate.digest,
  findings: rewriteRequired
    ? [{ kind: 'hard_continuity', code: 'timeline', message: '时间线称谓不一致', location: '第 2 段' }]
    : [{ kind: 'non_blocking', code: 'rhythm', message: '节奏可接受', location: '全文' }],
  blocking: false,
  rewriteRequired,
  rewriteInstructions: rewriteRequired ? '修正时间称谓，不改变剧情事实。' : '',
});

test('job freezes a closed contiguous chapter range, context provenance and warnings', () => {
  const created = job();
  assert.equal(created.kind, 'batch_polish');
  assert.equal(created.stage, 'queued');
  assert.equal(created.startOrdinal, 3);
  assert.equal(created.endOrdinal, 4);
  assert.equal(created.cursor, 0);
  assert.deepEqual(created.targets.map(target => target.id), ['chapter-3', 'chapter-4']);
  assert.equal(created.targets[0].sourceContent, '主角抵达渡口。');
  assert.notEqual(created.targets[0].sourceDigest, '');
  assert.deepEqual(created.contextOptions, OPTIONS);
  assert.deepEqual(created.contextSnapshot.map(item => item.sourcePath), [
    'plot/state.md', 'setting/foreshadows/ferry.md', 'setting/decisions/tone.md',
  ]);
  assert.deepEqual(created.warnings.map(warning => warning.kind), ['plot_stale', 'unresolved']);
});

test('range validation rejects duplicate ids, duplicate or non-contiguous ordinals and empty source', () => {
  const base = targets();
  assert.throws(() => makePolishJob({
    jobId: 'duplicate-id', projectId: 'project-1', branchId: 'main', expectedCas: CAS, now: NOW,
    targets: [base[0], makePolishChapterTarget({
      id: base[0].id, ordinal: 4, title: '夜船', sourceContent: '正文',
    })],
    contextOptions: OPTIONS, contextSnapshot: contextSnapshot(), warnings: [], modelPolicyAtStart: POLICY,
  }));
  assert.throws(() => makePolishJob({
    jobId: 'gap', projectId: 'project-1', branchId: 'main', expectedCas: CAS, now: NOW,
    targets: [base[0], makePolishChapterTarget({
      id: 'chapter-5', ordinal: 5, title: '远航', sourceContent: '正文',
    })],
    contextOptions: OPTIONS, contextSnapshot: contextSnapshot(), warnings: [], modelPolicyAtStart: POLICY,
  }));
  assert.throws(() => makePolishJob({
    jobId: 'duplicate-ordinal', projectId: 'project-1', branchId: 'main', expectedCas: CAS, now: NOW,
    targets: [base[0], makePolishChapterTarget({
      id: 'chapter-other', ordinal: 3, title: '夜船', sourceContent: '正文',
    })],
    contextOptions: OPTIONS, contextSnapshot: contextSnapshot(), warnings: [], modelPolicyAtStart: POLICY,
  }));
  assert.throws(() => makePolishChapterTarget({
    id: 'empty', ordinal: 1, title: '空章', sourceContent: '  ',
  }));
});

test('context snapshot is bounded, path-safe and cannot include a disabled source kind', () => {
  assert.equal(MAX_POLISH_CONTEXT_ITEMS > 0, true);
  assert.equal(MAX_POLISH_CONTEXT_ITEM_CHARS > 0, true);
  assert.equal(MAX_POLISH_CONTEXT_TOTAL_CHARS >= MAX_POLISH_CONTEXT_ITEM_CHARS, true);
  assert.throws(() => makePolishContextSnapshotItem({
    kind: 'plot', sourcePath: '../secret.md', content: '不可越界',
  }));
  assert.throws(() => makePolishContextSnapshotItem({
    kind: 'plot', sourcePath: 'plot/huge.md', content: 'x'.repeat(MAX_POLISH_CONTEXT_ITEM_CHARS + 1),
  }));
  assert.throws(() => makePolishJob({
    jobId: 'disabled-context', projectId: 'project-1', branchId: 'main', expectedCas: CAS, now: NOW,
    targets: targets(), contextOptions: OPTIONS,
    contextSnapshot: [makePolishContextSnapshotItem({
      kind: 'character', sourcePath: 'setting/characters/lead.md', content: '角色设定',
    })],
    warnings: [], modelPolicyAtStart: POLICY,
  }));
  const tooMany = Array.from({ length: MAX_POLISH_CONTEXT_ITEMS + 1 }, (_, index) =>
    makePolishContextSnapshotItem({
      kind: 'plot', sourcePath: `plot/${index}.md`, content: `片段 ${index}`,
    }));
  assert.throws(() => makePolishJob({
    jobId: 'too-many-context', projectId: 'project-1', branchId: 'main', expectedCas: CAS, now: NOW,
    targets: targets(), contextOptions: OPTIONS, contextSnapshot: tooMany,
    warnings: [], modelPolicyAtStart: POLICY,
  }));
});

test('claim lease and epoch reject a second or stale owner', () => {
  const claimed = claimPolishJob(job(), 'owner-a', NOW + 1, 60_000);
  assert.equal(claimed.claim?.epoch, 1);
  assert.throws(() => claimPolishJob(claimed, 'owner-b', NOW + 2, 60_000));
  assert.doesNotThrow(() => assertPolishClaim(claimed, { token: 'owner-a', epoch: 1 }, NOW + 2));
  assert.throws(() => assertPolishClaim(claimed, { token: 'owner-a', epoch: 0 }, NOW + 2));
  const reclaimed = claimPolishJob(claimed, 'owner-b', NOW + 60_001, 60_000);
  assert.equal(reclaimed.claim?.epoch, 2);
  assert.throws(() => assertPolishClaim(reclaimed, { token: 'owner-a', epoch: 1 }, NOW + 60_002));
});

test('candidate and review are bound to chapter, source and candidate digests', () => {
  const writing = writingJob();
  const candidate = candidateFor(writing);
  const reviewing = withPolishCandidate(writing, candidate, NOW + 2);
  assert.equal(reviewing.stage, 'reviewing');
  assert.throws(() => withPolishCandidate(writing, {
    jobId: candidate.jobId,
    candidateId: candidate.candidateId,
    chapterId: candidate.chapterId,
    chapterOrdinal: candidate.chapterOrdinal,
    sourceDigest: 'wrong-source',
    content: candidate.content,
    digest: candidate.digest,
    attempt: candidate.attempt,
  }, NOW + 2));
  const review = validatePolishReview(reviewFor(candidate), candidate);
  assert.equal(review.candidateDigest, candidate.digest);
  assert.throws(() => validatePolishReview({
    jobId: review.jobId,
    chapterId: review.chapterId,
    chapterOrdinal: review.chapterOrdinal,
    sourceDigest: review.sourceDigest,
    candidateId: review.candidateId,
    candidateDigest: 'wrong-candidate',
    findings: review.findings,
    blocking: review.blocking,
    rewriteRequired: review.rewriteRequired,
    rewriteInstructions: review.rewriteInstructions,
  }, candidate));
});

test('durable validation rejects tampered target, context and candidate content', () => {
  const created = job();
  assert.throws(() => validateDurablePolishJob({
    ...created,
    targets: [{ ...created.targets[0], sourceContent: '被篡改的冻结正文' }, created.targets[1]],
  }));
  assert.throws(() => validateDurablePolishJob({
    ...created,
    contextSnapshot: [{ ...created.contextSnapshot[0], content: '被篡改的上下文' },
      ...created.contextSnapshot.slice(1)],
  }));

  const reviewing = withPolishCandidate(writingJob(), candidateFor(writingJob()), NOW + 2);
  assert.throws(() => validateDurablePolishJob({
    ...reviewing,
    candidate: reviewing.candidate === null ? null : {
      ...reviewing.candidate, content: '被篡改的候选正文',
    },
  }));
});

test('candidate and review from another job cannot be transplanted', () => {
  const writing = writingJob();
  const foreignCandidate = { ...candidateFor(writing), jobId: 'polish-other' };
  assert.throws(() => withPolishCandidate(writing, foreignCandidate, NOW + 2));

  const candidate = candidateFor(writing);
  const foreignReview = { ...reviewFor(candidate), jobId: 'polish-other' };
  assert.throws(() => validatePolishReview(foreignReview, candidate));
});

test('review permits at most two targeted rewrites before a durable failure', () => {
  let current = withPolishCandidate(writingJob(), candidateFor(writingJob()), NOW + 2);
  const firstCandidate = current.candidate as PolishCandidate;
  current = applyPolishReview(current, reviewFor(firstCandidate, true), NOW + 3);
  assert.equal(current.stage, 'rewriting_1');
  current = withPolishCandidate(current, candidateFor(current, 1, 'candidate-3-r1'), NOW + 4);
  const secondCandidate = current.candidate as PolishCandidate;
  current = applyPolishReview(current, reviewFor(secondCandidate, true), NOW + 5);
  assert.equal(current.stage, 'rewriting_2');
  current = withPolishCandidate(current, candidateFor(current, 2, 'candidate-3-r2'), NOW + 6);
  const thirdCandidate = current.candidate as PolishCandidate;
  current = applyPolishReview(current, reviewFor(thirdCandidate, true), NOW + 7);
  assert.equal(current.stage, 'failed');
  assert.equal(current.resumeStage, 'reviewing');
  assert.match(current.failure ?? '', /两次/);
});

test('pause and system yield clear ownership, resume exact stage, and failed retry restores it', () => {
  const claimed = claimPolishJob(writingJob(), 'owner-a', NOW + 2, 60_000);
  const paused = pausePolishJob(claimed, { token: 'owner-a', epoch: 1 }, NOW + 3);
  assert.equal(paused.stage, 'paused');
  assert.equal(paused.resumeStage, 'writing');
  assert.equal(paused.claim, null);
  assert.equal(resumePolishJob(paused, NOW + 4).stage, 'writing');

  const claimedAgain = claimPolishJob(resumePolishJob(paused, NOW + 4), 'owner-b', NOW + 5, 60_000);
  const waiting = yieldPolishJobToSystem(claimedAgain, { token: 'owner-b', epoch: 2 }, NOW + 6);
  assert.equal(waiting.stage, 'waiting_system');
  assert.equal(resumePolishJob(waiting, NOW + 7).stage, 'writing');

  const claimedThird = claimPolishJob(resumePolishJob(waiting, NOW + 7), 'owner-c', NOW + 8, 60_000);
  const failed = failPolishJob(claimedThird, { token: 'owner-c', epoch: 3 }, 'provider failed', NOW + 9);
  assert.equal(failed.stage, 'failed');
  assert.equal(failed.resumeStage, 'writing');
  assert.equal(retryPolishJob(failed, NOW + 10).stage, 'writing');
});

test('reviewed commit records a bound receipt and advances the cursor chapter by chapter', () => {
  let current = withPolishCandidate(writingJob(), candidateFor(writingJob()), NOW + 2);
  const firstCandidate = current.candidate as PolishCandidate;
  current = applyPolishReview(current, reviewFor(firstCandidate), NOW + 3);
  assert.equal(current.stage, 'committing');
  current = claimPolishJob(current, 'owner-a', NOW + 4, 60_000);
  const firstReceipt = polishReceipt(
    current.jobId, firstCandidate.chapterId, firstCandidate.chapterOrdinal, firstCandidate.sourceDigest,
    firstCandidate.candidateId, firstCandidate.digest,
  );
  current = commitPolishChapter(current, { token: 'owner-a', epoch: 1 }, {
    branchId: 'main', commitId: 'c2', receipt: firstReceipt,
    nextCas: { branchId: 'main', head: 'c2', treeDigest: 'tree-2' },
  }, NOW + 5);
  assert.equal(current.cursor, 1);
  assert.equal(current.stage, 'queued');
  assert.equal(current.progress[0].receipt, firstReceipt);
  assert.equal(current.expectedCas.head, 'c2');

  current = transitionPolishJob(current, 'writing', NOW + 6);
  const secondCandidate = candidateFor(current, 0, 'candidate-4');
  current = withPolishCandidate(current, secondCandidate, NOW + 7);
  current = applyPolishReview(current, reviewFor(secondCandidate), NOW + 8);
  const secondReceipt = polishReceipt(
    current.jobId, secondCandidate.chapterId, secondCandidate.chapterOrdinal, secondCandidate.sourceDigest,
    secondCandidate.candidateId, secondCandidate.digest,
  );
  current = commitPolishChapter(current, { token: 'owner-a', epoch: 1 }, {
    branchId: 'main', commitId: 'c3', receipt: secondReceipt,
    nextCas: { branchId: 'main', head: 'c3', treeDigest: 'tree-3' },
  }, NOW + 9);
  assert.equal(current.cursor, 2);
  assert.equal(current.stage, 'completed');
  assert.equal(current.claim, null);
  assert.deepEqual(projectPolishProgress(current, new Set<string>(['c1', 'c2', 'c3']))
    .map(progress => progress.chapterOrdinal), [3, 4]);
  assert.deepEqual(projectPolishProgress(current, new Set<string>(['c1', 'c3']))
    .map(progress => progress.chapterOrdinal), [4]);
});

test('validation rejects malformed context bindings and receipts, while cancel is terminal', () => {
  const current = job();
  const invalidContext = {
    ...current,
    contextSnapshot: [{
      kind: 'character' as const,
      sourcePath: 'setting/characters/lead.md',
      digest: 'digest',
      content: '角色设定',
    }],
  };
  assert.throws(() => validateDurablePolishJob(invalidContext));
  const invalidReceipt = {
    ...current,
    cursor: 1,
    progress: [{
      chapterId: current.targets[0].id,
      chapterOrdinal: current.targets[0].ordinal,
      sourceDigest: current.targets[0].sourceDigest,
      candidateId: 'candidate-3',
      candidateDigest: 'candidate-digest',
      receipt: 'wrong',
      commitId: 'c2',
      branchId: 'main',
    }],
  };
  assert.throws(() => validateDurablePolishJob(invalidReceipt));
  const cancelled = cancelPolishJob(current, NOW + 1);
  assert.equal(cancelled.stage, 'cancelled');
  assert.equal(cancelled.claim, null);
  assert.throws(() => transitionPolishJob(cancelled, 'writing', NOW + 2));
});
