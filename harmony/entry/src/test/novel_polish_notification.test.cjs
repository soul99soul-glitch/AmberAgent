const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../../../chat/node_modules/typescript');
const { projectPolishOutcomes } = require('../../../deepread/src/main/ets/novel/polish_outcomes.ts');

const fixture = () => {
  const filename = path.resolve(__dirname, '../main/ets/di/AppContainer.ets');
  const source = ts.createSourceFile(filename, fs.readFileSync(filename, 'utf8'), ts.ScriptTarget.Latest, true);
  const declaration = source.statements.find(statement => ts.isVariableStatement(statement)
    && statement.declarationList.declarations.some(item => item.name.getText(source) === 'getNovelCreation'));
  assert.ok(declaration);
  let effects;
  const published = [];
  const exports = {};
  vm.runInNewContext(ts.transpileModule(`let novelCreation = null;\n${declaration.getText(source)}`, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, {
    exports, Promise, projectPolishOutcomes,
    getAppContainer: () => ({}), getNovelProjectRepository: () => ({}),
    createNovelCreation: deps => { effects = deps.polishEffects; return {}; },
    createEntryNovelChatRunAdapter: () => ({}), resolveNovelInteractiveRuntime: () => {},
    loadNovelModelDefaults: () => {}, loadNovelPolishPreference: () => {},
    publishNovelJobNotification: async (...args) => { published.push(args); },
    scheduleNovelPolishWork: () => {}, cancelNovelPolishWork: () => {},
  }, { filename });
  exports.getNovelCreation();
  return { effects, published };
};

const job = statuses => ({ projectId: 'project', branchId: 'branch', jobId: 'same-job', stage: 'completed',
  cursor: statuses.length, targets: statuses.map((_, i) => ({ id: `c${i}`, ordinal: i + 1 })), progress: [],
  outcomes: statuses.map((status, i) => ({ chapterId: `c${i}`, chapterOrdinal: i + 1, status })), failure: null });

test('actual DI notification distinguishes processed mixed results from successful collection', async () => {
  const { effects, published } = fixture();
  await effects.notify(job(['success', 'failed', 'driftSkipped']));
  assert.deepEqual(published[0].slice(0, 3), ['project', 'branch', 'same-job']);
  assert.equal(published[0][3], '批量润色处理结束');
  assert.match(published[0][4], /成功 1\/3/);
  assert.match(published[0][4], /失败 1/);
  assert.match(published[0][4], /跳过 1/);
  await effects.notify(job(['success', 'success']));
  assert.equal(published[1][3], '批量润色完成');
});

test('actual retry notification counts preserved successes rather than the reset selected cursor', async () => {
  const { effects, published } = fixture();
  const retry = job(['unprocessed', 'success', 'success']);
  retry.stage = 'writing'; retry.cursor = 0;
  await effects.notify(retry);
  assert.match(published[0][4], /正在处理第 1 章/);
  assert.match(published[0][4], /成功 2\/3/);
  assert.match(published[0][4], /未处理 1/);
});
