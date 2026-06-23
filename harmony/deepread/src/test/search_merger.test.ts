import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { interleaveSearchResults } from '../main/ets/research/search_merger.ts';
import type { SearchHit } from '../main/ets/platform/search.ts';
import { MAX_SEARCH_RESULTS } from '../main/ets/domain/enums.ts';

const hit = (url: string, title: string = url): SearchHit => ({
  title, url, snippet: null, source: 'test',
});

// ===== single bucket passthrough =====

test('single bucket → passthrough (order preserved)', () => {
  const bucket = [hit('https://a.com/1'), hit('https://b.com/2'), hit('https://c.com/3')];
  const result = interleaveSearchResults([bucket]);
  assert.deepEqual(result.map(h => h.url), [
    'https://a.com/1', 'https://b.com/2', 'https://c.com/3',
  ]);
});

test('single empty bucket → empty result', () => {
  assert.deepEqual(interleaveSearchResults([[]]), []);
});

// ===== round-robin multiple buckets =====

test('multiple buckets → round-robin interleaving', () => {
  const b1 = [hit('https://a.com/1'), hit('https://a.com/4'), hit('https://a.com/7')];
  const b2 = [hit('https://b.com/2'), hit('https://b.com/5')];
  const b3 = [hit('https://c.com/3'), hit('https://c.com/6'), hit('https://c.com/8'), hit('https://c.com/9')];
  const result = interleaveSearchResults([b1, b2, b3]);
  // index 0: a1,b2,c3  index 1: a4,b5,c6  index 2: a7,c8  index 3: c9
  assert.deepEqual(result.map(h => h.url), [
    'https://a.com/1', 'https://b.com/2', 'https://c.com/3',
    'https://a.com/4', 'https://b.com/5', 'https://c.com/6',
    'https://a.com/7', 'https://c.com/8', 'https://c.com/9',
  ]);
});

test('uneven buckets → shorter buckets skipped at higher indices', () => {
  const big = [hit('https://x.com/1'), hit('https://x.com/2'), hit('https://x.com/3')];
  const small = [hit('https://y.com/a')];
  const result = interleaveSearchResults([big, small]);
  assert.deepEqual(result.map(h => h.url), [
    'https://x.com/1', 'https://y.com/a',  // index 0
    'https://x.com/2',  // index 1 (y has no index 1)
    'https://x.com/3',  // index 2
  ]);
});

// ===== cross-bucket URL dedup =====

test('dedup: same URL across buckets kept once (first occurrence)', () => {
  const b1 = [hit('https://dup.com/1'), hit('https://a.com/2')];
  const b2 = [hit('https://dup.com/1'), hit('https://b.com/3')];
  const result = interleaveSearchResults([b1, b2]);
  assert.deepEqual(result.map(h => h.url), [
    'https://dup.com/1', 'https://a.com/2', 'https://b.com/3',
  ]);
});

test('dedup: within-bucket duplicate also removed', () => {
  const bucket = [hit('https://dup.com/1'), hit('https://dup.com/1'), hit('https://other.com/2')];
  const result = interleaveSearchResults([bucket]);
  assert.deepEqual(result.map(h => h.url), ['https://dup.com/1', 'https://other.com/2']);
});

// ===== cap MAX_SEARCH_RESULTS =====

test('caps at MAX_SEARCH_RESULTS (14)', () => {
  // 3 buckets × 10 unique hits each = 30, but capped at 14
  const buckets: SearchHit[][] = [];
  for (let b = 0; b < 3; b++) {
    const bucket: SearchHit[] = [];
    for (let i = 0; i < 10; i++) bucket.push(hit(`https://b${b}.com/${i}`));
    buckets.push(bucket);
  }
  const result = interleaveSearchResults(buckets);
  assert.equal(result.length, MAX_SEARCH_RESULTS);
  assert.equal(MAX_SEARCH_RESULTS, 14);
});

test('cap hits exactly during round-robin, not after bucket drain', () => {
  // 1 bucket with 20 items → capped at 14
  const bucket: SearchHit[] = [];
  for (let i = 0; i < 20; i++) bucket.push(hit(`https://x.com/${i}`));
  const result = interleaveSearchResults([bucket]);
  assert.equal(result.length, 14);
});

// ===== empty / edge cases =====

test('no buckets → empty result', () => {
  assert.deepEqual(interleaveSearchResults([]), []);
});

test('all empty buckets → empty result', () => {
  assert.deepEqual(interleaveSearchResults([[], [], []]), []);
});

test('mixed empty and non-empty buckets', () => {
  const result = interleaveSearchResults([
    [],
    [hit('https://a.com/1'), hit('https://a.com/2')],
    [],
  ]);
  assert.deepEqual(result.map(h => h.url), ['https://a.com/1', 'https://a.com/2']);
});

// ===== dedup + cap interaction =====

test('dedup applied before cap counting', () => {
  // many duplicates so effective unique count < cap
  const b1 = [hit('https://same.com'), hit('https://a.com/1'), hit('https://a.com/2')];
  const b2 = [hit('https://same.com'), hit('https://b.com/1'), hit('https://b.com/2')];
  const result = interleaveSearchResults([b1, b2]);
  assert.deepEqual(result.map(h => h.url), [
    'https://same.com', 'https://a.com/1', 'https://b.com/1',
    'https://a.com/2', 'https://b.com/2',
  ]);
});
