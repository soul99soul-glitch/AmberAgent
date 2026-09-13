// probe-batch5-settings.cjs — batch 5B real-logic probe (F35 assistants RMW + R26 theme rollback).
//
// F35: drives the REAL `mutateAssistant` (harmony/entry/src/main/ets/platform_impl/
//   AssistantMutation.ets) on top of the REAL `KeyedAsyncMutex` (AtomicJsonFile.ets)
//   and the REAL `assistants` KV helpers (harmony/chat/src/main/ets/chat/kv_store.ts)
//   backed by a real memory store. A control body models the REMOVED load-modify-save
//   pattern (compute next from a stale snapshot) to show the probe would catch a
//   regression (it loses a concurrent update).
//
// R26: extracts the REAL `setTheme` + `applyThemeMode` bodies from
//   harmony/entry/src/main/ets/pages/SettingAppearancePage.ets via the TS AST
//   (probe-lib) and runs them headless with a rejecting/racing KV. Asserts the
//   failed write rolls the applied mode back and that a late failure from an older
//   operation does NOT roll back a newer successful selection.

const path = require('node:path');
const assert = require('node:assert/strict');
const {
  REPO_ROOT, ts, parsePage, findClass, memberText, fieldDecl, createLoader,
} = require('../../2026-09-13-harmony-merged-evidence/probe-lib.cjs');

const CHAT_DIR = path.join(REPO_ROOT, 'harmony/chat/src/main/ets/chat');
const IMPL_DIR = path.join(REPO_ROOT, 'harmony/entry/src/main/ets/platform_impl');
const PAGES_DIR = path.join(REPO_ROOT, 'harmony/entry/src/main/ets/pages');

const log = (...a) => console.log(...a);

