import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createNovelCreation } from '../main/ets/novel/creation.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { makeNovelChapterVersion, makeNovelMaterial, makeNovelMessage } from '../main/ets/novel/models.ts';

const setup = async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const creation = createNovelCreation({ repository, modelRunning: {
    async validate() {}, start() { throw new Error('版本查询不能调用模型'); }, cancel() {},
  } });
  const project = await creation.create('版本检查点');
  const chapter = await creation.saveChapter(project.id, null, '旧标题', '旧正文');
  await repository.commitProject(project.id, (await repository.workspaceStatus(project.id)).cas, 'old-context', 'material_edit', p => ({
    ...p, materials: [makeNovelMaterial({ id: 'world', kind: 'world', title: '世界', content: '旧世界', now: 1000 })],
    messages: [makeNovelMessage({ id: 'old-dialogue', role: 'user', mode: 'discuss', content: '旧对话', createdAt: 1000 })],
  }));
  const beforeSave = (await repository.workspaceStatus(project.id)).cas;
  await creation.saveChapter(project.id, chapter.id, '新标题', '新正文');
  const version = (await repository.loadProject(project.id)).chapterVersions[0];
  return { repository, creation, project, chapter, version, beforeSave };
};

test('saved previous chapter version resolves to the latest real matching checkpoint with its whole context', async () => {
  const { repository, project, version, beforeSave } = await setup();
  const checkpoint = await repository.chapterVersionCheckpoint(project.id, version.id);
  assert.equal(checkpoint?.commitId, beforeSave.head);
  const fork = await repository.forkFromHistory(project.id, checkpoint!.commitId, '从旧版本分叉',
    (await repository.workspaceStatus(project.id)).cas, 'fork-version');
  assert.equal(fork.chapters[0].title, '旧标题');
  assert.equal(fork.chapters[0].content, '旧正文');
  assert.equal(fork.materials[0].content, '旧世界');
  assert.equal(fork.messages[0].id, 'old-dialogue');
});

test('chapter version without an exact chapter id title and body checkpoint returns null', async () => {
  const { repository, project, chapter } = await setup();
  await repository.commitProject(project.id, (await repository.workspaceStatus(project.id)).cas, 'orphan-version', 'compat_update', p => ({
    ...p, chapterVersions: p.chapterVersions.concat([
      makeNovelChapterVersion(chapter.id, 'manual', '旧标题', '工作区未保存过的正文', 2000),
      makeNovelChapterVersion('other-chapter', 'manual', '旧标题', '旧正文', 2000),
      makeNovelChapterVersion(chapter.id, 'manual', '错误标题', '旧正文', 2000),
    ]),
  }));
  const versions = (await repository.loadProject(project.id)).chapterVersions.slice(-3);
  for (const version of versions) assert.equal(await repository.chapterVersionCheckpoint(project.id, version.id), null);
  assert.equal(await repository.chapterVersionCheckpoint(project.id, 'not-present'), null);
});

test('version checkpoint lookup rejects stale frozen CAS without creating a branch or mutation', async () => {
  const { repository, project, version, beforeSave } = await setup();
  const before = await repository.workspaceHistory(project.id);
  await assert.rejects(repository.chapterVersionCheckpoint(project.id, version.id, beforeSave), /工作区已变化/);
  assert.deepEqual(await repository.workspaceHistory(project.id), before);
  const cas = (await repository.workspaceStatus(project.id)).cas;
  assert.ok(await repository.chapterVersionCheckpoint(project.id, version.id, cas));
});
