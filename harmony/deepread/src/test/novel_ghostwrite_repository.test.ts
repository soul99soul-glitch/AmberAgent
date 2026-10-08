import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { makeNovelProject } from '../main/ets/novel/models.ts';
import type { NovelProject } from '../main/ets/novel/models.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import type { NovelProjectRepository } from '../main/ets/novel/repository.ts';
import { ghostwriteReceipt, makeGhostwriteCandidate } from '../main/ets/novel/ghostwrite.ts';
import type {
  DurableGhostwriteJob, GhostwriteCandidate, GhostwriteClaimRef, GhostwriteReview,
} from '../main/ets/novel/ghostwrite.ts';

const NOW = 3_000_000;

const projectWithPlan = (id: string, plan: string = '第一章计划'): NovelProject => {
  const project: NovelProject = makeNovelProject({ id, name: id, now: NOW });
  return {
    ...project,
    branchSettings: { ...project.branchSettings, thisChapterPlan: plan },
  };
};

const claimRef = (job: DurableGhostwriteJob): GhostwriteClaimRef => {
  if (job.claim === null) throw new Error('test job has no claim');
  return { token: job.claim.token, epoch: job.claim.epoch };
};

const candidateFor = (job: DurableGhostwriteJob, suffix: string): GhostwriteCandidate => {
  if (job.frozenPlan === null) throw new Error('test job has no plan');
  return makeGhostwriteCandidate({
    candidateId: `candidate-${suffix}`,
    chapterOrdinal: job.currentChapterOrdinal,
    title: `第${job.currentChapterOrdinal}章`,
    content: `正文-${suffix}`,
    planId: job.frozenPlan.planId,
    planDigest: job.frozenPlan.digest,
    attempt: job.rewriteCount,
  });
};

const passingReview = (
  job: DurableGhostwriteJob, candidate: GhostwriteCandidate, nextPlan: string | null,
): GhostwriteReview => {
  if (job.frozenPlan === null) throw new Error('test job has no plan');
  return {
    candidateId: candidate.candidateId,
    candidateDigest: candidate.digest,
    planId: job.frozenPlan.planId,
    planDigest: job.frozenPlan.digest,
    findings: [],
    blocking: false,
    rewriteRequired: false,
    rewriteInstructions: '',
    nextPlan,
    stateDelta: {
      plotState: `剧情-${candidate.chapterOrdinal}`,
      chapterHighlight: `要点-${candidate.chapterOrdinal}`,
    },
  };
};

const prepareCommitting = async (
  repository: NovelProjectRepository, projectId: string, job: DurableGhostwriteJob,
  claim: GhostwriteClaimRef, suffix: string, nextPlan: string | null, now: number,
): Promise<{ job: DurableGhostwriteJob; candidate: GhostwriteCandidate; receipt: string }> => {
  let current: DurableGhostwriteJob = await repository.checkpointGhostwriteStage(
    projectId, job.jobId, claim, 'writing', now,
  );
  const candidate: GhostwriteCandidate = candidateFor(current, suffix);
  current = await repository.checkpointGhostwriteCandidate(
    projectId, job.jobId, claim, candidate, now + 1,
  );
  current = await repository.checkpointGhostwriteReview(
    projectId, job.jobId, claim, passingReview(current, candidate, nextPlan), now + 2,
  );
  return {
    job: current,
    candidate,
    receipt: ghostwriteReceipt(
      current.jobId, candidate.chapterOrdinal, candidate.planId,
      candidate.planDigest, candidate.candidateId,
    ),
  };
};

