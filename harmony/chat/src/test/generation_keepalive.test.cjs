// E13-B aggregate lifecycle/progress group: actual KeepAlive state machine and
// tracker with controlled platform stubs. No system-task or live-view claim.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const ENTRY = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl');
const loadModule = (name, imports) => {
  const exports = {};
  const filename = path.join(ENTRY, name);
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, {
    exports,
    require: (spec) => {
      if (spec === './GenerationProgressTracker.ets') return loadModule('GenerationProgressTracker.ets', imports);
      if (imports[spec]) return imports[spec];
      throw new Error(`unexpected import ${spec}`);
    },
    Error, Promise, Map, JSON, String, Number, Date, setTimeout, clearTimeout,
  }, { filename });
  return exports;
};

const trackerImports = () => ({
  // Harmony 一参形态 → Node encode() 等价物
  '@kit.ArkTS': { util: { TextEncoder: { create: () => ({ encodeInto: (s) => new TextEncoder().encode(s) }) } } },
});

const keepAliveFixture = () => {
  const calls = { starts: 0, stops: 0, publishes: [] };
  const gate = { startDeferred: null };
  const notification = { notificationId: 4242, slotType: 4, contentType: 9 };
  const imports = {
    '@kit.ArkTS': { util: { TextEncoder: { create: () => ({ encodeInto: (s) => new TextEncoder().encode(s) }) } } },
    '@kit.BackgroundTasksKit': {
      backgroundTaskManager: {
        startBackgroundRunning: (context, modes, want) => {
          calls.starts++;
          if (gate.startDeferred !== null) return gate.startDeferred.promise;
          return Promise.resolve(notification);
        },
        stopBackgroundRunning: async () => { calls.stops++; },
      },
    },
    '@kit.AbilityKit': { wantAgent: { getWantAgent: async () => ({}), OperationType: { START_ABILITY: 0 } } },
    '@kit.NotificationKit': {
      notificationManager: {
        publish: async (request) => { calls.publishes.push(JSON.parse(JSON.stringify(request))); },
        SlotType: { LIVE_VIEW: 4 },
        ContentType: { NOTIFICATION_CONTENT_SYSTEM_LIVE_VIEW: 9 },
      },
    },
    '@kit.PerformanceAnalysisKit': { hilog: { info: () => {}, warn: () => {} } },
  };
  const api = loadModule('BackgroundGenerationKeepAlive.ets', imports);
  return { api, calls, gate };
};

const flush = async (turns = 6) => { for (let i = 0; i < turns; i++) await new Promise((r) => setImmediate(r)); };
const tick = async (ms = 1100) => { await new Promise((r) => setTimeout(r, ms)); await flush(); };
const ctx = {};
const snap = (source, runId, active, chars, bytes) => ({ source, runId, generationActive: active, receivedChars: chars, receivedBytes: bytes });

test('A/B activity lifecycle: shared task, per-run removal, same-ID absolute progress, silence stays silent', async () => {
  const { api, calls } = keepAliveFixture();
  api.reportGenerationProgress(ctx, snap('chat', 'A', true, 0, 0));
  api.reportGenerationProgress(ctx, snap('novel', 'B', true, 100, 300));
  await api.setGenerationKeepAliveBackground(ctx, true);
  assert.equal(calls.starts, 1);
  await tick();
  const first = calls.publishes.at(-1);
  assert.equal(first.id, 4242);
  assert.equal(first.notificationSlotType, 4);
  assert.equal(first.content.systemLiveView.typeCode, 8);
  assert.equal(first.content.systemLiveView.progress.currentValue, 300);
  assert.equal(first.content.systemLiveView.progress.isPercentage, false);
  assert.equal(first.content.systemLiveView.progress.maxValue, undefined);
  // 静默不增长:同样的字节量不再产生新发布
  const before = calls.publishes.length;
  api.reportGenerationProgress(ctx, snap('novel', 'B', true, 100, 300));
  await tick();
  assert.equal(calls.publishes.length, before);
  // A 结束不能释放 B 的任务
  api.reportGenerationProgress(ctx, snap('chat', 'A', false, 0, 0));
  await flush();
  assert.equal(calls.stops, 0);
  // 真实增长发布同 ID 新值;B 归零才停止
  api.reportGenerationProgress(ctx, snap('novel', 'B', true, 200, 640));
  await tick();
  assert.equal(calls.publishes.at(-1).content.systemLiveView.progress.currentValue, 640);
  api.reportGenerationProgress(ctx, snap('novel', 'B', false, 200, 640));
  await flush();
  assert.equal(calls.stops, 1);
  await api.setGenerationKeepAliveBackground(ctx, false);
});

