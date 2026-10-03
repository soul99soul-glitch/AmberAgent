import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { HttpClient, HttpRequest } from '@amber/deepread-domain';
import { createOpenAIResponsesApi } from '../main/ets/chat/openai_responses_api.ts';
import type { ResponseCursor } from '../main/ets/chat/openai_responses_request.ts';
import type { MessageChunk } from '../main/ets/chat/message.ts';
import { makeAssistantMessage, makeUserMessage } from '../main/ets/chat/message.ts';
import { makeChatModel, makeProviderSettingOpenAI, makeTextGenerationParams } from '../main/ets/chat/provider_model.ts';

const setting = () => makeProviderSettingOpenAI({ id: 'openai', baseUrl: 'https://api.openai.com/v1',
  useResponseApi: true, authMode: 'api_key' });
const params = () => makeTextGenerationParams({ model: makeChatModel({ modelId: 'gpt-4o' }) });
const cursor: ResponseCursor = { responseId: 'resp_same', sequence: 17, providerId: 'openai' };
const messages = () => [makeUserMessage('写正文'), makeAssistantMessage('Hel')];
const text = (chunk: MessageChunk): string => chunk.choices.flatMap(choice => (choice.delta ?? choice.message)?.parts ?? [])
  .map(part => part.type === 'text' ? part.text : '').join('');
const completed = (sequence: number, content: string) => ({ type: 'response.completed', sequence_number: sequence,
  response: { id: 'resp_same', model: 'gpt-4o', status: 'completed',
    output: [{ type: 'message', content: [{ type: 'output_text', text: content }] }] } });
const fixture = (events: object[], disconnect = false, status = 200) => {
  const requests: HttpRequest[] = [];
  const bytes = new TextEncoder().encode(events.map(event => `data: ${JSON.stringify(event)}\n\n`).join(''));
  const http: HttpClient = {
    fetch: async () => { throw new Error('nonstream path forbidden'); },
    fetchStream: async (request, opts) => {
      requests.push(request); opts.onChunk(bytes.buffer as ArrayBuffer, true);
      if (disconnect) throw new Error('connection dropped');
      return { status, headers: {}, body: status === 404 ? '{"error":{"message":"response expired"}}' : '' };
    },
  };
  return { http, requests };
};

test('actual resume HTTP is GET for the same response ID and durable cursor, without POST body', async () => {
  const f = fixture([{ type: 'response.output_text.delta', sequence_number: 18, item_id: 'msg', delta: 'lo' },
    completed(19, 'Hello there')]);
  const saved: Array<{ cursor: ResponseCursor; text: string }> = [];
  let visible = '';
  const api = createOpenAIResponsesApi({ http: f.http, setting: setting(), enableResponsesResume: true,
    runId: 'original-run', resumeFrom: cursor, resumeMessages: messages(), headers: () => ({ Authorization: 'Bearer test' }),
    onResumeCheckpoint: async value => { saved.push({ cursor: value, text: visible }); },
  });
  await api.streamText(messages(), params(), chunk => { visible += text(chunk); });
  assert.equal(f.requests.length, 1);
  assert.equal(f.requests[0].method, 'GET');
  assert.equal(f.requests[0].url, 'https://api.openai.com/v1/responses/resp_same?stream=true&starting_after=17');
  assert.equal(f.requests[0].body, undefined);
  assert.equal(f.requests[0].headers.Authorization, 'Bearer test');
  assert.equal(visible, 'lo there');
  assert.deepEqual(saved, [{ cursor: { ...cursor, sequence: 18 }, text: 'lo' },
    { cursor: { ...cursor, sequence: 19, terminalStatus: 'completed' }, text: 'lo there' }]);
});

test('initial resumable generation explicitly posts background/store true and checkpoints created ID', async () => {
  const f = fixture([{ type: 'response.created', sequence_number: 0, response: { id: 'resp_same' } },
    { type: 'response.output_text.delta', sequence_number: 1, item_id: 'msg', delta: 'Hello' }, completed(2, 'Hello')]);
  const saved: Array<{ cursor: ResponseCursor; text: string }> = [];
  let visible = '';
  const api = createOpenAIResponsesApi({ http: f.http, setting: setting(), enableResponsesResume: true,
    runId: 'run', onResumeCheckpoint: async value => { saved.push({ cursor: value, text: visible }); },
  });
  await api.streamText([makeUserMessage('写正文')], params(), chunk => { visible += text(chunk); });
  const body = JSON.parse(f.requests[0].body!);
  assert.equal(f.requests[0].method, 'POST'); assert.equal(body.background, true);
  assert.equal(body.stream, true); assert.equal(body.store, true);
  assert.deepEqual(saved.map(value => value.cursor), [0, 1, 2].map(sequence => ({ ...cursor, sequence,
    ...(sequence === 2 ? { terminalStatus: 'completed' } : {}) })));
  assert.equal(saved[1].text, 'Hello');
});

