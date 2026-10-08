const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

test('late WantAgent completion cannot publish an old run over the new owner', async () => {
  const filename = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl/NotificationNotifier.ets');
  const pending = [];
  const titles = [];
  const exports = {};
  const imports = {
    './ProductIdentity.ets': { getAppBundleName: () => 'app.amber.deepread' },
    '@kit.NotificationKit': { notificationManager: {
      ContentType: { NOTIFICATION_CONTENT_BASIC_TEXT: 0 },
      publish: async r => { titles.push(r.content.normal.title); }, cancel: async () => {},
    } },
    '@kit.AbilityKit': { wantAgent: {
      OperationType: { START_ABILITY: 0 }, WantAgentFlags: { UPDATE_PRESENT_FLAG: 0 },
      getWantAgent: () => new Promise(resolve => pending.push(resolve)),
    } },
    '@kit.PerformanceAnalysisKit': { hilog: { warn: () => {} } },
    '@kit.BasicServicesKit': {},
  };
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, require: s => imports[s], Map, Promise, Error }, { filename });
  const notifier = exports.createNotificationNotifier();
  const runningA = notifier.notifyRunning('t', 'A', 1);
  pending.shift()({}); await runningA;
  const completeA = notifier.notifyCompleted('t', 'A', true, 1);
  const runningB = notifier.notifyRunning('t', 'B', 2);
  pending.pop()({}); await runningB;
  pending.shift()({}); await completeA;
  assert.deepEqual(titles, ['正在深度阅读', '正在深度阅读']);
});

test('failed terminal publication rejects instead of falsely claiming a delivered failure', async () => {
  const filename = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl/NotificationNotifier.ets');
  const exports = {};
  const imports = {
    './ProductIdentity.ets': { getAppBundleName: () => 'app.amber.deepread' },
    '@kit.NotificationKit': { notificationManager: {
      ContentType: { NOTIFICATION_CONTENT_BASIC_TEXT: 0 },
      publish: async request => {
        if (request.content.normal.title === '深度阅读失败') throw new Error('notification unavailable');
      },
      cancel: async () => {},
    } },
    '@kit.AbilityKit': { wantAgent: {
      OperationType: { START_ABILITY: 0 }, WantAgentFlags: { UPDATE_PRESENT_FLAG: 0 },
      getWantAgent: async () => ({}),
    } },
    '@kit.PerformanceAnalysisKit': { hilog: { warn: () => {} } },
    '@kit.BasicServicesKit': {},
  };
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, require: spec => imports[spec], Map, Promise, Error }, { filename });
  const notifier = exports.createNotificationNotifier();
  await notifier.notifyRunning('topic', 'title', 1);
  await assert.rejects(notifier.notifyFailed('topic', 'title', 'source failed', 1), /notification unavailable/);
});

test('late failure WantAgent completion cannot publish over a replacement owner', async () => {
  const filename = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl/NotificationNotifier.ets');
  const pending = [];
  const titles = [];
  const exports = {};
  const imports = {
    './ProductIdentity.ets': { getAppBundleName: () => 'app.amber.deepread' },
    '@kit.NotificationKit': { notificationManager: {
      ContentType: { NOTIFICATION_CONTENT_BASIC_TEXT: 0 },
      publish: async request => { titles.push(request.content.normal.title); }, cancel: async () => {},
    } },
    '@kit.AbilityKit': { wantAgent: {
      OperationType: { START_ABILITY: 0 }, WantAgentFlags: { UPDATE_PRESENT_FLAG: 0 },
      getWantAgent: () => new Promise(resolve => pending.push(resolve)),
    } },
    '@kit.PerformanceAnalysisKit': { hilog: { warn: () => {} } },
    '@kit.BasicServicesKit': {},
  };
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, require: spec => imports[spec], Map, Promise, Error }, { filename });
  const notifier = exports.createNotificationNotifier();
  const first = notifier.notifyRunning('topic', 'first', 1);
  pending.shift()({}); await first;
  const failure = notifier.notifyFailed('topic', 'first', 'error', 1);
  const next = notifier.notifyRunning('topic', 'next', 2);
  pending.pop()({}); await next;
  pending.shift()({}); await failure;
  assert.deepEqual(titles, ['正在深度阅读', '正在深度阅读']);
});

test('a delayed old cancellation finishes before the replacement notification is published', async () => {
  const filename = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl/NotificationNotifier.ets');
  const exports = {};
  const events = [];
  let displayed = '';
  let finishCancel;
  let markCancelStarted;
  const cancelStarted = new Promise(resolve => { markCancelStarted = resolve; });
  const imports = {
    './ProductIdentity.ets': { getAppBundleName: () => 'app.amber.deepread' },
    '@kit.NotificationKit': { notificationManager: {
      ContentType: { NOTIFICATION_CONTENT_BASIC_TEXT: 0 },
      publish: async request => {
        displayed = request.content.normal.text;
        events.push(`publish:${displayed}`);
      },
      cancel: () => new Promise(resolve => {
        events.push('cancel:pending');
        finishCancel = () => {
          displayed = '';
          events.push('cancel:done');
          resolve();
        };
        markCancelStarted();
      }),
    } },
    '@kit.AbilityKit': { wantAgent: {
      OperationType: { START_ABILITY: 0 }, WantAgentFlags: { UPDATE_PRESENT_FLAG: 0 },
      getWantAgent: async () => ({}),
    } },
    '@kit.PerformanceAnalysisKit': { hilog: { warn: () => {} } },
    '@kit.BasicServicesKit': {},
  };
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, require: spec => imports[spec], Map, Promise, Error }, { filename });
  const notifier = exports.createNotificationNotifier();
  await notifier.notifyRunning('topic', 'old', 1);
  const oldCancellation = notifier.cancelRunning('topic', 1);
  await cancelStarted;
  const replacement = notifier.notifyRunning('topic', 'new', 2);
  await new Promise(resolve => setImmediate(resolve));
  finishCancel();
  await Promise.all([oldCancellation, replacement]);
  assert.equal(displayed, 'new');
  assert.deepEqual(events, ['publish:old', 'cancel:pending', 'cancel:done', 'publish:new']);
  // Completion also confirms old cleanup did not delete the replacement owner.
  const beforeCompletion = events.length;
  await notifier.notifyCompleted('topic', 'new', true, 2);
  assert.equal(events.length, beforeCompletion + 1);
  assert.equal(events.at(-1), 'publish:new');
});
