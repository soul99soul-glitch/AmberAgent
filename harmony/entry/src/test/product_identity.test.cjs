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
  ['app.amber.deepread', 'agent', 'pages/ChatPage'],
  ['app.amber.novel', 'novel', 'pages/ChatPage'],
  ['app.amber.deepread.reader', 'deepread', 'pages/ChatPage'],
]) {
  test(`${kind} root and notification target their installed sandbox (${bundle})`, async () => {
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
    assert.equal(wants.length, 1);
    assert.equal(wants[0].bundleName, bundle);
    assert.equal(wants[0].abilityName, 'EntryAbility');
    assert.equal(wants[0].parameters.topicId, 'topic');
  });
}

test('an uninitialized context does not send a standalone notification to the host', () => {
  const identity = load('platform_impl/ProductIdentity.ets', new Map());
  assert.throws(() => identity.getAppBundleName(), /尚未初始化/);
});
