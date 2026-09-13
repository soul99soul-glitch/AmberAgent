// Independent counter-review probe for Topic C (review-c).
// Uses REAL chat-domain + MCP transport logic with in-memory fakes; no real network/model.
// Run: cd harmony/chat && npx tsx --tsconfig tsconfig.json ../../docs/reviews/2026-09-13-harmony-deep-audit/probes/probe-review-c.ts

import { runChatTurn, createMemoryConversationStore } from '../../../../harmony/chat/src/main/ets/chat/chat_turn.ts';
import type { ChatStreamProvider } from '../../../../harmony/chat/src/main/ets/chat/chat_turn.ts';
import { makeAssistant } from '../../../../harmony/chat/src/main/ets/chat/assistant.ts';
import { makeConversation, currentMessages } from '../../../../harmony/chat/src/main/ets/chat/conversation.ts';
import type { Conversation } from '../../../../harmony/chat/src/main/ets/chat/conversation.ts';
import { makeUserMessage } from '../../../../harmony/chat/src/main/ets/chat/message.ts';
import type { MessageChunk, UIMessage } from '../../../../harmony/chat/src/main/ets/chat/message.ts';
import { parseOpenAiStreamEventData } from '../../../../harmony/chat/src/main/ets/chat/openai_parse.ts';
import { buildChatCompletionRequest } from '../../../../harmony/chat/src/main/ets/chat/openai_request.ts';
import { buildClaudeMessageRequest } from '../../../../harmony/chat/src/main/ets/chat/claude_request.ts';
import { buildResponsesRequestBody } from '../../../../harmony/chat/src/main/ets/chat/openai_responses_request.ts';
import {
  makeChatModel, makeProviderSettingOpenAI, makeTextGenerationParams,
} from '../../../../harmony/chat/src/main/ets/chat/provider_model.ts';
import { makeProviderSettingClaude } from '../../../../harmony/chat/src/main/ets/chat/provider_settings.ts';
import { createOpenAIChatApi, asChatStreamProvider } from '../../../../harmony/chat/src/main/ets/chat/openai_chat_api.ts';
import {
  McpSseClientTransport, McpStreamableHttpTransport,
} from '../../../../harmony/chat/src/main/ets/chat/mcp_transports.ts';
import type {
  McpHttpPort, McpHttpResponse, McpSseEvent, McpSseStream, McpPostStreamResponse,
} from '../../../../harmony/chat/src/main/ets/chat/mcp_transports.ts';
import { McpClient } from '../../../../harmony/chat/src/main/ets/chat/mcp_protocol.ts';

const out: string[] = [];
const rec = (k: string, v: unknown): void => { out.push(`${k}: ${JSON.stringify(v)}`); };
const enc = new TextEncoder();

// ============ C01: is there a real UI/chat_turn empty-output guard? ============
// Provider that resolves WITHOUT emitting any chunk == the observable shape of a
// 200 + non-SSE body (SseAssembler yields 0 events, streamText resolves).
const silentProvider = (): ChatStreamProvider => ({
  streamText: (): Promise<void> => Promise.resolve(),
  generateText: (): Promise<MessageChunk> => Promise.resolve({
    id: '', model: '', choices: [{
      index: 0, delta: null,
      message: { id: 'm', role: 'assistant', parts: [], annotations: [],
        createdAt: '2026-07-28T00:00:00Z', finishedAt: null, modelId: null,
        usage: null, translation: null },
      finishReason: null,
    }], usage: null,
  }),
});

