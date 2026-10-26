const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
process.env.TSX_TSCONFIG_PATH ??= path.resolve(__dirname, '../../../chat/tsconfig.json');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const prefs = require('../../../chat/src/main/ets/search/search_prefs.ts');
const services = require('../../../chat/src/main/ets/search/search_service.ts');
const { loadSearchSettings } = require('./chat_search_settings_fixture.cjs');
const owners = loadSearchSettings({ ...prefs, ...services });

async function fixture(configured) {
  const entries = new Map(), writes = [], pending = [];
  const failures = { put: false };
  const kv = { get: async key => entries.get(key) ?? null,
    put: async (key, value) => {
      if (failures.put) throw new Error('fixture storage unavailable');
      writes.push([key, value]); entries.set(key, value);
    } };
  if (configured) {
    entries.set(prefs.SEARCH_PREFS_KEYS.searchServices,
      JSON.stringify(configured.map(services.searchServiceOptionsToJson)));
  }
  const { page, modes } = owners.create(kv);
  await page.settings.refresh();
  const apply = page.settings.applyPatch.bind(page.settings);
  page.settings.applyPatch = patch => { const result = apply(patch); pending.push(result); return result; };
  return { page, modes, entries, writes, pending, failures,
    settle: async () => { await Promise.all(pending); await new Promise(setImmediate); } };
}

test('the actual Chat editor load/build pair preserves all 17 provider variants', () => {
  assert.deepEqual(owners.SEARCH_TYPE_ORDER, ['bing_local', 'amber_agent', 'zhipu', 'tavily', 'exa',
    'searxng', 'linkup', 'brave', 'serper', 'serpapi', 'metaso', 'ollama', 'perplexity',
    'firecrawl', 'jina', 'bocha', 'grok']);
  const editor = new owners.ChatSearchServiceEditorSession();
  const fields = { apiKey: ' secret ', depth: 'deep', url: 'https://search.example', engines: 'google,bing',
    language: 'zh', username: 'user', password: 'password', searchUrl: 'https://search.example/',
    scrapeUrl: 'https://reader.example/', model: 'grok-test', customUrl: 'https://custom.example',
    systemPrompt: 'Keep this prompt', maxTokens: 42, maxTokensPerPage: 24, summary: false };
  for (const type of owners.SEARCH_TYPE_ORDER) {
    const options = services.makeSearchServiceOptions(type);
    options.id = `fixture-${type}`;
    for (const key of Object.keys(options)) if (Object.hasOwn(fields, key)) options[key] = fields[key];
    if (type === 'tavily') options.depth = 'advanced';
    editor.loadDraft(options);
    assert.deepEqual(editor.draftOptions(), options, type);
  }
});

test('Chat keeps its signed-integer token policy and only Jina URLs are trimmed', () => {
  const editor = new owners.ChatSearchServiceEditorSession();
  editor.loadDraft({ ...services.makeSearchServiceOptions('perplexity'), maxTokens: 0, maxTokensPerPage: -1 });
  assert.equal(editor.dMaxTokens, ''); assert.equal(editor.dMaxTokensPerPage, '');
  editor.dApiKey = ' secret '; editor.dMaxTokens = ' -7 '; editor.dMaxTokensPerPage = '+12';
  assert.equal(editor.draftOptions().maxTokens, -7); assert.equal(editor.draftOptions().maxTokensPerPage, 12);
  editor.dMaxTokens = '12junk'; assert.equal(editor.draftOptions().maxTokens, null);
  editor.loadDraft(services.makeSearchServiceOptions('jina'));
  editor.dSearchUrl = ' https://s.example/ '; editor.dScrapeUrl = ' https://r.example/ ';
  assert.equal(editor.draftOptions().searchUrl, 'https://s.example/');
  assert.equal(editor.draftOptions().scrapeUrl, 'https://r.example/');
});

