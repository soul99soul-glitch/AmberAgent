import { test } from 'node:test';
import assert from 'node:assert/strict';
import { providerFromImportJson } from '../main/ets/chat/provider_import.ts';
import {
  createMemoryKeyValueStore, loadProviders, saveProviders, PROVIDERS_KEY,
} from '../main/ets/chat/kv_store.ts';
import { makeProviderSettingOpenAIVariant } from '../main/ets/chat/provider_settings.ts';

const exportDoc = () => ({
  format: 'amber-provider', version: 1, type: 'openai',
  name: 'Imported', baseUrl: 'https://api.example.com/v1',
  models: [{ modelId: 'model-1', displayName: 'Model One', type: 'chat' }],
});

test('Provider import creates new IDs and no credentials, and all model kinds survive KV roundtrip', async () => {
  const doc = exportDoc();
  doc.models = ['chat', 'image', 'embedding'].map((type) => ({
    modelId: type, displayName: type, type,
  }));
  const json = JSON.stringify({ ...doc, id: 'old-provider', apiKey: 'must-not-import' });
  const first = providerFromImportJson(json);
  const second = providerFromImportJson(json);
  assert.notEqual(first.id, 'old-provider');
  assert.notEqual(first.id, second.id);
  assert.notEqual(first.models[0].id, second.models[0].id);
  assert.equal(first.apiKey, '');
  const kv = createMemoryKeyValueStore();
  await saveProviders(kv, [first]);
  assert.deepEqual(await loadProviders(kv), [first]);
});

test('Provider import preserves the three supported variants and typed optional fields', () => {
  const openai = providerFromImportJson(JSON.stringify({
    ...exportDoc(), useResponseApi: true, chatCompletionsPath: '/custom',
  }));
  assert.equal(openai.type, 'openai');
  if (openai.type === 'openai') {
    assert.equal(openai.useResponseApi, true);
    assert.equal(openai.chatCompletionsPath, '/custom');
  }
  const google = providerFromImportJson(JSON.stringify({
    ...exportDoc(), type: 'google', vertexAI: true, location: 'asia-east1', projectId: 'p',
  }));
  assert.equal(google.type, 'google');
  if (google.type === 'google') {
    assert.equal(google.vertexAI, true);
    assert.equal(google.projectId, 'p');
    assert.equal(google.location, 'asia-east1');
    assert.equal(google.privateKey, '');
  }
  const claude = providerFromImportJson(JSON.stringify({
    ...exportDoc(), type: 'claude', promptCaching: true,
  }));
  assert.equal(claude.type, 'claude');
  if (claude.type === 'claude') assert.equal(claude.promptCaching, true);
});

test('Invalid imports reject before persistence and preserve the existing readable Provider list', async () => {
  const invalidDocs: unknown[] = [
    null, [], 'not an object',
    { ...exportDoc(), format: 'other' },
    { ...exportDoc(), version: 2 },
    { ...exportDoc(), version: undefined },
    { ...exportDoc(), type: 'unknown' },
    { ...exportDoc(), name: 7 },
    { ...exportDoc(), name: '' },
    { ...exportDoc(), baseUrl: false },
    { ...exportDoc(), baseUrl: ' ' },
    { ...exportDoc(), models: {} },
    { ...exportDoc(), models: undefined },
    { ...exportDoc(), models: [null] },
    { ...exportDoc(), models: [{ modelId: 'm', type: 'bogus' }] },
    { ...exportDoc(), models: [{ modelId: 'm' }] },
    { ...exportDoc(), models: [{ modelId: 17, type: 'chat' }] },
    { ...exportDoc(), models: [{ modelId: '', type: 'chat' }] },
    { ...exportDoc(), models: [{ modelId: 'm', type: 'chat', displayName: false }] },
    { ...exportDoc(), useResponseApi: 'false' },
    { ...exportDoc(), chatCompletionsPath: [] },
    { ...exportDoc(), type: 'google', vertexAI: 'false' },
    { ...exportDoc(), type: 'google', location: 7 },
    { ...exportDoc(), type: 'google', projectId: false },
    { ...exportDoc(), type: 'claude', promptCaching: 'false' },
  ];
  for (const doc of invalidDocs) {
    const kv = createMemoryKeyValueStore();
    const existing = makeProviderSettingOpenAIVariant({ name: 'Existing', apiKey: 'keep' });
    await saveProviders(kv, [existing]);
    const before = await kv.get(PROVIDERS_KEY);
    await assert.rejects(async () => {
      const provider = providerFromImportJson(JSON.stringify(doc));
      await saveProviders(kv, [existing, provider]);
    }, `invalid file must fail: ${JSON.stringify(doc)}`);
    assert.equal(await kv.get(PROVIDERS_KEY), before);
    assert.deepEqual(await loadProviders(kv), [existing]);
  }
});

test('Unknown model type is rejected before it can corrupt the Provider wire format', () => {
  assert.throws(() => providerFromImportJson(JSON.stringify({
    ...exportDoc(), models: [{ modelId: 'm', type: 'bogus' }],
  })), /type/);
});
