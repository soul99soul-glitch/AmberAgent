// Actual Jev Entry lifecycle + strict domain codec. HTTP/store/hash are controlled host ports;
// this fixture does not claim device Crypto or live evaluation acceptance.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const ts = require('typescript');
require('tsx/cjs');
const models = require('../main/ets/chat/jev_models.ts');
const client = require('../main/ets/chat/jev_client.ts');
const approval = require('../main/ets/chat/jev_approval.ts');
const web = require('../main/ets/chat/jev_web.ts');
const conversations = require('../main/ets/chat/conversation.ts');
const messages = require('../main/ets/chat/message.ts');
const tools = require('../main/ets/chat/tool.ts');
const { createMemoryKeyValueStore } = require('../main/ets/chat/kv_store.ts');
const domain = { ...models, ...client, ...approval, ...web, ...conversations, ...messages, ...tools };
const clone = value => JSON.parse(JSON.stringify(value));
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };
const turn = () => new Promise(resolve => setImmediate(resolve));
const parent = (name = 'custom_tool', input = '{"command":"raw-secret-command","headers":{"Authorization":"token-secret"}}') => ({
  type: 'tool', toolCallId: 'parent', toolName: name, input, output: [], approvalState: { type: 'pending' },
  metadata: { keep: 'original', permission_trace: { policy: { category: 'terminal', risk: 'high', mutates: false } } },
});
const makeConversation = part => conversations.makeConversation('conversation', [
  conversations.toMessageNode(messages.makeUIMessage('user', [{ type: 'text', text: '搜索 raw-secret-task curl https://host/?token=private', metadata: null }], { id: 'user' })),
  conversations.toMessageNode(messages.makeUIMessage('assistant', [part], { id: 'assistant' })),
]);

async function fixture(mode = 'active') {
  const kv = createMemoryKeyValueStore();
  let settings = models.makeJevSettings({ mode: 'active', apiKey: 'jev-secret', model: 'evaluation-model',
    approvalTriage: { mode, allowToolMetadata: true, allowTaskText: true, allowPageContent: false },
    webAutomation: { mode: 'active', allowToolMetadata: true, allowTaskText: false, allowPageContent: true } });
  await models.saveJevSettings(kv, settings);
  let canonical = makeConversation(parent());
  const requests = [], observations = [], applied = [];
  let httpGate = null, entered = null, queueHook = null;
  const http = { fetch: async (request, options) => {
    requests.push({ request, signal: options.signal });
    if (entered) entered.resolve();
    if (httpGate) await httpGate.promise;
    const questions = JSON.parse(request.body).questions;
    const answers = {};
    for (const id of Object.keys(questions)) answers[id] = questions[id].type === 'choice' ? 'candidate' : id === 'readonly' ? 0.7 : id === 'reversible' ? 0.3 : 0.5;
    return { status: 200, headers: {}, body: JSON.stringify({ answers, model: 'evaluation-model' }) };
  } };
  const imports = {
    '@amber/chat-domain': domain,
    '../di/AppContainer.ets': { getChatKvStore: () => kv, getAppContainer: () => ({ httpClient: http }) },
    './EntryLocalToolPorts.ets': { sha256HexUtf8: text => crypto.createHash('sha256').update(text).digest('hex') },
  };
  const cache = new Map();
  const entry = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl');
  function load(file) {
    if (cache.has(file)) return cache.get(file);
    const exports = {}; cache.set(file, exports);
    const code = ts.transpileModule(fs.readFileSync(path.join(entry, file), 'utf8'), {
      compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
    }).outputText;
    vm.runInNewContext(code, { exports, require: name => {
      if (Object.hasOwn(imports, name)) return imports[name];
      if (name.startsWith('./') && name.endsWith('.ets')) return load(name.slice(2));
      throw new Error('Unexpected import ' + name);
    }, Error, Promise, Map, Set, Date, JSON, String, Number, RegExp, setTimeout, clearTimeout }, { filename: file });
    return exports;
  }
  const support = load('JevSupport.ets');
  const entryApi = load('JevApprovalTriage.ets');
  const deps = {
    loadConversation: async () => clone(canonical),
    loadSettings: () => models.loadJevSettings(kv),
    policy: () => ({ readonly: 'unknown', reversible: 'unknown', category: 'terminal', risk: 'high', mutates: false }),
    evaluate: support.jevEvaluate,
    recordShadow: async record => { observations.push(clone(record)); },
    mutateConversation: async (_id, update) => {
      if (queueHook) await queueHook();
      if (canonical === null) return null;
      const result = await update(clone(canonical));
      if (result !== null) { canonical = clone(result); applied.push(clone(result)); }
      return result;
    },
  };
  const runtime = new entryApi.JevApprovalTriageRuntime(deps);
  return {
    support, entryApi, runtime, deps, requests, observations, applied,
    get canonical() { return canonical; }, set canonical(value) { canonical = value; },
    set queueHook(value) { queueHook = value; },
    async settings(update) { settings = update(settings); await models.saveJevSettings(kv, settings); },
    pauseHttp() { httpGate = deferred(); entered = deferred(); return { entered: entered.promise, release: () => httpGate.resolve() }; },
  };
}

