// MiniAppFiles — MiniAppRepository 的文件序列化(entry 适配)
//
// 文件布局(filesDir/miniapp/):
//   index.json           记录列表(无 html;versions 元数据内嵌,与 html 分离)
//   <appId>.html         当前版 html(每运行版快照)
//   <appId>.v<N>.html    历史版 html(N = versionNumber)
//   grants.json          { apps: { appId: { permission: 'ALLOW'|'DENY' } } }
//   audit.json           { entries: MiniAppAuditEntry[] }
//   shared.json          { namespaces: { ns: { key: MiniAppSharedDataRecord } } }
//
// 策略:
//   - 完整业务规则在 HAR 内存实现(createMemoryMiniAppRepository)中,本文件以
//     内存 Map 为缓存层,加载时把文件灌入内存,变更后按需增量序列化回盘。
//   - 写入走临时文件 + rename 原子替换;写操作串行队列(enqueue)。
//   - 配额/prune/乐观并发等全部由内存实现承担,此处不重复。
//
// 偏差登记:
//   - Android Room 每字段变更事务 → 本文件按变更类型整写对应文件
//     (index.html 无 html / 仅版本变更才写 html 文件,避免 markRun 重写大文件)。
//   - 损坏/缺失文件 → 跳过对应记录(kotlinx 反序列化失败等价丢弃)。

import { fileIo } from '@kit.CoreFileKit';
import type { Context } from '@kit.AbilityKit';
import type {
  MiniAppRepository, MiniAppRecord, MiniAppVersionRecord, MiniAppAuditEntry,
  MiniAppGrantDecision, MiniAppSharedDataRecord, MemoryMiniAppRepository,
  MiniAppGeneratedOutput, MiniAppPermission, JsonValue,
} from '@amber/chat-domain';
import { createMemoryMiniAppRepository } from '@amber/chat-domain';

export interface MiniAppFilesOpts {
  baseDir: string;
  sha256Hex: (s: string) => string;
  clock?: () => number;
  idGen?: () => string;
}

// 文件仓库在 MiniAppRepository 之外额外暴露同步 grant 读取(供 MiniAppSandbox
//   grantLookup 同步注入;Android 侧 runBlocking{repository.grantDecision} 等价)
export interface MiniAppFileRepository extends MiniAppRepository {
  grantSync(appId: string, permission: string): MiniAppGrantDecision | null;
}

interface MiniAppIndexVersionJson {
  versionNumber: number;
  htmlHash: string;
  changeNote: string | null;
  createdAt: number;
}

interface MiniAppIndexEntryJson {
  id: string;
  title: string;
  description: string;
  sourceConversationId: string | null;
  sourceMessageId: string | null;
  iconEmoji: string | null;
  category: string | null;
  permissions: string[];
  pinned: boolean;
  runCount: number;
  boardSummary: string | null;
  version: number;
  htmlHash: string | null;
  createdAt: number;
  updatedAt: number;
  versions: MiniAppIndexVersionJson[];
}

interface MiniAppIndexJson {
  apps: MiniAppIndexEntryJson[];
}

// index 条目结构守卫:损坏/旧版字段缺失只跳过单条,id 参与路径拼接必须受控
const SAFE_ENTRY_ID_PATTERN: RegExp = /^[A-Za-z0-9_-]{1,64}$/;

const isValidIndexVersion = (v: MiniAppIndexVersionJson): boolean =>
  v !== null && typeof v === 'object'
  && typeof v.versionNumber === 'number' && Number.isSafeInteger(v.versionNumber)
  && v.versionNumber >= 1
  && typeof v.htmlHash === 'string'
  && (v.changeNote === null || typeof v.changeNote === 'string')
  && typeof v.createdAt === 'number' && Number.isFinite(v.createdAt);

const isValidIndexEntry = (e: MiniAppIndexEntryJson): boolean =>
  e !== null && typeof e === 'object'
  && typeof e.id === 'string' && SAFE_ENTRY_ID_PATTERN.test(e.id)
  && typeof e.title === 'string'
  && Array.isArray(e.permissions)
  && e.permissions.every((p: string): boolean => typeof p === 'string')
  && typeof e.pinned === 'boolean'
  && typeof e.runCount === 'number' && Number.isSafeInteger(e.runCount)
  && e.runCount >= 0
  && typeof e.createdAt === 'number' && Number.isFinite(e.createdAt)
  && typeof e.updatedAt === 'number' && Number.isFinite(e.updatedAt)
  && Array.isArray(e.versions)
  && e.versions.every((v: MiniAppIndexVersionJson): boolean => isValidIndexVersion(v));

