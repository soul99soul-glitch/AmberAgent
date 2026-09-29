// Execute production page methods, DI forwarding and launch helpers. ArkUI lifecycle,
// keyboard mode, persistence and plugin preparation are controlled host ports here;
// this is not evidence of ArkUI rendering, keyboard geometry or device navigation.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../chat/node_modules/typescript');
process.env.TSX_TSCONFIG_PATH = path.resolve(__dirname, '../chat/tsconfig.json');
require('../chat/node_modules/tsx/dist/cjs/index.cjs');
const domain = require('../chat/src/main/ets/index.ts');

const entry = path.resolve(__dirname, '../entry/src/main/ets');
const read = file => fs.readFileSync(path.join(entry, file), 'utf8');
const modes = { OFFSET: 'OFFSET', RESIZE: 'RESIZE', NONE: 'NONE' };
const deferred = () => {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
};

function evaluate(source, dependencies) {
  const output = ts.transpileModule(source, { compilerOptions: {
    target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS,
  } }).outputText;
  const exports = {};
  new Function('exports', ...Object.keys(dependencies), output)(exports, ...Object.values(dependencies));
  return exports;
}

function pageClass(kind, dependencies = {}) {
  const name = kind === 'plugin' ? 'PluginsPage' : 'RecipesPage';
  const source = read(`pages/${name}.ets`);
  // Keep every production field and method before the first UI Builder. Only ArkUI
  // decorators/struct syntax are removed; lifecycle and async bodies are unchanged.
  const body = source.slice(source.indexOf(`struct ${name} {`), source.indexOf('\n  @Builder'))
    .replace(`struct ${name}`, `export class ${name}`)
    .replace(/^(\s*)(?:@\w+(?:\([^)]*\))?\s*)+/gm, '$1');
  return evaluate(body + '\n}', {
    KeyboardAvoidMode: modes, PluginPageColors: class {}, RecipeColors: class {}, ACCENT: '#ff9900',
    ...domain, ...dependencies,
  })[name];
}

function createPage(kind, dependencies = {}) {
  const Page = pageClass(kind, dependencies);
  const page = new Page();
  const writes = [];
  let mode = modes.NONE;
  page.getUIContext = () => ({
    getKeyboardAvoidMode: () => mode,
    setKeyboardAvoidMode: value => { mode = value; writes.push(value); },
  });
  // Loading data is independent of the lifecycle and launch behavior under test.
  page.load = async () => {};
  page.aboutToAppear();
  page.onPageShow?.();
  page.loaded = true;
  return { page, writes, mode: () => mode, setMode: value => { mode = value; } };
}

for (const kind of ['plugin', 'recipe']) {
  test(`${kind}: visible lifetime restores actual keyboard mode and cleanup is idempotent`, () => {
    const f = createPage(kind);
    assert.equal(f.mode(), modes.RESIZE);
    f.page.onPageShow?.();
    f.page.onPageHide?.();
    assert.equal(f.mode(), modes.NONE, 'push hides the retained source page and must restore its actual prior mode');
    assert.deepEqual(f.writes, [modes.RESIZE, modes.NONE], 'duplicate show must not overwrite the captured mode');
    f.setMode(modes.OFFSET);
    f.page.onPageShow?.();
    assert.equal(f.mode(), modes.RESIZE, 'returning to the source page reacquires RESIZE');
    f.page.onPageHide?.();
    assert.equal(f.mode(), modes.OFFSET, 'each visible lifetime captures the current destination mode');
    f.setMode(modes.NONE);
    f.page.onPageHide?.();
    f.page.aboutToDisappear();
    assert.equal(f.mode(), modes.NONE, 'hidden-page destruction must not overwrite another page mode');
    assert.equal(f.writes.length, 4);

    const direct = createPage(kind);
    direct.page.aboutToDisappear();
    assert.equal(direct.mode(), modes.NONE, 'destruction without a hide callback also restores the captured mode');
  });
}

