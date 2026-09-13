// probe-f20-clean-orphans.cjs — UI F-20 isolated probe (no real user files, device, or network).
//
// METHOD: the real `SettingFilesPage` methods `aboutToAppear` / `reload` / `cleanOrphans`
// and its fields (`rows` / `loaded` / `cleaning` / `repo`) are extracted VERBATIM from
// harmony/entry/src/main/ets/pages/SettingFilesPage.ets via the TypeScript lexer
// (see probe-lib.cjs). `struct` -> `class`, the ArkUI `build()` DSL is excluded, and the
// original `import` header is reused. Nothing in the code under test is hand-written.
//
// Only platform edges are mocked: @kit.CoreFileKit fileIo (in-memory FS), @kit.ArkUI
// promptAction, AppStorage context, and the ArkUI component/font modules (unused once
// build() is excluded). `getFilesRepository`/`ManagedFileStore.ets` are the REAL sources.
//
// Safety note: the registry and the upload files live in an IN-MEMORY mock filesystem.
// Deletions reported below happen on that mock only — no real device/app file is touched.

const path = require('node:path');
const assert = require('node:assert/strict');
const {
  REPO_ROOT, parsePage, findClass, memberText, fieldDecl, interfaceText, createLoader, sha,
} = require('./probe-lib.cjs');

// ---------- mocked in-memory filesystem + fileIo ----------
const files = new Map();      // abs file path -> text/buffer
const dirs = new Map();       // abs dir -> Set(childName)
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
const setFile = (p, data) => { files.set(p, data); addToDir(p); };
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
  open: async (p) => ({ fd: p }),
  write: async (fd, data) => { setFile(fd, String(data)); },
  close: async () => {},
  renameSync: (from, to) => {
    if (files.has(from)) { setFile(to, files.get(from)); delFile(from); }
    else if (dirs.has(from)) { dirs.set(to, dirs.get(from)); dirs.delete(from); }
    else throw new Error('rename missing ' + from);
  },
  unlinkSync: (p) => {
    if (dirs.has(p)) throw new Error('EISDIR ' + p); // real fileIo unlink on a dir fails
    if (!files.has(p)) throw new Error('ENOENT ' + p);
    delFile(p);
  },
  listFileSync: (p) => {
    if (!dirs.has(p)) throw new Error('ENOENT ' + p);
    return [...dirs.get(p)];
  },
  statSync: (p) => ({ size: files.has(p) ? files.get(p).length : 0 }),
};

const appCtx = { filesDir: '/mock/files' };
const loader = createLoader(Object.assign((name) => {
  if (name === '@kit.CoreFileKit') return { fileIo: mockFileIo };
  if (name === '@kit.PerformanceAnalysisKit') { return { hilog: { info() {}, warn() {}, error() {} } }; }
  if (name === '@kit.AbilityKit') return {};
  if (name === '@kit.ArkUI') return { promptAction: { showToast() {} } };
  if (name === '@amber/chat-domain') {
    const real = loader.load(path.join(REPO_ROOT, 'harmony/chat/src/main/ets/chat/managed_files.ts'));
    return Object.assign({}, real, { clearPendingQueueStorage: async () => {} });
  }
  if (name.endsWith('ManagedFileStore.ets')) {
    return loader.load(path.join(REPO_ROOT, 'harmony/entry/src/main/ets/platform_impl/ManagedFileStore.ets'));
  }
  if (name.endsWith('AtomicJsonFile.ets')) {
    return loader.load(path.join(REPO_ROOT, 'harmony/entry/src/main/ets/platform_impl/AtomicJsonFile.ets'));
  }
  if (name.endsWith('FavoriteStore.ets')) return { removeFavoritesOfConversation: async () => {} };
  if (name.endsWith('di/AppContainer.ets')) return { getChatKvStore: () => ({}) };
  // ArkUI-only modules used by the excluded build() DSL — never invoked here.
  return {};
}, {
  sandboxGlobals: { console, setTimeout, clearTimeout, AppStorage: { get: () => appCtx } },
}));

