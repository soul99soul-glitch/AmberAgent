import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  createProject, renameProject, saveChapter, deleteChapter, upsertMaterial, deleteMaterial,
  collect, replacePendingSuggestions, resolveSuggestion, normalizeTitle, appendMessage,
  resolveSettingProposal,
} from '../main/ets/novel/mutations.ts';
import {
  makeNovelProject, makeNovelMessage, makeNovelSuggestion,
} from '../main/ets/novel/models.ts';
import type {
  NovelProject, NovelCollectionTarget, NovelSettingProposal,
} from '../main/ets/novel/models.ts';
import { isNovelError } from '../main/ets/novel/error.ts';

const NOW = 1_000_000;
const base = (): NovelProject => makeNovelProject({ name: '测试小说', now: NOW });

const historicalSettingProposal = (
  kind: NovelSettingProposal['kind'], title: string, content: string,
): NovelSettingProposal => ({
  id: 'legacy-setting-proposal-1',
  sourceMessageId: 'assistant-1',
  kind: kind,
  title: title,
  content: content,
  status: 'pending',
  createdAt: NOW,
  resolvedAt: null,
});

const assistantWrite = (content: string, over: Partial<ReturnType<typeof makeNovelMessage>> = {}) =>
  makeNovelMessage({ role: 'assistant', mode: 'write', content: content, createdAt: NOW, ...over });

test('createProject: trims name and sets schema', () => {
  const p = createProject('  我的小说  ', NOW);
  assert.equal(p.name, '我的小说');
  assert.equal(p.schemaVersion, 4);
});

test('createProject: blank name throws NovelError', () => {
  try {
    createProject('   ', NOW);
    assert.fail('should throw');
  } catch (e) {
    assert.ok(isNovelError(e));
  }
});

test('renameProject: updates name and updatedAt', () => {
  const p = renameProject(base(), '新名字', NOW + 10);
  assert.equal(p.name, '新名字');
  assert.equal(p.updatedAt, NOW + 10);
});

test('saveChapter: new chapter created with value', () => {
  const m = saveChapter(base(), null, '第一章', '正文内容', NOW);
  assert.equal(m.value.title, '第一章');
  assert.equal(m.value.content, '正文内容');
  assert.equal(m.project.chapters.length, 1);
});

test('saveChapter: blank title throws', () => {
  assert.throws(() => saveChapter(base(), null, '  ', 'x', NOW));
});

test('saveChapter: update existing; missing throws', () => {
  const created = saveChapter(base(), null, '第一章', 'v1', NOW);
  const id = created.value.id;
  const updated = saveChapter(created.project, id, '第一章改', 'v2', NOW + 1);
  assert.equal(updated.project.chapters.length, 1);
  assert.equal(updated.value.content, 'v2');
  assert.throws(() => saveChapter(base(), 'ghost', 't', 'x', NOW));
});

test('deleteChapter: nulls collectedChapterId and drops its suggestions', () => {
  let p = base();
  const c = saveChapter(p, null, '第一章', '正文', NOW);
  p = c.project;
  const cid = c.value.id;
  // 一条已收录消息 + 一个该章建议
  const msg = assistantWrite('回复', { collectedChapterId: cid });
  p = appendMessage(p, msg, NOW);
  const sug = makeNovelSuggestion({ sourceChapterId: cid, kind: 'character', title: '主角', content: 'x', now: NOW });
  p = replacePendingSuggestions(p, cid, [sug], NOW);
  assert.equal(p.materialSuggestions.length, 1);

  const after = deleteChapter(p, cid, NOW + 1);
  assert.equal(after.chapters.length, 0);
  assert.equal(after.materialSuggestions.length, 0);
  assert.equal(after.messages[0].collectedChapterId, null);
});