function launchFixture(kind, action) {
  const enteredSave = deferred();
  const releaseSave = deferred();
  const routes = [];
  const saved = [];
  const repository = { save: async conversation => {
    enteredSave.resolve();
    await releaseSave.promise;
    saved.push(conversation);
  } };
  const router = { pushUrl: async route => { routes.push(route); } };
  const launchName = kind === 'plugin' ? 'PluginPageLaunch.ets' : 'RecipePageLaunch.ets';
  const helper = evaluate(read(`platform_impl/${launchName}`), { require: name => {
    if (name === '@kit.ArkUI') return { router };
    assert.equal(name, '@amber/chat-domain');
    return domain;
  } });
  const tool = { toolId: 'plugin__fixture__run', name: 'run', inputSchema: { type: 'object' } };
  const plugin = { enabled: true, package: { hash: 'fixed-plugin', manifest: { name: 'Fixture' }, tools: [tool] } };
  const manifest = { schema: 'amber.recipe.v1', name: 'fixture', version: '1', description: 'Test fixture',
    inputs: {}, steps: [{ id: 'read', tool: 'file_read', arguments: {} }], outputs: {} };
  const descriptor = { manifest, canonicalJSON: domain.canonicalRecipeJSON(manifest), hash: 'fixed-recipe' };
  const primitives = [{ name: 'file_read' }];
  const assistant = domain.makeAssistant({ id: 'fixture-assistant' });

  // Extract the exact two DI exports, including their real forwarding signatures.
  const diSource = read('di/AppContainer.ets');
  const parsed = ts.createSourceFile('AppContainer.ts', diSource, ts.ScriptTarget.ES2022, true);
  const exportName = kind === 'plugin' ? 'startPluginFromPage' : 'startRecipeFromPage';
  const declaration = parsed.statements.find(statement => ts.isVariableStatement(statement)
    && statement.declarationList.declarations.some(item => item.name.getText(parsed) === exportName));
  assert.ok(declaration, `production DI export ${exportName}`);
  const di = evaluate(declaration.getText(parsed), {
    ...helper, newId: domain.newId,
    getChatAssistants: async () => [assistant], getChatRepository: () => repository,
    resolveChatRuntime: async () => ({}),
    pluginPagePrimitives: async () => primitives, recipePagePrimitives: async () => primitives,
    getPluginStore: async () => ({ listInstalled: async () => [plugin] }),
    getRecipeStore: async () => ({ listInstalled: async () => [{ descriptor, enabled: true }] }),
    createEntryPluginAdapter: () => ({ supports: () => true, prepare: async parent => parent }),
  });
  const f = createPage(kind, di);
  if (kind === 'plugin') {
    f.page.selectedToolId = tool.toolId;
    if (action === 'run') f.page.detail = plugin;
    else f.page.preview = { candidate: plugin.package, source: { kind: 'directory', workspacePath: 'plugins/fixture' },
      enable: true, baseHash: null };
  } else {
    f.page.detail = descriptor;
    f.page.detailEnabled = true;
  }
  return { ...f, enteredSave, releaseSave, routes, saved,
    launch: () => kind === 'plugin' ? f.page.launch(action) : f.page.runRecipe() };
}

for (const [kind, action] of [['plugin', 'run'], ['plugin', 'import'], ['plugin', 'test'], ['recipe', 'run']]) {
  for (const leave of ['stay', 'hide', 'destroy', 'hide-show']) {
    test(`${kind} ${action}: deferred save with ${leave} only navigates for its original visible lifetime`, { timeout: 3000 }, async () => {
      const f = launchFixture(kind, action);
      const pending = f.launch();
      await Promise.race([f.enteredSave.promise, pending.then(() => {
        assert.fail(`launch did not reach repository.save: ${f.page.error}`);
      })]);
      assert.equal(f.routes.length, 0, 'save is still the navigation barrier');
      if (leave === 'hide' || leave === 'hide-show') f.page.onPageHide?.();
      if (leave === 'destroy') f.page.aboutToDisappear();
      if (leave === 'hide-show') f.page.onPageShow?.();
      f.releaseSave.resolve();
      await pending;
      assert.equal(f.page.error, '');
      assert.equal(f.saved.length, 1, 'leaving the source keeps the already prepared conversation');
      assert.equal(f.routes.length, leave === 'stay' ? 1 : 0, 'late persistence must not pull the user into Chat');
      if (leave === 'stay') {
        assert.equal(f.routes[0].url, 'pages/ChatPage');
        assert.equal(f.routes[0].params.conversationId, f.saved[0].id);
      }
      if (leave === 'hide-show') {
        await f.launch();
        assert.equal(f.saved.length, 2);
        assert.equal(f.routes.length, 1, 'a fresh submission after returning still navigates normally');
        assert.equal(f.routes[0].params.conversationId, f.saved[1].id);
      }
    });
  }
}
