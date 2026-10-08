import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  freezeGhostwritePlan, ghostwriteReceipt, makeGhostwriteJob, projectGhostwriteProgress,
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
