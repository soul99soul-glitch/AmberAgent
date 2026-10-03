import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createNovelCreation } from '../main/ets/novel/creation.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { makeNovelProject, makeNovelMaterial } from '../main/ets/novel/models.ts';
import { makeAssistantMessage, makeUserMessage, latestAssistantText, makeUIMessage } from '../main/ets/agent/message.ts';
import type { NovelModelRunning, NovelModelRequest, NovelModelEvent } from '../main/ets/novel/model_running.ts';
import type { NovelRun, NovelRunEvent } from '../main/ets/novel/creation.ts';
import type { UIMessage } from '../main/ets/agent/message.ts';
const terminal = (run: NovelRun): Promise<NovelRunEvent> => new Promise(resolve => run.subscribe(event => {
  if (event.kind === 'completed' || event.kind === 'failed' || event.kind === 'interrupted') resolve(event);
}));
const frozen = { providerId: 'container', modelId: 'model', responsesProviderId: 'effective', configurationJson: '{"temperature":0.3,"model":"wire-original"}' };
const fixture = async () => {
  const files = createMemoryFileStore(); const repository = createFileNovelRepository(files);
  const project = await repository.createProject({ ...makeNovelProject({ id: 'ordinary', name: '冻结', now: 1 }),
    materials: [makeNovelMaterial({ id: 'material', kind: 'world', title: '旧设定', content: '原事实', now: 1 })] });
  return { files, repository, project };
};
const partialModel = (requests: NovelModelRequest[]): NovelModelRunning => ({
  prepareOrdinaryRequest: async () => ({ runtimeSnapshot: frozen, responsesResumeSupported: true }),
  validate: async () => {}, cancel: () => {}, start(request) {
    requests.push(request); const listeners = new Set<(event: NovelModelEvent) => void>();
    setTimeout(async () => {
      if (request.operation.kind !== 'turn') throw new Error('Expected original turn');
      const messages = request.history.concat([makeUserMessage(request.operation.userPrompt), makeAssistantMessage('保存的部分正文')]);
      await request.checkpoint(messages, { responseId: 'resp-same', sequence: 7, providerId: 'effective' });
      listeners.forEach(listener => listener({ kind: 'snapshot', messages, generationActive: true, textDeltasLive: true, transport: 'live' }));
      listeners.forEach(listener => listener({ kind: 'failed', message: '网络断开' }));
    }, 0);
    return { subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); } };
  },
});

test('cold exact retry retains partial but sends original request, settings, model, context and candidate', async () => {
  const f = await fixture(); const requests: NovelModelRequest[] = [];
  const first = createNovelCreation({ repository: f.repository, modelRunning: partialModel(requests) });
  assert.equal((await terminal(first.generate(f.project.id, '作者原请求', 'write', 'whole_chapter', 'prose_whole_chapter'))).kind, 'failed');
  const saved = await first.readOrdinaryRun(f.project.id);
  assert.equal(saved.record?.status, 'failed'); assert.equal(saved.resumeAllowed, true); assert.equal(saved.retryAllowed, true);
  assert.equal(latestAssistantText(saved.record!.checkpointMessages), '保存的部分正文');
  await first.setBranchSettings(f.project.id, { ...(await f.repository.loadProject(f.project.id)).branchSettings, preferences: '新偏好不会进入旧请求' });
  await first.upsertMaterial(f.project.id, 'material', 'world', '新设定', '新事实', true);
  const coldRepo = createFileNovelRepository(f.files);
  const retryModel: NovelModelRunning = { prepareOrdinaryRequest: async () => { throw new Error('Must not resolve latest settings'); },
    validate: async target => { assert.deepEqual(target, { kind: 'fixed', providerId: 'container', modelId: 'model' }); }, cancel: () => {},
    start(request) {
      requests.push(request); const listeners = new Set<(event: NovelModelEvent) => void>();
      setTimeout(async () => {
        if (request.operation.kind !== 'turn') throw new Error('Expected explicit fresh retry');
        const messages = request.history.concat([makeUserMessage(request.operation.userPrompt), makeAssistantMessage('新的完整结果')]);
        await request.checkpoint(messages, { responseId: 'resp-new', sequence: 2, providerId: 'effective' });
        listeners.forEach(listener => listener({ kind: 'snapshot', messages, generationActive: false, textDeltasLive: false, transport: 'buffered' }));
        listeners.forEach(listener => listener({ kind: 'completed' }));
      }, 0);
      return { subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); } };
    },
  };
  const cold = createNovelCreation({ repository: coldRepo, modelRunning: retryModel,
    loadModelDefaults: async () => { throw new Error('Must not read current model preference'); } });
  assert.equal((await terminal(cold.retryOrdinaryRun(f.project.id, saved.record!.id))).kind, 'completed');
  for (const key of ['systemPrompt', 'context', 'runtimeSnapshot', 'toolProfile', 'history', 'modelTarget', 'operation'] as const) {
    assert.deepEqual(requests[1][key], requests[0][key], `frozen ${key}`);
  }
  const project = await coldRepo.loadProject(f.project.id);
  assert.equal(project.messages.filter(message => message.role === 'assistant').length, 2);
  assert.equal(project.messages.some(message => message.content === '保存的部分正文'), true);
  assert.equal(project.ordinaryRun?.status, 'completed');
  assert.deepEqual(project.ordinaryRun?.candidate, saved.record?.candidate, 'source provenance never retargets the edited book');
  assert.equal((await cold.readOrdinaryRun(f.project.id)).retryAllowed, false);
});

