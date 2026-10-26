const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../../../chat/node_modules/typescript');
const { actualPage } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');

function fixture() {
  const values = new Map(), exports = {};
  const AppStorage = { get: key => values.get(key), setOrCreate: (key, value) => values.set(key, value) };
  const file = path.join(__dirname, '../main/ets/platform_impl/ArtifactInteraction.ets');
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, AppStorage, TouchType: { Down: 0 }, Number });
  return { values, AppStorage, api: exports };
}
const area = { globalPosition: { x: 40, y: 120 }, width: 300, height: 280 };
const down = (x, y) => ({ type: 0, touches: [{ windowX: x, windowY: y }], changedTouches: [] });

test('one canvas owns interaction; inside touches retain it and outside touches release it', () => {
  const f = fixture();
  f.api.activateArtifactInteraction('first', area);
  f.api.activateArtifactInteraction('second', area);
  f.api.clearArtifactInteraction('first');
  assert.equal(f.values.get('activeArtifactInteraction'), 'second');
  f.api.clearArtifactInteractionOutside(down(180, 220));
  assert.equal(f.values.get('activeArtifactInteraction'), 'second');
  f.api.clearArtifactInteractionOutside(down(180, 410));
  assert.equal(f.values.get('activeArtifactInteraction'), '');
});

test('focus bounds update while moving; invalid bounds never lock chat scrolling', () => {
  const f = fixture();
  f.api.activateArtifactInteraction('canvas', area);
  f.api.activateArtifactInteraction('canvas', { ...area, globalPosition: { x: 40, y: 160 } });
  f.api.clearArtifactInteractionOutside(down(80, 140));
  assert.equal(f.values.get('activeArtifactInteraction'), '');
  f.api.activateArtifactInteraction('invalid', { ...area, width: NaN });
  assert.equal(f.values.get('activeArtifactInteraction'), '');
});

test('a native 3D cover activates its live canvas; SVG and empty scenes remain passive', async () => {
  const activated = [];
  const preview = actualPage('components/VisualArtifactPreview.ets',
    ['isPassive', 'activateInteraction'], { activateArtifactInteraction: (...args) => activated.push(args),
      util: { generateRandomUUID: () => 'new-owner' } });
  Object.assign(preview, { renderer: 'svg', hasContent: true, interactiveReady: false, scene: null,
    canvasArea: area, alive: true, visible: true, backgrounded: false, coverSource: 'scene',
    interactionOwner: 'demo', isInteracting: () => false,
    loadCover: async () => {}, mountsThree: () => false });
  await preview.activateInteraction();
  assert.equal(preview.isPassive(), true);
  assert.equal(activated.length, 0);
  preview.renderer = 'threejs';
  await preview.activateInteraction();
  assert.equal(activated.length, 0);
  preview.scene = { objects: [{ id: 'cube' }] };
  await preview.activateInteraction();
  assert.equal(activated.length, 1);
  let finishLoading;
  preview.loadCover = () => new Promise(resolve => { finishLoading = resolve; });
  const pending = preview.activateInteraction();
  preview.renderer = 'svg'; preview.coverSource = '';
  finishLoading(); await pending;
  assert.equal(activated.length, 1, 'a delayed 3D click cannot lock scrolling after the card changes renderer');
});

test('exiting a 3D canvas detaches its Web host instead of retaining an inactive context', () => {
  const scripts = [];
  let detached = 0;
  const preview = actualPage('components/VisualArtifactPreview.ets', ['syncInteraction'], {});
  Object.assign(preview, { alive: true, attached: true, renderer: 'threejs', interactiveReady: true,
    isInteracting: () => false, mountsThree: () => false, syncPreview() {}, detachHost: () => { detached++; },
    controller: { runJavaScript: script => { scripts.push(script); return Promise.resolve(); } } });
  preview.syncInteraction();
  assert.equal(detached, 1);
  assert.equal(preview.interactiveReady, false);
  assert.equal(scripts.length, 0);
});

