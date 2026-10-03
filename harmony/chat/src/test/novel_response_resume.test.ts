import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { NovelModelRequest, NovelModelEvent, NovelResponsesResumeCursor, NovelRuntimeSnapshot } from '@amber/deepread-domain';
import { latestAssistantText } from '@amber/deepread-domain';
import { createNovelInteractiveAdapter } from '../main/ets/chat/novel_interactive_adapter.ts';
import type { NovelInteractiveRuntimeConfig, NovelResponsesStreamOptions } from '../main/ets/chat/novel_interactive_adapter.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import { makeAssistantMessage, makeUserMessage } from '../main/ets/chat/message.ts';
import type { MessageChunk, UIMessage } from '../main/ets/chat/message.ts';
import { makeGenerationRetrySetting } from '../main/ets/chat/generation_retry.ts';
import { makeAgentTool, makeInputSchemaObj } from '../main/ets/chat/tool.ts';
import { toChatToolDefinition } from '../main/ets/chat/tool.ts';
import { createOpenAIResponsesApi, asResponsesChatStreamProvider } from '../main/ets/chat/openai_responses_api.ts';
import { makeProviderSettingOpenAI, makeChatModel, makeTextGenerationParams } from '../main/ets/chat/provider_model.ts';
import type { HttpClient, HttpRequest } from '@amber/deepread-domain';

const frozen: NovelRuntimeSnapshot = { providerId: 'openai', modelId: 'gpt-4o', configurationJson: '{"frozen":true}' };
const cursor: NovelResponsesResumeCursor = { responseId: 'resp_original', sequence: 17, providerId: 'openai' };
const chunk = (text: string): MessageChunk => ({ id: 'msg_server', model: 'gpt-4o', usage: null,
  choices: [{ index: 0, delta: makeAssistantMessage(text), message: null, finishReason: null }] });
const request = (checkpoint: NovelModelRequest['checkpoint'] = async () => {}): NovelModelRequest => ({
  runId: 'same-run', projectId: 'book', modelTarget: { kind: 'fixed', providerId: 'openai', modelId: 'gpt-4o' },
  runtimeSnapshot: frozen, responsesResumeEnabled: true, maxOutputTokens: 1000, systemPrompt: '只写正文',
  history: [makeUserMessage('写'), makeAssistantMessage('先前前缀')],
  toolProfile: 'none', operation: { kind: 'resume_response', cursor }, checkpoint,
});
const terminal = (model: ReturnType<typeof createNovelInteractiveAdapter>, req: NovelModelRequest): Promise<NovelModelEvent[]> =>
  new Promise(resolve => {
    const events: NovelModelEvent[] = [];
    model.start(req).subscribe(event => { events.push(event);
      if (event.kind === 'completed' || event.kind === 'failed' || event.kind === 'waiting_user') resolve(events);
    });
  });
const runtime = (): NovelInteractiveRuntimeConfig => ({ assistant: makeAssistant({ id: 'novel' }),
  provider: { streamText: async () => { throw new Error('fresh provider forbidden'); } },
  runtimeSnapshot: frozen, responsesResumeSupported: true, contextWindowTokens: 10000, maxOutputTokens: 1000,
  retrySetting: makeGenerationRetrySetting({ enabled: true, maxRetries: 3, initialDelayMs: 1 }),
});

test('resume uses frozen runtime, resumes same assistant prefix and atomically checkpoints canonical plus cursor', async () => {
  const saved: Array<{ messages: UIMessage[]; cursor?: NovelResponsesResumeCursor }> = [];
  const req = request(async (messages, cursor) => { saved.push({ messages, cursor }); });
  const initialAssistantId = req.history[1].id;
  let calls = 0;
  let options!: NovelResponsesStreamOptions;
  const resolved = runtime();
  resolved.makeProviderForOutputTokens = (_cap, _task, opts) => {
    options = opts!;
    return { streamText: async (_messages, onChunk) => {
      calls += 1; onChunk(chunk('新后缀')); await options.onCheckpoint({ ...cursor, sequence: 18 });
    } };
  };
  const model = createNovelInteractiveAdapter({ createAbortController: () => new AbortController(),
    resolveRuntime: async (_target, _project, snapshot) => { assert.deepEqual(snapshot, frozen); return resolved; } });
  const events = await terminal(model, req);
  assert.equal(events.at(-1)?.kind, 'completed'); assert.equal(calls, 1);
  assert.deepEqual(options.resumeFrom, cursor); assert.equal(options.runId, req.runId);
  assert.deepEqual(options.partialMessages, req.history);
  const durable = saved.find(item => item.cursor?.sequence === 18)!;
  assert.equal(latestAssistantText(durable.messages), '先前前缀新后缀');
  assert.equal(durable.messages.length, 2); assert.equal(durable.messages[1].id, initialAssistantId);
  assert.equal(saved.at(-1)!.messages[1].finishedAt !== null, true);
});

