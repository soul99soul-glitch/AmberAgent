import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createNovelCreation, createFileNovelRepository, createMemoryFileStore, makeNovelProject, latestAssistantText } from '@amber/deepread-domain';
import type { NovelRun, NovelRunEvent, NovelModelRequest, NovelModelEvent, HttpClient, HttpRequest } from '@amber/deepread-domain';
import { createNovelInteractiveAdapter } from '../main/ets/chat/novel_interactive_adapter.ts';
import type { NovelInteractiveRuntimeConfig } from '../main/ets/chat/novel_interactive_adapter.ts';
import { createAskUserTool } from '../main/ets/chat/builtin_ask_user_tool.ts';
import { AgentToolDispatcher } from '../main/ets/chat/tool_dispatcher.ts';
import { makeGenerationRetrySetting } from '../main/ets/chat/generation_retry.ts';
import { makeAssistantMessage } from '../main/ets/chat/message.ts';
import type { UIMessage } from '../main/ets/chat/message.ts';
import type { ChatStreamProvider } from '../main/ets/chat/chat_turn.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import { createOpenAIChatApi, asChatStreamProvider } from '../main/ets/chat/openai_chat_api.ts';
import { makeProviderSettingOpenAI, makeChatModel, makeTextGenerationParams } from '../main/ets/chat/provider_model.ts';

const terminal = (run: NovelRun): Promise<NovelRunEvent> => new Promise(resolve => run.subscribe(event => {
  if (event.kind === 'completed' || event.kind === 'failed' || event.kind === 'interrupted') resolve(event);
}));
const snapshot = { providerId: 'openai', modelId: 'gpt-4o', configurationJson: '{"temperature":0.3}' };
const created = { type: 'response.created', sequence_number: 0, response: { id: 'resp_original' } };
const delta = (sequence: number, text: string) => ({ type: 'response.output_text.delta', sequence_number: sequence, item_id: 'msg', delta: text });
const completed = (sequence: number) => ({ type: 'response.completed', sequence_number: sequence,
  response: { id: 'resp_original', status: 'completed', model: 'gpt-4o',
    output: [{ type: 'message', content: [{ type: 'output_text', text: 'Hello there' }] }] } });
const fixture = async (disconnect: boolean, failFinalCommit = false) => {
  const requests: HttpRequest[] = [];
  const files = createMemoryFileStore(); const repository = createFileNovelRepository(files);
  const project = await repository.createProject(makeNovelProject({ id: 'http-book', name: '冷恢复', now: 1 }));
  const commitProject = repository.commitProject;
  repository.commitProject = async (id, cas, commandId, kind, transform) => {
    if (failFinalCommit && /^novel:.*:complete$/.test(commandId)) {
      failFinalCommit = false; throw new Error('crash at final transcript commit');
    }
    return commitProject(id, cas, commandId, kind, transform);
  };
  const http: HttpClient = { fetch: async () => { throw new Error('nonstream forbidden'); }, fetchStream: async (request, opts) => {
    const attempt = requests.length; requests.push(request);
    const events = disconnect && attempt === 0 ? [created, delta(1, 'Hel')]
      : disconnect ? [delta(2, 'lo'), completed(3)] : [created, delta(1, 'Hello there'), completed(2)];
    const bytes = new TextEncoder().encode(events.map(event => `data: ${JSON.stringify(event)}\n\n`).join(''));
    opts.onChunk(bytes.buffer as ArrayBuffer, true);
    if (disconnect && attempt === 0) throw new Error('network disconnected');
    return { status: 200, headers: {}, body: '' };
  } };
  const makeModel = () => createNovelInteractiveAdapter({ createAbortController: () => new AbortController(),
    resolveRuntime: async (_target, _projectId, frozen) => {
      if (frozen !== undefined) assert.equal(frozen.configurationJson, snapshot.configurationJson);
      const runtime: NovelInteractiveRuntimeConfig = { assistant: makeAssistant({ id: 'novel' }),
        runtimeSnapshot: snapshot, responsesResumeSupported: true, contextWindowTokens: 100000, maxOutputTokens: 1000,
        provider: { streamText: async () => { throw new Error('unprepared provider forbidden'); } } };
      runtime.makeProviderForOutputTokens = (_cap, _task, options) => asChatStreamProvider(createOpenAIChatApi({ http,
        setting: makeProviderSettingOpenAI({ id: 'openai', baseUrl: 'https://api.openai.com/v1', useResponseApi: true }),
        enableResponsesResume: true, runId: options!.runId, resumeFrom: options!.resumeFrom,
        resumeMessages: options!.partialMessages, onResumeCheckpoint: options!.onCheckpoint,
      }), () => makeTextGenerationParams({ model: makeChatModel({ modelId: 'gpt-4o' }) }));
      return runtime;
    } });
  return { requests, files, repository, project, makeModel };
};

