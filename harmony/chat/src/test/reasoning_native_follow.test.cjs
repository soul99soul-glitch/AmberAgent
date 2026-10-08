// Run the actual reasoning component's geometry/ownership methods with a native Scroll boundary.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

async function fixture() {
  const domain = await import('../main/ets/chat/reasoning_display.ts');
  const file = path.resolve(__dirname, '../../../entry/src/main/ets/components/ChatMessageReasoning.ets');
  const source = fs.readFileSync(file, 'utf8');
  const names = ['displayedReasoningText', 'updateThinkingContentHeight', 'updateThinkingViewportHeight',
    'requestThinkingFollow', 'stopThinkingFollow', 'beginThinkingTouch', 'endThinkingTouch',
    'pauseThinkingFollow', 'resumeThinkingFollow', 'reasoningLines', 'updateReasoningSlots'];
  const methods = names.map(name => {
    const match = source.match(new RegExp(`  private ${name}\\([^]*?\\n  \\}`));
    assert.ok(match, `${name} must be a real component method`);
    return match[0];
  }).join('\n');
  const slotSource = source.match(/class ReasoningLineSlot \{[^]*?\n\}/)?.[0];
  assert.ok(slotSource, 'reasoning slots must have a real implementation');
  const slotsModule = {};
  vm.runInNewContext(ts.transpileModule(`${slotSource}\nexports.Slot = ReasoningLineSlot;`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText, { exports: slotsModule });
  const slotWrites = [];
  let slotCreations = 0;
  class ObservedSlot {
    constructor(line) {
      slotCreations++;
      return new Proxy(new slotsModule.Slot(line), { set(target, key, value) {
        slotWrites.push({ slotKey: target.key, key, value });
        target[key] = value;
        return true;
      } });
    }
  }
  const exportsObject = {};
  const compiled = ts.transpileModule(`class Probe {${methods}\n}\nexports.Probe = Probe;`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  vm.runInNewContext(compiled, { exports: exportsObject, ReasoningLineSlot: ObservedSlot, Math, Curve: { Linear: 'linear' },
    toDisplayReasoningText: domain.toDisplayReasoningText, reasoningDisplayLimit: domain.reasoningDisplayLimit });
  const writes = [];
  let offset = 0;
  const probe = Object.assign(new exportsObject.Probe(), {
    loading: true, followThinking: true, thinkingReady: true, thinkingTouchActive: false,
    thinkingContentHeight: 0, thinkingViewportHeight: 0, thinkingAnimationTarget: -1,
    part: { reasoning: '**原始符号**\n第二行 `code`' },
    thinkingScroller: {
      currentOffset: () => ({ yOffset: offset }),
      scrollTo: options => writes.push(options),
      isAtEnd: () => offset >= Math.max(0, probe.thinkingContentHeight - probe.thinkingViewportHeight),
    },
  });
  let slots = [];
  let slotAssignments = 0;
  Object.defineProperty(probe, 'reasoningSlots', {
    get: () => slots,
    set: value => { slotAssignments++; slots = value; },
  });
  return { probe, writes, offset: value => { offset = value; }, slotWrites,
    slotCreations: () => slotCreations, slotAssignments: () => slotAssignments };
}

test('thinking text preserves raw line breaks and Markdown symbols, matching the iOS plain text view', async () => {
  const { probe } = await fixture();
  assert.equal(probe.displayedReasoningText(), '**原始符号**\n第二行 `code`');
});

test('only overflow animates and repeated geometry does not restart the glide', async () => {
  const { probe, writes } = await fixture();
  probe.updateThinkingContentHeight(150);
  probe.updateThinkingViewportHeight(180);
  assert.equal(writes.length, 0);
  probe.updateThinkingContentHeight(210);
  assert.equal(writes.length, 1);
  assert.equal(writes[0].yOffset, 30);
  assert.equal(writes[0].animation.duration, 280);
  assert.equal(writes[0].animation.curve, 'linear');
  probe.updateThinkingContentHeight(210);
  probe.updateThinkingViewportHeight(180);
  assert.equal(writes.length, 1);
});

test('a growing target retargets from the visible offset and large catch-up is speed-limited', async () => {
  const { probe, writes, offset } = await fixture();
  probe.updateThinkingViewportHeight(180);
  probe.updateThinkingContentHeight(210);
  offset(10);
  probe.updateThinkingContentHeight(750);
  assert.equal(writes.length, 2);
  assert.equal(writes[1].yOffset, 570);
  assert.equal(writes[1].animation.duration, 560 / 540 * 1000);
});

test('content measuring before a still-growing card viewport does not start a false overflow glide', async () => {
  const { probe, writes } = await fixture();
  probe.updateThinkingViewportHeight(150);
  probe.updateThinkingContentHeight(170);
  probe.updateThinkingViewportHeight(170);
  assert.equal(writes.length, 0);
});

test('touch cancels native motion; an upward browse pauses until the user returns to bottom', async () => {
  const { probe, writes, offset } = await fixture();
  probe.updateThinkingViewportHeight(180);
  probe.updateThinkingContentHeight(210);
  offset(10);
  probe.beginThinkingTouch();
  assert.equal(writes[1].animation, false);
  assert.equal(writes[1].yOffset, 10);
  probe.pauseThinkingFollow();
  probe.updateThinkingContentHeight(250);
  probe.endThinkingTouch();
  assert.equal(writes.length, 2);
  offset(70);
  probe.resumeThinkingFollow();
  probe.updateThinkingContentHeight(280);
  assert.equal(writes[2].yOffset, 100);
});

test('stop is idempotent and a detached thinking scroll cannot restart', async () => {
  const { probe, writes, offset } = await fixture();
  probe.updateThinkingViewportHeight(180);
  probe.updateThinkingContentHeight(210);
  offset(12);
  probe.stopThinkingFollow();
  probe.stopThinkingFollow();
  assert.equal(writes.length, 2);
  assert.equal(writes[1].yOffset, 12);
  assert.equal(writes[1].animation, false);
  probe.thinkingReady = false;
  probe.updateThinkingContentHeight(300);
  assert.equal(writes.length, 2);
});


test('reasoning freezes completed text blocks while preserving every original newline', async () => {
  const { probe } = await fixture();
  probe.part.reasoning = 'first'.repeat(90) + '\n\n' + 'partial'.repeat(20);
  const before = probe.reasoningLines();
  assert.equal(before.map(line => line.text).join('\n'), probe.displayedReasoningText());
  probe.part.reasoning += ' final\n';
  const after = probe.reasoningLines();
  assert.equal(after[0].key, before[0].key);
  assert.equal(after.at(-1).key, before.at(-1).key, 'growing tail must preserve native Text identity');
  assert.equal(after.map(line => line.text).join('\n'), probe.displayedReasoningText());
});

test('a 6000-character thought with 200 short lines uses bounded native text blocks', async () => {
  const { probe } = await fixture();
  probe.part.reasoning = Array.from({ length: 200 }, (_, i) => `${i}:` + 'a'.repeat(27)).join('\n');
  const before = probe.reasoningLines();
  assert.ok(before.length < 20, `${before.length} native Text nodes recreate the long-thought layout bottleneck`);
  assert.equal(before.map(line => line.text).join('\n'), probe.displayedReasoningText());
  probe.part.reasoning += '\nnew partial line';
  const after = probe.reasoningLines();
  const stable = before.slice(2, -1);
  assert.ok(stable.length > 5);
  for (const block of stable) assert.ok(after.some(next => next.key === block.key));
  assert.equal(after.map(line => line.text).join('\n'), probe.displayedReasoningText());
});

test('the stream-to-body handoff preserves the 6000-character window until explicit reopen', async () => {
  const { probe } = await fixture();
  probe.part.reasoning = Array.from({ length: 250 }, (_, i) => `${i}:` + 'z'.repeat(29)).join('\n');
  probe.preserveStreamLines = true;
  const before = probe.reasoningLines();
  const text = probe.displayedReasoningText();
  probe.loading = false;
  assert.equal(probe.displayedReasoningText(), text);
  assert.deepEqual(probe.reasoningLines(), before);
  probe.preserveStreamLines = false;
  assert.equal(probe.displayedReasoningText(), probe.part.reasoning);
});

test('window boundaries, blank lines and long unbroken paragraphs retain exact raw text', async () => {
  const { probe } = await fixture();
  for (const text of ['', '\n', 'a\n\n', 'x'.repeat(6500), 'a'.repeat(511) + '\n\n' + 'b'.repeat(6000),
    Array.from({ length: 500 }, (_, i) => i % 3 ? 'line-' + i : '').join('\n')]) {
    probe.part.reasoning = text;
    assert.equal(probe.reasoningLines().map(line => line.text).join('\n'), probe.displayedReasoningText());
  }
});


test('each delta updates the existing tail slot without republishing the ForEach array or frozen text', async () => {
  const f = await fixture();
  f.probe.part.reasoning = 'first'.repeat(90) + '\n\n' + 'partial'.repeat(20);
  f.probe.updateReasoningSlots();
  const before = f.probe.reasoningSlots;
  const tail = before.at(-1);
  const created = f.slotCreations();
  f.probe.part.reasoning += ' final';
  f.probe.updateReasoningSlots();
  assert.strictEqual(f.probe.reasoningSlots, before);
  assert.strictEqual(f.probe.reasoningSlots.at(-1), tail);
  assert.equal(f.slotCreations(), created);
  assert.equal(f.slotAssignments(), 1);
  assert.equal(f.slotWrites.length, 1);
  assert.equal(f.slotWrites[0].slotKey, tail.key);
  assert.equal(f.slotWrites[0].key, 'text');
  assert.equal(tail.text, 'partial'.repeat(20) + ' final');
  f.probe.updateReasoningSlots();
  assert.equal(f.slotWrites.length, 1, 'metadata-only updates must not invalidate text');
});

test('a moving 6000-character window retains notice and clipped-edge slots by absolute source position', async () => {
  const f = await fixture();
  f.probe.part.reasoning = Array.from({ length: 250 }, (_, i) => `${i}:` + 'z'.repeat(29)).join('\n');
  f.probe.updateReasoningSlots();
  const before = f.probe.reasoningSlots;
  const created = f.slotCreations();
  const notice = before[0];
  const clipped = before[1];
  const tail = before.at(-1);
  const stableText = before.slice(2, -1).map(slot => slot.text);
  f.probe.part.reasoning += 'x';
  f.probe.updateReasoningSlots();
  assert.strictEqual(f.probe.reasoningSlots, before);
  assert.strictEqual(f.probe.reasoningSlots[0], notice);
  assert.strictEqual(f.probe.reasoningSlots[1], clipped);
  assert.strictEqual(f.probe.reasoningSlots.at(-1), tail);
  assert.equal(f.slotCreations(), created);
  assert.equal(notice.key, 'notice');
  assert.equal(new Set(f.slotWrites.map(write => write.slotKey)).size, 3);
  assert.deepEqual(before.slice(2, -1).map(slot => slot.text), stableText);
  assert.equal(before.map(slot => slot.text).join('\n'), f.probe.displayedReasoningText());
});

test('adding and evicting blocks changes only the array structure and preserves surviving slots', async () => {
  const f = await fixture();
  f.probe.part.reasoning = 'a'.repeat(450) + '\n' + 'b'.repeat(200);
  f.probe.updateReasoningSlots();
  const first = f.probe.reasoningSlots;
  f.probe.part.reasoning += '\n' + 'c'.repeat(400);
  f.probe.updateReasoningSlots();
  assert.notStrictEqual(f.probe.reasoningSlots, first);
  assert.strictEqual(f.probe.reasoningSlots[0], first[0]);
  assert.strictEqual(f.probe.reasoningSlots[1], first[1]);
  const large = Array.from({ length: 260 }, (_, i) => `${i}:` + 'x'.repeat(28)).join('\n');
  f.probe.part.reasoning = large;
  f.probe.updateReasoningSlots();
  const oldByKey = new Map(f.probe.reasoningSlots.map(slot => [slot.key, slot]));
  f.probe.part.reasoning += '\n' + 'new line '.repeat(80);
  f.probe.updateReasoningSlots();
  for (const slot of f.probe.reasoningSlots) {
    if (oldByKey.has(slot.key)) assert.strictEqual(slot, oldByKey.get(slot.key));
  }
  assert.equal(f.probe.reasoningSlots.map(slot => slot.text).join('\n'), f.probe.displayedReasoningText());
});

test('finished handoff retains stream slots and explicit reopen leaves the continuous Text path intact', async () => {
  const f = await fixture();
  f.probe.preserveStreamLines = true;
  f.probe.part.reasoning = 'long thought\n'.repeat(700);
  f.probe.updateReasoningSlots();
  const slots = f.probe.reasoningSlots;
  f.probe.loading = false;
  f.probe.updateReasoningSlots();
  assert.strictEqual(f.probe.reasoningSlots, slots);
  assert.equal(f.slotWrites.length, 0);
  f.probe.preserveStreamLines = false;
  f.probe.updateReasoningSlots();
  assert.strictEqual(f.probe.reasoningSlots, slots, 'hidden slots do not rebuild when explicitly opening full text');
  assert.equal(f.probe.displayedReasoningText(), f.probe.part.reasoning);
  const source = fs.readFileSync(path.resolve(__dirname,
    '../../../entry/src/main/ets/components/ChatMessageReasoning.ets'), 'utf8');
  assert.match(source, /@Prop @Watch\('updateReasoningSlots'\) part:/);
  assert.match(source, /@ObjectLink slot: ReasoningLineSlot/);
  assert.match(source, /ForEach\(this\.reasoningSlots/);
  assert.doesNotMatch(source, /ForEach\(this\.reasoningLines\(\)/);
  assert.match(source, /Text\(this\.displayedReasoningText\(\)\)/);
});
