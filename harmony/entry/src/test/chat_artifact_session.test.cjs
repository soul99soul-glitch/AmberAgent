const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const { loadPureModule } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');
const domain = loadPureModule(path.resolve(__dirname, '../../../chat/src/main/ets/chat/conversation_artifacts.ts'));

const source = fs.readFileSync(path.resolve(__dirname,
  '../main/ets/platform_impl/chat/ChatArtifactSession.ets'), 'utf8');
const panels = fs.readFileSync(path.resolve(__dirname,
  '../main/ets/components/chat/ChatArtifactPanels.ets'), 'utf8');
const defer = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const settle = async () => { await Promise.resolve(); await Promise.resolve(); };
const conversation = (id = 'c') => ({ id, messageNodes: [] });
const doc = (fileName, text) => ({ type: 'document', fileName, url: text, mime: 'text/plain' });

function fixture(options = {}) {
  const code = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  const exports = {};
  const reads = [], repositoryReads = [], errors = [], locations = [];
  const state = { current: conversation(), fullyLoaded: false, stored: conversation(), active: null };
  new Function('exports', 'require', 'Observed', code)(exports, name => {
    if (name === '@amber/chat-domain') return domain;
    throw Error(`Unexpected artifact owner dependency: ${name}`);
  }, value => value);
  const session = new exports.ChatArtifactSession();
  session.start({ getById: id => {
    repositoryReads.push(id);
    return options.load ? options.load(id) : Promise.resolve(state.stored);
  } }, { snapshot: () => state.active }, {
    currentConversation: () => state.current,
    fullyLoaded: () => state.fullyLoaded,
    enableGenerativeWidgets: () => true,
    error: message => errors.push(message),
    locateSource: (...args) => locations.push(args),
  }, part => { reads.push(part); return options.read ? options.read(part) : Promise.resolve(part.url); });
  return { session, state, reads, repositoryReads, errors, locations };
}

function sourceConversation(id = 'c') {
  return { id, messageNodes: [{ id: 'n', selectIndex: 0,
    messages: [{ id: 'm', role: 'assistant', parts: [doc('notes.md', 'saved')] }] }] };
}
const location = { conversationId: 'c', nodeId: 'n', messageId: 'm' };

test('shelf reads complete repository history outside the tail window', async () => {
  const f = fixture();
  f.state.stored = sourceConversation();
  await f.session.openArtifactShelf();
  assert.deepEqual(f.repositoryReads, ['c']);
  assert.equal(f.session.shelfArtifacts.docs.length, 1);
  assert.equal(f.session.shelfArtifacts.docs[0].part.fileName, 'notes.md');
  assert.equal(f.session.artifactShelfOpen, true);
});

test('fully loaded shelf avoids IO and still prefers the current run snapshot', async () => {
  const f = fixture();
  f.state.fullyLoaded = true;
  f.state.active = { conversation: sourceConversation() };
  await f.session.openArtifactShelf();
  assert.deepEqual(f.repositoryReads, []);
  assert.equal(f.session.shelfArtifacts.docs.length, 1);
  f.state.active = null;
  await f.session.openArtifactShelf();
  assert.equal(f.session.shelfArtifacts.docs.length, 0);
});

test('inflight shelf result is rejected after hide, disposal, or conversation switch', async () => {
  for (const action of ['hide', 'dispose', 'switch']) {
    const wait = defer();
    const f = fixture({ load: () => wait.promise });
    const pending = f.session.openArtifactShelf();
    if (action === 'switch') f.state.current = conversation('other');
    else f.session[action]();
    wait.resolve(sourceConversation());
    await pending;
    assert.equal(f.session.artifactShelfOpen, false, action);
    assert.deepEqual(f.errors, []);
  }
});

