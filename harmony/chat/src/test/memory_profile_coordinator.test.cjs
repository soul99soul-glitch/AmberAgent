// Exercise the actual foreground coordinator; SDK, persistence and provider are controlled.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };

const harness = async () => {
  const domain = await import('../main/ets/index.ts');
  const baseline = [1, 2, 3].map(id => domain.makeMemoryRecord({ id, content: '用户喜欢清楚简洁的解释' + id,
    scope: 'long_term', kind: 'user', createdAt: 1000, updatedAt: 1000 }));
  const values = new Map([['appBackgrounded', false], ['chatGenerating', false]]);
  const kv = domain.createMemoryKeyValueStore();
  const worker = { dreamModelEnabled: true, topicWriteEnabled: true, runOnlyOnIdle: true,
    runOnlyOnCharging: true, dreamMaxDailyRuns: 1, timeoutMs: 1000, daydreamReasoningLevel: 'high' };
  const storeCalls = [], modelCalls = [];
  let modelResponse = async () => ({ choices: [{ message: domain.makeAssistantMessage(JSON.stringify({ topics: [],
    profile: [{ text: '偏好简洁解释', memoryIds: [1, 2, 3] }], duplicates: [] })) }] });
  const ops = { snapshotForTopics: async () => baseline, snapshotProfile: async () => null,
    applyTopics: async () => ({ records: baseline, topicIds: [], changed: false, staleCount: 0, rejectedReasons: [] }),
    applyOptimization: async (...args) => {
      await args[4](); storeCalls.push(args);
      return { records: baseline, topicIds: [], changed: true, staleCount: 0, rejectedReasons: [],
        profileUpdated: true, mergedIds: [2] };
    } };
  class MemoryDocumentRefreshError extends Error {}
  class Controller {
    constructor() {
      this.listeners = new Set(); this.signal = { aborted: false,
        addEventListener: (_, listener) => this.listeners.add(listener),
        removeEventListener: (_, listener) => this.listeners.delete(listener) };
    }
    abort() { this.signal.aborted = true; for (const listener of this.listeners) listener(); }
  }
  const imports = { '@amber/chat-domain': domain,
    '@kit.BasicServicesKit': { batteryInfo: { chargingStatus: 1, BatteryChargeState: { ENABLE: 1, FULL: 3 } } },
    '../di/AppContainer.ets': { getChatKvStore: () => kv, prepareProviderApi: async () => ({
      generateText: async (...args) => { modelCalls.push(args); return modelResponse(); } }) },
    './AgentRuntimePrefs.ets': { loadAgentRuntimeSnapshot: async () => ({ memoryWorker: worker }) },
    './AtomicJsonFile.ets': { sharedKvMutex: { withLock: async (_, action) => action() } },
    './EntryAbortController.ets': { EntryAbortController: Controller },
    './MemoryDreamOps.ets': { resolveMemoryDaydreamChoice: async () => ({
      provider: domain.makeProviderSettingOpenAI(), model: domain.makeProviderModel({ modelId: 'memory-model' }) }) },
    './MemoryStore.ets': { getMemoryTopicStoreOps: () => ops, MemoryDocumentRefreshError } };
  const filename = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl/MemoryTopicOps.ets');
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText;
  const exports = {};
  vm.runInNewContext(code, { exports, require: name => {
    assert.ok(imports[name], 'Known coordinator dependency: ' + name); return imports[name];
  }, AppStorage: { get: key => values.get(key) }, Error, Promise, Map, Set, Math, Number, JSON, String,
    Date, setTimeout, clearTimeout }, { filename });
  return { coordinator: new exports.MemoryTopicCoordinator(), baseline, worker, values, ops,
    storeCalls, modelCalls, setModel: response => { modelResponse = response; } };
};

