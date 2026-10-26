const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { actualPage, entryRoot } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');

function timers() {
  let nextId = 1;
  const pending = new Map();
  return {
    pending,
    setTimeout: (callback, delay) => {
      const id = nextId++;
      pending.set(id, { callback, delay });
      return id;
    },
    clearTimeout: id => pending.delete(id),
    flush: () => {
      const callbacks = [...pending.values()];
      pending.clear();
      callbacks.forEach(({ callback }) => callback());
    },
  };
}

function block(kind) {
  const clock = timers(), calls = [], links = [], images = [], deleted = [], saved = [], released = [];
  const isMath = kind === 'math';
  const file = `components/${isMath ? 'MathRichBlock' : 'MermaidBlock'}.ets`;
  const source = fs.readFileSync(path.join(entryRoot, file), 'utf8');
  const names = isMath
    ? ['aboutToAppear', 'aboutToDisappear', 'renderChanged', 'renderNow', 'hostMessage', 'onHostReady', 'onVisibilityChange']
    : ['aboutToAppear', 'aboutToDisappear', 'onSourceChange', 'renderNow', 'onHostMessage', 'onHostReady',
      'onVisibilityChange', 'clearRenderTimer', 'exportImage'];
  const card = actualPage(file, names.filter(name => new RegExp(`^  (?:private )?(?:async )?${name}\\(`, 'm').test(source)), {
    ACCENT: 'accent', INK3: 'muted', LINE: 'line', SURFACE2: 'surface',
    console, setTimeout: clock.setTimeout, clearTimeout: clock.clearTimeout,
    display: { getDefaultDisplaySync: () => ({ width: 360, height: 600 }) }, px2vp: value => value,
    vp2px: value => value,
    imageExportPages: () => [{ top: 0, height: 80 }],
    packPixelMapToCache: async () => '/cache/chart.png',
    saveImageFilesToAlbum: async files => { saved.push([...files]); },
    removeCachedImageFiles: files => { released.push([...files]); },
    promptAction: { showToast: options => { throw new Error(options.message); } },
  });
  Object.assign(card, {
    source: isMath ? '{"kind":"paragraph","inlines":[]}' : 'graph TD; A-->B',
    attached: true, alive: true, visible: false, renderPending: true,
    renderedHeight: 620, revision: 0, renderRevision: 0,
    fontSize: 16, lineHeight: 26, fontColor: 'ink', fontFamily: 'body', headingFamily: 'heading', readingMode: false,
    darkMode: false, renderReady: true, renderTimer: -1, frameTimer: -1, finishFrame: null,
    exporting: false, screenHeightPx: 600, blockId: 'diagram',
    onOpenLink: url => links.push(url), onOpenImage: url => images.push(url),
    onVisibleGrow: () => { card.grows++; }, grows: 0,
    controller: {
      runJavaScript: script => { calls.push(script); return Promise.resolve('0'); },
      deleteJavaScriptRegister: name => { deleted.push(name); },
    },
  });
  card.getUIContext = () => ({ getComponentSnapshot: () => ({
    get: async id => { assert.equal(id, 'diagram'); return { release: async () => { released.push('pixelMap'); } }; },
  }) });
  card.waitRenderFrame = async () => {};
  return { card, calls, clock, links, images, deleted, saved, released };
}

test('offscreen math coalesces source and style updates and only renders the latest state on return', () => {
  const { card, calls } = block('math');
  for (let index = 1; index <= 20; index++) {
    card.source = JSON.stringify({ kind: 'paragraph', inlines: [{ type: 'math', text: `x_${index}` }] });
    card.renderChanged();
  }
  card.fontSize = 20; card.renderChanged();
  assert.equal(calls.length, 0, 'retained offscreen math must not cross the JS bridge');
  card.onVisibilityChange(true);
  assert.equal(calls.length, 1);
  assert.match(calls[0], /x_20/);
  assert.match(calls[0], /"size":20/);
  assert.match(calls[0], /, 21\)$/);
  card.onVisibilityChange(false); card.onVisibilityChange(true);
  assert.equal(calls.length, 1, 'a clean re-entry must not render again');
});

test('math waits for both visibility and host readiness and ignores callbacks after destruction', () => {
  const { card, calls, deleted } = block('math');
  card.attached = false;
  card.onHostReady();
  assert.equal(calls.length, 0);
  card.onVisibilityChange(true);
  assert.equal(calls.length, 1);
  card.aboutToDisappear();
  card.onHostReady(); card.onVisibilityChange(true); card.renderChanged();
  assert.equal(calls.length, 1);
  assert.deepEqual(deleted, ['AmberMath']);
});

