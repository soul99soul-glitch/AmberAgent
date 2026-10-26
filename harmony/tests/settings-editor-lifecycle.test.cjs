const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../chat/node_modules/typescript');
require('../chat/node_modules/tsx/dist/cjs/index.cjs');
const prefs = require('../chat/src/main/ets/search/search_prefs.ts');
const services = require('../chat/src/main/ets/search/search_service.ts');

// Compile the production state and methods, excluding only ArkUI builders/decorators.
function loadPage(name, kv) {
  const source = fs.readFileSync(path.join(__dirname, '../entry/src/main/ets/pages', `${name}.ets`), 'utf8');
  const prefix = source.slice(0, source.indexOf('@Entry'));
  const parsed = ts.createSourceFile(name, prefix, ts.ScriptTarget.ES2022, true, ts.ScriptKind.TS);
  const helpers = parsed.statements.filter(node => !ts.isImportDeclaration(node))
    .map(node => node.getText(parsed)).join('\n');
  const start = source.indexOf(`struct ${name} {`);
  const methods = source.slice(start, source.indexOf('  @Builder', start))
    .replace(`struct ${name}`, `class ${name}`)
    .replace(/@(?:State|StorageProp|StorageLink|Watch)(?:\([^\n]*?\))?\s*/g, '');
  const code = ts.transpileModule(`${helpers}\n${methods}\n}\nreturn new ${name}();`, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const routes = [];
  const deps = { ...prefs, ...services, getChatKvStore: () => kv, ACCENT: '#000000',
    KeyboardAvoidMode: { OFFSET: 0, RESIZE: 1 }, router: { back: () => routes.push('back') } };
  const page = new Function(...Object.keys(deps), code)(...Object.values(deps));
  page.pageAlive = true;
  page.loaded = true;
  page.getUIContext = () => ({ getKeyboardAvoidMode: () => 0, setKeyboardAvoidMode: () => {} });
  return { page, routes };
}

function controlledStore() {
  const entries = new Map();
  let resolve, reject;
  const gate = new Promise((yes, no) => { resolve = yes; reject = no; });
  let writes = 0;
  return { entries, resolve, reject, get writes() { return writes; },
    get: async key => entries.get(key) ?? null,
    put: async (key, value) => { writes++; await gate; entries.set(key, value); },
  };
}
const settle = () => new Promise(resolve => setImmediate(resolve));

const { loadSearchSettings } = require('../entry/src/test/chat_search_settings_fixture.cjs');
const searchOwners = loadSearchSettings({ ...prefs, ...services });
async function searchFixture() {
  const kv = controlledStore();
  const { page, modes } = searchOwners.create(kv);
  await page.settings.refresh();
  const pending = [];
  const apply = page.settings.applyPatch.bind(page.settings);
  page.settings.applyPatch = patch => {
    const operation = apply(patch);
    operation.catch(() => {});
    pending.push(operation);
    return operation;
  };
  page.editor.openAdd();
  page.editor.dType = 'exa';
  page.editor.dApiKey = 'fixture-secret';
  return { page, kv, pending, modes };
}

test('quick-message system back closes its editor before leaving the page', () => {
  const { page, routes } = loadPage('QuickMessagesPage', controlledStore());
  page.startNew();
  page.editTitle = 'draft';
  assert.equal(page.onBackPress(), true);
  assert.equal(page.editing, false);
  assert.deepEqual(routes, []);
  assert.equal(page.onBackPress(), false);
});

test('quick-message save A cannot close a subsequently opened editor B', async () => {
  const kv = controlledStore();
  const { page } = loadPage('QuickMessagesPage', kv);
  page.startNew();
  page.editTitle = 'A';
  page.saveEdit();
  page.cancelEdit();
  page.startNew();
  page.editTitle = 'B';
  kv.resolve();
  await settle();
  assert.equal(page.editing, true);
  assert.equal(page.editTitle, 'B');
  assert.equal(page.items[0].title, 'A', 'completed write still updates the list');
});

test('search save remains open until real preference writes complete', async () => {
  const { page, kv, pending } = await searchFixture();
  page.editor.confirmAdd();
  const remainedOpen = page.editor.editorVisible;
  await settle();
  assert.ok(kv.writes > 0, 'the production prefs writer reached the controlled KV');
  kv.resolve();
  await Promise.allSettled(pending);
  await settle();
  assert.equal(remainedOpen, true);
  assert.equal(page.editor.editorVisible, false);
  assert.equal(page.settings.s.searchServices[0].apiKey, 'fixture-secret');
});

test('search failed add retains the editor and draft with a visible error', async () => {
  const { page, kv, pending } = await searchFixture();
  page.editor.confirmAdd();
  await settle();
  kv.reject(new Error('fixture_write_failed'));
  await Promise.allSettled(pending);
  await settle();
  assert.equal(page.editor.editorVisible, true);
  assert.equal(page.editor.dApiKey, 'fixture-secret');
  assert.ok(page.editor.editorError.length > 0);
  assert.equal(page.editor.editorSaving, false);
});

for (const outcome of ['resolve', 'reject']) {
  test(`search old ${outcome} cannot close or annotate a reopened editor`, async () => {
    const { page, kv, pending } = await searchFixture();
    page.editor.confirmAdd();
    await settle();
    page.onBackPress();
    page.editor.openAdd();
    page.editor.dApiKey = 'new-draft';
    kv[outcome](outcome === 'reject' ? new Error('old_write_failed') : undefined);
    await Promise.allSettled(pending);
    await settle();
    assert.equal(page.editor.editorVisible, true);
    assert.equal(page.editor.dApiKey, 'new-draft');
    assert.equal(page.editor.editorError ?? '', '');
    assert.equal(page.editor.editorSaving ?? false, false);
  });
}

test('quick-message top back follows the same editor/list boundary', () => {
  const { page, routes } = loadPage('QuickMessagesPage', controlledStore());
  page.startNew();
  page.requestBack();
  assert.equal(page.editing, false);
  assert.deepEqual(routes, []);
  page.requestBack();
  assert.deepEqual(routes, ['back']);
});

for (const action of ['confirmEdit', 'confirmDelete']) {
  test(`search ${action} failure retains the current editor and allows retry`, async () => {
    const { page, kv, pending } = await searchFixture();
    const current = services.makeSearchServiceOptions('exa');
    const list = [page.settings.s.searchServices[0], current];
    kv.entries.set(prefs.SEARCH_PREFS_KEYS.searchServices,
      JSON.stringify(list.map(services.searchServiceOptionsToJson)));
    page.settings.s.searchServices = list;
    page.editor.openEdit(current, 1);
    page.editor.dApiKey = 'edited-draft';
    page.editor[action]();
    await settle();
    assert.ok(kv.writes > 0);
    kv.reject(new Error('fixture_write_failed'));
    await Promise.allSettled(pending);
    await settle();
    assert.equal(page.editor.editorVisible, true);
    assert.equal(page.editor.dApiKey, 'edited-draft');
    assert.ok(page.editor.editorError.length > 0);
    assert.equal(page.editor.editorSaving, false);
    kv.put = async (key, value) => { kv.entries.set(key, value); };
    page.editor[action]();
    await Promise.allSettled(pending);
    await settle();
    assert.equal(page.editor.editorVisible, false, 'retry closes only after its successful write');
    assert.equal(page.editor.editorError, '');
  });
}

test('search ignores repeat saves during a single in-flight submission', async () => {
  const { page, kv, pending } = await searchFixture();
  page.editor.confirmAdd();
  page.editor.confirmAdd();
  assert.equal(pending.length, 1);
  kv.resolve();
  await Promise.allSettled(pending);
  await settle();
  assert.equal(page.settings.s.searchServices.filter(item => item.apiKey === 'fixture-secret').length, 1);
});

test('search completion A does not clear the saving state of a newer submission B', async () => {
  const { page, kv, pending } = await searchFixture();
  page.editor.confirmAdd();
  await settle();
  page.onBackPress();
  page.editor.openAdd();
  page.editor.dType = 'exa';
  page.editor.dApiKey = 'new-draft';
  const nextWrite = controlledStore();
  kv.put = async (key, value) => { await nextWrite.put(key, value); kv.entries.set(key, value); };
  page.editor.confirmAdd();
  kv.resolve();
  await pending[0];
  await settle();
  const openWhileBPending = page.editor.editorVisible;
  const savingWhileBPending = page.editor.editorSaving;
  const errorWhileBPending = page.editor.editorError;
  nextWrite.resolve();
  await Promise.allSettled(pending);
  await settle();
  assert.equal(openWhileBPending, true);
  assert.equal(savingWhileBPending, true);
  assert.equal(errorWhileBPending, '');
  assert.equal(page.editor.editorVisible, false);
  assert.equal(page.settings.s.searchServices[0].apiKey, 'new-draft');
});

test('search completion after page destruction does not mutate the discarded page', async () => {
  const { page, kv, pending } = await searchFixture();
  page.editor.confirmAdd();
  await settle();
  page.aboutToDisappear();
  kv.resolve();
  await Promise.allSettled(pending);
  await settle();
  assert.equal(page.editor.editorVisible, true, 'a discarded page is not closed by late completion');
  assert.equal(page.settings.s.searchServices.some(item => item.apiKey === 'fixture-secret'), false);
});
