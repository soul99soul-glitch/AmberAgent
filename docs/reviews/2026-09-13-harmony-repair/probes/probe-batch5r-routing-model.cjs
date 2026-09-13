// probe-batch5r-routing-model.cjs — batch 5R probe (final-review P2 fixes).
//
// Loads the REAL changed modules headless (deps stubbed) and drives them:
//   - harmony/entry/src/main/ets/platform_impl/ChatModelSelectionSupport.ets
//   - harmony/entry/src/main/ets/entryability/EntryAbility.ets
//
// Verifies the final-review P2 fixes:
//   saveEffectiveChatChoice:
//     (1) explicit assistantId not found -> throw, NO fallback global write
//     (2) assistant deleted inside the mutate lock -> throw, NO fallback global write
//     (3) matched model with empty internal UUID (broken) -> reject
//     (4) unbound assistant -> global write (accepted semantics, unchanged)
//     (5) bound assistant -> chatModelId = internal UUID
//   publishWantRoute:
//     (A) valid target -> pending + seq + dispatch; cleared only after nav success
//     (B) incomplete routing intent (novelProjectId only) -> invalidate old pending
//     (C) no routing fields (ordinary activation) -> PRESERVE pending
//     (D) nav failure -> pending retained
//     (E) seq guard: late completion of an invalidated old push must not route
//
// Single probe file, no new test suite.

const path = require('node:path');
const assert = require('node:assert/strict');
const {
  REPO_ROOT, createLoader,
} = require('../../2026-09-13-harmony-merged-evidence/probe-lib.cjs');

const ENTRY_DIR = path.join(REPO_ROOT, 'harmony/entry/src/main/ets');
const log = (...a) => console.log(...a);
const flush = () => new Promise((r) => setTimeout(r, 0));

// ---------------- shared stubs ----------------
const makeAppStorage = () => {
  const map = new Map();
  return {
    get: (k) => (map.has(k) ? map.get(k) : undefined),
    setOrCreate: (k, v) => { map.set(k, v); },
    _map: map,
  };
};

let pushCalls = [];
let pushImpl = () => Promise.resolve();
const routerStub = {
  pushUrl: (opts) => { pushCalls.push(opts); return pushImpl(opts); },
};

const appStorage = makeAppStorage();

const entryResolver = (name) => {
  if (name === '@kit.AbilityKit') {
    return {
      UIAbility: class {},
      AbilityConstant: {},
      Configuration: {},
      ConfigurationConstant: { ColorMode: { COLOR_MODE_DARK: 1 } },
    };
  }
  if (name === '@kit.ArkUI') {
    return {
      window: {},
      display: { getDefaultDisplaySync: () => ({ densityPixels: 3 }) },
      router: routerStub,
    };
  }
  if (name === '@kit.PerformanceAnalysisKit') {
    return { hilog: { info() {}, warn() {}, error() {} } };
  }
  if (name === '@kit.NotificationKit') return { notificationManager: {} };
  if (name === '@kit.BasicServicesKit') return { BusinessError: class {} };
  if (name.includes('AppContainer')) {
    return {
      getSkillManager: () => ({ installBuiltinSkillsIfMissing: () => Promise.resolve() }),
      rescheduleCronTasksOnStartup: () => {},
      getChatKvStore: () => ({}),
    };
  }
  if (name === '@amber/chat-domain') return { loadDisplaySetting: async () => ({ accentColor: '' }) };
  if (name.includes('design/tokens')) {
    return { ACCENT: '#000', BG: '#fff', applyTheme() {}, setAccentColor() {} };
  }
  if (name.includes('design/fonts')) {
    return { registerAmberFonts() {}, registerAmberFontsFor() {} };
  }
  return {};
};
entryResolver.sandboxGlobals = { console, setTimeout, clearTimeout, AppStorage: appStorage };
const { load: loadEntry } = createLoader(entryResolver);
const E = loadEntry(path.join(ENTRY_DIR, 'entryability/EntryAbility.ets'));