test('shelf reports missing conversation and storage errors with original identity guard', async () => {
  const f = fixture();
  f.state.stored = null;
  await f.session.openArtifactShelf();
  assert.deepEqual(f.errors, ['会话已不存在']);
  const wait = defer();
  const failed = fixture({ load: () => wait.promise });
  const pending = failed.session.openArtifactShelf();
  failed.state.current = conversation('other');
  wait.reject(Error('read failure'));
  await pending;
  assert.deepEqual(failed.errors, []);
  const current = fixture({ load: async () => { throw Error('read failure'); } });
  await current.session.openArtifactShelf();
  assert.match(current.errors[0], /无法打开产物架：read failure/);
});

test('source emits latest full snapshot/index without owning canonical state', async () => {
  const f = fixture();
  const active = sourceConversation();
  f.state.active = { conversation: active };
  f.session.artifactShelfOpen = true;
  await f.session.openArtifactSource(location);
  assert.deepEqual(f.repositoryReads, ['c']);
  assert.deepEqual(f.locations, [[active, 'n', 0]]);
  assert.equal(f.session.artifactShelfOpen, false);
  assert.equal(f.state.current.messageNodes.length, 0);
  assert.equal(Object.hasOwn(f.session, 'conversation'), false);
});

test('source rejects switched conversation and unselected source branch', async () => {
  const f = fixture();
  f.state.current = conversation('other');
  await f.session.openArtifactSource(location);
  assert.deepEqual(f.repositoryReads, []);
  assert.deepEqual(f.errors, ['来源会话已切换']);
  f.state.current = conversation();
  const stored = sourceConversation();
  stored.messageNodes[0].messages.push({ id: 'new', role: 'assistant', parts: [] });
  stored.messageNodes[0].selectIndex = 1;
  f.state.stored = stored;
  await f.session.openArtifactSource(location);
  assert.match(f.errors[1], /来源消息已不存在或不在当前分支/);
  assert.deepEqual(f.locations, []);
});

test('source late resolution does not navigate from a hidden page; show restores actions', async () => {
  const wait = defer();
  const f = fixture({ load: () => wait.promise });
  const pending = f.session.openArtifactSource(location);
  f.session.hide();
  wait.resolve(sourceConversation());
  await pending;
  assert.deepEqual(f.locations, []);
  f.session.show();
  await f.session.openArtifactSource(location);
  assert.equal(f.locations.length, 1);
});

test('preview rejects an older request and a completion after closing or disposal', async () => {
  const requests = [defer(), defer(), defer(), defer()];
  const f = fixture({ read: () => requests.shift().promise });
  const [old, latest, closed, disposed] = requests.slice();
  f.session.openDocumentPreview(doc('old', 'old'));
  f.session.openDocumentPreview(doc('latest', 'latest'));
  latest.resolve('latest content');
  await settle();
  old.resolve('stale content');
  await settle();
  assert.equal(f.session.documentPreviewTitle, 'latest');
  assert.equal(f.session.documentPreviewText, 'latest content');
  f.session.openDocumentPreview(doc('closed', 'closed'));
  f.session.documentPreviewOpen = false;
  closed.resolve('closed content');
  await settle();
  assert.equal(f.session.documentPreviewText, '正在读取附件…');
  f.session.openDocumentPreview(doc('disposed', 'disposed'));
  f.session.dispose();
  disposed.resolve('disposed content');
  await settle();
  assert.equal(f.session.documentPreviewText, '正在读取附件…');
});

test('preview keeps original alive/open guard while hidden and reports read failures', async () => {
  const wait = defer();
  const f = fixture({ read: () => wait.promise });
  f.session.openDocumentPreview(doc('notes', ''));
  f.session.hide();
  wait.reject(Error('unsupported document'));
  await settle();
  assert.equal(f.session.documentPreviewText, '无法预览：unsupported document');
});