const probeC01Guard = async (): Promise<void> => {
  const conv: Conversation = makeConversation('c1', [], { title: 't' });
  const store = createMemoryConversationStore();
  let threw: string | null = null;
  let res: Conversation | null = null;
  try {
    res = await runChatTurn(conv, 'hi', {
      assistant: makeAssistant({ streamOutput: true }),
      inputTransformers: [], outputTransformers: [],
      provider: silentProvider(), store,
    });
  } catch (e) { threw = e instanceof Error ? e.message : String(e); }
  rec('C01.stream-empty.streamOutput=true', {
    threw,
    nodes: res !== null ? res.messageNodes.length : null,
    assistantNodes: res !== null ? currentMessages(res).filter((m) => m.role === 'assistant').length : null,
  });

  // Non-stream path (assistant.streamOutput=false) with an EMPTY message chunk.
  let threw2: string | null = null;
  let res2: Conversation | null = null;
  try {
    res2 = await runChatTurn(makeConversation('c1b', [], { title: 't' }), 'hi', {
      assistant: makeAssistant({ streamOutput: false }),
      inputTransformers: [], outputTransformers: [],
      provider: silentProvider(), store: createMemoryConversationStore(),
    });
  } catch (e) { threw2 = e instanceof Error ? e.message : String(e); }
  rec('C01.nonstream-empty.streamOutput=false', {
    threw: threw2,
    nodes: res2 !== null ? res2.messageNodes.length : null,
  });

  // End-to-end with the REAL API + fake 200 non-SSE body.
  const fakeHttp = {
    fetch: (): Promise<{ status: number; headers: Record<string, string>; body: string }> =>
      Promise.resolve({ status: 200, headers: { 'content-type': 'application/json' }, body: '{"error":{"message":"nope"}}' }),
    fetchStream: (_req: unknown, opts: { onChunk: (c: ArrayBuffer, e: boolean) => void; onDataEnd?: () => void }) => {
      opts.onChunk(enc.encode('{"error":{"message":"nope"}}').buffer as ArrayBuffer, true);
      opts.onDataEnd?.();
      return Promise.resolve({ status: 200, headers: { 'content-type': 'application/json' }, body: '' });
    },
  };
  const api = createOpenAIChatApi({
    http: fakeHttp as never,
    setting: makeProviderSettingOpenAI({ baseUrl: 'https://gw.example.com/v1' }),
  });
  const provider = asChatStreamProvider(api, () => makeTextGenerationParams({ model: makeChatModel({ modelId: 'gpt-4o' }) }));
  let threw3: string | null = null;
  let res3: Conversation | null = null;
  try {
    res3 = await runChatTurn(makeConversation('c1c', [], { title: 't' }), 'hi', {
      assistant: makeAssistant({}),
      inputTransformers: [], outputTransformers: [], provider, store: createMemoryConversationStore(),
    });
  } catch (e) { threw3 = e instanceof Error ? e.message : String(e); }
  rec('C01.e2e.real-api.200-non-sse', {
    threw: threw3,
    nodes: res3 !== null ? res3.messageNodes.length : null,
    assistantNodes: res3 !== null ? currentMessages(res3).filter((m) => m.role === 'assistant').length : null,
  });
};

// ============ C03: error:null — real parse behavior ============
const probeC03 = (): void => {
  const nullErr = 'data: {"id":"x","model":"m","choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":null}],"error":null}';
  let threw: string | null = null;
  let chunks = 0;
  try {
    const r = parseOpenAiStreamEventData(nullErr);
    chunks = r.chunks.length;
  } catch (e) { threw = e instanceof Error ? e.message : String(e); }
  rec('C03.parse.error-null', { threw, chunks });

  // 'error' key ABSENT must not throw.
  let threwAbsent: string | null = null;
  try {
    parseOpenAiStreamEventData('data: {"id":"x","model":"m","choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":null}]}');
  } catch (e) { threwAbsent = e instanceof Error ? e.message : String(e); }
  rec('C03.parse.error-absent', { threw: threwAbsent });

  // error as an OBJECT must still throw (real error channel).
  let threwObj: string | null = null;
  try {
    parseOpenAiStreamEventData('data: {"id":"x","choices":[],"error":{"message":"rate limit"}}');
  } catch (e) { threwObj = e instanceof Error ? e.message : String(e); }
  rec('C03.parse.error-object', { threw: threwObj });
};

// ============ C04: customBody stream override per protocol ============
const probeC04 = (): void => {
  const custom = [{ key: 'stream', value: false }];
  const oa = buildChatCompletionRequest({
    messages: [makeUserMessage('hi')],
    params: makeTextGenerationParams({ model: makeChatModel({ modelId: 'gpt-4o' }), customBody: custom }),
    setting: makeProviderSettingOpenAI({}), stream: true,
  });
  const cl = buildClaudeMessageRequest({
    messages: [makeUserMessage('hi')],
    params: makeTextGenerationParams({ model: makeChatModel({ modelId: 'claude-opus-4-7' }), customBody: custom }),
    setting: makeProviderSettingClaude({}), stream: true,
  });
  const rs = buildResponsesRequestBody({
    messages: [makeUserMessage('hi')],
    params: makeTextGenerationParams({ model: makeChatModel({ modelId: 'gpt-4o' }), customBody: custom }),
    setting: makeProviderSettingOpenAI({ useResponseApi: true }), stream: true,
  });
  rec('C04.stream-after-customBody', {
    openaiChat: oa['stream'], claude: cl['stream'], responses: rs['stream'],
  });
  // customBody.stream=true with caller stream=false (reverse direction)
  const oaRev = buildChatCompletionRequest({
    messages: [makeUserMessage('hi')],
    params: makeTextGenerationParams({ model: makeChatModel({ modelId: 'gpt-4o' }), customBody: [{ key: 'stream', value: true }] }),
    setting: makeProviderSettingOpenAI({}), stream: false,
  });
  rec('C04.reverse.customBody-true.caller-false.openaiChat', { stream: oaRev['stream'] });
};

