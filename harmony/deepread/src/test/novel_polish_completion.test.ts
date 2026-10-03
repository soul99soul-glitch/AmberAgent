import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { makeNovelProject, makeNovelChapter, makeNovelMaterial } from '../main/ets/novel/models.ts';
import { createNovelCreation } from '../main/ets/novel/creation.ts';
import { createPolishRunner } from '../main/ets/novel/polish_runner.ts';
import { projectPolishOutcomes } from '../main/ets/novel/polish.ts';
import { makeAssistantMessage } from '../main/ets/agent/message.ts';
import type { NovelModelRunning, NovelModelRequest, NovelModelEvent } from '../main/ets/novel/model_running.ts';
import type { DurablePolishJob, PolishContextOptions } from '../main/ets/novel/polish.ts';
const options: PolishContextOptions = { includePlot: true, includeCharacters: true, includeForeshadows: true, includeDecisions: true };
const setup = async () => {
  const store = createMemoryFileStore();
  const repo = createFileNovelRepository(store);
  await repo.createProject({ ...makeNovelProject({ id: 'p5polish', name: '润色闭环', now: 1000 }), polishPreference: '保留短句与对白',
    chapters: [1, 2, 3, 4].map(n => makeNovelChapter({ id: `c${n}`, ordinal: n, title: `第${n}章`, content: `阿岚在雨夜调查，第${n}章原文`, now: 1000 })),
    materials: [
      { ...makeNovelMaterial({ id: 'smartAlias', kind: 'character', title: '林岚', content: '主角左手有伤', now: 1000 }), aliases: ['阿岚'], tags: [], injectionMode: 'smart' },
      { ...makeNovelMaterial({ id: 'smartTag', kind: 'world', title: '天气规则', content: '下雨时桥上无人', now: 1000 }), aliases: [], tags: ['雨夜'], injectionMode: 'smart' },
      { ...makeNovelMaterial({ id: 'off', kind: 'character', title: '关掉的人物', content: '不入上下文', now: 1000 }), injectionMode: 'off', enabled: false },
      { ...makeNovelMaterial({ id: 'irrelevant', kind: 'character', title: '无关人物', content: '无关事实', now: 1000 }), injectionMode: 'smart' },
    ],
  });
  const cas = async () => (await repo.workspaceStatus('p5polish')).cas;
  return { repo, store, cas };
};
interface Input { jobId: string; chapter: { id: string; ordinal: number; sourceDigest: string; sourceContent: string }; }
const input = (request: NovelModelRequest): Input => {
  if (request.operation.kind !== 'turn') throw Error('polish must issue turn');
  return JSON.parse(request.operation.userPrompt.split('\n')[0]) as Input;
};
const model = (repo: ReturnType<typeof createFileNovelRepository>, failIds: Set<string>, driftIds = new Set<string>(), rewriteIds = new Set<string>()) => {
  const requests: NovelModelRequest[] = [];
  const port: NovelModelRunning = { async validate() {}, cancel() {}, start(request) {
    requests.push(request);
    const callbacks = new Set<(event: NovelModelEvent) => void>();
    setTimeout(() => { void (async () => {
      const source = input(request);
      if (request.systemPrompt.includes('润色者') && failIds.has(source.chapter.id)) {
        callbacks.forEach(cb => cb({ kind: 'failed', message: `provider failed ${source.chapter.id}` })); return;
      }
      const job = await repo.loadPolishJob('p5polish', source.jobId);
      const candidate = job.candidate;
      const text = request.systemPrompt.includes('润色者') ? JSON.stringify({ jobId: job.jobId, content: source.chapter.sourceContent + '，润色后的表达' })
        : JSON.stringify({ jobId: job.jobId, chapterId: source.chapter.id, chapterOrdinal: source.chapter.ordinal,
          sourceDigest: source.chapter.sourceDigest, candidateId: candidate!.candidateId, candidateDigest: candidate!.digest,
          blocking: driftIds.has(source.chapter.id), rewriteRequired: rewriteIds.has(source.chapter.id), rewriteInstructions: rewriteIds.has(source.chapter.id) ? '收紧语句' : '', findings: driftIds.has(source.chapter.id)
            ? [{ kind: 'hard_continuity', code: 'drift', message: '改变了已知事实', location: '全文' }] : [] });
      const messages = [makeAssistantMessage(text)];
      callbacks.forEach(cb => cb({ kind: 'snapshot', messages, generationActive: false, textDeltasLive: false, transport: 'live' }));
      callbacks.forEach(cb => cb({ kind: 'completed' }));
    })().catch(error => callbacks.forEach(cb => cb({ kind: 'failed', message: String(error) }))); }, 0);
    return { subscribe(callback) { callbacks.add(callback); return () => { callbacks.delete(callback); }; } };
  } };
  return { port, requests };
};
const runner = (repo: ReturnType<typeof createFileNovelRepository>, port: NovelModelRunning) => createPolishRunner({ repository: repo, modelRunning: port, nowMs: () => 2000 });

