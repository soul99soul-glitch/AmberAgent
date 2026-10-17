import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  JEV_PROVIDERS, JEV_PROVIDER_ORDER, JEV_VERCEL_DEFAULT_BASE,
  jevEndpointOf, jevRequestUrlOf, loadJevSettings, makeJevSettings, saveJevSettings, switchJevProvider,
} from '../main/ets/chat/jev_models.ts';
import { createMemoryKeyValueStore } from '../main/ets/chat/kv_store.ts';

test('four Jev providers expose contract, endpoint and default model', () => {
  assert.deepEqual(JEV_PROVIDER_ORDER, ['typesafe', 'vercel', 'perplexity', 'liquid']);
  assert.equal(JEV_PROVIDERS.typesafe.contract, 'typesafe');
  assert.equal(JEV_PROVIDERS.typesafe.endpoint, 'https://api.typesafe.ai/v1/systemone');
  assert.equal(JEV_PROVIDERS.typesafe.defaultModel, 'jev-1.14.0');
  assert.equal(JEV_PROVIDERS.vercel.contract, 'vercel');
  assert.equal(JEV_PROVIDERS.vercel.defaultModel, 'typesafe-ai/jev-1.14.0');
  assert.equal(JEV_PROVIDERS.perplexity.contract, 'typesafe');
  assert.equal(JEV_PROVIDERS.perplexity.endpoint, 'https://api.perplexity.ai/v1/decisions');
  assert.equal(JEV_PROVIDERS.perplexity.defaultModel, 'pplx-decider-v1.2');
  assert.equal(JEV_PROVIDERS.liquid.contract, 'typesafe');
  assert.equal(JEV_PROVIDERS.liquid.defaultModel, 'd1');
});

test('settings derive contract and endpoint from provider; Vercel base can be overridden', () => {
  const perplexity = makeJevSettings({ provider: 'perplexity', model: 'pplx-decider-v1.2' });
  assert.equal(perplexity.apiMode, 'typesafe');
  assert.equal(perplexity.modelProvider, 'perplexity');
  assert.equal(jevEndpointOf(perplexity), 'https://api.perplexity.ai/v1/decisions');
  const vercel = makeJevSettings({ provider: 'vercel', baseUrl: 'https://gateway.example/v4/ai' });
  assert.equal(vercel.apiMode, 'vercel');
  assert.equal(jevEndpointOf(vercel), 'https://gateway.example/v4/ai');
  assert.equal(jevRequestUrlOf(vercel), 'https://gateway.example/v4/ai/evaluation-model');
  assert.equal(jevRequestUrlOf(perplexity), 'https://api.perplexity.ai/v1/decisions');
  const legacy = makeJevSettings({ apiMode: 'vercel' });
  assert.equal(legacy.provider, 'vercel');
  assert.equal(legacy.apiMode, 'vercel');
  assert.equal(jevEndpointOf(legacy), JEV_VERCEL_DEFAULT_BASE);
});

test('switching provider clears the old key and sends the new default model', () => {
  const original = makeJevSettings({
    provider: 'typesafe', model: 'jev-1.14.0', apiKey: 'ts-key', mode: 'active',
  });
  const switched = switchJevProvider(original, 'perplexity');
  assert.equal(switched.provider, 'perplexity');
  assert.equal(switched.modelProvider, 'perplexity');
  assert.equal(switched.model, 'pplx-decider-v1.2');
  assert.equal(switched.apiKey, '');
  assert.equal(switched.mode, 'active');
  const same = switchJevProvider(
    makeJevSettings({ provider: 'perplexity', model: 'custom-model', apiKey: 'p' }), 'perplexity');
  assert.equal(same.model, 'custom-model');
  const away = switchJevProvider(
    makeJevSettings({ provider: 'perplexity', model: 'custom-model', apiKey: 'p' }), 'liquid');
  const back = switchJevProvider(away, 'perplexity');
  assert.equal(back.model, 'pplx-decider-v1.2', 'switch back uses the new vendor default, not the old custom id');
  assert.equal(back.apiKey, '', 'switch back requires re-entering the key');
});

test('provider/modelProvider survive save and load; legacy apiMode-only settings migrate', async () => {
  const kv = createMemoryKeyValueStore();
  const settings = makeJevSettings({ provider: 'liquid', model: 'd1', apiKey: 'k' });
  await saveJevSettings(kv, settings);
  const loaded = await loadJevSettings(kv);
  assert.equal(loaded.provider, 'liquid');
  assert.equal(loaded.apiMode, 'typesafe');
  assert.equal(loaded.modelProvider, 'liquid');
  assert.equal(loaded.model, 'd1');

  const legacyKv = createMemoryKeyValueStore();
  await legacyKv.put('jev_settings', JSON.stringify({ mode: 'shadow', apiMode: 'vercel', model: 'legacy' }));
  const legacy = await loadJevSettings(legacyKv);
  assert.equal(legacy.provider, 'vercel');
  assert.equal(legacy.apiMode, 'vercel');
  assert.equal(legacy.modelProvider, 'vercel');
  assert.equal(legacy.model, 'legacy');
  assert.equal(legacy.mode, 'shadow');

  const mismatchKv = createMemoryKeyValueStore();
  await mismatchKv.put('jev_settings', JSON.stringify({ provider: 'liquid', modelProvider: 'typesafe', model: 'old-model' }));
  const mismatch = await loadJevSettings(mismatchKv);
  assert.equal(mismatch.model, 'd1', 'a model owned by another vendor is replaced with the current default');

  const emptyKv = createMemoryKeyValueStore();
  await emptyKv.put('jev_settings', JSON.stringify({ provider: 'vercel', model: '' }));
  const empty = await loadJevSettings(emptyKv);
  assert.equal(empty.model, 'typesafe-ai/jev-1.14.0', 'empty legacy model adopts the provider default');
});
