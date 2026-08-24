// KeyValueStore Port — DataStore/Preferences 语义的最小契约
//
// Android 基准: core/settings PreferencesStore(Settings DataStore,
//   assistants 为 Settings blob 内 List<Assistant> JSON)。
// HarmonyOS adapter: @kit.ArkData preferences(设备端,G-C 后接)。
//
// 语义:get 无键 → null(由调用方落默认值);put 覆盖;delete 移除。

import type { Assistant } from './assistant.ts';
import { serializeAssistantList, parseAssistantList } from './assistant_serialize.ts';
import type { ProviderSetting } from './provider_settings.ts';
import { serializeProviderSettingList, parseProviderSettingList } from './provider_settings_serialize.ts';
import type { McpServerConfig } from './mcp_config.ts';
import { parseMcpServerConfigList, serializeMcpServerConfigList } from './mcp_config_serialize.ts';

export interface KeyValueStore {
  get(key: string): Promise<string | null>;
  put(key: string, value: string): Promise<void>;
  delete(key: string): Promise<void>;
  // 全部字符串键值枚举(设备端 Preferences getAll 语义;内存实现必给,
  // 其余 adapter 未实现则 MiniAppStorage 跳过配额核算)
  allEntries?(): Promise<Array<{ key: string; value: string }>>;
}

export interface MemoryKeyValueStore extends KeyValueStore {
  entries: Map<string, string>;
}

export const createMemoryKeyValueStore = (): MemoryKeyValueStore => {
  const entries = new Map<string, string>();
  return {
    entries,
    get(key: string): Promise<string | null> {
      const v = entries.get(key);
      return Promise.resolve(v === undefined ? null : v);
    },
    put(key: string, value: string): Promise<void> {
      entries.set(key, value);
      return Promise.resolve();
    },
    delete(key: string): Promise<void> {
      entries.delete(key);
      return Promise.resolve();
    },
    allEntries(): Promise<Array<{ key: string; value: string }>> {
      const out: Array<{ key: string; value: string }> = [];
      for (const [key, value] of entries) {
        out.push({ key, value });
      }
      return Promise.resolve(out);
    },
  };
};

// assistants blob 键(我方 schema;Android 为整个 Settings blob 内嵌字段,
// 设备端 adapter 决定最终存储形态 — D-016 登记)
export const ASSISTANTS_KEY = 'assistants';

export const saveAssistants = (store: KeyValueStore, list: Assistant[]): Promise<void> =>
  store.put(ASSISTANTS_KEY, serializeAssistantList(list));

// 无键 → null(对齐 DataStore 无值语义);有键 → 解析
export const loadAssistants = async (store: KeyValueStore): Promise<Assistant[] | null> => {
  const raw = await store.get(ASSISTANTS_KEY);
  if (raw === null) return null;
  return parseAssistantList(raw);
};

// providers blob 键(同 D-016 口径,我方 schema)
export const PROVIDERS_KEY = 'providers';

export const saveProviders = (store: KeyValueStore, list: ProviderSetting[]): Promise<void> =>
  store.put(PROVIDERS_KEY, serializeProviderSettingList(list));

export const loadProviders = async (store: KeyValueStore): Promise<ProviderSetting[] | null> => {
  const raw = await store.get(PROVIDERS_KEY);
  if (raw === null) return null;
  return parseProviderSettingList(raw);
};

// D-116:mcpServers blob 键(Settings.kt:101 内嵌字段 → 独立 KV,D-016 同口径;
//   线格式 = kotlinx sealed 鉴别名 'sse'/'streamable_http' 逐字)
export const MCP_SERVERS_KEY = 'mcp_servers';

export const saveMcpServers = (store: KeyValueStore, list: McpServerConfig[]): Promise<void> =>
  store.put(MCP_SERVERS_KEY, serializeMcpServerConfigList(list));

export const loadMcpServers = async (store: KeyValueStore): Promise<McpServerConfig[] | null> => {
  const raw = await store.get(MCP_SERVERS_KEY);
  if (raw === null) return null;
  return parseMcpServerConfigList(raw);
};
