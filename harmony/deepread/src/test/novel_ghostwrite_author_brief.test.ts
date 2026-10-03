import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createNovelCreation } from '../main/ets/novel/creation.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { makeAssistantMessage } from '../main/ets/agent/message.ts';
import { makeGhostwriteCandidate } from '../main/ets/novel/ghostwrite.ts';
import { exportNovelNativeBackup, importNovelNativeBackup } from '../main/ets/novel/native_backup.ts';
import type { NovelWorkspaceArchiveCodec, NovelWorkspaceArchiveFile } from '../main/ets/novel/workspace_exchange.ts';
import type { NovelModelEvent, NovelModelRequest, NovelModelRunning } from '../main/ets/novel/model_running.ts';
const waitFor = async (predicate: () => Promise<boolean>): Promise<void> => {
  for (let i = 0; i < 100; i++) {
    if (await predicate()) return;
    await new Promise<void>(resolve => setTimeout(resolve, 5));
  }
  throw new Error('author brief test timed out');
};
const setup = async () => {
  const files = createMemoryFileStore();
  const repository = createFileNovelRepository(files);
  const requests: NovelModelRequest[] = [];
  let writerCalls = 0;
  let reviewerCalls = 0;
  let releaseRevision: (() => void) | null = null;
  const model: NovelModelRunning = {
    validate: async () => {},
    start(request) {
      requests.push(request);
      return { subscribe(callback: (event: NovelModelEvent) => void) {
        const emit = (text: string) => {
          callback({ kind: 'snapshot', messages: [makeAssistantMessage(text)], generationActive: false,
            textDeltasLive: false, transport: 'unavailable' });
          callback({ kind: 'completed' });
        };
        const timer = setTimeout(() => {
          if (request.systemPrompt.includes('正文写作者')) {
            writerCalls += 1;
            const body = JSON.stringify({ title: '第一章', content: writerCalls === 1 ? '旧稿：骑马逃离。' : '新稿：步行逃离。' });
            if (writerCalls === 2) releaseRevision = () => emit(body);
            else emit(body);
          } else {
            reviewerCalls += 1;
            const input = JSON.parse(request.operation.kind === 'turn' ? request.operation.userPrompt : '{}');
            emit(JSON.stringify({
              candidateId: input.candidateId, candidateDigest: input.candidateDigest,
              planId: input.planId, planDigest: input.planDigest,
              findings: reviewerCalls === 1 ? [{ kind: 'hard_continuity', code: 'horse', message: '马已丢失', location: '开头' }] : [],
              blocking: reviewerCalls === 1, rewriteRequired: false, rewriteInstructions: '',
              nextPlan: input.isFinalChapter ? null : '第二章：继续赶路。',
              stateDelta: { plotState: '逃离', chapterHighlight: '踏上旅途' },
            }));
          }
        }, 0);
        return () => clearTimeout(timer);
      } };
    },
    cancel() {},
  };
  let now = 1_700_010_000_000;
  const creation = createNovelCreation({ repository, modelRunning: model, nowMs: () => ++now });
  const project = await creation.create('作者修订代写');
  await creation.setBranchSettings(project.id, { ...project.branchSettings, thisChapterPlan: '主角离开故乡。' });
  await creation.setUpcomingArc(project.id, ['返航']);
  const job = await creation.startGhostwrite(project.id, 2);
  await waitFor(async () => (await repository.loadGhostwriteJob(project.id, job.jobId)).stage === 'failed');
  return { files, repository, requests, creation, projectId: project.id, jobId: job.jobId,
    release: () => { if (releaseRevision === null) throw new Error('writer not held'); releaseRevision(); } };
};
test('作者 brief 从失败候选到实际 writer：冻结、保留来源、新稿检查点清除，下一章不泄漏', async () => {
  const env = await setup();
  const failed = await env.repository.loadGhostwriteJob(env.projectId, env.jobId);
  const cas = (await env.creation.readWorkspaceSnapshot(env.projectId)).status.cas;
  const revised = await env.creation.reviseGhostwriteWithBrief(env.projectId, env.jobId, '改为步行；允许修正马匹事实。', cas);
  assert.equal(revised.stage, 'writing');
  await waitFor(async () => env.requests.filter(request => request.systemPrompt.includes('正文写作者')).length === 2);
  const durable = await createFileNovelRepository(env.files).loadGhostwriteJob(env.projectId, env.jobId);
  assert.equal(durable.authorBrief, '改为步行；允许修正马匹事实。');
  assert.deepEqual(durable.candidate, failed.candidate, '新稿成功前原候选保持完整');
  assert.deepEqual(durable.frozenPlan, failed.frozenPlan);
  const writers = env.requests.filter(request => request.systemPrompt.includes('正文写作者'));
  const prompt = writers[1].operation.kind === 'turn' ? writers[1].operation.userPrompt : '';
  assert.match(prompt, /改为步行；允许修正马匹事实/);
  assert.match(prompt, /旧稿：骑马逃离/);
  env.release();
  await waitFor(async () => (await env.repository.loadGhostwriteJob(env.projectId, env.jobId)).stage === 'completed');
  const completed = await env.repository.loadGhostwriteJob(env.projectId, env.jobId);
  assert.equal(completed.authorBrief, undefined);
  assert.equal(completed.progress.length, 2);
  const lastWriter = env.requests.filter(request => request.systemPrompt.includes('正文写作者'))[2];
  assert.equal(lastWriter.operation.kind === 'turn' && lastWriter.operation.userPrompt.includes('改为步行'), false);
  assert.equal((await env.repository.loadProject(env.projectId)).chapters[0].content, '新稿：步行逃离。');
});
test('作者 brief 拒绝过期预览 CAS 与空要求，不写入任务也不调用模型', async () => {
  const env = await setup();
  const before = await env.repository.loadGhostwriteJob(env.projectId, env.jobId);
  const cas = (await env.creation.readWorkspaceSnapshot(env.projectId)).status.cas;
  await assert.rejects(env.creation.reviseGhostwriteWithBrief(env.projectId, env.jobId, '修正事实', { ...cas, head: 'stale' }), /工作区已变化/);
  await assert.rejects(env.creation.reviseGhostwriteWithBrief(env.projectId, env.jobId, '   ', cas), /要求不能为空/);
  assert.deepEqual(await env.repository.loadGhostwriteJob(env.projectId, env.jobId), before);
  assert.equal(env.requests.length, 2);
});

