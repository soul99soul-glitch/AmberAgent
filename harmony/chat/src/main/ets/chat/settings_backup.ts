// 设置备份入口：按所选类别组装快照，并独立反馈 settings 覆盖/回滚。
//
// H4.3 导出边界：备份载荷里不放明文凭据。Android SyncPayloadExporter:52-70 走
//   redactForExport（掩码 + refs，明文永不进导出 JSON），iOS IOSSyncBackup.export:52-56
//   走 IOSCredentialRedactor，都是「加密不能代替脱敏」。鸿蒙此前从 loadProviders
//   拿到回填后的明文 key 直接放进 settings blob，于是关掉「使用密码加密」就会得到
//   一份明文凭据的备份。这里在打包前用同一套 secret_fields_blob 位置把凭据清空。
//
// 取舍：恢复时密钥字段为空，需要用户重新填写（与 Android 的 refs 方案不同——refs 要求
//   两端共享 SecretStore 寻址；鸿蒙当前没有跨端 secret 寻址协议）。宁可少恢复
//   一个可重填的密钥，也不把密钥写进导出文件。
import type { JsonObject } from './json.ts';
import type { KeyValueStore } from './kv_store.ts';
import type { SyncRestoreSettings } from './sync_snapshot.ts';
import {
  loadAssistants, saveAssistants, loadProviders, saveProviders,
  ASSISTANTS_KEY, PROVIDERS_KEY,
} from './kv_store.ts';
import { serializeAssistantList } from './assistant_serialize.ts';
import { serializeProviderSettingList } from './provider_settings_serialize.ts';
import { stripSecretFields } from './secret_fields_blob.ts';

export const buildSyncSettingsBlob = async (
  kv: KeyValueStore, includeAssistants: boolean, includeProviders: boolean,
): Promise<JsonObject> => {
  const blob: JsonObject = {};
  if (includeAssistants) {
    const assistants = await loadAssistants(kv);
    if (assistants !== null) {
      const serialized: string = serializeAssistantList(assistants);
      blob['assistants'] = JSON.parse(stripSecretFields(serialized)) as JsonObject[];
    }
  }
  if (includeProviders) {
    const providers = await loadProviders(kv);
    if (providers !== null) {
      // 先序列化再按位置剥掉凭据:与静息存储同一套字段位置(apiKey/privateKey/
      // customHeaders 的 value/providerOverwrite.apiKey),不另写一份脱敏规则。
      const serialized: string = serializeProviderSettingList(providers);
      blob['providers'] = JSON.parse(stripSecretFields(serialized)) as JsonObject[];
    }
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
