import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { makeEmptyDeepReadOutput } from '../main/ets/domain/models.ts';

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
