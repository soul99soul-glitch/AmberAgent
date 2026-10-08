import test from 'node:test';
import assert from 'node:assert/strict';
import type { HttpClient, HttpResponse } from '@amber/deepread-domain';
import type { JsonObject } from '../main/ets/chat/json.ts';
import type { MessageChunk, UIMessage, UIMessagePartTool } from '../main/ets/chat/message.ts';
import { makeAssistantMessage, makeUserMessage, toText } from '../main/ets/chat/message.ts';
import { parseGoogleMessagePart, parseGoogleResponseBody } from '../main/ets/chat/google_parse.ts';
import { buildGoogleContents } from '../main/ets/chat/google_request.ts';
import { createGoogleChatApi, asGoogleChatStreamProvider } from '../main/ets/chat/google_chat_api.ts';
import { makeProviderSettingGoogle } from '../main/ets/chat/provider_settings.ts';
import { makeChatModel, makeTextGenerationParams } from '../main/ets/chat/provider_model.ts';
import { MessageStreamAccumulator } from '../main/ets/chat/stream_accumulator.ts';
import { parseMessageList, serializeMessageList } from '../main/ets/chat/serialize.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import { makeConversation } from '../main/ets/chat/conversation.ts';
import { createMemoryConversationStore } from '../main/ets/chat/chat_turn.ts';
import { runChatTurnWithTools } from '../main/ets/chat/tool_loop.ts';
import { makeAgentTool, makeInputSchemaObj } from '../main/ets/chat/tool.ts';

const params = makeTextGenerationParams({ model: makeChatModel({ modelId: 'gemini-3-pro' }) });
const messages = [makeUserMessage('run')];
const candidate = (parts: JsonObject[], finishReason?: string): JsonObject => ({
  candidates: [{ content: { parts }, ...(finishReason === undefined ? {} : { finishReason }) }],
});
const fixture = (events: JsonObject[], body: JsonObject = events[0]) => {
  const http: HttpClient = {
    fetch: async (): Promise<HttpResponse> => ({ status: 200, headers: {}, body: JSON.stringify(body) }),
    fetchStream: async (_request, opts): Promise<HttpResponse> => {
      for (const event of events) {
        const bytes = new TextEncoder().encode(`data: ${JSON.stringify(event)}\n\n`);
        opts.onChunk(bytes.buffer as ArrayBuffer, false);
        if (opts.shouldStop?.()) break;
      }
      opts.onChunk(new ArrayBuffer(0), true);
      return { status: 200, headers: {}, body: '' };
    },
  };
  return createGoogleChatApi({ http, setting: makeProviderSettingGoogle({ apiKey: 'fixture' }) });
};

test('Gemini wire call IDs and no-args survive persistence and round-trip call/response', () => {
  const tool = parseGoogleMessagePart({ functionCall: { id: 'wire/opaque-1', name: 'ping' },
    thoughtSignature: 'signature' }) as UIMessagePartTool;
  assert.equal(tool.toolCallId, 'wire/opaque-1');
  assert.equal(tool.input, '{}');
  tool.output = [{ type: 'text', text: 'pong', metadata: null }];
  const restored = parseMessageList(serializeMessageList([{ ...makeAssistantMessage(''), parts: [tool] }]));
  const contents = buildGoogleContents(restored);
  assert.deepEqual(contents, [
    { role: 'model', parts: [{ functionCall: { id: 'wire/opaque-1', name: 'ping', args: {} }, thoughtSignature: 'signature' }] },
    { role: 'user', parts: [{ functionResponse: { id: 'wire/opaque-1', name: 'ping', response: { result: 'pong' } } }] },
  ]);
  const legacy = parseGoogleMessagePart({ functionCall: { name: 'ping', args: {} } }) as UIMessagePartTool;
  legacy.output = tool.output;
  const oldContents = buildGoogleContents([{ ...makeAssistantMessage(''), parts: [legacy] }]);
  assert.equal((oldContents[0].parts as JsonObject[])[0].functionCall &&
    ((oldContents[0].parts as JsonObject[])[0].functionCall as JsonObject).id, undefined);
});

test('Gemini same-name calls across events stay distinct, repeated wire ID replaces full arguments', async () => {
  const api = fixture([
    candidate([{ functionCall: { id: 'a', name: 'search', args: { q: 'first' } } }]),
    candidate([{ functionCall: { id: 'b', name: 'search', args: { q: 'second' } } }]),
    candidate([{ functionCall: { id: 'a', name: 'search', args: { q: 'updated' } } }], 'STOP'),
  ]);
  const acc = new MessageStreamAccumulator([...messages, makeAssistantMessage('')]);
  await api.streamText(messages, params, chunk => acc.append(chunk));
  const tools = acc.snapshot().at(-1)!.parts.filter((part): part is UIMessagePartTool => part.type === 'tool');
  assert.deepEqual(tools.map(tool => [tool.toolCallId, tool.toolName, tool.input]),
    [['a', 'search', '{"q":"updated"}'], ['b', 'search', '{"q":"second"}']]);
});

