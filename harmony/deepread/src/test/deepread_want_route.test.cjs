const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const entryFile = path.resolve(__dirname, '../../../entry/src/main/ets/entryability/EntryAbility.ets');
const pageFile = path.resolve(__dirname, '../../../entry/src/main/ets/pages/ChatListPage.ets');
const entrySource = fs.readFileSync(entryFile, 'utf8');
const pageSource = fs.readFileSync(pageFile, 'utf8');
const transpile = source => ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
function block(source, start) {
  assert.ok(start >= 0);
  const open = source.indexOf('{', start);
  let depth = 1, end = open + 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(start, end);
}
const pageMethod = name => block(pageSource,
  pageSource.search(new RegExp('^  (?:private )?' + name + '\\(', 'm')));
const settle = () => new Promise(setImmediate);
const plain = value => JSON.parse(JSON.stringify(value));

function harness(push = () => Promise.resolve()) {
  const storage = new Map();
  const pushes = [], globalPushes = [];
  const pageRouter = { pushUrl: options => { pushes.push(plain(options)); return push(options); } };
  const exports = {};
  const imports = {
    '../platform_impl/ProductIdentity.ets': { getProductKind: () => 'agent', productRootPage: () => 'pages/ChatListPage' },
    '../platform_impl/DeepReadMotionPreferences.ets': { DeepReadMotionPreferences: class {
      start() {} refresh() {} stop() {}
    } },
    '@kit.AbilityKit': { UIAbility: class {} },
    '@kit.ArkUI': { router: { pushUrl: options => {
      globalPushes.push(plain(options)); return Promise.resolve();
    } } },
    '@kit.PerformanceAnalysisKit': { hilog: { info() {}, warn() {}, error() {} } },
    '@kit.NotificationKit': { notificationManager: { requestEnableNotification: async () => {} } },
    '../design/fonts.ets': { registerAmberFontsFor() {} },
    '../platform_impl/MacGatewayClient.ets': { syncMacGatewayPushTokenOnLaunch() {} },
  };
  // Execute the actual loadContent success callback, independently of the root page event.
  const start = block(entrySource, entrySource.indexOf('    const start: () => void'));
  vm.runInNewContext(transpile(entrySource +
    `\nexports.loadRootForTest = function(windowStage) { ${start}; start(); };`), {
    exports, require: name => imports[name] || {}, Promise, Error,
    AppStorage: { get: key => storage.get(key), setOrCreate: (key, value) => storage.set(key, value) },
  }, { filename: entryFile });
  const module = { exports: {} };
  vm.runInNewContext(transpile(`module.exports = class {
    pageAlive = true; firstShowDone = false;
    observeRunning() {} refresh() {} loadContinue() {} loadLastOpened() {}
    getUIContext() { return { getRouter: () => pageRouter }; }
    ${pageMethod('onPageShow')}
    ${pageMethod('consumeDeepLink')}
  };`), {
    module, pageRouter, Promise,
    loadDisplaySetting: async () => ({ userNickname: '', userAvatar: '' }),
    getChatKvStore: () => ({}),
    markWantRoutingReady: exports.markWantRoutingReady,
    routePendingWant: exports.routePendingWant,
  }, { filename: pageFile });
  const entry = new exports.default();
  return {
    storage, pushes, globalPushes, page: new module.exports(),
    receive: parameters => entry.routeFromWant({ parameters }),
    destroyWindow: () => entry.onWindowStageDestroy(),
    loadRoot: () => exports.loadRootForTest.call(entry, {
      loadContent: (_url, callback) => callback({ code: 0 }),
      getMainWindowSync: () => ({ getUIContext: () => ({ getFont: () => ({}) }) }),
    }),
  };
}

test('cold notification stays pending after loadContent success until the rendered root page shows', async () => {
  const h = harness();
  h.receive({ topicId: 'gpt-topic', title: 'GPT article' });
  h.loadRoot(); await settle();
  assert.deepEqual(h.pushes, []);
  assert.deepEqual(h.globalPushes, []);
  assert.equal(h.storage.get('wantTopicId'), 'gpt-topic');
  h.page.onPageShow(); await settle();
  assert.deepEqual(h.pushes, [{ url: 'pages/DeepReadArticlePage',
    params: { topicId: 'gpt-topic', title: 'GPT article' } }]);
  assert.deepEqual(h.globalPushes, []);
  assert.equal(h.storage.get('wantTopicId'), '');
});

