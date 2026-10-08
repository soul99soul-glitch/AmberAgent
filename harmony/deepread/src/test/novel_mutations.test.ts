import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  createProject, saveChapter, deleteChapter, collect, replacePendingSuggestions, appendMessage,
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

test('createProject: blank name throws NovelError', () => {
  try {
    createProject('   ', NOW);
    assert.fail('should throw');
  } catch (e) {
    assert.ok(isNovelError(e));
  }
});

test('saveChapter: blank title throws', () => {
  assert.throws(() => saveChapter(base(), null, '  ', 'x', NOW));
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

test('collect editedContent: 收录前编辑稿覆盖消息原文', () => {
  let p = base();
  const msg = assistantWrite('原文第一段\n\n原文第二段');
  p = appendMessage(p, msg, NOW);
  const res = collect(p, msg.id, { kind: 'new_chapter', title: '第二章' }, NOW + 1, '只要第一段');
  assert.equal(res.value.content, '只要第一段');
  // 空编辑稿回落原文(段落全不选时不应为空章节)
  let p2 = base();
  const msg2 = assistantWrite('原文');
  p2 = appendMessage(p2, msg2, NOW);
  assert.throws(() => collect(p2, msg2.id, { kind: 'new_chapter', title: 't' }, NOW, '  '));
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
