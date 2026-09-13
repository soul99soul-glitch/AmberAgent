// probe-e-io.cjs — Topic E isolated probe for E-05 (MemoryStore) and E-07 (FavoriteStore).
//
// Imports and transpiles the CURRENT source (like probe-f): no implementation is copied.
//   - MemoryStore.ets   is loaded with a mocked @kit.CoreFileKit fileIo (in-memory FS).
//   - AtomicJsonFile.ets is loaded with the SAME mocked fileIo, so the atomic write path
//     exercised here is the real one.
//   - FavoriteStore.ets is loaded with a mocked ../di/AppContainer.ets (in-memory KV).
// No real user files, no device, no network, no credentials.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const root = path.resolve(__dirname, '../../../..');
const ts = require(path.join(root, 'harmony/chat/node_modules/typescript'));
const ETS = 'harmony/entry/src/main/ets/platform_impl/';

// ---------- mocked in-memory filesystem + fileIo ----------
const files = new Map();      // abs path -> text
const writeBuf = new Map();   // fd -> text
let failReadText = false;     // transient IO failure switch (E-05)
let readTextCalls = 0;

const mockFileIo = {
  OpenMode: { READ_ONLY: 0, READ_WRITE: 2, CREATE: 64, TRUNC: 512, APPEND: 1024 },
  mkdir: async () => {},
  access: async (p) => files.has(p),
  readText: async (p) => {
    readTextCalls++;
    if (failReadText) throw new Error('synthetic transient EIO on readText');
    if (!files.has(p)) throw new Error('ENOENT ' + p);
    return files.get(p);
  },
  open: async (p) => ({ fd: p }),
  write: async (fd, text) => { writeBuf.set(fd, String(text)); },
  close: async () => {},
  renameSync: (from, to) => {
    if (!writeBuf.has(from)) throw new Error('rename missing tmp ' + from);
    files.set(to, writeBuf.get(from));
    writeBuf.delete(from);
  },
  unlinkSync: (p) => { files.delete(p); },
  readTextSync: (p) => files.get(p),
};
const mockHilog = { info() {}, warn() {}, error() {} };

// minimal domain mocks (dependencies, not the code under test)
const mockDomain = {
  appendMemoryRecord: (all, params) => {
    const id = all.reduce((m, r) => Math.max(m, r.id), 0) + 1;
    const record = {
      id, content: params.content, scope: params.scope, kind: params.kind,
      assistantId: params.assistantId, sourceConversationId: params.sourceConversationId ?? null,
      sourceMessageIds: [], supersedesIds: [], expiresAt: params.expiresAt ?? null,
      confidence: params.confidence ?? 1, pinned: false, archived: false,
      createdAt: 1, updatedAt: 1, lastUsedAt: null,
    };
    return { records: [...all, record], record };
  },
  deleteMemoryRecord: (all, id) => all.filter((r) => r.id !== id),
  updateMemoryRecordContent: (all, id, content) => {
    const idx = all.findIndex((r) => r.id === id);
    if (idx < 0) throw new Error('Memory record #' + id + ' not found');
    const next = [...all];
    next[idx] = { ...all[idx], content, updatedAt: 2 };
    return { records: next, record: next[idx] };
  },
  touchMemoryRecords: (all, ids) => all.map((r) => ids.includes(r.id) ? { ...r, lastUsedAt: 2 } : r),
  filterActiveMemoryRecords: (all) => all,
  memoriesOfAssistant: (all, assistantId) => all.filter((r) => r.assistantId === assistantId),
  memoryRecordToAssistantMemory: (r) => r,
  upsertMemoryRecord: (all, record) => {
    const idx = all.findIndex((r) => r.id === record.id && record.id !== 0);
    if (idx >= 0) { const next = [...all]; next[idx] = record; return { records: next, record }; }
    const id = all.reduce((m, r) => Math.max(m, r.id), 0) + 1;
    const inserted = { ...record, id };
    return { records: [...all, inserted], record: inserted };
  },
  createMemoryDreamPlanStore: () => ({}),
  memoryDreamPlanSummaryText: () => '',
  DREAM_NOTIFICATION_ID: 0,
  DREAM_NOTIFY_FAILED_TITLE: '', DREAM_NOTIFY_PENDING_TITLE: '',
  DREAM_NOTIFY_RUNNING_TEXT: '', DREAM_NOTIFY_RUNNING_TITLE: '',
};

