// probe-batch4-webmount-tools.cjs — L01 regression probe (JS injection + approval contract).
//
// Drives the REAL `createWebMountTools` from
// harmony/entry/src/main/ets/platform_impl/WebMountTools.ets and the REAL
// `siteAllowsUrl`/`makeSiteGate` from WebMountSites.ets. The only substitution is a
// fake WebMountSession whose `evalJson` runs the produced script inside a node vm
// with a synthetic `window`/`document` — the business `clickScript`/`typeScript` text
// is produced by the real code under test, not copied.
//
// Asserts:
//   - wm_click now carries mandatory approval (allowsAutoApproval=false,
//     mandatoryApproval=true, needsApproval=true);
//   - hostile target/selector/text values (quotes, backslash, newline, template,
//     U+2028) never evaluate an extra expression, even with wm_eval omitted;
//   - an aborted signal aborts click/eval;
//   - revoking the site makes page tools refuse and reset the page (nav-time recheck).

const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const { REPO_ROOT, createLoader } = require('../../2026-09-13-harmony-merged-evidence/probe-lib.cjs');

const ETS = path.join(REPO_ROOT, 'harmony/entry/src/main/ets/platform_impl');
const NativeURL = globalThis.URL;
class UrlShim { static parseURL(s, b) { return new NativeURL(s, b); } }

// mutable KV backing the real loadWebMountSites / loadWebMountGlobalEnabled
const kvState = {
  sites: JSON.stringify([{ id: 'github', name: 'GitHub', url: 'https://github.com/login' }]),
  global: 'true',
};
const kv = {
  async get(k) { return k === 'webmount_global_enabled' ? kvState.global : kvState.sites; },
  async put(k, v) { if (k === 'webmount_global_enabled') kvState.global = v; else kvState.sites = v; },
};

const { load } = createLoader((name, loadFn) => {
  if (name === '@kit.ArkTS') return { url: { URL: UrlShim } };
  if (name === '@amber/chat-domain') {
    return { ...loadFn(path.join(REPO_ROOT, 'harmony/chat/src/main/ets/chat/tool.ts')), newId: () => 'synthetic-id' };
  }
  if (name.includes('AppContainer')) return { getChatKvStore: () => kv };
  if (name.endsWith('WebMountSites.ets')) return loadFn(path.join(ETS, 'WebMountSites.ets'));
  return undefined;
});

const sites = load(path.join(ETS, 'WebMountSites.ets'));
const toolsMod = load(path.join(ETS, 'WebMountTools.ets'));

// fake session: enough surface for the tools; records scripts and reset calls
function makeSession() {
  const ctxObj = {
    window: { __audit: 0 },
    document: {
      querySelector: () => null, querySelectorAll: () => [],
      body: { innerText: '' }, title: '',
    },
    location: { href: 'https://github.com/login' },
  };
  const sandbox = vm.createContext(ctxObj);
  const state = {
    url: 'https://github.com/login', ready: true, gate: null, resets: 0, scripts: [],
    enabled: true,
    setEnabled(v) { state.enabled = v; if (!v) state.resetToBlank(); },
    isEnabled() { return state.enabled; },
    setUrlGate(g) { state.gate = g; },
    getUrl() { return state.url; },
    isReady() { return state.ready; },
    resetToBlank() { state.resets++; state.url = ''; state.ready = false; },
    async evalJson(expr) {
      state.scripts.push(expr);
      return JSON.stringify({ ok: true, value: vm.runInContext(expr, sandbox) });
    },
    ctxObj,
  };
  return state;
}

