const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../../../chat/node_modules/typescript');
const sourceRoot = path.resolve(__dirname, '../main/ets');

function load(relative, storage, imports = {}) {
  const exports = {};
  const filename = path.join(sourceRoot, relative);
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, {
    exports, require: spec => imports[spec] || {},
    AppStorage: { get: key => storage.get(key), setOrCreate: (key, value) => storage.set(key, value) },
    Promise, Error, Map, Date, setTimeout, clearTimeout, canIUse: () => true,
  }, { filename });
  return exports;
}

for (const [bundle, kind, root] of [
  ['app.amber.deepread', 'agent', 'pages/ChatListPage'],
  ['app.amber.novel', 'novel', 'pages/NovelProjectsPage'],
  ['app.amber.deepread.reader', 'deepread', 'pages/DeepReadRootPage'],
]) {
  test(`${kind} root and both notification types target their installed sandbox`, async () => {
    const storage = new Map([['abilityContext', { applicationInfo: { name: bundle } }]]);
    const identity = load('platform_impl/ProductIdentity.ets', storage);
    storage.set('productKind', identity.productKindForBundle(identity.getAppBundleName()));
    assert.equal(identity.getProductKind(), kind);
    assert.equal(identity.productRootPage(), root);
    const wants = [];
    const notifier = load('platform_impl/NotificationNotifier.ets', storage, {
      './ProductIdentity.ets': identity,
      '@kit.AbilityKit': { wantAgent: {
        OperationType: { START_ABILITY: 0 }, WantAgentFlags: { UPDATE_PRESENT_FLAG: 0 },
        getWantAgent: async info => { wants.push(info.wants[0]); return {}; },
      } },
      '@kit.NotificationKit': { notificationManager: {
        ContentType: { NOTIFICATION_CONTENT_BASIC_TEXT: 0 }, publish: async () => {}, cancel: async () => {},
      } },
      '@kit.PerformanceAnalysisKit': { hilog: { warn() {} } },
    });
    await notifier.createNotificationNotifier().notifyRunning('topic', 'Article', 1);
    await notifier.buildNovelJobWantAgent('project', 'branch', 'job');
    assert.equal(wants.length, 2);
    assert.ok(wants.every(want => want.bundleName === bundle && want.abilityName === 'EntryAbility'));
    assert.equal(wants[0].parameters.topicId, 'topic');
    assert.equal(wants[1].parameters.novelJobId, 'job');
  });
  test(`${kind} work scheduling follows the WorkAbility context`, () => {
    const storage = new Map([['abilityContext', { applicationInfo: { name: bundle } }]]);
    const identity = load('platform_impl/ProductIdentity.ets', storage);
    const calls = [];
    const scheduler = load('platform_impl/NovelPolishWorkScheduler.ets', storage, {
      './ProductIdentity.ets': identity,
      '@kit.BackgroundTasksKit': { workScheduler: {
        NetworkType: { NETWORK_TYPE_ANY: 0 }, startWork: work => calls.push(work),
        stopWork: work => calls.push(work),
      } },
      '@kit.PerformanceAnalysisKit': { hilog: { error() {}, warn() {} } },
    });
    const key = { projectId: 'p', branchId: 'b', jobId: 'j' };
    scheduler.scheduleNovelPolishWork(key); scheduler.cancelNovelPolishWork(key);
    assert.equal(calls.length, 2);
    assert.ok(calls.every(work => work.bundleName === bundle));
    assert.equal(calls[0].workId, calls[1].workId);
  });
}

test('an uninitialized context does not send a standalone notification to the host', () => {
  const identity = load('platform_impl/ProductIdentity.ets', new Map());
  assert.throws(() => identity.getAppBundleName(), /尚未初始化/);
});
