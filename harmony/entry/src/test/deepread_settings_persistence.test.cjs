const { test } = require('node:test');
const assert = require('node:assert/strict');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const { loadSearchPrefs, saveSearchPrefs } = require('../../../chat/src/main/ets/search/search_prefs.ts');
const { actualPage } = require('../../../deepread/src/test/deepread_settings_fixture.cjs');

test('independent settings Save writes a result-size-only change through the real prefs owner and preserves equal-value deduplication', async () => {
  const values = new Map(); let writes = 0;
  const kv = { get: async key => values.get(key) ?? null,
    put: async (key, value) => { writes++; values.set(key, value); } };
  await saveSearchPrefs(kv, await loadSearchPrefs(kv)); writes = 0;
  const draft = await loadSearchPrefs(kv);
  assert.equal(draft.searchCommonOptions.resultSize, 10);
  draft.searchCommonOptions.resultSize = 11;
  const reading = [];
  const storage = { get: async (_key, fallback) => fallback, set: async (key, value) => reading.push([key, value]) };
  const page = actualPage('pages/SettingDeepReadPage.ets', ['saveStandaloneSettings'], {
    getProductKind: () => 'deepread', getChatKvStore: () => kv, getAppContainer: () => ({ storage }),
    setTimeout: () => 1, clearTimeout() {},
  });
  Object.assign(page, { storage, pageAlive: true, standaloneSearchLoaded: true, standaloneSearch: draft,
    selectedSearchId: draft.searchServices[0].id, pageLoad: Promise.resolve() });
  await page.saveStandaloneSettings();
  assert.equal((await loadSearchPrefs(kv)).searchCommonOptions.resultSize, 11);
  assert.equal(writes, 11, 'the unchanged prefs writer persists its existing eleven keys');
  assert.equal(reading.length, 3, 'the actual owner also commits reading fields after search succeeds');
  assert.match(page.saved, /已保存/);
  await page.saveStandaloneSettings();
  assert.equal(writes, 11, 'equal search values retain the real prefs JSON deduplication');
});