interface MiniAppGrantsJson {
  apps: Record<string, Record<string, MiniAppGrantDecision>>;
}

interface MiniAppAuditJson {
  entries: MiniAppAuditEntry[];
}

interface MiniAppSharedJson {
  namespaces: Record<string, Record<string, MiniAppSharedDataRecord>>;
}

export const miniAppFilesDir = (): string => {
  const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
  return `${ctx.filesDir}/miniapp`;
};

const emptyIndex = (): MiniAppIndexJson => ({ apps: [] });

// ===== 文件 IO(临时文件 + rename 原子替换) =====

class MiniAppFilesIo {
  private dir: string;
  private writeQueue: Promise<void> = Promise.resolve();

  constructor(dir: string) {
    this.dir = dir;
  }

  private enqueue(op: () => Promise<void>): Promise<void> {
    const next: Promise<void> = this.writeQueue.then(op);
    this.writeQueue = next.then((): void => {}, (): void => {});
    return next;
  }

  private async ensureDir(): Promise<void> {
    try {
      await fileIo.mkdir(this.dir, true);
    } catch {
      // 已存在
    }
  }

  private async readText(name: string): Promise<string | null> {
    const path: string = `${this.dir}/${name}`;
    try {
      const ok: boolean = await fileIo.access(path);
      if (!ok) return null;
      return await fileIo.readText(path);
    } catch {
      return null;
    }
  }

  private async writeTextAtomic(name: string, content: string): Promise<void> {
    await this.ensureDir();
    const path: string = `${this.dir}/${name}`;
    const tmp: string = `${this.dir}/${name}.tmp`;
    const mode: number = fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC;
    const file: fileIo.File = await fileIo.open(tmp, mode);
    try {
      await fileIo.write(file.fd, content);
    } finally {
      await fileIo.close(file);
    }
    try {
      await fileIo.rename(tmp, path);
    } catch (_e) {
      // rename 目标已存在失败(平台差异)→ 先删目标再 rename
      try {
        await fileIo.unlink(path);
      } catch (_e2) {
        // 目标不存在
      }
      await fileIo.rename(tmp, path);
    }
  }

  private async removeOrphanHtml(expected: Set<string>): Promise<void> {
    try {
      const names: string[] = await fileIo.listFile(this.dir);
      for (const name of names) {
        if (name.endsWith('.tmp')) continue;
        if (!name.endsWith('.html')) continue;
        if (expected.has(name)) continue;
        try {
          await fileIo.unlink(`${this.dir}/${name}`);
        } catch (_e) {
          // 已不存在
        }
      }
    } catch (_e) {
      // 目录不可列(未创建)→ 无孤儿
    }
  }

  // ===== 加载 =====