// ============ MCP fakes ============
class FakeSseStream implements McpSseStream {
  private q: Array<McpSseEvent | null> = [];
  private w: Array<(e: McpSseEvent | null) => void> = [];
  cancelled = false;
  push(e: McpSseEvent): void { const w = this.w.shift(); if (w !== undefined) w(e); else this.q.push(e); }
  end(): void { const w = this.w.shift(); if (w !== undefined) w(null); else this.q.push(null); }
  next(): Promise<McpSseEvent | null> {
    const e = this.q.shift();
    if (e !== undefined) return Promise.resolve(e);
    return new Promise((r) => { this.w.push(r); });
  }
  cancel(): Promise<void> { this.cancelled = true; for (const w of this.w) w(null); this.w = []; return Promise.resolve(); }
}

class FakePort implements McpHttpPort {
  openError: Error | null = null;
  streams: FakeSseStream[] = [];
  openCalls: number = 0;
  postStreamQueue: McpPostStreamResponse[] = [];
  postStreamCalls: number = 0;
  request(): Promise<McpHttpResponse> { return Promise.resolve({ status: 200, statusDescription: '', headers: [], contentType: null, bodyText: '' }); }
  openSse(): Promise<McpSseStream> {
    this.openCalls += 1;
    if (this.openError !== null) return Promise.reject(this.openError);
    const s = new FakeSseStream(); this.streams.push(s); return Promise.resolve(s);
  }
  postStream(): Promise<McpPostStreamResponse> {
    this.postStreamCalls += 1;
    const r = this.postStreamQueue.shift();
    return Promise.resolve(r as McpPostStreamResponse);
  }
}

const tick = (): Promise<void> => new Promise((r) => { setTimeout(r, 0); });

// ============ Exclusion 10: transport.start() failure outside try in Client.connect ============
const probeExcl10 = async (): Promise<void> => {
  let closed = 0;
  let started = 0;
  const transport = {
    start: (): Promise<void> => { started += 1; return Promise.reject(new Error('boom-start')); },
    close: (): Promise<void> => { closed += 1; return Promise.resolve(); },
    send: (): Promise<void> => Promise.resolve(),
    onClose: (): void => {}, onError: (): void => {}, onMessage: (): void => {},
  };
  const client = new McpClient({ name: 't', version: '1' });
  let threw: string | null = null;
  try { await client.connect(transport as never); } catch (e) { threw = e instanceof Error ? e.message : String(e); }
  rec('EXCL10.client.connect.start-rejects', {
    threw, started, transportCloseCalled: closed, clientHasTransport: client.hasTransport,
  });

  // Real SseClientTransport: does its own start() self-clean on openSse failure?
  const port = new FakePort();
  port.openError = new Error('openSse down');
  const t = new McpSseClientTransport(port, 'http://h/sse', []);
  let closeCb = 0;
  let errCb = 0;
  t.onClose((): void => { closeCb += 1; });
  t.onError((): void => { errCb += 1; });
  let startThrew: string | null = null;
  try { await t.start(); } catch (e) { startThrew = e instanceof Error ? e.message : String(e); }
  // after a failed start, a second start() should be allowed only if initialized was reset
  let secondStartThrew: string | null = null;
  try { await t.start(); } catch (e) { secondStartThrew = e instanceof Error ? e.message : String(e); }
  rec('EXCL10.sse-transport.start-fails-selfclean', {
    startThrew, closeCb, errCb, secondStartThrew, openCalls: port.openCalls,
  });
};