test('hot notification uses the root UIContext router immediately from another stack top', async () => {
  const h = harness();
  h.page.onPageShow(); await settle();
  h.receive({ topicId: 'infoq-topic', title: 'InfoQ' }); await settle();
  assert.equal(h.pushes.length, 1);
  assert.equal(h.pushes[0].params.topicId, 'infoq-topic');
  assert.deepEqual(h.globalPushes, []);
});

test('Novel notification retains its existing complete target and chapters tab', async () => {
  const h = harness();
  h.receive({ novelProjectId: 'project', novelBranchId: 'branch', novelJobId: 'job' });
  h.loadRoot(); await settle();
  assert.deepEqual(h.globalPushes, []);
  h.page.onPageShow(); await settle();
  assert.deepEqual(h.pushes, [{ url: 'pages/NovelWorkspacePage',
    params: { projectId: 'project', branchId: 'branch', jobId: 'job', tab: 'chapters' } }]);
});

test('cold Chat activity opens its conversation only after the root page shows', async () => {
  const h = harness();
  h.receive({ conversationId: '  conversation-cold  ' });
  h.loadRoot(); await settle();
  assert.deepEqual(h.pushes, []);
  assert.equal(h.storage.get('wantConversationId'), 'conversation-cold');
  h.page.onPageShow(); await settle();
  assert.deepEqual(h.pushes, [{ url: 'pages/ChatPage', params: { conversationId: 'conversation-cold' } }]);
  assert.equal(h.storage.get('wantConversationId'), '');
});

test('hot Chat activity opens its conversation immediately from another stack top', async () => {
  const h = harness();
  h.page.onPageShow(); await settle();
  h.receive({ conversationId: 'conversation-hot' }); await settle();
  assert.deepEqual(h.pushes, [{ url: 'pages/ChatPage', params: { conversationId: 'conversation-hot' } }]);
  assert.deepEqual(h.globalPushes, []);
});

test('Novel workspace activity opens the project current branch without job parameters', async () => {
  const h = harness();
  h.receive({ novelWorkspaceProjectId: 'project-current' });
  h.page.onPageShow(); await settle();
  assert.deepEqual(h.pushes, [{ url: 'pages/NovelWorkspacePage', params: { projectId: 'project-current' } }]);
  assert.equal(h.storage.get('wantNovelWorkspaceProjectId'), '');
});

test('an incomplete legacy Novel target still invalidates a pending route', async () => {
  for (const parameters of [{ novelProjectId: 'legacy-project' },
    { novelProjectId: 'legacy-project', novelBranchId: 'branch' },
    { novelProjectId: 'legacy-project', novelJobId: 'job' }]) {
    const h = harness();
    h.receive({ conversationId: 'conversation' });
    h.receive(parameters);
    h.page.onPageShow(); await settle();
    assert.deepEqual(h.pushes, []);
    assert.equal(h.storage.get('wantRouteSeq'), 0);
    assert.equal(h.storage.get('wantConversationId'), '');
  }
});

test('an activation without a stable target does not push a content page', async () => {
  const h = harness();
  h.receive({});
  h.page.onPageShow(); await settle();
  assert.deepEqual(h.pushes, []);
  h.receive({ keepAlive: true }); await settle();
  assert.deepEqual(h.pushes, []);
});

test('an in-flight Chat route releases a newer Novel workspace target after success', async () => {
  let finishFirst;
  let count = 0;
  const h = harness(() => ++count === 1 ? new Promise(resolve => { finishFirst = resolve; }) : Promise.resolve());
  h.page.onPageShow(); await settle();
  h.receive({ conversationId: 'first-conversation' });
  h.receive({ novelWorkspaceProjectId: 'newer-project' });
  assert.equal(h.storage.get('wantConversationId'), '');
  assert.equal(h.storage.get('wantNovelWorkspaceProjectId'), 'newer-project');
  finishFirst(); await settle();
  assert.deepEqual(h.pushes, [
    { url: 'pages/ChatPage', params: { conversationId: 'first-conversation' } },
    { url: 'pages/NovelWorkspacePage', params: { projectId: 'newer-project' } },
  ]);
  assert.equal(h.storage.get('wantNovelWorkspaceProjectId'), '');
});

