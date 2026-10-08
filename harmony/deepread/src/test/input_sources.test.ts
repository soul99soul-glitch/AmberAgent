import test from 'node:test';
import assert from 'node:assert/strict';
import { makeInputSource, sourceInputs, mergeCollectedSources, generationSources, extractDocxSourceText, decodeInputSourceText } from '../main/ets/domain/input_sources.ts';

test('text is normalized and truncation is explicit while all inputs remain stored', () => {
  const source = makeInputSource('text', '粘贴文本', ' x\r\n' + '汉'.repeat(40001));
  assert.equal(source.status, 'ready');
  assert.equal(source.content.length, 40000);
  assert.equal(source.truncated, true);
  assert.match(source.note ?? '', /40000/);
  const many = Array.from({ length: 12 }, (_, index) => makeInputSource('file', `${index}.txt`, `body ${index}`));
  assert.equal(many.length, 12);
  assert.equal(generationSources(many).length, 10);
  assert.equal(many.length, 12);
});

test('URL inputs validate independently, dedupe and preserve failure details', () => {
  const inputs = sourceInputs('body', 'https://example.com/a\nhttps://example.com/a\ninvalid');
  assert.equal(inputs.length, 3);
  assert.equal(inputs[1].status, 'pending');
  assert.equal(inputs[2].status, 'failed');
  assert.match(inputs[2].error ?? '', /HTTP/);
});

test('collection keeps full successful sources and failed original URLs; retry retains verified body', () => {
  const originals = sourceInputs('', 'https://example.com/good\nhttps://example.com/bad');
  const result = mergeCollectedSources(originals, [{ sourceId: 'src-1', url: 'https://example.com/good', title: 'Article',
    source: 'seed', evidenceText: 'verified body', credibility: 'medium', freshness: 'unknown', publishedAt: null, imageCandidates: [] }],
  [{ url: 'https://example.com/bad', title: 'bad', error: 'HTTP 403' }]);
  assert.equal(result.length, 2);
  assert.equal(result[1].error, 'HTTP 403');
  assert.equal(result[0].researchSource?.evidenceText, '', 'body is stored once instead of duplicated in research metadata');
  assert.equal(generationSources(result)[0].evidenceText, 'verified body');
  const retried = mergeCollectedSources(result, [], [{ url: 'https://example.com/good', title: 'good', error: 'HTTP 500' }]);
  assert.equal(retried[0].content, 'verified body');
  assert.equal(retried[0].status, 'ready');
  assert.equal(generationSources(retried)[0].url, 'https://example.com/good');
});

test('DOCX extraction includes paragraph boundaries and XML entities', () => {
  assert.equal(extractDocxSourceText('<w:p><w:r><w:t>Hello &amp; &#x4E2D;</w:t></w:r></w:p><w:p><w:r><w:t>next</w:t></w:r></w:p>'), 'Hello & 中\nnext');
});

test('file text decoder handles UTF-8 BOM, UTF-16 BOM and GB18030 fallback', () => {
  const decode = (bytes: Uint8Array, encoding: string): string => new TextDecoder(encoding, { fatal: true }).decode(bytes);
  assert.equal(decodeInputSourceText(new Uint8Array([0xef, 0xbb, 0xbf, 65]), decode), 'A');
  assert.equal(decodeInputSourceText(new Uint8Array([0xff, 0xfe, 45, 78]), decode), '中');
  assert.equal(decodeInputSourceText(new Uint8Array([0xfe, 0xff, 78, 45]), decode), '中');
  assert.equal(decodeInputSourceText(new Uint8Array([0xd6, 0xd0]), decode), '中');
});
