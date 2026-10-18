// Phase 1 unit tests — OpenAI Responses request/parse + Grok + Google contracts

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  buildResponsesRequestBody,
  buildResponsesInput,
  openAIResponsesReasoningEffort,
} from '../main/ets/chat/openai_responses_request.ts';

import {
  createOpenAIResponsesApi,
} from '../main/ets/chat/openai_responses_api.ts';
import {
  grokNeedsRefresh,
  resolveGrokBearer,
  buildGrokCliChatRequest,
  GrokAuthError,
  GROK_REFRESH_SKEW_MS,
  type GrokOAuthTokens,
  type GrokTokenStore,
} from '../main/ets/chat/grok_oauth.ts';

import {
  makeUIMessage,
  makeAssistantMessage,
  makeUserMessage,
  makeSystemMessage,
} from '../main/ets/chat/message.ts';
import { makeChatModel, makeTextGenerationParams, makeProviderSettingOpenAI } from '../main/ets/chat/provider_model.ts';
import type { UIMessagePartTool } from '../main/ets/chat/message.ts';

const sampleMessages = () => [
  makeSystemMessage('You are helpful.'),
  makeUserMessage('Hello'),
  makeAssistantMessage('Hi there'),
];

test('buildResponsesRequestBody: model/stream/input/instructions', () => {
  const body = buildResponsesRequestBody({
    messages: sampleMessages(),
    params: makeTextGenerationParams({
      model: makeChatModel({ modelId: 'gpt-4o' }),
      temperature: 0.7,
    }),
    setting: makeProviderSettingOpenAI({ baseUrl: 'https://api.openai.com/v1', useResponseApi: true }),
    stream: true,
  });
  assert.equal(body['model'], 'gpt-4o');
  assert.equal(body['stream'], true);
  assert.equal(body['store'], false);
  assert.equal(body['instructions'], 'You are helpful.');
  assert.equal(body['temperature'], 0.7);
  const input = body['input'];
  assert.ok(Array.isArray(input));
  assert.equal((input as unknown[]).length, 2); // system filtered; user+assistant
});

test('buildResponsesRequestBody: image_generation omits store and adds built-in tool', () => {
  const body = buildResponsesRequestBody({
    messages: sampleMessages(),
    params: makeTextGenerationParams({
      model: makeChatModel({ modelId: 'gpt-4o', abilities: ['tool'], tools: ['image_generation'] }),
    }),
    setting: makeProviderSettingOpenAI({ baseUrl: 'https://api.openai.com/v1', useResponseApi: true }),
    stream: false,
  });
  assert.equal('store' in body, false);
  const tools = body['tools'] as Array<{ type: string; model?: string }>;
  assert.ok(Array.isArray(tools));
  assert.ok(tools.some((t) => t.type === 'image_generation' && t.model === 'gpt-image-2'));
});

test('buildResponsesRequestBody: search built-in maps to web_search', () => {
  const body = buildResponsesRequestBody({
    messages: sampleMessages(),
    params: makeTextGenerationParams({
      model: makeChatModel({ modelId: 'gpt-4o', tools: ['search'] }),
    }),
    setting: makeProviderSettingOpenAI({ baseUrl: 'https://api.openai.com/v1', useResponseApi: true }),
    stream: false,
  });
  assert.equal(body['store'], false);
  const tools = body['tools'] as Array<{ type: string }>;
  assert.ok(tools.some((t) => t.type === 'web_search'));
});

test('buildResponsesRequestBody: o-series strips temperature', () => {
  const body = buildResponsesRequestBody({
    messages: sampleMessages(),
    params: makeTextGenerationParams({
      model: makeChatModel({ modelId: 'o3-mini' }),
      temperature: 0.5,
      topP: 0.9,
    }),
    setting: makeProviderSettingOpenAI({ baseUrl: 'https://api.openai.com/v1' }),
    stream: false,
  });
  assert.equal(body['temperature'], undefined);
  assert.equal(body['top_p'], undefined);
});

test('buildResponsesInput: assistant tools → function_call + output', () => {
  const messages = [
    makeUIMessage('assistant', [
      { type: 'text', text: 'calling', metadata: null },
      {
        type: 'tool',
        toolCallId: 'call_1',
        toolName: 'get_time',
        input: '{"tz":"UTC"}',
        output: [{ type: 'text', text: 'noon', metadata: null }],
        approvalState: { type: 'auto' },
        metadata: null,
      },
    ]),
  ];
  const input = buildResponsesInput(messages);
  const types = (input as Array<{ type?: string }>).map((x) => x.type);
  assert.ok(types.indexOf('function_call') >= 0);
  assert.ok(types.indexOf('function_call_output') >= 0);
  const fc = input.find((x) => (x as { type?: string }).type === 'function_call') as Record<string, unknown>;
  assert.equal(fc['call_id'], 'call_1');
  assert.equal(fc['name'], 'get_time');
  assert.equal(fc['arguments'], '{"tz":"UTC"}');
});