test('Gemini failed stream keeps partial text and never publishes tools for speculative execution', async () => {
  const api = fixture([
    candidate([{ text: 'partial ' }, { functionCall: { id: 'unsafe', name: 'write', args: {} } }]),
    candidate([{ text: 'tail' }, { functionCall: { id: 'unsafe2', name: 'write', args: {} } }], 'SAFETY'),
    candidate([{ text: 'must not consume' }], 'STOP'),
  ]);
  const acc = new MessageStreamAccumulator([...messages, makeAssistantMessage('')]);
  const published: MessageChunk[] = [];
  await assert.rejects(api.streamText(messages, params, chunk => { published.push(chunk); acc.append(chunk); }), /SAFETY/);
  assert.equal(toText(acc.snapshot().at(-1)!), 'partial tail');
  assert.equal(published.flatMap(chunk => chunk.choices.flatMap(choice => choice.delta?.parts ?? []))
    .filter(part => part.type === 'tool').length, 0);
});

test('Gemini nonstream failed body carries safe partial chunk, including latest tail', async () => {
  const body = candidate([{ text: 'partial' }, { functionCall: { name: 'write', args: {} } }], 'RECITATION');
  await assert.rejects(fixture([], body).generateText(messages, params), (error: Error & { partialChunk?: MessageChunk | null }) => {
    assert.match(error.message, /RECITATION/);
    assert.ok(error.partialChunk);
    assert.equal(toText(error.partialChunk.choices[0].message!), 'partial');
    assert.equal(error.partialChunk.choices[0].message!.parts.some(part => part.type === 'tool'), false);
    return true;
  });
});

for (const reason of ['STOP', 'MAX_TOKENS', 'FINISH_REASON_UNSPECIFIED', 'future_reason']) {
  test(`Gemini keeps existing successful/truncated/unknown semantics: ${reason}`, async () => {
    const body = candidate([{ text: 'ok' }, { functionCall: { name: 'ping' } }], reason);
    const chunk = parseGoogleResponseBody(body, 'gemini-3-pro');
    assert.equal(chunk.choices[0].finishReason, reason);
    const acc = new MessageStreamAccumulator([...messages, makeAssistantMessage('')]);
    await fixture([body]).streamText(messages, params, item => acc.append(item));
    assert.equal(toText(acc.snapshot().at(-1)!).trimEnd(), 'ok');
    assert.equal(acc.snapshot().at(-1)!.parts.filter(part => part.type === 'tool').length, 1);
  });
}

test('Gemini old endpoints without wire IDs keep same-name calls across events and flush on normal EOF', async () => {
  const api = fixture([
    candidate([{ functionCall: { name: 'search', args: { q: 'first' } } }]),
    candidate([{ functionCall: { name: 'search', args: { q: 'second' } } }]),
  ]);
  const acc = new MessageStreamAccumulator([...messages, makeAssistantMessage('')]);
  await api.streamText(messages, params, chunk => acc.append(chunk));
  const tools = acc.snapshot().at(-1)!.parts.filter((part): part is UIMessagePartTool => part.type === 'tool');
  assert.deepEqual(tools.map(tool => [tool.toolName, tool.input]),
    [['search', '{"q":"first"}'], ['search', '{"q":"second"}']]);
  assert.notEqual(tools[0].toolCallId, tools[1].toolCallId);
});

test('Gemini cancellation after receiving calls never publishes buffered calls', async () => {
  const signal = { aborted: false };
  const body = candidate([{ text: 'partial' }, { functionCall: { name: 'write', args: {} } }]);
  const http: HttpClient = {
    fetch: async (): Promise<HttpResponse> => ({ status: 200, headers: {}, body: '' }),
    fetchStream: async (_request, opts): Promise<HttpResponse> => {
      const bytes = new TextEncoder().encode(`data: ${JSON.stringify(body)}\n\n`);
      opts.onChunk(bytes.buffer as ArrayBuffer, false);
      signal.aborted = true;
      return { status: 200, headers: {}, body: '' };
    },
  };
  const api = createGoogleChatApi({ http, setting: makeProviderSettingGoogle({ apiKey: 'fixture' }) });
  const acc = new MessageStreamAccumulator([...messages, makeAssistantMessage('')]);
  await assert.rejects(api.streamText(messages, params, chunk => acc.append(chunk), { signal }), { name: 'AbortError' });
  assert.equal(toText(acc.snapshot().at(-1)!), 'partial');
  assert.equal(acc.snapshot().at(-1)!.parts.some(part => part.type === 'tool'), false);
});

for (const streamOutput of [true, false]) {
  test(`Gemini real tool loop rejects failed terminal, publishes partial, and executes zero tools (stream=${streamOutput})`, async () => {
    const body = candidate([{ text: 'retained partial' }, { functionCall: { id: 'unsafe', name: 'get_time_info', args: {} } }], 'SAFETY');
    const provider = asGoogleChatStreamProvider(fixture([body]), () => params);
    const snapshots: UIMessage[][] = [];
    let executed = 0;
    const tool = makeAgentTool({
      name: 'get_time_info', description: 'fixture', parameters: () => makeInputSchemaObj({}),
      execute: async () => { executed++; return [{ type: 'text', text: 'unexpected', metadata: null }]; },
    });
    await assert.rejects(runChatTurnWithTools(makeConversation('google-failed', []), 'run', {
      assistant: makeAssistant({ streamOutput }), inputTransformers: [], outputTransformers: [],
      provider, store: createMemoryConversationStore(), flushIntervalMs: 0,
      onRawFlushSnapshot: snapshot => snapshots.push(snapshot),
    }, { tools: [tool], makeProviderForStep: () => provider, speculativeEnabled: true, autoApproveTools: true }), /SAFETY/);
    assert.equal(executed, 0);
    const last = snapshots.at(-1)!.at(-1)!;
    assert.equal(toText(last), 'retained partial');
    assert.equal(last.parts.some(part => part.type === 'tool'), false);
  });
}
