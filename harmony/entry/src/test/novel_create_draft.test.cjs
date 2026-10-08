const { test } = require('node:test');
const assert = require('node:assert/strict');
const { actualPage } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');

function fixture() {
  const dialogs = [], opened = [], created = [];
  let restores = 0;
  const page = actualPage('pages/NovelProjectsPage.ets', [
    'openCreateSheet', 'dismissCreateSheet', 'hasCreateDraft', 'isCreateSessionActive',
    'requestDismissCreateSheet', 'onBackPress', 'submitCreate',
  ], {
    AlertDialog: { show: dialog => dialogs.push(dialog) }, ERROR: 'red',
    novelStorySeedText: story => story.coreIdea,
    getNovelCreation: () => ({ create: async (...args) => { created.push(args); return { id: 'created' }; } }),
  });
  Object.assign(page, {
    pageAlive: true, designVisible: true, createSession: 0, createSheetOpen: false,
    createExitPromptOpen: false, creating: false, importing: false, restoring: false,
    importPreview: null, recoveryPreview: null, errorMsg: '',
    enableKeyboardAvoidance() {}, restoreKeyboardAvoidance() { restores++; },
    openWorkspace: (...args) => opened.push(args),
  });
  page.openCreateSheet();
  return { page, dialogs, opened, created, get restores() { return restores; } };
}

test('a retained creation sheet reacquires keyboard resize after a system screen returns', () => {
  let mode = 'offset';
  const page = actualPage('pages/NovelProjectsPage.ets', [
    'onPageShow', 'onPageHide', 'enableKeyboardAvoidance', 'restoreKeyboardAvoidance',
  ], { KeyboardAvoidMode: { RESIZE: 'resize' }, getProductKind: () => 'agent' });
  Object.assign(page, { createSheetOpen: true, keyboardAvoidanceActive: false, firstShowDone: false,
    getUIContext: () => ({ getKeyboardAvoidMode: () => mode, setKeyboardAvoidMode: next => { mode = next; } }),
    loadProjects: async () => {},
  });
  page.onPageShow(); assert.equal(mode, 'resize');
  page.onPageHide(); assert.equal(mode, 'offset');
  mode = 'none'; page.onPageShow(); assert.equal(mode, 'resize');
  page.onPageHide(); assert.equal(mode, 'none');
  page.createSheetOpen = false; page.onPageShow(); assert.equal(mode, 'none');
});

test('empty creation closes directly; name or any story seed preserves inputs until explicit discard', () => {
  const empty = fixture();
  empty.page.createName = '  ';
  assert.equal(empty.page.onBackPress(), true);
  assert.equal(empty.page.createSheetOpen, false);
  assert.equal(empty.dialogs.length, 0);
  assert.equal(empty.restores, 1);

  for (const field of ['createName', 'quickGenre', 'quickIdea', 'quickWorld', 'quickCharacters', 'quickDirection']) {
    const f = fixture(); f.page[field] = '保留草稿';
    f.page.requestDismissCreateSheet(); f.page.onBackPress();
    assert.equal(f.dialogs.length, 1, field);
    assert.equal(f.page.createSheetOpen, true, field);
    f.dialogs[0].primaryButton.action();
    assert.equal(f.page[field], '保留草稿', field);
    assert.equal(f.page.createSheetOpen, true, field);
    f.page.requestDismissCreateSheet(); f.dialogs[1].cancel();
    assert.equal(f.page[field], '保留草稿', field);
    f.page.onBackPress(); f.dialogs[2].secondaryButton.action();
    assert.equal(f.page.createSheetOpen, false, field);
    assert.equal(f.restores, 1, field);
  }
});

test('old confirmation callbacks cannot dismiss or alter a later creation session, or a hidden/dead page', () => {
  const f = fixture(); f.page.createName = '旧草稿';
  f.page.requestDismissCreateSheet(); const old = f.dialogs[0];
  f.page.dismissCreateSheet(); f.page.openCreateSheet(); f.page.createName = '新草稿';
  f.page.requestDismissCreateSheet();
  old.primaryButton.action(); old.cancel(); old.secondaryButton.action();
  assert.equal(f.page.createSheetOpen, true);
  assert.equal(f.page.createExitPromptOpen, true);
  assert.equal(f.page.createName, '新草稿');
  for (const flag of ['designVisible', 'pageAlive']) {
    const hidden = fixture(); hidden.page.createName = '草稿'; hidden.page.requestDismissCreateSheet();
    hidden.page[flag] = false; hidden.dialogs[0].secondaryButton.action();
    assert.equal(hidden.page.createSheetOpen, true, flag);
  }
});

test('creation in progress blocks all dismissal requests; successful creation closes without a discard prompt', async () => {
  const f = fixture(); f.page.createName = '长夜来信';
  f.page.requestDismissCreateSheet(); f.page.creating = true;
  f.dialogs[0].secondaryButton.action(); f.dialogs[0].primaryButton.action();
  f.page.requestDismissCreateSheet(); assert.equal(f.page.onBackPress(), true);
  assert.equal(f.page.createSheetOpen, true);
  assert.equal(f.dialogs.length, 1);
  f.page.creating = false; f.page.createExitPromptOpen = false;
  await f.page.submitCreate();
  assert.equal(f.dialogs.length, 1);
  assert.equal(f.page.createSheetOpen, false);
  assert.equal(f.page.creating, false);
  assert.deepEqual(f.opened, [['created', '']]);
  assert.deepEqual(f.created, [['长夜来信', undefined]]);
});

test('standalone model setup prompt tracks usable models on refresh and reports loading failures', async () => {
  let models = [{ available: false }], product = 'novel', fail = false, modelLoads = 0;
  let projects = [];
  const page = actualPage('pages/NovelProjectsPage.ets', ['loadProjects'], {
    getProductKind: () => product,
    getNovelCreation: () => ({ listProjectInventory: async () => ({ projects, failures: [] }) }),
    loadNovelModelOptions: async () => { modelLoads++; if (fail) throw Error('model catalog failed'); return models; },
    animateTo: (_options, change) => change(), MOTION_ENTER: 200, Curve: { EaseOut: 'out' },
  });
  Object.assign(page, { pageAlive: true, loadToken: 0, loadedOnce: false, modelSetupRequired: false });
  await page.loadProjects(); assert.equal(page.modelSetupRequired, true);
  models = [{ available: false }, { available: true }];
  await page.loadProjects(); assert.equal(page.modelSetupRequired, false);
  fail = true; projects = [{ id: 'healthy-local-project', name: '本地作品' }];
  await assert.rejects(page.loadProjects(), /model catalog failed/);
  assert.equal(page.modelSetupRequired, false); assert.equal(page.loading, false);
  assert.deepEqual(page.projects, projects, 'service configuration failure cannot hide local manuscripts');
  product = 'amber'; const previousLoads = modelLoads;
  await page.loadProjects(); assert.equal(modelLoads, previousLoads);
  assert.equal(page.modelSetupRequired, false);
});
