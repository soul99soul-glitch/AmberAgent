import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  STAGE_ORDER, STAGE_LABELS, IMAGE_CONFIDENCE,
  WRITER_TOOL_NAMES, STAGE_TIMEOUT_MS, STAGE_SOURCE_LIMIT, STAGE_EXCERPT_LIMIT,
} from '../main/ets/domain/enums.ts';

test('STAGE_ORDER is in declaration order', () => {
  assert.deepEqual(STAGE_ORDER, ['OVERVIEW', 'NARRATIVE', 'ANALYSIS', 'EXTENDED_READING']);
});

test('STAGE_LABELS covers all stages', () => {
  for (const s of STAGE_ORDER) {
    assert.ok(s in STAGE_LABELS, `stage ${s} missing label`);
    assert.ok(STAGE_LABELS[s].length > 0);
  }
});

test('IMAGE_CONFIDENCE constants', () => {
  assert.equal(IMAGE_CONFIDENCE.HERO, 'hero');
  assert.equal(IMAGE_CONFIDENCE.INLINE, 'inline');
  assert.equal(IMAGE_CONFIDENCE.REJECT, 'reject');
});

test('WRITER_TOOL_NAMES maps each stage to its tool', () => {
  assert.equal(WRITER_TOOL_NAMES.OVERVIEW, 'deep_read_write_overview');
  assert.equal(WRITER_TOOL_NAMES.NARRATIVE, 'deep_read_write_narrative');
  assert.equal(WRITER_TOOL_NAMES.ANALYSIS, 'deep_read_write_analysis');
  assert.equal(WRITER_TOOL_NAMES.EXTENDED_READING, 'deep_read_write_extended_reading');
});

test('STAGE_TIMEOUT_MS per-stage values match Android', () => {
  assert.equal(STAGE_TIMEOUT_MS.OVERVIEW, 90_000);
  assert.equal(STAGE_TIMEOUT_MS.NARRATIVE, 110_000);
  assert.equal(STAGE_TIMEOUT_MS.ANALYSIS, 150_000);
  assert.equal(STAGE_TIMEOUT_MS.EXTENDED_READING, 90_000);
});

test('STAGE_SOURCE_LIMIT and STAGE_EXCERPT_LIMIT per-stage values', () => {
  assert.equal(STAGE_SOURCE_LIMIT.OVERVIEW, 6);
  assert.equal(STAGE_SOURCE_LIMIT.NARRATIVE, 9);
  assert.equal(STAGE_SOURCE_LIMIT.ANALYSIS, 8);
  assert.equal(STAGE_SOURCE_LIMIT.EXTENDED_READING, 12);
  assert.equal(STAGE_EXCERPT_LIMIT.OVERVIEW, 1000);
  assert.equal(STAGE_EXCERPT_LIMIT.EXTENDED_READING, 700);
});
