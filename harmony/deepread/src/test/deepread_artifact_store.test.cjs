const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const vm = require('node:vm');
const ts = require('typescript');

const ENTRY = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl');
const load = (name, imports) => {
  const filename = path.join(ENTRY, name);
  const exports = {};
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, require: spec => {
    if (imports[spec]) return imports[spec];
    throw new Error(`unexpected import ${spec}`);
  }, Error, Promise, Map, Set, JSON, String, Number, Date, Math, Uint8Array, ArrayBuffer }, { filename });
  return exports;
};
const complete = value => value.generationComplete === true
  && ['OVERVIEW', 'NARRATIVE', 'ANALYSIS', 'EXTENDED_READING'].every(stage => value.sectionStates[stage]?.status === 'READY');
const entry = (summary = '旧成品', updatedAt = 1) => ({ topicId: 'same-topic', title: '标题', sourceUrl: null,
  output: { summary, generationComplete: true, generationPhase: 'COMPLETE', sectionStates:
    Object.fromEntries(['OVERVIEW', 'NARRATIVE', 'ANALYSIS', 'EXTENDED_READING'].map(stage => [stage, { status: 'READY' }])) },
  phase: 'COMPLETE', attemptCount: 0, lastError: null, createdAt: 1, updatedAt, expiresAt: 9 });
const fixture = () => {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'deepread-artifact-'));
  let root = path.join(temp, 'root-a');
  let current = entry();
  let renameFailure = null;
  let writeFailure = null;
  let blockNextWrite = null;
  let kvFailure = null;
  const values = new Map();
  const fileIo = {
    OpenMode: { READ_WRITE: 1, CREATE: 2, TRUNC: 4 },
    mkdir: async (dir) => fs.mkdirSync(dir, { recursive: true }),
    open: async (filename) => ({ fd: fs.openSync(filename, 'w+') }),
    write: async (fd, bytes) => {
      if (writeFailure) throw writeFailure;
      if (blockNextWrite) { const block = blockNextWrite; blockNextWrite = null; await block(); }
      return fs.writeSync(fd, Buffer.from(bytes));
    },
    close: async (file) => fs.closeSync(file.fd),
    renameSync: (from, to) => { if (renameFailure) throw renameFailure; fs.renameSync(from, to); },
    unlinkSync: filename => fs.unlinkSync(filename),
  };
  // Execute the production atomic writer against real OS files, including its failure cleanup.
  const atomic = load('AtomicJsonFile.ets', {
    '@kit.CoreFileKit': { fileIo },
    '@kit.PerformanceAnalysisKit': { hilog: { warn() {}, info() {}, error() {} } },
    '@kit.ArkTS': { util: { TextEncoder: { create: () => ({ encodeInto: value => new TextEncoder().encode(value) }) } } },
  });
  const kv = { get: async key => values.get(key) ?? null,
    put: async (key, value) => { if (kvFailure) throw kvFailure; values.set(key, value); } };
  const repository = { get: async () => current };
  const imports = {
    '@amber/deepread-domain': { isComplete: complete, deepReadToMarkdown: row => `${row.title}\n${row.output.summary}\n` },
    './AtomicJsonFile.ets': atomic,
    './WorkspaceStore.ets': { workspaceRootAbs: () => root },
    './EntryLocalToolPorts.ets': { sha256HexUtf8: value => crypto.createHash('sha256').update(value).digest('hex') },
  };
  const { DeepReadArtifactStore } = load('DeepReadArtifactStore.ets', imports);
  const store = new DeepReadArtifactStore(repository, kv);
  return { temp, store, kv, repository, imports, DeepReadArtifactStore,
    setEntry: value => { current = value; }, setRoot: value => { root = value; },
    setRenameFailure: value => { renameFailure = value; }, setKvFailure: value => { kvFailure = value; },
    setWriteFailure: value => { writeFailure = value; },
    blockWrite: block => { blockNextWrite = block; }, root: () => root,
    cleanup: () => fs.rmSync(temp, { recursive: true, force: true }) };
};

test('same-topic replacement stays one full file, and an actual atomic rename failure preserves the old file', async () => {
  const f = fixture();
  try {
    const first = await f.store.save('same-topic');
    assert.equal(first.error, null);
    assert.equal(first.statusError, null);
    assert.ok(fs.readFileSync(first.path, 'utf8').includes('旧成品'));
    f.setEntry(entry('新成品', 2));
    f.setRenameFailure(new Error('rename denied'));
    const failed = await f.store.save('same-topic');
    assert.equal(failed.error, 'rename denied');
    assert.ok(fs.readFileSync(first.path, 'utf8').includes('旧成品'));
    assert.deepEqual(fs.readdirSync(path.dirname(first.path)), [path.basename(first.path)]);
    f.setRenameFailure(null);
    const retried = await f.store.save('same-topic');
    assert.equal(retried.path, first.path);
    assert.equal(retried.articleUpdatedAt, 2);
    assert.ok(fs.readFileSync(first.path, 'utf8').includes('新成品'));
    assert.deepEqual(fs.readdirSync(path.dirname(first.path)), [path.basename(first.path)]);
  } finally { f.cleanup(); }
});

