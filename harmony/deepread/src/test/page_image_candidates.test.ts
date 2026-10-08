import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { extractPageImageCandidates } from '../main/ets/research/page_image_candidates.ts';

const page = 'https://news.example.com/2026/story/index.html?ref=feed';

test('OG metadata accepts reordered attributes and binds dimensions/alt to its own image', () => {
  const images = extractPageImageCandidates(`
    <META CONTENT='/photos/first.jpg?a=1&amp;b=2' PROPERTY='OG:IMAGE'>
    <meta content=1200 property=og:image:width>
    <meta property="og:image:height" content="630">
    <meta property="og:image:alt" content="First &amp; second">
    <meta content="https://cdn.example.com/second.jpg" property="og:image">
    <meta property="og:image:width" content="900">
    <meta property="og:image:height" content="600">
  `, page);
  assert.deepEqual(images.map(image => [image.url, image.width, image.height, image.altText]), [
    ['https://news.example.com/photos/first.jpg?a=1&b=2', 1200, 630, 'First & second'],
    ['https://cdn.example.com/second.jpg', 900, 600, ''],
  ]);
  assert.ok(images.every(image => image.sourceUrl === page));
});

test('Twitter metadata and img duplicates preserve the available OG dimensions', () => {
  const images = extractPageImageCandidates(`
    <meta property="og:image" content="/cover.jpg">
    <meta property="og:image:width" content="1200">
    <meta property="og:image:height" content="630">
    <meta content="/cover.jpg" name="twitter:image">
    <meta content="Cover detail" name="twitter:image:alt">
    <img src="/cover.jpg">
    <meta name="twitter:image:src" content="/twitter.jpg">
  `, page);
  assert.equal(images.length, 2);
  assert.equal(images[0].width, 1200);
  assert.equal(images[0].height, 630);
  assert.equal(images[0].altText, 'Cover detail');
  assert.equal(images[1].url, 'https://news.example.com/twitter.jpg');
  assert.equal(images[1].width, 0);
  assert.equal(images[1].height, 0);
});

test('img attribute names are exact and invalid src falls through to lazy attributes', () => {
  const images = extractPageImageCandidates(`
    <img data-src="/lazy.jpg" src="/actual.jpg" width=1200 height=800 alt='Actual'>
    <img SRC="data:image/gif;base64,abc" DATA-SRC="../lazy.jpg" ALT="A &quot;photo&quot;">
    <img src="javascript:alert(1)" data-original="original.jpg">
    <img x-src="/wrong.jpg">
  `, page);
  assert.deepEqual(images.map(image => image.url), [
    'https://news.example.com/lazy.jpg',
    'https://news.example.com/2026/lazy.jpg',
    'https://news.example.com/2026/story/original.jpg',
  ]);
  assert.equal(images[1].altText, 'A "photo"');
  assert.equal(images[1].width, 0);
  assert.equal(images[1].height, 0);
});

test('real lazy attributes take precedence over a valid HTTP placeholder src', () => {
  const images = extractPageImageCandidates(`
    <img src="/placeholder.gif" data-src="/real-photo.jpg">
    <img src="/placeholder.gif" data-original="/original-photo.jpg">
    <img src="/fallback-photo.jpg" data-src="javascript:bad" data-original="data:image/gif;base64,abc">
  `, page);
  assert.deepEqual(images.map(image => image.url), [
    'https://news.example.com/real-photo.jpg',
    'https://news.example.com/original-photo.jpg',
    'https://news.example.com/fallback-photo.jpg',
  ]);
});

test('standalone og:image:secure_url starts an image with its own metadata', () => {
  const images = extractPageImageCandidates(`
    <meta property="og:image:secure_url" content="https://cdn.example.com/secure.jpg">
    <meta property="og:image:width" content="1200">
    <meta property="og:image:height" content="630">
    <meta property="og:image:alt" content="Secure image description">
  `, page);
  assert.deepEqual(images, [{
    url: 'https://cdn.example.com/secure.jpg', width: 1200, height: 630,
    altText: 'Secure image description', sourceUrl: page,
  }]);
});

