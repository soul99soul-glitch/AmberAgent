// Execute exact production methods. This proves state/lifecycle behavior, not ArkUI geometry.
// Optional CHAT_OVERLAY_SOURCE_REF must include the extracted owner architecture.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../chat/node_modules/typescript');
const { loadChatSmartUiSession } = require('../entry/src/test/chat_smart_ui_fixture.cjs');
const ChatSmartUiSession = loadChatSmartUiSession({});
const root = path.resolve(__dirname, '../..');
const revision = process.env.CHAT_OVERLAY_SOURCE_REF || process.argv[2];

function productionClass(relativePath, methodNames) {
  const sourcePath = `harmony/entry/src/main/ets/${relativePath}`;
  const source = revision
    ? require('node:child_process').execFileSync('git', ['show', `${revision}:${sourcePath}`], { cwd: root, encoding: 'utf8' })
    : fs.readFileSync(path.join(root, sourcePath), 'utf8');
  const extracted = [];
  for (const name of methodNames) {
    const match = new RegExp(`^  (?:private )?(?:async )?${name}\\(`, 'm').exec(source);
    assert.ok(match, `Missing production method ${name}`);
    const start = match.index;
    const body = source.indexOf('{', start);
    let depth = 1, end = body + 1;
    // Selected non-UI methods have balanced braces in strings/comments. No method body is rewritten.
    while (depth > 0 && end < source.length) {
      if (source[end] === '{') depth++;
      if (source[end] === '}') depth--;
      end++;
    }
    assert.equal(depth, 0);
    extracted.push(source.slice(start, end));
    console.log(`EXTRACT ${revision || 'working-tree'}:${relativePath}:${source.slice(0, start).split('\n').length}-${source.slice(0, end).split('\n').length} ${name}`);
  }
  const js = ts.transpileModule(`class Subject {\n${extracted.join('\n')}\n}\nglobalThis.Subject = Subject;`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None }, reportDiagnostics: true,
  });
  assert.equal(js.diagnostics.length, 0);
  return dependencies => {
    const context = vm.createContext(dependencies);
    vm.runInContext(js.outputText, context);
    return new context.Subject();
  };
}
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
const settle = () => new Promise(resolve => setImmediate(resolve));
function productionOwner(filename, name, dependencies = {}, globals = {}) {
  const sourcePath = `harmony/entry/src/main/ets/platform_impl/chat/${filename}`;
  const source = revision
    ? require('node:child_process').execFileSync('git', ['show', `${revision}:${sourcePath}`], { cwd: root, encoding: 'utf8' })
    : fs.readFileSync(path.join(root, sourcePath), 'utf8');
  const js = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  const exports = {};
  const context = vm.createContext({ exports, Observed: constructor => constructor,
    ...globals, require: name => {
      assert.ok(Object.hasOwn(dependencies, name), `Unexpected owner dependency: ${name}`);
      return dependencies[name];
    } });
  vm.runInContext(js, context);
  return new exports[name]();
}
const makeChat = productionClass('pages/ChatPage.ets', [
  'onBackPress', 'onPageHide', 'aboutToDisappear', 'syncStreamingFrameRate', 'stopGreetTimer',
]);
function chat({ loadProviders = () => Promise.resolve([]), resolveChoice = () => Promise.resolve(null),
  getManager = () => Promise.reject(new Error('unexpected manager request')) } = {}) {
  const page = makeChat({
    AppStorage: { get: () => undefined, setOrCreate: () => {} },
    clearInterval, clearTimeout,
    getConversationRunService: () => ({ discardNewConversation() {} }),
  });
  Object.assign(page, {
    pageAlive: true, pageVisible: true, appBackgrounded: false, greetTimer: -1, cursorTimer: -1,
  });
  const logger = { hilog: { warn() {} } };
  const domain = { loadProviders, buildTopModelMenuGroups: () => [], makeDisplaySetting: () => ({}),
    createInitialTimelineLoadState: () => ({ initialized: false, fullyLoaded: false }) };
  const animation = { animateTo: (_options, mutation) => mutation(), Curve: { EaseOut: 'EaseOut' } };
  const inputMethod = { getController: () => ({ stopInputSession: () => Promise.resolve() }) };
  page.presentation = productionOwner('ChatConversationPresentation.ets', 'ChatConversationPresentation', {
    '@kit.ArkUI': { promptAction: {} }, '@kit.IMEKit': { inputMethod }, '@kit.SensorServiceKit': {},
    '@kit.PerformanceAnalysisKit': logger, '@amber/chat-domain': domain, '../ConversationRunGuard.ets': {},
    '../../components/StreamingFrameRate.ets': { StreamingFrameRate: class { update() {} } },
    './ChatCompactionSupport.ets': {},
  });
  page.presentation.start({ runService: { discardNewConversation() {} } });
  page.presentation.conversation = { id: 'same', messageNodes: [] };
  page.modelContext = productionOwner('ChatModelContextSession.ets', 'ChatModelContextSession', {
    '@kit.PerformanceAnalysisKit': logger, '@kit.ArkUI': { promptAction: {} },
    '@amber/chat-domain': domain, '../NativeTokenCounter.ets': {}, '../../design/tokens.ets': {},
  }, animation);
  page.modelContext.start({ currentConversation: () => page.presentation.conversation, error() {}, kvStore: {},
    compactStore: {}, getAssistants: async () => [], resolveChoice, saveChoice: async () => {},
    mutateAssistant: async () => null, compact: async () => ({}) });
  const win = { on() {}, off() {} };
  page.timeline = productionOwner('ChatTimelineSession.ets', 'ChatTimelineSession', {
    '@kit.ArkUI': { window: { getLastWindow: async () => win }, KeyboardAvoidMode: { OFFSET: 0 } },
    '@kit.IMEKit': { inputMethod }, '@kit.PerformanceAnalysisKit': logger, '@amber/chat-domain': domain,
    '../../components/NativeTimelineFollow.ets': {},
    './ChatTimelineDataSource.ets': { ChatTimelineDataSource: class { clear() {} } },
  }, { Scroller: class {}, clearTimeout, setTimeout, ...animation });
  page.timeline.start({ getHostContext: () => ({}), setKeyboardAvoidMode() {} }, {}, {
    currentConversation: () => page.presentation.conversation, isSending: () => page.presentation.sending,
  });
  page.timeline.imeListenerAttached = true; page.timeline.imeWindow = win; page.timeline.imeKeyboardHeightVp = 240;
  page.runDetails = productionOwner('ChatRunDetailsSession.ets', 'ChatRunDetailsSession', {
    '@kit.PerformanceAnalysisKit': logger, '@amber/chat-domain': domain,
    '../WebMountSession.ets': { getWebMountSession: () => ({ bindHooks: () => ({ unbind() {} }) }) },
    '../JevApprovalTriage.ets': {},
  });
  page.runDetails.start({ loadSubAgentManager: getManager, readSetting: async () => null,
    runSnapshot: () => null, currentConversation: () => page.presentation.conversation,
    currentStreamingTail: () => [], currentRegenTail: () => null, sending: () => page.presentation.sending,
    error() {}, stop() {}, submitVerdict: () => false });
  page.drawer = productionOwner('ChatConversationDrawerSession.ets', 'ChatConversationDrawerSession', {
    '@amber/chat-domain': domain, '../ConversationRunGuard.ets': {}, '../../design/tokens.ets': {},
  });
  page.drawer.start({ repository: { list: async () => [] }, currentConversationId: () => page.presentation.conversation?.id ?? '',
    closeModelMenu: () => page.modelContext.closeModelMenu(),
    suspendIME: () => {
      page.timeline.detachKeyboardListener(); page.timeline.imeKeyboardHeightVp = 0;
      inputMethod.getController().stopInputSession().catch(() => {});
    },
    resumeIME: () => { if (page.pageVisible) page.timeline.attachKeyboardListener(); },
    onNavigate() { throw new Error('Same conversation must not navigate'); },
  });
  page.messageActions = productionOwner('ChatMessageActionsSession.ets', 'ChatMessageActionsSession', {
    '@kit.ArkUI': { promptAction: {} }, '@kit.BasicServicesKit': {}, '@kit.ShareKit': {}, '@kit.ArkData': {},
    '@kit.PerformanceAnalysisKit': logger, '@amber/chat-domain': domain, '../../design/tokens.ets': {},
    './ChatCompactionSupport.ets': {},
  });
  page.messageActions.start({ currentConversation: () => page.presentation.conversation });
  page.artifacts = productionOwner('ChatArtifactSession.ets', 'ChatArtifactSession', {
    '@amber/chat-domain': domain, '../DocumentSupport.ets': {},
  });
  page.artifacts.start({}, {}, { currentConversation: () => page.presentation.conversation });
  page.composer = productionOwner('ChatComposerSession.ets', 'ChatComposerSession', {
    '@kit.ArkUI': { promptAction: {} }, '@kit.CoreFileKit': {}, '@kit.CameraKit': {},
    '@kit.MediaLibraryKit': {}, '@kit.ArkTS': {}, '@kit.PerformanceAnalysisKit': logger,
    '@amber/chat-domain': domain, '../ImageCompress.ets': {}, '../EntryImageEncoder.ets': {},
    '../EntryLocalToolPorts.ets': {}, './ChatAttachmentIO.ets': {},
  }, { clearTimeout, setTimeout });
  page.composer.start({ currentConversation: () => page.presentation.conversation, isSending: () => page.presentation.sending,
    kvStore: { put: async () => {} } });
  page.smartUi = new ChatSmartUiSession();
  page.smartUi.start({ flush: async () => {} }, () => {});
  return page;
}

