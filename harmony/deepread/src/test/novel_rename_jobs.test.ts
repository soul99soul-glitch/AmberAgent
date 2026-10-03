import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createNovelCreation } from '../main/ets/novel/creation.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import type { NovelModelEvent, NovelModelRunning } from '../main/ets/novel/model_running.ts';

const heldModel = (): NovelModelRunning => {
  const listeners = new Map<string, (event: NovelModelEvent) => void>();
  return {
    validate: async () => {},
    start(request) {
      return { subscribe(callback) {
        listeners.set(request.runId, callback);
        return () => { listeners.delete(request.runId); };
      } };
    },
    cancel(runId) { listeners.get(runId)?.({ kind: 'failed', message: 'cancelled' }); },
  };
};

test('project rename preserves recoverable ghostwrite CAS through running, pause and failure', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const creation = createNovelCreation({ repository, modelRunning: heldModel() });
  const project = await creation.create('代写期间原项目名');
  await creation.setBranchSettings(project.id, { ...project.branchSettings, thisChapterPlan: '主角离乡。' });
  const job = await creation.startGhostwrite(project.id, 1);
  const expected = (await repository.workspaceStatus(project.id)).cas;
  await assert.rejects(creation.rename(project.id, '运行中重命名'), /完成或取消.*任务/);
  await creation.pauseGhostwrite(project.id, job.jobId);
  await assert.rejects(creation.rename(project.id, '暂停后重命名'), /完成或取消.*任务/);
  const paused = await repository.loadGhostwriteJob(project.id, job.jobId);
  await repository.resumeGhostwriteJob(project.id, job.jobId, paused.updatedAt + 1);
  const claimed = await repository.claimGhostwriteJob(project.id, job.jobId, 'failed-owner', paused.updatedAt + 2, 1000);
  assert.ok(claimed.claim);
  await repository.failGhostwriteJob(project.id, job.jobId,
    { token: claimed.claim.token, epoch: claimed.claim.epoch }, '网络失败', paused.updatedAt + 3);
  await assert.rejects(creation.rename(project.id, '失败后重命名'), /完成或取消.*任务/);
  assert.deepEqual((await repository.workspaceStatus(project.id)).cas, expected);
  assert.equal((await repository.loadProject(project.id)).name, project.name);
  assert.equal((await repository.retryGhostwriteJob(project.id, job.jobId, paused.updatedAt + 4)).stage, claimed.stage);
  await creation.cancelGhostwrite(project.id, job.jobId);
  assert.equal((await creation.rename(project.id, '取消后允许重命名')).name, '取消后允许重命名');
});

test('project rename preserves a paused polish source and permits rename after cancellation', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const creation = createNovelCreation({ repository, modelRunning: heldModel() });
  const project = await creation.create('润色期间原项目名');
  const chapter = await creation.saveChapter(project.id, null, '第一章', '原始正文。');
  const job = await creation.startChapterPolish(project.id, chapter.id,
    { includePlot: true, includeForeshadows: false, includeCharacters: false, includeDecisions: false });
  const expected = (await repository.workspaceStatus(project.id)).cas;
  await assert.rejects(creation.rename(project.id, '润色中重命名'), /完成或取消.*任务/);
  await creation.pausePolish(project.id, job.jobId);
  await assert.rejects(creation.rename(project.id, '暂停润色后重命名'), /完成或取消.*任务/);
  assert.deepEqual((await repository.workspaceStatus(project.id)).cas, expected);
  assert.equal((await repository.loadProject(project.id)).chapters[0].content, chapter.content);
  await creation.cancelPolish(project.id, job.jobId);
  assert.equal((await creation.rename(project.id, '润色取消后重命名')).name, '润色取消后重命名');
});
