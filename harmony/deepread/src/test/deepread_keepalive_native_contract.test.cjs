// Actual shared Entry source with controlled native API/timers. Device evidence
// for the synchronous 401 is in P6-force-crash-pid51906.log; no device claim here.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const SOURCE = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl/BackgroundGenerationKeepAlive.ets');
const fixture = (options = {}) => {
  const calls = { starts: 0, stops: 0, publishes: [], warnings: [], capabilities: [] };
  const timers = new Map();
  let timerId = 0;
  const imports = {
    './ProductIdentity.ets': { getAppBundleName: () => 'app.amber.deepread' },
    '@kit.BackgroundTasksKit': { backgroundTaskManager: {
      startBackgroundRunning: async () => {
        calls.starts++;
        return { notificationId: 4242, slotType: 4, contentType: 5, ...options.notification };
      },
      stopBackgroundRunning: async () => { calls.stops++; },
    } },
    '@kit.AbilityKit': { wantAgent: { getWantAgent: async () => ({}), OperationType: { START_ABILITY: 0 },
      WantAgentFlags: { UPDATE_PRESENT_FLAG: 1 } } },
    '@kit.NotificationKit': { notificationManager: {
      SlotType: options.slotEnum ?? { LIVE_VIEW: 4, OTHER_TYPES: 0xffff },
      ContentType: options.contentEnum ?? { NOTIFICATION_CONTENT_SYSTEM_LIVE_VIEW: 5, NOTIFICATION_CONTENT_BASIC_TEXT: 0 },
      publish: request => {
        calls.publishes.push(JSON.parse(JSON.stringify(request)));
        if (options.syncError) throw options.syncError;
        return options.asyncError ? Promise.reject(options.asyncError) : Promise.resolve();
      },
    } },
    '@kit.PerformanceAnalysisKit': { hilog: {
      info: () => {}, warn: (...args) => calls.warnings.push(args),
    } },
  };
  const exports = {};
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(SOURCE, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, {
    exports,
    require: spec => {
      if (!imports[spec]) throw new Error(`unexpected import ${spec}`);
      return imports[spec];
    },
    Error, Promise, Map, String, Date,
    canIUse: capability => { calls.capabilities.push(capability); return options.notificationSupported !== false; },
    setTimeout: callback => { const id = ++timerId; timers.set(id, callback); return id; },
    clearTimeout: id => timers.delete(id),
  }, { filename: SOURCE });
  return { api: exports, calls, runTimers: () => {
    const callbacks = [...timers.values()];
    timers.clear();
    callbacks.forEach(callback => callback());
  } };
};

const ctx = {};
const active = bytes => ({ source: 'deepread', runId: 'topic:token', generationActive: true, receivedChars: 2, receivedBytes: bytes });
const settle = async () => { for (let i = 0; i < 6; i++) await new Promise(resolve => setImmediate(resolve)); };
const start = async f => {
  f.api.reportGenerationProgress(ctx, active(6));
  await f.api.setGenerationKeepAliveBackground(ctx, true);
};

test('native publish synchronous 401 is contained and preserves the acquired task', async () => {
  const error = Object.assign(new Error('Invalid parameter'), { code: 401 });
  const f = fixture({ syncError: error });
  await start(f);
  assert.doesNotThrow(() => f.runTimers(), 'native validation can throw before publish returns a Promise');
  await settle();
  assert.equal(f.calls.publishes.length, 1);
  assert.equal(f.api.generationKeepAliveHeld(), true);
  assert.match(f.api.generationKeepAliveLastError(), /publish failed: Error: Invalid parameter/);
  assert.equal(f.calls.warnings.length, 1);
  await f.api.setGenerationKeepAliveBackground(ctx, false);
  assert.equal(f.calls.stops, 1);
});

test('native publish asynchronous rejection remains observable without releasing the task', async () => {
  const f = fixture({ asyncError: new Error('notification update rejected') });
  await start(f);
  f.runTimers();
  await settle();
  assert.match(f.api.generationKeepAliveLastError(), /publish failed: Error: notification update rejected/);
  assert.equal(f.api.generationKeepAliveHeld(), true);
  assert.equal(f.calls.stops, 0);
  await f.api.disposeGenerationKeepAlive(ctx);
});

for (const [name, options] of [
  ['Notification capability is unavailable', { notificationSupported: false }],
  ['system live-view enum is absent', { contentEnum: { NOTIFICATION_CONTENT_BASIC_TEXT: 0 }, notification: { contentType: undefined } }],
  ['LIVE_VIEW slot enum is absent', { slotEnum: { OTHER_TYPES: 0xffff }, notification: { slotType: undefined } }],
  ['returned slot is not LIVE_VIEW', { notification: { slotType: 3 } }],
]) {
  test(`${name}: skip incompatible publication, preserve task and expose the unsupported contract`, async () => {
    const f = fixture(options);
    await start(f);
    assert.doesNotThrow(() => f.runTimers());
    await settle();
    assert.equal(f.calls.publishes.length, 0, 'do not attach systemLiveView to an unsupported native notification type');
    assert.equal(f.api.generationKeepAliveHeld(), true);
    assert.match(f.api.generationKeepAliveLastError(), /progress unavailable:.*slot=.*content=/);
    assert.equal(f.calls.starts, 1);
    assert.equal(f.calls.stops, 0);
    await f.api.setGenerationKeepAliveBackground(ctx, false);
    assert.equal(f.calls.stops, 1);
  });
}

test('supported system live-view publishes the actual same ID, slot, public type and real byte amount', async () => {
  const f = fixture();
  await start(f);
  f.runTimers();
  await settle();
  const request = f.calls.publishes[0];
  assert.equal(request.id, 4242);
  assert.equal(request.notificationSlotType, 4);
  assert.equal(request.content.notificationContentType, 5);
  assert.equal(request.content.systemLiveView.typeCode, 8);
  assert.deepEqual(request.content.systemLiveView.progress, { currentValue: 6, isPercentage: false });
  assert.equal(request.template.name, 'downloadTemplate');
  assert.equal(request.template.data.title, request.content.systemLiveView.title);
  assert.equal(request.template.data.fileName, '模型输出');
  assert.equal(request.template.data.progressValue, undefined, 'unknown total must not become a fake percentage');
  assert.equal(f.api.generationKeepAliveLastError(), '');
  assert.deepEqual(f.calls.capabilities, ['SystemCapability.Notification.Notification']);
  await f.api.disposeGenerationKeepAlive(ctx);
});

// API24 returns an internal contentType=8 for DATA_TRANSFER. The official
// startBackgroundRunning sample publishes public SYSTEM_LIVE_VIEW on its ID.
test('native internal type 8 updates the acquired ID with the documented public system type', async () => {
  const f = fixture({ notification: { contentType: 8 } });
  await start(f);
  f.runTimers();
  await settle();
  assert.equal(f.calls.publishes.length, 1);
  assert.equal(f.calls.publishes[0].id, 4242);
  assert.equal(f.calls.publishes[0].content.notificationContentType, 5);
  assert.equal(f.calls.publishes[0].content.systemLiveView.capsule.title, 'Amber · 深读中');
  await f.api.disposeGenerationKeepAlive(ctx);
});
