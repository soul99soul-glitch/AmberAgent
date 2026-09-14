'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');

const assetPath = path.resolve(__dirname, '..', 'app/src/main/assets/webmount/zcode-ui-tree.js');
const assetSource = fs.readFileSync(assetPath, 'utf8');

function createHarness() {
  const dom = new JSDOM(`<!doctype html><html><body>
    <header data-testid="workspace-header" data-workspace-header-variant="task">
      <button aria-label="返回任务首页">Back</button>
      <h1 data-testid="workspace-title" title="Task A">Task A</h1>
      <div data-testid="workspace-path" title="/tmp/project-a">project-a</div>
    </header>
    <nav>
      <button data-testid="task-item-task-a" data-state="selected" data-pinned="true">Task A project-a 2</button>
      <button data-testid="task-item-task-b" data-state="idle">Task B project-a 1</button>
      <button aria-expanded="false" title="/tmp/project-a · 2 tasks">Project A</button>
    </nav>
    <div data-testid="v4-session-pane-workspace-main" data-session-id="task-a"
         data-projection-seq="7" data-running-subagent-ids=""
         data-running-subagent-work-ids="">
      <div data-testid="v4-timeline" data-v4-timeline-scroll="true"
           data-row-count="6" data-window-row-count="6" data-total-row-count="20"
           data-following="true" data-loading-older="false">
        <div data-row-id="1" class="group/user-row"><span>Question one</span></div>
        <div data-row-id="2" class="group/assistant-row"><span>Answer one</span></div>
        <div data-row-id="3" class="py-0"><span data-tool-name="Bash" data-status="completed">terminal</span></div>
        <div data-row-id="4" class="group/assistant-row"><span>Answer two</span>
          <button data-testid="chat-reasoning-trigger" aria-expanded="false" data-state="closed">思考</button>
          <div data-testid="chat-reasoning-content" data-state="closed"><span>HIDDEN_REASONING</span></div>
        </div>
        <div data-row-id="5" class="group/user-row"><div data-v4-user-input-collapsible-content>Question two</div></div>
        <div data-row-id="6" class="group/assistant-row"><span>Final answer</span>
          <div data-testid="chat-reasoning-content" data-state="closed"><span>HIDDEN_FINAL_REASONING</span></div>
        </div>
      </div>
    </div>
    <div data-testid="v4-composer" data-input-routing="startNow">
      <div data-testid="v4-composer-input" data-lexical-editor="true" contenteditable="true"
           role="textbox" aria-placeholder="Ask ZCode"></div>
      <button data-testid="v4-composer-send" type="submit">Send</button>
      <button data-testid="chat-model-select-trigger" aria-expanded="false">Model</button>
      <button data-testid="chat-mode-select-trigger" role="combobox" aria-label="切换模式">完全访问</button>
    </div>
    <div data-testid="chat-loading" role="status" aria-label="加载中"></div>
    <div role="dialog" aria-label="Visible dialog"><button>Dialog action</button></div>
  </body></html>`, { url: 'https://zcode.test/remote/v4', runScripts: 'outside-only' });
  const { window } = dom;
  const { document } = window;
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: 800 });
  Object.defineProperty(window, 'innerHeight', { configurable: true, value: 400 });

  function boxFor(el) {
    const rowId = Number(el.getAttribute && el.getAttribute('data-row-id'));
    if (Number.isFinite(rowId)) {
      const top = rowId === 6 ? 500 : rowId * 40;
      return { left: 0, top, right: 760, bottom: top + 30, width: 760, height: 30 };
    }
    const testId = el.getAttribute && el.getAttribute('data-testid');
    if (testId === 'v4-timeline') return { left: 0, top: 0, right: 800, bottom: 360, width: 800, height: 360 };
    if (testId === 'v4-composer') return { left: 0, top: 360, right: 800, bottom: 400, width: 800, height: 40 };
    if (testId === 'v4-composer-input' || testId === 'v4-composer-send' || testId === 'chat-model-select-trigger' || testId === 'chat-mode-select-trigger') {
      return { left: 0, top: 360, right: 300, bottom: 390, width: 300, height: 30 };
    }
    return { left: 0, top: 10, right: 300, bottom: 40, width: 300, height: 30 };
  }

  window.HTMLElement.prototype.getBoundingClientRect = function getBoundingClientRect() {
    return boxFor(this);
  };
  Object.defineProperty(window.HTMLElement.prototype, 'clientHeight', {
    configurable: true,
    get() { return this.getAttribute('data-testid') === 'v4-timeline' ? 360 : 30; },
  });
  Object.defineProperty(window.HTMLElement.prototype, 'scrollHeight', {
    configurable: true,
    get() { return this.getAttribute('data-testid') === 'v4-timeline' ? 1000 : 30; },
  });
  window.getComputedStyle = (el) => ({
    visibility: 'visible',
    display: el.hidden ? 'none' : 'block',
    opacity: '1',
    overflowY: el.getAttribute('data-testid') === 'v4-timeline' ? 'auto' : 'visible',
    overflowX: 'hidden',
  });

  let refSeq = 0;
  const entries = new Map();
  const helpers = {
    snapshotId: 9,
    isVisible(el) {
      return !el.hidden && String(el.getAttribute('aria-hidden') || '').toLowerCase() !== 'true' &&
        String(window.getComputedStyle(el).display) !== 'none';
    },
    describe(el) {
      const rect = boxFor(el);
      const role = el.getAttribute('role') || (el.tagName === 'BUTTON' ? 'button' : null);
      const name = el.getAttribute('aria-label') || el.getAttribute('aria-placeholder') ||
        el.getAttribute('title') || (el.textContent || '').trim();
      return {
        tag: el.tagName.toLowerCase(),
        path: '#' + (el.id || el.getAttribute('data-testid') || el.getAttribute('data-row-id') || (++refSeq)),
        role,
        name: name || null,
        rect: [rect.left, rect.top, rect.width, rect.height],
        visible: helpers.isVisible(el),
        text: (el.textContent || '').trim(),
      };
    },
    remember(el, node) {
      const ref = `wmref_test_${++refSeq}`;
      node.ref = ref;
      node.css = node.path;
      node.fingerprint = { key: node.kind + '|' + (node.name || '') };
      entries.set(ref, el);
      return node;
    },
  };
  window.eval(assetSource);
  assert.equal(typeof window.__amberZCodeUiTree, 'function');
  return { window, document, helpers };
}

