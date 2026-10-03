const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const { observeDeepReadLibrary, queryDeepReadLibraryRows } = require('../../../deepread/src/main/ets/domain/library.ts');
const { makeEmptyDeepReadOutput } = require('../../../deepread/src/main/ets/domain/models.ts');
const { STAGE_ORDER } = require('../../../deepread/src/main/ets/domain/enums.ts');
const { isCacheEntryExpired } = require('../../../deepread/src/main/ets/platform/deep_read_progress.ts');
const source = fs.readFileSync(path.join(__dirname, '../main/ets/pages/DeepReadHistoryPage.ets'), 'utf8');
function method(name) {
  const match = new RegExp('^  (?:private )?(?:async )?' + name + '\\(', 'm').exec(source);
  assert.ok(match, name);
  let end = source.indexOf('{', match.index) + 1, depth = 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(match.index, end);
}
const names = ['aboutToAppear', 'onPageShow', 'onPageHide', 'aboutToDisappear', 'leaveVisible', 'stopObservation',
  'startObservation', 'attachObservation', 'connectScheduler', 'reloadHistory', 'loadHistorySnapshot', 'visibleItems',
  'reopen', 'rowKey', 'createArticle'];
const globals = source.slice(source.indexOf('function historyStatusLabel'), source.indexOf('@Entry'));
const code = ts.transpileModule(globals + '\nclass Page {\n' + names.map(method).join('\n') + '\n}\nreturn Page;',
  { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
const tick = async () => { for (let i = 0; i < 15; i++) await Promise.resolve(); };
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; }
function stream(initial) {
  let value = initial;
  const listeners = new Set(), callbacks = [];
  let subscriptions = 0, releases = 0;
  return {
    subscribe(callback) { listeners.add(callback); callbacks.push(callback); subscriptions++; callback(value);
      return () => { if (listeners.delete(callback)) releases++; }; },
    getCurrent: () => value,
    emit(next) { value = next; for (const callback of listeners) callback(value); },
    listeners, callbacks, get subscriptions() { return subscriptions; }, get releases() { return releases; },
  };
}
function entry(id = 'saved', title = '阅读文章', complete = false) {
  const output = makeEmptyDeepReadOutput();
  output.generationComplete = complete;
  if (complete) for (const stage of STAGE_ORDER) output.sectionStates[stage] = { status: 'READY', errorMessage: null, quality: 'STANDARD' };
  return { topicId: id, title, output, sourceUrl: null, phase: complete ? 'COMPLETE' : 'IDLE', attemptCount: 0,
    lastError: null, createdAt: 100, updatedAt: 100, expiresAt: Date.now() + 86400000 };
}
function fixture(initial = [], options = {}) {
  const history = stream(initial), runs = stream([]), routes = [], keyboard = [];
  const scheduler = { getActiveRuns: () => runs.getCurrent(), observeActiveRuns: () => runs };
  const repository = { observeHistory: limit => { assert.equal(limit, 0); return history; },
    listHistory: limit => { assert.equal(limit, 0); return options.read ? options.read() : Promise.resolve(history.getCurrent().slice()); } };
  const Page = new Function('getRepository', 'getDeepReadScheduler', 'observeDeepReadLibrary', 'queryDeepReadLibraryRows',
    'isCacheEntryExpired', 'router', 'KeyboardAvoidMode', code)(() => repository,
    options.scheduler ? options.scheduler : async () => scheduler, observeDeepReadLibrary, queryDeepReadLibraryRows, isCacheEntryExpired,
    { pushUrl: async route => { routes.push(route); } }, { OFFSET: 'offset', RESIZE: 'resize' });
  const page = new Page(); Object.assign(page, { pageAlive: false, pageVisible: false, observationToken: 0,
    loadToken: 0, unsubscribeLibrary: null, entries: [], activeRuns: [], query: '', filter: 'all', loaded: false,
    loadError: '', runtimeError: '', keyboardAdjusted: false, previousKeyboardAvoidMode: 'offset',
    getUIContext: () => ({ getKeyboardAvoidMode: () => 'offset', setKeyboardAvoidMode: mode => keyboard.push(mode) }) });
  return { page, history, runs, routes, keyboard, scheduler, repository };
}

test('actual aggregate subscription adds a new admitted task before its first cache and updates completion live', async () => {
  const f = fixture([entry()]); f.page.aboutToAppear(); f.page.onPageShow(); await tick();
  assert.equal(f.history.listeners.size, 1); assert.equal(f.runs.listeners.size, 1);
  f.runs.emit([{ topicId: 'new', title: '新主题', startedAt: 200 }]);
  assert.equal(f.page.visibleItems()[0].id, 'new'); assert.equal(f.page.visibleItems()[0].cached, false);
  assert.equal(f.page.visibleItems()[0].status, '生成中');
  const completed = entry('new', '新主题', true); completed.updatedAt = 300;
  f.history.emit([completed, entry()]); assert.equal(f.page.visibleItems()[0].status, '生成中');
  f.runs.emit([]); assert.equal(f.page.visibleItems()[0].status, '完成'); assert.equal(f.page.visibleItems()[0].cached, true);
});

test('hide releases both observers and ignores queued callbacks; return resubscribes with the current library', async () => {
  const f = fixture([entry()]); f.page.aboutToAppear(); f.page.onPageShow(); await tick();
  const oldHistory = f.history.callbacks.at(-1), oldRuns = f.runs.callbacks.at(-1);
  f.page.query = '保留搜索'; f.page.filter = 'complete'; f.page.onPageHide();
  assert.equal(f.history.listeners.size, 0); assert.equal(f.runs.listeners.size, 0);
  oldHistory([entry('late')]); oldRuns([{ topicId: 'late', title: '迟到', startedAt: 500 }]);
  assert.equal(f.page.entries[0].topicId, 'saved'); assert.deepEqual(f.page.activeRuns, []);
  f.history.emit([entry('returned', '保留搜索', true)]); f.page.onPageShow(); await tick();
  assert.equal(f.history.listeners.size, 1); assert.equal(f.runs.listeners.size, 1);
  assert.equal(f.page.visibleItems()[0].id, 'returned'); assert.equal(f.page.query, '保留搜索'); assert.equal(f.page.filter, 'complete');
  assert.deepEqual(f.keyboard, ['resize', 'offset', 'resize']);
  f.page.aboutToDisappear(); assert.equal(f.history.listeners.size, 0); assert.equal(f.runs.listeners.size, 0);
  assert.equal(f.keyboard.at(-1), 'offset');
});

test('scheduler initialization resolving after hide cannot register a late aggregate observer', async () => {
  const pending = deferred(); const f = fixture([entry()], { scheduler: () => pending.promise });
  f.page.aboutToAppear(); f.page.onPageShow(); await tick();
  assert.equal(f.page.entries.length, 1); assert.equal(f.history.listeners.size, 1);
  f.page.onPageHide(); pending.resolve(f.scheduler); await tick();
  assert.equal(f.history.listeners.size, 0); assert.equal(f.runs.listeners.size, 0);
});

test('unavailable model/runtime leaves the saved library readable and its repository subscription live', async () => {
  const f = fixture([entry()], { scheduler: async () => { throw Error('未配置可用模型'); } });
  f.page.aboutToAppear(); f.page.onPageShow(); await tick();
  assert.equal(f.page.loaded, true); assert.equal(f.page.visibleItems()[0].id, 'saved');
  assert.match(f.page.runtimeError, /未配置/); assert.equal(f.page.loadError, ''); assert.equal(f.history.listeners.size, 1);
  f.history.emit([entry('later', '之后保存')]); assert.equal(f.page.visibleItems()[0].id, 'later');
});

test('a newer history event wins over an older manual snapshot; hidden initial read never writes', async () => {
  const read = deferred(); const f = fixture([entry()], { read: () => read.promise });
  f.page.aboutToAppear(); f.page.onPageShow(); await tick();
  f.history.emit([entry('fresh')]); read.resolve([entry('stale')]); await tick();
  assert.equal(f.page.entries[0].topicId, 'fresh');
  const pending = deferred(); const hidden = fixture([], { read: () => pending.promise });
  hidden.page.aboutToAppear(); hidden.page.onPageShow(); hidden.page.onPageHide();
  pending.resolve([entry('late')]); await tick(); assert.deepEqual(hidden.page.entries, []);
});

test('title/body search and status filtering use production library queries; row version follows same-ID content', async () => {
  const saved = entry(); saved.output.summary = '正文检索词';
  const f = fixture([saved]); f.page.aboutToAppear(); f.page.onPageShow(); await tick();
  f.page.query = '正文检索词'; assert.equal(f.page.visibleItems().length, 1);
  const firstKey = f.page.rowKey(f.page.visibleItems()[0]);
  f.history.emit([{ ...saved, title: '新标题', updatedAt: 200 }]);
  assert.notEqual(f.page.rowKey(f.page.visibleItems()[0]), firstKey);
  f.runs.emit([{ topicId: 'saved', title: '新标题', startedAt: 300 }]); f.page.filter = 'running';
  assert.equal(f.page.visibleItems()[0].status, '生成中');
  f.page.filter = 'complete'; assert.equal(f.page.visibleItems().length, 0);
});

test('repository read failures remain visible without blocking creation and reopening routes', async () => {
  const f = fixture([], { read: async () => { throw Error('读取失败'); } });
  f.page.aboutToAppear(); f.page.onPageShow(); await tick();
  f.page.reloadHistory(); await tick();
  assert.match(f.page.loadError, /读取失败/);
  f.page.createArticle(); f.page.reopen({ id: 'topic', title: '标题' }); await tick();
  assert.deepEqual(f.routes.map(route => route.url), ['pages/DeepReadComposePage', 'pages/DeepReadArticlePage']);
  assert.equal(f.routes[1].params.topicId, 'topic');
  assert.match(source, /minHeight: 44/); assert.doesNotMatch(source, /setTimeout|setInterval|observeRunning\(/);
});

test('manual refresh failure stays visible when the only library row is an active task without a cache', async () => {
  const f = fixture([], { read: async () => { throw Error('历史读取失败'); } });
  f.page.aboutToAppear(); f.page.onPageShow(); await tick();
  f.runs.emit([{ topicId: 'active-only', title: '尚未保存正文', startedAt: 500 }]);
  f.page.reloadHistory(); await tick();
  assert.equal(f.page.entries.length, 0); assert.equal(f.page.visibleItems().length, 1);
  assert.match(f.page.loadError, /历史读取失败/);
  assert.match(source, /this\.loadError\.length > 0 && this\.visibleItems\(\)\.length > 0/);
});
