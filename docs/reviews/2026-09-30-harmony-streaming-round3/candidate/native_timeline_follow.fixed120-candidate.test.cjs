// Execute the real Entry frame driver with a controlled native frame/layout host.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

async function fixture({ sdk = 24, refreshRate = 60, supported = [30, 60, 120] } = {}) {
  const domain = await import('../main/ets/chat/native_timeline_motion.ts');
  const file = path.resolve(__dirname, '../../../entry/src/main/ets/components/NativeTimelineFollow.ets');
  const source = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  const clocks = [], frames = [], moves = [];
  const displaySync = { create: () => {
    const clock = { callback: null, rate: null, running: false,
      setExpectedFrameRateRange: range => { clock.rate = range; },
      on: (_event, callback) => { clock.callback = callback; },
      off: () => { clock.callback = null; },
      start: () => {
        clock.running = true;
        const callback = clock.callback;
        const frame = { onFrame: timestamp => {
          callback({ timestamp });
          if (clock.running && clock.callback === callback) frames.push(frame);
        } };
        frames.push(frame);
      },
      stop: () => { clock.running = false; } };
    clocks.push(clock); return clock;
  } };
  const exports = {};
  vm.runInNewContext(source, { exports, require: name => name === '@kit.ArkUI' ? { FrameCallback: class {}, display: {
      getDefaultDisplaySync: () => ({ refreshRate, get supportedRefreshRates() {
        assert.ok(sdk >= 20, 'API12 devices must not access the API20 property'); return supported;
      } })
    } } : name === '@kit.ArkGraphics2D' ? { displaySync } : name === '@kit.BasicServicesKit'
      ? { deviceInfo: { sdkApiVersion: sdk } } : domain,
    canIUse: () => true, Edge: { Bottom: 'bottom' }, Math });
  let offset = 0, target = 80, allowed = true, time = 0;
  const scroller = { currentOffset: () => ({ yOffset: offset }),
    scrollBy: (_x, y) => { offset += y; moves.push(y); },
    scrollEdge: () => { offset = target ?? 160; moves.push('edge'); } };
  const driver = new exports.NativeTimelineFollow({ postFrameCallback: f => frames.push(f), runScopedTask: f => f() }, scroller,
    () => allowed, () => target, () => {});
  const busyFrame = () => { const f = frames.shift(); assert.ok(f); time += 1000 / 60; f.onFrame(time * 1e6); };
  const frame = () => { const f = frames.shift(); assert.ok(f); time += 1000 / 60;
    f.onFrame(time * 1e6); if (f.onIdle) f.onIdle(1e6); };
  return { driver, clocks, frames, moves, frame, busyFrame, offset: () => offset,
    target: value => { target = value; }, allow: value => { allowed = value; } };
}

test('layout requests coalesce, sample after layout and move in bounded native frames', async () => {
  const f = await fixture();
  f.driver.request(); f.driver.request();
  assert.equal(f.frames.length, 1); assert.equal(f.moves.length, 0);
  f.target(120); f.frame();
  assert.ok(f.offset() > 0 && f.offset() < 120);
  while (f.frames.length) f.frame();
  assert.ok(Math.abs(f.offset() - 120) < 0.01);
  assert.ok(f.moves.length > 3 && f.moves.length < 40);
  assert.equal(f.frames.length, 0, 'arrived driver does not keep requesting idle vsyncs');
});

test('touch/cancel prevents a queued old frame from moving the list', async () => {
  const f = await fixture();
  f.driver.request(); f.allow(false); f.driver.cancel(); f.frame();
  assert.equal(f.moves.length, 0);
  f.allow(true); f.driver.request(); f.frame();
  assert.ok(f.offset() > 0);
});

test('growing geometry retargets the active spring; late terminal layout starts another frame', async () => {
  const f = await fixture();
  f.driver.request(); f.frame(); f.target(160); f.driver.request();
  assert.equal(f.frames.length, 1);
  while (f.frames.length) f.frame();
  assert.equal(f.offset(), 160);
  f.target(180); f.driver.request();
  while (f.frames.length) f.frame();
  assert.equal(f.offset(), 180);
});

test('new structural row outside measured range anchors once without a frame loop', async () => {
  const f = await fixture(); f.target(null); f.driver.request(); f.frame();
  assert.deepEqual(f.moves, ['edge']); assert.equal(f.frames.length, 0);
});

// ArkUI defers onIdle when layout leaves <= 1ms before vsync.
test('busy layout frames advance scrolling without waiting for onIdle', async () => {
  const f = await fixture();
  f.driver.request();
  f.busyFrame();
  assert.ok(f.offset() > 0, 'stream growth must not stall until an idle frame');
  assert.equal(f.frames.length, 1);
  f.busyFrame();
  assert.ok(f.moves.length >= 2);
});


test('active native follow requests display frames above the default 30fps vote and releases them at rest', async () => {
  const f = await fixture();
  f.driver.request();
  assert.equal(f.clocks.length, 1, 'native motion must own a DisplaySync clock');
  const clock = f.clocks[0];
  assert.equal(clock.rate.expected, 120);
  assert.equal(clock.rate.min, 120);
  assert.ok(clock.running);
  for (let i = 1; clock.running && i < 120; i++) clock.callback({ timestamp: i * 1e9 / 120 });
  assert.equal(clock.running, false);
  assert.equal(clock.callback, null);
  assert.equal(f.offset(), 80);
});

test('API12 uses its known current refresh rate and cancellation releases the vote', async () => {
  const f = await fixture({ sdk: 12, refreshRate: 60 });
  f.driver.request();
  assert.equal(f.clocks[0].rate.expected, 60);
  assert.equal(f.clocks[0].rate.max, 60);
  f.driver.cancel();
  assert.equal(f.clocks[0].running, false);
  assert.equal(f.clocks[0].callback, null);
});
