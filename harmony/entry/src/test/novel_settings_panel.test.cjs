const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');

// Run the page's async persistence methods, including the actual sheet session ownership.
function fixture(reads = {}) {
  const source = fs.readFileSync(path.join(__dirname, '../main/ets/pages/NovelStandaloneSettingsPage.ets'), 'utf8');
  const methods = source.slice(source.indexOf('  private labelFor('), source.indexOf('  private modelPanelTransition('))
    .replace(/private /g, '');
  const back = source.slice(source.indexOf('  onBackPress()'), source.indexOf('  private backgroundChanged('))
    .replace(/private /g, '');
  const reload = source.slice(source.indexOf('  private async reload('), source.indexOf('  private labelFor('))
    .replace(/private /g, '');
  const navigation = source.slice(source.indexOf('  private navigate('), source.indexOf('  private enableKeyboardAvoidance('))
    .replace(/private /g, '');
  const compiled = ts.transpileModule('class Page {' + methods + back + reload + navigation + '} return Page;',
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const writes = [];
  let resolve, reject;
  const saving = new Promise((done, fail) => { resolve = done; reject = fail; });
  const save = value => { writes.push(value); return saving; };
  const read = async (key, value) => {
    if (reads.failAt === key) throw new Error(`${key} read failed`);
    return reads[key] ?? value;
  };
  const routes = [];
  const Page = new Function('saveNovelModelDefaults', 'saveNovelPolishContext',
    'saveNovelResearchEnabled', 'getChatKvStore', 'router', 'loadNovelModelOptions',
    'describeChatModel', 'loadNovelModelDefaults', 'loadNovelResearchEnabled', 'loadNovelPolishContext', compiled)(
    save, save, (_store, value) => save(value), () => ({}),
    { back() {}, pushUrl(value) { routes.push(value); return Promise.resolve(); } },
    () => read('options', []), () => read('chat', 'global-model'),
    () => read('defaults', { writing: { kind: 'global' }, review: { kind: 'global' }, stateSync: { kind: 'global' } }),
    () => read('research', false),
    () => read('context', { includePlot: true, includeForeshadows: true, includeCharacters: true, includeDecisions: true }));
  const page = new Page();
  Object.assign(page, { alive: true, visible: true, loading: false, ready: true, saving: false,
    appBackgrounded: false, pickingRole: null, panelSession: 0, panelError: '', saveToken: 0,
    loadToken: 0, error: '', savedMessage: '', options: [], chatModelLabel: 'global-model',
    defaults: { writing: { kind: 'global' }, review: { kind: 'global' }, stateSync: { kind: 'global' } },
    includePlot: true, includeForeshadows: true, includeCharacters: true, includeDecisions: true,
    researchEnabled: false });
  return { page, writes, routes, resolve, reject };
}
const option = { providerId: 'provider', modelId: 'model', label: 'Model', providerLabel: 'Provider', available: true };

test('responsibility selection persists only the chosen role and dismisses after successful save', async () => {
  for (const role of ['writing', 'review', 'stateSync']) {
    const f = fixture(); f.page.openModelPanel(role);
    const choosing = f.page.chooseModel(role, option, f.page.panelSession);
    assert.equal(f.page.pickingRole, role);
    await f.page.chooseModel(role, option, f.page.panelSession);
    assert.equal(f.writes.length, 1);
    for (const field of ['writing', 'review', 'stateSync']) {
      assert.equal(f.writes[0][field].kind, field === role ? 'fixed' : 'global');
    }
    f.resolve(); await choosing;
    assert.equal(f.page.defaults[role].modelId, 'model');
    assert.equal(f.page.pickingRole, null);
    assert.equal(f.page.saving, false);
  }
});

test('Back closes the active panel; an earlier save or closing callback cannot close a reopened panel', async () => {
  const f = fixture(); f.page.openModelPanel('writing');
  const previousSession = f.page.panelSession;
  const choosing = f.page.chooseModel('writing', option, previousSession);
  assert.equal(f.page.onBackPress(), true);
  f.page.openModelPanel('review');
  const currentSession = f.page.panelSession;
  f.page.closeModelPanel(previousSession);
  assert.equal(f.page.pickingRole, 'review');
  f.resolve(); await choosing;
  assert.equal(f.page.pickingRole, 'review');
  assert.equal(f.page.panelSession, currentSession);
  assert.equal(f.page.defaults.writing.modelId, 'model');
});

test('failed model save retains selection, reports the failure in the active sheet, and rejects unavailable choices', async () => {
  const f = fixture(); f.page.openModelPanel('review');
  await f.page.chooseModel('review', { ...option, available: false }, f.page.panelSession);
  assert.equal(f.writes.length, 0);
  const choosing = f.page.chooseModel('review', option, f.page.panelSession);
  f.reject(new Error('disk full')); await choosing;
  assert.equal(f.page.pickingRole, 'review');
  assert.equal(f.page.defaults.review.kind, 'global');
  assert.match(f.page.panelError, /disk full/);
  assert.equal(f.page.saving, false);
});

test('polish context saves on change, protects pending exit and restores the switch when persistence fails', async () => {
  const f = fixture();
  const changing = f.page.changeContext('foreshadows', false);
  assert.equal(f.page.includeForeshadows, false);
  assert.equal(f.writes[0].includeForeshadows, false);
  assert.equal(f.page.onBackPress(), true);
  await f.page.changeContext('plot', false);
  assert.equal(f.writes.length, 1);
  f.reject(new Error('preferences unavailable')); await changing;
  assert.equal(f.page.includeForeshadows, true);
  assert.equal(f.page.includePlot, true);
  assert.match(f.page.error, /preferences unavailable/);
  assert.equal(f.page.onBackPress(), false);
});

test('leaving the page invalidates stale picker actions without clearing an unavailable fixed binding', async () => {
  const f = fixture(); f.page.defaults.review = { kind: 'fixed', providerId: 'removed', modelId: 'kept' };
  assert.match(f.page.labelFor(f.page.defaults.review), /模型已移除/);
  f.page.openModelPanel('review'); const session = f.page.panelSession;
  f.page.visible = false;
  await f.page.chooseModel('review', null, session);
  assert.equal(f.writes.length, 0);
  assert.equal(f.page.defaults.review.modelId, 'kept');
});


test('failed initial reload never enables whole-object preference writes, while provider and retry stay reachable', async () => {
  for (const failAt of ['options', 'chat', 'defaults', 'research', 'context']) {
    const reads = { failAt };
    const f = fixture(reads); f.page.ready = false; f.page.loading = true;
    await f.page.reload();
    assert.equal(f.page.ready, false);
    assert.equal(f.page.loading, false);
    assert.match(f.page.error, new RegExp(`${failAt} read failed`));
    f.page.openModelPanel('writing');
    assert.equal(f.page.pickingRole, null);
    // Even a previously captured picker callback is unable to persist uninitialized defaults.
    f.page.pickingRole = 'writing';
    await f.page.chooseModel('writing', option, f.page.panelSession);
    f.page.pickingRole = null;
    for (const field of ['plot', 'foreshadows', 'characters', 'decisions']) {
      await f.page.changeContext(field, false);
    }
    await f.page.saveResearch(true);
    assert.equal(f.writes.length, 0);
    assert.equal(f.page.includePlot, true);
    assert.equal(f.page.includeForeshadows, true);
    assert.equal(f.page.includeCharacters, true);
    assert.equal(f.page.includeDecisions, true);
    assert.equal(f.page.researchEnabled, false);
    assert.match(f.page.error, new RegExp(`${failAt} read failed`));
    f.page.navigate('pages/ChatProviderSettingsPage');
    assert.equal(f.routes[0].url, 'pages/ChatProviderSettingsPage');
    assert.equal(f.page.onBackPress(), false);

    reads.failAt = '';
    reads.defaults = { writing: { kind: 'global' }, review: { kind: 'fixed', providerId: 'existing', modelId: 'retain' }, stateSync: { kind: 'global' } };
    reads.context = { includePlot: false, includeForeshadows: false, includeCharacters: false, includeDecisions: false };
    await f.page.reload();
    assert.equal(f.page.ready, true);
    assert.equal(f.page.error, '');
    assert.equal(f.page.includeCharacters, false);
    f.page.openModelPanel('writing');
    const choosing = f.page.chooseModel('writing', option, f.page.panelSession);
    assert.equal(f.writes.length, 1);
    assert.equal(f.writes[0].review.modelId, 'retain');
    f.resolve(); await choosing;
  }
});

test('refresh invalidates earlier ready state until a complete success and never reads during persistence', async () => {
  const reads = { failAt: 'context' }; const f = fixture(reads);
  const reloading = f.page.reload();
  assert.equal(f.page.ready, false);
  assert.equal(f.page.loading, true);
  await f.page.changeContext('plot', false);
  assert.equal(f.writes.length, 0);
  await reloading;
  assert.equal(f.page.ready, false);
  await f.page.saveResearch(true);
  assert.equal(f.writes.length, 0);

  reads.failAt = ''; await f.page.reload();
  const changing = f.page.changeContext('plot', false);
  reads.failAt = 'options';
  await f.page.reload();
  assert.equal(f.page.ready, true);
  assert.equal(f.page.includePlot, false);
  f.resolve(); await changing;
  assert.equal(f.page.error, '');
});
