import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import {
  makeNovelChapter, makeNovelMaterial, makeNovelProject,
} from '../main/ets/novel/models.ts';
import type { NovelProject } from '../main/ets/novel/models.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import type { NovelProjectRepository } from '../main/ets/novel/repository.ts';
import { defaultGhostwriteDigest } from '../main/ets/novel/ghostwrite.ts';
import type {
  DurableGhostwriteJob, GhostwriteClaimRef,
} from '../main/ets/novel/ghostwrite.ts';
import { makePolishCandidate, polishReceipt } from '../main/ets/novel/polish.ts';
import type {
  DurablePolishJob, PolishCandidate, PolishContextOptions, PolishReview,
} from '../main/ets/novel/polish.ts';

const NOW = 7_000_000;
const JOBS_PATH = (id: string): string =>
  `amberagent/novel-workspace/${id}/.amber/jobs.json`;
const LEDGER_PATH = (id: string): string =>
  `amberagent/novel-workspace/${id}/.amber/ledger.jsonl`;

const ALL_CONTEXT: PolishContextOptions = {
  includePlot: true,
  includeForeshadows: true,
  includeCharacters: true,
  includeDecisions: true,
};

const projectFixture = (id: string): NovelProject => {
  const project: NovelProject = makeNovelProject({ id, name: id, now: NOW });
  return {
    ...project,
    chapters: [
      makeNovelChapter({ id: 'chapter-1', title: '第一章', content: '第一章旧正文。', now: NOW }),
      makeNovelChapter({ id: 'chapter-2', title: '第二章', content: '第二章旧正文。', now: NOW }),
      makeNovelChapter({ id: 'chapter-3', title: '第三章', content: '第三章旧正文。', now: NOW }),
    ],
    materials: [
      makeNovelMaterial({
        id: 'hero', kind: 'character', title: '林岚', content: '沉着，左手有旧伤。', now: NOW,
      }),
      makeNovelMaterial({
        id: 'disabled-character', kind: 'character', title: '未采用角色',
        content: '不应进入快照。', enabled: false, now: NOW,
      }),
      makeNovelMaterial({
        id: 'world', kind: 'world', title: '城池', content: '不属于角色上下文。', now: NOW,
      }),
    ],
    branchSettings: {
      ...project.branchSettings,
      thisChapterPlan: '续写第四章。',
      foreshadows: [{
        id: 'ferry', title: '渡船旧案', content: '船夫知道旧案。', status: 'open',
        createdAt: NOW, resolvedAt: null,
      }],
      confirmedDecisions: [{
        id: 'tone', title: '叙事视角', content: '保持第三人称限知。', confirmedAt: NOW,
      }],
    },
  };
};

const claimRef = (job: DurablePolishJob): GhostwriteClaimRef => {
  if (job.claim === null) throw new Error('test polish job has no claim');
  return { token: job.claim.token, epoch: job.claim.epoch };
};

const candidateFor = (job: DurablePolishJob, suffix: string): PolishCandidate => {
  const target = job.targets[job.cursor];
  if (target === undefined) throw new Error('test polish job has no current target');
  return makePolishCandidate({
    jobId: job.jobId,
    candidateId: `candidate-${suffix}`,
    chapterId: target.id,
    chapterOrdinal: target.ordinal,
    sourceDigest: target.sourceDigest,
    content: `${target.title}润色正文-${suffix}`,
    attempt: job.rewriteCount,
  });
};

const passingReview = (candidate: PolishCandidate): PolishReview => ({
  jobId: candidate.jobId,
  chapterId: candidate.chapterId,
  chapterOrdinal: candidate.chapterOrdinal,
  sourceDigest: candidate.sourceDigest,
  candidateId: candidate.candidateId,
  candidateDigest: candidate.digest,
  findings: [],
  blocking: false,
  rewriteRequired: false,
  rewriteInstructions: '',
});

const prepareCommitting = async (
  repository: NovelProjectRepository, projectId: string, job: DurablePolishJob,
  claim: GhostwriteClaimRef, suffix: string, now: number,
): Promise<{ job: DurablePolishJob; candidate: PolishCandidate; receipt: string }> => {
  let current: DurablePolishJob = await repository.checkpointPolishStage(
    projectId, job.jobId, claim, 'writing', now,
  );
  const candidate: PolishCandidate = candidateFor(current, suffix);
  current = await repository.checkpointPolishCandidate(
    projectId, job.jobId, claim, candidate, now + 1,
  );
  current = await repository.checkpointPolishReview(
    projectId, job.jobId, claim, passingReview(candidate), now + 2,
  );
  return {
    job: current,
    candidate,
    receipt: polishReceipt(
      current.jobId, candidate.chapterId, candidate.chapterOrdinal, candidate.sourceDigest,
      candidate.candidateId, candidate.digest,
    ),
  };
};

