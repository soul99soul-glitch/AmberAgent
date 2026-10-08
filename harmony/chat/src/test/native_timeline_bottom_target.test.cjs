// Exercise ChatPage's actual geometry calculation without mounting unrelated ArkUI builders.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const file = path.resolve(__dirname, '../../../entry/src/main/ets/pages/ChatPage.ets');
const source = fs.readFileSync(file, 'utf8');
const method = source.match(/  private measuredBottomTarget\(\): number \| null \{[\s\S]*?\n  \}/);
assert.ok(method, 'ChatPage must expose the measured bottom target calculation');
const compiled = ts.transpileModule(`class TargetProbe {\n${method[0]}\n}\nexports.TargetProbe = TargetProbe;`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
}).outputText;
const exportsObject = {};
vm.runInNewContext(compiled, { exports: exportsObject, Math });

function target({ offset = 100, height = 530, y = 0, viewport = 500, navBarHeight = 24,
  atEnd = false, lastVisible = 1, itemCount = 2, suggestions = [], onMeasure = () => {} } = {}) {
  return exportsObject.TargetProbe.prototype.measuredBottomTarget.call({
    scroller: {
      currentOffset: () => ({ yOffset: offset }),
      isAtEnd: () => atEnd,
      getItemRect: index => { onMeasure(index); return { y, height }; },
    },
    activeListItemCount: () => itemCount,
    lastVisibleListIndex: lastVisible,
    listViewportHeight: viewport,
    navBarHeight,
    suggestions,
  });
}

test('one new line follows its measured growth without the composer navigation inset', () => {
  assert.equal(target(), 130, 'a 30vp line must add only 30vp to the scroll target');
  assert.equal(target({ navBarHeight: 0 }), 130);
  assert.equal(target({ navBarHeight: 40 }), 130);
});

test('measured target settles through the spring instead of hitting the native boundary early', async () => {
  const { nativeTimelineSpringStep } = await import('../main/ets/chat/native_timeline_motion.ts');
  const realBottom = 130;
  const measured = target();
  let position = 100;
  let velocity = 0;
  for (let frame = 0; frame < 4; frame++) {
    const sample = nativeTimelineSpringStep(position, velocity, measured, 1 / 60);
    position = Math.min(realBottom, sample.position);
    velocity = sample.velocity;
  }
  assert.ok(position > 115 && position < 125,
    `after four frames the 30vp growth should still be easing, got ${position}`);
});

test('an already settled list and an unmeasured tail retain their existing semantics', () => {
  assert.equal(target({ atEnd: true }), 100);
  assert.equal(target({ lastVisible: 0 }), null);
  assert.equal(target({ viewport: 0 }), null);
});


test('normal text growth keeps a measurable target when only the suggestion spacer leaves the viewport', async () => {
  const measuredIndices = [];
  const measured = target({ offset: 1000, y: -400, height: 940, viewport: 500,
    itemCount: 3, lastVisible: 1, suggestions: ['follow-up'], onMeasure: index => measuredIndices.push(index) });
  assert.equal(measured, 1092, 'visible message bottom + actual 52vp spacer must replace the null edge-jump target');
  assert.deepEqual(measuredIndices, [1], 'read the visible message, not the offscreen spacer');
  const { nativeTimelineSpringStep } = await import('../main/ets/chat/native_timeline_motion.ts');
  const sample = nativeTimelineSpringStep(1000, 0, measured, 1 / 60);
  assert.ok(sample.position > 1000 && sample.position < 1020,
    `first frame must ease toward 1092, got ${sample.position}`);
});

test('a visible suggestion spacer uses its measured rectangle without adding its height twice', () => {
  const measuredIndices = [];
  assert.equal(target({ offset: 1000, y: 480, height: 52, viewport: 500,
    itemCount: 3, lastVisible: 2, suggestions: ['follow-up'], onMeasure: index => measuredIndices.push(index) }), 1032);
  assert.deepEqual(measuredIndices, [2]);
});

test('unmeasured structural messages and missing viewport geometry preserve the existing null result', () => {
  const onMeasure = () => assert.fail('an unmeasured message must not be queried');
  assert.equal(target({ itemCount: 3, lastVisible: 1, onMeasure }), null);
  assert.equal(target({ itemCount: 3, lastVisible: 0, suggestions: ['follow-up'], onMeasure }), null);
  assert.equal(target({ itemCount: 3, lastVisible: 1, viewport: 0, suggestions: ['follow-up'], onMeasure }), null);
  assert.equal(target({ itemCount: 1, lastVisible: -1, suggestions: ['follow-up'], onMeasure }), null);
});
