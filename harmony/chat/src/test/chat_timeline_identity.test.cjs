// Execute the native timeline's production slots and retained row action callbacks.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const base = path.resolve(__dirname, '../../../entry/src/main/ets');
const source = fs.readFileSync(path.join(base, 'components/chat/ChatTimelineView.ets'), 'utf8');
const itemSource = fs.readFileSync(path.join(base, 'components/chat/ChatTimelineMessageItem.ets'), 'utf8');
const pageSource = fs.readFileSync(path.join(base, 'pages/ChatPage.ets'), 'utf8');
const compile = text => ts.transpileModule(text, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
}).outputText;

function copyCallback(probe, item, receive) {
  const start = pageSource.indexOf('onMessageCommand: (');
  const end = pageSource.indexOf('\n        onApprove:', start);
  const dispatch = pageSource.slice(start + 'onMessageCommand: '.length, end).trim().replace(/,$/, '');
  const dispatchBody = dispatch.slice(dispatch.indexOf('=>') + 2);
  const rowCopy = itemSource.match(/onCopy: \(\): void => \{([^]*?)\},/)?.[1];
  assert.ok(rowCopy, 'copy must execute the production retained-row callback');
  const exports = {};
  vm.runInNewContext(compile(`exports.dispatch = function(command, row, index) ${dispatchBody}; exports.copy = function() { ${rowCopy} };`), { exports });
  const onMessageCommand = exports.dispatch.bind({ presentation: probe.presentation,
    messageActions: { openCopySheet: receive } });
  return exports.copy.bind({ item, onMessageCommand });
}

function fixture() {
  const state = {};
  new Function('exports', compile(fs.readFileSync(path.join(base, 'components/MessageRowViewState.ets'), 'utf8')))(state);
  const exports = {};
  new Function('exports', 'require', 'Observed',
    compile(fs.readFileSync(path.join(base, 'platform_impl/chat/ChatTimelineDataSource.ets'), 'utf8')))(
    exports, name => {
      if (name.endsWith('/MessageRowViewState.ets')) return state;
      throw new Error('Unexpected timeline source dependency: ' + name);
    }, value => value);
  return { dataSource: new exports.ChatTimelineDataSource(), rows: [],
    presentation: { streamingTail: [], regenTail: null, regenNodeId: '' } };
}
const message = (id, text, role = 'assistant') => ({ id, role, parts: [{ type: 'text', text, metadata: null }] });
const row = (nodeId, message, extra = {}) => ({ nodeId, message, branchCount: 1, selectIndex: 0,
  isAssistant: message.role === 'assistant', isLastAssistant: message.role === 'assistant',
  displayRevision: 0, displaySignature: '', ...extra });
function sync(probe) {
  const p = probe.presentation;
  probe.dataSource.sync(probe.rows, p.streamingTail, p.regenTail, p.regenNodeId, () => 0);
  return new Map(Array.from({ length: probe.dataSource.totalCount() }, (_, index) => {
    const item = probe.dataSource.getData(index);
    return [item.message.id, item];
  }));
}

test('stream completion retains the native row slot, reveal state and latest action callback', () => {
  const probe = fixture();
  probe.rows = [row('user-node', message('user', 'prompt', 'user'))];
  probe.presentation.streamingTail = [message('answer', 'first')];
  const active = sync(probe).get('answer');
  const viewState = active.viewState;
  viewState.reasoningStates.think = 'expanded';
  let copied;
  const copy = copyCallback(probe, active, value => { copied = value; });
  probe.presentation.streamingTail = [message('answer', 'first and final')];
  sync(probe);
  assert.equal(active.message.parts[0].text, 'first and final');
  assert.equal(active.streaming, true);
  const finalRow = row('persisted-node', probe.presentation.streamingTail[0]);
  probe.rows = [probe.rows[0], finalRow];
  probe.presentation.streamingTail = [];
  assert.strictEqual(sync(probe).get('answer'), active);
  assert.strictEqual(active.viewState, viewState);
  assert.equal(active.viewState.reasoningStates.think, 'expanded');
  assert.strictEqual(active.row, finalRow);
  assert.equal(active.streaming, false);
  copy();
  assert.strictEqual(copied, finalRow.message);
});

test('a tool continuation and all terminal assistant messages retain their own identities', () => {
  const probe = fixture();
  probe.presentation.streamingTail = [message('tool-answer', 'tool output'), message('summary', 'partial')];
  const live = sync(probe);
  probe.rows = probe.presentation.streamingTail.map((item, index) => row(`node-${index}`, item));
  probe.presentation.streamingTail = [];
  const complete = sync(probe);
  assert.strictEqual(complete.get('tool-answer'), live.get('tool-answer'));
  assert.strictEqual(complete.get('summary'), live.get('summary'));
  assert.equal(live.get('summary').row.nodeId, 'node-1');
});

