const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
process.env.TSX_TSCONFIG_PATH ??= path.resolve(__dirname, '../../tsconfig.json');
require('tsx/cjs');
const models = require('../main/ets/chat/jev_models.ts');
const client = require('../main/ets/chat/jev_client.ts');
const approval = require('../main/ets/chat/jev_approval.ts');
const conversations = require('../main/ets/chat/conversation.ts');
const messages = require('../main/ets/chat/message.ts');
const completion = require('../main/ets/chat/jev_completion.ts');
const { createMemoryKeyValueStore } = require('../main/ets/chat/kv_store.ts');
const question = { fact: { kind: 'noul', instructions: 'x', trueCriteria: '', falseCriteria: '' } };
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
async function fixture(overrides = {}, fetchHook = null) {
  const kv = createMemoryKeyValueStore();
  const settings = models.makeJevSettings({ mode: 'active', model: 'model', apiKey: 'key',
    completionCheck: { mode: 'active', allowTaskText: true, allowToolMetadata: true }, ...overrides });
  await models.saveJevSettings(kv, settings);
  const requests = [], activeTimers = new Set();
  const imports = { '@amber/chat-domain': { ...models, ...client, ...approval, ...conversations, ...completion },
    '../di/AppContainer.ets': { getChatKvStore: () => kv, getAppContainer: () => ({ httpClient: {
      fetch: async (request, options) => { requests.push({ request, signal: options.signal });
        if (fetchHook) return fetchHook(request, options);
        return { status: 200, body: JSON.stringify({ answers: { fact: 0.9 }, model: 'model' }) };
      } } }) } };
  const cache = new Map();
  function load(name) {
    if (cache.has(name)) return cache.get(name);
    const exports = {}; cache.set(name, exports);
    const file = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl', name);
    const code = ts.transpileModule(fs.readFileSync(file, 'utf8'), { compilerOptions: {
      target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText;
    vm.runInNewContext(code, { exports, require: name => imports[name] ?? load(name.slice(2)),
      Error, Promise, Map, Set, Date, JSON, String, Number,
      setTimeout: (fn, ms) => { const id = setTimeout(() => { activeTimers.delete(id); fn(); }, ms); activeTimers.add(id); return id; },
      clearTimeout: id => { activeTimers.delete(id); clearTimeout(id); },
    }, { filename: file });
    return exports;
  }
  return { support: load('JevSupport.ets'), kv, settings, requests, activeTimers, load };
}

test('purpose off is zero HTTP, global shadow caps active, and no timers remain', async () => {
  const off = await fixture({ completionCheck: { mode: 'off' } });
  assert.equal((await off.support.jevEvaluatePurpose('completion_check', {}, question)).ok, false);
  assert.equal(off.requests.length, 0);
  const shadow = await fixture({ mode: 'shadow' });
  assert.equal((await shadow.support.jevEvaluatePurpose('completion_check', {}, question)).shadow, true);
  assert.equal(shadow.activeTimers.size, 0);
});

test('concurrent requests reserve the last daily attempt before HTTP', async () => {
  const f = await fixture();
  await f.kv.put(`jev_budget/${new Date().toISOString().substring(0, 10)}`, '199');
  const results = await Promise.all([f.support.jevEvaluatePurpose('completion_check', {}, question),
    f.support.jevEvaluatePurpose('completion_check', {}, question)]);
  assert.equal(f.requests.length, 1);
  assert.equal(results.filter(result => result.ok).length, 1);
  assert.equal((await f.support.loadJevRuntimeStatus()).todayCount, 200);
});

test('deadline bounds even an HTTP adapter that ignores abort, without cooldown', async (context) => {
  context.mock.timers.enable({ apis: ['setTimeout'] });
  const entered = deferred();
  const f = await fixture({}, async () => { entered.resolve(); return new Promise(() => {}); });
  const pending = f.support.jevEvaluatePurpose('completion_check', {}, question);
  await entered.promise;
  context.mock.timers.runAll();
  const result = await pending;
  assert.equal(result.reason, 'deadline');
  assert.equal(f.requests[0].signal.aborted, true);
  assert.equal(f.activeTimers.size, 0);
  assert.equal((await f.support.loadJevRuntimeStatus()).cooldownUntil, 0);
});

test('parent cancellation relays to HTTP and removes listeners and timer', async () => {
  const gate = deferred(); const entered = deferred();
  const f = await fixture({}, async () => { entered.resolve(); return gate.promise; });
  const controller = new (f.load('EntryAbortController.ets').EntryAbortController)();
  const result = f.support.jevEvaluatePurpose('completion_check', {}, question, controller.signal);
  await entered.promise; controller.abort();
  assert.equal((await result).reason, 'cancelled');
  assert.equal(f.requests[0].signal.aborted, true);
  assert.equal(f.activeTimers.size, 0);
  assert.equal((await f.support.loadJevRuntimeStatus()).cooldownUntil, 0);
});

test('consent revoked during HTTP discards successful result', async () => {
  const gate = deferred(); const entered = deferred();
  const f = await fixture({}, async () => { entered.resolve(); return gate.promise; });
  const result = f.support.jevEvaluatePurpose('completion_check', {}, question);
  await entered.promise;
  await models.saveJevSettings(f.kv, models.makeJevSettings({ ...f.settings,
    completionCheck: { ...f.settings.completionCheck, allowTaskText: false } }));
  gate.resolve({ status: 200, body: JSON.stringify({ answers: { fact: 0.9 }, model: 'model' }) });
  assert.equal((await result).reason, 'settings_changed');
});

test('deadline includes preflight KV and no HTTP starts after the timed-out read resumes', async (context) => {
  context.mock.timers.enable({ apis: ['setTimeout'] });
  const f = await fixture();
  const gate = deferred(), entered = deferred(); const read = f.kv.get;
  f.kv.get = async key => {
    if (key !== 'jev_settings') return read(key);
    entered.resolve();
    return gate.promise;
  };
  const pending = f.support.jevEvaluatePurpose('completion_check', {}, question);
  await entered.promise;
  context.mock.timers.runAll();
  const result = await pending;
  assert.equal(result.reason, 'deadline'); assert.equal(f.requests.length, 0);
  gate.resolve(JSON.stringify(f.settings));
  await new Promise(done => setImmediate(done));
  assert.equal(f.requests.length, 0); assert.equal(f.activeTimers.size, 0);
});

test('independent required scopes keep each new purpose active without unrelated permissions', async () => {
  for (const [purpose, field, core] of [
    ['tool_context_selection', 'toolContextSelection', { allowToolOutput: true }],
    ['context_retention', 'contextRetention', { allowToolOutput: true }],
    ['auto_approval', 'autoApproval', { allowToolMetadata: true }],
    ['completion_check', 'completionCheck', { allowTaskText: true }],
  ]) {
    const f = await fixture({ [field]: { mode: 'active', ...core } });
    const result = await f.support.jevEvaluatePurpose(purpose, {}, question);
    assert.equal(result.ok, true, purpose); assert.equal(f.requests.length, 1, purpose);
  }
});

const changedConversation = () => conversations.makeConversation('completion', [
  conversations.toMessageNode(messages.makeUIMessage('user', [{ type: 'text', text: 'user task', metadata: null }])),
  conversations.toMessageNode(messages.makeUIMessage('assistant', [{ type: 'tool', toolCallId: 'write', toolName: 'file_write', input: '{}',
    output: [{ type: 'text', text: '{"path":"private/file.ts"}', metadata: null }], approvalState: { type: 'approved' }, metadata: null },
    { type: 'text', text: 'done', metadata: null }], { id: 'final' })),
]);
const claimsResponse = request => { const questions = JSON.parse(request.body).questions; const answers = {};
  for (const id of Object.keys(questions)) answers[id] = 0.9;
  return { status: 200, body: JSON.stringify({ answers, model: 'model' }) };
};

test('actual completion Entry evaluates task-only consent and never sends optional private file paths/output', async () => {
  const f = await fixture({ completionCheck: { mode: 'active', allowTaskText: true } }, async request => claimsResponse(request));
  const controller = new (f.load('EntryAbortController.ets').EntryAbortController)();
  const result = await f.load('JevCompletionSupport.ets').evaluateJevCompletion(changedConversation(), controller.signal);
  assert.equal(result.finalMessageId, 'final'); assert.equal(f.requests.length, 1);
  const body = JSON.parse(f.requests[0].request.body);
  assert.equal(JSON.stringify(body).includes('private/file.ts'), false);
  assert.equal(JSON.stringify(body).includes('user task'), true); assert.equal(JSON.stringify(body).includes('done'), true);
  assert.deepEqual(Object.keys(body.questions).sort(), ['claims_done', 'claims_verified']);
});

test('actual completion Entry shadow/off and disabled-late consent cannot publish a notice candidate', async () => {
  for (const mode of ['off', 'shadow']) {
    const f = await fixture({ completionCheck: { mode, allowTaskText: true } }, async request => claimsResponse(request));
    const controller = new (f.load('EntryAbortController.ets').EntryAbortController)();
    assert.equal(await f.load('JevCompletionSupport.ets').evaluateJevCompletion(changedConversation(), controller.signal), null);
    assert.equal(f.requests.length, mode === 'off' ? 0 : 1);
  }
  const entered = deferred(), gate = deferred();
  const f = await fixture({}, async request => { entered.resolve(); await gate.promise; return claimsResponse(request); });
  const controller = new (f.load('EntryAbortController.ets').EntryAbortController)();
  const result = f.load('JevCompletionSupport.ets').evaluateJevCompletion(changedConversation(), controller.signal);
  await entered.promise;
  await models.saveJevSettings(f.kv, models.makeJevSettings({ ...f.settings, mode: 'off' })); gate.resolve();
  assert.equal(await result, null);
});

test('consent revoked after building optional payload but before evaluation sends zero HTTP', async () => {
  const f = await fixture({ completionCheck: { mode: 'active', allowTaskText: true, allowToolMetadata: true } });
  const built = completion.buildJevCompletionBatch(f.settings,
    completion.jevUnverifiedChanges(conversations.currentMessages(changedConversation())), 'task');
  await models.saveJevSettings(f.kv, models.makeJevSettings({ ...f.settings,
    completionCheck: { ...f.settings.completionCheck, allowToolMetadata: false } }));
  const result = await f.support.jevEvaluatePurpose('completion_check', built.state, built.questions, undefined, f.settings);
  assert.equal(result.reason, 'settings_changed'); assert.equal(f.requests.length, 0);
});
