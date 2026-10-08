const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const stages = ['OVERVIEW', 'NARRATIVE', 'ANALYSIS', 'EXTENDED_READING'];
const source = fs.readFileSync(path.join(__dirname, '../main/ets/pages/DeepReadArticlePage.ets'), 'utf8');
const helpers = fs.readFileSync(path.join(__dirname, '../../../deepread/src/main/ets/domain/helpers.ts'), 'utf8');
function method(name) {
  const match = source.match(new RegExp('^  (?:private )?(?:async )?' + name + '\\([\\s\\S]*?^  }', 'm'));
  assert.ok(match, name + ' exists in the production controller');
  return match[0];
}
function fixture(resultPromise = Promise.resolve({ ok: true, output: output() }), repository = {}) {
  const helperBody = helpers.slice(helpers.indexOf('export const statusOf'), helpers.indexOf('export const firstFailureMessage'))
    .replace(/export /g, '');
  const methods = ['clearEditorialMotion', 'editorialActive', 'celebratePublished', 'onPageHide', 'onPageShow',
    'runNow', 'retryFirstFailure', 'subscribeToUpdates', 'aboutToDisappear', 'celebrateObservedRun', 'sourceFailureCount',
    'showNewsroom', 'cancelGeneration'].map(method).join('\n');
  const js = ts.transpileModule(helperBody + '\nclass Page {' + methods + '} return Page;',
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  let scheduler;
  const Page = new Function('STAGE_ORDER', 'getProductKind', 'getDeepReadScheduler', 'getRepository', 'firstFailedStage', js)(
    stages, () => 'deepread', () => Promise.resolve(scheduler), () => repository,
    value => stages.find(stage => value.sectionStates[stage]?.status === 'FAILED') ?? null);
  const page = new Page();
  Object.assign(page, { dockScroll: { reset() {} }, pageAlive: true, pageVisible: true, appBackgrounded: false, pageToken: 1, motionCycle: 0,
    completionStamp: 0, publicationRequest: 0, observedRunStart: 0, observedRunCycle: -1, publishedRunStart: 0, topicId: 'this-topic', title: 'title', sourceUrl: '', seedUrls: [], output: output(),
    cancelled: false, runStartsUnreadable: false, unsubscribeActivity: null, unsubscribeOutput: null, unsubscribeRunning: null,
    scheduler: { run: () => resultPromise }, newsroomStage: '', newsroomLabel: '' });
  scheduler = page.scheduler;
  scheduler.runSection = () => resultPromise;
  page.hasReadableOutput = () => true;
  page.stageLabel = stage => stage;
  page.progressLabel = label => label;
  page.phaseLabel = () => '完成';
  page.applyOutput = value => { page.output = value; };
  page.refreshWorkspaceStatus = () => {};
  return page;
}
function output(complete = true) {
  return { generationComplete: complete, sectionStates: Object.fromEntries(stages.map(stage => [stage, { status: 'READY' }])) };
}

test('force regeneration failure or abort cannot celebrate a retained complete article', async () => {
  for (const error of ['provider failed', 'aborted']) {
    const old = output();
    const page = fixture(Promise.resolve({ ok: false, error, output: old }));
    await page.runNow(1, true);
    assert.equal(page.output, old);
    assert.equal(page.completionStamp, 0);
    assert.equal(page.running, false);
    assert.equal(page.errorMsg, error === 'aborted' ? '生成已取消' : error);
  }
});

test('only a successful complete persisted result receives the publication stamp', async () => {
  const page = fixture();
  await page.runNow(1, true);
  assert.equal(page.completionStamp, 1);
  const partial = fixture(Promise.resolve({ ok: true, output: output(false) }));
  await partial.runNow(1, false);
  assert.equal(partial.completionStamp, 0);
  const missingSection = output();
  missingSection.sectionStates.ANALYSIS.status = 'FAILED';
  const incomplete = fixture(Promise.resolve({ ok: true, output: missingSection }));
  await incomplete.runNow(1, false);
  assert.equal(incomplete.completionStamp, 0);
});

test('leave and return suppresses a late celebration while allowing the article result to settle', async () => {
  let resolve;
  const pending = new Promise(value => { resolve = value; });
  const page = fixture(pending);
  const work = page.runNow(1, true);
  page.onPageHide(); page.onPageShow();
  assert.equal(page.pageToken, 1, 'UI visibility does not cancel or replace the business request');
  const finished = output(); resolve({ ok: true, output: finished });
  await work;
  assert.equal(page.output, finished);
  assert.equal(page.running, false);
  assert.equal(page.completionStamp, 0);
});

test('background or theme changes clear the trigger and reject a late completion from that visual cycle', () => {
  const page = fixture();
  const cycle = page.motionCycle;
  page.completionStamp = 1; page.clearEditorialMotion();
  page.celebratePublished({ ok: true, output: output() }, cycle);
  assert.equal(page.completionStamp, 0);
  page.appBackgrounded = true;
  page.celebratePublished({ ok: true, output: output() }, page.motionCycle);
  assert.equal(page.completionStamp, 0);
});

test('newsroom observes only this topic, never creates completion stamps, and releases all listeners', () => {
  const page = fixture();
  let update, stopped = 0;
  page.scheduler.observeActiveRuns = () => ({ subscribe: callback => { update = callback; return () => { stopped++; }; } });
  page.scheduler.observeRunning = () => ({ subscribe: () => () => { stopped++; } });
  const repository = { observe: () => ({ subscribe: () => () => { stopped++; } }) };
  page.subscribeToUpdates(repository, 1);
  update([{ topicId: 'other-topic', stage: 'WRITING', label: 'analysis' }]);
  assert.equal(page.newsroomStage, '');
  update([{ topicId: 'this-topic', stage: 'VERIFYING', label: '校验并保存' }]);
  assert.equal(page.newsroomStage, 'VERIFYING');
  assert.equal(page.newsroomLabel, '校验并保存');
  update([{ topicId: 'this-topic', stage: 'COMPLETE', label: '已完成' }]);
  assert.equal(page.completionStamp, 0, 'activity replay is never a success trigger');
  page.aboutToDisappear();
  assert.equal(stopped, 3);
  update([{ topicId: 'this-topic', stage: 'COLLECTING', label: 'late' }]);
  assert.equal(page.newsroomStage, 'COMPLETE');
});


test('section retry celebrates only when the whole article becomes complete', async () => {
  for (const complete of [false, true]) {
    const page = fixture(Promise.resolve({ ok: true, output: output(complete) }));
    page.output.sectionStates.ANALYSIS.status = 'FAILED';
    page.subscribeToUpdates = () => {};
    page.retryFirstFailure();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(page.completionStamp, complete ? 1 : 0);
    assert.equal(page.running, false);
  }
});

function streamingReader(initial) {
  let resolve, entryUpdate, runningUpdate, activityUpdate;
  const pending = new Promise(value => { resolve = value; });
  const page = fixture(pending, { listHistory: async () => [] });
  page.output = initial;
  page.hasReadableOutput = () => !!page.output?.summary?.trim();
  page.scheduler.abort = () => {};
  page.scheduler.observeActiveRuns = () => ({ subscribe: callback => { activityUpdate = callback; return () => {}; } });
  page.scheduler.observeRunning = () => ({ subscribe: callback => { runningUpdate = callback; return () => {}; } });
  page.subscribeToUpdates({ observe: () => ({ subscribe: callback => { entryUpdate = callback; return () => {}; } }) }, 1);
  return { page, resolve, save: value => entryUpdate({ output: value }), running: value => runningUpdate(value),
    phase: stage => activityUpdate([{ topicId: 'this-topic', startedAt: 99, stage, label: stage }]) };
}

test('a first draft stays in the newsroom through saved intermediate sections and VERIFYING, then exposes the finished article', async () => {
  const f = streamingReader(null);
  const work = f.page.runNow(1, false);
  assert.equal(f.page.runStartsUnreadable, true);
  for (const stage of ['PLANNING', 'WRITING', 'VERIFYING']) {
    f.save({ ...output(false), summary: '已经保存的概览', generationPhase: stage }); f.phase(stage);
    assert.equal(f.page.hasReadableOutput(), true, 'intermediate output remains in the real repository consumer');
    assert.equal(f.page.showNewsroom(), true, stage + ' remains visible before the run reaches its terminal result');
  }
  f.page.onPageHide(); f.page.onPageShow();
  assert.equal(f.page.showNewsroom(), true, 'returning to the same page does not reclassify overview as an old draft');
  const complete = { ...output(), summary: '已保存的完整正文' };
  f.save(complete);
  assert.equal(f.page.showNewsroom(), true, 'a saved output callback does not pretend the still running request is done');
  f.resolve({ ok: true, output: complete }); await work;
  assert.equal(f.page.running, false);
  assert.equal(f.page.showNewsroom(), false);
  assert.equal(f.page.output, complete);
});

test('first-draft failure and cancellation release readable partial output without clearing it or awaiting a fake READY phase', async () => {
  for (const cancel of [false, true]) {
    const f = streamingReader(null); const work = f.page.runNow(1, false);
    const partial = { ...output(false), summary: '已经保存的部分稿', generationPhase: 'WRITING' };
    f.save(partial); assert.equal(f.page.showNewsroom(), true);
    if (cancel) {
      f.page.cancelGeneration();
      assert.equal(f.page.showNewsroom(), false, 'cancel immediately reveals the actual partial output');
      f.running(true);
      assert.equal(f.page.running, false, 'a residual running signal cannot revive cancelled newsroom');
    }
    f.resolve({ ok: false, output: partial, error: cancel ? 'aborted' : '后续模型调用失败' }); await work;
    assert.equal(f.page.showNewsroom(), false);
    assert.equal(f.page.output, partial);
    assert.equal(f.page.errorMsg, cancel ? '生成已取消' : '后续模型调用失败');
  }
  const f = streamingReader(null); const work = f.page.runNow(1, false);
  f.running(false);
  assert.equal(f.page.showNewsroom(), false, 'a real observed terminal running state also releases the newsroom');
  f.resolve({ ok: false, output: null, error: '模型不可用' }); await work;
  assert.equal(f.page.hasReadableOutput(), false);
  assert.equal(f.page.errorMsg, '模型不可用');
});

test('existing complete and partial drafts keep their body during regeneration or continuation and ignore stale page callbacks', async () => {
  for (const complete of [false, true]) {
    const old = { ...output(complete), summary: '进入本轮前已有的稿件' };
    const f = streamingReader(old); const work = f.page.runNow(1, complete);
    assert.equal(f.page.runStartsUnreadable, false);
    f.save({ ...output(false), summary: '新的概览' }); f.phase('WRITING');
    assert.equal(f.page.showNewsroom(), false);
    f.page.pageToken++;
    const current = f.page.output;
    f.save({ ...output(), summary: '过期页面订阅结果' });
    f.resolve({ ok: true, output: old }); await work;
    assert.equal(f.page.output, current, 'obsolete repository and result callbacks cannot replace the live page snapshot');
  }
});


test('a reentered reader celebrates only a watched real COMPLETE signal after confirming persisted output, once', async () => {
  const saved = { output: output() };
  const page = fixture(undefined, { get: async () => saved });
  let update;
  page.scheduler.observeActiveRuns = () => ({ subscribe: callback => { update = callback; return () => {}; } });
  page.scheduler.observeRunning = () => ({ subscribe: () => () => {} });
  page.subscribeToUpdates({ observe: () => ({ subscribe: () => () => {} }) }, 1);
  update([{ topicId: 'this-topic', startedAt: 1, stage: 'COMPLETE', label: '已完成' }]);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(page.completionStamp, 0, 'initial terminal replay is not a watched transition');
  update([{ topicId: 'this-topic', startedAt: 2, stage: 'WRITING', label: '撰写分析' }]);
  update([{ topicId: 'this-topic', startedAt: 2, stage: 'COMPLETE', label: '已完成' }]);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(page.completionStamp, 1);
  page.celebratePublished({ ok: true, output: saved.output }, page.motionCycle);
  update([{ topicId: 'this-topic', startedAt: 2, stage: 'COMPLETE', label: '已完成' }]);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(page.completionStamp, 1, 'observer, promise and duplicate callback share one publication');
});

test('observed completion cannot celebrate a save failure, a hidden cycle or the previous job after replacement', async () => {
  for (const outcome of ['read-failed', 'partial', 'hidden', 'replaced']) {
    let finish;
    const read = new Promise(resolve => { finish = resolve; });
    const page = fixture(undefined, { get: () => outcome === 'read-failed' ? Promise.reject(new Error('RDB failed')) : read });
    page.observedRunStart = 10; page.observedRunCycle = page.motionCycle;
    const work = page.celebrateObservedRun({ topicId: 'this-topic', startedAt: 10, stage: 'COMPLETE' }, 1);
    if (outcome === 'hidden') page.onPageHide();
    if (outcome === 'replaced') page.observedRunStart = 11;
    if (outcome !== 'read-failed') finish({ output: output(outcome !== 'partial') });
    await work;
    assert.equal(page.completionStamp, 0, outcome);
  }
});

test('first drafts select first, tenth and hundredth seals from all retained complete articles; rewrites stay 付印', async () => {
  for (const count of [1, 10, 100, 101]) {
    const page = fixture(undefined, { listHistory: async limit => {
      assert.equal(limit, 0);
      return [...Array.from({ length: count }, () => ({ output: output() })), { output: output(false) }];
    } });
    page.celebratePublished({ ok: true, output: output() }, page.motionCycle, true);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(page.completionInscription, count === 1 ? '首篇' : count === 10 ? '十篇' : count === 100 ? '百篇' : '付印');
    page.celebratePublished({ ok: true, output: output() }, page.motionCycle, false);
    assert.equal(page.completionInscription, '付印');
  }
});

test('a late milestone lookup after leaving cannot replay a completion seal', async () => {
  let finish;
  const read = new Promise(resolve => { finish = resolve; });
  const page = fixture(undefined, { listHistory: () => read });
  page.celebratePublished({ ok: true, output: output() }, page.motionCycle, true);
  page.onPageHide(); page.onPageShow();
  finish([{ output: output() }]);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(page.completionStamp, 0);
});

test('source failure summary counts actual failed inputs in a complete article', () => {
  const page = fixture();
  page.output.inputSources = [{ status: 'ready' }, { status: 'failed' }, { status: 'pending' }, { status: 'failed' }];
  assert.equal(page.sourceFailureCount(), 2);
  page.output = null;
  assert.equal(page.sourceFailureCount(), 0);
});
