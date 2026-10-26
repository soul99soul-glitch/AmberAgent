// Execute the actual ChatTimelineView callbacks: long native animation must not become a user drag.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const file = path.resolve(__dirname, '../../../entry/src/main/ets/components/chat/ChatTimelineView.ets');
const source = fs.readFileSync(file, 'utf8');
const ScrollState = { Idle: 0, Scroll: 1, Fling: 2 };
const ScrollSource = { DRAG: 1, FLING: 2, SCROLLER_ANIMATION: 3 };
function callback(name, next) {
  const start = source.indexOf(`${name}: (`);
  assert.ok(start > 0);
  const bodyStart = source.indexOf('=> {', start) + 3;
  const end = source.indexOf(`        ${next}:`, bodyStart);
  const body = source.slice(bodyStart, end).trim().replace(/,$/, '');
  const parameters = source.slice(start + name.length + 2, bodyStart - 3).replace(/:\s*void\s*$/, '');
  const code = ts.transpileModule(`exports.run = function${parameters} ${body}`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  const exports = {};
  vm.runInNewContext(code, { exports, ScrollState, ScrollSource });
  return exports.run;
}
const view = timeline => ({ timeline, previewScrollChanged() {} });
const didScroll = callback('onTimelineScroll', 'onTimelineScrollStop');
const willScroll = callback('onTimelineWillScroll', 'onTimelineScroll');
function host(running = true) {
  const h = { programmaticScrollPending: false, userTouchActive: false, userScrolls: 0, cancellations: 0,
    nativeFollow: { isRunning: () => running, cancel: () => { h.cancellations++; running = false; } },
    cancelPrependRestore: () => {}, beginUserTimelineScroll: () => {}, isGenerationActive: () => true, handleUserScroll: () => { h.userScrolls++; }, showScrollNavigation: () => {} };
  return h;
}
test('native motion after250ms attribution expiry is not classified as user scroll', () => {
  const h = host(); didScroll.call(view(h), 5, ScrollState.Scroll); didScroll.call(view(h), 5, ScrollState.Fling);
  assert.equal(h.userScrolls, 0);
});
test('an idle callback from retargeting does not turn the next native frame into a user scroll', () => {
  const h = host(); h.programmaticScrollPending = true;
  didScroll.call(view(h), 0, ScrollState.Idle); didScroll.call(view(h), 5, ScrollState.Scroll);
  assert.equal(h.userScrolls, 0);
});
test('real drag and fling stop native motion before the user position is reconciled', () => {
  for (const source of [ScrollSource.DRAG, ScrollSource.FLING]) {
    const h = host(); h.programmaticScrollPending = true;
    willScroll.call(view(h), -5, ScrollState.Scroll, source);
    didScroll.call(view(h), -5, ScrollState.Scroll);
    assert.equal(h.cancellations, 1); assert.equal(h.userScrolls, 1);
  }
});
test('native animation source does not cancel itself; touch and ordinary user movement still reconcile', () => {
  const h = host(); willScroll.call(view(h), 5, ScrollState.Scroll, ScrollSource.SCROLLER_ANIMATION);
  assert.equal(h.cancellations, 0);
  h.userTouchActive = true; didScroll.call(view(h), 5, ScrollState.Scroll); assert.equal(h.userScrolls, 1);
  const idle = host(false); didScroll.call(view(idle), 5, ScrollState.Scroll); assert.equal(idle.userScrolls, 1);
});

test('native prepend compensation preserves attribution while a real user drag still takes priority', () => {
  const h = host(false);
  h.programmaticScrollPending = true;
  didScroll.call(view(h), 48, ScrollState.Scroll);
  assert.equal(h.userScrolls, 0);
  h.userTouchActive = true;
  didScroll.call(view(h), -12, ScrollState.Scroll);
  assert.equal(h.userScrolls, 1);
});

test('jump to top pauses and releases native follow before changing position', () => {
  const label = source.indexOf(".accessibilityText('回到消息顶部')");
  assert.ok(label > 0);
  const start = source.lastIndexOf('.onClick((): void => {', label);
  const body = source.slice(start + '.onClick((): void => {'.length, label).trim().replace(/}\)$/, '');
  const code = ts.transpileModule(`exports.run = function() {${body}}`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  const exports = {}; vm.runInNewContext(code, { exports, Edge: { Top: 'top' } });
  const h = { followMode: 'following', atBottomApprox: true, cancelled: false,
    nativeFollow: { cancel: () => { h.cancelled = true; assert.equal(h.followMode, 'paused'); } },
    cancelPrependRestore: () => {}, beginProgrammaticScroll: () => {}, scroller: { scrollEdge: edge => {
      assert.equal(h.cancelled, true); assert.equal(h.atBottomApprox, false); assert.equal(edge, 'top');
    } } };
  exports.run.call(view(h));
  assert.equal(h.followMode, 'paused');
});

test('scroll navigation extends its hide delay without changing observed state on every frame', () => {
  const timers = new Map();
  let nextTimer = 0;
  const exports = {};
  const timelineFile = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl/chat/ChatTimelineSession.ets');
  const code = ts.transpileModule(fs.readFileSync(timelineFile, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS,
      experimentalDecorators: true },
  }).outputText;
  vm.runInNewContext(code, {
    exports, Observed: target => target, Scroller: class {},
    require: name => name === '@kit.ArkUI' ? { KeyboardAvoidMode: { OFFSET: 0 } }
      : name === '@amber/chat-domain' ? { createInitialTimelineLoadState: () => ({}) } : {},
    setTimeout: (callback, delay) => {
      assert.equal(delay, 1200);
      const id = ++nextTimer;
      timers.set(id, callback);
      return id;
    },
    clearTimeout: id => timers.delete(id),
  }, { filename: timelineFile });
  const changes = [];
  // V1 @Observed invalidates subscribers when a first-level property changes.
  // The device trace verifies the resulting message-row update cost.
  const timeline = new Proxy(new exports.ChatTimelineSession(), {
    set(target, property, value) {
      if (target[property] !== value) changes.push(property);
      target[property] = value;
      return true;
    },
  });
  for (let frame = 0; frame < 60; frame++) timeline.showScrollNavigation();
  assert.deepEqual(changes, ['scrollNavigationVisible']);
  assert.equal(timers.size, 1, 'only the latest scroll frame owns a hide callback');
  const hide = timers.values().next().value;
  timers.clear();
  hide();
  assert.equal(timeline.scrollNavigationVisible, false);
  timeline.showScrollNavigation();
  timeline.hideScrollNavigation();
  assert.equal(timers.size, 0, 'leaving the page cancels the pending hide');
  assert.equal(timeline.scrollNavigationVisible, false);
});
