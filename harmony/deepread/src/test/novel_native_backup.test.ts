import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { randomBytes } from 'node:crypto';
import { deflateRawSync, inflateRawSync } from 'node:zlib';
import {
  exportNovelNativeBackup, importNovelNativeBackup, NOVEL_NATIVE_BACKUP_MANIFEST_PATH,
} from '../main/ets/novel/native_backup.ts';
import type { NovelNativeBackupMetadata } from '../main/ets/novel/native_backup.ts';
import type {
  NovelWorkspaceArchiveCodec, NovelWorkspaceArchiveEntry, NovelWorkspaceArchiveFile,
} from '../main/ets/novel/workspace_exchange.ts';

const encoder = new TextEncoder();

class MemoryCodec implements NovelWorkspaceArchiveCodec {
  files: NovelWorkspaceArchiveFile[] = [];
  extraEntries: NovelWorkspaceArchiveEntry[] = [];
  extracted: string[] = [];
  async list(_archive: Uint8Array): Promise<NovelWorkspaceArchiveEntry[]> {
    return this.files.map(file => ({ path: file.path, compressedSize: file.bytes.length,
      uncompressedSize: file.bytes.length, isDirectory: false })).concat(this.extraEntries);
  }
  async extract(_archive: Uint8Array, path: string): Promise<Uint8Array> {
    this.extracted.push(path);
    return this.files.find(file => file.path === path)!.bytes;
  }
  async create(files: NovelWorkspaceArchiveFile[]): Promise<Uint8Array> {
    this.files = files;
    return new Uint8Array([80, 75]);
  }
}

const metadata = (): NovelNativeBackupMetadata => ({
  projectId: 'novel-project', title: '完整小说 🌙', schemaVersion: 4, activeBranch: 'alternate',
  state: { branchId: 'alternate', head: 'commit-2', treeDigest: 'tree-2' }, createdAt: 123,
});

const fixture = (): NovelWorkspaceArchiveFile[] => [
  { path: 'manifest.yaml', bytes: encoder.encode('public manifest') },
  { path: 'project.md', bytes: encoder.encode('# 完整小说') },
  { path: '.amber/project-state.json', bytes: encoder.encode(JSON.stringify({
    schemaVersion: 4, activeBranch: 'alternate', chapterPlots: [{ chapterId: 'chapter-1', stale: true }],
    discussionArchives: [{ summary: '已确认摘要', sourceMessageIds: ['m1'] }],
  })) },
  { path: '.amber/branches/main.json', bytes: encoder.encode(JSON.stringify({ chapters: [{ id: 'chapter-1', body: '原文' }] })) },
  { path: '.amber/branches/alternate.json', bytes: encoder.encode(JSON.stringify({ chapters: [{ id: 'chapter-1', body: '分支原文' }] })) },
  { path: '.amber/commits/commit-2.json', bytes: encoder.encode('full historical snapshot') },
  { path: '.amber/messages.json', bytes: encoder.encode(JSON.stringify([{ id: 'm1', role: 'assistant',
    parts: [{ type: 'tool_call', id: 'tool-1', name: 'edit_plan', arguments: { text: '计划' } },
      { type: 'tool_result', toolCallId: 'tool-1', result: { proposalId: 'proposal-1' } }],
  }])) },
  { path: '.amber/jobs.json', bytes: encoder.encode(JSON.stringify([{ status: 'paused', stage: 'review', receipts: ['r1'] }])) },
  { path: '.amber/proposals.json', bytes: encoder.encode(JSON.stringify([{ id: 'proposal-1', status: 'pending', patches: ['plan.md'] }])) },
  { path: '.amber/undo/alternate.json', bytes: encoder.encode(JSON.stringify({ head: 'commit-1', treeDigest: 'tree-1' })) },
  { path: '.amber/unknown-extension.bin', bytes: new Uint8Array([0, 255, 128, 3, 0, 254]) },
  { path: 'assets/.unknown', bytes: new Uint8Array([255, 255, 0]) },
];

const make = async (): Promise<MemoryCodec> => {
  const codec = new MemoryCodec();
  await exportNovelNativeBackup(codec, metadata(), fixture());
  return codec;
};

const editManifest = (codec: MemoryCodec, edit: (manifest: Record<string, unknown>) => void): void => {
  const file = codec.files.find(value => value.path === NOVEL_NATIVE_BACKUP_MANIFEST_PATH)!;
  const manifest = JSON.parse(new TextDecoder().decode(file.bytes));
  edit(manifest);
  file.bytes = encoder.encode(JSON.stringify(manifest));
};

test('native backup roundtrip preserves all private state, tools, jobs, proposals, undo and unknown bytes', async () => {
  const source = fixture();
  const codec = new MemoryCodec();
  const archive = await exportNovelNativeBackup(codec, metadata(), source);
  const restored = await importNovelNativeBackup(codec, archive);
  assert.equal(restored.projectId, 'novel-project');
  assert.equal(restored.manifest.title, '完整小说 🌙');
  assert.deepEqual(restored.manifest.state, metadata().state);
  assert.equal(restored.manifest.checksumAlgorithm, 'fnv1a32');
  assert.deepEqual(restored.files, source);
  source[0].bytes[0] = 0;
  assert.notEqual(codec.files[0].bytes[0], 0, 'export freezes caller buffers');
  restored.files[0].bytes[0] = 0;
  assert.notEqual(codec.files[0].bytes[0], 0, 'import returns independent buffers');
});

