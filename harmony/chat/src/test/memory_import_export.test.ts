// memory_frontmatter + memory_import_export + upsertRecord 测试(D-085e)
// 锚点:MemoryFrontmatterCodec.kt / MemoryImportExportManager.kt / MemoryRepository.kt:135-143
import assert from 'node:assert/strict';
import test from 'node:test';
import type { MemoryEvent, MemoryRecord } from '../main/ets/chat/memory_models.ts';
import { makeMemoryEvent, makeMemoryRecord } from '../main/ets/chat/memory_models.ts';
import {
  decodeMemoryFrontmatter, encodeMemoryFrontmatter,
} from '../main/ets/chat/memory_frontmatter.ts';
import type { MemoryExportFs, MemoryImportExportDeps } from '../main/ets/chat/memory_import_export.ts';
import {
  exportMemoriesTo, importMemoriesFrom, memoryEventToJsonText,
  memoryExportFileName, resolveMemoryExportRoot,
} from '../main/ets/chat/memory_import_export.ts';
import { upsertMemoryRecord } from '../main/ets/chat/memory_write.ts';

const NOW = new Date(2026, 6, 28, 12, 0, 0).getTime();
const nowFn = (): number => NOW;

const record = (over: Partial<MemoryRecord>): MemoryRecord => makeMemoryRecord({
  id: over.id ?? 1, content: over.content ?? '内容',
  scope: over.scope ?? 'long_term', kind: over.kind ?? 'note',
  assistantId: over.assistantId ?? '__long_term__',
  sourceConversationId: over.sourceConversationId ?? null,
  sourceMessageIds: over.sourceMessageIds ?? [],
  supersedesIds: over.supersedesIds ?? [],
  expiresAt: over.expiresAt ?? null,
  confidence: over.confidence ?? 1, pinned: over.pinned ?? false,
  archived: over.archived ?? false,
  createdAt: over.createdAt ?? NOW, updatedAt: over.updatedAt ?? NOW,
  lastUsedAt: over.lastUsedAt ?? null,
});

// ===== frontmatter encode =====

test('encode: 键序逐字 + quote 转义 + Float 文本 + 可选行省略', () => {
  const text: string = encodeMemoryFrontmatter(record({
    id: 7, content: '含 "引号" 与 \\反斜杠', scope: 'core', kind: 'user',
    confidence: 1, sourceConversationId: 'c-1', sourceMessageIds: ['m1', 'm"2'],
    supersedesIds: [3, 4], pinned: true, createdAt: NOW, updatedAt: NOW,
  }), { now: nowFn });
  const lines: string[] = text.split('\n');
  assert.equal(lines[0], '---');
  assert.equal(lines[1], 'id: "7"');
  assert.equal(lines[2], 'kind: "user"');
  assert.equal(lines[3], 'scope: "core"');
  assert.equal(lines[4], 'confidence: 1.0'); // Float 整数值 → N.0
  // 本地时区 ISO_OFFSET(测试环境时区无关 — 只验形状)
  assert.match(lines[5], /^created_at: "2026-07-28T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}"$/);
  assert.equal(lines[7], 'source_conversation_id: "c-1"');
  assert.equal(lines[8], 'source_message_ids: ["m1", "m\\"2"]');
  assert.equal(lines[9], 'supersedes_ids: [3, 4]');
  assert.equal(lines[10], 'pinned: true');
  assert.equal(lines[11], 'archived: false');
  assert.equal(lines[12], 'reinforcement_count: 0');
  assert.equal(lines[13], '---');
  assert.equal(lines[14], '');
  assert.equal(lines[15], '含 "引号" 与 \\反斜杠');
  // expires_at 缺失(null)→ 无该行
  assert.ok(!text.includes('expires_at'));
});

test('encode: expiresAt 非 null → 该行在 updated_at 之后;confidence 非整数原样', () => {
  const text: string = encodeMemoryFrontmatter(record({
    expiresAt: NOW + 1000, confidence: 0.85,
  }), { now: nowFn });
  const lines: string[] = text.split('\n');
  assert.equal(lines[4], 'confidence: 0.85');
  assert.match(lines[7], /^expires_at: ".*"$/);
});

// ===== frontmatter decode =====

