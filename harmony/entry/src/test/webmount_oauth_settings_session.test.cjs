const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const root = path.resolve(__dirname, '../main/ets');
const compile = source => ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
const exportsObject = {};
new Function('exports', 'Observed', compile(fs.readFileSync(path.join(root,
  'platform_impl/webmount_settings/WebMountOAuthSettingsSession.ets'), 'utf8')))(exportsObject, value => value);
const { WebMountOAuthSettingsSession } = exportsObject;
const tick = async () => { await new Promise(setImmediate); };
function deferred() {
  let resolve, reject;
  const promise = new Promise((a, b) => { resolve = a; reject = b; });
  return { promise, resolve, reject };
}
function application(siteId = 'example') {
  return { siteId, bindingHash: '1234567890', authorizationEndpoint: 'https://example.com/auth',
    tokenEndpoint: 'https://example.com/token', clientId: 'client', redirectUri: 'https://example.com/callback',
    scope: 'read', tokenEncoding: 'json', clientAuthentication: 'basic',
    apiOrigins: ['https://api.example.com'], clientSecretRef: 'saved-secret-ref' };
}
function fixture(overrides = {}) {
  const calls = { subscriptions: [], releases: 0, saves: [], connects: [], cancels: [], disconnects: [], animations: 0 };
  const runtime = {
    loadApplications: async () => [application()],
    saveApplication: async draft => { calls.saves.push(draft); return draft.application; },
    readState: async siteId => ({ siteId, status: 'disconnected' }),
    subscribe: (siteId, listener) => {
      calls.subscriptions.push({ siteId, listener });
      return () => { calls.releases++; };
    },
    connect: async siteId => { calls.connects.push(siteId); return { siteId, status: 'awaiting_callback' }; },
    cancel: siteId => { calls.cancels.push(siteId); },
    disconnect: async siteId => { calls.disconnects.push(siteId); },
    ...overrides,
  };
  // Execute the production page's real ports assembly and dispose call, without its other product regions.
  const source = fs.readFileSync(path.join(root, 'pages/SettingExperimentalWebMountPage.ets'), 'utf8');
  const lifecycle = source.slice(source.indexOf('  aboutToAppear()'), source.indexOf('  // ── E12:HAR 导入'));
  const page = new Function('WebMountOAuthSettingsSession', 'getWebMountOAuthRuntime', 'MOTION_OVERLAY', 'Curve',
    compile(`class Page { oauth = new WebMountOAuthSettingsSession();\n${lifecycle}\n}\nreturn new Page();`))(
    WebMountOAuthSettingsSession, () => runtime, 220, { EaseOut: 0 });
  page.refresh = page.refreshFeishu = page.refreshHar = () => {};
  page.getUIContext = () => ({ animateTo: (_options, apply) => { calls.animations++; apply(); } });
  page.aboutToAppear();
  return { page, session: page.oauth, runtime, calls };
}

test('late application load cannot establish subscriptions after the actual page is disposed', async () => {
  const load = deferred(), f = fixture({ loadApplications: () => load.promise });
  f.page.aboutToDisappear();
  load.resolve([application()]); await tick();
  assert.equal(f.calls.subscriptions.length, 0);
  assert.deepEqual(f.session.oauthApps, []);
});

test('refresh releases current subscriptions and superseded load/read/listener results cannot replace current state', async () => {
  const loads = [deferred(), deferred()], reads = [deferred(), deferred()]; let index = 0, readIndex = 0;
  const f = fixture({ loadApplications: () => loads[index++].promise, readState: () => reads[readIndex++].promise });
  f.session.refreshOauth(); loads[1].resolve([application('new')]); await tick();
  const listener = f.calls.subscriptions[0].listener;
  loads[0].resolve([application('old')]); await tick();
  assert.deepEqual(f.session.oauthApps.map(x => x.siteId), ['new']);
  assert.equal(f.calls.subscriptions.length, 1);
  const before = f.session.oauthStates;
  listener({ siteId: 'new', status: 'connected' });
  assert.notEqual(f.session.oauthStates, before); assert.equal(f.session.oauthStatusText('new'), '已连接');
  f.page.aboutToDisappear(); assert.equal(f.calls.releases, 1);
  listener({ siteId: 'new', status: 'failed' }); reads[0].resolve({ siteId: 'new', status: 'expired' }); await tick();
  assert.equal(f.session.oauthStatusText('new'), '已连接');
});

