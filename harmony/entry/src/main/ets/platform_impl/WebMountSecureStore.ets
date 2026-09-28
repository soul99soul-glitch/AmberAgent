// WebMountSecureStore — 站点 OAuth client secret / token 的安全引用存储(E12)
//
// 与 GoogleSecureStore 同构:每命名空间一把 Asset 内随机 32B key(DEVICE_UNLOCKED/
//   NEVER 同步),AES-GCM 密文落私有目录原子写;KV 只存配置与引用。
// 命名空间:secret 按引用寻址(record 内认证 siteId+reference);
//   token 按 siteId+bindingHash 寻址,binding 变化即读不到旧 token。
// 这不是飞书 Preferences token 的迁移,也不重构既有 provider 存储。

import { asset } from '@kit.AssetStoreKit';
import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import type { Context } from '@kit.AbilityKit';
import type { BusinessError } from '@kit.BasicServicesKit';
import type { WebMountOAuthToken, SyncCryptoPort } from '@amber/chat-domain';
import { KeyedAsyncMutex, writeTextFileAtomic } from './AtomicJsonFile.ets';
import { createEntrySyncCryptoPort } from './SyncCryptoPort.ets';

interface WebMountCipherEnvelope { version: number; namespace: string; ivBase64: string; ciphertextBase64: string; }
interface WebMountSecretRecord { version: number; siteId: string; reference: string; secret: string; }
interface WebMountTokenRecord { version: number; siteId: string; bindingHash: string; token: WebMountOAuthToken; }

const crypto: SyncCryptoPort = createEntrySyncCryptoPort();
const mutex: KeyedAsyncMutex = new KeyedAsyncMutex();
const MAX_RECORD_BYTES: number = 64 * 1024;
const MAX_ENVELOPE_BYTES: number = Math.ceil((MAX_RECORD_BYTES + 16) / 3) * 4 + 4096;
const SITE_ID_PATTERN: RegExp = /^[a-z0-9][a-z0-9_-]{0,39}$/;
const REFERENCE_PATTERN: RegExp = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const BINDING_PATTERN: RegExp = /^[A-Za-z0-9_-]{16,128}$/;

