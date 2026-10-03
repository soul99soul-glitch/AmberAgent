const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const { projectPolishOutcomes } = require('../../../deepread/src/main/ets/novel/polish.ts');
const { novelChapterOrdinal, makeNovelProject, makeNovelChapter } = require('../../../deepread/src/main/ets/novel/models.ts');
const { createNovelCreation } = require('../../../deepread/src/main/ets/novel/creation.ts');
const { createFileNovelRepository } = require('../../../deepread/src/main/ets/novel/repository.ts');
const { createMemoryFileStore } = require('../../../deepread/src/main/ets/platform/files.ts');
function methods(file, names) {
  const source = fs.readFileSync(path.join(__dirname, '../main/ets', file), 'utf8');
  return names.map(name => {
    const match = new RegExp('^  (?:private )?(?:async )?' + name + '\\(', 'm').exec(source);
    assert.ok(match, name);
    let depth = 1, end = source.indexOf('{', match.index) + 1;
    for (; depth && end < source.length; end++) {
      if (source[end] === '{') depth++;
      if (source[end] === '}') depth--;
    }
    return source.slice(match.index, end);
  }).join('\n');
}
const cas = { branchId: 'main', head: 'head', treeDigest: 'tree' };
const options = { includePlot: true, includeForeshadows: true, includeCharacters: true, includeDecisions: true };
const project = { name: '作品', polishPreference: '保留节奏', chapters: [1, 2, 3].map(n => ({ id: `c${n}`,
  ordinal: n, title: `章${n}`, content: `正文${n}`, updatedAt: 100, discarded: false })) };
const receipt = { cas, chapterIds: ['c1', 'c3'], targets: [project.chapters[0], project.chapters[2]].map(chapter => ({
  id: chapter.id, ordinal: chapter.ordinal, title: chapter.title, sourceContent: chapter.content, sourceDigest: 'digest' })),
  polishPreference: '保留节奏', preferenceSource: 'project', contextOptions: options, contextSnapshot: [] };
const outcome = (id, status, message = null) => ({ chapterId: id, chapterOrdinal: Number(id.slice(1)), status, message, updatedAt: 100 });
const job = { jobId: 'job', stage: 'completed', updatedAt: 100, cursor: 3, branchId: 'main',
  targets: project.chapters.map(chapter => ({ ...chapter, sourceContent: chapter.content, sourceDigest: 'digest' })),
  progress: [], outcomes: [outcome('c1', 'success'), outcome('c2', 'failed', '网络失败'), outcome('c3', 'driftSkipped', '新增剧情')] };
