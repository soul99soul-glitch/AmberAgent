import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  makeNovelProject, makeNovelChapter, makeNovelMaterial, makeNovelSuggestion,
  makeNovelSettingProposal, makeNovelMessage,
} from '../main/ets/novel/models.ts';
import type { NovelProject } from '../main/ets/novel/models.ts';
import { resolveSuggestion, resolveSettingProposal, upsertMaterial } from '../main/ets/novel/mutations.ts';
import {
  buildSuggestionAdoption, buildSettingProposalAdoption, materialSuggestionChapterDigest,
} from '../main/ets/novel/material_adoption.ts';
import { isNovelError } from '../main/ets/novel/error.ts';

const NOW = 1000;
const fixture = (): NovelProject => {
  const chapter = makeNovelChapter({ title: '第一章', content: '林舟在城门外等候。', now: NOW });
  return {
    ...makeNovelProject({ name: '长夜', now: NOW }),
    chapters: [chapter],
    materials: [makeNovelMaterial({ kind: 'character', title: '林舟', content: '作者原始设定', enabled: false, now: NOW })],
    materialSuggestions: [makeNovelSuggestion({
      sourceChapterId: chapter.id, sourceDigest: materialSuggestionChapterDigest(chapter),
      kind: 'character', title: '林舟', content: '候选设定', now: NOW,
    })],
    settingProposals: [makeNovelSettingProposal({
      sourceMessageId: 'discussion-source', kind: 'character', title: '林舟', content: '讨论提案', now: NOW,
    })],
  };
};

test('same-title suggestions and proposals default to creating without overwriting author material', () => {
  const project = fixture();
  const suggestionResult = resolveSuggestion(project, project.materialSuggestions[0].id, true, NOW + 1);
  const proposalResult = resolveSettingProposal(project, project.settingProposals[0].id, true, NOW + 1);
  for (const result of [suggestionResult, proposalResult]) {
    assert.equal(result.project.materials.length, 2);
    assert.equal(result.project.materials[0].content, '作者原始设定');
    assert.notEqual(result.value?.id, project.materials[0].id);
    assert.equal(result.value?.enabled, true);
  }
});

test('explicit update adopts edited kind, title and complete body while preserving target enabled state', () => {
  const project = fixture();
  const suggestion = project.materialSuggestions[0];
  const edit = {
    ...buildSuggestionAdoption(project, suggestion, project.materials[0].id),
    kind: 'requirement' as const, title: '作者确认的要求', content: '作者编辑后完整内容',
  };
  const result = resolveSuggestion(project, suggestion.id, true, NOW + 1, edit);
  assert.equal(result.project.materials.length, 1);
  assert.equal(result.value?.id, project.materials[0].id);
  assert.equal(result.value?.kind, 'requirement');
  assert.equal(result.value?.title, '作者确认的要求');
  assert.equal(result.value?.content, '作者编辑后完整内容');
  assert.equal(result.value?.enabled, false);
  assert.equal(result.project.materialSuggestions[0].status, 'accepted');
});

test('explicit new proposal adopts edited fields rather than original model fields', () => {
  const project = fixture();
  const proposal = project.settingProposals[0];
  const edit = { ...buildSettingProposalAdoption(project, proposal), title: '新人物', content: '编辑后的提案' };
  const result = resolveSettingProposal(project, proposal.id, true, NOW + 1, edit);
  assert.equal(result.value?.title, '新人物');
  assert.equal(result.value?.content, '编辑后的提案');
  assert.equal(result.project.materials[0].content, '作者原始设定');
});

test('target changes after preview reject the old adoption request without overwriting', () => {
  const project = fixture();
  const proposal = project.settingProposals[0];
  const edit = buildSettingProposalAdoption(project, proposal, project.materials[0].id);
  const changed = upsertMaterial(project, project.materials[0].id, 'character', '林舟', '新作者设定', true, NOW + 1).project;
  assert.throws(() => resolveSettingProposal(changed, proposal.id, true, NOW + 2, edit),
    error => isNovelError(error) && error.message.includes('目标资料已变更'));
  assert.equal(changed.materials[0].content, '新作者设定');
  assert.equal(changed.settingProposals[0].status, 'pending');
});

test('source chapter changes after analysis reject even a freshly reopened old suggestion', () => {
  const project = fixture();
  const changed = { ...project, chapters: [{ ...project.chapters[0], content: '正文已改写' }] };
  assert.throws(() => buildSuggestionAdoption(changed, changed.materialSuggestions[0]),
    error => isNovelError(error) && error.message.includes('来源章节已变更'));
  assert.throws(() => resolveSuggestion(changed, changed.materialSuggestions[0].id, true, NOW + 1),
    error => isNovelError(error) && error.message.includes('来源章节已变更'));
});

test('proposal content or its source message changing after preview reject the stale request', () => {
  let project = fixture();
  project = {
    ...project,
    messages: [makeNovelMessage({ id: 'discussion-source', role: 'assistant', mode: 'discuss', content: '原讨论', createdAt: NOW })],
  };
  const proposal = project.settingProposals[0];
  const edit = buildSettingProposalAdoption(project, proposal);
  const changedProposal = { ...project, settingProposals: [{ ...proposal, content: '另一提案' }] };
  const changedMessage = {
    ...project,
    messages: [makeNovelMessage({ id: 'discussion-source', role: 'assistant', mode: 'discuss', content: '修改后讨论', createdAt: NOW })],
  };
  for (const changed of [changedProposal, changedMessage]) {
    assert.throws(() => resolveSettingProposal(changed, proposal.id, true, NOW + 1, edit),
      error => isNovelError(error) && error.message.includes('来源已变更'));
  }
});

test('rejecting a stale suggestion remains possible and changes no material', () => {
  const project = fixture();
  const changed = { ...project, chapters: [{ ...project.chapters[0], content: '正文已改写' }] };
  const result = resolveSuggestion(changed, project.materialSuggestions[0].id, false, NOW + 1);
  assert.equal(result.value, null);
  assert.equal(result.project.materialSuggestions[0].status, 'rejected');
  assert.deepEqual(result.project.materials, project.materials);
});