test('resumable SSE processing waits for canonical/cursor checkpoint before consuming the next event', async () => {
  const f = fixture([{ type: 'response.created', sequence_number: 0, response: { id: 'resp_same' } },
    { type: 'response.output_text.delta', sequence_number: 1, item_id: 'msg', delta: '正文' }, completed(2, '正文')]);
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  const saved: number[] = [];
  let visible = '';
  const api = createOpenAIResponsesApi({ http: f.http, setting: setting(), enableResponsesResume: true,
    runId: 'run', onResumeCheckpoint: async value => {
      saved.push(value.sequence); if (value.sequence === 0) await gate;
    },
  });
  const result = api.streamText([makeUserMessage('写')], params(), chunk => { visible += text(chunk); });
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(saved, [0]); assert.equal(visible, '');
  release(); await result;
  assert.deepEqual(saved, [0, 1, 2]); assert.equal(visible, '正文');
});

test('disconnect preserves the last parsed partial/cursor and never posts a fresh generation', async () => {
  const f = fixture([{ type: 'response.output_text.delta', sequence_number: 18, item_id: 'msg', delta: 'lo' }], true);
  const saved: number[] = [];
  let visible = '';
  const api = createOpenAIResponsesApi({ http: f.http, setting: setting(), enableResponsesResume: true,
    runId: 'run', resumeFrom: cursor, resumeMessages: messages(),
    onResumeCheckpoint: async value => { saved.push(value.sequence); assert.equal(visible, 'lo'); },
  });
  await assert.rejects(api.streamText(messages(), params(), chunk => { visible += text(chunk); }), /connection dropped/);
  assert.deepEqual(saved, [18]); assert.equal(f.requests.length, 1); assert.equal(f.requests[0].method, 'GET');
});

test('checkpoint failure blocks later chunks and cursor advancement', async () => {
  const f = fixture([{ type: 'response.created', sequence_number: 0, response: { id: 'resp_same' } },
    { type: 'response.output_text.delta', sequence_number: 1, item_id: 'msg', delta: '不得继续' }, completed(2, '不得继续')]);
  const saved: number[] = [];
  let visible = '';
  const api = createOpenAIResponsesApi({ http: f.http, setting: setting(), enableResponsesResume: true,
    runId: 'run', onResumeCheckpoint: async value => { saved.push(value.sequence); throw new Error('atomic checkpoint failed'); },
  });
  await assert.rejects(api.streamText(messages(), params(), chunk => { visible += text(chunk); }), /atomic checkpoint failed/);
  assert.deepEqual(saved, [0]); assert.equal(visible, ''); assert.equal(f.requests.length, 1);
});

test('GET resume rejects unsupported provider, disabled resume and wrong provider without sending anything', async () => {
  for (const changes of [{ setting: makeProviderSettingOpenAI({ ...setting(), baseUrl: 'https://compatible.test/v1' }) },
    { enableResponsesResume: false }, { resumeFrom: { ...cursor, providerId: 'other' } }]) {
    const f = fixture([completed(18, 'Hello')]);
    const api = createOpenAIResponsesApi({ http: f.http, setting: setting(), enableResponsesResume: true,
      runId: 'run', resumeFrom: cursor, onResumeCheckpoint: async () => {}, ...changes });
    await assert.rejects(api.streamText(messages(), params(), () => {}));
    assert.equal(f.requests.length, 0);
  }
});

test('invalid ID or sequence is rejected before HTTP, with no fallback', async () => {
  for (const changes of [{ responseId: 'resp_same/../new' }, { responseId: '' }, { sequence: -1 },
    { sequence: 0.5 }, { sequence: NaN }]) {
    const f = fixture([]);
    const api = createOpenAIResponsesApi({ http: f.http, setting: setting(), enableResponsesResume: true,
      runId: 'run', resumeFrom: { ...cursor, ...changes }, onResumeCheckpoint: async () => {} });
    await assert.rejects(api.streamText(messages(), params(), () => {})); assert.equal(f.requests.length, 0);
  }
});

