// Exercise the production timeline/presentation owners and ChatPage's actual wiring.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const domain = require('../../../chat/src/main/ets/index.ts');
const base = path.resolve(__dirname, '../main/ets');
const read = file => fs.readFileSync(path.join(base, file), 'utf8');
const compile = source => ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
}).outputText;
function owner(file, dependencies, globals = {}) {
  const exports = {};
  new Function('exports', 'require', 'Observed', ...Object.keys(globals), compile(read(file)))(
    exports, name => {
      if (name === '@amber/chat-domain') return domain;
      assert.ok(Object.hasOwn(dependencies, name), 'Unexpected dependency ' + name);
      return dependencies[name];
    }, value => value, ...Object.values(globals));
  return exports;
}
const state = owner('components/MessageRowViewState.ets', {});
const source = owner('platform_impl/chat/ChatTimelineDataSource.ets', {
  '../../components/MessageRowViewState.ets': state,
});
function callback(page, name, next) {
  const source = read('pages/ChatPage.ets');
  const start = source.indexOf('      ' + name + ':', source.indexOf('const presentationPorts:'));
  const end = source.indexOf('\n      ' + next + ':', start);
  const expression = source.slice(start + ('      ' + name + ':').length, end).trim().replace(/,$/, '');
  return new Function('buildRowsFromNodes', 'TIMELINE_PAGE_SIZE', 'applyTailWindow',
    compile('return (' + expression + ');')).call(page,
    (nodes, previous) => domain.buildChatTimelineRows(nodes, previous), 50, domain.applyTailWindow);
}
const message = (id, text, role = 'assistant') => ({ id, role, parts: [{ type: 'text', text, metadata: null }] });
const node = (id, item) => ({ id, messages: [item], selectIndex: 0 });
const conversation = (nodes, id = 'conversation') => ({ id, title: 'test', messageNodes: nodes, chatSuggestions: [] });
const settle = async () => { for (let count = 0; count < 8; count++) await Promise.resolve(); };
function fixture(repository = {}) {
  const timers = new Map(), calls = [], visibleRects = new Map(), layoutCallbacks = new Set(), measured = [];
  const observer = {
    on(type, callback) { assert.equal(type, 'didLayout'); layoutCallbacks.add(callback); },
    off(type, callback) { assert.equal(type, 'didLayout'); layoutCallbacks.delete(callback); },
  };
  const layout = () => { for (const callback of [...layoutCallbacks]) callback(); };
  let nextTimer = 1;
  class Scroller {
    scrollToIndex(...args) {
      calls.push(['index', ...args]);
      if (args[3]) {
        visibleRects.clear();
        visibleRects.set(args[0], { y: -args[3].extraOffset.value, height: 80 });
      }
    }
    scrollEdge(...args) { calls.push(['edge', ...args]); }
    scrollBy() { assert.fail('native prepend must not add a second manual compensation'); }
    getItemRect(index) {
      measured.push(index);
      assert.ok(visibleRects.has(index), 'getItemRect must only read an actually visible item');
      return visibleRects.get(index);
    }
  }
  const timelineModule = owner('platform_impl/chat/ChatTimelineSession.ets', {
    '@kit.ArkUI': { KeyboardAvoidMode: { OFFSET: 0 }, LengthMetrics: { vp: value => ({ value }) } }, '@kit.IMEKit': {},
    '@kit.PerformanceAnalysisKit': { hilog: { warn() {} } },
    '../../components/NativeTimelineFollow.ets': { NativeTimelineFollow: class { request() {} cancel() {} } },
    './ChatTimelineDataSource.ets': source,
  }, { Scroller, ScrollAlign: { START: 'start' }, Edge: { Bottom: 'bottom' },
    setTimeout: (fn, delay) => { const id = nextTimer++; timers.set(id, { fn, delay }); return id; },
    clearTimeout: id => timers.delete(id) });
  const presentationModule = owner('platform_impl/chat/ChatConversationPresentation.ets', {
    '@kit.ArkUI': { promptAction: {} }, '@kit.IMEKit': {}, '@kit.SensorServiceKit': {},
    '@kit.PerformanceAnalysisKit': {}, '../ConversationRunGuard.ets': {},
    '../../components/StreamingFrameRate.ets': { StreamingFrameRate: class { update() {} } },
    './ChatCompactionSupport.ets': {},
  });
  const timeline = new timelineModule.ChatTimelineSession();
  const presentation = new presentationModule.ChatConversationPresentation();
  const compactCounts = new Map();
  const page = { timeline, presentation, chatHeaderHeight: 0, statusBarHeight: 0, activeArtifactInteraction: '',
    modelContext: { compactBoundaryCount: id => compactCounts.get(id) ?? 0 } };
  const pageSource = read('pages/ChatPage.ets');
  const start = pageSource.indexOf('const timelinePorts:');
  const objectStart = pageSource.indexOf('{', start);
  const objectEnd = pageSource.indexOf('\n    };', objectStart) + '\n    }'.length;
  const ports = new Function(compile('return (' + pageSource.slice(objectStart, objectEnd) + ');')).call(page);
  timeline.start({ getUIObserver: () => observer }, repository, ports);
  presentation.start({
    refreshModel() {}, refreshOcr() {}, refreshPendingRows() {}, publishRunDetails() {},
    publishModelContext() {}, reconcileSmartUi() {},
    timelineState: () => timeline.timelineLoadState,
    clearTimeline: callback(page, 'clearTimeline', 'syncTimeline'),
    syncTimeline: callback(page, 'syncTimeline', 'projectConversation'),
    projectConversation: callback(page, 'projectConversation', 'resetTimeline'),
    followStreamingUpdate() {}, handleGenerateEnd() {}, settleScroll() {},
  });
  return { timeline, presentation, compactCounts, calls, timers, visibleRects, layoutCallbacks, layout, measured };
}
const snapshot = (conv, tail, extra = {}) => ({ runId: 'run', sending: true,
  conversation: conv, visualConversation: conv, streamingTail: tail, regenTail: null,
  regenNodeId: '', pendingQueue: [], errorMsg: '', completionNotice: null, phase: 'running',
  conversationDeleted: false, ...extra });