  async load(mem: MemoryMiniAppRepository): Promise<void> {
    await this.ensureDir();
    const indexText: string | null = await this.readText('index.json');
    if (indexText !== null) {
      let index: MiniAppIndexJson = emptyIndex();
      let parsedOk: boolean = false;
      try {
        const parsed: MiniAppIndexJson = JSON.parse(indexText) as MiniAppIndexJson;
        if (Array.isArray(parsed.apps)) {
          index = parsed;
          parsedOk = true;
        }
      } catch (_e) {
        parsedOk = false;
      }
      if (!parsedOk) {
        // 顶层损坏:隔离保留现场(下次写入以空索引起步,不静默覆盖原文件)
        try {
          fileIo.renameSync(`${this.dir}/index.json`, `${this.dir}/index.${Date.now()}.bad`);
        } catch (_e) {
          // 隔离尽力而为
        }
      }
      for (const entry of index.apps) {
        // 数据清理策略(有意):无法安全加载的条目跳过且不回写 index —
        // 后续任意 persistIndex 会将其从索引移除(HTML 成孤儿文件)
        if (!isValidIndexEntry(entry)) continue;
        const html: string | null = await this.readText(`${entry.id}.html`);
        if (html === null) continue;
        const record: MiniAppRecord = {
          id: entry.id,
          title: entry.title,
          description: entry.description,
          htmlContent: html,
          sourceConversationId: entry.sourceConversationId,
          sourceMessageId: entry.sourceMessageId,
          iconEmoji: entry.iconEmoji,
          category: entry.category,
          permissions: entry.permissions,
          pinned: entry.pinned,
          runCount: entry.runCount,
          boardSummary: entry.boardSummary,
          version: entry.version,
          htmlHash: entry.htmlHash,
          createdAt: entry.createdAt,
          updatedAt: entry.updatedAt,
        };
        mem.records.set(entry.id, record);
        const versions: MiniAppVersionRecord[] = [];
        for (const v of entry.versions) {
          const vhtml: string | null = await this.readText(`${entry.id}.v${v.versionNumber}.html`);
          if (vhtml === null) continue;
          versions.push({
            appId: entry.id,
            versionNumber: v.versionNumber,
            htmlContent: vhtml,
            htmlHash: v.htmlHash,
            changeNote: v.changeNote,
            createdAt: v.createdAt,
          });
        }
        if (versions.length > 0) mem.versions.set(entry.id, versions);
      }
    }
    const grantsText: string | null = await this.readText('grants.json');
    if (grantsText !== null) {
      try {
        const grantsJson: MiniAppGrantsJson = JSON.parse(grantsText) as MiniAppGrantsJson;
        for (const appId of Object.keys(grantsJson.apps)) {
          const perms: Record<string, MiniAppGrantDecision> = grantsJson.apps[appId];
          const map: Map<string, MiniAppGrantDecision> = new Map<string, MiniAppGrantDecision>();
          for (const permission of Object.keys(perms)) {
            map.set(permission, perms[permission]);
          }
          mem.grants.set(appId, map);
        }
      } catch (_e) {
        // 损坏 → 忽略 grants
      }
    }
    const auditText: string | null = await this.readText('audit.json');
    if (auditText !== null) {
      try {
        const auditJson: MiniAppAuditJson = JSON.parse(auditText) as MiniAppAuditJson;
        for (const entry of auditJson.entries) {
          mem.auditLogs.push(entry);
        }
      } catch (_e) {
        // 损坏 → 忽略 audit
      }
    }
    const sharedText: string | null = await this.readText('shared.json');
    if (sharedText !== null) {
      try {
        const sharedJson: MiniAppSharedJson = JSON.parse(sharedText) as MiniAppSharedJson;
        for (const ns of Object.keys(sharedJson.namespaces)) {
          const entries: Record<string, MiniAppSharedDataRecord> = sharedJson.namespaces[ns];
          const map: Map<string, MiniAppSharedDataRecord> =
            new Map<string, MiniAppSharedDataRecord>();
          for (const key of Object.keys(entries)) {
            map.set(key, entries[key]);
          }
          mem.sharedData.set(ns, map);
        }
      } catch (_e) {
        // 损坏 → 忽略 shared
      }
    }
  }

  // ===== 增量持久化 =====

  // index.json(无 html;含版本元数据)— markRun/pinned/rename/boardSummary 变更后
  persistIndex(mem: MemoryMiniAppRepository): Promise<void> {
    return this.enqueue(async (): Promise<void> => {
      const apps: MiniAppIndexEntryJson[] = [];
      for (const record of mem.records.values()) {
        const list: MiniAppVersionRecord[] = mem.versions.get(record.id) ?? [];
        const versionMeta: MiniAppIndexVersionJson[] = [];
        for (const v of list) {
          versionMeta.push({
            versionNumber: v.versionNumber,
            htmlHash: v.htmlHash,
            changeNote: v.changeNote,
            createdAt: v.createdAt,
          });
        }
        apps.push({
          id: record.id,
          title: record.title,
          description: record.description,
          sourceConversationId: record.sourceConversationId,
          sourceMessageId: record.sourceMessageId,
          iconEmoji: record.iconEmoji,
          category: record.category,
          permissions: record.permissions,
          pinned: record.pinned,
          runCount: record.runCount,
          boardSummary: record.boardSummary,
          version: record.version,
          htmlHash: record.htmlHash,
          createdAt: record.createdAt,
          updatedAt: record.updatedAt,
          versions: versionMeta,
        });
      }
      const index: MiniAppIndexJson = { apps };
      await this.writeTextAtomic('index.json', JSON.stringify(index));
    });
  }