test('srcset and data-srcset choose their largest valid descriptor without inventing dimensions', () => {
  const images = extractPageImageCandidates(`
    <img src="small.jpg" srcset="small.jpg 320w, large.jpg 1200w, bad.jpg nope, medium.jpg 640w">
    <img data-srcset="single.jpg 1x, double.jpg 2x, half.jpg .5x">
    <img src="fallback.jpg" srcset="javascript:bad 4x, zero.jpg 0x, double.jpg 2x">
    <img data-src="lazy-fallback.jpg" srcset="bad.jpg nope">
  `, page);
  assert.deepEqual(images.map(image => image.url), [
    'https://news.example.com/2026/story/large.jpg',
    'https://news.example.com/2026/story/double.jpg',
    'https://news.example.com/2026/story/lazy-fallback.jpg',
  ]);
  assert.ok(images.every(image => image.width === 0 && image.height === 0));
});

test('URL resolution accepts HTTP, protocol-relative, root, directory and parent paths', () => {
  const images = extractPageImageCandidates(`
    <img src=HTTP://cdn.example.com/absolute.jpg>
    <img src=//cdn.example.com/protocol.jpg>
    <img src=/root.jpg>
    <img src=photo.jpg>
    <img src=../../photo.jpg>
    <img src=./sub/../same.jpg?one=1&amp;two=2>
  `, page);
  assert.deepEqual(images.map(image => image.url), [
    'http://cdn.example.com/absolute.jpg',
    'https://cdn.example.com/protocol.jpg',
    'https://news.example.com/root.jpg',
    'https://news.example.com/2026/story/photo.jpg',
    'https://news.example.com/photo.jpg',
    'https://news.example.com/2026/story/same.jpg?one=1&two=2',
  ]);
  const plainHttp = extractPageImageCandidates('<img src="//cdn.example.com/photo.jpg">', 'http://example.com/a');
  assert.equal(plainHttp[0].url, 'http://cdn.example.com/photo.jpg');
});

test('explicit non-HTTP schemes, empty references and malformed hosts are rejected', () => {
  const images = extractPageImageCandidates(`
    <img src="data:image/png;base64,abc">
    <img src="javascript:alert(1)">
    <img src="file:///tmp/photo.jpg">
    <img src="ftp://example.com/photo.jpg">
    <img src="https:///no-host.jpg">
    <img src="https://">
    <img src="">
    <img src="#photo">
  `, page);
  assert.deepEqual(images, []);
});

test('data URL commas in srcset do not turn the encoded payload into a relative image', () => {
  const images = extractPageImageCandidates(`
    <img src="/fallback.jpg" srcset="data:image/png;base64,abc 4x, /retina.jpg 2x">
    <img src="/fallback.jpg" srcset="data:image/png;base64,abc 4x">
  `, page);
  assert.deepEqual(images.map(image => image.url), [
    'https://news.example.com/retina.jpg',
    'https://news.example.com/fallback.jpg',
  ]);
});

test('quoted > does not truncate alt text and known small dimensions stay explicit', () => {
  const images = extractPageImageCandidates('<img src="/photo.jpg" alt="A > B" width=200 height=200>', page);
  assert.equal(images[0].altText, 'A > B');
  assert.equal(images[0].width, 200);
  assert.equal(images[0].height, 200);
});

test('comment and script examples are not image candidates', () => {
  const images = extractPageImageCandidates(`
    <!-- <img src="/comment.jpg"> -->
    <script>const template = '<img src="/script.jpg">';</script>
    <style>/* <img src="/style.jpg"> */</style>
    <img src="/visible.jpg">
  `, page);
  assert.deepEqual(images.map(image => image.url), ['https://news.example.com/visible.jpg']);
});