test('actual Jev HTTP uses a bounded child signal, cancellation is not an error cooldown', async () => {
  const f = await fixture();
  const signal = { aborted: false };
  await f.support.jevEvaluate({}, { fact: { kind: 'noul', instructions: 'x', trueCriteria: '', falseCriteria: '' } }, signal);
  assert.notEqual(f.requests[0].signal, signal);
  assert.equal(f.requests[0].signal.aborted, false);
  signal.aborted = true;
  const before = f.requests.length;
  await f.support.jevEvaluate({}, { fact: { kind: 'noul', instructions: 'x', trueCriteria: '', falseCriteria: '' } }, signal);
  assert.equal(f.requests.length, before);
});

test('OFF has zero HTTP/application; SHADOW only narrow observation; ACTIVE preserves approvals and latest metadata', async () => {
  const off = await fixture('off'); await off.runtime.run('conversation');
  assert.equal(off.requests.length, 0); assert.equal(off.applied.length, 0);
  const shadow = await fixture('shadow'); await shadow.runtime.run('conversation');
  assert.equal(shadow.requests.length, 1); assert.equal(shadow.applied.length, 0); assert.equal(shadow.observations.length, 1);
  assert.deepEqual(Object.keys(shadow.observations[0]).sort(), ['facts', 'model', 'purpose', 'status', 'subjectHash']);
  assert.equal(JSON.stringify(shadow.observations).includes('raw-secret'), false);
  const dispatchShadow = await fixture(); const evaluate = dispatchShadow.deps.evaluate;
  dispatchShadow.deps.evaluate = async (...args) => ({ ...await evaluate(...args), shadow: true });
  await dispatchShadow.runtime.run('conversation');
  assert.equal(dispatchShadow.applied.length, 0); assert.equal(dispatchShadow.observations.length, 1);
  const active = await fixture(); const paused = active.pauseHttp();
  const run = active.runtime.run('conversation'); await paused.entered;
  active.canonical.messageNodes[1].messages[0].parts[0].metadata.other = { preserved: true };
  paused.release(); await run;
  const result = active.canonical.messageNodes[1].messages[0].parts[0];
  assert.deepEqual(result.approvalState, { type: 'pending' }); assert.deepEqual(result.output, []);
  assert.equal(result.metadata.keep, 'original'); assert.deepEqual(result.metadata.other, { preserved: true });
  assert.equal(result.metadata.jev_approval_v1.readonly, 'yes'); assert.equal(result.metadata.jev_approval_v1.reversible, 'no');
  const request = JSON.stringify(active.requests[0].request);
  for (const secret of ['raw-secret-command', 'raw-secret-task', 'token-secret', '?token=private', 'curl https']) assert.equal(request.includes(secret), false);
});

test('late result after answered/deleted/changed input/initiating user or cancelled session cannot apply', async () => {
  for (const kind of ['answered', 'deleted', 'input', 'user', 'cancel']) {
    const f = await fixture(); const paused = f.pauseHttp(); const run = f.runtime.run('conversation'); await paused.entered;
    if (kind === 'answered') f.canonical.messageNodes[1].messages[0].parts[0].approvalState = { type: 'answered', answer: 'human' };
    if (kind === 'deleted') f.canonical = null;
    if (kind === 'input') f.canonical.messageNodes[1].messages[0].parts[0].input = '{"command":"changed"}';
    if (kind === 'user') f.canonical.messageNodes[0].messages[0].id = 'new-user';
    if (kind === 'cancel') f.runtime.cancel('conversation');
    paused.release(); await run; assert.equal(f.applied.length, 0, kind);
  }
});

test('settings disabled inside the actual canonical mutation queue drop the completed ACTIVE result', async () => {
  const f = await fixture();
  f.queueHook = () => f.settings(settings => models.makeJevSettings({ ...settings, approvalTriage: { ...settings.approvalTriage, mode: 'off' } }));
  await f.runtime.run('conversation');
  assert.equal(f.applied.length, 0);
});