test('a complete run snapshot replaces live tail with history in one synchronization', async () => {
  const f = fixture();
  const prompt = node('user-node', message('user', 'prompt', 'user'));
  const initial = conversation([prompt]);
  f.presentation.applyConversationRunSnapshot(snapshot(initial, [message('answer', 'partial')]), false);
  const source = f.timeline.dataSource, active = source.getData(1);
  const events = [];
  source.registerDataChangeListener({ onDataAdd: i => events.push(['add', i]),
    onDataDelete: i => events.push(['delete', i]) });
  const final = conversation([prompt, node('answer-node', message('answer', 'final'))]);
  f.presentation.applyConversationRunSnapshot(snapshot(final, [], { sending: false, phase: 'completed' }), false);
  await settle();
  assert.strictEqual(source.getData(1), active);
  assert.equal(active.row.nodeId, 'answer-node');
  assert.equal(active.message.parts[0].text, 'final');
  assert.equal(active.streaming, false);
  assert.deepEqual(events, [], 'no intermediate live removal or completion remount');
});

test('loader and compact mapping stays correct before and after the final prepend', async () => {
  const older = node('old-node', message('old', 'older'));
  const recent = node('recent-node', message('recent', 'recent'));
  const f = fixture({ getConversationNodePage: async () => ({ nodes: [older], oldestLoadedIndex: 0 }) });
  f.presentation.conversation = conversation([older, recent]);
  f.compactCounts.set('old', 1);
  f.compactCounts.set('recent', 1);
  f.timeline.timelineLoadState = domain.applyTailWindow(f.timeline.timelineLoadState, 1, 1, 2);
  f.timeline.setRows(domain.buildChatTimelineRows([recent]));
  await settle();
  const retained = f.timeline.dataSource.getData(0);
  assert.equal(f.timeline.listIndexForRow(0), 1, 'loader precedes the first message');
  assert.equal(f.timeline.activeListItemCount(), 3, 'loader plus message plus compact');
  f.timeline.loadOlderTimeline();
  await settle();
  assert.equal(f.timeline.timelineLoadState.fullyLoaded, true);
  assert.strictEqual(f.timeline.dataSource.getData(2), retained);
  assert.equal(f.timeline.listIndexForRow(0), 0, 'fully loaded history has no loader offset');
  assert.equal(f.timeline.listIndexForRow(1), 2, 'the preceding compact occupies one native item');
  assert.equal(f.timeline.activeListItemCount(), 4);
  assert.equal(f.timeline.prefetching, false, 'no native row was mounted, so there is no scroll to restore');
  assert.deepEqual(f.calls, []);
  f.timeline.initialScrollTarget = 1;
  f.timeline.performInitialScroll();
  assert.deepEqual(f.calls, [['index', 2, false, 'start']]);
});

