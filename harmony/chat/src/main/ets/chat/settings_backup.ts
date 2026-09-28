// 设置备份入口：按所选类别组装快照，并独立反馈 settings 覆盖/回滚。
import type { JsonObject } from './json.ts';
import type { KeyValueStore } from './kv_store.ts';
import type { SyncRestoreSettings } from './sync_snapshot.ts';
import {
  loadAssistants, saveAssistants, loadProviders, saveProviders,
  ASSISTANTS_KEY, PROVIDERS_KEY,
} from './kv_store.ts';
import { serializeAssistantList } from './assistant_serialize.ts';
import { serializeProviderSettingList } from './provider_settings_serialize.ts';

export const buildSyncSettingsBlob = async (
  kv: KeyValueStore, includeAssistants: boolean, includeProviders: boolean,
): Promise<JsonObject> => {
  const blob: JsonObject = {};
  if (includeAssistants) {
    const assistants = await loadAssistants(kv);
    if (assistants !== null) blob['assistants'] = JSON.parse(serializeAssistantList(assistants)) as JsonObject[];
  }
  if (includeProviders) {
    const providers = await loadProviders(kv);
    if (providers !== null) blob['providers'] = JSON.parse(serializeProviderSettingList(providers)) as JsonObject[];
  }
  return blob;
};

export const applySyncRestoreSettings = async (
  settings: SyncRestoreSettings, kv: KeyValueStore,
): Promise<string | null> => {
  const writeAssistants: boolean = settings.assistants !== null;
  const writeProviders: boolean = settings.providers !== null;
  if (!writeAssistants && !writeProviders) return null;
  let prevAssistants: string | null = null;
  let prevProviders: string | null = null;
  // 预读失败时尚未写入，单独反馈；不可把空的 prev 值当成原值回滚。
  try {
    if (writeAssistants) prevAssistants = await kv.get(ASSISTANTS_KEY);
    if (writeProviders) prevProviders = await kv.get(PROVIDERS_KEY);
  } catch (e) {
    return `（settings 读取失败，未覆盖：${e instanceof Error ? e.message : String(e)}）`;
  }
  try {
    if (settings.assistants !== null) await saveAssistants(kv, settings.assistants);
    if (settings.providers !== null) await saveProviders(kv, settings.providers);
    return '（已覆盖 settings）';
  } catch (e) {
    let rollbackNote: string = '';
    try {
      if (writeAssistants) {
        if (prevAssistants === null) await kv.delete(ASSISTANTS_KEY);
        else await kv.put(ASSISTANTS_KEY, prevAssistants);
      }
    } catch (re) {
      rollbackNote += ` assistants 回滚失败：${re instanceof Error ? re.message : String(re)}`;
    }
    try {
      if (writeProviders) {
        if (prevProviders === null) await kv.delete(PROVIDERS_KEY);
        else await kv.put(PROVIDERS_KEY, prevProviders);
      }
    } catch (re) {
      rollbackNote += ` providers 回滚失败：${re instanceof Error ? re.message : String(re)}`;
    }
    const msg: string = e instanceof Error ? e.message : String(e);
    if (rollbackNote.length > 0) {
      return `（settings 覆盖失败，回滚未完成：${msg}${rollbackNote}）`;
    }
    return `（settings 覆盖失败，已回滚原设置：${msg}）`;
  }
};
