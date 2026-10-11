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

test('only a rendered 3D canvas activates; SVG and streaming placeholders remain passive', () => {
  const activated = [];
  const preview = actualPage('components/VisualArtifactPreview.ets',
    ['isPassive', 'activateInteraction'], { activateArtifactInteraction: (...args) => activated.push(args) });
  Object.assign(preview, { renderer: 'svg', hasContent: true, interactiveReady: false,
    canvasArea: area, interactionOwner: 'demo' });
  preview.activateInteraction();
  assert.equal(preview.isPassive(), true);
  assert.equal(activated.length, 0);
  preview.renderer = 'threejs';
  preview.hasContent = false;
  preview.activateInteraction();
  assert.equal(activated.length, 0);
  preview.hasContent = true;
  preview.activateInteraction();
  assert.equal(activated.length, 0);
  preview.interactiveReady = true;
  preview.activateInteraction();
  assert.equal(activated.length, 1);
});

test('automatic timeline following pauses while a canvas owns gestures and resumes after exit', () => {
  const allowed = [];
  class Follow {
    constructor(_ui, _scroller, canFollow) { this.canFollow = canFollow; }
    request() { allowed.push(this.canFollow()); }
  }
  const page = actualPage('pages/ChatPage.ets', ['requestNativeFollow'], {
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
