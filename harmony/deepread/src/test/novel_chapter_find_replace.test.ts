import { test } from 'node:test';
import assert from 'node:assert/strict';
import { findNovelChapterMatches, replaceNovelChapterMatch } from '../main/ets/novel/chapter_find_replace.ts';
import { createNovelCreation } from '../main/ets/novel/creation.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { makeNovelChapter, makeNovelProject } from '../main/ets/novel/models.ts';

const NOW = 1_700_000_000_000;

test('chapter search is literal, case-sensitive and finds non-overlapping matches', () => {
  assert.deepEqual(findNovelChapterMatches('林岚说：林岚。', '林岚'), [0, 4]);
  assert.deepEqual(findNovelChapterMatches('a.a aa AAA', 'a.'), [0]);
  assert.deepEqual(findNovelChapterMatches('aaaa', 'aa'), [0, 2]);
  assert.deepEqual(findNovelChapterMatches('正文', ''), []);
});

test('single replacement targets the selected match while all replacement stays inside the supplied body', () => {
  assert.equal(replaceNovelChapterMatch('林岚看见林岚。', '林岚', '小林', 1), '林岚看见小林。');
  assert.equal(replaceNovelChapterMatch('a.a a.a', 'a.a', '$&', null), '$& $&');
  assert.equal(replaceNovelChapterMatch('aaaa', 'aa', 'b', null), 'bb');
  assert.equal(replaceNovelChapterMatch('正文', '', '空', null), '正文');
  assert.equal(replaceNovelChapterMatch('正文', '文', '稿', 5), '正文');
});

test('replacement saves only the edited chapter with a manual version and rejects stale editor CAS', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const creation = createNovelCreation({ repository, nowMs: () => NOW, modelRunning: {
    async validate() {}, start() { throw new Error('replacement must not invoke a model'); }, cancel() {},
  }});
  const project = makeNovelProject({ id: 'find-replace', name: '查找替换', now: NOW });
  await repository.createProject({ ...project, chapters: [
    makeNovelChapter({ id: 'one', title: '林岚标题', content: '林岚看见林岚。', now: NOW }),
    makeNovelChapter({ id: 'two', title: '第二章', content: '林岚保持原样。', now: NOW }),
  ] });
  const original = (await repository.workspaceStatus(project.id)).cas;
  await creation.saveChapter(project.id, 'one', '林岚标题',
    replaceNovelChapterMatch('林岚看见林岚。', '林岚', '小林', null), original);
  let updated = await repository.loadProject(project.id);
  assert.equal(updated.chapters[0].content, '小林看见小林。');
  assert.equal(updated.chapters[0].title, '林岚标题');
  assert.equal(updated.chapters[1].content, '林岚保持原样。');
  assert.equal(updated.chapterVersions[0].content, '林岚看见林岚。');
  assert.equal(updated.chapterVersions[0].kind, 'manual');
  await assert.rejects(creation.saveChapter(project.id, 'one', '旧标题', '旧编辑覆盖', original), /工作区已变化，拒绝覆盖/);
  updated = await repository.loadProject(project.id);
  assert.equal(updated.chapters[0].content, '小林看见小林。');
  assert.equal(updated.chapterVersions.length, 1);
});
