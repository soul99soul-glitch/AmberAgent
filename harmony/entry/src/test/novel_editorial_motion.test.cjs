const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');

const { loadSession } = require('./novel_workspace_sessions_fixture.cjs');

// Execute the manuscript owner and the page's impression callback against a controlled commit boundary.
function fixture({ archive = false } = {}) {
  const source = fs.readFileSync(path.join(__dirname, '../main/ets/pages/NovelWorkspacePage.ets'), 'utf8');
  const helpers = source.slice(source.indexOf('  private studioBackgroundChanged()'),
    source.indexOf('  private enableKeyboardAvoidance()')).replace(/private /g, '');
  const callbackMatch = /^  private onManuscriptCollected\(/m.exec(source);
  assert.ok(callbackMatch, 'page owns the collection impression callback');
  let depth = 1, end = source.indexOf('{', callbackMatch.index) + 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  const callback = source.slice(callbackMatch.index, end).replace('private ', '');
  const js = ts.transpileModule('class Page {' + helpers + callback + '} return Page;',
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  let resolveCommit, rejectCommit;
  const animations = [];
  const committed = new Promise((resolve, reject) => { resolveCommit = resolve; rejectCommit = reject; });
  const Page = new Function('getProductKind', 'animateTo', 'Curve', js)(
    () => 'novel', (options, update) => { animations.push(options); update(); }, { EaseOut: 'ease' });
  const page = new Page();
  Object.assign(page, {
    busy: false, errorMsg: '', pageAlive: true, pageHiddenOnce: false,
    writing: { background() {} },
    studioPageVisible: true, studioBackgrounded: false,
    studioVisibleCycle: 0, collectionStamp: 0, projectId: 'project', tab: 0, reduceMotion: false,
    workspaceStatus: { activeBranchId: 'branch' },
    project: { messages: [{ id: 'message', granularity: 'whole_chapter' }] },
    hasBlockingNovelRun: () => false, reload: async () => {},
    onCollected: (...args) => page.onManuscriptCollected(...args),
  });
  const dialogs = [];
  const session = loadSession('NovelManuscriptSession', page, { collectMessage: () => committed }, dialogs);
  Object.assign(session, { collectMessageId: 'message', collectDraft: 'committed prose',
    collectSelected: [true], collectMode: archive ? 0 : 1,
    collectChapterId: 'chapter', collectTitle: 'chapter title' });
  return { page, session, animations, resolveCommit, rejectCommit, dialog: () => dialogs[0],
    success: () => resolveCommit({ analysisFinished: Promise.resolve({ count: 0 }) }) };
}

const nextTurn = () => new Promise(resolve => setTimeout(resolve, 0));

test('collection impression follows persisted success, never merely starting or failed collection', async () => {
  const f = fixture();
  const collecting = f.session.submitCollect();
  await nextTurn();
  assert.equal(f.page.collectionStamp, 0);
  f.success(); await collecting;
  assert.equal(f.page.collectionStamp, 1);
  assert.equal(f.page.tab, 1);
  assert.equal(f.session.collectMessageId, '');

  const failed = fixture();
  const attempting = failed.session.submitCollect(); await nextTurn();
  failed.rejectCommit(new Error('commit rejected')); await attempting;
  assert.equal(failed.page.collectionStamp, 0);
  assert.equal(failed.session.collectMessageId, 'message');
});

test('leaving and returning, backgrounding, and changing theme suppress old async success', async () => {
  for (const invalidate of [
    p => { p.studioPageVisible = false; p.clearStudioFeedback(); p.studioPageVisible = true; },
    p => { p.studioBackgrounded = true; p.studioBackgroundChanged(); p.studioBackgrounded = false; },
    p => p.clearStudioFeedback(),
  ]) {
    const f = fixture(); const collecting = f.session.submitCollect(); await nextTurn();
    invalidate(f.page); f.success(); await collecting;
    assert.equal(f.page.collectionStamp, 0);
    assert.equal(f.session.collectMessageId, ''); // Persisted data still follows the existing flow.
  }
});

test('whole chapter collection completes directly without an automatic archive dialog', async () => {
  const f = fixture({ archive: true }); const collecting = f.session.submitCollect(); await nextTurn();
  f.success(); await collecting;
  assert.equal(f.dialog(), undefined, 'discussion archive must remain a manual action');
  assert.equal(f.page.collectionStamp, 1);
  assert.equal(f.page.tab, 1);
  assert.equal(f.session.collectMessageId, '');
});

test('the impression stays in the originating project and branch; leaving正文 clears it', () => {
  const f = fixture(); f.page.tab = 1;
  f.page.showCollectionStamp('other', 'branch', 0);
  f.page.showCollectionStamp('project', 'other', 0);
  assert.equal(f.page.collectionStamp, 0);
  f.page.showCollectionStamp('project', 'branch', 0);
  assert.equal(f.page.collectionStamp, 1);
  f.page.selectStudioTab(0);
  assert.equal(f.page.studioTabDirection, -1);
  assert.equal(f.page.collectionStamp, 0);
  f.page.selectStudioTab(2);
  assert.equal(f.page.studioTabDirection, 1);
});


test('writing mode changes preserve selection with reduced motion and never animate a hidden page', () => {
  const f = fixture();
  f.page.selectStudioTab(1);
  assert.equal(f.page.tab, 1);
  assert.equal(f.animations.at(-1).duration, 180);
  f.page.reduceMotion = true;
  f.page.selectStudioTab(2);
  assert.equal(f.page.tab, 2);
  assert.equal(f.animations.at(-1).duration, 100);
  f.page.studioBackgrounded = true;
  f.page.selectStudioTab(0);
  assert.equal(f.page.tab, 0);
  assert.equal(f.animations.at(-1).duration, 0);
  f.page.studioBackgrounded = false;
  f.page.studioPageVisible = false;
  f.page.selectStudioTab(1);
  assert.equal(f.page.tab, 1);
  assert.equal(f.animations.at(-1).duration, 0);
});
