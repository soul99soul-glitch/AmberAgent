const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');

const source = fs.readFileSync(path.join(__dirname, '../main/ets/components/NativeThreeCover.ets'), 'utf8');
const component = source.slice(source.indexOf('@Component'), source.indexOf('  build(): void'))
  .replace('@Component', '').replace('export struct', 'class')
  .replace(/@(?:Prop|State)\b/g, '').replace(/@Watch\('[^']+'\)/g, '');
const code = ts.transpileModule(component + '}\nreturn new NativeThreeCover();', {
  compilerOptions: { target: ts.ScriptTarget.ES2022 },
}).outputText;
const tick = async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); };
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { resolve, promise }; };

function fixture() {
  const sources = [], covers = [];
  const cover = new Function('image', 'util', 'renderThreeSceneCover', code)({
    createImageSource(buffer) {
      const source = {
        bytes: typeof buffer === 'string' ? null : new Uint8Array(buffer), path: typeof buffer === 'string' ? buffer : null,
        released: false, requests: [],
        getImageInfo: async () => ({ size: { width: 1600, height: 2400 } }),
        createPixelMap(options) {
          const pending = deferred(); source.requests.push({ options, pending }); return pending.promise;
        },
        release: async () => { source.released = true; },
      };
      sources.push(source); return source;
    },
  }, {
    TextEncoder: { create: () => ({ encodeInto: text => new TextEncoder().encode(text) }) },
    Base64Helper: class { decodeSync(text) { return new Uint8Array(Buffer.from(text, 'base64')); } },
  }, (scene, dark) => {
    covers.push({ scene, dark }); return '<svg width="100%" viewBox="0 0 680 320"></svg>';
  });
  cover.scene = { objects: [{}] };
  cover.aboutToAppear();
  return { cover, sources, covers };
}
function pixelMap() { return { released: false, release: async function () { this.released = true; } }; }

test('native cover decodes only on first visibility and reuses the same completed cover', async () => {
  const f = fixture(); assert.equal(f.sources.length, 0);
  f.cover.visibilityChanged(true); f.cover.visibilityChanged(false); f.cover.visibilityChanged(true);
  assert.equal(f.sources.length, 1);
  assert.match(new TextDecoder().decode(f.sources[0].bytes), /width="680" height="320"/);
  assert.deepEqual(f.sources[0].requests[0].options.desiredSize, { width: 680, height: 320 });
  const map = pixelMap(); f.sources[0].requests[0].pending.resolve(map); await tick();
  assert.equal(f.cover.pixelMap, map); assert.equal(f.sources[0].released, true);
  f.cover.visibilityChanged(false); f.cover.visibilityChanged(true);
  assert.equal(f.sources.length, 1);
  f.cover.aboutToDisappear(); await tick();
  assert.equal(map.released, true); assert.equal(f.cover.pixelMap, undefined);
});

test('changed scene and disappearance reject late results while releasing native resources', async () => {
  const f = fixture(); f.cover.visibilityChanged(true);
  f.cover.scene = { objects: [{ id: 'replacement' }] }; f.cover.invalidateCover();
  const stale = pixelMap(); f.sources[0].requests[0].pending.resolve(stale); await tick();
  assert.equal(stale.released, true); assert.equal(f.sources[0].released, true);
  assert.equal(f.cover.pixelMap, undefined);
  f.cover.aboutToDisappear();
  const leaving = pixelMap(); f.sources[1].requests[0].pending.resolve(leaving); await tick();
  assert.equal(leaving.released, true); assert.equal(f.sources[1].released, true);
  assert.equal(f.cover.pixelMap, undefined);
});

test('PNG snapshots bypass SVG work, decode a proportional thumbnail, and fail without repeated retries', async () => {
  const f = fixture(); f.cover.snapshot = 'data:image/png;base64,AQID';
  f.cover.visibilityChanged(true); await tick();
  assert.equal(f.covers.length, 0);
  assert.deepEqual([...f.sources[0].bytes], [1, 2, 3]);
  assert.deepEqual(f.sources[0].requests[0].options.desiredSize, { width: 373, height: 560 });
  const map = pixelMap(); f.sources[0].requests[0].pending.resolve(map); await tick();
  f.cover.snapshot = 'invalid'; f.cover.invalidateCover(); await tick();
  assert.equal(map.released, false); assert.equal(f.cover.pixelMap, map); assert.equal(f.cover.failed, true);
  f.cover.visibilityChanged(false); f.cover.visibilityChanged(true); await tick();
  assert.equal(f.sources.length, 1);
  f.cover.snapshot = 'file:///cache/preview.png'; f.cover.invalidateCover(); await tick();
  assert.equal(f.sources[1].path, '/cache/preview.png');
  assert.equal(f.cover.pixelMap, map, 'old cover remains until the new file is decoded');
  const cached = pixelMap(); f.sources[1].requests[0].pending.resolve(cached); await tick();
  assert.equal(map.released, true); assert.equal(f.cover.pixelMap, cached);
});
