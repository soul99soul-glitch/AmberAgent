import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createNovelCreation } from '../main/ets/novel/creation.ts';
import type { NovelRun, NovelRunEvent } from '../main/ets/novel/creation.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import type { NovelModelRunning, NovelModelRequest, NovelModelEvent } from '../main/ets/novel/model_running.ts';
import { makeAssistantMessage, makeUserMessage } from '../main/ets/agent/message.ts';
import { makeNovelMessage, makeNovelProject, makeNovelMaterial } from '../main/ets/novel/models.ts';
import { novelPlanDigest } from '../main/ets/novel/candidate_provenance.ts';

const terminal = (run: NovelRun): Promise<NovelRunEvent> => new Promise(resolve => {
  run.subscribe(event => {
    if (event.kind === 'completed' || event.kind === 'failed' || event.kind === 'interrupted') resolve(event);
  });
});

const modelReturning = (output: string, requests: NovelModelRequest[]): NovelModelRunning => ({
  async validate(): Promise<void> {},
  start(request: NovelModelRequest) {
    requests.push(request);
    const subscribers = new Set<(event: NovelModelEvent) => void>();
    setTimeout(() => {
      void (async () => {
        assert.equal(request.operation.kind, 'turn');
        if (request.operation.kind !== 'turn') return;
        const messages = request.history.concat([
          makeUserMessage(request.operation.userPrompt), makeAssistantMessage(output),
        ]);
        await request.checkpoint(messages);
        subscribers.forEach(cb => cb({ kind: 'snapshot', messages, generationActive: false,
          textDeltasLive: false, transport: 'live' }));
        subscribers.forEach(cb => cb({ kind: 'completed' }));
      })().catch(error => subscribers.forEach(cb => cb({ kind: 'failed', message: String(error) })));
    }, 0);
    return { subscribe(cb: (event: NovelModelEvent) => void) {
      subscribers.add(cb);
      return () => { subscribers.delete(cb); };
    } };
  },
  cancel(): void {},
});

const setup = (output = '候选正文') => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const requests: NovelModelRequest[] = [];
  const creation = createNovelCreation({ repository, modelRunning: modelReturning(output, requests) });
  return { creation, repository, requests };
};

test('writing candidates persist their branch/source and disable side-effect tools', async () => {
  const { creation, repository, requests } = setup();
  const project = await creation.create('来源');
  const chapter = await creation.saveChapter(project.id, null, '第一章', '源正文');
  assert.equal((await terminal(creation.generate(project.id, '续写', 'write', 'continuation'))).kind, 'completed');
  const saved = await creation.open(project.id);
  const candidate = saved.messages[saved.messages.length - 1].candidate;
  assert.equal(requests[0].toolProfile, 'none');
  assert.equal(candidate?.kind, 'write');
  assert.equal(candidate?.branchId, (await repository.workspaceStatus(project.id)).activeBranchId);
  assert.equal(candidate?.sourceChapterId, chapter.id);
  assert.ok(candidate?.baseManuscriptDigest);
  assert.ok(candidate?.sourceDigest);
  await terminal(creation.generate(project.id, '讨论', 'discuss'));
  assert.notEqual(requests[1].toolProfile, 'none');
  assert.deepEqual((await creation.open(project.id)).messages[1].candidate, candidate,
    '后续 canonical transcript checkpoint 不应丢失旧候选来源');
});

test('collect permits transcript-only changes but rejects manuscript or plan changes', async () => {
  const { creation } = setup();
  const project = await creation.create('基线');
  const chapter = await creation.saveChapter(project.id, null, '第一章', '源正文');
  const completed = await terminal(creation.generate(project.id, '续写', 'write', 'continuation'));
  assert.equal(completed.kind, 'completed');
  if (completed.kind !== 'completed') return;
  await terminal(creation.generate(project.id, '讨论', 'discuss'));
  await creation.saveChapter(project.id, chapter.id, '第一章', '作者改过的正文');
  await assert.rejects(creation.collectMessage(project.id, completed.message.id,
    { kind: 'append', chapterId: chapter.id }), /书稿.*变更/);
  const fresh = await terminal(creation.generate(project.id, '写新章', 'write', 'whole_chapter'));
  if (fresh.kind !== 'completed') return assert.fail('fresh generation must complete');
  const current = await creation.open(project.id);
  await creation.setBranchSettings(project.id, { ...current.branchSettings, thisChapterPlan: '新目标' });
  await assert.rejects(creation.collectMessage(project.id, fresh.message.id,
    { kind: 'new_chapter', title: '第二章' }), /计划|设定/);
});

