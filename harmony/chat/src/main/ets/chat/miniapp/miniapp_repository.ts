// miniapp_repository — MiniAppRepository Port + 内存实现
//
// Android 基准: feature/miniapp/MiniAppRepository.kt(全文 320 行)+
//   feature/miniapp/MiniAppV3Runtime.kt minimalHostContext(190-199)
// 偏差:
//   - Room DAO → 内存 Map(createMemoryMiniAppRepository);事务语义逐条保留
//   - permissionsJson(String)→ permissions(string[])(Port 内联数组)
//   - sha256 → sha256Hex 注入(测试假实现;entry 接 @ohos.security.cryptoFramework)
//   - Uuid.random() → idGen 注入(默认 newId);System.currentTimeMillis → clock 注入
//   - observeAll/observeVersions/observeAuditLogs(Flow)不移植(列表读用 listAll/listVersions/
//     listAudit;adapter 层再决定响应式)
//   - saveNewVersion 为内部辅助(private,restoreVersion 复用)
//   - minimalHostContext 为纯函数(record 输入)

import {
  MINI_APP_AUDIT_SUMMARY_MAX_CHARS, MINI_APP_BOARD_SUMMARY_MAX_CHARS,
  MINI_APP_CHANGE_NOTE_MAX_CHARS, MINI_APP_RENAME_DESCRIPTION_MAX_CHARS,
  MINI_APP_RENAME_TITLE_MAX_CHARS, MINI_APP_SHARED_NAMESPACE_BYTES,
  MINI_APP_SHARED_VALUE_BYTES, MINI_APP_VERSION_KEEP_LIMIT,
  MiniAppValidationException,
} from './miniapp_models.ts';
import type {
  MiniAppAuditEntry, MiniAppCardRef, MiniAppGeneratedOutput, MiniAppGrantDecision,
  MiniAppPermission, MiniAppRecord, MiniAppVersionRecord,
} from './miniapp_models.ts';
import { utf8ByteLength } from './miniapp_models.ts';
import { newId } from '../ids.ts';
import type { JsonValue } from '../json.ts';
import { validateMiniAppHtml } from './miniapp_html_validator.ts';

export interface MiniAppRepositoryDeps {
  sha256Hex: (s: string) => string;
  idGen?: () => string;
  clock?: () => number;
}

export interface MiniAppSharedDataRecord {
  namespace: string;
  key: string;
  value: string;
  lastWriterId: string;
  updatedAt: number;
}

// ===== Port =====

export interface MiniAppRepository {
  listAll(): Promise<MiniAppRecord[]>;
  getById(id: string): Promise<MiniAppRecord | null>;
  saveGenerated(
    output: MiniAppGeneratedOutput,
    sourceConversationId?: string | null,
    sourceMessageId?: string | null,
  ): Promise<MiniAppRecord>;
  saveRevision(
    appId: string,
    output: MiniAppGeneratedOutput,
    expectedBaseVersion?: number | null,
    sourceMessageId?: string | null,
    changeNote?: string | null,
  ): Promise<MiniAppRecord | null>;
  markRun(id: string): Promise<void>;
  setPinned(id: string, pinned: boolean): Promise<void>;
  rename(id: string, title: string, description: string): Promise<void>;
  updateBoardSummary(id: string, summary: string): Promise<void>;
  delete(id: string): Promise<void>;
  listVersions(appId: string): Promise<MiniAppVersionRecord[]>;
  getVersion(appId: string, versionNumber: number): Promise<MiniAppVersionRecord | null>;
  restoreVersion(appId: string, versionNumber: number): Promise<MiniAppRecord | null>;
  getGrant(appId: string, permission: string): Promise<MiniAppGrantDecision | null>;
  setGrant(appId: string, permission: string, decision: MiniAppGrantDecision): Promise<void>;
  appendAudit(
    appId: string,
    method: string,
    permission: MiniAppPermission,
    summary: string,
    payload: string,
  ): Promise<void>;
  listAudit(appId: string, limit?: number): Promise<MiniAppAuditEntry[]>;
  sharedGet(appId: string, namespace: string, key: string): Promise<JsonValue | null>;
  sharedSet(appId: string, namespace: string, key: string, value: JsonValue): Promise<void>;
  sharedRemove(appId: string, namespace: string, key: string): Promise<void>;
}

