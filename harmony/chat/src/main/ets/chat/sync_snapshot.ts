// sync_snapshot — 加密备份快照契约（对齐 Android SyncManifest / iOS IOSSyncBackup）
//
// 纯逻辑：manifest + payload 组装；密码学经 CryptoPort 注入（entry 接
// native/sync-crypto 或 CryptoKit；测试用 mock）。
//
// 不含：云上传（WebDAV/S3/CloudKit/Huawei Drive）——上传是 entry/sync 适配层。

import type { Conversation, MessageNode } from './conversation.ts';
import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessage } from './message.ts';
import type { Assistant } from './assistant.ts';
import type { ProviderSetting } from './provider_settings.ts';
import { parseAssistantList } from './assistant_serialize.ts';
import { parseProviderSettingList } from './provider_settings_serialize.ts';
import { serializeUIMessage, parseUIMessage } from './serialize.ts';
import { nowIso } from './ids.ts';

// 归档容器版本。v2 起 payload.conversations 为「完整 nodes DTO」；
// v1 旧归档仍可读(见 validateSyncPayload 的 legacy 分支，仅能还原当时打平的所选消息)。
export const SYNC_ARCHIVE_VERSION = 2;
// payload 语义版本(与归档版本同步推进；v1 = 打平 messages 旧格式)
export const SYNC_PAYLOAD_VERSION = 2;
export const SYNC_LEGACY_PAYLOAD_VERSION = 1;
export const MANIFEST_ENTRY = 'manifest.json';
export const PAYLOAD_ENTRY = 'payload.bin';

export type SyncMode = 'STANDARD' | 'FULL';

