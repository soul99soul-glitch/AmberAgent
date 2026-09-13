// probe-batch4-webmount-mount.cjs — R07 regression probe (host ready / symmetry / cancel).
//
// Compiles the REAL `WebMountSession` class from
// harmony/entry/src/main/ets/platform_impl/WebMountSession.ets (only the trailing
// ArkUI `@Component struct WebMountHost` is omitted, because TypeScript cannot parse
// it). Controller / timer edges are mocked; the class logic under test is verbatim.
// The URL gate uses the real WebMountSites `makeSiteGate`.
//
// Asserts:
//   - host readiness now comes from a real controller-attached signal: once
//     markControllerAttached fires, open() proceeds to loadUrl and resolves on
//     page end (the old code polled 50x100ms and always rejected);
//   - mount timeout / page timeout REJECT and leave no residual waiters;
//   - onDetach fails in-flight waiters symmetrically;
//   - an AbortSignalLike aborts open/cancel mid-wait;
//   - per-navigation gate recheck: a redirect to a non-allowed host fails the open;
//   - bindHooks returns an owner-scoped unbind that cannot unbind a newer owner.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const { REPO_ROOT } = require('../../2026-09-13-harmony-merged-evidence/probe-lib.cjs');

const ts = require(path.join(REPO_ROOT, 'harmony/chat/node_modules/typescript'));
const file = path.join(REPO_ROOT, 'harmony/entry/src/main/ets/platform_impl/WebMountSession.ets');
const full = fs.readFileSync(file, 'utf8');
const source = full.slice(0, full.indexOf('@Component'));
const js = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;

// ---- controllable fake clock ----
let clockNow = 0;
let seq = 1;
let timers = [];
function fakeSetTimeout(fn, ms) { const id = seq++; timers.push({ id, at: clockNow + ms, seq: id, fn }); return id; }
function fakeClearTimeout(id) { const i = timers.findIndex((t) => t.id === id); if (i >= 0) timers.splice(i, 1); }
async function advance(ms) {
  clockNow += ms;
  for (;;) {
    timers.sort((a, b) => a.at - b.at || a.seq - b.seq);
    const t = timers.find((x) => x.at <= clockNow);
    if (!t) break;
    timers.splice(timers.indexOf(t), 1);
    t.fn();
    await new Promise((r) => setImmediate(r));
  }
}
function pendingTimers() { return timers.length; }

// ---- mocks ----
const nav = { loads: [], refresh: 0, back: 0, fwd: 0, stopped: 0 };
class MockController {
  loadUrl(url) { nav.loads.push(url); }
  refresh() { nav.refresh++; }
  backward() { nav.back++; }
  forward() { nav.fwd++; }
  stop() { nav.stopped++; }
  accessBackward() { return true; }
  accessForward() { return true; }
  runJavaScript() { return Promise.resolve('{}'); }
}

const NativeURL = globalThis.URL;
class UrlShim { static parseURL(s, b) { return new NativeURL(s, b); } }

