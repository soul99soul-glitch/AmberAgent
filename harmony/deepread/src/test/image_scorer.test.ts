import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { scoreImageCandidate, scoreAndDedup, ImageCandidate } from '../main/ets/research/image_scorer.ts';

const bigImg = (overrides: Partial<ImageCandidate> = {}): ImageCandidate => ({
  url: 'https://example.com/photo.jpg',
  width: 1200,
  height: 800,
  altText: 'A detailed photo of the subject matter',
  sourceUrl: 'https://example.com/article',
  ...overrides,
});

test('boundary: width 321 height 161 → NOT too_small (just above threshold)', () => {
  // 321x161: too_small is <320 OR <160 → passes; tiny_asset needs smaller<=160 → min=161 → passes
  const s = scoreImageCandidate(bigImg({ width: 321, height: 161, altText: '' }), '');
  assert.ok(!s.riskFlags.includes('too_small'));
  assert.notEqual(s.confidence, 'reject');
});

test('tiny_asset catches 320x160 (min<=160 AND max<=320)', () => {
  // Android tiny_asset: smaller<=160 && larger<=320 — this IS a hard reject
  const s = scoreImageCandidate(bigImg({ width: 320, height: 160, altText: '' }), '');
  assert.equal(s.confidence, 'reject');
});

test('unknown dimensions remain inline even with a high score', () => {
  const s = scoreImageCandidate(bigImg({ width: 0, height: 0, byteSize: 60_000 }), 'subject matter');
  assert.equal(s.confidence, 'inline');
  assert.ok(s.riskFlags.includes('unknown_dimensions'));
  assert.ok(!s.riskFlags.includes('too_small'));
  assert.ok(Number.isFinite(s.score));
});

test('one unknown dimension never qualifies as hero', () => {
  for (const dimensions of [{ width: 1200, height: 0 }, { width: 0, height: 800 }]) {
    const s = scoreImageCandidate(bigImg(dimensions), 'subject matter');
    assert.equal(s.confidence, 'inline');
    assert.ok(s.riskFlags.includes('unknown_dimensions'));
  }
});

test('known small dimensions still reject when the other dimension is unknown', () => {
  for (const dimensions of [{ width: 200, height: 0 }, { width: 0, height: 100 }, { width: 200, height: 200 }]) {
    const s = scoreImageCandidate(bigImg(dimensions), '');
    assert.equal(s.confidence, 'reject');
    assert.ok(s.riskFlags.includes('too_small'));
  }
});

test('unknown dimensions do not bypass logo, avatar or tracking risk rejection', () => {
  for (const name of ['logo.png', 'avatar.jpg', '1x1.gif']) {
    const s = scoreImageCandidate(bigImg({ url: `https://example.com/${name}`, width: 0, height: 0 }), '');
    assert.equal(s.confidence, 'reject');
  }
});

// ===== reject: site_brand_asset (URL-based, Android fidelity) =====

test('site_brand_asset: logo in url → reject', () => {
  const s = scoreImageCandidate(bigImg({ url: 'https://example.com/logo.png' }), '');
  assert.equal(s.confidence, 'reject');
});

// ===== reject: avatar / tracking / sprite =====

test('avatar in url → reject', () => {
  const s = scoreImageCandidate(bigImg({ url: 'https://example.com/avatar.jpg' }), '');
  assert.equal(s.confidence, 'reject');
});

test('tracking pixel / 1x1 in url → reject', () => {
  const s = scoreImageCandidate(bigImg({ url: 'https://track.example.com/1x1.gif' }), '');
  assert.equal(s.confidence, 'reject');
});

test('spacer/blank.gif in url → reject', () => {
  const s = scoreImageCandidate(bigImg({ url: 'https://example.com/blank.gif' }), '');
  assert.equal(s.confidence, 'reject');
});

test('sprite in url → reject', () => {
  const s = scoreImageCandidate(bigImg({ url: 'https://example.com/sprite.png' }), '');
  assert.equal(s.confidence, 'reject');
});

// ===== reject: icon_format (.ico/.svg) =====

test('.svg extension → reject (icon_format)', () => {
  const s = scoreImageCandidate(bigImg({ url: 'https://example.com/icon.svg' }), '');
  assert.equal(s.confidence, 'reject');
});

// ===== reject: non-http (data: URL) =====

test('data: URL → reject (non_http_url)', () => {
  const s = scoreImageCandidate(bigImg({ url: 'data:image/png;base64,iVBOR=' }), '');
  assert.equal(s.confidence, 'reject');
});

