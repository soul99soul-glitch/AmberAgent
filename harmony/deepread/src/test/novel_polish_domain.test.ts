import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  MAX_POLISH_CONTEXT_ITEMS, MAX_POLISH_CONTEXT_ITEM_CHARS, applyPolishReview, cancelPolishJob,
  claimPolishJob, commitPolishChapter, makePolishCandidate, makePolishChapterTarget,
  makePolishContextSnapshotItem, makePolishJob, polishReceipt, projectPolishProgress,
  transitionPolishJob, validateDurablePolishJob, withPolishCandidate, type DurablePolishJob,
  type PolishCandidate, type PolishContextOptions, type PolishReview,
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

test('target validation rejects duplicate ids, duplicate or descending ordinals and empty source', () => {
  const base = targets();
  assert.throws(() => makePolishJob({
    jobId: 'duplicate-id', projectId: 'project-1', branchId: 'main', expectedCas: CAS, now: NOW,
    targets: [base[0], makePolishChapterTarget({
      id: base[0].id, ordinal: 4, title: '夜船', sourceContent: '正文',
    })],
    contextOptions: OPTIONS, contextSnapshot: contextSnapshot(), warnings: [], modelPolicyAtStart: POLICY,
  }));
  assert.throws(() => makePolishJob({
    jobId: 'descending', projectId: 'project-1', branchId: 'main', expectedCas: CAS, now: NOW,
    targets: [base[0], makePolishChapterTarget({
      id: 'chapter-2', ordinal: 2, title: '远航', sourceContent: '正文',
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
