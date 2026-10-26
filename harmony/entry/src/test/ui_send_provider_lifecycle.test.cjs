// Run the actual ArkTS owners and retained page methods; mock platform/domain boundaries only.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const { loadChatSmartUiSession } = require('./chat_smart_ui_fixture.cjs');
const domain = require('../../../chat/src/main/ets/index.ts');
const ChatSmartUiSession = loadChatSmartUiSession(domain);
const { loadChatComposerSession } = require('./chat_composer_fixture.cjs');
const ChatComposerSession = loadChatComposerSession({ ...domain, firstImageBlockingIssueForSend: async () => null });
const { loadProviderSessions, providerPage } = require('./provider_session_fixture.cjs');
const { rootComponentInitializers } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');
const root = path.resolve(__dirname, '../main/ets/pages');
const read = name => fs.readFileSync(path.join(root, name + '.ets'), 'utf8');
function method(source, name) {
  const re = new RegExp('^  (?:private )?(?:async )?' + name + '\\(', 'm');
  const match = re.exec(source);
  assert.ok(match, 'missing source method ' + name);
  const start = match.index;
  let body = source.indexOf('{', start), depth = 1, end = body + 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(start, end);
}
function actualPage(name, names, env = {}, extra = '') {
  const source = read(name);
  const code = ts.transpileModule('class Page {\n' + rootComponentInitializers(source) + '\n' + names.map(n => method(source, n)).join('\n')
    + '\n' + extra + '\n}\nreturn Page;', { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  return new (new Function(...Object.keys(env), code)(...Object.values(env)))();
}
const tick = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; }
function loadDrawerSession() {
  const source = fs.readFileSync(path.join(root, '../platform_impl/chat/ChatConversationDrawerSession.ets'), 'utf8');
  const js = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText;
  const exports = {};
  new Function('exports', 'require', 'Observed', js)(exports, name => {
    if (name === '@amber/chat-domain') return domain;
    if (name.endsWith('/ConversationRunGuard.ets')) return {};
    if (name.endsWith('/design/tokens.ets')) return {};
    throw new Error('Unexpected drawer dependency: ' + name);
  }, constructor => constructor);
  return exports.ChatConversationDrawerSession;
}

function productionSubmission({ options, smartUi, runService, conversation }) {
  const source = fs.readFileSync(path.join(root, '../platform_impl/chat/ChatConversationPresentation.ets'), 'utf8');
  const js = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText;
  const exports = {};
  new Function('exports', 'require', 'Observed', js)(exports, name => {
    if (name === '@amber/chat-domain') return domain;
    if (name === '@kit.ArkUI') return { promptAction: { showToast() {} } };
    if (name === '@kit.IMEKit') return { inputMethod: {} };
    if (name === '@kit.SensorServiceKit') return { vibrator: {} };
    if (name === '@kit.PerformanceAnalysisKit') return { hilog: { warn() {} } };
    if (name.endsWith('/ConversationRunGuard.ets')) return { conversationRestoreVersion: () => options.rejectSubmission ? 1 : 0 };
    if (name.endsWith('/StreamingFrameRate.ets')) return { StreamingFrameRate: class { update() {} } };
    if (name.endsWith('/ChatCompactionSupport.ets')) return {};
    throw new Error('Unexpected presentation dependency: ' + name);
  }, constructor => constructor);
  const presentation = new exports.ChatConversationPresentation();
  presentation.start({
    repository: { getById: async () => conversation }, runService,
    clearSmartUi: () => smartUi.clearSmartUiMemory(), resetTimeline() {}, refreshModel() {},
  });
  presentation.conversation = conversation;
  presentation.runConversationId = conversation.id;
  presentation.unsubscribeRun = () => {};
  return presentation;
}

function composer(options = {}) {
  const gate = deferred(), append = deferred();
  const calls = [];
  let choicePromise = gate.promise;
  let state;
  const page = new ChatComposerSession();
  const smartUi = new ChatSmartUiSession();
  smartUi.start({}, message => { state.errorMsg = message; });
  const sourceContext = () => ({ conversationId: state.conversation.id, messages: [] });
  const runService = {
    isBusy: () => false,
    append: (id, parts) => { calls.push({ kind: 'append', id, parts }); return append.promise; },
    start: (_id, command) => { calls.push(command); state.sending = true; return { runId: 'run', done: Promise.resolve() }; },
    enqueue: async (_id, parts) => { calls.push({ kind: 'queue', parts }); },
  };
  // The real presentation owns the canonical conversation and restore-version submission guard.
  const submission = productionSubmission({ options, smartUi, runService, conversation: { id: 'c1', messageNodes: [], chatSuggestions: [] } });
  state = submission;
  page.start({
    kvStore: { put: async () => {}, get: async () => null }, runService,
    currentConversation: () => state.conversation, isSending: () => state.sending,
    currentQueue: () => [], currentCompletionNotice: () => state.completionNotice,
    setCompletionNotice: value => { state.completionNotice = value; },
    error: message => { state.errorMsg = message; }, resolveChoice: () => choicePromise,
    loadOcrSeed: async () => null, probeOcrHealth: async () => ({}),
    submit: command => submission.submitConversationRun(command),
    dismissKeyboard() {}, autoScrollEnabled: () => false, jumpToBottom() {}, openDeepRead() {},
    captureContinuation: text => smartUi.captureSmartUiContinuation(text, sourceContext()),
    continuationAvailable: () => smartUi.latestSmartUiContinuationRuntime(sourceContext()) !== undefined,
    continuationCurrent: capture => smartUi.smartUiContinuationCurrent(capture, sourceContext()),
    loadMentionRoles: async () => [], abilityContext: () => ({}),
  });
  page.setInputText('accepted draft');
  return { page, state, gate, append, calls, setChoice: value => { choicePromise = Promise.resolve(value); } };
}
test('UI-02 duplicate click/enter while model preflight is pending dispatches once', async () => {
  const f = composer(); f.page.send(); f.page.send(); f.gate.resolve({ model: {} }); await tick();
  assert.equal(f.calls.length, 1); assert.equal(f.calls[0].kind, 'send');
});
test('UI-02 failed preflight releases sending and allows retry', async () => {
  const f = composer(); f.page.send(); f.gate.resolve(null); await tick();
  assert.equal(f.page.sendPrechecking, false); assert.equal(f.page.inputText, 'accepted draft');
  f.setChoice({ model: {} });
  f.page.send(); await tick(); assert.equal(f.calls.length, 1);
});
test('UI-03 long press consumes only its accepted snapshot after durable append', async () => {
  const f = composer(); f.page.sendWithoutAnswer();
  f.page.setInputText('new draft'); f.page.pendingDocs = [{ url: 'new.pdf', fileName: 'new.pdf' }];
  f.gate.resolve({ model: {} }); await tick();
  assert.equal(f.page.inputText, 'new draft'); assert.equal(f.page.pendingDocs.length, 1);
  f.append.resolve(); await tick();
  assert.equal(f.page.inputText, 'new draft'); assert.equal(f.page.pendingDocs.length, 1);
});
test('UI-03 rejected append retains accepted text and attachments', async () => {
  const f = composer(); f.page.pendingImages = ['accepted.png']; f.page.sendWithoutAnswer();
  f.gate.resolve({ model: {} }); await tick(); f.append.reject(new Error('disk_full')); await tick();
  assert.equal(f.page.inputText, 'accepted draft'); assert.deepEqual(f.page.pendingImages, ['accepted.png']);
});
test('UI-03 leaving during preflight does not append the captured message', async () => {
  const f = composer(); f.page.sendWithoutAnswer(); f.page.hide();
  f.gate.resolve({ model: {} }); await tick(); assert.equal(f.calls.length, 0);
  assert.equal(f.page.sendPrechecking, false);
});
test('ordinary send and edit keep draft when restore-version guard rejects service start', async () => {
  for (const editing of [false, true]) {
    const f = composer({ rejectSubmission: true }); if (editing) f.page.editingNodeId = 'user-node';
    f.page.send(); f.gate.resolve({ model: {} }); await tick();
    assert.equal(f.page.inputText, 'accepted draft');
  }
});
function provider(options = {}) {
  let stored = options.newProvider ? [] : [{ id: 'oauth', type: 'openai', apiKey: 'new-token', models: [] }];
  const navigation = [], toasts = [], providerRead = deferred();
  const loadProviders = () => options.delayed ? providerRead.promise : Promise.resolve(stored);
  const { ProviderEditSession } = loadProviderSessions({
    loadProviders, saveProviders: async (_kv, list) => { stored = list; },
    makeProviderSettingGoogle: x => ({ type: 'google', ...x }), makeProviderSettingClaude: x => ({ type: 'claude', ...x }),
    makeProviderSettingOpenAIVariant: x => ({ type: 'openai', ...x }), openAIAuthModeFixedBaseUrl: () => null,
  });
  const edit = new ProviderEditSession();
  edit.start({ kvStore: {}, catalogModel: () => undefined,
    loadCodexTokens: async () => ({ accessToken: options.codexToken ?? 'new-token' }),
    loadGrokTokens: async () => ({ accessToken: options.grokToken ?? 'new-token' }),
  });
  Object.assign(edit, { provider: { id: 'oauth', type: 'openai', apiKey: '', models: [] },
    authMode: options.authMode ?? 'codex_oauth', apiKey: '', name: 'OAuth', baseUrl: 'https://oauth',
    chatPath: '/chat/completions', providerEnabled: true, isNew: options.newProvider ?? false });
  const page = providerPage(['openModelParameters'], { getChatKvStore: () => ({}), loadProviders,
    router: { pushUrl: route => navigation.push(route) }, promptAction: { showToast: t => toasts.push(t) },
  }, { edit });
  return { page, edit, stored: () => stored, navigation, toasts, providerRead };
}
for (const mode of ['codex_oauth', 'grok_oauth']) {
  for (const newProvider of [false, true]) {
    test('UI-01 ' + mode + ' save preserves current access token (new=' + newProvider + ')', async () => {
      const f = provider({ authMode: mode, newProvider }); await f.edit.save(); await tick();
      assert.equal(f.stored()[0].apiKey, 'new-token');
    });
  }
}
test('UI-08 new provider model parameters require explicit save', async () => {
  const f = provider({ newProvider: true }); await f.page.openModelParameters('model'); await tick();
  assert.equal(f.navigation.length, 0);
  assert.match(f.edit.savedMsg + f.toasts.map(t => t.message).join(' '), /保存/);
});
test('UI-08 saved provider parameters retain the ordinary navigation', async () => {
  const f = provider(); await f.page.openModelParameters('model'); await tick();
  assert.equal(f.navigation.length, 1); assert.equal(f.navigation[0].params.modelId, 'model');
});
test('UI-04 notification entry cannot override an explicit branch switch', async () => {
  let active = 'A'; const calls = [];
  const creation = { open: async () => ({ messages: [] }), activeRun: () => null,
    readOrdinaryRun: async () => ({ record: null, active: false, retryAllowed: false, resumeAllowed: false }),
    stopActiveRun: async () => {},
    workspaceStatus: async () => ({ activeBranchId: active }),
    switchBranch: async (_id, branch) => { active = branch; calls.push(branch); },
    ghostwriteJob: async () => null, latestGhostwriteReport: async () => null,
    polishJob: async (_id, job) => job ? ({ jobId: job, branchId: 'A', stage: 'completed' }) : null,
    validateGhostwriteModels: async () => {} };
  const loadNovelOwner = name => {
    const ownerSource = fs.readFileSync(path.join(root, '../platform_impl/novel', `${name}.ets`), 'utf8');
    const code = ts.transpileModule(ownerSource, {
      compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
    }).outputText;
    const exported = {};
    new Function('exports', 'require', 'Observed', 'Scroller', code)(exported, dependency => {
      if (dependency === '@amber/deepread-domain') return require('../../../deepread/src/main/ets/novel/models.ts');
      if (dependency === '@amber/chat-domain') return {};
      if (dependency.endsWith('/ProductIdentity.ets')) return { getProductKind: () => 'novel' };
      if (dependency.endsWith('/NovelComposerState')) return require('../main/ets/novel/NovelComposerState.ts');
      if (dependency === '@kit.ArkUI') return { router: {}, promptAction: { showToast() {} } };
      if (dependency === '@kit.BasicServicesKit' || dependency.endsWith('/NativeTimelineFollow.ets') ||
        dependency.endsWith('/NovelArchivePresentation.ets') || dependency.endsWith('/NovelSettingProposalProjection.ets')) return {};
      throw Error('Unexpected novel owner dependency: ' + dependency);
    }, constructor => constructor, class {});
    return new exported[name]();
  };
  const page = actualPage('NovelWorkspacePage', ['reload', 'selectWorkspaceBranch', 'stopAndSwitchWorkspaceBranch',
    'clearStudioFeedback', 'hasBlockingNovelRun'], {
    getNovelCreation: () => creation, composerOwner: (p, b) => JSON.stringify([p, b]),
    promptAction: { showToast() {} }, getProductKind: () => 'novel',
  });
  Object.assign(page, { projectId: 'P', requestedBranchId: 'A', requestedPolishJobId: 'old', reloadToken: 0,
    pageAlive: true, busy: false, project: null, workspaceStatus: { activeBranchId: 'A' },
    studioVisibleCycle: 0, collectionStamp: 0,
    discussion: loadNovelOwner('NovelDiscussionSession'), jobs: loadNovelOwner('NovelJobSession'),
    manuscript: { clearCollectionFeedback() {} }, compendium: { settingTab: 0 },
    showWorkspaceOperationError: (_label, error) => { throw error; },
  });
  const commonPorts = {
    creation, projectId: () => page.projectId, currentProject: () => page.project,
    currentStatus: () => page.workspaceStatus, isBusy: () => page.busy,
    error: value => { page.errorMsg = value; },
    hasBlockingNovelRun: () => page.hasBlockingNovelRun(), reload: () => page.reload(),
  };
  page.discussion.start({ ...commonPorts,
    composerDraftStore: { load: async () => ({ text: '', mode: 'whole_chapter' }), save: async () => {} },
    setBusy: value => { page.busy = value; }, operationError: page.showWorkspaceOperationError,
    jobLocksWorkspace: () => page.jobs.novelJobLocksWorkspace(),
    currentGhostwriteJob: () => page.jobs.ghostwriteJob, currentGhostwriteReport: () => page.jobs.ghostwriteReport,
    reloadToken: () => page.reloadToken, selectedTab: () => 0, backgrounded: () => false,
    closeControl() {}, uiContext: () => ({}),
  });
  page.jobs.start({ ...commonPorts,
    requestedPolishJobId: () => page.requestedPolishJobId,
    clearRequestedPolishJob: () => { page.requestedPolishJobId = ''; },
    hasOrdinaryRun: () => page.discussion.activeRunId.length > 0,
    publishWorkspace: (project, status) => { page.project = project; page.workspaceStatus = status; },
    operationError: page.showWorkspaceOperationError, closeControl() {}, showJobSettings() {},
  });
  await page.reload(); assert.equal(page.jobs.polishJob.jobId, 'old');
  await page.selectWorkspaceBranch('B'); assert.equal(active, 'B');
  assert.equal(page.workspaceStatus.activeBranchId, 'B'); assert.deepEqual(calls, ['B']);
});
test('UI-05 reverse title search completion cannot overwrite the current query', async () => {
  const old = deferred(), fresh = deferred();
  const Drawer = loadDrawerSession();
  const page = new Drawer();
  page.start({ repository: { search: q => (q === 'old' ? old : fresh).promise, searchMessages: async () => [] },
    onError: message => assert.fail(message) });
  page.refreshDrawerConversations('old'); page.refreshDrawerConversations('new');
  fresh.resolve([{ id: 'new-hit' }]); await tick(); old.resolve([{ id: 'old-hit' }]); await tick();
  assert.equal(page.drawerSearchText, 'new'); assert.equal(page.drawerConversations[0].id, 'new-hit');
});
test('UI-07 returning from the reader refreshes history completion state', async () => {
  require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
  const { observeDeepReadLibrary } = require('../../../deepread/src/main/ets/domain/library.ts');
  let complete = false;
  const limits = [];
  const currentEntries = () => [{ topicId: 'a', output: { generationComplete: complete } }];
  const page = actualPage('DeepReadHistoryPage',
    ['aboutToAppear', 'onPageShow', 'onPageHide', 'leaveVisible', 'settleArrival', 'settleEmptyFeedback', 'stopObservation', 'startObservation',
      'attachObservation', 'connectScheduler', 'reloadHistory', 'loadHistorySnapshot'], {
      getRepository: () => ({ observeHistory: () => ({ subscribe: callback => { callback(currentEntries()); return () => {}; } }), listHistory: async limit => {
        limits.push(limit);
        return currentEntries();
      } }),
      getDeepReadScheduler: async () => ({ getActiveRuns: () => [], observeActiveRuns: () => ({ subscribe: callback => { callback([]); return () => {}; } }) }),
      observeDeepReadLibrary, KeyboardAvoidMode: { OFFSET: 'offset', RESIZE: 'resize' }, getProductKind: () => 'agent',
    });
  Object.assign(page, { pageAlive: false, pageVisible: false, loadToken: 0, observationToken: 0, unsubscribeLibrary: null,
    entries: [], activeRuns: [], loaded: false, loadError: '', runtimeError: '', keyboardAdjusted: false,
    arrivalTimer: -1, arrivalOffset: 0, emptyBounceTimer: -1, emptyBounceId: 0, emptyIconScale: 1,
    getUIContext: () => ({ getKeyboardAvoidMode: () => 'offset', setKeyboardAvoidMode() {} }) });
  page.aboutToAppear(); page.onPageShow(); await tick();
  assert.equal(page.entries[0].output.generationComplete, false);
  page.onPageHide(); complete = true; page.onPageShow(); await tick();
  assert.equal(page.entries[0].output.generationComplete, true);
  assert.equal(page.loaded, true);
  assert.equal(page.loadError, '');
  assert.deepEqual(limits, [0, 0], 'every entry reads the complete persisted library');
});

test('UI-03 edit-mode long press preserves new draft typed during durable append', async () => {
  const f = composer(); f.page.editingNodeId = 'user-node'; f.page.editingMessageId = 'old-message';
  const pending = f.page.sendWithoutAnswer(); f.gate.resolve({ model: {} }); await tick();
  f.page.setInputText('new draft during append'); f.append.resolve(); await pending;
  assert.equal(f.page.inputText, 'new draft during append');
  assert.equal(f.page.editingNodeId, ''); assert.equal(f.page.editingMessageId, '');
});

for (const sending of ['send', 'sendWithoutAnswer']) {
  test('UI-03 ' + sending + ' cannot apply old input to a newly selected variant in the same node', async () => {
    const f = composer(); f.page.editingNodeId = 'user-node'; f.page.editingMessageId = 'old-message';
    const pending = f.page[sending]();
    f.page.startEditMessage({ nodeId: 'user-node', message: { id: 'new-message', parts: [{ type: 'text', text: 'new variant' }] } });
    f.gate.resolve({ model: {} }); f.append.resolve(); await pending;
    assert.equal(f.calls.length, 0); assert.equal(f.page.inputText, 'new variant');
    assert.equal(f.page.editingMessageId, 'new-message');
  });
}

test('UI-01 auth-mode changes during save cannot change the captured payload token family', async () => {
  const f = provider({ delayed: true, codexToken: 'codex-current', grokToken: 'grok-current' });
  const pending = f.edit.save(); f.edit.authMode = 'grok_oauth'; f.providerRead.resolve(f.stored()); await pending;
  assert.equal(f.stored()[0].authMode, 'codex_oauth'); assert.equal(f.stored()[0].apiKey, 'codex-current');
});

function loginFixture(mode) {
  const requests = [], polls = [], exchanges = [], tokenLoads = [];
  let saves = 0, catalogLoads = 0;
  const { ProviderEditSession, ProviderLoginSession } = loadProviderSessions({
    openAIAuthModeFixedBaseUrl: m => m === 'api_key' ? null : 'https://oauth.test',
  });
  const edit = new ProviderEditSession(), page = new ProviderLoginSession();
  edit.start({ onProviderApplied: provider => page.loadAccount(provider) });
  page.start({
    currentContext: () => ({ provider: edit.provider, authMode: edit.authMode, baseUrl: edit.baseUrl }),
    setAuthMode: value => { edit.authMode = value; }, setApiKey: value => { edit.apiKey = value; },
    setBaseUrl: value => { edit.baseUrl = value; }, setUseResponseApi: value => { edit.useResponseApi = value; },
    setModelsText: value => { edit.modelsText = value; }, setUnsupportedAuth: value => { edit.unsupportedAuthMsg = value; },
    requestCodexDeviceCode: () => { const d = deferred(); requests.push(d); return d.promise; },
    pollCodexDeviceCode: (_id, _auth, shouldCancel) => { const d = deferred(); d.shouldCancel = shouldCancel; polls.push(d); return d.promise; },
    exchangeGrokCode: (_id, _code, _verifier, shouldCancel) => { const d = deferred(); d.shouldCancel = shouldCancel; exchanges.push(d); return d.promise; },
    fetchCodexModels: async () => { catalogLoads++; return [{ modelId: 'codex' }]; },
    extractGrokCode: () => 'code', grokFallbackModels: [{ modelId: 'grok' }],
    buildGrokAuthorization: () => ({ codeVerifier: 'new-verifier' }),
    abilityContext: () => ({ startAbility: async () => {} }),
    loadCodexTokens: () => { const d = deferred(); tokenLoads.push(d); return d.promise; },
    loadGrokTokens: () => { const d = deferred(); tokenLoads.push(d); return d.promise; },
    save: () => { saves++; return Promise.resolve(true); },
  });
  Object.assign(edit, { provider: { id: 'provider', type: 'openai' }, authMode: mode, apiKey: '', baseUrl: 'https://oauth.test' });
  Object.assign(page, { grokLoginOpen: true, grokAuth: { codeVerifier: 'verifier' },
    grokPasteUrl: 'callback?code=x', authModeAt: () => 'api_key' });
  return { page, edit, requests, polls, exchanges, tokenLoads, saves: () => saves, catalogLoads: () => catalogLoads };
}
test('UI-09 cancelled Grok exchange cannot overwrite a newly selected API-key form', async () => {
  const f = loginFixture('grok_oauth'); f.page.confirmGrokLogin(); f.page.cancelGrokLogin();
  f.page.selectAuthMode(0); f.edit.apiKey = 'author-key'; f.edit.baseUrl = 'https://private.test';
  f.exchanges[0].resolve({ accessToken: 'late-grok', email: 'old' }); await tick();
  assert.equal(f.edit.apiKey, 'author-key'); assert.equal(f.edit.baseUrl, 'https://private.test');
  assert.equal(f.saves(), 0);
});
test('UI-09 switching mode while device-code request is pending cannot reopen Codex login', async () => {
  const f = loginFixture('codex_oauth'); f.page.startCodexLogin(); f.page.selectAuthMode(0);
  f.requests[0].resolve({ userCode: 'late-code' }); await tick();
  assert.equal(f.polls.length, 0); assert.equal(f.page.codexLoginOpen, false);
});
test('UI-09 cancelling then restarting Codex isolates the previous login session', async () => {
  const f = loginFixture('codex_oauth'); f.page.startCodexLogin(); f.page.cancelCodexLogin(); f.page.startCodexLogin();
  f.requests[0].resolve({ userCode: 'old-code' }); await tick();
  f.requests[1].resolve({ userCode: 'new-code' }); await tick();
  assert.equal(f.polls.length, 1); assert.equal(f.page.codexUserCode, 'new-code');
});
test('UI-09 destroyed provider page ignores a late OAuth result', async () => {
  const f = loginFixture('grok_oauth'); f.page.confirmGrokLogin(); f.page.dispose();
  f.exchanges[0].resolve({ accessToken: 'late-grok', email: 'old' }); await tick();
  assert.equal(f.saves(), 0); assert.equal(f.edit.apiKey, '');
});
test('UI-09 current Grok login still applies its token and explicitly saves', async () => {
  const f = loginFixture('grok_oauth'); f.page.confirmGrokLogin();
  f.exchanges[0].resolve({ accessToken: 'current-grok', email: 'current' }); await tick();
  assert.equal(f.edit.apiKey, 'current-grok'); assert.equal(f.saves(), 1);
});
test('UI-09 current Codex login still loads models and saves', async () => {
  const f = loginFixture('codex_oauth'); f.page.startCodexLogin();
  f.requests[0].resolve({ userCode: 'current-code' }); await tick();
  f.polls[0].resolve({ accessToken: 'current-codex', email: 'current' }); await tick();
  assert.equal(f.edit.apiKey, 'current-codex'); assert.equal(f.catalogLoads(), 1); assert.equal(f.saves(), 1);
});
test('UI-09 cancelled Codex token response cannot load models or save', async () => {
  const f = loginFixture('codex_oauth'); f.page.startCodexLogin();
  f.requests[0].resolve({ userCode: 'current-code' }); await tick(); f.page.cancelCodexLogin();
  assert.equal(f.polls[0].shouldCancel(), true);
  f.polls[0].resolve({ accessToken: 'late-codex', email: 'old' }); await tick();
  assert.equal(f.edit.apiKey, ''); assert.equal(f.catalogLoads(), 0); assert.equal(f.saves(), 0);
});

test('UI-09 Grok exchange receives current login cancellation before it can persist tokens', () => {
  const f = loginFixture('grok_oauth'); f.page.confirmGrokLogin();
  assert.equal(typeof f.exchanges[0].shouldCancel, 'function');
  assert.equal(f.exchanges[0].shouldCancel(), false);
  f.page.cancelGrokLogin(); assert.equal(f.exchanges[0].shouldCancel(), true);
});

for (const mode of ['codex_oauth', 'grok_oauth']) {
  const account = mode === 'codex_oauth' ? 'codexAccount' : 'grokAccount';
  const draft = { id: 'provider', type: 'openai', name: 'OAuth', enabled: true, models: [],
    baseUrl: 'https://oauth.test', apiKey: '', authMode: mode, useResponseApi: true,
    chatCompletionsPath: '/chat/completions' };
  test(`UI-09 ${mode} current initial token lookup retains account/error display`, async () => {
    for (const tokens of [null, { email: 'current-account' }]) {
      const f = loginFixture(mode); f.edit.unsupportedAuthMsg = 'previous-error';
      f.edit.applyProvider(draft); f.tokenLoads[0].resolve(tokens); await tick();
      assert.equal(f.page[account], tokens === null ? '' : 'current-account');
      if (tokens === null) assert.match(f.edit.unsupportedAuthMsg, /登录已失效/);
      else assert.equal(f.edit.unsupportedAuthMsg, '');
    }
  });
  test(`UI-09 ${mode} mode change clears stale error while same-mode selection retains it`, async () => {
    const f = loginFixture(mode); f.edit.applyProvider(draft);
    f.tokenLoads[0].resolve(null); await tick();
    f.page.authModeAt = () => mode; f.page.selectAuthMode(0);
    assert.match(f.edit.unsupportedAuthMsg, /登录已失效/);
    f.page.authModeAt = () => 'api_key'; f.page.selectAuthMode(0);
    assert.equal(f.edit.unsupportedAuthMsg, '');
  });
  test(`UI-09 ${mode} initial lookup cannot overwrite a changed mode/session/page`, async () => {
    for (const invalidation of ['mode', 'login', 'leave']) {
      for (const tokens of [null, { email: 'old-account' }]) {
        const f = loginFixture(mode); f.edit.applyProvider(draft);
        if (invalidation === 'mode') f.page.selectAuthMode(0);
        if (invalidation === 'login') {
          if (mode === 'codex_oauth') f.page.startCodexLogin();
          else f.page.startGrokLogin();
        }
        if (invalidation === 'leave') f.page.dispose();
        f.page[account] = 'current-account'; f.edit.unsupportedAuthMsg = 'current-error';
        f.tokenLoads[0].resolve(tokens); await tick();
        assert.equal(f.page[account], 'current-account');
        assert.equal(f.edit.unsupportedAuthMsg, 'current-error');
      }
    }
  });
}
