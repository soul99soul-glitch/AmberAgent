// PlaceholderSupport — PlaceholderValues entry 实现(D-073)
// Android 基准: PlaceholderTransformer.kt:62-134(resolver 平台取值)
// 偏差(登记):
//   - Java FormatStyle.MEDIUM → Intl dateStyle/timeStyle medium(语义档等价,字节级不保证)
//   - Locale.getDefault().displayName → language-region 原始 tag(Intl.DisplayNames
//     在 ArkTS 不可用,登记)
//   - TimeZone.getDefault().displayName → IANA 名(Intl resolvedOptions().timeZone)
//   - "Android SDK v${SDK_INT} (${RELEASE})" → "HarmonyOS API v${sdkApiVersion} (${osFullName})"
//   - Build.BRAND+MODEL → deviceInfo.brand+productModel
//   - BATTERY_PROPERTY_CAPACITY(不可用 → -1)→ batteryInfo.batterySOC(异常 → -1)
import { batteryInfo, deviceInfo } from '@kit.BasicServicesKit';
import { i18n } from '@kit.LocalizationKit';
import type { KeyValueStore, PlaceholderValues } from '@amber/chat-domain';
import { loadDisplaySetting } from '@amber/chat-domain';
import { systemLanguage } from './AgentRuntimePrefs.ets';

// PreferencesKeys.kt:22(逐字)
export const DISPLAY_SETTING_KEY: string = 'display_setting';

// UIPrefs.kt:56 — 缺失/解析失败 → 默认(userNickname='')
// 统一经 domain 的 loadDisplaySetting 读取(消除并行 DisplaySettingJson 解析)
export const loadUserNickname = async (kv: KeyValueStore): Promise<string> => {
  try {
    const setting = await loadDisplaySetting(kv);
    return setting.userNickname;
  } catch {
    return '';
  }
};

const mediumDate = (): string =>
  new Intl.DateTimeFormat(systemLanguage(), { dateStyle: 'medium' }).format(new Date());

const mediumTime = (): string =>
  new Intl.DateTimeFormat(systemLanguage(), { timeStyle: 'medium' }).format(new Date());

const mediumDatetime = (): string =>
  new Intl.DateTimeFormat(systemLanguage(), { dateStyle: 'medium', timeStyle: 'medium' })
    .format(new Date());

// Locale.getDefault().displayName 偏差:原始 tag(zh-Hans-CN / en-US)
const localeTag = (): string => {
  const lang: string = systemLanguage();
  try {
    const region: string = i18n.System.getSystemRegion();
    return region.length > 0 ? `${lang}-${region}` : lang;
  } catch {
    return lang;
  }
};

const ianaTimezone = (): string => {
  try {
    const tz: string = new Intl.DateTimeFormat().resolvedOptions().timeZone;
    return tz.length > 0 ? tz : 'UTC';
  } catch {
    return 'UTC';
  }
};

const harmonySystemVersion = (): string => {
  try {
    return `HarmonyOS API v${deviceInfo.sdkApiVersion} (${deviceInfo.osFullName})`;
  } catch {
    return 'HarmonyOS';
  }
};

const harmonyDeviceInfo = (): string => {
  try {
    return `${deviceInfo.brand} ${deviceInfo.productModel}`;
  } catch {
    return '';
  }
};

const batteryCapacity = (): string => {
  try {
    // BATTERY_PROPERTY_CAPACITY → batteryInfo.batterySOC(@ohos.batteryInfo:53)
    return String(batteryInfo.batterySOC);
  } catch {
    return '-1'; // BatteryManager.getIntProperty 不可用 → -1
  }
};

// modelId/modelName = 当前聊天模型(ctx.model 等价,rt.params.model);
//   nickname 由 ChatPage 快照持有(读取时机 = transform 调用时)
export const createEntryPlaceholderValues = (
  modelId: string, modelName: string, nicknameOf: () => string,
): PlaceholderValues => ({
  curDate: mediumDate,
  curTime: mediumTime,
  curDatetime: mediumDatetime,
  modelId,
  modelName,
  localeName: localeTag,
  timezoneName: ianaTimezone,
  systemVersion: harmonySystemVersion(),
  deviceInfo: harmonyDeviceInfo(),
  batteryLevel: batteryCapacity,
  nickname: nicknameOf,
});
