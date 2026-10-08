const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const { actualPage } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');
const source = fs.readFileSync(path.join(__dirname, '../main/ets/pages/NovelChapterReaderPage.ets'), 'utf8');

function method(name) {
  const match = new RegExp('^  (?:private )?(?:async )?' + name + '\\(', 'm').exec(source);
  assert.ok(match, 'missing actual reader method ' + name);
  let depth = 1, end = source.indexOf('{', match.index) + 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(match.index, end);
}

function readerFor(creation) {
  const dialogs = [], returns = [];
  const code = ts.transpileModule('class Reader {\n' + ['previewVersion', 'confirmVersionFork', 'forkVersion'].map(method).join('\n')
    + '\n}\nreturn Reader;', { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const Reader = new Function('getNovelCreation', 'AlertDialog', 'router', code)(() => creation,
    { show: dialog => dialogs.push(dialog) }, { back: () => returns.push('workspace') });
  const reader = new Reader();
  Object.assign(reader, { pageAlive: true, projectId: 'project', versionsOpen: true, busy: false,
    versionPreview: null, versionCheckpointToken: 0 });
  return { reader, dialogs, returns };
}
const cas = { branchId: 'main', head: 'latest', treeDigest: 'digest' };
const version = { id: 'version-id', title: '历史稿', createdAt: 123 };
const checkpoint = { commitId: 'associated-checkpoint' };

test('a closing preview renders its captured version after the parent selection is cleared', () => {
  let rendered;
  const reader = actualPage('pages/NovelChapterReaderPage.ets', ['VersionPreviewSheet'], {
    NovelChapterVersionPreview: props => { rendered = props; }, charCountOf: text => text.length,
  });
  const selected = { ...version, content: 'original content', kind: 'manual' };
  Object.assign(reader, { versionPreview: selected, busy: false, navBarHeight: 0,
    versionKindLabel: () => 'manual', versionTime: () => 'time', versionCheckpoint: null,
  });
  reader.VersionPreviewSheet(selected); rendered.onBack();
  assert.equal(reader.versionPreview, null);
  reader.VersionPreviewSheet(selected);
  assert.equal(rendered.title, '历史稿'); assert.equal(rendered.content, 'original content');
  reader.busy = true; reader.VersionPreviewSheet(selected); assert.equal(rendered.busy, true);
});

test('a closing ghostwrite confirmation retains the frozen author plan after selection is cleared', () => {
  let rendered;
  const page = actualPage('pages/NovelWorkspacePage.ets', ['GhostwriteStartPreviewSheet'], {
    NovelGhostwriteStartSheet: props => { rendered = props; },
  });
  const preview = { branchName: 'main', targetChapterCount: 3, planContent: 'frozen author plan' };
  Object.assign(page, { ghostwritePreview: preview, ghostwriteBusy: false, navBarHeight: 0,
    ghostwriteAuthorPlanPreview: value => value.planContent,
  });
  page.GhostwriteStartPreviewSheet(preview); rendered.onClose(); assert.equal(page.ghostwritePreview, null);
  page.GhostwriteStartPreviewSheet(preview);
  assert.equal(rendered.branchName, 'main'); assert.equal(rendered.chapterCount, 3);
  assert.equal(rendered.planContent, 'frozen author plan');
});

test('version preview resolves an exact version checkpoint using the frozen snapshot CAS', async () => {
  const calls = [];
  const { reader } = readerFor({ readWorkspaceSnapshot: async () => ({ status: { cas } }),
    chapterVersionCheckpoint: async (...args) => { calls.push(args); return checkpoint; } });
  await reader.previewVersion(version);
  assert.deepEqual(calls, [['project', version.id, cas]]);
  assert.equal(reader.versionCheckpoint, checkpoint);
  assert.equal(reader.versionCheckpointCas, cas);
  assert.match(reader.versionForkDetail, /完整正文、资料、讨论/);
});

test('a version without a matching checkpoint stays unavailable and does not guess from timestamps', async () => {
  const { reader, dialogs } = readerFor({ readWorkspaceSnapshot: async () => ({ status: { cas } }),
    chapterVersionCheckpoint: async () => null });
  await reader.previewVersion(version);
  reader.versionForkName = 'new branch';
  reader.confirmVersionFork();
  assert.equal(reader.versionCheckpoint, null);
  assert.equal(reader.versionCheckpointCas, null);
  assert.match(reader.versionForkDetail, /没有关联.*仍可恢复本章正文/);
  assert.equal(dialogs.length, 0);
});

test('a late checkpoint lookup cannot overwrite a newer selected preview', async () => {
  let finish;
  const lookup = new Promise(resolve => { finish = resolve; });
  const { reader } = readerFor({ readWorkspaceSnapshot: async () => ({ status: { cas } }),
    chapterVersionCheckpoint: async () => lookup });
  const pending = reader.previewVersion(version);
  await Promise.resolve();
  reader.versionPreview = { id: 'other-version' };
  finish(checkpoint); await pending;
  assert.equal(reader.versionCheckpoint, null);
  assert.equal(reader.versionCheckpointCas, null);
});

test('fork requires a name and explicit confirmation of the full-workspace scope', async () => {
  const { reader, dialogs } = readerFor({});
  Object.assign(reader, { versionPreview: version, versionCheckpoint: checkpoint, versionCheckpointCas: cas,
    versionForkName: ' ' });
  reader.confirmVersionFork();
  assert.equal(dialogs.length, 0);
  reader.versionForkName = '  Alternative  ';
  reader.confirmVersionFork();
  assert.equal(dialogs.length, 1);
  assert.match(dialogs[0].message, /完整正文、资料、讨论和分支偏好/);
  const calls = [];
  reader.forkVersion = (...args) => calls.push(args);
  dialogs[0].primaryButton.action();
  assert.deepEqual(calls, []);
  dialogs[0].secondaryButton.action();
  assert.deepEqual(calls, [[version.id, checkpoint.commitId, 'Alternative', cas]]);
});

test('fork revalidates version association and commits with the exact frozen CAS before returning to workspace', async () => {
  const calls = [];
  const { reader, returns } = readerFor({ chapterVersionCheckpoint: async (...args) => { calls.push(['lookup', ...args]); return checkpoint; },
    forkFromHistory: async (...args) => calls.push(['fork', ...args]) });
  reader.versionPreview = version;
  await reader.forkVersion(version.id, checkpoint.commitId, 'Alternative', cas);
  assert.deepEqual(calls, [['lookup', 'project', version.id, cas], ['fork', 'project', checkpoint.commitId, 'Alternative', cas]]);
  assert.deepEqual(returns, ['workspace']);
  assert.equal(reader.busy, false);
});

test('changed checkpoint or stale CAS prevents fork and keeps preview with a visible error', async () => {
  for (const lookup of [async () => ({ commitId: 'different' }), async () => { throw new Error('CAS 已变化'); }]) {
    let forks = 0;
    const { reader, returns } = readerFor({ chapterVersionCheckpoint: lookup, forkFromHistory: async () => { forks++; } });
    reader.versionPreview = version;
    await reader.forkVersion(version.id, checkpoint.commitId, 'Alternative', cas);
    assert.equal(forks, 0);
    assert.equal(returns.length, 0);
    assert.match(reader.errorMsg, /创建历史分支失败/);
    assert.equal(reader.busy, false);
  }
});
