import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createProject } from '../main/ets/novel/mutations.ts';
import { projectWithNovelStorySeed, novelQuickStartRequestText } from '../main/ets/novel/standalone_defaults.ts';
import { createNovelCreation } from '../main/ets/novel/creation.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import type { NovelModelRequest } from '../main/ets/novel/model_running.ts';

const seed = { genre: '悬疑', coreIdea: '最初的来信', world: '海边小城', characters: '邮差', direction: '揭开谜团' };

test('creation metadata preserves an independent copy of all original seed fields', () => {
  const draft = { ...seed };
  const project = projectWithNovelStorySeed(createProject('故事', 1), draft, 1);
  draft.coreIdea = '调用者后来修改';
  assert.equal(project.creationMode, 'quickStart');
  assert.deepEqual(project.quickStartSeed, seed);
  const text = novelQuickStartRequestText(project, '放慢节奏', '这一轮的新来信');
  assert.match(text, /这一轮的新来信/);
  assert.match(text, /放慢节奏/);
  assert.match(text, /海边小城/);
  assert.deepEqual(project.quickStartSeed, seed);
});

test('blank legacy projects cannot silently regenerate a nonexistent original seed', () => {
  assert.throws(() => novelQuickStartRequestText(createProject('空白', 1)), /原始故事种子/);
});

test('saved seed and project preference survive disk reload and regeneration uses the existing model checkpoint path', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const requests: NovelModelRequest[] = [];
  const creation = createNovelCreation({ repository, modelRunning: {
    async validate() {},
    start(request) {
      requests.push(request);
      return { subscribe(callback) {
        const timer = setTimeout(() => callback({ kind: 'failed', message: 'captured request' }), 0);
        return () => clearTimeout(timer);
      } };
    }, cancel() {},
  } });
  const project = await creation.create('种子作品', seed);
  const status = await repository.workspaceStatus(project.id);
  await creation.setProjectPolishPreference(project.id, '克制留白', status.cas);
  assert.deepEqual((await repository.loadProject(project.id)).quickStartSeed, seed);
  assert.equal((await repository.loadProject(project.id)).polishPreference, '克制留白');
  await assert.rejects(creation.setProjectPolishPreference(project.id, '过期表单', status.cas));
  const run = await creation.quickStartFromSeed(project.id, '采用倒叙', '当次核心');
  await new Promise<void>(resolve => run.subscribe(event => {
    if (event.kind === 'failed' || event.kind === 'completed' || event.kind === 'interrupted') resolve();
  }));
  assert.equal(requests.length, 1);
  assert.equal(requests[0].operation.kind, 'turn');
  if (requests[0].operation.kind === 'turn') {
    assert.match(requests[0].operation.userPrompt, /当次核心/);
    assert.match(requests[0].operation.userPrompt, /采用倒叙/);
  }
  const reloaded = await repository.loadProject(project.id);
  assert.equal(reloaded.creationMode, 'quickStart');
  assert.deepEqual(reloaded.quickStartSeed, seed);
  assert.equal(reloaded.polishPreference, '克制留白');
});

test('project settings and history mutations reject an in-flight generation before writing', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const creation = createNovelCreation({ repository, modelRunning: {
    async validate() {},
    start() { return { subscribe(callback) {
      const timer = setTimeout(() => callback({ kind: 'failed', message: 'end test run' }), 0);
      return () => clearTimeout(timer);
    } }; }, cancel() {},
  } });
  const project = await creation.create('活动生成门禁', seed);
  const status = await repository.workspaceStatus(project.id);
  const importPlan = await creation.publicExportPlan(project.id);
  const run = creation.generate(project.id, '生成建议', 'discuss', null, 'quick_start');
  assert.throws(() => creation.setProjectPolishPreference(project.id, '不能写入'), /项目正在生成内容/);
  await assert.rejects(creation.installWorkspacePlan(importPlan), /项目正在生成内容/);
  await assert.rejects(creation.restoreProjectRecovery({
    projectId: project.id, title: project.name, source: 'head_snapshot', sourceToken: status.cas.head,
  }), /项目正在生成内容/);
  for (const mutation of [
    () => creation.forkFromHistory(project.id, status.cas.head, '分支'),
    () => creation.undoToCheckpoint(project.id, status.cas.head),
    () => creation.renameBranch(project.id, status.activeBranchId, '新名'),
    () => creation.setMainBranch(project.id, status.activeBranchId),
    () => creation.deleteBranch(project.id, status.activeBranchId),
  ]) await assert.rejects(mutation(), /项目正在生成内容/);
  await new Promise<void>(resolve => run.subscribe(event => {
    if (event.kind === 'failed' || event.kind === 'completed' || event.kind === 'interrupted') resolve();
  }));
  const reloaded = await repository.loadProject(project.id);
  assert.equal(reloaded.polishPreference, '');
  assert.equal((await repository.workspaceStatus(project.id)).activeBranchName, status.activeBranchName);
});