const lineCount = (raw: string | null): number =>
  raw === null || raw.trim().length === 0 ? 0 : raw.trim().split('\n').length;

test('polish start requires synchronized plot and snapshots selected context after sync', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  await repository.createProject(projectFixture('start'));
  const created = await repository.workspaceStatus('start');
  await repository.createProposal('start', created.cas, 'seed-plot', [{
    operation: 'write',
    path: 'branches/main/plan/plot.md',
    content: '林岚已抵达渡口，旧案线索尚未揭开。',
  }], NOW + 1);
  await repository.resolveProposal('start', 'seed-plot', true, 'accept-seed-plot', NOW + 2);
  const synced = await repository.workspaceStatus('start');
  await repository.commitProject(
    'start', synced.cas, 'middle-edit-before-polish', 'manual_edit', project => ({
      ...project,
      chapters: project.chapters.map(chapter => chapter.id === 'chapter-1'
        ? { ...chapter, content: '第一章启动前修订。', updatedAt: NOW + 1 }
        : chapter),
      updatedAt: NOW + 3,
    }),
  );
  const status = await repository.workspaceStatus('start');
  assert.equal(status.plotStale, true);
  assert.equal(status.unresolvedFromOrdinal, 2);

  await assert.rejects(repository.startPolishJob(
    'start', status.cas, 'polish-1-3', 1, 3, ALL_CONTEXT, NOW + 4,
  ), /润色前必须同步剧情/);
  await repository.syncPlot('start', status.cas, 'sync-before-polish');
  const ready = await repository.workspaceStatus('start');
  const job = await repository.startPolishJob(
    'start', ready.cas, 'polish-1-3', 1, 3, ALL_CONTEXT, NOW + 4,
  );
  assert.deepEqual(job.targets.map(target => target.ordinal), [1, 2, 3]);
  assert.deepEqual(job.targets.map(target => target.id), ['chapter-1', 'chapter-2', 'chapter-3']);
  assert.equal(job.targets[0].sourceContent, '第一章启动前修订。');
  assert.equal(job.targets[0].sourceDigest, defaultGhostwriteDigest('第一章启动前修订。'));
  assert.deepEqual(job.contextSnapshot.map(item => item.kind), [
    'material', 'character', 'plot', 'plot', 'plot', 'plot', 'foreshadow', 'decision',
  ]);
  assert.deepEqual(job.contextSnapshot.map(item => item.sourcePath), [
    'branches/main/setting/world/world.md',
    'branches/main/setting/character/hero.md',
    'branches/main/plan/plot.md',
    'branches/main/plot/chapters/chapter-1.md',
    'branches/main/plot/chapters/chapter-2.md',
    'branches/main/plot/chapters/chapter-3.md',
    'branches/main/setting/catalog.json/foreshadow/ferry',
    'branches/main/setting/catalog.json/decision/tone',
  ]);
  assert.equal(job.contextSnapshot.some(item => item.content.includes('未采用角色')), false);
  assert.equal(job.contextSnapshot.some(item => item.content.includes('不属于角色上下文')), true);
  for (let i: number = 0; i < job.contextSnapshot.length; i++) {
    assert.equal(job.contextSnapshot[i].digest, defaultGhostwriteDigest(job.contextSnapshot[i].content));
  }
  assert.deepEqual(job.warnings, []);
  assert.deepEqual((await repository.listPolishJobs('start')).map(item => item.jobId), ['polish-1-3']);

  const missingRepository = createFileNovelRepository(createMemoryFileStore());
  await missingRepository.createProject(projectFixture('invalid-range'));
  const missingStatus = await missingRepository.workspaceStatus('invalid-range');
  await assert.rejects(missingRepository.startPolishJob(
    'invalid-range', missingStatus.cas, 'reverse', 3, 2, ALL_CONTEXT, NOW + 3,
  ), /润色范围无效/);
  await assert.rejects(missingRepository.startPolishJob(
    'invalid-range', missingStatus.cas, 'missing', 2, 4, ALL_CONTEXT, NOW + 4,
  ), /第 4 章不存在或已废弃/);
});