test('actual noncontiguous batch continues failed and drift-skipped chapters and retries only explicitly failed IDs in same durable job', async () => {
  const { repo, store, cas } = await setup();
  const failures = new Set(['c1']);
  const mock = model(repo, failures, new Set(['c3']));
  const started = await repo.startPolishJob('p5polish', await cas(), 'mixed-results', 1, 4, options, 2000, [1, 3, 4]);
  assert.equal(started.polishPreferenceAtStart, '保留短句与对白');
  const completed = await runner(repo, mock.port).run('p5polish', started.jobId, 'owner');
  assert.equal(completed.stage, 'completed');
  assert.deepEqual(completed.outcomes!.map(item => item.status), ['failed', 'driftSkipped', 'success']);
  assert.deepEqual(completed.progress.map(item => item.chapterId), ['c4']);
  assert.deepEqual(mock.requests.filter(r => r.systemPrompt.includes('润色者')).map(r => input(r).chapter.id), ['c1', 'c3', 'c4']);
  let project = await repo.loadProject('p5polish');
  assert.ok(project.chapters[0].content.endsWith('原文'));
  assert.ok(project.chapters[1].content.endsWith('原文'));
  assert.ok(project.chapters[2].content.endsWith('原文'));
  assert.match(project.chapters[3].content, /润色后的表达/);
  assert.equal(project.chapterVersions.length, 1);
  const fresh = createFileNovelRepository(store);
  await assert.rejects(fresh.retryPolishJob('p5polish', started.jobId, 2001, ['c4']), /只能重试/);
  await fresh.retryPolishJob('p5polish', started.jobId, 2001, ['c1']);
  const retryMock = model(fresh, new Set());
  const retried = await createPolishRunner({ repository: fresh, modelRunning: retryMock.port, nowMs: () => 2002 }).run('p5polish', started.jobId, 'retry-owner');
  assert.equal(retried.jobId, started.jobId);
  assert.deepEqual(retried.outcomes!.map(item => item.status), ['success', 'driftSkipped', 'success']);
  assert.deepEqual(retryMock.requests.filter(r => r.systemPrompt.includes('润色者')).map(r => input(r).chapter.id), ['c1']);
  project = await fresh.loadProject('p5polish');
  assert.equal(project.chapterVersions.length, 2);
});

