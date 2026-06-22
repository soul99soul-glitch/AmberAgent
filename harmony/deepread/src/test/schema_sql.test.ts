import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { DEEP_READ_SCHEMA_SQL } from '../main/ets/platform/database.ts';

test('DEEP_READ_SCHEMA_SQL contains deep_read_cache table', () => {
  assert.ok(DEEP_READ_SCHEMA_SQL.includes('CREATE TABLE IF NOT EXISTS deep_read_cache'));
});

test('DEEP_READ_SCHEMA_SQL has all required columns', () => {
  const requiredCols = [
    'topic_id', 'title', 'source_url', 'output_json', 'phase',
    'attempt_count', 'last_error', 'created_at', 'updated_at', 'expires_at',
  ];
  for (const col of requiredCols) {
    assert.ok(DEEP_READ_SCHEMA_SQL.includes(col), `missing column: ${col}`);
  }
});

test('DEEP_READ_SCHEMA_SQL has 3 indexes', () => {
  const indexCount = (DEEP_READ_SCHEMA_SQL.match(/CREATE INDEX/g) ?? []).length;
  assert.equal(indexCount, 3);
});

test('DEEP_READ_SCHEMA_SQL marks topic_id as PRIMARY KEY', () => {
  assert.ok(DEEP_READ_SCHEMA_SQL.includes('topic_id') && DEEP_READ_SCHEMA_SQL.includes('PRIMARY KEY'));
});

test('attempt_count has default 0', () => {
  assert.ok(/attempt_count\s+INTEGER\s+NOT\s+NULL\s+DEFAULT\s+0/i.test(DEEP_READ_SCHEMA_SQL));
});
