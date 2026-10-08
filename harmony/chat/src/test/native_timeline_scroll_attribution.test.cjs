// Execute the actual ChatPage callbacks: long native animation must not become a user drag.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const file = process.env.NATIVE_FOLLOW_CHAT_SOURCE || path.resolve(__dirname, '../../../entry/src/main/ets/pages/ChatPage.ets');
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
const didScroll = callback('onTimelineScroll', 'onTimelineScrollStop');
const willScroll = callback('onTimelineWillScroll', 'onTimelineScroll');
function host(running = true) {
  const h = { programmaticScrollPending: false, userTouchActive: false, userScrolls: 0, cancellations: 0,
    nativeFollow: { isRunning: () => running, cancel: () => { h.cancellations++; running = false; } },
    isGenerationActive: () => true, handleUserScroll: () => { h.userScrolls++; }, showScrollNavigation: () => {} };
  return h;
}
test('native motion after250ms attribution expiry is not classified as user scroll', () => {
  const h = host(); didScroll.call(h, 5, ScrollState.Scroll); didScroll.call(h, 5, ScrollState.Fling);
  assert.equal(h.userScrolls, 0);
});
test('an idle callback from retargeting does not turn the next native frame into a user scroll', () => {
  const h = host(); h.programmaticScrollPending = true;
  didScroll.call(h, 0, ScrollState.Idle); didScroll.call(h, 5, ScrollState.Scroll);
  assert.equal(h.userScrolls, 0);
});
test('real drag and fling stop native motion before the user position is reconciled', () => {
  for (const source of [ScrollSource.DRAG, ScrollSource.FLING]) {
    const h = host(); h.programmaticScrollPending = true;
    willScroll.call(h, -5, ScrollState.Scroll, source);
    didScroll.call(h, -5, ScrollState.Scroll);
    assert.equal(h.cancellations, 1); assert.equal(h.userScrolls, 1);
  }
});
test('native animation source does not cancel itself; touch and ordinary user movement still reconcile', () => {
  const h = host(); willScroll.call(h, 5, ScrollState.Scroll, ScrollSource.SCROLLER_ANIMATION);
  assert.equal(h.cancellations, 0);
  h.userTouchActive = true; didScroll.call(h, 5, ScrollState.Scroll); assert.equal(h.userScrolls, 1);
  const idle = host(false); didScroll.call(idle, 5, ScrollState.Scroll); assert.equal(idle.userScrolls, 1);
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
    beginProgrammaticScroll: () => {}, scroller: { scrollEdge: edge => {
      assert.equal(h.cancelled, true); assert.equal(h.atBottomApprox, false); assert.equal(edge, 'top');
    } } };
  exports.run.call(h);
  assert.equal(h.followMode, 'paused');
});