test('export consumes back before the underlying drawer', () => {
  const page = chat(); page.messageActions.exportSheetOpen = true; page.drawer.drawerOpen = true;
  assert.equal(page.onBackPress(), true);
  assert.equal(page.messageActions.exportSheetOpen, false); assert.equal(page.drawer.drawerOpen, true);
});
test('drawer current conversation restores keyboard listener', async () => {
  const page = chat(); page.drawer.openDrawer(); assert.equal(page.timeline.imeListenerAttached, false);
  page.drawer.openDrawerConversation('same'); await settle();
  assert.equal(page.drawer.drawerOpen, false); assert.equal(page.timeline.imeListenerAttached, true);
});
for (const stage of ['providers', 'effective choice']) {
  test(`pending model ${stage} result cannot reopen after drawer supersedes it`, async () => {
    const request = deferred();
    const page = stage === 'providers' ? chat({ loadProviders: () => request.promise })
      : chat({ resolveChoice: () => request.promise });
    page.modelContext.toggleModelMenu(); await settle(); page.drawer.openDrawer();
    request.resolve(stage === 'providers' ? [] : null); await settle();
    assert.equal(page.drawer.drawerOpen, true); assert.equal(page.modelContext.modelMenuOpen, false);
    assert.equal(page.modelContext.modelMenuLoading, false);
  });
}
for (const action of ['second tap', 'back']) {
  test(`${action} cancels a pending model menu opening`, async () => {
    const request = deferred(); const page = chat({ loadProviders: () => request.promise });
    page.modelContext.toggleModelMenu();
    if (action === 'back') assert.equal(page.onBackPress(), true);
    else page.modelContext.toggleModelMenu();
    request.resolve([]); await settle();
    assert.equal(page.modelContext.modelMenuOpen, false); assert.equal(page.modelContext.modelMenuLoading, false);
  });
}
test('old rejected model request cannot clear newer loading state', async () => {
  const requests = [deferred(), deferred()]; let next = 0;
  const page = chat({ loadProviders: () => requests[next++].promise });
  page.modelContext.toggleModelMenu(); page.modelContext.closeModelMenu(); page.modelContext.toggleModelMenu();
  assert.equal(next, 2); requests[0].reject(new Error('old request failed')); await settle();
  assert.equal(page.modelContext.modelMenuLoading, true);
  requests[1].resolve([]); await settle(); assert.equal(page.modelContext.modelMenuOpen, true);
});