test('regeneration keeps the new branch instance and retained copy reads latest branch metadata', () => {
  const probe = fixture();
  probe.rows = [row('node', message('old-branch', 'old'))];
  probe.presentation.regenNodeId = 'node';
  probe.presentation.regenTail = message('new-branch', 'partial');
  const active = sync(probe).get('new-branch');
  assert.equal(active.streaming, true);
  let copied;
  const copy = copyCallback(probe, active, value => { copied = value; });
  copy();
  assert.strictEqual(copied, probe.presentation.regenTail);
  probe.rows = [row('node', message('new-branch', 'complete'), { branchCount: 2, selectIndex: 1, displayRevision: 4 })];
  probe.presentation.regenTail = null;
  probe.presentation.regenNodeId = '';
  assert.strictEqual(sync(probe).get('new-branch'), active);
  copy();
  assert.strictEqual(copied, probe.rows[0].message);
  assert.equal(active.row.branchCount, 2);
  assert.equal(active.row.selectIndex, 1);
  assert.equal(active.message.parts[0].text, 'complete');
});

test('resuming a streamed tool row keeps the same message slot and Markdown stream identity', () => {
  const probe = fixture();
  probe.presentation.streamingTail = [message('answer', 'waiting for approval')];
  const active = sync(probe).get('answer');
  probe.rows = [row('node', probe.presentation.streamingTail[0])];
  probe.presentation.streamingTail = [];
  probe.presentation.regenNodeId = 'node';
  probe.presentation.regenTail = message('answer', 'approved and continuing');
  assert.strictEqual(sync(probe).get('answer'), active);
  assert.equal(active.streaming, true);
  assert.equal(active.message.parts[0].text, 'approved and continuing');
  assert.doesNotMatch(itemSource, /streamKeyTag:/, 'resume must keep the same Markdown stream key');
});

test('same-id edits and tool approvals update current rows while switching branches changes identity', () => {
  const probe = fixture();
  probe.rows = [row('node', message('same', 'one'))];
  const initial = sync(probe).get('same');
  const updated = row('node', message('same', 'two'), { displayRevision: 9, isLastAssistant: false });
  probe.rows = [updated];
  assert.strictEqual(sync(probe).get('same'), initial);
  assert.equal(initial.message.parts[0].text, 'two');
  assert.equal(initial.row.isLastAssistant, false);
  probe.rows = [row('node', message('other-branch', 'different'), { selectIndex: 1, branchCount: 2 })];
  const switched = sync(probe);
  assert.equal(switched.has('same'), false);
  assert.notStrictEqual(switched.get('other-branch'), initial);
});

test('loading older history preserves slots and streaming never clones historical messages', () => {
  const probe = fixture();
  const recent = row('recent-node', message('recent', 'unchanged'));
  probe.rows = [recent];
  probe.presentation.streamingTail = [message('live', 'one')];
  const initial = sync(probe);
  for (let update = 0; update < 20; update++) {
    probe.presentation.streamingTail = [message('live', `update ${update}`)];
    const current = sync(probe);
    assert.strictEqual(current.get('recent'), initial.get('recent'));
    assert.strictEqual(current.get('recent').message, recent.message);
  }
  probe.rows = [row('older-node', message('older', 'history')), recent];
  const prepended = sync(probe);
  assert.strictEqual(prepended.get('recent'), initial.get('recent'));
  assert.strictEqual(prepended.get('live'), initial.get('live'));
});

test('the actions-sheet resolver returns latest canonical rows or active tail without text signatures', () => {
  const start = source.indexOf('export function resolveChatTimelineRow(');
  assert.ok(start >= 0);
  const exports = {};
  vm.runInNewContext(compile(source.slice(start, source.indexOf('\n@Component', start))), { exports });
  const resolve = exports.resolveChatTimelineRow;
  const seed = row('node', message('same', 'old'));
  const latest = row('node', message('same', 'edited'), { branchCount: 2, selectIndex: 1 });
  assert.strictEqual(resolve([latest], [], seed), latest);
  const liveSeed = row('', message('live', 'partial'));
  const live = message('live', 'updated');
  assert.strictEqual(resolve([], [live], liveSeed).message, live);
  assert.strictEqual(resolve([], [], seed), seed);
});
