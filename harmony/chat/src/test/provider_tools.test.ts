import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createMemoryKeyValueStore, loadProviders, saveProviders } from '../main/ets/chat/kv_store.ts';
import { makeProviderModel, makeProviderSettingOpenAIVariant, makeProviderSettingGoogle } from '../main/ets/chat/provider_settings.ts';
import { createProviderManagementTools } from '../main/ets/chat/provider_tools.ts';
import type { HttpClient, HttpRequest } from '@amber/deepread-domain';
import type { JsonObject } from '../main/ets/chat/json.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import type { UIMessagePart } from '../main/ets/chat/message.ts';
import { maskSensitiveJsonText } from '../main/ets/chat/conversation_export.ts';
import { buildTopModelMenuGroups } from '../main/ets/chat/top_model_menu.ts';

const setup = () => {
  const store = createMemoryKeyValueStore();
  const requests: HttpRequest[] = [];
  let response = { status: 200, headers: {}, body: '{"data":[{"id":"chat-one"},{"id":"chat-two"}]}' };
  const http: HttpClient = {
    fetch: async (request, opts) => {
      if (opts?.signal?.aborted) throw new Error('cancelled');
      requests.push(request);
      return response;
    },
    fetchStream: async () => { throw new Error('unused'); },
  };
  let tail: Promise<unknown> = Promise.resolve();
  const tools = createProviderManagementTools({ store, http, withLock: (action) => {
    const result = tail.then(action);
    tail = result.catch(() => {});
    return result;
  } });
  const call = async (name: string, input: JsonObject = {}): Promise<JsonObject> => {
    const tool = tools.find((item) => item.name === name) as AgentTool;
    const parts: UIMessagePart[] = await tool.execute(input);
    assert.equal(parts[0].type, 'text');
    return JSON.parse((parts[0] as { text: string }).text);
  };
  return { store, requests, tools, call, respond: (next: typeof response) => { response = next; } };
};
const config = (name = 'My gateway'): JsonObject => ({
  name, type: 'openai', baseUrl: 'https://gateway.example/v1/', apiKey: 'test-secret-key',
  models: [{ modelId: 'chat-one', abilities: ['tool', 'reasoning'], inputModalities: ['text', 'image'] }],
});

test('batch config persists three protocols and returns no credentials', async () => {
  const s = setup();
  const result = await s.call('provider_configure', { providers: [config(),
    { name: 'Claude gateway', type: 'claude', apiKey: 'claude-secret', baseUrl: 'https://claude.example/v1',
      models: [{ modelId: 'claude-chat' }] },
    { name: 'Gemini gateway', type: 'google', apiKey: 'google-secret', baseUrl: 'https://google.example/v1beta',
      models: [{ modelId: 'gemini-chat' }] },
  ] });
  assert.equal(result.status, 'ok');
  const stored = (await loadProviders(s.store))!;
  assert.equal(stored.length, 3);
  assert.equal(stored[0].apiKey, 'test-secret-key');
  assert.equal(stored[0].baseUrl, 'https://gateway.example/v1');
  assert.deepEqual(stored[0].models[0].abilities, ['tool', 'reasoning']);
  assert.deepEqual(stored[0].models[0].inputModalities, ['text', 'image']);
  assert.equal(stored[1].models[0].abilities.includes('tool'), true);
  const groups = buildTopModelMenuGroups(stored, 'chat', '', '');
  assert.equal(groups.length, 3);
  assert.equal(groups[0].models[0].modelId, 'chat-one');
  assert.equal(JSON.stringify(result).includes('secret'), false);
  assert.equal(JSON.stringify(await s.call('provider_list')).includes('test-secret-key'), false);
});

