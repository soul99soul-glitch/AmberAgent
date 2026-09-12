import { test } from 'node:test';
import assert from 'node:assert/strict';

import { buildHealthSummary, type HealthMetricRecord } from '../main/ets/chat/health_summary.ts';

const D = (y: number, m: number, d: number): number => new Date(y, m - 1, d, 12, 0, 0).getTime();

test('buildHealthSummary aggregates steps/hr/sleep/weight by day', () => {
  const now = D(2026, 9, 11);
  const records: HealthMetricRecord[] = [
    { type: 'steps', startEpochMillis: D(2026, 9, 11), endEpochMillis: D(2026, 9, 11), value: 4000 },
    { type: 'steps', startEpochMillis: D(2026, 9, 11), endEpochMillis: D(2026, 9, 11), value: 2000 },
    { type: 'steps', startEpochMillis: D(2026, 9, 10), endEpochMillis: D(2026, 9, 10), value: 8000 },
    { type: 'heart_rate', startEpochMillis: D(2026, 9, 11), endEpochMillis: D(2026, 9, 11), value: 60 },
    { type: 'heart_rate', startEpochMillis: D(2026, 9, 11), endEpochMillis: D(2026, 9, 11), value: 70 },
    { type: 'sleep', startEpochMillis: D(2026, 9, 10), endEpochMillis: D(2026, 9, 10) + 7.5 * 3600000, value: 0 },
    { type: 'weight', startEpochMillis: D(2026, 9, 11), endEpochMillis: D(2026, 9, 11), value: 70.5 },
  ];
  const s = buildHealthSummary(records, now);
  assert.equal(s.days.length, 7);
  const today = s.days[s.days.length - 1];
  assert.equal(today.steps, 6000);
  assert.equal(today.heartRateBpm, 65);
  assert.equal(today.weightKg, 70.5);
  const yesterday = s.days[s.days.length - 2];
  assert.equal(yesterday.steps, 8000);
  assert.equal(yesterday.sleepHours, 7.5);
  assert.equal(s.weekly.steps, 14000);
});

test('empty records → zeros', () => {
  const s = buildHealthSummary([], Date.now());
  assert.equal(s.weekly.steps, 0);
  assert.equal(s.weekly.averageHeartRateBpm, null);
  assert.equal(s.days.length, 7);
});
