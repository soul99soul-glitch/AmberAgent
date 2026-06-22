import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { makeEmptyDeepReadOutput } from '../main/ets/domain/models.ts';
import {
  isComplete, sectionsReady, hasAnyReadySection, statusOf,
} from '../main/ets/domain/helpers.ts';
import { STAGE_ORDER } from '../main/ets/domain/enums.ts';

const readyState = { status: 'READY' as const, errorMessage: null };
const pendingState = { status: 'PENDING' as const, errorMessage: null };

test('sectionsReady false on empty sectionStates', () => {
  const o = makeEmptyDeepReadOutput();
  assert.equal(sectionsReady(o), false);
});

test('sectionsReady true when all 4 stages READY', () => {
  const o = makeEmptyDeepReadOutput();
  for (const s of STAGE_ORDER) o.sectionStates[s] = readyState;
  assert.equal(sectionsReady(o), true);
});

test('sectionsReady false when any stage not READY', () => {
  const o = makeEmptyDeepReadOutput();
  for (const s of STAGE_ORDER) o.sectionStates[s] = readyState;
  o.sectionStates['ANALYSIS'] = pendingState;
  assert.equal(sectionsReady(o), false);
});

test('sectionsReady false when a required stage missing', () => {
  const o = makeEmptyDeepReadOutput();
  o.sectionStates['OVERVIEW'] = readyState;
  o.sectionStates['NARRATIVE'] = readyState;
  o.sectionStates['ANALYSIS'] = readyState;
  // EXTENDED_READING 缺失
  assert.equal(sectionsReady(o), false);
});

test('hasAnyReadySection false when none ready', () => {
  const o = makeEmptyDeepReadOutput();
  assert.equal(hasAnyReadySection(o), false);
});

test('hasAnyReadySection true when at least one READY', () => {
  const o = makeEmptyDeepReadOutput();
  o.sectionStates['OVERVIEW'] = readyState;
  assert.equal(hasAnyReadySection(o), true);
});

test('isComplete requires generationComplete AND sectionsReady', () => {
  const o = makeEmptyDeepReadOutput();
  assert.equal(isComplete(o), false);
  o.generationComplete = true;
  assert.equal(isComplete(o), false);
  for (const s of STAGE_ORDER) o.sectionStates[s] = readyState;
  assert.equal(isComplete(o), true);
});

test('statusOf returns PENDING for missing stage', () => {
  const o = makeEmptyDeepReadOutput();
  assert.equal(statusOf(o, 'OVERVIEW'), 'PENDING');
});

test('statusOf returns actual status', () => {
  const o = makeEmptyDeepReadOutput();
  o.sectionStates['OVERVIEW'] = { status: 'FAILED', errorMessage: 'timeout' };
  assert.equal(statusOf(o, 'OVERVIEW'), 'FAILED');
});