test('native backup rejects tampered private file and wrong checksum', async () => {
  const codec = await make();
  codec.files.find(file => file.path === '.amber/jobs.json')!.bytes[0] ^= 1;
  await assert.rejects(importNovelNativeBackup(codec, new Uint8Array([1])), /校验失败/);
  const other = await make();
  editManifest(other, manifest => { (manifest.entries as { checksum: string }[])[0].checksum = '00000000'; });
  await assert.rejects(importNovelNativeBackup(other, new Uint8Array([1])), /校验失败/);
});

test('native backup rejects unknown format version and algorithm before extracting payload', async () => {
  for (const field of ['version', 'checksumAlgorithm', 'format']) {
    const codec = await make();
    editManifest(codec, manifest => { manifest[field] = field === 'version' ? 2 : 'future-format'; });
    await assert.rejects(importNovelNativeBackup(codec, new Uint8Array([1])), /不受支持/);
    assert.deepEqual(codec.extracted, [NOVEL_NATIVE_BACKUP_MANIFEST_PATH]);
  }
});

test('native backup rejects missing, extra and duplicate declared inventory', async () => {
  const missing = await make();
  missing.files.pop();
  await assert.rejects(importNovelNativeBackup(missing, new Uint8Array([1])), /缺少/);
  const extra = await make();
  extra.files.push({ path: 'undeclared.bin', bytes: new Uint8Array([1]) });
  await assert.rejects(importNovelNativeBackup(extra, new Uint8Array([1])), /清单与 ZIP 不一致/);
  const duplicate = await make();
  editManifest(duplicate, manifest => {
    const entries = manifest.entries as unknown[];
    entries.push(entries[0]);
  });
  await assert.rejects(importNovelNativeBackup(duplicate, new Uint8Array([1])), /重复/);
});

test('native backup rejects unsafe or duplicate paths before extracting files', async () => {
  for (const path of ['../outside', '.amber/../outside', '/absolute', 'C:/drive', 'a\\b', 'a//b', 'a/./b', 'a\0b']) {
    const codec = await make();
    codec.files.push({ path, bytes: new Uint8Array([1]) });
    await assert.rejects(importNovelNativeBackup(codec, new Uint8Array([1])), /路径无效/);
    assert.deepEqual(codec.extracted, []);
  }
  const duplicate = await make();
  duplicate.files.push(duplicate.files[0]);
  await assert.rejects(importNovelNativeBackup(duplicate, new Uint8Array([1])), /重复/);
  const collision = await make();
  collision.files.push({ path: '.amber', bytes: new Uint8Array([1]) });
  await assert.rejects(importNovelNativeBackup(collision, new Uint8Array([1])), /文件与目录冲突/);
});

test('native backup validates directory conflicts in both archive orders', async () => {
  const codec = await make();
  codec.files.push({ path: 'directory', bytes: new Uint8Array([1]) });
  codec.extraEntries.push({ path: 'directory/child/', compressedSize: 0, uncompressedSize: 0, isDirectory: true });
  await assert.rejects(importNovelNativeBackup(codec, new Uint8Array([1])), /文件与目录冲突/);
  codec.files.reverse();
  await assert.rejects(importNovelNativeBackup(codec, new Uint8Array([1])), /文件与目录冲突/);
});

test('native backup enforces total, per entry, count and actual archive budgets', async () => {
  const codec = await make();
  const limits = { maxEntries: 1024, maxCompressedBytes: 16000000, maxExpandedBytes: 64000000,
    maxEntryCompressedBytes: 8000000, maxEntryExpandedBytes: 16000000 };
  for (const values of [
    { maxEntries: 1 }, { maxCompressedBytes: 1 }, { maxExpandedBytes: 1 },
    { maxEntryCompressedBytes: 1 }, { maxEntryExpandedBytes: 1 }, { maxEntries: 0 },
  ]) {
    await assert.rejects(importNovelNativeBackup(codec, new Uint8Array([1, 2]), { ...limits, ...values }), /限制/);
  }
  const exportCodec = new MemoryCodec();
  await assert.rejects(exportNovelNativeBackup(exportCodec, metadata(), fixture(), { ...limits, maxEntries: 1 }), /限制/);
  assert.equal(exportCodec.files.length, 0, 'invalid export is rejected before codec creation');
});

test('native backup rejects invalid metadata and reserved source manifest', async () => {
  for (const data of [
    { ...metadata(), projectId: '../outside' }, { ...metadata(), schemaVersion: -1 },
    { ...metadata(), state: { branchId: 'other', head: 'h', treeDigest: 't' } },
  ]) await assert.rejects(exportNovelNativeBackup(new MemoryCodec(), data, fixture()), /无效|不一致/);
  await assert.rejects(exportNovelNativeBackup(new MemoryCodec(), metadata(), [
    { path: NOVEL_NATIVE_BACKUP_MANIFEST_PATH, bytes: new Uint8Array([1]) },
  ]), /路径冲突/);
  const codec = await make();
  editManifest(codec, manifest => { manifest.state = null; });
  await assert.rejects(importNovelNativeBackup(codec, new Uint8Array([1])), /state 无效/);
});

