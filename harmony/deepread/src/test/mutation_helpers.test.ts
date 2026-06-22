import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { makeEmptyDeepReadOutput, DeepReadOutput, DeepReadImageAsset } from '../main/ets/domain/models.ts';
import {
  withSectionStatus, withSectionQuality, withPhase,
  firstFailureMessage, verifiedImageUrls,
} from '../main/ets/domain/helpers.ts';
import { IMAGE_CONFIDENCE, STAGE_ORDER } from '../main/ets/domain/enums.ts';

test('withSectionStatus returns new reference (immutable)', () => {
  const o = makeEmptyDeepReadOutput();
  const result = withSectionStatus(o, 'OVERVIEW', 'READY');
  assert.notEqual(result, o, 'must return new object');
  assert.notEqual(result.sectionStates, o.sectionStates, 'sectionStates must be new');
});

test('withSectionStatus does not mutate original', () => {
  const o = makeEmptyDeepReadOutput();
  const originalStatesSnapshot = { ...o.sectionStates };
  withSectionStatus(o, 'OVERVIEW', 'READY');
  assert.deepEqual(o.sectionStates, originalStatesSnapshot, 'original unchanged');
});

test('withSectionStatus writes new state', () => {
  const o = makeEmptyDeepReadOutput();
  const result = withSectionStatus(o, 'OVERVIEW', 'READY');
  assert.equal(result.sectionStates['OVERVIEW']?.status, 'READY');
  assert.equal(result.sectionStates['OVERVIEW']?.errorMessage, null);
});

test('withSectionStatus recomputes generationComplete when demoting a stage', () => {
  // 先构造一个 complete output
  let o = makeEmptyDeepReadOutput();
  for (const s of STAGE_ORDER) o = withSectionStatus(o, s, 'READY');
  o = { ...o, generationComplete: true };
  assert.equal(o.generationComplete, true);
  // 把 OVERVIEW 降级为 FAILED → generationComplete 必须变 false
  const demoted = withSectionStatus(o, 'OVERVIEW', 'FAILED', 'timeout');
  assert.equal(demoted.generationComplete, false, 'demoting a stage must flip generationComplete to false');
});

test('withSectionStatus preserves generationComplete when all still READY', () => {
  let o = makeEmptyDeepReadOutput();
  for (const s of STAGE_ORDER) o = withSectionStatus(o, s, 'READY');
  o = { ...o, generationComplete: true };
  // 重新写 OVERVIEW=READY(无变化)→ generationComplete 保持 true
  const rewritten = withSectionStatus(o, 'OVERVIEW', 'READY');
  assert.equal(rewritten.generationComplete, true);
});

test('withSectionQuality returns new reference + writes quality', () => {
  const o = makeEmptyDeepReadOutput();
  const result = withSectionQuality(o, 'OVERVIEW', 'STANDARD');
  assert.notEqual(result, o);
  assert.equal(result.sectionQualities['OVERVIEW'], 'STANDARD');
  assert.equal(o.sectionQualities['OVERVIEW'], undefined, 'original unchanged');
});

test('withPhase returns new reference + writes phase', () => {
  const o = makeEmptyDeepReadOutput();
  const result = withPhase(o, 'WRITING');
  assert.notEqual(result, o);
  assert.equal(result.generationPhase, 'WRITING');
  assert.equal(o.generationPhase, 'IDLE', 'original unchanged');
});

test('withPhase merges extra fields', () => {
  const o = makeEmptyDeepReadOutput();
  const result = withPhase(o, 'COMPLETE', { generationComplete: true });
  assert.equal(result.generationPhase, 'COMPLETE');
  assert.equal(result.generationComplete, true);
});

test('firstFailureMessage returns null when no FAILED stages', () => {
  const o = makeEmptyDeepReadOutput();
  assert.equal(firstFailureMessage(o), null);
});

test('firstFailureMessage returns first FAILED in declaration order', () => {
  const o = makeEmptyDeepReadOutput();
  // 反向设置,验证声明顺序(OVERVIEW 优先)
  o.sectionStates = {
    EXTENDED_READING: { status: 'FAILED', errorMessage: 'ext error' },
    ANALYSIS: { status: 'FAILED', errorMessage: 'analysis error' },
    OVERVIEW: { status: 'FAILED', errorMessage: 'overview error' },
  };
  assert.equal(firstFailureMessage(o), 'overview error');
});

test('firstFailureMessage returns errorMessage even if null', () => {
  const o = makeEmptyDeepReadOutput();
  o.sectionStates = { OVERVIEW: { status: 'FAILED', errorMessage: null } };
  assert.equal(firstFailureMessage(o), null);
});

test('verifiedImageUrls empty when no assets', () => {
  const o = makeEmptyDeepReadOutput();
  assert.equal(verifiedImageUrls(o).size, 0);
});

test('verifiedImageUrls excludes REJECT and non-http', () => {
  const mkAsset = (overrides: Partial<DeepReadImageAsset>): DeepReadImageAsset => ({
    url: '', caption: null, confidence: IMAGE_CONFIDENCE.INLINE, score: 50,
    source: null, qualityHint: null, selectionReason: null,
    relatedEntities: [], relatedTimelineIndex: null,
    ...overrides,
  });
  const o = makeEmptyDeepReadOutput();
  o.imageAssets = [
    mkAsset({ url: 'https://ok.com/a.jpg', confidence: IMAGE_CONFIDENCE.HERO }),
    mkAsset({ url: 'https://ok.com/b.jpg', confidence: IMAGE_CONFIDENCE.INLINE }),
    mkAsset({ url: 'https://reject.com/c.jpg', confidence: IMAGE_CONFIDENCE.REJECT }),
    mkAsset({ url: 'data:image/png;base64,xxx', confidence: IMAGE_CONFIDENCE.INLINE }),
    mkAsset({ url: 'ftp://other.com/d.jpg', confidence: IMAGE_CONFIDENCE.HERO }),
  ];
  const result = verifiedImageUrls(o);
  assert.equal(result.size, 2);
  assert.ok(result.has('https://ok.com/a.jpg'));
  assert.ok(result.has('https://ok.com/b.jpg'));
});
