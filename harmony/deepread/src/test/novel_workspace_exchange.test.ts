import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import {
  exportNovelWorkspaceArchive, importNovelWorkspaceArchive,
  NovelWorkspaceArchiveCodec, NovelWorkspaceArchiveEntry, NovelWorkspaceArchiveFile,
  NovelWorkspaceExchangeLimits,
} from '../main/ets/novel/workspace_exchange.ts';

const encoder = new TextEncoder();

const manifest = (): Uint8Array => encoder.encode([
  'format: "amber.novel.workspace"',
  'version: 1',
  'project_id: "cultivation"',
  'title: "修仙日常"',
  'active_branch: "main"',
  'created_at: 1',
  'updated_at: 2',
  '',
].join('\n'));

class MemoryArchiveCodec implements NovelWorkspaceArchiveCodec {
  readonly extracted: string[] = [];
  created: NovelWorkspaceArchiveFile[] = [];

  constructor(
    private readonly entries: NovelWorkspaceArchiveEntry[],
    private readonly contents: Map<string, Uint8Array>,
    private readonly failPath: string = '',
  ) {}

  async list(_archive: Uint8Array): Promise<NovelWorkspaceArchiveEntry[]> {
    return this.entries;
  }

  async extract(_archive: Uint8Array, path: string): Promise<Uint8Array> {
    this.extracted.push(path);
    if (path === this.failPath) throw new Error('interrupted archive');
    const content: Uint8Array | undefined = this.contents.get(path);
    if (content === undefined) throw new Error(`missing ${path}`);
    return content;
  }

  async create(files: NovelWorkspaceArchiveFile[]): Promise<Uint8Array> {
    this.created = files;
    return new Uint8Array([7, 8, 9]);
  }
}

const entry = (path: string, size: number, isDirectory: boolean = false): NovelWorkspaceArchiveEntry => ({
  path,
  compressedSize: size,
  uncompressedSize: size,
  isDirectory,
});

const smallLimits = (): NovelWorkspaceExchangeLimits => ({
  maxEntries: 2,
  maxCompressedBytes: 5,
  maxExpandedBytes: 6,
  maxEntryCompressedBytes: 4,
  maxEntryExpandedBytes: 4,
});

test('workspace exchange imports a complete v1 file set and filters host private files on export', async () => {
  const project: Uint8Array = encoder.encode('# project');
  const codec = new MemoryArchiveCodec(
    [entry('manifest.yaml', manifest().length), entry('project.md', project.length), entry('setting/', 0, true)],
    new Map<string, Uint8Array>([['manifest.yaml', manifest()], ['project.md', project]]),
  );

  const imported = await importNovelWorkspaceArchive(codec, new Uint8Array([1]));
  assert.equal(imported.manifest.projectId, 'cultivation');
  assert.deepEqual(imported.files.map(file => file.path), ['manifest.yaml', 'project.md']);

  const archive = await exportNovelWorkspaceArchive(codec, [
    { path: 'manifest.yaml', bytes: manifest() },
    { path: 'project.md', bytes: project },
    { path: '.amber/ledger.jsonl', bytes: encoder.encode('private') },
  ]);
  assert.deepEqual(archive, new Uint8Array([7, 8, 9]));
  assert.deepEqual(codec.created.map(file => file.path), ['manifest.yaml', 'project.md']);
});

test('workspace exchange accepts a valid highly-compressed entry within its expanded budget', async () => {
  const project: Uint8Array = new Uint8Array(9 * 1024 * 1024);
  const codec = new MemoryArchiveCodec(
    [
      entry('manifest.yaml', manifest().length),
      { path: 'project.md', compressedSize: 1, uncompressedSize: project.length, isDirectory: false },
    ],
    new Map<string, Uint8Array>([['manifest.yaml', manifest()], ['project.md', project]]),
  );

  const imported = await importNovelWorkspaceArchive(codec, new Uint8Array([1]));
  assert.equal(imported.files[1].bytes.length, project.length);
});