const requireSiteId = (siteId: string): void => {
  if (!SITE_ID_PATTERN.test(siteId)) throw new Error('webmount_secret_invalid_site');
};
const requireReference = (reference: string): void => {
  if (!REFERENCE_PATTERN.test(reference)) throw new Error('webmount_secret_invalid_reference');
};
const requireBinding = (bindingHash: string): void => {
  if (!BINDING_PATTERN.test(bindingHash)) throw new Error('webmount_secret_invalid_binding');
};
const secretNamespace = (siteId: string, reference: string): string => {
  requireSiteId(siteId); requireReference(reference);
  return `webmount-secret-${reference}`;
};
const tokenNamespace = (siteId: string, bindingHash: string): string => {
  requireSiteId(siteId); requireBinding(bindingHash);
  return `webmount-tokens-${siteId}-${bindingHash}`;
};
const pathFor = (namespace: string): string => {
  const context: Context | undefined = AppStorage.get<Context>('abilityContext');
  if (context === undefined) throw new Error('webmount_secret_context_unavailable');
  return `${context.filesDir}/amberagent/webmount-oauth/${namespace}.json`;
};
const aliasQuery = (namespace: string): asset.AssetMap => {
  const query: asset.AssetMap = new Map<asset.Tag, asset.Value>();
  query.set(asset.Tag.ALIAS, util.TextEncoder.create('utf-8').encodeInto(`amber-webmount-${namespace}`));
  return query;
};
const queryKey = async (namespace: string, type: asset.ReturnType): Promise<asset.AssetMap | null> => {
  const query: asset.AssetMap = aliasQuery(namespace);
  query.set(asset.Tag.RETURN_TYPE, type);
  try {
    const records: asset.AssetMap[] = await asset.query(query);
    if (records.length !== 1) throw new Error('webmount_secret_key_invalid_result');
    return records[0];
  } catch (error) {
    if ((error as BusinessError).code === asset.ErrorCode.NOT_FOUND) return null;
    throw error as Error;
  }
};
const fileExists = (path: string): boolean => {
  try {
    const stat: fileIo.Stat = fileIo.lstatSync(path);
    if (stat.isSymbolicLink() || !stat.isFile() || stat.size > MAX_ENVELOPE_BYTES) {
      throw new Error('webmount_secret_corrupt');
    }
    return true;
  } catch (error) {
    if ((error as BusinessError).code === 13900002) return false;
    throw error as Error;
  }
};
const keyBytes = (record: asset.AssetMap): Uint8Array => {
  const key: asset.Value | undefined = record.get(asset.Tag.SECRET);
  if (!(key instanceof Uint8Array) || key.byteLength !== 32) throw new Error('webmount_secret_key_invalid');
  return key;
};
const decodeEnvelope = (text: string, namespace: string): WebMountCipherEnvelope => {
  try {
    const value: WebMountCipherEnvelope = JSON.parse(text) as WebMountCipherEnvelope;
    if (value === null || value.version !== 1 || value.namespace !== namespace
      || typeof value.ivBase64 !== 'string' || !/^[A-Za-z0-9+/]{16}$/.test(value.ivBase64)
      || typeof value.ciphertextBase64 !== 'string' || value.ciphertextBase64.length < 24
      || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value.ciphertextBase64)) {
      throw new Error('webmount_secret_corrupt');
    }
    return value;
  } catch (_error) { throw new Error('webmount_secret_corrupt'); }
};
const readEncrypted = async (namespace: string): Promise<string | null> => {
  const path: string = pathFor(namespace);
  if (!fileExists(path)) return null;
  const envelope: WebMountCipherEnvelope = decodeEnvelope(await fileIo.readText(path), namespace);
  const record: asset.AssetMap | null = await queryKey(namespace, asset.ReturnType.ALL);
  if (record === null) throw new Error('webmount_secret_key_missing');
  const key: Uint8Array = keyBytes(record);
  let plaintext: Uint8Array | null = null;
  try {
    const ciphertext: Uint8Array = new util.Base64Helper().decodeSync(envelope.ciphertextBase64);
    plaintext = await crypto.decrypt(ciphertext, key, envelope.ivBase64);
    if (plaintext === null || plaintext.byteLength > MAX_RECORD_BYTES) throw new Error('webmount_secret_corrupt');
    return util.TextDecoder.create('utf-8', { fatal: true }).decodeToString(plaintext);
  } finally { plaintext?.fill(0); key.fill(0); }
};
const removeEncrypted = async (namespace: string): Promise<void> => {
  const path: string = pathFor(namespace);
  if (fileExists(path)) await fileIo.unlink(path);
  try { await asset.remove(aliasQuery(namespace)); }
  catch (error) {
    if ((error as BusinessError).code !== asset.ErrorCode.NOT_FOUND) throw error as Error;
  }
};
const writeEncrypted = async (namespace: string, text: string, createOnly: boolean): Promise<void> => {
  const path: string = pathFor(namespace);
  const present: boolean = fileExists(path);
  const existing: asset.AssetMap | null = await queryKey(namespace,
    createOnly ? asset.ReturnType.ATTRIBUTES : asset.ReturnType.ALL);
  if (createOnly && (present || existing !== null)) throw new Error('webmount_secret_reference_exists');
  if (present && existing === null) throw new Error('webmount_secret_key_missing');
  const plaintext: Uint8Array = util.TextEncoder.create('utf-8').encodeInto(text);
  let key: Uint8Array | null = null;
  let added: boolean = false;
  try {
    if (plaintext.byteLength > MAX_RECORD_BYTES) throw new Error('webmount_secret_too_large');
    key = existing === null ? await crypto.randomBytes(32) : keyBytes(existing);
    const iv: Uint8Array = await crypto.randomBytes(12);
    const ivBase64: string = new util.Base64Helper().encodeToStringSync(iv);
    const ciphertext: Uint8Array = await crypto.encrypt(plaintext, key, ivBase64);
    if (existing === null) {
      const attributes: asset.AssetMap = aliasQuery(namespace);
      attributes.set(asset.Tag.SECRET, key);
      attributes.set(asset.Tag.ACCESSIBILITY, asset.Accessibility.DEVICE_UNLOCKED);
      attributes.set(asset.Tag.SYNC_TYPE, asset.SyncType.NEVER);
      attributes.set(asset.Tag.CONFLICT_RESOLUTION, asset.ConflictResolution.THROW_ERROR);
      await asset.add(attributes); added = true;
    }
    const envelope: WebMountCipherEnvelope = { version: 1, namespace: namespace, ivBase64: ivBase64,
      ciphertextBase64: new util.Base64Helper().encodeToStringSync(ciphertext) };
    await writeTextFileAtomic(path, JSON.stringify(envelope));
  } catch (error) {
    // 仅回收本次失败写入新建的 key;既有密文不受影响
    if (added) {
      try { await asset.remove(aliasQuery(namespace)); }
      catch (_cleanupError) { throw new Error('webmount_secret_stage_cleanup_failed'); }
    }
    throw error as Error;
  } finally { plaintext.fill(0); key?.fill(0); }
};

