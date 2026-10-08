const { test } = require('node:test');
const assert = require('node:assert/strict');
const { actualPage } = require('./deepread_ui_fixture.cjs');
const KEY = 'deepreadSettingsSessionDraft';
const freshSearch = () => ({ searchCommonOptions: { resultSize: 10, language: 'zh' },
  searchServices: [{ id: 'a', type: 'tavily', apiKey: 'old-a' }, { id: 'b', type: 'bocha', apiKey: 'old-b' }],
  searchServiceSelected: 0, searchEnabledServiceIds: ['a', 'b'], searchBuiltinDuckDuckGoEnabled: true,
  searchBuiltinBingEnabled: true, searchBuiltinJinaEnabled: true, searchBuiltinWikipediaEnabled: true,
  searchBuiltinHackerNewsEnabled: true, searchGoogleWebViewFallbackEnabled: false });
const tick = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
function environment(memory = new Map()) {
  const values = new Map([['deepread_font_mode', 'serif'], ['deepread_font_scale', 100], ['deepread_template_id', 'none']]);
  const writes = [];
  const state = { search: freshSearch(), reference: { kind: 'auto' }, models: [
    { providerId: 'p', modelId: 'a', providerLabel: '服务', label: '模型 A', available: true },
    { providerId: 'p', modelId: 'b', providerLabel: '服务', label: '模型 B', available: true },
  ] };
  const storage = { get: async (key, fallback) => values.has(key) ? values.get(key) : fallback,
    set: async (key, value) => { writes.push([key, value]); values.set(key, value); } };
  const env = { loadDeepReadAppearance: async () => {}, consumeDeepReadSearchEditorDraft: () => null, SETTINGS_SESSION_DRAFT: KEY, HOTLIST_PRESETS: [],
    AppStorage: { get: key => memory.get(key), setOrCreate: (key, value) => memory.set(key, value), delete: key => memory.delete(key) },
    getAppContainer: () => ({ storage }), getChatKvStore: () => storage, getModelRegistry: () => ({ list: async () => [] }),
    loadCustomTemplates: async () => [], loadSearchPrefs: async () => structuredClone(state.search),
    updateSearchPrefs: async (_store, transform) => { state.search = transform(state.search); writes.push(['search', state.search]); },
    loadDeepReadModelSelection: async () => ({ reference: state.reference, options: state.models, label: '跟随当前模型', detail: '当前模型' }),
    saveDeepReadModelSelection: async option => { state.reference = option === null ? { kind: 'auto' }
      : { kind: 'fixed', pair: { providerId: option.providerId, modelId: option.modelId } }; writes.push(['model', state.reference]); return state.reference; },
    markWantRoutingReady() {}, DeepReadHaptics: { selection() {}, success() {} },
    Curve: { EaseOut: 'ease' }, curves: { springCurve: () => 'spring' }, setTimeout: () => 1, clearTimeout() {},
  };
  const createPage = (product = 'deepread', depth = 1) => {
    const page = actualPage('pages/SettingDeepReadPage.ets', ['aboutToAppear', 'onPageShow', 'onPageHide', 'aboutToDisappear',
      'readStandaloneRoot', 'restoreSessionDraft', 'saveSessionDraft', 'markSettingsEdited', 'clearSavedNotice', 'loadPage',
      'refreshStandaloneSearch', 'refreshTaskModelSelection', 'refreshTemplateSelection', 'selectTaskModel', 'saveStandaloneReading',
      'arrive', 'settleArrival', 'saveStandaloneSettings'], { ...env, getProductKind: () => product,
      router: { getLength: () => String(depth), getParams: () => ({}) } });
    Object.assign(page, { pageAlive: false, pageVisible: false, loadToken: 0, modelLoadToken: 0, searchLoadToken: 0,
      standaloneRoot: false, standaloneSearch: freshSearch(), standaloneSearchLoaded: false, selectedSearchId: '',
      pendingSearchDraft: null, restoredReadingDraft: false, readingSettingsLoaded: false, settingsEdited: false,
      persistedTemplateId: 'none', templateId: 'none', modelDraftDirty: false, taskModelReference: null,
      taskModelOptions: [], taskModelLabel: '读取中', taskModelDetail: '', fontModeIndex: 0, fontScalePercent: 100,
      firstArrival: true, arrivalTimer: -1, arrivalOffset: 0, activeTab: 2, reduceMotion: false, appBackgrounded: false,
      confirmationTimer: -1, saved: '', savingModel: false,
      dockScroll: { reset() {} }, settingsScroller: { currentOffset: () => ({ yOffset: 120 }) },
      getUIContext: () => ({ getRouter: () => 'bound', animateTo: (_options, apply) => apply() }) });
    return page;
  };
  const start = async page => { page.aboutToAppear(); page.onPageShow(); await page.pageLoad; await tick(); };
  const form = page => {
    const view = actualPage('components/deepread/DeepReadSettingsForm.ets', ['changeResultSize', 'changeSearchFlag', 'selectSearch', 'saveSettings'], env);
    for (const [child, owner] of [['search', 'standaloneSearch'], ['selectedSearchId', 'selectedSearchId'], ['searchLoaded', 'standaloneSearchLoaded'], ['searchError', 'standaloneSearchError']]) {
      Object.defineProperty(view, child, { get: () => page[owner], set: value => { page[owner] = value; } });
    }
    Object.assign(view, { alive: true, saving: false, error: '', onEdited: () => page.markSettingsEdited(), onSave: () => page.saveStandaloneSettings() });
    return view;
  };
  return { env, state, values, writes, memory, storage, createPage, start, form };
}

