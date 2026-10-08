// Execute exact production methods. This proves state/lifecycle behavior, not ArkUI geometry.
// Baseline: CHAT_OVERLAY_SOURCE_REF=0271ba191 node --test harmony/tests/chat-overlay-lifecycle.test.cjs
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../chat/node_modules/typescript');
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
const makeChat = productionClass('pages/ChatPage.ets', [
  'openDrawer', 'closeDrawer', 'openDrawerConversation', 'closeModelMenu', 'toggleModelMenu', 'onBackPress',
  'openSubAgentSheet', 'closeSubAgentSheet', 'onPageHide', 'aboutToDisappear',
  'hideScrollNavigation', 'restoreKeyboardAvoidance', 'syncStreamingFrameRate', 'closeRecap',
]);
function chat({ loadProviders = () => Promise.resolve([]), resolveChoice = () => Promise.resolve(null),
  getManager = () => Promise.reject(new Error('unexpected manager request')) } = {}) {
  const page = makeChat({
    animateTo: (_options, mutation) => mutation(), Curve: { EaseOut: 'EaseOut' },
    loadProviders, getChatKvStore: () => ({}), getSubAgentManager: getManager,
    resolveEffectiveChatChoice: resolveChoice, buildTopModelMenuGroups: () => [],
    hilog: { warn() {} }, HILOG_DOMAIN: 1, HILOG_TAG: 'test', clearInterval, clearTimeout,
    inputMethod: { getController: () => ({ stopInputSession: () => Promise.resolve() }) },
    router: { replaceUrl() { throw new Error('Same conversation must not navigate'); } },
  });
  Object.assign(page, {
    pageAlive: true, pageVisible: true, conversation: { id: 'same' },
    imeListenerAttached: true, imeKeyboardHeightVp: 240, modelMenuOpen: false,
    modelMenuLoading: false, modelMenuSeq: 0, drawerOpen: false,
    exportSheetOpen: false, documentPreviewOpen: false, denyDialogOpen: false, contextMeterOpen: false,
    subAgentSheetSeq: 0, subAgentSheetOpen: false, subAgentSheetRunId: '', subAgentSheetTools: [],
    subAgentLiveText: '', unsubSubAgentLive: null,
    runConversationId: '', contextChoiceSeq: 0, nativeContextSeq: 0, webmountHookBinding: null,
    greetTimer: -1, cursorTimer: -1,
    scrollNavigationHideTimer: -1, scrollNavigationVisible: false, keyboardAvoidanceActive: false,
    appBackgrounded: false, sending: false, streamingFrameRate: { update() {} }, nativeFollow: null,
    recapOpen: false, recapSourceLoading: false, recapOpenSeq: 0,
    refreshDrawerConversations() {}, detachConversationRun() {}, flushInputDraft() {},
    invalidatePicker() {}, stopGreetTimer() {},
    detachKeyboardListener() { this.imeListenerAttached = false; },
    attachKeyboardListener() { this.imeListenerAttached = true; },
  });
  return page;
}

test('export consumes back before the underlying drawer', () => {
  const page = chat(); page.exportSheetOpen = true; page.drawerOpen = true;
  assert.equal(page.onBackPress(), true);
  assert.equal(page.exportSheetOpen, false); assert.equal(page.drawerOpen, true);
});
test('drawer current conversation restores keyboard listener', () => {
  const page = chat(); page.openDrawer(); assert.equal(page.imeListenerAttached, false);
  page.openDrawerConversation('same');
  assert.equal(page.drawerOpen, false); assert.equal(page.imeListenerAttached, true);
});
for (const stage of ['providers', 'effective choice']) {
  test(`pending model ${stage} result cannot reopen after drawer supersedes it`, async () => {
    const request = deferred();
    const page = stage === 'providers' ? chat({ loadProviders: () => request.promise })
      : chat({ resolveChoice: () => request.promise });
    page.toggleModelMenu(); await settle(); page.openDrawer();
    request.resolve(stage === 'providers' ? [] : null); await settle();
    assert.equal(page.drawerOpen, true); assert.equal(page.modelMenuOpen, false);
    assert.equal(page.modelMenuLoading, false);
  });
}
for (const action of ['second tap', 'back']) {
  test(`${action} cancels a pending model menu opening`, async () => {
    const request = deferred(); const page = chat({ loadProviders: () => request.promise });
    page.toggleModelMenu();
    if (action === 'back') assert.equal(page.onBackPress(), true);
    else page.toggleModelMenu();
    request.resolve([]); await settle();
    assert.equal(page.modelMenuOpen, false); assert.equal(page.modelMenuLoading, false);
  });
}
test('old rejected model request cannot clear newer loading state', async () => {
  const requests = [deferred(), deferred()]; let next = 0;
  const page = chat({ loadProviders: () => requests[next++].promise });
  page.toggleModelMenu(); page.closeModelMenu(); page.toggleModelMenu();
  assert.equal(next, 2); requests[0].reject(new Error('old request failed')); await settle();
  assert.equal(page.modelMenuLoading, true);
  requests[1].resolve([]); await settle(); assert.equal(page.modelMenuOpen, true);
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
    const opening = page.openSubAgentSheet('A', []); page[close]();
    request.resolve(service); await opening;
    assert.equal(page.subAgentSheetOpen, false); assert.equal(service.subscribed.length, 0);
    assert.equal(page.unsubSubAgentLive, null);
  });
}
test('late SubAgent initialization cannot replace a newer run subscription', async () => {
  const requests = [deferred(), deferred()], service = manager(); let next = 0;
  const page = chat({ getManager: () => requests[next++].promise });
  const first = page.openSubAgentSheet('A', []), second = page.openSubAgentSheet('B', []);
  requests[1].resolve(service); await second; requests[0].resolve(service); await first;
  assert.equal(page.subAgentSheetRunId, 'B'); assert.equal(page.subAgentLiveText, 'snapshot:B');
  assert.deepEqual([...service.active.keys()], ['B']);
  page.closeSubAgentSheet(); assert.equal(service.active.size, 0);
});
test('opening a new SubAgent run releases the prior live subscription', async () => {
  const service = manager(); const page = chat({ getManager: async () => service });
  await page.openSubAgentSheet('A', []); await page.openSubAgentSheet('B', []);
  assert.deepEqual([...service.active.keys()], ['B']);
  service.deliveries.get('A')('late:A');
  assert.equal(page.subAgentLiveText, 'snapshot:B');
  page.closeSubAgentSheet(); assert.equal(service.active.size, 0);
});
test('SubAgent initialization failure after closing cannot reopen fallback sheet', async () => {
  const request = deferred(); const page = chat({ getManager: () => request.promise });
  const opening = page.openSubAgentSheet('A', []); page.closeSubAgentSheet();
  request.reject(new Error('old initialization failed')); await opening;
  assert.equal(page.subAgentSheetOpen, false);
});

const makeMiniApp = productionClass('components/MiniAppChatCard.ets', ['restoreVersion', 'aboutToDisappear']);
function miniApp() {
  const request = deferred(), notices = [], target = { app: { id: 'app', version: 3 }, versions: [] };
  const card = makeMiniApp({});
  Object.assign(card, { alive: true, versionTarget: target,
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
