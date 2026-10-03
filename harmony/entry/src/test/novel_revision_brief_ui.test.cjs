const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const root = path.resolve(__dirname, '../main/ets');
function method(source, name) {
  const match = new RegExp('^  (?:private )?(?:async )?' + name + '\\(', 'm').exec(source);
  assert.ok(match, name);
  let depth = 1, end = source.indexOf('{', match.index) + 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(match.index, end);
}
function instance(file, names, env) {
  const source = fs.readFileSync(path.join(root, file), 'utf8');
  const code = ts.transpileModule('class UI {\n' + names.map(name => method(source, name)).join('\n') + '\n}\nreturn new UI();',
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  return new Function(...Object.keys(env), code)(...Object.values(env));
}
function sheetFixture() {
  const queue = [], confirmed = [], alerts = [], cancelled = [];
  const sheet = instance('components/NovelRevisionBriefSheet.ets', ['resetDraft', 'confirm', 'cancel', 'aboutToDisappear'], {
    setTimeout: callback => queue.push(callback), AlertDialog: { show: dialog => alerts.push(dialog) }, ERROR: '#f00',
  });
  Object.assign(sheet, { alive: true, busy: false, committing: false, inputToken: 0, allowEmpty: false, initialText: '',
    controller: { stopEditing() {} }, onConfirm: text => confirmed.push(text), onCancel: () => cancelled.push(true) });
  sheet.resetDraft();
  return { sheet, queue, confirmed, alerts, cancelled };
}
test('IME commits before revision submission and empty required brief never emits', () => {
  const { sheet, queue, confirmed } = sheetFixture();
  sheet.confirm(); queue.shift()(); assert.deepEqual(confirmed, []);
  sheet.controller.stopEditing = () => { sheet.text = ' 输入法刚完成的要求 '; };
  sheet.confirm(); sheet.confirm(); assert.equal(queue.length, 1);
  queue.shift()(); assert.deepEqual(confirmed, ['输入法刚完成的要求']);
});
test('regeneration explicitly allows empty brief and sheet reset/disappearance invalidate queued submission', () => {
  const { sheet, queue, confirmed } = sheetFixture();
  sheet.allowEmpty = true;
  sheet.confirm(); queue.shift()(); assert.deepEqual(confirmed, ['']);
  sheet.confirm(); sheet.resetDraft(); queue.shift()(); assert.equal(confirmed.length, 1);
  sheet.confirm(); sheet.aboutToDisappear(); queue.shift()(); assert.equal(confirmed.length, 1);
});
test('dirty cancellation commits IME first; old confirmation cannot close a newer sheet', () => {
  const { sheet, queue, alerts, cancelled } = sheetFixture();
  sheet.controller.stopEditing = () => { sheet.text = '尚未提交'; };
  sheet.cancel(); queue.shift()(); assert.equal(alerts.length, 1); assert.equal(cancelled.length, 0);
  sheet.resetDraft(); alerts[0].secondaryButton.action(); assert.equal(cancelled.length, 0);
  sheet.cancel(); queue.shift()(); alerts[1].secondaryButton.action(); assert.equal(cancelled.length, 1);
});
function readerFixture(api) {
  const back = [], toast = [];
  const page = instance('pages/NovelChapterReaderPage.ets', ['openRevision', 'submitRevision'], {
    getNovelCreation: () => api, novelChapterOrdinal: (_chapter, fallback) => fallback,
    router: { back: () => back.push(true) }, promptAction: { showToast: value => toast.push(value) },
  });
  Object.assign(page, { pageAlive: true, busy: false, revisionToken: 0, revisionOpen: false,
    projectId: 'project', chapterId: 'chapter', revisionKind: 'revise' });
  return { page, back, toast };
}
const chapter = { id: 'chapter', title: '目标章', content: '旧正文', discarded: false };
const cas = { branchId: 'branch', head: 'head', treeDigest: 'digest' };
const snapshot = { project: { chapters: [chapter] }, status: { cas, activeBranchName: '主线' } };
test('reader previews exact chapter/branch then dispatches semantic revise and regenerate APIs with frozen CAS', async () => {
  const calls = [];
  const api = { readWorkspaceSnapshot: async () => snapshot,
    reviseChapter: async (...args) => calls.push(['revise', ...args]), regenerateChapter: async (...args) => calls.push(['regenerate', ...args]) };
  const { page, back } = readerFixture(api);
  await page.openRevision('revise');
  assert.ok(page.revisionDetail.includes('目标章')); assert.ok(page.revisionDetail.includes('主线'));
  assert.ok(page.revisionDetail.includes('确认收录前保留现有正文'));
  page.chapterId = 'another';
  await page.submitRevision('保留情节，更自然');
  assert.deepEqual(calls[0], ['revise', 'project', 'chapter', '保留情节，更自然', cas]);
  assert.equal(back.length, 1); assert.equal(chapter.content, '旧正文');
  page.chapterId = 'chapter'; await page.openRevision('regenerate'); await page.submitRevision('');
  assert.deepEqual(calls[1], ['regenerate', 'project', 'chapter', '', cas]);
});
test('model/CAS failure keeps brief sheet and error visible; blank revise cannot start', async () => {
  let requests = 0;
  const { page, back } = readerFixture({ readWorkspaceSnapshot: async () => snapshot,
    reviseChapter: async () => { requests++; throw new Error('工作区已变化'); } });
  await page.openRevision('revise'); await page.submitRevision('  '); assert.equal(requests, 0);
  await page.submitRevision('要求'); assert.equal(requests, 1); assert.equal(page.revisionOpen, true);
  assert.ok(page.revisionError.includes('工作区已变化')); assert.equal(page.busy, false); assert.equal(back.length, 0);
});
test('discarded/deleted chapter cannot open action and late snapshot after page exit cannot show sheet', async () => {
  const { page } = readerFixture({ readWorkspaceSnapshot: async () => ({ ...snapshot, project: { chapters: [{ ...chapter, discarded: true }] } }) });
  await page.openRevision('regenerate'); assert.equal(page.revisionOpen, false); assert.ok(page.errorMsg.includes('恢复本章'));
  let resolve;
  const late = readerFixture({ readWorkspaceSnapshot: () => new Promise(done => { resolve = done; }) }).page;
  const pending = late.openRevision('revise'); late.pageAlive = false; late.revisionToken++;
  resolve(snapshot); await pending; assert.equal(late.revisionOpen, false);
});

function evidenceReader(api) {
  const page = instance('pages/NovelChapterReaderPage.ets', ['reload', 'chapter', 'chapterIndex'], {
    getNovelCreation: () => api, defaultGhostwriteDigest: content => content,
  });
  Object.assign(page, { projectId: 'project', chapterId: 'chapter', pageAlive: true, loaded: false,
    evidenceQuote: 'Old fact.', evidenceDigest: 'Old fact.', evidenceBranchId: 'main',
    evidenceStart: 0, evidenceEnd: 9, evidenceFocused: true, notice: '', errorMsg: '' });
  return page;
}
test('reader reload uses one coherent snapshot when the author changes body between legacy project/status reads', async () => {
  const old = { chapters: [{ id: 'chapter', content: 'Old fact.' }] };
  let manuscript = old, snapshots = 0, splitReads = 0;
  const commitAuthorEdit = () => { manuscript = { chapters: [{ id: 'chapter', content: 'New author fact.' }] }; };
  const page = evidenceReader({
    open: async () => { splitReads++; commitAuthorEdit(); return old; },
    workspaceStatus: async () => { splitReads++; return { activeBranchId: 'main' }; },
    readWorkspaceSnapshot: async () => {
      snapshots++; commitAuthorEdit(); return { project: manuscript, status: { activeBranchId: 'main' } };
    },
  });
  await page.reload();
  assert.equal(page.project.chapters[0].content, 'New author fact.');
  assert.equal(page.evidenceQuote, ''); assert.equal(page.evidenceFocused, false);
  assert.ok(page.notice.includes('旧稿')); assert.equal(snapshots, 1); assert.equal(splitReads, 0);
});
test('coherent snapshot rejects old branch evidence even when body is identical, and preserves valid same-snapshot focus', async () => {
  const project = { chapters: [{ id: 'chapter', content: 'Old fact.' }] };
  const other = evidenceReader({ readWorkspaceSnapshot: async () => ({ project, status: { activeBranchId: 'other' } }) });
  await other.reload(); assert.equal(other.evidenceFocused, false); assert.equal(other.evidenceQuote, '');
  const valid = evidenceReader({ readWorkspaceSnapshot: async () => ({ project, status: { activeBranchId: 'main' } }) });
  await valid.reload(); assert.equal(valid.evidenceFocused, true); assert.equal(valid.evidenceQuote, 'Old fact.');
});
test('late snapshot after Reader disappearance cannot change displayed project or evidence focus', async () => {
  let resolve;
  const page = evidenceReader({ readWorkspaceSnapshot: () => new Promise(done => { resolve = done; }) });
  const pending = page.reload(); page.pageAlive = false;
  resolve({ project: { chapters: [{ id: 'chapter', content: 'New fact.' }] }, status: { activeBranchId: 'main' } });
  await pending; assert.equal(page.project, undefined); assert.equal(page.loaded, false); assert.equal(page.evidenceFocused, true);
});
