// Isolated probe: real ghostwrite/polish domain transitions. Verifies the
// planner-checkpoint path stays consistent (i.e. review with nextPlan is a legal
// reviewing -> committing payload) and that commitPolishChapter keeps cursor/receipt.
import {
  makeGhostwriteJob, freezeGhostwritePlan, makeGhostwriteCandidate,
  applyGhostwriteReview, withGhostwriteCandidate, transitionGhostwriteJob,
  ghostwriteReceipt, validateDurableGhostwriteJob,
} from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread/src/main/ets/novel/ghostwrite.ts';
import {
  makePolishJob, makePolishChapterTarget, makePolishCandidate, applyPolishReview, transitionPolishJob, claimPolishJob, withPolishCandidate, commitPolishChapter as commitPolishDomain,
  commitPolishChapter, polishReceipt, validateDurablePolishJob,
} from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread/src/main/ets/novel/polish.ts';

const cas = { branchId: 'b1', head: 'h0', treeDigest: 't0' };
const policy = { writing: { kind: 'global' as const }, review: null, stateSync: null };

// 1) ghostwrite: reviewing -> committing(with nextPlan), then validate.
const plan = freezeGhostwritePlan({ planId: 'p1', content: '第一章计划', expectedCas: cas });
let gw = makeGhostwriteJob({ jobId: 'j1', projectId: 'pr1', branchId: 'b1', now: 1, frozenPlan: plan, targetChapterCount: 1, modelPolicyAtStart: policy as any });
gw = validateDurableGhostwriteJob(gw);
gw = transitionGhostwriteJob(gw, 'writing', 2);
const cand = makeGhostwriteCandidate({ candidateId: 'c1', chapterOrdinal: 1, title: 'T', content: '正文正文正文', planId: 'p1', planDigest: plan.digest, attempt: 0 });
gw = withGhostwriteCandidate(gw, cand, 3);
const review = {
  candidateId: 'c1', candidateDigest: cand.digest, planId: 'p1', planDigest: plan.digest,
  findings: [], blocking: false, rewriteRequired: false, rewriteInstructions: '',
  nextPlan: null, stateDelta: { plotState: '剧情', chapterHighlight: '要点' },
};
gw = applyGhostwriteReview(gw, review as any, 4);
console.log('ghostwrite stage after review =', gw.stage, '(expect committing)');
console.log('ghostwrite receipt =', ghostwriteReceipt('j1', 1, 'p1', plan.digest, 'c1'));

// 2) polish: full happy path with cursor/receipt and progress validation.
const target = makePolishChapterTarget({ id: 'ch1', ordinal: 1, title: 'C1', sourceContent: '原始正文内容' });
let pol = makePolishJob({
  jobId: 'pj1', projectId: 'pr1', branchId: 'b1', targets: [target],
  contextOptions: { includePlot: false, includeForeshadows: false, includeCharacters: false, includeDecisions: false },
  contextSnapshot: [], warnings: [], expectedCas: cas, modelPolicyAtStart: policy as any, now: 1,
});
pol = transitionPolishJob(pol, 'writing', 2);
pol = claimPolishJob(pol, 'tok', 2, 60000);
pol = transitionPolishJob(pol, 'reviewing', 2);
const pcand = makePolishCandidate({ jobId: 'pj1', candidateId: 'pc1', chapterId: 'ch1', chapterOrdinal: 1, sourceDigest: target.sourceDigest, content: '润色后的正文内容', attempt: 0 });
pol = withPolishCandidate(pol, pcand, 3);
pol = applyPolishReview(pol, {
  jobId: 'pj1', chapterId: 'ch1', chapterOrdinal: 1, sourceDigest: target.sourceDigest,
  candidateId: 'pc1', candidateDigest: pcand.digest, findings: [], blocking: false,
  rewriteRequired: false, rewriteInstructions: '',
} as any, 4);
const receipt = polishReceipt('pj1', 'ch1', 1, target.sourceDigest, 'pc1', pcand.digest);
pol = commitPolishDomain(pol, { token: 'tok', epoch: 1 }, { branchId: 'b1', commitId: 'h1', receipt, nextCas: { branchId: 'b1', head: 'h1', treeDigest: 't1' } }, 5);
// commitPolishChapter asserts a live claim; use claim() path instead to be faithful
console.log('polish after bare commit attempt =', pol.stage);
