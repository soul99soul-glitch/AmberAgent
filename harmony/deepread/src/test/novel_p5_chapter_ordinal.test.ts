import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { makeNovelProject, makeNovelMessage, novelChapterOrdinal } from '../main/ets/novel/models.ts';
import { saveChapter, collect, deleteChapter } from '../main/ets/novel/mutations.ts';
import { buildNovelWorkspaceImportPlan, buildNovelWorkspacePublicFiles } from '../main/ets/novel/workspace_interop.ts';
import { parseNovelWorkspaceManifest } from '../main/ets/novel/workspace_contract.ts';
import type { NovelWorkspaceArchiveFile } from '../main/ets/novel/workspace_exchange.ts';
import type { NovelChapter } from '../main/ets/novel/models.ts';
const chapter = (id: string, ordinal?: number): NovelChapter => ({ id, ordinal, title: id, content: '正文',
  createdAt: 1, updatedAt: 1, discarded: false });
const importPlan = (files: NovelWorkspaceArchiveFile[]) => {
  const text = (path: string) => new TextDecoder().decode(files.find(file => file.path === path)!.bytes);
  return buildNovelWorkspaceImportPlan(parseNovelWorkspaceManifest(text('manifest.yaml'), text('project.md')), files);
};
const fixture = () => {
  const raw = [
    ['manifest.yaml', 'format: amber.novel.workspace\nformatVersion: 1\nmainBranch: Main\nsource:\n  projectID: project-seven\n'],
    ['project.md', '---\nid: project-seven\nkind: project\ntitle: 七章导入\n---\n\n'],
    ['branches/Main/branch.md', '---\nid: branch-main\nkind: branch\ntitle: Main\nsyncStatus: synchronized\n---\n\n'],
    ['branches/Main/chapters/007-第七章.md', '---\nid: uuid-seven\nkind: chapter\nordinal: 7\ntitle: 第七章\n---\n\n第七章正文'],
  ];
  return raw.map(([path, text]) => ({ path, bytes: new TextEncoder().encode(text) }));
};

test('imported UUID ordinal7 followed by manual and collected chapters retains 7,8,9 through real repository export/import', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const p = await repository.installWorkspacePlan(importPlan(fixture()));
  let created: NovelChapter | null = null;
  await repository.updateProject(p.id, current => {
    const result = saveChapter(current, null, '第八章', '第八章正文', 2);
    created = result.value; return result.project;
  });
  assert.equal(created!.ordinal, 8);
  const source = makeNovelMessage({ role: 'assistant', mode: 'write', content: '第九章正文', createdAt: 3 });
  await repository.updateProject(p.id, current => ({ ...current, messages: current.messages.concat([source]) }));
  const status = await repository.workspaceStatus(p.id);
  await repository.commitProject(p.id, status.cas, 'collect-nine', 'collect',
    current => collect(current, source.id, { kind: 'new_chapter', title: '第九章' }, 4).project);
  const publicFiles = buildNovelWorkspacePublicFiles(await repository.publicExportPlan(p.id));
  const restoredRepository = createFileNovelRepository(createMemoryFileStore());
  const restored = await restoredRepository.installWorkspacePlan(importPlan(publicFiles));
  assert.deepEqual(restored.chapters.map((item, index) => [item.title, novelChapterOrdinal(item, index + 1)]),
    [['第七章', 7], ['第八章', 8], ['第九章', 9]]);
  assert.deepEqual(restored.chapters.map(item => item.content), ['第七章正文', '第八章正文', '第九章正文']);
});

test('deleting a middle chapter leaves surviving ordinals unchanged and both creation paths append after max', () => {
  const p = { ...makeNovelProject({ name: '删除', now: 1 }), chapters: [chapter('c7', 7), chapter('c8', 8), chapter('c9', 9)] };
  const removed = deleteChapter(p, 'c8', 2);
  const manual = saveChapter(removed, null, '第十章', '十', 3);
  assert.deepEqual(manual.project.chapters.map((item, index) => novelChapterOrdinal(item, index + 1)), [7, 9, 10]);
  const source = makeNovelMessage({ role: 'assistant', mode: 'write', content: '十', createdAt: 3 });
  const collected = collect({ ...removed, messages: [source] }, source.id, { kind: 'new_chapter', title: '第十章' }, 4);
  assert.equal(collected.value.ordinal, 10);
});

test('legacy ID ordinals and legacy positional chapters remain compatible; discarded ordinals are still occupied', () => {
  const p = { ...makeNovelProject({ name: '旧格式', now: 1 }), chapters: [chapter('old-uuid'), chapter('chapter-007-first')] };
  assert.equal(saveChapter(p, null, '八', '正文', 2).value.ordinal, 8);
  assert.equal(saveChapter({ ...p, chapters: [chapter('a'), chapter('b')] }, null, '三', '正文', 2).value.ordinal, 3);
  assert.equal(saveChapter({ ...p, chapters: [{ ...chapter('discarded', 9), discarded: true }] }, null, '十', '正文', 2).value.ordinal, 10);
});

test('deleting legacy implicit middle chapters freezes surviving positional ordinals', () => {
  const p = { ...makeNovelProject({ name: '旧章删除', now: 1 }), chapters: [chapter('a'), chapter('b'), chapter('c')] };
  const removed = deleteChapter(p, 'b', 2);
  assert.deepEqual(removed.chapters.map((item, index) => novelChapterOrdinal(item, index + 1)), [1, 3]);
  assert.equal(saveChapter(removed, null, '四', '正文', 3).value.ordinal, 4);
});

test('both new chapter paths reject ordinal overflow before mutating existing project', () => {
  const p = { ...makeNovelProject({ name: '上限', now: 1 }), chapters: [chapter('last', 999)] };
  assert.throws(() => saveChapter(p, null, '下一章', '正文', 2), /999/);
  const source = makeNovelMessage({ role: 'assistant', mode: 'write', content: '正文', createdAt: 3 });
  assert.throws(() => collect({ ...p, messages: [source] }, source.id, { kind: 'new_chapter', title: '下一章' }, 4), /999/);
  assert.equal(p.chapters.length, 1);
});
