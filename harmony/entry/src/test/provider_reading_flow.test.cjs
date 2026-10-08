const { test } = require('node:test');
const assert = require('node:assert/strict');
const { actualPage } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');
const tick = () => new Promise(resolve => setImmediate(resolve));

test('model discovery uses current key drafts while keeping saved Google account catalog authentication', async () => {
  for (const type of ['claude', 'google']) {
    const calls = [], catalogs = [];
    const page = actualPage('pages/ChatProviderDetailPage.ets', ['fetchModels', 'parseModelIds'], {
      getAppContainer: () => ({ httpClient: { fetch: async request => {
        calls.push(request); return { status: 200, body: type === 'google'
          ? '{"models":[{"name":"models/gemini-new"}]}' : '{"data":[{"id":"claude-new"}]}' };
      } } }), EntryAbortController: class { signal = { aborted: false }; },
      loadGoogleModelCatalog: async provider => { catalogs.push(provider);
        return { supported: true, models: [{ modelId: 'account-model' }] }; },
    });
    Object.assign(page, { provider: { type, id: 'provider', authMode: 'api_key', useServiceAccount: false, apiKey: 'old-key' },
      fetchBusy: false, vertexAI: false, apiKey: ' new-draft-key ', baseUrl: ' https://draft.test/v1 ',
      modelsText: '', fetchedIds: [], googleFetchedModels: [], fetchMsg: '' });
    page.fetchModels(); await tick();
    assert.equal(calls.length, 1); assert.equal(calls[0].url, 'https://draft.test/v1/models');
    assert.equal(calls[0].headers[type === 'claude' ? 'x-api-key' : 'x-goog-api-key'], 'new-draft-key');
    assert.deepEqual(page.fetchedIds, [type === 'google' ? 'gemini-new' : 'claude-new']);
    if (type === 'google') {
      page.provider.authMode = 'antigravity_oauth';
      page.fetchModels(); await tick();
      assert.equal(calls.length, 1); assert.equal(catalogs[0], page.provider);
      assert.deepEqual(page.fetchedIds, ['account-model']);
      page.provider.authMode = 'api_key'; page.vertexAI = true;
      page.fetchModels(); assert.equal(calls.length, 1); assert.match(page.fetchMsg, /手动/);
    }
  }
});

test('single provider save returns only after success, keeps latest model parameters, and retains failed drafts', async () => {
  const old = { id: 'provider', type: 'openai', apiKey: 'saved-key', models: [
    { id: 'model', modelId: 'model-id', displayName: 'Old', customBodies: [] } ] };
  let providers = [JSON.parse(JSON.stringify(old))], fail = false, writes = 0, backs = 0;
  const page = actualPage('pages/ChatProviderDetailPage.ets', ['save', 'saveAndBack', 'parseModels', 'readingModelIds'], {
    getChatKvStore: () => ({}), loadProviders: async () => providers,
    saveProviders: async (_store, value) => { if (fail) throw Error('disk unavailable'); providers = value; writes++; },
    makeProviderSettingOpenAIVariant: value => ({ type: 'openai', ...value }),
    makeProviderModel: value => ({ id: 'new-model', ...value }), openAIAuthModeFixedBaseUrl: () => null,
    setTimeout: () => 0, router: { back: () => backs++ },
  });
  Object.assign(page, { provider: old, ioBusy: false, isNew: false, authMode: 'api_key', apiKey: ' draft-key ',
    name: 'Reading service', baseUrl: 'https://service.test/v1', chatPath: '/chat/completions', providerEnabled: true,
    useResponseApi: false, modelsText: 'model-id\nnew-model, model-id', googleFetchedModels: [], savedMsg: '' });
  assert.deepEqual(page.readingModelIds(), ['model-id', 'new-model']);
  providers[0].models[0].displayName = 'Latest';
  providers[0].models[0].customBodies = [{ key: 'temperature', value: 0.6 }];
  fail = true; await page.saveAndBack();
  assert.equal(backs, 0); assert.equal(writes, 0); assert.match(page.savedMsg, /disk unavailable/);
  assert.equal(page.modelsText, 'model-id\nnew-model, model-id');
  fail = false; await page.saveAndBack();
  assert.equal(backs, 1); assert.equal(writes, 1); assert.equal(providers[0].apiKey, 'draft-key');
  assert.equal(providers[0].models.length, 2); assert.equal(providers[0].models[0].displayName, 'Latest');
  assert.deepEqual(providers[0].models[0].customBodies, [{ key: 'temperature', value: 0.6 }]);
  providers = []; await page.saveAndBack();
  assert.equal(backs, 1); assert.equal(writes, 1); assert.match(page.savedMsg, /已被删除/);
});
