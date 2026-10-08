import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createNovelCreation } from '../main/ets/novel/creation.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { makeNovelMessage } from '../main/ets/novel/models.ts';
import { eligibleNovelDiscussion } from '../main/ets/novel/discussion_archive.ts';
import { makeAssistantMessage } from '../main/ets/agent/message.ts';
import type { NovelModelRunning, NovelModelEvent, NovelModelRequest } from '../main/ets/novel/model_running.ts';

const setup = (output = '{"summary":"守卫持有铜牌；作者仍未决定结局。","decisions":["铜牌属于守卫","结局一定悲剧"]}', inputBudget?: number) => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const requests: NovelModelRequest[] = [];
  const model: NovelModelRunning = {
    async validate() {},
    start(request) {
      requests.push(request);
      if (inputBudget !== undefined && request.operation.kind === 'turn') {
        assert.ok(Math.floor((request.systemPrompt.length + request.operation.userPrompt.length) / 4) <= inputBudget,
          '归档来源超出发送端模型预算');
      }
      const subscribers = new Set<(event: NovelModelEvent) => void>();
      setTimeout(() => {
        subscribers.forEach(cb => cb({ kind: 'snapshot', messages: [makeAssistantMessage(output)],
          generationActive: false, textDeltasLive: false, transport: 'live' }));
        subscribers.forEach(cb => cb({ kind: 'completed' }));
      }, 0);
      return { subscribe(cb: (event: NovelModelEvent) => void) {
        subscribers.add(cb); return () => { subscribers.delete(cb); };
      } };
    },
    cancel() {},
  };
  if (inputBudget !== undefined) {
    model.inputBudgetTokens = async (_target, _projectId, outputLimit) => {
      assert.equal(outputLimit, 3072, '预估与实际归档输出上限必须相同');
      return inputBudget;
    };
    model.estimateInputTokens = (system, user) => Math.floor((system.length + user.length) / 4);
  }
  return { repository, requests, creation: createNovelCreation({ repository, modelRunning: model }) };
};

test('archive draft changes nothing until author confirms summary and selects decisions', async () => {
  const { creation, repository, requests } = setup();
  const project = await creation.create('确认归档');
  const user = makeNovelMessage({ role: 'user', mode: 'discuss', content: '铜牌归守卫，结局再想想', createdAt: 1 });
  const assistant = makeNovelMessage({ role: 'assistant', mode: 'discuss', content: '结局也可以悲剧', createdAt: 2 });
  await repository.updateProject(project.id, current => ({ ...current, messages: [user, assistant] }));
  const draft = await creation.prepareDiscussionArchive(project.id, [user.id, assistant.id]);
  assert.equal(requests[0].toolProfile, 'none');
  assert.deepEqual(requests[0].modelTarget, { kind: 'global' }, 'null stateSync应跟随writing');
  const before = await creation.open(project.id);
  assert.equal(before.discussionArchives.length, 0);
  assert.equal(before.branchSettings.confirmedDecisions.length, 0);
  await creation.confirmDiscussionArchive(project.id, draft, '铜牌属于守卫；结局未定。', [0]);
  const saved = await creation.open(project.id);
  assert.equal(saved.messages.length, 2, 'canonical完整历史保留');
  assert.equal(saved.discussionArchives.length, 1);
  assert.equal(saved.discussionArchives[0].summary, '铜牌属于守卫；结局未定。');
  assert.equal(saved.discussionArchives[0].throughMessageId, assistant.id);
  assert.deepEqual(saved.branchSettings.confirmedDecisions.map(item => item.content), ['铜牌属于守卫']);
  await assert.rejects(creation.confirmDiscussionArchive(project.id, draft, draft.summary, []), /归档|范围/);
});

test('archive scope binds branch and source message content rather than transcript revision', async () => {
  const { creation, repository } = setup();
  const project = await creation.create('归档来源');
  const message = makeNovelMessage({ role: 'user', mode: 'discuss', content: '守卫持有铜牌', createdAt: 1 });
  await repository.updateProject(project.id, current => ({ ...current, messages: [message] }));
  const draft = await creation.prepareDiscussionArchive(project.id, [message.id]);
  const status = await repository.workspaceStatus(project.id);
  await repository.createBranch(project.id, '另一线', status.cas, 'archive-fork');
  await assert.rejects(creation.confirmDiscussionArchive(project.id, draft, draft.summary, []), /分支/);
  const fork = await repository.workspaceStatus(project.id);
  await repository.switchBranch(project.id, draft.branchId, fork.cas);
  await repository.updateProject(project.id, current => ({ ...current, messages: [
    makeNovelMessage({ id: message.id, uiMessage: { ...message.uiMessage,
      parts: [{ type: 'text', text: '作者已经改变这条讨论', metadata: null }] },
      role: message.role, mode: message.mode, createdAt: message.createdAt }),
  ] }));
  await assert.rejects(creation.confirmDiscussionArchive(project.id, draft, draft.summary, []), /来源|讨论.*变更/);
});

