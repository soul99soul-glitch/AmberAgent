import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { createNovelCreation } from '../main/ets/novel/creation.ts';
import { makeNovelProject, makeNovelChapter, novelChapterOrdinal } from '../main/ets/novel/models.ts';
import { makeGhostwriteCandidate, ghostwriteReceipt } from '../main/ets/novel/ghostwrite.ts';
import type { GhostwriteClaimRef } from '../main/ets/novel/ghostwrite.ts';
import { makePolishCandidate } from '../main/ets/novel/polish.ts';
import type { NovelModelEvent, NovelModelRequest, NovelModelRunning } from '../main/ets/novel/model_running.ts';
import { makeAssistantMessage } from '../main/ets/agent/message.ts';

const NOW = 2_000_000;
const sleep = (ms: number): Promise<void> => new Promise(resolve => { setTimeout(resolve, ms); });
const waitFor = async (predicate: () => boolean): Promise<void> => {
  for (let i = 0; i < 100; i++) {
    if (predicate()) return;
    await sleep(2);
  }
  throw new Error('model request did not start');
};
const noModel: NovelModelRunning = {
  validate: async (): Promise<void> => {},
  start: (): never => { throw new Error('unexpected model start'); },
  cancel: (): void => {},
};
const contextOptions = {
  includePlot: false, includeForeshadows: false, includeCharacters: false, includeDecisions: false,
};
const setupGhostwrite = async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const project = makeNovelProject({ name: '审查', now: NOW });
  project.branchSettings.thisChapterPlan = '作者计划';
  project.chapters = [makeNovelChapter({ title: '旧章', content: '旧章正文', ordinal: 7, now: NOW })];
  await repository.createProject(project);
  const status = await repository.workspaceStatus(project.id);
  const job = await repository.startGhostwriteJob(project.id, status.cas, 'job', 'plan', 1, NOW + 1);
  const claimed = await repository.claimGhostwriteJob(project.id, job.jobId, 'owner', NOW + 2, 1_000_000);
  const claim: GhostwriteClaimRef = { token: 'owner', epoch: claimed.claim!.epoch };
  await repository.checkpointGhostwriteStage(project.id, 'job', claim, 'writing', NOW + 3);
  const candidate = makeGhostwriteCandidate({
    candidateId: 'candidate', chapterOrdinal: job.currentChapterOrdinal, title: '新章', content: '新章正文',
    planId: 'plan', planDigest: job.frozenPlan!.digest, attempt: 0,
  });
  await repository.checkpointGhostwriteCandidate(project.id, 'job', claim, candidate, NOW + 4);
  const review = {
    candidateId: candidate.candidateId, candidateDigest: candidate.digest,
    planId: 'plan', planDigest: candidate.planDigest, findings: [], blocking: false,
    rewriteRequired: true, rewriteInstructions: '调整文风', nextPlan: null,
    stateDelta: { plotState: '剧情', chapterHighlight: '要点' },
  };
  return { repository, project, job, candidate, review, claim };
};

for (const action of ['pause', 'fail', 'cancel'] as const) {
  test(`ghostwrite 重写阶段可 ${action} 且保留旧候选，冷读状态合法`, async () => {
    const { repository, project, review, claim } = await setupGhostwrite();
    await repository.checkpointGhostwriteReview(project.id, 'job', claim, review, NOW + 5);
    if (action === 'pause') await repository.pauseGhostwriteJob(project.id, 'job', claim, NOW + 6);
    else if (action === 'fail') await repository.failGhostwriteJob(project.id, 'job', claim, 'provider error', NOW + 6);
    else await repository.cancelGhostwriteJob(project.id, 'job', claim, NOW + 6);
    const stored = await repository.loadGhostwriteJob(project.id, 'job');
    assert.equal(stored.stage, action === 'pause' ? 'paused' : action === 'fail' ? 'failed' : 'cancelled');
    assert.equal(stored.candidate?.attempt, 0);
    assert.equal(stored.rewriteCount, 1);
    if (action === 'pause') {
      assert.equal((await repository.resumeGhostwriteJob(project.id, 'job', NOW + 7)).stage, 'rewriting_1');
    } else if (action === 'fail') {
      assert.equal((await repository.retryGhostwriteJob(project.id, 'job', NOW + 7)).stage, 'rewriting_1');
    }
  });
}

