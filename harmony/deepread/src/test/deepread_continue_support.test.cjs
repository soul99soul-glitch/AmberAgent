const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const filename = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl/DeepReadContinueSupport.ets');
const loadSupport = () => {
  const exports = {};
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, Promise, Object, String, Set, Math }, { filename });
  return exports;
};

const entry = (topicId, phase, complete = false, lastError = null, sectionStates = {}) => ({
  topicId, title: topicId, phase, lastError, updatedAt: 1000,
  output: { generationComplete: complete, generationPhase: phase, sectionStates },
});

test('history is filtered before limiting: old resumable entries survive newer complete articles', async () => {
  const api = loadSupport();
  let requestedLimit = 0;
  const rows = [entry('done1', 'COMPLETE', true), entry('done2', 'COMPLETE', true),
    entry('done3', 'COMPLETE', true), entry('old1', 'WRITING'), entry('old2', 'COLLECTING'),
    entry('old3', 'IDLE'), entry('old4', 'PLANNING')];
  const repository = { listHistory: async (limit) => { requestedLimit = limit; return rows; } };
  const actual = await api.loadDeepReadContinueCandidates(repository, { isRunning: () => false });
  assert.equal(requestedLimit, 100);
  assert.deepEqual(Array.from(actual, (item) => item.sourceId), ['old1', 'old2', 'old3']);
});

test('only scheduler activity shows running; persisted intermediate phases show paused', () => {
  const api = loadSupport();
  const rows = [entry('active', 'COMPLETE', true), entry('interrupted', 'WRITING'),
    entry('fresh', 'IDLE')];
  const actual = api.deepReadContinueCandidates(rows, { isRunning: (id) => id === 'active' });
  assert.equal(actual[0].status, 'running');
  assert.equal(actual[0].isRunning, true);
  assert.equal(actual[0].summary, '正在生成');
  assert.equal(actual[1].status, 'paused');
  assert.equal(actual[1].isRunning, false);
  assert.equal(actual[1].summary, '已暂停，可继续');
  assert.equal(actual[2].status, 'waiting_user');
  assert.equal(actual[2].summary, '待开始');
  assert.equal(actual[2].route.topicId, 'fresh');
});

test('total errors and partial COMPLETE output remain resumable, without a fake spinner', () => {
  const api = loadSupport();
  const rows = [entry('failed', 'COLLECTING', false, '没有来源'),
    entry('partial', 'COMPLETE', false, null, { ANALYSIS: { status: 'FAILED', errorMessage: 'timeout' } }),
    entry('cancelled', 'IDLE', false, null, { OVERVIEW: { status: 'READY', errorMessage: null } })];
  const actual = api.deepReadContinueCandidates(rows, { isRunning: () => false });
  assert.equal(actual[0].status, 'failed_resumable');
  assert.equal(actual[0].summary, '生成失败，可重试');
  assert.equal(actual[1].status, 'failed_resumable');
  assert.equal(actual[2].status, 'paused');
  assert.equal(actual.every((item) => item.isRunning === false), true);
});

test('a retained older article with an active regeneration is included before inactive drafts', () => {
  const api = loadSupport();
  const rows = [entry('draft1', 'WRITING'), entry('draft2', 'WRITING'), entry('draft3', 'WRITING'),
    entry('active-old', 'COMPLETE', true)];
  const actual = api.deepReadContinueCandidates(rows, { isRunning: (id) => id === 'active-old' });
  assert.equal(actual[0].sourceId, 'active-old');
  assert.equal(actual.length, 3);
});

test('a first run collecting sources appears even before it has written history', async () => {
  const api = loadSupport();
  const scheduler = {
    isRunning: (id) => id === 'collecting-new',
    getActiveRuns: () => [{ topicId: 'collecting-new', title: '新主题', startedAt: 1234 }],
  };
  const actual = await api.loadDeepReadContinueCandidates({ listHistory: async () => [] }, scheduler);
  assert.equal(actual.length, 1);
  assert.equal(actual[0].sourceId, 'collecting-new');
  assert.equal(actual[0].status, 'running');
  assert.equal(actual[0].lastUpdatedAt, 1234);
  assert.equal(actual[0].route.topicId, 'collecting-new');
  assert.equal(actual[0].route.title, '新主题');
});

test('active job inventory includes a force regeneration older than the history window, without duplicates', () => {
  const api = loadSupport();
  const scheduler = {
    isRunning: (id) => id === 'outside-window' || id === 'also-in-history',
    getActiveRuns: () => [
      { topicId: 'outside-window', title: '旧稿', startedAt: 2000 },
      { topicId: 'also-in-history', title: '新稿', startedAt: 3000 },
    ],
  };
  const actual = api.deepReadContinueCandidates([entry('also-in-history', 'WRITING')], scheduler);
  assert.equal(actual.length, 2);
  assert.equal(actual.filter((item) => item.sourceId === 'also-in-history').length, 1);
  assert.equal(actual.some((item) => item.sourceId === 'outside-window' && item.isRunning), true);
});
