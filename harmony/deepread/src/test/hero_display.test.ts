import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { displayHeroImageUrl, displayHeroCaption } from '../main/ets/domain/helpers.ts';
import { makeEmptyDeepReadOutput, DeepReadImageAsset } from '../main/ets/domain/models.ts';
import { IMAGE_CONFIDENCE } from '../main/ets/domain/enums.ts';

const httpUrl = 'https://example.com/hero.jpg';

const mkAsset = (overrides: Partial<DeepReadImageAsset>): DeepReadImageAsset => ({
  url: '',
  caption: null,
  confidence: IMAGE_CONFIDENCE.INLINE,
  score: 50,
  source: null,
  qualityHint: null,
  selectionReason: null,
  relatedEntities: [],
  relatedTimelineIndex: null,
  ...overrides,
});

test('displayHeroImageUrl null when no hero and no imageAssets', () => {
  const o = makeEmptyDeepReadOutput();
  assert.equal(displayHeroImageUrl(o), null);
});

test('displayHeroImageUrl returns heroImageUrl when confidence=hero and in assets', () => {
  const o = makeEmptyDeepReadOutput();
  o.heroImageUrl = httpUrl;
  o.heroImageConfidence = IMAGE_CONFIDENCE.HERO;
  o.imageAssets = [mkAsset({ url: httpUrl, confidence: IMAGE_CONFIDENCE.HERO, score: 90 })];
  assert.equal(displayHeroImageUrl(o), httpUrl);
});

test('displayHeroImageUrl ignores heroImageUrl when confidence != hero', () => {
  const o = makeEmptyDeepReadOutput();
  o.heroImageUrl = httpUrl;
  o.heroImageConfidence = IMAGE_CONFIDENCE.INLINE;
  o.imageAssets = [mkAsset({ url: httpUrl, confidence: IMAGE_CONFIDENCE.INLINE, score: 50 })];
  // hero confidence 不匹配,fallback 到第一个非 reject
  assert.equal(displayHeroImageUrl(o), httpUrl);
});

test('displayHeroImageUrl skips REJECT assets', () => {
  const o = makeEmptyDeepReadOutput();
  o.imageAssets = [
    mkAsset({ url: 'https://example.com/a.jpg', confidence: IMAGE_CONFIDENCE.REJECT, score: 10 }),
    mkAsset({ url: 'https://example.com/b.jpg', confidence: IMAGE_CONFIDENCE.INLINE, score: 60 }),
  ];
  assert.equal(displayHeroImageUrl(o), 'https://example.com/b.jpg');
});

test('displayHeroImageUrl skips non-http urls', () => {
  const o = makeEmptyDeepReadOutput();
  o.imageAssets = [
    mkAsset({ url: 'data:image/png;base64,xxx', confidence: IMAGE_CONFIDENCE.INLINE, score: 60 }),
  ];
  assert.equal(displayHeroImageUrl(o), null);
});

test('displayHeroCaption null when no image', () => {
  const o = makeEmptyDeepReadOutput();
  assert.equal(displayHeroCaption(o), null);
});

test('displayHeroCaption returns heroCaption when matching heroImageUrl', () => {
  const o = makeEmptyDeepReadOutput();
  o.heroImageUrl = httpUrl;
  o.heroCaption = '主图说明';
  o.heroImageConfidence = IMAGE_CONFIDENCE.HERO;
  o.imageAssets = [mkAsset({ url: httpUrl, confidence: IMAGE_CONFIDENCE.HERO, score: 90 })];
  assert.equal(displayHeroCaption(o), '主图说明');
});

test('displayHeroCaption falls back to asset caption', () => {
  const o = makeEmptyDeepReadOutput();
  o.imageAssets = [mkAsset({ url: httpUrl, caption: 'asset 说明', confidence: IMAGE_CONFIDENCE.INLINE, score: 60 })];
  assert.equal(displayHeroCaption(o), 'asset 说明');
});