test('history positioning initializes the source and conversation changes reset per-message state', async () => {
  const first = node('first-node', message('first', 'first'));
  const second = node('second-node', message('second', 'second'));
  const f = fixture();
  f.presentation.conversation = conversation([first, second]);
  f.compactCounts.set('first', 1);
  f.timeline.timelineLoadState = domain.applyTailWindow(f.timeline.timelineLoadState, 1, 1, 2);
  f.timeline.setRows(domain.buildChatTimelineRows([second]));
  await settle();
  const state = f.timeline.dataSource.getData(0).viewState;
  f.timeline.positionToTargetNode('first-node', 0);
  f.timeline.performInitialScroll();
  assert.deepEqual(f.calls, [['index', 0, false, 'start']]);
  assert.equal(f.timeline.dataSource.totalCount(), 3);
  assert.strictEqual(f.timeline.dataSource.getData(2).viewState, state);
  f.presentation.conversation = conversation([second], 'another');
  f.timeline.setRows(domain.buildChatTimelineRows([second]));
  await settle();
  assert.notStrictEqual(f.timeline.dataSource.getData(0).viewState, state);
});


test('prepend seeks the visible message with its exact local y after layout, including loader removal', async () => {
  let requests = 0;
  const older = node('old-node', message('old', 'older'));
  const recent = node('recent-node', message('recent', 'recent'));
  const f = fixture({ getConversationNodePage: async () => {
    requests++;
    return { nodes: [older], oldestLoadedIndex: 0 };
  } });
  f.presentation.conversation = conversation([older, recent]);
  f.compactCounts.set('old', 1);
  f.timeline.timelineLoadState = domain.applyTailWindow(f.timeline.timelineLoadState, 1, 1, 2);
  f.timeline.setRows(domain.buildChatTimelineRows([recent]));
  await settle();
  f.timeline.listReady = true;
  f.timeline.firstVisibleListIndex = 0; // Loader is visible; the message is the anchor.
  f.timeline.lastVisibleListIndex = 1;
  f.visibleRects.set(1, { y: 48, height: 80 });
  f.timeline.loadOlderTimeline();
  await settle();
  assert.deepEqual(f.measured, [1], 'record only the old visible message before mutation');
  assert.equal(f.timeline.prefetching, true);
  assert.deepEqual(f.calls, [], 'seek waits for the native data and loader layout');
  f.timeline.loadOlderTimeline();
  assert.equal(requests, 1, 'the insertion ReachStart cannot load another page');
  f.layout();
  assert.deepEqual(f.calls, [['index', 2, false, 'start', { extraOffset: { value: -48 } }]]);
  assert.deepEqual(f.measured, [1], 'the old offscreen anchor is never measured after insertion');
  assert.equal(f.visibleRects.get(2).y, 48, 'the same message has its original List-local y');
  assert.equal(f.timeline.prefetching, true, 'seek layout remains part of this single prepend');
  f.layout();
  assert.equal(f.timeline.prefetching, false);
  assert.equal(f.layoutCallbacks.size, 0);
});