test('updates by id preserve credentials, model ids and existing advanced settings', async () => {
  const s = setup();
  const existing = makeProviderSettingOpenAIVariant({ name: 'Existing', apiKey: 'kept-key',
    useResponseApi: true, models: [makeProviderModel({ modelId: 'chat-one', abilities: [],
      customHeaders: [{ name: 'Authorization', value: 'header-secret' }], contextWindowTokens: 1234 }),
    makeProviderModel({ modelId: 'keep-model' })] });
  await saveProviders(s.store, [existing]);
  await s.call('provider_configure', { providers: [{ id: existing.id, name: 'Renamed',
    models: [{ modelId: 'chat-one', displayName: 'One' }, { modelId: 'new-model', type: 'embedding' }] }] });
  const stored = (await loadProviders(s.store))![0];
  assert.equal(stored.apiKey, 'kept-key');
  assert.equal(stored.type === 'openai' && stored.useResponseApi, true);
  assert.equal(stored.models[0].id, existing.models[0].id);
  assert.deepEqual(stored.models[0].abilities, []);
  assert.deepEqual(stored.models[0].customHeaders, existing.models[0].customHeaders);
  assert.equal(stored.models[0].contextWindowTokens, 1234);
  assert.equal(stored.models.length, 3);
  assert.deepEqual(stored.models[2].abilities, []);
  assert.equal(JSON.stringify(await s.call('provider_list')).includes('header-secret'), false);
});

test('retry matches unique name without duplicating; ambiguous names require explicit id', async () => {
  const s = setup();
  await s.call('provider_configure', { providers: [config()] });
  const id = (await loadProviders(s.store))![0].id;
  await s.call('provider_configure', { providers: [config()] });
  assert.equal((await loadProviders(s.store))!.length, 1);
  assert.equal((await loadProviders(s.store))![0].id, id);
  await saveProviders(s.store, [makeProviderSettingOpenAIVariant({ name: 'Duplicate', apiKey: 'a' }),
    makeProviderSettingOpenAIVariant({ name: 'Duplicate', apiKey: 'b' })]);
  const result = await s.call('provider_configure', { providers: [config('Duplicate')] });
  assert.equal(result.status, 'error');
  assert.equal((await loadProviders(s.store))![0].apiKey, 'a');
});

test('invalid batch never partially saves or echoes secrets; stale ids and protocol changes fail', async () => {
  const s = setup();
  const invalidDocs: JsonObject[] = [
    { name: 'Bad', type: 'openai', baseUrl: 'file:///tmp/a', apiKey: 'do-not-echo' },
    { name: 'Bad', type: 'openai', baseUrl: 'https://host/v1?api_key=do-not-echo', apiKey: 'x' },
    { name: 'Bad', type: 'openai', baseUrl: 'https://do-not-echo@host/v1', apiKey: 'x' },
    { name: 'Bad', type: 'openai', baseUrl: 'https://host/v1', apiKey: '' },
    { id: 'missing', name: 'Bad' },
    { ...config('Bad'), models: [{ modelId: 'model', abilities: ['fake'] }] },
    { ...config('Bad'), baseURL: 'typo' },
  ];
  for (const invalid of invalidDocs) {
    const result = await s.call('provider_configure', { providers: [config(), invalid] });
    assert.equal(result.status, 'error');
    assert.equal(JSON.stringify(result).includes('do-not-echo'), false);
    assert.equal(await loadProviders(s.store), null);
  }
  await s.call('provider_configure', { providers: [config()] });
  const id = (await loadProviders(s.store))![0].id;
  assert.equal((await s.call('provider_configure', { providers: [{ id, type: 'claude' }] })).status, 'error');
});

test('Token Plan model discovery uses saved auth headers and does not mutate models', async () => {
  const s = setup();
  await s.call('provider_configure', { providers: [{ ...config('MiMo'), brand: 'mimo',
    authMode: 'mimo_coding_plan', baseUrl: 'https://token-plan-cn.xiaomimimo.com/v1' }] });
  const stored = (await loadProviders(s.store))![0];
  const result = await s.call('provider_models', { id: stored.id });
  assert.equal(result.status, 'ok');
  assert.deepEqual(result.models, ['chat-one', 'chat-two']);
  assert.deepEqual(s.requests[0].headers, { 'api-key': 'test-secret-key' });
  assert.equal(s.requests[0].url, 'https://token-plan-cn.xiaomimimo.com/v1/models');
  assert.equal((await loadProviders(s.store))![0].models.length, 1);
});

