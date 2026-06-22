import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { validateJsonSchema } from '../main/ets/platform/json_schema_validator.ts';

test('validates correct string type', () => {
  assert.equal(validateJsonSchema('hello', { type: 'string' }).valid, true);
});

test('rejects wrong type', () => {
  const r = validateJsonSchema(42, { type: 'string' });
  assert.equal(r.valid, false);
  assert.match(r.errors[0], /expected string/);
});

test('validates object with required fields', () => {
  const schema = {
    type: 'object' as const,
    required: ['summary', 'topic_type'],
    properties: {
      summary: { type: 'string' as const },
      topic_type: { type: 'string' as const },
    },
  };
  assert.equal(validateJsonSchema({ summary: 'x', topic_type: 'event' }, schema).valid, true);
  const r = validateJsonSchema({ topic_type: 'event' }, schema);
  assert.equal(r.valid, false);
  assert.ok(r.errors.some(e => e.includes("'summary'")));
});

test('validates array items', () => {
  const schema = { type: 'array' as const, items: { type: 'string' as const } };
  assert.equal(validateJsonSchema(['a', 'b'], schema).valid, true);
  assert.equal(validateJsonSchema(['a', 1], schema).valid, false);
});

test('validates minLength / maxLength', () => {
  const schema = { type: 'string' as const, minLength: 24, maxLength: 1200 };
  assert.equal(validateJsonSchema('x'.repeat(24), schema).valid, true);
  assert.equal(validateJsonSchema('short', schema).valid, false);
  assert.equal(validateJsonSchema('x'.repeat(1201), schema).valid, false);
});

test('validates enum', () => {
  const schema = { enum: ['hero', 'inline', 'reject'] };
  assert.equal(validateJsonSchema('hero', schema).valid, true);
  assert.equal(validateJsonSchema('logo', schema).valid, false);
});

test('validates integer vs number', () => {
  assert.equal(validateJsonSchema(42, { type: 'integer' }).valid, true);
  assert.equal(validateJsonSchema(42.5, { type: 'integer' }).valid, false);
  assert.equal(validateJsonSchema(42.5, { type: 'number' }).valid, true);
});

test('validates nested object', () => {
  const schema = {
    type: 'object' as const,
    required: ['analysis'],
    properties: {
      analysis: {
        type: 'object' as const,
        required: ['coreDispute'],
        properties: { coreDispute: { type: 'string' as const } },
      },
    },
  };
  assert.equal(validateJsonSchema({ analysis: { coreDispute: 'x' } }, schema).valid, true);
  assert.equal(validateJsonSchema({ analysis: {} }, schema).valid, false);
});

test('minimum / maximum for numbers', () => {
  assert.equal(validateJsonSchema(5, { type: 'number', minimum: 1 }).valid, true);
  assert.equal(validateJsonSchema(0, { type: 'number', minimum: 1 }).valid, false);
  assert.equal(validateJsonSchema(100, { type: 'number', maximum: 50 }).valid, false);
});

test('null type', () => {
  assert.equal(validateJsonSchema(null, { type: 'null' }).valid, true);
  assert.equal(validateJsonSchema('x', { type: 'null' }).valid, false);
});

test('object type rejects null and array', () => {
  assert.equal(validateJsonSchema(null, { type: 'object' }).valid, false);
  assert.equal(validateJsonSchema([], { type: 'object' }).valid, false);
  assert.equal(validateJsonSchema({}, { type: 'object' }).valid, true);
});

test('boolean type', () => {
  assert.equal(validateJsonSchema(true, { type: 'boolean' }).valid, true);
  assert.equal(validateJsonSchema(1, { type: 'boolean' }).valid, false);
});

test('deep_read_write_overview schema accepts valid input', () => {
  const overviewSchema = {
    type: 'object' as const,
    required: ['summary'],
    properties: {
      topic_type: { type: 'string' as const },
      summary: { type: 'string' as const, minLength: 24, maxLength: 1200 },
      key_entities: { type: 'array' as const, items: { type: 'string' as const } },
    },
  };
  const valid = {
    topic_type: 'event',
    summary: '这是一段足够长的概览摘要满足最小长度要求条件再加几个字',
    key_entities: ['实体A', '实体B'],
  };
  assert.equal(validateJsonSchema(valid, overviewSchema).valid, true);
});

test('deep_read_write_overview rejects too-short summary', () => {
  const overviewSchema = {
    type: 'object' as const,
    required: ['summary'],
    properties: {
      summary: { type: 'string' as const, minLength: 24 },
    },
  };
  assert.equal(validateJsonSchema({ summary: '短' }, overviewSchema).valid, false);
});

test('deep_read_write_diagram requires type/title/nodes', () => {
  const diagramSchema = {
    type: 'object' as const,
    required: ['type', 'title', 'nodes'],
    properties: {
      type: { type: 'string' as const },
      title: { type: 'string' as const },
      nodes: { type: 'array' as const, items: { type: 'object' as const } },
    },
  };
  assert.equal(validateJsonSchema({ type: 'causal_chain', title: '因果链', nodes: [] }, diagramSchema).valid, true);
  assert.equal(validateJsonSchema({ type: 'causal_chain', nodes: [] }, diagramSchema).valid, false);
});

test('multiple errors are all reported', () => {
  const schema = {
    type: 'object' as const,
    required: ['a', 'b'],
    properties: {
      a: { type: 'string' as const },
      b: { type: 'number' as const },
    },
  };
  const r = validateJsonSchema({ a: 123 }, schema);
  assert.equal(r.valid, false);
  assert.ok(r.errors.length >= 2);
  assert.ok(r.errors.some(e => e.includes("'b'")));
  assert.ok(r.errors.some(e => e.includes('expected string')));
});