// ============ Exclusion 11: Streamable SSE EOF vs Legacy SSE EOF ============
const probeExcl11 = async (): Promise<void> => {
  // Legacy SSE: endpoint then server closes the GET stream.
  const p1 = new FakePort();
  const legacy = new McpSseClientTransport(p1, 'http://h/sse', []);
  let legacyClose = 0;
  let legacyErr = 0;
  legacy.onClose((): void => { legacyClose += 1; });
  legacy.onError((): void => { legacyErr += 1; });
  const startP = legacy.start();
  await tick();
  p1.streams[0].push({ event: 'endpoint', data: '/msg', id: null });
  await startP;
  await tick();
  p1.streams[0].end(); // server closes GET stream
  await tick(); await tick();
  rec('EXCL11.legacy-sse.eof', { closeCb: legacyClose, errCb: legacyErr });

  // Streamable: initialize send returns 202 -> starts SSE session -> server closes it.
  const p2 = new FakePort();
  const st = new McpStreamableHttpTransport(p2, 'http://h/mcp', []);
  let stClose = 0;
  let stErr = 0;
  st.onClose((): void => { stClose += 1; });
  st.onError((): void => { stErr += 1; });
  await st.start();
  p2.postStreamQueue.push({ status: 202, statusDescription: '', headers: [], contentType: null, bodyText: '', lines: null });
  await st.send({ jsonrpc: '2.0', method: 'notifications/initialized' });
  await tick(); await tick();
  const openedAfterInit = p2.openCalls;
  if (p2.streams.length > 0) p2.streams[p2.streams.length - 1].end(); // server closes SSE session
  await tick(); await tick();
  // Can the transport still be used / is it marked closed?
  let subsequentSendThrew: string | null = null;
  p2.postStreamQueue.push({ status: 200, statusDescription: '', headers: [], contentType: 'application/json', bodyText: '{"jsonrpc":"2.0","id":"x","result":{}}', lines: null });
  try { await st.send({ jsonrpc: '2.0', id: 'x', method: 'ping' }); } catch (e) { subsequentSendThrew = e instanceof Error ? e.message : String(e); }
  rec('EXCL11.streamable-sse.eof', {
    openCalls: openedAfterInit, closeCb: stClose, errCb: stErr, subsequentSendThrew,
  });
};

// ============ C01b: non-stream empty chunk -> what parts land in the node? ============
const probeC01b = async (): Promise<void> => {
  const emptyAssistant: MessageChunk = {
    id: '', model: '', choices: [{
      index: 0, delta: null,
      message: { id: 'm', role: 'assistant', parts: [], annotations: [],
        createdAt: '2026-07-28T00:00:00Z', finishedAt: null, modelId: null, usage: null, translation: null },
      finishReason: null,
    }], usage: null,
  };
  const provider: ChatStreamProvider = {
    streamText: (): Promise<void> => Promise.resolve(),
    generateText: (): Promise<MessageChunk> => Promise.resolve(emptyAssistant),
  };
  const res = await runChatTurn(makeConversation('c1d', [], { title: 't' }), 'hi', {
    assistant: makeAssistant({ streamOutput: false }),
    inputTransformers: [], outputTransformers: [], provider, store: createMemoryConversationStore(),
  });
  const assistants = currentMessages(res).filter((m) => m.role === 'assistant');
  rec('C01b.nonstream-empty.parts', {
    nodes: res.messageNodes.length,
    assistantParts: assistants.map((m) => m.parts.length),
    finishedAt: assistants.map((m) => m.finishedAt),
  });
};

// ============ EXCL10b: real SseClientTransport start failure + real McpClient ============
const probeExcl10b = async (): Promise<void> => {
  const port = new FakePort();
  port.openError = new Error('openSse down');
  const t = new McpSseClientTransport(port, 'http://h/sse', []);
  const client = new McpClient({ name: 't', version: '1' });
  let threw: string | null = null;
  try { await client.connect(t); } catch (e) { threw = e instanceof Error ? e.message : String(e); }
  rec('EXCL10b.real-sse.connect-openSse-fail', {
    threw, clientHasTransport: client.hasTransport,
    // if hasTransport stays true, manager.sync()/listTools path would skip reconnect
  });
};

const main = async (): Promise<void> => {
  await probeC01Guard();
  await probeC01b();
  probeC03();
  probeC04();
  await probeExcl10();
  await probeExcl10b();
  await probeExcl11();
  for (const line of out) console.log(line);
};
main().catch((e): void => { console.error('PROBE ERROR', e); process.exitCode = 1; });