function fixture(creation = {}) {
  const names = ['chapters', 'ordinal', 'options', 'terminal', 'running', 'canPrepare', 'outcomes', 'retryable', 'retryTitle',
    'applyJob', 'sameCas', 'reload', 'invalidatePreview', 'toggleChapter', 'setOption', 'prepare', 'start',
    'control', 'retryFailed', 'poll', 'contextLabel', 'jobKey'];
  const code = ts.transpileModule('class Page {\n' + methods('pages/NovelPolishPage.ets', names) + '\n}\nreturn Page;',
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const Page = new Function('getNovelCreation', 'loadNovelPolishContext', 'novelChapterOrdinal', 'projectPolishOutcomes', code)(
    () => creation, async () => options, novelChapterOrdinal, projectPolishOutcomes);
  const page = new Page();
  Object.assign(page, { alive: true, visible: true, pageToken: 0, loadToken: 0, pollToken: 0,
    projectId: 'project', project, branchName: '主线', cas, loading: false, busy: false, initialized: true,
    modelReady: true, modelError: '', ordinaryRunning: false, selectedIds: ['c1', 'c2', 'c3'], retrySelectedIds: [],
    retryInitializedJobId: '', preview: null, job: null, includePlot: true, includeForeshadows: true,
    includeCharacters: true, includeDecisions: true, error: '', notice: '', schedulePoll() {}, stopPolling() {} });
  return page;
}

test('sparse chapter selection and context changes invalidate the frozen preview', () => {
  const page = fixture(); page.preview = receipt;
  page.toggleChapter('c2');
  assert.deepEqual(page.selectedIds, ['c1', 'c3']); assert.equal(page.preview, null);
  page.preview = receipt; page.setOption('characters', false);
  assert.equal(page.includeCharacters, false); assert.equal(page.preview, null);
  page.ordinaryRunning = true; page.toggleChapter('c1');
  assert.deepEqual(page.selectedIds, ['c1', 'c3']);
});

test('preview uses exact chapter IDs and rejects a changed workspace without consuming a fresh CAS', async () => {
  const calls = [];
  const page = fixture({ previewPolishSelected: async (...args) => { calls.push(args); return receipt; } });
  page.selectedIds = ['c1', 'c3']; await page.prepare();
  assert.deepEqual(calls, [['project', ['c1', 'c3'], options]]); assert.equal(page.preview, receipt);
  const stale = fixture({ previewPolishSelected: async () => ({ ...receipt, cas: { ...cas, head: 'changed' } }) });
  await stale.prepare(); assert.equal(stale.preview, null); assert.equal(stale.cas, cas); assert.match(stale.error, /工作区已变化/);
});

test('start uses only the reviewed receipt IDs, context, and frozen CAS', async () => {
  const calls = [];
  const page = fixture({ startPolishSelected: async (...args) => { calls.push(args); return { ...job, stage: 'queued' }; } });
  page.preview = receipt; await page.start();
  assert.deepEqual(calls, [['project', ['c1', 'c3'], options, cas]]);
  assert.equal(page.preview, null); assert.equal(page.job.stage, 'queued'); assert.equal(page.busy, false);
});

test('failed start preserves preview and selection; missing model prevents starting', async () => {
  let starts = 0;
  const page = fixture({ startPolishSelected: async () => { starts++; throw Error('CAS 已变化'); } });
  page.preview = receipt; await page.start();
  assert.equal(page.preview, receipt); assert.deepEqual(page.selectedIds, ['c1', 'c2', 'c3']); assert.match(page.error, /CAS/);
  page.modelReady = false; await page.start(); assert.equal(starts, 1);
});

test('failed-set selection excludes successes and drift skips and passes only explicit selected IDs', async () => {
  const calls = [];
  const page = fixture({ retryPolish: async (...args) => { calls.push(args); return { ...job, stage: 'queued' }; } });
  page.applyJob(job);
  assert.deepEqual(page.retrySelectedIds, ['c2']); assert.deepEqual(page.retryable().map(result => result.chapterId), ['c2']);
  await page.retryFailed(); assert.deepEqual(calls, [['project', 'job', ['c2']]]);
  assert.match(page.notice, /成功及剧情漂移跳过/);
});

test('cancelled batch includes unprocessed chapters; reload does not undo author retry deselection', () => {
  const page = fixture();
  const cancelled = { ...job, stage: 'cancelled', outcomes: [outcome('c1', 'success'), outcome('c2', 'failed'), outcome('c3', 'unprocessed')] };
  page.applyJob(cancelled); assert.deepEqual(page.retrySelectedIds, ['c2', 'c3']);
  page.retrySelectedIds = ['c3']; page.applyJob({ ...cancelled, updatedAt: 101 });
  assert.deepEqual(page.retrySelectedIds, ['c3']);
});

test('late start and poll results do not overwrite a hidden or newer page lifecycle', async () => {
  let finish;
  const pending = new Promise(resolve => { finish = resolve; });
  const page = fixture({ startPolishSelected: async () => pending, polishJob: async () => pending });
  page.preview = receipt; const started = page.start(); page.visible = false; page.pageToken++;
  finish({ ...job, stage: 'queued' }); await started;
  assert.equal(page.job, null); assert.equal(page.preview, receipt); assert.equal(page.busy, false);
  page.visible = true; page.job = job; page.pollToken = 2;
  await page.poll(1); assert.equal(page.job, job);
});

test('late author preview does not write into a hidden page', async () => {
  let finish;
  const page = fixture({ previewPolishSelected: () => new Promise(resolve => { finish = resolve; }) });
  const preparing = page.prepare(); page.visible = false; page.pageToken++;
  finish(receipt); await preparing;
  assert.equal(page.preview, null); assert.equal(page.error, ''); assert.equal(page.busy, false);
});

test('completion and cancellation refresh changed chapter snapshots before another preview', async () => {
  const updated = { ...project, chapters: project.chapters.map(chapter => ({ ...chapter, content: '已润色正文' })) };
  const updatedCas = { ...cas, head: 'polished-head', treeDigest: 'polished-tree' };
  const creation = { polishJob: async () => job,
    readWorkspaceSnapshot: async () => ({ project: updated, status: { cas: updatedCas, activeBranchId: 'main', activeBranchName: '主线' } }),
    activeRun: () => null, validateGhostwriteModels: async () => {}, cancelPolish: async () => ({ ...job, stage: 'cancelled' }) };
  const page = fixture(creation); page.job = { ...job, stage: 'polishing' };
  await page.poll(0);
  assert.equal(page.cas, updatedCas); assert.equal(page.project, updated); assert.equal(page.canPrepare(), true);
  page.cas = cas; page.project = project; page.job = { ...job, stage: 'polishing' };
  await page.control('cancel');
  assert.equal(page.cas, updatedCas); assert.equal(page.project, updated); assert.equal(page.busy, false);
});

test('read-only current job is retained when models unavailable, and branch switch resets only chapter selection/preview', async () => {
  const page = fixture({ readWorkspaceSnapshot: async () => ({ project, status: { cas: { ...cas, branchId: 'other' }, activeBranchId: 'other', activeBranchName: '支线' } }),
    polishJob: async () => ({ ...job, branchId: 'other' }), activeRun: () => null,
    validateGhostwriteModels: async () => { throw Error('未配置职责模型'); } });
  page.preview = receipt; await page.reload();
  assert.deepEqual(page.selectedIds, []); assert.equal(page.preview, null);
  assert.equal(page.job.branchId, 'other'); assert.equal(page.modelReady, false); assert.match(page.modelError, /未配置/);
});

test('context labels distinguish mandatory creative materials and reader version key tracks same-ID outcome changes', () => {
  const page = fixture(); assert.equal(page.contextLabel('material'), '必需创作资料');
  assert.notEqual(page.jobKey(job), page.jobKey({ ...job, outcomes: [outcome('c1', 'success'), outcome('c2', 'success'), outcome('c3', 'driftSkipped')] }));
});

function renderableBuilders(source) {
  const pattern = /\b(Row|Column|Scroll)\(\)\s*\{/g;
  let result = '', cursor = 0, match;
  while ((match = pattern.exec(source))) {
    const open = source.indexOf('{', match.index);
    let close = open + 1, depth = 1;
    for (; depth && close < source.length; close++) {
      if (source[close] === '{') depth++;
      if (source[close] === '}') depth--;
    }
    result += source.slice(cursor, match.index) + match[1] + '().children(() => {'
      + renderableBuilders(source.slice(open + 1, close - 1)) + '})';
    cursor = close; pattern.lastIndex = close;
  }
  return result + source.slice(cursor);
}
const cardNames = ['jobBody', 'header', 'progressSection', 'stageSection', 'warningSection', 'candidateSection',
  'reviewSection', 'failureSection', 'resultSection', 'outcomeRow', 'detailToggle', 'actionRow', 'smallButton',
  'progressBar', 'processedCount', 'incompleteCount', 'currentChapterLabel', 'progressPercent', 'stageFlow',
  'stageLabel', 'stageDescription', 'stageColor', 'warningLabel', 'reviewSummary', 'findingLabel', 'showResults',
  'outcomeLabel', 'resultSummary', 'targetTitle', 'toggleDetail', 'performAction'];
const cardCode = ts.transpileModule('class Card {\n' + renderableBuilders(methods('components/NovelPolishJobCard.ets', cardNames)) + '\n}\nreturn Card;',
  { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
const renderedNodes = [];
function node(type, value) {
  const record = { type, value, attrs: {} }; renderedNodes.push(record);
  const proxy = new Proxy({}, { get: (_, key) => (...args) => {
    if (key === 'children') args[0](); else record.attrs[key] = args[0];
    return proxy;
  } });
  return proxy;
}
const effect = { combine() { return this; }, animation() { return this; } };
const cardEnv = { projectPolishOutcomes, Text: value => node('Text', value), Row: () => node('Row'), Column: () => node('Column'),
  Scroll: () => node('Scroll'), Blank: () => {}, ForEach: (items, render) => items.forEach(render),
  FontWeight: { Bold: 'bold', Medium: 'medium' }, TextOverflow: { Ellipsis: 'ellipsis' }, TextAlign: { Center: 'center' },
  HorizontalAlign: { Start: 'start', End: 'end' }, VerticalAlign: { Center: 'center' }, FlexAlign: { End: 'end' },
  TransitionEffect: { OPACITY: effect, translate: () => effect }, MOTION_OVERLAY: 200, Curve: { EaseOut: 'easeout' },
  ACCENT: 'accent', ACCENT_INK: 'accentink', SURFACE: 'surface', SURFACE2: 'surface2', ERROR: 'error',
  INK: 'ink', INK2: 'ink2', INK3: 'ink3', INK4: 'ink4', LINE: 'line', RAISED: 'raised' };
const Card = new Function(...Object.keys(cardEnv), cardCode)(...Object.values(cardEnv));
const textValues = () => renderedNodes.filter(item => item.type === 'Text').map(item => item.value);
function cardFixture(currentJob = job) {
  const card = new Card(); Object.assign(card, { job: currentJob, busy: false, retryReady: true,
    candidateExpanded: true, reviewExpanded: true, resultsExpanded: true,
    getUIContext: () => ({ animateTo: (_, action) => action() }), onPause() {}, onResume() {}, onRetry() {}, onCancel() {} });
  return card;
}
test('job card distinguishes all per-chapter outcomes, including a completed batch with failures', () => {
  const card = new Card();
  assert.equal(card.resultSummary(job), '1 已收录 · 1 漂移跳过 · 1 失败 · 0 未处理');
  assert.equal(card.outcomeLabel(outcome('c3', 'driftSkipped')), '剧情漂移，跳过并保留原稿');
  assert.equal(card.targetTitle(job, 'c2'), '章2');
});

test('actual failed-job buttons react to cleared selection, reselected IDs, and busy prop', () => {
  const page = fixture(); page.applyJob({ ...job, stage: 'failed' });
  const card = cardFixture(page.job);
  const render = () => {
    renderedNodes.length = 0;
    card.retryReady = page.retrySelectedIds.length > 0 && page.job.stage === 'failed';
    card.actionRow();
    return renderedNodes.find(item => item.value === '重试').attrs.enabled;
  };
  assert.equal(render(), true);
  page.retrySelectedIds = []; assert.equal(render(), false);
  page.retrySelectedIds = ['c2']; assert.equal(render(), true);
  card.busy = true; assert.equal(render(), false);
  const cardSource = fs.readFileSync(path.join(__dirname, '../main/ets/components/NovelPolishJobCard.ets'), 'utf8');
  assert.match(cardSource, /@Prop retryReady: boolean = true/);
});

test('actual progress builder counts retained chapter outcomes while selected retry cursor starts at zero', () => {
  const retry = { ...job, stage: 'queued', cursor: 0, progress: [{ chapterId: 'c1', chapterOrdinal: 1 }],
    outcomes: [outcome('c1', 'success'), outcome('c2', 'unprocessed'), outcome('c3', 'failed')] };
  const card = cardFixture(retry); renderedNodes.length = 0; card.progressSection();
  assert.ok(textValues().includes('2/3'));
  assert.ok(renderedNodes.some(item => item.type === 'Row' && item.attrs.width === '66%'));
  assert.equal(card.processedCount(retry), 2); assert.equal(card.incompleteCount(retry), 1);
  assert.equal(card.currentChapterLabel(retry), '第 1 章');
  const completed = { ...retry, stage: 'completed', outcomes: [outcome('c1', 'success'), outcome('c2', 'driftSkipped'), outcome('c3', 'failed')] };
  assert.equal(card.processedCount(completed), 3); assert.equal(card.incompleteCount(completed), 0);
});

test('same card instance reads live same-ID job through every nested builder without remounting', () => {
  const targets = [job.targets[0], job.targets[2]];
  const first = { ...job, targets, cursor: 0, stage: 'writing', warnings: [], candidate: null, review: null, failure: null,
    outcomes: [outcome('c1', 'unprocessed'), outcome('c3', 'unprocessed')] };
  const card = cardFixture(first); renderedNodes.length = 0; card.jobBody();
  assert.ok(textValues().includes('0/2')); assert.ok(textValues().includes('第 1 章'));
  card.job = { ...first, cursor: 1, warnings: [{ kind: 'unresolved', message: '新的提醒' }],
    candidate: { chapterOrdinal: 3, attempt: 1, content: '新候选正文' },
    review: { rewriteInstructions: '新重写说明', findings: [], rewriteRequired: true, blocking: false },
    failure: '第一章失败详情', outcomes: [outcome('c1', 'failed', '失败详情'), outcome('c3', 'unprocessed')] };
  renderedNodes.length = 0; card.jobBody();
  const writing = textValues();
  assert.ok(writing.includes('1/2')); assert.ok(writing.includes('第 3 章'));
  assert.ok(writing.includes('第 3 章 · 自动返修 1 次')); assert.ok(writing.includes('新候选正文'));
  assert.ok(writing.includes('需重写 · 0 项发现')); assert.ok(writing.includes('重写说明：新重写说明'));
  assert.ok(writing.includes('第一章失败详情')); assert.ok(writing.some(value => value.includes('新的提醒')));
  card.job = { ...card.job, stage: 'completed', cursor: 2,
    outcomes: [outcome('c1', 'failed', '失败详情'), outcome('c3', 'success')] };
  renderedNodes.length = 0; card.jobBody();
  assert.ok(textValues().includes('2/2')); assert.ok(textValues().includes('1 已收录 · 0 漂移跳过 · 1 失败 · 0 未处理'));
  assert.ok(textValues().includes('失败详情')); assert.ok(textValues().includes('已润色并收录'));
  assert.equal(card.candidateExpanded, true); assert.equal(card.reviewExpanded, true); assert.equal(card.resultsExpanded, true);
  renderedNodes.length = 0; card.detailToggle('results');
  renderedNodes.find(item => item.value === '收起').attrs.onClick(); assert.equal(card.resultsExpanded, false);
  renderedNodes.length = 0; card.detailToggle('results'); assert.ok(textValues().includes('查看'));
  const source = fs.readFileSync(path.join(__dirname, '../main/ets/components/NovelPolishJobCard.ets'), 'utf8');
  assert.doesNotMatch(source, /@Builder\s+private \w+\(job:/);
  assert.doesNotMatch(source, /this\.(?:jobBody|progressSection|progressBar|actionRow)\(this\.job/);
});

async function productionFixture() {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const initial = makeNovelProject({ id: 'ui-polish', name: '润色真实事务', now: 100,
    modelPolicy: { writing: { kind: 'global' }, review: { kind: 'global' }, stateSync: null } });
  initial.polishPreference = '项目约定文风';
  initial.chapters = [1, 2, 3].map(n => makeNovelChapter({ id: `c${n}`, title: `章${n}`, content: `正文${n}`, now: 100 }));
  await repository.createProject(initial);
  const creation = createNovelCreation({ repository, loadPolishPreference: async () => '应用偏好不应覆盖项目',
    modelRunning: { validate: async () => {}, start: () => ({ subscribe: cb => {
      queueMicrotask(() => cb({ kind: 'failed', message: '测试模型不生成正文' })); return () => {};
    } }), cancel() {} } });
  const page = fixture(creation); page.projectId = initial.id; page.project = null; page.cas = null;
  await page.reload(); page.selectedIds = ['c1', 'c3'];
  return { page, creation, repository };
}

test('real UI preview/start freezes sparse IDs and project preference into repository job', async () => {
  const { page, repository } = await productionFixture();
  await page.prepare(); assert.equal(page.error, '');
  assert.deepEqual(page.preview.chapterIds, ['c1', 'c3']);
  assert.equal(page.preview.preferenceSource, 'project'); assert.equal(page.preview.polishPreference, '项目约定文风');
  const shown = page.preview; await page.start(); assert.equal(page.error, '');
  const persisted = await repository.loadPolishJob('ui-polish', page.job.jobId);
  assert.deepEqual(persisted.targets.map(target => target.id), shown.chapterIds);
  assert.equal(persisted.polishPreferenceAtStart, shown.polishPreference);
  assert.deepEqual(persisted.contextSnapshot, shown.contextSnapshot);
});

test('project preference change after actual preview rejects start and keeps reviewed draft', async () => {
  const { page, creation, repository } = await productionFixture();
  await page.prepare(); const shown = page.preview;
  await creation.setProjectPolishPreference('ui-polish', '改变后的文风', shown.cas);
  await page.start();
  assert.match(page.error, /工作区|CAS/); assert.equal(page.preview, shown);
  assert.equal((await repository.listPolishJobs('ui-polish')).length, 0);
});