function manager() {
  const active = new Map(), subscribed = [], deliveries = new Map();
  return { active, subscribed, deliveries,
    subscribeLiveText(runId, listener) {
      subscribed.push(runId); active.set(runId, listener); deliveries.set(runId, listener);
      listener(`live:${runId}`);
      return () => active.delete(runId);
    },
    snapshot: runId => ({ displayText: `snapshot:${runId}` }),
  };
}
for (const close of ['closeSubAgentSheet', 'onPageHide', 'aboutToDisappear']) {
  test(`SubAgent pending open is invalidated by production ${close}`, async () => {
    const request = deferred(), service = manager(); const page = chat({ getManager: () => request.promise });
    const opening = page.runDetails.openSubAgentSheet('A', []); if (close === 'closeSubAgentSheet') page.runDetails.closeSubAgentSheet(); else page[close]();
    request.resolve(service); await opening;
    assert.equal(page.runDetails.subAgentSheetOpen, false); assert.equal(service.subscribed.length, 0);
    assert.equal(page.runDetails.unsubSubAgentLive, null);
  });
}
test('late SubAgent initialization cannot replace a newer run subscription', async () => {
  const requests = [deferred(), deferred()], service = manager(); let next = 0;
  const page = chat({ getManager: () => requests[next++].promise });
  const first = page.runDetails.openSubAgentSheet('A', []), second = page.runDetails.openSubAgentSheet('B', []);
  requests[1].resolve(service); await second; requests[0].resolve(service); await first;
  assert.equal(page.runDetails.subAgentSheetRunId, 'B'); assert.equal(page.runDetails.subAgentLiveText, 'snapshot:B');
  assert.deepEqual([...service.active.keys()], ['B']);
  page.runDetails.closeSubAgentSheet(); assert.equal(service.active.size, 0);
});
test('opening a new SubAgent run releases the prior live subscription', async () => {
  const service = manager(); const page = chat({ getManager: async () => service });
  await page.runDetails.openSubAgentSheet('A', []); await page.runDetails.openSubAgentSheet('B', []);
  assert.deepEqual([...service.active.keys()], ['B']);
  service.deliveries.get('A')('late:A');
  assert.equal(page.runDetails.subAgentLiveText, 'snapshot:B');
  page.runDetails.closeSubAgentSheet(); assert.equal(service.active.size, 0);
});
test('SubAgent initialization failure after closing cannot reopen fallback sheet', async () => {
  const request = deferred(); const page = chat({ getManager: () => request.promise });
  const opening = page.runDetails.openSubAgentSheet('A', []); page.runDetails.closeSubAgentSheet();
  request.reject(new Error('old initialization failed')); await opening;
  assert.equal(page.runDetails.subAgentSheetOpen, false);
});