test('ghostwrite and polish jobs are mutually exclusive in both directions on one branch', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  await repository.createProject(projectFixture('exclusive'));
  const status = await repository.workspaceStatus('exclusive');
  await repository.startPolishJob(
    'exclusive', status.cas, 'polish-active', 1, 1, ALL_CONTEXT, NOW + 1,
  );
  await assert.rejects(repository.startGhostwriteJob(
    'exclusive', status.cas, 'ghost-blocked', 'plan', 1, NOW + 2,
  ), /已有未结束的小说作业/);

  const cancelled = await repository.cancelPolishJob('exclusive', 'polish-active', NOW + 3);
  assert.equal(cancelled.stage, 'cancelled');
  await repository.startGhostwriteJob(
    'exclusive', status.cas, 'ghost-active', 'plan', 1, NOW + 4,
  );
  await assert.rejects(repository.startPolishJob(
    'exclusive', status.cas, 'polish-blocked', 1, 1, ALL_CONTEXT, NOW + 5,
  ), /已有未结束的小说作业/);
});

test('active novel job blocks branch switch until the job is terminal', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  await repository.createProject(projectFixture('branch-lock'));
  const mainStatus = await repository.workspaceStatus('branch-lock');
  await repository.createBranch('branch-lock', '支线', mainStatus.cas, 'create-fork');
  const forkStatus = await repository.workspaceStatus('branch-lock');
  await repository.startPolishJob(
    'branch-lock', forkStatus.cas, 'polish-branch-lock', 1, 1, ALL_CONTEXT, NOW + 1,
  );
  await assert.rejects(repository.switchBranch(
    'branch-lock', mainStatus.activeBranchId, forkStatus.cas,
  ), /已有未结束的小说作业/);
  await repository.cancelPolishJob('branch-lock', 'polish-branch-lock', NOW + 2);
  const switched = await repository.switchBranch(
    'branch-lock', mainStatus.activeBranchId, forkStatus.cas,
  );
  assert.equal(switched.id, 'branch-lock');
  const backOnMain = await repository.workspaceStatus('branch-lock');
  assert.equal(backOnMain.activeBranchId, mainStatus.activeBranchId);
  await repository.startPolishJob(
    'branch-lock', backOnMain.cas, 'polish-create-branch-lock', 1, 1, ALL_CONTEXT, NOW + 3,
  );
  await assert.rejects(repository.createBranch(
    'branch-lock', '不应创建', backOnMain.cas, 'blocked-fork',
  ), /已有未结束的小说作业/);
});

test('shared FileStore lock serializes concurrent claims across repository instances', async () => {
  const store = createMemoryFileStore();
  const firstRepository = createFileNovelRepository(store);
  const secondRepository = createFileNovelRepository(store);
  await firstRepository.createProject(projectFixture('cross-process-lock'));
  const status = await firstRepository.workspaceStatus('cross-process-lock');
  await firstRepository.startPolishJob(
    'cross-process-lock', status.cas, 'polish-claim-race', 1, 1, ALL_CONTEXT, NOW + 1,
  );

  const claims = await Promise.allSettled([
    firstRepository.claimPolishJob('cross-process-lock', 'polish-claim-race', 'owner-a', NOW + 2, 1_000),
    secondRepository.claimPolishJob('cross-process-lock', 'polish-claim-race', 'owner-b', NOW + 2, 1_000),
  ]);
  const fulfilled = claims.filter(
    (result): result is PromiseFulfilledResult<DurablePolishJob> => result.status === 'fulfilled');
  assert.equal(fulfilled.length, 1);
  const persisted = await firstRepository.loadPolishJob('cross-process-lock', 'polish-claim-race');
  assert.equal(persisted.claim?.token, fulfilled[0].value.claim?.token);
  assert.equal(persisted.claim?.epoch, fulfilled[0].value.claim?.epoch);

  const claim = claimRef(persisted);
  const stageResults = await Promise.allSettled([
    firstRepository.checkpointPolishStage(
      'cross-process-lock', persisted.jobId, claim, 'writing', NOW + 3),
    secondRepository.checkpointPolishStage(
      'cross-process-lock', persisted.jobId, claim, 'writing', NOW + 3),
  ]);
  assert.equal(stageResults.filter(result => result.status === 'fulfilled').length, 1);
  let writing = await firstRepository.loadPolishJob('cross-process-lock', persisted.jobId);
  assert.equal(writing.stage, 'writing');
  const candidate = candidateFor(writing, 'locked');
  writing = await firstRepository.checkpointPolishCandidate(
    'cross-process-lock', writing.jobId, claim, candidate, NOW + 4);
  writing = await firstRepository.checkpointPolishReview(
    'cross-process-lock', writing.jobId, claim, passingReview(candidate), NOW + 5);
  const receipt = polishReceipt(
    writing.jobId, candidate.chapterId, candidate.chapterOrdinal, candidate.sourceDigest,
    candidate.candidateId, candidate.digest,
  );
  const commits = await Promise.all([
    firstRepository.commitPolishChapter(
      'cross-process-lock', writing.jobId, claim, receipt, NOW + 6),
    secondRepository.commitPolishChapter(
      'cross-process-lock', writing.jobId, claim, receipt, NOW + 6),
  ]);
  assert.equal(commits[0].progress.length, 1);
  assert.equal(commits[1].progress.length, 1);
  const committed = await firstRepository.loadProject('cross-process-lock');
  assert.equal(committed.chapters[0].content, candidate.content);
  assert.equal(committed.chapterVersions.length, 1);
});