function assertNoPublicFingerprintOrCss(value) {
  if (!value || typeof value !== 'object') return;
  if (Array.isArray(value)) {
    value.forEach(assertNoPublicFingerprintOrCss);
    return;
  }
  assert.equal(Object.prototype.hasOwnProperty.call(value, 'css'), false, 'tree must not duplicate CSS paths');
  assert.equal(Object.prototype.hasOwnProperty.call(value, 'fingerprint'), false, 'tree must not duplicate fingerprints');
  Object.keys(value).forEach((key) => assertNoPublicFingerprintOrCss(value[key]));
}

function main() {
  const { window, document, helpers } = createHarness();
  const first = window.__amberZCodeUiTree({ max_messages: 2, max_text_chars: 100 }, helpers);
  assert.equal(first.ok, true);
  assert.equal(first.snapshot_id, 9);
  assert.equal(first.page_kind, 'task');
  assert.equal(first.task_id, 'task-a');
  assert.deepEqual(Array.from(first.ui_tree.messages.items, (item) => item.row_id), ['5', '6']);
  assert.equal(first.ui_tree.messages.total, 20);
  assert.equal(first.ui_tree.messages.rendered, 6);
  assert.equal(first.ui_tree.messages.truncated, true);
  assert.equal(first.ui_tree.messages.earlier_unavailable, true);
  assert.equal(first.ui_tree.messages.items[1].in_viewport, false);
  assert.equal(first.ui_tree.messages.items[1].text.includes('HIDDEN_FINAL_REASONING'), false);
  assert.equal(first.ui_tree.workspaces.length, 0, 'task pages must not expose history rows as workspaces');
  assert.equal(first.ui_tree.composer.input.ref != null, true);
  assert.equal(first.ui_tree.composer.send.ref != null, true);
  assert.equal(first.ui_tree.composer.routing, 'startNow');
  assert.equal(first.ui_tree.scroll_regions[0].can_scroll_down, true);
  assert.equal(first.ui_tree.scroll_regions[0].total_row_count, 20);
  assertNoPublicFingerprintOrCss(first);

  const finalText = document.querySelector('[data-row-id="6"] span');
  finalText.textContent = 'Final answer streamed update';
  const update = window.__amberZCodeUiTree({
    cursor: first.message_cursor,
    max_messages: 2,
    max_text_chars: 100,
  }, helpers);
  assert.equal(update.ui_tree.messages.cursor_reset, false);
  assert.equal(update.ui_tree.messages.cursor_state, 'changed');
  assert.deepEqual(Array.from(update.ui_tree.messages.items, (item) => item.row_id), ['6']);
  assert.equal(update.ui_tree.messages.items[0].text, 'Final answer streamed update');

  const pane = document.querySelector('[data-testid^="v4-session-pane-"]');
  pane.setAttribute('data-session-id', 'task-b');
  pane.setAttribute('data-projection-seq', '8');
  const switched = window.__amberZCodeUiTree({
    cursor: update.message_cursor,
    max_messages: 2,
  }, helpers);
  assert.equal(switched.ui_tree.messages.cursor_reset, true);
  assert.equal(switched.ui_tree.messages.cursor_reset_reason, 'task_changed');
  assert.deepEqual(Array.from(switched.ui_tree.messages.items, (item) => item.row_id), ['5', '6']);
  assert.equal(switched.message_cursor != null, true);
  assertNoPublicFingerprintOrCss(switched);

  pane.remove();
  document.querySelector('[data-testid="v4-composer"]').remove();
  const home = window.__amberZCodeUiTree({ max_messages: 2 }, helpers);
  assert.equal(home.page_kind, 'home');
  assert.equal(home.ui_tree.workspaces.length, 1);
  assert.equal(home.ui_tree.tasks.length, 2);
  assert.equal(home.ui_tree.tasks[0].task_id, 'task-a');
  assert.equal(home.ui_tree.tasks[0].selected, true);
  assert.equal(home.ui_tree.tasks[0].ref != null, true);
  assert.equal(home.ui_tree.tasks[1].task_id, 'task-b');
  assert.equal(home.ui_tree.tasks[1].selected, false);
  assert.equal(home.ui_tree.workspaces[0].ref != null, true);

  console.log('zcode ui tree regression: PASS');
}

try {
  main();
} catch (error) {
  console.error(error.stack || error);
  process.exitCode = 1;
}
