import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createNovelCreation } from '../main/ets/novel/creation.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { makeNovelMessage } from '../main/ets/novel/models.ts';

const setup = () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const creation = createNovelCreation({ repository, modelRunning: {
    async validate() {}, start() { throw new Error('素材分析在本测试中不可用'); }, cancel() {},
  } });
  return { repository, creation };
};

test('collected reply clone preserves original record and native lineage, permits fresh adoption but rejects duplicate adoption', async () => {
  const { repository, creation } = setup();
  const project = await creation.create('再次收录');
  const original = makeNovelMessage({ role: 'assistant', mode: 'write', content: '原回复正文', createdAt: 1 });
  const chapter = await creation.saveChapter(project.id, null, '原章', '作者编辑后的书稿');
  const initial = (await repository.workspaceStatus(project.id)).cas;
  await repository.updateProject(project.id, current => ({ ...current, messages: [{ ...original, collectedChapterId: chapter.id }],
    ordinaryRun: { version: 1, id: 'old-run', branchId: initial.branchId, mode: 'write', granularity: 'whole_chapter',
      runKind: 'prose_whole_chapter', userText: '旧请求', originalRequest: { systemPrompt: '写作', maxOutputTokens: 1024,
        modelTarget: { kind: 'global' }, toolProfile: 'none', history: [], operation: { kind: 'turn', userPrompt: '旧请求' } },
      transcriptPrefix: [], checkpointMessages: [original.uiMessage], cursor: null, status: 'failed', error: '断线', startedAt: 1, updatedAt: 2 } }));
  const frozen = (await repository.workspaceStatus(project.id)).cas;
  const clone = await creation.cloneCollectedMessage(project.id, original.id, frozen);
  assert.notEqual(clone.id, original.id);
  assert.equal((await creation.open(project.id)).ordinaryRun, undefined, 'new author decision clears prior retry ownership but retains canonical history');
  assert.equal(clone.collectedChapterId, null);
  assert.equal(clone.content, '原回复正文');
  assert.equal(clone.clonedFromMessageId, original.id);
  assert.equal(clone.rootMessageId, original.id);
  assert.equal((await creation.open(project.id)).messages[0].collectedChapterId, chapter.id);
  const result = await creation.collectMessage(project.id, clone.id, { kind: 'new_chapter', title: '再次采用' });
  await result.analysisFinished.catch(() => {});
  await assert.rejects(creation.collectMessage(project.id, clone.id, { kind: 'new_chapter', title: '重复' }), /收进章节/);
  const second = await creation.cloneCollectedMessage(project.id, clone.id, (await repository.workspaceStatus(project.id)).cas);
  assert.equal(second.clonedFromMessageId, clone.id);
  assert.equal(second.rootMessageId, original.id);
  const saved = await repository.nativeBackupSnapshot(project.id);
  const native = createFileNovelRepository(createMemoryFileStore());
  const backup = { projectId: project.id, files: saved.files, manifest: { ...saved.metadata,
    format: 'amber.novel.native-backup' as const, version: 1 as const, checksumAlgorithm: 'fnv1a32' as const, entries: [] } };
  await native.installNativeBackup(backup, await native.inspectNativeRestore(backup));
  const reloaded = await native.loadProject(project.id);
  assert.equal(reloaded.messages.find(message => message.id === second.id)!.rootMessageId, original.id);
  assert.equal(reloaded.messages.find(message => message.id === clone.id)!.collectedChapterId, result.chapter.id);
});

test('clone freezes author decision with CAS and fresh candidate rejects subsequent manuscript change', async () => {
  const { repository, creation } = setup();
  const project = await creation.create('来源保护');
  const chapter = await creation.saveChapter(project.id, null, '原章', '已有书稿');
  const source = makeNovelMessage({ role: 'assistant', mode: 'write', content: '可再次确认的原回复', collectedChapterId: chapter.id, createdAt: 1 });
  await repository.updateProject(project.id, current => ({ ...current, messages: [source] }));
  const frozen = (await repository.workspaceStatus(project.id)).cas;
  await creation.rename(project.id, '作者刚改名');
  await assert.rejects(creation.cloneCollectedMessage(project.id, source.id, frozen), /CAS|工作区|变更/);
  assert.equal((await creation.open(project.id)).messages.length, 1);
  const clone = await creation.cloneCollectedMessage(project.id, source.id, (await repository.workspaceStatus(project.id)).cas);
  await creation.saveChapter(project.id, chapter.id, chapter.title, '后来修改的书稿');
  await assert.rejects(creation.collectMessage(project.id, clone.id, { kind: 'new_chapter', title: '旧基线' }), /书稿已变更/);
  assert.equal((await creation.open(project.id)).messages.find(message => message.id === clone.id)!.collectedChapterId, null);
});
