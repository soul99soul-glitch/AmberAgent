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