test('resumable initial turn checkpoints its actual new user before created cursor and keeps raw prefix', async () => {
  const saved: Array<{ messages: UIMessage[]; cursor?: NovelResponsesResumeCursor }> = [];
  const req = { ...request(async (messages, cursor) => { saved.push({ messages, cursor }); }),
    history: [], operation: { kind: 'turn' as const, userPrompt: '冻结原请求' } };
  const resolved = runtime();
  resolved.makeProviderForOutputTokens = (_cap, _task, options) => ({ streamText: async (_messages, onChunk) => {
    assert.equal(options!.resumeFrom, null);
    await options!.onCheckpoint({ ...cursor, sequence: 0 });
    onChunk(chunk('原始正文')); await options!.onCheckpoint({ ...cursor, sequence: 1 });
  } });
  const model = createNovelInteractiveAdapter({ createAbortController: () => new AbortController(), resolveRuntime: async () => resolved });
  assert.equal((await terminal(model, req)).at(-1)?.kind, 'completed');
  assert.equal(saved.find(item => item.cursor?.sequence === 0)!.messages[0].parts[0].type, 'text');
  assert.equal(saved.find(item => item.cursor?.sequence === 0)!.messages[0].role, 'user');
  assert.equal(latestAssistantText(saved.find(item => item.cursor?.sequence === 1)!.messages), '原始正文');
});

test('resume cannot invoke a fresh provider or retry a failed transport even when normal retry is enabled', async () => {
  const resolved = runtime(); let calls = 0;
  resolved.makeProviderForOutputTokens = () => ({ streamText: async (_messages, onChunk) => {
    calls += 1; onChunk(chunk('未完成后缀')); throw new Error('network interrupted');
  } });
  const model = createNovelInteractiveAdapter({ createAbortController: () => new AbortController(), resolveRuntime: async () => resolved });
  const events = await terminal(model, request());
  assert.equal(events.at(-1)?.kind, 'failed'); assert.equal(calls, 1);
  const snapshots = events.filter(event => event.kind === 'snapshot');
  assert.ok(snapshots.some(event => event.kind === 'snapshot' && latestAssistantText(event.messages) === '先前前缀未完成后缀'));
});

test('unsupported runtime or disabled response resume rejects without a provider call', async () => {
  for (const disabled of [false, true]) {
    const resolved = runtime(); let calls = 0;
    resolved.responsesResumeSupported = !disabled;
    resolved.makeProviderForOutputTokens = () => ({ streamText: async () => { calls += 1; } });
    const model = createNovelInteractiveAdapter({ createAbortController: () => new AbortController(), resolveRuntime: async () => resolved });
    const req = { ...request(), responsesResumeEnabled: disabled };
    const events = await terminal(model, req);
    assert.equal(events.at(-1)?.kind, 'failed'); assert.equal(calls, 0);
  }
});

test('prepare freezes exact runtime and selected tool catalog; changed catalog rejects before sending', async () => {
  const tool = makeAgentTool({ name: 'test', description: '原描述', parameters: () => makeInputSchemaObj({}), execute: async () => [] });
  const resolved = { ...runtime(), tools: [tool], makeProviderForStep: () => runtime().provider };
  const model = createNovelInteractiveAdapter({ createAbortController: () => new AbortController(), resolveRuntime: async () => resolved });
  const req = { ...request(), responsesResumeEnabled: false, toolProfile: 'all' as const,
    operation: { kind: 'turn' as const, userPrompt: '写' } };
  const prepared = await model.prepareOrdinaryRequest!(req);
  assert.equal(prepared.runtimeSnapshot.providerId, 'openai');
  assert.equal(prepared.runtimeSnapshot.frozenToolCatalogJson, JSON.stringify([toChatToolDefinition(tool)]));
  assert.equal(prepared.responsesResumeSupported, true);
  resolved.tools = [{ ...tool, description: '已变描述' }];
  const events = await terminal(model, { ...req, runtimeSnapshot: prepared.runtimeSnapshot });
  assert.equal(events.at(-1)?.kind, 'failed');
  assert.match((events.at(-1) as { message: string }).message, /原请求工具配置已变化/);
});

