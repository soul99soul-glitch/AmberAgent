// Execute the actual settings owners; adapt the existing page/Link fixtures to their new ports.
const fs = require('node:fs');
const path = require('node:path');
const ts = require('typescript');
const shared = require('./deepread_ui_fixture.cjs');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const prefs = require('../../../chat/src/main/ets/search/search_prefs.ts');
const sessionRoot = path.join(shared.entryRoot, 'platform_impl/deepread_settings');
const formFields = { search: 'standaloneSearch', searchLoaded: 'standaloneSearchLoaded', searchError: 'standaloneSearchError',
  saving: 'saving', error: 'error', selectedSearchId: 'selectedSearchId', fontModeIndex: 'fontModeIndex', fontScalePercent: 'fontScalePercent', templateId: 'templateId', notice: 'saved' };
function actualPage(file, names, env = {}) {
  if (file === 'components/deepread/DeepReadSettingsForm.ets') {
    const form = shared.actualPage(file, names, env);
    Object.assign(form, { search: prefs.defaultSearchPrefs(), searchLoaded: false, searchError: '', selectedSearchId: '',
      fontModeIndex: 0, fontScalePercent: 100, templateId: 'none', notice: '', saving: false, error: '', onEdited() {}, onSave: async () => {}, onSearchRetry() {} });
    form.settings = { markSettingsEdited: () => form.onEdited(), saveStandaloneSettings: () => form.onSave(),
      refreshStandaloneSearch: () => form.onSearchRetry() };
    Object.defineProperty(form.settings, 'pageAlive', { get: () => form.alive });
    form.settings.submit = shared.actualPage('platform_impl/deepread_settings/DeepReadSettingsSession.ets', ['submit'], {}).submit;
    for (const [field, owner] of Object.entries(formFields)) Object.defineProperty(form.settings, owner,
      { configurable: true, get: () => form[field], set: value => { form[field] = value; } });
    return form;
  }
  if (file !== 'pages/SettingDeepReadPage.ets') return shared.actualPage(file, names, env);
  const memory = new Map();
  const storage = env.getAppContainer?.().storage ?? { get: async (_key, fallback) => fallback, set: async () => {} };
  const AppStorage = env.AppStorage ?? { get: key => memory.get(key), setOrCreate: (key, value) => memory.set(key, value), delete: key => memory.delete(key) };
  const deps = {
    '@kit.ArkUI': { promptAction: env.promptAction ?? { showToast() {} } },
    '@kit.PerformanceAnalysisKit': { hilog: { info() {}, error() {} } },
    '@amber/chat-domain': { ...prefs, ...env },
    '@amber/deepread-domain': { makeModelConfig: env.makeModelConfig },
    '../DeepReadTemplate.ets': { BUILTIN_TEMPLATES: env.BUILTIN_TEMPLATES ?? [], loadCustomTemplates: env.loadCustomTemplates ?? (async () => []) },
    '../../design/DeepReadHaptics.ets': { DeepReadHaptics: env.DeepReadHaptics ?? { selection() {}, success() {} } },
    '../DeepReadSearchDraft.ets': { consumeDeepReadSearchEditorDraft: env.consumeDeepReadSearchEditorDraft ?? (() => null), writeDeepReadSearchEditorDraft: env.writeDeepReadSearchEditorDraft ?? (() => {}) },
    '../NewsNowProvider.ets': { HOTLIST_PRESETS: env.HOTLIST_PRESETS ?? [] },
    '../ChatDeepReadDraft.ets': { chatChoiceToDeepReadDraft: env.chatChoiceToDeepReadDraft ?? (value => value) },
  };
  function load(name) {
    const source = fs.readFileSync(path.join(sessionRoot, name + '.ets'), 'utf8').replace(/^@Observed\s*$/gm, '');
    const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText;
    const exports = {};
    new Function('exports', 'require', 'AppStorage', 'setTimeout', 'clearTimeout', 'Curve', code)(exports,
      key => { if (key in deps) return deps[key]; throw Error('Unexpected settings dependency: ' + key); }, AppStorage,
      env.setTimeout ?? setTimeout, env.clearTimeout ?? clearTimeout, env.Curve ?? { EaseOut: 'ease-out' });
    return new exports[name]();
  }
  const settings = load('DeepReadSettingsSession'), host = load('DeepReadHostSettings');
  const source = fs.readFileSync(path.join(shared.entryRoot, file), 'utf8');
  const pageMethods = names.filter(name => new RegExp('^  (?:private )?(?:async )?' + name + '\\(', 'm').test(source));
  const page = shared.actualPage(file, pageMethods, { ...env, AppStorage });
  page.settings = settings; page.host = host;
  page.getUIContext = () => ({ animateTo: (_options, apply) => apply(), getRouter: () => ({}) });
  page.dockScroll = { reset() {} }; page.pageVisible = false;
  const settingsPorts = { get storage() { return settings.storage ?? storage; }, get kv() { return env.getChatKvStore?.() ?? storage; },
    get standalone() { return env.getProductKind?.() === 'deepread'; }, reduceMotion: () => page.reduceMotion ?? false,
    uiContext: () => page.getUIContext(), loadAppearance: env.loadDeepReadAppearance ?? (async () => {}),
    loadHost: token => host.loadPage(token), loadModelSelection: env.loadDeepReadModelSelection ?? (async () => ({ reference: { kind: 'auto' }, options: [], label: '', detail: '' })),
    saveModelSelection: env.saveDeepReadModelSelection ?? (async () => ({ kind: 'auto' })),
    openSearchEditor: id => env.router?.pushUrl({ url: 'pages/DeepReadSearchServiceEditorPage', params: { serviceId: id } }) ?? Promise.resolve() };
  const hostPorts = { get storage() { return settings.storage ?? storage; }, isAlive: () => settings.isAlive(), isCurrent: token => settings.isCurrent(token),
    waitForLoad: () => settings.pageLoad, setNotice: value => { settings.saved = value; }, getNotice: () => settings.saved,
    listModels: () => env.getModelRegistry?.().list() ?? Promise.resolve([]), saveModels: models => env.getModelRegistry?.().save(models) ?? Promise.resolve(),
    resolveChatChoice: env.resolveChatProviderChoice ?? (async () => null) };
  page.startSessions = () => { host.start(hostPorts); settings.start(settingsPorts); };
  page.startSessions();
  for (const owner of [settings, host]) {
    const fields = new Set([...Object.keys(owner), ...Object.getOwnPropertyNames(Object.getPrototypeOf(owner))]);
    for (const field of fields) {
      if (['constructor', 'ports', 'deps', 'start'].includes(field) || field in page) continue;
      const descriptor = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(owner), field);
      if (descriptor?.get || descriptor?.set) continue;
      Object.defineProperty(page, field, { configurable: true, get: () => typeof owner[field] === 'function' ? owner[field].bind(owner) : owner[field], set: value => { owner[field] = value; } });
    }
  }
  return page;
}
module.exports = { ...shared, actualPage };
