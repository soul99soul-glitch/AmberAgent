const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const ENTRY = path.resolve(__dirname, '../../../entry/src/main/ets');
const transpile = (source) => ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
const option = (providerId, modelId, label, available = true) => ({
  providerId, modelId, label, providerLabel: providerId, available,
});
const loadSelection = (overrides = {}) => {
  const filename = path.join(ENTRY, 'platform_impl/DeepReadModelSelection.ets');
  const writes = [];
  const imports = {
    '@amber/chat-domain': { DEFAULT_AUTO_MODEL_ID: 'auto' },
    '../di/AppContainer.ets': {
      TASK_DEEPREAD_MODEL_KEY: 'deepread_task_model',
      readTaskModelReference: async () => ({ kind: 'auto' }),
      loadDeepReadModelOptions: async () => [option('provider', 'model-uuid', 'API model')],
      resolveChatProviderChoice: async () => ({ provider: { name: 'Chat provider' }, model: { displayName: 'Current model', modelId: 'api-model' } }),
      resolveConfiguredTaskModel: async () => null,
      saveTaskModelPair: async (key, pair) => writes.push({ key, pair }),
      saveTaskModelId: async (key, id) => writes.push({ key, id }),
      ...overrides,
    },
  };
  const exports = {};
  vm.runInNewContext(transpile(fs.readFileSync(filename, 'utf8')), {
    exports, require: (name) => { assert.ok(imports[name], name); return imports[name]; },
    Promise, Error, String,
  }, { filename });
  return { api: exports, writes };
};

test('auto describes the current Chat choice without copying legacy API configuration', async () => {
  const { api, writes } = loadSelection();
  const state = await api.loadDeepReadModelSelection();
  assert.equal(state.reference.kind, 'auto');
  assert.match(state.label, /跟随当前模型/);
  assert.match(state.detail, /Chat provider.*Current model/);
  assert.equal(writes.length, 0);
});

test('a missing fixed provider/model is unavailable and does not silently use the Chat choice', async () => {
  let chatReads = 0;
  const { api } = loadSelection({
    readTaskModelReference: async () => ({ kind: 'fixed', pair: { providerId: 'deleted-provider', modelId: 'deleted-model' } }),
    resolveChatProviderChoice: async () => { chatReads++; return { provider: { name: 'Fallback' }, model: {} }; },
  });
  const state = await api.loadDeepReadModelSelection();
  assert.equal(state.available, false);
  assert.match(state.label, /不可用/);
  assert.equal(chatReads, 0);
});

test('fixed selection persists the provider ID and model configuration UUID to the new slot', async () => {
  const { api, writes } = loadSelection();
  const reference = await api.saveDeepReadModelSelection(option('p-uuid', 'm-uuid', 'api-string'));
  assert.equal(reference.kind, 'fixed');
  assert.deepEqual(JSON.parse(JSON.stringify(writes)), [{ key: 'deepread_task_model', pair: { providerId: 'p-uuid', modelId: 'm-uuid' } }]);
});

test('unavailable choices are rejected and store failures remain failures', async () => {
  const { api, writes } = loadSelection({ saveTaskModelPair: async () => { throw new Error('disk'); } });
  await assert.rejects(api.saveDeepReadModelSelection(option('p', 'm', 'x', false)), /不可用/);
  await assert.rejects(api.saveDeepReadModelSelection(option('p', 'm', 'x')), /disk/);
  assert.equal(writes.length, 0);
});

const loadSettingsSaveMethod = (saveSelection) => {
  const filename = path.join(ENTRY, 'pages/SettingDeepReadPage.ets');
  const source = fs.readFileSync(filename, 'utf8');
  const start = source.indexOf('  private async selectTaskModel(');
  const end = source.indexOf('\n  private taskModelSelected(', start);
  assert.ok(start >= 0 && end > start);
  const exports = {};
  const toasts = [];
  const fixture = `class SettingsFixture {
    savingModel = false; loadToken = 1; modelLoadToken = 0; pageAlive = true;
    taskModelReference = { kind: 'auto' }; taskModelLabel = 'previous'; taskModelDetail = 'previous detail';
    modelError = ''; modelSheetOpen = true; saved = ''; async refreshTaskModelSelection() {}
    ${source.slice(start, end)}
  } exports.SettingsFixture = SettingsFixture;`;
  vm.runInNewContext(transpile(fixture), {
    exports, Promise, Error, String, getProductKind: () => 'agent', saveDeepReadModelSelection: saveSelection,
    promptAction: { showToast: (value) => toasts.push(value.message) },
  }, { filename });
  return { page: new exports.SettingsFixture(), toasts };
};

test('settings save failure retains the prior selection and keeps the selector open with a visible error', async () => {
  const { api } = loadSelection({ saveTaskModelPair: async () => { throw new Error('disk'); } });
  const { page, toasts } = loadSettingsSaveMethod(api.saveDeepReadModelSelection);
  page.saved = '上次模型已保存 ✓';
  await page.selectTaskModel(option('p', 'm', 'New model'));
  assert.equal(page.taskModelReference.kind, 'auto');
  assert.equal(page.taskModelLabel, 'previous');
  assert.equal(page.modelSheetOpen, true);
  assert.equal(page.savingModel, false);
  assert.equal(page.saved, '');
  assert.match(page.modelError, /模型保存失败.*disk/);
  assert.equal(toasts.length, 1);
});

