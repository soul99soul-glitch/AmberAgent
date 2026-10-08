const { test } = require('node:test');
const assert = require('node:assert/strict');
const { actualPage } = require('./deepread_ui_fixture.cjs');

const haptics = { selection() {}, success() {} };
const ui = { animateTo: (_options, apply) => apply() };
const prefs = () => ({ enableWebSearch: true, searchCommonOptions: { resultSize: 12, language: 'zh', includeDomains: ['example.org'] },
  searchServices: [{ id: 'a', type: 'tavily', apiKey: 'original', secret: 'keep' }, { id: 'b', type: 'bocha', apiKey: 'second' }],
  searchServiceSelected: 0, searchEnabledServiceIds: ['a', 'b'], searchBuiltinDuckDuckGoEnabled: true,
  searchBuiltinBingEnabled: true, searchBuiltinJinaEnabled: true, searchBuiltinWikipediaEnabled: true,
  searchBuiltinHackerNewsEnabled: true, searchGoogleWebViewFallbackEnabled: false });

test('settings form delegates its complete edited draft to the page save owner', async () => {
  let stored = prefs(), applied = 0;
  const page = actualPage('pages/SettingDeepReadPage.ets', ['saveStandaloneSettings'], {
    getChatKvStore: () => ({}), updateSearchPrefs: async (_store, transform) => { stored = transform(stored); },
  });
  Object.assign(page, { clearSavedNotice() {}, standaloneSearchLoaded: true, selectedSearchId: 'a', standaloneSearch: structuredClone(stored),
    saveStandaloneReading: async () => { applied++; } });
  const form = actualPage('components/deepread/DeepReadSettingsForm.ets', ['changeSearchFlag', 'selectSearch', 'changeResultSize', 'saveSettings'], { DeepReadHaptics: haptics });
  Object.assign(form, { alive: true, searchLoaded: true, searchError: '', saving: false, error: '', onEdited() {}, onSave: () => page.saveStandaloneSettings() });
  for (const [child, parent] of [['search', 'standaloneSearch'], ['selectedSearchId', 'selectedSearchId']]) {
    Object.defineProperty(form, child, { get: () => page[parent], set: value => { page[parent] = value; } });
  }
  form.selectSearch(1); form.changeSearchFlag(2, false); form.changeSearchFlag(5, true); form.changeResultSize(1);
  page.standaloneSearch.searchServices.reverse(); page.standaloneSearch.searchServices[1].apiKey = 'new key'; stored.searchCommonOptions.language = 'en';
  await form.saveSettings();
  assert.equal(stored.searchServiceSelected, 0, 'selection follows the selected ID after a service reorder');
  assert.equal(stored.searchServices[1].apiKey, 'new key'); assert.deepEqual(stored.searchEnabledServiceIds, ['a', 'b']);
  assert.equal(stored.searchCommonOptions.language, 'en'); assert.deepEqual(stored.searchCommonOptions.includeDomains, ['example.org']);
  assert.equal(stored.searchCommonOptions.resultSize, 13); assert.equal(stored.searchBuiltinJinaEnabled, false);
  assert.equal(stored.searchGoogleWebViewFallbackEnabled, true); assert.equal(form.saving, false); assert.equal(applied, 1);
});

test('a service removed from the current draft leaves an actionable error without applying reading settings', async () => {
  let applied = 0;
  const page = actualPage('pages/SettingDeepReadPage.ets', ['saveStandaloneSettings'], { getChatKvStore: () => ({}), updateSearchPrefs: async () => {} });
  Object.assign(page, { clearSavedNotice() {}, standaloneSearchLoaded: true, standaloneSearch: prefs(), selectedSearchId: 'a', saveStandaloneReading: async () => { applied++; } });
  page.standaloneSearch.searchServices = [page.standaloneSearch.searchServices[1]];
  const form = actualPage('components/deepread/DeepReadSettingsForm.ets', ['saveSettings'], {});
  Object.assign(form, { alive: true, searchLoaded: true, saving: false, error: '', onSave: () => page.saveStandaloneSettings() });
  await form.saveSettings(); assert.match(form.error, /已删除/); assert.equal(applied, 0); assert.equal(form.saving, false);
});