test('cold response resume uses saved canonical partial and cursor without appending another user', async () => {
  const f = await fixture(); const requests: NovelModelRequest[] = [];
  const first = createNovelCreation({ repository: f.repository, modelRunning: partialModel(requests) });
  await terminal(first.generate(f.project.id, '继续原响应', 'write', 'whole_chapter', 'prose_whole_chapter'));
  const saved = await first.readOrdinaryRun(f.project.id);
  const model: NovelModelRunning = { validate: async () => {}, cancel: () => {}, start(request) {
    requests.push(request); const listeners = new Set<(event: NovelModelEvent) => void>();
    setTimeout(async () => {
      assert.deepEqual(request.operation, { kind: 'resume_response', cursor: { responseId: 'resp-same', sequence: 7, providerId: 'effective' } });
      assert.equal(latestAssistantText(request.history), '保存的部分正文');
      const messages = request.history.map(message => message.role === 'assistant'
        ? { ...message, parts: makeAssistantMessage('保存的部分正文，已续接完成').parts } : message);
      await request.checkpoint(messages, { responseId: 'resp-same', sequence: 9, providerId: 'effective' });
      listeners.forEach(listener => listener({ kind: 'snapshot', messages, generationActive: false, textDeltasLive: false, transport: 'buffered' }));
      listeners.forEach(listener => listener({ kind: 'completed' }));
    }, 0);
    return { subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); } };
  } };
  const coldRepo = createFileNovelRepository(f.files);
  const cold = createNovelCreation({ repository: coldRepo, modelRunning: model });
  let visiblePartial: string = '';
  const recovered = cold.resumeOrdinaryRun(f.project.id, saved.record!.id);
  recovered.subscribe(event => {
    if (event.kind === 'snapshot') {
      const historyCount = cold.activeRun(f.project.id)!.historyCount;
      assert.equal(historyCount, saved.record!.transcriptPrefix.length);
      visiblePartial = latestAssistantText(event.messages.slice(historyCount));
    }
  });
  assert.equal((await terminal(recovered)).kind, 'completed');
  assert.equal(visiblePartial, '保存的部分正文，已续接完成', 'real Workspace snapshot slicing sees the resumed same-ID assistant');
  const project = await coldRepo.loadProject(f.project.id);
  assert.equal(project.messages.filter(message => message.role === 'user').length, 1);
  assert.equal(project.messages.filter(message => message.role === 'assistant').length, 1);
  assert.equal(project.messages.at(-1)?.content, '保存的部分正文，已续接完成');
  assert.equal(project.ordinaryRun?.cursor?.sequence, 9);
  assert.equal(project.messages.at(-1)?.candidate?.complete, true);
});

test('model preparation failure durably keeps the author input and frozen domain context without pretending it can resume', async () => {
  const f = await fixture(); let starts: number = 0;
  const creation = createNovelCreation({ repository: f.repository, modelRunning: {
    prepareOrdinaryRequest: async () => { throw new Error('原模型配置不可用'); },
    validate: async () => {}, cancel: () => {}, start() { starts++; throw new Error('Must not start'); },
  } });
  assert.equal((await terminal(creation.generate(f.project.id, '需要保留的原输入', 'write', 'whole_chapter', 'prose_whole_chapter'))).kind, 'failed');
  const cold = createNovelCreation({ repository: createFileNovelRepository(f.files), modelRunning: partialModel([]) });
  const view = await cold.readOrdinaryRun(f.project.id);
  assert.equal(view.record?.status, 'failed'); assert.equal(view.record?.userText, '需要保留的原输入');
  assert.match(view.record!.originalRequest.systemPrompt, /原事实/);
  assert.equal(view.retryAllowed, false); assert.equal(view.resumeAllowed, false);
  assert.match(view.blockedReason, /未能冻结/); assert.equal(starts, 0);
});


