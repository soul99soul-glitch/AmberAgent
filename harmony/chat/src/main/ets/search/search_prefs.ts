// search_prefs — 搜索偏好持久化(D-070)
// Android 基准:
//   core/settings/.../prefs/SearchPrefs.kt(全文 92 行)
//   core/app-infra/.../PreferencesKeys.kt:49-58(键字符串 ADR-0001 冻结)
// 偏差:DataStore typed keys → KeyValueStore 字符串 Port(int/boolean 存串;
//   读 'false' 串 → false,缺失/其他 → true,同 `!= false` 语义);
//   data class 相等 → JSON 串相等比较(update 短路)
import type { JsonObject, JsonValue } from '../chat/json.ts';
import type { KeyValueStore } from '../chat/kv_store.ts';
import type { SearchCommonOptions, SearchServiceOptions } from './search_service.ts';
import {
  DEFAULT_SEARCH_COMMON_OPTIONS, DEFAULT_SEARCH_SERVICE_OPTIONS,
  searchServiceOptionsFromJson, searchServiceOptionsToJson,
} from './search_service.ts';
import type { SearchSettings } from './search_aggregator.ts';

// PreferencesKeys.kt:49-58 冻结键字符串;D-099 += 'enable_web_search'(PreferencesKeys.kt:26)
export const SEARCH_PREFS_KEYS = {
  enableWebSearch: 'enable_web_search',
  searchServices: 'search_services',
  searchCommon: 'search_common',
  searchSelected: 'search_selected',
  searchEnabledServiceIds: 'search_enabled_service_ids',
  builtinDuckDuckGo: 'search_builtin_duckduckgo_enabled',
  builtinBing: 'search_builtin_bing_enabled',
  builtinJina: 'search_builtin_jina_enabled',
  builtinWikipedia: 'search_builtin_wikipedia_enabled',
  builtinHackerNews: 'search_builtin_hackernews_enabled',
  googleWebViewFallback: 'search_google_webview_fallback_enabled',
} as const;

// SearchPrefsData(:22-33,默认逐字);enableWebSearch 属 ChatPrefs(PreferencesKeys.kt:26)
//   — ChatPrefs.kt:66 `p[ENABLE_WEB_SEARCH] == true` → 缺失默认 false
//   (D-099 修正:D-070 硬默认 true 为过渡偏差,设置面落地同步关闭)
export const defaultSearchPrefs = (): SearchSettings => ({
  enableWebSearch: false,
  searchCommonOptions: { resultSize: DEFAULT_SEARCH_COMMON_OPTIONS.resultSize },
  searchServices: [DEFAULT_SEARCH_SERVICE_OPTIONS],
  searchServiceSelected: 0,
  searchEnabledServiceIds: [],
  searchBuiltinDuckDuckGoEnabled: true,
  searchBuiltinBingEnabled: true,
  searchBuiltinJinaEnabled: true,
  searchBuiltinWikipediaEnabled: true,
  searchBuiltinHackerNewsEnabled: true,
  searchGoogleWebViewFallbackEnabled: true,
});

const parseServices = (raw: string | null): SearchServiceOptions[] => {
  if (raw === null) return [DEFAULT_SEARCH_SERVICE_OPTIONS];
  try {
    const arr: JsonValue = JSON.parse(raw) as JsonValue;
    if (!Array.isArray(arr)) return [DEFAULT_SEARCH_SERVICE_OPTIONS];
    // 逐条解码:未知 type(未来版本/损坏单条)只跳过该条,
    // 不得把整份已配置服务列表抹成默认;全部无效才回落默认
    const out: SearchServiceOptions[] = [];
    for (const v of arr) {
      try {
        out.push(searchServiceOptionsFromJson(
          (typeof v === 'object' && v !== null) ? v as JsonObject : {}));
      } catch {
        // skip unknown entry
      }
    }
    return out.length > 0 ? out : [DEFAULT_SEARCH_SERVICE_OPTIONS];
  } catch {
    return [DEFAULT_SEARCH_SERVICE_OPTIONS]; // decodeJsonOrNull ?: default
  }
};

const parseCommon = (raw: string | null): SearchCommonOptions => {
  if (raw === null) return { ...DEFAULT_SEARCH_COMMON_OPTIONS };
  try {
    const j: JsonValue = JSON.parse(raw) as JsonValue;
    if (typeof j !== 'object' || j === null || Array.isArray(j)) {
      return { ...DEFAULT_SEARCH_COMMON_OPTIONS };
    }
    const rs: JsonValue | undefined = (j as JsonObject)['resultSize'];
    return { resultSize: typeof rs === 'number' ? rs : DEFAULT_SEARCH_COMMON_OPTIONS.resultSize };
  } catch {
    return { ...DEFAULT_SEARCH_COMMON_OPTIONS };
  }
};

const parseIds = (raw: string | null): string[] => {
  if (raw === null) return [];
  try {
    const arr: JsonValue = JSON.parse(raw) as JsonValue;
    if (!Array.isArray(arr)) return [];
    return arr.filter((v: JsonValue): boolean => typeof v === 'string') as string[];
  } catch {
    return [];
  }
};

// `!= false` 语义:仅 'false' 串 → false;缺失/其他 → true
const parseBoolNotFalse = (raw: string | null): boolean => raw !== 'false';

