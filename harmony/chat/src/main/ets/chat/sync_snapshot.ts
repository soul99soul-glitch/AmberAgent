// sync_snapshot — 加密备份快照契约（对齐 Android SyncManifest / iOS IOSSyncBackup）
//
// 纯逻辑：manifest + payload 组装；密码学经 CryptoPort 注入（entry 接
// native/sync-crypto 或 CryptoKit；测试用 mock）。
//
// 不含：云上传（WebDAV/S3/CloudKit/Huawei Drive）——上传是 entry/sync 适配层。

import type { Conversation } from './conversation.ts';
import type { JsonObject } from './json.ts';
import { exportConversationJson } from './conversation_export.ts';

export const SYNC_ARCHIVE_VERSION = 1;
export const MANIFEST_ENTRY = 'manifest.json';
export const PAYLOAD_ENTRY = 'payload.bin';

export type SyncMode = 'STANDARD' | 'FULL';

export interface SyncKdfInfo {
  name: 'PBKDF2' | 'scrypt';
  saltBase64: string;
  iterations: number;
}

export interface SyncCipherInfo {
  name: 'AES-GCM-256';
  ivBase64: string;
}

export interface SyncManifest {
  archiveVersion: number;
  appVersionName: string;
  appVersionCode: number;
  createdAt: number;
  deviceId: string;
  deviceLabel: string;
  mode: SyncMode;
  encrypted: true;
  passphraseProtected: boolean;
  kdf: SyncKdfInfo;
  cipher: SyncCipherInfo;
  /** hex sha256 of encrypted payload bytes (ciphertext||tag) */
  payloadSha256: string;
}

export interface SyncArchiveEntry {
  name: string;
  /** raw bytes; manifest is UTF-8 JSON, payload is ciphertext */
  data: Uint8Array;
}

export interface SyncArchive {
  entries: SyncArchiveEntry[];
}

export interface SyncCryptoPort {
  /** PBKDF2/scrypt 等价：由 passphrase+salt+iter 得 32B key */
  deriveKey(passphrase: string, saltBase64: string, iterations: number): Promise<Uint8Array>;
  /** AES-GCM：返回 ciphertext||tag(16B) */
  encrypt(plaintext: Uint8Array, key: Uint8Array, ivBase64: string): Promise<Uint8Array>;
  /** 失败返回 null（auth fail） */
  decrypt(ciphertext: Uint8Array, key: Uint8Array, ivBase64: string): Promise<Uint8Array | null>;
  sha256Hex(bytes: Uint8Array): Promise<string>;
  randomBytes(n: number): Promise<Uint8Array>;
}

export interface SyncPayload {
  version: number;
  createdAt: number;
  conversations: ReturnType<typeof exportConversationJson>[];
  /** settings blob（assistants/providers JSON 字符串）；不含密钥明文由调用方过滤 */
  settings?: JsonObject;
}

export interface SyncBackupInput {
  conversations: Conversation[];
  settings?: JsonObject;
  appVersionName: string;
  appVersionCode: number;
  deviceId: string;
  deviceLabel?: string;
  mode?: SyncMode;
  passphrase: string | null;
}

export interface SyncInspectPreview {
  manifest: SyncManifest;
  fileName: string;
  sizeBytes: number;
  needsPassphrase: boolean;
}

const utf8Encode = (s: string): Uint8Array => {
  const out: number[] = [];
  for (let i = 0; i < s.length; i++) {
    let c = s.charCodeAt(i);
    if (c < 0x80) out.push(c);
    else if (c < 0x800) {
      out.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
    } else {
      out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
    }
  }
  return new Uint8Array(out);
};

const utf8Decode = (bytes: Uint8Array): string => {
  let s = '';
  let i = 0;
  while (i < bytes.length) {
    const b = bytes[i];
    if (b < 0x80) { s += String.fromCharCode(b); i += 1; }
    else if ((b & 0xe0) === 0xc0) {
      s += String.fromCharCode(((b & 0x1f) << 6) | (bytes[i + 1] & 0x3f));
      i += 2;
    } else {
      s += String.fromCharCode(((b & 0x0f) << 12) | ((bytes[i + 1] & 0x3f) << 6) | (bytes[i + 2] & 0x3f));
      i += 3;
    }
  }
  return s;
};

const base64Encode = (bytes: Uint8Array): string => {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  let out = '';
  for (let i = 0; i < bytes.length; i += 3) {
    const b0 = bytes[i];
    const b1 = i + 1 < bytes.length ? bytes[i + 1] : 0;
    const b2 = i + 2 < bytes.length ? bytes[i + 2] : 0;
    out += alphabet[b0 >> 2];
    out += alphabet[((b0 & 3) << 4) | (b1 >> 4)];
    out += i + 1 < bytes.length ? alphabet[((b1 & 15) << 2) | (b2 >> 6)] : '=';
    out += i + 2 < bytes.length ? alphabet[b2 & 63] : '=';
  }
  return out;
};

const base64Decode = (s: string): Uint8Array => {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  const clean = s.replace(/=+$/, '');
  const out: number[] = [];
  let bits = 0;
  let value = 0;
  for (let i = 0; i < clean.length; i++) {
    const idx = alphabet.indexOf(clean[i]);
    if (idx < 0) continue;
    value = (value << 6) | idx;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out.push((value >> bits) & 0xff);
    }
  }
  return new Uint8Array(out);
};

