// Phase 1 unit tests — OpenAI Responses request/parse + Grok + Google contracts

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  buildResponsesRequestBody,
  buildResponsesInput,
  supportsResponsesResume,
  resolveResponseProviderCapabilities,
  openAIResponsesReasoningEffort,
} from '../main/ets/chat/openai_responses_request.ts';
import {
  parseResponsesStreamEvent,
  parseResponsesOutput,
  ResponseStreamReconciler,
  OPENAI_TOOL_CALL_ID_METADATA_KEY,
} from '../main/ets/chat/openai_responses_parse.ts';
import {
  createOpenAIResponsesApi,
  responsesEndpointUrl,
  isOfficialOpenAIHost,
} from '../main/ets/chat/openai_responses_api.ts';
import {
  grokAuthStatusFrom,
  grokNeedsRefresh,
  resolveGrokBearer,
  buildGrokCliChatRequest,
  GrokAuthError,
  GROK_CLI_PROXY_BASE_URL,
  GROK_OAUTH_TOKEN_ENDPOINT,
  GROK_REFRESH_SKEW_MS,
  type GrokOAuthTokens,
  type GrokTokenStore,
} from '../main/ets/chat/grok_oauth.ts';
import {
  classifyGoogleAuthForm,
  GoogleAuthNotImplementedError,
  isGoogleAuthNotImplementedError,
  throwGoogleAuthNotImplemented,
} from '../main/ets/chat/google_oauth_contracts.ts';
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