// ===== assemble the headless class from ORIGINAL source via the TS AST =====
const PAGE = path.join(REPO_ROOT, 'harmony/entry/src/main/ets/pages/SettingFilesPage.ets');
const { raw, sf } = parsePage(PAGE);
const cls = findClass(sf, 'SettingFilesPage');
assert.ok(cls !== null, 'SettingFilesPage class node found');

const header = raw.slice(0, raw.indexOf('interface FileRow'));
assert.ok(header.includes("from '@kit.CoreFileKit'"), 'header carries real imports');

const fileRowIf = interfaceText(sf, 'FileRow');
const fields = ['rows', 'loaded', 'cleaning', 'repo'].map((n) => fieldDecl(sf, cls, n));
const mAbout = memberText(sf, cls, 'aboutToAppear');
const mReload = memberText(sf, cls, 'reload');
const mClean = memberText(sf, cls, 'cleanOrphans');

// provenance guards: the slices really are the production bodies (verbatim AST ranges)
assert.ok(mReload.includes('this.repo.listByFolder(f)'), 'reload slice is the real body');
assert.ok(mClean.includes('fileIo.unlinkSync(`${base}/${rel}`)'), 'cleanOrphans slice is the real body');
assert.ok(mClean.includes('known.indexOf(rel) < 0'), 'cleanOrphans keeps the real orphan test');
assert.ok(mAbout.includes('this.reload()'), 'aboutToAppear slice is the real body');
console.log('[extract] SettingFilesPage members from AST; cleanOrphans sha256/16:',
  sha(mClean), 'len', mClean.length, '| reload sha256/16:', sha(mReload), 'len', mReload.length);

const assembled = [
  header,
  fileRowIf,
  'class SettingFilesPage {',
  ...fields,
  mAbout,
  mReload,
  mClean,
  '}',
  'module.exports = { SettingFilesPage };',
].join('\n');

const pageModule = loader.load(assembleVirtual(assembled));
function assembleVirtual(text) {
  const dir = path.join(REPO_ROOT, 'docs/reviews/2026-09-13-harmony-merged-evidence/.probe-tmp');
  require('node:fs').mkdirSync(dir, { recursive: true });
  const f = path.join(dir, 'SettingFilesPage.headless.ets');
  require('node:fs').writeFileSync(f, text);
  return f;
}

