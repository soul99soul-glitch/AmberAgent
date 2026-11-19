import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { makeMemoryRecord } from '../main/ets/chat/memory_models.ts';
import { buildMemoryPortrait } from '../main/ets/chat/memory_portrait.ts';

describe('memory_portrait', () => {
  it('groups active records into 3 dimensions, excludes inactive records, and dedups near duplicates', () => {
    const now = 1_760_000_000_000;
    const records = [
      makeMemoryRecord({
        id: 1, content: '用户正在推进 Harmony Muse Redesign 项目', scope: 'core', kind: 'project',
        pinned: true, confidence: 0.95, updatedAt: now - 5_000,
      }),
      makeMemoryRecord({
        id: 2, content: '用户正在推进 Harmony Muse Redesign 项目！', scope: 'long_term', kind: 'project',
        pinned: false, confidence: 0.7, updatedAt: now - 10_000,
      }),
      makeMemoryRecord({
        id: 3, content: '回复请尽量简洁，不要套固定模板', scope: 'core', kind: 'feedback',
        confidence: 0.9, updatedAt: now - 2_000,
      }),
      makeMemoryRecord({
        id: 4, content: '单流分段与 Episodic Recall 收口', scope: 'short_term', kind: 'topic',
        topicTitle: 'Episodic Context', updatedAt: now - 1_000,
      }),
      makeMemoryRecord({
        id: 5, content: '已归档历史记忆', scope: 'long_term', kind: 'note',
        archived: true, updatedAt: now - 1_000,
      }),
      makeMemoryRecord({
        id: 6, content: '已过期短期记忆', scope: 'short_term', kind: 'note',
        expiresAt: now - 100, updatedAt: now - 200,
      }),
      makeMemoryRecord({
        id: 7, content: '已失效记录', scope: 'core', kind: 'user',
        invalidatedAt: now - 50, updatedAt: now - 100,
      }),
    ];

    const portrait = buildMemoryPortrait(records, { nowMs: now });
    assert.equal(portrait.totalActiveCount, 4);
    assert.equal(portrait.filteredCount, 3);
    assert.equal(portrait.dedupedCount, 1);
    assert.equal(portrait.dimensions.length, 3);
    assert.equal(portrait.dimensions[0].items.length, 1);
    assert.equal(portrait.dimensions[0].items[0].id, 1);
    assert.equal(portrait.dimensions[1].items.length, 1);
    assert.equal(portrait.dimensions[1].items[0].id, 3);
    assert.equal(portrait.dimensions[2].items.length, 1);
    assert.equal(portrait.dimensions[2].items[0].id, 4);
    assert.equal(portrait.starterPrompts.length, 4);
    assert.ok(portrait.starterPrompts[0].includes('Episodic Context'));
  });

  it('filters by bucket scope deterministically', () => {
    const now = 1_760_000_000_000;
    const records = [
      makeMemoryRecord({ id: 1, content: '长期项目背景', scope: 'core', kind: 'user', updatedAt: now }),
      makeMemoryRecord({ id: 2, content: '本周临时调查项', scope: 'short_term', kind: 'note', updatedAt: now }),
      makeMemoryRecord({ id: 3, content: '技术参考资料', scope: 'long_term', kind: 'reference', updatedAt: now }),
    ];

    const shortOnly = buildMemoryPortrait(records, { bucket: 'short_term', nowMs: now });
    assert.equal(shortOnly.totalActiveCount, 3);
    assert.equal(shortOnly.filteredCount, 1);
    assert.equal(shortOnly.dimensions[0].items.length, 0);
    assert.equal(shortOnly.dimensions[2].items[0].id, 2);
  });
});
