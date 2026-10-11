// Run with: node --test harmony/entry/src/test/inline_miniapp_runtime.test.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { actualPage } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');

const entryRoot = path.resolve(__dirname, '..');
const runtime = fs.readFileSync(
  path.join(entryRoot, 'main/ets/components/InlineMiniAppRuntime.ets'), 'utf8');
const shell = fs.readFileSync(
  path.resolve(entryRoot, '../../chat/src/main/ets/chat/miniapp/miniapp_shell.ts'), 'utf8');
const card = fs.readFileSync(
  path.join(entryRoot, 'main/ets/components/MiniAppChatCard.ets'), 'utf8');

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function fixture(names, env = {}) {
  const page = actualPage('components/InlineMiniAppRuntime.ets', names, {
    hilog: { warn() {} }, HILOG_DOMAIN: 1, HILOG_TAG: 'test', ...env,
  });
  Object.assign(page, { alive: true, attached: true, visible: true, backgrounded: false,
    complete: true, runtimeRevision: 0, activeRevision: 0, app: null, bridge: null,
    imageCache: new Map(), sessionToken: '', mountedAppId: '', mountedAppVersion: 0,
    activeArtifactInteraction: '', interactionOwner: 'inline-test-owner', interactionArea: null });
  return page;
}

test('late platform completions cannot mount or overwrite a newer app version', async () => {
  const requests = [], mounted = [], closed = [];
  const page = fixture(['prepareRuntime', 'isCurrent', 'isActive', 'closeBridge',
    'clearOwnInteraction', 'isCanvasInteractionActive', 'onAppChanged'], {
    getMiniAppPlatform() { const d = deferred(); requests.push(d); return d.promise; },
  });
  page.loadApp = (app, revision) => mounted.push([app.version, revision]);
  page.app = { id: 'demo', version: 1 };
  page.bridge = { close() { closed.push(1); } };
  page.prepareRuntime();
  assert.deepEqual(closed, [1]);
  page.app = { id: 'demo', version: 2 };
  page.onAppChanged();
  requests[1].resolve({ name: 'current' });
  await new Promise(resolve => setImmediate(resolve));
  requests[0].resolve({ name: 'stale' });
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(mounted, [[2, 2]]);
  assert.equal(page.platform.name, 'current');
  page.alive = false;
  assert.equal(page.isCurrent(2, page.app), false);
});

test('image responses completing after a version change never enter the new cache', async () => {
  const page = fixture(['fetchAndCacheImage', 'isCurrent', 'isActive']);
  const image = deferred();
  let permission = '';
  page.platform = { sandboxFor() { return { require(value) { permission = value; } }; } };
  page.fetchImageBytes = () => image.promise;
  page.app = { id: 'demo', version: 1, permissions: ['externalImages'] };
  page.runtimeRevision = 1;
  const pending = page.fetchAndCacheImage('https://example.com/x.png', { bytes: 0 }, 1, page.app);
  page.app = { id: 'demo', version: 2, permissions: [] };
  page.runtimeRevision = 2;
  image.resolve(new Uint8Array([1, 2, 3]));
  assert.equal(await pending, false);
  assert.equal(permission, 'externalImages');
  assert.equal(page.imageCache.size, 0);
});

test('completed mini apps use the full bridged runtime and retain permission gates', () => {
  assert.match(runtime, /injectMiniAppShell\(html, bridgeScript, token, true\)/);
  assert.match(runtime, /new MiniAppBridgeImpl\(platform/);
  assert.match(runtime, /sandboxFor\(app\.id, app\.permissions\)\.require\('externalImages'\)/);
  assert.match(runtime, /javaScriptAccess\(true\)/);
  assert.match(runtime, /NestedScrollMode\.PARENT_FIRST/);
  assert.match(runtime, /NestedScrollMode\.SELF_ONLY/);
  assert.match(runtime, /touch-action:pan-y!important/);
  assert.match(runtime, /postInteraction/);
  assert.match(runtime, /!this\.ready \|\| !this\.isActive\(\)/);
  assert.match(runtime, /message\.sessionToken !== this\.sessionToken/);
  assert.match(runtime, /退出操控/);
  assert.match(runtime, /NestedScrollMode\.SELF_ONLY/);
  assert.match(runtime, /@StorageLink\('activeArtifactInteraction'\)/);
  assert.match(runtime, /onWebAreaChange/);
  assert.match(runtime, /clearOwnInteraction/);
  assert.match(runtime, /request\.isRequestGesture\(\) \|\| !url\.startsWith\(MINI_APP_SHELL_BASE_URL\)/);
  assert.match(runtime, /onVisibleAreaChange/);
});

test('streaming cards keep the script-free preview until a saved record exists', () => {
  const inlineBranch = card.indexOf('InlineMiniAppRuntime({');
  const safeBranch = card.indexOf('VisualArtifactPreview({');
  assert.ok(inlineBranch >= 0 && safeBranch >= 0);
  assert.ok(inlineBranch < safeBranch);
  assert.match(card, /this\.canRun && this\.previewApp !== null && this\.part !== null[\s\S]*InlineMiniAppRuntime\(\{/);
  assert.match(card, /During generation the HTML is only a safe, script-free preview/);
});

test('inline shell gates canvas handlers before generated scripts while leaving the runner unchanged', () => {
  const interactionIndex = shell.indexOf('if (inlineInteraction) prefixParts.push(CANVAS_INTERACTION_JS)');
  const bridgeIndex = shell.indexOf("prefixParts.push('<script>', bridgeScript, '</script>')");
  assert.ok(interactionIndex >= 0 && bridgeIndex > interactionIndex);
  assert.match(shell, /event\.stopImmediatePropagation\(\)/);
  assert.match(shell, /event\.isTrusted === true/);
  assert.match(shell, /Math\.abs\(point\.x - startPoint\.x\) > 10/);
  assert.match(shell, /Math\.abs\(point\.y - startPoint\.y\) > 10/);
  assert.match(shell, /canvas\.style\.setProperty\("touch-action", active \? "none" : "pan-y", "important"\)/);
  assert.match(shell, /sessionToken: window\.__AMBER_MINIAPP_SESSION_TOKEN__/);
  assert.match(shell, /window\.__amberSetCanvasInteraction/);
});

test('interaction callback rejects stale sessions and inactive pages', () => {
  const activated = [];
  const page = fixture(['onInteractionMessage', 'isActive'], {
    activateArtifactInteraction(owner, area) { activated.push([owner, area]); },
    clearArtifactInteraction() {},
  });
  page.sessionToken = 'current-session';
  page.ready = true;
  page.interactionArea = { width: 10, height: 10 };
  page.onInteractionMessage(JSON.stringify({ type: 'canvasFocus', focused: true, sessionToken: 'old-session' }));
  assert.equal(activated.length, 0);
  page.alive = false;
  page.onInteractionMessage(JSON.stringify({ type: 'canvasFocus', focused: true, sessionToken: 'current-session' }));
  assert.equal(activated.length, 0);
  page.alive = true;
  page.onInteractionMessage(JSON.stringify({ type: 'canvasFocus', focused: true, sessionToken: 'current-session' }));
  assert.deepEqual(activated, [['inline-test-owner', page.interactionArea]]);
});
