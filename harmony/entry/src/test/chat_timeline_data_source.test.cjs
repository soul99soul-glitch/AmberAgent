const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');

function productionSource() {
  const base = path.resolve(__dirname, '../main/ets');
  const compile = source => ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const state = {};
  new Function('exports', compile(fs.readFileSync(path.join(base, 'components/MessageRowViewState.ets'), 'utf8')))(state);
  const exports = {};
  new Function('exports', 'require', 'Observed',
    compile(fs.readFileSync(path.join(base, 'platform_impl/chat/ChatTimelineDataSource.ets'), 'utf8')))(
    exports, name => {
      if (name.endsWith('/MessageRowViewState.ets')) return state;
      throw new Error('Unexpected timeline source dependency: ' + name);
    }, value => value);
  return new exports.ChatTimelineDataSource();
}

const message = (id, text, role = 'assistant') => ({ id, role, parts: [{ type: 'text', text, metadata: null }] });
const row = (id, item, extra = {}) => ({ nodeId: id, message: item, branchCount: 1,
  selectIndex: 0, isAssistant: item.role === 'assistant', isLastAssistant: item.role === 'assistant',
  displayRevision: 0, displaySignature: '', ...extra });
const noCompact = () => 0;
function observe(source) {
  const keys = [], events = [];
  const assertCurrentKeys = () => assert.deepEqual(keys,
    Array.from({ length: source.totalCount() }, (_, index) => source.getData(index).key));
  const listener = {
    onDataAdd(index) { events.push(['add', index]); keys.splice(index, 0, source.getData(index).key); assertCurrentKeys(); },
    onDataDelete(index) { events.push(['delete', index]); keys.splice(index, 1); assertCurrentKeys(); },
    onDataChange() { assert.fail('same-key content updates must use observed slots'); },
    onDataReloaded() { assert.fail('timeline updates must not reload all rows'); },
  };
  source.registerDataChangeListener(listener);
  return { keys, events, listener };
}

test('twenty text frames update one live slot without structural events or historical writes', () => {
  const source = productionSource();
  const observed = observe(source);
  const history = row('user-node', message('user', 'prompt', 'user'));
  source.sync([history], [message('answer', 'first')], null, '', noCompact);
  const historical = source.getData(0), live = source.getData(1);
  let writes = 0;
  let historicalRow = historical.row;
  Object.defineProperty(historical, 'row', { get: () => historicalRow,
    set: value => { writes++; historicalRow = value; } });
  observed.events.length = 0;
  for (let index = 0; index < 20; index++) {
    const next = message('answer', 'update ' + index);
    source.sync([{ ...history, displaySignature: 'ignored legacy signature ' + index }], [next], null, '', noCompact);
    assert.strictEqual(source.getData(1), live);
    assert.strictEqual(live.message, next);
    assert.equal(live.streaming, true);
  }
  assert.equal(writes, 0);
  assert.deepEqual(observed.events, []);
  assert.deepEqual(observed.keys, ['message:user', 'message:answer']);
  const committed = row('answer-node', live.message, { branchCount: 2, selectIndex: 1 });
  source.sync([history, committed], [], null, '', noCompact);
  assert.strictEqual(source.getData(1), live);
  assert.strictEqual(live.row, committed);
  assert.equal(live.streaming, false);
  assert.deepEqual(observed.events, []);
});

test('regeneration and tool resumption keep the displayed message identity through commit', () => {
  const source = productionSource();
  const observed = observe(source);
  const original = row('node', message('old', 'old branch'));
  const regenerated = message('new', 'partial');
  source.sync([original], [], regenerated, 'node', noCompact);
  const live = source.getData(0), state = live.viewState;
  state.askAnswers.question = 'draft';
  assert.equal(live.key, 'message:new');
  assert.strictEqual(live.message, regenerated);
  const committed = row('node', message('new', 'complete'), { branchCount: 2, selectIndex: 1 });
  observed.events.length = 0;
  source.sync([committed], [], null, '', noCompact);
  assert.strictEqual(source.getData(0), live);
  assert.strictEqual(live.viewState, state);
  assert.equal(live.row.selectIndex, 1);
  assert.equal(live.message.parts[0].text, 'complete');
  source.sync([committed], [], message('new', 'approved continuation'), 'node', noCompact);
  assert.strictEqual(source.getData(0), live);
  assert.equal(live.streaming, true);
  assert.equal(live.viewState.askAnswers.question, 'draft');
  assert.deepEqual(observed.events, []);
});

test('prepend and compact marker changes maintain actual data indices and notification order', () => {
  const source = productionSource();
  const observed = observe(source);
  const a = row('a-node', message('a', 'a'));
  const b = row('b-node', message('b', 'b'));
  source.sync([a, b], [], null, '', id => id === 'a' ? 2 : 0);
  const retained = source.getData(2);
  assert.deepEqual(observed.keys, ['message:a', 'compact:a', 'message:b']);
  assert.equal(source.dataIndexForRow(1), 2);
  assert.equal(source.dataIndexForNode('b-node'), 2);
  assert.equal(source.rowIndexAt(1), 0);
  const older = row('older-node', message('older', 'older'));
  source.sync([older, a, b], [], null, '', id => id === 'older' || id === 'b' ? 1 : 0);
  assert.deepEqual(observed.keys, ['message:older', 'compact:older', 'message:a', 'message:b', 'compact:b']);
  assert.strictEqual(source.getData(3), retained);
  assert.equal(source.dataIndexForRow(0), 0);
  assert.equal(source.dataIndexForRow(1), 2);
  assert.equal(source.dataIndexForRow(2), 3);
  assert.equal(source.dataIndexForNode('b-node'), 3);
  assert.equal(source.rowIndexAt(4), 2);
  assert.equal(source.rowIndexAt(-1), -1);
  assert.equal(source.rowIndexAt(9), -1);
  assert.equal(source.dataIndexForNode('missing'), -1);
  assert.equal(source.dataIndexForNode(''), -1);
  assert.equal(source.dataIndexForRow(-1), -1);
});

test('removed message views can recover their state until the conversation is cleared', () => {
  const source = productionSource();
  const observed = observe(source);
  const first = row('node', message('first', 'first branch'));
  const other = row('node', message('other', 'other branch'));
  source.sync([first], [], null, '', noCompact);
  const state = source.getData(0).viewState;
  state.reasoningStates.reason = 'expanded';
  source.sync([other], [], null, '', noCompact);
  assert.notStrictEqual(source.getData(0).viewState, state);
  source.sync([first], [], null, '', noCompact);
  assert.strictEqual(source.getData(0).viewState, state);
  source.clear();
  assert.equal(source.totalCount(), 0);
  assert.deepEqual(observed.keys, []);
  source.sync([first], [], null, '', noCompact);
  assert.notStrictEqual(source.getData(0).viewState, state);
});

test('a tail message already present in history updates the same item instead of duplicating keys', () => {
  const source = productionSource();
  const observed = observe(source);
  const current = row('node', message('same', 'saved'));
  const live = message('same', 'new tail');
  source.sync([current], [live], null, '', noCompact);
  assert.equal(source.totalCount(), 1);
  assert.deepEqual(observed.keys, ['message:same']);
  assert.strictEqual(source.getData(0).message, live);
  assert.equal(source.getData(0).row.nodeId, 'node');
  assert.strictEqual(source.getData(0).row.message, current.message);
  assert.equal(source.dataIndexForNode(''), -1);
});