test('upsertMaterial: create then update', () => {
  const created = upsertMaterial(base(), null, 'world', '世界观', '内容A', true, NOW);
  assert.equal(created.project.materials.length, 1);
  const id = created.value.id;
  const updated = upsertMaterial(created.project, id, 'world', '世界观', '内容B', false, NOW + 1);
  assert.equal(updated.project.materials.length, 1);
  assert.equal(updated.value.content, '内容B');
  assert.equal(updated.value.enabled, false);
});

test('deleteMaterial: removes; missing throws', () => {
  const created = upsertMaterial(base(), null, 'character', '主角', 'x', true, NOW);
  const after = deleteMaterial(created.project, created.value.id, NOW + 1);
  assert.equal(after.materials.length, 0);
  assert.throws(() => deleteMaterial(base(), 'ghost', NOW));
});

test('collect append: joins with blank line and marks message', () => {
  let p = base();
  const c = saveChapter(p, null, '第一章', '已有正文', NOW);
  p = c.project;
  const msg = assistantWrite('新段落');
  p = appendMessage(p, msg, NOW);
  const target: NovelCollectionTarget = { kind: 'append', chapterId: c.value.id };
  const res = collect(p, msg.id, target, NOW + 1);
  assert.equal(res.value.content, '已有正文\n\n新段落');
  const collected = res.project.messages.find(m => m.id === msg.id);
  assert.equal(collected?.collectedChapterId, c.value.id);
});

test('collect replace: overwrites chapter content', () => {
  let p = base();
  const c = saveChapter(p, null, '第一章', '旧', NOW);
  p = c.project;
  const msg = assistantWrite('全新');
  p = appendMessage(p, msg, NOW);
  const res = collect(p, msg.id, { kind: 'replace', chapterId: c.value.id }, NOW + 1);
  assert.equal(res.value.content, '全新');
});

test('collect new_chapter: creates chapter', () => {
  let p = base();
  const msg = assistantWrite('整章内容');
  p = appendMessage(p, msg, NOW);
  const before = p.chapters.length;
  const res = collect(p, msg.id, { kind: 'new_chapter', title: '第二章' }, NOW + 1);
  assert.equal(res.project.chapters.length, before + 1);
  assert.equal(res.value.title, '第二章');
});

test('collect: rejects non-write / already-collected / blank', () => {
  let p = base();
  const discussMsg = makeNovelMessage({ role: 'assistant', mode: 'discuss', content: 'x', createdAt: NOW });
  p = appendMessage(p, discussMsg, NOW);
  assert.throws(() => collect(p, discussMsg.id, { kind: 'new_chapter', title: 't' }, NOW));

  let p2 = base();
  const c = saveChapter(p2, null, '第一章', 'x', NOW);
  p2 = c.project;
  const already = assistantWrite('y', { collectedChapterId: c.value.id });
  p2 = appendMessage(p2, already, NOW);
  assert.throws(() => collect(p2, already.id, { kind: 'append', chapterId: c.value.id }, NOW));
});

test('replacePendingSuggestions: keeps other chapters and non-pending', () => {
  let p = base();
  const c1 = saveChapter(p, null, '第一章', 'x', NOW);
  p = c1.project;
  const c2 = saveChapter(p, null, '第二章', 'y', NOW);
  p = c2.project;
  const s1 = makeNovelSuggestion({ sourceChapterId: c1.value.id, kind: 'world', title: 'w', content: 'x', now: NOW });
  const s2 = makeNovelSuggestion({ sourceChapterId: c2.value.id, kind: 'world', title: 'w2', content: 'y', now: NOW });
  p = replacePendingSuggestions(p, c1.value.id, [s1], NOW);
  p = replacePendingSuggestions(p, c2.value.id, [s2], NOW);
  assert.equal(p.materialSuggestions.length, 2);
  // 替换 c1 的 pending
  const s1b = makeNovelSuggestion({ sourceChapterId: c1.value.id, kind: 'character', title: 'c', content: 'z', now: NOW });
  p = replacePendingSuggestions(p, c1.value.id, [s1b], NOW);
  assert.equal(p.materialSuggestions.length, 2);
  assert.ok(p.materialSuggestions.some(s => s.id === s1b.id));
  assert.ok(!p.materialSuggestions.some(s => s.id === s1.id));
});