  // 当前版 + 历史版 html 文件(版本新增/恢复/删除后)
  persistHtml(mem: MemoryMiniAppRepository): Promise<void> {
    return this.enqueue(async (): Promise<void> => {
      const expected: Set<string> = new Set<string>();
      for (const record of mem.records.values()) {
        expected.add(`${record.id}.html`);
        await this.writeTextAtomic(`${record.id}.html`, record.htmlContent);
      }
      for (const appId of mem.versions.keys()) {
        const list: MiniAppVersionRecord[] = mem.versions.get(appId) ?? [];
        for (const v of list) {
          expected.add(`${appId}.v${v.versionNumber}.html`);
          await this.writeTextAtomic(`${appId}.v${v.versionNumber}.html`, v.htmlContent);
        }
      }
      await this.removeOrphanHtml(expected);
    });
  }

  persistGrants(mem: MemoryMiniAppRepository): Promise<void> {
    return this.enqueue(async (): Promise<void> => {
      const grants: Record<string, Record<string, MiniAppGrantDecision>> = {};
      for (const appId of mem.grants.keys()) {
        const map: Map<string, MiniAppGrantDecision> | undefined = mem.grants.get(appId);
        if (map === undefined) continue;
        const perms: Record<string, MiniAppGrantDecision> = {};
        for (const permission of map.keys()) {
          const decision: MiniAppGrantDecision | undefined = map.get(permission);
          if (decision !== undefined) perms[permission] = decision;
        }
        grants[appId] = perms;
      }
      const payload: MiniAppGrantsJson = { apps: grants };
      await this.writeTextAtomic('grants.json', JSON.stringify(payload));
    });
  }

  persistAudit(mem: MemoryMiniAppRepository): Promise<void> {
    return this.enqueue(async (): Promise<void> => {
      const payload: MiniAppAuditJson = { entries: mem.auditLogs };
      await this.writeTextAtomic('audit.json', JSON.stringify(payload));
    });
  }

  persistShared(mem: MemoryMiniAppRepository): Promise<void> {
    return this.enqueue(async (): Promise<void> => {
      const namespaces: Record<string, Record<string, MiniAppSharedDataRecord>> = {};
      for (const ns of mem.sharedData.keys()) {
        const map: Map<string, MiniAppSharedDataRecord> | undefined = mem.sharedData.get(ns);
        if (map === undefined) continue;
        const entries: Record<string, MiniAppSharedDataRecord> = {};
        for (const key of map.keys()) {
          const entry: MiniAppSharedDataRecord | undefined = map.get(key);
          if (entry !== undefined) entries[key] = entry;
        }
        namespaces[ns] = entries;
      }
      const payload: MiniAppSharedJson = { namespaces };
      await this.writeTextAtomic('shared.json', JSON.stringify(payload));
    });
  }
}

// ===== 组装:内存缓存层 + 文件持久化 =====

class FileMiniAppRepository implements MiniAppFileRepository {
  private io: MiniAppFilesIo;
  private mem: MemoryMiniAppRepository;

  constructor(io: MiniAppFilesIo, mem: MemoryMiniAppRepository) {
    this.io = io;
    this.mem = mem;
  }

  grantSync(appId: string, permission: string): MiniAppGrantDecision | null {
    const map: Map<string, MiniAppGrantDecision> | undefined = this.mem.grants.get(appId);
    if (map === undefined) return null;
    const decision: MiniAppGrantDecision | undefined = map.get(permission);
    return decision === undefined ? null : decision;
  }

  listAll(): Promise<MiniAppRecord[]> {
    return this.mem.listAll();
  }

  getById(id: string): Promise<MiniAppRecord | null> {
    return this.mem.getById(id);
  }