// ===== 辅助(独立导出供 UI/adapter 复用)=====

export const miniAppToCardRef = (record: MiniAppRecord): MiniAppCardRef => ({
  appId: record.id,
  title: record.title,
  description: record.description,
  iconEmoji: record.iconEmoji,
  category: record.category,
  permissions: record.permissions,
  htmlHash: record.htmlHash,
  version: record.version,
});

// minimalHostContext(MiniAppV3Runtime.kt:190-199,minimalHostContext 为 JsonObject)
export interface MiniAppHostContext {
  untrustedContext: boolean;
  appId: string;
  title: string;
  description: string;
  boardSummary: string;
  sourceConversationId: string;
  sourceMessageId: string;
  note: string;
}

export const miniAppMinimalHostContext = (record: MiniAppRecord, maxChars: number): MiniAppHostContext => ({
  untrustedContext: true,
  appId: record.id,
  title: record.title.slice(0, 80),
  description: record.description.slice(0, 200),
  boardSummary: (record.boardSummary ?? '').slice(0, maxChars),
  sourceConversationId: record.sourceConversationId ?? '',
  sourceMessageId: record.sourceMessageId ?? '',
  note: 'MiniApp host context is minimized. Full chat history, system prompts, provider settings, credentials, and hidden tool outputs are not exposed.',
});

// ===== 内存实现 =====

export interface MemoryMiniAppRepository extends MiniAppRepository {
  records: Map<string, MiniAppRecord>;
  versions: Map<string, MiniAppVersionRecord[]>;
  grants: Map<string, Map<string, MiniAppGrantDecision>>;
  auditLogs: MiniAppAuditEntry[];
  sharedData: Map<string, Map<string, MiniAppSharedDataRecord>>;
}

const SHARED_KEY_REGEX: RegExp = /^[a-zA-Z0-9._:-]+$/;