// readFrom(:58-75)
export const loadSearchPrefs = async (store: KeyValueStore): Promise<SearchSettings> => {
  const [
    services, common, selected, ids,
    ddg, bing, jina, wiki, hn, webview, webSearch,
  ] = await Promise.all([
    store.get(SEARCH_PREFS_KEYS.searchServices),
    store.get(SEARCH_PREFS_KEYS.searchCommon),
    store.get(SEARCH_PREFS_KEYS.searchSelected),
    store.get(SEARCH_PREFS_KEYS.searchEnabledServiceIds),
    store.get(SEARCH_PREFS_KEYS.builtinDuckDuckGo),
    store.get(SEARCH_PREFS_KEYS.builtinBing),
    store.get(SEARCH_PREFS_KEYS.builtinJina),
    store.get(SEARCH_PREFS_KEYS.builtinWikipedia),
    store.get(SEARCH_PREFS_KEYS.builtinHackerNews),
    store.get(SEARCH_PREFS_KEYS.googleWebViewFallback),
    store.get(SEARCH_PREFS_KEYS.enableWebSearch),
  ]);
  const d: SearchSettings = defaultSearchPrefs();
  const selectedParsed: number = selected === null ? 0 : parseInt(selected, 10);
  return {
    ...d,
    // ChatPrefs.kt:66 `== true` 语义:仅 'true' 串 → true;缺失/其他 → false
    enableWebSearch: webSearch === 'true',
    searchServices: parseServices(services),
    searchCommonOptions: parseCommon(common),
    searchServiceSelected: Number.isNaN(selectedParsed) ? 0 : selectedParsed,
    searchEnabledServiceIds: parseIds(ids),
    searchBuiltinDuckDuckGoEnabled: parseBoolNotFalse(ddg),
    searchBuiltinBingEnabled: parseBoolNotFalse(bing),
    searchBuiltinJinaEnabled: parseBoolNotFalse(jina),
    searchBuiltinWikipediaEnabled: parseBoolNotFalse(wiki),
    searchBuiltinHackerNewsEnabled: parseBoolNotFalse(hn),
    searchGoogleWebViewFallbackEnabled: parseBoolNotFalse(webview),
  };
};

// writeTo(:77-90)
export const saveSearchPrefs = async (store: KeyValueStore, data: SearchSettings): Promise<void> => {
  const servicesJson: string = JSON.stringify(
    data.searchServices.map((o: SearchServiceOptions): JsonObject => searchServiceOptionsToJson(o)),
  );
  await Promise.all([
    store.put(SEARCH_PREFS_KEYS.enableWebSearch, String(data.enableWebSearch)),
    store.put(SEARCH_PREFS_KEYS.searchServices, servicesJson),
    store.put(SEARCH_PREFS_KEYS.searchCommon, JSON.stringify({ resultSize: data.searchCommonOptions.resultSize })),
    store.put(SEARCH_PREFS_KEYS.searchSelected, String(data.searchServiceSelected)),
    store.put(SEARCH_PREFS_KEYS.searchEnabledServiceIds, JSON.stringify(data.searchEnabledServiceIds)),
    store.put(SEARCH_PREFS_KEYS.builtinDuckDuckGo, String(data.searchBuiltinDuckDuckGoEnabled)),
    store.put(SEARCH_PREFS_KEYS.builtinBing, String(data.searchBuiltinBingEnabled)),
    store.put(SEARCH_PREFS_KEYS.builtinJina, String(data.searchBuiltinJinaEnabled)),
    store.put(SEARCH_PREFS_KEYS.builtinWikipedia, String(data.searchBuiltinWikipediaEnabled)),
    store.put(SEARCH_PREFS_KEYS.builtinHackerNews, String(data.searchBuiltinHackerNewsEnabled)),
    store.put(SEARCH_PREFS_KEYS.googleWebViewFallback, String(data.searchGoogleWebViewFallbackEnabled)),
  ]);
};

// Standalone DeepRead starts with its free aggregate enabled, matching iOS.
// An existing catalog or enabled-ID list belongs to the user, including an empty list.
export const initializeDeepReadSearchPrefs = async (store: KeyValueStore): Promise<void> => {
  const [services, ids] = await Promise.all([
    store.get(SEARCH_PREFS_KEYS.searchServices),
    store.get(SEARCH_PREFS_KEYS.searchEnabledServiceIds),
  ]);
  if (services !== null || ids !== null) return;
  const settings: SearchSettings = await loadSearchPrefs(store);
  settings.searchEnabledServiceIds = settings.searchServices.map((service: SearchServiceOptions): string => service.id);
  await saveSearchPrefs(store, settings);
};

// 按存储实例串行化 load→transform→save:快速连续改设置时并发快照
// 会互相覆盖(丢一次修改);WeakMap 键 = store 身份,测试多实例不互扰
const updateQueues: WeakMap<object, Promise<void>> = new WeakMap<object, Promise<void>>();

// update(:49-56):next == current 短路(JSON 串相等,偏差登记)
export const updateSearchPrefs = (
  store: KeyValueStore,
  transform: (current: SearchSettings) => SearchSettings,
): Promise<void> => {
  const storeKey: object = store as unknown as object;
  const previous: Promise<void> = updateQueues.get(storeKey) ?? Promise.resolve();
  const run: Promise<void> = previous.catch((): void => {}).then(async (): Promise<void> => {
    const current: SearchSettings = await loadSearchPrefs(store);
    const next: SearchSettings = transform(current);
    if (JSON.stringify(next) === JSON.stringify(current)) return;
    await saveSearchPrefs(store, next);
  });
  updateQueues.set(storeKey, run.then((): void => {}, (): void => {}));
  return run;
};
