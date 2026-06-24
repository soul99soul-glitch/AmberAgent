// PreferencesStorage — Storage 契约的真实实现
// @kit.ArkData preferences。对照设计 §3.8。ArkTS 兼容:class implements Storage。

import { preferences } from '@kit.ArkData';
import type { Storage } from '@amber/deepread-domain';

class PreferencesStorage implements Storage {
  private pref: preferences.Preferences | null = null;

  private async getPref(): Promise<preferences.Preferences> {
    if (this.pref !== null) {
      return this.pref;
    }
    // 从 AppStorage 取 EntryAbility 写入的 context
    const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
    this.pref = await preferences.getPreferences(ctx, 'deep_read_settings');
    return this.pref;
  }

  async get<T>(key: string, defaultValue: T): Promise<T> {
    const p: preferences.Preferences = await this.getPref();
    const v: preferences.ValueType = await p.get(key, defaultValue as preferences.ValueType);
    return v as T;
  }

  async set<T>(key: string, value: T): Promise<void> {
    const p: preferences.Preferences = await this.getPref();
    await p.put(key, value as preferences.ValueType);
    await p.flush();
  }

  async delete(key: string): Promise<void> {
    const p: preferences.Preferences = await this.getPref();
    await p.delete(key);
    await p.flush();
  }

  async getSecret(key: string): Promise<string | null> {
    const p: preferences.Preferences = await this.getPref();
    const v: preferences.ValueType = await p.get(key, '');
    if (typeof v === 'string' && v.length > 0) {
      return v;
    }
    return null;
  }

  async setSecret(key: string, value: string): Promise<void> {
    const p: preferences.Preferences = await this.getPref();
    await p.put(key, value);
    await p.flush();
  }

  async deleteSecret(key: string): Promise<void> {
    const p: preferences.Preferences = await this.getPref();
    await p.delete(key);
    await p.flush();
  }
}

export const createPreferencesStorage = (): Storage => {
  return new PreferencesStorage();
};