test('ghostwrite start rejects empty/out-of-range and accepts every target 1 through 10', async () => {
  const store = createMemoryFileStore();
  const repository = createFileNovelRepository(store);
  await repository.createProject(projectWithPlan('empty-plan', '   '));
  const emptyStatus = await repository.workspaceStatus('empty-plan');
  await assert.rejects(repository.startGhostwriteJob(
    'empty-plan', emptyStatus.cas, 'empty-job', 'plan-empty', 1, NOW + 1,
  ), /必须存在本章计划/);

  for (let target: number = 1; target <= 10; target++) {
    const id: string = `target-${target}`;
    await repository.createProject(projectWithPlan(id));
    const status = await repository.workspaceStatus(id);
    const job = await repository.startGhostwriteJob(
      id, status.cas, `job-${target}`, `plan-${target}`, target, NOW + target,
    );
    assert.equal(job.targetChapterCount, target);
    assert.equal(job.startChapterOrdinal, 1);
    assert.equal(job.endChapterOrdinal, target);
  }

  await repository.createProject(projectWithPlan('bad-target'));
  const badStatus = await repository.workspaceStatus('bad-target');
  await assert.rejects(repository.startGhostwriteJob(
    'bad-target', badStatus.cas, 'job-0', 'plan-0', 0, NOW + 20,
  ), /1-10/);
  await assert.rejects(repository.startGhostwriteJob(
    'bad-target', badStatus.cas, 'job-11', 'plan-11', 11, NOW + 21,
  ), /1-10/);
});

test('ghostwrite claim rejects double owner, old epoch and expired lease; lifecycle rotates owner', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  await repository.createProject(projectWithPlan('claim'));
  const status = await repository.workspaceStatus('claim');
  await repository.startGhostwriteJob('claim', status.cas, 'job', 'plan', 1, NOW + 1);
  const first = await repository.claimGhostwriteJob('claim', 'job', 'owner-a', NOW + 2, 10);
  const firstRef = claimRef(first);
  await assert.rejects(repository.claimGhostwriteJob(
    'claim', 'job', 'owner-b', NOW + 3, 10,
  ), /其他 owner/);
  const second = await repository.claimGhostwriteJob('claim', 'job', 'owner-b', NOW + 13, 10);
  const secondRef = claimRef(second);
  assert.equal(secondRef.epoch, firstRef.epoch + 1);
  await assert.rejects(repository.checkpointGhostwriteStage(
    'claim', 'job', firstRef, 'writing', NOW + 14,
  ), /lease 已失效/);
  await assert.rejects(repository.checkpointGhostwriteStage(
    'claim', 'job', secondRef, 'writing', NOW + 23,
  ), /lease 已失效/);
  const third = await repository.claimGhostwriteJob('claim', 'job', 'owner-c', NOW + 24, 100);
  const thirdRef = claimRef(third);
  const writing = await repository.checkpointGhostwriteStage(
    'claim', 'job', thirdRef, 'writing', NOW + 25,
  );
  const paused = await repository.pauseGhostwriteJob('claim', 'job', thirdRef, NOW + 26);
  assert.equal(paused.stage, 'paused');
  assert.equal((await repository.resumeGhostwriteJob('claim', 'job', NOW + 27)).stage, writing.stage);
  const fourth = await repository.claimGhostwriteJob('claim', 'job', 'owner-d', NOW + 28, 100);
  const fourthRef = claimRef(fourth);
  const failed = await repository.failGhostwriteJob('claim', 'job', fourthRef, 'provider', NOW + 29);
  assert.equal(failed.stage, 'failed');
  assert.equal((await repository.retryGhostwriteJob('claim', 'job', NOW + 30)).stage, 'writing');
  const fifth = await repository.claimGhostwriteJob('claim', 'job', 'owner-e', NOW + 31, 100);
  assert.equal((await repository.cancelGhostwriteJob(
    'claim', 'job', claimRef(fifth), NOW + 32)).stage, 'cancelled');
});

test('ghostwrite actions fail closed on CAS drift, block branch changes and freeze bound plan', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  await repository.createProject(projectWithPlan('drift'));
  const before = await repository.workspaceStatus('drift');
  await repository.startGhostwriteJob('drift', before.cas, 'job', 'plan', 1, NOW + 1);
  await assert.rejects(repository.commitProject(
    'drift', before.cas, 'change-plan', 'branch_settings_change', project => ({
      ...project,
      branchSettings: { ...project.branchSettings, thisChapterPlan: '偷换计划' },
    }),
  ), /冻结本章计划/);
  await repository.commitProject(
    'drift', before.cas, 'rename-drift', 'rename', project => ({ ...project, name: '变更' }),
  );
  await assert.rejects(repository.claimGhostwriteJob(
    'drift', 'job', 'owner', NOW + 2, 100,
  ), /工作区已变化/);

  await repository.createProject(projectWithPlan('branch-drift'));
  const main = await repository.workspaceStatus('branch-drift');
  await repository.startGhostwriteJob('branch-drift', main.cas, 'branch-job', 'plan', 1, NOW + 3);
  await assert.rejects(repository.createBranch(
    'branch-drift', 'fork', main.cas, 'fork-command',
  ), /已有未结束的小说作业/);
});