test('actual Creation file reopen resumes HTTP same ID and durable partial with one user and assistant', async () => {
  const f = await fixture(true); const first = createNovelCreation({ repository: f.repository, modelRunning: f.makeModel() });
  assert.equal((await terminal(first.generate(f.project.id, '作者原输入', 'write', 'whole_chapter', 'prose_whole_chapter'))).kind, 'failed');
  const saved = (await first.readOrdinaryRun(f.project.id)).record!;
  assert.equal(latestAssistantText(saved.checkpointMessages), 'Hel'); assert.equal(saved.cursor?.sequence, 1);
  const assistantId = saved.checkpointMessages.at(-1)!.id;
  const coldRepository = createFileNovelRepository(f.files);
  const cold = createNovelCreation({ repository: coldRepository, modelRunning: f.makeModel() });
  assert.equal((await terminal(cold.resumeOrdinaryRun(f.project.id, saved.id))).kind, 'completed');
  assert.deepEqual(f.requests.map(request => request.method), ['POST', 'GET']);
  assert.match(f.requests[1].url, /\/responses\/resp_original\?stream=true&starting_after=1$/);
  assert.equal(f.requests[1].body, undefined);
  const final = await coldRepository.loadProject(f.project.id);
  assert.equal(final.messages.filter(message => message.role === 'user').length, 1);
  assert.equal(final.messages.filter(message => message.role === 'assistant').length, 1);
  assert.equal(final.messages.at(-1)!.content, 'Hello there');
  assert.equal(final.ordinaryRun!.checkpointMessages.at(-1)!.id, assistantId);
  assert.equal(final.messages.at(-1)!.candidate?.complete, true); assert.equal(final.chapters.length, 0);
});

test('completed raw checkpoint survives final commit crash and cold recovery finishes with zero further HTTP', async () => {
  const f = await fixture(false, true); const first = createNovelCreation({ repository: f.repository, modelRunning: f.makeModel() });
  assert.equal((await terminal(first.generate(f.project.id, '作者原输入', 'write', 'whole_chapter', 'prose_whole_chapter'))).kind, 'failed');
  const saved = (await first.readOrdinaryRun(f.project.id)).record!;
  assert.deepEqual(saved.cursor, { responseId: 'resp_original', sequence: 2, providerId: 'openai', terminalStatus: 'completed' });
  assert.equal(latestAssistantText(saved.checkpointMessages), 'Hello there');
  const coldRepository = createFileNovelRepository(f.files);
  const cold = createNovelCreation({ repository: coldRepository, modelRunning: f.makeModel() });
  assert.equal((await terminal(cold.resumeOrdinaryRun(f.project.id, saved.id))).kind, 'completed');
  assert.equal(f.requests.length, 1);
  const final = await coldRepository.loadProject(f.project.id);
  assert.equal(final.messages.filter(message => message.role === 'user').length, 1);
  assert.equal(final.messages.filter(message => message.role === 'assistant').length, 1);
  assert.equal(final.messages.at(-1)!.content, 'Hello there');
  assert.equal(final.messages.at(-1)!.candidate?.complete, true); assert.equal(final.chapters.length, 0);
  assert.equal(final.ordinaryRun?.status, 'completed'); assert.equal((await cold.readOrdinaryRun(f.project.id)).resumeAllowed, false);
});

