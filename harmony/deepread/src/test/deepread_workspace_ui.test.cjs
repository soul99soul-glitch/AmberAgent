const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const source = fs.readFileSync(path.resolve(__dirname, '../../../entry/src/main/ets/pages/DeepReadArticlePage.ets'), 'utf8');
function method(name) {
  const start = source.search(new RegExp('^  private (?:async )?' + name + '\\(', 'm'));
  assert.ok(start >= 0);
  const open = source.indexOf('{', start); let depth = 1, end = open + 1;
  for (; depth && end < source.length; end++) { if (source[end] === '{') depth++; if (source[end] === '}') depth--; }
  return source.slice(start, end);
}
function harness(store) {
  const toasts = [];
  const context = { module: { exports: {} }, Promise, String,
    getDeepReadArtifactStore: () => store,
    promptAction: { showToast: ({ message }) => toasts.push(message) },
    getDeepReadScheduler: () => { throw Error('Workspace retry must not start generation'); },
  };
  const code = 'module.exports = class { ' + ['applyWorkspaceStatus', 'refreshWorkspaceStatus', 'retryWorkspaceSave'].map(method).join('\n') + ' };';
  vm.runInNewContext(ts.transpileModule(code, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText, context);
  const page = Object.assign(new context.module.exports(), { pageAlive: true, pageToken: 2, topicId: 'topic',
    workspaceRequest: 0, workspaceSaveOwner: 0, workspaceError: '', workspaceSaving: false, running: false,
    output: { summary: 'saved article' },
  });
  return { page, toasts };
}
const status = (error = null, statusError = null) => ({ path: 'deepread/topic.md', savedAt: 10, articleUpdatedAt: 10, error, statusError });

test('Workspace retry preserves article and reports actual write or receipt failure', async () => {
  let calls = 0;
  const { page, toasts } = harness({ save: async id => { calls++; assert.equal(id, 'topic'); return status('permission denied'); } });
  const article = page.output;
  await page.retryWorkspaceSave();
  assert.equal(page.output, article); assert.equal(calls, 1); assert.equal(page.workspaceSaving, false);
  assert.match(page.workspaceError, /文章已保留.*permission denied/); assert.equal(toasts.length, 0);
  page.applyWorkspaceStatus(status(null, 'receipt write rejected'));
  assert.match(page.workspaceError, /保存状态记录失败/); assert.doesNotMatch(page.workspaceError, /工作区保存未完成/);
});

test('duplicate retry and active generation are gated; a successful independent save clears error', async () => {
  let done, calls = 0;
  const { page, toasts } = harness({ save: () => { calls++; return new Promise(resolve => { done = resolve; }); } });
  page.running = true; await page.retryWorkspaceSave(); assert.equal(calls, 0);
  page.running = false; page.workspaceError = 'old failure'; const pending = page.retryWorkspaceSave();
  await page.retryWorkspaceSave(); assert.equal(calls, 1);
  done(status()); await pending;
  assert.equal(page.workspaceError, ''); assert.equal(page.workspaceSaving, false); assert.deepEqual(toasts, ['已保存到工作区']);
});

test('late Workspace status and save results do not update a departed reader', async () => {
  let done;
  const { page, toasts } = harness({ save: () => new Promise(resolve => { done = resolve; }) });
  const pending = page.retryWorkspaceSave(); page.pageAlive = false; done(status('late failure')); await pending;
  assert.equal(page.workspaceError, ''); assert.equal(toasts.length, 0);
});

test('latest status read wins and concurrent status refresh does not strand the save busy flag', async () => {
  const reads = []; let saveDone;
  const { page } = harness({ getStatus: () => new Promise(resolve => reads.push(resolve)),
    save: () => new Promise(resolve => { saveDone = resolve; }),
  });
  const first = page.refreshWorkspaceStatus(2), second = page.refreshWorkspaceStatus(2);
  reads[1](status('current')); await second; reads[0](status('obsolete')); await first;
  assert.match(page.workspaceError, /current/); assert.doesNotMatch(page.workspaceError, /obsolete/);
  const save = page.retryWorkspaceSave(); const refresh = page.refreshWorkspaceStatus(2);
  reads[2](status()); await refresh; saveDone(status()); await save;
  assert.equal(page.workspaceSaving, false);
});