test('ghostwrite candidate and review checkpoints reject mismatched bindings', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  await repository.createProject(projectWithPlan('binding'));
  const status = await repository.workspaceStatus('binding');
  await repository.startGhostwriteJob('binding', status.cas, 'job', 'plan', 1, NOW + 1);
  const claimed = await repository.claimGhostwriteJob('binding', 'job', 'owner', NOW + 2, 100);
  const claim = claimRef(claimed);
  const writing = await repository.checkpointGhostwriteStage(
    'binding', 'job', claim, 'writing', NOW + 3,
  );
  const wrongCandidate: GhostwriteCandidate = {
    ...candidateFor(writing, 'wrong'),
    planDigest: 'wrong-digest',
  };
  await assert.rejects(repository.checkpointGhostwriteCandidate(
    'binding', 'job', claim, wrongCandidate, NOW + 4,
  ), /绑定不匹配/);
  const candidate = candidateFor(writing, 'good');
  const reviewing = await repository.checkpointGhostwriteCandidate(
    'binding', 'job', claim, candidate, NOW + 5,
  );
  const wrongReview: GhostwriteReview = {
    ...passingReview(reviewing, candidate, null),
    candidateId: 'other-candidate',
  };
  await assert.rejects(repository.checkpointGhostwriteReview(
    'binding', 'job', claim, wrongReview, NOW + 6,
  ), /不匹配/);
});

test('ghostwrite commit is atomic, replay-safe, rotates next plan and clears final plan after reload', async () => {
  const store = createMemoryFileStore();
  const repository = createFileNovelRepository(store);
  await repository.createProject(projectWithPlan('commit'));
  const status = await repository.workspaceStatus('commit');
  await repository.startGhostwriteJob('commit', status.cas, 'job', 'plan-1', 2, NOW + 1);
  let job = await repository.claimGhostwriteJob('commit', 'job', 'owner', NOW + 2, 1_000);
  const claim = claimRef(job);
  const first = await prepareCommitting(
    repository, 'commit', job, claim, 'one', '第二章计划', NOW + 3,
  );
  assert.equal((await repository.loadProject('commit')).chapters.length, 0);
  const beforeCommitLedger = await store.readText(
    'amberagent/novel-workspace/commit/.amber/ledger.jsonl');
  assert.equal((beforeCommitLedger ?? '').trim().split('\n').length, 1);
  const beforeCommitFiles = await repository.workspaceFiles('commit');
  assert.equal(beforeCommitFiles.some(file =>
    new TextDecoder().decode(file.bytes).includes(first.candidate.content)), false);
  job = await repository.commitGhostwriteChapter(
    'commit', 'job', claim, first.receipt, NOW + 6,
  );
  assert.equal(job.stage, 'planning');
  assert.equal(job.currentChapterOrdinal, 2);
  assert.equal((await repository.loadProject('commit')).branchSettings.thisChapterPlan, '第二章计划');
  const firstReplay = await repository.commitGhostwriteChapter(
    'commit', 'job', claim, first.receipt, NOW + 7,
  );
  assert.equal(firstReplay.progress.length, 1);
  assert.equal((await repository.loadProject('commit')).chapters.length, 1);

  const second = await prepareCommitting(
    repository, 'commit', job, claim, 'two', null, NOW + 8,
  );
  job = await repository.commitGhostwriteChapter(
    'commit', 'job', claim, second.receipt, NOW + 11,
  );
  assert.equal(job.stage, 'completed');
  assert.equal(job.progress.length, 2);
  const project = await repository.loadProject('commit');
  assert.equal(project.chapters.length, 2);
  assert.equal(project.branchSettings.thisChapterPlan, '');
  assert.equal(project.messages.length, 0);
  assert.deepEqual(project.chapterPlots.map(pointer => pointer.stale), [false, false]);
  assert.equal(project.chapterPlots[1].text.includes('正文-two'), true);
  const ledger = await store.readText('amberagent/novel-workspace/commit/.amber/ledger.jsonl');
  assert.equal((ledger ?? '').trim().split('\n').length, 3);
  const cold = createFileNovelRepository(store);
  const reloaded = await cold.loadGhostwriteJob('commit', 'job');
  assert.equal(reloaded.stage, 'completed');
  assert.equal(reloaded.progress.length, 2);
  assert.equal((await cold.ghostwriteProgress('commit', 'job')).length, 2);
  assert.deepEqual((await cold.loadProject('commit')).chapterPlots, project.chapterPlots);
});