// ---- state for the helper stubs ----
const state = {
  assistants: [],
  providers: [],
  globalSaves: [],
  mutateCalls: [],
  mutatedAssistant: null,
  mutateReturnsNull: false,
};
const domainStub = {
  // trivial equivalent of the real spread-based patchAssistant (peer, not under test)
  patchAssistant: (base, patch) => Object.assign({}, base, patch),
  // faithful minimal hasUsableAuth: enabled && non-empty apiKey
  hasUsableAuth: (p) => p.enabled !== false
    && typeof p.apiKey === 'string' && p.apiKey.length > 0,
  loadProviders: async () => state.providers,
};
const appStub = {
  getChatKvStore: () => ({}),
  getChatAssistants: async () => state.assistants,
  resolveChatProviderChoice: async () => null,
  saveChatModelSelection: async (sel) => { state.globalSaves.push(sel); },
};
const mutationStub = {
  mutateAssistant: async (id, mut) => {
    state.mutateCalls.push(id);
    if (state.mutateReturnsNull) return null;
    const a = state.assistants.find((x) => x.id === id);
    if (!a) return null;
    state.mutatedAssistant = mut(a);
    return state.mutatedAssistant;
  },
};
const helperResolver = (name) => {
  if (name === '@amber/chat-domain') return domainStub;
  if (name.includes('AppContainer')) return appStub;
  if (name.includes('AssistantMutation')) return mutationStub;
  return {};
};
helperResolver.sandboxGlobals = { console, setTimeout, clearTimeout, AppStorage: appStorage };
const { load: loadHelper } = createLoader(helperResolver);
const M = loadHelper(path.join(ENTRY_DIR, 'platform_impl/ChatModelSelectionSupport.ets'));

// ---------------- model selection ----------------
async function modelSelection() {
  const sel = { providerId: 'p1', modelId: 'gpt-x' };
  state.providers = [{
    id: 'p1', enabled: true, apiKey: 'k',
    models: [{ id: 'uuid-A', modelId: 'gpt-x', type: 'chat' }],
  }];

  // (1) explicit assistantId not found -> throw, no global fallback
  state.assistants = [];
  state.globalSaves = [];
  await assert.rejects(
    () => M.saveEffectiveChatChoice(sel, 'ghost'),
    /助手不存在/,
    'P2-1: explicit missing assistantId must reject',
  );
  assert.equal(state.globalSaves.length, 0, 'P2-1: must NOT fall back to global write');
  log('[F34] (1) explicit missing assistant -> throw, no global write');

  // (2) unbound assistant -> global write (accepted semantics)
  state.assistants = [{ id: 'a2', chatModelId: null }];
  state.mutations = [];
  state.globalSaves = [];
  state.mutateCalls = [];
  await M.saveEffectiveChatChoice(sel, 'a2');
  assert.equal(state.mutateCalls.length, 0, 'unbound must not mutate binding');
  assert.deepEqual(state.globalSaves.at(-1), sel, 'unbound -> global write');
  log('[F34] (2) unbound assistant -> global write (accepted)');

  // (3) bound assistant -> chatModelId = internal UUID
  state.assistants = [{ id: 'a3', chatModelId: 'old-uuid' }];
  state.mutateCalls = [];
  state.globalSaves = [];
  state.mutatedAssistant = null;
  await M.saveEffectiveChatChoice(sel, 'a3');
  assert.deepEqual(state.mutateCalls, ['a3'], 'bound -> mutateAssistant(a3)');
  assert.equal(state.mutatedAssistant.chatModelId, 'uuid-A',
    'bound -> internal ProviderModel.id (not the human modelId)');
  assert.equal(state.globalSaves.length, 0, 'bound -> global keys untouched');
  log('[F34] (3) bound assistant -> chatModelId=uuid-A, no global write');

  // (4) broken model with empty internal UUID -> reject
  state.providers = [{
    id: 'p1', enabled: true, apiKey: 'k',
    models: [{ id: '   ', modelId: 'gpt-broken', type: 'chat' }],
  }];
  state.assistants = [{ id: 'a3', chatModelId: 'old-uuid' }];
  state.globalSaves = [];
  state.mutateCalls = [];
  await assert.rejects(
    () => M.saveEffectiveChatChoice({ providerId: 'p1', modelId: 'gpt-broken' }, 'a3'),
    /不存在或不可用/,
    'P2-2: empty internal UUID must be rejected',
  );
  assert.equal(state.mutateCalls.length, 0, 'broken model must not be bound');
  assert.equal(state.globalSaves.length, 0, 'broken model must not write global');
  log('[F34] (4) empty-UUID model -> reject, no bind / no global');

  // (5) disabled provider -> reject
  state.providers = [{
    id: 'p1', enabled: false, apiKey: 'k',
    models: [{ id: 'uuid-A', modelId: 'gpt-x', type: 'chat' }],
  }];
  await assert.rejects(
    () => M.saveEffectiveChatChoice(sel, 'a3'),
    /不存在或不可用/,
    'disabled provider must be rejected',
  );
  log('[F34] (5) disabled provider -> reject');

  // (6) assistant deleted inside the lock -> throw, no global fallback
  state.providers = [{
    id: 'p1', enabled: true, apiKey: 'k',
    models: [{ id: 'uuid-A', modelId: 'gpt-x', type: 'chat' }],
  }];
  state.assistants = [{ id: 'a4', chatModelId: 'old-uuid' }];
  state.mutateReturnsNull = true;
  state.globalSaves = [];
  await assert.rejects(
    () => M.saveEffectiveChatChoice(sel, 'a4'),
    /助手已删除/,
    'P2-1b: lock-time deletion must reject',
  );
  assert.equal(state.globalSaves.length, 0, 'P2-1b: must NOT fall back to global write');
  state.mutateReturnsNull = false;
  log('[F34] (6) lock-time deletion -> throw, no global write');
}

