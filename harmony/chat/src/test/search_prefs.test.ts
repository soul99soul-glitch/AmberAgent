// search_prefs.test.ts — D-070 SearchPrefs 持久化
// Android 基准: SearchPrefs.kt(全文 92 行) + PreferencesKeys.kt:49-58(键冻结)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createMemoryKeyValueStore } from '../main/ets/chat/kv_store.ts';
import { makeSearchServiceOptions } from '../main/ets/search/search_service.ts';
import {
  SEARCH_PREFS_KEYS, defaultSearchPrefs, loadSearchPrefs, saveSearchPrefs, updateSearchPrefs,
} from '../main/ets/search/search_prefs.ts';

test('prefs:空 store → 默认逐字(services [bing_local]/common 10/selected 0/ids []/五内置 true/webview true)', async () => {
  const store = createMemoryKeyValueStore();
  const d = await loadSearchPrefs(store);
  assert.equal(d.searchServices.length, 1);
  assert.equal(d.searchServices[0].type, 'bing_local');
  assert.equal(d.searchCommonOptions.resultSize, 10);
  assert.equal(d.searchServiceSelected, 0);
  assert.deepEqual(d.searchEnabledServiceIds, []);
  assert.equal(d.searchBuiltinDuckDuckGoEnabled, true);
  assert.equal(d.searchBuiltinBingEnabled, true);
  assert.equal(d.searchBuiltinJinaEnabled, true);
  assert.equal(d.searchBuiltinWikipediaEnabled, true);
  assert.equal(d.searchBuiltinHackerNewsEnabled, true);
  assert.equal(d.searchGoogleWebViewFallbackEnabled, true);
});

test('prefs:save→load 往返;键字符串冻结(ADR-0001);services JSON type 居首', async () => {
  const store = createMemoryKeyValueStore();
  const d = defaultSearchPrefs();
  const tavily = makeSearchServiceOptions('tavily');
  d.searchServices = [tavily];
  d.searchServiceSelected = 0;
  d.searchEnabledServiceIds = [tavily.id];
  d.searchBuiltinBingEnabled = false;
  d.searchGoogleWebViewFallbackEnabled = false;
  d.searchCommonOptions = { resultSize: 20 };
  await saveSearchPrefs(store, d);
  // 冻结键
  for (const key of Object.values(SEARCH_PREFS_KEYS)) {
    assert.ok(store.entries.has(key), `missing frozen key ${key}`);
  }
  assert.equal(store.entries.get('search_selected'), '0');
  assert.equal(store.entries.get('search_builtin_bing_enabled'), 'false');
  assert.equal(store.entries.get('search_common'), '{"resultSize":20}');
  const servicesJson = store.entries.get('search_services') as string;
  assert.ok(servicesJson.startsWith('[{"type":"tavily"'));
  const loaded = await loadSearchPrefs(store);
  assert.equal(loaded.searchServices[0].type, 'tavily');
  assert.equal(loaded.searchCommonOptions.resultSize, 20);
  assert.deepEqual(loaded.searchEnabledServiceIds, [tavily.id]);
  assert.equal(loaded.searchBuiltinBingEnabled, false);
  assert.equal(loaded.searchGoogleWebViewFallbackEnabled, false);
});

test('prefs:decode 失败 → 默认;`!= false` 语义;selected 解析失败 → 0', async () => {
  const store = createMemoryKeyValueStore();
  await store.put('search_services', '{bad');
  await store.put('search_common', '[1,2]');
  await store.put('search_enabled_service_ids', 'not json');
  await store.put('search_selected', 'abc');
  await store.put('search_builtin_jina_enabled', 'false');
  await store.put('search_builtin_bing_enabled', '1'); // 非 'false' → true
  const d = await loadSearchPrefs(store);
  assert.equal(d.searchServices[0].type, 'bing_local'); // decode 失败回默认
  assert.equal(d.searchCommonOptions.resultSize, 10);
  assert.deepEqual(d.searchEnabledServiceIds, []);
  assert.equal(d.searchServiceSelected, 0);
  assert.equal(d.searchBuiltinJinaEnabled, false);
  assert.equal(d.searchBuiltinBingEnabled, true);
});

test('prefs:update — next == current 短路不写;变化才写', async () => {
  const store = createMemoryKeyValueStore();
  let puts: number = 0;
  const spy = {
    get: (k: string) => store.get(k),
    put: async (k: string, v: string): Promise<void> => {
      puts++;
      await store.put(k, v);
    },
    delete: (k: string) => store.delete(k),
  };
  await updateSearchPrefs(spy, (c) => c); // 恒等 → 短路
  assert.equal(puts, 0);
  await updateSearchPrefs(spy, (c) => ({ ...c, searchServiceSelected: 2 }));
  assert.equal(puts, 10); // 十键全写(writeTo 逐字)
  const d = await loadSearchPrefs(store);
  assert.equal(d.searchServiceSelected, 2);
});
