// PreferencesStorage — Storage 契约的真实实现
// @kit.ArkData preferences。对照设计 §3.8。ArkTS 兼容:class implements Storage。
//
// 静息凭据边界:模型与检索服务的 API Key 不进 Preferences。
//   - 结构型 blob(providers / model_configs / search_services / mcp_servers):经
//     secret_fields_blob 剥离/回填 apiKey|privateKey|password / customHeaders / MCP headers;
//   - 标量键(*_api_key,含 ai_api_key 与 tavily/exa/bocha/brave 等检索服务键):
//     经 sealScalarSecret/openScalarSecret 整体加密。
//   真值由 ProviderKeyVault(Asset 主密钥 + AES-GCM 密文)保管;读侧顺带完成旧明文
//   数据的一次性迁移。其余键(非密配置)原样读写。

import { preferences } from '@kit.ArkData';
import type { Storage } from '@amber/deepread-domain';
import type { SecretFieldStore } from '@amber/chat-domain';
import { KeyedAsyncMutex } from './AtomicJsonFile.ets';
import {
  sealSecretFields, migrateSecretFields, openSecretFields, pruneNamespace, blobHasInlineSecrets,
  stripSecretFields, sealScalarSecret, openScalarSecret, SECRET_RECOVERY_NOTICE_KEY,
} from '@amber/chat-domain';

// ArkTS 不允许对象字面量类型;Storage.getAllStringEntries 返回元素的具名形态
interface StringKVEntry {
  key: string;
  value: string;
}

// Preferences 键 → 凭据命名空间。未登记 = 非密 blob,原样读写。
interface SealedBlob {
  prefKey: string;
  namespace: string;
}

const SEALED_BLOBS: SealedBlob[] = [
  { prefKey: 'providers', namespace: 'providers' },
  { prefKey: 'model_configs', namespace: 'models' },
  // 聊天侧检索服务目录:每项带 apiKey / searxng password
  { prefKey: 'search_services', namespace: 'search' },
  // MCP 服务器:commonOptions.headers 是 [name, value] 对,value 常带 Authorization
  { prefKey: 'mcp_servers', namespace: 'mcp' },
  // 助手配置:customHeaders[].value 常带鉴权头
  { prefKey: 'assistants', namespace: 'assistants' },
];

// 读写同一 Preferences 键必须串行:get 的迁移回写若与 set 交错,vault 会留下旧值
const prefMutex: KeyedAsyncMutex = new KeyedAsyncMutex();

const namespaceOf = (key: string): string | null => {
  for (const sealed of SEALED_BLOBS) {
    if (sealed.prefKey === key) return sealed.namespace;
  }
  return null;
};

// 标量凭据键:整段字符串即密钥。命名沿用既有 ai_api_key / <source>_api_key 约定
// 及 oauth_tokens/* (Codex/Grok OAuth 令牌 JSON)。
const SCALAR_SECRET_SUFFIX: string = '_api_key';
const OAUTH_TOKEN_PREFIX: string = 'oauth_tokens/';

const isScalarSecret = (key: string): boolean =>
  (key.length > SCALAR_SECRET_SUFFIX.length && key.endsWith(SCALAR_SECRET_SUFFIX))
  || (key.length > OAUTH_TOKEN_PREFIX.length && key.startsWith(OAUTH_TOKEN_PREFIX));

const scalarOwnerKeyOf = (key: string): string => `scalar/${key}`;

// 这些错误说明密文已不可解(主密钥被清、密文损坏、密文被挪用),重试永远不会成功。
// 其它错误(如 Asset 服务暂时不可用、单条写入尺寸超限)必须原样上抛,不能当成本地密钥丢失去清库。
const UNRECOVERABLE_SECRET_ERRORS: string[] = [
  'provider_secret_key_missing',
  'provider_secret_corrupt',
  'provider_secret_binding_mismatch',
  'provider_secret_too_large',
];

// 不用 instanceof:密文库实现与调用可能来自不同 realm(单测 vm / 原生侧),跨 realm
// 的 Error 恒不等于本 realm 的 Error,会把真实失败漏判成「未知错误」。
const isUnrecoverableSecretError = (error: Error): boolean => {
  const message: string = error.message;
  return typeof message === 'string' && UNRECOVERABLE_SECRET_ERRORS.indexOf(message) >= 0;
};

class PreferencesStorage implements Storage {
  private pref: preferences.Preferences | null = null;
  private secrets: SecretFieldStore;

  constructor(secrets: SecretFieldStore) {
    this.secrets = secrets;
  }

  private async getPref(): Promise<preferences.Preferences> {
    if (this.pref !== null) {
      return this.pref;
    }
    // 从 AppStorage 取 EntryAbility 写入的 context
    const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
    this.pref = await preferences.getPreferences(ctx, 'deep_read_settings');
    return this.pref;
  }

  // 迁移回写:只有 Preferences 里的当前值仍是我们读到的那个才写,避免把并发 set 覆盖掉
  private async writeBackIfUnchanged(
    p: preferences.Preferences, key: string, expected: string, value: string,
  ): Promise<void> {
    if (value === expected) return;
    const current: preferences.ValueType = await p.get(key, '');
    if (typeof current !== 'string' || current !== expected) return;
    await p.put(key, value);
    await p.flush();
  }

  async get<T>(key: string, defaultValue: T): Promise<T> {
    return prefMutex.withLock<T>(`pref:${key}`, async (): Promise<T> => this.read(key, defaultValue));
  }