// ---------------- want routing ----------------
async function wantRouting() {
  E.markWantRoutingReady();

  // (A) valid topic: dispatch + clear after nav success
  pushCalls = [];
  pushImpl = () => Promise.resolve();
  E.publishWantRoute('t1', 'T1', '', '', '');
  assert.equal(pushCalls.length, 1, 'valid topic -> one push');
  assert.equal(pushCalls[0].url, 'pages/DeepReadArticlePage');
  assert.equal(pushCalls[0].params.topicId, 't1');
  await flush();
  assert.equal(appStorage.get('wantRouteSeq'), 0, 'cleared after nav success');
  assert.equal(appStorage.get('wantTopicId'), '');
  log('[R23] (A) valid topic -> dispatch, cleared on nav success');

  // (B) incomplete routing intent invalidates old pending; late old push must not route
  let resolveHang;
  pushImpl = () => new Promise((r) => { resolveHang = r; });
  pushCalls = [];
  E.publishWantRoute('tOld', 'TOld', '', '', '');       // seq 1, in flight
  assert.equal(appStorage.get('wantRouteSeq'), 1);
  E.publishWantRoute('', '', 'onlyProject', '', '');    // malformed new intent
  assert.equal(appStorage.get('wantRouteSeq'), 0, 'incomplete intent invalidates pending');
  assert.equal(appStorage.get('wantTopicId'), '', 'old pending fields cleared');
  assert.equal(appStorage.get('wantNovelProjectId'), '');
  const callsBefore = pushCalls.length;
  resolveHang();
  await flush();
  assert.equal(pushCalls.length, callsBefore, 'late old push must not route new');
  log('[R23] (B) incomplete intent -> invalidate old pending, no late mis-route');

  // (C) no routing fields preserves pending
  pushImpl = () => new Promise((r) => { resolveHang = r; });
  pushCalls = [];
  E.publishWantRoute('tKeep', 'TKeep', '', '', '');     // seq 1, in flight
  E.publishWantRoute('', '', '', '', '');               // ordinary activation, no fields
  assert.equal(appStorage.get('wantRouteSeq'), 1, 'no-field activation preserves pending');
  assert.equal(appStorage.get('wantTopicId'), 'tKeep', 'pending target preserved');
  resolveHang();
  await flush();
  log('[R23] (C) no-field activation -> pending preserved');

  // (D) nav failure retains pending
  pushImpl = () => Promise.reject(new Error('nav fail'));
  pushCalls = [];
  E.publishWantRoute('tFail', 'T', '', '', '');
  await flush();
  assert.equal(appStorage.get('wantTopicId'), 'tFail', 'nav failure retains pending');
  assert.equal(appStorage.get('wantRouteSeq'), 1);
  log('[R23] (D) nav failure -> pending retained for retry');

  // (E) valid novel target dispatch
  pushImpl = () => Promise.resolve();
  pushCalls = [];
  E.publishWantRoute('', '', 'proj', 'br', 'job');
  assert.equal(pushCalls.length, 1);
  assert.equal(pushCalls[0].url, 'pages/NovelWorkspacePage');
  assert.equal(pushCalls[0].params.jobId, 'job');
  await flush();
  log('[R23] (E) valid novel target -> NovelWorkspacePage');
}

(async () => {
  await modelSelection();
  await wantRouting();
  log('PROBE OK: batch 5R (F34 effective model + R23 want routing)');
})().catch((e) => { console.error('PROBE FAILED:', e); process.exit(1); });