test('Responses replays foreign assistant image history as easy input with the original role and order', () => {
  const messages = [makeUserMessage('Draw a scene'), makeUIMessage('assistant', [
    { type: 'text', text: 'First view', metadata: null },
    { type: 'image', url: 'file:///foreign.png', metadata: null },
    { type: 'text', text: 'Second view', metadata: null },
    { type: 'image', url: 'https://example.test/foreign.png', metadata: null },
  ]), makeUserMessage('Describe the scene')];
  const before = JSON.stringify(messages);
  const body = buildResponsesRequestBody({ messages, params: makeTextGenerationParams(),
    setting: makeProviderSettingOpenAI({ useResponseApi: true }), stream: false,
    encodeImage: (url): string => `data:image/png;base64,${url}` });
  assert.deepEqual(body['input'], [
    { role: 'user', content: 'Draw a scene' },
    { role: 'assistant', content: [
      { type: 'input_text', text: 'First view' },
      { type: 'input_image', image_url: 'data:image/png;base64,file:///foreign.png' },
      { type: 'input_text', text: 'Second view' },
      { type: 'input_image', image_url: 'data:image/png;base64,https://example.test/foreign.png' },
    ] },
    { role: 'user', content: 'Describe the scene' },
  ]);
  assert.equal(JSON.stringify(messages), before);
});

test('Responses native image call replay retains its id between surrounding history content', () => {
  const encoded: string[] = [];
  const input = buildResponsesInput([makeUIMessage('assistant', [
    { type: 'text', text: 'Foreign image', metadata: null },
    { type: 'image', url: 'file:///foreign.png', metadata: null },
    { type: 'image', url: 'file:///native.png', metadata: { openai_image_call_id: 'ig_native' } },
    { type: 'text', text: 'Native result', metadata: null },
  ])], (url): string => { encoded.push(url); return `encoded:${url}`; });
  assert.deepEqual(input, [
    { role: 'assistant', content: [
      { type: 'input_text', text: 'Foreign image' },
      { type: 'input_image', image_url: 'encoded:file:///foreign.png' },
    ] },
    { type: 'image_generation_call', id: 'ig_native' },
    { role: 'assistant', content: 'Native result' },
  ]);
  assert.deepEqual(encoded, ['file:///foreign.png']);
});

test('Responses tool output carries images and text in source order with the original wire call id', () => {
  // FunctionCallOutput.output accepts input_text/input_image content directly.
  // https://developers.openai.com/api/reference/resources/responses/methods/create
  const shot: UIMessagePartTool = { type: 'tool', toolCallId: 'local-shot', toolName: 'wm_screenshot', input: '{}',
    output: [{ type: 'image', url: 'file:///shot.png', metadata: null }],
    approvalState: { type: 'auto' }, metadata: { openai_call_id: 'wire-shot' } };
  const mixed: UIMessagePartTool = { ...shot, toolCallId: 'mixed', toolName: 'inspect', metadata: null,
    output: [{ type: 'text', text: 'before', metadata: null },
      { type: 'image', url: 'https://example.test/page.png', metadata: null },
      { type: 'text', text: 'after', metadata: null }] };
  const plain: UIMessagePartTool = { ...shot, toolCallId: 'plain', metadata: null,
    output: [{ type: 'text', text: 'first', metadata: null }, { type: 'text', text: 'second', metadata: null }] };
  const messages = [makeUIMessage('assistant', [shot, mixed, plain])];
  const before = JSON.stringify(messages);
  const encoded: string[] = [];
  const body = buildResponsesRequestBody({ messages, params: makeTextGenerationParams(),
    setting: makeProviderSettingOpenAI({ useResponseApi: true }), stream: true,
    encodeImage: (url): string => { encoded.push(url); return `data:image/png;base64,${url}`; } });
  const items = body['input'] as Array<Record<string, unknown>>;
  assert.deepEqual(items.map((item) => [item.type, item.call_id]), [
    ['function_call', 'wire-shot'], ['function_call_output', 'wire-shot'],
    ['function_call', 'mixed'], ['function_call_output', 'mixed'],
    ['function_call', 'plain'], ['function_call_output', 'plain'],
  ]);
  assert.deepEqual(items[1].output, [{ type: 'input_image', image_url: 'data:image/png;base64,file:///shot.png' }]);
  assert.deepEqual(items[3].output, [{ type: 'input_text', text: 'before' },
    { type: 'input_image', image_url: 'data:image/png;base64,https://example.test/page.png' },
    { type: 'input_text', text: 'after' }]);
  assert.equal(items[5].output, 'first\nsecond', 'text-only results retain the existing string wire shape');
  assert.deepEqual(encoded, ['file:///shot.png', 'https://example.test/page.png']);
  assert.equal(JSON.stringify(messages), before);
});

