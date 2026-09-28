import { test } from 'node:test';
import assert from 'node:assert/strict';
import { imageExportPages } from '../main/ets/chat/image_export_pages.ts';

test('ordinary content remains one image at its measured height', () => {
  assert.deepEqual(imageExportPages(1210, 2856), [{ top: 0, height: 1210 }]);
  assert.deepEqual(imageExportPages(2856, 2856), [{ top: 0, height: 2856 }]);
});

test('huge content covers every physical row once with balanced screen-safe pages', () => {
  const contentHeight = 92783;
  const pages = imageExportPages(contentHeight, 2856);
  const rows = new Uint8Array(contentHeight);
  let nextTop = 0;
  for (const page of pages) {
    assert.equal(page.top, nextTop);
    assert.ok(page.height > 0 && page.height <= 2856);
    for (let row = page.top; row < page.top + page.height; row++) rows[row]++;
    nextTop = page.top + page.height;
  }
  assert.equal(nextTop, contentHeight);
  assert.ok(rows.every(count => count === 1));
  assert.equal(pages.length, Math.ceil(contentHeight / 2856));
  const heights = pages.map(page => page.height);
  assert.ok(Math.max(...heights) - Math.min(...heights) <= 1);
});

test('fractional measurement preserves the last pixel without exceeding a fractional screen limit', () => {
  assert.deepEqual(imageExportPages(4000.2, 2000.8), [
    { top: 0, height: 1334 }, { top: 1334, height: 1334 }, { top: 2668, height: 1333 },
  ]);
});

test('fractional SVG heights round only physical pixels so final scroll clamping stays below one pixel', () => {
  for (const density of [1, 1.25, 2, 2.75, 3, 3.5]) {
    for (const cssHeight of [2000.1, 2000.49, 2000.51, 2000.99, 92317.1234]) {
      const contentHeightPx = cssHeight * density;
      const pages = imageExportPages(contentHeightPx, 2856);
      const finalPage = pages.at(-1)!;
      const trueMaximumOffsetPx = Math.max(0, contentHeightPx - finalPage.height);
      const clampedOffsetPx = Math.min(finalPage.top, trueMaximumOffsetPx);
      assert.ok(finalPage.top - clampedOffsetPx < 1,
        `${cssHeight} CSS pixels at density ${density}: tail offset was clamped by over one physical pixel`);
      assert.ok(finalPage.top + finalPage.height >= contentHeightPx);
    }
  }
  // 2000.1vp × 3 = 6000.3px，总计只补 0.7px；不能先将 vp 放大到 2001。
  assert.deepEqual(imageExportPages(2000.1 * 3, 2856).at(-1), { top: 4001, height: 2000 });
});

test('content just above a screen multiple does not isolate trailing decoration in a tiny page', () => {
  const height = 9 * 2856 + 92;
  const pages = imageExportPages(height, 2856);
  assert.equal(pages.length, 10);
  assert.deepEqual(pages.map(page => page.height), [2580, 2580, 2580, 2580, 2580, 2580, 2579, 2579, 2579, 2579]);
  assert.equal(pages[0].top, 0);
  for (let i = 1; i < pages.length; i++) {
    assert.equal(pages[i].top, pages[i - 1].top + pages[i - 1].height);
  }
  assert.equal(pages.at(-1)!.top + pages.at(-1)!.height, height);
});

test('all balanced pages keep count, integer dimensions, exact coverage and screen limit', () => {
  for (const screen of [1, 17, 1000.7, 2856]) {
    for (const height of [1, 17, 999.1, 2000, 2857, 25796.4]) {
      const pages = imageExportPages(height, screen);
      assert.equal(pages.length, Math.ceil(Math.ceil(height) / Math.floor(screen)));
      assert.equal(pages[0].top, 0);
      assert.equal(pages.reduce((sum, page) => sum + page.height, 0), Math.ceil(height));
      const heights = pages.map(page => page.height);
      assert.ok(Math.max(...heights) - Math.min(...heights) <= 1);
      for (const page of pages) {
        assert.ok(Number.isInteger(page.height) && Number.isInteger(page.top));
        assert.ok(page.height > 0 && page.height <= Math.floor(screen));
      }
    }
  }
});

test('an exact multiple has no empty trailing page', () => {
  assert.deepEqual(imageExportPages(6000, 2000), [
    { top: 0, height: 2000 }, { top: 2000, height: 2000 }, { top: 4000, height: 2000 },
  ]);
});

test('invalid measurements fail before capture', () => {
  for (const [height, limit] of [[0, 2000], [100, 0], [NaN, 2000], [Infinity, 2000], [100, 0.9]]) {
    assert.throws(() => imageExportPages(height, limit), /图片尺寸无效/);
  }
});