test('actual AskUser executor output survives failed continuation and cold retry without repeating user or answer', async () => {
  const files = createMemoryFileStore(); const repository = createFileNovelRepository(files);
  const project = await repository.createProject(makeNovelProject({ id: 'tool-book', name: '原工具续跑', now: 1 }));
  const requests: NovelModelRequest[] = []; const sends: UIMessage[][] = [];
  let executedAnswers = 0; let currentConfiguration = snapshot.configurationJson;
  const makeModel = () => {
    const dispatcher = new AgentToolDispatcher();
    const executeBatchPairs = dispatcher.executeBatchPairs.bind(dispatcher);
    dispatcher.executeBatchPairs = async (...args) => {
      executedAnswers += args[0].filter(tool => tool.toolName === 'ask_user' && tool.approvalState.type === 'answered').length;
      return executeBatchPairs(...args);
    };
    const provider: ChatStreamProvider = { streamText: async (messages, onChunk) => {
      sends.push(structuredClone(messages));
      if (sends.length === 1) {
        const assistant = makeAssistantMessage('');
        assistant.parts = [{ type: 'tool', toolCallId: 'ask-original', toolName: 'ask_user',
          input: JSON.stringify({ questions: [{ id: 'tone', question: '什么语气？' }] }),
          output: [], approvalState: { type: 'auto' }, metadata: null }];
        onChunk({ id: 'tool-response', model: 'gpt-4o', usage: null,
          choices: [{ index: 0, delta: assistant, message: null, finishReason: null }] }); return;
      }
      const ask = messages.flatMap(message => message.parts).find(part => part.type === 'tool' && part.toolCallId === 'ask-original');
      assert.ok(ask?.type === 'tool'); assert.equal(ask.approvalState.type, 'answered');
      assert.deepEqual(ask.output.map(part => part.type === 'text' ? part.text : ''), ['{"tone":"温暖"}']);
      if (sends.length === 2) {
        onChunk({ id: 'partial-response', model: 'gpt-4o', usage: null,
          choices: [{ index: 0, delta: makeAssistantMessage('PARTIAL_BEFORE_FAILURE'), message: null, finishReason: null }] });
        throw new Error('provider failed after saved tool output');
      }
      onChunk({ id: 'completed-response', model: 'gpt-4o', usage: null,
        choices: [{ index: 0, delta: makeAssistantMessage('已按原回答继续'), message: null, finishReason: null }] });
    } };
    const adapter = createNovelInteractiveAdapter({ createAbortController: () => new AbortController(),
      resolveRuntime: async (_target, _projectId, frozen) => ({ assistant: makeAssistant({ id: 'novel-tools' }), provider,
        runtimeSnapshot: frozen ?? { ...snapshot, configurationJson: currentConfiguration }, responsesResumeSupported: true,
        tools: [createAskUserTool()], makeProviderForStep: () => provider, contextWindowTokens: 100000, maxOutputTokens: 1000,
        retrySetting: makeGenerationRetrySetting({ enabled: false }), toolLoopOptions: { dispatcher },
      }) });
    const start = adapter.start.bind(adapter);
    adapter.start = request => { requests.push(request); return start(request); };
    return adapter;
  };
  const first = createNovelCreation({ repository, modelRunning: makeModel() });
  const waiting = first.generate(project.id, '只问一次的原请求', 'discuss', null, 'discussion');
  await new Promise<void>(resolve => waiting.subscribe(event => { if (event.kind === 'waiting_user') resolve(); }));
  assert.equal(executedAnswers, 0);
  const failedContinuation = await terminal(first.continueTool(project.id, 'ask-original', { kind: 'answered', answer: '{"tone":"温暖"}' }));
  assert.equal(failedContinuation.kind, 'failed');
  assert.match((failedContinuation as { message: string }).message, /provider failed after saved tool output/);
  const saved = (await first.readOrdinaryRun(project.id)).record!;
  assert.equal(saved.status, 'failed'); assert.equal(saved.originalRequest.operation.kind, 'tool_continuation');
  assert.equal(saved.originalRequest.responsesResumeEnabled, undefined, 'tool continuation stays outside Responses resume');
  assert.equal(executedAnswers, 1);
  assert.equal(latestAssistantText(saved.checkpointMessages), 'PARTIAL_BEFORE_FAILURE', 'visible raw partial is durable before failure');
  assert.equal((await createFileNovelRepository(files).loadProject(project.id)).messages.some(message => message.content === 'PARTIAL_BEFORE_FAILURE'), true);
  const savedAsk = saved.checkpointMessages.flatMap(message => message.parts).find(part => part.type === 'tool' && part.toolCallId === 'ask-original');
  assert.ok(savedAsk?.type === 'tool'); assert.equal(savedAsk.output.length, 1);
  currentConfiguration = '{"temperature":0.9}';
  await first.setBranchSettings(project.id, { ...(await repository.loadProject(project.id)).branchSettings, preferences: '后改偏好不得进入原续跑' });
  const coldRepository = createFileNovelRepository(files);
  const cold = createNovelCreation({ repository: coldRepository, modelRunning: makeModel(),
    loadModelDefaults: async () => { throw new Error('cold retry must use original model'); } });
  assert.equal((await cold.readOrdinaryRun(project.id)).retryAllowed, true);
  let visibleResumedText = '';
  const retry = cold.retryOrdinaryRun(project.id, saved.id);
  retry.subscribe(event => {
    if (event.kind === 'snapshot') visibleResumedText = latestAssistantText(event.messages.slice(cold.activeRun(project.id)!.historyCount));
  });
  const retryTerminal = await terminal(retry);
  assert.equal(retryTerminal.kind, 'completed', JSON.stringify(retryTerminal));
  assert.equal(sends.length, 3); assert.equal(executedAnswers, 1, 'saved AskUser answer must not execute again');
  assert.equal(latestAssistantText(requests[2].history).includes('PARTIAL_BEFORE_FAILURE'), false, 'retry input retains tool state but excludes failed text');
  assert.equal(latestAssistantText(sends[2]).includes('PARTIAL_BEFORE_FAILURE'), false, 'actual provider input excludes failed text');
  assert.equal(requests.filter(request => request.operation.kind === 'turn').length, 1);
  for (const key of ['operation', 'runtimeSnapshot', 'systemPrompt', 'context', 'modelTarget', 'toolProfile'] as const)
    assert.deepEqual(requests[2][key], requests[1][key], `frozen continuation ${key}`);
  const systemContent = (messages: UIMessage[]) => messages.filter(message => message.role === 'system')
    .map(message => ({ role: message.role, parts: message.parts }));
  assert.deepEqual(systemContent(sends[2]), systemContent(sends[1]), 'same system/context content despite new transport message IDs');
  assert.equal(visibleResumedText, '已按原回答继续', 'Workspace history slicing sees the resumed same-ID assistant');
  const final = await coldRepository.loadProject(project.id);
  assert.equal(final.messages.filter(message => message.role === 'user').length, 1);
  const finalTools = final.ordinaryRun!.checkpointMessages.flatMap(message => message.parts).filter(part => part.type === 'tool');
  assert.equal(finalTools.length, 1); assert.equal(finalTools[0].type === 'tool' && finalTools[0].output.length, 1);
  assert.equal(final.messages.at(-1)?.content, '已按原回答继续'); assert.equal(final.ordinaryRun?.status, 'completed');
});

