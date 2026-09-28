import { asset } from '@kit.AssetStoreKit';
import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import type { Context } from '@kit.AbilityKit';
import type { BusinessError } from '@kit.BasicServicesKit';
import type { GoogleOAuthBinding, GoogleOAuthMode, GoogleOAuthTokens, SyncCryptoPort } from '@amber/chat-domain';
import { KeyedAsyncMutex, writeTextFileAtomic } from './AtomicJsonFile.ets';
import { createEntrySyncCryptoPort } from './SyncCryptoPort.ets';

export type GoogleSecretKind = 'service_account_private_key' | 'oauth_client_secret';

interface GoogleCipherEnvelope { version: number; namespace: string; ivBase64: string; ciphertextBase64: string; }
interface GoogleSecretRecord {
  version: number; reference: string; providerId: string; kind: GoogleSecretKind; secret: string;
}
interface GoogleTokenRecord { version: number; binding: GoogleOAuthBinding; tokens: GoogleOAuthTokens; }

const crypto: SyncCryptoPort = createEntrySyncCryptoPort();
const mutex: KeyedAsyncMutex = new KeyedAsyncMutex();
const MAX_SECRET_BYTES: number = 64 * 1024;
const MAX_RECORD_BYTES: number = 128 * 1024;
const MAX_ENVELOPE_BYTES: number = Math.ceil((MAX_RECORD_BYTES + 16) / 3) * 4 + 4096;
const secretByteLength = (secret: string): number => {
  const bytes: Uint8Array = util.TextEncoder.create('utf-8').encodeInto(secret);
  const length: number = bytes.byteLength; bytes.fill(0); return length;
};

const requireUUID = (value: string): void => {
  const match: RegExpMatchArray | null = value.match(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i);
  if (match === null || match[0] !== value) throw new Error('google_secret_invalid_id');
};
const requireKind = (kind: GoogleSecretKind): void => {
  if (kind !== 'service_account_private_key' && kind !== 'oauth_client_secret') {
    throw new Error('google_secret_invalid_kind');
  }
};
const secretNamespace = (providerId: string, kind: GoogleSecretKind, reference: string): string => {
  requireUUID(providerId); requireUUID(reference); requireKind(kind);
  // The reference is the lookup address; the owner and kind are authenticated inside the file.
  return `secret-${reference}`;
};
const tokenNamespace = (binding: GoogleOAuthBinding): string => {
  requireUUID(binding.providerId);
  if (binding.mode !== 'gemini_code_assist_oauth' && binding.mode !== 'antigravity_oauth') {
    throw new Error('google_oauth_invalid_mode');
  }
  if (binding.clientId.length === 0 || binding.redirectUri.length === 0) {
    throw new Error('google_oauth_invalid_binding');
  }
  if (binding.clientSecretRef.length > 0) requireUUID(binding.clientSecretRef);
  return `tokens-${binding.providerId}-${binding.mode}`;
};
const copyBinding = (binding: GoogleOAuthBinding): GoogleOAuthBinding => ({ providerId: binding.providerId,
  mode: binding.mode, clientId: binding.clientId, redirectUri: binding.redirectUri,
  clientSecretRef: binding.clientSecretRef });