// ---------- load real modules ----------
const { load } = createLoader((name, loadFn) => {
  if (name === '@amber/chat-domain') return loadFn(path.join(CHAT_DIR, 'kv_store.ts'));
  if (name.includes('AppContainer')) return { getChatKvStore: () => { throw new Error('unused'); } };
  if (name.endsWith('.ets')) return loadFn(path.join(IMPL_DIR, name.replace(/^\.\//, '')));
  if (name.startsWith('./')) return loadFn(path.join(CHAT_DIR, name.replace(/^\.\//, '')));
  if (name === '@kit.CoreFileKit') return { fileIo: {} };
  if (name === '@kit.PerformanceAnalysisKit') return { hilog: { error() {}, warn() {} } };
  return undefined;
});

const kvmod = load(path.join(CHAT_DIR, 'kv_store.ts'));
const assistantMod = load(path.join(CHAT_DIR, 'assistant.ts'));
const atomicMod = load(path.join(IMPL_DIR, 'AtomicJsonFile.ets'));
const mutmod = load(path.join(IMPL_DIR, 'AssistantMutation.ets'));

const { ASSISTANTS_KEY, saveAssistants, loadAssistants, createMemoryKeyValueStore } = kvmod;
const { patchAssistant } = assistantMod;
const { mutateAssistantInStore: mutateAssistant } = mutmod;

log('[F35] loaded real mutateAssistant + sharedKvMutex:', typeof mutateAssistant === 'function',
  typeof atomicMod.sharedKvMutex.withLock === 'function');

const idsFrom = (a) => a.enabledSkills.slice().sort();

async function f35() {
  // 1) real helper: 20 concurrent toggles must ALL survive (no lost update)
  const store = createMemoryKeyValueStore();
  await saveAssistants(store, [patchAssistant(
    assistantMod.makeAssistant({ name: 'A' }), { id: 'a1', enabledSkills: [] })]);

  const names = [];
  for (let i = 0; i < 20; i++) names.push(`skill-${i}`);

  // fire-and-forget concurrently, exactly like rapid Switch toggles
  await Promise.all(names.map((n) => mutateAssistant(store, 'a1', (cur) =>
    patchAssistant(cur, {
      enabledSkills: cur.enabledSkills.indexOf(n) >= 0
        ? cur.enabledSkills : cur.enabledSkills.concat([n]),
    }))));

  const after = (await loadAssistants(store)).find((a) => a.id === 'a1');
  assert.equal(after.enabledSkills.length, 20,
    `F35 real mutateAssistant lost updates: got ${after.enabledSkills.length}/20`);
  assert.equal(JSON.stringify(idsFrom(after)), JSON.stringify(idsFrom({ enabledSkills: names })));
  log('[F35] real mutateAssistant: 20/20 concurrent toggles preserved');

  // 2) control: the removed stale-snapshot pattern loses one of two interleaved writes
  const s2 = createMemoryKeyValueStore();
  await saveAssistants(s2, [patchAssistant(
    assistantMod.makeAssistant({ name: 'A' }), { id: 'a1', enabledSkills: [] })]);
  const write = (name) => (async () => {
    const list = (await loadAssistants(s2)).slice();          // both read the same []
    list[0] = patchAssistant(list[0], { enabledSkills: [name] }); // computed from stale view
    await saveAssistants(s2, list);
  })();
  await Promise.all([write('A'), write('B')]);
  const stale = (await loadAssistants(s2)).find((a) => a.id === 'a1');
  assert.equal(JSON.stringify(stale.enabledSkills), JSON.stringify(['B']),
    'control must reproduce the F35 lost update (A dropped)');
  log('[F35] control (removed pattern): stale snapshot dropped A →', JSON.stringify(stale.enabledSkills));

  // 3) target missing must be a no-op, never a whole-table overwrite
  const s3 = createMemoryKeyValueStore();
  await saveAssistants(s3, [patchAssistant(
    assistantMod.makeAssistant({ name: 'A' }), { id: 'a1', enabledSkills: [] })]);
  const miss = await mutateAssistant(s3, 'nope', (cur) => cur);
  assert.equal(miss, null);
  assert.equal((await loadAssistants(s3)).length, 1);
  log('[F35] missing target: returned null, table untouched');
}

// ---------- R26: extract real theme methods ----------
function buildThemeHost(kv) {
  const file = path.join(PAGES_DIR, 'SettingAppearancePage.ets');
  const { sf } = parsePage(file);
  const cls = findClass(sf, 'SettingAppearancePage');
  assert.ok(cls, 'SettingAppearancePage class not found');

  const src = `
class Host {
  ${fieldDecl(sf, cls, 'themeMode')}
  ${fieldDecl(sf, cls, 'themeError')}
  ${fieldDecl(sf, cls, 'persistedThemeMode')}
  ${fieldDecl(sf, cls, 'themeSeq')}
  ${fieldDecl(sf, cls, 'systemDarkMode')}
  ${memberText(sf, cls, 'applyThemeMode')}
  ${memberText(sf, cls, 'setTheme')}
}
`;
  // provenance: the extracted bodies are verbatim from the page
  assert.ok(src.includes('persistedThemeMode = mode;') || src.includes('this.persistedThemeMode = mode;'),
    'R26 setTheme must track last persisted mode');
  assert.ok(src.includes('seq !== this.themeSeq'), 'R26 setTheme must carry the race guard');

  const js = ts.transpileModule(src, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, isolatedModules: true },
  }).outputText;
  const applied = [];
  const Host = new Function('applyTheme', 'applyWindowTheme', 'getChatKvStore', 'AppStorage',
    'UI_COLOR_MODE_KEY',
    js + '\nreturn Host;')(
    (_dark) => {}, (_dark) => {},
    () => kv,
    { setOrCreate() {} },
    'ui_color_mode', // real module const from SettingAppearancePage
  );
  return { host: new Host(), applied };
}

async function r26() {
  // (a) put rejects → applied mode rolls back to last persisted, error visible
  const kvA = { put: () => Promise.reject(new Error('quota')), get: async () => null };
  const { host: a } = buildThemeHost(kvA);
  a.persistedThemeMode = 'dark';
  a.themeMode = 'dark';
  await a.setTheme('light');
  assert.equal(a.themeMode, 'dark', 'R26 failed write must roll back applied mode');
  assert.ok(a.themeError.length > 0, 'R26 failed write must surface an error');
  log('[R26] single failure: rolled back to', a.themeMode, '| error:', JSON.stringify(a.themeError));

  // (b) race guard: older op fails AFTER a newer op succeeded → no rollback of newer
  let rejectLight = null;
  const kvB = {
    put: (_k, mode) => mode === 'light'
      ? new Promise((_res, rej) => { rejectLight = rej; })
      : Promise.resolve(),
    get: async () => null,
  };
  const { host: b } = buildThemeHost(kvB);
  b.persistedThemeMode = 'system';
  b.themeMode = 'system';
  const pOld = b.setTheme('light');   // seq 1, hangs then rejects
  const pNew = b.setTheme('dark');    // seq 2, succeeds
  await pNew;
  assert.equal(b.persistedThemeMode, 'dark');
  rejectLight(new Error('late failure'));
  await pOld;
  assert.equal(b.themeMode, 'dark', 'R26 late failure must NOT roll back the newer selection');
  assert.equal(b.themeError, '', 'R26 late failure must not flag the newer successful op');
  log('[R26] race guard: late failure left newer selection =', b.themeMode, '| error empty');
}

(async () => {
  await f35();
  await r26();
  log('PROBE OK: batch 5B (F35 + R26)');
})().catch((e) => { console.error('PROBE FAILED:', e); process.exit(1); });