test('cold reload rejects a tampered durable candidate before commit', async () => {
  const store = createMemoryFileStore();
  const repository = createFileNovelRepository(store);
  await repository.createProject(projectFixture('tamper-cold'));
  const status = await repository.workspaceStatus('tamper-cold');
  let job = await repository.startPolishJob(
    'tamper-cold', status.cas, 'polish-tamper', 1, 1, ALL_CONTEXT, NOW + 1,
  );
  job = await repository.claimPolishJob('tamper-cold', job.jobId, 'owner', NOW + 2, 1_000);
  const claim = claimRef(job);
  job = await repository.checkpointPolishStage(
    'tamper-cold', job.jobId, claim, 'writing', NOW + 3);
  await repository.checkpointPolishCandidate(
    'tamper-cold', job.jobId, claim, candidateFor(job, 'safe'), NOW + 4);
  const raw = await store.readText(JOBS_PATH('tamper-cold'));
  if (raw === null) throw new Error('test jobs store missing');
  const parsed = JSON.parse(raw) as { jobs: Array<{ candidate: PolishCandidate | null }> };
  if (parsed.jobs[0].candidate === null) throw new Error('test candidate missing');
  parsed.jobs[0].candidate.content = '篡改后的未审稿正文';
  await store.writeText(JOBS_PATH('tamper-cold'), JSON.stringify(parsed));

  const cold = createFileNovelRepository(store);
  await assert.rejects(cold.loadPolishJob('tamper-cold', 'polish-tamper'), /digest 与 content 不匹配/);
});

