const { test } = require('node:test');
const assert = require('node:assert/strict');
const { actualPage } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');

function shellFixture(reduceMotion = false) {
  const storage = new Map(), timers = new Map(), animations = [];
  let nextTimer = 0;
  const root = actualPage('pages/DeepReadRootPage.ets', ['selectTab', 'settleContent', 'layerOpacity', 'motionPolicyChanged'], {
    AppStorage: { setOrCreate: (key, value) => storage.set(key, value) },
    setTimeout: callback => { timers.set(++nextTimer, callback); return nextTimer; },
    clearTimeout: timer => timers.delete(timer), Curve: { EaseInOut: 'ease' },
  });
  Object.assign(root, { selected: 0, outgoing: -1, offsets: [0, 1, 1], visible: true, transitioning: false,
    queuedTab: -1, motionId: 0, startTimer: -1, alive: true, reduceMotion, fade: 1,
    getUIContext: () => ({ animateTo: (options, apply) => { animations.push(options); apply(); } }) });
  function frame() { const pending = [...timers.values()]; timers.clear(); pending.forEach(callback => callback()); }
  return { root, storage, animations, frame };
}

test('retained root uses full-width directional layers and hides the middle root on a two-tab jump', () => {
  const f = shellFixture();
  f.root.selectTab(2);
  assert.deepEqual(f.root.offsets, [0, 1, 1]);
  assert.equal(f.root.layerOpacity(0), 1); assert.equal(f.root.layerOpacity(1), 0); assert.equal(f.root.layerOpacity(2), 1);
  assert.equal(f.storage.get('deepreadActiveTab'), 2);
  f.frame(); assert.equal(f.animations[0].duration, 380);
  assert.deepEqual(f.root.offsets, [-1, -1, 0]);
  f.animations[0].onFinish(); assert.equal(f.root.outgoing, -1);
  f.root.selectTab(0); assert.deepEqual(f.root.offsets, [-1, -1, 0]);
  f.frame(); assert.deepEqual(f.root.offsets, [0, 1, 1]);
});

test('rapid Dock selections queue the latest target and obsolete animation callbacks cannot reopen hidden roots', () => {
  const f = shellFixture();
  f.root.selectTab(2); f.root.selectTab(1); f.root.selectTab(0);
  assert.equal(f.root.selected, 2); assert.equal(f.root.queuedTab, 0);
  f.frame(); const oldFinish = f.animations[0].onFinish;
  oldFinish(); assert.equal(f.root.selected, 0); assert.equal(f.root.transitioning, true);
  f.frame(); oldFinish(); assert.equal(f.root.transitioning, true);
  f.root.appBackgrounded = true; f.root.motionPolicyChanged();
  f.animations[1].onFinish(); assert.equal(f.root.transitioning, false); assert.equal(f.root.outgoing, -1);
  assert.equal(f.root.layerOpacity(0), 1); assert.equal(f.root.layerOpacity(2), 0);
});

test('reduced motion crossfades briefly and hidden layer never becomes readable or clickable', () => {
  const f = shellFixture(true); f.root.selectTab(1);
  assert.equal(f.root.layerOpacity(0), 1); assert.equal(f.root.layerOpacity(1), 0); assert.equal(f.root.layerOpacity(2), 0);
  f.frame(); assert.equal(f.animations[0].duration, 120);
  assert.equal(f.root.layerOpacity(0), 0); assert.equal(f.root.layerOpacity(1), 1);
});

test('subpage Dock returns to existing retained root even when selecting its current tab, with pending-write guard', () => {
  const stored = new Map(), back = [], replacement = [];
  const bound = { getStateByIndex: index => index === 1 ? { name: 'DeepReadRootPage' } : { name: 'ChatProviderSettingsPage' },
    back: options => back.push(options), replaceUrl: options => { replacement.push(options); return Promise.resolve(); } };
  const tab = actualPage('components/deepread/DeepReadTabBar.ets', ['selectTab'], {
    router: { getLength: () => '2' }, H: { selection() {} }, promptAction: { showToast() {} },
    AppStorage: { setOrCreate: (key, value) => stored.set(key, value) },
  });
  Object.assign(tab, { selected: 2, retainedRoot: false, clearHistoryOnSelect: true, navigating: false,
    routes: [0, 1, 2], onBeforeSelect: () => false, getUIContext: () => ({ getRouter: () => bound }) });
  tab.selectTab(2); assert.equal(back.length, 0); assert.equal(tab.navigating, false);
  tab.onBeforeSelect = () => true; tab.selectTab(2);
  assert.deepEqual(back, [{ url: 'pages/DeepReadRootPage' }]); assert.equal(stored.get('deepreadDesiredTab'), 2);
  assert.equal(replacement.length, 0);
});

test('root Dock delegates to retained shell without changing the Router stack', () => {
  const selected = [];
  const tab = actualPage('components/deepread/DeepReadTabBar.ets', ['selectTab'], { H: { selection() {} } });
  Object.assign(tab, { selected: 0, retainedRoot: true, navigating: false, routes: [0, 1, 2],
    onBeforeSelect: () => true, onSelect: index => selected.push(index), getUIContext: () => { throw Error('router must remain untouched'); } });
  tab.selectTab(2); assert.deepEqual(selected, [2]); assert.equal(tab.navigating, false);
});

test('subpage fallback captures its Router, clears stale roots only after replacement and restores tab on rejection', async () => {
  const storage = new Map(), stack = ['pages/BoardPage', 'pages/DeepReadSourcesPage'], notices = [];
  let reject = true, clears = 0, destroyed = false;
  const bound = { getStateByIndex: index => ({ path: stack[index - 1] }),
    replaceUrl: options => {
      assert.deepEqual(options, { url: 'pages/DeepReadRootPage', params: { tab: 1 } });
      if (reject) return Promise.reject(Error('navigation failed'));
      stack[stack.length - 1] = options.url; destroyed = true; return Promise.resolve();
    }, clear: () => { clears++; stack.splice(0, stack.length - 1); } };
  const tab = actualPage('components/deepread/DeepReadTabBar.ets', ['selectTab'], {
    router: { getLength: () => String(stack.length) }, H: { selection() {} },
    AppStorage: { setOrCreate: (key, value) => storage.set(key, value) },
    promptAction: { showToast: options => notices.push(options.message) },
  });
  Object.assign(tab, { selected: 0, retainedRoot: false, clearHistoryOnSelect: true, navigating: false,
    routes: [0, 1, 2], onBeforeSelect: () => true, getUIContext: () => {
      assert.equal(destroyed, false, 'capture the Router before destroying its page'); return { getRouter: () => bound };
    } });
  tab.selectTab(1); await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(stack, ['pages/BoardPage', 'pages/DeepReadSourcesPage']); assert.equal(clears, 0);
  assert.equal(storage.get('deepreadActiveTab'), 0); assert.equal(tab.navigating, false); assert.equal(notices.length, 1);
  reject = false; tab.selectTab(1); assert.equal(clears, 0);
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(stack, ['pages/DeepReadRootPage']); assert.equal(clears, 1);
});