(async () => {
  // ---- click approval contract (L01) ----
  const s1 = makeSession();
  const toolsNoEval = toolsMod.createWebMountTools(s1, false);
  assert.equal(toolsNoEval.some((t) => t.name === 'wm_eval'), false);
  const click = toolsNoEval.find((t) => t.name === 'wm_click');
  assert.equal(click.needsApproval, true);
  assert.equal(click.allowsAutoApproval, false, 'click must not be auto-approvable');
  assert.equal(click.mandatoryApproval, true, 'click must be mandatory-approval');
  console.log('[L01-T1] wm_click = needsApproval/!auto/mandatory; includeEval=false hides wm_eval');

  // ---- hostile targets never gain code execution ----
  const hostileTargets = [
    `[data-x="x'+(window.__audit=1)+'"]`,
    `[data-x='x"+(window.__audit=2)+"']`,
    `[data-x="a\\"+(window.__audit=3)+"b"]`,
    `[data-x="a\n'+(window.__audit=4)+'\nb"]`,
    'wm-0"+(window.__audit=5)+"',
    '[data-x="`+(window.__audit=6)+`"]`',
    '[data-x="\u2028"+(window.__audit=7)+"\u2029"]',
  ];
  for (const target of hostileTargets) {
    await click.execute({ target });
    assert.equal(s1.ctxObj.window.__audit, 0, 'injected expression ran for target: ' + JSON.stringify(target));
  }
  // the produced script must be a JSON-encoded literal, never contain a bare quote-escape
  assert.ok(s1.scripts.every((s) => s.includes('const sel =')));
  console.log('[L01-T2] 7 hostile targets: window.__audit stayed 0 (no injected expression executed)');

  // ---- wm_type / wm_wait injection ----
  const s2 = makeSession();
  const tools2 = toolsMod.createWebMountTools(s2, false);
  const wmType = tools2.find((t) => t.name === 'wm_type');
  await wmType.execute({ selector: `"+(window.__audit=11)+"`, text: `'+(window.__audit=12)+'` });
  const wmWait = tools2.find((t) => t.name === 'wm_wait');
  await wmWait.execute({ url_includes: `x'||(window.__audit=13)||'`, timeout_ms: 0 });
  assert.equal(s2.ctxObj.window.__audit, 0, 'type/wait injected expression ran');
  console.log('[L01-T3] wm_type / wm_wait hostile values: no extra expression');

  // ---- aborted signal ----
  const s3 = makeSession();
  const tools3 = toolsMod.createWebMountTools(s3, true);
  const click3 = tools3.find((t) => t.name === 'wm_click');
  const eval3 = tools3.find((t) => t.name === 'wm_eval');
  const aborted = { aborted: true };
  await assert.rejects(() => click3.execute({ target: 'button' }, aborted), /cancelled/);
  await assert.rejects(() => eval3.execute({ code: '1+1' }, aborted), /cancelled/);
  console.log('[L01-T4] aborted signal aborts wm_click / wm_eval');

  // ---- nav-time recheck: revoke site -> page tool refuses and resets ----
  const s4 = makeSession();
  const tools4 = toolsMod.createWebMountTools(s4, false);
  const extract = tools4.find((t) => t.name === 'wm_extract');
  await extract.execute({});
  assert.equal(s4.resets, 0, 'allowed page must not reset');
  kvState.sites = JSON.stringify([]); // user revoked every site; empty array is authoritative
  const res = await extract.execute({});
  const payload = JSON.parse(res[0].text);
  assert.equal(payload.ok, false);
  assert.match(payload.error, /no longer allowed/);
  assert.equal(s4.resets, 1, 'revoked page must be reset to about:blank');
  console.log('[L01-T5] revoke site: extra extract refused, page reset (gate refreshed per page op)');

  // ---- open rejects an unparsable/non-http url via the site gate ----
  const s5 = makeSession();
  const tools5 = toolsMod.createWebMountTools(s5, false);
  const open = tools5.find((t) => t.name === 'wm_open');
  const deny = JSON.parse((await open.execute({ url: 'javascript:alert(1)' }))[0].text);
  assert.equal(deny.ok, false);
  assert.equal(s5.scripts.length, 0, 'denied open must not reach the page');
  console.log('[L01-T6] wm_open javascript: URL denied by standard parser before any navigation');

  // ---- P2-1: global switch off terminates the session, but OR semantics hold ----
  // global off + assistant NOT opted in -> refuse
  const s6 = makeSession();
  const tools6 = toolsMod.createWebMountTools(s6, false, false);
  const extract6 = tools6.find((t) => t.name === 'wm_extract');
  const open6 = tools6.find((t) => t.name === 'wm_open');
  kvState.sites = JSON.stringify([{ id: 'github', name: 'GitHub', url: 'https://github.com/login' }]);
  kvState.global = 'false';
  const openDenied = JSON.parse((await open6.execute({ url: 'https://github.com/login' }))[0].text);
  assert.equal(openDenied.ok, false);
  assert.match(openDenied.error, /disabled/);
  const blocked = JSON.parse((await extract6.execute({}))[0].text);
  assert.equal(blocked.ok, false);
  assert.match(blocked.error, /disabled/);
  assert.equal(s6.scripts.length, 0, 'disabled session must not run page scripts');
  console.log('[P2-1-T1] global off + assistant not opted: page tools refuse, wm_open denied');

  // global off + assistant explicitly opted in -> OR allows (global is not a total disable)
  const s7 = makeSession();
  s7.enabled = false; // simulate a session already terminated by the global switch
  s7.url = 'https://github.com/login';
  s7.ready = true;
  const tools7 = toolsMod.createWebMountTools(s7, false, true);
  const extract7 = tools7.find((t) => t.name === 'wm_extract');
  const res7 = JSON.parse((await extract7.execute({}))[0].text);
  assert.equal(res7.ok, true, 'assistant opt-in must survive a global off (OR semantics)');
  assert.equal(s7.enabled, true, 'session re-enabled by OR on next tool call');
  console.log('[P2-1-T2] global off + assistant opted: OR semantics keep WebMount usable');

  // assistant off + global on -> OR allows
  const s8 = makeSession();
  const tools8 = toolsMod.createWebMountTools(s8, false, false);
  kvState.global = 'true';
  const res8 = JSON.parse((await tools8.find((t) => t.name === 'wm_extract').execute({}))[0].text);
  assert.equal(res8.ok, true, 'global on must keep WebMount usable when assistant not opted');
  console.log('[P2-1-T3] global on + assistant not opted: OR semantics keep WebMount usable');
  kvState.global = 'true';

  console.log('\nAll batch-4 L01 assertions passed. Fake session + vm sandbox only; no browser/network/user data.');
})().catch((e) => { console.error(e); process.exitCode = 1; });