test('native backup checks extracted manifest and payload lengths independently of their checksums', async () => {
  class ShortExtractCodec extends MemoryCodec {
    shortPath: string = '';
    async extract(archive: Uint8Array, path: string): Promise<Uint8Array> {
      const bytes = await super.extract(archive, path);
      return path === this.shortPath ? bytes.slice(0, -1) : bytes;
    }
  }
  for (const path of [NOVEL_NATIVE_BACKUP_MANIFEST_PATH, '.amber/unknown-extension.bin']) {
    const codec = new ShortExtractCodec();
    await exportNovelNativeBackup(codec, metadata(), fixture());
    codec.shortPath = path;
    await assert.rejects(importNovelNativeBackup(codec, new Uint8Array([1])), /大小不匹配|校验失败/);
  }
});

test('native backup rejects malformed manifest and unsafe sizes before returning restore input', async () => {
  const malformed = await make();
  malformed.files.find(file => file.path === NOVEL_NATIVE_BACKUP_MANIFEST_PATH)!.bytes = encoder.encode('{bad json');
  await assert.rejects(importNovelNativeBackup(malformed, new Uint8Array([1])), /JSON 无效/);
  for (const size of [-1, NaN, Infinity, 0.5]) {
    const codec = await make();
    codec.extraEntries.push({ path: 'size.bin', isDirectory: false, compressedSize: size, uncompressedSize: 0 });
    await assert.rejects(importNovelNativeBackup(codec, new Uint8Array([1])), /大小超过限制/);
    assert.deepEqual(codec.extracted, []);
  }
});

test('native export validates actual ZIP compressed entry sizes and returns a re-importable package', async () => {
  interface ZipEntry {
    name: string;
    method: number;
    compressedSize: number;
    uncompressedSize: number;
    localHeaderOffset: number;
  }
  interface ZipWriterPort {
    buildZipBytes: (files: { name: string; data: Uint8Array; compression: 'deflate' }[],
      deflate: (bytes: Uint8Array) => Promise<Uint8Array>, time: number, date: number) => Promise<Uint8Array>;
  }
  interface ZipReaderPort {
    readZipEntries: (archive: Uint8Array) => ZipEntry[];
    readZipEntryBytes: (archive: Uint8Array, entry: ZipEntry,
      inflate: (bytes: Uint8Array, expectedSize: number) => Promise<Uint8Array>) => Promise<Uint8Array>;
  }
  // 执行真实 ZIP 实现，但跨模块测试端口不扩张 deepread 的编译 graph 到 Chat aliases。
  const writerPath: string = '../../../chat/src/main/ets/chat/workspace_artifacts.ts';
  const readerPath: string = '../../../chat/src/main/ets/chat/zip_archive.ts';
  const writer: ZipWriterPort = await import(writerPath) as ZipWriterPort;
  const reader: ZipReaderPort = await import(readerPath) as ZipReaderPort;
  const codec: NovelWorkspaceArchiveCodec = {
    async create(files: NovelWorkspaceArchiveFile[]): Promise<Uint8Array> {
      return writer.buildZipBytes(files.map(file => ({ name: file.path, data: file.bytes, compression: 'deflate' as const })),
        async bytes => new Uint8Array(deflateRawSync(bytes)), 0, 0);
    },
    async list(archive: Uint8Array): Promise<NovelWorkspaceArchiveEntry[]> {
      return reader.readZipEntries(archive).map(entry => ({ path: entry.name, compressedSize: entry.compressedSize,
        uncompressedSize: entry.uncompressedSize, isDirectory: entry.name.endsWith('/') }));
    },
    async extract(archive: Uint8Array, path: string): Promise<Uint8Array> {
      return reader.readZipEntryBytes(archive, reader.readZipEntries(archive).find(entry => entry.name === path)!,
        async bytes => new Uint8Array(inflateRawSync(bytes)));
    },
  };
  const files = [{ path: '.amber/blob.bin', bytes: new Uint8Array(randomBytes(4096)) }];
  const limits = { maxEntries: 100, maxCompressedBytes: 100000, maxExpandedBytes: 100000,
    maxEntryCompressedBytes: 2048, maxEntryExpandedBytes: 8192 };
  await assert.rejects(exportNovelNativeBackup(codec, metadata(), files, limits), /大小超过限制/);
  const acceptedLimits = { ...limits, maxEntryCompressedBytes: 5000 };
  const archive = await exportNovelNativeBackup(codec, metadata(), files, acceptedLimits);
  assert.ok((await codec.list(archive)).find(entry => entry.path === '.amber/blob.bin')!.compressedSize > 2048);
  assert.deepEqual((await importNovelNativeBackup(codec, archive, acceptedLimits)).files, files);
});