test('polish checkpoints and atomic commits preserve backups, replay receipts and survive cold reload', async () => {
  const store = createMemoryFileStore();
  const repository = createFileNovelRepository(store);
  await repository.createProject(projectFixture('commit'));
  const status = await repository.workspaceStatus('commit');
  let job = await repository.startPolishJob(
    'commit', status.cas, 'polish-two', 1, 2, ALL_CONTEXT, NOW + 1,
  );
  job = await repository.claimPolishJob('commit', 'polish-two', 'owner', NOW + 2, 1_000);
  const claim = claimRef(job);
  job = await repository.checkpointPolishStage(
    'commit', 'polish-two', claim, 'writing', NOW + 3,
  );
  const candidate = candidateFor(job, 'one');
  await assert.rejects(repository.checkpointPolishCandidate(
    'commit', 'polish-two', claim, { ...candidate, sourceDigest: 'wrong-source' }, NOW + 4,
  ), /绑定不匹配/);
  job = await repository.checkpointPolishCandidate(
    'commit', 'polish-two', claim, candidate, NOW + 5,
  );
  await assert.rejects(repository.checkpointPolishReview(
    'commit', 'polish-two', claim,
    { ...passingReview(candidate), candidateId: 'wrong-candidate' }, NOW + 6,
  ), /绑定不匹配/);
  job = await repository.checkpointPolishReview(
    'commit', 'polish-two', claim, passingReview(candidate), NOW + 7,
  );
  assert.equal(job.stage, 'committing');
  const firstReceipt = polishReceipt(
    job.jobId, candidate.chapterId, candidate.chapterOrdinal, candidate.sourceDigest,
    candidate.candidateId, candidate.digest,
  );
  assert.equal((await repository.loadProject('commit')).chapters[0].content, '第一章旧正文。');
  assert.equal(lineCount(await store.readText(LEDGER_PATH('commit'))), 1);
  await assert.rejects(repository.commitPolishChapter(
    'commit', 'polish-two', claim, 'wrong-receipt', NOW + 8,
  ), /receipt 与候选不匹配/);

  job = await repository.commitPolishChapter(
    'commit', 'polish-two', claim, firstReceipt, NOW + 9,
  );
  assert.equal(job.stage, 'queued');
  assert.equal(job.cursor, 1);
  assert.equal(job.progress.length, 1);
  const afterFirst = await repository.loadProject('commit');
  assert.equal(afterFirst.chapters[0].content, candidate.content);
  assert.equal(afterFirst.chapters[1].content, '第二章旧正文。');
  assert.equal(afterFirst.chapterVersions.length, 1);
  assert.equal(afterFirst.chapterVersions[0].chapterId, 'chapter-1');
  assert.equal(afterFirst.chapterVersions[0].kind, 'polish');
  assert.equal(afterFirst.chapterVersions[0].content, '第一章旧正文。');
  assert.deepEqual(afterFirst.chapterPlots.map(pointer => pointer.stale), [false, true, true]);
  assert.equal(lineCount(await store.readText(LEDGER_PATH('commit'))), 2);
  assert.deepEqual(job.expectedCas, (await repository.workspaceStatus('commit')).cas);

  const replay = await repository.commitPolishChapter(
    'commit', 'polish-two', claim, firstReceipt, NOW + 10,
  );
  assert.equal(replay.progress.length, 1);
  assert.equal((await repository.loadProject('commit')).chapterVersions.length, 1);
  assert.equal(lineCount(await store.readText(LEDGER_PATH('commit'))), 2);

  const second = await prepareCommitting(
    repository, 'commit', job, claim, 'two', NOW + 11,
  );
  job = await repository.commitPolishChapter(
    'commit', 'polish-two', claim, second.receipt, NOW + 14,
  );
  assert.equal(job.stage, 'completed');
  assert.equal(job.cursor, 2);
  assert.equal(job.progress.length, 2);
  const completed = await repository.loadProject('commit');
  assert.deepEqual(completed.chapters.slice(0, 2).map(chapter => chapter.content), [
    candidate.content, second.candidate.content,
  ]);
  assert.deepEqual(completed.chapterVersions.map(version => version.content), [
    '第一章旧正文。', '第二章旧正文。',
  ]);
  assert.deepEqual(completed.chapterPlots.map(pointer => pointer.stale), [false, false, true]);
  assert.equal((await repository.workspaceStatus('commit')).unresolvedFromOrdinal, 3);
  assert.equal(lineCount(await store.readText(LEDGER_PATH('commit'))), 3);

  const cold = createFileNovelRepository(store);
  const reloaded = await cold.loadPolishJob('commit', 'polish-two');
  assert.equal(reloaded.stage, 'completed');
  assert.equal(reloaded.progress.length, 2);
  assert.equal((await cold.polishProgress('commit', 'polish-two')).length, 2);
  const coldReplay = await cold.commitPolishChapter(
    'commit', 'polish-two', claim, firstReceipt, NOW + 15,
  );
  assert.equal(coldReplay.progress.length, 2);
  assert.equal(lineCount(await store.readText(LEDGER_PATH('commit'))), 3);
});