for (const kind of ['ghostwrite', 'polish'] as const) {
  test(`${kind} 暂停后立即继续等待旧 owner 收口，迟到回调不覆盖新 owner`, async () => {
    const repository = createFileNovelRepository(createMemoryFileStore());
    const requests: NovelModelRequest[] = [];
    const subscribers = new Map<string, (event: NovelModelEvent) => void>();
    const model: NovelModelRunning = {
      validate: async (): Promise<void> => {},
      start(request) {
        requests.push(request);
        return { subscribe(callback) {
          subscribers.set(request.runId, callback);
          return (): void => { subscribers.delete(request.runId); };
        } };
      },
      cancel(runId): void {
        const callback = subscribers.get(runId);
        setTimeout(() => { callback?.({ kind: 'failed', message: 'asynchronous abort' }); }, 15);
      },
    };
    const creation = createNovelCreation({ repository, modelRunning: model, nowMs: (): number => NOW + 20 });
    const project = await creation.create('继续');
    await creation.setBranchSettings(project.id, { ...project.branchSettings, thisChapterPlan: '计划' });
    const chapter = await creation.saveChapter(project.id, null, '第一章', '正文');
    const job = kind === 'ghostwrite'
      ? await creation.startGhostwrite(project.id, 1)
      : await creation.startChapterPolish(project.id, chapter.id, contextOptions);
    await waitFor(() => requests.length === 1);
    const oldCallback = subscribers.get(requests[0].runId)!;
    if (kind === 'ghostwrite') {
      await creation.pauseGhostwrite(project.id, job.jobId);
      await creation.resumeGhostwrite(project.id, job.jobId);
    } else {
      await creation.pausePolish(project.id, job.jobId);
      await creation.resumePolish(project.id, job.jobId);
    }
    await waitFor(() => requests.length === 2);
    const running = kind === 'ghostwrite'
      ? await repository.loadGhostwriteJob(project.id, job.jobId)
      : await repository.loadPolishJob(project.id, job.jobId);
    assert.equal(running.stage, 'writing');
    assert.equal(running.claim?.epoch, 2);
    oldCallback({ kind: 'snapshot', messages: [makeAssistantMessage('{"title":"旧输出","content":"旧正文"}')],
      generationActive: false, textDeltasLive: false, transport: 'unavailable' });
    oldCallback({ kind: 'completed' });
    await sleep(5);
    const after = kind === 'ghostwrite'
      ? await repository.loadGhostwriteJob(project.id, job.jobId)
      : await repository.loadPolishJob(project.id, job.jobId);
    assert.deepEqual(after.claim, running.claim);
    assert.equal(after.stage, 'writing');
    assert.equal((await repository.loadProject(project.id)).chapters[0].content, '正文');
    if (kind === 'ghostwrite') await creation.cancelGhostwrite(project.id, job.jobId);
    else await creation.cancelPolish(project.id, job.jobId);
    await sleep(20);
  });
}

test('Reader 编辑旧章后真实写入仍拒绝旧 CAS，但可取消代笔并解除分支锁', async () => {
  const { repository, project, claim } = await setupGhostwrite();
  const creation = createNovelCreation({ repository, modelRunning: noModel, nowMs: (): number => NOW + 20 });
  await creation.saveChapter(project.id, project.chapters[0].id, '旧章', 'Reader 改稿');
  await assert.rejects(repository.checkpointGhostwriteStage(project.id, 'job', claim, 'committing', NOW + 21), /工作区已变化/);
  await assert.rejects(repository.cancelGhostwriteJob(project.id, 'job', { token: 'wrong', epoch: claim.epoch }, NOW + 21), /lease 已失效/);
  await assert.rejects(repository.cancelGhostwriteJob(project.id, 'job', null, NOW + 21), /仍有效/);
  assert.equal((await creation.cancelGhostwrite(project.id, 'job')).stage, 'cancelled');
  assert.equal((await repository.loadProject(project.id)).chapters[0].content, 'Reader 改稿');
  await creation.createBranch(project.id, '可继续分支');
});