test('decode: encode 往返(转义/列表/可选字段)+ assistantId 按 scope 桶重导', () => {
  const original: MemoryRecord = record({
    id: 9, content: '往返 "内容" 甲乙', scope: 'short_term', kind: 'project',
    confidence: 0.7, pinned: true, archived: false,
    sourceConversationId: 'conv-x', sourceMessageIds: ['a', 'b"c'],
    supersedesIds: [1, 2], expiresAt: NOW + 5000,
  });
  const decoded: MemoryRecord = decodeMemoryFrontmatter(
    encodeMemoryFrontmatter(original, { now: nowFn }), { now: nowFn });
  assert.equal(decoded.id, 9);
  assert.equal(decoded.content, '往返 "内容" 甲乙');
  assert.equal(decoded.scope, 'short_term');
  assert.equal(decoded.kind, 'project');
  assert.equal(decoded.assistantId, '__short_term__'); // bucketForScope 重导
  assert.equal(decoded.confidence, 0.7);
  assert.equal(decoded.pinned, true);
  assert.deepEqual(decoded.sourceMessageIds, ['a', 'b"c']);
  assert.deepEqual(decoded.supersedesIds, [1, 2]);
  assert.equal(decoded.expiresAt, NOW + 5000);
  assert.equal(decoded.createdAt, NOW);
  assert.equal(decoded.lastUsedAt, null);
});

test('decode: 缺分隔 → require 抛错;缺时间行 → now;非法 confidence → 1', () => {
  assert.throws((): void => { decodeMemoryFrontmatter('没有分隔符'); },
    /Invalid memory frontmatter/);
  const minimal: string = '---\nid: "3"\nscope: "core"\n---\n\n内容体';
  const decoded: MemoryRecord = decodeMemoryFrontmatter(minimal, { now: nowFn });
  assert.equal(decoded.createdAt, NOW);
  assert.equal(decoded.updatedAt, NOW);
  assert.equal(decoded.expiresAt, null);
  assert.equal(decoded.kind, 'note'); // 缺失 → fromWireName 兜底
  const badConf: string = '---\nconfidence: "abc"\n---\n\n体';
  assert.equal(decodeMemoryFrontmatter(badConf, { now: nowFn }).confidence, 1);
  // 无冒号的行跳过;同键后值覆盖(toMap)
  const dup: string = '---\n无效行\nid: "1"\nid: "5"\n---\n\n体';
  assert.equal(decodeMemoryFrontmatter(dup, { now: nowFn }).id, 5);
});

// ===== fileName / resolveRoot =====

test('fileName: 日期_id_slug.mem.md(slug 归一/take48/空→memory)', () => {
  const name: string = memoryExportFileName(record({
    id: 12, content: 'User Likes Tea!! 偏好', updatedAt: NOW,
  }), nowFn);
  assert.equal(name, '2026-07-28_12_user-likes-tea-偏好.mem.md');
  const blank: string = memoryExportFileName(record({ id: 1, content: '!!!' }), nowFn);
  assert.equal(blank, '2026-07-28_1_memory.mem.md');
  const long: string = memoryExportFileName(record({ id: 2, content: 'a'.repeat(100) }), nowFn);
  assert.ok(long.length === '2026-07-28_2_'.length + 48 + '.mem.md'.length);
  assert.equal(resolveMemoryExportRoot('/tmp/x'), '/tmp/x/AmberAgentMemory');
  assert.equal(resolveMemoryExportRoot('/tmp/AmberAgentMemory'), '/tmp/AmberAgentMemory');
});

// ===== 虚拟 FS + 导出/导入 =====

class MemFs implements MemoryExportFs {
  files: Map<string, string> = new Map();
  dirs: Set<string> = new Set();

  mkdirs(path: string): Promise<void> {
    this.dirs.add(path);
    return Promise.resolve();
  }

  writeText(path: string, content: string): Promise<void> {
    this.files.set(path, content);
    return Promise.resolve();
  }

  readText(path: string): Promise<string> {
    const v: string | undefined = this.files.get(path);
    if (v === undefined) return Promise.reject(new Error(`no file: ${path}`));
    return Promise.resolve(v);
  }

  exists(path: string): Promise<boolean> {
    // File.exists() 语义:目录(含仅有后裔的路径)亦为存在
    if (this.files.has(path) || this.dirs.has(path)) return Promise.resolve(true);
    const prefix: string = `${path}/`;
    for (const key of this.files.keys()) {
      if (key.startsWith(prefix)) return Promise.resolve(true);
    }
    for (const d of this.dirs) {
      if (d.startsWith(prefix)) return Promise.resolve(true);
    }
    return Promise.resolve(false);
  }

