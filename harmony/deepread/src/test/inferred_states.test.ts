import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { withInferredSectionStates } from '../main/ets/domain/helpers.ts';
import { makeEmptyDeepReadOutput } from '../main/ets/domain/models.ts';

test('legacyComplete forces all stages READY', () => {
  const o = makeEmptyDeepReadOutput();
  o.generationComplete = true;
  o.summary = '';
  o.sectionStates = {};
  const result = withInferredSectionStates(o);
  assert.equal(result.sectionStates['OVERVIEW']?.status, 'READY');
  assert.equal(result.sectionStates['EXTENDED_READING']?.status, 'READY');
});