test('malformed archive output fails and leaves cursor and decisions untouched', async () => {
  const { creation, repository } = setup('not json');
  const project = await creation.create('失败归档');
  const message = makeNovelMessage({ role: 'user', mode: 'discuss', content: '再讨论', createdAt: 1 });
  await repository.updateProject(project.id, current => ({ ...current, messages: [message] }));
  await assert.rejects(creation.prepareDiscussionArchive(project.id, [message.id]), /JSON|摘要/);
  assert.equal((await creation.open(project.id)).discussionArchives.length, 0);
});

test('long discussion archives complete earliest sources within the model budget then continues with cumulative context', async () => {
  const { creation, repository, requests } = setup(undefined, 13312);
  const project = await creation.create('长讨论分批归档');
  const messages = Array.from({ length: 12 }, (_, index) => makeNovelMessage({
    role: index % 2 === 0 ? 'user' : 'assistant', mode: 'discuss',
    content: `消息${index}：${String.fromCharCode(65 + index).repeat(7990)}`, createdAt: index + 1,
  }));
  await repository.updateProject(project.id, current => ({ ...current, messages }));
  const first = await creation.prepareDiscussionArchive(project.id, messages.map(message => message.id));
  assert.ok(first.sourceMessageIds.length > 0 && first.sourceMessageIds.length < messages.length);
  assert.deepEqual(first.sourceMessageIds, messages.slice(0, first.sourceMessageIds.length).map(message => message.id));
  assert.equal(requests[0].operation.kind, 'turn');
  if (requests[0].operation.kind !== 'turn') throw new Error('归档必须使用普通文本任务');
  for (const source of messages.slice(0, first.sourceMessageIds.length)) {
    assert.ok(requests[0].operation.userPrompt.includes(source.content), '每条采用来源须完整，不切正文');
  }
  const confirmedSummary = '作者编辑的累计摘要：守卫持有铜牌，结局仍未定。';
  await creation.confirmDiscussionArchive(project.id, first, confirmedSummary, []);
  const saved = await creation.open(project.id);
  assert.deepEqual(saved.messages, messages, '归档后保留完整 canonical 原文');
  const remaining = eligibleNovelDiscussion(saved);
  assert.deepEqual(remaining.map(message => message.id), messages.slice(first.sourceMessageIds.length).map(message => message.id));
  const second = await creation.prepareDiscussionArchive(project.id, remaining.map(message => message.id));
  assert.deepEqual(second.sourceMessageIds, remaining.map(message => message.id));
  assert.equal(requests[1].operation.kind, 'turn');
  if (requests[1].operation.kind !== 'turn') throw new Error('归档必须使用普通文本任务');
  assert.ok(requests[1].operation.userPrompt.includes(confirmedSummary), '下一批必须带上作者确认的累计摘要');
  await creation.confirmDiscussionArchive(project.id, second, second.summary, []);
  assert.equal(eligibleNovelDiscussion(await creation.open(project.id)).length, 0);
});

test('archive explains an earliest source that cannot fit without sending or advancing its cursor', async () => {
  const { creation, repository, requests } = setup(undefined, 13312);
  const project = await creation.create('单条过长讨论');
  const message = makeNovelMessage({ role: 'assistant', mode: 'discuss', content: '正文'.repeat(32000), createdAt: 1 });
  await repository.updateProject(project.id, current => ({ ...current, messages: [message] }));
  await assert.rejects(creation.prepareDiscussionArchive(project.id, [message.id]), /首条讨论.*(窗口|预算).*更大/);
  assert.equal(requests.length, 0, '无法容纳完整来源时不外呼模型');
  const saved = await creation.open(project.id);
  assert.deepEqual(saved.messages.map(item => item.id), [message.id]);
  assert.equal(saved.discussionArchives.length, 0);
});


test('manual archive accepts a complete JSON fence while still validating the archive schema', async () => {
  for (const valid of [true, false]) {
    const { creation, repository } = setup('```json\n' + JSON.stringify(valid
      ? { summary: '守卫持有铜牌。', decisions: ['铜牌属于守卫'] }
      : { summary: '守卫持有铜牌。', decisions: '铜牌属于守卫' }) + '\n```');
    const project = await creation.create('围栏归档');
    const message = makeNovelMessage({ role: 'user', mode: 'discuss', content: '铜牌归守卫', createdAt: 1 });
    await repository.updateProject(project.id, current => ({ ...current, messages: [message] }));
    if (valid) {
      const draft = await creation.prepareDiscussionArchive(project.id, [message.id]);
      assert.equal(draft.summary, '守卫持有铜牌。');
      assert.deepEqual(draft.decisions, ['铜牌属于守卫']);
    } else {
      await assert.rejects(creation.prepareDiscussionArchive(project.id, [message.id]), /决定列表/);
    }
    assert.equal((await creation.open(project.id)).discussionArchives.length, 0);
  }
});