test('foreground during a pending start stops the late-acquired task immediately', async () => {
  const { api, calls, gate } = keepAliveFixture();
  let resolveStart;
  gate.startDeferred = { promise: new Promise((r) => { resolveStart = r; }) };
  api.reportGenerationProgress(ctx, snap('chat', 'A', true, 10, 30));
  const backgrounded = api.setGenerationKeepAliveBackground(ctx, true);
  await flush();
  assert.equal(calls.starts, 1);
  // start 尚未 resolve 即回前台(desired 同步落地,串行 op 随后追赶)
  const foreground = api.setGenerationKeepAliveBackground(ctx, false);
  resolveStart({ notificationId: 4242, slotType: 4, contentType: 9 });
  await backgrounded;
  await foreground;
  await flush(8);
  assert.equal(calls.stops, 1); // 迟到完成的任务被立即停掉
  assert.equal(api.generationKeepAliveHeld(), false);
});

test('tracker: history baseline is zero, growth counts once, retry never decreases, USER/tool-output excluded, UTF8 bytes are real', async () => {
  const { GenerationProgressTracker } = loadModule('GenerationProgressTracker.ets', trackerImports());
  const tracker = new GenerationProgressTracker();
  const msg = (id, role, parts) => ({ id, role, parts, createdAt: '', modelId: '', usage: null, annotations: [] });
  const text = (t) => ({ type: 'text', text: t, metadata: null });
  const reasoning = (t) => ({ type: 'reasoning', reasoning: t, metadata: null, signature: '' });
  const tool = (name, input, output) => ({ type: 'tool', toolCallId: 'c', toolName: name, input, output: output ? [text(output)] : [], approvalState: { type: 'auto' }, metadata: null });
  const history = [msg('h1', 'assistant', [text('已有内容')]), msg('u1', 'user', [text('历史用户')])];
  tracker.seedBaseline(history);
  const zeroed = tracker.totals();
  assert.equal(zeroed.chars, 0);
  assert.equal(zeroed.bytes, 0);
  // 新消息增长一次;USER 与工具 output 不计;中文按真实 UTF8 字节
  const step1 = [msg('h1', 'assistant', [text('已有内容')]), msg('u1', 'user', [text('历史用户')]),
    msg('m1', 'assistant', [text('你好'), tool('file_write', '{"path":"a"}', '工具输出不应计入')])];
  const gained1 = tracker.observe(step1);
  const expectChars = 2 + 'file_write'.length + '{"path":"a"}'.length;
  assert.equal(gained1.chars, expectChars);
  assert.equal(gained1.bytes, 2 * 3 + 'file_write'.length + '{"path":"a"}'.length);
  const totals1 = tracker.totals();
  assert.equal(totals1.chars, expectChars);
  assert.equal(totals1.bytes, 2 * 3 + 10 + 12);
  // 同量快照不再增长
  const silent = tracker.observe(step1);
  assert.equal(silent.chars, 0);
  assert.equal(silent.bytes, 0);
  // 重试:同一消息缩短再重写,租期不减、旧内容不重复计
  tracker.observe([msg('m1', 'assistant', [text('你')])]);
  assert.equal(tracker.totals().chars, expectChars);
  // 重写到超过此前最大值才计增量(此前 m1 已计 24 字符/28 字节)
  const regained = tracker.observe([msg('m1', 'assistant', [text('abcdefghijklmnopqrstuvwxyz0123'), reasoning('思考')])]);
  assert.equal(regained.chars, 32 - expectChars);
  assert.equal(regained.bytes, 36 - (2 * 3 + 10 + 12));
  assert.equal(tracker.totals().chars, 32);
});
