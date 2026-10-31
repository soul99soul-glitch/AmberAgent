// ProviderKeyVault — 普通模型 provider 凭据的静息加密存储
//
// 与 GoogleSecureStore / WebMountSecureStore 同构:32B 随机主密钥放 Asset Store
//   (DEVICE_UNLOCKED / SyncType.NEVER),AES-GCM 逐条密文落应用私有目录原子写。
//   Preferences 内的 providers / model_configs / search_services 只保留非密配置
//   (apiKey/privateKey/password 由 chat/secret_fields_blob.ts 的 sealSecretFields
//   清空),明文凭据不再进键值库。
//
// 为什么不用 Asset SECRET 直存:Asset 单条 SECRET 上限 1024B,而 codex/grok 登录态
//   会把 JWT 写进 apiKey 字段,长度不可控;主密钥 + 密文文件既保留硬件保护又不设上限。
//
// 失败语义(fail-closed):密文在而主密钥缺失、密文损坏、GCM 认证失败一律上抛,不返回
//   空串——上游拿到空串再回存就会删掉真实凭据。只有「从未写入」才返回 null。

import { asset } from '@kit.AssetStoreKit';
import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import type { Context } from '@kit.AbilityKit';
import type { BusinessError } from '@kit.BasicServicesKit';
import type { SecretField, SecretFieldStore, SyncCryptoPort } from '@amber/chat-domain';
import { isRedactedSecretPlaceholder } from '@amber/chat-domain';
import { KeyedAsyncMutex, writeTextFileAtomic } from './AtomicJsonFile.ets';
import { createEntrySyncCryptoPort } from './SyncCryptoPort.ets';

interface ProviderSecretEntry {
  id: string;
  field: string;
  ivBase64: string;
  ciphertextBase64: string;
}

interface ProviderSecretFile {
  version: number;
  entries: ProviderSecretEntry[];
}

// 密文内认证的绑定:解密后必须与条目的明文 id/field 一致,防止把 A 的密文挪到 B 上
interface ProviderSecretPayload {
  id: string;
  field: string;
  secret: string;
}

const crypto: SyncCryptoPort = createEntrySyncCryptoPort();
const mutex: KeyedAsyncMutex = new KeyedAsyncMutex();
const VAULT_NAMESPACE: string = 'provider-keys-v1';
const MAX_SECRET_BYTES: number = 64 * 1024;
// 单条密文上限按「一个凭据 + 绑定 + GCM tag」估;整份文件装多条,故文件上限单独放宽,
// 否则 provider 一多就会把整库判成损坏而永久不可读。
// 读侧允许的最大明文载荷:密钥本体 + 绑定 JSON 的转义余量。写入与校验共用同一个上限,
// 否则「写得进、读不出」的那一小段会让整份文件被判成损坏。
const MAX_PAYLOAD_BYTES: number = MAX_SECRET_BYTES + 1024;
const MAX_ENTRY_BASE64: number = Math.ceil((MAX_PAYLOAD_BYTES + 16 + 2) / 3) * 4 + 64;
const MAX_VAULT_FILE_BYTES: number = 4 * 1024 * 1024;
const IV_BASE64: RegExp = /^[A-Za-z0-9+/]{16}$/;
const BASE64_PATTERN: RegExp = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;

const vaultPath = (): string => {
  const context: Context | undefined = AppStorage.get<Context>('abilityContext');
  if (context === undefined) throw new Error('provider_secret_context_unavailable');
  return `${context.filesDir}/amberagent/provider-keys/${VAULT_NAMESPACE}.json`;
};

const aliasQuery = (): asset.AssetMap => {
  const query: asset.AssetMap = new Map<asset.Tag, asset.Value>();
  query.set(asset.Tag.ALIAS, util.TextEncoder.create('utf-8').encodeInto(`amber-${VAULT_NAMESPACE}`));
  return query;
};

const queryKey = async (type: asset.ReturnType): Promise<asset.AssetMap | null> => {
  const query: asset.AssetMap = aliasQuery();
  query.set(asset.Tag.RETURN_TYPE, type);
  try {
    const records: asset.AssetMap[] = await asset.query(query);
    if (records.length !== 1) throw new Error('provider_secret_key_invalid_result');
    return records[0];
  } catch (error) {
    if ((error as BusinessError).code === asset.ErrorCode.NOT_FOUND) return null;
    throw error as Error;
  }
};

const keyBytes = (record: asset.AssetMap): Uint8Array => {
  const key: asset.Value | undefined = record.get(asset.Tag.SECRET);
  if (!(key instanceof Uint8Array) || key.byteLength !== 32) throw new Error('provider_secret_key_invalid');
  return key;
};