test('passive previews capture a real frame and camera without requiring interaction; stale replies are ignored', async () => {
  for (const quoted of [true, false]) {
    let resolve;
    let calls = 0;
    const saved = [];
    const preview = actualPage('components/VisualArtifactPreview.ets', ['captureThreeSnapshot'], {
      writeThreePreview: async (...args) => { saved.push(args); },
    });
    Object.assign(preview, { alive: true, revision: 4, complete: false, snapshotRevision: -1,
      coverKey: 'scene-key', threeSnapshot: '', syncInteraction() {}, releasePreview() {}, isInteracting: () => false,
      controller: { runJavaScript: () => {
        calls++;
        return new Promise(done => { resolve = done; });
      } } });
    preview.captureThreeSnapshot(4);
    preview.captureThreeSnapshot(4);
    assert.equal(calls, 1);
    const png = 'data:image/png;base64,current';
    const frame = JSON.stringify({ snapshot: png, view: 'camera' });
    resolve(quoted ? JSON.stringify(frame) : frame);
    await new Promise(done => setImmediate(done));
    assert.equal(preview.threeSnapshot, png);
    assert.equal(preview.cameraView, 'camera');
    assert.deepEqual(saved, [['scene-key', png, 'camera']]);
    preview.revision = 5;
    preview.captureThreeSnapshot(5);
    preview.revision = 6;
    resolve(JSON.stringify(JSON.stringify({ snapshot: 'data:image/png;base64,stale', view: 'stale' })));
    await new Promise(done => setImmediate(done));
    assert.equal(preview.threeSnapshot, png);
  }
});

test('a detached Web cannot clear a new interaction when its pending render rejects', async () => {
  let rejectRender;
  const cleared = [];
  const preview = actualPage('components/VisualArtifactPreview.ets', ['renderNow', 'detachHost'], {
    widgetDocument: () => '', sanitizeWidgetHtml: text => text,
    clearArtifactInteraction: owner => cleared.push(owner), clearTimeout,
  });
  Object.assign(preview, { alive: true, attached: true, revision: 7, timer: -1,
    renderer: 'threejs', html: '', strictSvg: false, scene: { objects: [] },
    darkMode: false, complete: true, interactiveReady: true, interactionOwner: 'owner',
    isInteracting: () => true, mountsThree: () => true, loadCover: async () => {}, motion: { cancel() {} }, renderError: '',
    controller: { deleteJavaScriptRegister() {}, runJavaScript: script => {
      if (script.includes('AmberVisual.update')) return new Promise((_resolve, reject) => { rejectRender = reject; });
      return Promise.resolve();
    } } });
  await preview.renderNow();
  preview.detachHost(true);
  preview.attached = true;
  rejectRender(new Error('old Web has been removed'));
  await new Promise(done => setImmediate(done));
  assert.deepEqual(cleared, []);
  assert.equal(preview.renderError, '');
});

test('automatic timeline following pauses while a canvas owns gestures and resumes after exit', () => {
  const allowed = [];
  class Follow {
    constructor(_ui, _scroller, canFollow) { this.canFollow = canFollow; }
    request() { allowed.push(this.canFollow()); }
  }
  const page = actualPage('platform_impl/chat/ChatTimelineSession.ets', ['requestNativeFollow'], {
    NativeTimelineFollow: Follow, shouldScrollOnStreamUpdate: () => true,
  });
  Object.assign(page, { nativeFollow: null, getUIContext: () => ({}), scroller: {},
    pageAlive: true, pageVisible: true, listReady: true, enableAutoScroll: true,
    userTouchActive: false, initialJumpSuppressedUntilUserScroll: false,
    activeArtifactInteraction: '', followMode: 'following', atBottomApprox: true });
  page.requestNativeFollow();
  page.activeArtifactInteraction = 'canvas';
  page.requestNativeFollow();
  page.activeArtifactInteraction = '';
  page.requestNativeFollow();
  assert.deepEqual(allowed, [true, false, true]);
});