test('switching activity targets clears fields from every previous target', async () => {
  const h = harness();
  h.receive({ novelProjectId: 'project', novelBranchId: 'branch', novelJobId: 'job' });
  h.receive({ conversationId: 'conversation' });
  assert.equal(h.storage.get('wantNovelProjectId'), '');
  assert.equal(h.storage.get('wantNovelBranchId'), '');
  assert.equal(h.storage.get('wantNovelJobId'), '');
  h.receive({ novelWorkspaceProjectId: 'current-project' });
  assert.equal(h.storage.get('wantConversationId'), '');
  h.receive({ topicId: 'topic', title: 'Article' });
  assert.equal(h.storage.get('wantNovelWorkspaceProjectId'), '');
  h.receive({ conversationId: 'final-conversation' });
  assert.equal(h.storage.get('wantTopicId'), '');
  assert.equal(h.storage.get('wantTitle'), '');
  h.page.onPageShow(); await settle();
  assert.deepEqual(h.pushes, [{ url: 'pages/ChatPage', params: { conversationId: 'final-conversation' } }]);
});

test('activity route parameters use the existing string length validation', async () => {
  for (const parameters of [{ conversationId: 'x'.repeat(513) }, { conversationId: 42 },
    { novelWorkspaceProjectId: 'x'.repeat(513) }, { novelWorkspaceProjectId: {} }]) {
    const h = harness();
    h.receive(parameters);
    h.page.onPageShow(); await settle();
    assert.deepEqual(h.pushes, []);
  }
  const h = harness();
  h.receive({ conversationId: 'x'.repeat(512) });
  h.page.onPageShow(); await settle();
  assert.equal(h.pushes[0].params.conversationId.length, 512);
});

test('a rejected route remains pending for the next real root page show', async () => {
  let rejected = false;
  const h = harness(() => {
    if (!rejected) { rejected = true; return Promise.reject(new Error('page stack busy')); }
    return Promise.resolve();
  });
  h.receive({ topicId: 'topic', title: 'Article' });
  h.page.onPageShow(); await settle();
  assert.equal(h.storage.get('wantTopicId'), 'topic');
  h.page.onPageShow(); await settle();
  assert.equal(h.pushes.length, 2);
  assert.equal(h.storage.get('wantTopicId'), '');
});

test('an older in-flight route cannot erase a newer notification target', async () => {
  let finishFirst;
  let count = 0;
  const h = harness(() => ++count === 1 ? new Promise(resolve => { finishFirst = resolve; }) : Promise.resolve());
  h.page.onPageShow(); await settle();
  h.receive({ topicId: 'first', title: 'First' });
  h.receive({ topicId: 'second', title: 'Second' });
  assert.equal(h.storage.get('wantTopicId'), 'second');
  finishFirst(); await settle();
  assert.deepEqual(h.pushes.map(item => item.params.topicId), ['first', 'second']);
  assert.equal(h.storage.get('wantTopicId'), '');
});

test('a rejected older route releases the newer notification already waiting in flight', async () => {
  let rejectFirst;
  let count = 0;
  const h = harness(() => ++count === 1 ? new Promise((_resolve, reject) => { rejectFirst = reject; })
    : Promise.resolve());
  h.page.onPageShow(); await settle();
  h.receive({ topicId: 'first', title: 'First' });
  h.receive({ topicId: 'second', title: 'Second' });
  rejectFirst(new Error('first route rejected')); await settle();
  assert.deepEqual(h.pushes.map(item => item.params.topicId), ['first', 'second']);
  assert.equal(h.storage.get('wantTopicId'), '');
});

test('destroying the window releases its router until the next rendered root page shows', async () => {
  const h = harness();
  h.page.onPageShow(); await settle();
  h.destroyWindow();
  h.receive({ topicId: 'new-window', title: 'Article' }); await settle();
  assert.deepEqual(h.pushes, []);
  assert.equal(h.storage.get('wantTopicId'), 'new-window');
  h.loadRoot(); await settle();
  assert.deepEqual(h.pushes, []);
  h.page.onPageShow(); await settle();
  assert.equal(h.pushes[0].params.topicId, 'new-window');
});