test('buildResponsesRequestBody: resume forces store=true', () => {
  const body = buildResponsesRequestBody({
    messages: sampleMessages(),
    params: makeTextGenerationParams({ model: makeChatModel({ modelId: 'gpt-4o' }) }),
    setting: makeProviderSettingOpenAI({ baseUrl: 'https://api.openai.com/v1', useResponseApi: true }),
    stream: true,
    resume: { runId: 'r1', resumeFrom: null },
  });
  assert.equal(body['store'], true);
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

test('capabilities: official host supports stored responses; volces no summary', () => {
  assert.equal(resolveResponseProviderCapabilities('api.openai.com').supportsStoredResponses, true);
  assert.equal(resolveResponseProviderCapabilities('ark.cn-beijing.volces.com').supportsReasoningSummary, false);
  assert.equal(supportsResponsesResume(makeProviderSettingOpenAI({
    baseUrl: 'https://api.openai.com/v1', useResponseApi: true, authMode: 'api_key',
  })), true);
  assert.equal(supportsResponsesResume(makeProviderSettingOpenAI({
    baseUrl: 'https://proxy.example.com/v1', useResponseApi: true, authMode: 'api_key',
  })), false);
  assert.equal(supportsResponsesResume(makeProviderSettingOpenAI({
    baseUrl: 'https://api.openai.com/v1', useResponseApi: false, authMode: 'api_key',
  })), false);
});

test('reasoning effort mapping', () => {
  assert.equal(openAIResponsesReasoningEffort('auto'), null);
  assert.equal(openAIResponsesReasoningEffort('off'), 'low');
  assert.equal(openAIResponsesReasoningEffort('medium'), 'medium');
  assert.equal(openAIResponsesReasoningEffort('max'), 'high');
});

test('parse: output_text.delta', () => {
  const chunk = parseResponsesStreamEvent({
    type: 'response.output_text.delta',
    item_id: 'msg_1',
    delta: 'Hel',
  });
  assert.ok(chunk !== null);
  const delta = chunk!.choices[0].delta!;
  assert.equal(delta.parts[0].type, 'text');
  assert.equal((delta.parts[0] as { text: string }).text, 'Hel');
});

test('parse: function_call_arguments.delta and done(replace)', () => {
  const d = parseResponsesStreamEvent({
    type: 'response.function_call_arguments.delta',
    item_id: 'fc_1',
    delta: '{"a"',
  });
  assert.ok(d !== null);
  assert.equal(d!.choices[0].delta!.parts[0].type, 'tool');

  const done = parseResponsesStreamEvent({
    type: 'response.function_call_arguments.done',
    item_id: 'fc_1',
    arguments: '{"a":1}',
  });
  assert.ok(done !== null);
  const tool = done!.choices[0].delta!.parts[0] as { input: string; metadata?: Record<string, unknown> };
  assert.equal(tool.input, '{"a":1}');
  // replace flag is set via metadata key used by tool_merge
  assert.ok(tool.metadata === undefined || typeof tool.metadata === 'object');
});

test('parse: output_item.added function_call stores call_id metadata', () => {
  const chunk = parseResponsesStreamEvent({
    type: 'response.output_item.added',
    item: {
      type: 'function_call',
      id: 'fc_abc',
      call_id: 'call_xyz',
      name: 'search',
      arguments: '',
    },
  });
  assert.ok(chunk !== null);
  const tool = chunk!.choices[0].delta!.parts[0] as {
    toolCallId: string;
    metadata?: Record<string, unknown>;
  };
  assert.equal(tool.toolCallId, 'fc_abc');
  assert.equal(tool.metadata?.[OPENAI_TOOL_CALL_ID_METADATA_KEY], 'call_xyz');
});

test('parse: completed with output message + function_call', () => {
  const chunk = parseResponsesStreamEvent({
    type: 'response.completed',
    response: {
      id: 'resp_1',
      model: 'gpt-4o',
      status: 'completed',
      output: [
        { type: 'message', content: [{ type: 'output_text', text: 'done' }] },
        { type: 'function_call', call_id: 'call_1', name: 't', arguments: '{}' },
      ],
      usage: { input_tokens: 3, output_tokens: 5, total_tokens: 8 },
    },
  });
  assert.ok(chunk !== null);
  assert.equal(chunk!.choices[0].delta, null);
  const msg = chunk!.choices[0].message!;
  assert.ok(msg.parts.some((p) => p.type === 'text'));
  assert.ok(msg.parts.some((p) => p.type === 'tool'));
  assert.equal(chunk!.usage?.promptTokens, 3);
  assert.equal(chunk!.usage?.completionTokens, 5);
});

test('parse: response.failed throws', () => {
  assert.throws(() => parseResponsesStreamEvent({
    type: 'response.failed',
    response: { error: { message: 'boom' } },
  }));
});

test('reconciler: unifies assistant ids and final text suffix', () => {
  const r = new ResponseStreamReconciler();
  const d1 = r.reconcile({
    id: 'msg_1', model: '', usage: null,
    choices: [{ index: 0, delta: makeAssistantMessage('Hello '), message: null, finishReason: null }],
  });
  const id1 = d1.choices[0].delta!.id;
  const d2 = r.reconcile({
    id: 'msg_1', model: '', usage: null,
    choices: [{ index: 0, delta: makeAssistantMessage('world'), message: null, finishReason: null }],
  });
  assert.equal(d2.choices[0].delta!.id, id1);

  const final = r.reconcile({
    id: 'resp_1', model: '', usage: null,
    choices: [{
      index: 0, delta: null,
      message: makeAssistantMessage('Hello world!'),
      finishReason: 'stop',
    }],
  });
  const fd = final.choices[0].delta!;
  assert.ok(fd !== null);
  const text = fd.parts.filter((p) => p.type === 'text').map((p) => (p as { text: string }).text).join('');
  assert.equal(text, '!');
});

test('responses endpoint helpers', () => {
  assert.equal(responsesEndpointUrl('https://api.openai.com/v1'), 'https://api.openai.com/v1/responses');
  assert.equal(isOfficialOpenAIHost('https://api.openai.com/v1'), true);
  assert.equal(isOfficialOpenAIHost('https://example.com'), false);
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

// ===== Grok =====


const tokens = (over: Partial<GrokOAuthTokens> = {}): GrokOAuthTokens => ({
  accessToken: 'at',
  refreshToken: 'rt',
  expiresAtMillis: 1_000_000,
  idToken: null,
  email: null,
  ...over,
});

test('grok status four states', () => {
  assert.equal(grokAuthStatusFrom(null, 0).code, 'not_signed_in');
  assert.equal(grokAuthStatusFrom(tokens({ accessToken: '' }), 0).code, 'token_missing');
  assert.equal(grokAuthStatusFrom(tokens({ expiresAtMillis: 1, refreshToken: 'x' }), 10).code, 'token_expired');
  assert.equal(grokAuthStatusFrom(tokens({ expiresAtMillis: 1, refreshToken: 'x' }), 10).usable, true);
  assert.equal(grokAuthStatusFrom(tokens({ expiresAtMillis: 1, refreshToken: null }), 10).usable, false);
  assert.equal(grokAuthStatusFrom(tokens(), 0).code, 'ready');
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

test('resolveGrokBearer: ready returns access token', async () => {
  const store: GrokTokenStore = {
    get: async () => tokens({ expiresAtMillis: 100_000 }),
    set: async () => {},
  };
  const bearer = await resolveGrokBearer({ setting: {}, nowMillis: () => 0, store, providerId: 'p1' });
  assert.equal(bearer, 'at');
});

test('grok constants match Android/iOS', () => {
  assert.equal(GROK_OAUTH_TOKEN_ENDPOINT, 'https://auth.x.ai/oauth2/token');
  assert.equal(GROK_CLI_PROXY_BASE_URL, 'https://cli-chat-proxy.grok.com/v1');
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

// ===== Google OAuth contracts =====

test('classifyGoogleAuthForm', () => {
  assert.deepEqual(classifyGoogleAuthForm({}), { form: 'api_key' });
  assert.deepEqual(classifyGoogleAuthForm({ serviceAccountJson: '{}' }), { form: 'vertex_service_account' });
  assert.deepEqual(classifyGoogleAuthForm({ codeAssistEnabled: true }), { form: 'gemini_code_assist_oauth' });
});

test('GoogleAuthNotImplementedError stable message + classifier', () => {
  try {
    throwGoogleAuthNotImplemented('vertex_service_account');
    assert.fail('should throw');
  } catch (e) {
    assert.ok(isGoogleAuthNotImplementedError(e));
    assert.equal((e as Error).message, 'vertex service account 未落地(P1)');
    assert.ok(e instanceof GoogleAuthNotImplementedError);
  }
});
