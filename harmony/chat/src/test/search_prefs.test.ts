// search_prefs.test.ts — D-070 SearchPrefs 持久化
// Android 基准: SearchPrefs.kt(全文 92 行) + PreferencesKeys.kt:49-58(键冻结)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createMemoryKeyValueStore } from '../main/ets/chat/kv_store.ts';
import { makeSearchServiceOptions } from '../main/ets/search/search_service.ts';
import {
  SEARCH_PREFS_KEYS,
  defaultSearchPrefs,
  loadSearchPrefs,
  saveSearchPrefs,
  updateSearchPrefs,
  initializeDeepReadSearchPrefs,
} from '../main/ets/search/search_prefs.ts';

test('DeepRead first launch enables and persists the free aggregate before settings and search read it', async () => {
  const store = createMemoryKeyValueStore();
  assert.deepEqual((await loadSearchPrefs(store)).searchEnabledServiceIds, [], 'chat defaults remain unchanged');
  await store.put(SEARCH_PREFS_KEYS.builtinBing, 'false');
  await initializeDeepReadSearchPrefs(store);
  const settings = await loadSearchPrefs(store);
  assert.equal(settings.searchServices[0].type, 'bing_local');
  assert.deepEqual(settings.searchEnabledServiceIds, [settings.searchServices[0].id]);
  assert.equal(settings.searchBuiltinBingEnabled, false, 'other saved source preferences survive initialization');
  assert.equal(settings.enableWebSearch, false, 'chat web-search master switch is independent');
  const saved = new Map(store.entries);
  await initializeDeepReadSearchPrefs(store);
  assert.deepEqual(store.entries, saved, 'subsequent launches do not rewrite settings or service IDs');
});

test('DeepRead initialization preserves existing services and an explicitly disabled aggregate', async () => {
  const store = createMemoryKeyValueStore();
  const settings = defaultSearchPrefs();
  await saveSearchPrefs(store, settings);
  const saved = new Map(store.entries);
  await initializeDeepReadSearchPrefs(store);
  assert.deepEqual(store.entries, saved);
  assert.deepEqual((await loadSearchPrefs(store)).searchEnabledServiceIds, []);

  for (const key of [SEARCH_PREFS_KEYS.searchServices, SEARCH_PREFS_KEYS.searchEnabledServiceIds]) {
    const existing = createMemoryKeyValueStore();
    await existing.put(key, '[]');
    await initializeDeepReadSearchPrefs(existing);
    assert.equal(existing.entries.size, 1, 'a partial existing preference set is not treated as a fresh install');
  }
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
  assert.equal(puts, 11); // 十一键全写(writeTo 逐字;D-099 += enable_web_search)
  const d = await loadSearchPrefs(store);
  assert.equal(d.searchServiceSelected, 2);
});

test('prefs:enable_web_search(D-099)— 缺失 → false(ChatPrefs.kt:66 == true 语义);往返', async () => {
  const store = createMemoryKeyValueStore();
  // 缺失 → false(Android 默认 OFF;修复 D-070 硬默认 true 偏差)
  assert.equal((await loadSearchPrefs(store)).enableWebSearch, false);
  const d = defaultSearchPrefs();
  assert.equal(d.enableWebSearch, false);
  d.enableWebSearch = true;
  await saveSearchPrefs(store, d);
  assert.equal(store.entries.get('enable_web_search'), 'true');
  assert.equal((await loadSearchPrefs(store)).enableWebSearch, true);
  // == true 语义:仅 'true' 串 → true;其他/缺失 → false(区别于 builtin 的 != false)
  await store.put('enable_web_search', 'garbage');
  assert.equal((await loadSearchPrefs(store)).enableWebSearch, false);
});

// ===== Phase 6 回归:未知服务 type 只跳过单条,不抹掉整份配置 =====

test('loadSearchPrefs: unknown service type skipped, valid services preserved', async () => {
  const store = createMemoryKeyValueStore();
  const raw = JSON.stringify([
    { type: 'tavily', id: 's1', apiKey: 'k1' },
    { type: 'future_provider_v9', id: 's2' },
    { type: 'bing_local', id: 's3' },
  ]);
  await store.put(SEARCH_PREFS_KEYS.searchServices, raw);
  const prefs = await loadSearchPrefs(store);
  const types = prefs.searchServices.map((s): string => s.type);
  assert.deepEqual(types, ['tavily', 'bing_local'],
    'unknown entry skipped; configured valid services preserved');
});

test('loadSearchPrefs: all-unknown services blob falls back to default (not empty)', async () => {
  const store = createMemoryKeyValueStore();
  await store.put(SEARCH_PREFS_KEYS.searchServices, JSON.stringify([{ type: 'nope_v1' }]));
  const prefs = await loadSearchPrefs(store);
  assert.ok(prefs.searchServices.length > 0);
  assert.equal(prefs.searchServices[0].type, 'bing_local');
});
