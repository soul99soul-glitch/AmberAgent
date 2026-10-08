import assert from 'node:assert/strict';
import test from 'node:test';
import { makeMemoryRecord } from '../main/ets/chat/memory_models.ts';
import { makeUIMessage } from '../main/ets/chat/message.ts';
import { applyMemoryExtractionActions, collectMemoryExtractionSources, isGroundedMemoryRewrite,
  memoryExpiryOn, normalizeMemoryRelativeDates } from '../main/ets/chat/memory_extraction_actions.ts';
import type { MemoryExtractionAction } from '../main/ets/chat/memory_extraction_actions.ts';
const NOW = new Date(2026, 9, 3, 12).getTime();
const original = () => makeMemoryRecord({ id: 1, content: '用户目前是素食者', scope: 'short_term', kind: 'user', updatedAt: 100 });
const action = (over: Partial<MemoryExtractionAction> = {}): MemoryExtractionAction => ({
  action: 'update', content: '用户已经不再吃素食', evidence: '我已经不再吃素食', sourceMessageId: 'u2',
  sourceConversationId: 'c', scope: 'long_term', kind: 'user', confidence: .95,
  expiresAt: null, targetId: 1, targetUpdatedAt: 100, ...over,
});
test('update preserves old evidence and links archived history; invalidates topic summaries', () => {
  const old = original();
  const topic = makeMemoryRecord({ id: 5, content: '素食主题', scope: 'long_term', kind: 'topic', memberIds: [1,2] });
  const result = applyMemoryExtractionActions([old, topic], [action()], NOW);
  assert.equal(old.archived, false);
  assert.equal(result.updated.length, 1);
  assert.equal(result.records[0].archived, true);
  assert.equal(result.records[0].invalidatedAt, NOW);
  assert.equal(result.records[0].content, old.content);
  assert.equal(result.records[1].archived, true);
  assert.deepEqual(result.updated[0].supersedesIds, [1]);
  assert.equal(result.updated[0].scope, 'short_term');
  assert.equal(result.updated[0].evidence, '我已经不再吃素食');
});
test('stale CAS and expired targets skip locally while valid add still succeeds', () => {
  const result = applyMemoryExtractionActions([original()], [action({ targetUpdatedAt: 99 }),
    action({ action: 'add', content: '用户喜欢阅读科幻小说', targetId: null, targetUpdatedAt: null })], NOW);
  assert.equal(result.updated.length, 0);
  assert.equal(result.added.length, 1);
  const expired = { ...original(), expiresAt: NOW };
  assert.equal(applyMemoryExtractionActions([expired], [action({ action: 'invalidate' })], NOW).changed, false);
});
test('invalidate archives but preserves content, protects pinned/core/topic targets', () => {
  const result = applyMemoryExtractionActions([original()], [action({ action: 'invalidate' })], NOW);
  assert.equal(result.invalidated.length, 1);
  assert.equal(result.records[0].content, original().content);
  for (const protectedRecord of [{ ...original(), pinned: true }, { ...original(), scope: 'core' as const },
    { ...original(), kind: 'topic' as const }]) {
    assert.equal(applyMemoryExtractionActions([protectedRecord], [action({ action: 'invalidate' })], NOW).changed, false);
    assert.equal(applyMemoryExtractionActions([protectedRecord], [action()], NOW).changed, false);
  }
});
test('confirm and duplicate add reinforce once per user source, noop writes nothing', () => {
  const confirm = action({ action: 'confirm', content: original().content });
  const first = applyMemoryExtractionActions([original()], [confirm, confirm], NOW);
  assert.equal(first.confirmed.length, 1);
  assert.equal(first.records[0].reinforcementCount, 1);
  assert.equal(applyMemoryExtractionActions(first.records, [action({ action: 'add', content: original().content })], NOW + 1).changed, false);
  assert.equal(applyMemoryExtractionActions([original()], [action({ action: 'noop' })], NOW).changed, false);
});
test('sensitive content/evidence and expired writes never mutate', () => {
  assert.equal(applyMemoryExtractionActions([], [action({ action: 'add', content: '用户的 password 为 abc123' })], NOW).changed, false);
  assert.equal(applyMemoryExtractionActions([original()], [action({ evidence: '我的银行卡号是 123456' })], NOW).changed, false);
  assert.equal(applyMemoryExtractionActions([original()], [action({ expiresAt: NOW - 1 })], NOW).changed, false);
});
test('user text alone is evidence and only previous assistant text is context', () => {
  const sources = collectMemoryExtractionSources([
    makeUIMessage('assistant', [{ type: 'text', text: '第二个方案使用 SQLite', metadata: null }]),
    makeUIMessage('user', [{ type: 'text', text: '就用第二个方案', metadata: null }], { id: 'u' }),
    makeUIMessage('assistant', [{ type: 'text', text: '未来助手不可作为来源', metadata: null }]),
  ], ['u']);
  assert.equal(sources.length, 1);
  assert.equal(sources[0].evidenceText, '就用第二个方案');
  assert.equal(sources[0].assistantContext, '第二个方案使用 SQLite');
  assert.equal(isGroundedMemoryRewrite('用户选用 SQLite', '就用第二个方案', sources[0].assistantContext), true);
  assert.equal(isGroundedMemoryRewrite('用户选用 PostgreSQL', '就用第二个方案', sources[0].assistantContext), false);
  assert.equal(isGroundedMemoryRewrite('用户选用 SQLite', '好', sources[0].assistantContext), false);
});
test('relative dates use message day and strict expires_on respects local day boundary', () => {
  const source = '2026-10-01T10:00:00';
  assert.equal(normalizeMemoryRelativeDates('明天要去东京', source), '2026-10-02要去东京');
  assert.equal(normalizeMemoryRelativeDates('下周准备出差', source), '2026-10-05所在周准备出差');
  assert.equal(normalizeMemoryRelativeDates('下周二准备出差', source), '2026-10-06准备出差');
  assert.equal(memoryExpiryOn('2026-10-03'), new Date(2026, 9, 4).getTime());
  assert.equal(memoryExpiryOn('2026-02-30'), null);
});
