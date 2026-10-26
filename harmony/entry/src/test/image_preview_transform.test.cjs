const { test } = require('node:test');
const assert = require('node:assert/strict');
const { actualPage } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');

function fixture() {
  const page = actualPage('components/ui/ImagePreviewDialog.ets', [
    'sourceAt', 'resetImageTransform', 'clampImageOffset', 'setViewportSize', 'onImageLoaded', 'imageLoadHandler',
    'startPinch', 'updatePinch', 'startPan', 'updatePan', 'changeImage', 'resetSources',
    'onImagesChanged', 'onShowChanged', 'aboutToAppear',
  ], {});
  Object.assign(page, {
    images: ['wide', 'tall'], sources: ['wide', 'tall'], initialIndex: 0, currentIndex: 0,
    show: true, alive: true, sourceEpoch: 0, imageScale: 1, pinchStartScale: 1,
    imageOffsetX: 0, imageOffsetY: 0, panLastOffsetX: 0, panLastOffsetY: 0,
    viewportWidth: 320, viewportHeight: 600, imageWidth: 0, imageHeight: 0, imageSizeUrl: '',
    resolveSources: async () => {}, resolveCurrent: async () => {},
  });
  return page;
}
const offsets = page => [page.imageOffsetX, page.imageOffsetY];

test('zoomed drag uses contained image bounds and cumulative gesture deltas for landscape and portrait images', () => {
  const wide = fixture();
  wide.onImageLoaded('wide', 1600, 900);
  wide.startPinch(); wide.updatePinch(2); wide.startPan();
  wide.updatePan(100, 100);
  assert.deepEqual(offsets(wide), [100, 0], 'letterbox space is not a draggable image edge');
  wide.updatePan(130, 100);
  assert.deepEqual(offsets(wide), [130, 0], 'the event offset is cumulative, not a per-frame delta');
  wide.updatePan(1000, -1000);
  assert.deepEqual(offsets(wide), [160, 0]);
  wide.updatePan(990, -1000);
  assert.deepEqual(offsets(wide), [150, 0], 'reversing at the image edge moves immediately');
  wide.startPan(); wide.updatePan(-50, 80);
  assert.deepEqual(offsets(wide), [100, 0], 'each new drag starts at the current image offset');

  const tall = fixture();
  tall.onImageLoaded('wide', 600, 1200);
  tall.startPinch(); tall.updatePinch(2); tall.startPan(); tall.updatePan(1000, -1000);
  assert.deepEqual(offsets(tall), [140, -300]);
});

test('zoom reduction and viewport changes constrain offsets; returning to scale one recenters', () => {
  const page = fixture();
  page.onImageLoaded('wide', 320, 600);
  page.startPinch(); page.updatePinch(10); page.startPan(); page.updatePan(1000, 1000);
  assert.equal(page.imageScale, 4);
  assert.deepEqual(offsets(page), [480, 900]);
  page.startPinch(); page.updatePinch(0.5);
  assert.deepEqual(offsets(page), [160, 300]);
  page.setViewportSize(600, 320);
  assert.deepEqual(offsets(page), [0, 160]);
  page.startPinch(); page.updatePinch(0.01);
  assert.equal(page.imageScale, 1);
  assert.deepEqual(offsets(page), [0, 0]);
  page.startPan(); page.updatePan(300, 300);
  assert.deepEqual(offsets(page), [0, 0]);
});

test('switching images rejects old dimensions and resets transforms; replacing images and reopening also reset', () => {
  const page = fixture();
  page.onImageLoaded('wide', 1600, 900);
  const oldImageCompleted = page.imageLoadHandler('wide');
  page.startPinch(); page.updatePinch(3); page.startPan(); page.updatePan(100, 100);
  page.changeImage(1);
  assert.equal(page.currentIndex, 1);
  assert.equal(page.imageScale, 1);
  assert.deepEqual(offsets(page), [0, 0]);
  oldImageCompleted({ width: 1600, height: 900, loadingStatus: 1 });
  assert.equal(page.imageSizeUrl, '');
  page.onImageLoaded('tall', 600, 1200);
  page.startPinch(); page.updatePinch(2); page.startPan(); page.updatePan(100, 100);
  page.images = ['tall', 'replacement']; page.onImagesChanged();
  assert.equal(page.imageScale, 1);
  assert.deepEqual(offsets(page), [0, 0]);
  page.initialIndex = 0; page.onShowChanged();
  page.onImageLoaded('tall', 600, 1200);
  page.startPinch(); page.updatePinch(2); page.startPan(); page.updatePan(100, 100);
  page.show = false; page.onShowChanged();
  assert.equal(page.imageScale, 2, 'hiding leaves the existing lifecycle contract intact');
  page.show = true; page.onShowChanged();
  assert.equal(page.currentIndex, 0);
  assert.equal(page.imageScale, 1);
  assert.deepEqual(offsets(page), [0, 0]);
  assert.equal(page.imageSizeUrl, 'tall', 'unchanged image identity retains known dimensions');
});

test('before an image or viewport is measured the image remains centered', () => {
  const page = fixture();
  page.startPinch(); page.updatePinch(2); page.startPan(); page.updatePan(200, 200);
  assert.deepEqual(offsets(page), [0, 0]);
  page.onImageLoaded('wide', 1600, 900);
  page.setViewportSize(0, 0); page.updatePan(200, 200);
  assert.deepEqual(offsets(page), [0, 0]);
});

test('an ongoing pan does not carry ignored scale-one movement into a subsequent pinch', () => {
  const page = fixture();
  page.onImageLoaded('wide', 1600, 900);
  page.startPan(); page.updatePan(100, 0);
  assert.deepEqual(offsets(page), [0, 0]);
  page.startPinch(); page.updatePinch(2);
  page.updatePan(110, 0);
  assert.deepEqual(offsets(page), [10, 0]);
});
