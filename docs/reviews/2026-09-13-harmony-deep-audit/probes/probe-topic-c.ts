// Topic C probe — isolated reproduction of candidate findings.
// Run: cd harmony/chat && npx tsx probe-topic-c.ts
// No real network: all HttpClient/port implementations are in-memory fakes.

import { SseAssembler } from '@amber/deepread-domain';
import { createOpenAIChatApi } from '../../../../harmony/chat/src/main/ets/chat/openai_chat_api.ts';
import { createClaudeChatApi } from '../../../../harmony/chat/src/main/ets/chat/claude_chat_api.ts';
import { createGoogleChatApi } from '../../../../harmony/chat/src/main/ets/chat/google_chat_api.ts';
import { buildClaudeMessageRequest } from '../../../../harmony/chat/src/main/ets/chat/claude_request.ts';
import { buildChatCompletionRequest } from '../../../../harmony/chat/src/main/ets/chat/openai_request.ts';
import { buildResponsesRequestBody } from '../../../../harmony/chat/src/main/ets/chat/openai_responses_request.ts';
import { createOpenAIResponsesApi } from '../../../../harmony/chat/src/main/ets/chat/openai_responses_api.ts';
import {
  makeProviderSettingOpenAI, makeChatModel, makeTextGenerationParams,
} from '../../../../harmony/chat/src/main/ets/chat/provider_model.ts';
import {
  makeProviderSettingClaude, makeProviderSettingGoogle,
} from '../../../../harmony/chat/src/main/ets/chat/provider_settings.ts';
import { makeUserMessage } from '../../../../harmony/chat/src/main/ets/chat/message.ts';

const enc = new TextEncoder();

const results: string[] = [];
const record = (name: string, value: unknown): void => {
  results.push(`${name}: ${JSON.stringify(value)}`);
};

// Fake HttpClient that replays one raw body chunk with end=true, status 200.
const rawBodyHttp = (body: string, captured: { body?: string; url?: string }) => ({
  fetch: (req: { url: string; body?: string }) => {
    captured.url = req.url;
    captured.body = req.body;
    return Promise.resolve({ status: 200, headers: {}, body });
  },
  fetchStream: (req: { url: string; body?: string }, opts: {
    onChunk: (c: ArrayBuffer, end: boolean) => void; onDataEnd?: () => void;
  }) => {
    captured.url = req.url;
    captured.body = req.body;
    opts.onChunk(enc.encode(body).buffer as ArrayBuffer, true);
    opts.onDataEnd?.();
    return Promise.resolve({ status: 200, headers: {}, body });
  },
});

const params = makeTextGenerationParams({ model: makeChatModel({ modelId: 'gpt-4o' }) });

// ===== P1 probe: HTTP 200 + non-SSE body is silently accepted (no error, no chunk) =====
const probeP1 = async (): Promise<void> => {
  const jsonErr = '{"error":{"message":"model not found","type":"invalid_request_error"}}';

  const cap1: { body?: string } = {};
  const openai = createOpenAIChatApi({
    http: rawBodyHttp(jsonErr, cap1),
    setting: makeProviderSettingOpenAI({}),
  });
  const chunks1: unknown[] = [];
  let threw1: string | null = null;
  try {
    await openai.streamText([makeUserMessage('hi')], params,
      (c): void => { chunks1.push(c); });
  } catch (e) {
    threw1 = e instanceof Error ? e.message : String(e);
  }
  record('P1.openai.200-non-sse', { threw: threw1, chunks: chunks1.length });

  const cap2: { body?: string } = {};
  const claude = createClaudeChatApi({
    http: rawBodyHttp(jsonErr, cap2),
    setting: makeProviderSettingClaude({}),
  });
  const chunks2: unknown[] = [];
  let threw2: string | null = null;
  try {
    await claude.streamText([makeUserMessage('hi')], params,
      (c): void => { chunks2.push(c); });
  } catch (e) {
    threw2 = e instanceof Error ? e.message : String(e);
  }
  record('P1.claude.200-non-sse', { threw: threw2, chunks: chunks2.length });

  const cap3: { body?: string } = {};
  const google = createGoogleChatApi({
    http: rawBodyHttp(jsonErr, cap3),
    setting: makeProviderSettingGoogle({ vertexAI: false, authMode: 'api_key' }),
  });
  const chunks3: unknown[] = [];
  let threw3: string | null = null;
  try {
    await google.streamText([makeUserMessage('hi')], params,
      (c): void => { chunks3.push(c); });
  } catch (e) {
    threw3 = e instanceof Error ? e.message : String(e);
  }
  record('P1.google.200-non-sse', { threw: threw3, chunks: chunks3.length });

  // control: an SSE body carrying the same error IS surfaced
  const sseErr = `data: ${jsonErr}\n\n`;
  const ctl = createOpenAIChatApi({
    http: rawBodyHttp(sseErr, {}),
    setting: makeProviderSettingOpenAI({}),
  });
  let threwCtl: string | null = null;
  try {
    await ctl.streamText([makeUserMessage('hi')], params, (): void => {});
  } catch (e) {
    threwCtl = e instanceof Error ? e.message : String(e);
  }
  record('P1.control.sse-error-surfaced', { threw: threwCtl });
};