test('a candidate copied into a fork cannot be collected on the other branch', async () => {
  const { creation, repository } = setup();
  const project = await creation.create('分支');
  const completed = await terminal(creation.generate(project.id, '写', 'write', 'whole_chapter'));
  if (completed.kind !== 'completed') return assert.fail('generation must complete');
  const status = await repository.workspaceStatus(project.id);
  await repository.createBranch(project.id, '另一条线', status.cas, 'fork-test');
  await assert.rejects(creation.collectMessage(project.id, completed.message.id,
    { kind: 'new_chapter', title: '新章' }), /分支/);
});

test('collect rejects a candidate after an injected character alias changes without body edits', async () => {
  const { creation, requests } = setup('老张走出了村庄。');
  const project = await creation.create('别名来源');
  const material = await creation.upsertMaterial(project.id, null, 'character', '张明', '主角设定', true,
    { aliases: ['老张'], tags: ['故乡'], customKind: '主角', injectionMode: 'always' });
  const event = await terminal(creation.generate(project.id, '写主角离乡', 'write', 'whole_chapter'));
  if (event.kind !== 'completed') return assert.fail('generation must complete');
  assert.match(requests[0].systemPrompt, /Aliases: 老张/);
  await creation.upsertMaterial(project.id, material.id, material.kind, material.title, material.content, true,
    { aliases: ['小李'], tags: material.tags, customKind: material.customKind, injectionMode: 'always' });
  await assert.rejects(creation.collectMessage(project.id, event.message.id,
    { kind: 'new_chapter', title: '第一章' }), /计划或设定已变更/);
  assert.equal((await creation.open(project.id)).chapters.length, 0);
});

test('candidate source digest covers injection fields and uses the same legacy field defaults', async () => {
  const { creation } = setup();
  const project = await creation.create('注入字段来源');
  await creation.upsertMaterial(project.id, null, 'character', '张明', '主角设定', true);
  const saved = await creation.open(project.id);
  const material = saved.materials[0];
  const original = novelPlanDigest(saved);
  for (const fields of [
    { aliases: ['老张'] }, { tags: ['故乡'] }, { customKind: '主角' }, { injectionMode: 'smart' as const },
  ]) {
    assert.notEqual(novelPlanDigest({ ...saved, materials: [{ ...material, ...fields }] }), original);
  }
  assert.equal(novelPlanDigest({ ...saved, materials: [{ ...material,
    aliases: undefined, tags: undefined, customKind: undefined, injectionMode: undefined }] }), original);
  const legacy = makeNovelProject({ id: 'legacy', name: 'legacy', now: 1 });
  const legacyMaterial = makeNovelMaterial({ id: 'legacy-character', kind: 'character', title: '张明',
    content: '主角设定', enabled: true, now: 1 });
  // The pre-metadata source digest for this fixed legacy fixture must remain valid.
  assert.equal(novelPlanDigest({ ...legacy, materials: [legacyMaterial] }), 'a284ac10');
});

test('regeneration binds its source chapter and includes the actual source body', async () => {
  const { creation, requests } = setup();
  const project = await creation.create('重写');
  const chapter = await creation.saveChapter(project.id, null, '第一章', '旧正文里的关键事实');
  const event = await terminal(await creation.regenerateChapter(project.id, chapter.id));
  if (event.kind !== 'completed') return assert.fail('regeneration must complete');
  assert.equal(event.message.candidate?.kind, 'regenerate');
  assert.equal(event.message.candidate?.sourceChapterId, chapter.id);
  assert.ok(requests[0].operation.kind === 'turn' &&
    requests[0].operation.userPrompt.includes('旧正文里的关键事实'));
});

