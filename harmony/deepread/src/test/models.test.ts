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
