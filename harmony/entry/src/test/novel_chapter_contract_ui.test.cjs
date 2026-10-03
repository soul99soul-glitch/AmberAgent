const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const source = fs.readFileSync(path.join(__dirname, '../main/ets/pages/NovelChapterContractPage.ets'), 'utf8');

function method(name) {
  const match = new RegExp('^  (?:private )?(?:async )?' + name + '\\(', 'm').exec(source);
  assert.ok(match, 'missing actual contract UI method ' + name);
  let depth = 1, end = source.indexOf('{', match.index) + 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(match.index, end);
}
const oldCas = { branchId: 'main', head: 'before', treeDigest: 'tree-before' };
const newCas = { branchId: 'main', head: 'after', treeDigest: 'tree-after' };
const contract = { id: 'plan', branchId: 'main', status: 'confirmed', outlinePlacement: '第 3 章',
  goalAndConflict: '找出使者', mustHappen: ['发现假身份'], mustNotHappen: ['揭露主谋'], endingHook: '敲门声',
  visibleFacts: ['使者说谎'], contentDigest: 'digest', updatedAt: 100, confirmedAt: 100 };
function snap(settings, cas = oldCas) {
  settings = { thisChapterPlan: '', futurePlan: '', ...settings };
  return { project: { id: 'project', name: '长篇作品', branchSettings: settings },
    status: { cas, activeBranchId: cas.branchId, activeBranchName: '主线' } };
}
function fixture(creation = {}) {
  const dialogs = [], backs = [];
  class Controller { constructor() { this.cancelled = false; } cancel() { this.cancelled = true; } }
  const methods = ['lines', 'contractInput', 'hasDirtyDraft', 'editable', 'fillContract', 'acceptSnapshot',
    'refreshSaved', 'saveContract', 'saveArc', 'confirmClear', 'clear', 'confirmProposal', 'cancelProposal',
    'propose', 'onBackPress', 'load', 'reload'];
  const code = ts.transpileModule('class ContractPage {\n' + methods.map(method).join('\n')
    + '\n}\nreturn ContractPage;', { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const Page = new Function('getNovelCreation', 'AlertDialog', 'router', 'NovelAuditController', 'ERROR', code)(
    () => creation, { show: dialog => dialogs.push(dialog) }, { back: () => backs.push(true) }, Controller, '#red');
  const page = new Page();
  Object.assign(page, { projectId: 'project', alive: true, hidden: false, busy: false, proposing: false,
    loading: false, expectedCas: null, contract: null, contractBaseline: '', arcBaseline: '',
    outlinePlacement: '', goalAndConflict: '', mustHappenText: '', mustNotHappenText: '', endingHook: '',
    visibleFactsText: '', arcText: '', guidance: '', error: '', notice: '', loadToken: 0, proposalToken: 0,
    proposalController: null });
  page.acceptSnapshot(snap({ chapterContract: contract, upcomingArc: { beats: ['第一步', '第二步'], updatedAt: 100 } }));
  return { page, dialogs, backs, creation };
}

test('persisted contract and arc load clean; unsaved changes require explicit discard to leave', () => {
  const { page, dialogs, backs } = fixture();
  assert.equal(page.hasDirtyDraft(), false);
  page.goalAndConflict = '新的冲突';
  assert.equal(page.onBackPress(), true);
  assert.equal(dialogs.length, 1);
  dialogs[0].primaryButton.action(); assert.equal(backs.length, 0);
  dialogs[0].secondaryButton.action(); assert.equal(backs.length, 1);
});

test('saving contract uses frozen CAS and preserves an unsaved arc while consuming the committed CAS', async () => {
  const calls = [];
  const persisted = { chapterContract: { ...contract, goalAndConflict: '新的冲突', status: 'draft' },
    upcomingArc: { beats: ['第一步', '第二步'], updatedAt: 100 } };
  const { page } = fixture({ setChapterContract: async (...args) => {
    calls.push(args); return snap(persisted).project;
  }, readWorkspaceSnapshot: async () => snap(persisted, newCas) });
  page.goalAndConflict = '  新的冲突  ';
  page.arcText = '未保存的第三步';
  await page.saveContract('draft');
  assert.deepEqual(calls, [['project', { outlinePlacement: '第 3 章', goalAndConflict: '新的冲突',
    mustHappen: ['发现假身份'], mustNotHappen: ['揭露主谋'], endingHook: '敲门声', visibleFacts: ['使者说谎'] }, 'draft', oldCas]]);
  assert.deepEqual(page.expectedCas, newCas);
  assert.equal(page.arcText, '未保存的第三步');
  assert.equal(page.hasDirtyDraft(), true);
  assert.equal(page.contract.status, 'draft');
  assert.equal(page.busy, false);
});

test('arc save preserves unsaved contract edits and rejects ninth or overlong beat before any write', async () => {
  const calls = [];
  const persisted = { chapterContract: contract, upcomingArc: { beats: ['新的未来'], updatedAt: 200 } };
  const { page } = fixture({ setUpcomingArc: async (...args) => { calls.push(args); return snap(persisted).project; },
    readWorkspaceSnapshot: async () => snap(persisted, newCas) });
  for (const invalid of [Array.from({ length: 9 }, (_, i) => `步${i}`).join('\n'), '字'.repeat(161)]) {
    page.arcText = invalid; await page.saveArc();
    assert.equal(calls.length, 0); assert.match(page.error, /最多 8 条/);
  }
  page.goalAndConflict = '未保存的冲突'; page.arcText = '  新的未来  \n\n';
  await page.saveArc();
  assert.deepEqual(calls, [['project', ['新的未来'], oldCas]]);
  assert.equal(page.goalAndConflict, '未保存的冲突');
  assert.equal(page.arcText, '新的未来');
  assert.equal(page.hasDirtyDraft(), true);
});

test('CAS write failure leaves edits and saved status visible without claiming success', async () => {
  const { page } = fixture({ setChapterContract: async () => { throw new Error('CAS 已变化'); } });
  page.goalAndConflict = '作者尚未保存的冲突';
  await page.saveContract('confirmed');
  assert.equal(page.goalAndConflict, '作者尚未保存的冲突');
  assert.equal(page.contract.status, 'confirmed');
  assert.match(page.error, /CAS 已变化/);
  assert.equal(page.notice, ''); assert.equal(page.busy, false);
});

test('after-save external branch or settings change prevents rebinding drafts to a new CAS', async () => {
  for (const changed of [snap({ chapterContract: contract }, { ...newCas, branchId: 'other' }),
    snap({ chapterContract: { ...contract, goalAndConflict: '其他编辑' } }, newCas)]) {
    const { page } = fixture({ setChapterContract: async () => snap({ chapterContract: contract }).project,
      readWorkspaceSnapshot: async () => changed });
    page.goalAndConflict = '作者尚未保存的冲突';
    await page.saveContract('draft');
    assert.equal(page.expectedCas, null);
    assert.equal(page.goalAndConflict, '作者尚未保存的冲突');
    assert.match(page.error, /工作区又发生变化/);
    assert.equal(page.notice, '');
  }
});

test('model proposal only fills editable fields and does not persist or confirm the saved plan', async () => {
  const calls = [];
  const draft = { ...contract, status: 'draft', goalAndConflict: '模型拟定冲突', confirmedAt: null };
  const { page } = fixture({ proposeChapterContract: async (...args) => {
    calls.push(args); return { contract: draft, expectedCas: oldCas };
  } });
  page.guidance = '加强人物冲突';
  await page.propose();
  assert.equal(calls.length, 1); assert.deepEqual(calls[0].slice(0, 3), ['project', '加强人物冲突', oldCas]);
  assert.equal(page.goalAndConflict, '模型拟定冲突');
  assert.equal(page.contract.status, 'confirmed');
  assert.equal(page.hasDirtyDraft(), true);
  assert.match(page.notice, /保存草稿或确认计划/);
  assert.equal(page.busy, false); assert.equal(page.proposing, false);
});

test('cancelled or departed proposal cancels the controller and ignores a late model result', async () => {
  let finish;
  const result = new Promise(resolve => { finish = resolve; });
  const { page } = fixture({ proposeChapterContract: async () => result });
  const pending = page.propose();
  const controller = page.proposalController;
  page.cancelProposal();
  assert.equal(controller.cancelled, true);
  page.goalAndConflict = '取消后继续编辑';
  finish({ contract: { ...contract, goalAndConflict: '晚到模型内容' }, expectedCas: oldCas });
  await pending;
  assert.equal(page.goalAndConflict, '取消后继续编辑');
  assert.equal(page.busy, false); assert.equal(page.proposing, false);
});

test('clear uses the CAS frozen at confirmation and refreshes only the chosen area', async () => {
  const calls = [];
  const persisted = { upcomingArc: { beats: ['第一步', '第二步'], updatedAt: 100 } };
  const { page, dialogs } = fixture({ clearChapterContract: async (...args) => { calls.push(args); return snap(persisted).project; },
    readWorkspaceSnapshot: async () => snap(persisted, newCas) });
  page.confirmClear('contract');
  assert.match(dialogs[0].message, /当前分支/);
  page.arcText = '保留未保存走向';
  await dialogs[0].secondaryButton.action();
  // Dialog callback intentionally fires async save without waiting.
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(calls, [['project', oldCas]]);
  assert.equal(page.contract, null);
  assert.equal(page.goalAndConflict, '');
  assert.equal(page.arcText, '保留未保存走向');
});

test('dirty plan requires author confirmation before a paid model proposal starts', () => {
  const { page, dialogs } = fixture();
  let proposals = 0; page.propose = () => { proposals++; };
  page.goalAndConflict = '尚未保存'; page.confirmProposal();
  assert.equal(proposals, 0); assert.equal(dialogs.length, 1);
  dialogs[0].primaryButton.action(); assert.equal(proposals, 0);
  dialogs[0].secondaryButton.action(); assert.equal(proposals, 1);
});

require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const { createNovelCreation } = require('../../../deepread/src/main/ets/novel/creation.ts');
const { createFileNovelRepository } = require('../../../deepread/src/main/ets/novel/repository.ts');
const { createMemoryFileStore } = require('../../../deepread/src/main/ets/platform/files.ts');
const { buildNovelContext } = require('../../../deepread/src/main/ets/novel/context_builder.ts');
async function productionFixture() {
  let now = 100;
  const creation = createNovelCreation({ repository: createFileNovelRepository(createMemoryFileStore()),
    modelRunning: { validate: async () => {}, start: () => { throw new Error('no model in save test'); }, cancel() {} },
    nowMs: () => ++now });
  const project = await creation.create('真实事务测试');
  const { page } = fixture(creation);
  page.projectId = project.id;
  page.acceptSnapshot(await creation.readWorkspaceSnapshot(project.id));
  return { page, creation, project };
}

test('actual UI save goes through production creation/repository: draft excluded, confirmed injected, arc retained across reload', async () => {
  const { page, creation, project } = await productionFixture();
  Object.assign(page, { outlinePlacement: '第3章', goalAndConflict: '找出线索', mustHappenText: '发现来信\n遭到阻拦',
    mustNotHappenText: '揭露幕后主谋', endingHook: '来信署名', visibleFactsText: '邮戳日期' });
  await page.saveContract('draft');
  assert.equal(page.error, '');
  let loaded = await creation.readWorkspaceSnapshot(project.id);
  assert.equal(loaded.project.branchSettings.chapterContract.status, 'draft');
  assert.equal(buildNovelContext(loaded.project, 'write', 'whole_chapter').sections.some(s => s.key === 'chapter_plan'), false);
  await page.saveContract('confirmed');
  assert.equal(page.error, '');
  loaded = await creation.readWorkspaceSnapshot(project.id);
  const plan = buildNovelContext(loaded.project, 'write', 'whole_chapter').sections.find(s => s.key === 'chapter_plan');
  assert.match(plan.text, /发现来信/); assert.match(plan.text, /揭露幕后主谋/); assert.match(plan.text, /邮戳日期/);
  page.arcText = '使者身份曝光\n主角前往旧城';
  await page.saveArc();
  assert.equal(page.error, '');
  const reopened = fixture(creation).page;
  reopened.projectId = project.id;
  await reopened.load();
  assert.equal(reopened.contract.status, 'confirmed');
  assert.equal(reopened.arcText, '使者身份曝光\n主角前往旧城');
  assert.equal(reopened.hasDirtyDraft(), false);
});

test('production CAS rejects contract edits after a real branch switch and keeps author input', async () => {
  const { page, creation, project } = await productionFixture();
  page.goalAndConflict = '主线尚未保存的冲突';
  await creation.createBranch(project.id, '另一个分支');
  await page.saveContract('draft');
  assert.match(page.error, /工作区|分支|CAS/);
  assert.equal(page.goalAndConflict, '主线尚未保存的冲突');
  assert.equal(page.notice, '');
  const loaded = await creation.readWorkspaceSnapshot(project.id);
  assert.equal(loaded.project.branchSettings.chapterContract, undefined);
});


test('legacy plain-text plans stay visible until an explicit structured-plan save or clear', () => {
  const { page } = fixture();
  page.acceptSnapshot(snap({ thisChapterPlan: '原有完整文字计划', futurePlan: '原有完整未来方向' }));
  assert.equal(page.contract, null);
  assert.equal(page.legacyPlanText, '原有完整文字计划');
  assert.equal(page.legacyArcText, '原有完整未来方向');
  assert.equal(page.hasSavedArc, true);
  assert.equal(page.hasDirtyDraft(), false);
});
