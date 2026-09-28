// memory_import_export — MemoryImportExportManager.kt 移植(D-085e)
//
// Android 基准: core/memory/export/MemoryImportExportManager.kt(全文 116 行)
//   - exportTo(:16-56):resolveRoot(name=='AmberAgentMemory' 否则嵌套)+
//     六 kind × memories|archive 目录 + 逐记录 .mem.md + manifest.json
//     (JsonInstant map<String,String> 键序 version/exported_at/count/
//     active_count/archived_count/event_count,值全字符串)+ events ndjson
//     (MemoryEvent serializer 声明序,encodeDefaults+explicitNulls)
//   - importFrom(:58-77):resolveExistingRoot(manifest.json 在→本目录,
//     否则嵌套存在→嵌套)+ walk memories|archive 的 *.mem.md → decode →
//     首个 scope+kind+normalize(content) 匹配 → upsertRecord(id 沿用或 0)
//   - fileName(:79-91):updatedAt 本地日期 + '_' + id + '_' + slug
//     (lowercase,[^\p{L}\p{N}]+→'-',trim '-',take 48,空→'memory')+ '.mem.md'
// 偏差:java.io.File → MemoryExportFs 依赖注入(walkTopDown 顺序 = fs 实现序,
//   Android 为 OS 目录序 — 仅影响多文件导入的处理序,不影集内容)

import type { MemoryEvent, MemoryKind, MemoryRecord } from './memory_models.ts';
import { decodeMemoryFrontmatter, encodeMemoryFrontmatter } from './memory_frontmatter.ts';

const MEMORY_KIND_WIRES: MemoryKind[] = ['user', 'feedback', 'project', 'reference', 'routine', 'note'];

// ===== FS 抽象(java.io.File 子集) =====

export interface MemoryExportFs {
  mkdirs: (path: string) => Promise<void>;
  writeText: (path: string, content: string) => Promise<void>;
  readText: (path: string) => Promise<string>;
  exists: (path: string) => Promise<boolean>;
  // 递归列目录下全部文件路径(walkTopDown 等价)
  walkFiles: (path: string) => Promise<string[]>;
}

export interface MemoryImportExportDeps {
  fs: MemoryExportFs;
  getAllRecords: () => Promise<MemoryRecord[]>;
  getRecentEvents: (limit: number) => Promise<MemoryEvent[]>;
  upsertRecord: (record: MemoryRecord) => Promise<MemoryRecord>;
  now?: () => number;
}

export interface MemoryExportResult {
  root: string;
  memoryCount: number;
  archivedCount: number;
  eventCount: number;
}

export interface MemoryImportResult {
  root: string;
  importedCount: number;
}

const joinPath = (dir: string, name: string): string =>
  dir.endsWith('/') ? `${dir}${name}` : `${dir}/${name}`;

const dirName = (path: string): string => {
  const idx: number = path.replace(/\/+$/, '').lastIndexOf('/');
  return idx < 0 ? path : path.replace(/\/+$/, '').substring(idx + 1);
};

// resolveRoot(:93-94)
export const resolveMemoryExportRoot = (directory: string): string =>
  dirName(directory) === 'AmberAgentMemory' ? directory : joinPath(directory, 'AmberAgentMemory');

// resolveExistingRoot(:96-100)— 需 fs.exists,内部化在 importFrom
const resolveExistingRoot = async (root: string, fs: MemoryExportFs): Promise<string> => {
  if (await fs.exists(joinPath(root, 'manifest.json'))) return root;
  const nested: string = joinPath(root, 'AmberAgentMemory');
  return await fs.exists(nested) ? nested : root;
};