test('regenerating a long existing chapter uses the full current source rather than the input limit', async () => {
  const { creation, requests } = setup();
  const project = await creation.create('长章');
  const source = '完整正文'.repeat(3_000);
  const chapter = await creation.saveChapter(project.id, null, '第一章', source);
  const event = await terminal(await creation.regenerateChapter(project.id, chapter.id));
  assert.equal(event.kind, 'completed');
  assert.ok(requests[0].operation.kind === 'turn' && requests[0].operation.userPrompt.includes(source));
});

test('new checkpoints do not fabricate provenance for legacy assistant messages', async () => {
  const { creation, repository } = setup();
  const project = await creation.create('旧消息');
  const legacy = makeNovelMessage({ role: 'assistant', mode: 'write', content: '旧候选', createdAt: 1 });
  await repository.updateProject(project.id, current => ({ ...current, messages: [legacy] }));
  await terminal(creation.generate(project.id, '写', 'write'));
  assert.equal((await creation.open(project.id)).messages[0].candidate, undefined);
});

test('discussion checkpoints cannot overwrite a branch switched after the run starts', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  let request: NovelModelRequest | null = null;
  let notify = (_event: NovelModelEvent): void => {};
  const model: NovelModelRunning = {
    async validate(): Promise<void> {},
    start(input) { request = input; return { subscribe(cb: (event: NovelModelEvent) => void) {
      notify = cb; return () => {};
    } }; },
    cancel() {},
  };
  const creation = createNovelCreation({ repository, modelRunning: model });
  const project = await creation.create('讨论分支');
  const run = creation.generate(project.id, '主线讨论', 'discuss');
  const done = terminal(run);
  await new Promise(resolve => setTimeout(resolve, 10));
  const status = await repository.workspaceStatus(project.id);
  await repository.createBranch(project.id, '新线', status.cas, 'discuss-fork');
  assert.ok(request !== null);
  const bound = request as unknown as NovelModelRequest;
  const messages = bound.history.concat([makeUserMessage('主线讨论'), makeAssistantMessage('主线结果')]);
  try {
    await assert.rejects(bound.checkpoint(messages), /分支/);
  } finally {
    notify({ kind: 'failed', message: '分支已切换' });
  }
  assert.equal((await done).kind, 'failed');
  assert.equal((await creation.open(project.id)).messages.length, 0);
});

test('retry reset containing only old assistants does not attribute old prose to the stopped run', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  let request: NovelModelRequest | null = null;
  let notify = (_event: NovelModelEvent): void => {};
  const model: NovelModelRunning = {
    async validate(): Promise<void> {},
    start(input) { request = input; return { subscribe(cb: (event: NovelModelEvent) => void) {
      notify = cb; return () => {};
    } }; },
    cancel() { notify({ kind: 'failed', message: 'cancelled' }); },
  };
  const creation = createNovelCreation({ repository, modelRunning: model });
  const project = await creation.create('重试停止');
  const old = makeNovelMessage({ role: 'assistant', mode: 'write', content: '上一轮旧稿', createdAt: 1 });
  await repository.updateProject(project.id, current => ({ ...current, messages: [old] }));
  const run = creation.generate(project.id, '再写', 'write');
  const done = terminal(run);
  await new Promise(resolve => setTimeout(resolve, 10));
  assert.ok(request !== null);
  const bound = request as unknown as NovelModelRequest;
  const messages = bound.history.concat([makeUserMessage('再写')]);
  await bound.checkpoint(messages);
  notify({ kind: 'snapshot', messages, generationActive: true, textDeltasLive: false, transport: 'buffered' });
  creation.interrupt(run.id);
  const event = await done;
  assert.equal(event.kind, 'interrupted');
  assert.equal(event.kind === 'interrupted' ? event.partial : 'failed', '');
  const saved = await creation.open(project.id);
  assert.equal(saved.messages.filter(message => message.role === 'assistant').length, 1);
  assert.equal(saved.messages[0].candidate, undefined);
});

