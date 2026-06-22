import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { makeEmptyDeepReadOutput, DeepReadOutput } from '../main/ets/domain/models.ts';

const __dirname_here = dirname(fileURLToPath(import.meta.url));

test('empty DeepReadOutput has correct defaults', () => {
  const o = makeEmptyDeepReadOutput();
  assert.equal(o.topicType, 'event');
  assert.equal(o.generationComplete, false);
  assert.equal(o.generationPhase, 'IDLE');
  assert.equal(o.summary, '');
  assert.deepEqual(o.keyEntities, []);
  assert.equal(o.timeline, null);
  assert.equal(o.corePoints, null);
  assert.deepEqual(o.extendedReading, []);
  assert.equal(o.heroImageUrl, null);
  assert.deepEqual(o.imageAssets, []);
  assert.equal(o.diagram, null);
  assert.deepEqual(o.references, []);
  assert.deepEqual(o.sectionStates, {});
  assert.deepEqual(o.sectionQualities, {});
});

test('DeepReadOutput survives JSON round-trip', () => {
  const fixturePath = join(__dirname_here, 'fixtures', 'deepread_output_complete.json');
  const json = readFileSync(fixturePath, 'utf-8');
  const parsed = JSON.parse(json) as DeepReadOutput;
  const roundTripped = JSON.parse(JSON.stringify(parsed)) as DeepReadOutput;
  assert.deepEqual(roundTripped, parsed);
  assert.equal(parsed.generationComplete, true);
  assert.equal(parsed.sectionStates['OVERVIEW']!.status, 'READY');
  assert.equal(parsed.heroImageConfidence, 'hero');
});

test('DeepReadOutput with empty sectionStates survives round-trip', () => {
  const minimal = { ...makeEmptyDeepReadOutput() };
  const roundTripped = JSON.parse(JSON.stringify(minimal)) as DeepReadOutput;
  assert.deepEqual(roundTripped.sectionStates, {});
});

test('DeepReadOutput with partial sectionStates (one stage) survives round-trip', () => {
  const o = makeEmptyDeepReadOutput();
  o.sectionStates = { OVERVIEW: { status: 'READY', errorMessage: null } };
  const roundTripped = JSON.parse(JSON.stringify(o)) as DeepReadOutput;
  assert.deepEqual(roundTripped.sectionStates, { OVERVIEW: { status: 'READY', errorMessage: null } });
  assert.equal(roundTripped.sectionStates['NARRATIVE'], undefined);
});

test('corePoints null vs absent both parse to null/undefined consistently', () => {
  const withNull = JSON.parse('{"corePoints": null}') as Partial<DeepReadOutput>;
  const absent = JSON.parse('{}') as Partial<DeepReadOutput>;
  assert.equal(withNull.corePoints, null);
  assert.equal(absent.corePoints, undefined);
  // 两种"无 corePoints"情况,helper 应通过可选链统一处理
});