test('Responses tool images require an encoder and propagate encoding failures', () => {
  const shot: UIMessagePartTool = { type: 'tool', toolCallId: 'shot', toolName: 'wm_screenshot', input: '{}',
    output: [{ type: 'image', url: 'file:///missing.png', metadata: null }],
    approvalState: { type: 'auto' }, metadata: null };
  const messages = [makeUIMessage('assistant', [shot])];
  assert.throws(() => buildResponsesInput(messages), /requires injected ImageEncoder/);
  const error = new Error('screenshot file missing');
  assert.throws(() => buildResponsesInput(messages, () => { throw error; }), error);
});

test('reasoning effort mapping', () => {
  assert.equal(openAIResponsesReasoningEffort('auto'), null);
  assert.equal(openAIResponsesReasoningEffort('off'), 'low');
  assert.equal(openAIResponsesReasoningEffort('medium'), 'medium');
  assert.equal(openAIResponsesReasoningEffort('max'), 'high');
});

test('createOpenAIResponsesApi: generateText posts /responses', async () => {
  const calls: Array<{ url: string; body: string }> = [];
  const api = createOpenAIResponsesApi({
    http: {
      fetch: async (req) => {
        calls.push({ url: req.url, body: req.body ?? '' });
        return {
          status: 200,
          headers: {},
          body: JSON.stringify({
            id: 'resp_1',
            model: 'gpt-4o',
            status: 'completed',
            output: [{ type: 'message', content: [{ type: 'output_text', text: 'ok' }] }],
          }),
        };
      },
      fetchStream: async () => ({ status: 200, headers: {}, body: '' }),
    },
    setting: makeProviderSettingOpenAI({ baseUrl: 'https://api.openai.com/v1', useResponseApi: true }),
  });
  const chunk = await api.generateText(sampleMessages(), makeTextGenerationParams({
    model: makeChatModel({ modelId: 'gpt-4o' }),
  }));
  assert.equal(calls.length, 1);
  assert.ok(calls[0].url.endsWith('/responses'));
  const parsed = JSON.parse(calls[0].body) as Record<string, unknown>;
  assert.equal(parsed['stream'], false);
  assert.equal(parsed['store'], false);
  assert.equal(chunk.choices[0].message!.parts[0].type, 'text');
});

test('createOpenAIResponsesApi: streamText parses Responses SSE into chunks', async () => {
  const encoder = new TextEncoder();
  const sse = [
    'event: response.output_text.delta',
    'data: {"type":"response.output_text.delta","item_id":"msg_1","delta":"Hel"}',
    '',
    'event: response.output_text.delta',
    'data: {"type":"response.output_text.delta","item_id":"msg_1","delta":"lo"}',
    '',
    'event: response.completed',
    'data: {"type":"response.completed","response":{"id":"resp_1","model":"gpt-4o","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Hello"}]}],"usage":{"input_tokens":1,"output_tokens":2,"total_tokens":3}}}',
    '',
  ].join('\n');
  const buf = encoder.encode(sse);
  const chunks: Array<{ choices: Array<{ delta: { parts: Array<{ type: string; text?: string }> } | null }> }> = [];
  const api = createOpenAIResponsesApi({
    http: {
      fetch: async () => ({ status: 200, headers: {}, body: '' }),
      fetchStream: async (_req, opts): Promise<{ status: number; headers: Record<string, string>; body: string }> => {
        opts.onChunk(buf.buffer as ArrayBuffer, true);
        return { status: 200, headers: {}, body: '' };
      },
    },
    setting: makeProviderSettingOpenAI({ baseUrl: 'https://api.openai.com/v1', useResponseApi: true }),
  });
  await api.streamText(sampleMessages(), makeTextGenerationParams({
    model: makeChatModel({ modelId: 'gpt-4o' }),
  }), (c): void => { chunks.push(c as never); });
  assert.ok(chunks.length >= 2);
  const firstDelta = chunks[0].choices[0].delta;
  assert.ok(firstDelta !== null && firstDelta !== undefined);
  assert.equal(firstDelta.parts[0].type, 'text');
  assert.equal((firstDelta.parts[0] as { text: string }).text, 'Hel');
});