test('pending ordinary tool continuation keeps the original model settings and context after cold reopen', async () => {
  const f = await fixture(); const requests: NovelModelRequest[] = [];
  const model: NovelModelRunning = {
    prepareOrdinaryRequest: async () => ({ runtimeSnapshot: frozen, responsesResumeSupported: false }),
    validate: async () => {}, cancel: () => {}, start(request) {
      requests.push(request); const listeners = new Set<(event: NovelModelEvent) => void>();
      setTimeout(async () => {
        if (request.operation.kind === 'turn') {
          const messages = request.history.concat([makeUserMessage(request.operation.userPrompt), makeUIMessage('assistant', [{
            type: 'tool', toolCallId: 'ask', toolName: 'ask_user', input: '{}', output: [],
            approvalState: { type: 'pending' }, metadata: null,
          }])]);
          await request.checkpoint(messages);
          listeners.forEach(listener => listener({ kind: 'snapshot', messages, generationActive: false, textDeltasLive: false, transport: 'buffered' }));
          listeners.forEach(listener => listener({ kind: 'waiting_user' }));
        } else {
          assert.equal(request.operation.kind, 'tool_continuation');
          const messages = request.history.concat([makeAssistantMessage('原配置完成工具续接')]);
          await request.checkpoint(messages);
          listeners.forEach(listener => listener({ kind: 'snapshot', messages, generationActive: false, textDeltasLive: false, transport: 'buffered' }));
          listeners.forEach(listener => listener({ kind: 'completed' }));
        }
      }, 0);
      return { subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); } };
    },
  };
  const first = createNovelCreation({ repository: f.repository, modelRunning: model });
  const waiting = first.generate(f.project.id, '讨论原设定', 'discuss', null, 'discussion');
  await new Promise<void>(resolve => waiting.subscribe(event => { if (event.kind === 'waiting_user') resolve(); }));
  await first.upsertMaterial(f.project.id, 'material', 'world', '后改资料', '不进入旧工具请求', true);
  const cold = createNovelCreation({ repository: createFileNovelRepository(f.files), modelRunning: model,
    loadModelDefaults: async () => { throw new Error('Must not resolve current defaults for a pending original tool'); } });
  const done = await terminal(cold.continueTool(f.project.id, 'ask', { kind: 'answered', answer: '作者回答' }));
  assert.equal(done.kind, 'completed');
  for (const key of ['systemPrompt', 'context', 'runtimeSnapshot', 'toolProfile', 'modelTarget', 'maxOutputTokens'] as const)
    assert.deepEqual(requests[1][key], requests[0][key], `original pending tool ${key}`);
  assert.equal((await cold.readOrdinaryRun(f.project.id)).record?.status, 'completed');
});

test('terminated ordinary record rejects a late checkpoint instead of mutating its saved transcript', async () => {
  const f = await fixture(); const requests: NovelModelRequest[] = [];
  const creation = createNovelCreation({ repository: f.repository, modelRunning: partialModel(requests) });
  await terminal(creation.generate(f.project.id, '原任务', 'write', 'whole_chapter', 'prose_whole_chapter'));
  const before = await f.repository.loadProject(f.project.id);
  await assert.rejects(requests[0].checkpoint(requests[0].history.concat([makeUserMessage('迟到请求'), makeAssistantMessage('不能覆盖')]),
    { responseId: 'resp-same', sequence: 8, providerId: 'effective' }), /任务已终止/);
  assert.deepEqual((await f.repository.loadProject(f.project.id)).messages, before.messages);
  assert.deepEqual((await f.repository.loadProject(f.project.id)).ordinaryRun, before.ordinaryRun);
});

test('cold recovery rejects changed conversation ownership before resolving any provider', async () => {
  const f = await fixture(); const requests: NovelModelRequest[] = [];
  const first = createNovelCreation({ repository: f.repository, modelRunning: partialModel(requests) });
  await terminal(first.generate(f.project.id, '原任务', 'write', 'whole_chapter', 'prose_whole_chapter'));
  const record = (await first.readOrdinaryRun(f.project.id)).record!;
  await f.repository.updateProject(f.project.id, project => ({ ...project, messages: [] }));
  let starts = 0;
  const cold = createNovelCreation({ repository: createFileNovelRepository(f.files), modelRunning: {
    validate: async () => { throw new Error('Must not resolve another owner'); }, cancel: () => {},
    start() { starts++; throw new Error('Must not send'); },
  } });
  assert.equal((await cold.readOrdinaryRun(f.project.id)).resumeAllowed, false);
  assert.equal((await terminal(cold.resumeOrdinaryRun(f.project.id, record.id))).kind, 'failed');
  assert.equal(starts, 0); assert.equal((await f.repository.loadProject(f.project.id)).messages.length, 0);
});