test('Google and Claude catalogs use native auth and parse native model ids', async () => {
  const s = setup();
  await s.call('provider_configure', { providers: [{ name: 'Google', type: 'google',
    apiKey: 'google-key', models: [] }, { name: 'Claude', type: 'claude', apiKey: 'claude-key' }] });
  const stored = (await loadProviders(s.store))!;
  s.respond({ status: 200, headers: {}, body: '{"models":[{"name":"models/gemini-chat"}]}' });
  assert.deepEqual((await s.call('provider_models', { id: stored[0].id })).models, ['gemini-chat']);
  assert.deepEqual(s.requests[0].headers, { 'x-goog-api-key': 'google-key' });
  s.respond({ status: 200, headers: {}, body: '{"data":[{"id":"claude-chat"}]}' });
  await s.call('provider_models', { id: stored[1].id });
  assert.deepEqual(s.requests[1].headers, { 'x-api-key': 'claude-key', 'anthropic-version': '2023-06-01' });
});

test('catalog HTTP failures do not echo server body, parse failures stay distinct from save success', async () => {
  const s = setup();
  await s.call('provider_configure', { providers: [config()] });
  const id = (await loadProviders(s.store))![0].id;
  s.respond({ status: 401, headers: {}, body: 'Rejected test-secret-key' });
  const result = await s.call('provider_models', { id });
  assert.equal(result.status, 'error');
  assert.equal(result.httpStatus, 401);
  assert.equal(JSON.stringify(result).includes('test-secret-key'), false);
  s.respond({ status: 200, headers: {}, body: '{}' });
  assert.equal((await s.call('provider_models', { id })).status, 'error');
});

test('OAuth and service-account credentials require their existing login/configuration flow', async () => {
  const s = setup();
  const p = makeProviderSettingGoogle({ name: 'OAuth', authMode: 'antigravity_oauth' });
  await saveProviders(s.store, [p]);
  assert.equal((await s.call('provider_models', { id: p.id })).status, 'error');
  assert.equal((await s.call('provider_configure', { providers: [{ id: p.id, apiKey: 'fake-oauth' }] })).status, 'error');
  assert.equal(s.requests.length, 0);
});

test('simultaneous batches preserve both providers through the shared mutation lock', async () => {
  const s = setup();
  await Promise.all([s.call('provider_configure', { providers: [config('One')] }),
    s.call('provider_configure', { providers: [config('Two')] })]);
  assert.deepEqual((await loadProviders(s.store))!.map((p) => p.name), ['One', 'Two']);
});

test('custom authorization header/body pairs and nested keys are hidden in previews and exports', async () => {
  const s = setup();
  const request = { providers: [{ ...config(), models: [{ modelId: 'chat-one',
    customHeaders: [{ name: 'Authorization', value: 'Bearer header-secret' },
      { name: 'api-key', value: 'plan-secret' }, { name: 'X-Region', value: 'cn' }],
    customBodies: [{ key: 'api_key', value: '"body-secret"' }, { key: 'temperature', value: '0.5' }],
  }] }] };
  assert.equal((await s.call('provider_configure', request)).status, 'ok');
  const preview = maskSensitiveJsonText(JSON.stringify(request));
  assert.equal(preview.includes('secret'), false);
  assert.equal(preview.includes('cn'), true);
  const stored = (await loadProviders(s.store))![0];
  assert.equal(stored.models[0].customHeaders[0].value, 'Bearer header-secret');
  assert.equal(stored.models[0].customBodies[1].value, 0.5);
  assert.equal(JSON.stringify(await s.call('provider_list')).includes('header-secret'), false);
});

test('enabling a Coding Plan requires an explicit documented endpoint', async () => {
  const s = setup();
  const request = { name: 'MiMo', type: 'openai', apiKey: 'plan-key', brand: 'mimo', authMode: 'mimo_coding_plan' };
  assert.equal((await s.call('provider_configure', { providers: [request] })).status, 'error');
  assert.equal(await loadProviders(s.store), null);
});