// ===== P2 probe: customBody "stream" override diverges per provider =====
const probeP2 = (): void => {
  const user = [makeUserMessage('hi')];
  const custom = [{ key: 'stream', value: false }];

  const oa = buildChatCompletionRequest({
    messages: user,
    params: makeTextGenerationParams({
      model: makeChatModel({ modelId: 'gpt-4o' }), customBody: custom,
    }),
    setting: makeProviderSettingOpenAI({}),
    stream: true,
  });
  record('P2.openai.chat.stream', oa['stream']);

  const resp = buildResponsesRequestBody({
    messages: user,
    params: makeTextGenerationParams({
      model: makeChatModel({ modelId: 'gpt-4o' }), customBody: custom,
    }),
    setting: makeProviderSettingOpenAI({ useResponseApi: true }),
    stream: true,
  });
  record('P2.openai.responses.stream', resp['stream']);

  const cl = buildClaudeMessageRequest({
    messages: user,
    params: makeTextGenerationParams({
      model: makeChatModel({ modelId: 'claude-opus-4-7' }), customBody: custom,
    }),
    setting: makeProviderSettingClaude({}),
    stream: true,
  });
  record('P2.claude.stream', cl['stream']);
};

// ===== P2b probe: Claude streamText with overridden stream=false yields silent empty =====
const probeP2b = async (): Promise<void> => {
  // Server obeys body.stream=false: returns one non-SSE JSON message.
  const oneShot = JSON.stringify({
    id: 'msg_1', model: 'claude', stop_reason: 'end_turn',
    content: [{ type: 'text', text: 'hello' }],
  });
  const cap: { body?: string } = {};
  const api = createClaudeChatApi({
    http: rawBodyHttp(oneShot, cap),
    setting: makeProviderSettingClaude({}),
  });
  const clParams = makeTextGenerationParams({
    model: makeChatModel({ modelId: 'claude-opus-4-7' }),
    customBody: [{ key: 'stream', value: false }],
  });
  const chunks: unknown[] = [];
  let threw: string | null = null;
  try {
    await api.streamText([makeUserMessage('hi')], clParams, (c): void => { chunks.push(c); });
  } catch (e) {
    threw = e instanceof Error ? e.message : String(e);
  }
  record('P2b.claude.customBodyStreamFalse', {
    sentStream: JSON.parse(cap.body ?? '{}')['stream'], threw, chunks: chunks.length,
  });
};

// ===== P3 probe: Responses resume cursor is never serialized =====
const probeP3 = (): void => {
  const body = buildResponsesRequestBody({
    messages: [makeUserMessage('hi')],
    params: makeTextGenerationParams({ model: makeChatModel({ modelId: 'gpt-4o' }) }),
    setting: makeProviderSettingOpenAI({ baseUrl: 'https://api.openai.com/v1', useResponseApi: true }),
    stream: true,
    resume: {
      runId: 'run-1',
      resumeFrom: { responseId: 'resp_prev', sequence: 7, providerId: 'openai-default' },
    },
  });
  record('P3.responses.resume-body', {
    store: body['store'],
    hasPreviousResponseId: Object.prototype.hasOwnProperty.call(body, 'previous_response_id'),
    previousResponseId: body['previous_response_id'] ?? null,
  });
};