test('server expired response and terminal failure propagate after exactly one GET', async () => {
  for (const terminalFailure of [false, true]) {
    const f = terminalFailure ? fixture([{ type: 'response.failed', sequence_number: 18,
      response: { id: 'resp_same', status: 'failed', error: { message: 'upstream unavailable' }, output: [] } }])
      : fixture([], false, 404);
    const api = createOpenAIResponsesApi({ http: f.http, setting: setting(), enableResponsesResume: true,
      runId: 'run', resumeFrom: cursor, onResumeCheckpoint: async () => {} });
    await assert.rejects(api.streamText(messages(), params(), () => {}), terminalFailure ? /upstream unavailable/ : /expired/);
    assert.equal(f.requests.length, 1); assert.equal(f.requests[0].method, 'GET');
  }
});

test('resumed stream ignores already committed sequence numbers and requires explicit terminal event', async () => {
  const f = fixture([{ type: 'response.output_text.delta', sequence_number: 17, item_id: 'msg', delta: '重复' },
    { type: 'response.output_text.delta', sequence_number: 18, item_id: 'msg', delta: 'lo' }]);
  let visible = '';
  const saved: number[] = [];
  const api = createOpenAIResponsesApi({ http: f.http, setting: setting(), enableResponsesResume: true,
    runId: 'run', resumeFrom: cursor, resumeMessages: messages(), onResumeCheckpoint: async value => { saved.push(value.sequence); } });
  await assert.rejects(api.streamText(messages(), params(), chunk => { visible += text(chunk); }), /terminal/);
  assert.equal(visible, 'lo'); assert.deepEqual(saved, [18]);
});

test('created response without real ID/sequence fails instead of inventing resumability', async () => {
  for (const event of [{ type: 'response.created', response: { id: 'resp_same' } },
    { type: 'response.created', sequence_number: 0, response: {} },
    { type: 'response.output_text.delta', sequence_number: 1, item_id: 'msg', delta: '没有来源ID' }]) {
    const f = fixture([event, completed(2, 'text')]);
    const api = createOpenAIResponsesApi({ http: f.http, setting: setting(), enableResponsesResume: true,
      runId: 'run', onResumeCheckpoint: async () => {} });
    await assert.rejects(api.streamText(messages(), params(), () => {}));
  }
});

test('terminal cursor is checkpointed after complete tool content reaches the canonical accumulator', async () => {
  const f = fixture([{ type: 'response.created', sequence_number: 0, response: { id: 'resp_same' } },
    { type: 'response.output_item.added', sequence_number: 1, item: { type: 'function_call', id: 'fc',
      call_id: 'call', name: 'novel_workspace_read', arguments: '' } },
    { type: 'response.function_call_arguments.delta', sequence_number: 2, item_id: 'fc', delta: '{"path":"p"}' },
    { type: 'response.completed', sequence_number: 3, response: { id: 'resp_same', status: 'completed', model: 'gpt-4o',
      output: [{ type: 'function_call', id: 'fc', call_id: 'call', name: 'novel_workspace_read', arguments: '{"path":"p"}' }] } }]);
  const checkpointed: Array<{ sequence: number; toolCount: number }> = [];
  let toolCount = 0;
  const api = createOpenAIResponsesApi({ http: f.http, setting: setting(), enableResponsesResume: true, runId: 'run',
    onResumeCheckpoint: async value => { checkpointed.push({ sequence: value.sequence, toolCount }); } });
  await api.streamText([makeUserMessage('read')], params(), chunk => {
    toolCount += chunk.choices.flatMap(choice => choice.delta?.parts ?? []).filter(part => part.type === 'tool').length;
  });
  assert.equal(checkpointed.find(item => item.sequence === 2)!.toolCount, 0);
  assert.equal(checkpointed.find(item => item.sequence === 3)!.toolCount, 1);
});

test('completed cursor requires a validated completed response status', async () => {
  const event = completed(18, 'Hello'); event.response.status = 'in_progress';
  const f = fixture([event]); const saved: ResponseCursor[] = [];
  const api = createOpenAIResponsesApi({ http: f.http, setting: setting(), enableResponsesResume: true,
    runId: 'run', resumeFrom: cursor, onResumeCheckpoint: async value => { saved.push(value); } });
  await assert.rejects(api.streamText(messages(), params(), () => {}), /completed.*status/);
  assert.equal(saved.some(value => value.terminalStatus === 'completed'), false);
});
