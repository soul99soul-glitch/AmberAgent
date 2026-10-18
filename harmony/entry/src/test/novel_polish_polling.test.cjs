const { test } = require('node:test');
const assert = require('node:assert/strict');
const { actualPage } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');

function polishPage(creation) {
  const timers = new Map();
  let timerId = 0;
  const page = actualPage('pages/NovelPolishPage.ets',
    ['terminal', 'running', 'stopPolling', 'schedulePoll', 'poll'], {
      getNovelCreation: () => creation,
      setTimeout: action => { timers.set(++timerId, action); return timerId; },
      clearTimeout: id => timers.delete(id),
    });
  Object.assign(page, { alive: true, visible: true, projectId: 'project',
    job: { jobId: 'polish', stage: 'waiting_system' }, pollToken: 0, pollTimer: -1,
    applyJob(job) { this.job = job; }, reload: async () => {},
  });
  return { page, timers };
}

function workspacePage(creation) {
  const timers = [];
  const page = actualPage('pages/NovelWorkspacePage.ets',
    ['polishStageRuns', 'polishRuns', 'schedulePolishPoll', 'pollPolish', 'onPageHide'], {
      getNovelCreation: () => creation,
      setTimeout: action => { timers.push(action); return timers.length; },
    });
  Object.assign(page, { pageAlive: true, studioPageVisible: true, projectId: 'project', requestedPolishJobId: '',
    polishJob: { jobId: 'polish', stage: 'waiting_system', progress: [] }, polishPollToken: 0,
    closeControlSheet() {}, clearStudioFeedback() {}, flushComposerDraft() {}, restoreKeyboardAvoidance() {},
  });
  return { page, timers };
}

test('polish page observes waiting_system through external resume and completion without treating it as running', async () => {
  let durable = { jobId: 'polish', stage: 'writing' };
  const { page, timers } = polishPage({ polishJob: async () => durable });
  assert.equal(page.running(page.job), false);
  page.schedulePoll();
  assert.equal(timers.size, 1, 'waiting_system must keep observing background work');
  await page.poll(page.pollToken);
  assert.equal(page.job.stage, 'writing');
  assert.equal(page.running(page.job), true);
  assert.equal(timers.size, 1);
  let reloads = 0;
  page.reload = async () => { reloads++; page.stopPolling(); };
  durable = { jobId: 'polish', stage: 'completed' };
  await page.poll(page.pollToken);
  assert.equal(reloads, 1);
  assert.equal(timers.size, 0);
});

test('workspace page observes waiting_system through external resume and completion while keeping execution eligibility', async () => {
  let durable = { jobId: 'polish', stage: 'writing', progress: [] };
  const { page, timers } = workspacePage({ polishJob: async () => durable,
    open: async () => ({ chapters: [] }), workspaceStatus: async () => ({ activeBranchId: 'main' }) });
  assert.equal(page.polishRuns(), false);
  page.schedulePolishPoll();
  assert.equal(timers.length, 1, 'waiting_system must keep observing background work');
  await page.pollPolish(page.polishPollToken);
  assert.equal(page.polishJob.stage, 'writing');
  assert.equal(page.polishRuns(), true);
  assert.equal(timers.length, 2);
  durable = { jobId: 'polish', stage: 'completed', progress: [{}] };
  await page.pollPolish(page.polishPollToken);
  assert.equal(page.polishJob.stage, 'completed');
  assert.equal(timers.length, 2);
});

test('polish polling stops for paused failed terminal jobs and hidden pages', () => {
  for (const stage of ['paused', 'failed', 'cancelled', 'completed']) {
    const polish = polishPage({});
    polish.page.job.stage = stage;
    polish.page.schedulePoll();
    assert.equal(polish.timers.size, 0, stage);
    const workspace = workspacePage({});
    workspace.page.polishJob.stage = stage;
    workspace.page.schedulePolishPoll();
    assert.equal(workspace.timers.length, 0, stage);
  }
  const polish = polishPage({});
  polish.page.visible = false;
  polish.page.schedulePoll();
  assert.equal(polish.timers.size, 0);
  const workspace = workspacePage({});
  workspace.page.studioPageVisible = false;
  workspace.page.polishJob.stage = 'writing';
  workspace.page.schedulePolishPoll();
  assert.equal(workspace.timers.length, 0);
});

test('already scheduled polish timers ignore a hidden page', () => {
  let reads = 0;
  const creation = { polishJob: async () => { reads++; return null; } };
  const polish = polishPage(creation);
  polish.page.job.stage = 'writing';
  polish.page.schedulePoll();
  polish.page.visible = false;
  [...polish.timers.values()][0]();
  const workspace = workspacePage(creation);
  workspace.page.polishJob.stage = 'writing';
  workspace.page.schedulePolishPoll();
  workspace.page.studioPageVisible = false;
  workspace.timers[0]();
  assert.equal(reads, 0);
});

test('workspace hide invalidates a pending polish refresh before the page is shown again', async () => {
  let release;
  const reading = new Promise(resolve => { release = resolve; });
  const { page, timers } = workspacePage({ polishJob: () => reading });
  const pending = page.pollPolish(page.polishPollToken);
  const original = page.polishJob;
  page.onPageHide();
  page.studioPageVisible = true;
  release({ jobId: 'polish', stage: 'writing', progress: [] });
  await pending;
  assert.equal(page.polishJob, original);
  assert.equal(timers.length, 0);
});

test('workspace hide prevents an old progress refresh from replacing the newly shown workspace', async () => {
  let release;
  let opened;
  const atOpen = new Promise(resolve => { opened = resolve; });
  const reading = new Promise(resolve => { release = resolve; });
  const { page } = workspacePage({
    polishJob: async () => ({ jobId: 'polish', stage: 'writing', progress: [] }),
    open: () => { opened(); return reading; },
    workspaceStatus: async () => ({ activeBranchId: 'old' }),
  });
  const pending = page.pollPolish(page.polishPollToken);
  await atOpen;
  page.onPageHide();
  page.studioPageVisible = true;
  page.project = { id: 'new' };
  page.workspaceStatus = { activeBranchId: 'new' };
  release({ id: 'old' });
  await pending;
  assert.equal(page.project.id, 'new');
  assert.equal(page.workspaceStatus.activeBranchId, 'new');
});