test('ordinary failed partial save propagates checkpoint rejection once without retrying the owner write', async () => {
  for (const providerFails of [false, true]) {
    let saves = 0; let providerCalls = 0;
    const provider: ChatStreamProvider = { streamText: async (_messages, onChunk) => {
      providerCalls++;
      onChunk({ id: 'partial', model: 'gpt-4o', usage: null,
        choices: [{ index: 0, delta: makeAssistantMessage('待保存正文'), message: null, finishReason: null }] });
      if (providerFails) throw new Error('provider disconnected');
    } };
    const model = createNovelInteractiveAdapter({ createAbortController: () => new AbortController(),
      resolveRuntime: async () => ({ assistant: makeAssistant({}), provider, runtimeSnapshot: snapshot,
        responsesResumeSupported: false, tools: [createAskUserTool()], makeProviderForStep: () => provider,
        retrySetting: makeGenerationRetrySetting({ enabled: false }), contextWindowTokens: 100000, maxOutputTokens: 1000,
      }) });
    const answered = makeAssistantMessage('');
    answered.parts = [{ type: 'tool', toolCallId: 'answered', toolName: 'ask_user', input: '{}',
      approvalState: { type: 'answered', answer: '原答案' }, output: [{ type: 'text', text: '原答案', metadata: null }], metadata: null }];
    const request: NovelModelRequest = { runId: `failed-save-${providerFails}`, projectId: 'book', history: [answered],
      modelTarget: { kind: 'fixed', providerId: snapshot.providerId, modelId: snapshot.modelId }, runtimeSnapshot: snapshot,
      responsesResumeEnabled: false, systemPrompt: '原prompt', maxOutputTokens: 1000, toolProfile: 'all',
      operation: { kind: 'tool_continuation', toolCallId: 'answered', verdict: { kind: 'answered', answer: '原答案' } },
      checkpoint: async messages => {
        saves++; if (latestAssistantText(messages) === '') return;
        assert.equal(latestAssistantText(messages), '待保存正文'); throw new Error('checkpoint disk full');
      },
    };
    const events: NovelModelEvent[] = [];
    await new Promise<void>(resolve => model.start(request).subscribe(event => {
      events.push(event); if (event.kind === 'failed' || event.kind === 'completed') resolve();
    }));
    assert.equal(events.at(-1)?.kind, 'failed');
    assert.match((events.at(-1) as { message: string }).message, /checkpoint disk full/);
    assert.equal(saves, 2, 'one approval save and one rejected partial save'); assert.equal(providerCalls, 1);
    assert.equal(events.some(event => event.kind === 'completed'), false);
  }
});
