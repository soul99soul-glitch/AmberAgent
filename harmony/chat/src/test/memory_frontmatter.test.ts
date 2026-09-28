// memory_frontmatter round-trip 回归:scalar 反转义(encode 转义 \\ 与 \")
import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  encodeMemoryFrontmatter, decodeMemoryFrontmatter,
} from '../main/ets/chat/memory_frontmatter.ts';
import type { MemoryRecord } from '../main/ets/chat/memory_models.ts';

const record: MemoryRecord = {
  id: 7,
  content: 'body',
  scope: 'long_term',
  kind: 'user',
  assistantId: '__global__',
  sourceConversationId: 'conv-"quoted"\\path',
  sourceMessageIds: ['m1'],
  supersedesIds: [],
  expiresAt: null,
  confidence: 0.9,
  pinned: false,
  archived: false,
  createdAt: 1750000000000,
  updatedAt: 1750000000000,
  lastUsedAt: null,
  topicTitle: null,
  memberIds: [],
};

test('round-trip: scalar 含反斜杠/引号时 encode→decode 无失真', () => {
  const decoded: MemoryRecord = decodeMemoryFrontmatter(encodeMemoryFrontmatter(record));
  assert.equal(decoded.sourceConversationId, 'conv-"quoted"\\path');
  assert.equal(decoded.scope, 'long_term');
  assert.equal(decoded.kind, 'user');
  assert.equal(decoded.id, 7);
});
