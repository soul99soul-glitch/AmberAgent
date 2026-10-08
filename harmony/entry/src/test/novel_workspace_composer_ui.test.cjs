const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const { actualPage } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');

const source = fs.readFileSync(path.join(__dirname, '../main/ets/pages/NovelWorkspacePage.ets'), 'utf8');

test('a retained active message row reads each current snapshot, including reasoning and body growth', () => {
  const row = source.slice(source.indexOf('ForEach(this.novelTimelineMessages()'));
  const binding = row.match(/AgentMessageRow\(\{\s*message(?:\s*:\s*([^\n]+?))?,/);
  assert.ok(binding, 'active row binding must exist');
  const page = actualPage('pages/NovelWorkspacePage.ets',
    source.includes('  private activeRunMessage(') ? ['activeRunMessage', 'novelTimelineMessageUi', 'novelTimelineMessageLive', 'savedTimelineMessage'] : [], { makeUIMessage: () => { throw Error('unexpected quick start'); } });
  page.activeQuickStart = false; page.activeRunId = 'run';
  const seed = { id: 'assistant', role: 'assistant', parts: [{ type: 'reasoning', reasoning: 'first' }] };
  const read = new Function('message', 'return ' + (binding[1] || 'message'));
  page.activeMessages = [seed];
  assert.strictEqual(read.call(page, { id: seed.id, uiMessage: seed }), seed);
  for (const parts of [[{ type: 'reasoning', reasoning: 'first second' }],
    [{ type: 'reasoning', reasoning: 'first second', finishedAt: 'done' }, { type: 'text', text: 'chapter grows' }]]) {
    const current = { ...seed, parts };
    page.activeMessages = [current];
    assert.strictEqual(read.call(page, { id: seed.id, uiMessage: seed }), current,
      'ForEach retains the seed; the row must resolve the latest same-ID message');
  }
});

test('quick start streams current thinking while keeping the structured JSON out of the visible message', () => {
  const page = actualPage('pages/NovelWorkspacePage.ets', ['activeRunMessage', 'activeRunTimelineMessages'], {
    makeUIMessage: (role, parts, identity) => ({ role, parts, ...identity }),
  });
  page.activeQuickStart = true;
  const seed = { id: 'quick', role: 'assistant', parts: [{ type: 'reasoning', reasoning: 'first' }] };
  const current = { ...seed, parts: [{ type: 'reasoning', reasoning: 'first second' },
    { type: 'text', text: '{"settingProposals":[' }] };
  page.activeMessages = [{ id: 'user', role: 'user', parts: [{ type: 'text', text: 'seed' }] }, current];
  assert.deepEqual(page.activeRunTimelineMessages(), [current]);
  assert.deepEqual(page.activeRunMessage(seed).parts, [current.parts[0]]);
  page.activeMessages = [{ ...current, parts: [current.parts[1]] }];
  assert.deepEqual(page.activeRunTimelineMessages(), []);
});
const between = (start, end) => source.slice(source.indexOf(start), source.indexOf(end)).replace(/private /g, '');
const defaultsSource = fs.readFileSync(path.join(__dirname,
  '../../../deepread/src/main/ets/novel/standalone_defaults.ts'), 'utf8');
const resolveSource = defaultsSource.slice(defaultsSource.indexOf('export const resolveNovelDefaultTarget'),
  defaultsSource.indexOf('export const novelStorySeedText')).replace('export ', '');
const compile = text => ts.transpileModule(text, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
const resolveNovelDefaultTarget = new Function(compile(resolveSource) + 'return resolveNovelDefaultTarget;')();
const global = { kind: 'global' };
const fixed = { kind: 'fixed', providerId: 'provider', modelId: 'model' };
const defaults = () => ({ writing: global, review: global, stateSync: global });
const option = { providerId: 'provider', modelId: 'model', label: 'Writer', providerLabel: 'Service', available: true };

function fixture({ product = 'novel', reads = {} } = {}) {
  const code = compile('class Page {' +
    between('  private writingModelLabel()', '  private workspaceSubtitle()') +
    between('  private async openControlSheet()', '  private selectWritingTarget(') +
    between('  private async savePlanPrefs()', '  // 写作模型选项行') +
    between('  private branchSettingsWith(', '  private async addStoryForeshadow()') +
    between('  private async loadProjectModelOptions()', '  private ghostwriteModelsReady()') +
    '} return Page;');
  const modelWrites = [], planWrites = [], draftWrites = [];
  const draftValues = reads.draftValues ?? new Map();
  let finishModel, failModel;
  const modelCommit = new Promise((resolve, reject) => { finishModel = resolve; failModel = reject; });
  const read = async (key, fallback) => {
    if (reads.failAt === key) throw new Error(key + ' read failed');
    return reads[key] ?? fallback;
  };
  const effect = () => ({ combine() { return this; }, animation(options) { this.options = options; return this; } });
  const transitions = { IDENTITY: 'identity', get OPACITY() { return effect(); }, move: effect,
    asymmetric: (enter, exit) => ({ enter, exit }) };
  const Page = new Function('getProductKind', 'resolveNovelDefaultTarget', 'composerOwner',
    'loadNovelModelOptions', 'loadNovelModelDefaults', 'describeChatModel', 'defaultNovelModelDefaults',
    'TransitionEffect', 'TransitionEdge', 'Curve', 'sameControlPlanDraft', code)(
    () => product, resolveNovelDefaultTarget, (p, b) => p + ':' + b,
    () => read('options', [option]), () => read('defaults', defaults()), () => read('chat', 'Chat / Current'),
    defaults, transitions, { BOTTOM: 'bottom' }, { EaseOut: 'out', EaseIn: 'in' },
    (a, b) => a.thisChapterPlan === b.thisChapterPlan && a.futurePlan === b.futurePlan && a.preferences === b.preferences);
  const page = new Page();
  Object.assign(page, { projectId: 'project', project: { modelPolicy: defaults(), branchSettings: {
    thisChapterPlan: 'published', futurePlan: 'future', preferences: 'style', foreshadows: [], confirmedDecisions: [] } },
    workspaceStatus: { activeBranchId: 'main' }, pageAlive: true, studioPageVisible: true, studioBackgrounded: false,
    reduceMotion: false, busy: false, errorMsg: '', modelOptions: [option], modelOptionsLoadToken: 0,
    modelOptionsReady: true, modelOptionsLoading: false, modelOptionsError: '', novelModelDefaults: defaults(),
    chatModelLabel: 'Chat / Current', composerModelSheetOpen: false, composerModelSession: 0, composerModelError: '',
    controlPlanOwner: '', controlPlanBaseline: { thisChapterPlan: '', futurePlan: '', preferences: '' },
    controlPlanDrafts: new Map(), planThisChapter: '', planFuture: '', planPreferences: '', ghostwriteJob: null,
    controlSheetOpen: false, controlNotice: '', controlPlanReady: false, controlPlanSession: 0, controlPlanWriteRevision: 0,
    planDraftStore: { load: async owner => {
      if (reads.draftReadFailure) throw new Error('draft read failed');
      if (reads.delayedDraft) return reads.delayedDraft;
      return draftValues.get(owner) ?? null;
    }, save: async (owner, value) => {
      draftWrites.push({ owner, value });
      if (reads.draftWriteFailure) throw new Error('draft write failed');
      if (value === null) draftValues.delete(owner); else draftValues.set(owner, value);
    } },
    saveNovelModelPolicy: async policy => {
      page.busy = true; modelWrites.push(policy);
      try { await modelCommit; page.project.modelPolicy = policy; page.errorMsg = ''; }
      catch (error) { page.errorMsg = String(error); }
      finally { page.busy = false; }
    },
    saveNovelBranchSettings: async settings => {
      planWrites.push(settings);
      if (reads.planFailure) { page.errorMsg = 'plan commit failed'; return; }
      page.project.branchSettings = settings; page.errorMsg = '';
    },
  });
  return { page, modelWrites, planWrites, draftWrites, draftValues, finishModel, failModel };
}

test('composer shows the effective novel default while preserving a missing or disabled fixed choice', () => {
  const f = fixture();
  assert.equal(f.page.writingModelLabel(), '小说默认 · Chat / Current');
  f.page.novelModelDefaults.writing = fixed;
  assert.equal(f.page.writingModelLabel(), '小说默认 · Writer');
  f.page.project.modelPolicy.writing = fixed;
  assert.equal(f.page.writingModelLabel(), 'Writer');
  f.page.modelOptions = [{ ...option, available: false }];
  assert.equal(f.page.writingModelLabel(), 'Writer（当前不可用）');
  f.page.modelOptions = [];
  assert.match(f.page.writingModelLabel(), /模型已移除.*model/);
  const host = fixture({ product: 'agent' });
  assert.equal(host.page.writingModelLabel(), '跟随全局');
});

test('model selection persists only writing and closes after commit; Back cannot interrupt the pending sheet', async () => {
  const f = fixture(); f.page.composerModelSheetOpen = true; f.page.composerModelSession = 1;
  const choosing = f.page.chooseComposerModel(fixed, 1);
  assert.equal(f.modelWrites.length, 1);
  assert.equal(f.modelWrites[0].review.kind, 'global');
  assert.equal(f.modelWrites[0].stateSync.kind, 'global');
  f.page.closeComposerModelSheet(1);
  assert.equal(f.page.composerModelSheetOpen, true);
  await f.page.chooseComposerModel(fixed, 1);
  assert.equal(f.modelWrites.length, 1);
  f.finishModel(); await choosing;
  assert.equal(f.page.project.modelPolicy.writing.modelId, 'model');
  assert.equal(f.page.composerModelSheetOpen, false);
});

test('failed model persistence stays in the sheet, unavailable choices do not save, and stale sessions cannot close new sheets', async () => {
  const f = fixture(); f.page.composerModelSheetOpen = true; f.page.composerModelSession = 1;
  f.page.modelOptions = [{ ...option, available: false }];
  await f.page.chooseComposerModel(fixed, 1);
  assert.equal(f.modelWrites.length, 0);
  f.page.modelOptions = [option];
  const choosing = f.page.chooseComposerModel(fixed, 1);
  f.failModel(new Error('disk full')); await choosing;
  assert.match(f.page.composerModelError, /disk full/);
  assert.equal(f.page.project.modelPolicy.writing.kind, 'global');
  assert.equal(f.page.composerModelSheetOpen, true);

  const late = fixture(); late.page.composerModelSheetOpen = true; late.page.composerModelSession = 1;
  const saving = late.page.chooseComposerModel(fixed, 1);
  late.page.closeComposerModelSheet(1, true); // Page hiding / backgrounding closes the presentation.
  late.page.composerModelSheetOpen = true; late.page.composerModelSession = 3;
  late.page.closeComposerModelSheet(1);
  late.finishModel(); await saving;
  assert.equal(late.page.composerModelSheetOpen, true);
  assert.equal(late.page.composerModelSession, 3);
  await late.page.chooseComposerModel(global, 1);
  assert.equal(late.modelWrites.length, 1);
});

test('a failed defaults read never enables a model write and exposes a retryable configuration error', async () => {
  const reads = { failAt: 'defaults' }; const f = fixture({ reads });
  await f.page.loadProjectModelOptions();
  assert.equal(f.page.modelOptionsReady, false);
  assert.equal(f.page.modelOptionsLoading, false);
  assert.match(f.page.modelOptionsError, /defaults read failed/);
  f.page.composerModelSheetOpen = true; f.page.composerModelSession = 1;
  await f.page.chooseComposerModel(global, 1);
  assert.equal(f.modelWrites.length, 0);
  reads.failAt = '';
  await f.page.loadProjectModelOptions();
  assert.equal(f.page.modelOptionsReady, true);
  assert.equal(f.page.modelOptionsError, '');
});

test('closing and reopening project control retains all unsaved plan fields without publishing or crossing branches', async () => {
  const f = fixture(); await f.page.openControlSheet();
  f.page.planThisChapter = 'main draft'; f.page.planFuture = 'new future'; f.page.planPreferences = 'new style';
  f.page.controlSheetOpen = false; await f.page.openControlSheet();
  assert.equal(f.page.planThisChapter, 'main draft');
  assert.equal(f.page.planFuture, 'new future');
  assert.equal(f.page.planPreferences, 'new style');
  assert.equal(f.page.project.branchSettings.thisChapterPlan, 'published');
  assert.equal(f.planWrites.length, 0);
  f.page.workspaceStatus.activeBranchId = 'other';
  f.page.project.branchSettings.thisChapterPlan = 'other published';
  await f.page.openControlSheet();
  assert.equal(f.page.planThisChapter, 'other published');
  f.page.planThisChapter = 'other draft';
  f.page.workspaceStatus.activeBranchId = 'main';
  f.page.project.branchSettings.thisChapterPlan = 'published';
  await f.page.openControlSheet();
  assert.equal(f.page.planThisChapter, 'main draft');
  assert.equal(f.page.planFuture, 'new future');
});

test('failed plan saves keep the draft; successful saves refresh the baseline so later official edits remain visible', async () => {
  const reads = { planFailure: true }; const f = fixture({ reads }); await f.page.openControlSheet();
  f.page.planThisChapter = 'author draft';
  await f.page.savePlanPrefs(); f.page.controlSheetOpen = false; await f.page.openControlSheet();
  assert.equal(f.page.planThisChapter, 'author draft');
  assert.equal(f.page.project.branchSettings.thisChapterPlan, 'published');
  reads.planFailure = false; await f.page.savePlanPrefs();
  assert.equal(f.page.controlNotice, '已保存到当前分支');
  f.page.project.branchSettings.thisChapterPlan = 'later official edit';
  f.page.controlSheetOpen = false; await f.page.openControlSheet();
  assert.equal(f.page.planThisChapter, 'later official edit');
});

test('model sheet uses matching enter and exit motion, reduced opacity, and no hidden/background animation', () => {
  const f = fixture();
  assert.equal(f.page.composerModelTransition(true).enter.options.duration, 320);
  assert.equal(f.page.composerModelTransition(true).exit.options.duration, 250);
  f.page.reduceMotion = true;
  assert.equal(f.page.composerModelTransition(true).options.duration, 160);
  f.page.studioBackgrounded = true;
  assert.equal(f.page.composerModelTransition(true), 'identity');
  f.page.studioBackgrounded = false; f.page.studioPageVisible = false;
  assert.equal(f.page.composerModelTransition(true), 'identity');
});


test('a new workspace page restores disk drafts and formal apply removes the saved draft', async () => {
  const draftValues = new Map();
  const first = fixture({ reads: { draftValues } }); await first.page.openControlSheet();
  first.page.changeControlPlan('chapter', 'persisted author input');
  await Promise.resolve();
  assert.equal(first.planWrites.length, 0);
  first.page.closeControlSheet();
  const fresh = fixture({ reads: { draftValues } }); await fresh.page.openControlSheet();
  assert.equal(fresh.page.planThisChapter, 'persisted author input');
  assert.match(fresh.page.controlNotice, /已恢复/);
  await fresh.page.savePlanPrefs();
  assert.equal(draftValues.size, 0);
  const again = fixture({ reads: { draftValues } }); await again.page.openControlSheet();
  assert.equal(again.page.planThisChapter, 'published');
});

test('failed restore disables draft editing and formal application until a successful retry', async () => {
  const reads = { draftReadFailure: true }; const f = fixture({ reads });
  await f.page.openControlSheet();
  assert.equal(f.page.controlPlanReady, false);
  assert.match(f.page.controlPlanError, /draft read failed/);
  f.page.changeControlPlan('chapter', 'would erase saved draft'); await f.page.savePlanPrefs();
  assert.equal(f.draftWrites.length, 0); assert.equal(f.planWrites.length, 0);
  reads.draftReadFailure = false; await f.page.openControlSheet();
  assert.equal(f.page.controlPlanReady, true);
});

test('old sheet reads cannot overwrite a later sheet and failed autosave keeps editable input for retry', async () => {
  let resolve; const reads = { delayedDraft: new Promise(done => { resolve = done; }) };
  const f = fixture({ reads }); const stale = f.page.openControlSheet();
  f.page.closeControlSheet(); reads.delayedDraft = undefined;
  await f.page.openControlSheet();
  reads.draftWriteFailure = true;
  f.page.changeControlPlan('chapter', 'new author input');
  resolve({ thisChapterPlan: 'old disk draft', futurePlan: '', preferences: '' }); await stale;
  assert.equal(f.page.planThisChapter, 'new author input');
  await Promise.resolve(); await Promise.resolve();
  assert.match(f.page.controlPlanError, /draft write failed/);
  reads.draftWriteFailure = false; f.page.retryControlPlanDraft(); await Promise.resolve(); await Promise.resolve();
  assert.equal(f.page.controlPlanError, '');
  assert.equal(f.draftValues.get('project:main').thisChapterPlan, 'new author input');
});

test('a failed clear never resurrects a withdrawn disk draft and successful retry allows later formal edits', async () => {
  const reads = { draftValues: new Map([['project:main', { thisChapterPlan: 'withdrawn draft', futurePlan: 'future', preferences: 'style' }]]) };
  const f = fixture({ reads }); await f.page.openControlSheet();
  reads.draftWriteFailure = true;
  f.page.changeControlPlan('chapter', 'published');
  await Promise.resolve(); await Promise.resolve();
  f.page.closeControlSheet(); await f.page.openControlSheet();
  assert.equal(f.page.planThisChapter, 'published');
  reads.draftWriteFailure = false; f.page.retryControlPlanDraft();
  await Promise.resolve(); await Promise.resolve();
  f.page.project.branchSettings.thisChapterPlan = 'later official';
  f.page.closeControlSheet(); await f.page.openControlSheet();
  assert.equal(f.page.planThisChapter, 'later official');
  assert.equal(f.draftValues.size, 0);
});

test('reopening an autosave failure retries the retained snapshot before a new page reads it', async () => {
  const reads = { draftWriteFailure: true }; const f = fixture({ reads });
  await f.page.openControlSheet();
  f.page.changeControlPlan('chapter', 'latest unsaved plan');
  await Promise.resolve(); await Promise.resolve();
  assert.match(f.page.controlPlanError, /draft write failed/);
  f.page.closeControlSheet();
  reads.draftWriteFailure = false;
  await f.page.openControlSheet(); await Promise.resolve(); await Promise.resolve();
  const fresh = fixture({ reads: { draftValues: f.draftValues } });
  await fresh.page.openControlSheet();
  assert.equal(fresh.page.planThisChapter, 'latest unsaved plan');
  assert.equal(f.page.controlPlanError, '');
});

test('classified quick-start proposals retain the existing editable adoption and rejection paths', async () => {
  const rejected = [], adopted = [];
  const page = actualPage('pages/NovelWorkspacePage.ets', [
    'pendingSettingProposalsIn', 'resolveSettingProposal',
  ], { getNovelCreation: () => ({ resolveSettingProposal: async (...args) => { rejected.push(args); } }) });
  const proposal = (id, kind, status = 'pending') => ({ id, kind, status, title: id, content: 'editable proposal' });
  Object.assign(page, { projectId: 'project', project: { settingProposals: [
    proposal('world', 'world'), proposal('outline', 'outline'), proposal('other', 'other'),
    proposal('requirement', 'requirement'), proposal('accepted', 'other', 'accepted'),
  ] }, busy: false, hasBlockingNovelRun: () => false, reload: async () => {},
    openMaterialAdoption: (...args) => adopted.push(args),
  });
  assert.deepEqual(page.pendingSettingProposalsIn(['requirement', 'other']).map(p => p.id), ['other', 'requirement']);
  assert.deepEqual(page.pendingSettingProposalsIn(['outline']).map(p => p.id), ['outline']);
  await page.resolveSettingProposal(page.project.settingProposals[2], true);
  assert.deepEqual(adopted, [['other', true]]); assert.equal(rejected.length, 0);
  await page.resolveSettingProposal(page.project.settingProposals[3], false);
  assert.deepEqual(rejected, [['project', 'requirement', false]]);
  page.busy = true; await page.resolveSettingProposal(page.project.settingProposals[0], true);
  assert.equal(adopted.length, 1);
});

test('plan and story edits preserve structured branch settings and invalidate only the changed plan', async () => {
  const { createFileNovelRepository } = await import('../../../deepread/src/main/ets/novel/repository.ts');
  const { createMemoryFileStore } = await import('../../../deepread/src/main/ets/platform/files.ts');
  const { createNovelCreation } = await import('../../../deepread/src/main/ets/novel/creation.ts');
  const repository = createFileNovelRepository(createMemoryFileStore());
  const creation = createNovelCreation({ repository, modelRunning: {
    async validate() {}, start() { throw new Error('no provider needed'); }, cancel() {},
  } });
  const project = await creation.create('retained branch settings');
  await creation.setChapterContract(project.id, { outlinePlacement: 'third chapter', goalAndConflict: 'find the letter',
    mustHappen: ['letter'], mustNotHappen: ['culprit'], endingHook: 'phone', visibleFacts: ['postmark'] }, 'confirmed');
  await creation.setUpcomingArc(project.id, ['ferry', 'return']);
  await repository.commitProject(project.id, (await repository.workspaceStatus(project.id)).cas, 'count',
    'branch_settings_change', p => ({ ...p, branchSettings: { ...p.branchSettings, suggestedChapterCount: 3 } }));
  const before = await repository.loadProject(project.id);
  const f = fixture(); f.page.project = before; f.page.projectId = project.id;
  await f.page.openControlSheet(); f.page.planPreferences = 'new writing style';
  f.page.saveNovelBranchSettings = async settings => {
    await creation.setBranchSettings(project.id, settings);
    f.page.project = await repository.loadProject(project.id); f.page.errorMsg = '';
  };
  await f.page.savePlanPrefs();
  assert.deepEqual(f.page.project.branchSettings.chapterContract, before.branchSettings.chapterContract);
  assert.deepEqual(f.page.project.branchSettings.upcomingArc, before.branchSettings.upcomingArc);
  assert.equal(f.page.project.branchSettings.suggestedChapterCount, 3);
  const foreshadows = [{ id: 'letter', title: 'letter', content: 'postmark', status: 'open', createdAt: 1, resolvedAt: null }];
  await f.page.saveNovelBranchSettings(f.page.branchSettingsWith(foreshadows, before.branchSettings.confirmedDecisions));
  assert.deepEqual(f.page.project.branchSettings.foreshadows, foreshadows);
  assert.deepEqual(f.page.project.branchSettings.chapterContract, before.branchSettings.chapterContract);
  assert.deepEqual(f.page.project.branchSettings.upcomingArc, before.branchSettings.upcomingArc);
  f.page.planThisChapter = 'a genuinely changed chapter plan'; await f.page.savePlanPrefs();
  assert.equal(f.page.project.branchSettings.chapterContract, undefined);
  assert.deepEqual(f.page.project.branchSettings.upcomingArc, before.branchSettings.upcomingArc);
  assert.equal(f.page.project.branchSettings.suggestedChapterCount, undefined);
  f.page.planFuture = 'changed future plan'; await f.page.savePlanPrefs();
  assert.equal(f.page.project.branchSettings.upcomingArc, undefined);
});
