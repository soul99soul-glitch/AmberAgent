const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const root = path.resolve(__dirname, '../main/ets');

function method(source, name) {
  const match = new RegExp('^  (?:private )?(?:async )?' + name + '\\(', 'm').exec(source);
  assert.ok(match, 'missing actual page method ' + name);
  let depth = 1, end = source.indexOf('{', match.index) + 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(match.index, end);
}
const tick = async () => { for (let i = 0; i < 25; i++) await Promise.resolve(); };

test('returning from provider settings refreshes models and rereads persisted polish context defaults', async () => {
  const source = fs.readFileSync(path.join(root, 'pages/NovelStandaloneSettingsPage.ets'), 'utf8');
  let persistedContext = { includePlot: false, includeForeshadows: false, includeCharacters: true, includeDecisions: true };
  const env = {
    loadNovelModelDefaults: async () => ({ writing: { kind: 'fixed', providerId: 'new-provider', modelId: 'new-default' }, review: { kind: 'global' }, stateSync: { kind: 'global' } }),
    loadNovelModelOptions: async () => [{ providerId: 'new-provider', modelId: 'new-model', available: true }],
    loadNovelPolishContext: async () => persistedContext,
    loadNovelPolishPreference: async () => { throw Error('unconsumed global field must not be loaded'); },
    describeChatModel: async () => 'new current model',
    loadNovelResearchEnabled: async () => false,
    getChatKvStore: () => ({}),
  };
  const code = ts.transpileModule('class Page {\n' + ['reload', 'onPageShow', 'applyContext'].map(n => method(source, n)).join('\n')
    + '\n}\nreturn Page;', { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const page = new (new Function(...Object.keys(env), code)(...Object.values(env)))();
  Object.assign(page, { alive: true, loading: false, ready: true, saving: false, loadToken: 0, enableKeyboardAvoidance() {},
    defaults: { writing: { kind: 'global' } }, includePlot: true, includeForeshadows: true,
    includeCharacters: true, includeDecisions: true, options: [], error: '' });
  page.onPageShow(); await tick();
  assert.equal(page.options[0].modelId, 'new-model');
  assert.equal(page.chatModelLabel, 'new current model');
  assert.equal(page.defaults.writing.modelId, 'new-default');
  assert.equal(page.includePlot, false);
  assert.equal(page.includeForeshadows, false);
  assert.equal(page.ready, true);
  persistedContext = { includePlot: true, includeForeshadows: true, includeCharacters: true, includeDecisions: true };
  await page.reload();
  assert.equal(page.error, '', 'cold default read does not depend on unconsumed global polish text');
  assert.equal(page.includePlot, true);
});

test('settings polish save writes only the actual four context defaults and reports the same scope', async () => {
  const source = fs.readFileSync(path.join(root, 'pages/NovelStandaloneSettingsPage.ets'), 'utf8');
  const code = ts.transpileModule('class Page {\n' + ['changeContext', 'contextDraft', 'applyContext'].map(n => method(source, n)).join('\n') + '\n}\nreturn Page;',
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const contexts = []; let globalWrites = 0;
  const Page = new Function('saveNovelPolishContext', 'saveNovelPolishPreference', code)(
    async context => contexts.push(context), async () => { globalWrites++; });
  const page = new Page();
  Object.assign(page, { alive: true, saving: false, ready: true, loading: false, saveToken: 0, loadToken: 0, includePlot: true,
    includeForeshadows: true, includeCharacters: false, includeDecisions: true, error: 'old error' });
  await page.changeContext('plot', false);
  assert.deepEqual(contexts, [{ includePlot: false, includeForeshadows: true,
    includeCharacters: false, includeDecisions: true }]);
  assert.equal(globalWrites, 0);
  assert.equal(page.savedMessage, '润色上下文已保存');
  assert.equal(page.error, ''); assert.equal(page.saving, false);
});

test('novel picker availability matches the exact fixed-model execution path with provider overrides', async () => {
  const source = fs.readFileSync(path.join(root, 'di/AppContainer.ets'), 'utf8');
  const ast = ts.createSourceFile('AppContainer.ts', source, ts.ScriptTarget.ES2022, true);
  const wanted = new Set(['effectiveProvider', 'makeProviderChoice', 'resolveConfiguredProviderChatModel', 'loadNovelModelOptions']);
  const snippets = ast.statements.filter(node => ts.isVariableStatement(node)
    && node.declarationList.declarations.some(d => wanted.has(d.name.getText(ast))))
    .map(node => node.getText(ast).replace(/^export /, ''));
  assert.equal(snippets.length, wanted.size);
  const code = ts.transpileModule(snippets.join('\n')
    + '\nreturn { resolveConfiguredProviderChatModel, loadNovelModelOptions };',
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  for (const parentReady of [true, false]) {
    for (const overwrite of [null, { enabled: true, apiKey: '' }, { enabled: true, apiKey: 'override-key' }]) {
      const model = { id: 'model-uuid', modelId: 'api-model', type: 'chat', displayName: '', providerOverwrite: overwrite };
      const providers = [{ id: 'provider-uuid', name: 'Provider', enabled: true, apiKey: parentReady ? 'parent-key' : '', models: [model] }];
      const env = { loadProviders: async () => providers, getChatKvStore: () => ({}),
        hasUsableAuth: p => p.enabled && p.apiKey.length > 0,
        copyProviderSettingWithModels: (p, models) => ({ ...p, models }) };
      const api = new Function(...Object.keys(env), code)(...Object.values(env));
      const options = await api.loadNovelModelOptions();
      const choice = await api.resolveConfiguredProviderChatModel('provider-uuid', 'model-uuid');
      assert.equal(options[0].available, choice !== null, 'picker and execution must agree');
    }
  }
});