export const buildSyncPayload = (input: SyncBackupInput, now: number): SyncPayload => {
  const payload: SyncPayload = {
    version: SYNC_ARCHIVE_VERSION,
    createdAt: now,
    conversations: input.conversations.map((c: Conversation) =>
      exportConversationJson(c)),
  };
  if (input.settings !== undefined) payload.settings = input.settings;
  return payload;
};

const encodeArchive = (entries: SyncArchiveEntry[]): Uint8Array => {
  // 简易容器：JSON 元数据 + base64 条目（便于测试与 KV/文件整包存取）
  const meta = entries.map((e) => ({
    name: e.name,
    b64: base64Encode(e.data),
  }));
  return utf8Encode(JSON.stringify(meta));
};

export const decodeArchive = (bytes: Uint8Array): SyncArchive => {
  const raw = utf8Decode(bytes);
  const parsed: unknown = JSON.parse(raw);
  if (!Array.isArray(parsed)) throw new Error('invalid sync archive');
  const entries: SyncArchiveEntry[] = [];
  for (const item of parsed as Array<{ name?: unknown; b64?: unknown }>) {
    if (typeof item.name !== 'string' || typeof item.b64 !== 'string') {
      throw new Error('invalid sync archive entry');
    }
    entries.push({ name: item.name, data: base64Decode(item.b64) });
  }
  return { entries };
};

export const inspectSyncArchive = async (
  archiveBytes: Uint8Array, fileName: string,
): Promise<SyncInspectPreview> => {
  const archive = decodeArchive(archiveBytes);
  const manifestEntry = archive.entries.find((e) => e.name === MANIFEST_ENTRY);
  const payloadEntry = archive.entries.find((e) => e.name === PAYLOAD_ENTRY);
  if (manifestEntry === undefined || payloadEntry === undefined) {
    throw new Error('同步备份缺少 manifest 或 payload');
  }
  const manifest = JSON.parse(utf8Decode(manifestEntry.data)) as SyncManifest;
  if (typeof manifest.archiveVersion !== 'number' || manifest.archiveVersion > SYNC_ARCHIVE_VERSION) {
    throw new Error('不支持的备份版本');
  }
  return {
    manifest,
    fileName,
    sizeBytes: archiveBytes.length,
    needsPassphrase: manifest.passphraseProtected,
  };
};

export const createSyncBackup = async (
  crypto: SyncCryptoPort,
  input: SyncBackupInput,
  now: number = Date.now(),
): Promise<Uint8Array> => {
  const passphrase = input.passphrase;
  const protectedArchive = passphrase !== null && passphrase.length > 0;
  const salt = await crypto.randomBytes(16);
  const iv = await crypto.randomBytes(12);
  const saltB64 = base64Encode(salt);
  const ivB64 = base64Encode(iv);
  const key = await crypto.deriveKey(passphrase ?? '', saltB64, 100_000);
  const payload = buildSyncPayload(input, now);
  const plain = utf8Encode(JSON.stringify(payload));
  const encrypted = await crypto.encrypt(plain, key, ivB64);
  const sha = await crypto.sha256Hex(encrypted);
  const manifest: SyncManifest = {
    archiveVersion: SYNC_ARCHIVE_VERSION,
    appVersionName: input.appVersionName,
    appVersionCode: input.appVersionCode,
    createdAt: now,
    deviceId: input.deviceId,
    deviceLabel: input.deviceLabel ?? '',
    mode: input.mode ?? 'STANDARD',
    encrypted: true,
    passphraseProtected: protectedArchive,
    kdf: { name: 'PBKDF2', saltBase64: saltB64, iterations: 100_000 },
    cipher: { name: 'AES-GCM-256', ivBase64: ivB64 },
    payloadSha256: sha,
  };
  return encodeArchive([
    { name: MANIFEST_ENTRY, data: utf8Encode(JSON.stringify(manifest)) },
    { name: PAYLOAD_ENTRY, data: encrypted },
  ]);
};

export interface SyncRestoreResult {
  manifest: SyncManifest;
  payload: SyncPayload;
}

export const restoreSyncBackup = async (
  crypto: SyncCryptoPort,
  archiveBytes: Uint8Array,
  passphrase: string | null,
): Promise<SyncRestoreResult> => {
  const preview = await inspectSyncArchive(archiveBytes, 'backup');
  const archive = decodeArchive(archiveBytes);
  const payloadEntry = archive.entries.find((e) => e.name === PAYLOAD_ENTRY);
  if (payloadEntry === undefined) throw new Error('同步备份缺少 payload');
  const actual = await crypto.sha256Hex(payloadEntry.data);
  if (actual !== preview.manifest.payloadSha256) {
    throw new Error('备份校验失败');
  }
  if (preview.manifest.passphraseProtected && (passphrase === null || passphrase.length === 0)) {
    throw new Error('需要密码');
  }
  const key = await crypto.deriveKey(
    passphrase ?? '',
    preview.manifest.kdf.saltBase64,
    preview.manifest.kdf.iterations,
  );
  const plain = await crypto.decrypt(payloadEntry.data, key, preview.manifest.cipher.ivBase64);
  if (plain === null) throw new Error('密码错误或备份损坏');
  const payload = JSON.parse(utf8Decode(plain)) as SyncPayload;
  return { manifest: preview.manifest, payload };
};