test('saved Recipe/Plugin child, ask_user parent locator and fixed target bind facts to the actual step', () => {
  const locator = { conversationId: 'c', messageId: 'm', partIndex: 0, toolCallId: 'parent' };
  const child = parent('ask_user', '{"question":"raw-secret-question"}'); child.toolCallId = 'child';
  const recipe = parent('recipe__flow'); recipe.metadata.recipe_v1 = { kind: 'run', phase: 'awaiting_approval',
    executionId: 'exec', descriptor: { hash: 'recipe-hash', manifest: { name: 'flow', steps: [{ tool: 'ask_user' }] } },
    inputs: {}, nextIndex: 0, pendingStep: child };
  const saved = approval.buildJevApprovalSubject(locator, recipe, 'u');
  assert.equal(saved.action.toolName, 'ask_user'); assert.equal(saved.packageHash, 'recipe-hash');
  assert.equal(JSON.parse(saved.bindingJSON).locator.toolCallId, 'parent');
  const plugin = parent('plugin_test'); plugin.metadata.plugin_v1 = { kind: 'run', phase: 'awaiting_approval',
    executionId: 'exec', descriptor: { packageHash: 'package-hash' }, pendingStep: child,
    pendingCallId: 'call', inputs: {}, recipeState: null,
    test: { kind: 'candidate_test', candidateHash: 'package-hash', expectedProvided: true, expectedResult: 'fixed' } };
  const before = approval.buildJevApprovalSubject(locator, plugin, 'u').bindingJSON;
  plugin.metadata.plugin_v1.test.expectedProvided = false;
  assert.notEqual(approval.buildJevApprovalSubject(locator, plugin, 'u').bindingJSON, before);
  const terminal = parent('terminal_execute'); assert.equal(approval.buildJevApprovalSubject(locator, terminal, 'u'), null);
  terminal.metadata.terminal_target = { profileId: 'ssh', digest: 'fixed', usesDefault: true };
  const target = approval.buildJevApprovalSubject(locator, terminal, 'u').bindingJSON;
  terminal.metadata.terminal_target.digest = 'edited';
  assert.notEqual(approval.buildJevApprovalSubject(locator, terminal, 'u').bindingJSON, target);
  const mcp = parent('mcp_call_tool', '{"tool_name":"search"}');
  mcp.metadata.council_mcp_target = { serverId: 'srv', toolName: 'search' };
  const pinned = approval.buildJevApprovalSubject(locator, mcp, 'u').bindingJSON;
  assert.deepEqual(JSON.parse(pinned).action.councilMcpTarget, { serverId: 'srv', toolName: 'search' });
  mcp.metadata.council_mcp_target = { serverId: 'other', toolName: 'search' };
  assert.notEqual(approval.buildJevApprovalSubject(locator, mcp, 'u').bindingJSON, pinned);
  mcp.metadata.council_mcp_target = { serverId: 'srv' };
  assert.equal(approval.buildJevApprovalSubject(locator, mcp, 'u'), null);
  delete mcp.metadata.council_mcp_target;
  assert.equal(JSON.parse(approval.buildJevApprovalSubject(locator, mcp, 'u').bindingJSON).action.councilMcpTarget, null);
});

test('old settings default new purposes OFF, global caps survive codec; facts decode strictly and absent material stays unknown', async () => {
  const kv = createMemoryKeyValueStore(); await kv.put('jev_settings', '{"mode":"active","apiKey":"k","model":"m"}');
  const settings = await models.loadJevSettings(kv); assert.equal(settings.approvalTriage.mode, 'off'); assert.equal(settings.webAutomation.allowPageContent, false);
  assert.equal(approval.resolveJevPurposeMode(models.makeJevSettings({ mode: 'shadow', approvalTriage: { mode: 'active' } }), 'approval_triage'), 'shadow');
  assert.equal(approval.jevFactFromProbability(0.35), 'no'); assert.equal(approval.jevFactFromProbability(0.65), 'yes');
  assert.equal(approval.jevFactFromProbability(0.5), 'unknown'); for (const value of [NaN, Infinity, -1, 2]) assert.throws(() => approval.jevFactFromProbability(value));
  const subject = { action: parent(), packageHash: null, baseHash: null, bindingJSON: 'local' };
  const batch = approval.buildJevApprovalBatch(settings, subject, 'hash', null, 'raw-secret-task');
  assert.deepEqual(batch.questions, {}); assert.equal(batch.facts.goalAligned, 'unknown');
  assert.throws(() => client.decodeJevResponse('{"answers":{"readonly":2}}', { readonly: { kind: 'noul', instructions: '', trueCriteria: '', falseCriteria: '' } }, 'typesafe'));
});

test('finite Web seam excludes candidate inputs/raw descriptions; shadow returns handback and cannot execute', async () => {
  const f = await fixture(); const api = (() => {
    const file = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl/JevWebDecision.ets'); const exports = {};
    vm.runInNewContext(ts.transpileModule(fs.readFileSync(file, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText,
      { exports, require: () => domain, Error, Promise, JSON, Object, String, Number }); return exports;
  })();
  api.initializeJevWebDecision({ loadSettings: f.deps.loadSettings, evaluate: f.support.jevEvaluate, recordShadow: async () => {} });
  const observation = { sessionId: 'session', revision: 'revision', pageSummary: '搜索 raw-secret-page' };
  const candidates = [{ id: 'candidate', toolName: 'wm_click', input: { url: 'https://secret/?token=private' }, description: 'raw-secret-description' }];
  const batch = web.buildJevWebChoice(observation, candidates);
  assert.equal(JSON.stringify(batch).includes('raw-secret'), false); assert.equal(JSON.stringify(batch).includes('token=private'), false);
  await f.settings(settings => models.makeJevSettings({ ...settings, webAutomation: { ...settings.webAutomation, mode: 'shadow' } }));
  const result = await api.chooseJevWebCandidate(observation, candidates);
  assert.equal(result.kind, 'handback'); assert.equal(result.shadow, true); assert.equal(f.applied.length, 0);
});
