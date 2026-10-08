import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createNovelCreation } from '../main/ets/novel/creation.ts';
import type { NovelRun, NovelRunEvent } from '../main/ets/novel/creation.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { makeAssistantMessage, makeUserMessage } from '../main/ets/agent/message.ts';
import { makeNovelMessage } from '../main/ets/novel/models.ts';
import type { NovelModelRunning, NovelModelEvent, NovelModelRequest } from '../main/ets/novel/model_running.ts';
import { buildNovelWorkspacePublicFiles } from '../main/ets/novel/workspace_interop.ts';
import { validateNovelWorkspacePath } from '../main/ets/novel/workspace_contract.ts';
import { parseNovelQuickStart, stageNovelQuickStart, formatNovelQuickStartOverview } from '../main/ets/novel/quick_start_proposals.ts';

const output = JSON.stringify({ overview: '低魔世界里的失忆侦探。\n开篇调查灯塔。', proposals: [
  { kind: 'character', title: '侦探', content: '失忆，擅长观察。' },
  { kind: 'world', title: '灯塔城', content: '海岸城市。' },
] });
const terminal = (run: NovelRun): Promise<NovelRunEvent> => new Promise(resolve => run.subscribe(event => {
  if (['completed', 'failed', 'interrupted', 'waiting_user'].includes(event.kind)) resolve(event);
}));
const setup = (text = output, waiting = false) => {
  const repo = createFileNovelRepository(createMemoryFileStore());
  const requests: NovelModelRequest[] = [];
  let beforeComplete: (() => Promise<void>) | undefined;
  const model: NovelModelRunning = { async validate() {}, cancel() {}, start(request) {
    requests.push(request);
    const callbacks = new Set<(event: NovelModelEvent) => void>();
    setTimeout(() => { void (async () => {
      const messages = request.history.concat([makeUserMessage('灵感'), makeAssistantMessage(text)]);
      await request.checkpoint(messages);
      callbacks.forEach(cb => cb({ kind: 'snapshot', messages, generationActive: false,
        textDeltasLive: false, transport: 'live' }));
      if (beforeComplete !== undefined) await beforeComplete();
      callbacks.forEach(cb => cb(waiting ? { kind: 'waiting_user' } : { kind: 'completed' }));
    })().catch(error => callbacks.forEach(cb => cb({ kind: 'failed', message: String(error) }))); }, 0);
    return { subscribe(cb) { callbacks.add(cb); return () => { callbacks.delete(cb); }; } };
  } };
  return { repo, requests, creation: createNovelCreation({ repository: repo, modelRunning: model }),
    setBeforeComplete(callback: () => Promise<void>) { beforeComplete = callback; } };
};

test('quick-start terminal persists bound proposals only, retains rich canonical message and uses no tools', async () => {
  const { repo, creation, requests } = setup();
  const p = await creation.create('快速开始');
  const event = await terminal(await creation.quickStart(p.id, '侦探'));
  assert.equal(event.kind, 'completed');
  const saved = await repo.loadProject(p.id);
  assert.equal(saved.materials.length, 0);
  assert.equal(saved.chapters.length, 0);
  assert.equal(saved.settingProposals.length, 2);
  const message = saved.messages.at(-1)!;
  assert.equal(message.content, output);
  assert.equal(message.runKind, 'quick_start');
  assert.equal(saved.messages[0].runKind, undefined);
  assert.equal(saved.settingProposals[0].sourceMessageId, message.id);
  assert.equal(saved.settingProposals[0].status, 'pending');
  assert.equal(formatNovelQuickStartOverview(saved, message), JSON.parse(output).overview);
  assert.equal(requests[0].toolProfile, 'none');
  const files = buildNovelWorkspacePublicFiles(await repo.publicExportPlan(p.id));
  assert.equal(files.filter(file => file.path.includes('/inbox/')).length, 2);
  files.forEach(file => validateNovelWorkspacePath(file.path));
});


