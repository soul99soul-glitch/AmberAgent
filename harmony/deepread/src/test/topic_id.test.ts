import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { deriveTopicId, normalizeUrl, normalizeTitle } from '../main/ets/domain/topic_id.ts';

test('URL input: hash of normalized URL, 32 hex chars', () => {
  const id = deriveTopicId({ url: 'https://example.com/news/123', title: 'ignored' });
  assert.match(id, /^[a-f0-9]{32}$/);
});

test('URL input: same URL → same id', () => {
  const a = deriveTopicId({ url: 'https://example.com/news/123', title: 'x' });
  const b = deriveTopicId({ url: 'https://example.com/news/123', title: 'y' });
  assert.equal(a, b);
});

test('URL normalization: strip tracking params', () => {
  const a = deriveTopicId({ url: 'https://example.com/news/123', title: 'x' });
  const b = deriveTopicId({ url: 'https://example.com/news/123?utm_source=foo&utm_medium=bar', title: 'x' });
  assert.equal(a, b, 'utm_ params should not affect topic_id');
});

test('URL normalization: lowercase host', () => {
  const a = deriveTopicId({ url: 'https://Example.com/News/123', title: 'x' });
  const b = deriveTopicId({ url: 'https://example.com/News/123', title: 'x' });
  assert.equal(a, b, 'host case-insensitive');
});

test('URL normalization: strip fragment', () => {
  const a = deriveTopicId({ url: 'https://example.com/news/123', title: 'x' });
  const b = deriveTopicId({ url: 'https://example.com/news/123#section', title: 'x' });
  assert.equal(a, b);
});

test('title input: hash of normalized title, 32 hex chars', () => {
  const id = deriveTopicId({ url: null, title: '某话题标题' });
  assert.match(id, /^[a-f0-9]{32}$/);
});

test('title normalization: lowercase + single space + trim', () => {
  const a = deriveTopicId({ url: null, title: '某  话题   标题' });
  const b = deriveTopicId({ url: null, title: '某 话题 标题' });
  // Android topicId() 用 lowercase + \s+ → 单空格 + trim
  assert.equal(a, b);
});

test('URL input and title input produce different ids', () => {
  const urlId = deriveTopicId({ url: 'https://example.com/abc', title: '某话题' });
  const titleId = deriveTopicId({ url: null, title: '某话题' });
  assert.notEqual(urlId, titleId);
});

test('non-http URL falls back to title path', () => {
  const id = deriveTopicId({ url: 'ftp://example.com', title: '某话题' });
  const titleId = deriveTopicId({ url: null, title: '某话题' });
  assert.equal(id, titleId, 'non-http url falls back to title path');
});

test('normalizeUrl exports', () => {
  assert.equal(normalizeUrl('https://example.com/path'), 'https://example.com/path');
  assert.equal(normalizeUrl('HTTPS://EXAMPLE.com/path'), 'https://example.com/path');
});

test('normalizeTitle exports', () => {
  assert.equal(normalizeTitle('  Hello   World  '), 'hello world');
});