test('暂停写稿可提交作者 brief，旧 provider 结束后才恢复，活动任务拒绝修改', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const requests: NovelModelRequest[] = [];
  const subscribers = new Map<string, (event: NovelModelEvent) => void>();
  const cancelled: string[] = [];
  const model: NovelModelRunning = {
    validate: async () => {},
    start(request) {
      requests.push(request);
      return { subscribe(callback) {
        subscribers.set(request.runId, callback);
        return () => { subscribers.delete(request.runId); };
      } };
    },
    cancel(runId) {
      cancelled.push(runId);
      subscribers.get(runId)?.({ kind: 'failed', message: 'cancelled' });
    },
  };
  let now = 1_700_020_000_000;
  const creation = createNovelCreation({ repository, modelRunning: model, nowMs: () => ++now });
  const project = await creation.create('暂停修订');
  await creation.setBranchSettings(project.id, { ...project.branchSettings, thisChapterPlan: '起程。' });
  const job = await creation.startGhostwrite(project.id, 1);
  await waitFor(async () => requests.length === 1);
  const cas = (await repository.workspaceStatus(project.id)).cas;
  await assert.rejects(repository.reviseGhostwriteWithBrief(project.id, job.jobId, '步行起程', cas, ++now), /仅暂停或失败/);
  await creation.pauseGhostwrite(project.id, job.jobId);
  const revised = await creation.reviseGhostwriteWithBrief(project.id, job.jobId, '步行起程', cas);
  assert.equal(revised.stage, 'writing');
  await waitFor(async () => requests.length === 2);
  assert.deepEqual(cancelled, [requests[0].runId]);
  assert.equal(requests[1].operation.kind === 'turn' && requests[1].operation.userPrompt.includes('步行起程'), true);
  await creation.cancelGhostwrite(project.id, job.jobId);
});

