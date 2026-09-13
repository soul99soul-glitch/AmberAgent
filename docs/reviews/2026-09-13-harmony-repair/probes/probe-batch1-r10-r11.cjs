// probe-batch1-r10-r11.cjs — batch 1 regression probe (R10 + R11).
//
// Loads the REAL .ets sources (MemoryStore / ManagedFileStore / FavoriteStore /
// EntryCompactStore / AtomicJsonFile) with mocked platform edges only:
//   - @kit.CoreFileKit fileIo  -> in-memory FS
//   - ../di/AppContainer.ets   -> in-memory KV (FavoriteStore)
//   - @amber/chat-domain       -> real managed_files.ts + minimal domain mocks
// No real files, device, network or credentials. All "deletions" happen in the
// in-memory mock FS / mock KV.

const path = require('node:path');
const assert = require('node:assert/strict');
const { REPO_ROOT, createLoader } = require('../../2026-09-13-harmony-merged-evidence/probe-lib.cjs');

const ETS = path.join(REPO_ROOT, 'harmony/entry/src/main/ets/platform_impl');

// ---------- mocked in-memory filesystem + fileIo ----------
const files = new Map();
const writeBuf = new Map();
let failReadText = false;

const mockFileIo = {
  OpenMode: { READ_ONLY: 0, READ_WRITE: 2, CREATE: 64, TRUNC: 512, APPEND: 1024 },
  mkdir: async () => {},
  access: async (p) => files.has(p),
  readText: async (p) => {
    if (failReadText) throw new Error('synthetic transient EIO on readText');
    if (!files.has(p)) throw new Error('ENOENT ' + p);
    return files.get(p);
  },
  open: async (p) => ({ fd: p }),
  write: async (fd, text) => { writeBuf.set(fd, String(text)); },
  close: async () => {},
  renameSync: (from, to) => {
    // two real-world uses: atomic tmp -> final, and quarantine final -> *.bad
    if (files.has(from)) { files.set(to, files.get(from)); files.delete(from); return; }
    if (writeBuf.has(from)) { files.set(to, writeBuf.get(from)); writeBuf.delete(from); return; }
    throw new Error('rename missing ' + from);
  },
  unlinkSync: (p) => { files.delete(p); },
  unlink: async (p) => {
    if (!files.has(p)) throw new Error('ENOENT ' + p);
    files.delete(p);
  },
};

// in-memory KV (shared by FavoriteStore + EntryCompactStore)
const kvMap = new Map();
const kv = {
  get: async (k) => (kvMap.has(k) ? kvMap.get(k) : null),
  put: async (k, v) => { kvMap.set(k, v); },
  delete: async (k) => { kvMap.delete(k); },
};

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
  clearPendingQueueStorage: async () => {},
};

const appCtx = { filesDir: '/mock/files' };
let realDomain = null;

const loader = createLoader(Object.assign(function resolve(name) {
  if (name === '@kit.CoreFileKit') return { fileIo: mockFileIo };
  if (name === '@kit.PerformanceAnalysisKit') return { hilog: { info() {}, warn() {}, error() {} } };
  if (name === '@kit.AbilityKit') return {};
  if (name === '@amber/chat-domain') {
    if (realDomain === null) {
      realDomain = loader.load(path.join(REPO_ROOT, 'harmony/chat/src/main/ets/chat/managed_files.ts'));
    }
    return Object.assign({}, mockDomain, realDomain);
  }
  if (name.endsWith('AtomicJsonFile.ets')) return loader.load(path.join(ETS, 'AtomicJsonFile.ets'));
  if (name.endsWith('EntryCompactStore.ets')) return loader.load(path.join(ETS, 'EntryCompactStore.ets'));
  if (name.endsWith('FavoriteStore.ets')) return loader.load(path.join(ETS, 'FavoriteStore.ets'));
  if (name.endsWith('NotificationNotifier.ets')) {
    return { publishBasicNotification() {}, cancelNotification() {} };
  }
  if (name.endsWith('di/AppContainer.ets')) return { getChatKvStore: () => kv };
  if (name.endsWith('ManagedFileStore.ets')) return loader.load(path.join(ETS, 'ManagedFileStore.ets'));
  if (name.endsWith('MemoryStore.ets')) return loader.load(path.join(ETS, 'MemoryStore.ets'));
  return {};
}, { sandboxGlobals: { console, setTimeout, clearTimeout, AppStorage: { get: () => appCtx } } }));