  async saveGenerated(
    output: MiniAppGeneratedOutput,
    sourceConversationId: string | null = null,
    sourceMessageId: string | null = null,
  ): Promise<MiniAppRecord> {
    const record: MiniAppRecord = await this.mem.saveGenerated(
      output, sourceConversationId, sourceMessageId);
    await this.io.persistIndex(this.mem);
    await this.io.persistHtml(this.mem);
    return record;
  }

  async saveRevision(
    appId: string,
    output: MiniAppGeneratedOutput,
    expectedBaseVersion: number | null = null,
    sourceMessageId: string | null = null,
    changeNote: string | null = null,
  ): Promise<MiniAppRecord | null> {
    const record: MiniAppRecord | null = await this.mem.saveRevision(
      appId, output, expectedBaseVersion, sourceMessageId, changeNote);
    if (record !== null) {
      await this.io.persistIndex(this.mem);
      await this.io.persistHtml(this.mem);
    }
    return record;
  }

  async markRun(id: string): Promise<void> {
    await this.mem.markRun(id);
    await this.io.persistIndex(this.mem);
  }

  async setPinned(id: string, pinned: boolean): Promise<void> {
    await this.mem.setPinned(id, pinned);
    await this.io.persistIndex(this.mem);
  }

  async rename(id: string, title: string, description: string): Promise<void> {
    await this.mem.rename(id, title, description);
    await this.io.persistIndex(this.mem);
  }

  async updateBoardSummary(id: string, summary: string): Promise<void> {
    await this.mem.updateBoardSummary(id, summary);
    await this.io.persistIndex(this.mem);
  }

  async delete(id: string): Promise<void> {
    await this.mem.delete(id);
    await this.io.persistIndex(this.mem);
    await this.io.persistHtml(this.mem);
    await this.io.persistGrants(this.mem);
    await this.io.persistAudit(this.mem);
    await this.io.persistShared(this.mem);
  }

  listVersions(appId: string): Promise<MiniAppVersionRecord[]> {
    return this.mem.listVersions(appId);
  }

  getVersion(appId: string, versionNumber: number): Promise<MiniAppVersionRecord | null> {
    return this.mem.getVersion(appId, versionNumber);
  }

  async restoreVersion(appId: string, versionNumber: number): Promise<MiniAppRecord | null> {
    const record: MiniAppRecord | null = await this.mem.restoreVersion(appId, versionNumber);
    if (record !== null) {
      await this.io.persistIndex(this.mem);
      await this.io.persistHtml(this.mem);
    }
    return record;
  }

  getGrant(appId: string, permission: string): Promise<MiniAppGrantDecision | null> {
    return this.mem.getGrant(appId, permission);
  }

  async setGrant(appId: string, permission: string, decision: MiniAppGrantDecision): Promise<void> {
    await this.mem.setGrant(appId, permission, decision);
    await this.io.persistGrants(this.mem);
  }

  async appendAudit(
    appId: string,
    method: string,
    permission: MiniAppPermission,
    summary: string,
    payload: string,
  ): Promise<void> {
    await this.mem.appendAudit(appId, method, permission, summary, payload);
    await this.io.persistAudit(this.mem);
  }

  listAudit(appId: string, limit: number = 100): Promise<MiniAppAuditEntry[]> {
    return this.mem.listAudit(appId, limit);
  }

  sharedGet(appId: string, namespace: string, key: string): Promise<JsonValue | null> {
    return this.mem.sharedGet(appId, namespace, key);
  }

  async sharedSet(appId: string, namespace: string, key: string, value: JsonValue): Promise<void> {
    await this.mem.sharedSet(appId, namespace, key, value);
    await this.io.persistShared(this.mem);
  }

  async sharedRemove(appId: string, namespace: string, key: string): Promise<void> {
    await this.mem.sharedRemove(appId, namespace, key);
    await this.io.persistShared(this.mem);
  }
}

export const createFileMiniAppRepository = (
  opts: MiniAppFilesOpts,
): Promise<MiniAppFileRepository> => {
  const io: MiniAppFilesIo = new MiniAppFilesIo(opts.baseDir);
  const mem: MemoryMiniAppRepository = createMemoryMiniAppRepository({
    sha256Hex: opts.sha256Hex,
    idGen: opts.idGen,
    clock: opts.clock,
  });
  return io.load(mem).then((): MiniAppFileRepository =>
    new FileMiniAppRepository(io, mem));
};