// ===== P3b probe: streamText with resume+runId writes cursor but never resumes/clears =====
const probeP3b = async (): Promise<void> => {
  const saved: Array<{ runId: string; responseId: string; seq: number }> = [];
  let loads = 0;
  let clears = 0;
  const store = {
    save: async (runId: string, responseId: string, seq: number): Promise<void> => {
      saved.push({ runId, responseId, seq });
    },
    load: async (): Promise<null> => { loads += 1; return null; },
    clear: async (): Promise<void> => { clears += 1; },
  };
  const sse = [
    'event: response.output_text.delta',
    'data: {"type":"response.output_text.delta","item_id":"msg_1","delta":"Hi"}',
    '',
    'event: response.completed',
    'data: {"type":"response.completed","response":{"id":"resp_new","model":"gpt-4o","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Hi"}]}]}}',
    '',
  ].join('\n');
  const cap: { body?: string } = {};
  const api = createOpenAIResponsesApi({
    http: rawBodyHttp(sse, cap),
    setting: makeProviderSettingOpenAI({ baseUrl: 'https://api.openai.com/v1', useResponseApi: true }),
    resumeStore: store,
    enableResponsesResume: true,
    runId: 'run-1',
  });
  await api.streamText([makeUserMessage('hi')],
    makeTextGenerationParams({ model: makeChatModel({ modelId: 'gpt-4o' }) }),
    (): void => {});
  const sentBody = JSON.parse(cap.body ?? '{}') as Record<string, unknown>;
  record('P3b.responses.resume-runtime', {
    loads, saves: saved.length, clears,
    sentStore: sentBody['store'],
    sentPreviousResponseId: sentBody['previous_response_id'] ?? null,
  });
};

// ===== P4 probe: terminal/error SSE event does NOT cancel the underlying HTTP stream =====
// Android cancel(): on error/[DONE] the EventSource is cancelled immediately.
// Harmony Chat API has no port-level cancel; fetchStream keeps running until dataEnd/signal.
const probeP4 = async (): Promise<void> => {
  const makeNeverEndingHttp = (body: string, onStarted: () => void) => ({
    fetch: (): Promise<{ status: number; headers: Record<string, string>; body: string }> =>
      Promise.resolve({ status: 200, headers: {}, body: '' }),
    fetchStream: (_req: unknown, opts: {
      onChunk: (c: ArrayBuffer, end: boolean) => void;
      onDataEnd?: () => void;
      signal?: { aborted: boolean };
    }): Promise<{ status: number; headers: Record<string, string>; body: string }> => {
      // deliver the error frame, then keep the connection open forever (no dataEnd)
      opts.onChunk(enc.encode(body).buffer as ArrayBuffer, false);
      onStarted();
      return new Promise(() => { /* never settles */ });
    },
  });

  const sseErr = 'data: {"error":{"message":"rate limit","type":"rate_limit_error"}}\n\n';
  let started = false;
  const api = createOpenAIChatApi({
    http: makeNeverEndingHttp(sseErr, (): void => { started = true; }),
    setting: makeProviderSettingOpenAI({}),
  });
  let settled = false;
  const run = api.streamText([makeUserMessage('hi')], params, (): void => {})
    .then((): void => { settled = true; })
    .catch((): void => { settled = true; });
  await new Promise((r): void => { setTimeout(r, 60); });
  record('P4.openai.error-then-idle', { started, settledWithin60ms: settled });
  void run;

  // Control: [DONE] then connection stays open
  let started2 = false;
  const cap2: { body?: string } = {};
  const api2 = createOpenAIChatApi({
    http: makeNeverEndingHttp('data: [DONE]\n\n', (): void => { started2 = true; }),
    setting: makeProviderSettingOpenAI({}),
  });
  cap2.body = 'x';
  let settled2 = false;
  const run2 = api2.streamText([makeUserMessage('hi')], params, (): void => {})
    .then((): void => { settled2 = true; })
    .catch((): void => { settled2 = true; });
  await new Promise((r): void => { setTimeout(r, 60); });
  record('P4.openai.done-then-idle', { started: started2, settledWithin60ms: settled2 });
  void run2;
};

