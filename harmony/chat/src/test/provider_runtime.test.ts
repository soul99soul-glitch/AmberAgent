import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { HttpClient, HttpRequest, HttpResponse } from '@amber/deepread-domain';
import { prepareProviderRuntime } from '../main/ets/chat/provider_runtime.ts';
import type { ProviderOAuthSnapshot, ProviderRuntimeOptions, ProviderRuntimeSnapshot } from '../main/ets/chat/provider_runtime.ts';
import { createOpenAIChatApi, openAIAuthHeaders } from '../main/ets/chat/openai_chat_api.ts';
import {
  hasUsableAuth, makeProviderModel, makeProviderSettingOpenAIVariant,
} from '../main/ets/chat/provider_settings.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import { makeUserMessage } from '../main/ets/chat/message.ts';
import type { OpenAIAuthMode } from '../main/ets/chat/provider_model.ts';

const encoder = new TextEncoder();
const chatSse = 'data: {"id":"chat-1","model":"fixture-model","choices":[{"index":0,"delta":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n';
const responsesSse = 'event: response.completed\ndata: {"type":"response.completed","response":{"id":"resp-1","model":"fixture-model","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}}\n\n';

interface Capture {
  requests: HttpRequest[];
  buildCount: number;
  accounts: string[];
}
const capture = (): Capture => ({ requests: [], buildCount: 0, accounts: [] });
const http = (recorded: Capture): HttpClient => ({
  fetch: async (): Promise<HttpResponse> => { throw new Error('Use the existing SSE API'); },
  fetchStream: async (request, opts): Promise<HttpResponse> => {
    recorded.requests.push(request);
    const bytes = encoder.encode(request.url.endsWith('/responses') ? responsesSse : chatSse);
    opts.onChunk(bytes.buffer as ArrayBuffer, true);
    opts.onDataEnd?.();
    return { status: 200, headers: {}, body: '' };
  },
});

const options = (recorded: Capture, mode: OpenAIAuthMode = 'codex_oauth'): ProviderRuntimeOptions => ({
  provider: makeProviderSettingOpenAIVariant({
    id: 'provider-1', apiKey: 'stale-token', authMode: mode, baseUrl: 'https://provider-a.test/v1',
    useResponseApi: mode === 'codex_oauth',
  }),
  model: makeProviderModel({
    id: 'model-1', modelId: 'fixture-model', abilities: ['tool', 'reasoning'],
    customHeaders: [{ name: 'X-Model', value: 'model-a' }, { name: 'X-Shared', value: 'model-a' }],
    customBodies: [{ key: 'model_body', value: { version: 'a' } }],
  }),
  assistant: makeAssistant({
    reasoningLevel: 'high', temperature: 0.4, topP: 0.8, maxTokens: 4096,
    customHeaders: [{ name: 'X-Assistant', value: 'assistant-a' }, { name: 'X-Shared', value: 'assistant-a' }],
    customBodies: [{ key: 'assistant_body', value: { version: 'a' } }],
  }),
  refreshCodex: async (): Promise<ProviderOAuthSnapshot | null> => ({ accessToken: 'fresh-codex', accountId: 'account-1' }),
  refreshGrok: async (): Promise<ProviderOAuthSnapshot | null> => ({ accessToken: 'fresh-grok' }),
  buildApi: (provider, headers, accountId) => {
    recorded.buildCount += 1;
    recorded.accounts.push(accountId);
    if (provider.type !== 'openai') throw new Error('Expected OpenAI variant');
    // Entry owns this callback. Real auth helper + provider request builder are
    // used here to verify that the shared snapshot supplies the fresh inputs.
    return createOpenAIChatApi({
      http: http(recorded), setting: provider,
      headers: (): Record<string, string> => ({
        ...openAIAuthHeaders(provider.authMode, provider.baseUrl, provider.apiKey),
        ...(accountId.length > 0 ? { 'ChatGPT-Account-Id': accountId } : {}), ...headers,
      }),
    });
  },
});

const request = (runtime: ProviderRuntimeSnapshot): Promise<void> => runtime.api.streamText(
  [makeUserMessage('hi')], runtime.params, (): void => {},
);

for (const mode of ['codex_oauth', 'grok_oauth'] as const) {
  test(`shared runtime ${mode}: refresh result reaches the first real HTTP Authorization`, async () => {
    const recorded = capture();
    const config = options(recorded, mode);
    const originalKey = config.provider.type === 'openai' ? config.provider.apiKey : '';
    const runtime = await prepareProviderRuntime(config);
    await request(runtime);
    assert.equal(recorded.requests.length, 1);
    assert.equal(recorded.requests[0].headers.Authorization, mode === 'codex_oauth' ? 'Bearer fresh-codex' : 'Bearer fresh-grok');
    assert.equal(config.provider.type === 'openai' ? config.provider.apiKey : '', originalKey, 'Refreshing the run copy must not mutate the saved input');
    assert.equal(recorded.requests[0].headers['X-Assistant'], 'assistant-a');
    assert.equal(recorded.requests[0].headers['X-Model'], 'model-a');
    assert.equal(recorded.requests[0].headers['X-Shared'], 'model-a');
    if (mode === 'codex_oauth') {
      assert.deepEqual(recorded.accounts, ['account-1']);
      assert.equal(recorded.requests[0].headers['ChatGPT-Account-Id'], 'account-1');
      assert.ok(recorded.requests[0].url.endsWith('/responses'));
    } else {
      assert.deepEqual(recorded.accounts, ['']);
      assert.equal(recorded.requests[0].headers['ChatGPT-Account-Id'], undefined);
    }
  });
}