// fileName(:79-91)
export const memoryExportFileName = (record: MemoryRecord, now: () => number): string => {
  const ms: number = record.updatedAt > 0 ? record.updatedAt : now();
  const d: Date = new Date(ms);
  const pad2 = (n: number): string => String(n).padStart(2, '0');
  const date: string = `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
  const slugRaw: string = record.content.toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 48);
  const slug: string = slugRaw.length > 0 ? slugRaw : 'memory';
  return `${date}_${record.id}_${slug}.mem.md`;
};

// normalize(:102-103)— 与 MemoryCandidateFilter 同式
const normalize = (text: string): string => {
  let out: string = '';
  for (const ch of text.toLowerCase()) {
    if (/[\p{L}\p{N}]/u.test(ch)) out += ch;
    if (out.length >= 200) break;
  }
  return out;
};

// Instant.now().toString():毫秒零 → 省略小数(对齐 JS toISOString 差异)
const instantNowText = (nowMs: number): string => {
  const iso: string = new Date(nowMs).toISOString();
  return iso.endsWith('.000Z') ? iso.substring(0, iso.length - 5) + 'Z' : iso;
};

// MemoryEvent serializer 形状(MemoryModels.kt:115-126 声明序;
//   JsonInstant encodeDefaults=true + explicitNulls → 全字段恒在)
export const memoryEventToJsonText = (event: MemoryEvent): string => JSON.stringify({
  id: event.id,
  type: event.type,
  conversationId: event.conversationId,
  memoryId: event.memoryId,
  candidateId: event.candidateId,
  modelId: event.modelId,
  message: event.message,
  durationMs: event.durationMs,
  messageCount: event.messageCount,
  createdAt: event.createdAt,
});

// exportTo(:16-56)
export const exportMemoriesTo = async (
  directory: string, deps: MemoryImportExportDeps,
): Promise<MemoryExportResult> => {
  const nowFn: () => number = deps.now ?? ((): number => Date.now());
  const root: string = resolveMemoryExportRoot(directory);
  const records: MemoryRecord[] = (await deps.getAllRecords()).filter(
    (record: MemoryRecord): boolean => record.kind !== 'topic');
  const events: MemoryEvent[] = await deps.getRecentEvents(500);
  for (const kind of MEMORY_KIND_WIRES) {
    await deps.fs.mkdirs(joinPath(root, `memories/${kind}`));
    await deps.fs.mkdirs(joinPath(root, `archive/${kind}`));
  }
  for (const record of records) {
    const baseDir: string = record.archived
      ? `archive/${record.kind}` : `memories/${record.kind}`;
    const file: string = joinPath(root, `${baseDir}/${memoryExportFileName(record, nowFn)}`);
    const parent: string = file.substring(0, file.lastIndexOf('/'));
    await deps.fs.mkdirs(parent);
    await deps.fs.writeText(file, encodeMemoryFrontmatter(record, { now: nowFn }));
  }
  const manifest: string = JSON.stringify({
    version: '1',
    exported_at: instantNowText(nowFn()),
    count: String(records.length),
    active_count: String(records.filter((r: MemoryRecord): boolean => !r.archived).length),
    archived_count: String(records.filter((r: MemoryRecord): boolean => r.archived).length),
    event_count: String(events.length),
  });
  await deps.fs.writeText(joinPath(root, 'manifest.json'), manifest);
  await deps.fs.mkdirs(joinPath(root, 'events'));
  await deps.fs.writeText(
    joinPath(root, 'events/memory_events.ndjson'),
    events.map(memoryEventToJsonText).join('\n'));
  return {
    root,
    memoryCount: records.length,
    archivedCount: records.filter((r: MemoryRecord): boolean => r.archived).length,
    eventCount: events.length,
  };
};

// importFrom(:58-77)
export const importMemoriesFrom = async (
  root: string, deps: MemoryImportExportDeps,
): Promise<MemoryImportResult> => {
  const resolvedRoot: string = await resolveExistingRoot(root, deps.fs);
  // 去重索引须可变:固定快照下,前一个文件插入的记录不进索引,
  // 导入包内相同内容的文件会互相重复插入(id=0 双插)
  const known: MemoryRecord[] = (await deps.getAllRecords()).filter(
    (record: MemoryRecord): boolean => record.kind !== 'topic');
  let imported: number = 0;
  for (const dir of [joinPath(resolvedRoot, 'memories'), joinPath(resolvedRoot, 'archive')]) {
    if (!await deps.fs.exists(dir)) continue;
    const files: string[] = await deps.fs.walkFiles(dir);
    for (const file of files) {
      if (!file.endsWith('.mem.md')) continue;
      const decoded: MemoryRecord = decodeMemoryFrontmatter(await deps.fs.readText(file));
      const existing: MemoryRecord | undefined = known.find(
        (record: MemoryRecord): boolean =>
          record.scope === decoded.scope
          && record.kind === decoded.kind
          && normalize(record.content) === normalize(decoded.content));
      const inserted: MemoryRecord = await deps.upsertRecord({
        ...decoded,
        id: existing !== undefined ? existing.id : 0,
      });
      if (existing === undefined) {
        // 新插入的记录以 upsert 返回值(含已分配 id)进入去重索引 —
        // 用 decoded(id=0) 会让导入包内重复文件再次按新增插入
        known.push(inserted);
      }
      imported++;
    }
  }
  return { root: resolvedRoot, importedCount: imported };
};
