import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { withInferredSectionStates } from '../main/ets/domain/helpers.ts';
import { makeEmptyDeepReadOutput } from '../main/ets/domain/models.ts';

test('withInferredSectionStates is no-op when sectionStates populated', () => {
  const o = makeEmptyDeepReadOutput();
  o.sectionStates = { OVERVIEW: { status: 'READY', errorMessage: null } };
  const result = withInferredSectionStates(o);
  assert.equal(result, o);  // 同一引用(早返回)
});

test('infers READY from summary for OVERVIEW', () => {
  const o = makeEmptyDeepReadOutput();
  o.summary = '这是一段足够长的摘要用于推断 overview ready';
  o.sectionStates = {};
  const result = withInferredSectionStates(o);
  assert.equal(result.sectionStates['OVERVIEW']?.status, 'READY');
});

test('infers READY from timeline for NARRATIVE', () => {
  const o = makeEmptyDeepReadOutput();
  o.timeline = [{ date: '2026-06-01', event: '这是一段足够长的事件描述', isHighlight: false, imageUrl: null, imageCaption: null }];
  o.sectionStates = {};
  const result = withInferredSectionStates(o);
  assert.equal(result.sectionStates['NARRATIVE']?.status, 'READY');
});

test('infers READY from analysis perspectives for ANALYSIS', () => {
  const o = makeEmptyDeepReadOutput();
  o.analysis = {
    coreDispute: null, implications: null,
    perspectives: [{ holder: null, viewpoint: '这是一段足够长的观点描述用于分析' }],
    quotes: [],
  };
  o.sectionStates = {};
  const result = withInferredSectionStates(o);
  assert.equal(result.sectionStates['ANALYSIS']?.status, 'READY');
});

test('infers READY from extendedReading for EXTENDED_READING', () => {
  const o = makeEmptyDeepReadOutput();
  o.extendedReading = [{ title: 'x', url: 'https://example.com', source: null, publishedAt: null }];
  o.sectionStates = {};
  const result = withInferredSectionStates(o);
  assert.equal(result.sectionStates['EXTENDED_READING']?.status, 'READY');
});

test('legacyComplete forces all stages READY', () => {
  const o = makeEmptyDeepReadOutput();
  o.generationComplete = true;
  o.summary = '';
  o.sectionStates = {};
  const result = withInferredSectionStates(o);
  assert.equal(result.sectionStates['OVERVIEW']?.status, 'READY');
  assert.equal(result.sectionStates['EXTENDED_READING']?.status, 'READY');
});
