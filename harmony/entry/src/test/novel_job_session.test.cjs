const { test } = require('node:test');
const assert = require('node:assert/strict');
const { session } = require('./novel_job_session_fixture.cjs');
test('hidden ghostwrite progress read cannot overwrite a newer shown workspace after durable completion', async () => {
  let release, reached;
  const atOpen = new Promise(resolve => { reached = resolve; });
  const delayed = new Promise(resolve => { release = resolve; });
  const { owner, state } = session({
    ghostwriteJob: async () => ({ jobId: 'job', stage: 'writing', progress: [{}] }),
    open: () => { reached(); return delayed; }, workspaceStatus: async () => ({ activeBranchId: 'main' }),
  });
  owner.ghostwriteJob = { jobId: 'job', stage: 'writing', progress: [] };
  const pending = owner.pollGhostwrite(owner.ghostwritePollToken);
  await atOpen;
  owner.hide(); owner.show();
  owner.ghostwriteJob = null;
  state.project = { id: 'p', chapters: [{ id: 'chapter', content: '后台任务完成后的新稿' }] };
  owner.scheduleGhostwritePoll();
  release({ id: 'p', chapters: [{ id: 'chapter', content: '旧轮询先前读取的旧稿' }] });
  await pending;
  assert.equal(state.project.chapters[0].content, '后台任务完成后的新稿');
  assert.equal(owner.ghostwriteJob, null);
});

test('hidden ghostwrite completion report read cannot overwrite the newer shown report', async () => {
  let release, reached;
  const atReport = new Promise(resolve => { reached = resolve; });
  const delayed = new Promise(resolve => { release = resolve; });
  const { owner } = session({ ghostwriteJob: async () => null,
    latestGhostwriteReport: () => { reached(); return delayed; },
    open: async () => ({ id: 'p', chapters: [] }), workspaceStatus: async () => ({ activeBranchId: 'main' }) });
  owner.ghostwriteJob = { jobId: 'job', stage: 'writing', progress: [] };
  const pending = owner.pollGhostwrite(owner.ghostwritePollToken);
  await atReport;
  owner.hide(); owner.show();
  const current = { jobId: 'completed-new' };
  owner.ghostwriteReport = current;
  release({ jobId: 'completed-old' });
  await pending;
  assert.equal(owner.ghostwriteReport, current);
});
