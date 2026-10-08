import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  extractReadableTextArkTs, extractReadableText, ExtractedArticle,
} from '../main/ets/research/reader_extractor.ts';

// ===== basic title + body extraction =====

test('extractReadableTextArkTs: extracts title from <title> tag', () => {
  const html = '<html><head><title>Breaking News Today</title></head>' +
    '<body><p>This is a sufficiently long paragraph that exceeds the eighteen character minimum length.</p></body></html>';
  const r = extractReadableTextArkTs(html);
  assert.equal(r.title, 'Breaking News Today');
});

test('extractReadableTextArkTs: falls back to baseUrl when no title', () => {
  const html = '<body><p>This is a sufficiently long paragraph exceeding the eighteen character minimum length.</p></body>';
  const r = extractReadableTextArkTs(html, 'https://example.com/x');
  assert.equal(r.title, 'https://example.com/x');
  assert.equal(r.sectionCount, 1);
});

// ===== script/style/noscript/svg/canvas stripping (Android fidelity) =====

test('strips <script> content', () => {
  const html = '<body>' +
    '<script>var x = "this should NOT appear in output whatsoever";</script>' +
    '<p>This is a sufficiently long paragraph that exceeds the eighteen character minimum length.</p>' +
    '</body>';
  const r = extractReadableTextArkTs(html);
  assert.ok(!r.contentText.includes('should NOT appear'));
  assert.ok(r.contentText.includes('sufficiently long paragraph'));
});

test('strips <style> content', () => {
  const html = '<body>' +
    '<style>.evil { color: red; display: none; }</style>' +
    '<p>This is a sufficiently long paragraph that exceeds the eighteen character minimum length.</p>' +
    '</body>';
  const r = extractReadableTextArkTs(html);
  assert.ok(!r.contentText.includes('color: red'));
});

test('strips <noscript> content', () => {
  const html = '<body>' +
    '<noscript>fallback content here is long enough yes</noscript>' +
    '<p>This is a sufficiently long paragraph that exceeds the eighteen character minimum length.</p>' +
    '</body>';
  const r = extractReadableTextArkTs(html);
  assert.ok(!r.contentText.includes('fallback content'));
});

test('strips <svg> and <canvas> content', () => {
  const html = '<body>' +
    '<svg><path d="M0 0"/></svg>' +
    '<canvas></canvas>' +
    '<p>This is a sufficiently long paragraph that exceeds the eighteen character minimum length.</p>' +
    '</body>';
  const r = extractReadableTextArkTs(html);
  assert.ok(!r.contentText.includes('path'));
});

// ===== block-level newline semantics (Android fidelity) =====

test('<br> and block close tags create line breaks', () => {
  const html = '<p>First paragraph exceeds the eighteen character minimum length easily.</p>' +
    '<br>' +
    '<div>Second paragraph also exceeds the eighteen character minimum length easily.</div>';
  const r = extractReadableTextArkTs(html);
  const lines = r.contentText.split('\n');
  assert.equal(lines.length, 2, 'two blocks → two lines');
  assert.ok(lines[0].includes('First paragraph'));
  assert.ok(lines[1].includes('Second paragraph'));
});

// ===== HTML entity unescaping (Android fidelity) =====

test('unescapes common HTML entities', () => {
  const html = '<p>Tom &amp; Jerry &lt;ran&gt; &quot;fast&quot; &#39;today&#39; very quickly indeed.</p>';
  const r = extractReadableTextArkTs(html);
  assert.ok(r.contentText.includes('Tom & Jerry'));
  assert.ok(r.contentText.includes('<ran>'));
  assert.ok(r.contentText.includes('"fast"'));
  assert.ok(r.contentText.includes("'today'"));
});

// ===== ≥18 char line filter (Android fidelity, key) =====

test('filters out lines shorter than 18 characters', () => {
  const html = '<p>short.</p><p>this line is long enough to pass the filter test yes.</p>';
  const r = extractReadableTextArkTs(html);
  const lines = r.contentText.split('\n');
  assert.equal(lines.length, 1, 'short line filtered');
  assert.ok(lines[0].includes('long enough to pass'));
  assert.equal(r.sectionCount, 1);
});

// ===== distinct dedup (Android fidelity) =====

test('dedups identical lines', () => {
  const line = 'this line is long enough to pass the filter test yes';
  const html = `<p>${line}</p><p>${line}</p><p>${line}</p>`;
  const r = extractReadableTextArkTs(html);
  const lines = r.contentText.split('\n');
  assert.equal(lines.length, 1, 'duplicates removed');
});

// ===== extractReadableText double-path =====

test('extractReadableText: no native extractor → ArkTS path', () => {
  const html = '<body><p>This is a sufficiently long paragraph that exceeds the eighteen character minimum.</p></body>';
  const text = extractReadableText(html, 'https://example.com');
  assert.ok(text.includes('sufficiently long paragraph'));
});

test('extractReadableText: native success (>= 18 chars) uses native result', () => {
  const html = '<body><p>native extractor content here that is plenty long.</p></body>';
  const native: ExtractedArticle = {
    title: 'Native Title',
    contentText: 'native-extracted-body-text-from-rust-napi-engine-here',
    contentHtml: '',
    sectionCount: 1,
  };
  let calledHtml = '';
  let calledUrl = '';
  const extractor = (h: string, u: string): ExtractedArticle | null => {
    calledHtml = h;
    calledUrl = u;
    return native;
  };
  const text = extractReadableText(html, 'https://example.com', extractor);
  assert.equal(text, native.contentText);
  assert.equal(calledUrl, 'https://example.com');
  assert.ok(calledHtml.length > 0);
});

test('extractReadableText: native returns null → fallback to ArkTS', () => {
  const html = '<body><p>This is a sufficiently long paragraph that exceeds the eighteen character minimum.</p></body>';
  const extractor = (): ExtractedArticle | null => null;
  const text = extractReadableText(html, 'https://example.com', extractor);
  assert.ok(text.includes('sufficiently long paragraph'), 'fell back to ArkTS');
});

test('extractReadableText: native short result (< 18 chars) → fallback', () => {
  const html = '<body><p>This is a sufficiently long paragraph that exceeds the eighteen character minimum.</p></body>';
  const shortNative: ExtractedArticle = {
    title: 'x', contentText: 'too short', contentHtml: '', sectionCount: 0,
  };
  const extractor = (): ExtractedArticle | null => shortNative;
  const text = extractReadableText(html, 'https://example.com', extractor);
  assert.ok(text.includes('sufficiently long paragraph'), 'short native fell back to ArkTS');
  assert.ok(!text.includes('too short'));
});

test('extractReadableText: native throws → fallback to ArkTS (no throw propagates)', () => {
  const html = '<body><p>This is a sufficiently long paragraph that exceeds the eighteen character minimum.</p></body>';
  const extractor = (): ExtractedArticle | null => { throw new Error('napi crash'); };
  const text = extractReadableText(html, 'https://example.com', extractor);
  assert.ok(text.includes('sufficiently long paragraph'), 'threw → fell back to ArkTS');
});

test('extractReadableText: null url → skips native even if provided', () => {
  const html = '<body><p>This is a sufficiently long paragraph that exceeds the eighteen character minimum.</p></body>';
  let called = false;
  const extractor = (): ExtractedArticle | null => { called = true; return null; };
  const text = extractReadableText(html, null, extractor);
  assert.equal(called, false, 'native not invoked when url is null');
  assert.ok(text.includes('sufficiently long paragraph'));
});