test('edit and add own the complete form while preserving the original add encoding/auth choices', async () => {
  const f = fixture(); await tick(); const s = f.session;
  s.oauthSecret = 'discard'; s.editOauth(application());
  assert.equal(s.oauthSecret, ''); assert.equal(s.oauthFormOpen, true);
  assert.equal(s.oauthApiOrigins, 'https://api.example.com'); assert.match(s.oauthNote, /密钥留空则保留/);
  s.openAdd();
  for (const field of ['oauthSiteId', 'oauthAuthEndpoint', 'oauthTokenEndpoint', 'oauthClientId', 'oauthRedirect', 'oauthScope', 'oauthApiOrigins', 'oauthSecret', 'oauthNote']) assert.equal(s[field], '', field);
  assert.equal(s.oauthEncoding, 'json'); assert.equal(s.oauthClientAuth, 'basic');
  assert.equal(f.calls.animations, 2); f.page.aboutToDisappear();
});

test('saving captures the original normalized application and blank secret keep contract, then clears secret/closes/refreshes', async () => {
  const save = deferred(), f = fixture({ saveApplication: draft => { f.calls.saves.push(draft); return save.promise; } });
  await tick(); const s = f.session; s.editOauth(application());
  s.oauthSiteId = ' example '; s.oauthClientId = ' client '; s.oauthRedirect = ' '; s.oauthScope = ' read ';
  s.oauthApiOrigins = ' https://api.example.com, , https://other.example.com '; s.oauthSecret = ' ';
  s.saveOauth(); s.saveOauth();
  assert.equal(f.calls.saves.length, 1); assert.equal(s.oauthBusy, true); assert.equal(s.oauthFormOpen, true);
  const draft = f.calls.saves[0];
  assert.equal(draft.secretAction, 'keep'); assert.equal(draft.clientSecret, null); assert.equal(draft.application.clientSecretRef, null);
  assert.equal(draft.application.redirectUri, 'http://127.0.0.1:53682/callback');
  assert.deepEqual(draft.application.apiOrigins, ['https://api.example.com', 'https://other.example.com']);
  assert.equal(draft.application.bindingHash, ''); assert.equal(draft.application.clientId, 'client');
  save.resolve(application()); await tick();
  assert.equal(s.oauthSecret, ''); assert.equal(s.oauthFormOpen, false); assert.equal(s.oauthBusy, false);
  assert.match(s.oauthNote, /已保存 example/); assert.equal(f.calls.releases, 1); f.page.aboutToDisappear();
});

test('failed replacement retains the secret and form for correction, with the same validation policies', async () => {
  const f = fixture({ saveApplication: async draft => { f.calls.saves.push(draft); throw new Error('write failed'); } });
  await tick(); const s = f.session; s.openAdd(); s.oauthSecret = ' key '; s.oauthClientAuth = 'invalid'; s.oauthEncoding = 'JSON';
  s.saveOauth(); await tick();
  assert.equal(f.calls.saves[0].secretAction, 'replace'); assert.equal(f.calls.saves[0].clientSecret, 'key');
  assert.equal(f.calls.saves[0].application.clientAuthentication, 'body'); assert.equal(f.calls.saves[0].application.tokenEncoding, 'json');
  assert.equal(s.oauthSecret, ' key '); assert.equal(s.oauthFormOpen, true); assert.equal(s.oauthBusy, false);
  assert.equal(s.oauthNote, '保存失败：write failed'); f.page.aboutToDisappear();
});

test('connect/cancel/disconnect retain the runtime protocol and callback state projection', async () => {
  const f = fixture(); await tick(); const s = f.session;
  s.connectOauth('example'); s.connectOauth('example'); await tick();
  assert.deepEqual(f.calls.connects, ['example']); assert.equal(s.oauthBusy, false); assert.match(s.oauthNote, /系统浏览器/);
  f.calls.subscriptions[0].listener({ siteId: 'example', status: 'failed', message: 'callback failed' });
  assert.equal(s.oauthStatusText('example'), '失败：callback failed');
  s.cancelOauth('example'); assert.deepEqual(f.calls.cancels, ['example']); assert.equal(s.oauthNote, '已取消本次授权');
  s.disconnectOauth('example'); await tick(); assert.deepEqual(f.calls.disconnects, ['example']); assert.equal(s.oauthNote, '已断开 example');
  f.page.aboutToDisappear(); assert.deepEqual(f.calls.cancels, ['example'], 'page disposal releases observers without canceling authorization');
});

test('a pending save still reaches runtime but cannot mutate or reconnect the discarded page', async () => {
  const save = deferred(), f = fixture({ saveApplication: () => save.promise }); await tick();
  f.session.openAdd(); f.session.oauthSecret = 'draft'; f.session.saveOauth();
  const note = f.session.oauthNote; f.page.aboutToDisappear(); save.resolve(application()); await tick();
  assert.equal(f.session.oauthSecret, 'draft'); assert.equal(f.session.oauthNote, note);
  assert.equal(f.calls.subscriptions.length, 1); assert.equal(f.calls.releases, 1);
});