test('complete polish strips its protocol sentinel from persisted and collected text', async () => {
  const { creation } = setup('润色后的正文\n<AMBER_NOVEL_POLISH_COMPLETE>');
  const project = await creation.create('润色');
  const chapter = await creation.saveChapter(project.id, null, '第一章', '旧正文');
  const event = await terminal(await creation.polishChapter(project.id, chapter.id));
  if (event.kind !== 'completed') return assert.fail('polish must complete');
  assert.equal(event.message.content, '润色后的正文');
  assert.equal(event.message.candidate?.complete, true);
  const result = await creation.collectMessage(project.id, event.message.id,
    { kind: 'replace', chapterId: chapter.id });
  assert.equal(result.chapter.content, '润色后的正文');
});

test('incomplete polish is reported as failed and cannot be adopted', async () => {
  const { creation } = setup('没有结束标记的部分润色');
  const project = await creation.create('不完整润色');
  const chapter = await creation.saveChapter(project.id, null, '第一章', '完整源稿');
  const event = await terminal(await creation.polishChapter(project.id, chapter.id));
  assert.equal(event.kind, 'failed');
  const saved = await creation.open(project.id);
  await assert.rejects(creation.collectMessage(project.id, saved.messages[saved.messages.length - 1].id,
    { kind: 'replace', chapterId: chapter.id }), /润色.*完整/);
  assert.equal((await creation.open(project.id)).chapters[0].content, '完整源稿');
});

test('a polish completion marker without prose is rejected rather than reported as complete', async () => {
  const { creation } = setup('<AMBER_NOVEL_POLISH_COMPLETE>');
  const project = await creation.create('空润色');
  const chapter = await creation.saveChapter(project.id, null, '第一章', '完整源稿');
  const event = await terminal(await creation.polishChapter(project.id, chapter.id));
  assert.equal(event.kind, 'failed');
  assert.equal((await creation.open(project.id)).chapters[0].content, '完整源稿');
});

test('stop persistence failure produces a failed terminal with an unsaved warning', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  let failing = false;
  const wrapped = { ...repository, commitProject: (...args: Parameters<typeof repository.commitProject>) => {
    if (failing) return Promise.reject(new Error('磁盘已满'));
    return repository.commitProject(...args);
  } };
  let notify = (_event: NovelModelEvent): void => {};
  let request: NovelModelRequest | null = null;
  const model: NovelModelRunning = {
    async validate(): Promise<void> {},
    start(input) { request = input; return { subscribe(cb: (event: NovelModelEvent) => void) {
      notify = cb;
      return () => {};
    } }; },
    cancel() { notify({ kind: 'failed', message: 'cancelled' }); },
  };
  const creation = createNovelCreation({ repository: wrapped, modelRunning: model });
  const project = await creation.create('停止');
  const oldUser = makeNovelMessage({ role: 'user', mode: 'discuss', content: '旧讨论', createdAt: 1 });
  const oldAssistant = makeNovelMessage({ role: 'assistant', mode: 'discuss', content: '旧回答', createdAt: 2 });
  await repository.updateProject(project.id, current => ({ ...current, messages: [oldUser, oldAssistant] }));
  const run = creation.generate(project.id, '别丢掉这个输入', 'write', 'continuation');
  const eventPromise = terminal(run);
  await new Promise(resolve => setTimeout(resolve, 10));
  assert.ok(request !== null);
  const bound = request as unknown as NovelModelRequest;
  const messages = bound.history.concat([makeUserMessage('别丢掉这个输入'), makeAssistantMessage('尚未保存的稿子')]);
  notify({ kind: 'snapshot', messages, generationActive: true, textDeltasLive: true, transport: 'live' });
  failing = true;
  creation.interrupt(run.id);
  const event = await eventPromise;
  assert.equal(event.kind, 'failed');
  assert.ok(event.kind === 'failed' && event.message.includes('未保存'));
  assert.ok(event.kind === 'failed' && event.unsavedMessages?.some(message =>
    message.parts.some(part => part.type === 'text' && part.text === '尚未保存的稿子')));
  assert.equal(event.kind === 'failed' ? event.unsavedMessages?.length : 0, 2);
  assert.ok(event.kind === 'failed' && !event.unsavedMessages?.some(message => message.id === oldAssistant.uiMessage.id));
});