test('tiny_file: 4096 boundary → reject, 4097 not', () => {
  assert.equal(scoreImageCandidate(bigImg({ byteSize: 4096 }), '').confidence, 'reject');
  // 4097 alone doesn't trigger tiny_file; need also to avoid other reject flags
  const ok = scoreImageCandidate(bigImg({ byteSize: 4097, altText: 'a nice photo here' }), '');
  assert.notEqual(ok.confidence, 'reject');
});

test('tiny_asset: smaller<=160 and larger<=320 → reject', () => {
  const s = scoreImageCandidate(bigImg({ width: 150, height: 200 }), '');
  assert.equal(s.confidence, 'reject');
});

// ===== hero: large + good aspect + high score =====

test('large square-ish image + good alt → hero candidate', () => {
  const s = scoreImageCandidate(bigImg({ width: 800, height: 600, altText: 'a wonderful meaningful alt description' }), '');
  assert.equal(s.confidence, 'hero');
});

test('hero requires score >= 60', () => {
  // min dim 400 (not 800) → +20 not +30; no alt → no +10; → 30+20=50 < 60 → inline
  const s = scoreImageCandidate(bigImg({ width: 400, height: 300, altText: '' }), '');
  assert.equal(s.confidence, 'inline');
});

test('hero requires aspect ratio 0.75..2.0', () => {
  // 2000x800 → ratio 2.5, too wide even with high score
  const s = scoreImageCandidate(bigImg({ width: 2000, height: 800, altText: 'a nice photo here with more text' }), '');
  assert.notEqual(s.confidence, 'hero');
});

// ===== extreme aspect ratio → inline + risk flag (not reject) =====

test('extreme aspect ratio (>3) → inline with risk flag', () => {
  const s = scoreImageCandidate(bigImg({ width: 3000, height: 320, altText: 'a wide banner image text here' }), '');
  // not reject (width 3000 >= 320, height 320 >= 160, ratio 9.375 → extreme)
  assert.notEqual(s.confidence, 'reject');
  assert.ok(s.riskFlags.includes('extreme_aspect_ratio'));
});

// ===== scoreAndDedup =====

test('scoreAndDedup: same URL keeps higher score', () => {
  const url = 'https://example.com/same.jpg';
  const a = scoreImageCandidate({ url, width: 400, height: 300, altText: '', sourceUrl: 's' }, '');
  const b = scoreImageCandidate({ url, width: 1200, height: 800, altText: 'a meaningful alt text here', sourceUrl: 's' }, '');
  // feed raw candidates (dedup scores internally)
  const result = scoreAndDedup([
    { url, width: 400, height: 300, altText: '', sourceUrl: 's' },
    { url, width: 1200, height: 800, altText: 'a meaningful alt text here', sourceUrl: 's' },
  ], '');
  assert.equal(result.length, 1, 'deduped to one');
  assert.equal(result[0].score, b.score, 'kept higher score');
});

test('scoreAndDedup: orders by score descending', () => {
  const result = scoreAndDedup([
    { url: 'https://example.com/a.jpg', width: 400, height: 300, altText: '', sourceUrl: 's' },
    { url: 'https://example.com/b.jpg', width: 1200, height: 900, altText: 'a really meaningful description here', sourceUrl: 's' },
  ], '');
  assert.ok(result[0].score >= result[1].score, 'sorted desc');
});

// ===== alt text topic overlap bonus =====

test('alt text overlapping topic title gets score bonus', () => {
  // 用 CJK 话题区分:含话题字符的 alt 拿加分,纯拉丁的 alt 不拿
  const sWithOverlap = scoreImageCandidate(
    bigImg({ altText: '量子计算机突破现场照片实拍图' }), '量子计算机重大突破');
  const sWithout = scoreImageCandidate(
    bigImg({ altText: 'a generic stock photograph with no relation' }), '量子计算机重大突破');
  assert.ok(sWithOverlap.score > sWithout.score, 'overlap bonus applied');
  assert.ok(sWithOverlap.riskFlags.length === 0);
});

test('alt text below 4 overlap chars gets no bonus', () => {
  const s = scoreImageCandidate(
    bigImg({ altText: 'abc' }), '量子计算机重大突破');  // alt 与 CJK 标题无重叠
  // 仍可能是 inline(无 reject),但不应拿到 overlap 加分
  assert.ok(!s.selectionReason.includes('overlaps topic title'));
});
