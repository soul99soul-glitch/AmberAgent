const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../../../chat/node_modules/typescript');
const { actualPage, entryRoot } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');

function fixture() {
  const values = new Map(), exports = {};
  let changed = () => {};
  const AppStorage = { get: key => values.get(key), setOrCreate(key, value) {
    const previous = values.get(key); values.set(key, value);
    if (previous !== value) changed(key, value);
  } };
  const source = fs.readFileSync(path.join(entryRoot, 'components/deepread/DeepReadDockScroll.ets'), 'utf8');
  vm.runInNewContext(ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, AppStorage });
  return { values, AppStorage, scroll: new exports.DeepReadDockScroll(),
    observe: callback => { changed = callback; } };
}

test('list Dock stays expanded across restored positions, scroll direction and background policies', () => {
  const { values, scroll } = fixture();
  scroll.reset();
  for (const [delta, offset, userScroll] of [[720, 720, false], [36, 756, true], [-16, 740, true], [-740, 0, true]]) {
    scroll.update(delta, offset, userScroll);
    assert.equal(values.get('deepreadDockCollapsed'), false, 'scroll never steals navigation area from the list');
  }
  values.set('deepreadDockCollapsed', true);
  scroll.update(0, 720, false);
  assert.equal(values.get('deepreadDockCollapsed'), false, 'a retained older page cannot restore the removed compact mode');
  for (const policy of ['reduceMotion', 'appBackgrounded']) {
    values.set(policy, true);
    scroll.update(100, 216, true);
    assert.equal(values.get('deepreadDockCollapsed'), false);
  }
});

test('fixed Dock indicator stops pending motion on reduced motion, background and disposal', () => {
  const f = fixture(), animations = [], cancelled = [];
  const tab = actualPage('components/deepread/DeepReadTabBar.ets',
    ['selectedChanged', 'settleMotion', 'aboutToDisappear'],
    { AppStorage: f.AppStorage, Curve: { EaseInOut: 'ease' }, curves: { springCurve: () => 'spring' }, clearTimeout: id => cancelled.push(id) });
  Object.assign(tab, { alive: true, reduceMotion: false, appBackgrounded: false, selected: 2,
    indicator: 0, indicatorTimer: -1,
    getUIContext: () => ({ animateTo(options, apply) { animations.push(options); apply(); } }) });
  tab.selectedChanged();
  assert.equal(tab.indicator, 2, 'the retained Dock follows its live selection');
  for (const policy of ['reduceMotion', 'appBackgrounded']) {
    tab.indicatorTimer = 7; tab.indicator = 0; tab[policy] = true;
    tab.settleMotion();
    assert.equal(tab.indicator, 2);
    assert.equal(tab.indicatorTimer, -1);
    assert.ok(cancelled.includes(7));
    tab[policy] = false;
  }
  tab.indicatorTimer = 8;
  tab.aboutToDisappear();
  assert.equal(tab.alive, false);
  assert.equal(tab.indicatorTimer, -1);
  assert.ok(cancelled.includes(8));
});
