// Exercise the actual picker callback and settings handler, without ArkUI/network.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const base = path.resolve(__dirname, '../main/ets');
const sheet = fs.readFileSync(path.join(base, 'components/ProviderTemplatePickerSheet.ets'), 'utf8');
const settings = fs.readFileSync(path.join(base, 'pages/ChatProviderSettingsPage.ets'), 'utf8');
function evaluate(code, env = {}) {
  const js = ts.transpileModule(code, { compilerOptions: { target: ts.ScriptTarget.ES2022,
    module: ts.ModuleKind.CommonJS } }).outputText;
  return new Function(...Object.keys(env), js)(...Object.values(env));
}
function arrow(source, start) {
  const begin = source.indexOf('(', start), brace = source.indexOf('{', source.indexOf('=>', begin));
  let end = brace + 1, depth = 1;
  for (; depth; end++) { if (source[end] === '{') depth++; if (source[end] === '}') depth--; }
  return source.slice(begin, end);
}
function callback(source, env) {
  return evaluate('return function (...args) { return (' + source + ')(...args); };', env);
}
function fixture() {
  let next = 0;
  const newId = () => `provider-${++next}`;
  const exports = {};
  const providerSource = fs.readFileSync(path.resolve(__dirname,
    '../../../chat/src/main/ets/chat/provider_settings.ts'), 'utf8');
  evaluate(providerSource, { exports, require: name => name === './ids.ts'
    ? { newId } : { defaultModelAbilities: () => [] } });
  const pending = [], routes = [], toasts = [];
  const env = { ...exports, newId, getProductKind: () => 'amber', setPendingProvider: p => pending.push(p),
    router: { pushUrl: p => routes.push(p) }, promptAction: { showToast: p => toasts.push(p) } };
  const helpers = /^  private addOAuthProvider\(/m.test(settings)
    ? settings.slice(settings.indexOf('  private addOAuthProvider('), settings.indexOf('\n  }',
      settings.indexOf('  private addOAuthProvider(')) + 4) : '';
  const page = evaluate('class Page { ' + helpers + ' } return new Page();', env);
  page.sheetShow = true;
  page.all = [{ id: 'existing-openai', type: 'openai', apiKey: 'author-key' }];
  page.onOauthPick = callback(arrow(settings, settings.indexOf('onOauthPick:')), env).bind(page);
  page.onPickPreset = callback(arrow(settings, settings.indexOf('onPickPreset:')), env).bind(page);
  const rows = evaluate('return ' + /const OAUTH_ROWS: OAuthRow\[\] = (\[[\s\S]*?\]);/.exec(sheet)[1] + ';');
  const clickStart = sheet.lastIndexOf('.onClick(', sheet.indexOf('this.onOauthPick(row.kind)'));
  const clickCode = arrow(sheet, clickStart);
  const click = row => callback(clickCode, { row }).call(page);
  return { page, rows, click, pending, routes, toasts };
}
test('UI-10 picker callback distinguishes the Codex and Gemini OAuth rows', () => {
  const f = fixture(), kinds = [];
  f.page.onOauthPick = kind => kinds.push(kind);
  f.rows.forEach(f.click);
  assert.deepEqual(kinds, ['codex', 'gemini']);
});
test('UI-10 Codex row opens an independent OAuth draft without modifying existing providers', () => {
  const f = fixture(), existing = JSON.stringify(f.page.all);
  f.click(f.rows[0]);
  assert.equal(f.pending.length, 1);
  const p = f.pending[0];
  assert.equal(p.type, 'openai'); assert.equal(p.authMode, 'codex_oauth');
  assert.equal(p.name, 'OpenAI Codex OAuth'); assert.equal(p.brand, 'openai');
  assert.equal(p.baseUrl, 'https://chatgpt.com/backend-api/codex');
  assert.equal(p.useResponseApi, true); assert.equal(p.apiKey, ''); assert.deepEqual(p.models, []);
  assert.notEqual(p.id, f.page.all[0].id);
  assert.equal(f.page.sheetShow, false); assert.equal(JSON.stringify(f.page.all), existing);
  assert.deepEqual(f.routes, [{ url: 'pages/ChatProviderDetailPage', params: { isNew: '1' } }]);
  assert.equal(f.toasts.length, 0);
  f.click(f.rows[0]); assert.notEqual(f.pending[0].id, f.pending[1].id);
});
test('UI-10 Gemini OAuth retains its gate and reports only Gemini as unsupported', () => {
  const f = fixture(); f.click(f.rows[1]);
  assert.equal(f.pending.length, 0); assert.equal(f.routes.length, 0);
  assert.equal(f.page.sheetShow, false); assert.equal(f.toasts.length, 1);
  assert.match(f.toasts[0].message, /Gemini OAuth/);
});
test('UI-10 API key presets still pass their existing provider to detail', () => {
  const f = fixture(), p = { id: 'preset' };
  f.page.onPickPreset(p);
  assert.equal(f.pending[0], p); assert.equal(f.toasts.length, 0);
  assert.deepEqual(f.routes, [{ url: 'pages/ChatProviderDetailPage', params: { isNew: '1' } }]);
});
function appStorageHost() {
  const values = new Map();
  return { setOrCreate: (key, value) => values.set(key, value), get: key => values.get(key),
    delete: key => values.delete(key) };
}
function actualDraftHandoff(AppStorage = appStorageHost()) {
  const source = fs.readFileSync(path.join(base, 'pages/ChatProviderDetailPage.ets'), 'utf8');
  const exports = {};
  const slot = source.slice(source.indexOf('// 模板新增流'), source.indexOf('// Provider 导出载荷'));
  evaluate(slot, { exports, AppStorage });
  assert.equal(typeof exports.takePendingProvider, 'function', 'drafts must be consumed through the accessor');
  const start = source.indexOf('  aboutToAppear(): void {');
  const brace = source.indexOf('{', start);
  let depth = 1, end = brace + 1;
  for (; depth; end++) { if (source[end] === '{') depth++; if (source[end] === '}') depth--; }
  let resolve;
  const loaded = new Promise(r => { resolve = r; });
  const page = evaluate('class Detail { ' + source.slice(start, end) + ' } return new Detail();', {
    ...exports, router: { getParams: () => ({ isNew: '1' }) },
    getChatKvStore: () => ({}), loadProviders: () => loaded,
  });
  page.applyProvider = p => { page.provider = p; };
  return { ...exports, page, resolve };
}
test('UI-11 pending accessor consumes and clears a provider exactly once', () => {
  const f = actualDraftHandoff(), preset = { id: 'preset', type: 'openai' };
  f.setPendingProvider(preset);
  assert.equal(f.takePendingProvider(), preset);
  assert.equal(f.takePendingProvider(), null);
});
test('UI-11 Detail consumes its draft before asynchronous providers load', async () => {
  const f = actualDraftHandoff(), draft = { id: 'codex-draft', authMode: 'codex_oauth' };
  f.setPendingProvider(draft); f.page.aboutToAppear();
  assert.equal(f.takePendingProvider(), null);
  const nextDraft = { id: 'next-draft' }; f.setPendingProvider(nextDraft);
  f.resolve([{ id: 'existing-openai' }]);
  await Promise.resolve(); await Promise.resolve();
  assert.equal(f.page.provider, draft);
  assert.equal(f.takePendingProvider(), nextDraft);
});
test('UI-11 separate routing module contexts share only the process draft slot', () => {
  for (const draft of [{ id: 'codex-draft', authMode: 'codex_oauth' },
    { id: 'preset-draft', authMode: 'api_key' }]) {
    const sharedStorage = appStorageHost();
    const writer = actualDraftHandoff(sharedStorage), reader = actualDraftHandoff(sharedStorage);
    writer.setPendingProvider(draft);
    assert.equal(reader.takePendingProvider(), draft);
    assert.equal(writer.takePendingProvider(), null);
  }
});

function method(source, name) {
  const start = source.indexOf(`  private ${name}(`);
  const asyncStart = source.indexOf(`  private async ${name}(`);
  const begin = start >= 0 ? start : asyncStart;
  assert.ok(begin >= 0, `actual method ${name} exists`);
  const brace = source.indexOf('{', begin);
  let depth = 1, end = brace + 1;
  for (; depth; end++) { if (source[end] === '{') depth++; if (source[end] === '}') depth--; }
  return source.slice(begin, end);
}
test('DeepRead permits a second preset without overwriting its existing provider or model IDs', () => {
  let next = 0;
  const pending = [], preset = { id: 'original-provider', models: [{ id: 'original-model', modelId: 'text-model' }] };
  const page = { sheetShow: true };
  callback(arrow(settings, settings.indexOf('onPickPreset:')), {
    getProductKind: () => 'deepread', newId: () => `new-${++next}`,
    setPendingProvider: p => pending.push(p), router: { pushUrl() {} },
  }).call(page, preset);
  assert.notEqual(pending[0].id, preset.id);
  assert.notEqual(pending[0].models[0].id, preset.models[0].id);
  assert.equal(preset.id, 'original-provider'); assert.equal(preset.models[0].id, 'original-model');
  const picker = evaluate('class Picker { ' + method(sheet, 'bundled') + ' } return new Picker();', {
    DEFAULT_PROVIDERS: [preset], getProductKind: () => 'deepread',
  });
  picker.existingIds = JSON.stringify([preset.id]);
  assert.deepEqual(picker.bundled(), [preset]);
});
test('Provider import merges the latest catalog and refuses to overwrite it after a read failure', async () => {
  const writes = [], toasts = [], imported = { id: 'imported' };
  let fail = false;
  class SelectOptions {}
  class ViewPicker { async select() { return ['file://picked']; } }
  const page = evaluate('class Page { ' + method(settings, 'importProvider') + ' } return new Page();', {
    AppStorage: { get: () => ({}) }, picker: { DocumentSelectOptions: SelectOptions, DocumentViewPicker: ViewPicker },
    fileIo: { readTextSync: () => '{}' }, providerFromImportJson: () => imported,
    getChatKvStore: () => ({}), loadProviders: async () => { if (fail) throw Error('read denied'); return [{ id: 'fresh' }]; },
    saveProviders: async (_store, providers) => writes.push(providers),
    promptAction: { showToast: toast => toasts.push(toast.message) },
  });
  page.loaded = true; page.all = [{ id: 'stale' }]; page.rebuildRows = () => {};
  await page.importProvider();
  assert.deepEqual(writes[0], [{ id: 'fresh' }, imported]);
  fail = true; await page.importProvider();
  assert.equal(writes.length, 1); assert.match(toasts.at(-1), /导入失败.*read denied/);
});
test('Provider load failure and seed write failure keep actions gated until a successful retry', async () => {
  let readFailure = true, seedFailure = false;
  const page = evaluate('class Page { ' + method(settings, 'load') + ' } return new Page();', {
    getChatKvStore: () => ({}), DEFAULT_PROVIDERS: [{ id: 'seed' }],
    loadProviders: async () => { if (readFailure) throw Error('read denied'); return null; },
    saveProviders: async () => { if (seedFailure) throw Error('write denied'); },
  });
  page.loadSeq = 0; page.loaded = true; page.rebuildRows = () => {};
  const settled = () => new Promise(resolve => setImmediate(resolve));
  page.load(); await settled(); assert.equal(page.loaded, false); assert.match(page.loadError, /read denied/);
  readFailure = false; seedFailure = true;
  page.load(); await settled(); assert.equal(page.loaded, false); assert.match(page.loadError, /write denied/);
  seedFailure = false; page.load(); await settled();
  assert.equal(page.loaded, true); assert.equal(page.loadError, ''); assert.deepEqual(page.all, [{ id: 'seed' }]);
});

test('DeepRead custom service choices construct their actual protocol independently', () => {
  const requested = [], pending = [];
  const factory = type => options => { requested.push({ type, name: options.name }); return { type, models: [] }; };
  const picker = evaluate('class Picker { ' + method(sheet, 'pickProtocol') + ' } return new Picker();', {
    makeProviderSettingOpenAIVariant: factory('openai'), makeProviderSettingGoogle: factory('google'),
    makeProviderSettingClaude: factory('claude'),
  });
  picker.onPickPreset = provider => pending.push(provider);
  for (const protocol of ['openai', 'google', 'claude']) picker.pickProtocol(protocol);
  assert.deepEqual(pending.map(provider => provider.type), ['openai', 'google', 'claude']);
  assert.deepEqual(requested.map(request => request.name), ['OpenAI 兼容 API', 'Google Gemini', 'Claude']);
});
