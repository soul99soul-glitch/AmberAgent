const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const { actualPage } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');

const source = fs.readFileSync(path.join(__dirname, '../main/ets/platform_impl/UIGenerationMotion.ets'), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));

function fixture() {
  const exports = {}, timers = new Map();
  let sequence = 0;
  vm.runInNewContext(ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, {
    exports,
    require: name => name === '@kit.ArkTS'
      ? { util: { TextDecoder: { create: () => ({ decodeToString: () => '' }) } } }
      : { hilog: { warn() {} } },
    AppStorage: { get: key => key === 'darkMode' ? false :
      { resourceManager: { getRawFileContentSync: () => new Uint8Array() } } },
    setTimeout: callback => { timers.set(++sequence, callback); return sequence; },
    clearTimeout: id => timers.delete(id),
  });
  const tick = async () => {
    const callbacks = [...timers.values()]; timers.clear();
    callbacks.forEach(callback => callback()); await flush();
  };
  return { Motion: exports.UIGenerationMotion, timers, tick };
}

test('history is inert, competing surfaces wait, and only a started reveal consumes the key', async () => {
  const { Motion, tick, timers } = fixture();
  const calls = [];
  let status = 'pending';
  const controller = { runJavaScript: script => { calls.push(script); return Promise.resolve(status); } };
  const inline = new Motion(controller), full = new Motion(controller);
  inline.play('app:1', false, false);
  assert.equal(calls.length, 0);
  inline.play('app:1', true, false); await flush();
  full.play('app:1', true, false);
  assert.equal(calls.length, 1);
  inline.cancel(false);
  full.play('app:1', true, false); await flush();
  assert.equal(timers.size, 1);
  status = '"started"'; await tick();
  assert.equal(timers.size, 0);
  full.cancel();
  const count = calls.length;
  new Motion(controller).play('app:1', true, false);
  assert.equal(calls.length, count);
});

test('late replies from a cancelled document cannot consume the new document key', async () => {
  const { Motion } = fixture();
  let resolveOld;
  let starts = 0;
  const controller = { runJavaScript: script => {
    if (script.includes('.play(')) {
      starts++;
      if (starts === 1) return new Promise(resolve => { resolveOld = resolve; });
    }
    return Promise.resolve('pending');
  } };
  const motion = new Motion(controller);
  motion.play('old', true, false);
  motion.cancel();
  motion.play('new', true, false); await flush();
  resolveOld('started'); await flush();
  motion.cancel();
  new Motion(controller).play('new', true, false);
  assert.equal(starts, 3);
});

test('user cancellation consumes the attempt and polling has a finite deadline', async () => {
  const { Motion, tick, timers } = fixture();
  const calls = [];
  const controller = { runJavaScript: script => { calls.push(script); return Promise.resolve('pending'); } };
  const motion = new Motion(controller);
  motion.play('touch', true, false); await flush();
  motion.cancel(true);
  const count = calls.length;
  new Motion(controller).play('touch', true, false);
  assert.equal(calls.length, count);
  motion.play('stalled', true, false); await flush();
  for (let i = 0; i < 50; i++) await tick();
  assert.equal(timers.size, 0);
  assert.ok(calls.at(-1).includes('.cancel()'));
});

test('render confirmation, revision, visibility and compact viewport gate the real preview', () => {
  const plays = [];
  const page = actualPage('components/VisualArtifactPreview.ets', ['requestReveal'], {});
  Object.assign(page, { alive: true, attached: true, visible: true, backgrounded: false,
    hasContent: true, renderError: '', complete: true, renderer: 'html', revision: 3, renderedRevision: 2,
    revealKey: 'widget:message:1:0', revealEligible: true, reduceMotion: false, renderedHeight: 180,
    motion: { play: (...args) => plays.push(args) } });
  page.requestReveal(); assert.equal(plays.length, 0);
  page.renderedRevision = 3; page.requestReveal();
  assert.deepEqual(plays[0], ['widget:message:1:0', true, false, true]);
  page.renderedHeight = 500; page.requestReveal();
  assert.equal(plays[1][2], true);
  page.backgrounded = true; page.requestReveal(); assert.equal(plays.length, 2);
});
