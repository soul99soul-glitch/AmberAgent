const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const { novelComposerRunKind } = require('../../../deepread/src/main/ets/novel/context_builder.ts');
const { canRestoreFailedInput } = require('../main/ets/novel/NovelComposerState.ts');
const source = fs.readFileSync(path.join(__dirname, '../main/ets/pages/NovelWorkspacePage.ets'), 'utf8');

function method(name) {
  const match = new RegExp('^  (?:private )?(?:async )?' + name + '\\(', 'm').exec(source);
  assert.ok(match, 'missing production method ' + name);
  let depth = 1, end = source.indexOf('{', match.index) + 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(match.index, end);
}

function fixture() {
  let params = {};
  const calls = [];
  const creation = { validateWritingModel: async () => {}, generate: (...args) => { calls.push(args); return { id: 'run' }; } };
  const code = ts.transpileModule('class UI {\n' + ['consumeContextOverrides', 'send'].map(method).join('\n') + '\n}\nreturn new UI();',
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const page = new Function('router', 'getNovelCreation', 'novelComposerRunKind', 'canRestoreFailedInput', code)(
    { getParams: () => params }, () => creation, novelComposerRunKind, canRestoreFailedInput);
  Object.assign(page, { projectId: 'project', project: { messages: [] }, workspaceStatus: { activeBranchId: 'main' },
    lastContextReceiptToken: '', contextOverrides: null, pageAlive: true, busy: false, draft: '作者当前输入',
    composerDraftOwner: 'project:main', composeMode: 'discuss', sendStarting: false, activeRunId: '', composerRevision: 0,
    hasBlockingNovelRun: () => false, novelJobLocksWorkspace: () => false, flushComposerDraft() {}, attachNovelRun() {} });
  page.setComposerDraft = value => { page.draft = value; page.composerRevision++; };
  return { page, calls, creation, result(value) { params = { novelContextOverrides: JSON.stringify(value) }; } };
}
const selected = { projectId: 'project', branchId: 'main', forceIncludeMaterialIds: ['include'], forceExcludeMaterialIds: ['exclude'], receiptToken: 'return-1' };

test('a matching preview return is applied once; later page shows cannot reimport a consumed selection', () => {
  const { page, result } = fixture();
  result(selected); page.consumeContextOverrides();
  assert.deepEqual(page.contextOverrides, { branchId: 'main', forceIncludeMaterialIds: ['include'], forceExcludeMaterialIds: ['exclude'] });
  page.contextOverrides = null;
  page.consumeContextOverrides();
  assert.equal(page.contextOverrides, null);
});

test('preview return from another project or branch cannot populate the current composer', () => {
  for (const change of [{ projectId: 'other' }, { branchId: 'alternate' }]) {
    const { page, result } = fixture();
    page.contextOverrides = { branchId: 'main', forceIncludeMaterialIds: ['old'], forceExcludeMaterialIds: [] };
    result({ ...selected, ...change }); page.consumeContextOverrides();
    assert.equal(page.contextOverrides, null);
    assert.match(page.errorMsg, /其他项目或分支/);
  }
});

for (const [composeMode, mode, granularity, runKind] of [
  ['discuss', 'discuss', null, 'discussion'], ['continue', 'write', 'continuation', 'prose_continuation'],
  ['whole_chapter', 'write', 'whole_chapter', 'prose_whole_chapter'],
]) {
  test(`${composeMode} sends the chosen overrides with the actual run kind and consumes them after acceptance`, async () => {
    const { page, result, calls } = fixture();
    result(selected); page.consumeContextOverrides(); page.composeMode = composeMode;
    await page.send();
    assert.equal(calls.length, 1);
    assert.deepEqual(calls[0], ['project', '作者当前输入', mode, granularity, runKind, null,
      { branchId: 'main', forceIncludeMaterialIds: ['include'], forceExcludeMaterialIds: ['exclude'] }]);
    assert.equal(page.contextOverrides, null);
    assert.equal(page.draft, '');
    page.consumeContextOverrides();
    assert.equal(page.contextOverrides, null);
  });
}

test('model validation failure retains both author input and unconsumed one-run overrides', async () => {
  const { page, result, calls, creation } = fixture();
  result(selected); page.consumeContextOverrides();
  creation.validateWritingModel = async () => { throw new Error('模型未配置'); };
  await page.send();
  assert.equal(calls.length, 0);
  assert.equal(page.draft, '作者当前输入');
  assert.deepEqual(page.contextOverrides.forceIncludeMaterialIds, ['include']);
  assert.match(page.errorMsg, /模型未配置/);
});