export const createMemoryMiniAppRepository = (deps: MiniAppRepositoryDeps): MemoryMiniAppRepository => {
  const idGen: () => string = deps.idGen ?? newId;
  const clock: () => number = deps.clock ?? ((): number => Date.now());
  const sha256Hex: (s: string) => string = deps.sha256Hex;

  const records: Map<string, MiniAppRecord> = new Map();
  const versions: Map<string, MiniAppVersionRecord[]> = new Map();
  const grants: Map<string, Map<string, MiniAppGrantDecision>> = new Map();
  const auditLogs: MiniAppAuditEntry[] = [];
  const sharedData: Map<string, Map<string, MiniAppSharedDataRecord>> = new Map();

  const upsertVersion = (record: MiniAppVersionRecord): void => {
    const list: MiniAppVersionRecord[] = versions.get(record.appId) ?? [];
    const index: number = list.findIndex(
      (v: MiniAppVersionRecord): boolean => v.versionNumber === record.versionNumber);
    if (index >= 0) list[index] = record;
    else list.push(record);
    versions.set(record.appId, list);
  };

  const maxVersionNumber = (appId: string): number => {
    const list: MiniAppVersionRecord[] = versions.get(appId) ?? [];
    let max: number = 0;
    for (const v of list) {
      if (v.versionNumber > max) max = v.versionNumber;
    }
    return max;
  };

  // MiniAppRepository.kt:113/MiniAppVersionDAO.pruneOldVersions(keep 30)
  const pruneOldVersions = (appId: string, keep: number): void => {
    const list: MiniAppVersionRecord[] = versions.get(appId) ?? [];
    if (list.length <= keep) return;
    const sorted: MiniAppVersionRecord[] = [...list].sort(
      (a: MiniAppVersionRecord, b: MiniAppVersionRecord): number => b.versionNumber - a.versionNumber);
    versions.set(appId, sorted.slice(0, keep));
  };

  const insertRecord = (record: MiniAppRecord): MiniAppRecord => {
    records.set(record.id, record);
    return record;
  };

  const saveNewVersion = (
    app: MiniAppRecord, htmlContent: string, changeNote: string | null,
  ): MiniAppRecord => {
    validateMiniAppHtml(htmlContent);
    const now: number = clock();
    const nextVersion: number = maxVersionNumber(app.id) + 1;
    const hash: string = sha256Hex(htmlContent);
    const updated: MiniAppRecord = {
      ...app,
      htmlContent,
      htmlHash: hash,
      version: nextVersion,
      updatedAt: now,
    };
    insertRecord(updated);
    upsertVersion({
      appId: app.id,
      versionNumber: nextVersion,
      htmlContent,
      htmlHash: hash,
      changeNote,
      createdAt: now,
    });
    pruneOldVersions(app.id, MINI_APP_VERSION_KEEP_LIMIT);
    return updated;
  };

  // MiniAppRepository.kt:299-305
  const validateSharedNamespace = (appId: string, namespace: string): string => {
    const normalized: string = namespace.trim().length === 0 ? appId : namespace.trim();
    if (normalized !== appId) {
      throw new MiniAppValidationException('Cross-app SharedStore namespaces are not granted yet');
    }
    return normalized;
  };

  // MiniAppRepository.kt:307-313
  const validateSharedKey = (key: string): string => {
    const normalized: string = key.trim();
    if (normalized.length < 1 || normalized.length > 64 || !SHARED_KEY_REGEX.test(normalized)) {
      throw new MiniAppValidationException('Invalid SharedStore key');
    }
    return normalized;
  };

  const namespaceBytes = (namespace: string): number => {
    const map: Map<string, MiniAppSharedDataRecord> | undefined = sharedData.get(namespace);
    if (map === undefined) return 0;
    let total: number = 0;
    for (const entry of map.values()) total += utf8ByteLength(entry.value);
    return total;
  };

  const sharedGetRecord = (namespace: string, key: string): MiniAppSharedDataRecord | null => {
    const map: Map<string, MiniAppSharedDataRecord> | undefined = sharedData.get(namespace);
    if (map === undefined) return null;
    const entry: MiniAppSharedDataRecord | undefined = map.get(key);
    return entry === undefined ? null : entry;
  };

  return {
    records,
    versions,
    grants,
    auditLogs,
    sharedData,

    async listAll(): Promise<MiniAppRecord[]> {
      return [...records.values()];
    },

    async getById(id: string): Promise<MiniAppRecord | null> {
      const record: MiniAppRecord | undefined = records.get(id);
      return record === undefined ? null : record;
    },

    // MiniAppRepository.kt:36-72
    async saveGenerated(
      output: MiniAppGeneratedOutput,
      sourceConversationId: string | null = null,
      sourceMessageId: string | null = null,
    ): Promise<MiniAppRecord> {
      validateMiniAppHtml(output.html);
      const now: number = clock();
      const htmlHash: string = sha256Hex(output.html);
      const entity: MiniAppRecord = {
        id: idGen(),
        title: output.title.trim(),
        description: output.description.trim(),
        htmlContent: output.html,
        sourceConversationId,
        sourceMessageId,
        iconEmoji: output.icon !== null ? (output.icon.trim().length === 0 ? null : output.icon.trim()) : null,
        category: output.category,
        permissions: output.permissions,
        pinned: false,
        runCount: 0,
        boardSummary: null,
        version: 1,
        htmlHash,
        createdAt: now,
        updatedAt: now,
      };
      insertRecord(entity);
      upsertVersion({
        appId: entity.id,
        versionNumber: entity.version,
        htmlContent: entity.htmlContent,
        htmlHash,
        changeNote: 'Initial version',
        createdAt: now,
      });
      return entity;
    },

    // MiniAppRepository.kt:74-116(乐观并发 expectedBaseVersion)
    async saveRevision(
      appId: string,
      output: MiniAppGeneratedOutput,
      expectedBaseVersion: number | null = null,
      sourceMessageId: string | null = null,
      changeNote: string | null = null,
    ): Promise<MiniAppRecord | null> {
      validateMiniAppHtml(output.html);
      const now: number = clock();
      const app: MiniAppRecord | undefined = records.get(appId);
      if (app === undefined) return null;
      if (expectedBaseVersion !== null && app.version !== expectedBaseVersion) return null;
      const nextVersion: number = maxVersionNumber(app.id) + 1;
      const htmlHash: string = sha256Hex(output.html);
      const updated: MiniAppRecord = {
        ...app,
        title: output.title.trim(),
        description: output.description.trim(),
        htmlContent: output.html,
        sourceMessageId: sourceMessageId ?? app.sourceMessageId,
        iconEmoji: output.icon !== null ? (output.icon.trim().length === 0 ? null : output.icon.trim()) : null,
        category: output.category,
        permissions: output.permissions,
        htmlHash,
        version: nextVersion,
        updatedAt: now,
      };
      insertRecord(updated);
      upsertVersion({
        appId: updated.id,
        versionNumber: nextVersion,
        htmlContent: updated.htmlContent,
        htmlHash,
        changeNote: changeNote !== null ? (changeNote.trim().slice(0, MINI_APP_CHANGE_NOTE_MAX_CHARS) === '' ? '' : changeNote.trim().slice(0, MINI_APP_CHANGE_NOTE_MAX_CHARS)) : 'MiniApp revision',
        createdAt: now,
      });
      pruneOldVersions(updated.id, MINI_APP_VERSION_KEEP_LIMIT);
      return updated;
    },

    // MiniAppRepository.kt:202
    async markRun(id: string): Promise<void> {
      const record: MiniAppRecord | undefined = records.get(id);
      if (record === undefined) return;
      insertRecord({ ...record, runCount: record.runCount + 1 });
    },

    // MiniAppRepository.kt:204
    async setPinned(id: string, pinned: boolean): Promise<void> {
      const record: MiniAppRecord | undefined = records.get(id);
      if (record === undefined) return;
      insertRecord({ ...record, pinned, updatedAt: clock() });
    },

    // MiniAppRepository.kt:206-213
    async rename(id: string, title: string, description: string): Promise<void> {
      const record: MiniAppRecord | undefined = records.get(id);
      if (record === undefined) return;
      const trimmedTitle: string = title.trim().slice(0, MINI_APP_RENAME_TITLE_MAX_CHARS);
      insertRecord({
        ...record,
        title: trimmedTitle.length === 0 ? '未命名小应用' : trimmedTitle,
        description: description.trim().slice(0, MINI_APP_RENAME_DESCRIPTION_MAX_CHARS),
        updatedAt: clock(),
      });
    },

    // MiniAppRepository.kt:215-217
    async updateBoardSummary(id: string, summary: string): Promise<void> {
      const record: MiniAppRecord | undefined = records.get(id);
      if (record === undefined) return;
      insertRecord({
        ...record,
        boardSummary: summary.trim().slice(0, MINI_APP_BOARD_SUMMARY_MAX_CHARS),
        updatedAt: clock(),
      });
    },

    // MiniAppRepository.kt:136-144(级联删除)
    // 注:HarmonyOS es2abc 不支持对象字面量中 `async delete(...)` 方法键
    //   (delete 为保留字 + async 修饰 → 'Unexpected token in property key'),
    //   引用字符串键规避(等价语义;ArkTS 编译缺口,登记)
    async 'delete'(id: string): Promise<void> {
      records.delete(id);
      grants.delete(id);
      versions.delete(id);
      for (let i: number = auditLogs.length - 1; i >= 0; i--) {
        if (auditLogs[i].appId === id) auditLogs.splice(i, 1);
      }
      sharedData.delete(id);
      // 其它 app 以本 app 为 namespace 的共享数据也应级联(Android deleteForApp 同时删 lastWriterId 或 namespace)
      for (const namespace of [...sharedData.keys()]) {
        const map: Map<string, MiniAppSharedDataRecord> | undefined = sharedData.get(namespace);
        if (map === undefined) continue;
        const removals: string[] = [];
        for (const entry of map.values()) {
          if (entry.lastWriterId === id) removals.push(entry.key);
        }
        for (const key of removals) map.delete(key);
        if (map.size === 0) sharedData.delete(namespace);
      }
    },

    async listVersions(appId: string): Promise<MiniAppVersionRecord[]> {
      return [...(versions.get(appId) ?? [])].sort(
        (a: MiniAppVersionRecord, b: MiniAppVersionRecord): number => b.versionNumber - a.versionNumber);
    },

    async getVersion(appId: string, versionNumber: number): Promise<MiniAppVersionRecord | null> {
      const list: MiniAppVersionRecord[] = versions.get(appId) ?? [];
      const found: MiniAppVersionRecord | undefined = list.find(
        (v: MiniAppVersionRecord): boolean => v.versionNumber === versionNumber);
      return found === undefined ? null : found;
    },

    // MiniAppRepository.kt:179-183
    async restoreVersion(appId: string, versionNumber: number): Promise<MiniAppRecord | null> {
      const app: MiniAppRecord | undefined = records.get(appId);
      if (app === undefined) return null;
      const version: MiniAppVersionRecord | null = await this.getVersion(appId, versionNumber);
      if (version === null) return null;
      return saveNewVersion(app, version.htmlContent, `Restored from v${versionNumber}`);
    },

    async getGrant(appId: string, permission: string): Promise<MiniAppGrantDecision | null> {
      const map: Map<string, MiniAppGrantDecision> | undefined = grants.get(appId);
      if (map === undefined) return null;
      const decision: MiniAppGrantDecision | undefined = map.get(permission);
      return decision === undefined ? null : decision;
    },

    // MiniAppRepository.kt:185-194
    async setGrant(appId: string, permission: string, decision: MiniAppGrantDecision): Promise<void> {
      const map: Map<string, MiniAppGrantDecision> = grants.get(appId) ?? new Map();
      map.set(permission, decision);
      grants.set(appId, map);
    },

    // MiniAppRepository.kt:222-240
    async appendAudit(
      appId: string,
      method: string,
      permission: MiniAppPermission,
      summary: string,
      payload: string,
    ): Promise<void> {
      auditLogs.push({
        id: idGen(),
        appId,
        method,
        permission,
        summary: summary.slice(0, MINI_APP_AUDIT_SUMMARY_MAX_CHARS),
        payloadHash: sha256Hex(payload),
        createdAt: clock(),
      });
    },

    // MiniAppRepository.kt:219-220(limit 缺省 100,createdAt 降序)
    async listAudit(appId: string, limit: number = 100): Promise<MiniAppAuditEntry[]> {
      return auditLogs
        .filter((e: MiniAppAuditEntry): boolean => e.appId === appId)
        .sort((a: MiniAppAuditEntry, b: MiniAppAuditEntry): number => b.createdAt - a.createdAt)
        .slice(0, limit);
    },

    // MiniAppRepository.kt:242-248
    async sharedGet(appId: string, namespace: string, key: string): Promise<JsonValue | null> {
      const normalized: string = validateSharedNamespace(appId, namespace);
      const safeKey: string = validateSharedKey(key);
      const entry: MiniAppSharedDataRecord | null = sharedGetRecord(normalized, safeKey);
      if (entry === null) return null;
      try {
        return JSON.parse(entry.value) as JsonValue;
      } catch (_e) {
        return null;
      }
    },

    // MiniAppRepository.kt:250-272
    async sharedSet(appId: string, namespace: string, key: string, value: JsonValue): Promise<void> {
      const normalized: string = validateSharedNamespace(appId, namespace);
      const safeKey: string = validateSharedKey(key);
      const encoded: string = JSON.stringify(value);
      const valueBytes: number = utf8ByteLength(encoded);
      if (valueBytes > MINI_APP_SHARED_VALUE_BYTES) {
        throw new MiniAppValidationException('SharedStore value is too large');
      }
      const currentBytes: number = namespaceBytes(normalized);
      const old: MiniAppSharedDataRecord | null = sharedGetRecord(normalized, safeKey);
      const oldBytes: number = old === null ? 0 : utf8ByteLength(old.value);
      if (currentBytes - oldBytes + valueBytes > MINI_APP_SHARED_NAMESPACE_BYTES) {
        throw new MiniAppValidationException('SharedStore namespace is full');
      }
      const map: Map<string, MiniAppSharedDataRecord> = sharedData.get(normalized) ?? new Map();
      map.set(safeKey, {
        namespace: normalized,
        key: safeKey,
        value: encoded,
        lastWriterId: appId,
        updatedAt: clock(),
      });
      sharedData.set(normalized, map);
    },

    // MiniAppRepository.kt:274-276
    async sharedRemove(appId: string, namespace: string, key: string): Promise<void> {
      const normalized: string = validateSharedNamespace(appId, namespace);
      const safeKey: string = validateSharedKey(key);
      const map: Map<string, MiniAppSharedDataRecord> | undefined = sharedData.get(normalized);
      if (map === undefined) return;
      map.delete(safeKey);
      if (map.size === 0) sharedData.delete(normalized);
    },
  };
};
