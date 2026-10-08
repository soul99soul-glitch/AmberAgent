const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');

function fixture() {
  const source = fs.readFileSync(path.join(__dirname, '../main/ets/components/studio/StudioInkStamp.ets'), 'utf8');
  const start = source.indexOf('export struct StudioInkStamp {') + 'export struct StudioInkStamp {'.length;
  const body = source.slice(start, source.indexOf('  build()'))
    .replace(/@(StorageProp|Watch)\([^)]*\)\s*/g, '').replace(/@(Prop|State)\s*/g, '');
  const js = ts.transpileModule('class Stamp {' + body + '} return Stamp;',
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const pending = new Map();
  let nextId = 0;
  const Stamp = new Function('setTimeout', 'clearTimeout', 'curves', 'Curve', js)(
    callback => { const id = ++nextId; pending.set(id, callback); return id; },
    id => pending.delete(id), { springCurve: () => 'spring' }, { EaseOut: 'ease' });
  const stamp = new Stamp();
  const visibility = [];
  stamp.onVisibilityChange = value => visibility.push(value);
  stamp.getUIContext = () => ({ animateTo: (_options, update) => update() });
  return { stamp, pending, visibility };
}

test('opening without a new trigger stays quiet; repeated stamps replace their pending fade', () => {
  const f = fixture();
  f.stamp.aboutToAppear();
  assert.equal(f.stamp.ink, 0);
  assert.equal(f.pending.size, 0);
  f.stamp.trigger = 1; f.stamp.play();
  const firstId = [...f.pending.keys()][0];
  f.stamp.trigger = 2; f.stamp.play();
  assert.equal(f.pending.has(firstId), false);
  assert.equal(f.pending.size, 1);
  [...f.pending.values()][0]();
  assert.equal(f.stamp.ink, 0);
  assert.deepEqual(f.visibility, [true, true, false]);
});

test('leaving during a stamp settles it and suppresses a late fade callback', () => {
  const f = fixture();
  f.stamp.aboutToAppear(); f.stamp.trigger = 1; f.stamp.play();
  const late = [...f.pending.values()][0];
  f.stamp.active = false; f.stamp.activeChanged();
  assert.equal(f.pending.size, 0);
  assert.equal(f.stamp.ink, 0);
  late();
  assert.deepEqual(f.visibility, [true, false]);
});

test('destroyed stamps cannot update their owner through a retained callback', () => {
  const f = fixture();
  f.stamp.aboutToAppear(); f.stamp.trigger = 1; f.stamp.play();
  const late = [...f.pending.values()][0];
  f.stamp.aboutToDisappear(); late();
  assert.equal(f.pending.size, 0);
  assert.deepEqual(f.visibility, [true]);
});

test('backgrounding settles the stamp without restarting it on foreground', () => {
  const f = fixture();
  f.stamp.aboutToAppear(); f.stamp.trigger = 1; f.stamp.play();
  const late = [...f.pending.values()][0];
  f.stamp.appBackgrounded = true; f.stamp.backgroundChanged(); late();
  assert.equal(f.pending.size, 0);
  assert.equal(f.stamp.ink, 0);
  f.stamp.appBackgrounded = false; f.stamp.backgroundChanged();
  assert.equal(f.pending.size, 0);
  assert.deepEqual(f.visibility, [true, false]);
});
