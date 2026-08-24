// miniapp_setting — MiniAppSetting 17 布尔开关 + 默认值 + 序列化 + 全局权限映射
//
// Android 基准:
//   core/settings/PreferencesStore.kt:186-204(MiniAppSetting data class 默认值)
//   feature/miniapp/MiniAppSandbox.kt:33-54(isGloballyEnabled 映射)
// 偏差:
//   - 鸿蒙侧为独立 KV 键(mini_app_setting;display_setting.ts 同款 load/save 模式),
//     Android 为 Settings DataStore 内嵌字段 — 存储形态由 adapter 决定
//   - isGloballyEnabled 提取为纯函数 isPermissionGloballyEnabled(供 Sandbox 复用)

import type { MiniAppPermission } from './miniapp_models.ts';

export interface MiniAppSetting {
  enabled: boolean;
  networkEnabled: boolean;
  externalImagesEnabled: boolean;
  searchEnabled: boolean;
  clipboardCopyEnabled: boolean;
  boardSummaryUpdateEnabled: boolean;
  hostContextEnabled: boolean;
  hostWriteEnabled: boolean;
  aiEnabled: boolean;
  sharedStoreEnabled: boolean;
  eventBusEnabled: boolean;
  launchEnabled: boolean;
  sensorEnabled: boolean;
  locationEnabled: boolean;
  clipboardReadEnabled: boolean;
  webViewDebugEnabled: boolean;
  showSourceButton: boolean;
}

// PreferencesStore.kt:186-204 默认值逐字
export const makeMiniAppSetting = (opts: Partial<MiniAppSetting> = {}): MiniAppSetting => ({
  enabled: opts.enabled ?? true,
  networkEnabled: opts.networkEnabled ?? true,
  externalImagesEnabled: opts.externalImagesEnabled ?? true,
  searchEnabled: opts.searchEnabled ?? true,
  clipboardCopyEnabled: opts.clipboardCopyEnabled ?? true,
  boardSummaryUpdateEnabled: opts.boardSummaryUpdateEnabled ?? true,
  hostContextEnabled: opts.hostContextEnabled ?? false,
  hostWriteEnabled: opts.hostWriteEnabled ?? false,
  aiEnabled: opts.aiEnabled ?? true,
  sharedStoreEnabled: opts.sharedStoreEnabled ?? true,
  eventBusEnabled: opts.eventBusEnabled ?? true,
  launchEnabled: opts.launchEnabled ?? true,
  sensorEnabled: opts.sensorEnabled ?? true,
  locationEnabled: opts.locationEnabled ?? false,
  clipboardReadEnabled: opts.clipboardReadEnabled ?? false,
  webViewDebugEnabled: opts.webViewDebugEnabled ?? false,
  showSourceButton: opts.showSourceButton ?? true,
});

export const MINI_APP_SETTING_KEY: string = 'mini_app_setting';

export const serializeMiniAppSetting = (setting: MiniAppSetting): string =>
  JSON.stringify(setting);

export const parseMiniAppSetting = (raw: string): MiniAppSetting => {
  try {
    const parsed: Partial<MiniAppSetting> = JSON.parse(raw);
    return makeMiniAppSetting(parsed);
  } catch (_e) {
    return makeMiniAppSetting();
  }
};

import type { KeyValueStore } from '../kv_store.ts';

export const loadMiniAppSetting = async (store: KeyValueStore): Promise<MiniAppSetting> => {
  const raw: string | null = await store.get(MINI_APP_SETTING_KEY);
  if (raw === null) return makeMiniAppSetting();
  return parseMiniAppSetting(raw);
};

export const saveMiniAppSetting = async (store: KeyValueStore, setting: MiniAppSetting): Promise<void> => {
  await store.put(MINI_APP_SETTING_KEY, serializeMiniAppSetting(setting));
};

// ===== 全局能力开关映射(MiniAppSandbox.kt:33-54)=====
export const isPermissionGloballyEnabled = (
  permission: MiniAppPermission,
  setting: MiniAppSetting,
): boolean => {
  switch (permission) {
    case 'storage':
    case 'toast':
    case 'theme':
      return true;
    case 'network':
      return setting.networkEnabled;
    case 'externalImages':
      return setting.externalImagesEnabled;
    case 'search':
      return setting.searchEnabled;
    case 'clipboard.copy':
      return setting.clipboardCopyEnabled;
    case 'host.updateBoardSummary':
      return setting.boardSummaryUpdateEnabled;
    case 'host.context':
      return setting.hostContextEnabled;
    case 'host.sendToConversation':
    case 'host.createArtifact':
      return setting.hostWriteEnabled;
    case 'ai.generate':
      return setting.aiEnabled;
    case 'sharedStore':
      return setting.sharedStoreEnabled;
    case 'eventBus':
      return setting.eventBusEnabled;
    case 'launch':
      return setting.launchEnabled;
    case 'sensor':
      return setting.sensorEnabled;
    case 'location':
      return setting.locationEnabled;
    case 'clipboard.read':
      return setting.clipboardReadEnabled;
  }
};