test('polish rejects stale start CAS and source drift without overwriting newer chapter text', async () => {
  const store = createMemoryFileStore();
  const repository = createFileNovelRepository(store);
  await repository.createProject(projectFixture('drift'));
  const stale = await repository.workspaceStatus('drift');
  await repository.commitProject(
    'drift', stale.cas, 'rename-before-start', 'rename', project => ({ ...project, name: '已改名' }),
  );
  await assert.rejects(repository.startPolishJob(
    'drift', stale.cas, 'stale-start', 1, 1, ALL_CONTEXT, NOW + 1,
  ), /工作区已变化/);

  const current = await repository.workspaceStatus('drift');
  let job = await repository.startPolishJob(
    'drift', current.cas, 'source-drift', 1, 1, ALL_CONTEXT, NOW + 2,
  );
  job = await repository.claimPolishJob('drift', 'source-drift', 'owner', NOW + 3, 1_000);
  const claim = claimRef(job);
  const prepared = await prepareCommitting(
    repository, 'drift', job, claim, 'prepared', NOW + 4,
  );
  const beforeDrift = await repository.workspaceStatus('drift');
  await repository.commitProject(
    'drift', beforeDrift.cas, 'external-source-edit', 'manual_edit', project => ({
      ...project,
      chapters: project.chapters.map(chapter => chapter.id === 'chapter-1'
        ? { ...chapter, content: '任务启动后的新正文。', updatedAt: NOW + 7 }
        : chapter),
      updatedAt: NOW + 7,
    }),
  );
  await assert.rejects(repository.commitPolishChapter(
    'drift', 'source-drift', claim, prepared.receipt, NOW + 8,
  ), /工作区已变化/);

  // Bring only the durable job's CAS up to date to isolate the independent source-digest guard.
  const afterDrift = await repository.workspaceStatus('drift');
  const raw = await store.readText(JOBS_PATH('drift'));
  const parsed = JSON.parse(raw ?? '{"version":2,"jobs":[]}') as {
    version: number;
    jobs: DurablePolishJob[];
  };
  parsed.jobs[0].expectedCas = afterDrift.cas;
  await store.writeText(JOBS_PATH('drift'), JSON.stringify(parsed));
  const cold = createFileNovelRepository(store);
  await assert.rejects(cold.commitPolishChapter(
    'drift', 'source-drift', claim, prepared.receipt, NOW + 9,
  ), /目标章节已在任务启动后变更/);
  const unchanged = await cold.loadProject('drift');
  assert.equal(unchanged.chapters[0].content, '任务启动后的新正文。');
  assert.equal(unchanged.chapterVersions.length, 0);
});

test('polish pause, waiting-system, resume, fail, retry and cancel persist across repository calls', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  await repository.createProject(projectFixture('lifecycle'));
  const status = await repository.workspaceStatus('lifecycle');
  await repository.startPolishJob(
    'lifecycle', status.cas, 'lifecycle-job', 1, 1, ALL_CONTEXT, NOW + 1,
  );
  let job = await repository.claimPolishJob(
    'lifecycle', 'lifecycle-job', 'owner-a', NOW + 2, 100,
  );
  let claim = claimRef(job);
  job = await repository.checkpointPolishStage(
    'lifecycle', 'lifecycle-job', claim, 'writing', NOW + 3,
  );
  job = await repository.pausePolishJob('lifecycle', 'lifecycle-job', claim, NOW + 4);
  assert.equal(job.stage, 'paused');
  assert.equal(job.resumeStage, 'writing');
  assert.equal(job.claim, null);
  job = await repository.resumePolishJob('lifecycle', 'lifecycle-job', NOW + 5);
  assert.equal(job.stage, 'writing');

  job = await repository.claimPolishJob(
    'lifecycle', 'lifecycle-job', 'owner-b', NOW + 6, 100,
  );
  claim = claimRef(job);
  job = await repository.yieldPolishJob('lifecycle', 'lifecycle-job', claim, NOW + 7);
  assert.equal(job.stage, 'waiting_system');
  assert.equal(job.resumeStage, 'writing');
  job = await repository.resumePolishJob('lifecycle', 'lifecycle-job', NOW + 8);
  assert.equal(job.stage, 'writing');

  job = await repository.claimPolishJob(
    'lifecycle', 'lifecycle-job', 'owner-c', NOW + 9, 100,
  );
  claim = claimRef(job);
  job = await repository.failPolishJob(
    'lifecycle', 'lifecycle-job', claim, ' provider timeout ', NOW + 10,
  );
  assert.equal(job.stage, 'failed');
  assert.equal(job.resumeStage, 'writing');
  assert.equal(job.failure, 'provider timeout');
  job = await repository.retryPolishJob('lifecycle', 'lifecycle-job', NOW + 11);
  assert.equal(job.stage, 'queued');
  assert.equal(job.failure, null);
  job = await repository.cancelPolishJob('lifecycle', 'lifecycle-job', NOW + 12);
  assert.equal(job.stage, 'cancelled');
  assert.equal(job.claim, null);
  assert.equal((await repository.loadPolishJob('lifecycle', 'lifecycle-job')).stage, 'cancelled');
});