test('shared runtime snapshots before async refresh; next run picks up later model, headers and body', async () => {
  const recorded = capture();
  const config = options(recorded);
  let finishRefresh: (value: ProviderOAuthSnapshot) => void = (): void => { throw new Error('No pending refresh'); };
  config.refreshCodex = (): Promise<ProviderOAuthSnapshot | null> => new Promise((resolve) => { finishRefresh = resolve; });
  const firstPending = prepareProviderRuntime(config);
  if (config.provider.type !== 'openai') throw new Error('Expected OpenAI provider');
  config.provider.baseUrl = 'https://provider-b.test/v1';
  config.model.modelId = 'fixture-model-b';
  config.model.customHeaders[0].value = 'model-b';
  config.model.customBodies[0].value = { version: 'b' };
  config.assistant.reasoningLevel = 'low';
  config.assistant.temperature = 0.7;
  config.assistant.maxTokens = 8192;
  config.assistant.customHeaders[0].value = 'assistant-b';
  config.assistant.customBodies[0].value = { version: 'b' };
  finishRefresh({ accessToken: 'fresh-a', accountId: 'account-a' });
  const first = await firstPending;
  await request(first);
  const bodyA = JSON.parse(recorded.requests[0].body ?? '{}');
  assert.ok(recorded.requests[0].url.startsWith('https://provider-a.test/'));
  assert.equal(recorded.requests[0].headers['X-Assistant'], 'assistant-a');
  assert.equal(recorded.requests[0].headers['X-Model'], 'model-a');
  assert.equal(bodyA.model, 'fixture-model');
  assert.deepEqual(bodyA.assistant_body, { version: 'a' });
  assert.deepEqual(bodyA.model_body, { version: 'a' });
  assert.equal(first.params.reasoningLevel, 'high');
  assert.equal(first.params.maxTokens, 4096);
  assert.equal(first.params.temperature, 0.4);

  config.refreshCodex = async (): Promise<ProviderOAuthSnapshot | null> => ({ accessToken: 'fresh-b', accountId: 'account-b' });
  const second = await prepareProviderRuntime(config);
  await request(second);
  const bodyB = JSON.parse(recorded.requests[1].body ?? '{}');
  assert.ok(recorded.requests[1].url.startsWith('https://provider-b.test/'));
  assert.equal(recorded.requests[1].headers.Authorization, 'Bearer fresh-b');
  assert.equal(recorded.requests[1].headers['X-Assistant'], 'assistant-b');
  assert.equal(recorded.requests[1].headers['X-Model'], 'model-b');
  assert.equal(bodyB.model, 'fixture-model-b');
  assert.deepEqual(bodyB.assistant_body, { version: 'b' });
  assert.deepEqual(bodyB.model_body, { version: 'b' });
  assert.equal(second.params.reasoningLevel, 'low');
  assert.equal(second.params.maxTokens, 8192);
  assert.equal(second.params.temperature, 0.7);
});

for (const result of ['missing', 'blank', 'rejected'] as const) {
  test(`shared runtime OAuth refresh ${result} is an explicit failure without API construction or HTTP`, async () => {
    const recorded = capture();
    const config = options(recorded);
    config.refreshCodex = async (): Promise<ProviderOAuthSnapshot | null> => {
      if (result === 'rejected') throw new Error('refresh endpoint failed');
      return result === 'missing' ? null : { accessToken: '   ' };
    };
    await assert.rejects(prepareProviderRuntime(config), result === 'rejected' ? /refresh endpoint failed/ : /登录已失效/);
    assert.equal(recorded.buildCount, 0);
    assert.deepEqual(recorded.requests, []);
  });
}

test('shared runtime API-key mode does not invoke OAuth refreshers', async () => {
  const recorded = capture();
  const config = options(recorded, 'api_key');
  config.refreshCodex = config.refreshGrok = async (): Promise<ProviderOAuthSnapshot | null> => { throw new Error('Unexpected OAuth refresh'); };
  const runtime = await prepareProviderRuntime(config);
  await request(runtime);
  assert.equal(recorded.requests[0].headers.Authorization, 'Bearer stale-token');
  assert.equal(runtime.params.reasoningLevel, 'high');
});

test('hasUsableAuth admits API-key, OAuth and token-plan modes with a key; rejects empty or disabled providers', () => {
  for (const mode of ['api_key', 'codex_oauth', 'grok_oauth', 'zhipu_coding_plan',
    'kimi_coding_plan', 'mimo_coding_plan', 'minimax_token_plan'] as const) {
    assert.equal(hasUsableAuth(makeProviderSettingOpenAIVariant({ authMode: mode, apiKey: 'token' })), true);
    assert.equal(hasUsableAuth(makeProviderSettingOpenAIVariant({ authMode: mode, apiKey: ' \n ' })), false);
    assert.equal(hasUsableAuth(makeProviderSettingOpenAIVariant({ authMode: mode, apiKey: 'token', enabled: false })), false);
  }
});

for (const mode of ['zhipu_coding_plan', 'kimi_coding_plan', 'mimo_coding_plan', 'minimax_token_plan'] as const) {
  test(`shared runtime ${mode}: token-plan key reaches HTTP without OAuth refresh`, async () => {
    const recorded = capture();
    const config = options(recorded, mode);
    config.refreshCodex = config.refreshGrok = async () => { throw new Error('Unexpected OAuth refresh'); };
    const runtime = await prepareProviderRuntime(config);
    await request(runtime);
    if (mode === 'mimo_coding_plan') {
      assert.equal(recorded.requests[0].headers['api-key'], 'stale-token');
      assert.equal(recorded.requests[0].headers.Authorization, undefined);
    } else {
      assert.equal(recorded.requests[0].headers.Authorization, 'Bearer stale-token');
    }
  });
}
