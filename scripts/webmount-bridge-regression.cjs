'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');

const bridgePath = path.resolve(__dirname, '..', 'app/src/main/assets/webmount/bridge.js');
const bridgeSource = fs.readFileSync(bridgePath, 'utf8');

function createHarness() {
  const tailText = 'A'.repeat(4500) + 'OLD'.repeat(200);
  const dom = new JSDOM(`<!doctype html><html><head><title>bridge fixture</title></head><body>
    <form id="form">
      <label for="email">Email address</label><input id="email" type="text">
      <input id="check" type="checkbox">
      <select id="enabledSelect"><option value="a">A</option><option value="b">B</option></select>
      <select id="disabledSelect" disabled><option value="a">A</option><option value="b">B</option></select>
      <select id="optionSelect"><option value="a">A</option><option value="b" disabled>B</option></select>
      <input id="readonly" readonly value="fixed">
      <button id="submit">Submit</button>
    </form>
    <div id="wrapper"><button id="login">Login</button></div>
    <button id="disabled" disabled>Disabled</button>
    <button id="ariaDisabled" aria-disabled="true">Aria disabled</button>
    <button id="bottom">Bottom</button>
    <img id="partial" alt="partial">
    <img id="offscreen" alt="offscreen">
    <img id="visible" alt="visible">
    <p id="tail">${tailText}</p>
  </body></html>`, {
    url: 'https://fixture.test/page',
    runScripts: 'outside-only',
  });
  const { window } = dom;
  const { document } = window;

  let scrollY = 0;
  Object.defineProperty(window, 'scrollY', { configurable: true, get: () => scrollY });
  Object.defineProperty(window, 'scrollX', { configurable: true, value: 0 });
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: 800 });
  Object.defineProperty(window, 'innerHeight', { configurable: true, value: 600 });
  Object.defineProperty(window, 'devicePixelRatio', { configurable: true, value: 1 });
  window.scrollTo = (_x, y) => { scrollY = Number(y) || 0; };
  window.scrollBy = (_x, y) => { scrollY += Number(y) || 0; };
  window.document.elementFromPoint = () => document.getElementById('disabled');

  const tops = {
    email: 20,
    check: 50,
    enabledSelect: 80,
    disabledSelect: 110,
    optionSelect: 140,
    readonly: 170,
    submit: 200,
    login: 250,
    disabled: 290,
    ariaDisabled: 320,
    bottom: 500,
    partial: -50,
    offscreen: 2000,
    visible: 100,
  };
  const heights = { partial: 100, offscreen: 100, visible: 100 };
  Object.defineProperty(window.HTMLElement.prototype, 'innerText', {
    configurable: true,
    get() { return this.textContent || ''; },
    set(value) { this.textContent = value; },
  });
  window.HTMLElement.prototype.getBoundingClientRect = function () {
    const top = (tops[this.id] == null ? 10 : tops[this.id]) - scrollY;
    const width = 100;
    const height = heights[this.id] || 24;
    return { left: 0, top, width, height, right: width, bottom: top + height };
  };
  window.getComputedStyle = () => ({ visibility: 'visible', display: 'block', opacity: '1' });

  const results = Object.create(null);
  window.AmberWM = {
    resolve(id, payload) { results[id] = JSON.parse(payload); },
    reject(id, error) { results[id] = { __reject: error }; },
    onNetworkEvent() {},
    onDomMutation() {},
    log() {},
  };
  window.fetch = function (input) {
    const url = new URL(typeof input === 'string' ? input : input.url, window.location.href).href;
    if (url.endsWith('/api/body-error')) {
      return Promise.resolve({
        url,
        status: 200,
        headers: {
          forEach(callback) { callback('text/plain', 'content-type'); },
          get() { return 'text/plain'; },
        },
        text() { return Promise.reject(new Error('body stream failed')); },
      });
    }
    return new Promise(() => {});
  };
  window.eval(bridgeSource);

  function call(method, args, id) {
    window.__amberWm_call(method, JSON.stringify(args || {}), id);
    return results[id];
  }

  return { window, document, results, call };
}

function waitForResult(results, id, timeoutMs = 1000) {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + timeoutMs;
    function poll() {
      if (Object.prototype.hasOwnProperty.call(results, id)) {
        resolve(results[id]);
        return;
      }
      if (Date.now() >= deadline) {
        reject(new Error(`timed out waiting for ${id}`));
        return;
      }
      setTimeout(poll, 5);
    }
    poll();
  });
}