test('an actual staging-file write failure preserves the old artifact and removes the incomplete temp file', async () => {
  const f = fixture();
  try {
    const first = await f.store.save('same-topic');
    f.setEntry(entry('无法完整写入的新稿', 2));
    f.setWriteFailure(new Error('write disk full'));
    const failed = await f.store.save('same-topic');
    assert.equal(failed.error, 'write disk full');
    assert.ok(fs.readFileSync(first.path, 'utf8').includes('旧成品'));
    assert.deepEqual(fs.readdirSync(path.dirname(first.path)), [path.basename(first.path)]);
  } finally { f.cleanup(); }
});

test('a queued retry reads the latest committed article inside the complete-path lock', async () => {
  const f = fixture();
  try {
    let release;
    let started;
    const waiting = new Promise(resolve => { release = resolve; });
    const writing = new Promise(resolve => { started = resolve; });
    f.blockWrite(async () => { started(); await waiting; });
    const first = f.store.save('same-topic');
    await writing;
    const queued = f.store.save('same-topic');
    f.setEntry(entry('排队期间提交的新成品', 3));
    release();
    await first;
    const last = await queued;
    assert.equal(last.articleUpdatedAt, 3);
    assert.ok(fs.readFileSync(last.path, 'utf8').includes('排队期间提交的新成品'));
  } finally { f.cleanup(); }
});

test('status survives reopening and an independent retry uses the current Workspace root with no model', async () => {
  const f = fixture();
  try {
    f.setRenameFailure(new Error('disk unavailable'));
    await f.store.save('same-topic');
    const reopened = new f.DeepReadArtifactStore(f.repository, f.kv);
    assert.equal((await reopened.getStatus('same-topic')).error, 'disk unavailable');
    f.setRenameFailure(null);
    f.setRoot(path.join(f.temp, 'root-b'));
    const retry = await reopened.save('same-topic');
    assert.ok(retry.path.startsWith(f.root() + '/deepread/'));
    assert.equal(retry.error, null);
    assert.ok(fs.readFileSync(retry.path, 'utf8').includes('旧成品'));
    assert.equal((await new f.DeepReadArtifactStore(f.repository, f.kv).getStatus('same-topic')).path, retry.path);
  } finally { f.cleanup(); }
});

test('KV failure reports status persistence separately after a successful file commit', async () => {
  const f = fixture();
  try {
    f.setKvFailure(new Error('preferences full'));
    const result = await f.store.save('same-topic');
    assert.equal(result.error, null);
    assert.ok(result.statusError.includes('preferences full'));
    assert.ok(fs.readFileSync(result.path, 'utf8').includes('旧成品'));
    assert.equal((await f.store.getStatus('same-topic')).path, result.path);
    assert.ok((await f.store.getStatus('same-topic')).statusError.includes('preferences full'));
  } finally { f.cleanup(); }
});

test('missing or incomplete cache rows cannot overwrite an existing complete Workspace file', async () => {
  const f = fixture();
  try {
    const first = await f.store.save('same-topic');
    f.setEntry(null);
    assert.ok((await f.store.save('same-topic')).error.includes('已完整保存'));
    const partial = entry('部分稿');
    partial.output.generationComplete = false;
    f.setEntry(partial);
    assert.ok((await f.store.save('same-topic')).error.includes('已完整保存'));
    assert.ok(fs.readFileSync(first.path, 'utf8').includes('旧成品'));
  } finally { f.cleanup(); }
});

test('automatic callback runs only after a complete RDB/cache commit and cannot reverse article success', async () => {
  const row = entry();
  let release;
  let stored = null;
  let callbacks = 0;
  const repository = { listHistory: async () => [], upsert: written => {
    stored = written;
    return new Promise(resolve => { release = resolve; });
  } };
  const { DeepReadRunRepositoryAdapter } = load('DeepReadRunRepository.ets', {
    '@amber/deepread-domain': { isComplete: complete },
    '@kit.PerformanceAnalysisKit': { hilog: { warn() {} } },
  });
  let adapter;
  adapter = new DeepReadRunRepositoryAdapter(repository, async committed => {
    callbacks++;
    assert.equal(adapter.get(committed.topicId, committed.title), committed.output);
    assert.equal(stored, committed);
    throw new Error('secondary artifact failed');
  });
  const pending = adapter.save(row.topicId, row.title, row.output);
  assert.equal(callbacks, 0);
  assert.equal(adapter.get(row.topicId, row.title), null);
  release();
  await pending;
  assert.equal(callbacks, 1);
  assert.equal(adapter.get(row.topicId, row.title), row.output);
  const partial = entry('部分稿').output;
  partial.generationComplete = false;
  const checkpoint = adapter.save(row.topicId, row.title, partial);
  release();
  await checkpoint;
  assert.equal(callbacks, 1);
});

test('a failed article upsert never invokes the automatic artifact callback', async () => {
  let callbacks = 0;
  const { DeepReadRunRepositoryAdapter } = load('DeepReadRunRepository.ets', {
    '@amber/deepread-domain': { isComplete: complete },
    '@kit.PerformanceAnalysisKit': { hilog: { warn() {} } },
  });
  const adapter = new DeepReadRunRepositoryAdapter({ upsert: async () => { throw new Error('database full'); } },
    async () => { callbacks++; });
  const row = entry();
  await assert.rejects(() => adapter.save(row.topicId, row.title, row.output), /database full/);
  assert.equal(callbacks, 0);
  assert.equal(adapter.get(row.topicId, row.title), null);
});