let appStorageCtx = { filesDir: '/mock/files' };
const sandboxGlobals = {
  console, setTimeout, clearTimeout,
  AppStorage: { get: () => appStorageCtx },
};

const cache = new Map();
function load(relative) {
  const filename = path.join(root, relative);
  if (cache.has(filename)) return cache.get(filename);
  const source = fs.readFileSync(filename, 'utf8');
  const js = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const module = { exports: {} };
  function dependency(name) {
    if (name === '@kit.CoreFileKit') return { fileIo: mockFileIo };
    if (name === '@kit.PerformanceAnalysisKit') return { hilog: mockHilog };
    if (name === '@kit.AbilityKit') return {};
    if (name === '@amber/chat-domain') return Object.assign({}, mockDomain, realManagedFiles());
    if (name.endsWith('AtomicJsonFile.ets')) return load(ETS + 'AtomicJsonFile.ets');
    if (name.endsWith('FavoriteStore.ets')) return load(ETS + 'FavoriteStore.ets');
    if (name.endsWith('NotificationNotifier.ets')) {
      return { publishBasicNotification() {}, cancelNotification() {} };
    }
    if (name.endsWith('di/AppContainer.ets')) return { getChatKvStore: () => kv };
    throw new Error('Unexpected runtime dependency: ' + name);
  }
  vm.runInNewContext('(function(require,module,exports){' + js + '\n})',
    sandboxGlobals, { filename })(dependency, module, module.exports);
  cache.set(filename, module.exports);
  return module.exports;
}

// Real managed_files.ts is a dependency provider (createFilesRepository et al.),
// so we load the actual source instead of copying it.
const realManagedFiles = () =>
  load('harmony/chat/src/main/ets/chat/managed_files.ts');

// in-memory KV for FavoriteStore (E-07)
const map = new Map();
const kv = {
  get: async (k) => (map.has(k) ? map.get(k) : null),
  put: async (k, v) => { map.set(k, v); },
  delete: async (k) => { map.delete(k); },
};

