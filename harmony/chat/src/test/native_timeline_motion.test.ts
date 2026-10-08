import { test } from 'node:test';
import assert from 'node:assert/strict';
import { nativeTimelineSpringStep } from '../main/ets/chat/native_timeline_motion.ts';

test('native follow converges monotonically at 60/90/120Hz without overshoot', () => {
  for (const hz of [60, 90, 120]) {
    let position = 0, velocity = 0;
    for (let frame = 0; frame < hz; frame++) {
      const next = nativeTimelineSpringStep(position, velocity, 80, 1 / hz);
      assert.ok(next.position >= position && next.position <= 80);
      position = next.position; velocity = next.velocity;
    }
    assert.ok(80 - position < 0.001);
  }
});

test('a growing target preserves momentum and converges independently of frame rate', () => {
  const at = (hz: number) => {
    let position = 0, velocity = 0;
    for (let frame = 0; frame < hz / 2; frame++) {
      const target = frame < hz / 10 ? 24 : 48;
      const next = nativeTimelineSpringStep(position, velocity, target, 1 / hz);
      assert.ok(next.position >= position && next.position <= target);
      position = next.position; velocity = next.velocity;
    }
    return position;
  };
  assert.ok(Math.abs(at(60) - at(90)) < 0.01);
  assert.ok(Math.abs(at(90) - at(120)) < 0.01);
});

test('zero delta is inert; a shrinking layout does not retain upward overshoot', () => {
  assert.deepEqual(nativeTimelineSpringStep(10, 20, 40, 0), { position: 10, velocity: 20 });
  assert.deepEqual(nativeTimelineSpringStep(60, 20, 40, 1 / 60), { position: 40, velocity: 0 });
});

test('a multi-paragraph layout burst cannot fast-forward several text lines in one frame', () => {
  // Device capture: several newly laid-out blocks added ~160vp together. The
  // previous spring produced a visible multi-line catch-up on ordinary 60Hz ticks.
  for (const hz of [60, 90, 120]) {
    let position = 0, velocity = 0;
    for (let frame = 0; frame < hz; frame++) {
      const target = frame < hz / 5 ? 24 : 184;
      const next = nativeTimelineSpringStep(position, velocity, target, 1 / hz);
      assert.ok(next.position - position <= 720 / hz + 1e-6,
        `${hz}Hz frame ${frame}: ${next.position - position}vp jumped in one frame`);
      assert.ok(next.velocity <= 720, 'a limited frame must not retain hidden catch-up momentum');
      assert.ok(next.position >= position && next.position <= target);
      position = next.position; velocity = next.velocity;
    }
    assert.ok(184 - position < 0.01, 'the burst still settles in less than a second');
  }
});