test('stopActiveRun waits for generation cleanup before a branch mutation runs', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  let modelStartedResolve: () => void = () => {};
  const modelStarted = new Promise<void>(resolve => { modelStartedResolve = resolve; });
  let callback: ((event: import('../main/ets/novel/model_running.ts').NovelModelEvent) => void) | null = null;
  const creation = createNovelCreation({ repository, modelRunning: {
    async validate() {},
    start() { return { subscribe(listener) {
      callback = listener;
      modelStartedResolve();
      return () => { callback = null; };
    } }; },
    cancel() { setTimeout(() => callback?.({ kind: 'failed', message: 'cancelled by user' }), 0); },
  } });
  const project = await creation.create('停止后再切换', seed);
  const initialStatus = await repository.workspaceStatus(project.id);
  await creation.createBranch(project.id, '另一条线');
  const branchBeforeRun = (await repository.workspaceStatus(project.id)).activeBranchId;
  creation.generate(project.id, '讨论人物', 'discuss');
  await modelStarted;
  const stopping = creation.stopActiveRun(project.id);
  assert.notEqual(creation.activeRun(project.id), null);
  assert.equal((await repository.workspaceStatus(project.id)).activeBranchId, branchBeforeRun);
  await stopping;
  assert.equal(creation.activeRun(project.id), null);
  await creation.switchBranch(project.id, initialStatus.activeBranchId);
  assert.equal((await repository.workspaceStatus(project.id)).activeBranchId, initialStatus.activeBranchId);
  await creation.stopActiveRun(project.id); // already stopped is a harmless no-op
});

for (const action of ['stop-and-switch', 'delete'] as const) {
  test(`failed interrupted checkpoint blocks ${action} and keeps the visible partial available`, async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  let modelStartedResolve: () => void = () => {};
  const modelStarted = new Promise<void>(resolve => { modelStartedResolve = resolve; });
  let callback: ((event: import('../main/ets/novel/model_running.ts').NovelModelEvent) => void) | null = null;
  const { makeUserMessage, makeAssistantMessage } = await import('../main/ets/agent/message.ts');
  const creation = createNovelCreation({ repository, modelRunning: {
    async validate() {},
    start(request) { return { subscribe(listener) {
      callback = listener;
      const prompt = request.operation.kind === 'turn' ? request.operation.userPrompt : '继续';
      listener({ kind: 'snapshot', messages: [makeUserMessage(prompt), makeAssistantMessage('尚未保存的可见片段')],
        generationActive: true, textDeltasLive: true, transport: 'live' });
      modelStartedResolve();
      return () => { callback = null; };
    } }; },
    cancel() { setTimeout(() => callback?.({ kind: 'failed', message: 'cancelled by user' }), 0); },
  } });
  const project = await creation.create('停止保存失败', seed);
  const initialStatus = await repository.workspaceStatus(project.id);
  await creation.createBranch(project.id, '当前线');
  const branchBeforeRun = (await repository.workspaceStatus(project.id)).activeBranchId;
  const originalCommit = repository.commitProject.bind(repository);
  repository.commitProject = async (id, expected, command, kind, transform) => {
    if (command.endsWith(':interrupt')) throw new Error('disk checkpoint write failed');
    return originalCommit(id, expected, command, kind, transform);
  };
  const events: import('../main/ets/novel/creation.ts').NovelRunEvent[] = [];
  const run = creation.generate(project.id, '讨论人物', 'discuss');
  run.subscribe(event => events.push(event));
  await modelStarted;
  await assert.rejects(async () => {
    if (action === 'delete') await creation.delete(project.id);
    else {
      await creation.stopActiveRun(project.id);
      await creation.switchBranch(project.id, initialStatus.activeBranchId);
    }
  }, /停止后内容未保存.*disk checkpoint write failed/);
  assert.equal((await repository.workspaceStatus(project.id)).activeBranchId, branchBeforeRun);
  const failure = events.find(event => event.kind === 'failed');
  assert.equal(failure?.kind, 'failed');
  if (failure?.kind === 'failed') {
    assert.ok(failure.unsavedMessages?.some(message => message.parts.some(part =>
      part.type === 'text' && part.text === '尚未保存的可见片段')));
  }
});
}

test('reader chapter version lookup uses the frozen CAS and can fork a matching full checkpoint', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const creation = createNovelCreation({ repository, modelRunning: {
    async validate() {}, start() { throw new Error('No AI needed for a reader history action'); }, cancel() {},
  } });
  const project = await creation.create('Reader历史', seed);
  const chapter = await creation.saveChapter(project.id, null, '旧标题', '旧正文');
  await creation.saveChapter(project.id, chapter.id, '新标题', '新正文');
  const snapshot = await creation.readWorkspaceSnapshot(project.id);
  const version = snapshot.project.chapterVersions.find(item => item.chapterId === chapter.id);
  assert.ok(version);
  const checkpoint = await creation.chapterVersionCheckpoint(project.id, version.id, snapshot.status.cas);
  assert.ok(checkpoint);
  await creation.rename(project.id, '已经变化');
  await assert.rejects(creation.chapterVersionCheckpoint(project.id, version.id, snapshot.status.cas), /工作区已变化/);
  await assert.rejects(creation.forkFromHistory(project.id, checkpoint.commitId, '过期确认', snapshot.status.cas), /工作区已变化/);
  await creation.forkFromHistory(project.id, checkpoint.commitId, '由旧稿继续');
  const forked = await repository.loadProject(project.id);
  assert.equal(forked.chapters[0].title, '旧标题');
  assert.equal(forked.chapters[0].content, '旧正文');
  assert.deepEqual(forked.quickStartSeed, seed);
});