test('changing a type creates a fresh identity and resets all previous provider fields without writing', async () => {
  const f = await fixture(), editor = f.page.editor;
  editor.openAdd(); const prior = editor.dId;
  editor.dApiKey = 'old'; editor.dUrl = 'old'; editor.dModel = 'old'; editor.dSummary = false;
  editor.typeMenuOpen = true; editor.onTypeChange('bocha');
  assert.notEqual(editor.dId, prior); assert.equal(editor.dType, 'bocha');
  assert.equal(editor.dApiKey, ''); assert.equal(editor.dUrl, ''); assert.equal(editor.dModel, '');
  assert.equal(editor.dSummary, true); assert.equal(editor.typeMenuOpen, false); assert.equal(f.writes.length, 0);
});

test('add prepends and enables its identity; type-changing edit replaces enabled identity without moving selected index', async () => {
  const first = services.makeSearchServiceOptions('exa'), second = services.makeSearchServiceOptions('tavily');
  const f = await fixture([first, second]), { settings, editor } = f.page;
  await settings.applyPatch(next => { next.searchEnabledServiceIds = [second.id]; next.searchServiceSelected = 1; });
  editor.openEdit(second, 1); editor.onTypeChange('grok'); const updatedId = editor.dId;
  editor.dApiKey = 'saved'; editor.confirmEdit(); await f.settle();
  assert.equal(settings.s.searchServices[1].id, updatedId);
  assert.deepEqual(settings.s.searchEnabledServiceIds, [updatedId]); assert.equal(settings.s.searchServiceSelected, 1);
  editor.openAdd(); const addedId = editor.dId; editor.confirmAdd(); await f.settle();
  assert.equal(settings.s.searchServices[0].id, addedId); assert.equal(settings.s.searchServiceSelected, 0);
  assert.deepEqual(settings.s.searchEnabledServiceIds, [addedId, updatedId]);
});

test('edit resolves the target identity in the latest prefs rather than the stale row index', async () => {
  const a = services.makeSearchServiceOptions('exa'), b = services.makeSearchServiceOptions('tavily');
  const f = await fixture([a, b]), { settings, editor } = f.page;
  editor.openEdit(b, 1); editor.dApiKey = 'current-target';
  await settings.applyPatch(next => { next.searchServices = [b, a]; });
  editor.confirmEdit(); await f.settle();
  assert.equal(settings.s.searchServices[0].apiKey, 'current-target');
  assert.equal(settings.s.searchServices[1].id, a.id);
});

test('reordering and deletion preserve the selected identity, and the last service cannot be deleted', async () => {
  const a = services.makeSearchServiceOptions('exa'), b = services.makeSearchServiceOptions('tavily'), c = services.makeSearchServiceOptions('grok');
  const f = await fixture([a, b, c]), { settings, editor } = f.page;
  await settings.applyPatch(next => { next.searchServiceSelected = 1; next.searchEnabledServiceIds = [a.id, b.id]; });
  settings.moveService(b.id, -1); await f.settle();
  assert.equal(settings.s.searchServices[settings.s.searchServiceSelected].id, b.id);
  editor.openEdit(a, 1); editor.confirmDelete(); await f.settle();
  assert.equal(settings.s.searchServices[settings.s.searchServiceSelected].id, b.id);
  assert.deepEqual(settings.s.searchEnabledServiceIds, [b.id]);
  editor.openEdit(b, 0); editor.confirmDelete(); await f.settle();
  assert.equal(settings.s.searchServices[0].id, c.id); assert.equal(settings.s.searchServiceSelected, 0);
  editor.openEdit(c, 0); const before = f.writes.length; editor.confirmDelete();
  await Promise.allSettled(f.pending); await new Promise(setImmediate);
  assert.equal(f.writes.length, before); assert.equal(editor.editorVisible, true);
  assert.match(editor.editorError, /至少保留一个/);
});

