const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

function fixture() {
  const file = path.resolve(__dirname, '../../../entry/src/main/ets/components/MarkdownText.ets');
  const source = fs.readFileSync(file, 'utf8');
  const names = ['updateDisplay', 'updateBlockSlots'];
  const methods = names.map(name => source.match(new RegExp(`  private ${name}\\([^]*?\\n  \\}`))?.[0] ?? '').join('\n');
  const writes = [];
  class MarkdownBlockSlot {
    constructor(index, block, tableColumnWidths) {
      this.index = index;
      this.key = `md-${index}`;
      this.block = block;
      this.tableColumnWidths = tableColumnWidths;
      return new Proxy(this, { set(target, key, value) {
        writes.push({ index, key, value }); target[key] = value; return true;
      } });
    }
  }
  const exports = {};
  vm.runInNewContext(ts.transpileModule(`class Probe {${methods}}; exports.Probe = Probe;`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText, { exports, MarkdownBlockSlot });
  let assignments = 0;
  let slots = [];
  let nextBlocks = [];
  let tableMeasurements = 0;
  const probe = Object.assign(new exports.Probe(), {
    content: '', lastParsedText: '', parsedBlocks: [], displayText: '', hasDisplayContent: false,
    parseBlocks: () => nextBlocks,
    resolveTableWidths: () => { tableMeasurements++; return [120]; },
  });
  Object.defineProperty(probe, 'displaySlots', { get: () => slots, set: value => { assignments++; slots = value; } });
  return { probe, writes, slots: () => slots, assignments: () => assignments,
    tableMeasurements: () => tableMeasurements,
    publish(blocks) { nextBlocks = blocks; probe.content += 'x'; probe.updateDisplay(); } };
}

const paragraph = text => ({ kind: 'paragraph', inlines: [{ type: 'text', text }] });

test('48ms append leaves frozen slots untouched and updates the live tail on the same slot', () => {
  const f = fixture();
  const frozen = paragraph('finished');
  const firstTail = paragraph('par');
  f.publish([frozen, firstTail]);
  assert.equal(f.slots().length, 2);
  const oldSlots = f.slots();
  const tailSlot = oldSlots[1];
  const nextTail = paragraph('partial');
  f.publish([frozen, nextTail]);
  assert.equal(f.slots(), oldSlots, 'ordinary append must not invalidate the ForEach array');
  assert.equal(f.slots()[1], tailSlot, 'live native Text must keep its owner');
  assert.equal(tailSlot.block, nextTail, 'stable identity must not freeze the live tail');
  assert.equal(f.writes.filter(write => write.index === 0).length, 0);
  assert.deepEqual(f.writes.filter(write => write.key === 'block').map(write => write.index), [1]);
});

test('completed tables retain their widths and avoid another width scan on every tail update', () => {
  const f = fixture();
  const table = { kind: 'table', headers: [], rows: [], alignments: [] };
  f.publish([table, paragraph('a')]);
  const widths = f.slots()[0].tableColumnWidths;
  for (let i = 0; i < 10; i++) f.publish([table, paragraph(`a${i}`)]);
  assert.equal(f.tableMeasurements(), 1);
  assert.equal(f.slots()[0].tableColumnWidths, widths);
  assert.equal(f.writes.filter(write => write.index === 0).length, 0);
});

test('closing a tail and appending a block preserves all existing slot identities', () => {
  const f = fixture();
  const first = paragraph('first');
  const tail = paragraph('tail');
  f.publish([first, tail]);
  const initial = [...f.slots()];
  const closedTail = paragraph('tail complete');
  f.publish([first, closedTail, paragraph('new')]);
  assert.equal(f.assignments(), 2);
  assert.equal(f.slots()[0], initial[0]);
  assert.equal(f.slots()[1], initial[1]);
  assert.equal(f.slots()[1].block, closedTail);
  assert.equal(f.slots()[2].index, 2);
});

test('retyping a block keeps the slot but publishes the new AST and table geometry', () => {
  const f = fixture();
  f.publish([paragraph('| a |')]);
  const slot = f.slots()[0];
  const table = { kind: 'table', headers: [], rows: [], alignments: [] };
  f.publish([table]);
  assert.equal(f.slots()[0], slot);
  assert.equal(slot.block, table);
  assert.deepEqual(slot.tableColumnWidths, [120]);
  assert.equal(f.tableMeasurements(), 1);
});

test('replacement and truncation remove obsolete blocks without leaving stale captured data', () => {
  const f = fixture();
  f.publish([paragraph('old first'), paragraph('old second')]);
  const firstSlot = f.slots()[0];
  const replacement = paragraph('replacement');
  f.publish([replacement]);
  assert.equal(f.slots().length, 1);
  assert.equal(f.slots()[0], firstSlot);
  assert.equal(firstSlot.block, replacement);
  f.publish([]);
  assert.equal(f.slots().length, 0);
});


test('real incremental Markdown cache emits zero frozen-prefix writes across repeated streaming snapshots', async () => {
  const { MarkdownCache } = await import('../main/ets/chat/markdown_cache.ts');
  const cache = new MarkdownCache();
  const f = fixture();
  let text = '# Frozen title\n\nLive';
  let previousLength = 0;
  f.probe.parseBlocks = () => cache.getOrParse('slot-integration', previousLength, text).blocks;
  f.probe.content = text;
  f.probe.updateDisplay();
  const frozenSlot = f.slots()[0];
  const liveSlot = f.slots()[1];
  const array = f.slots();
  for (let i = 0; i < 30; i++) {
    previousLength = text.length;
    text += ` delta-${i}`;
    f.probe.content = text;
    f.probe.updateDisplay();
    assert.equal(f.slots(), array);
    assert.equal(f.slots()[0], frozenSlot);
    assert.equal(f.slots()[1], liveSlot);
    assert.ok(liveSlot.block.inlines.map(token => token.text).join('').endsWith(`delta-${i}`));
  }
  assert.equal(f.writes.filter(write => write.index === 0).length, 0);
  assert.equal(f.writes.filter(write => write.index === 1 && write.key === 'block').length, 30);
  assert.equal(f.writes.filter(write => write.key === 'tableColumnWidths').length, 0);
});