test('失效 CAS 的代笔能保存失败原因，失败且无 owner 的作业可取消', async () => {
  const { repository, project, claim } = await setupGhostwrite();
  const creation = createNovelCreation({ repository, modelRunning: noModel, nowMs: (): number => NOW + 20 });
  await creation.saveChapter(project.id, project.chapters[0].id, '旧章', '作者改稿导致正文来源变化');
  const failed = await repository.failGhostwriteJob(project.id, 'job', claim, '工作区已变化，拒绝覆盖', NOW + 21);
  assert.equal(failed.stage, 'failed');
  assert.equal(failed.claim, null);
  await assert.rejects(repository.retryGhostwriteJob(project.id, 'job', NOW + 22), /工作区已变化/);
  assert.equal((await creation.cancelGhostwrite(project.id, 'job')).stage, 'cancelled');
});

test('项目三职责模型切分支保持设置且 head 书稿校验继续严格', async () => {
  const store = createMemoryFileStore();
  const repository = createFileNovelRepository(store);
  const creation = createNovelCreation({ repository, modelRunning: noModel });
  const project = await creation.create('项目模型');
  const chapter = await creation.saveChapter(project.id, null, '第一章', 'main正文');
  await creation.createBranch(project.id, 'fork');
  const fork = await repository.workspaceStatus(project.id);
  const policy = { writing: { kind: 'fixed' as const, providerId: 'p', modelId: 'm' }, review: null, stateSync: null };
  await creation.setModelPolicy(project.id, policy);
  await creation.switchBranch(project.id, 'main');
  assert.deepEqual((await creation.open(project.id)).modelPolicy, policy);
  await creation.saveChapter(project.id, chapter.id, '第一章', 'main改稿');
  await creation.undoLast(project.id);
  assert.equal((await creation.open(project.id)).chapters[0].content, 'main正文');
  assert.deepEqual((await creation.open(project.id)).modelPolicy, policy);
  await creation.switchBranch(project.id, fork.activeBranchId);
  assert.deepEqual((await creation.open(project.id)).modelPolicy, policy);
  await store.writeText(`amberagent/novel-workspace/${project.id}/branches/${fork.activeBranchId}/chapters/001-第一章.md`, '磁盘漂移');
  await assert.rejects(creation.open(project.id), /正文树与台账不一致/);
});

test('润色重写候选取消保留，旧 CAS 仅阻止写入而不阻止取消', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const project = makeNovelProject({ name: '润色取消', now: NOW });
  project.chapters = [makeNovelChapter({ title: '第一章', content: '原文', ordinal: 1, now: NOW })];
  await repository.createProject(project);
  const status = await repository.workspaceStatus(project.id);
  const job = await repository.startPolishJob(project.id, status.cas, 'polish', 1, 1, contextOptions, NOW + 1);
  const claimed = await repository.claimPolishJob(project.id, job.jobId, 'owner', NOW + 2, 1_000_000);
  const claim = { token: 'owner', epoch: claimed.claim!.epoch };
  await repository.checkpointPolishStage(project.id, job.jobId, claim, 'writing', NOW + 3);
  const target = job.targets[0];
  const candidate = makePolishCandidate({ jobId: job.jobId, candidateId: 'candidate', chapterId: target.id,
    chapterOrdinal: 1, sourceDigest: target.sourceDigest, content: '润色候选', attempt: 0 });
  await repository.checkpointPolishCandidate(project.id, job.jobId, claim, candidate, NOW + 4);
  await repository.checkpointPolishReview(project.id, job.jobId, claim, {
    jobId: candidate.jobId, chapterId: candidate.chapterId, chapterOrdinal: candidate.chapterOrdinal,
    candidateId: candidate.candidateId, candidateDigest: candidate.digest, sourceDigest: target.sourceDigest,
    findings: [], blocking: false, rewriteRequired: true, rewriteInstructions: '优化文风',
  }, NOW + 5);
  const creation = createNovelCreation({ repository, modelRunning: noModel, nowMs: (): number => NOW + 20 });
  await creation.saveChapter(project.id, target.id, '第一章', '作者新稿');
  await assert.rejects(repository.checkpointPolishStage(project.id, job.jobId, claim, 'reviewing', NOW + 21), /工作区已变化/);
  const cancelled = await creation.cancelPolish(project.id, job.jobId);
  assert.equal(cancelled.stage, 'cancelled');
  assert.equal((await repository.loadPolishJob(project.id, job.jobId)).candidate?.content, '润色候选');
  assert.equal((await repository.loadProject(project.id)).chapters[0].content, '作者新稿');
});