test('main preference edits use the real queue and clamp result count while retaining unrelated fields', async () => {
  const f = await fixture(), { settings } = f.page;
  const initial = settings.s;
  settings.setResultSize(99); settings.setMaster(!initial.enableWebSearch); settings.setBuiltin('wikipedia', false);
  await f.settle();
  assert.equal(settings.s.searchCommonOptions.resultSize, 40);
  assert.equal(settings.s.enableWebSearch, !initial.enableWebSearch);
  assert.equal(settings.s.searchBuiltinWikipediaEnabled, false);
  assert.equal(initial.searchCommonOptions.resultSize, 10, 'prefs transform does not mutate its previous snapshot');
  settings.setResultSize(-1); await f.settle(); assert.equal(settings.s.searchCommonOptions.resultSize, 1);
});

test('rapid result increments and moves apply to the latest queued preferences', async () => {
  const a = services.makeSearchServiceOptions('exa'), b = services.makeSearchServiceOptions('tavily'), c = services.makeSearchServiceOptions('grok');
  const f = await fixture([a, b, c]), { settings } = f.page;
  settings.adjustResultSize(1); settings.adjustResultSize(1);
  settings.moveService(a.id, 1); settings.moveService(a.id, 1);
  await f.settle();
  assert.equal(settings.s.searchCommonOptions.resultSize, 12);
  assert.deepEqual(settings.s.searchServices.map(service => service.id), [b.id, c.id, a.id]);
  assert.equal(settings.s.searchServices[settings.s.searchServiceSelected].id, a.id);
  settings.setResultSize(40); await f.settle();
  const before = f.writes.length;
  settings.adjustResultSize(1); settings.moveService(a.id, 1); await f.settle();
  assert.equal(settings.s.searchCommonOptions.resultSize, 40);
  assert.equal(f.writes.length, before, 'bounds produce no preference write');
});

test('editing or deleting a vanished identity preserves the other services and keeps the draft visible', async () => {
  for (const action of ['confirmEdit', 'confirmDelete']) {
    const a = services.makeSearchServiceOptions('exa'), b = services.makeSearchServiceOptions('tavily'), c = services.makeSearchServiceOptions('grok');
    const f = await fixture([a, b, c]), { settings, editor } = f.page;
    editor.openEdit(b, 1); editor.dApiKey = 'preserved draft';
    await settings.applyPatch(next => { next.searchServices = [a, c]; });
    const before = f.writes.length;
    editor[action]();
    await Promise.allSettled(f.pending); await new Promise(setImmediate);
    assert.deepEqual(settings.s.searchServices, [a, c], action);
    assert.equal(f.writes.length, before, action);
    assert.equal(editor.editorVisible, true, action);
    assert.equal(editor.dApiKey, 'preserved draft', action);
    assert.match(editor.editorError, /已删除/, action);
    assert.equal(editor.editorSaving, false, action);
  }
});

test('main preference write errors are observable and a later successful edit clears the error', async () => {
  const f = await fixture(), { settings } = f.page;
  f.failures.put = true;
  settings.setMaster(true);
  await Promise.allSettled(f.pending); await new Promise(setImmediate);
  assert.equal(settings.s.enableWebSearch, false);
  assert.match(settings.error, /保存.*失败/);
  f.failures.put = false;
  settings.setMaster(true);
  await Promise.allSettled(f.pending); await new Promise(setImmediate);
  assert.equal(settings.s.enableWebSearch, true);
  assert.equal(settings.error, '');
});

test('the actual page gives back to the type menu before editor and restores its prior keyboard mode', async () => {
  const f = await fixture(), editor = f.page.editor;
  editor.openAdd(); editor.typeMenuOpen = true;
  assert.equal(f.page.onBackPress(), true); assert.equal(editor.typeMenuOpen, false); assert.equal(editor.editorVisible, true);
  assert.equal(f.page.onBackPress(), true); assert.equal(editor.editorVisible, false);
  assert.equal(f.page.onBackPress(), false);
  f.page.onPageShow(); f.page.onPageHide(); assert.deepEqual(f.modes, [1, 7]);
  f.page.onPageShow(); f.page.aboutToDisappear(); assert.deepEqual(f.modes, [1, 7, 1, 7]);
});