const MEM_PATH = '/mock/files/memory/memory_records.json';
const seedMemory = () => JSON.stringify([
  { id: 1, content: 'alpha', scope: 'core', kind: 'note', assistantId: '__global__', archived: false, expiresAt: null },
  { id: 2, content: 'beta', scope: 'core', kind: 'note', assistantId: '__global__', archived: false, expiresAt: null },
]);

(async () => {
  // ================= R10 / MemoryStore =================
  const mem = loader.load(path.join(ETS, 'MemoryStore.ets'));
  const ops = mem.getMemoryLibraryOps();
  const add = (content) => ops.addMemory({
    scope: 'core', kind: 'note', content, assistantId: '__global__',
    sourceConversationId: null, sourceMessageIds: [], expiresAt: null, confidence: 1,
  });

  // T1: transient read failure must reject and must NOT overwrite the file
  files.set(MEM_PATH, seedMemory());
  assert.equal((await ops.listBucketRecords('__global__')).length, 2, 'sanity: two records seeded');
  failReadText = true;
  let t1Error = null;
  await add('gamma').then(() => {}, (e) => { t1Error = e; });
  failReadText = false;
  const t1 = JSON.parse(files.get(MEM_PATH));
  console.log('[R10-T1] transient readText during addMemory -> rejected:', t1Error !== null,
    '| persisted contents:', JSON.stringify(t1.map((r) => r.content)));
  assert.ok(t1Error !== null, 'transient read failure must surface an error');
  assert.deepEqual(t1.map((r) => r.content), ['alpha', 'beta'], 'prior records preserved');
  console.log('[R10-T1] PASS: error read is rejected, no empty-snapshot overwrite.');

  // T2: corrupt parse -> quarantine; later writes must NOT reopen as "first init"
  files.delete(MEM_PATH);
  files.set(MEM_PATH, '{ not valid json');
  let t2Error = null;
  await add('gamma').then(() => {}, (e) => { t2Error = e; });
  console.log('[R10-T2] corrupt registry -> addMemory rejected:', t2Error !== null,
    '| quarantine marker:', files.has(MEM_PATH + '.quarantined'));
  assert.ok(t2Error !== null, 'corrupt parse must reject the write');
  assert.equal(files.has(MEM_PATH + '.quarantined'), true, 'quarantine marker persisted');
  let t2bError = null;
  await add('delta').then(() => {}, (e) => { t2bError = e; });
  assert.ok(t2bError !== null, 'post-quarantine write must also be rejected (not treated as first init)');
  assert.equal(files.has(MEM_PATH), false, 'no empty-snapshot file was written after quarantine');
  console.log('[R10-T2] PASS: quarantine is sticky; next write is not a first-init overwrite.');

  // T3 (negative control):真首次初始化(file absent, no marker) still writes
  files.delete(MEM_PATH);
  files.delete(MEM_PATH + '.quarantined');
  const first = await add('first');
  const t3 = JSON.parse(files.get(MEM_PATH));
  console.log('[R10-T3] missing file (true first init) -> addMemory ok, id:', first.id,
    '| contents:', JSON.stringify(t3.map((r) => r.content)));
  assert.deepEqual(t3.map((r) => r.content), ['first'], 'true first init still works');

  // ================= R10 / ManagedFileStore =================
  const mf = loader.load(path.join(ETS, 'ManagedFileStore.ets'));
  const repo = mf.getFilesRepository();
  const MF_PATH = '/mock/files/managed_files.json';
  const seedManaged = () => JSON.stringify({ nextId: 3, rows: [
    { id: 1, folder: 'upload', relativePath: 'upload/a.txt', displayName: 'a', mimeType: 'text/plain', sizeBytes: 1, createdAt: 1, updatedAt: 1 },
    { id: 2, folder: 'upload', relativePath: 'upload/b.txt', displayName: 'b', mimeType: 'text/plain', sizeBytes: 1, createdAt: 2, updatedAt: 2 },
  ] });
  const insertC = () => repo.insert({
    id: 0, folder: 'upload', relativePath: 'upload/c.txt', displayName: 'c',
    mimeType: 'text/plain', sizeBytes: 1, createdAt: 3, updatedAt: 3,
  });

  files.set(MF_PATH, seedManaged());
  failReadText = true;
  let mfErr = null;
  await insertC().then(() => {}, (e) => { mfErr = e; });
  failReadText = false;
  const mfT1 = JSON.parse(files.get(MF_PATH));
  console.log('[R10-T4] managed insert with transient read failure -> rejected:', mfErr !== null,
    '| rows:', JSON.stringify(mfT1.rows.map((r) => r.relativePath)));
  assert.ok(mfErr !== null, 'managed insert must reject on read failure');
  assert.deepEqual(mfT1.rows.map((r) => r.relativePath), ['upload/a.txt', 'upload/b.txt']);

  files.set(MF_PATH, '{ broken');
  await insertC().then(() => {}, () => {});
  const mfBlocked = await repo.listByFolder('upload').then(() => 'ok', (e) => e.message);
  console.log('[R10-T5] managed listByFolder on corrupt registry -> error:', mfBlocked);
  assert.notEqual(mfBlocked, 'ok', 'read on corrupt registry must not return []');
  assert.equal(files.has(MF_PATH + '.quarantined'), true, 'managed quarantine marker present');

  // negative control: true first init still allowed
  files.delete(MF_PATH);
  files.delete(MF_PATH + '.quarantined');
  const inserted = await insertC();
  console.log('[R10-T6] managed first init insert ok, id:', inserted.id);
  assert.equal(inserted.id, 1);

  // ================= R10 / FavoriteStore =================
  const fav = loader.load(path.join(ETS, 'FavoriteStore.ets'));
  const FAV = 'favorites_json';
  kvMap.set(FAV, JSON.stringify([
    { id: 'old-1', title: 't1', content: 'c1', conversationId: 'conv', createdAt: 1 },
  ]));
  kvMap.set(FAV, '{ not valid json');
  let favErr = null;
  await fav.addFavorite({ id: 'new-1', title: 'new', content: 'new', conversationId: 'conv', createdAt: 3 })
    .then(() => {}, (e) => { favErr = e; });
  console.log('[R10-T7] FavoriteStore addFavorite on corrupt blob -> rejected:', favErr !== null,
    '| stored blob unchanged:', kvMap.get(FAV));
  assert.ok(favErr !== null, 'favorite add must reject on corrupt parse');
  assert.equal(kvMap.get(FAV), '{ not valid json', 'corrupt blob was not overwritten');

  // ================= R11 / compact cleanup + resurrection guard =================
  const compact = loader.load(path.join(ETS, 'EntryCompactStore.ets'));
  kvMap.set('compacts/conv1', JSON.stringify([{ conversationId: 'conv1', summaryId: 's1' }]));
  kvMap.set('compacts/conv1.events', '{"event_type":"x"}\n');
  await compact.deleteConversationCompactData(kv, 'conv1');
  const marker = kvMap.get('compacts/conv1.deleted');
  console.log('[R11-T1] deleteConversationCompactData -> compacts:', kvMap.has('compacts/conv1'),
    '| events:', kvMap.has('compacts/conv1.events'), '| marker:', marker !== undefined);
  assert.equal(kvMap.has('compacts/conv1'), false, 'compact key removed');
  assert.equal(kvMap.has('compacts/conv1.events'), false, 'events key removed');
  assert.notEqual(marker, undefined, 'deletion marker written');

  const store = compact.createEntryCompactStore(kv);
  await store.insertCompact({ conversationId: 'conv1', summaryId: 'late' });
  await store.recordEvent('conv1', 'compact_created', 'late', 'late write');
  console.log('[R11-T2] late insertCompact/recordEvent after delete -> compacts:',
    JSON.stringify(await store.getCompacts('conv1')), '| events key:', kvMap.has('compacts/conv1.events'));
  assert.equal((await store.getCompacts('conv1')).length, 0, 'late summary did not resurrect');
  assert.equal(kvMap.has('compacts/conv1.events'), false, 'late audit event rejected');

  // cleanupDeletedConversation (real orchestration) must include the compact cleanup
  const conversation = { id: 'conv2', messageNodes: [] };
  const repository = {
    list: async () => [{ id: 'conv2' }],
    getById: async (id) => (id === 'conv2' ? conversation : null),
  };
  kvMap.set('compacts/conv2', JSON.stringify([{ conversationId: 'conv2', summaryId: 's2' }]));
  kvMap.set('compacts/conv2.events', 'x\n');
  kvMap.set(FAV, JSON.stringify([{ id: 'f1', title: '', content: '', conversationId: 'conv2', createdAt: 1 }]));
  await mf.cleanupDeletedConversation(conversation, repository, kv);
  console.log('[R11-T3] cleanupDeletedConversation -> compact:', kvMap.has('compacts/conv2'),
    '| events:', kvMap.has('compacts/conv2.events'),
    '| marker:', kvMap.get('compacts/conv2.deleted') !== undefined,
    '| favorites:', kvMap.get(FAV));
  assert.equal(kvMap.has('compacts/conv2'), false, 'cleanup removed compact');
  assert.equal(kvMap.has('compacts/conv2.events'), false, 'cleanup removed events');
  assert.notEqual(kvMap.get('compacts/conv2.deleted'), undefined, 'cleanup wrote deletion marker');
  assert.deepEqual(JSON.parse(kvMap.get(FAV)), [], 'favorites cascade still works');

  // ---- R11-T4: corrupt favorites must NOT abort the compact cleanup ----
  const conversation3 = { id: 'conv3', messageNodes: [] };
  const repository3 = {
    list: async () => [{ id: 'conv3' }],
    getById: async (id) => (id === 'conv3' ? conversation3 : null),
  };
  kvMap.set('compacts/conv3', JSON.stringify([{ conversationId: 'conv3', summaryId: 's3' }]));
  kvMap.set(FAV, '{ corrupt');
  let cleanup4Error = null;
  await mf.cleanupDeletedConversation(conversation3, repository3, kv)
    .then(() => {}, (e) => { cleanup4Error = e; });
  console.log('[R11-T4] cleanup with corrupt favorites -> threw:', cleanup4Error !== null,
    '| compact removed:', !kvMap.has('compacts/conv3'),
    '| corrupt blob kept:', kvMap.get(FAV));
  assert.equal(kvMap.has('compacts/conv3'), false, 'compact cleanup ran despite corrupt favorites');
  assert.equal(kvMap.get(FAV), '{ corrupt', 'corrupt favorites blob not overwritten');
  console.log('[R11-T4] PASS: compact cleanup independent of the favorites cascade.');

  // ---- R11-T5: late writer that passed the pre-check races an out-of-band delete ----
  // Interleaving: insertCompact's guard passes, then the delete lands (marker+delete)
  // before the writer's put; the post-write re-check must clean up the late write.
  const conv5 = 'conv5';
  let raced = false;
  const racingKv = {
    get: async (k) => {
      const v = kvMap.has(k) ? kvMap.get(k) : null;
      // after insertCompact's marker check + compact read, inject the delete
      if (k === 'compacts/conv5' && !raced) {
        raced = true;
        kvMap.set('compacts/conv5.deleted', JSON.stringify({ deletedAt: 1 }));
        kvMap.delete('compacts/conv5');
      }
      return v;
    },
    put: async (k, v) => { kvMap.set(k, v); },
    delete: async (k) => { kvMap.delete(k); },
  };
  const store5 = compact.createEntryCompactStore(racingKv);
  await store5.insertCompact({ conversationId: conv5, summaryId: 'late-race' });
  console.log('[R11-T5] racing delete during insert -> raw compact key present:',
    kvMap.has('compacts/conv5'));
  assert.equal(kvMap.has('compacts/conv5'), false,
    'writer cleaned up its own late write after the delete won the race');
  console.log('[R11-T5] PASS: post-write generation re-check prevents residual summary data.');

  console.log('\nAll batch-1 R10/R11 assertions passed. Real .ets sources, mocked FS/KV only.');
})().catch((e) => { console.error(e); process.exitCode = 1; });