// ===== P5 probe: Google stream on data: [DONE] sentinel =====
const probeP5 = async (): Promise<void> => {
  const cap: { body?: string } = {};
  const api = createGoogleChatApi({
    http: rawBodyHttp('data: [DONE]\n\n', cap),
    setting: makeProviderSettingGoogle({ vertexAI: false, authMode: 'api_key' }),
  });
  const chunks: unknown[] = [];
  let threw: string | null = null;
  try {
    await api.streamText([makeUserMessage('hi')], params, (c): void => { chunks.push(c); });
  } catch (e) {
    threw = e instanceof Error ? e.message : String(e);
  }
  record('P5.google.data-DONE', { threw, chunks: chunks.length });
};

// ===== P6 probe: resume save rejection is unhandled (fire-and-forget) =====
const probeP6 = async (): Promise<void> => {
  const rejections: string[] = [];
  const handler = (reason: unknown): void => { rejections.push(String(reason)); };
  process.on('unhandledRejection', handler);
  const store = {
    save: async (): Promise<void> => { throw new Error('kv write failed'); },
    load: async (): Promise<null> => null,
    clear: async (): Promise<void> => {},
  };
  const sse = [
    'event: response.output_text.delta',
    'data: {"type":"response.output_text.delta","item_id":"msg_1","delta":"Hi"}',
    '',
    'event: response.completed',
    'data: {"type":"response.completed","response":{"id":"resp_new","model":"gpt-4o","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Hi"}]}]}}',
    '',
  ].join('\n');
  const api = createOpenAIResponsesApi({
    http: rawBodyHttp(sse, {}),
    setting: makeProviderSettingOpenAI({ baseUrl: 'https://api.openai.com/v1', useResponseApi: true }),
    resumeStore: store,
    enableResponsesResume: true,
    runId: 'run-rej',
  });
  let threw: string | null = null;
  try {
    await api.streamText([makeUserMessage('hi')],
      makeTextGenerationParams({ model: makeChatModel({ modelId: 'gpt-4o' }) }), (): void => {});
  } catch (e) {
    threw = e instanceof Error ? e.message : String(e);
  }
  await new Promise((r): void => { setTimeout(r, 30); });
  process.removeListener('unhandledRejection', handler);
  record('P6.resume-save-rejects', { threw, unhandledRejections: rejections });
};

// ===== P3c probe: real wiring (AppContainer) never passes runId → resume inert =====
const probeP3c = async (): Promise<void> => {
  const saved: number[] = [];
  let loads = 0;
  const store = {
    save: async (): Promise<void> => { saved.push(1); },
    load: async (): Promise<null> => { loads += 1; return null; },
    clear: async (): Promise<void> => {},
  };
  const sse = [
    'event: response.output_text.delta',
    'data: {"type":"response.output_text.delta","item_id":"msg_1","delta":"Hi"}',
    '',
    'event: response.completed',
    'data: {"type":"response.completed","response":{"id":"resp_new","model":"gpt-4o","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Hi"}]}]}}',
    '',
  ].join('\n');
  const cap: { body?: string } = {};
  // mirrors AppContainer.buildProviderApi: resumeStore + enableResponsesResume, NO runId
  const api = createOpenAIResponsesApi({
    http: rawBodyHttp(sse, cap),
    setting: makeProviderSettingOpenAI({ baseUrl: 'https://api.openai.com/v1', useResponseApi: true }),
    resumeStore: store,
    enableResponsesResume: true,
  });
  await api.streamText([makeUserMessage('hi')],
    makeTextGenerationParams({ model: makeChatModel({ modelId: 'gpt-4o' }) }), (): void => {});
  record('P3c.responses.no-runId (real wiring)', {
    loads, saves: saved.length, sentStore: JSON.parse(cap.body ?? '{}')['store'],
  });
};