test('cursor provider mismatch cannot call a frozen provider from another account', async () => {
  const resolved = runtime(); let called = false;
  resolved.makeProviderForOutputTokens = () => ({ streamText: async () => { called = true; } });
  const model = createNovelInteractiveAdapter({ createAbortController: () => new AbortController(), resolveRuntime: async () => resolved });
  const events = await terminal(model, { ...request(), operation: { kind: 'resume_response', cursor: { ...cursor, providerId: 'other' } } });
  assert.equal(events.at(-1)?.kind, 'failed'); assert.equal(called, false);
});

test('actual HTTP adapter disconnect and cold resume keep one request, one user and the same canonical assistant', async () => {
  const requests: HttpRequest[] = [];
  const saves: Array<{ messages: UIMessage[]; cursor?: NovelResponsesResumeCursor }> = [];
  const bodies = [
    [{ type: 'response.created', sequence_number: 0, response: { id: 'resp_original' } },
      { type: 'response.output_text.delta', sequence_number: 1, item_id: 'msg', delta: 'Hel' }],
    [{ type: 'response.output_text.delta', sequence_number: 2, item_id: 'msg', delta: 'lo' },
      { type: 'response.completed', sequence_number: 3, response: { id: 'resp_original', status: 'completed', model: 'gpt-4o',
        output: [{ type: 'message', content: [{ type: 'output_text', text: 'Hello there' }] }] } }],
  ];
  const http: HttpClient = { fetch: async () => { throw new Error('nonstream forbidden'); },
    fetchStream: async (request, opts) => {
      const attempt = requests.length; requests.push(request);
      const bytes = new TextEncoder().encode(bodies[attempt].map(event => `data: ${JSON.stringify(event)}\n\n`).join(''));
      opts.onChunk(bytes.buffer as ArrayBuffer, true);
      if (attempt === 0) throw new Error('network disconnected');
      return { status: 200, headers: {}, body: '' };
    },
  };
  const makeModel = () => {
    const resolved = runtime();
    resolved.makeProviderForOutputTokens = (_cap, _task, options) => asResponsesChatStreamProvider(createOpenAIResponsesApi({
      http, setting: makeProviderSettingOpenAI({ id: 'openai', baseUrl: 'https://api.openai.com/v1', useResponseApi: true }),
      runId: options!.runId, enableResponsesResume: true, resumeFrom: options!.resumeFrom,
      resumeMessages: options!.partialMessages, onResumeCheckpoint: options!.onCheckpoint,
    }), makeTextGenerationParams({ model: makeChatModel({ modelId: 'gpt-4o' }) }));
    return createNovelInteractiveAdapter({ createAbortController: () => new AbortController(), resolveRuntime: async () => resolved });
  };
  const checkpoint: NovelModelRequest['checkpoint'] = async (messages, cursor) => {
    saves.push(JSON.parse(JSON.stringify({ messages, cursor })));
  };
  const initial: NovelModelRequest = { ...request(checkpoint), history: [], operation: { kind: 'turn', userPrompt: '原请求' } };
  const failed = await terminal(makeModel(), initial);
  assert.equal(failed.at(-1)?.kind, 'failed'); assert.equal(requests.length, 1);
  const durable = saves.find(item => item.cursor?.sequence === 1)!;
  assert.equal(latestAssistantText(durable.messages), 'Hel');
  const assistantId = durable.messages[1].id;
  const resumed = await terminal(makeModel(), { ...initial, history: durable.messages,
    operation: { kind: 'resume_response', cursor: durable.cursor! } });
  assert.equal(resumed.at(-1)?.kind, 'completed'); assert.equal(requests.length, 2);
  assert.equal(requests[0].method, 'POST'); assert.equal(requests[1].method, 'GET');
  assert.match(requests[1].url, /responses\/resp_original\?stream=true&starting_after=1$/);
  const final = saves.at(-1)!;
  assert.equal(latestAssistantText(final.messages), 'Hello there');
  assert.equal(final.messages.length, 2); assert.equal(final.messages[1].id, assistantId);
  assert.deepEqual(final.cursor, { ...cursor, sequence: 3, terminalStatus: 'completed' });
});