  walkFiles(path: string): Promise<string[]> {
    const prefix: string = path.endsWith('/') ? path : `${path}/`;
    const out: string[] = [];
    for (const key of this.files.keys()) {
      if (key.startsWith(prefix)) out.push(key);
    }
    return Promise.resolve(out);
  }
}

const depsWith = (fs: MemFs, records: MemoryRecord[], events: MemoryEvent[],
  upsert?: (r: MemoryRecord) => void): MemoryImportExportDeps => ({
  fs,
  getAllRecords: (): Promise<MemoryRecord[]> => Promise.resolve(records),
  getRecentEvents: (limit: number): Promise<MemoryEvent[]> => Promise.resolve(events.slice(0, limit)),
  upsertRecord: (r: MemoryRecord): Promise<MemoryRecord> => {
    upsert?.(r);
    return Promise.resolve(r);
  },
  now: nowFn,
});

test('exportTo: 目录结构 + manifest 键序/字符串值 + ndjson 形状 + 结果计数', async () => {
  const fs = new MemFs();
  const records: MemoryRecord[] = [
    record({ id: 1, kind: 'user', archived: false }),
    record({ id: 2, kind: 'project', archived: true, content: '归档项' }),
  ];
  const events: MemoryEvent[] = [makeMemoryEvent({ type: 'memory_created', memoryId: 1 })];
  const result = await exportMemoriesTo('/out', depsWith(fs, records, events));
  assert.equal(result.root, '/out/AmberAgentMemory');
  assert.equal(result.memoryCount, 2);
  assert.equal(result.archivedCount, 1);
  assert.equal(result.eventCount, 1);
  // 六 kind × 两目录
  assert.ok(fs.dirs.has('/out/AmberAgentMemory/memories/user'));
  assert.ok(fs.dirs.has('/out/AmberAgentMemory/archive/routine'));
  // 记录文件落位(archived → archive/)
  const memFiles: string[] = [...fs.files.keys()].filter((k: string): boolean => k.endsWith('.mem.md'));
  assert.equal(memFiles.length, 2);
  assert.ok(memFiles.some((k: string): boolean => k.startsWith('/out/AmberAgentMemory/memories/user/')));
  assert.ok(memFiles.some((k: string): boolean => k.startsWith('/out/AmberAgentMemory/archive/project/')));
  // manifest:键序 + 值全字符串
  const manifest: string = fs.files.get('/out/AmberAgentMemory/manifest.json') ?? '';
  assert.deepEqual(Object.keys(JSON.parse(manifest) as object),
    ['version', 'exported_at', 'count', 'active_count', 'archived_count', 'event_count']);
  const parsed = JSON.parse(manifest) as Record<string, string>;
  assert.equal(parsed['version'], '1');
  assert.equal(parsed['count'], '2');
  assert.equal(parsed['active_count'], '1');
  // Instant.now() 毫秒零 → 无小数
  assert.match(parsed['exported_at'], /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/);
  // ndjson:serializer 声明序全字段
  const ndjson: string = fs.files.get('/out/AmberAgentMemory/events/memory_events.ndjson') ?? '';
  assert.deepEqual(Object.keys(JSON.parse(ndjson) as object),
    ['id', 'type', 'conversationId', 'memoryId', 'candidateId', 'modelId',
      'message', 'durationMs', 'messageCount', 'createdAt']);
});

test('importFrom: memories|archive 遍历 → decode → 匹配既有(沿用 id)或新增(0)', async () => {
  const fs = new MemFs();
  const existing: MemoryRecord = record({
    id: 5, scope: 'long_term', kind: 'user', content: '已有内容甲乙丙',
  });
  // 一个与既有同内容(归一后),一个全新
  fs.files.set('/root/AmberAgentMemory/memories/user/2026-01-01_0_x.mem.md',
    encodeMemoryFrontmatter(record({ id: 0, kind: 'user', content: '已有内容甲乙丙!!' }), { now: nowFn }));
  fs.files.set('/root/AmberAgentMemory/archive/note/2026-01-02_0_y.mem.md',
    encodeMemoryFrontmatter(record({ id: 0, kind: 'note', content: '全新归档内容' }), { now: nowFn }));
  fs.files.set('/root/AmberAgentMemory/manifest.json', '{}');
  fs.files.set('/root/AmberAgentMemory/memories/user/skip.txt', 'not memory');
  const upserted: MemoryRecord[] = [];
  const result = await importMemoriesFrom('/root',
    depsWith(fs, [existing], [], (r: MemoryRecord): void => { upserted.push(r); }));
  assert.equal(result.root, '/root/AmberAgentMemory'); // manifest 在 → 本目录
  assert.equal(result.importedCount, 2);
  assert.equal(upserted[0].id, 5);   // 匹配既有 → 沿用 id
  assert.equal(upserted[1].id, 0);   // 全新 → 0(自增由 upsertRecord 内部)
  // resolveExistingRoot:manifest 缺 + 嵌套存在 → 嵌套;均无 → 原样
  const fs2 = new MemFs();
  fs2.dirs.add('/bare/AmberAgentMemory');
  const r2 = await importMemoriesFrom('/bare', depsWith(fs2, [], []));
  assert.equal(r2.root, '/bare/AmberAgentMemory');
  assert.equal(r2.importedCount, 0);
});

