const { test } = require('node:test');
const assert = require('node:assert/strict');
const { actualPage } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');
const file = 'pages/SettingDeepReadPage.ets';
function settings(key = 'draft-key') {
  return { enableWebSearch: true, searchServices: [{ id: 'service', type: 'tavily', apiKey: key }],
    searchEnabledServiceIds: ['service'], searchServiceSelected: 0, searchCommonOptions: { resultSize: 7 },
    searchBuiltinDuckDuckGoEnabled: true, searchBuiltinBingEnabled: false, searchBuiltinJinaEnabled: true,
    searchBuiltinWikipediaEnabled: true, searchBuiltinHackerNewsEnabled: false, searchGoogleWebViewFallbackEnabled: true };
}
function state(page) {
  Object.assign(page, { pageAlive: true, standaloneRoot: true, searchLoadToken: 0, standaloneSearchLoaded: true,
    standaloneSearch: settings(), selectedSearchId: 'service', pendingSearchDraft: null,
    standaloneSearchError: '', settingsEdited: false, modelDraftDirty: false, readingSettingsLoaded: false,
    saved: '已保存（旧）', clearSavedNotice() { this.saved = ''; }, taskModelReference: null, taskModelLabel: '', taskModelDetail: '', markSettingsEdited() { this.settingsEdited = true; } });
  return page;
}

test('returning from search editor consumes its complete draft without reloading persisted credentials', async () => {
  let loads = 0, consumed = false;
  const page = state(actualPage(file, ['refreshStandaloneSearch'], {
    consumeDeepReadSearchEditorDraft: () => { consumed = true; return { settings: settings(), selectedId: 'service', dirty: true }; },
    loadSearchPrefs: async () => { loads++; return settings('saved-key'); }, getChatKvStore: () => ({}) }));
  await page.refreshStandaloneSearch();
  assert.equal(consumed, true); assert.equal(loads, 0);
  assert.equal(page.standaloneSearch.searchServices[0].apiKey, 'draft-key'); assert.equal(page.settingsEdited, true);
});

test('switching tabs retains added services, credentials and stable selection until explicit save', async () => {
  const memory = new Map();
  const env = { getProductKind: () => 'deepread', SETTINGS_SESSION_DRAFT: 'session',
    AppStorage: { setOrCreate: (k, v) => memory.set(k, v), get: k => memory.get(k), delete: k => memory.delete(k) },
    consumeDeepReadSearchEditorDraft: () => null, getChatKvStore: () => ({}), loadSearchPrefs: async () => settings('saved-key') };
  const first = state(actualPage(file, ['saveSessionDraft'], env)); first.settingsEdited = true;
  first.standaloneSearch.searchServices.push({ id: 'added', type: 'jina', apiKey: 'added-key' });
  first.selectedSearchId = 'added'; first.saveSessionDraft();
  const second = state(actualPage(file, ['restoreSessionDraft', 'refreshStandaloneSearch'], env));
  second.standaloneSearchLoaded = false; second.restoreSessionDraft(); await second.refreshStandaloneSearch();
  assert.equal(second.selectedSearchId, 'added'); assert.equal(second.standaloneSearch.searchServiceSelected, 1);
  assert.equal(second.standaloneSearch.searchServices[1].apiKey, 'added-key');
});

test('save applies the complete service catalog and flags before reporting reading settings saved', async () => {
  const writes = []; let persisted;
  const page = state(actualPage(file, ['saveStandaloneSettings'], {
    getChatKvStore: () => ({}), updateSearchPrefs: async (_store, transform) => {
      persisted = transform(settings('saved-key')); writes.push('search'); } }));
  page.saveStandaloneReading = async () => writes.push('reading');
  await page.saveStandaloneSettings();
  assert.deepEqual(writes, ['search', 'reading']); assert.equal(persisted.searchServices[0].apiKey, 'draft-key');
  assert.equal(persisted.searchCommonOptions.resultSize, 7); assert.equal(persisted.searchBuiltinBingEnabled, false);
});

test('a failed search write or removed selected service cannot produce a success confirmation', async () => {
  let reading = 0, writes = 0;
  const page = state(actualPage(file, ['saveStandaloneSettings'], {
    getChatKvStore: () => ({}), updateSearchPrefs: async () => { writes++; throw Error('disk'); } }));
  page.saveStandaloneReading = async () => reading++;
  await assert.rejects(page.saveStandaloneSettings(), /disk/); assert.equal(reading, 0); assert.equal(page.saved, '');
  page.selectedSearchId = 'removed';
  await assert.rejects(page.saveStandaloneSettings(), /首选搜索服务已删除/); assert.equal(writes, 1); assert.equal(reading, 0);
});