// compile WebMountSites too (real gate) — it needs @kit.ArkTS + @amber/chat-domain
const sitesFile = path.join(REPO_ROOT, 'harmony/entry/src/main/ets/platform_impl/WebMountSites.ets');
const sitesJs = ts.transpileModule(fs.readFileSync(sitesFile, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
const toolFile = path.join(REPO_ROOT, 'harmony/chat/src/main/ets/chat/tool.ts');

function runModule(code, filename, resolveDep, sandboxExtra) {
  const module = { exports: {} };
  const sandbox = Object.assign({ console, setTimeout: fakeSetTimeout, clearTimeout: fakeClearTimeout }, sandboxExtra || {});
  const require = (name) => resolveDep(name, runModule);
  vm.runInNewContext('(function(require,module,exports){' + code + '\n})', sandbox, { filename })(require, module, module.exports);
  return module.exports;
}

const depSites = (name) => {
  if (name === '@kit.ArkTS') return { url: { URL: UrlShim } };
  if (name === '@amber/chat-domain') return { newId: () => 'synthetic-id' };
  return undefined;
};
const sites = runModule(sitesJs, sitesFile, depSites);
const registered = [{ id: 'github', name: 'GitHub', url: 'https://github.com/login' }];

const built = runModule(js, file, (name) => {
  if (name === '@kit.ArkWeb') return { webview: { WebviewController: MockController } };
  if (name === '@kit.ArkUI') return {};
  if (name === '@kit.ImageKit') return { image: {} };
  if (name === '@kit.ArkTS') return { util: { Base64Helper: class { encodeToStringSync() { return ''; } } } };
  if (name === '@kit.PerformanceAnalysisKit') return { hilog: { info() {}, warn() {} } };
  if (name === '@amber/deepread-domain') return {};
  if (name === './WebMountSites.ets') return sites;
  return undefined;
});
const { WebMountSession } = built;

const sleep0 = () => new Promise((r) => setImmediate(r));
const rejectionOf = (p) => p.then(() => null, (e) => e);

(async () => {
  // ---- T1: real attached signal drives open() ----
  {
    const s = new WebMountSession();
    s.setUrlGate(sites.makeSiteGate(registered));
    let mounts = 0;
    s.bindHooks({ onMountNeeded: () => { mounts++; } });
    const p = s.open('https://github.com/login');
    await sleep0();
    assert.equal(mounts, 1, 'onMountNeeded once');
    assert.equal(nav.loads.length, 0, 'no load before attach');
    s.markControllerAttached();
    await sleep0();
    assert.equal(nav.loads.length, 1, 'loadUrl after attach');
    assert.equal(nav.loads[0], 'https://github.com/login');
    s.markPageEnd('https://github.com/login');
    await p;
    assert.equal(s.isReady(), true);
    assert.equal(s.getUrl(), 'https://github.com/login');
    console.log('[R07-T1] open: mount-needed -> controller-attached -> loadUrl -> page-end resolve');
  }

  // ---- T2: mount timeout rejects, no residual waiters ----
  {
    const s = new WebMountSession();
    s.bindHooks({ onMountNeeded: () => {} });
    const base = nav.loads.length;
    const settled = rejectionOf(s.open('https://github.com/x'));
    await sleep0();
    assert.equal(pendingTimers() > 0, true);
    await advance(10001);
    const err = await settled;
    assert.match(err.message, /mount timeout/);
    assert.equal(nav.loads.length, base, 'loadUrl never called');
    assert.equal(pendingTimers(), 0, 'timeout cleared its own timer');
    console.log('[R07-T2] mount timeout rejects and clears waiter/timer');
  }

  // ---- T3: page timeout rejects (old code resolved) ----
  {
    const s = new WebMountSession();
    s.bindHooks({ onMountNeeded: () => {} });
    const base = nav.loads.length;
    const settled = rejectionOf(s.open('https://github.com/x'));
    await sleep0();
    s.markControllerAttached();
    await sleep0();
    assert.equal(nav.loads.length, base + 1);
    await advance(15001);
    const err = await settled;
    assert.match(err.message, /page load timeout/);
    assert.equal(pendingTimers(), 0);
    console.log('[R07-T3] page timeout rejects and clears waiter/timer (no silent success)');
  }

  // ---- T4: detach fails in-flight waiters ----
  {
    const s = new WebMountSession();
    s.bindHooks({ onMountNeeded: () => {} });
    const settled = rejectionOf(s.open('https://github.com/x'));
    await sleep0();
    s.markControllerAttached();
    await sleep0();
    s.markControllerDetached();
    const err = await settled;
    assert.match(err.message, /detached/);
    assert.equal(s.isReady(), false);
    console.log('[R07-T4] onDetach fails pending nav waiters and resets ready');
  }

  // ---- T5: AbortSignalLike cancels mid-wait ----
  {
    const s = new WebMountSession();
    s.bindHooks({ onMountNeeded: () => {} });
    const listeners = [];
    const signal = {
      aborted: false,
      addEventListener(_t, l) { listeners.push(l); },
      removeEventListener(_t, l) { const i = listeners.indexOf(l); if (i >= 0) listeners.splice(i, 1); },
    };
    const settled = rejectionOf(s.open('https://github.com/x', signal));
    await sleep0();
    signal.aborted = true;
    for (const l of [...listeners]) l();
    const err = await settled;
    assert.match(err.message, /cancelled/);
    console.log('[R07-T5] AbortSignalLike cancels a pending open');
  }

  // ---- T6: per-navigation gate + real event url ----
  {
    const s = new WebMountSession();
    s.setUrlGate(sites.makeSiteGate(registered));
    assert.equal(s.shouldBlockUrl('https://github.com/x'), false);
    assert.equal(s.shouldBlockUrl('https://gist.github.com/a'), false);
    assert.equal(s.shouldBlockUrl('about:blank'), false, 'internal placeholder allowed');
    assert.equal(s.shouldBlockUrl('https://outside.invalid/x'), true);
    assert.equal(s.getLastBlockedUrl(), 'https://outside.invalid/x');

    s.bindHooks({ onMountNeeded: () => {} });
    const settled = rejectionOf(s.open('https://github.com/login'));
    await sleep0();
    s.markControllerAttached();
    await sleep0();
    // redirect lands on a non-allowed host -> must not be considered ready
    s.markPageEnd('https://outside.invalid/landing');
    const err6 = await settled;
    assert.match(err6.message, /page load failed/);
    assert.equal(s.isReady(), false);
    assert.equal(s.getUrl(), 'https://outside.invalid/landing', 'uses the real event url');
    console.log('[R07-T6] navigation/redirect recheck fails open on non-allowed host');
  }

  // ---- T7: owner-scoped unbind ----
  {
    const s = new WebMountSession();
    let h1 = 0, h2 = 0;
    const b1 = s.bindHooks({ onMountNeeded: () => { h1++; } });
    s.bindHooks({ onMountNeeded: () => { h2++; } });
    b1.unbind(); // stale page 1 leaves; must NOT unbind page 2's hooks
    const settled = rejectionOf(s.open('https://github.com/x'));
    await sleep0();
    assert.equal(h2, 1, 'new owner still bound');
    assert.equal(h1, 0);
    // page 2 unbinds -> hooks gone (open would then skip mount-needed, but we just check no throw path)
    s.markControllerDetached();
    const err7 = await settled;
    assert.match(err7.message, /detached/);
    console.log('[R07-T7] bindHooks unbind is owner-scoped (older page cannot unbind newer hooks)');
  }

  // ---- T8: P2-1 revoke without a next tool, driven by the real saveWebMountSites listener ----
  {
    const g = built.getWebMountSession(); // creates global + auto-registers sites listener
    assert.equal(g.isEnabled(), true);
    g.setUrlGate(sites.makeSiteGate(registered));
    g.bindHooks({ onMountNeeded: () => {} });
    const settled = rejectionOf(g.open('https://github.com/login'));
    await sleep0();
    g.markControllerAttached();
    await sleep0();
    g.markPageEnd('https://github.com/login');
    await settled;
    assert.equal(g.getUrl(), 'https://github.com/login');
    assert.equal(g.isReady(), true);

    // revoke every site via the real persistence helper -> listener must reset immediately,
    // with NO subsequent tool call.
    const writeKv = { get: async () => null, put: async () => {} };
    await sites.saveWebMountSites(writeKv, []);
    assert.equal(g.getUrl(), '', 'revoke must reset current url without a next tool');
    assert.equal(g.isReady(), false, 'revoked page must no longer be ready');
    console.log('[P2-1-T2] revoke via saveWebMountSites listener immediately resets the live session');

    // global switch off also blocks synchronously
    built.setWebMountGlobalEnabled(false);
    assert.equal(g.isEnabled(), false);
    assert.equal(g.shouldBlockUrl('https://github.com/x'), true, 'disabled session blocks navigation');
    built.setWebMountGlobalEnabled(true);
    assert.equal(g.isEnabled(), true);
    console.log('[P2-1-T3] global switch off synchronously disables the live session');
  }

  console.log('\nAll batch-4 R07 assertions passed. Mock controller + fake clock only; no SDK/UI/network.');
})().catch((e) => { console.error(e); process.exitCode = 1; });
