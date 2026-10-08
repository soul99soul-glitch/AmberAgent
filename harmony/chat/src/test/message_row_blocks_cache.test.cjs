const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const text = value => ({ type: 'text', text: value, metadata: null });
const reasoning = (value, finishedAt = null) => ({ type: 'reasoning', reasoning: value,
  createdAt: '2026-10-01T00:00:00Z', finishedAt, metadata: null });
const tool = (approvalState, output = []) => ({ type: 'tool', toolCallId: 'tool-1',
  toolName: 'read_file', input: '{}', output, approvalState, metadata: null });

async function fixture(initialParts) {
  const { groupMessageParts } = await import('../main/ets/chat/message_grouping.ts');
  const file = path.resolve(__dirname, '../../../entry/src/main/ets/components/AgentMessageRow.ets');
  const source = fs.readFileSync(file, 'utf8');
  const names = ['getBlocks', 'onMessageChanged', 'thinkingStepsAt', 'reasoningPartAt', 'toolPartAt',
    'contentTextAt', 'isLastTextBlock'];
  const methods = names.map(name => source.match(new RegExp(`  private ${name}\\([^]*?\\n  \\}`))?.[0] ?? '').join('\n');
  let groupCalls = 0;
  let messageReads = 0;
  let partsReads = 0;
  let message;
  const exports = {};
  vm.runInNewContext(ts.transpileModule(`class Probe {${methods}}; exports.Probe = Probe;`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText, { exports, groupMessageParts: parts => { groupCalls++; return groupMessageParts(parts); } });
  const probe = new exports.Probe();
  probe.cachedBlocks = null;
  Object.defineProperty(probe, 'message', { get() { messageReads++; return message; } });
  function publish(parts, id = 'same-message-id') {
    message = { id, role: 'assistant' };
    Object.defineProperty(message, 'parts', { get() { partsReads++; return parts; } });
    if (probe.onMessageChanged) probe.onMessageChanged();
  }
  publish(initialParts);
  return { probe, source, publish, groupCalls: () => groupCalls,
    messageReads: () => messageReads, partsReads: () => partsReads };
}

test('one reasoning/body snapshot is grouped once across all six existing render reads', async () => {
  const f = await fixture([reasoning('completed thought'.repeat(500)), text('live body')]);
  const blocks = f.probe.getBlocks();
  f.probe.thinkingStepsAt(0);
  f.probe.reasoningPartAt(0, 0);
  f.probe.reasoningPartAt(0, 0); // loading argument uses the same accessor again.
  assert.equal(f.probe.contentTextAt(1), 'live body');
  assert.equal(f.probe.isLastTextBlock(f.probe.getBlocks(), 1), true);
  assert.equal(f.groupCalls(), 1);
  assert.equal(f.messageReads(), 6, 'cache hits must still register the V1 message dependency');
  assert.equal(f.partsReads(), 6, 'read current parts even when the derived blocks are cached');
  assert.equal(f.probe.getBlocks(), blocks);
});

test('same-id text snapshots invalidate grouping without changing content part indices', async () => {
  const f = await fixture([reasoning('done'), text('first'), text(' second')]);
  const before = f.probe.getBlocks();
  assert.equal(f.probe.contentTextAt(1), 'first second');
  f.publish([reasoning('done'), text('new'), text(' content')]);
  const after = f.probe.getBlocks();
  assert.notEqual(after, before);
  assert.equal(f.probe.contentTextAt(1), 'new content');
  assert.equal(after[1].index, 1);
  assert.equal(f.groupCalls(), 2);
});

test('same-id terminal reasoning metadata and tool approval/output changes invalidate the cache', async () => {
  const f = await fixture([reasoning('thought'), tool({ type: 'pending' }), text('body')]);
  f.probe.getBlocks();
  assert.equal(f.probe.reasoningPartAt(0, 0).finishedAt, null);
  f.publish([reasoning('thought', '2026-10-01T00:00:02Z'),
    tool({ type: 'denied', reason: 'user choice' }, [text('tool result')]), text('body')]);
  assert.equal(f.probe.reasoningPartAt(0, 0).finishedAt, '2026-10-01T00:00:02Z');
  assert.equal(f.probe.toolPartAt(0, 1).approvalState.type, 'denied');
  assert.equal(f.probe.toolPartAt(0, 1).output[0].text, 'tool result');
  assert.equal(f.groupCalls(), 2);
});

test('the actual message Prop watcher owns invalidation, including empty and replacement messages', async () => {
  const f = await fixture([text('old')]);
  assert.match(f.source, /@Prop\s+@Watch\('onMessageChanged'\)\s+message:\s*UIMessage/);
  f.probe.getBlocks();
  f.publish([], 'replacement-id');
  assert.equal(f.probe.getBlocks().length, 0);
  f.publish([text('replacement')], 'replacement-id');
  assert.equal(f.probe.contentTextAt(0), 'replacement');
  assert.equal(f.groupCalls(), 3);
});