test('createOpenAIResponsesApi.streamText: non-2xx status throws', async () => {
  const api = createOpenAIResponsesApi({
    http: {
      fetch: async () => ({ status: 401, headers: {}, body: '' }),
      fetchStream: async () => ({
        status: 401,
        headers: {},
        body: JSON.stringify({ error: { message: 'invalid api key' } }),
      }),
    },
    setting: makeProviderSettingOpenAI({ baseUrl: 'https://api.openai.com/v1', useResponseApi: true }),
  });
  await assert.rejects(
    () => api.streamText(sampleMessages(), makeTextGenerationParams({
      model: makeChatModel({ modelId: 'gpt-4o' }),
    }), (): void => {}),
    /invalid api key|Failed to get response/,
  );
});

// ===== R06 / R18:Responses 协议收口与非 SSE 守卫 =====

test('R06/R18 responses streamText: 200 非 SSE → 抛错;response.completed → 请求收口', async () => {
  const encoder = new TextEncoder();
  const jsonBody = JSON.stringify({ error: { message: 'gateway json error' } });
  const nonSse = createOpenAIResponsesApi({
    http: {
      fetch: async () => ({ status: 200, headers: {}, body: '' }),
      fetchStream: async (_req, opts): Promise<{ status: number; headers: Record<string, string>; body: string }> => {
        opts.onChunk(encoder.encode(jsonBody).buffer as ArrayBuffer, true);
        return { status: 200, headers: {}, body: jsonBody };
      },
    },
    setting: makeProviderSettingOpenAI({ baseUrl: 'https://api.openai.com/v1', useResponseApi: true }),
  });
  await assert.rejects(
    () => nonSse.streamText(sampleMessages(), makeTextGenerationParams({
      model: makeChatModel({ modelId: 'gpt-4o' }),
    }), (): void => {}),
    /gateway json error/, '200 + 非 SSE JSON 必须报错');

  const sse = [
    'event: response.output_text.delta',
    'data: {"type":"response.output_text.delta","item_id":"msg_1","delta":"hi"}',
    '',
    'event: response.completed',
    'data: {"type":"response.completed","response":{"id":"resp_1","model":"gpt-4o","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"hi"}]}]}}',
    '',
  ].join('\n');
  let stopRequested = false;
  const ok = createOpenAIResponsesApi({
    http: {
      fetch: async () => ({ status: 200, headers: {}, body: '' }),
      fetchStream: async (_req, opts): Promise<{ status: number; headers: Record<string, string>; body: string }> => {
        opts.onChunk(encoder.encode(sse).buffer as ArrayBuffer, true);
        if (opts.shouldStop !== undefined && opts.shouldStop()) stopRequested = true;
        return { status: 200, headers: {}, body: '' };
      },
    },
    setting: makeProviderSettingOpenAI({ baseUrl: 'https://api.openai.com/v1', useResponseApi: true }),
  });
  await ok.streamText(sampleMessages(), makeTextGenerationParams({
    model: makeChatModel({ modelId: 'gpt-4o' }),
  }), (): void => {});
  assert.equal(stopRequested, true, 'response.completed 后必须请求关闭底层流');
});

// ===== Grok =====

const tokens = (over: Partial<GrokOAuthTokens> = {}): GrokOAuthTokens => ({
  accessToken: 'at',
  refreshToken: 'rt',
  expiresAtMillis: 1_000_000,
  idToken: null,
  email: null,
  ...over,
});

test('grok needs refresh uses skew', () => {
  const now = 10_000;
  assert.equal(grokNeedsRefresh(tokens({ expiresAtMillis: now + GROK_REFRESH_SKEW_MS }), now), true);
  assert.equal(grokNeedsRefresh(tokens({ expiresAtMillis: now + GROK_REFRESH_SKEW_MS + 1 }), now), false);
});

test('resolveGrokBearer: not signed in throws stable error', async () => {
  const store: GrokTokenStore = {
    get: async () => null,
    set: async () => {},
  };
  await assert.rejects(
    () => resolveGrokBearer({ setting: {}, nowMillis: () => 0, store, providerId: 'p1' }),
    (e: unknown) => e instanceof GrokAuthError && e.statusCode === 'not_signed_in',
  );
});

test('buildGrokCliChatRequest', () => {
  const req = buildGrokCliChatRequest('tok', {
    model: 'grok-3',
    messages: [{ role: 'user', content: 'hi' }],
    stream: true,
  });
  assert.ok(req.url.endsWith('/chat/completions'));
  assert.equal(req.headers['Authorization'], 'Bearer tok');
  assert.equal(JSON.parse(req.body)['stream'], true);
});