// ===== P7 probe: explicit "error": null is treated as a stream error =====
// Android guards with `it["error"] != null` (ChatCompletionsAPI.kt:213,
// ResponseAPI.kt:186); Harmony uses `!== undefined` / `in`, so an explicit JSON
// null aborts the whole stream with message "null".
const probeP7 = async (): Promise<void> => {
  const chunkWithNullError =
    'data: {"id":"x","model":"m","choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":null}],"error":null}\n\n';
  const api = createOpenAIChatApi({
    http: rawBodyHttp(chunkWithNullError, {}),
    setting: makeProviderSettingOpenAI({}),
  });
  const chunks: unknown[] = [];
  let threw: string | null = null;
  try {
    await api.streamText([makeUserMessage('hi')], params, (c): void => { chunks.push(c); });
  } catch (e) {
    threw = e instanceof Error ? e.message : String(e);
  }
  record('P7.openai.error-null', { threw, chunks: chunks.length });

  const sse = [
    'event: response.output_text.delta',
    'data: {"type":"response.output_text.delta","item_id":"msg_1","delta":"Hi","error":null}',
    '',
    'event: response.completed',
    'data: {"type":"response.completed","response":{"id":"resp_1","model":"gpt-4o","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Hi"}]}]}}',
    '',
  ].join('\n');
  const api2 = createOpenAIResponsesApi({
    http: rawBodyHttp(sse, {}),
    setting: makeProviderSettingOpenAI({ baseUrl: 'https://api.openai.com/v1', useResponseApi: true }),
  });
  const chunks2: unknown[] = [];
  let threw2: string | null = null;
  try {
    await api2.streamText([makeUserMessage('hi')],
      makeTextGenerationParams({ model: makeChatModel({ modelId: 'gpt-4o' }) }),
      (c): void => { chunks2.push(c); });
  } catch (e) {
    threw2 = e instanceof Error ? e.message : String(e);
  }
  record('P7.responses.error-null', { threw: threw2, chunks: chunks2.length });
};

// ===== Positive controls =====
const probeControls = (): void => {
  // UTF-8 multibyte char split across two ArrayBuffer chunks
  const a = new SseAssembler();
  const full = 'data: {"t":"你好"}\n\n';
  const bytes = enc.encode(full);
  const cut = full.indexOf('你'); // byte offset of the 3-byte '你'
  const first = bytes.slice(0, cut + 1); // split inside '你'
  const second = bytes.slice(cut + 1);
  const ev1 = a.feed(first.buffer as ArrayBuffer);
  const ev2 = a.feed(second.buffer as ArrayBuffer);
  record('CTRL.utf8-split', {
    ev1: ev1.length, ev2: ev2.length, data: ev2.length > 0 ? ev2[0].data : null,
  });

  // reasoning_content delta survives parse
  const b = new SseAssembler();
  const ev = b.feed(enc.encode(
    'data: {"id":"x","model":"m","choices":[{"index":0,"delta":{"reasoning_content":"think"},"finish_reason":null}]}\n\n',
  ).buffer as ArrayBuffer);
  const parts = (ev[0] as unknown as { data: string });
  record('CTRL.reasoning-delta', { events: ev.length, payloadHasReasoning: parts.data.includes('reasoning_content') });

  // bare text/plain 200 body (no data: prefix) yields no events even on flush
  const c = new SseAssembler();
  const e1 = c.feed(enc.encode('not an sse stream').buffer as ArrayBuffer);
  const e2 = c.flush();
  record('CTRL.non-sse-flush', { fed: e1.length, flushed: e2.length });
};

const main = async (): Promise<void> => {
  await probeP1();
  probeP2();
  await probeP2b();
  probeP3();
  await probeP3b();
  await probeP3c();
  await probeP4();
  await probeP5();
  await probeP6();
  await probeP7();
  probeControls();
  for (const line of results) console.log(line);
};

main().catch((e): void => {
  console.error('PROBE ERROR', e);
  process.exitCode = 1;
});