test('quick-start stages no proposal before completed and exposes its reconnect run kind', async () => {
  const { repo, creation, setBeforeComplete } = setup();
  const p = await creation.create('待完成');
  setBeforeComplete(async () => {
    assert.equal(creation.activeRun(p.id)?.runKind, 'quick_start');
    assert.equal((await repo.loadProject(p.id)).settingProposals.length, 0);
  });
  assert.equal((await terminal(await creation.quickStart(p.id, '灵感'))).kind, 'completed');
  assert.equal((await repo.loadProject(p.id)).settingProposals.length, 2);
});

test('quick-start waiting terminal cannot create proposals from incomplete output', async () => {
  const { creation } = setup(output, true);
  const p = await creation.create('等待');
  assert.equal((await terminal(await creation.quickStart(p.id, '灵感'))).kind, 'waiting_user');
  assert.equal((await creation.open(p.id)).settingProposals.length, 0);
});

test('quick-start stable source/index replay preserves accepted and rejected decisions', async () => {
  const { creation } = setup();
  const p = await creation.create('重放');
  const source = makeNovelMessage({ role: 'assistant', mode: 'discuss', content: output, createdAt: 1 });
  const first = stageNovelQuickStart({ ...p, messages: [source] }, source.id, output, 1);
  const decided = { ...first, settingProposals: first.settingProposals.map((proposal, index) => ({ ...proposal,
    status: index === 0 ? 'accepted' as const : 'rejected' as const, resolvedAt: 2 })) };
  const replay = stageNovelQuickStart(decided, source.id, output, 3);
  assert.deepEqual(replay.settingProposals, decided.settingProposals);
  assert.equal(replay.materials.length, 0);
});

test('quick-start malformed output visibly fails without partial proposals', async () => {
  for (const text of ['bad JSON', '{"overview":"ok","proposals":[]}',
    '{"overview":"ok","proposals":[{"kind":"character","title":"a","content":"b"},null]}']) {
    const { creation } = setup(text);
    const p = await creation.create('坏 JSON');
    const event = await terminal(await creation.quickStart(p.id, '灵感'));
    assert.equal(event.kind, 'failed');
    if (event.kind === 'failed') assert.match(event.message, /快速开始解析失败/);
    const cold = await creation.open(p.id);
    assert.equal(cold.settingProposals.length, 0);
    const failed = cold.messages.at(-1)!;
    assert.equal(failed.runKind, 'quick_start');
    assert.equal(failed.content, text);
    assert.match(formatNovelQuickStartOverview(cold, failed) ?? '', /未完成/);
  }
  assert.throws(() => parseNovelQuickStart(output.replace('character', 'unsupported'), 'a', 1));
});

test('quick-start cannot attach proposals to branch switched after canonical checkpoint', async () => {
  const { repo, creation, setBeforeComplete } = setup();
  const p = await creation.create('分支切换');
  setBeforeComplete(async () => {
    const status = await repo.workspaceStatus(p.id);
    await repo.createBranch(p.id, '支线', status.cas, 'switch-during-start');
  });
  const event = await terminal(await creation.quickStart(p.id, '灵感'));
  assert.equal(event.kind, 'failed');
  if (event.kind === 'failed') assert.match(event.message, /分支已切换/);
  assert.equal((await creation.open(p.id)).settingProposals.length, 0);
});

