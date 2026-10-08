// Execute the real Entry frame driver with a controlled native frame/layout host.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

async function fixture({ sdk = 24, refreshRate = 60, supported = [30, 60, 90, 120] } = {}) {
  const domain = await import('../main/ets/chat/native_timeline_motion.ts');
  const file = path.resolve(__dirname, '../../../entry/src/main/ets/components/NativeTimelineFollow.ets');
  const source = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  const clocks = [], frames = [], moves = [], animations = [];
  let nativeMotion = null;
  let advanceNative = () => {};
  const displaySync = { create: () => {
    const clock = { callback: null, rate: null, running: false,
      setExpectedFrameRateRange: range => { clock.rate = range; },
      on: (_event, callback) => { clock.callback = callback; },
      off: () => { clock.callback = null; },
      start: () => {
        clock.running = true;
        const callback = clock.callback;
        const frame = { onFrame: (timestamp, frameNanos = 1e9 / 60) => {
          advanceNative(frameNanos / 1e6);
          callback({ timestamp, targetTimestamp: timestamp + frameNanos });
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
    canIUse: () => true, Edge: { Bottom: 'bottom' }, Curve: { Linear: 'linear' }, Math });
  let offset = 0, target = 80, allowed = true, time = 0;
  advanceNative = intervalMs => {
    if (!nativeMotion) return;
    const step = Math.min(nativeMotion.target - offset, nativeMotion.speed * intervalMs / 1000);
    offset += step;
    if (step) moves.push(step);
    if (offset >= nativeMotion.target) nativeMotion = null;
  };
  const scroller = { currentOffset: () => ({ yOffset: offset }),
    scrollTo: options => {
      animations.push(options);
      if (options.animation === false) { offset = options.yOffset; nativeMotion = null; return; }
      nativeMotion = { target: options.yOffset, speed: (options.yOffset - offset) / options.animation.duration * 1000 };
    },
    scrollBy: (_x, y) => { offset += y; moves.push(y); },
    scrollEdge: () => { offset = target ?? 160; moves.push('edge'); } };
  const driver = new exports.NativeTimelineFollow({ postFrameCallback: f => frames.push(f), runScopedTask: f => f() }, scroller,
    () => allowed, () => target, () => {});
  const busyFrame = () => { const f = frames.shift(); assert.ok(f); time += 1000 / 60; f.onFrame(time * 1e6); };
  const frame = (intervalMs = 1000 / 60, elapsedMs = intervalMs) => { const f = frames.shift(); assert.ok(f); time += elapsedMs;
    f.onFrame(time * 1e6, intervalMs * 1e6); if (f.onIdle) f.onIdle(1e6); };
  return { driver, clocks, frames, moves, animations, frame, busyFrame, offset: () => offset,
    target: value => { target = value; }, allow: value => { allowed = value; } };
}

test('layout requests coalesce, sample after layout and move in bounded native frames', async () => {
  const f = await fixture();
  f.driver.request(); f.driver.request();
  assert.equal(f.frames.length, 1); assert.equal(f.moves.length, 0);
  f.target(120); f.frame(); f.frame();
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
  f.allow(true); f.driver.request(); f.frame(); f.frame();
  assert.ok(f.offset() > 0);
});

test('growing geometry retargets native animation; late terminal layout starts another frame', async () => {
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
  f.busyFrame(); f.busyFrame();
  assert.ok(f.offset() > 0, 'stream growth must not stall until an idle frame');
  assert.equal(f.frames.length, 1);
  f.busyFrame();
  assert.ok(f.moves.length >= 2);
});


test('active native follow prefers120Hz with LTPO headroom and releases its request at rest', async () => {
  const f = await fixture();
  f.driver.request();
  assert.equal(f.clocks.length, 1, 'native motion must own a DisplaySync clock');
  const clock = f.clocks[0];
  assert.equal(clock.rate.expected, 120);
  assert.equal(clock.rate.min, 60);
  assert.equal(clock.rate.max, 120);
  assert.ok(clock.running);
  for (let i = 1; clock.running && i < 120; i++) f.frame(1000 / 120);
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


test('LTPO ceiling uses supported modes without assuming120Hz on lower-refresh screens', async () => {
  for (const [supported, expected] of [[[120, 60, 90, 30], 120], [[30, 60, 90], 90], [[30, 60, 72], 72], [[30, 60, 144], 60]]) {
    const f = await fixture({ supported });
    f.driver.request();
    assert.equal(f.clocks[0].rate.expected, expected);
    assert.equal(f.clocks[0].rate.min, Math.min(60, expected));
    assert.equal(f.clocks[0].rate.max, expected);
    f.driver.cancel();
  }
});

test('API12 can request its already-known120Hz rate without reading newer display properties', async () => {
  const f = await fixture({ sdk: 12, refreshRate: 120 });
  f.driver.request();
  assert.equal(f.clocks[0].rate.expected, 120);
  assert.equal(f.clocks[0].rate.min, 60);
  f.driver.cancel();
});


test('LTPO120→90→60Hz changes retain bounded native motion without restarting the clock', async () => {
  const f = await fixture(); f.target(500); f.driver.request(); f.frame(1000 / 120);
  for (const hz of [120, 120, 90, 90, 60, 60, 120]) {
    const before = f.offset();
    f.frame(1000 / hz);
    const step = f.offset() - before;
    assert.ok(step > 0 && step <= 720 / hz + 1e-6, `${hz}Hz native tick moved ${step}vp`);
    assert.equal(f.clocks.length, 1);
    assert.equal(f.clocks[0].running, true);
  }
  while (f.frames.length) f.frame(1000 / 60);
  assert.equal(f.offset(), 500);
  assert.equal(f.clocks[0].running, false);
});

test('late layout observations do not issue a catch-up scrollBy or restart unchanged native motion', async () => {
  const f = await fixture(); f.target(500); f.driver.request(); f.frame(1000 / 120);
  f.frame(1000 / 120, 50);
  assert.equal(f.animations.length, 1);
  assert.equal(f.animations[0].yOffset, 500);
  f.driver.cancel();
});

test('system animation owns intermediate positions; unchanged geometry does not restart it', async () => {
  const f = await fixture(); f.target(160); f.driver.request(); f.frame();
  assert.equal(f.animations.length, 1, 'a native scrollTo animation must be issued');
  const animation = f.animations[0];
  assert.equal(animation.yOffset, 160);
  assert.equal(animation.animation.curve, 'linear');
  assert.ok(animation.animation.duration >= 160 / 720 * 1000);
  for (let i = 0; i < 8; i++) f.frame(1000 / 120);
  assert.equal(f.animations.length, 1, 'native animation must not restart every vsync');
  assert.equal(f.driver.isRunning(), true, 'long native animations retain programmatic attribution');
  f.driver.cancel();
  assert.equal(f.animations.at(-1).animation, false, 'touch/exit explicitly stops native scrollTo');
  assert.equal(f.driver.isRunning(), false);
});