test('root replacement restores all unsubmitted fields and service drafts, leaving the reader on persisted preferences', async () => {
  const f = environment(), first = f.createPage(); await f.start(first);
  const form = f.form(first); form.changeResultSize(1); form.selectSearch(1); for (let index = 0; index < 5; index++) form.changeSearchFlag(index, false); form.changeSearchFlag(5, true);
  first.fontModeIndex = 0; first.fontScalePercent = 145; first.templateId = 'unsaved-template'; first.markSettingsEdited();
  await first.selectTaskModel(f.state.models[1]); first.onPageHide(); first.aboutToDisappear();
  assert.equal(f.writes.length, 0); assert.equal(f.memory.get(KEY).search.settings.searchCommonOptions.resultSize, 11);
  assert.equal(f.memory.get(KEY).search.settings.searchServices[0].apiKey, 'old-a', 'credentials stay in the in-memory draft, with no persistence writes');
  f.state.search.searchServices.reverse(); f.state.search.searchServices[0].apiKey = 'updated-provider-key';
  f.state.search.searchCommonOptions.language = 'en'; f.state.models[1].label = '模型 B 更新';
  const returned = f.createPage(); await f.start(returned);
  assert.equal(returned.standaloneSearch.searchCommonOptions.resultSize, 11);
  assert.equal(returned.standaloneSearch.searchCommonOptions.language, 'zh');
  for (const key of ['searchBuiltinDuckDuckGoEnabled', 'searchBuiltinBingEnabled', 'searchBuiltinJinaEnabled',
    'searchBuiltinWikipediaEnabled', 'searchBuiltinHackerNewsEnabled']) assert.equal(returned.standaloneSearch[key], false, key);
  assert.equal(returned.standaloneSearch.searchGoogleWebViewFallbackEnabled, true);
  assert.equal(returned.selectedSearchId, 'b'); assert.equal(returned.standaloneSearch.searchServiceSelected, 1);
  assert.equal(returned.standaloneSearch.searchServices[1].apiKey, 'old-b', 'an external persisted catalog cannot replace an unsubmitted service draft');
  assert.equal(returned.fontModeIndex, 0); assert.equal(returned.fontScalePercent, 145); assert.equal(returned.templateId, 'unsaved-template');
  assert.equal(returned.taskModelReference.pair.modelId, 'b'); assert.match(returned.taskModelLabel, /B 更新/);
  assert.equal(f.writes.length, 0);
  const reader = actualPage('pages/DeepReadArticlePage.ets', ['loadDisplaySettings'], {
    ...f.env, getProductKind: () => 'deepread', FONT_SCALE_MIN: 0.7, FONT_SCALE_MAX: 1.8,
  });
  Object.assign(reader, { pageAlive: true, pageToken: 1 }); await reader.loadDisplaySettings(1);
  assert.equal(reader.fontScale, 1); assert.equal(reader.fontSerif, true, 'the session font draft has no effect outside Settings');
});