for (const product of ['deepread', 'agent']) {
  test(`fresh ${product} settings use the same default font as the real reader and save that choice`, async () => {
    const values = new Map();
    const storage = { get: async (key, fallback) => values.has(key) ? values.get(key) : fallback,
      set: async (key, value) => values.set(key, value) };
    const page = actualPage('pages/SettingDeepReadPage.ets', ['loadPage', 'saveStandaloneReading', 'clearSavedNotice'], {
      loadDeepReadAppearance: async () => {}, DeepReadHaptics: haptics, Curve: { EaseOut: 'ease' }, getAppContainer: () => ({ storage }), getProductKind: () => product, HOTLIST_PRESETS: [],
      getModelRegistry: () => ({ list: async () => [] }), loadCustomTemplates: async () => [], setTimeout: () => 1, clearTimeout: () => {},
    });
    Object.assign(page, { pageAlive: true, loadToken: 1, confirmationTimer: -1, refreshTaskModelSelection: async () => {}, pageLoad: Promise.resolve(), modelDraftDirty: false, getUIContext: () => ui });
    await page.loadPage(1); assert.equal(page.fontModeIndex, product === 'deepread' ? 1 : 0);
    const reader = actualPage('pages/DeepReadArticlePage.ets', ['loadDisplaySettings'], {
      loadDeepReadAppearance: async () => {}, getAppContainer: () => ({ storage }), getChatKvStore: () => ({ get: async key => values.get(key) ?? null }),
      getProductKind: () => product, FONT_SCALE_MIN: 0.7, FONT_SCALE_MAX: 1.8,
    });
    Object.assign(reader, { pageAlive: true, pageToken: 1 }); await reader.loadDisplaySettings(1);
    assert.equal(reader.fontSerif, page.fontModeIndex === 1);
    page.fontScalePercent = 125; await page.saveStandaloneReading();
    await reader.loadDisplaySettings(1); assert.equal(reader.fontScale, 1.25); assert.equal(reader.fontSerif, page.fontModeIndex === 1);
    assert.equal(values.get('deepread_template_id'), 'none');
  });
}

for (const params of [{ standaloneTabRoot: true }, {}, { standaloneTabRoot: false }]) {
  test(`Settings initializes the explicit tab root marker ${JSON.stringify(params)} before stack cleanup`, async () => {
    const page = actualPage('pages/SettingDeepReadPage.ets', ['aboutToAppear', 'readStandaloneRoot', 'restoreSessionDraft'], {
      SETTINGS_SESSION_DRAFT: 'deepreadSettingsSessionDraft', AppStorage: { get: () => undefined }, getProductKind: () => 'deepread', router: { getLength: () => '2', getParams: () => params },
    });
    Object.assign(page, { pageAlive: false, loadToken: 0, standaloneRoot: false, loadPage: async () => {} });
    page.aboutToAppear(); await page.pageLoad;
    assert.equal(page.standaloneRoot, params.standaloneTabRoot === true);
  });
}

test('host Settings ignores the standalone marker on a nested route', async () => {
  const page = actualPage('pages/SettingDeepReadPage.ets', ['aboutToAppear', 'readStandaloneRoot', 'restoreSessionDraft'], {
    getProductKind: () => 'agent', router: { getLength: () => '2', getParams: () => ({ standaloneTabRoot: true }) },
  });
  Object.assign(page, { pageAlive: false, loadToken: 0, standaloneRoot: false, loadPage: async () => {} });
  page.aboutToAppear(); await page.pageLoad; assert.equal(page.standaloneRoot, false);
});


test('search drafts survive unrelated child returns without being overwritten by saved services', async () => {
  let stored = prefs();
  const page = actualPage('pages/SettingDeepReadPage.ets', ['refreshStandaloneSearch'], {
    loadSearchPrefs: async () => structuredClone(stored), getChatKvStore: () => ({}), consumeDeepReadSearchEditorDraft: () => null,
  });
  Object.assign(page, { pageAlive: true, searchLoadToken: 0, standaloneSearchLoaded: false, standaloneSearchError: '', selectedSearchId: '', pendingSearchDraft: null });
  await page.refreshStandaloneSearch();
  const mountForm = () => {
    const form = actualPage('components/deepread/DeepReadSettingsForm.ets', ['selectSearch', 'changeResultSize', 'changeSearchFlag'], { DeepReadHaptics: haptics });
    for (const [child, parent] of [['search', 'standaloneSearch'], ['selectedSearchId', 'selectedSearchId'], ['searchError', 'standaloneSearchError']]) {
      Object.defineProperty(form, child, { get: () => page[parent], set: value => { page[parent] = value; } });
    }
    form.onEdited = () => {}; return form;
  };
  const first = mountForm(); first.selectSearch(1); first.changeResultSize(3); first.changeSearchFlag(0, false);
  stored.searchServices.reverse(); stored.searchServices[0].apiKey = 'edited in provider page';
  await page.refreshStandaloneSearch();
  const remounted = mountForm();
  assert.equal(remounted.search.searchCommonOptions.resultSize, 15);
  assert.equal(remounted.search.searchBuiltinDuckDuckGoEnabled, false);
  assert.equal(remounted.selectedSearchId, 'b'); assert.equal(remounted.search.searchServiceSelected, 1);
  assert.equal(remounted.search.searchServices[1].apiKey, 'second');
  stored.searchServices = [stored.searchServices[1]];
  await page.refreshStandaloneSearch(); assert.equal(page.selectedSearchId, 'b');
  assert.equal(page.standaloneSearch.searchServiceSelected, 1); assert.equal(page.standaloneSearchError, '');
});