// ===== upsertRecord =====

test('upsertMemoryRecord: id=0 insert(自增+实体映射)/id 非0 update(updatedAt=now)/未命中 no-op', () => {
  const base: MemoryRecord[] = [record({ id: 4 })];
  // insert 路径:createdAt≤0→now、assistantId 空白→bucket、confidence 钳、supersedes distinct
  const ins = upsertMemoryRecord(base, record({
    id: 0, content: '新入', assistantId: '', createdAt: 0,
    confidence: 1.5, supersedesIds: [2, 2, 3],
  }), NOW);
  assert.equal(ins.record.id, 5); // max(4)+1
  assert.equal(ins.record.assistantId, '__long_term__');
  assert.equal(ins.record.createdAt, NOW);
  assert.equal(ins.record.updatedAt, NOW);
  assert.equal(ins.record.confidence, 1);
  assert.deepEqual(ins.record.supersedesIds, [2, 3]);
  assert.equal(ins.records.length, 2);
  // update 路径:updatedAt 覆盖,createdAt 保留
  const upd = upsertMemoryRecord(ins.records, record({
    id: 4, content: '改写', createdAt: NOW - 5000,
  }), NOW);
  assert.equal(upd.record.updatedAt, NOW);
  assert.equal(upd.record.createdAt, NOW - 5000);
  assert.equal(upd.records.find((r: MemoryRecord): boolean => r.id === 4)?.content, '改写');
  // 未命中 update:记录集不变(@Update 0 行)
  const miss = upsertMemoryRecord(ins.records, record({ id: 99, content: '不存在' }), NOW);
  assert.equal(miss.records.length, 2);
  assert.equal(miss.record.id, 99);
});

test('memoryEventToJsonText: null 字段显式输出(explicitNulls)', () => {
  const text: string = memoryEventToJsonText(makeMemoryEvent({ type: 'extraction_skipped' }));
  const parsed = JSON.parse(text) as Record<string, unknown>;
  assert.equal(parsed['conversationId'], null);
  assert.equal(parsed['memoryId'], null);
  assert.equal(parsed['durationMs'], null);
  assert.equal(parsed['message'], '');
});

test('importFrom: 同一导入包内相同内容的两个文件只新增一次(可变去重索引)', async () => {
  const fs = new MemFs();
  const file: string = encodeMemoryFrontmatter(
    record({ id: 0, kind: 'user', content: '重复内容' }), { now: nowFn });
  fs.files.set('/root/AmberAgentMemory/memories/user/a.mem.md', file);
  fs.files.set('/root/AmberAgentMemory/memories/user/b.mem.md', file);
  fs.files.set('/root/AmberAgentMemory/manifest.json', '{}');
  let seq: number = 10;
  const upserted: MemoryRecord[] = [];
  const result = await importMemoriesFrom('/root', {
    fs,
    getAllRecords: (): Promise<MemoryRecord[]> => Promise.resolve([]),
    getRecentEvents: (): Promise<MemoryEvent[]> => Promise.resolve([]),
    upsertRecord: (r: MemoryRecord): Promise<MemoryRecord> => {
      // 尊重传入 id(沿用既有);0 才分配新 id(真实自增语义)
      const out: MemoryRecord = r.id !== 0 ? { ...r } : { ...r, id: seq++ };
      upserted.push(out);
      return Promise.resolve(out);
    },
    now: nowFn,
  });
  assert.equal(result.importedCount, 2);
  assert.equal(upserted.length, 2);
  assert.equal(upserted[0].id, 10, '第一个文件按新增(id=0→分配 10)');
  assert.equal(upserted[1].id, 10, '第二个文件命中索引 → 沿用 10,不再按 0 新增');
});