test('Save applies the recovered fields through existing stores and clears the unsubmitted session draft', async () => {
  const f = environment(), first = f.createPage(); await f.start(first);
  f.form(first).changeResultSize(1); first.fontScalePercent = 145; first.templateId = 'new-template'; first.markSettingsEdited();
  await first.selectTaskModel(f.state.models[1]); first.onPageHide(); first.aboutToDisappear();
  const returned = f.createPage(); await f.start(returned); await f.form(returned).saveSettings();
  assert.equal(f.state.search.searchCommonOptions.resultSize, 11); assert.equal(f.values.get('deepread_font_scale'), 145);
  assert.equal(f.values.get('deepread_template_id'), 'new-template'); assert.equal(f.state.reference.pair.modelId, 'b');
  assert.equal(returned.settingsEdited, false); assert.equal(f.memory.has(KEY), false);
  returned.onPageHide(); returned.aboutToDisappear(); assert.equal(f.memory.has(KEY), false, 'clean settings do not resurrect a saved draft');
});

test('a new process session and host or nested settings do not inherit the standalone root draft', async () => {
  const f = environment(), first = f.createPage(); await f.start(first); f.form(first).changeResultSize(1); first.fontScalePercent = 150; first.onPageHide();
  const cold = environment(); const fresh = cold.createPage(); await cold.start(fresh);
  assert.equal(fresh.standaloneSearch.searchCommonOptions.resultSize, 10); assert.equal(fresh.fontScalePercent, 100);
  const host = f.createPage('agent'); await f.start(host); host.onPageHide();
  assert.equal(host.fontScalePercent, 100); assert.equal(host.settingsEdited, false);
  const nested = f.createPage('deepread', 2); await f.start(nested);
  assert.equal(nested.standaloneSearch.searchCommonOptions.resultSize, 10); assert.equal(nested.fontScalePercent, 100);
  assert.equal(f.memory.get(KEY).search.settings.searchCommonOptions.resultSize, 11, 'other settings routes leave the root session intact');
});

test('freshly deleted preferred services stay actionable and a persisted child template replaces the prior session baseline', async () => {
  const f = environment(), first = f.createPage(); await f.start(first);
  const form = f.form(first); form.selectSearch(1); first.templateId = 'parent-unsaved'; first.markSettingsEdited(); first.onPageHide();
  f.memory.get(KEY).search.settings.searchServices = [f.memory.get(KEY).search.settings.searchServices[0]]; f.values.set('deepread_template_id', 'child-default');
  const returned = f.createPage(); await f.start(returned);
  assert.equal(returned.selectedSearchId, 'b'); assert.equal(returned.standaloneSearch.searchServiceSelected, -1);
  assert.match(returned.standaloneSearchError, /已删除/); assert.equal(returned.templateId, 'child-default');
  const attempted = f.form(returned); await attempted.saveSettings();
  assert.match(attempted.error, /已删除/); assert.equal(f.writes.length, 0); assert.equal(returned.settingsEdited, true);
});


test('a staged model removed from the fresh catalog remains an explicit unavailable draft after root replacement', async () => {
  const f = environment(), first = f.createPage(); await f.start(first);
  await first.selectTaskModel(f.state.models[1]); first.onPageHide(); f.state.models = [f.state.models[0]];
  const returned = f.createPage(); await f.start(returned);
  assert.equal(returned.taskModelReference.pair.modelId, 'b'); assert.equal(returned.taskModelOptions.length, 1);
  assert.match(returned.modelError, /已不可用/); assert.equal(returned.modelDraftDirty, true);
  const attempted = f.form(returned); await attempted.saveSettings();
  assert.match(attempted.error, /已不可用/); assert.equal(f.writes.some(([key]) => key === 'model'), false);
  returned.onPageHide(); assert.equal(f.memory.get(KEY).modelReference.pair.modelId, 'b');
});
