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
    canIUse: () => true,
  }, { filename });
  return exports;
};

const trackerImports = () => ({
  // Harmony 一参形态 → Node encode() 等价物
  '@kit.ArkTS': { util: { TextEncoder: { create: () => ({ encodeInto: (s) => new TextEncoder().encode(s) }) } } },
});

const keepAliveFixture = () => {
  const calls = { starts: 0, stops: 0, publishes: [], startContexts: [], stopContexts: [] };
  const gate = { startDeferred: null };
  const notification = { notificationId: 4242, slotType: 4, contentType: 5 };
  const imports = {
    './ProductIdentity.ets': { getAppBundleName: () => 'app.amber.deepread' },
    '@kit.ArkTS': { util: { TextEncoder: { create: () => ({ encodeInto: (s) => new TextEncoder().encode(s) }) } } },
    '@kit.BackgroundTasksKit': {
      backgroundTaskManager: {
        startBackgroundRunning: (context, modes, want) => {
          calls.starts++;
          calls.startContexts.push(context);
          if (gate.startDeferred !== null) return gate.startDeferred.promise;
          return Promise.resolve(notification);
        },
        stopBackgroundRunning: async (context) => { calls.stops++; calls.stopContexts.push(context); },
      },
    },
    '@kit.AbilityKit': { wantAgent: { getWantAgent: async (info) => ({ info }),
      OperationType: { START_ABILITY: 0 }, WantAgentFlags: { UPDATE_PRESENT_FLAG: 1 } } },
    '@kit.NotificationKit': {
      notificationManager: {
        publish: async (request) => { calls.publishes.push(JSON.parse(JSON.stringify(request))); },
        SlotType: { LIVE_VIEW: 4 },
        ContentType: { NOTIFICATION_CONTENT_SYSTEM_LIVE_VIEW: 5 },
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

test('UIAbility owner survives WorkScheduler progress and the last run stops with its original context', async () => {
  const { api, calls } = keepAliveFixture();
  const uiContext = { kind: 'UIAbilityContext' };
  const extensionContext = { kind: 'WorkSchedulerExtensionContext' };
  api.reportGenerationProgress(uiContext, snap('novel', 'job', true, 0, 0));
  await api.setGenerationKeepAliveBackground(uiContext, true);
  api.reportGenerationProgress(extensionContext, snap('novel', 'job', true, 10, 30));
  api.reportGenerationProgress(extensionContext, snap('novel', 'job', false, 10, 30));
  await flush();
  assert.equal(calls.startContexts[0], uiContext);
  assert.equal(calls.stopContexts[0], uiContext);
  assert.equal(api.generationKeepAliveHeld(), false);
  await api.disposeGenerationKeepAlive(uiContext);
});

test('cold WorkScheduler progress does not request a UIAbility background lease', async () => {
  const { api, calls } = keepAliveFixture();
  const extensionContext = { kind: 'WorkSchedulerExtensionContext' };
  api.reportGenerationProgress(extensionContext, snap('novel', 'cold-job', true, 10, 30));
  await flush();
  assert.equal(calls.starts, 0);
  api.reportGenerationProgress(extensionContext, snap('novel', 'cold-job', false, 10, 30));
  await flush();
  assert.equal(calls.stops, 0);
});

// This stub checks outgoing requests; it does not model the system owner-update filter or prove capsule visibility.
test('outgoing notification metadata and click target follow the remaining activity even without byte growth', async () => {
  const { api, calls } = keepAliveFixture();
  const chat = { ...snap('chat', 'A', true, 2, 6), title: '会话 A', conversationId: 'conversation-A' };
  api.reportGenerationProgress(ctx, chat);
  await api.setGenerationKeepAliveBackground(ctx, true);
  await tick();
  let request = calls.publishes.at(-1);
  assert.equal(request.content.systemLiveView.capsule.title, 'Amber · 回复中');
  assert.equal(request.content.systemLiveView.title, '会话 A · 回复中');
  assert.equal(request.wantAgent.info.wants[0].parameters.conversationId, 'conversation-A');
  // B has no output yet: membership alone must update request metadata and aggregate click target.
  api.reportGenerationProgress(ctx, { ...snap('deepread', 'B', true, 0, 0), topicId: 'topic-B' });
  await tick();
  request = calls.publishes.at(-1);
  assert.equal(request.content.systemLiveView.capsule.title, 'Amber · 2 个任务');
  assert.deepEqual(request.wantAgent.info.wants[0].parameters, {});
  api.reportGenerationProgress(ctx, { ...chat, generationActive: false });
  await tick();
  request = calls.publishes.at(-1);
  assert.equal(request.content.systemLiveView.capsule.title, 'Amber · 深读中');
  assert.equal(request.wantAgent.info.wants[0].parameters.topicId, 'topic-B');
  assert.equal(calls.stops, 0);
  api.reportGenerationProgress(ctx, snap('deepread', 'B', false, 0, 0));
  await flush();
  assert.equal(calls.stops, 1);
  await api.disposeGenerationKeepAlive(ctx);
});

test('replacement activity refreshes its full title when display prefix and bytes stay unchanged', async () => {
  const { api, calls } = keepAliveFixture();
  const prefix = '完整文章标题'.repeat(20);
  const old = { ...snap('deepread', 'topic:1', true, 0, 0), topicId: 'topic', title: `${prefix}旧标题` };
  const replacement = { ...snap('deepread', 'topic:2', true, 0, 0), topicId: 'topic', title: `${prefix}新标题` };
  api.reportGenerationProgress(ctx, old);
  await api.setGenerationKeepAliveBackground(ctx, true);
  await tick();
  assert.equal(calls.publishes.at(-1).wantAgent.info.wants[0].parameters.title, old.title);
  const initialCount = calls.publishes.length;
  const displayTitle = calls.publishes.at(-1).content.systemLiveView.title;
  // A late owner finishes after its replacement registers. The activity set
  // never empties, and the timer merges the intermediate two-activity state.
  api.reportGenerationProgress(ctx, replacement);
  api.reportGenerationProgress(ctx, { ...old, generationActive: false });
  await tick();
  assert.equal(calls.stops, 0);
  assert.equal(calls.publishes.length, initialCount + 1);
  assert.equal(calls.publishes.at(-1).content.systemLiveView.title, displayTitle);
  assert.equal(calls.publishes.at(-1).wantAgent.info.wants[0].parameters.title, replacement.title);
  api.reportGenerationProgress(ctx, { ...replacement, generationActive: false });
  await flush();
  await api.disposeGenerationKeepAlive(ctx);
});

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
  resolveStart({ notificationId: 4242, slotType: 4, contentType: 5 });
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

test('tracker skips unchanged history UTF8 work but measures immutable replacements and retry maxima', () => {
  let encodes = 0;
  const imports = {
    './ProductIdentity.ets': { getAppBundleName: () => 'app.amber.deepread' }, '@kit.ArkTS': { util: { TextEncoder: { create: () => ({ encodeInto: (text) => {
    encodes++; return new TextEncoder().encode(text);
  } }) } } } };
  const { GenerationProgressTracker } = loadModule('GenerationProgressTracker.ets', imports);
  const tracker = new GenerationProgressTracker();
  const message = (id, text) => ({ id, role: 'assistant', parts: [{ type: 'text', text }] });
  const history = message('history', '历史');
  tracker.seedBaseline([history]);
  assert.equal(encodes, 1);
  const first = message('tail', '你');
  tracker.observe([history, first]);
  tracker.observe([history, first]);
  assert.equal(encodes, 2, 'unchanged message references should skip concatenation and UTF8 encoding');
  tracker.observe([history, message('tail', '你好')]);
  tracker.observe([history, message('tail', '你')]);
  tracker.observe([history, message('tail', '你好！')]);
  assert.equal(encodes, 5);
  assert.deepEqual({ ...tracker.totals() }, { chars: 3, bytes: 9 });
});