const pathFor = (namespace: string): string => {
  const context: Context | undefined = AppStorage.get<Context>('abilityContext');
  if (context === undefined) throw new Error('google_secret_context_unavailable');
  return `${context.filesDir}/amberagent/google-auth/${namespace}.json`;
};
const aliasQuery = (namespace: string): asset.AssetMap => {
  const query: asset.AssetMap = new Map<asset.Tag, asset.Value>();
  query.set(asset.Tag.ALIAS, util.TextEncoder.create('utf-8').encodeInto(`amber-google-${namespace}`));
  return query;
};
const queryKey = async (namespace: string, type: asset.ReturnType): Promise<asset.AssetMap | null> => {
  const query: asset.AssetMap = aliasQuery(namespace);
  query.set(asset.Tag.RETURN_TYPE, type);
  try {
    const records: asset.AssetMap[] = await asset.query(query);
    if (records.length !== 1) throw new Error('google_secret_key_invalid_result');
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
      throw new Error('google_secret_corrupt');
    }
    return true;
  } catch (error) {
    if ((error as BusinessError).code === 13900002) return false;
    throw error as Error;
  }
};
const keyBytes = (record: asset.AssetMap): Uint8Array => {
  const key: asset.Value | undefined = record.get(asset.Tag.SECRET);
  if (!(key instanceof Uint8Array) || key.byteLength !== 32) throw new Error('google_secret_key_invalid');
  return key;
};
const decodeEnvelope = (text: string, namespace: string): GoogleCipherEnvelope => {
  try {
    const value: GoogleCipherEnvelope = JSON.parse(text) as GoogleCipherEnvelope;
    if (value === null || value.version !== 1 || value.namespace !== namespace ||
      typeof value.ivBase64 !== 'string' || !/^[A-Za-z0-9+/]{16}$/.test(value.ivBase64) ||
      value.ivBase64.length !== 16 || typeof value.ciphertextBase64 !== 'string' ||
      value.ciphertextBase64.length < 24 ||
      !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value.ciphertextBase64)) {
      throw new Error('google_secret_corrupt');
    }
    return value;
  } catch (_error) { throw new Error('google_secret_corrupt'); }
};
const readEncrypted = async (namespace: string): Promise<string | null> => {
  const path: string = pathFor(namespace);
  if (!fileExists(path)) return null;
  const envelope: GoogleCipherEnvelope = decodeEnvelope(await fileIo.readText(path), namespace);
  const record: asset.AssetMap | null = await queryKey(namespace, asset.ReturnType.ALL);
  if (record === null) throw new Error('google_secret_key_missing');
  const key: Uint8Array = keyBytes(record);
  let plaintext: Uint8Array | null = null;
  try {
    const ciphertext: Uint8Array = new util.Base64Helper().decodeSync(envelope.ciphertextBase64);
    plaintext = await crypto.decrypt(ciphertext, key, envelope.ivBase64);
    if (plaintext === null || plaintext.byteLength > MAX_RECORD_BYTES) throw new Error('google_secret_corrupt');
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
  if (createOnly && (present || existing !== null)) throw new Error('google_secret_reference_exists');
  if (present && existing === null) throw new Error('google_secret_key_missing');
  const plaintext: Uint8Array = util.TextEncoder.create('utf-8').encodeInto(text);
  let key: Uint8Array | null = null;
  let added: boolean = false;
  try {
    if (plaintext.byteLength > MAX_RECORD_BYTES) throw new Error('google_secret_too_large');
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
    const envelope: GoogleCipherEnvelope = { version: 1, namespace: namespace, ivBase64: ivBase64,
      ciphertextBase64: new util.Base64Helper().encodeToStringSync(ciphertext) };
    await writeTextFileAtomic(path, JSON.stringify(envelope));
  } catch (error) {
    // Only the key created by this unsuccessful write is reclaimed. Existing token ciphertext stays intact.
    if (added) {
      try { await asset.remove(aliasQuery(namespace)); }
      catch (_cleanupError) { throw new Error('google_secret_stage_cleanup_failed'); }
    }
    throw error as Error;
  } finally { plaintext.fill(0); key?.fill(0); }
};
const decodeSecret = (text: string, providerId: string, kind: GoogleSecretKind, reference: string): string => {
  let record: GoogleSecretRecord;
  try {
    record = JSON.parse(text) as GoogleSecretRecord;
    if (record === null || record.version !== 1 || typeof record.secret !== 'string' ||
      record.secret.length === 0 || typeof record.providerId !== 'string' || typeof record.kind !== 'string' ||
      typeof record.reference !== 'string' ||
      secretByteLength(record.secret) > MAX_SECRET_BYTES) {
      throw new Error('google_secret_corrupt');
    }
  } catch (_error) { throw new Error('google_secret_corrupt'); }
  if (record.providerId !== providerId || record.kind !== kind || record.reference !== reference) {
    throw new Error('google_secret_binding_mismatch');
  }
  return record.secret;
};
const sameBinding = (left: GoogleOAuthBinding, right: GoogleOAuthBinding): boolean =>
  left.providerId === right.providerId && left.mode === right.mode && left.clientId === right.clientId &&
    left.redirectUri === right.redirectUri && left.clientSecretRef === right.clientSecretRef;
const validTokens = (tokens: GoogleOAuthTokens): boolean => tokens !== null &&
  typeof tokens.accessToken === 'string' && tokens.accessToken.length > 0 &&
  (tokens.refreshToken === null || (typeof tokens.refreshToken === 'string' && tokens.refreshToken.length > 0)) &&
  Number.isFinite(tokens.expiresAt) && tokens.expiresAt > 0 &&
  (tokens.projectId === null || (typeof tokens.projectId === 'string' && tokens.projectId.length > 0)) &&
  (tokens.tierId === null || (typeof tokens.tierId === 'string' && tokens.tierId.length > 0));
const decodeTokens = (text: string): GoogleTokenRecord => {
  try {
    const record: GoogleTokenRecord = JSON.parse(text) as GoogleTokenRecord;
    if (record === null || record.version !== 1 || record.binding === null ||
      typeof record.binding.providerId !== 'string' || typeof record.binding.mode !== 'string' ||
      typeof record.binding.clientId !== 'string' || typeof record.binding.redirectUri !== 'string' ||
      typeof record.binding.clientSecretRef !== 'string' || !validTokens(record.tokens)) {
      throw new Error('google_secret_corrupt');
    }
    tokenNamespace(record.binding);
    return record;
  } catch (_error) { throw new Error('google_secret_corrupt'); }
};

export const stageGoogleSecret = async (
  providerId: string, kind: GoogleSecretKind, secret: string,
): Promise<string> => {
  const reference: string = util.generateRandomUUID(false);
  const namespace: string = secretNamespace(providerId, kind, reference);
  const length: number = secretByteLength(secret);
  if (length === 0 || length > MAX_SECRET_BYTES) throw new Error('google_secret_invalid_size');
  const record: GoogleSecretRecord = { version: 1, reference: reference, providerId: providerId,
    kind: kind, secret: secret };
  await mutex.withLock<void>(namespace, (): Promise<void> => writeEncrypted(namespace, JSON.stringify(record), true));
  return reference;
};
export const loadGoogleSecret = async (
  providerId: string, kind: GoogleSecretKind, reference: string,
): Promise<string | null> => {
  const namespace: string = secretNamespace(providerId, kind, reference);
  return mutex.withLock<string | null>(namespace, async (): Promise<string | null> => {
    const text: string | null = await readEncrypted(namespace);
    return text === null ? null : decodeSecret(text, providerId, kind, reference);
  });
};
export const deleteGoogleSecret = async (
  providerId: string, kind: GoogleSecretKind, reference: string,
): Promise<void> => {
  const namespace: string = secretNamespace(providerId, kind, reference);
  await mutex.withLock<void>(namespace, async (): Promise<void> => {
    const text: string | null = await readEncrypted(namespace);
    if (text === null) return;
    decodeSecret(text, providerId, kind, reference);
    await removeEncrypted(namespace);
  });
};
export const readGoogleOAuthTokens = async (binding: GoogleOAuthBinding): Promise<GoogleOAuthTokens | null> => {
  const captured: GoogleOAuthBinding = copyBinding(binding);
  const namespace: string = tokenNamespace(captured);
  return mutex.withLock<GoogleOAuthTokens | null>(namespace, async (): Promise<GoogleOAuthTokens | null> => {
    const text: string | null = await readEncrypted(namespace);
    if (text === null) return null;
    const record: GoogleTokenRecord = decodeTokens(text);
    return sameBinding(record.binding, captured) ? record.tokens : null;
  });
};
export const writeGoogleOAuthTokens = async (binding: GoogleOAuthBinding, tokens: GoogleOAuthTokens): Promise<void> => {
  const namespace: string = tokenNamespace(binding);
  if (!validTokens(tokens)) throw new Error('google_oauth_invalid_tokens');
  const record: GoogleTokenRecord = { version: 1, binding: binding, tokens: tokens };
  // Serialize before awaiting: callers cannot change the binding of a queued write.
  const text: string = JSON.stringify(record);
  await mutex.withLock<void>(namespace, (): Promise<void> => writeEncrypted(namespace, text, false));
};
export const removeGoogleOAuthTokens = async (binding: GoogleOAuthBinding): Promise<void> => {
  const captured: GoogleOAuthBinding = copyBinding(binding);
  const namespace: string = tokenNamespace(captured);
  await mutex.withLock<void>(namespace, async (): Promise<void> => {
    const text: string | null = await readEncrypted(namespace);
    if (text === null || !sameBinding(decodeTokens(text).binding, captured)) return;
    await removeEncrypted(namespace);
  });
};

// Called only after provider metadata deletion and B's cancellation/write-tail barrier.
// Its namespace is owned by this provider even when client configuration has changed.
export const removeGoogleOAuthTokensForProvider = async (providerId: string): Promise<void> => {
  requireUUID(providerId);
  const modes: GoogleOAuthMode[] = ['gemini_code_assist_oauth', 'antigravity_oauth'];
  for (const mode of modes) {
    const namespace: string = `tokens-${providerId}-${mode}`;
    await mutex.withLock<void>(namespace, async (): Promise<void> => {
      const text: string | null = await readEncrypted(namespace);
      if (text !== null) {
        const record: GoogleTokenRecord = decodeTokens(text);
        if (record.binding.providerId !== providerId || record.binding.mode !== mode) {
          throw new Error('google_secret_binding_mismatch');
        }
      }
      await removeEncrypted(namespace);
    });
  }
};