test('quick-start initial project/history and branch come from the same atomic read', async () => {
  const { repo, creation } = setup();
  const p = await creation.create('起始分支');
  await repo.updateProject(p.id, current => ({ ...current, messages: [makeNovelMessage({
    role: 'user', mode: 'discuss', content: 'MAIN KEEP', createdAt: 1 })] }));
  const main = await repo.workspaceStatus(p.id);
  await repo.createBranch(p.id, 'SIDE', main.cas, 'fork-initial');
  const side = await repo.workspaceStatus(p.id);
  await repo.updateProject(p.id, current => ({ ...current, messages: [makeNovelMessage({
    role: 'user', mode: 'discuss', content: 'SIDE KEEP', createdAt: 1 })] }));
  await repo.switchBranch(p.id, main.activeBranchId, (await repo.workspaceStatus(p.id)).cas);
  const read = repo.readWorkspaceSnapshot;
  let atomicReads = 0;
  repo.readWorkspaceSnapshot = async id => {
    const snapshot = await read(id); atomicReads++;
    if (atomicReads === 1) await repo.switchBranch(id, side.activeBranchId, (await repo.workspaceStatus(id)).cas);
    return snapshot;
  };
  const event = await terminal(await creation.quickStart(p.id, '主线灵感'));
  assert.equal(atomicReads, 1);
  assert.equal(event.kind, 'failed');
  const saved = await repo.loadProject(p.id);
  assert.deepEqual(saved.messages.map(message => message.content), ['SIDE KEEP']);
  assert.equal(saved.settingProposals.length, 0);
});

test('interrupted quick-start preserves typed origin on cold read without changing rich payload', async () => {
  const repo = createFileNovelRepository(createMemoryFileStore());
  const callbacks = new Set<(event: NovelModelEvent) => void>();
  let requested: NovelModelRequest | null = null;
  const model: NovelModelRunning = { async validate() {}, cancel() {
    callbacks.forEach(cb => cb({ kind: 'failed', message: 'stopped' }));
  }, start(request) { requested = request;
    return { subscribe(cb) { callbacks.add(cb); return () => { callbacks.delete(cb); }; } };
  } };
  const creation = createNovelCreation({ repository: repo, modelRunning: model });
  const p = await creation.create('中断');
  const run = await creation.quickStart(p.id, '灵感');
  const done = terminal(run);
  while (requested === null) await new Promise(resolve => setTimeout(resolve, 1));
  const request = requested as NovelModelRequest;
  const assistant = makeAssistantMessage('{"overview":"未结束');
  assistant.parts.unshift({ type: 'reasoning', reasoning: '保留原推理', createdAt: new Date(1).toISOString(), finishedAt: null, metadata: null });
  const messages = request.history.concat([makeUserMessage('灵感'), assistant]);
  await request.checkpoint(messages);
  callbacks.forEach(cb => cb({ kind: 'snapshot', messages, generationActive: true, textDeltasLive: true, transport: 'live' }));
  creation.interrupt(run.id);
  const stopped = await done;
  assert.equal(stopped.kind, 'interrupted', JSON.stringify(stopped));
  const cold = await repo.loadProject(p.id);
  const message = cold.messages.at(-1)!;
  assert.equal(message.runKind, 'quick_start'); assert.equal(message.interrupted, true);
  assert.equal(message.content, '{"overview":"未结束');
  assert.equal(message.uiMessage.parts[0].type, 'reasoning');
  assert.match(formatNovelQuickStartOverview(cold, message) ?? '', /已停止/);
  assert.equal(cold.settingProposals.length, 0);
  const ordinary = makeNovelMessage({ role: 'assistant', mode: 'discuss', content: output, createdAt: 1 });
  assert.equal(formatNovelQuickStartOverview(cold, ordinary), null, 'ordinary JSON is never guessed as a quick start');
});