test('effective Responses provider alias is frozen and its cursor remains distinct from container selection', async () => {
  const resolved = runtime(); resolved.responsesProviderId = 'effective-account';
  resolved.makeProviderForOutputTokens = (_cap, _task, options) => ({ streamText: async (_messages, onChunk) => {
    assert.equal(options!.resumeFrom!.providerId, 'effective-account'); onChunk(chunk('续')); } });
  const model = createNovelInteractiveAdapter({ createAbortController: () => new AbortController(), resolveRuntime: async () => resolved });
  const prepared = await model.prepareOrdinaryRequest!(request());
  assert.equal(prepared.runtimeSnapshot.providerId, 'openai');
  assert.equal(prepared.runtimeSnapshot.responsesProviderId, 'effective-account');
  const events = await terminal(model, { ...request(), runtimeSnapshot: prepared.runtimeSnapshot,
    operation: { kind: 'resume_response', cursor: { ...cursor, providerId: 'effective-account' } } });
  assert.equal(events.at(-1)?.kind, 'completed');
});

test('resumed tool response uses the existing executor before its authorized next POST, without a failed GET fallback', async () => {
  for (const cachedCompleted of [false, true]) {
  const resolved = runtime();
  const saved: UIMessage[][] = [];
  const stages: string[] = [];
  const tool = makeAgentTool({ name: 'novel_workspace_read', description: 'read', execute: async () => {
    stages.push('execute'); return [{ type: 'text', text: 'workspace事实', metadata: null }];
  } });
  resolved.tools = [tool];
  resolved.makeProviderForOutputTokens = (_cap, _task, options) => ({ streamText: async (_messages, onChunk) => {
    assert.equal(cachedCompleted, false, 'completed body must not repeat GET');
    assert.equal(options!.resumeFrom!.responseId, cursor.responseId); stages.push('GET');
    const output = makeAssistantMessage('');
    output.parts = [{ type: 'tool', toolCallId: 'call', toolName: tool.name, input: '{}', output: [],
      approvalState: { type: 'auto' }, metadata: null }];
    onChunk({ ...chunk(''), choices: [{ index: 0, delta: output, message: null, finishReason: null }] });
    await options!.onCheckpoint({ ...cursor, sequence: 18 });
  } });
  resolved.makeProviderForStep = (_definitions, _cap, options) => ({ streamText: async (messages, onChunk) => {
    assert.equal(options!.resumeFrom, null); stages.push('POST-after-result');
    assert.ok(messages.some(message => message.parts.some(part => part.type === 'tool'
      && part.output.some(item => item.type === 'text' && item.text === 'workspace事实'))));
    onChunk(chunk('据事实续写'));
  } });
  const model = createNovelInteractiveAdapter({ createAbortController: () => new AbortController(), resolveRuntime: async () => resolved });
  const req = { ...request(async messages => { saved.push(messages); }), toolProfile: 'all' as const };
  req.history[1].annotations = [{ type: 'generation_interrupted', reason: '用户停止生成' }];
  if (cachedCompleted) {
    req.operation = { kind: 'resume_response', cursor: { ...cursor, terminalStatus: 'completed' } };
    req.history[1].parts = [{ type: 'tool', toolCallId: 'call', toolName: tool.name, input: '{}', output: [],
      approvalState: { type: 'auto' }, metadata: null }];
  }
  const events = await terminal(model, req);
  assert.equal(events.at(-1)?.kind, 'completed');
  assert.deepEqual(stages, cachedCompleted ? ['execute', 'POST-after-result'] : ['GET', 'execute', 'POST-after-result']);
  assert.equal(saved.at(-1)!.find(message => message.id === req.history[1].id)!.annotations
    .some(annotation => annotation.type === 'generation_interrupted'), false);
  }
});

test('successful same-assistant resume clears interrupted annotation while failure retains it', async () => {
  for (const fail of [false, true]) {
    const req = request(); req.history[1].annotations = [{ type: 'generation_interrupted', reason: '用户停止生成' }];
    const saves: UIMessage[][] = []; req.checkpoint = async messages => { saves.push(messages); };
    const resolved = runtime(); resolved.makeProviderForOutputTokens = () => ({ streamText: async (_messages, onChunk) => {
      onChunk(chunk('恢复正文')); if (fail) throw new Error('再断线');
    } });
    const model = createNovelInteractiveAdapter({ createAbortController: () => new AbortController(), resolveRuntime: async () => resolved });
    const events = await terminal(model, req); assert.equal(events.at(-1)?.kind, fail ? 'failed' : 'completed');
    const last = fail ? events.filter(event => event.kind === 'snapshot').at(-1)! : { kind: 'snapshot', messages: saves.at(-1)! };
    assert.equal(last.kind === 'snapshot' && last.messages[1].annotations.some(annotation => annotation.type === 'generation_interrupted'), fail);
  }
});