test('a template chosen in the child replaces stale parent selection, while unchanged persistence keeps the parent draft', async () => {
  let stored = 'none';
  const page = actualPage('pages/SettingDeepReadPage.ets', ['refreshTemplateSelection'], {});
  Object.assign(page, { pageLoad: Promise.resolve(), storage: { get: async () => stored }, pageAlive: true, loadToken: 1,
    persistedTemplateId: 'none', templateId: 'parent-unsaved', clearSavedNotice() {} });
  await page.refreshTemplateSelection(); assert.equal(page.templateId, 'parent-unsaved');
  stored = 'child-selection'; await page.refreshTemplateSelection();
  assert.equal(page.templateId, 'child-selection'); assert.equal(page.persistedTemplateId, stored);
});

test('independent model selection stages the real pair and applies it only with Save', async () => {
  const writes = []; let storedFont;
  const option = { providerId: 'provider', modelId: 'model', providerLabel: '服务', label: '模型', available: true };
  const page = actualPage('pages/SettingDeepReadPage.ets', ['selectTaskModel', 'refreshTaskModelSelection', 'saveStandaloneReading', 'clearSavedNotice'], {
    getProductKind: () => 'deepread', loadDeepReadAppearance: async () => {}, DeepReadHaptics: haptics, Curve: { EaseOut: 'ease' },
    saveDeepReadModelSelection: async selected => { writes.push(selected); return { kind: 'fixed', pair: { providerId: selected.providerId, modelId: selected.modelId } }; },
    loadDeepReadModelSelection: async () => ({ reference: { kind: 'auto' }, options: [option], label: 'persisted model', detail: '' }),
    setTimeout: () => 1, clearTimeout() {},
  });
  Object.assign(page, { pageAlive: true, loadToken: 1, modelLoadToken: 0, savingModel: false, confirmationTimer: -1,
    modelDraftDirty: false, taskModelOptions: [option], pageLoad: Promise.resolve(), fontModeIndex: 1, fontScalePercent: 125, templateId: 'none',
    storage: { set: async (key, value) => { if (key === 'deepread_font_scale') storedFont = value; } }, getUIContext: () => ui });
  await page.selectTaskModel(option); assert.equal(writes.length, 0); assert.equal(page.modelDraftDirty, true);
  await page.refreshTaskModelSelection(); assert.equal(page.taskModelLabel, '服务 / 模型'); assert.equal(page.taskModelReference.kind, 'fixed');
  await page.saveStandaloneReading(); assert.equal(writes.length, 1); assert.equal(writes[0].modelId, 'model');
  assert.equal(storedFont, 125); assert.equal(page.modelDraftDirty, false); assert.match(page.saved, /已保存/);
});

test('template route receives staged reading preferences without a persistence side effect', () => {
  let route;
  const form = actualPage('components/deepread/DeepReadSettingsForm.ets', ['openTemplates'], { router: { pushUrl: value => { route = value; } } });
  Object.assign(form, { fontModeIndex: 1, fontScalePercent: 145 }); form.openTemplates();
  assert.deepEqual(route.params, { readerFontMode: 'serif', readerFontScale: 145 });
});