test('workspace exchange rejects a v1 archive without project.md', async () => {
  const codec = new MemoryArchiveCodec(
    [entry('manifest.yaml', manifest().length)],
    new Map<string, Uint8Array>([['manifest.yaml', manifest()]]),
  );

  await assert.rejects(
    importNovelWorkspaceArchive(codec, new Uint8Array([1])),
    /project\.md/,
  );
});

test('workspace exchange rejects zip-bomb metadata before any entry is extracted', async () => {
  const codec = new MemoryArchiveCodec(
    [entry('manifest.yaml', 8), {
      path: 'drafts/huge.md', compressedSize: 1, uncompressedSize: 65 * 1024 * 1024, isDirectory: false,
    }],
    new Map<string, Uint8Array>(),
  );

  await assert.rejects(importNovelWorkspaceArchive(codec, new Uint8Array()));
  assert.deepEqual(codec.extracted, []);
});

test('workspace exchange enforces entry and cumulative size budgets before extraction', async () => {
  const excessiveEntries = new MemoryArchiveCodec(
    [entry('manifest.yaml', 1), entry('project.md', 1), entry('drafts/a.md', 1)],
    new Map<string, Uint8Array>(),
  );
  await assert.rejects(importNovelWorkspaceArchive(excessiveEntries, new Uint8Array(), smallLimits()));
  assert.deepEqual(excessiveEntries.extracted, []);

  const excessiveCompressed = new MemoryArchiveCodec(
    [entry('manifest.yaml', 3), entry('project.md', 3)],
    new Map<string, Uint8Array>(),
  );
  await assert.rejects(importNovelWorkspaceArchive(excessiveCompressed, new Uint8Array(), smallLimits()));
  assert.deepEqual(excessiveCompressed.extracted, []);
});

test('workspace exchange rejects duplicate and file-directory conflict entries before extraction', async () => {
  const duplicate = new MemoryArchiveCodec(
    [entry('manifest.yaml', 1), entry('manifest.yaml', 1)],
    new Map<string, Uint8Array>(),
  );
  await assert.rejects(importNovelWorkspaceArchive(duplicate, new Uint8Array()));
  assert.deepEqual(duplicate.extracted, []);

  const conflict = new MemoryArchiveCodec(
    [entry('manifest.yaml', 1), entry('setting', 1), entry('setting/world.md', 1)],
    new Map<string, Uint8Array>(),
  );
  await assert.rejects(importNovelWorkspaceArchive(conflict, new Uint8Array()));
  assert.deepEqual(conflict.extracted, []);

  const privateEntry = new MemoryArchiveCodec(
    [entry('manifest.yaml', 1), entry('.amber/ledger.jsonl', 1)],
    new Map<string, Uint8Array>(),
  );
  await assert.rejects(importNovelWorkspaceArchive(privateEntry, new Uint8Array()));
  assert.deepEqual(privateEntry.extracted, []);
});

test('workspace exchange never resolves a partial import after malformed manifest or interrupted extraction', async () => {
  const badManifest = encoder.encode('format: "other"\nversion: 1\nproject_id: "p"\ntitle: "x"\nactive_branch: "main"\n');
  const malformed = new MemoryArchiveCodec(
    [entry('manifest.yaml', badManifest.length), entry('project.md', 1)],
    new Map<string, Uint8Array>([['manifest.yaml', badManifest], ['project.md', encoder.encode('x')]]),
  );
  await assert.rejects(importNovelWorkspaceArchive(malformed, new Uint8Array()));

  const interrupted = new MemoryArchiveCodec(
    [entry('manifest.yaml', manifest().length), entry('project.md', 1)],
    new Map<string, Uint8Array>([['manifest.yaml', manifest()], ['project.md', encoder.encode('x')]]),
    'project.md',
  );
  await assert.rejects(importNovelWorkspaceArchive(interrupted, new Uint8Array()));
  assert.deepEqual(interrupted.extracted, ['manifest.yaml', 'project.md']);
});
