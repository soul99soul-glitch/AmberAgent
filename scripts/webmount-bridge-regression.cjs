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
    <section id="zcodeTask" data-testid="v4-session-pane-task-a" data-session-id="remote-task-a">
      <form id="zcodeComposer" class="chat-composer-region" data-testid="v4-composer" data-input-routing="startNow">
        <div id="zcodeInput" data-testid="v4-composer-input" data-lexical-editor="true" role="textbox" contenteditable="true" aria-label="ZCode prompt"><p><br></p></div>
        <button id="zcodeSend" data-testid="v4-composer-send" type="submit">Send</button>
      </form>
    </section>
    <div id="plainEditable" contenteditable="true"><p>seed</p></div>
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
    zcodeTask: 350,
    zcodeComposer: 350,
    zcodeInput: 360,
    zcodeSend: 390,
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

  // Small browser editing shim for the Lexical fixture. It models the public
  // contenteditable contract: a native edit emits beforeinput, the editor may
  // consume it, and an unconsumed edit emits input after the DOM mutation.
  const zcodeInput = document.getElementById('zcodeInput');
  const editingEvents = [];
  const replaceEditableText = (text) => {
    zcodeInput.replaceChildren(document.createElement('p'));
    zcodeInput.firstElementChild.textContent = text;
  };
  zcodeInput.addEventListener('beforeinput', event => {
    editingEvents.push({ type: 'beforeinput', inputType: event.inputType, data: event.data });
    event.preventDefault();
    if (event.inputType === 'insertText') replaceEditableText(event.data || '');
    if (event.inputType === 'deleteContentBackward') replaceEditableText('');
  });
  zcodeInput.addEventListener('input', event => {
    editingEvents.push({ type: 'input', inputType: event.inputType, data: event.data });
  });
  const plainEditable = document.getElementById('plainEditable');
  const plainEditingEvents = [];
  plainEditable.addEventListener('beforeinput', event => {
    plainEditingEvents.push({ type: 'beforeinput', inputType: event.inputType, data: event.data });
  });
  plainEditable.addEventListener('input', event => {
    plainEditingEvents.push({ type: 'input', inputType: event.inputType, data: event.data });
  });

  const results = Object.create(null);
  window.AmberWM = {
    resolve(id, payload) { results[id] = JSON.parse(payload); },
    reject(id, error) { results[id] = { __reject: error }; },
    onNetworkEvent() {},
    onDomMutation() {},
    log() {},
  };
  window.__amberZCodeUiTree = (_args, hooks) => ({ ok: true, snapshot_id: hooks.snapshotId, nodes: [] });
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

  return { window, document, results, call, editingEvents, plainEditingEvents };
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
  const { window, document, results, call, editingEvents, plainEditingEvents } = createHarness();
  let loginClicks = 0;
  let disabledClicks = 0;
  let zcodeSubmits = 0;
  document.getElementById('login').addEventListener('click', () => { loginClicks++; });
  document.getElementById('disabled').addEventListener('click', () => { disabledClicks++; });
  document.getElementById('zcodeComposer').addEventListener('submit', event => {
    event.preventDefault();
    zcodeSubmits++;
  });

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

  const zcodeComposerElement = document.getElementById('zcodeComposer');
  zcodeComposerElement.setAttribute('data-input-routing', 'enqueue');
  const enqueueRead = call('zcode_read', { include_page_text: false }, 'zcode_read_enqueue');
  assert.equal(enqueueRead.zcode.supported, true);
  assert.equal(enqueueRead.zcode.input_routing, 'enqueue');
  assert.equal(enqueueRead.zcode.send_target, null);
  zcodeComposerElement.setAttribute('data-input-routing', 'startNow');
  const zcodeRead = call('zcode_read', { include_page_text: false }, 'zcode_read');
  assert.equal(zcodeRead.zcode.supported, true);
  assert.equal(zcodeRead.zcode.input_routing, 'startNow');
  assert.equal(zcodeRead.zcode.draft_chars, 0);
  assert.equal(zcodeRead.zcode.remote_task_id, 'remote-task-a');
  assert.ok(zcodeRead.zcode.composer_target.ref);
  assert.ok(zcodeRead.zcode.send_target.ref);
  assert.equal(zcodeRead.ui_tree.snapshot_id, zcodeRead.zcode.snapshot_id);
  assert.equal(Object.prototype.hasOwnProperty.call(zcodeRead.observation, 'readable'), false);
  assert.equal(Object.prototype.hasOwnProperty.call(zcodeRead.zcode.composer_target, 'text'), false);
  const wrongRemoteTask = call('zcode_prepare', {
    snapshot_id: zcodeRead.zcode.snapshot_id,
    remote_task_id: 'remote-task-b',
    input_target: zcodeRead.zcode.composer_target.ref,
    send_target: zcodeRead.zcode.send_target.ref,
    text: 'must not type',
  }, 'zcode_prepare_wrong_task');
  assert.equal(wrongRemoteTask.ok, false);
  assert.equal(wrongRemoteTask.error.code, 'remote_task_mismatch');
  assert.equal(document.getElementById('zcodeInput').textContent, '');
  const zcodePrepare = call('zcode_prepare', {
    snapshot_id: zcodeRead.zcode.snapshot_id,
    remote_task_id: zcodeRead.zcode.remote_task_id,
    input_target: zcodeRead.zcode.composer_target.ref,
    send_target: zcodeRead.zcode.send_target.ref,
    text: 'Ask ZCode',
  }, 'zcode_prepare');
  assert.equal(zcodePrepare.ok, true);
  assert.equal(zcodePrepare.text_chars, 9);
  assert.deepEqual(editingEvents, [{ type: 'beforeinput', inputType: 'insertText', data: 'Ask ZCode' }]);
  assert.equal(document.getElementById('zcodeInput').textContent, 'Ask ZCode');
  const zcodeSend = call('zcode_send', { ticket: zcodePrepare.ticket }, 'zcode_send');
  assert.equal(zcodeSend.ok, true);
  assert.equal(zcodeSend.dispatched, true);
  assert.equal(zcodeSubmits, 1);
  const reusedTicket = call('zcode_send', { ticket: zcodePrepare.ticket }, 'zcode_send_reused');
  assert.equal(reusedTicket.ok, false);
  assert.equal(reusedTicket.error.code, 'ticket_used');

  // A session pane can reuse its DOM node for another remote task. The
  // one-shot ticket must catch that identity change and retain the draft for
  // inspection instead of clicking the new task's submit button.
  const zcodeInputElement = document.getElementById('zcodeInput');
  zcodeInputElement.replaceChildren(document.createElement('p'));
  const zcodeReadAgain = call('zcode_read', {}, 'zcode_read_again');
  const zcodePrepareAgain = call('zcode_prepare', {
    snapshot_id: zcodeReadAgain.zcode.snapshot_id,
    remote_task_id: zcodeReadAgain.zcode.remote_task_id,
    input_target: zcodeReadAgain.zcode.composer_target.ref,
    send_target: zcodeReadAgain.zcode.send_target.ref,
    text: 'Task guarded',
  }, 'zcode_prepare_again');
  assert.equal(zcodePrepareAgain.ok, true);
  document.getElementById('zcodeTask').setAttribute('data-session-id', 'remote-task-b');
  const changedTaskSend = call('zcode_send', { ticket: zcodePrepareAgain.ticket }, 'zcode_send_changed_task');
  assert.equal(changedTaskSend.ok, false);
  assert.equal(changedTaskSend.error.code, 'ticket_stale');
  assert.equal(changedTaskSend.draft_retained, true);
  assert.equal(zcodeInputElement.textContent, 'Task guarded');
  assert.equal(zcodeSubmits, 1);

  // An ordinary contenteditable with no beforeinput consumer exercises the
  // bridge's real DOM Range fallback. Verify append, replace, and clear each
  // mutate the current document exactly once.
  const plainEditable = document.getElementById('plainEditable');
  const append = call('type', { selector: '#plainEditable', text: ' +', clear: false }, 'plain_append');
  assert.equal(append.ok, true);
  assert.equal(plainEditable.textContent, 'seed +');
  const replace = call('type', { selector: '#plainEditable', text: 'replaced', clear: true }, 'plain_replace');
  assert.equal(replace.ok, true);
  assert.equal(plainEditable.textContent, 'replaced');
  const clear = call('type', { selector: '#plainEditable', text: '', clear: true }, 'plain_clear');
  assert.equal(clear.ok, true);
  assert.equal(plainEditable.textContent, '');
  assert.deepEqual(plainEditingEvents, [
    { type: 'beforeinput', inputType: 'insertText', data: ' +' },
    { type: 'input', inputType: 'insertText', data: ' +' },
    { type: 'beforeinput', inputType: 'insertText', data: 'replaced' },
    { type: 'input', inputType: 'insertText', data: 'replaced' },
    { type: 'beforeinput', inputType: 'deleteContentBackward', data: null },
    { type: 'input', inputType: 'deleteContentBackward', data: null },
  ]);

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