test('project preference is frozen for writer/reviewer and native restore, mutable app preference is never reread', async () => {
  const { repo, cas } = await setup();
  const started = await repo.startPolishJob('p5polish', await cas(), 'freeze', 1, 1, options, 2000);
  const claimed = await repo.claimPolishJob('p5polish', started.jobId, 'backup-owner', 2000, 10000);
  await repo.pausePolishJob('p5polish', started.jobId, { token: claimed.claim!.token, epoch: claimed.claim!.epoch }, 2000);
  const backup = await repo.nativeBackupSnapshot('p5polish');
  const target = createFileNovelRepository(createMemoryFileStore());
  const inputBackup = { projectId: 'p5polish', files: backup.files, manifest: { ...backup.metadata, format: 'amber.novel.native-backup' as const,
    version: 1 as const, checksumAlgorithm: 'fnv1a32' as const, entries: [] } };
  await target.installNativeBackup(inputBackup, await target.inspectNativeRestore(inputBackup));
  await target.resumePolishJob('p5polish', started.jobId, 2001);
  const mock = model(target, new Set());
  let reads = 0;
  await createPolishRunner({ repository: target, modelRunning: mock.port, nowMs: () => 2001,
    loadPolishPreference: async () => { reads++; return '任务后修改的全局偏好'; } }).run('p5polish', started.jobId, 'restored-owner');
  assert.equal(reads, 0);
  for (const request of mock.requests) { assert.match(request.operation.kind === 'turn' ? request.operation.userPrompt : '', /保留短句与对白/); }
  assert.equal((await target.loadPolishJob('p5polish', started.jobId)).polishPreferenceAtStart, '保留短句与对白');
});

test('same freeze helper drives preview and selected-ID start, respects aliases/tags and protects branch off overrides', async () => {
  const { repo, cas } = await setup();
  await repo.commitProject('p5polish', await cas(), 'override-off', 'material_edit', p => ({ ...p,
    materials: p.materials.map(item => item.id === 'off' ? { ...item, content: '本分支实际事实，不可忽略', enabled: false, injectionMode: 'off' } : item) }));
  await repo.syncPlot('p5polish', await cas(), 'ready');
  const mock = model(repo, new Set());
  const creation = createNovelCreation({ repository: repo, modelRunning: mock.port, nowMs: () => 2000 });
  const preview = await creation.previewPolishSelected('p5polish', ['c3', 'c1'], options);
  assert.deepEqual(preview.chapterIds, ['c1', 'c3']);
  assert.equal(preview.preferenceSource, 'project');
  const job = await creation.startPolishSelected('p5polish', preview.chapterIds, options, preview.cas);
  assert.deepEqual(job.contextSnapshot, preview.contextSnapshot);
  assert.deepEqual(job.contextSnapshot.filter(item => item.kind === 'character').map(item => item.sourcePath.split('/').pop()), ['off.md', 'smartAlias.md']);
  const contexts = job.contextSnapshot.map(item => item.content).join('\n');
  assert.match(contexts, /Aliases: 阿岚/); assert.match(contexts, /Tags: 雨夜/); assert.match(contexts, /本分支实际事实/);
  assert.doesNotMatch(contexts, /无关事实/);
  // Cancel through the actual durable gate while its background driver is waiting.
  await creation.cancelPolish('p5polish', job.jobId);
});

test('selected start refuses stale preview CAS after model validation await, and protected oversized facts never silently truncate', async () => {
  const { repo, cas } = await setup();
  const modelPort: NovelModelRunning = { async validate() {}, cancel() {}, start() { throw Error('must not generate'); } };
  const creation = createNovelCreation({ repository: repo, modelRunning: modelPort });
  const preview = await creation.previewPolishSelected('p5polish', ['c1'], options);
  await repo.commitProject('p5polish', await cas(), 'new-preference', 'project_setup_change', p => ({ ...p, polishPreference: '新偏好' }));
  await assert.rejects(creation.startPolishSelected('p5polish', ['c1'], options, preview.cas), /工作区已变化/);
  await repo.commitProject('p5polish', await cas(), 'huge-required', 'material_edit', p => ({ ...p,
    materials: p.materials.concat([{ ...makeNovelMaterial({ id: 'large', kind: 'world', title: '完整强制事实', content: '事实'.repeat(5000), now: 2000 }), injectionMode: 'always' }]) }));
  await repo.syncPlot('p5polish', await cas(), 'huge-ready');
  await assert.rejects(creation.previewPolishSelected('p5polish', ['c1'], options), /未截断分支覆盖事实/);
  assert.equal((await repo.listPolishJobs('p5polish')).length, 0);
});
test('failure consumes one background chapter slot and cancellation retains unprocessed targets for selective retry', async () => {
  const { repo, cas } = await setup();
  const started = await repo.startPolishJob('p5polish', await cas(), 'background-fail', 1, 4, options, 2000, [1, 3, 4]);
  const mock = model(repo, new Set(['c1']));
  const yielded = await runner(repo, mock.port).run('p5polish', started.jobId, 'one-slot', undefined, 1);
  assert.equal(yielded.stage, 'waiting_system');
  assert.deepEqual(projectPolishOutcomes(yielded).map(item => item.status), ['failed', 'unprocessed', 'unprocessed']);
  assert.equal(mock.requests.length, 1);
  const cancelled = await repo.cancelPolishJob('p5polish', started.jobId, 2001);
  assert.equal(cancelled.stage, 'cancelled');
  await repo.retryPolishJob('p5polish', started.jobId, 2002, ['c3']);
  const retryModel = model(repo, new Set());
  const result = await createPolishRunner({ repository: repo, modelRunning: retryModel.port, nowMs: () => 2003 }).run('p5polish', started.jobId, 'retry-one');
  assert.deepEqual(projectPolishOutcomes(result).map(item => item.status), ['failed', 'success', 'unprocessed']);
  assert.deepEqual(retryModel.requests.filter(r => r.systemPrompt.includes('润色者')).map(r => input(r).chapter.id), ['c3']);
  assert.equal(result.jobId, started.jobId);
});