const fileExists = (path: string): boolean => {
  try {
    const stat: fileIo.Stat = fileIo.lstatSync(path);
    if (stat.isSymbolicLink() || !stat.isFile() || stat.size > MAX_VAULT_FILE_BYTES) {
      throw new Error('provider_secret_corrupt');
    }
    return true;
  } catch (error) {
    if ((error as BusinessError).code === 13900002) return false;
    throw error as Error;
  }
};

const validEntry = (entry: ProviderSecretEntry): boolean => entry !== null
  && typeof entry.id === 'string' && entry.id.length > 0 && entry.id.length <= 256
  && typeof entry.field === 'string' && entry.field.length > 0 && entry.field.length <= 64
  && typeof entry.ivBase64 === 'string' && IV_BASE64.test(entry.ivBase64)
  && typeof entry.ciphertextBase64 === 'string' && entry.ciphertextBase64.length >= 24
  && entry.ciphertextBase64.length <= MAX_ENTRY_BASE64
  && BASE64_PATTERN.test(entry.ciphertextBase64);

const loadEntries = async (): Promise<ProviderSecretEntry[]> => {
  const path: string = vaultPath();
  if (!fileExists(path)) return [];
  let parsed: ProviderSecretFile;
  try {
    parsed = JSON.parse(await fileIo.readText(path)) as ProviderSecretFile;
  } catch (_error) {
    throw new Error('provider_secret_corrupt');
  }
  if (parsed === null || parsed.version !== 1 || !Array.isArray(parsed.entries)) {
    throw new Error('provider_secret_corrupt');
  }
  const entries: ProviderSecretEntry[] = [];
  for (const entry of parsed.entries) {
    if (!validEntry(entry)) throw new Error('provider_secret_corrupt');
    entries.push(entry);
  }
  return entries;
};

const decryptEntry = async (entry: ProviderSecretEntry, key: Uint8Array): Promise<string> => {
  const ciphertext: Uint8Array = new util.Base64Helper().decodeSync(entry.ciphertextBase64);
  const plaintext: Uint8Array | null = await crypto.decrypt(ciphertext, key, entry.ivBase64);
  if (plaintext === null) throw new Error('provider_secret_corrupt');
  if (plaintext.byteLength > MAX_PAYLOAD_BYTES) {
    plaintext.fill(0);
    throw new Error('provider_secret_too_large');
  }
  let text: string;
  try {
    text = util.TextDecoder.create('utf-8', { fatal: true }).decodeToString(plaintext);
  } finally {
    plaintext.fill(0);
  }
  let payload: ProviderSecretPayload;
  try {
    payload = JSON.parse(text) as ProviderSecretPayload;
  } catch {
    throw new Error('provider_secret_corrupt');
  }
  if (payload === null || typeof payload.secret !== 'string') throw new Error('provider_secret_corrupt');
  // 密文内绑定的所有者必须与查表地址一致,否则说明密文被挪用
  if (payload.id !== entry.id || payload.field !== entry.field) {
    throw new Error('provider_secret_binding_mismatch');
  }
  return payload.secret;
};

// 条目少,整份文件原子重写比原地改更简单;归零时连主密钥一起销毁
const persistEntries = async (entries: ProviderSecretEntry[]): Promise<void> => {
  const path: string = vaultPath();
  if (entries.length === 0) {
    if (fileExists(path)) await fileIo.unlink(path);
    try {
      await asset.remove(aliasQuery());
    } catch (error) {
      if ((error as BusinessError).code !== asset.ErrorCode.NOT_FOUND) throw error as Error;
    }
    return;
  }
  if (await queryKey(asset.ReturnType.ATTRIBUTES) === null) throw new Error('provider_secret_key_missing');
  const file: ProviderSecretFile = { version: 1, entries: entries };
  await writeTextFileAtomic(path, JSON.stringify(file));
};

const ensureKey = async (): Promise<Uint8Array> => {
  const existing: asset.AssetMap | null = await queryKey(asset.ReturnType.ALL);
  if (existing !== null) return keyBytes(existing);
  const key: Uint8Array = await crypto.randomBytes(32);
  const attributes: asset.AssetMap = aliasQuery();
  attributes.set(asset.Tag.SECRET, key);
  attributes.set(asset.Tag.ACCESSIBILITY, asset.Accessibility.DEVICE_UNLOCKED);
  attributes.set(asset.Tag.SYNC_TYPE, asset.SyncType.NEVER);
  attributes.set(asset.Tag.CONFLICT_RESOLUTION, asset.ConflictResolution.THROW_ERROR);
  try {
    await asset.add(attributes);
  } catch (error) {
    key.fill(0);
    throw error as Error;
  }
  return key;
};

const indexOfEntry = (entries: ProviderSecretEntry[], id: string, field: string): number => {
  for (let i: number = 0; i < entries.length; i++) {
    if (entries[i].id === id && entries[i].field === field) return i;
  }
  return -1;
};

