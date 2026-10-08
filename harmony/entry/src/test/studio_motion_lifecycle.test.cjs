const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');

// Execute the production component's fields and methods, without rendering ArkUI.
function sceneFixture(active = true) {
  const source = fs.readFileSync(path.join(__dirname, '../main/ets/components/studio/StudioEmblem.ets'), 'utf8');
  const start = source.indexOf('export struct StudioEmblem {') + 'export struct StudioEmblem {'.length;
  const body = source.slice(start, source.indexOf('  @Builder'))
    .replace(/@(StorageProp|Watch)\([^)]*\)\s*/g, '').replace(/@(Prop|State)\s*/g, '');
  const js = ts.transpileModule('class Scene {' + body + '} return Scene;',
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  let now = 0, nextId = 0;
  const pending = new Map();
  const schedule = (callback, delay) => { const id = ++nextId; pending.set(id, { callback, time: now + delay }); return id; };
  const Scene = new Function('setTimeout', 'clearTimeout', 'curves', js)(
    schedule, id => pending.delete(id), { springCurve: () => 'spring' });
  const scene = new Scene();
  const secrets = [];
  scene.active = active;
  scene.onSecretChange = value => secrets.push(value);
  scene.getUIContext = () => ({ animateTo: (_options, update) => update() });
  function advance(ms) {
    const end = now + ms;
    while (true) {
      const due = [...pending].filter(([, timer]) => timer.time <= end).sort((a, b) => a[1].time - b[1].time)[0];
      if (!due) break;
      pending.delete(due[0]); now = due[1].time; due[1].callback();
    }
    now = end;
  }
  return { scene, pending, secrets, advance };
}

test('theme remount while the page is hidden still reveals the emblem when the page returns', () => {
  const f = sceneFixture(false);
  f.scene.aboutToAppear(); f.advance(40);
  f.scene.active = true; f.scene.activeChanged();
  assert.equal(f.scene.revealed, true);
  assert.equal(f.pending.size, 0);
});

test('hiding during a secret clears timers and settles the illustration without a retained callback', () => {
  const f = sceneFixture();
  f.scene.aboutToAppear(); f.advance(40); f.scene.unfold(true);
  assert.equal(f.scene.spread, 1);
  f.scene.active = false; f.scene.activeChanged();
  assert.equal(f.pending.size, 0);
  assert.equal(f.scene.spread, 0);
  assert.equal(f.scene.sparkling, false);
  assert.equal(f.scene.playing, false);
  f.advance(4000);
  assert.deepEqual(f.secrets, [true, false]);
});

test('destroying a scene cancels its delayed work and prevents late callbacks from updating its parent', () => {
  const f = sceneFixture();
  f.scene.aboutToAppear(); f.advance(40); f.scene.unfold(true);
  const stale = [...f.pending.values()].map(timer => timer.callback);
  f.scene.aboutToDisappear();
  assert.equal(f.pending.size, 0);
  stale.forEach(callback => callback());
  assert.deepEqual(f.secrets, [true]);
  assert.equal(f.pending.size, 0);
});

test('release click cannot replace a long-press secret and the finite animation accepts the next tap', () => {
  const f = sceneFixture();
  f.scene.aboutToAppear(); f.advance(40);
  f.scene.unfold(true); f.scene.unfold(false);
  assert.equal(f.scene.spread, 1);
  assert.deepEqual(f.secrets, [true]);
  f.advance(1900);
  assert.equal(f.scene.spread, 0);
  assert.equal(f.scene.playing, false);
  assert.equal(f.pending.size, 0);
  f.scene.unfold(false); assert.equal(f.scene.spread, 0.45);
  f.advance(1000);
  assert.equal(f.pending.size, 0);
});