test('CAS drift is a task failure and neither chapter result advance nor retry can overwrite edited source', async () => {
  const { repo, store, cas } = await setup();
  const started = await repo.startPolishJob('p5polish', await cas(), 'stale-job', 1, 4, options, 2000, [1, 4]);
  const jobsPath = 'amberagent/novel-workspace/p5polish/.amber/jobs.json';
  const jobs = await store.readText(jobsPath);
  // Reproduce an externally resumed stale durable job against a newer valid workspace.
  await store.writeText(jobsPath, JSON.stringify({ version: 2, jobs: [] }));
  await repo.commitProject('p5polish', await cas(), 'external-edit', 'manual_edit', p => ({ ...p,
    chapters: p.chapters.map(chapter => chapter.id === 'c1' ? { ...chapter, content: '用户更新的正文' } : chapter) }));
  await store.writeText(jobsPath, jobs!);
  const mock = model(repo, new Set());
  await assert.rejects(runner(repo, mock.port).run('p5polish', started.jobId, 'stale-owner'), /工作区已变化/);
  assert.equal(mock.requests.length, 0);
  await assert.rejects(repo.retryPolishJob('p5polish', started.jobId, 2001, ['c1']), /工作区已变化/);
  assert.equal((await repo.loadProject('p5polish')).chapters[0].content, '用户更新的正文');
  assert.equal((await repo.loadPolishJob('p5polish', started.jobId)).cursor, 0);
});
test('legacy native failed job upgrades only explicit selected retry and runs the author-selected chapter', async () => {
  const { repo, store, cas } = await setup();
  const started = await repo.startPolishJob('p5polish', await cas(), 'legacy-select', 1, 4, options, 2000);
  const owned = await repo.claimPolishJob('p5polish', started.jobId, 'legacy-owner', 2000, 10000);
  const claim = { token: owned.claim!.token, epoch: owned.claim!.epoch };
  await repo.checkpointPolishStage('p5polish', started.jobId, claim, 'writing', 2000);
  await repo.failPolishJob('p5polish', started.jobId, claim, '旧任务失败', 2000);
  const path = 'amberagent/novel-workspace/p5polish/.amber/jobs.json';
  const old = JSON.parse((await store.readText(path))!) as { version: number; jobs: DurablePolishJob[] };
  delete old.jobs[0].outcomes; delete old.jobs[0].polishPreferenceAtStart; delete old.jobs[0].retryChapterIds;
  await store.writeText(path, JSON.stringify(old));
  const backup = await repo.nativeBackupSnapshot('p5polish');
  const restored = createFileNovelRepository(createMemoryFileStore());
  const inputBackup = { projectId: 'p5polish', files: backup.files, manifest: { ...backup.metadata,
    format: 'amber.novel.native-backup' as const, version: 1 as const, checksumAlgorithm: 'fnv1a32' as const, entries: [] } };
  await restored.installNativeBackup(inputBackup, await restored.inspectNativeRestore(inputBackup));
  const selected = await restored.retryPolishJob('p5polish', started.jobId, 2001, ['c3']);
  assert.equal(selected.targets[selected.cursor].id, 'c3');
  const mock = model(restored, new Set());
  const result = await createPolishRunner({ repository: restored, modelRunning: mock.port, nowMs: () => 2002 }).run('p5polish', started.jobId, 'selected-owner');
  assert.deepEqual(mock.requests.filter(r => r.systemPrompt.includes('润色者')).map(r => input(r).chapter.id), ['c3']);
  assert.deepEqual(projectPolishOutcomes(result).map(item => item.status), ['failed', 'unprocessed', 'success', 'unprocessed']);
});

