const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');

function productionClass(relative, name, env) {
  const source = fs.readFileSync(path.join(__dirname, '../main/ets', relative), 'utf8');
  const body = source.slice(source.indexOf('export class ')).replace('export class ', 'class ');
  const code = ts.transpileModule(body + '\nreturn ' + name + ';',
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  return new Function(...Object.keys(env), code)(...Object.values(env));
}

function fixture(api = 23, supported = true) {
  const state = new Map(); let reduced = false; let observer; let reads = 0;
  const removed = []; const registrations = [];
  const env = {
    AppStorage: { setOrCreate: (key, value) => state.set(key, value) },
    deviceInfo: { sdkApiVersion: api }, canIUse: () => supported,
    hilog: { warn() {} },
    accessibility: {
      isAnimationReduceEnabledSync() { reads++; return reduced; },
      onAnimationReduceStateChange(callback) { observer = callback; registrations.push(callback); },
      offAnimationReduceStateChange(callback) { removed.push(callback); },
    },
  };
  const Preferences = productionClass('platform_impl/DeepReadMotionPreferences.ets', 'DeepReadMotionPreferences', env);
  return { preferences: new Preferences(), state, removed, registrations,
    emit(value) { observer(value); }, set(value) { reduced = value; },
    get reads() { return reads; }, get observer() { return observer; }, env };
}

test('API 12 and absent accessibility capability never call newer system APIs', () => {
  for (const [api, supported] of [[12, true], [22, true], [23, false]]) {
    const f = fixture(api, supported); f.preferences.start(); f.preferences.refresh(); f.preferences.stop();
    assert.equal(f.state.get('reduceMotion'), false);
    assert.equal(f.reads, 0); assert.equal(f.registrations.length, 0); assert.equal(f.removed.length, 0);
  }
});

test('system changes update UI state without a page remount; foreground refresh rereads the setting', () => {
  const f = fixture(); f.set(true); f.preferences.start(); f.preferences.start();
  assert.equal(f.state.get('reduceMotion'), true); assert.equal(f.registrations.length, 1);
  f.emit(false); assert.equal(f.state.get('reduceMotion'), false);
  f.set(true); f.preferences.refresh(); assert.equal(f.state.get('reduceMotion'), true);
  assert.equal(f.reads, 2);
});

test('ability destruction removes only its own observer and ignores a queued callback', () => {
  const f = fixture(); f.preferences.start(); const queued = f.observer;
  f.preferences.stop(); f.preferences.stop(); queued(true); f.preferences.refresh();
  assert.equal(f.removed.length, 1); assert.equal(f.removed[0], queued);
  assert.equal(f.state.get('reduceMotion'), false); assert.equal(f.reads, 1);
});

test('a failed setting read keeps the last value and a failed subscription is not falsely removed', () => {
  const f = fixture(); f.preferences.start(); f.emit(true);
  f.env.accessibility.isAnimationReduceEnabledSync = () => { throw Error('unavailable'); };
  f.preferences.refresh(); assert.equal(f.state.get('reduceMotion'), true); f.preferences.stop();
  const failed = fixture(); failed.env.accessibility.onAnimationReduceStateChange = () => { throw Error('denied'); };
  assert.doesNotThrow(() => failed.preferences.start()); failed.preferences.stop();
  assert.equal(failed.removed.length, 0);
});

test('touch haptics are optional and cannot block actions on the host, background or unsupported hardware', async () => {
  let product = 'deepread', background = false, supported = true; const calls = [];
  const Haptics = productionClass('design/DeepReadHaptics.ets', 'DeepReadHaptics', {
    getProductKind: () => product, AppStorage: { get: () => background }, canIUse: () => supported,
    vibrator: { startVibration(effect, attribute) { calls.push([effect, attribute]); return Promise.resolve(); } },
  });
  Haptics.selection(); Haptics.success(); Haptics.impact();
  assert.equal(calls.length, 3); assert.ok(calls.every(([, attr]) => attr.usage === 'touch'));
  product = 'agent'; Haptics.selection(); product = 'deepread'; background = true; Haptics.success();
  background = false; supported = false; Haptics.impact(); assert.equal(calls.length, 3);
  const Broken = productionClass('design/DeepReadHaptics.ets', 'DeepReadHaptics', {
    getProductKind: () => 'deepread', AppStorage: { get: () => false }, canIUse: () => true,
    vibrator: { startVibration() { throw Error('no motor'); } },
  });
  assert.doesNotThrow(() => Broken.selection());
});

function paperFixture() {
  const source = fs.readFileSync(path.join(__dirname, '../main/ets/components/deepread/DeepReadPaperBackground.ets'), 'utf8');
  const members = source.slice(source.indexOf('export struct DeepReadPaperBackground {')
    + 'export struct DeepReadPaperBackground {'.length, source.indexOf('\n  build():'))
    .replace(/@StorageProp\('[^']+'\)\s*/g, '').replace(/@(?:Prop|State)\s*/g, '');
  const code = ts.transpileModule('class Paper {' + members + '\n}\nreturn Paper;',
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const pending = new Map(), animations = []; let timerId = 0;
  const Paper = new Function('Date', 'Curve', 'setTimeout', 'clearTimeout', code)(
    class { getHours() { return 23; } }, { EaseIn: 'in', EaseOut: 'out' },
    (callback, delay) => { pending.set(++timerId, { callback, delay }); return timerId; },
    id => pending.delete(id));
  const paper = new Paper();
  Object.assign(paper, { nightReading: true, reduceMotion: false,
    getUIContext: () => ({ animateTo(options, update) { animations.push(options); update(); } }) });
  return { paper, pending, animations };
}

test('leaving before the first lamp frame cancels and ignores queued work', () => {
  const f = paperFixture(); f.paper.aboutToAppear();
  const queued = [...f.pending.values()][0].callback; f.paper.aboutToDisappear(); queued();
  assert.equal(f.pending.size, 0); assert.equal(f.animations.length, 0); assert.equal(f.paper.lampOpacity, 0);
});

test('a new DeepRead ability session clears unsubmitted settings without changing another product', () => {
  const source = fs.readFileSync(path.join(__dirname, '../main/ets/entryability/EntryAbility.ets'), 'utf8');
  const method = source.slice(source.indexOf('  onCreate('), source.indexOf('\n  onNewWant('));
  const code = ts.transpileModule('class Ability {' + method + '\n}\nreturn Ability;',
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  for (const product of ['deepread', 'novel', 'agent']) {
    const memory = new Map([['deepreadSettingsSessionDraft', { search: { resultSize: 11 } }]]);
    let starts = 0, routes = 0;
    const env = {
      hilog: { info() {} }, DOMAIN: 0, TAG: 'test', registerAmberFonts() {},
      ConfigurationConstant: { ColorMode: { COLOR_MODE_DARK: 1 } }, ACCENT: '#red',
      AppStorage: { setOrCreate: (key, value) => memory.set(key, value), delete: key => memory.delete(key) },
      productKindForBundle: () => product, getProductKind: () => product,
      DeepReadDesign: { registerFonts() {} }, BuildProfile: { DEBUG: false },
      getSkillManager: () => ({ installBuiltinSkillsIfMissing: () => Promise.resolve() }),
      rescheduleCronTasksOnStartup() {},
    };
    const Ability = new Function(...Object.keys(env), code)(...Object.values(env));
    const ability = new Ability();
    Object.assign(ability, { context: { config: { colorMode: 1 }, applicationInfo: { name: 'bundle' } },
      motionPreferences: { start() { starts++; } }, routeFromWant() { routes++; } });
    ability.onCreate({}, {});
    assert.equal(memory.has('deepreadSettingsSessionDraft'), product !== 'deepread');
    assert.equal(starts, product === 'agent' ? 0 : 1);
    assert.equal(routes, 1);
  }
});
