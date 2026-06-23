// PreferencesStorage — Storage 契约的真实实现
// @kit.ArkData preferences
// 对照设计 §3.8(Preferences + Asset Store)
// 存:settings(model/api keys/playbook flags)等键值。
//
// TODO(toolchain/ArkTS 验证): preferences.getPreferences / put / flush 签名以 API 24 为准。

import { preferences } from '@kit.ArkData';
import type { Storage } from '@amber/deepread-domain';

const PREF_NAME = 'deep_read_settings';

export const createPreferencesStorage = (): Storage => {
  let pref: preferences.Preferences | null = null;

  const init = async (): Promise<preferences.Preferences> => {
    if (pref !== null) return pref;
    const context = (globalThis as Record<string, object>).__abilityContext as unknown as Context;
    pref = await preferences.getPreferences(context, PREF_NAME);
    return pref;
  };

  const getString = async (key: string): Promise<string | null> => {
    const p = await init();
    const v = await p.get(key, '');
    return typeof v === 'string' && v.length > 0 ? v : null;
  };

  const setString = async (key: string, value: string): Promise<void> => {
    const p = await init();
    await p.put(key, value);
    await p.flush();
  };

  return { getString, setString };
};