test('diff reads latest two same-name versions in order and retains line summary contract', async () => {
  const f = fixture();
  f.session.shelfArtifacts.docs = ['ignored', 'a\nb', 'a\nc'].map(text => ({ part: doc('notes.md', text) }));
  await f.session.openArtifactDiff('notes.md');
  assert.deepEqual(f.reads.map(part => part.url), ['a\nb', 'a\nc']);
  assert.equal(f.session.artifactDiffTitle, '版本对比:notes.md');
  assert.match(f.session.artifactDiffText, /旧版本 2 行 · 新版本 2 行/);
  assert.match(f.session.artifactDiffText, /\+1 行新增 · −1 行移除/);
  assert.match(f.session.artifactDiffText, /− 2  b\n\+ 2  c/);
});

test('diff ignores insufficient versions, groups unnamed files, and displays IO failure', async () => {
  const f = fixture({ read: async () => { throw Error('no decoder'); } });
  f.session.shelfArtifacts.docs = [{ part: doc('', '') }];
  await f.session.openArtifactDiff('(未命名)');
  assert.equal(f.session.artifactDiffOpen, false);
  f.session.shelfArtifacts.docs.push({ part: doc('', '') });
  await f.session.openArtifactDiff('(未命名)');
  assert.equal(f.session.artifactDiffText, '无法对比:no decoder');
});

// Execute the production builder's event closures; component rendering belongs
// to the SDK gate, while these callbacks must keep closing and routing order.
function shelfCallbacks(session, events) {
  const start = panels.indexOf('  private artifactShelfSheet()');
  const end = panels.indexOf('\n  @Builder', start);
  const method = panels.slice(start, end);
  const code = ts.transpileModule('class Panels { ' + method + ' } return Panels;', {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText;
  let callbacks;
  const Panel = new Function('ChatArtifactShelf', code)(value => { callbacks = value; });
  const panel = new Panel();
  Object.assign(panel, { session, onDraft: text => events.push(['draft', text, session.artifactShelfOpen]),
    onRunMiniApp: id => events.push(['run', id, session.artifactShelfOpen]) });
  panel.artifactShelfSheet();
  return callbacks;
}

test('real shelf event closures close source/preview/diff and emit draft/MiniApp events', () => {
  const events = [];
  const session = { artifactShelfOpen: true, shelfArtifacts: {},
    openArtifactSource: source => events.push(['source', source, session.artifactShelfOpen]),
    openDocumentPreview: part => events.push(['document', part, session.artifactShelfOpen]),
    openArtifactDiff: name => events.push(['diff', name, session.artifactShelfOpen]) };
  const callbacks = shelfCallbacks(session, events);
  for (const [name, value, type] of [['onSource', location, 'source'], ['onOpenDocument', doc('a', ''), 'document'],
    ['onOpenDiff', 'a', 'diff'], ['onRunMiniApp', 'app', 'run']]) {
    session.artifactShelfOpen = true;
    callbacks[name](value);
    assert.deepEqual(events.pop(), [type, value, false]);
  }
  session.artifactShelfOpen = true;
  callbacks.onAction('draft text');
  assert.deepEqual(events.pop(), ['draft', 'draft text', true]);
  assert.equal(session.artifactShelfOpen, false);
  session.artifactShelfOpen = true;
  callbacks.onDismiss();
  assert.equal(session.artifactShelfOpen, false);
});

test('panels retain three independent observed 0x0 sheet hosts', () => {
  assert.equal((panels.match(/\.bindSheet\(/g) || []).length, 3);
  for (const name of ['documentPreview', 'artifactShelf', 'artifactDiff']) {
    assert.match(panels, new RegExp('Column\\(\\) \\{\\}\\s*\\.width\\(0\\)\\.height\\(0\\)\\s*' +
      '\\.hitTestBehavior\\(HitTestMode.None\\)\\s*\\.bindSheet\\(\\$\\$this.session.' + name + 'Open'));
  }
  assert.match(panels, /@ObjectLink session: ChatArtifactSession/);
  assert.doesNotMatch(panels, /aboutToDisappear|\.dispose\(/);
});
