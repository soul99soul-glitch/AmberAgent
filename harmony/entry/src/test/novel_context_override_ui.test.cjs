const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const { novelComposerRunKind } = require('../../../deepread/src/main/ets/novel/context_builder.ts');
const composer = require('../main/ets/novel/NovelComposerState.ts');
const source = fs.readFileSync(path.join(__dirname, '../main/ets/platform_impl/novel/NovelDiscussionSession.ets'), 'utf8');

function fixture() {
  let params = {};
  const calls = [];
  const creation = { validateWritingModel: async () => {}, generate: (...args) => { calls.push(args); return { id: 'run' }; } };
  const code = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  const exported = {};
  new Function('exports', 'require', 'Observed', 'Scroller', 'setTimeout', code)(exported, name => {
    if (name === '@amber/deepread-domain') return { novelComposerRunKind };
    if (name === '@amber/chat-domain') return {};
    if (name === '../../novel/NovelComposerState') return composer;
    if (name === '@kit.ArkUI') return { router: { getParams: () => params }, promptAction: { showToast() {} } };
    if (name === '@kit.BasicServicesKit') return { pasteboard: {} };
    if (name.endsWith('/NativeTimelineFollow.ets') || name.endsWith('/NovelArchivePresentation.ets')
      || name.endsWith('/NovelSettingProposalProjection.ets')) return {};
    throw new Error('Unexpected discussion dependency: ' + name);
  }, constructor => constructor, class {}, () => 0);
  const page = new exported.NovelDiscussionSession();
  const state = { errorMsg: '', busy: false };
  page.start({
    creation, projectId: () => 'project', currentProject: () => ({ messages: [] }),
    currentStatus: () => ({ activeBranchId: 'main' }), isBusy: () => state.busy,
    setBusy: value => { state.busy = value; }, error: value => { state.errorMsg = value; },
    operationError: (action, error) => { state.errorMsg = `${action}: ${error}`; },
    hasBlockingNovelRun: () => false, jobLocksWorkspace: () => false,
    currentGhostwriteJob: () => null, currentGhostwriteReport: () => null,
    reload: async () => {}, reloadToken: () => 0, selectedTab: () => 0,
    backgrounded: () => false, closeControl() {}, uiContext: () => ({}),
    composerDraftStore: { load: async () => ({ text: '', mode: 'whole_chapter' }), save: async () => {} },
  });
  Object.assign(page, { draft: '作者当前输入', composerDraftOwner: 'project:main', composeMode: 'discuss' });
  // Streaming attachment is beyond the selection/send boundary covered by this fixture.
  page.attachNovelRun = () => {};
  return { page, calls, creation, state, result(value) { params = { novelContextOverrides: JSON.stringify(value) }; } };
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
    const { page, result, state } = fixture();
    page.contextOverrides = { branchId: 'main', forceIncludeMaterialIds: ['old'], forceExcludeMaterialIds: [] };
    result({ ...selected, ...change }); page.consumeContextOverrides();
    assert.equal(page.contextOverrides, null);
    assert.match(state.errorMsg, /其他项目或分支/);
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
  const { page, result, calls, creation, state } = fixture();
  result(selected); page.consumeContextOverrides();
  creation.validateWritingModel = async () => { throw new Error('模型未配置'); };
  await page.send();
  assert.equal(calls.length, 0);
  assert.equal(page.draft, '作者当前输入');
  assert.deepEqual(page.contextOverrides.forceIncludeMaterialIds, ['include']);
  assert.match(state.errorMsg, /模型未配置/);
});
