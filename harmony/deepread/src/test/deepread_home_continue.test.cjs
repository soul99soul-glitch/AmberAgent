const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const pageFile = path.resolve(__dirname, '../../../entry/src/main/ets/pages/ChatListPage.ets');
const pageSource = fs.readFileSync(pageFile, 'utf8');
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
    module, Promise,
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
  const code = `module.exports = class {
    pageAlive = true; firstShowDone = false; continueSeq = 0; continueItem = null;
    observeRunning() {} refresh() {} loadLastOpened() {} consumeDeepLink() {}
    getUIContext() { return { getRouter: () => ({}) }; }
    ${method('onPageShow')}
    ${method('loadContinue')}
  };`;
  vm.runInNewContext(transpile(code), context, { filename: pageFile });
  return { page: new module.exports(), setActive: value => { active = value; },
    releaseFirstNovel: () => releaseFirstNovel([]) };
}

test('late pre-run Home aggregation cannot replace a fresh active force regeneration', async () => {
  const { page, setActive, releaseFirstNovel } = harness();
  page.loadContinue(); page.onPageShow(); await settle();
  setActive(true); page.onPageShow(); await settle();
  assert.equal(page.continueItem.sourceId, 'infoq');
  assert.equal(page.continueItem.status, 'running');
  releaseFirstNovel(); await settle();
  assert.equal(page.continueItem.sourceId, 'infoq');
  assert.equal(page.continueItem.status, 'running');
});

test('late running Home aggregation cannot revive a job after the completed refresh', async () => {
  const { page, setActive, releaseFirstNovel } = harness(true);
  page.loadContinue(); page.onPageShow(); await settle();
  setActive(false); page.onPageShow(); await settle();
  assert.equal(page.continueItem.sourceId, 'agents300k');
  assert.equal(page.continueItem.status, 'failed_resumable');
  releaseFirstNovel(); await settle();
  assert.equal(page.continueItem.sourceId, 'agents300k');
  assert.equal(page.continueItem.isRunning, false);
});
