const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const compile = source => ts.transpileModule(source, { compilerOptions: {
  module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
} }).outputText;
const read = name => fs.readFileSync(path.resolve(__dirname, '../main/ets', name), 'utf8');
const load = (source, dependencies) => {
  const exports = {};
  new Function('exports', 'require', compile(source))(exports, name => {
    if (Object.hasOwn(dependencies, name)) return dependencies[name];
    throw Error(`Unexpected dependency ${name}`);
  });
  return exports;
};

function fixture() {
  const guard = load(read('platform_impl/ConversationBrowserGuard.ets'), {
    '@amber/chat-domain': { makeAgentTool: value => value },
  });
  const sites = load(read('platform_impl/WebMountSites.ets'), {
    '@amber/chat-domain': { newId: () => 'fixture-id' },
    '@kit.ArkTS': { url: { URL: { parseURL: value => new URL(value) } } },
  });
  // Run the production callback in its captured run context; platform imports are the only replacements.
  const source = read('di/ConversationToolComposition.ets');
  const ast = ts.createSourceFile('Composition.ts', source, ts.ScriptTarget.ES2022, true);
  let initializer;
  const visit = node => {
    if (ts.isVariableDeclaration(node) && node.name.getText(ast) === 'browserFallback') initializer = node.initializer;
    ts.forEachChild(node, visit);
  };
  visit(ast);
  assert.ok(initializer, 'the actual composition provides browser fallback availability');
  const entries = new Map([['webmount_sites', '[]']]);
  const kv = { get: async key => entries.get(key) ?? null,
    put: async (key, value) => entries.set(key, value) };
  const ctx = { conversationId: 'chat', baseTools: [] }, localOptions = [];
  const check = new Function('ctx', 'localOptions', 'ports', 'conversationBrowserUnavailableReason',
    'loadWebMountSites', 'siteAllowsUrl', compile(`return ${initializer.getText(ast)};`))(
    ctx, localOptions, { getChatKvStore: () => kv }, guard.conversationBrowserUnavailableReason,
    sites.loadWebMountSites, sites.siteAllowsUrl);
  return { check, ctx, localOptions, guard, sites, entries, kv };
}

test('actual conversation fallback checks the current catalog, owner, enablement and exact site gate', async () => {
  const f = fixture(), url = 'https://www.google.com/search?q=test';
  assert.match(await f.check(url), /tool profile/);
  f.ctx.baseTools.push({ name: 'wm_open' });
  assert.match(await f.check(url), /当前会话/);
  f.guard.bindConversationBrowserPage('chat');
  assert.match(await f.check(url), /disabled/);
  f.entries.set('webmount_global_enabled', 'true');
  assert.match(await f.check(url), /not registered/);
  assert.equal(f.entries.get('webmount_sites'), '[]', 'checking must not authorize a search site');
  await f.sites.saveWebMountSites(f.kv, [{ id: 'google', name: 'Google', url: 'https://www.google.com' }]);
  assert.equal(await f.check(url), null);
  assert.match(await f.check('https://www.bing.com/search?q=test'), /not registered/);
  f.entries.set('webmount_global_enabled', 'false');
  assert.match(await f.check(url), /disabled/);
  f.localOptions.push('webmount');
  assert.equal(await f.check(url), null, 'the existing assistant OR global permission remains intact');
  f.ctx.baseTools = [];
  assert.match(await f.check(url), /tool profile/, 'a refreshed catalog cannot keep stale availability');
});

test('leaving the conversation while site settings are loading invalidates the suggestion', async () => {
  const f = fixture();
  f.ctx.baseTools.push({ name: 'wm_open' });
  f.localOptions.push('webmount');
  const leave = f.guard.bindConversationBrowserPage('chat');
  let finishSites;
  const get = f.kv.get;
  f.kv.get = key => key === 'webmount_sites'
    ? new Promise(resolve => { finishSites = resolve; }) : get(key);
  const pending = f.check('https://www.google.com/search?q=test');
  leave();
  finishSites(JSON.stringify([{ id: 'google', name: 'Google', url: 'https://www.google.com' }]));
  assert.match(await pending, /当前会话/);
});