const makeMiniApp = productionClass('components/MiniAppChatCard.ets', ['restoreVersion', 'aboutToDisappear', 'saveViewState']);
function miniApp() {
  const request = deferred(), notices = [], target = { app: { id: 'app', version: 3 }, versions: [] };
  const card = makeMiniApp({});
  Object.assign(card, { alive: true, viewState: null, versionTarget: target, part: { appId: 'app' }, previewRevision: 0,
    platform: { repository: { restoreVersion: () => request.promise } }, toast: message => notices.push(message) });
  return { card, target, request, notices };
}
for (const result of ['resolve', 'reject']) {
  test(`MiniApp old restore ${result} cannot close or annotate a reopened version dialog`, async () => {
    const { card, target, request, notices } = miniApp();
    card.restoreVersion(target, { versionNumber: 1 });
    card.versionTarget = null;
    const reopened = { app: { id: 'app', version: 4 }, versions: [] }; card.versionTarget = reopened;
    request[result](result === 'resolve' ? { id: 'app', version: 4 } : new Error('old restore failed'));
    await settle(); assert.equal(card.versionTarget, reopened); assert.deepEqual(notices, []);
  });
  test(`MiniApp restore ${result} after component disposal has no UI effects`, async () => {
    const { card, target, request, notices } = miniApp();
    card.restoreVersion(target, { versionNumber: 1 }); card.aboutToDisappear();
    request[result](result === 'resolve' ? { id: 'app', version: 4 } : new Error('old restore failed'));
    await settle(); assert.equal(card.versionTarget, target); assert.deepEqual(notices, []);
  });
}
test('MiniApp current restore success still closes its own dialog', async () => {
  const { card, target, request } = miniApp(); card.restoreVersion(target, { versionNumber: 1 });
  request.resolve({ id: 'app', version: 4 }); await settle(); assert.equal(card.versionTarget, null);
});
test('MiniApp current restore failure remains visible to its own dialog', async () => {
  const { card, target, request, notices } = miniApp(); card.restoreVersion(target, { versionNumber: 1 });
  request.reject(new Error('write failed')); await settle();
  assert.equal(card.versionTarget, target); assert.deepEqual(notices, ['恢复失败:write failed']);
});