test('normalizeTitle: lowercase + strip whitespace', () => {
  assert.equal(normalizeTitle(' 主角 A '), '主角a');
});

test('resolveSuggestion accept: updates existing material by kind+title', () => {
  let p = base();
  const mat = upsertMaterial(p, null, 'character', '主角', '旧设定', true, NOW);
  p = mat.project;
  const c = saveChapter(p, null, '第一章', 'x', NOW);
  p = c.project;
  const sug = makeNovelSuggestion({ sourceChapterId: c.value.id, kind: 'character', title: '主角', content: '新设定', now: NOW });
  p = replacePendingSuggestions(p, c.value.id, [sug], NOW);
  const res = resolveSuggestion(p, sug.id, true, NOW + 1);
  assert.ok(res.value !== null);
  assert.equal(res.value?.content, '新设定');
  assert.equal(res.project.materials.length, 1, 'updated in place, not duplicated');
  const resolved = res.project.materialSuggestions.find(s => s.id === sug.id);
  assert.equal(resolved?.status, 'accepted');
});

test('resolveSuggestion accept: creates material when no match', () => {
  let p = base();
  const c = saveChapter(p, null, '第一章', 'x', NOW);
  p = c.project;
  const sug = makeNovelSuggestion({ sourceChapterId: c.value.id, kind: 'world', title: '新世界', content: '设定', now: NOW });
  p = replacePendingSuggestions(p, c.value.id, [sug], NOW);
  const res = resolveSuggestion(p, sug.id, true, NOW + 1);
  assert.equal(res.project.materials.length, 1);
  assert.equal(res.value?.title, '新世界');
});

test('resolveSuggestion reject: no material, status rejected', () => {
  let p = base();
  const c = saveChapter(p, null, '第一章', 'x', NOW);
  p = c.project;
  const sug = makeNovelSuggestion({ sourceChapterId: c.value.id, kind: 'world', title: 'w', content: 'x', now: NOW });
  p = replacePendingSuggestions(p, c.value.id, [sug], NOW);
  const res = resolveSuggestion(p, sug.id, false, NOW + 1);
  assert.equal(res.value, null);
  assert.equal(res.project.materials.length, 0);
  assert.equal(res.project.materialSuggestions.find(s => s.id === sug.id)?.status, 'rejected');
});

test('historical setting proposal accepts and upserts material by kind+normalized title', () => {
  let project = upsertMaterial(base(), null, 'character', '主 角', '旧设定', true, NOW).project;
  project = {
    ...project,
    settingProposals: [historicalSettingProposal('character', ' 主角 ', '新设定')],
  };
  assert.equal(project.settingProposals.length, 1);
  assert.equal(project.settingProposals[0].sourceMessageId, 'assistant-1');
  assert.equal(project.settingProposals[0].status, 'pending');

  const resolved = resolveSettingProposal(project, project.settingProposals[0].id, true, NOW + 2);
  assert.equal(resolved.project.materials.length, 1);
  assert.equal(resolved.value?.content, '新设定');
  assert.equal(resolved.project.settingProposals[0].status, 'accepted');
  assert.equal(resolved.project.settingProposals[0].resolvedAt, NOW + 2);
});

test('reject historical setting proposal only resolves proposal', () => {
  const project: NovelProject = {
    ...base(),
    settingProposals: [historicalSettingProposal('world', '灵脉', '贯穿大陆')],
  };
  const resolved = resolveSettingProposal(project, project.settingProposals[0].id, false, NOW + 1);
  assert.equal(resolved.value, null);
  assert.equal(resolved.project.materials.length, 0);
  assert.equal(resolved.project.settingProposals[0].status, 'rejected');
  assert.equal(resolved.project.settingProposals[0].resolvedAt, NOW + 1);
});