test('连续两次改稿后单步撤销恢复最近稿', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const creation = createNovelCreation({ repository, modelRunning: noModel });
  const project = await creation.create('最近撤销');
  const chapter = await creation.saveChapter(project.id, null, '第一章', '原稿');
  await creation.saveChapter(project.id, chapter.id, '第一章', '第二稿');
  await creation.saveChapter(project.id, chapter.id, '第一章', '第三稿');
  await creation.undoLast(project.id);
  assert.equal((await creation.open(project.id)).chapters[0].content, '第二稿');
  assert.equal((await creation.workspaceStatus(project.id)).canUndo, true);
  await creation.undoLast(project.id);
  assert.equal((await creation.open(project.id)).chapters[0].content, '原稿');
});

test('代笔保留废弃章序号，相同标题的旧章恢复后项目仍可读', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const project = makeNovelProject({ name: '废弃章恢复', now: NOW });
  project.branchSettings.thisChapterPlan = '写下一章';
  const discarded = makeNovelChapter({ title: '第一章', content: '旧章正文', ordinal: 1, now: NOW });
  discarded.discarded = true;
  project.chapters = [discarded];
  await repository.createProject(project);
  const status = await repository.workspaceStatus(project.id);
  const job = await repository.startGhostwriteJob(project.id, status.cas, 'discard-job', 'plan', 1, NOW + 1);
  const claimed = await repository.claimGhostwriteJob(project.id, job.jobId, 'owner', NOW + 2, 1_000_000);
  const claim = { token: 'owner', epoch: claimed.claim!.epoch };
  await repository.checkpointGhostwriteStage(project.id, job.jobId, claim, 'writing', NOW + 3);
  const candidate = makeGhostwriteCandidate({ candidateId: 'new-candidate',
    chapterOrdinal: job.currentChapterOrdinal, title: '第一章', content: '新章正文',
    planId: 'plan', planDigest: job.frozenPlan!.digest, attempt: 0 });
  await repository.checkpointGhostwriteCandidate(project.id, job.jobId, claim, candidate, NOW + 4);
  await repository.checkpointGhostwriteReview(project.id, job.jobId, claim, {
    candidateId: candidate.candidateId, candidateDigest: candidate.digest, planId: candidate.planId,
    planDigest: candidate.planDigest, findings: [], blocking: false, rewriteRequired: false,
    rewriteInstructions: '', nextPlan: null, stateDelta: { plotState: '新剧情', chapterHighlight: '新章要点' },
  }, NOW + 5);
  await repository.commitGhostwriteChapter(project.id, job.jobId, claim,
    ghostwriteReceipt(job.jobId, job.currentChapterOrdinal, candidate.planId, candidate.planDigest, candidate.candidateId),
    NOW + 6);
  const creation = createNovelCreation({ repository, modelRunning: noModel });
  await creation.setChapterDiscarded(project.id, discarded.id, false);
  const restored = await creation.open(project.id);
  assert.deepEqual(restored.chapters.map(chapter => chapter.content), ['旧章正文', '新章正文']);
  assert.deepEqual(restored.chapters.map((chapter, index) => novelChapterOrdinal(chapter, index + 1)), [1, 2]);
});
