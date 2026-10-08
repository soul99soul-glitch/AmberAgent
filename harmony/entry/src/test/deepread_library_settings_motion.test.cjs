const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { actualPage, loadPureModule, domainRoot } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');
const { DEEPREAD_DISCOVERY_SOURCES } = loadPureModule(path.join(domainRoot, 'discovery.ts'));
const Curve = { Linear: 'linear', EaseOut: 'ease-out', EaseInOut: 'ease-in-out' };
const curves = { springMotion: (response, damping) => ({ response, damping }) };
function timers() {
  let id = 0; const pending = new Map();
  return { pending, setTimeout: fn => { pending.set(++id, fn); return id; }, clearTimeout: id => pending.delete(id),
    next() { const [id, fn] = pending.entries().next().value; pending.delete(id); fn(); } };
}
function ui() {
  const animations = [];
  return { animations, context: { animateTo(options, apply) { animations.push(options); apply(); } } };
}

test('library running-dot pulse stops on hidden page, clipped card, background, reduced motion and disposal', () => {
  const clock = timers(), animation = ui();
  const card = actualPage('components/deepread/DeepReadLibraryCard.ets',
    ['aboutToAppear', 'aboutToDisappear', 'stopPulse', 'syncPulse', 'pulse', 'changeVisibility'], { ...clock, Curve });
  Object.assign(card, { alive: false, active: true, inViewport: false, appBackgrounded: false, reduceMotion: false,
    statusKind: 'running', fullyVisible: true, dotOpacity: 1, pulseTimer: -1, getUIContext: () => animation.context });
  card.aboutToAppear(); assert.equal(clock.pending.size, 0);
  card.changeVisibility(1); assert.equal(clock.pending.size, 1); assert.equal(card.dotOpacity, 0.25);
  clock.next(); assert.equal(card.dotOpacity, 1); assert.equal(clock.pending.size, 1);
  card.changeVisibility(0.5); assert.equal(card.fullyVisible, false);
  card.changeVisibility(0); assert.equal(clock.pending.size, 0); assert.equal(card.dotOpacity, 1);
  card.changeVisibility(1);
  for (const field of ['active', 'appBackgrounded', 'reduceMotion']) {
    card[field] = field !== 'active'; card.syncPulse();
    assert.equal(clock.pending.size, 0, field); assert.equal(card.dotOpacity, 1, field);
    card[field] = field === 'active'; card.syncPulse(); assert.equal(clock.pending.size, 1);
  }
  const late = clock.pending.values().next().value;
  card.aboutToDisappear(); late(); assert.equal(clock.pending.size, 0);
});

test('saved icon feedback has one finite bounce and cancels on reduce-motion or disposal', () => {
  const animation = ui(), clock = timers();
  const button = actualPage('components/deepread/DeepReadSettingsVisual.ets',
    ['aboutToAppear', 'aboutToDisappear', 'savedChanged', 'stopBounce'], { curves, ...clock });
  Object.assign(button, { alive: false, success: false, reduceMotion: false, appBackgrounded: false, iconScale: 1,
    bounceTimer: -1, getUIContext: () => animation.context });
  button.aboutToAppear(); button.success = true; button.savedChanged();
  assert.equal(button.iconScale, 1.25); assert.equal(clock.pending.size, 1);
  clock.next(); assert.equal(button.iconScale, 1); assert.equal(clock.pending.size, 0);
  button.savedChanged(); button.reduceMotion = true; button.stopBounce();
  assert.equal(clock.pending.size, 0); button.savedChanged(); assert.equal(clock.pending.size, 0);
  button.reduceMotion = false; button.savedChanged(); const late = clock.pending.values().next().value;
  button.aboutToDisappear(); const count = animation.animations.length; late();
  assert.equal(animation.animations.length, count); assert.equal(clock.pending.size, 0);
});

test('composer insertion/removal keeps actual inputs and reduced motion removes spatial animation', () => {
  const animation = ui();
  const sheet = actualPage('components/deepread/DeepReadComposerSheet.ets', ['appendFile', 'removeFile'],
    { Curve, curves, getProductKind: () => 'deepread', DeepReadHaptics: { selection() {} } });
  Object.assign(sheet, { files: [], busy: false, pendingTopicId: '', reduceMotion: false, getUIContext: () => animation.context });
  const source = { id: 'file', title: '正文', content: '真实内容' }; sheet.appendFile(source);
  assert.equal(sheet.files[0], source);
  sheet.reduceMotion = true; sheet.removeFile('file'); assert.equal(sheet.files.length, 0);
  assert.equal(animation.animations[1].duration, 0); assert.equal(animation.animations[1].curve, 'linear');
  sheet.appendFile(source); sheet.pendingTopicId = 'saved'; sheet.removeFile('file'); assert.equal(sheet.files.length, 1);
});

function discovery(storage, product = 'deepread') {
  const page = actualPage('pages/DeepReadDiscoverySettingsPage.ets', ['changeSource', 'persistPreference', 'loadSettings', 'aboutToDisappear'],
    { getAppContainer: () => ({ storage }), getProductKind: () => product, DEEPREAD_DISCOVERY_SOURCES, DeepReadHaptics: { selection() {} } });
  Object.assign(page, { alive: true, loadToken: 1, sources: [{ id: 'hacker_news', displayName: 'Hacker News', category: 'AI · 英文源', enabled: true }],
    settingsLoaded: true, loading: false, saving: false, error: '', notice: '', pendingWrites: 0, writeFailure: '', writeQueue: Promise.resolve() });
  return page;
}

test('independent source edits persist in event order and continue after immediate Back', async () => {
  const values = new Map(), writes = []; let completeFirst;
  const storage = { set: async (key, value) => {
    if (writes.length === 0) { writes.push([key, value]); await new Promise(resolve => { completeFirst = resolve; }); }
    else writes.push([key, value]); values.set(key, value);
  } };
  const page = discovery(storage); page.changeSource('hacker_news'); page.changeSource('hacker_news');
  await Promise.resolve(); assert.equal(writes.length, 1);
  page.aboutToDisappear(); completeFirst(); await page.writeQueue;
  assert.deepEqual(writes, [['source_hacker_news', 'false'], ['source_hacker_news', 'true']]);
  assert.equal(values.get('source_hacker_news'), 'true'); assert.equal(page.pendingWrites, 0);
});

test('failed instant source write reloads the actual preference and shows the storage error', async () => {
  const storage = { get: async (_key, fallback) => fallback, set: async () => { throw Error('disk'); } };
  const page = discovery(storage); page.changeSource('hacker_news'); await page.writeQueue;
  assert.equal(page.sources.find(item => item.id === 'hacker_news').enabled, true);
  assert.match(page.error, /保存发现设置失败.*disk/); assert.equal(page.saving, false);
});

test('host discovery source edits retain the original explicit-save timing', async () => {
  let writes = 0;
  const page = discovery({ set: async () => { writes++; } }, 'agent'); page.changeSource('hacker_news'); await page.writeQueue;
  for (const [key, value] of [['deepread_hotlist_refresh_minutes', 15], ['deepread_hotlist_wifi_only', 'true'],
    ['deepread_hotlist_translate_zh', 'true'], ['deepread_focus_keywords', 'AI'], ['deepread_focus_mode', 'focus_only']]) {
    page.persistPreference(key, value);
  }
  await page.writeQueue; assert.equal(writes, 0); assert.equal(page.sources[0].enabled, false);
});