const putFile = (abs, text) => { files.set(abs, text); addToDir(abs); };
const uploads = () => [...files.keys()].filter((k) => k.startsWith('/mock/files/upload/')).sort();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const mf = loader.load(path.join(REPO_ROOT, 'harmony/entry/src/main/ets/platform_impl/ManagedFileStore.ets'));
  const registry = '/mock/files/managed_files.json';

  async function freshPage() {
    const page = new pageModule.SettingFilesPage(); // real class: fields via getFilesRepository()
    page.aboutToAppear();                            // real aboutToAppear -> this.reload()
    for (let i = 0; i < 50 && !page.loaded; i++) await sleep(5);
    return page;
  }

  // ---- T0: intact registry, two tracked files ----
  putFile('/mock/files/upload/a.png', 'A');
  putFile('/mock/files/upload/b.png', 'B');
  files.set(registry, JSON.stringify({ nextId: 3, rows: [
    { id: 1, folder: 'upload', relativePath: 'upload/a.png', displayName: 'a', mimeType: 'image/png', sizeBytes: 1, createdAt: 1, updatedAt: 1 },
    { id: 2, folder: 'upload', relativePath: 'upload/b.png', displayName: 'b', mimeType: 'image/png', sizeBytes: 1, createdAt: 2, updatedAt: 2 },
  ] }));
  let page = await freshPage();
  console.log('[T0] real reload -> rows:', page.rows.length, '| loaded:', page.loaded);
  assert.equal(page.rows.length, 2, 'tracked rows visible');
  assert.equal(page.loaded, true);

  // ---- T1: registry corrupt -> real readShape quarantines + empty -> cleanOrphans ----
  files.set(registry, '{ not valid json');
  page = await freshPage();
  console.log('[T1] real reload on corrupt registry -> rows:', page.rows.length, '| loaded:', page.loaded);
  assert.equal(page.rows.length, 0, 'corrupt registry -> empty rows');
  assert.equal(page.loaded, true, 'loaded=true so the !loaded guard does NOT fire');
  page.cleanOrphans();            // REAL method
  await sleep(20);
  const left1 = uploads();
  console.log('[T1] mock FS after real cleanOrphans -> remaining mock upload entries:', JSON.stringify(left1));
  assert.deepEqual(left1, [], 'both referenced mock upload files were removed from the mock FS');
  console.log('[T1] CONFIRMED: corrupt registry -> empty snapshot -> real cleanOrphans removes still-referenced files (mock FS).');

  // ---- T2: registry MISSING ----
  putFile('/mock/files/upload/c.png', 'C');
  files.delete(registry);
  page = await freshPage();
  page.cleanOrphans();
  await sleep(20);
  console.log('[T2] missing registry -> remaining mock upload entries:', JSON.stringify(uploads()));
  assert.equal(uploads().includes('/mock/files/upload/c.png'), false, 'missing registry also deletes real referenced files (mock)');
  console.log('[T2] CONFIRMED: access=false -> emptyShape (no quarantine) -> same destructive result.');

  // ---- T3: transient read failure (R10-overlapping root cause) ----
  putFile('/mock/files/upload/d.png', 'D');
  files.set(registry, JSON.stringify({ nextId: 4, rows: [
    { id: 3, folder: 'upload', relativePath: 'upload/d.png', displayName: 'd', mimeType: 'image/png', sizeBytes: 1, createdAt: 3, updatedAt: 3 },
  ] }));
  failReadText = true;
  page = await freshPage();
  failReadText = false;
  const rowsAtClean3 = page.rows.length;   // snapshot BEFORE cleanOrphans (it re-runs reload)
  page.cleanOrphans();
  await sleep(20);
  console.log('[T3] transient readText failure -> rows at clean:', rowsAtClean3,
    '| remaining mock upload entries:', JSON.stringify(uploads()));
  assert.equal(rowsAtClean3, 0, 'transient failure produced an empty snapshot');
  assert.equal(uploads().includes('/mock/files/upload/d.png'), false, 'transient read failure also deletes (mock)');
  console.log('[T3] CONFIRMED: same empty-snapshot root cause as R10.');

  // ---- T4: negative control — healthy registry deletes nothing ----
  putFile('/mock/files/upload/e.png', 'E');
  files.set(registry, JSON.stringify({ nextId: 5, rows: [
    { id: 4, folder: 'upload', relativePath: 'upload/e.png', displayName: 'e', mimeType: 'image/png', sizeBytes: 1, createdAt: 4, updatedAt: 4 },
  ] }));
  page = await freshPage();
  page.cleanOrphans();
  await sleep(20);
  console.log('[T4] healthy registry -> remaining mock upload entries:', JSON.stringify(uploads()));
  assert.deepEqual(uploads(), ['/mock/files/upload/e.png'], 'tracked file kept');
  console.log('[T4] CONFIRMED: destruction is specific to the empty/error snapshot.');

  // ---- T5: reference source is rows only (no conversation scan, no .bad read) ----
  console.log('[T5] cleanOrphans reference source = this.rows only; slice contains',
    "conversationFileUris?", mClean.includes('conversationFileUris'), '| .bad read?', mClean.includes('.bad'));

  console.log('\nAll T0-T5 assertions passed. Real page methods, mocked in-memory FS only; no real files/device/network.');
})().catch((e) => { console.error(e); process.exitCode = 1; });
