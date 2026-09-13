// probe-batch1-f20.cjs — UI-F20 regression probe (safe orphan cleanup).
//
// Drives the REAL `scanManagedOrphans` / `deleteManagedOrphans` from
// harmony/entry/src/main/ets/platform_impl/ManagedFileStore.ets against an
// in-memory FS and a mocked ConversationRepository. No real file is touched.
//
// Asserts the F20 boundary:
//   - registry corrupt / missing / transient-read-failure => cleanup refused,
//     no candidate produced, no file deleted;
//   - healthy registry =>候选 excludes tracked files AND files referenced by any
//     conversation; execution re-checks and only deletes still-orphan files.

const path = require('node:path');
const assert = require('node:assert/strict');
const { REPO_ROOT, createLoader } = require('../../2026-09-13-harmony-merged-evidence/probe-lib.cjs');

const ETS = path.join(REPO_ROOT, 'harmony/entry/src/main/ets/platform_impl');
const FILES_DIR = '/mock/files';

const files = new Map();
const dirs = new Map();
let failReadText = false;

const ensureDir = (d) => { if (!dirs.has(d)) dirs.set(d, new Set()); };
const addToDir = (p) => {
  const i = p.lastIndexOf('/');
  if (i > 0) { ensureDir(p.substring(0, i)); dirs.get(p.substring(0, i)).add(p.substring(i + 1)); }
};
const removeFromDir = (p) => {
  const i = p.lastIndexOf('/');
  if (i > 0 && dirs.has(p.substring(0, i))) dirs.get(p.substring(0, i)).delete(p.substring(i + 1));
};
const putFile = (p, data) => { files.set(p, data); addToDir(p); };
const delFile = (p) => { files.delete(p); removeFromDir(p); };

const mockFileIo = {
  OpenMode: { READ_ONLY: 0, READ_WRITE: 2, CREATE: 64, TRUNC: 512, APPEND: 1024 },
  mkdir: async (d) => { ensureDir(d); },
  access: async (p) => files.has(p) || dirs.has(p),
  readText: async (p) => {
    if (failReadText) throw new Error('synthetic transient EIO on readText');
    if (!files.has(p)) throw new Error('ENOENT ' + p);
    return files.get(p);
  },
  listFile: async (p) => {
    if (!dirs.has(p)) throw new Error('ENOENT ' + p);
    return [...dirs.get(p)];
  },
  unlink: async (p) => {
    if (!files.has(p)) throw new Error('ENOENT ' + p);
    delFile(p);
  },
  unlinkSync: (p) => {
    if (!files.has(p)) throw new Error('ENOENT ' + p);
    delFile(p);
  },
  open: async (p) => ({ fd: p }),
  write: async (fd, text) => { dirs.set('__buf__' + fd, String(text)); },
  close: async () => {},
  renameSync: (from, to) => {
    const bufKey = '__buf__' + from;
    if (files.has(from)) { files.set(to, files.get(from)); delFile(from); return; }
    if (dirs.has(bufKey)) { files.set(to, dirs.get(bufKey)); dirs.delete(bufKey); addToDir(to); return; }
    throw new Error('rename missing ' + from);
  },
};

const kvMap = new Map();
const kv = {
  get: async (k) => (kvMap.has(k) ? kvMap.get(k) : null),
  put: async (k, v) => { kvMap.set(k, v); },
  delete: async (k) => { kvMap.delete(k); },
};

const appCtx = { filesDir: FILES_DIR };
let degraded = false; // AppStorage('chatStorageDegraded')
const appStorage = {
  get: (k) => (k === 'chatStorageDegraded' ? degraded : appCtx),
};
let realDomain = null;
const loader = createLoader(Object.assign(function resolve(name) {
  if (name === '@kit.CoreFileKit') return { fileIo: mockFileIo };
  if (name === '@kit.PerformanceAnalysisKit') return { hilog: { info() {}, warn() {}, error() {} } };
  if (name === '@kit.AbilityKit') return {};
  if (name === '@amber/chat-domain') {
    if (realDomain === null) {
      realDomain = loader.load(path.join(REPO_ROOT, 'harmony/chat/src/main/ets/chat/managed_files.ts'));
    }
    return Object.assign({}, realDomain, { clearPendingQueueStorage: async () => {} });
  }
  if (name.endsWith('AtomicJsonFile.ets')) return loader.load(path.join(ETS, 'AtomicJsonFile.ets'));
  if (name.endsWith('EntryCompactStore.ets')) return loader.load(path.join(ETS, 'EntryCompactStore.ets'));
  if (name.endsWith('FavoriteStore.ets')) return loader.load(path.join(ETS, 'FavoriteStore.ets'));
  if (name.endsWith('NotificationNotifier.ets')) return { publishNotification() {} };
  if (name.endsWith('di/AppContainer.ets')) return { getChatKvStore: () => kv };
  return loader.load(path.join(ETS, 'ManagedFileStore.ets'));
}, { sandboxGlobals: { console, setTimeout, clearTimeout, AppStorage: appStorage } }));