const jobSetup = async () => {
  const repo = createFileNovelRepository(createMemoryFileStore());
  const model: NovelModelRunning = { async validate() {}, cancel() {}, start() {
    return { subscribe(cb) { setTimeout(() => cb({ kind: 'failed', message: '测试不执行 provider' }), 0); return () => {}; } };
  } };
  const creation = createNovelCreation({ repository: repo, modelRunning: model });
  const p = await creation.create('任务');
  await repo.updateProject(p.id, current => ({ ...current, branchSettings: { ...current.branchSettings,
    thisChapterPlan: '# 作者章计划\n\n保持未知答案，不改写成禁令。\n' } }));
  return { repo, creation, p };
};
test('atomic preview returns full author Markdown and stale plan/body/branch cannot start a job', async () => {
  for (const changed of ['plan', 'body', 'branch']) {
    const { repo, creation, p } = await jobSetup();
    const preview = await creation.prepareGhostwriteStart(p.id, 2);
    assert.equal(preview.planContent, '# 作者章计划\n\n保持未知答案，不改写成禁令。\n');
    if (changed === 'plan') await repo.updateProject(p.id, current => ({ ...current,
      branchSettings: { ...current.branchSettings, thisChapterPlan: '已改计划' } }));
    if (changed === 'body') await creation.saveChapter(p.id, null, '新正文', '正文');
    if (changed === 'branch') {
      const status = await repo.workspaceStatus(p.id); await repo.createBranch(p.id, '支线', status.cas, 'branch');
    }
    await assert.rejects(creation.startGhostwrite(p.id, 2, preview), /预览后/);
    assert.equal((await repo.listGhostwriteJobs(p.id)).length, 0);
  }
});

test('preview CAS is still checked atomically after model validation changes the body', async () => {
  const { repo, p } = await jobSetup();
  let changed = false;
  const model: NovelModelRunning = { async validate() {
    if (!changed) { changed = true; await repo.updateProject(p.id, current => ({ ...current, name: '变更' })); }
  }, cancel() {}, start() { throw new Error('must not run'); } };
  const creation = createNovelCreation({ repository: repo, modelRunning: model });
  const preview = await creation.prepareGhostwriteStart(p.id, 1);
  await assert.rejects(creation.startGhostwrite(p.id, 1, preview), /CAS|changed|冲突|过期|已变化/);
  assert.equal((await repo.listGhostwriteJobs(p.id)).length, 0);
});

test('single chapter polish uses independent imported ordinal and survives reopening', async () => {
  const { repo, creation, p } = await jobSetup();
  await repo.updateProject(p.id, current => ({ ...current, chapters: [{ id: 'uuid-import', ordinal: 7,
    title: '七', content: '原文', discarded: false, createdAt: 1, updatedAt: 1 }] }));
  const status = await repo.workspaceStatus(p.id); await repo.syncPlot(p.id, status.cas, 'sync');
  const options = { includePlot: false, includeForeshadows: false, includeCharacters: false, includeDecisions: false };
  const job = await creation.startChapterPolish(p.id, 'uuid-import', options);
  assert.equal(job.targets.length, 1); assert.equal(job.targets[0].ordinal, 7);
  assert.equal(job.targets[0].id, 'uuid-import');
  assert.equal((await repo.loadPolishJob(p.id, job.jobId)).targets[0].ordinal, 7);
  await assert.rejects(creation.startChapterPolish(p.id, 'unknown', options), /不存在/);
  await creation.setChapterDiscarded(p.id, 'uuid-import', true);
  await assert.rejects(creation.startChapterPolish(p.id, 'uuid-import', options), /已废弃/);
});

test('terminal ghostwrite report is independently readable without locking active UI', async () => {
  const { repo, creation, p } = await jobSetup();
  const snap = await repo.readWorkspaceSnapshot(p.id);
  let job = await repo.startGhostwriteJob(p.id, snap.status.cas, 'job', 'plan', 1, 1);
  assert.equal(await creation.latestGhostwriteReport(p.id), null, 'active job is never a historical report');
  job = await repo.claimGhostwriteJob(p.id, job.jobId, 'claim', 2, 1000);
  await repo.cancelGhostwriteJob(p.id, job.jobId, { token: 'claim', epoch: job.claimEpoch }, 3);
  assert.equal(await creation.ghostwriteJob(p.id), null);
  const report = await creation.latestGhostwriteReport(p.id);
  assert.equal(report?.stage, 'cancelled'); assert.equal(report?.targetChapterCount, 1);
});