class EntrySecretFieldStore implements SecretFieldStore {
  read(id: string, field: SecretField): Promise<string | null> {
    return mutex.withLock<string | null>(VAULT_NAMESPACE, async (): Promise<string | null> => {
      const entries: ProviderSecretEntry[] = await loadEntries();
      const index: number = indexOfEntry(entries, id, field);
      if (index < 0) return null;
      const record: asset.AssetMap | null = await queryKey(asset.ReturnType.ALL);
      if (record === null) throw new Error('provider_secret_key_missing');
      const key: Uint8Array = keyBytes(record);
      try {
        return await decryptEntry(entries[index], key);
      } finally {
        key.fill(0);
      }
    });
  }

  write(id: string, field: SecretField, value: string): Promise<void> {
    if (isRedactedSecretPlaceholder(value)) {
      return Promise.resolve();
    }
    const plaintext: Uint8Array = util.TextEncoder.create('utf-8').encodeInto(value);
    return mutex.withLock<void>(VAULT_NAMESPACE, async (): Promise<void> => {
      try {
        if (typeof id !== 'string' || id.length === 0 || id.length > 256
          || typeof field !== 'string' || field.length === 0 || field.length > 64
          || plaintext.byteLength === 0 || plaintext.byteLength > MAX_SECRET_BYTES) {
          throw new Error('provider_secret_invalid_size');
        }
        const key: Uint8Array = await ensureKey();
        try {
          const iv: Uint8Array = await crypto.randomBytes(12);
          const ivBase64: string = new util.Base64Helper().encodeToStringSync(iv);
          // 密文内绑定 {id, field, secret}:解密侧据此拒绝被挪用的密文
          const payload: ProviderSecretPayload = { id: id, field: field, secret: value };
          const bound: Uint8Array = util.TextEncoder.create('utf-8').encodeInto(JSON.stringify(payload));
          let ciphertext: Uint8Array;
          try {
            // 转义膨胀(引号/反斜杠/换行)会让载荷超过读取侧的判定上限;
            // 一旦某条越界,整份文件都会被判为 provider_secret_corrupt,所以写入前先挡住。
            if (bound.byteLength > MAX_PAYLOAD_BYTES) throw new Error('provider_secret_invalid_size');
            ciphertext = await crypto.encrypt(bound, key, ivBase64);
          } finally {
            bound.fill(0);
          }
          const entry: ProviderSecretEntry = {
            id: id,
            field: field,
            ivBase64: ivBase64,
            ciphertextBase64: new util.Base64Helper().encodeToStringSync(ciphertext),
          };
          const entries: ProviderSecretEntry[] = await loadEntries();
          const index: number = indexOfEntry(entries, id, field);
          if (index >= 0) {
            entries[index] = entry;
          } else {
            entries.push(entry);
          }
          await persistEntries(entries);
        } finally {
          key.fill(0);
        }
      } finally {
        plaintext.fill(0);
      }
    });
  }

  remove(id: string, field: SecretField): Promise<void> {
    return mutex.withLock<void>(VAULT_NAMESPACE, async (): Promise<void> => {
      const entries: ProviderSecretEntry[] = await loadEntries();
      const index: number = indexOfEntry(entries, id, field);
      if (index < 0) return;
      entries.splice(index, 1);
      await persistEntries(entries);
    });
  }

  /**
   * 丢弃整库:密文文件与主密钥一起删掉。只在主密钥不可恢复时调用——此后所有 read
   * 返回 null(字段回填为空),而不是每页都抛错。幂等,不因文件缺失/损坏而失败。
   */
  discardAll(): Promise<void> {
    return mutex.withLock<void>(VAULT_NAMESPACE, async (): Promise<void> => {
      const path: string = vaultPath();
      try {
        if (fileExists(path)) await fileIo.unlink(path);
      } catch (error) {
        // 尺寸越界等「已损坏」情况:直接删文件,不把它当成失败
        try {
          await fileIo.unlink(path);
        } catch (_ignored) {
        }
      }
      try {
        await asset.remove(aliasQuery());
      } catch (error) {
        if ((error as BusinessError).code !== asset.ErrorCode.NOT_FOUND) throw error as Error;
      }
    });
  }

  listOwnerKeys(): Promise<string[]> {
    return mutex.withLock<string[]>(VAULT_NAMESPACE, async (): Promise<string[]> => {
      const entries: ProviderSecretEntry[] = await loadEntries();
      const ids: string[] = [];
      for (const entry of entries) {
        if (ids.indexOf(entry.id) < 0) ids.push(entry.id);
      }
      return ids;
    });
  }
}

export const createEntrySecretFieldStore = (): SecretFieldStore => new EntrySecretFieldStore();