test('settings updates its label and closes only after the provider/model pair has saved', async () => {
  const { api, writes } = loadSelection();
  const { page } = loadSettingsSaveMethod(api.saveDeepReadModelSelection);
  await page.selectTaskModel(option('p', 'm', 'New model'));
  assert.equal(writes.length, 1);
  assert.equal(page.taskModelReference.pair.modelId, 'm');
  assert.equal(page.taskModelLabel, 'p / New model');
  assert.equal(page.modelSheetOpen, false);
  assert.match(page.saved, /已保存/);
});

const loadTemplateGenerateMethod = (getRuntime) => {
  const filename = path.join(ENTRY, 'components/deepread/DeepReadTemplateWorkbench.ets');
  const source = fs.readFileSync(filename, 'utf8');
  const start = source.indexOf('  private async generate(');
  const end = source.indexOf('\n  private templateDate(', start);
  const editStart = source.indexOf('  private startEdit(');
  const editEnd = source.indexOf('\n  private async save(', editStart);
  assert.ok(start >= 0 && end > start);
  assert.ok(editStart >= 0 && editEnd > editStart);
  const exports = {};
  const fixture = `class WorkbenchFixture {
    aiPrompt = 'new template'; generating = false; genError = ''; generationController = null;
    alive = true; lifecycleId = 0; editorId = 0; saving = false;
    editingId = ''; editName = ''; editHtml = ''; note = ''; storage = { get: () => { throw new Error('legacy configuration read'); } };
    ${source.slice(editStart, editEnd)}
    ${source.slice(start, end)}
  } exports.WorkbenchFixture = WorkbenchFixture;`;
  vm.runInNewContext(transpile(fixture), {
    exports, Promise, Error, String, AI_TEMPLATE_PROMPT: 'Template instruction: ',
    getDeepReadTextRuntime: getRuntime,
    getAppContainer: () => { throw new Error('legacy client used'); },
    createEntryAbortController: () => ({ signal: { aborted: false }, abort() { this.signal.aborted = true; } }),
    makeUserMessage: (text) => ({ role: 'user', parts: [{ type: 'text', text }] }),
    validateTemplateHtml: () => '',
    starterTemplateHtml: () => '<article>{{title}}{{content}}</article>',
  }, { filename });
  return new exports.WorkbenchFixture();
};

test('template generation uses one captured DeepRead runtime and never the independent legacy client', async () => {
  let model = 'initial-model';
  let getterCalls = 0;
  let request;
  let release;
  const gate = new Promise((resolve) => { release = resolve; });
  const page = loadTemplateGenerateMethod(async () => {
    getterCalls++;
    return { model, aiClient: { generateText: async (params) => {
      request = params; await gate;
      return [{ role: 'assistant', parts: [{ type: 'text', text: '<article>{{title}}</article>' }] }];
    } } };
  });
  const generation = page.generate();
  await new Promise((resolve) => setImmediate(resolve));
  model = 'changed-model';
  release();
  await generation;
  assert.equal(getterCalls, 1);
  assert.equal(request.model, 'initial-model');
  assert.equal(page.genError, '');
  assert.equal(page.editHtml, '<article>{{title}}</article>');
  assert.equal(page.generating, false);
});

test('unavailable runtime gives template generation a visible error without replacing the existing draft', async () => {
  const page = loadTemplateGenerateMethod(async () => { throw new Error('固定模型不可用'); });
  page.editHtml = '<article>existing</article>';
  await page.generate();
  assert.equal(page.editHtml, '<article>existing</article>');
  assert.match(page.genError, /生成失败.*固定模型不可用/);
  assert.equal(page.generating, false);
});

for (const action of ['edit', 'new']) {
  test(`switching to ${action} while template A generates keeps the new draft intact after A returns`, async () => {
    let release;
    const gate = new Promise((resolve) => { release = resolve; });
    const page = loadTemplateGenerateMethod(async () => ({ model: 'model', aiClient: { generateText: async () => {
      await gate;
      return [{ role: 'assistant', parts: [{ type: 'text', text: '<article>A generated</article>' }] }];
    } } }));
    const generatingA = page.generate();
    await new Promise((resolve) => setImmediate(resolve));
    if (action === 'edit') page.startEdit({ id: 'B', name: 'B name', html: '<article>B draft</article>' });
    else page.startNew();
    assert.equal(page.generating, false, 'switching editors cancels the previous request and opens the new editor');
    release();
    await generatingA;
    assert.equal(page.editingId, action === 'edit' ? 'B' : '');
    assert.equal(page.editName, action === 'edit' ? 'B name' : '自定义模板');
    assert.equal(page.editHtml, action === 'edit' ? '<article>B draft</article>' : '<article>{{title}}{{content}}</article>');
    assert.equal(page.note, '');
    assert.equal(page.generating, false);
    assert.equal(page.generationController, null);
  });
}