async function main() {
  const { window, document, results, call } = createHarness();
  let loginClicks = 0;
  let disabledClicks = 0;
  document.getElementById('login').addEventListener('click', () => { loginClicks++; });
  document.getElementById('disabled').addEventListener('click', () => { disabledClicks++; });

  const selectorClick = call('click', { selector: 'text=Login' }, 'selector_click');
  assert.equal(selectorClick.ok, true);
  assert.equal(selectorClick.target.name, 'Login');
  assert.equal(loginClicks, 1);

  const interactive = call('extract', { mode: 'interactive' }, 'interactive');
  const emailNode = interactive.nodes.find(node => node.name === 'Email address');
  assert.ok(emailNode, 'native label should provide the input accessible name');
  const bottomNode = interactive.nodes.find(node => node.name === 'Bottom');
  const loginNode = interactive.nodes.find(node => node.name === 'Login');
  assert.ok(bottomNode && loginNode);

  const disabledClick = call('click', { selector: '#disabled' }, 'disabled_click');
  assert.equal(disabledClick.ok, false);
  assert.equal(disabledClick.error.code, 'target_disabled');
  assert.equal(disabledClicks, 0);

  const ariaDisabledClick = call('click', { selector: '#ariaDisabled' }, 'aria_disabled_click');
  assert.equal(ariaDisabledClick.ok, false);
  assert.equal(ariaDisabledClick.error.code, 'target_disabled');

  const disabledTap = call('tap', { x: 10, y: 10 }, 'disabled_tap');
  assert.equal(disabledTap.ok, false);
  assert.equal(disabledTap.error.code, 'target_disabled');
  assert.equal(disabledClicks, 0);

  const readonly = document.getElementById('readonly');
  const readonlyType = call('type', { selector: '#readonly', text: 'x' }, 'readonly_type');
  assert.equal(readonlyType.ok, false);
  assert.equal(readonlyType.error.code, 'target_readonly');
  assert.equal(readonly.value, 'fixed');

  const disabledSelect = document.getElementById('disabledSelect');
  const disabledSelectResult = call('select', { selector: '#disabledSelect', value: 'b' }, 'disabled_select');
  assert.equal(disabledSelectResult.ok, false);
  assert.equal(disabledSelectResult.error.code, 'target_disabled');
  assert.equal(disabledSelect.selectedIndex, 0);

  const optionSelect = document.getElementById('optionSelect');
  const disabledOption = call('select', { selector: '#optionSelect', value: 'b' }, 'disabled_option');
  assert.equal(disabledOption.ok, false);
  assert.equal(disabledOption.error.code, 'option_disabled');
  assert.equal(optionSelect.selectedIndex, 0);

  window.scrollTo(0, 100);
  const staleAfterScroll = call('click', { target: bottomNode.ref }, 'stale_after_scroll');
  assert.equal(staleAfterScroll.ok, false);
  assert.equal(staleAfterScroll.error.code, 'target_not_found');
  window.scrollTo(0, 0);

  const visual = call('visual_snapshot', { max_candidates: 30 }, 'visual');
  assert.deepEqual(visual.candidates.map(node => node.alt), ['partial', 'visible']);
  assert.equal(visual.candidates.some(node => node.alt === 'offscreen'), false);
  assert.notEqual(visual.candidates[0].ref, emailNode.ref);
  const staleAfterSnapshot = call('click', { target: emailNode.ref }, 'stale_after_snapshot');
  assert.equal(staleAfterSnapshot.ok, false);
  assert.equal(staleAfterSnapshot.error.code, 'target_not_found');
  assert.equal(loginClicks, 1);

  const visualZero = call('visual_snapshot', { max_candidates: 0 }, 'visual_zero');
  assert.equal(visualZero.candidates.length, 0);
  const observeZero = call('observe', { max_visual_candidates: 0 }, 'observe_zero');
  assert.equal(observeZero.visual.candidates.length, 0);
  const cachedLogin = observeZero.interactive.nodes.find(node => node.name === 'Login');
  call('visual_snapshot', {}, 'replace_cached_snapshot');
  const restored = call('restore_snapshot_refs', observeZero, 'restore_cached_refs');
  assert.equal(restored.missing, 0);
  assert.equal(call('click', { target: cachedLogin.ref }, 'restored_click').ok, true);
  assert.equal(loginClicks, 2);

  const semantic = () => call('semantic_state', {}, `semantic_${Date.now()}_${Math.random()}`).semantic_fingerprint;
  const tail = document.getElementById('tail');
  const tailBefore = semantic();
  tail.textContent = 'A'.repeat(4500) + 'NEW'.repeat(200);
  assert.notEqual(tailBefore, semantic(), 'semantic fingerprint should include the readable tail');

  const email = document.getElementById('email');
  const valueBefore = semantic();
  email.value = 'abc';
  assert.notEqual(valueBefore, semantic(), 'semantic fingerprint should include a hashed control value');

  const checkbox = document.getElementById('check');
  const checkedBefore = semantic();
  checkbox.checked = true;
  assert.notEqual(checkedBefore, semantic(), 'semantic fingerprint should include checked state');

  const enabledSelect = document.getElementById('enabledSelect');
  const selectedBefore = semantic();
  enabledSelect.selectedIndex = 1;
  assert.notEqual(selectedBefore, semantic(), 'semantic fingerprint should include selected index');

  const disabledBefore = semantic();
  email.disabled = true;
  assert.notEqual(disabledBefore, semantic(), 'semantic fingerprint should include disabled state');

  const readonlyBefore = semantic();
  email.readOnly = true;
  assert.notEqual(readonlyBefore, semantic(), 'semantic fingerprint should include readonly state');

  const signalBefore = call('semantic_state', {}, 'signal_before').network_signal_seq;
  window.fetch('/log');
  window.fetch('/logs/collect');
  window.fetch('/login');
  window.fetch('/logout');
  window.fetch('/catalog?next=/log');
  assert.equal(call('semantic_state', {}, 'signal_after').network_signal_seq, signalBefore + 3);

  call('fetch_replay', { method: 'GET', url: '/api/body-error', max_chars: 100 }, 'fetch_body_error');
  const fetchBodyError = await waitForResult(results, 'fetch_body_error');
  assert.deepEqual(fetchBodyError, { ok: false, error: 'body stream failed' });

  console.log('webmount bridge regression: PASS');
}

main().catch(error => {
  console.error(error.stack || error);
  process.exitCode = 1;
});
