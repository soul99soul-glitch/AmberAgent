import test from 'node:test';
import assert from 'node:assert/strict';
import {
  PERSONA_SETTLE_DEBOUNCE_MS,
  PERSONA_BLOCKED_DWELL_MS,
  resolveRawPersonaMood,
  shouldFreezeMedallionAnimation,
  computeMedallionFrameIndex,
  PersonaMoodStabilizer,
  detectPersonaSpriteSheetLayout,
  spriteSheetRegionForMoodFrame,
} from '../main/ets/chat/persona_medallion.ts';

test('P1-4: resolveRawPersonaMood prioritizes blocked > needs_input > running > idle', () => {
  assert.equal(resolveRawPersonaMood({ running: true, awaitingApproval: true, hasBlockedError: true }), 'blocked');
  assert.equal(resolveRawPersonaMood({ running: true, awaitingApproval: true, hasBlockedError: false }), 'needs_input');
  assert.equal(resolveRawPersonaMood({ running: true, awaitingApproval: false, hasBlockedError: false }), 'running');
  assert.equal(resolveRawPersonaMood({ running: false, awaitingApproval: false, hasBlockedError: false }), 'idle');
});

test('P1-4: computeMedallionFrameIndex freezes to frame 0 under reduceMotion/background/sheetOpen', () => {
  assert.equal(shouldFreezeMedallionAnimation(false, false, false), false);
  assert.equal(shouldFreezeMedallionAnimation(true, false, false), true);
  assert.equal(shouldFreezeMedallionAnimation(false, true, false), true);
  assert.equal(shouldFreezeMedallionAnimation(false, false, true), true);

  assert.equal(computeMedallionFrameIndex(2, 4, true), 0);
  const cycle = [0, 1, 2, 3, 4, 5].map((step) => computeMedallionFrameIndex(step, 4, false));
  assert.deepEqual(cycle, [0, 1, 2, 3, 2, 1]);
});

test('P1-4: PersonaMoodStabilizer enforces 0.4s settle debounce and 2.0s blocked dwell', () => {
  const stabilizer = new PersonaMoodStabilizer();
  assert.equal(stabilizer.getCurrentMood(), 'idle');

  // Sub-400ms running spike stays idle
  const u1 = stabilizer.update({ running: true, awaitingApproval: false, hasBlockedError: false }, 1000);
  assert.equal(u1.mood, 'idle');
  assert.equal(u1.recheckAfterMs, PERSONA_SETTLE_DEBOUNCE_MS);

  const u2 = stabilizer.update({ running: true, awaitingApproval: false, hasBlockedError: false }, 1250);
  assert.equal(u2.mood, 'idle');

  // At 400ms settled -> running
  const u3 = stabilizer.update({ running: true, awaitingApproval: false, hasBlockedError: false }, 1400);
  assert.equal(u3.mood, 'running');

  // Transient flicker back to idle resets on return to running
  stabilizer.update({ running: false, awaitingApproval: false, hasBlockedError: false }, 1600);
  assert.equal(stabilizer.getCurrentMood(), 'running');
  const u4 = stabilizer.update({ running: true, awaitingApproval: false, hasBlockedError: false }, 1750);
  assert.equal(u4.mood, 'running');

  // Entering blocked requires 400ms settle
  stabilizer.update({ running: false, awaitingApproval: false, hasBlockedError: true }, 2000);
  assert.equal(stabilizer.getCurrentMood(), 'running');
  stabilizer.update({ running: false, awaitingApproval: false, hasBlockedError: true }, 2400);
  assert.equal(stabilizer.getCurrentMood(), 'blocked');

  // Blocked dwells at least 2000ms before clearing (even after 400ms idle)
  const u5 = stabilizer.update({ running: false, awaitingApproval: false, hasBlockedError: false }, 4000);
  assert.equal(u5.mood, 'blocked');
  assert.equal(u5.recheckAfterMs, 400);

  // Once 2000ms elapsed, starts 400ms settle to idle then transitions
  const u6 = stabilizer.update({ running: false, awaitingApproval: false, hasBlockedError: false }, 2400 + PERSONA_BLOCKED_DWELL_MS);
  assert.equal(u6.mood, 'blocked');
  const u7 = stabilizer.update({ running: false, awaitingApproval: false, hasBlockedError: false }, 2400 + PERSONA_BLOCKED_DWELL_MS + PERSONA_SETTLE_DEBOUNCE_MS);
  assert.equal(u7.mood, 'idle');
});

test('P1-4: detects 4x4 sprite sheet layout and extracts mood row/frame crops', () => {
  const sheet = detectPersonaSpriteSheetLayout(1024, 1024);
  assert.equal(sheet.kind, 'quad4x4');
  assert.equal(sheet.cellWidth, 256);
  assert.equal(sheet.cellHeight, 256);

  assert.deepEqual(spriteSheetRegionForMoodFrame(sheet, 'idle', 0), { x: 0, y: 0, width: 256, height: 256 });
  assert.deepEqual(spriteSheetRegionForMoodFrame(sheet, 'running', 2), { x: 512, y: 256, width: 256, height: 256 });
  assert.deepEqual(spriteSheetRegionForMoodFrame(sheet, 'needs_input', 1), { x: 256, y: 512, width: 256, height: 256 });
  assert.deepEqual(spriteSheetRegionForMoodFrame(sheet, 'blocked', 3), { x: 768, y: 768, width: 256, height: 256 });

  const portrait = detectPersonaSpriteSheetLayout(600, 1200);
  assert.equal(portrait.kind, 'static');
});