(async () => {
  // ================= E-05: MemoryStore file IO read failure -> silent empty -> overwrite =================
  const mem = load(ETS + 'MemoryStore.ets');
  const memPath = '/mock/files/memory/memory_records.json';
  const seed = JSON.stringify([
    { id: 1, content: 'alpha', scope: 'core', kind: 'note', assistantId: '__global__', archived: false, expiresAt: null },
    { id: 2, content: 'beta', scope: 'core', kind: 'note', assistantId: '__global__', archived: false, expiresAt: null },
  ]);
  files.set(memPath, seed);

  const ops = mem.getMemoryLibraryOps();
  const before = await ops.listBucketRecords('__global__');
  assert.equal(before.length, 2, 'sanity: two seeded records readable');

  // Transient read failure, then a normal write (the real addMemory path).
  failReadText = true;
  let writeError = null;
  const added = await ops.addMemory({
    scope: 'core', kind: 'note', content: 'gamma', assistantId: '__global__',
    sourceConversationId: null, sourceMessageIds: [], expiresAt: null, confidence: 1,
  }).catch((e) => { writeError = e; return null; });
  failReadText = false;

  const persistedRaw = files.get(memPath);
  const persisted = JSON.parse(persistedRaw);
  console.log('[E-05] readText threw during addMemory -> addMemory resolved:', writeError === null,
    '| returned id:', added ? added.id : null);
  console.log('[E-05] persisted records after the failed-read write:', persisted.length,
    'contents:', JSON.stringify(persisted.map((r) => r.content)));
  assert.equal(writeError, null, 'write path swallowed the read failure (no error surfaced)');
  assert.equal(persisted.length, 1, 'prior records were overwritten by the empty-snapshot write');
  assert.deepEqual(persisted.map((r) => r.content), ['gamma']);
  console.log('[E-05] CONFIRMED: one transient readText failure => next write persists only the new record; alpha/beta permanently lost, no error surfaced.');

  // ---- E-05b: same readShape pattern in ManagedFileStore (real DAO via getFilesRepository) ----
  const mf = load(ETS + 'ManagedFileStore.ets');
  const mfPath = '/mock/files/managed_files.json';
  files.set(mfPath, JSON.stringify({
    nextId: 3,
    rows: [
      { id: 1, folder: 'upload', relativePath: 'upload/a.txt', displayName: 'a', mimeType: 'text/plain', sizeBytes: 1, createdAt: 1, updatedAt: 1 },
      { id: 2, folder: 'upload', relativePath: 'upload/b.txt', displayName: 'b', mimeType: 'text/plain', sizeBytes: 1, createdAt: 2, updatedAt: 2 },
    ],
  }));
  const filesRepo = mf.getFilesRepository();
  assert.equal((await filesRepo.listByFolder('upload')).length, 2, 'sanity: two seeded managed files');

  failReadText = true;
  let mfWriteError = null;
  const insertedFile = await filesRepo.insert({
    id: 0, folder: 'upload', relativePath: 'upload/c.txt', displayName: 'c',
    mimeType: 'text/plain', sizeBytes: 1, createdAt: 3, updatedAt: 3,
  }).catch((e) => { mfWriteError = e; return null; });
  failReadText = false;

  const mfAfter = JSON.parse(files.get(mfPath));
  console.log('[E-05b] readText threw during insert -> insert resolved:', mfWriteError === null,
    '| returned:', JSON.stringify(insertedFile),
    '| persisted rows:', mfAfter.rows.length, JSON.stringify(mfAfter.rows.map((r) => r.relativePath)));
  assert.equal(mfWriteError, null, 'ManagedFileStore insert swallowed the read failure');
  assert.equal(mfAfter.rows.length, 1, 'prior managed_files rows were overwritten by the empty-snapshot write');
  assert.deepEqual(mfAfter.rows.map((r) => r.relativePath), ['upload/c.txt']);
  console.log('[E-05b] CONFIRMED: same pattern as MemoryStore; a/b tracking rows silently replaced by the single new row.');

  // ================= E-07: FavoriteStore corrupt value -> decode [] -> overwrite =================
  const fav = load(ETS + 'FavoriteStore.ets');
  const FAV = 'favorites_json';
  map.set(FAV, JSON.stringify([
    { id: 'old-1', title: 't1', content: 'c1', conversationId: 'conv', createdAt: 1 },
    { id: 'old-2', title: 't2', content: 'c2', conversationId: 'conv', createdAt: 2 },
  ]));
  const favBefore = await fav.listFavorites();
  assert.equal(favBefore.length, 2, 'sanity: two seeded favorites');

  // The stored value is corrupted (parse failure swallowed by decode -> []).
  map.set(FAV, '{ this is not valid json');
  await fav.addFavorite({ id: 'new-1', title: 'new', content: 'new', conversationId: 'conv', createdAt: 3 });
  const favAfter = JSON.parse(map.get(FAV));
  console.log('[E-07] stored favorites after corrupt-value + addFavorite:', favAfter.length,
    'ids:', JSON.stringify(favAfter.map((f) => f.id)));
  assert.equal(favAfter.length, 1, 'corrupt value was folded to [] and then overwritten');
  assert.deepEqual(favAfter.map((f) => f.id), ['new-1']);
  console.log('[E-07] CONFIRMED: parse-level read failure => decode returns [] => next addFavorite overwrites the whole favorites blob; no error surfaced.');

  console.log('');
  console.log('Note: E-07 is parse-level swallow (decode catch). A KV get() that itself throws is');
  console.log('NOT swallowed (no try/catch around get), so that variant would surface an error instead.');
  console.log('All probes import/transpile current source; no real user files, device, or network used.');
})().catch((e) => { console.error(e); process.exitCode = 1; });