test('current in-flight math measurements and interactions still arrive while offscreen', () => {
  const { card, links, images } = block('math');
  card.revision = 3;
  card.hostMessage(JSON.stringify({ kind: 'measure', revision: 2, height: 200 }));
  assert.equal(card.renderedHeight, 620);
  card.hostMessage(JSON.stringify({ kind: 'measure', revision: 3, height: 200 }));
  card.hostMessage(JSON.stringify({ kind: 'link', revision: 3, url: 'https://example.com' }));
  card.hostMessage(JSON.stringify({ kind: 'image', revision: 3, url: 'cached:image' }));
  assert.equal(card.renderedHeight, 200);
  assert.equal(card.grows, 1);
  assert.deepEqual(links, ['https://example.com']);
  assert.deepEqual(images, ['cached:image']);
});

test('offscreen Mermaid updates preserve the measured height and render only the newest revision on return', () => {
  const { card, calls, clock } = block('mermaid');
  for (let index = 1; index <= 20; index++) {
    card.source = `graph TD; A-->B${index}`;
    card.onSourceChange();
  }
  card.darkMode = true; card.onSourceChange();
  clock.flush();
  assert.equal(calls.length, 0, 'retained offscreen diagrams must not render');
  assert.equal(clock.pending.size, 0);
  assert.equal(card.renderReady, false);
  assert.equal(card.renderedHeight, 620, 'source updates must not reset a measured chart to 300');
  card.onVisibilityChange(true);
  assert.equal(calls.length, 1);
  assert.match(calls[0], /B20/);
  assert.match(calls[0], /, 21, "dark"\)/);
  card.onVisibilityChange(false); card.onVisibilityChange(true);
  assert.equal(calls.length, 1);
});

test('Mermaid host readiness consumes a pending debounce once and hiding cancels content timers', () => {
  const { card, calls, clock } = block('mermaid');
  card.visible = true;
  card.onSourceChange();
  assert.equal(clock.pending.size, 1);
  card.onHostReady(); clock.flush();
  assert.equal(calls.length, 1, 'host ready and debounce must not submit the same revision twice');
  card.source = 'graph TD; latest-->diagram'; card.onSourceChange();
  card.onVisibilityChange(false); clock.flush();
  assert.equal(calls.length, 1);
  assert.equal(clock.pending.size, 0);
  card.onVisibilityChange(true);
  assert.equal(calls.length, 2);
  assert.match(calls[1], /latest/);
});

test('Mermaid accepts the current measured height offscreen and rejects stale revisions', () => {
  const { card } = block('mermaid');
  card.renderRevision = 4;
  card.renderReady = false;
  card.onHostMessage(JSON.stringify({ revision: 3, height: 130, ok: true }));
  assert.equal(card.renderedHeight, 620);
  assert.equal(card.renderReady, false);
  card.onHostMessage(JSON.stringify({ revision: 4, height: 270, ok: true }));
  assert.equal(card.renderedHeight, 270);
  assert.equal(card.renderReady, true);
});

test('Mermaid destruction clears content timers and releases an outstanding export frame wait', () => {
  const { card, calls, clock } = block('mermaid');
  card.visible = true; card.onSourceChange();
  let releasedFrame = 0;
  card.finishFrame = () => { releasedFrame++; };
  card.frameTimer = clock.setTimeout(() => {}, 32);
  card.aboutToDisappear(); clock.flush();
  card.onHostReady(); card.onVisibilityChange(true); card.onSourceChange(); clock.flush();
  assert.equal(calls.length, 0);
  assert.equal(releasedFrame, 1);
  assert.equal(card.frameTimer, -1);
  assert.equal(clock.pending.size, 0);
});

test('a ready offscreen Mermaid can still export without passing through content visibility gating', async () => {
  const { card, calls, saved, released } = block('mermaid');
  await card.exportImage();
  assert.deepEqual(saved, [['/cache/chart.png']]);
  assert.ok(calls.some(script => script === 'window.__amberSetExportOffset(0)'));
  assert.ok(calls.some(script => script === 'window.__amberEndExport()'));
  assert.ok(released.includes('pixelMap'));
  assert.equal(card.exporting, false);
});