test('同一 workspace CAS 下 retry 更新候选后，旧 sheet 的来源 digest 拒绝修订且候选不受影响', async () => {
  const env = await setup();
  const old = await env.repository.loadGhostwriteJob(env.projectId, env.jobId);
  const cas = (await env.repository.workspaceStatus(env.projectId)).cas;
  let now = old.updatedAt + 100;
  await env.repository.reviseGhostwriteWithBrief(env.projectId, env.jobId, '另一次作者要求', cas, ++now, old.candidate?.digest);
  await env.repository.retryGhostwriteJob(env.projectId, env.jobId, ++now);
  const job = await env.repository.claimGhostwriteJob(env.projectId, env.jobId, 'manual-retry-2', ++now, 60_000);
  if (job.claim === null || job.frozenPlan === null) throw new Error('missing plan');
  const nextClaim = { token: job.claim.token, epoch: job.claim.epoch };
  const candidate = makeGhostwriteCandidate({ candidateId: 'new-source', chapterOrdinal: job.currentChapterOrdinal,
    title: '另一稿', content: '来源已经更新。', planId: job.frozenPlan.planId, planDigest: job.frozenPlan.digest,
    attempt: job.rewriteCount });
  await env.repository.checkpointGhostwriteCandidate(env.projectId, env.jobId, nextClaim, candidate, ++now);
  await env.repository.failGhostwriteJob(env.projectId, env.jobId, nextClaim, '审核仍失败', ++now);
  const before = await env.repository.loadGhostwriteJob(env.projectId, env.jobId);
  assert.deepEqual((await env.repository.workspaceStatus(env.projectId)).cas, cas);
  await assert.rejects(env.creation.reviseGhostwriteWithBrief(env.projectId, env.jobId, '旧预览指令', cas, old.candidate?.digest), /候选已变化/);
  assert.deepEqual(await env.repository.loadGhostwriteJob(env.projectId, env.jobId), before);
});

test('未消费 author brief 经原生备份 export → import → install → 重开保留冻结计划与来源候选', async () => {
  const env = await setup();
  const source = await env.repository.loadGhostwriteJob(env.projectId, env.jobId);
  const cas = (await env.repository.workspaceStatus(env.projectId)).cas;
  const pending = await env.repository.reviseGhostwriteWithBrief(env.projectId, env.jobId, '重写逃离方式。', cas,
    source.updatedAt + 100, source.candidate?.digest ?? '');
  let archiveFiles: NovelWorkspaceArchiveFile[] = [];
  const codec: NovelWorkspaceArchiveCodec = {
    async create(files) { archiveFiles = files; return new Uint8Array([80, 75]); },
    async list() { return archiveFiles.map(file => ({ path: file.path, compressedSize: file.bytes.length,
      uncompressedSize: file.bytes.length, isDirectory: false })); },
    async extract(_archive, path) {
      const file = archiveFiles.find(item => item.path === path);
      if (file === undefined) throw new Error('archive file missing');
      return file.bytes;
    },
  };
  const snapshot = await env.repository.nativeBackupSnapshot(env.projectId);
  const archive = await exportNovelNativeBackup(codec, snapshot.metadata, snapshot.files);
  const input = await importNovelNativeBackup(codec, archive);
  const restoredFiles = createMemoryFileStore();
  const target = createFileNovelRepository(restoredFiles);
  await target.installNativeBackup(input, await target.inspectNativeRestore(input));
  const reopened = createFileNovelRepository(restoredFiles);
  const job = await reopened.loadGhostwriteJob(env.projectId, env.jobId);
  assert.deepEqual(job, pending);
  assert.equal(job.authorBrief, '重写逃离方式。');
  assert.equal(job.resumeStage, 'writing');
  assert.deepEqual(job.candidate, source.candidate);
  assert.deepEqual(job.frozenPlan, source.frozenPlan);
  assert.deepEqual(job.frozenPlan?.upcomingArc, ['返航']);
  assert.deepEqual((await reopened.workspaceStatus(env.projectId)).cas, cas);
});