test('ghostwrite appends deterministic facts without overwriting author plot', async () => {
  const store = createMemoryFileStore();
  const repository = createFileNovelRepository(store);
  await repository.createProject(projectWithPlan('author-plot'));
  let status = await repository.workspaceStatus('author-plot');
  await repository.createProposal('author-plot', status.cas, 'author-plan', [{
    operation: 'write', path: 'branches/main/plan/plot.md', content: '作者的长线规划。',
  }], NOW + 1);
  await repository.resolveProposal('author-plot', 'author-plan', true, 'adopt-author-plan', NOW + 2);
  status = await repository.workspaceStatus('author-plot');
  await repository.startGhostwriteJob('author-plot', status.cas, 'job', 'plan', 1, NOW + 3);
  const job = await repository.claimGhostwriteJob('author-plot', 'job', 'owner', NOW + 4, 1000);
  const claim = claimRef(job);
  const prepared = await prepareCommitting(repository, 'author-plot', job, claim, 'one', null, NOW + 5);
  await repository.commitGhostwriteChapter('author-plot', 'job', claim, prepared.receipt, NOW + 9);
  assert.equal(await store.readText('amberagent/novel-workspace/author-plot/branches/main/plan/plot.md'), '作者的长线规划。');
  const project = await createFileNovelRepository(store).loadProject('author-plot');
  assert.equal(project.chapterPlots[0].text.includes(prepared.candidate.content), true);
  assert.equal((await repository.workspaceStatus('author-plot')).plotStale, false);
});

test('ghostwrite progress counts only receipts in the current branch ancestry', async () => {
  const store = createMemoryFileStore();
  const repository = createFileNovelRepository(store);
  await repository.createProject(projectWithPlan('ancestry'));
  const status = await repository.workspaceStatus('ancestry');
  await repository.startGhostwriteJob('ancestry', status.cas, 'job', 'plan', 2, NOW + 1);
  let job = await repository.claimGhostwriteJob('ancestry', 'job', 'owner', NOW + 2, 100);
  const claim = claimRef(job);
  const prepared = await prepareCommitting(
    repository, 'ancestry', job, claim, 'real', '下一章计划', NOW + 3,
  );
  job = await repository.commitGhostwriteChapter(
    'ancestry', 'job', claim, prepared.receipt, NOW + 6,
  );
  assert.equal((await repository.ghostwriteProgress('ancestry', 'job')).length, 1);
  const raw = await store.readText('amberagent/novel-workspace/ancestry/.amber/jobs.json');
  const parsed = JSON.parse(raw ?? '{"version":1,"jobs":[]}') as { version: number; jobs: DurableGhostwriteJob[] };
  parsed.jobs[0].progress.push({
    chapterOrdinal: 2,
    planId: 'fake-plan',
    planDigest: 'fake-digest',
    candidateId: 'fake-candidate',
    receipt: ghostwriteReceipt('job', 2, 'fake-plan', 'fake-digest', 'fake-candidate'),
    commitId: 'not-in-ancestry',
    branchId: 'main',
  });
  await store.writeText(
    'amberagent/novel-workspace/ancestry/.amber/jobs.json', JSON.stringify(parsed),
  );
  const cold = createFileNovelRepository(store);
  assert.equal((await cold.ghostwriteProgress('ancestry', 'job')).length, 1);
});
