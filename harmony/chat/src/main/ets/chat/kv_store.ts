// KeyValueStore Port — DataStore/Preferences 语义的最小契约
//
// Android 基准: core/settings PreferencesStore(Settings DataStore,
//   assistants 为 Settings blob 内 List<Assistant> JSON)。
// HarmonyOS adapter: @kit.ArkData preferences(设备端,G-C 后接)。
//
// 语义:get 无键 → null(由调用方落默认值);put 覆盖;delete 移除。

import type { Assistant } from './assistant.ts';
import { serializeAssistantList, parseAssistantList } from './assistant_serialize.ts';

export interface KeyValueStore {
  get(key: string): Promise<string | null>;
  put(key: string, value: string): Promise<void>;
  delete(key: string): Promise<void>;
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