export const stageWebMountSecret = async (siteId: string, secret: string): Promise<string> => {
  requireSiteId(siteId);
  const bytes: Uint8Array = util.TextEncoder.create('utf-8').encodeInto(secret);
  const length: number = bytes.byteLength; bytes.fill(0);
  if (length === 0 || length > 4096) throw new Error('webmount_secret_invalid_size');
  const reference: string = util.generateRandomUUID(false);
  const namespace: string = secretNamespace(siteId, reference);
  const record: WebMountSecretRecord = { version: 1, siteId: siteId, reference: reference, secret: secret };
  await mutex.withLock<void>(namespace, (): Promise<void> => writeEncrypted(namespace, JSON.stringify(record), true));
  return reference;
};

export const loadWebMountSecret = async (siteId: string, reference: string): Promise<string | null> => {
  const namespace: string = secretNamespace(siteId, reference);
  return mutex.withLock<string | null>(namespace, async (): Promise<string | null> => {
    const text: string | null = await readEncrypted(namespace);
    if (text === null) return null;
    let record: WebMountSecretRecord;
    try {
      record = JSON.parse(text) as WebMountSecretRecord;
      if (record === null || record.version !== 1 || typeof record.secret !== 'string') throw new Error('x');
    } catch (_error) { throw new Error('webmount_secret_corrupt'); }
    if (record.siteId !== siteId || record.reference !== reference) throw new Error('webmount_secret_binding_mismatch');
    return record.secret;
  });
};

export const deleteWebMountSecret = async (siteId: string, reference: string): Promise<void> => {
  const namespace: string = secretNamespace(siteId, reference);
  await mutex.withLock<void>(namespace, async (): Promise<void> => {
    if (await readEncrypted(namespace) === null) return;
    await removeEncrypted(namespace);
  });
};

const validToken = (token: WebMountOAuthToken): boolean => token !== null
  && typeof token.accessToken === 'string' && token.accessToken.length > 0
  && (token.refreshToken === null || (typeof token.refreshToken === 'string' && token.refreshToken.length > 0))
  && Number.isFinite(token.expiresAtMillis)
  && typeof token.tokenType === 'string' && token.tokenType.length > 0;

export const readWebMountOAuthToken = async (
  siteId: string, bindingHash: string,
): Promise<WebMountOAuthToken | null> => {
  const namespace: string = tokenNamespace(siteId, bindingHash);
  return mutex.withLock<WebMountOAuthToken | null>(namespace, async (): Promise<WebMountOAuthToken | null> => {
    const text: string | null = await readEncrypted(namespace);
    if (text === null) return null;
    let record: WebMountTokenRecord;
    try {
      record = JSON.parse(text) as WebMountTokenRecord;
      if (record === null || record.version !== 1 || !validToken(record.token)) throw new Error('x');
    } catch (_error) { throw new Error('webmount_secret_corrupt'); }
    if (record.siteId !== siteId || record.bindingHash !== bindingHash) return null;
    return record.token;
  });
};

export const writeWebMountOAuthToken = async (
  siteId: string, bindingHash: string, token: WebMountOAuthToken,
): Promise<void> => {
  const namespace: string = tokenNamespace(siteId, bindingHash);
  if (!validToken(token)) throw new Error('webmount_oauth_invalid_token');
  const record: WebMountTokenRecord = { version: 1, siteId: siteId, bindingHash: bindingHash, token: token };
  const text: string = JSON.stringify(record);
  await mutex.withLock<void>(namespace, (): Promise<void> => writeEncrypted(namespace, text, false));
};

export const removeWebMountOAuthToken = async (siteId: string, bindingHash: string): Promise<void> => {
  const namespace: string = tokenNamespace(siteId, bindingHash);
  await mutex.withLock<void>(namespace, async (): Promise<void> => {
    if (await readEncrypted(namespace) === null) return;
    await removeEncrypted(namespace);
  });
};
