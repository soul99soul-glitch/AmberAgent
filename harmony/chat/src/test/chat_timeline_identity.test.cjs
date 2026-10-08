// Exercise the actual V1 ChatPage projection with retained ForEach item closures.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const source = fs.readFileSync(path.resolve(__dirname, '../../../entry/src/main/ets/pages/ChatPage.ets'), 'utf8');

function fixture() {
  const names = ['currentTimelineRow', 'displayedRowMessage', 'chatTimelineItems',
    'timelineRowStreaming', 'timelineRowKey'];
  const methods = names.map(name => {
    const match = source.match(new RegExp(`  private ${name}\\([^]*?\\n  \\}`));
    assert.ok(match, `ChatPage.${name} must resolve retained rows from current state`);
    return match[0];
  }).join('\n');
  const compiled = ts.transpileModule(`class Probe { ${methods} }\nexports.Probe = Probe;`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  const exportsObject = {};
  vm.runInNewContext(compiled, { exports: exportsObject });
  return Object.assign(new exportsObject.Probe(), { rows: [], streamingTail: [], regenTail: null,
    regenNodeId: '', darkMode: false });
}
const message = (id, text, role = 'assistant') => ({ id, role, parts: [{ type: 'text', text, metadata: null }] });
const row = (nodeId, message, extra = {}) => ({ nodeId, message, branchCount: 1, selectIndex: 0,
  isAssistant: message.role === 'assistant', isLastAssistant: message.role === 'assistant', displayRevision: 0, ...extra });
function reconcile(probe, mounted = new Map()) {
  return new Map(probe.chatTimelineItems().map(seed => {
    const key = probe.timelineRowKey(seed);
    return [key, mounted.get(key) ?? { seed, revealProgress: 0 }];
  }));
}

test('streaming completion retains the exact row instance, reveal progress and current controls', () => {
  const probe = fixture();
  probe.rows = [row('user-node', message('user', 'prompt', 'user'))];
  probe.streamingTail = [message('answer', 'first')];
  const live = reconcile(probe);
  const active = live.get('answer');
  active.revealProgress = 3;
  probe.streamingTail = [message('answer', 'first and final')];
  assert.equal(probe.displayedRowMessage(active.seed).parts[0].text, 'first and final');
  assert.equal(probe.timelineRowStreaming(active.seed), true);
  const finalRow = row('persisted-node', probe.streamingTail[0]);
  probe.rows = [probe.rows[0], finalRow];
  probe.streamingTail = [];
  const complete = reconcile(probe, live);
  assert.strictEqual(complete.get('answer'), active);
  assert.equal(complete.get('answer').revealProgress, 3);
  assert.strictEqual(probe.currentTimelineRow(active.seed), finalRow);
  assert.equal(probe.timelineRowStreaming(active.seed), false);
});

test('a tool continuation and all terminal assistant messages retain their own identities', () => {
  const probe = fixture();
  probe.streamingTail = [message('tool-answer', 'tool output'), message('summary', 'partial')];
  const live = reconcile(probe);
  probe.rows = probe.streamingTail.map((item, index) => row(`node-${index}`, item));
  probe.streamingTail = [];
  const complete = reconcile(probe, live);
  assert.strictEqual(complete.get('tool-answer'), live.get('tool-answer'));
  assert.strictEqual(complete.get('summary'), live.get('summary'));
  assert.equal(probe.currentTimelineRow(live.get('summary').seed).nodeId, 'node-1');
});

test('regeneration keeps the new branch instance through commit and reads latest branch metadata', () => {
  const probe = fixture();
  const original = row('node', message('old-branch', 'old'));
  probe.rows = [original];
  probe.regenNodeId = 'node';
  probe.regenTail = message('new-branch', 'partial');
  const live = reconcile(probe);
  const active = live.get('new-branch');
  assert.equal(probe.timelineRowStreaming(active.seed), true);
  probe.rows = [row('node', message('new-branch', 'complete'), { branchCount: 2, selectIndex: 1, displayRevision: 4 })];
  probe.regenTail = null;
  probe.regenNodeId = '';
  const complete = reconcile(probe, live);
  assert.strictEqual(complete.get('new-branch'), active);
  assert.equal(probe.currentTimelineRow(active.seed).branchCount, 2);
  assert.equal(probe.currentTimelineRow(active.seed).selectIndex, 1);
  assert.equal(probe.displayedRowMessage(active.seed).parts[0].text, 'complete');
});

test('resuming tools on a formerly streamed row keeps its row and Markdown stream identity', () => {
  const probe = fixture();
  probe.streamingTail = [message('answer', 'waiting for approval')];
  const live = reconcile(probe);
  const active = live.get('answer');
  probe.rows = [row('node', probe.streamingTail[0])];
  probe.streamingTail = [];
  probe.regenNodeId = 'node';
  probe.regenTail = message('answer', 'approved and continuing');
  const resumed = reconcile(probe, live);
  assert.strictEqual(resumed.get('answer'), active);
  assert.equal(probe.timelineRowStreaming(active.seed), true);
  assert.equal(probe.displayedRowMessage(active.seed).parts[0].text, 'approved and continuing');
  const builder = source.match(/  chatTimelineRows\(\): void \{[^]*?\n  \}/)?.[0] ?? '';
  assert.doesNotMatch(builder, /streamKeyTag:/, 'resume must keep the same Markdown stream key');
});

test('same-id edits and tool approvals refresh content without revision-key remounts', () => {
  const probe = fixture();
  const seed = row('node', message('same', 'one'));
  probe.rows = [seed];
  const initial = reconcile(probe);
  const updated = row('node', message('same', 'two'), { displayRevision: 9, isLastAssistant: false });
  probe.rows = [updated];
  const next = reconcile(probe, initial);
  assert.strictEqual(next.get('same'), initial.get('same'));
  assert.equal(probe.displayedRowMessage(seed).parts[0].text, 'two');
  assert.equal(probe.currentTimelineRow(seed).isLastAssistant, false);
  probe.rows = [row('node', message('other-branch', 'different'), { selectIndex: 1, branchCount: 2 })];
  const switched = reconcile(probe, next);
  assert.equal(switched.has('same'), false);
  assert.notStrictEqual(switched.get('other-branch'), next.get('same'));
});

test('loading older history preserves identities and streaming projection never clones historical messages', () => {
  const probe = fixture();
  const recent = row('recent-node', message('recent', 'unchanged'));
  probe.rows = [recent];
  probe.streamingTail = [message('live', 'one')];
  const initial = reconcile(probe);
  for (let update = 0; update < 20; update++) {
    probe.streamingTail = [message('live', `update ${update}`)];
    assert.strictEqual(probe.chatTimelineItems()[0], recent);
    assert.strictEqual(probe.displayedRowMessage(recent), recent.message);
  }
  probe.rows = [row('older-node', message('older', 'history')), recent];
  const prepended = reconcile(probe, initial);
  assert.strictEqual(prepended.get('recent'), initial.get('recent'));
  assert.strictEqual(prepended.get('live'), initial.get('live'));
});

