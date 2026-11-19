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
