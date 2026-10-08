import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createNovelCreation } from '../main/ets/novel/creation.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { makeNovelSettingProposal, makeNovelSuggestion, makeNovelChapter, makeNovelMessage } from '../main/ets/novel/models.ts';
import { buildSettingProposalAdoption, buildSuggestionAdoption, materialSuggestionChapterDigest } from '../main/ets/novel/material_adoption.ts';
import { makeAssistantMessage } from '../main/ets/agent/message.ts';
import type { NovelModelEvent } from '../main/ets/novel/model_running.ts';
import type { NovelModelRunning } from '../main/ets/novel/model_running.ts';

const unusedModel: NovelModelRunning = {
  async validate() {}, start() { throw new Error('建议服务测试失败'); }, cancel() {},
};

for (const isSetting of [true, false]) {
  test(`${isSetting ? 'setting' : 'suggestion'} preview from original branch cannot adopt into identical fork`, async () => {
    const repository = createFileNovelRepository(createMemoryFileStore());
    const creation = createNovelCreation({ repository, modelRunning: unusedModel });
    const project = await creation.create('分支预览');
    const chapter = makeNovelChapter({ title: '第一章', content: '来源正文', now: 1 });
    const proposal = makeNovelSettingProposal({ sourceMessageId: 'source', kind: 'world', title: '城市', content: '原提案', now: 1 });
    const suggestion = makeNovelSuggestion({ sourceChapterId: chapter.id, sourceDigest: materialSuggestionChapterDigest(chapter), kind: 'world', title: '城市', content: '原建议', now: 1 });
    await repository.updateProject(project.id, p => ({ ...p, chapters: [chapter], settingProposals: [proposal], materialSuggestions: [suggestion] }));
    const original = await repository.workspaceStatus(project.id);
    const loaded = await creation.open(project.id);
    const preview = isSetting ? buildSettingProposalAdoption(loaded, proposal, null, original.activeBranchId)
      : buildSuggestionAdoption(loaded, suggestion, null, original.activeBranchId);
    await repository.createBranch(project.id, 'fork', original.cas, 'review-fork');
    const resolve = (edit = preview) => isSetting ? creation.resolveSettingProposal(project.id, proposal.id, true, edit)
      : creation.resolveMaterialSuggestion(project.id, suggestion.id, true, edit);
    await assert.rejects(resolve(), /预览不属于当前分支/);
    assert.equal((await creation.open(project.id)).materials.length, 0);
    await assert.rejects(resolve({ ...preview, branchId: null }), /预览不属于当前分支/);
    const fork = await repository.workspaceStatus(project.id);
    const fresh = { ...preview, branchId: fork.activeBranchId, content: '重新确认的内容' };
    assert.equal((await resolve(fresh))?.content, '重新确认的内容');
    await creation.switchBranch(project.id, original.activeBranchId);
    assert.equal((await creation.open(project.id)).materials.length, 0);
  });
}

test('collection returns one-shot analysis failure after chapter warning has been persisted', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const creation = createNovelCreation({ repository, modelRunning: unusedModel });
  const project = await creation.create('后台分析通知');
  const candidate = makeNovelMessage({ role: 'assistant', mode: 'write', content: '已确认正文', createdAt: 1 });
  await repository.updateProject(project.id, p => ({ ...p, messages: [candidate] }));
  const collected = await creation.collectMessage(project.id, candidate.id, { kind: 'new_chapter', title: '第一章' });
  assert.equal(collected.chapter.content, '已确认正文');
  await assert.rejects(collected.analysisFinished, /建议服务测试失败/);
  const loaded = await creation.open(project.id);
  assert.match(loaded.chapters[0].suggestionWarning ?? '', /建议服务测试失败/);
  assert.equal(loaded.messages[0].collectedChapterId, collected.chapter.id);
  assert.equal('analysisFinished' in loaded, false);
});

test('analysis notification resolves only after generated suggestions have been persisted', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const model: NovelModelRunning = {
    async validate() {}, cancel() {},
    start() {
      const listeners = new Set<(event: NovelModelEvent) => void>();
      setTimeout(() => {
        const messages = [makeAssistantMessage('{"suggestions":[{"kind":"world","title":"城门","content":"暮色中关闭"}]}')];
        for (const callback of listeners) callback({ kind: 'snapshot', messages, generationActive: false, textDeltasLive: false, transport: 'live' });
        for (const callback of listeners) callback({ kind: 'completed' });
      }, 20);
      return { subscribe(callback: (event: NovelModelEvent) => void) { listeners.add(callback); return () => { listeners.delete(callback); }; } };
    },
  };
  const creation = createNovelCreation({ repository, modelRunning: model });
  const project = await creation.create('后台建议通知');
  const candidate = makeNovelMessage({ role: 'assistant', mode: 'write', content: '城门在暮色中关闭。', createdAt: 1 });
  await repository.updateProject(project.id, p => ({ ...p, messages: [candidate] }));
  const collected = await creation.collectMessage(project.id, candidate.id, { kind: 'new_chapter', title: '第一章' });
  assert.equal((await creation.open(project.id)).materialSuggestions.length, 0);
  assert.equal((await collected.analysisFinished).count, 1);
  const loaded = await creation.open(project.id);
  assert.equal(loaded.materialSuggestions[0].title, '城门');
  assert.equal(loaded.chapters[0].suggestionWarning, null);
});