test('one gated model pass supplies topics, profile and confirmed duplicates to the same atomic commit', { timeout: 2000 }, async () => {
  const h = await harness();
  const outcome = await h.coordinator.run(true);
  assert.equal(outcome.status, 'completed', outcome.error);
  assert.equal(h.modelCalls.length, 1);
  assert.equal(h.storeCalls.length, 1, 'The coordinator must commit the combined pass');
  assert.equal(h.storeCalls[0][0], h.baseline);
  assert.equal(outcome.profileUpdated, true);
  assert.equal(outcome.mergedCount, 1);
  const prompt = h.modelCalls[0][0][0].parts.map(part => part.text || '').join('');
  assert.match(prompt, /topics/);
  assert.match(prompt, /profile/);
  assert.match(prompt, /duplicates/);
  assert.equal(h.values.get('chatGenerating'), false);
});

test('write permission revoked during combined model pass prevents all derived writes', { timeout: 2000 }, async () => {
  const h = await harness(), entered = deferred(), response = deferred();
  h.setModel(async () => { entered.resolve(); return response.promise; });
  const active = h.coordinator.run(true); await entered.promise;
  h.worker.topicWriteEnabled = false;
  response.resolve({ choices: [] });
  assert.equal((await active).status, 'skipped');
  assert.equal(h.storeCalls.length, 0);
});

test('combined optimization uses the existing automatic daily budget', { timeout: 2000 }, async () => {
  const h = await harness();
  assert.equal((await h.coordinator.run(false)).status, 'completed');
  assert.equal((await h.coordinator.run(false)).status, 'skipped');
  assert.equal(h.modelCalls.length, 1);
  assert.equal(h.storeCalls.length, 1);
});

test('timed-out combined model response never reaches the optimization commit', { timeout: 2000 }, async () => {
  const h = await harness(), response = deferred(); h.worker.timeoutMs = 10;
  h.setModel(() => response.promise);
  assert.equal((await h.coordinator.run(true)).status, 'timed_out');
  response.resolve({ choices: [] });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.storeCalls.length, 0);
});

test('malformed profile output rejects the combined pass without a partial topic commit', { timeout: 2000 }, async () => {
  const h = await harness();
  const domain = await import('../main/ets/index.ts');
  h.setModel(async () => ({ choices: [{ message: domain.makeAssistantMessage(JSON.stringify({
    topics: [{ title: '偏好', summary: '已存偏好', memberIds: [1, 2] }], profile: 'invalid', duplicates: [] })) }] }));
  assert.equal((await h.coordinator.run(true)).status, 'failed');
  assert.equal(h.storeCalls.length, 0);
});

test('background cancellation prevents topics, profile and duplicate writes', { timeout: 2000 }, async () => {
  const h = await harness(), entered = deferred(), response = deferred();
  h.setModel(async () => { entered.resolve(); return response.promise; });
  const active = h.coordinator.run(true); await entered.promise;
  h.coordinator.background();
  assert.equal((await active).status, 'cancelled');
  response.resolve({ choices: [] });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.storeCalls.length, 0);
});

test('combined prompt offers only stable eligible preferences for the user profile', { timeout: 2000 }, async () => {
  const h = await harness();
  const domain = await import('../main/ets/index.ts');
  h.baseline.push(domain.makeMemoryRecord({ id: 4, content: '项目备注', scope: 'long_term', kind: 'note' }),
    domain.makeMemoryRecord({ id: 5, content: '置顶偏好', scope: 'long_term', kind: 'user', pinned: true }),
    domain.makeMemoryRecord({ id: 6, content: '临时偏好', scope: 'long_term', kind: 'user', expiresAt: Date.now() + 60000 }));
  assert.equal((await h.coordinator.run(true)).status, 'completed');
  const prompt = h.modelCalls[0][0][0].parts.map(part => part.text || '').join('');
  const offered = JSON.parse(prompt.split('preference_memories：\n')[1].split('\nduplicate_candidates：')[0]);
  assert.deepEqual(offered.map(record => record.id), [1, 2, 3]);
});