test('bounded rewrite exhaustion is a chapter failure and still allows the following selected chapter to succeed', async () => {
  const { repo, cas } = await setup();
  const started = await repo.startPolishJob('p5polish', await cas(), 'rewrite-limit', 1, 4, options, 2000, [1, 4]);
  const mock = model(repo, new Set(), new Set(), new Set(['c1']));
  const completed = await runner(repo, mock.port).run('p5polish', started.jobId, 'rewrite-owner');
  assert.equal(completed.stage, 'completed');
  assert.deepEqual(projectPolishOutcomes(completed).map(item => item.status), ['failed', 'success']);
  assert.match(completed.outcomes![0].message ?? '', /两次上限/);
  assert.deepEqual(mock.requests.filter(r => r.systemPrompt.includes('润色者')).map(r => input(r).chapter.id), ['c1', 'c1', 'c1', 'c4']);
  assert.equal((await repo.loadProject('p5polish')).chapterVersions.length, 1);
});
test('legacy native cold resume preserves old success receipt then skips drift and continues following target', async () => {
  const { repo, store, cas } = await setup();
  const started = await repo.startPolishJob('p5polish', await cas(), 'legacy-drift', 1, 3, options, 2000);
  const initial = model(repo, new Set());
  const waiting = await runner(repo, initial.port).run('p5polish', started.jobId, 'first-owner', undefined, 1);
  assert.equal(waiting.stage, 'waiting_system');
  assert.equal(waiting.progress[0].chapterId, 'c1');
  const path = 'amberagent/novel-workspace/p5polish/.amber/jobs.json';
  const old = JSON.parse((await store.readText(path))!) as { version: number; jobs: DurablePolishJob[] };
  delete old.jobs[0].outcomes; delete old.jobs[0].polishPreferenceAtStart; delete old.jobs[0].retryChapterIds;
  await store.writeText(path, JSON.stringify(old));
  const backup = await repo.nativeBackupSnapshot('p5polish');
  const restored = createFileNovelRepository(createMemoryFileStore());
  const inputBackup = { projectId: 'p5polish', files: backup.files, manifest: { ...backup.metadata,
    format: 'amber.novel.native-backup' as const, version: 1 as const, checksumAlgorithm: 'fnv1a32' as const, entries: [] } };
  await restored.installNativeBackup(inputBackup, await restored.inspectNativeRestore(inputBackup));
  await restored.resumePolishJob('p5polish', started.jobId, 2001);
  const mock = model(restored, new Set(), new Set(['c2']));
  const completed = await createPolishRunner({ repository: restored, modelRunning: mock.port, nowMs: () => 2002 }).run('p5polish', started.jobId, 'cold-owner');
  assert.equal(completed.stage, 'completed');
  assert.deepEqual(projectPolishOutcomes(completed).map(item => item.status), ['success', 'driftSkipped', 'success']);
  assert.deepEqual(completed.progress.map(item => item.chapterId), ['c1', 'c3']);
  assert.equal(completed.progress[0].receipt, waiting.progress[0].receipt);
  assert.deepEqual(mock.requests.filter(r => r.systemPrompt.includes('润色者')).map(r => input(r).chapter.id), ['c2', 'c3']);
  assert.equal((await restored.loadProject('p5polish')).chapterVersions.length, 2);
});
