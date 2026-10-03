const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const { loadSearchPrefs, saveSearchPrefs, updateSearchPrefs, defaultSearchPrefs } = require('../../../chat/src/main/ets/search/search_prefs.ts');
const source = fs.readFileSync(path.join(__dirname, '../main/ets/pages/DeepReadSearchSettingsPage.ets'), 'utf8');
function method(name) {
  const match = new RegExp('^  (?:private )?(?:async )?' + name + '\\(', 'm').exec(source);
  assert.ok(match, name);
  let end = source.indexOf('{', match.index) + 1, depth = 1;
  for (; depth && end < source.length; end++) { if (source[end] === '{') depth++; if (source[end] === '}') depth--; }
  return source.slice(match.index, end);
}
function fixture(store) {
  const code = ts.transpileModule('class Page {' + ['onPageShow', 'aboutToDisappear', 'refresh', 'saveChoice'].map(method).join('\n') + '} return Page;',
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const Page = new Function('getChatKvStore', 'loadSearchPrefs', 'updateSearchPrefs', code)(() => store, loadSearchPrefs, updateSearchPrefs);
  const page = new Page();
  Object.assign(page, { settings: defaultSearchPrefs(), loading: true, busy: false, alive: true, loadToken: 0, error: '' });
  return page;
}
function memory() { const values = new Map(); return { get: async key => values.get(key) ?? null, put: async (key, value) => values.set(key, value), values }; }
const options = (id, type = 'brave') => ({ type, id, apiKey: 'local-synthetic-key' });

test('actual settings refresh and save change the selected ID and Google flag consumed by registry preferences', async () => {
  const store = memory(), settings = defaultSearchPrefs();
  settings.searchServices = [options('first'), options('second')];
  settings.searchEnabledServiceIds = ['first', 'second'];
  await saveSearchPrefs(store, settings);
  const page = fixture(store); await page.refresh();
  assert.equal(page.loading, false);
  await page.saveChoice('second', false);
  const persisted = await loadSearchPrefs(store);
  assert.equal(persisted.searchServices[persisted.searchServiceSelected].id, 'second');
  assert.equal(persisted.searchGoogleWebViewFallbackEnabled, false);
  assert.equal(page.settings.searchServiceSelected, 1);
  assert.equal(persisted.searchBuiltinWikipediaEnabled, true);
  assert.equal(store.values.has('active_search_source'), false, 'unused legacy settings are not written');
});

test('selection resolves the captured service ID against current storage after reordering; deleted choice is an explicit error', async () => {
  const store = memory(), settings = defaultSearchPrefs();
  settings.searchServices = [options('first'), options('second')]; await saveSearchPrefs(store, settings);
  const page = fixture(store); await page.refresh();
  settings.searchServices.reverse(); await saveSearchPrefs(store, settings);
  await page.saveChoice('first');
  const current = await loadSearchPrefs(store);
  assert.equal(current.searchServiceSelected, 1);
  await page.saveChoice('deleted');
  assert.match(page.error, /已删除/);
  assert.equal((await loadSearchPrefs(store)).searchServiceSelected, 1);
});

test('loading blocks initial default-value writes, and a late read after page disappearance cannot change rendered state', async () => {
  const store = memory(), page = fixture(store);
  await page.saveChoice(undefined, false);
  assert.equal(store.values.size, 0);
  let resolve;
  const pending = new Promise(r => { resolve = r; });
  store.get = async () => pending;
  const read = page.refresh(); page.aboutToDisappear(); resolve(null); await read;
  assert.equal(page.alive, false); assert.equal(page.settings.searchGoogleWebViewFallbackEnabled, true);
});