  private async read<T>(key: string, defaultValue: T): Promise<T> {
    const p: preferences.Preferences = await this.getPref();
    const raw: preferences.ValueType = await p.get(key, defaultValue as preferences.ValueType);
    if (typeof raw !== 'string') return raw as T;
    if (!isScalarSecret(key) && namespaceOf(key) === null) return raw as T;
    try {
      return await this.openValue<T>(p, key, raw);
    } catch (error) {
      if (!isUnrecoverableSecretError(error as Error)) throw error as Error;
      return await this.recoverUnreadableSecret<T>(p, key, raw);
    }
  }

  /** 读路径:先迁移仍为明文的字段,再回填。迁移只写不删(见 migrateSecretFields)。 */
  private async openValue<T>(p: preferences.Preferences, key: string, raw: string): Promise<T> {
    if (isScalarSecret(key)) {
      const ownerKey: string = scalarOwnerKeyOf(key);
      // 只有仍是明文的标量才迁移。空串代表「已迁移」或「从未设置」,此时绝不能调
      // sealScalarSecret —— 空值在写路径上表示用户清空,会删掉 vault 里的真值。
      if (raw.length > 0) {
        const sealed: string = await sealScalarSecret(raw, this.secrets, ownerKey, 'apiKey');
        await this.writeBackIfUnchanged(p, key, raw, sealed);
      }
      return await openScalarSecret(raw, this.secrets, ownerKey, 'apiKey') as T;
    }
    const namespace: string | null = namespaceOf(key);
    if (namespace === null) return raw as T;
    // 旧版本把明文凭据写在 blob 里:先收进 vault 再回写无密版本。
    // 已迁移的 blob 不含明文,跳过这一步;回填仍要为每个空字段读一次 vault。
    const migrated: string = blobHasInlineSecrets(raw)
      ? await migrateSecretFields(raw, this.secrets, namespace) : raw;
    await this.writeBackIfUnchanged(p, key, raw, migrated);
    return await openSecretFields(migrated, this.secrets, namespace) as T;
  }

  /**
   * 主密钥不可恢复或安全仓损坏时的恢复路径:先丢弃旧安全仓,若内存中的 raw 仍带有未迁移明文,
   * 直接把明文重新迁入干净的安全仓(不丢失完好明文);仅当凭据原本已迁入安全仓且无法解密时,
   * 才把密文字段清空、保留非密字段并落盘,同时留下可见标记提示用户重新填写。
   */
  private async recoverUnreadableSecret<T>(p: preferences.Preferences, key: string, raw: string): Promise<T> {
    await this.secrets.discardAll();
    if (isScalarSecret(key)) {
      if (raw.length > 0) {
        const ownerKey: string = scalarOwnerKeyOf(key);
        const sealed: string = await sealScalarSecret(raw, this.secrets, ownerKey, 'apiKey');
        await p.put(key, sealed);
        await p.flush();
        return await openScalarSecret(sealed, this.secrets, ownerKey, 'apiKey') as T;
      }
      await p.put(key, '');
      await p.put(SECRET_RECOVERY_NOTICE_KEY, '1');
      await p.flush();
      return '' as T;
    }
    const namespace: string | null = namespaceOf(key);
    if (namespace !== null && blobHasInlineSecrets(raw)) {
      const migrated: string = await migrateSecretFields(raw, this.secrets, namespace);
      await p.put(key, migrated);
      await p.flush();
      return await openSecretFields(migrated, this.secrets, namespace) as T;
    }
    const stripped: string = stripSecretFields(raw);
    await p.put(key, stripped);
    await p.put(SECRET_RECOVERY_NOTICE_KEY, '1');
    await p.flush();
    return stripped as T;
  }

  async set<T>(key: string, value: T): Promise<void> {
    await prefMutex.withLock<void>(`pref:${key}`, async (): Promise<void> => this.write(key, value));
  }

  private async write<T>(key: string, value: T): Promise<void> {
    const p: preferences.Preferences = await this.getPref();
    if (typeof value === 'string' && isScalarSecret(key)) {
      await p.put(key, await sealScalarSecret(value, this.secrets, scalarOwnerKeyOf(key), 'apiKey'));
    } else {
      const namespace: string | null = namespaceOf(key);
      if (namespace !== null && typeof value === 'string') {
        await p.put(key, await sealSecretFields(value, this.secrets, namespace));
      } else {
        await p.put(key, value as preferences.ValueType);
      }
    }
    await p.flush();
  }

  async delete(key: string): Promise<void> {
    await prefMutex.withLock<void>(`pref:${key}`, async (): Promise<void> => this.remove(key));
  }

  private async remove(key: string): Promise<void> {
    const p: preferences.Preferences = await this.getPref();
    await p.delete(key);
    await p.flush();
    if (isScalarSecret(key)) {
      await this.secrets.remove(scalarOwnerKeyOf(key), 'apiKey');
      return;
    }
    const namespace: string | null = namespaceOf(key);
    if (namespace === null) return;
    await pruneNamespace(this.secrets, namespace);
  }

  async getAllStringEntries(): Promise<StringKVEntry[]> {
    const p: preferences.Preferences = await this.getPref();
    const all: preferences.ValueType = await p.getAll();
    const rec: Record<string, preferences.ValueType> = all as Record<string, preferences.ValueType>;
    const out: StringKVEntry[] = [];
    for (const key of Object.keys(rec)) {
      const v: preferences.ValueType = rec[key];
      if (typeof v === 'string') {
        out.push({ key: key, value: v });
      }
    }
    return out;
  }
}

export const createPreferencesStorage = (secrets: SecretFieldStore): Storage =>
  new PreferencesStorage(secrets);
