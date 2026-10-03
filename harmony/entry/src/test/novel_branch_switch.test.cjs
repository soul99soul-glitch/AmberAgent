const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const source = fs.readFileSync(path.join(__dirname, '../main/ets/pages/NovelWorkspacePage.ets'), 'utf8');

function method(name) {
  const match = new RegExp('^  (?:private )?(?:async )?' + name + '\\(', 'm').exec(source);
  assert.ok(match, 'missing actual page method ' + name);
  let depth = 1, end = source.indexOf('{', match.index) + 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(match.index, end);
}

function pageFor(creation, blocked = false) {
  const dialogs = [];
  const code = ts.transpileModule('class Page {\n' + ['selectWorkspaceBranch', 'stopAndSwitchWorkspaceBranch'].map(method).join('\n')
    + '\n}\nreturn Page;', { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const Page = new Function('getNovelCreation', 'AlertDialog', code)(() => creation, { show: dialog => dialogs.push(dialog) });
  const page = new Page();
  Object.assign(page, { projectId: 'project', busy: false, ghostwriteBusy: false, polishBusy: false,
    sendStarting: false, composerRestoring: false, ghostwriteJob: null, polishJob: null,
    workspaceStatus: { activeBranchId: 'main', branches: [{ id: 'alternate', name: '另一条故事线' }] },
    ghostwritePollToken: 1, polishPollToken: 1, branchSheetOpen: true,
    hasBlockingNovelRun: () => blocked, polishLocksWorkspace: () => true,
    reload: async () => {}, showWorkspaceOperationError: (_label, error) => { page.error = error.message; } });
  return { page, dialogs };
}

test('running task branch choice requires explicit stop confirmation and cancel leaves it running', async () => {
  const calls = [];
  const { page, dialogs } = pageFor({ stopActiveRun: async () => calls.push('stop'), switchBranch: async () => calls.push('switch') }, true);
  await page.selectWorkspaceBranch('alternate');
  assert.equal(dialogs.length, 1);
  assert.match(dialogs[0].message, /另一条故事线/);
  assert.deepEqual(calls, []);
  dialogs[0].primaryButton.action();
  assert.deepEqual(calls, []);
  assert.equal(page.branchSheetOpen, true);
});

for (const kind of ['ordinary', 'ghostwrite', 'polish']) {
  test(`${kind} cancellation finishes before actual branch switch reads fresh workspace`, async () => {
    const calls = [];
    let release;
    const stopped = new Promise(resolve => { release = resolve; });
    const creation = {
      stopActiveRun: async id => { assert.equal(id, 'project'); calls.push('ordinary-stop'); await stopped; },
      cancelGhostwrite: async (_id, jobId) => { assert.equal(jobId, 'job'); calls.push('ghostwrite-cancel'); },
      cancelPolish: async (_id, jobId) => { assert.equal(jobId, 'job'); calls.push('polish-cancel'); return { stage: 'cancelled' }; },
      switchBranch: async (id, branch) => { assert.equal(id, 'project'); assert.equal(branch, 'alternate'); calls.push('switch'); },
    };
    const { page } = pageFor(creation);
    if (kind === 'ghostwrite') page.ghostwriteJob = { jobId: 'job', stage: 'writing' };
    if (kind === 'polish') page.polishJob = { jobId: 'job', stage: 'rewriting' };
    page.reload = async () => { calls.push('reload'); };
    const switched = page.stopAndSwitchWorkspaceBranch('alternate');
    assert.deepEqual(calls, ['ordinary-stop']);
    assert.equal(page.busy, true);
    release(); await switched;
    assert.deepEqual(calls, ['ordinary-stop', ...(kind === 'ordinary' ? [] : [kind + '-cancel']), 'switch', 'reload']);
    assert.equal(page.busy, false);
    assert.equal(page.branchSheetOpen, false);
  });
}

test('cancellation failure keeps original branch selected and surfaces error without switching', async () => {
  let switches = 0;
  const { page } = pageFor({ stopActiveRun: async () => {}, cancelGhostwrite: async () => { throw new Error('无法取消'); },
    switchBranch: async () => { switches++; } });
  page.ghostwriteJob = { jobId: 'job', stage: 'writing' };
  await page.stopAndSwitchWorkspaceBranch('alternate');
  assert.equal(switches, 0);
  assert.equal(page.workspaceStatus.activeBranchId, 'main');
  assert.equal(page.branchSheetOpen, true);
  assert.equal(page.error, '无法取消');
  assert.equal(page.ghostwritePollToken, 1, 'failed cancel keeps existing progress subscription alive');
  assert.equal(page.busy, false);
});