export interface SyncKdfInfo {
  /** 本端 SHA256-ITERATED；与 Android PBKDF2 不互通 */
  name: 'SHA256-ITERATED' | 'PBKDF2' | 'scrypt';
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

// 完整可逆 node DTO：保留一个位置的全部替代分支(alternatives)与 selectIndex，
// 不再是「每 node 仅所选消息」的打平数组。messages 为 kotlinx 线格式对象(真实序列化器产物)。
export interface SyncMessageNodeDto {
  id: string;
  selectIndex: number;
  messages: JsonObject[];
}

export interface SyncConversationDto {
  id: string;
  assistantId: string;
  title: string;
  createAt: string;
  updateAt: string;
  chatSuggestions: string[];
  isPinned: boolean;
  autoApproveToolCalls: boolean;
  nodes: SyncMessageNodeDto[];
}

export interface SyncPayload {
  version: number;
  createdAt: number;
  conversations: SyncConversationDto[];
  /** settings blob（assistants/providers）；不含密钥明文由调用方过滤 */
  settings?: JsonObject;
}

// 恢复 settings 的已校验产物(null = 备份不含该项)
export interface SyncRestoreSettings {
  assistants: Assistant[] | null;
  providers: ProviderSetting[] | null;
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
    // surrogate pair → 4-byte UTF-8
    if (c >= 0xd800 && c <= 0xdbff && i + 1 < s.length) {
      const c2 = s.charCodeAt(i + 1);
      if (c2 >= 0xdc00 && c2 <= 0xdfff) {
        const cp = 0x10000 + ((c - 0xd800) << 10) + (c2 - 0xdc00);
        out.push(
          0xf0 | (cp >> 18),
          0x80 | ((cp >> 12) & 0x3f),
          0x80 | ((cp >> 6) & 0x3f),
          0x80 | (cp & 0x3f),
        );
        i += 1;
        continue;
      }
    }
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
    } else if ((b & 0xf0) === 0xe0) {
      s += String.fromCharCode(((b & 0x0f) << 12) | ((bytes[i + 1] & 0x3f) << 6) | (bytes[i + 2] & 0x3f));
      i += 3;
    } else {
      const cp = ((b & 0x07) << 18)
        | ((bytes[i + 1] & 0x3f) << 12)
        | ((bytes[i + 2] & 0x3f) << 6)
        | (bytes[i + 3] & 0x3f);
      const offset = cp - 0x10000;
      s += String.fromCharCode(0xd800 + (offset >> 10), 0xdc00 + (offset & 0x3ff));
      i += 4;
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

// ===== 校验助手(导出与恢复共用；真实类型窄化，不做 as 强转) =====

const requireNonEmpty = (v: unknown, label: string): string => {
  if (typeof v !== 'string' || v.length === 0) throw new Error(`备份缺少有效 ${label}`);
  return v;
};

const requireIsoTime = (v: unknown, label: string): string => {
  if (typeof v !== 'string' || v.length === 0 || isNaN(Date.parse(v))) {
    throw new Error(`备份时间非法(${label})`);
  }
  return v;
};

const requireUniqueConversationIds = (convs: SyncConversationDto[]): void => {
  const seen: Set<string> = new Set<string>();
  for (const c of convs) {
    if (seen.has(c.id)) throw new Error(`备份存在重复会话 ID：${c.id}`);
    seen.add(c.id);
  }
};

// 节点 id 在 message_node PK 上全局唯一(跨会话)；备份内也据此拒绝跨 conv 撞 id。
const requireUniqueNodeIds = (convs: SyncConversationDto[]): void => {
  const seen: Set<string> = new Set<string>();
  for (const c of convs) {
    for (const n of c.nodes) {
      if (seen.has(n.id)) throw new Error(`备份存在重复节点 ID：${n.id}`);
      seen.add(n.id);
    }
  }
};

const asObject = (v: unknown): JsonObject | null =>
  typeof v === 'object' && v !== null && !Array.isArray(v) ? v as JsonObject : null;

const stringArrayOf = (v: JsonValue | undefined, label: string): string[] => {
  if (v === undefined) return [];
  if (!Array.isArray(v)) throw new Error(`备份字段非法(${label})`);
  const out: string[] = [];
  for (const item of v) {
    if (typeof item !== 'string') throw new Error(`备份字段非法(${label})`);
    out.push(item);
  }
  return out;
};

// settings 预校验(不写盘)：assistants/providers 必须为数组且能被真实反序列化器解析；
// 未知/畸形枚举在这里就以非持久化错误抛出。
const validateSettingsBlob = (blob: JsonObject): JsonObject => {
  const assistantsJson: JsonValue | undefined = blob['assistants'];
  if (assistantsJson !== undefined) parseAssistantList(JSON.stringify(assistantsJson));
  const providersJson: JsonValue | undefined = blob['providers'];
  if (providersJson !== undefined) parseProviderSettingList(JSON.stringify(providersJson));
  return blob;
};

// ===== 会话 DTO → 领域对象(恢复；仅接受已通过 validateSyncPayload 的 DTO) =====

const dtoMessageToDomain = (obj: JsonObject, now: () => string): UIMessage =>
  parseUIMessage(obj, now);

export const conversationFromSyncDto = (
  dto: SyncConversationDto, now: () => string = nowIso,
): Conversation => {
  const nodes: MessageNode[] = [];
  for (const n of dto.nodes) {
    const messages: UIMessage[] = n.messages.map(
      (m: JsonObject): UIMessage => dtoMessageToDomain(m, now));
    nodes.push({ id: n.id, messages, selectIndex: n.selectIndex });
  }
  return {
    id: dto.id,
    assistantId: dto.assistantId,
    title: dto.title,
    createAt: dto.createAt,
    updateAt: dto.updateAt,
    chatSuggestions: [...dto.chatSuggestions],
    isPinned: dto.isPinned,
    autoApproveToolCalls: dto.autoApproveToolCalls,
    messageNodes: nodes,
  };
};

// ===== payload 校验(真实序列化器，拒绝任何强转未校验) =====

// v1 旧格式(打平 messages、无分支/元数据)。只读兼容：还原为每消息一个单分支 node，
// 元数据回默认值(旧归档物理上不含这些字段，无法补回)。
const validateLegacyConversation = (v: JsonObject, now: () => string): SyncConversationDto => {
  const id: string = requireNonEmpty(v['id'], 'conversation.id');
  const assistantId: string = requireNonEmpty(v['assistantId'], `conversation ${id} assistantId`);
  const createAt: string = requireIsoTime(v['createAt'], `conversation ${id} createAt`);
  const updateAt: string = requireIsoTime(v['updateAt'], `conversation ${id} updateAt`);
  const messagesRaw: JsonValue | undefined = v['messages'];
  if (!Array.isArray(messagesRaw)) throw new Error(`备份会话缺少 messages：${id}`);
  const nodes: SyncMessageNodeDto[] = [];
  for (let i = 0; i < messagesRaw.length; i++) {
    const obj: JsonObject | null = asObject(messagesRaw[i]);
    if (obj === null) throw new Error(`备份消息非法(conversation ${id} message[${i}])`);
    // parseUIMessage 对缺 id 会 newId()、缺 createdAt 会用 now()，故必须在 parse 前强验原始字段，
    // 否则损坏归档被静默补值掩盖、恢复不再忠实(R02)。
    requireNonEmpty(obj['id'], `conversation ${id} message[${i}].id`);
    requireIsoTime(obj['createdAt'], `conversation ${id} message[${i}].createdAt`);
    // 真实反序列化器校验(会拒绝畸形 role/parts 等)
    const msg: UIMessage = parseUIMessage(obj, now);
    nodes.push({ id: msg.id, selectIndex: 0, messages: [serializeUIMessage(msg)] });
  }
  return {
    id,
    assistantId,
    title: typeof v['title'] === 'string' ? v['title'] : '',
    createAt,
    updateAt,
    chatSuggestions: [],
    isPinned: false,
    autoApproveToolCalls: false,
    nodes,
  };
};

const validateNodeDto = (v: JsonObject, convId: string, now: () => string): SyncMessageNodeDto => {
  const id: string = requireNonEmpty(v['id'], `conversation ${convId} node.id`);
  const si: JsonValue | undefined = v['selectIndex'];
  if (typeof si !== 'number' || !Number.isInteger(si) || si < 0) {
    throw new Error(`备份节点 selectIndex 非法：${id}`);
  }
  const messagesRaw: JsonValue | undefined = v['messages'];
  if (!Array.isArray(messagesRaw) || messagesRaw.length === 0) {
    throw new Error(`备份节点缺少 messages：${id}`);
  }
  if (si >= messagesRaw.length) throw new Error(`备份节点 selectIndex 越界：${id}`);
  const messages: JsonObject[] = [];
  const seenMsgIds: Set<string> = new Set<string>();
  for (let i = 0; i < messagesRaw.length; i++) {
    const obj: JsonObject | null = asObject(messagesRaw[i]);
    if (obj === null) throw new Error(`备份消息非法(node ${id} message[${i}])`);
    // 先验原始 id/createdAt(parseUIMessage 缺省会补新值，掩盖损坏)；id 需在节点内唯一。
    const mid: string = requireNonEmpty(obj['id'], `node ${id} message[${i}].id`);
    requireIsoTime(obj['createdAt'], `node ${id} message[${i}].createdAt`);
    if (seenMsgIds.has(mid)) throw new Error(`备份节点内消息 ID 重复：${mid}`);
    seenMsgIds.add(mid);
    const msg: UIMessage = parseUIMessage(obj, now);
    messages.push(serializeUIMessage(msg));
  }
  return { id, selectIndex: si, messages };
};

const validateConversationDto = (v: JsonObject, now: () => string): SyncConversationDto => {
  const id: string = requireNonEmpty(v['id'], 'conversation.id');
  const assistantId: string = requireNonEmpty(v['assistantId'], `conversation ${id} assistantId`);
  const createAt: string = requireIsoTime(v['createAt'], `conversation ${id} createAt`);
  const updateAt: string = requireIsoTime(v['updateAt'], `conversation ${id} updateAt`);
  const nodesRaw: JsonValue | undefined = v['nodes'];
  if (!Array.isArray(nodesRaw)) throw new Error(`备份会话缺少 nodes：${id}`);
  const nodes: SyncMessageNodeDto[] = [];
  for (let i = 0; i < nodesRaw.length; i++) {
    const obj: JsonObject | null = asObject(nodesRaw[i]);
    if (obj === null) throw new Error(`备份节点非法(conversation ${id} node[${i}])`);
    nodes.push(validateNodeDto(obj, id, now));
  }
  const pinned: JsonValue | undefined = v['isPinned'];
  if (pinned !== undefined && typeof pinned !== 'boolean') {
    throw new Error(`备份字段非法(conversation ${id} isPinned)`);
  }
  const auto: JsonValue | undefined = v['autoApproveToolCalls'];
  if (auto !== undefined && typeof auto !== 'boolean') {
    throw new Error(`备份字段非法(conversation ${id} autoApproveToolCalls)`);
  }
  return {
    id,
    assistantId,
    title: typeof v['title'] === 'string' ? v['title'] : '',
    createAt,
    updateAt,
    chatSuggestions: stringArrayOf(v['chatSuggestions'], `conversation ${id} chatSuggestions`),
    isPinned: pinned === true,
    autoApproveToolCalls: auto === true,
    nodes,
  };
};

/**
 * 用真实序列化器逐字段校验解密后的 payload，产出可安全恢复的完整 DTO。
 * 任何结构/ID/时间/selectIndex 非法都在写库前以非持久化错误抛出。
 * 支持读 v1(legacy 打平格式)与 v2(完整 nodes)。
 */
export const validateSyncPayload = (
  raw: unknown, now: () => string = nowIso,
): SyncPayload => {
  const obj: JsonObject | null = asObject(raw);
  if (obj === null) throw new Error('备份 payload 不是对象');
  const version: JsonValue | undefined = obj['version'];
  const createdAt: JsonValue | undefined = obj['createdAt'];
  const convRaw: JsonValue | undefined = obj['conversations'];
  if (!Array.isArray(convRaw)) throw new Error('备份缺少会话列表');
  if (convRaw.length > 0 && (typeof version !== 'number' || !Number.isInteger(version))) {
    throw new Error('备份 payload 版本非法');
  }
  const versionNum: number = typeof version === 'number' ? version : 0;
  const conversations: SyncConversationDto[] = [];
  for (const item of convRaw) {
    const entry: JsonObject | null = asObject(item);
    if (entry === null) throw new Error('备份会话条目非法');
    conversations.push(versionNum >= SYNC_PAYLOAD_VERSION
      ? validateConversationDto(entry, now)
      : validateLegacyConversation(entry, now));
  }
  requireUniqueConversationIds(conversations);
  requireUniqueNodeIds(conversations);
  const payload: SyncPayload = {
    version: versionNum,
    createdAt: typeof createdAt === 'number' ? createdAt : 0,
    conversations,
  };
  const settings: JsonValue | undefined = obj['settings'];
  if (settings !== undefined) {
    const blob: JsonObject | null = asObject(settings);
    if (blob === null) throw new Error('备份 settings 非法');
    payload.settings = validateSettingsBlob(blob);
  }
  return payload;
};

// 从已校验 payload 解析恢复用 settings(null=不含)。校验在 validateSyncPayload 已完成，
// 此处只做真实反序列化供第二阶段写入。
export const settingsFromSyncPayload = (payload: SyncPayload): SyncRestoreSettings => {
  const blob: JsonObject | undefined = payload.settings;
  let assistants: Assistant[] | null = null;
  let providers: ProviderSetting[] | null = null;
  if (blob !== undefined) {
    const a: JsonValue | undefined = blob['assistants'];
    if (a !== undefined) assistants = parseAssistantList(JSON.stringify(a));
    const p: JsonValue | undefined = blob['providers'];
    if (p !== undefined) providers = parseProviderSettingList(JSON.stringify(p));
  }
  return { assistants, providers };
};

// ===== 会话 DTO(单一完整格式) =====

// 会话 → 完整 DTO(单一真源；导出与恢复共用同一 DTO，消息经真实 kotlinx 序列化器)。
// 校验失败(异常时间/非法 selectIndex/缺 id)在打包阶段即抛出，避免产出不可恢复归档。
export const toSyncConversationDto = (conv: Conversation): SyncConversationDto => {
  const id: string = requireNonEmpty(conv.id, 'conversation.id');
  const assistantId: string = requireNonEmpty(conv.assistantId, 'conversation.assistantId');
  const createAt: string = requireIsoTime(conv.createAt, `conversation ${id} createAt`);
  const updateAt: string = requireIsoTime(conv.updateAt, `conversation ${id} updateAt`);
  const nodes: SyncMessageNodeDto[] = [];
  for (let i = 0; i < conv.messageNodes.length; i++) {
    const node: MessageNode = conv.messageNodes[i];
    const nodeId: string = requireNonEmpty(node.id, `conversation ${id} node[${i}].id`);
    if (!Array.isArray(node.messages) || node.messages.length === 0) {
      throw new Error(`conversation ${id} node[${i}] has no messages`);
    }
    if (!Number.isInteger(node.selectIndex) || node.selectIndex < 0
      || node.selectIndex >= node.messages.length) {
      throw new Error(`conversation ${id} node[${i}] selectIndex out of range: ${node.selectIndex}`);
    }
    const messages: JsonObject[] = [];
    for (let j = 0; j < node.messages.length; j++) {
      const m: UIMessage = node.messages[j];
      requireNonEmpty(m.id, `conversation ${id} node[${i}] message[${j}].id`);
      requireIsoTime(m.createdAt, `conversation ${id} node[${i}] message[${j}].createdAt`);
      messages.push(serializeUIMessage(m));
    }
    nodes.push({ id: nodeId, selectIndex: node.selectIndex, messages });
  }
  const suggestions: string[] = [];
  if (Array.isArray(conv.chatSuggestions)) {
    for (const s of conv.chatSuggestions) if (typeof s === 'string') suggestions.push(s);
  }
  return {
    id,
    assistantId,
    title: typeof conv.title === 'string' ? conv.title : '',
    createAt,
    updateAt,
    chatSuggestions: suggestions,
    isPinned: conv.isPinned === true,
    autoApproveToolCalls: conv.autoApproveToolCalls === true,
    nodes,
  };
};

export const buildSyncPayload = (
  input: SyncBackupInput, now: number,
): SyncPayload => {
  const conversations: SyncConversationDto[] = input.conversations.map(
    (c: Conversation): SyncConversationDto => toSyncConversationDto(c));
  requireUniqueConversationIds(conversations);
  const payload: SyncPayload = {
    version: SYNC_PAYLOAD_VERSION,
    createdAt: now,
    conversations,
  };
  if (input.settings !== undefined) {
    payload.settings = validateSettingsBlob(input.settings);
  }
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
    kdf: { name: 'SHA256-ITERATED', saltBase64: saltB64, iterations: 100_000 },
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
  const parsed: unknown = JSON.parse(utf8Decode(plain));
  // 真实反序列化器逐字段校验；非法结构在此以非持久化错误抛出，绝不强转未校验数据。
  const payload: SyncPayload = validateSyncPayload(parsed);
  return { manifest: preview.manifest, payload };
};