test('failed answered tool cold retry resumes the frozen continuation without repeating original turn', async () => {
  const f = await fixture(); const requests: NovelModelRequest[] = []; let failContinuation = true;
  const model: NovelModelRunning = {
    prepareOrdinaryRequest: async () => ({ runtimeSnapshot: frozen, responsesResumeSupported: false }),
    validate: async () => {}, cancel: () => {}, start(request) {
      requests.push(request); const listeners = new Set<(event: NovelModelEvent) => void>();
      setTimeout(async () => {
        if (request.operation.kind === 'turn') {
          const messages = request.history.concat([makeUserMessage(request.operation.userPrompt), makeUIMessage('assistant', [{
            type: 'tool', toolCallId: 'ask', toolName: 'ask_user', input: '{}', output: [], approvalState: { type: 'pending' }, metadata: null,
          }])]);
          await request.checkpoint(messages);
          listeners.forEach(listener => listener({ kind: 'snapshot', messages, generationActive: false, textDeltasLive: false, transport: 'buffered' }));
          listeners.forEach(listener => listener({ kind: 'waiting_user' })); return;
        }
        assert.equal(request.operation.kind, 'tool_continuation');
        const answered: UIMessage[] = request.history.map(message => ({ ...message, parts: message.parts.map(part => part.type === 'tool'
          ? { ...part, approvalState: { type: 'answered' as const, answer: '已确认答案' }, output: [{ type: 'text' as const, text: '已确认答案', metadata: null }] } : part) }));
        await request.checkpoint(answered);
        if (failContinuation) {
          const partial: UIMessage[] = answered.map(message => message.role === 'assistant'
            ? { ...message, parts: message.parts.concat(makeAssistantMessage('PARTIAL_BEFORE_FAILURE').parts) } : message);
          await request.checkpoint(partial);
          listeners.forEach(listener => listener({ kind: 'snapshot', messages: partial, generationActive: true, textDeltasLive: true, transport: 'live' }));
          listeners.forEach(listener => listener({ kind: 'failed', message: '回答后模型断线' })); return;
        }
        const messages = answered.concat([makeAssistantMessage('续接完成')]); await request.checkpoint(messages);
        listeners.forEach(listener => listener({ kind: 'snapshot', messages, generationActive: false, textDeltasLive: false, transport: 'buffered' }));
        listeners.forEach(listener => listener({ kind: 'completed' }));
      }, 0);
      return { subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); } };
    },
  };
  const first = createNovelCreation({ repository: f.repository, modelRunning: model });
  const waiting = first.generate(f.project.id, '不能重问的原turn', 'discuss', null, 'discussion');
  await new Promise<void>(resolve => waiting.subscribe(event => { if (event.kind === 'waiting_user') resolve(); }));
  assert.equal((await terminal(first.continueTool(f.project.id, 'ask', { kind: 'answered', answer: '已确认答案' }))).kind, 'failed');
  const cold = createNovelCreation({ repository: createFileNovelRepository(f.files), modelRunning: model,
    loadModelDefaults: async () => { throw new Error('Must not reread defaults'); } });
  const view = await cold.readOrdinaryRun(f.project.id);
  assert.equal(view.record?.status, 'failed'); assert.equal(view.retryAllowed, true);
  assert.equal(view.record?.originalRequest.operation.kind, 'tool_continuation');
  assert.equal(latestAssistantText(view.record!.checkpointMessages), 'PARTIAL_BEFORE_FAILURE');
  assert.equal(latestAssistantText(view.record!.originalRequest.history), '', 'retry seed freezes tool result without failed generated text');
  assert.equal((await terminal(cold.retryOrdinaryRun(f.project.id, view.record!.id))).kind, 'failed', 'a second failure remains recoverable');
  const repeated = await cold.readOrdinaryRun(f.project.id);
  assert.equal(repeated.retryAllowed, true);
  assert.equal(latestAssistantText(repeated.record!.checkpointMessages), 'PARTIAL_BEFORE_FAILURE');
  assert.equal(latestAssistantText(repeated.record!.originalRequest.history), '', 'original text stays immutable across another failed retry');
  failContinuation = false;
  assert.equal((await terminal(cold.retryOrdinaryRun(f.project.id, repeated.record!.id))).kind, 'completed');
  assert.equal(requests.filter(request => request.operation.kind === 'turn').length, 1);
  assert.equal(latestAssistantText(requests[2].history), '', 'failed text never becomes original continuation input');
  assert.equal(requests[2].history.at(-1)?.parts.some(part => part.type === 'tool' && part.approvalState.type === 'answered' && part.output.length > 0), true);
  assert.equal((await f.repository.loadProject(f.project.id)).messages.filter(message => message.role === 'user').length, 1);
});