test('a staged model persistence failure leaves Save visibly unsuccessful and preserves the staged pair for retry', async () => {
  const option = { providerId: 'provider', modelId: 'model', available: true };
  const page = actualPage('pages/SettingDeepReadPage.ets', ['saveStandaloneReading', 'clearSavedNotice'], {
    Curve: { EaseOut: 'ease' }, DeepReadHaptics: haptics, clearTimeout() {}, setTimeout: () => 1,
    saveDeepReadModelSelection: async () => { throw Error('disk'); },
  });
  let readingWrites = 0;
  Object.assign(page, { pageAlive: true, loadToken: 1, pageLoad: Promise.resolve(), confirmationTimer: -1, saved: '已保存（旧）',
    modelDraftDirty: true, taskModelReference: { kind: 'fixed', pair: { providerId: 'provider', modelId: 'model' } },
    taskModelOptions: [option], storage: { set: async () => { readingWrites++; } }, getUIContext: () => ui });
  await assert.rejects(page.saveStandaloneReading(), /disk/);
  assert.equal(page.saved, ''); assert.equal(page.modelDraftDirty, true); assert.equal(readingWrites, 0);
});

function settingsArrival() {
  const pending = new Map(), animations = [], memory = new Map([['deepreadTabDirection', 1]]); let timerId = 0;
  const page = actualPage('pages/SettingDeepReadPage.ets', ['onPageShow', 'onPageHide', 'aboutToDisappear', 'saveSessionDraft', 'arrive', 'settleArrival', 'motionPolicyChanged', 'restoreSettingsScroll', 'readStandaloneRoot'], {
    getProductKind: () => 'deepread', router: { getLength: () => '1', getParams: () => ({}) }, markWantRoutingReady() {},
    SETTINGS_SESSION_DRAFT: 'deepreadSettingsSessionDraft',
    AppStorage: { get: key => memory.get(key), setOrCreate: (key, value) => memory.set(key, value), delete: key => memory.delete(key) }, curves: { springCurve: () => 'spring' },
    setTimeout: (fn, delay) => { assert.equal(delay, 16); pending.set(++timerId, fn); return timerId; }, clearTimeout: id => pending.delete(id),
  });
  let offset = 245;
  Object.assign(page, { pageAlive: true, pageVisible: false, standaloneRoot: true, firstArrival: true, restoreScrollPending: true,
    storage: null, settingsEdited: false, modelDraftDirty: false, arrivalTimer: -1, arrivalOffset: 0, reduceMotion: false, appBackgrounded: false, activeTab: 2,
    loadToken: 1, modelLoadToken: 0, searchLoadToken: 0, confirmationTimer: -1, refreshStandaloneSearch() {},
    dockScroll: { reset() {} }, settingsScroller: { currentOffset: () => ({ yOffset: offset }), scrollTo: value => { offset = value.yOffset; } },
    getUIContext: () => ({ getRouter: () => 'bound', animateTo: (options, apply) => { animations.push(options); apply(); } }) });
  return { page, pending, animations, memory, offset: () => offset,
    frame() { const [id, fn] = pending.entries().next().value; pending.delete(id); fn(); } };
}

test('Settings root defers the initial animation frame and keeps scroll/back-return semantics', () => {
  const f = settingsArrival(); f.page.onPageShow();
  assert.equal(f.page.arrivalOffset, 30); assert.equal(f.animations.length, 0); assert.equal(f.pending.size, 1);
  f.frame(); assert.equal(f.page.arrivalOffset, 0); assert.equal(f.animations.length, 1);
  f.page.onPageHide(); assert.equal(f.memory.get('deepreadSettingsOffset'), 245);
  f.memory.set('deepreadSettingsOffset', 350); f.page.restoreSettingsScroll(); assert.equal(f.offset(), 350);
  f.page.onPageShow(); assert.equal(f.pending.size, 0); assert.equal(f.animations.length, 1);
});

for (const cancel of ['hide', 'disappear', 'reduceMotion', 'appBackgrounded', 'activeTab']) {
  test(`Settings deferred entrance cancels on ${cancel} and does not replay`, () => {
    const f = settingsArrival(); f.page.onPageShow(); const late = f.pending.values().next().value;
    if (cancel === 'hide') f.page.onPageHide();
    else if (cancel === 'disappear') f.page.aboutToDisappear();
    else { f.page[cancel] = cancel === 'activeTab' ? 0 : true; f.page.motionPolicyChanged(); }
    assert.equal(f.pending.size, 0); assert.equal(f.page.arrivalOffset, 0);
    f.page.pageAlive = true; f.page.pageVisible = true; f.page.reduceMotion = false; f.page.appBackgrounded = false; f.page.activeTab = 2;
    late(); f.page.arrive(); assert.equal(f.animations.length, 0); assert.equal(f.pending.size, 0);
  });
}