const mf = loader.load(path.join(ETS, 'ManagedFileStore.ets'));
const registry = `${FILES_DIR}/managed_files.json`;
const uploads = () => [...files.keys()]
  .filter((k) => k.startsWith(`${FILES_DIR}/upload/`)).map((k) => k.substring(`${FILES_DIR}/`.length)).sort();

const imagePart = (rel) => ({ type: 'image', url: `file://${FILES_DIR}/${rel}` });
const conv = (id, rels) => ({
  id,
  messageNodes: [{ messages: [{ parts: rels.map(imagePart) }] }],
});

const makeRepo = (convs) => ({
  list: async () => convs.map((c) => ({ id: c.id })),
  getById: async (id) => convs.find((c) => c.id === id) ?? null,
});

const setRegistry = (shape) => files.set(registry, JSON.stringify(shape));
const tracked = (rel, id) => ({
  id, folder: 'upload', relativePath: rel, displayName: rel, mimeType: 'image/png',
  sizeBytes: 1, createdAt: id, updatedAt: id,
});

(async () => {
  // ---- T1: corrupt registry -> refuse, no deletion ----
  putFile(`${FILES_DIR}/upload/a.png`, 'A');
  putFile(`${FILES_DIR}/upload/b.png`, 'B');
  files.set(registry, '{ not valid json');
  const repo1 = makeRepo([conv('c1', ['upload/a.png', 'upload/b.png'])]);
  const scan1 = await mf.scanManagedOrphans(repo1);
  let delErr = null;
  await mf.deleteManagedOrphans(repo1, ['upload/a.png', 'upload/b.png']).then(() => {}, (e) => { delErr = e; });
  console.log('[F20-T1] corrupt registry -> scan.error:', scan1.error, '| delete threw:', delErr !== null,
    '| uploads:', JSON.stringify(uploads()));
  assert.ok(scan1.error.length > 0, 'corrupt registry must block scanning');
  assert.equal(scan1.candidates.length, 0, 'no candidates from a corrupt registry');
  assert.ok(delErr !== null, 'execution must also refuse');
  assert.equal(uploads().join(','), ['upload/a.png', 'upload/b.png'].join(','), 'files untouched');

  // ---- T2: missing registry (marker cleared) -> refuse as "not initialised" ----
  files.delete(registry);
  files.delete(`${registry}.quarantined`); // drop T1's sticky marker to model a clean-missing store
  const repo2 = makeRepo([conv('c1', ['upload/a.png'])]);
  const scan2 = await mf.scanManagedOrphans(repo2);
  await mf.deleteManagedOrphans(repo2, ['upload/a.png']).then(() => {}, () => {});
  console.log('[F20-T2] missing registry -> scan.error:', scan2.error, '| uploads:', JSON.stringify(uploads()));
  assert.ok(scan2.error.length > 0, 'missing registry must block scanning');
  assert.equal(uploads().includes('upload/a.png'), true, 'a.png kept');

  // ---- T3: transient readText failure -> refuse ----
  setRegistry({ nextId: 3, rows: [tracked('upload/a.png', 1)] });
  putFile(`${FILES_DIR}/upload/c.png`, 'C');
  failReadText = true;
  const repo3 = makeRepo([conv('c1', [])]);
  const scan3 = await mf.scanManagedOrphans(repo3);
  await mf.deleteManagedOrphans(repo3, ['upload/c.png']).then(() => {}, () => {});
  failReadText = false;
  console.log('[F20-T3] transient read failure -> scan.error:', scan3.error, '| uploads:', JSON.stringify(uploads()));
  assert.ok(scan3.error.length > 0, 'transient read failure must block scanning');
  assert.equal(uploads().includes('upload/c.png'), true, 'c.png kept');

  // ---- T4: healthy registry -> candidates exclude tracked + session-referenced ----
  files.delete(registry);
  files.delete(`${registry}.quarantined`);
  delFile(`${FILES_DIR}/upload/a.png`); // clear the F20-T1/T2/T3 fixtures
  delFile(`${FILES_DIR}/upload/b.png`);
  delFile(`${FILES_DIR}/upload/c.png`);
  putFile(`${FILES_DIR}/upload/tracked.png`, 'T');   // in registry
  putFile(`${FILES_DIR}/upload/referenced.png`, 'R'); // NOT in registry, referenced by c1
  putFile(`${FILES_DIR}/upload/orphan.png`, 'O');     // neither -> real orphan
  setRegistry({ nextId: 2, rows: [tracked('upload/tracked.png', 1)] });
  const repo4 = makeRepo([conv('c1', ['upload/referenced.png'])]);
  const scan4 = await mf.scanManagedOrphans(repo4);
  console.log('[F20-T4] healthy registry -> candidates:', JSON.stringify(scan4.candidates));
  assert.equal(scan4.candidates.join(','), ['upload/orphan.png'].join(','), 'only the true orphan is a candidate');
  const removed = await mf.deleteManagedOrphans(repo4, scan4.candidates);
  console.log('[F20-T4] after delete -> removed:', removed, '| uploads:', JSON.stringify(uploads()));
  assert.equal(removed, 1, 'one orphan removed');
  assert.equal(uploads().join(','), ['upload/referenced.png', 'upload/tracked.png'].join(','),
    'tracked + session-referenced files kept; only the orphan removed');
  console.log('[F20-T4] PASS: reference check protects unregistered but referenced files.');

  // ---- T5: negative control — healthy registry with no orphans ----
  setRegistry({ nextId: 3, rows: [
    tracked('upload/tracked.png', 1), tracked('upload/orphan.png', 2),
  ] });
  const scan5 = await mf.scanManagedOrphans(repo4);
  console.log('[F20-T5] all files tracked -> candidates:', JSON.stringify(scan5.candidates));
  assert.equal(scan5.error.length, 0);
  assert.equal(scan5.candidates.length, 0, 'nothing to clean when registry is complete');

  // ---- T6: planned path validation (external input) ----
  console.log('[F20-T6] isSafeOrphanPath:',
    JSON.stringify({
      'upload/a.png': mf.isSafeOrphanPath('upload/a.png'),
      '../escape.png': mf.isSafeOrphanPath('../escape.png'),
      'upload/../a.png': mf.isSafeOrphanPath('upload/../a.png'),
      'upload/sub/a.png': mf.isSafeOrphanPath('upload/sub/a.png'),
      '../upload/a.png': mf.isSafeOrphanPath('../upload/a.png'),
      'downloads/a.png': mf.isSafeOrphanPath('downloads/a.png'),
      'upload/..': mf.isSafeOrphanPath('upload/..'),
      '': mf.isSafeOrphanPath(''),
    }));
  assert.equal(mf.isSafeOrphanPath('upload/a.png'), true);
  assert.equal(mf.isSafeOrphanPath('../escape.png'), false);
  assert.equal(mf.isSafeOrphanPath('upload/../a.png'), false);
  assert.equal(mf.isSafeOrphanPath('upload/sub/a.png'), false);
  assert.equal(mf.isSafeOrphanPath('../upload/a.png'), false);
  assert.equal(mf.isSafeOrphanPath('downloads/a.png'), false);
  assert.equal(mf.isSafeOrphanPath('upload/..'), false);
  assert.equal(mf.isSafeOrphanPath(''), false);
  // a hostile planned entry must never unlink outside the registry folders
  putFile(`${FILES_DIR}/outside.png`, 'X');
  files.delete(registry);
  files.delete(`${registry}.quarantined`);
  setRegistry({ nextId: 1, rows: [] });
  const expRepo = makeRepo([]);
  const expRemoved = await mf.deleteManagedOrphans(expRepo, ['../outside.png']);
  console.log('[F20-T6] hostile planned "../outside.png" -> removed:', expRemoved,
    '| outside.png kept:', files.has(`${FILES_DIR}/outside.png`));
  assert.equal(expRemoved, 0, 'unsafe planned paths are dropped');
  assert.equal(files.has(`${FILES_DIR}/outside.png`), true, 'path traversal did nothing');

  // ---- T7: degraded conversation storage -> refuse scan and delete ----
  delFile(`${FILES_DIR}/outside.png`);
  putFile(`${FILES_DIR}/upload/z.png`, 'Z');
  setRegistry({ nextId: 1, rows: [] });
  degraded = true;
  const scan7 = await mf.scanManagedOrphans(expRepo);
  let del7Error = null;
  await mf.deleteManagedOrphans(expRepo, ['upload/z.png']).then(() => {}, (e) => { del7Error = e; });
  degraded = false;
  console.log('[F20-T7] degraded storage -> scan.error:', scan7.error,
    '| delete threw:', del7Error !== null, '| z.png kept:', files.has(`${FILES_DIR}/upload/z.png`));
  assert.ok(scan7.error.length > 0, 'degraded storage must block scanning');
  assert.equal(scan7.candidates.length, 0);
  assert.ok(del7Error !== null, 'degraded storage must block deletion');
  assert.equal(files.has(`${FILES_DIR}/upload/z.png`), true, 'z.png kept while degraded');

  console.log('\nAll batch-1 F20 assertions passed. Mock FS + mock repository only; no real files.');
})().catch((e) => { console.error(e); process.exitCode = 1; });
