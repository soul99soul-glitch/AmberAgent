const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const pageFile = path.resolve(__dirname, '../../../entry/src/main/ets/components/HomeListPane.ets');
const pageSource = fs.readFileSync(pageFile, 'utf8');
const sessionFile = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl/home/HomeContinueSupport.ets');
const sessionSource = fs.readFileSync(sessionFile, 'utf8');
const transpile = (source) => ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
function method(name) {
  const start = pageSource.search(new RegExp('^  (?:private )?' + name + '\\(', 'm'));
  assert.ok(start >= 0);
  const open = pageSource.indexOf('{', start);
  let depth = 1, end = open + 1;
  for (; depth && end < pageSource.length; end++) {
    if (pageSource[end] === '{') depth++;
    if (pageSource[end] === '}') depth--;
  }
  return pageSource.slice(start, end);
}
function loadModule(relativePath) {
  const exports = {};
  vm.runInNewContext(transpile(fs.readFileSync(path.resolve(__dirname, relativePath), 'utf8')),
    { exports, Promise, Set, Math, Object });
  return exports;
}
const support = loadModule('../../../entry/src/main/ets/platform_impl/DeepReadContinueSupport.ets');
const merge = loadModule('../../../chat/src/main/ets/chat/continue_candidate.ts');
const settle = () => new Promise(setImmediate);
function harness(initiallyActive = false) {
  let active = initiallyActive;
  let releaseFirstNovel;
  const firstNovel = new Promise(resolve => { releaseFirstNovel = resolve; });
  let novelCalls = 0;
  const scheduler = {
    getActiveRuns: () => active ? [{ topicId: 'infoq', title: 'InfoQ', startedAt: 1000 }] : [],
    isRunning: id => active && id === 'infoq',
  };
  const entries = [
    { topicId: 'agents300k', title: 'Agents300K', phase: 'COLLECTING', lastError: 'no sources',
      updatedAt: 100, output: { generationComplete: false, sectionStates: {} } },
    { topicId: 'infoq', title: 'InfoQ', phase: 'COMPLETE', lastError: null,
      updatedAt: 200, output: { generationComplete: true, sectionStates: {} } },
  ];
  const module = { exports: {} };
  const context = {
    module, Promise, cachedContinue: null, cachedContinueReady: false,
    getNovelCreation: () => ({ listProjects: () => ++novelCalls === 1 ? firstNovel : Promise.resolve([]) }),
    getAgentCronManager: () => Promise.resolve({ listTasksSnapshot: async () => [] }),
    getDeepReadScheduler: () => Promise.resolve(scheduler),
    getRepository: () => ({ listHistory: async () => entries }),
    loadDeepReadContinueCandidates: support.loadDeepReadContinueCandidates,
    getMiniAppRepositorySync: () => ({ listAll: async () => [] }),
    getCouncilManager: () => null,
    getFilesRepository: () => ({ listByFolder: async () => [] }),
    mergeContinueCandidates: merge.mergeContinueCandidates,
    loadDisplaySetting: async () => ({ userNickname: '', userAvatar: '' }),
    getChatKvStore: () => ({}),
    markWantRoutingReady: () => {},
  };
  const sessionAst = ts.createSourceFile(sessionFile, sessionSource, ts.ScriptTarget.Latest, true);
  const sessionClass = sessionAst.statements.find(node => ts.isClassDeclaration(node) && node.name.text === 'HomeContinueSession');
  assert.ok(sessionClass, 'Execute the production continuation owner');
  const sessionExports = {};
  vm.runInNewContext(transpile(sessionClass.getText(sessionAst).replace('@Observed', '')), {
    ...context, exports: sessionExports,
  }, { filename: sessionFile });
  context.continuation = new sessionExports.HomeContinueSession(context);
  context.continuation.activate(false);
  const code = `module.exports = class {
    pageAlive = true; firstShowDone = false; hostVisible = true; twoPane = false;
    continuation = continuation;
    collection = { observeRunning() {}, refresh() {}, stopRunningSubscription() {} };
    loadLastOpened() {} loadUserProfile() {}
    getUIContext() { return { getRouter: () => ({}) }; }
    ${method('onHostVisibleChange')}
    loadContinue() { this.continuation.refresh(); }
    get continueItem() { return this.continuation.candidate; }
  };`;
  vm.runInNewContext(transpile(code), context, { filename: pageFile });
  return { page: new module.exports(), setActive: value => { active = value; },
    releaseFirstNovel: () => releaseFirstNovel([]) };
}

test('late pre-run Home aggregation cannot replace a fresh active force regeneration', async () => {
  const { page, setActive, releaseFirstNovel } = harness();
  page.loadContinue(); page.onHostVisibleChange(); await settle();
  setActive(true); page.onHostVisibleChange(); await settle();
  assert.equal(page.continueItem.sourceId, 'infoq');
  assert.equal(page.continueItem.status, 'running');
  releaseFirstNovel(); await settle();
  assert.equal(page.continueItem.sourceId, 'infoq');
  assert.equal(page.continueItem.status, 'running');
});

test('late running Home aggregation cannot revive a job after the completed refresh', async () => {
  const { page, setActive, releaseFirstNovel } = harness(true);
  page.loadContinue(); page.onHostVisibleChange(); await settle();
  setActive(false); page.onHostVisibleChange(); await settle();
  assert.equal(page.continueItem.sourceId, 'agents300k');
  assert.equal(page.continueItem.status, 'failed_resumable');
  releaseFirstNovel(); await settle();
  assert.equal(page.continueItem.sourceId, 'agents300k');
  assert.equal(page.continueItem.isRunning, false);
});
