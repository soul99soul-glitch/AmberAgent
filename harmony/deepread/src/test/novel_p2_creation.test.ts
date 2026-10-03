import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createNovelCreation } from '../main/ets/novel/creation.ts';
import type { NovelRun, NovelRunEvent } from '../main/ets/novel/creation.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { makeNovelMessage } from '../main/ets/novel/models.ts';
import { makeAssistantMessage, makeUserMessage } from '../main/ets/agent/message.ts';
import type { NovelModelRunning, NovelModelRequest, NovelModelEvent } from '../main/ets/novel/model_running.ts';

const terminal = (run: NovelRun): Promise<NovelRunEvent> => new Promise(resolve => {
  run.subscribe(event => {
    if (event.kind === 'completed' || event.kind === 'failed' || event.kind === 'interrupted') resolve(event);
  });
});

const setup = (suggestionOutput = '{"suggestions":[]}') => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const requests: NovelModelRequest[] = [];
  const model: NovelModelRunning = {
    async validate() {},
    start(request) {
      requests.push(request);
      const subscribers = new Set<(event: NovelModelEvent) => void>();
      setTimeout(() => {
        void (async () => {
          const isSuggestion = request.systemPrompt.includes('资料整理助手');
          const text = isSuggestion ? suggestionOutput : '新正文';
          const messages = request.history.concat([
            makeUserMessage(request.operation.kind === 'turn' ? request.operation.userPrompt : ''),
            makeAssistantMessage(text),
          ]);
          await request.checkpoint(messages);
          subscribers.forEach(cb => cb({ kind: 'snapshot', messages, generationActive: false,
            textDeltasLive: false, transport: 'live' }));
          subscribers.forEach(cb => cb({ kind: 'completed' }));
        })().catch(error => subscribers.forEach(cb => cb({ kind: 'failed', message: String(error) })));
      }, 0);
      return { subscribe(cb: (event: NovelModelEvent) => void) {
        subscribers.add(cb); return () => { subscribers.delete(cb); };
      } };
    },
    cancel() {},
  };
  return { repository, requests, creation: createNovelCreation({ repository, modelRunning: model }) };
};

test('ordinary write, regeneration, polish and new durable jobs share the stale gate; discussion stays usable', async () => {
  const { creation, repository, requests } = setup();
  const project = await creation.create('前文变更');
  const chapter = await creation.saveChapter(project.id, null, '一', '初稿');
  await creation.saveChapter(project.id, null, '二', '后章');
  await creation.saveChapter(project.id, chapter.id, '一', '作者改动前文');
  assert.equal((await repository.workspaceStatus(project.id)).plotStale, true);
  const starts = [
    async () => creation.generate(project.id, '写', 'write'),
    async () => creation.polishChapter(project.id, chapter.id),
  ];
  for (const start of starts) {
    const event = await terminal(await start());
    assert.equal(event.kind, 'failed');
    if (event.kind === 'failed') assert.match(event.message, /同步剧情|后续章节/);
  }
  await assert.rejects(creation.regenerateChapter(project.id, chapter.id), /同步剧情|后续章节/);
  await assert.rejects(creation.startGhostwrite(project.id, 1), /同步剧情|后续章节/);
  await assert.rejects(creation.startPolish(project.id, 1, 1,
    { includePlot: false, includeForeshadows: false, includeCharacters: false, includeDecisions: false }), /同步剧情|后续章节/);
  assert.equal(requests.length, 0, 'stale branches fail before a provider request');
  assert.equal((await terminal(creation.generate(project.id, '讨论改动', 'discuss'))).kind, 'completed');
  await creation.syncPlot(project.id);
  assert.equal((await terminal(creation.generate(project.id, '现在续写', 'write'))).kind, 'completed');
});

test('ordinary requests expose domain context while retaining canonical archived messages', async () => {
  const { creation, repository, requests } = setup();
  const project = await creation.create('上下文发送副本');
  const archived = makeNovelMessage({ role: 'user', mode: 'discuss', content: '完整旧讨论', createdAt: 1 });
  await repository.updateProject(project.id, current => ({ ...current, messages: [archived],
    discussionArchives: [{ id: 'a1', sourceMessageIds: [archived.id], throughMessageId: archived.id,
      summary: '旧讨论仍有未决问题', decisions: [], createdAt: 2 }],
    branchSettings: { ...current.branchSettings, thisChapterPlan: '作者计划', preferences: '作者偏好' } }));
  assert.equal((await terminal(creation.generate(project.id, '继续讨论', 'discuss'))).kind, 'completed');
  const request = requests[0];
  assert.equal(request.history.length, 1);
  assert.equal(request.history[0].id, archived.uiMessage.id);
  assert.deepEqual(request.context?.excludedHistoryMessageIds, [archived.uiMessage.id]);
  assert.ok(request.context?.sections.some(section => section.key === 'chapter_plan' && section.required));
  assert.ok(request.context?.sections.some(section => section.key === 'preferences' && section.required));
  assert.equal((await creation.open(project.id)).messages.length, 3);
});

test('foreground suggestion failures throw and persist a visible chapter warning with inherited stateSync', async () => {
  const { creation, requests } = setup('模型没有输出 JSON');
  const project = await creation.create('建议失败');
  const chapter = await creation.saveChapter(project.id, null, '一', '正文');
  await assert.rejects(creation.refreshMaterialSuggestions(project.id, chapter.id), /资料建议解析失败/);
  const saved = await creation.open(project.id);
  assert.match(saved.chapters[0].suggestionWarning ?? '', /资料建议解析失败/);
  assert.equal(saved.materialSuggestions.length, 0);
  assert.deepEqual(requests[0].modelTarget, { kind: 'global' });
});

test('suggestion completion after source edits or branch switches never writes old suggestions into the new snapshot', async () => {
  for (const change of ['source', 'branch']) {
    const repository = createFileNovelRepository(createMemoryFileStore());
    let finish: (() => void) | null = null;
    const model: NovelModelRunning = {
      async validate() {},
      start() {
        return { subscribe(callback: (event: NovelModelEvent) => void) {
          finish = () => {
            callback({ kind: 'snapshot', messages: [makeAssistantMessage(
              '{"suggestions":[{"kind":"character","title":"旧人物","content":"旧事实"}]}')],
            generationActive: false, textDeltasLive: false, transport: 'live' });
            callback({ kind: 'completed' });
          };
          return () => {};
        } };
      },
      cancel() {},
    };
    const creation = createNovelCreation({ repository, modelRunning: model });
    const project = await creation.create('建议竞争');
    const chapter = await creation.saveChapter(project.id, null, '一', '旧正文');
    const pending = creation.refreshMaterialSuggestions(project.id, chapter.id);
    const rejected = assert.rejects(pending, /来源章节或分支已变更/);
    for (let step = 0; step < 30 && finish === null; step++) {
      await new Promise<void>(resolve => setTimeout(resolve, 1));
    }
    assert.notEqual(finish, null);
    if (change === 'source') {
      await creation.saveChapter(project.id, chapter.id, '一', '作者改过的正文');
    } else {
      const status = await repository.workspaceStatus(project.id);
      await repository.createBranch(project.id, '新线', status.cas, 'suggestion-race');
    }
    (finish as unknown as () => void)();
    await rejected;
    const current = await creation.open(project.id);
    assert.equal(current.materialSuggestions.length, 0);
    assert.equal(current.chapters[0].suggestionWarning, undefined,
      'failure from old source must not label the new chapter/branch');
  }
});
