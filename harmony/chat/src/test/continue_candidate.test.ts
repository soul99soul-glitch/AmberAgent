import { test } from 'node:test';
import assert from 'node:assert/strict';

import { mergeContinueCandidates, STATUS_RANK, type ContinueCandidate } from '../main/ets/chat/continue_candidate.ts';

const cand = (over: Partial<ContinueCandidate>): ContinueCandidate => ({
  sourceKind: 'chat',
  sourceId: 'id',
  route: { kind: 'chat', conversationId: 'c' },
  title: 't',
  summary: 's',
  lastUpdatedAt: 0,
  status: 'draft',
  priority: 0,
  isRunning: false,
  ...over,
});

test('merge sorts by priority, status rank, time', () => {
  const merged = mergeContinueCandidates([
    [cand({ sourceId: 'a', status: 'draft', lastUpdatedAt: 10 })],
    [cand({ sourceId: 'b', status: 'running', lastUpdatedAt: 1 })],
    [cand({ sourceId: 'c', priority: 5, status: 'draft', lastUpdatedAt: 1 })],
  ]);
  assert.equal(merged[0].sourceId, 'c');
  assert.equal(merged[1].sourceId, 'b');
  assert.equal(merged[2].sourceId, 'a');
  assert.equal(STATUS_RANK.running < STATUS_RANK.draft, true);
});

test('merge respects limit', () => {
  const many = Array.from({ length: 20 }, (_, i) => cand({ sourceId: `${i}` }));
  assert.equal(mergeContinueCandidates([many], 5).length, 5);
});