test('v1 ghostwrite jobs store migrates through public repository operations without losing the job', async () => {
  const store = createMemoryFileStore();
  const repository = createFileNovelRepository(store);
  await repository.createProject(projectFixture('v1-migration'));
  const status = await repository.workspaceStatus('v1-migration');
  await repository.startGhostwriteJob(
    'v1-migration', status.cas, 'legacy-ghost', 'plan', 1, NOW + 1,
  );
  const currentRaw = await store.readText(JOBS_PATH('v1-migration'));
  const current = JSON.parse(currentRaw ?? '{"version":2,"jobs":[]}') as {
    version: number;
    jobs: Array<Record<string, unknown>>;
  };
  current.version = 1;
  delete current.jobs[0].kind;
  await store.writeText(JOBS_PATH('v1-migration'), JSON.stringify(current));

  const cold = createFileNovelRepository(store);
  const migrated = await cold.loadGhostwriteJob('v1-migration', 'legacy-ghost');
  assert.equal(migrated.kind, 'ghostwrite');
  assert.equal(migrated.stage, 'planning');
  assert.deepEqual((await cold.listGhostwriteJobs('v1-migration')).map(item => item.jobId), [
    'legacy-ghost',
  ]);
  assert.deepEqual(await cold.listPolishJobs('v1-migration'), []);

  const claimed: DurableGhostwriteJob = await cold.claimGhostwriteJob(
    'v1-migration', 'legacy-ghost', 'migration-owner', NOW + 2, 100,
  );
  assert.equal(claimed.kind, 'ghostwrite');
  const rewrittenRaw = await store.readText(JOBS_PATH('v1-migration'));
  const rewritten = JSON.parse(rewrittenRaw ?? '{}') as {
    version?: number;
    jobs?: Array<{ kind?: string; jobId?: string }>;
  };
  assert.equal(rewritten.version, 2);
  assert.equal(rewritten.jobs?.[0].kind, 'ghostwrite');
  assert.equal(rewritten.jobs?.[0].jobId, 'legacy-ghost');
});


test('arbitrary selection commits only selected chapters and survives a cold recovery between commits', async () => {
  const store = createMemoryFileStore();
  let repository = createFileNovelRepository(store);
  await repository.createProject(projectFixture('subset'));
  const status = await repository.workspaceStatus('subset');
  let job = await repository.startPolishJob(
    'subset', status.cas, 'polish-subset', 1, 3, ALL_CONTEXT, NOW + 1, [3, 1],
  );
  assert.deepEqual(job.targets.map(target => target.ordinal), [1, 3]);
  job = await repository.claimPolishJob('subset', job.jobId, 'owner', NOW + 2, 1_000);
  const claim = claimRef(job);
  const first = await prepareCommitting(repository, 'subset', job, claim, 'first', NOW + 3);
  job = await repository.commitPolishChapter('subset', job.jobId, claim, first.receipt, NOW + 6);
  repository = createFileNovelRepository(store);
  job = await repository.loadPolishJob('subset', job.jobId);
  assert.equal(job.targets[job.cursor].ordinal, 3);
  const second = await prepareCommitting(repository, 'subset', job, claim, 'third', NOW + 7);
  job = await repository.commitPolishChapter('subset', job.jobId, claim, second.receipt, NOW + 10);
  const project = await repository.loadProject('subset');
  assert.equal(job.stage, 'completed');
  assert.deepEqual(project.chapters.map(chapter => chapter.content), [
    first.candidate.content, '第二章旧正文。', second.candidate.content,
  ]);
  assert.deepEqual(project.chapterVersions.map(version => version.chapterId), ['chapter-1', 'chapter-3']);
  assert.deepEqual(job.progress.map(progress => progress.chapterOrdinal), [1, 3]);
});

test('selected ordinals reject empty, duplicate, invalid or missing chapters before persistence', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  await repository.createProject(projectFixture('invalid-subset'));
  const status = await repository.workspaceStatus('invalid-subset');
  for (const selected of [[], [1, 1], [0], [1.5], [4]]) {
    await assert.rejects(repository.startPolishJob(
      'invalid-subset', status.cas, 'bad-subset', 1, 3, ALL_CONTEXT, NOW + 1, selected,
    ));
  }
  assert.deepEqual(await repository.listPolishJobs('invalid-subset'), []);
});