test('a page returned during a drag waits for that gesture and captures its latest visible position', async () => {
  let resolvePage;
  const older = node('old-node', message('old', 'older'));
  const recent = node('recent-node', message('recent', 'recent'));
  const f = fixture({ getConversationNodePage: () => new Promise(resolve => { resolvePage = resolve; }) });
  f.presentation.conversation = conversation([older, recent]);
  f.timeline.timelineLoadState = domain.applyTailWindow(f.timeline.timelineLoadState, 1, 1, 2);
  f.timeline.setRows(domain.buildChatTimelineRows([recent]));
  await settle();
  f.timeline.listReady = true;
  f.timeline.firstVisibleListIndex = 1;
  f.timeline.lastVisibleListIndex = 1;
  f.visibleRects.set(1, { y: 48, height: 80 });
  f.timeline.userTouchActive = true;
  f.timeline.beginUserTimelineScroll();
  f.timeline.loadOlderTimeline();
  resolvePage({ nodes: [older], oldestLoadedIndex: 0 });
  await settle();
  assert.equal(f.timeline.rows.length, 1, 'do not mutate the list underneath the current drag');
  f.timeline.userTouchActive = false;
  f.timeline.finishTimelineTouch();
  assert.equal(f.timeline.rows.length, 1, 'touch release can still be followed by a fling');
  f.visibleRects.set(1, { y: -12, height: 80 });
  f.timeline.finishUserTimelineScroll();
  assert.equal(f.timeline.rows.length, 2);
  f.layout();
  assert.deepEqual(f.calls, [['index', 1, false, 'start', { extraOffset: { value: 12 } }]]);
  assert.equal(f.visibleRects.get(1).y, -12);
  f.layout();
  assert.equal(f.timeline.prefetching, false);
});

test('new input, explicit positioning, hiding and switching conversation remove pending layout callbacks', async () => {
  for (const cancel of ['input', 'position', 'hide', 'conversation', 'bufferedConversation']) {
    const older = node('old-node', message('old', 'older'));
    const recent = node('recent-node', message('recent', 'recent'));
    const f = fixture({ getConversationNodePage: async () => ({ nodes: [older], oldestLoadedIndex: 0 }) });
    f.presentation.conversation = conversation([older, recent]);
    f.timeline.timelineLoadState = domain.applyTailWindow(f.timeline.timelineLoadState, 1, 1, 2);
    f.timeline.setRows(domain.buildChatTimelineRows([recent]));
    await settle();
    f.timeline.listReady = true;
    f.timeline.firstVisibleListIndex = 1;
    f.timeline.lastVisibleListIndex = 1;
    f.visibleRects.set(1, { y: 24, height: 80 });
    if (cancel === 'bufferedConversation') f.timeline.userTouchActive = true;
    f.timeline.loadOlderTimeline();
    await settle();
    assert.equal(f.layoutCallbacks.size, cancel === 'bufferedConversation' ? 0 : 1);
    if (cancel === 'input') f.timeline.cancelPrependRestore();
    if (cancel === 'position') f.timeline.applyInitialScroll(0);
    if (cancel === 'hide') f.timeline.hide();
    if (cancel === 'conversation' || cancel === 'bufferedConversation') {
      f.presentation.conversation = conversation([recent], 'other');
      f.timeline.setRows(domain.buildChatTimelineRows([recent]));
      await settle();
    }
    f.layout();
    assert.equal(f.layoutCallbacks.size, 0, cancel);
    assert.equal(f.timeline.prefetching, false, cancel);
    assert.deepEqual(f.calls, [], 'cancelled restore cannot seek after ' + cancel);
  }
});
