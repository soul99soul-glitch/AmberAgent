const { test } = require('node:test');
const assert = require('node:assert/strict');
const { actualPage } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');
function fixture() {
  const model = { id: 'model', modelId: 'api-id', displayName: 'Saved', type: 'chat',
    customHeaders: [], customBodies: [{ key: 'old', value: true }], inputModalities: ['text'],
    outputModalities: ['text'], abilities: ['tool'], tools: ['search'], contextWindowTokens: null,
    providerOverwrite: { id: 'override', name: 'Override', type: 'google' } };
  let providers = [{ id: 'provider', name: 'Saved provider', type: 'google', models: [model] }];
  let loads = 0, writes = 0, backs = 0, fail = false, release;
  const page = actualPage('pages/ChatModelParamsPage.ets', ['confirm', 'removeOverwrite', 'rebuildProvider',
    'draftInputModalities', 'draftContextWindow', 'setBodyText', 'updateToolProtocol'], {
    getChatKvStore: () => ({}), loadProviders: async () => { loads++; return providers; },
    saveProviders: async (_store, next) => { writes++; if (release) await release;
      if (fail) throw Error('disk unavailable'); providers = next; }, router: { back: () => backs++ } });
  Object.assign(page, { provider: JSON.parse(JSON.stringify(providers[0])), modelRowId: 'model', saving: false,
    error: '', advancedExpanded: false, headerNames: [' X-Draft '], headerValues: [' draft '],
    bodyKeys: ['draft'], bodyTexts: ['true'], bodyValues: [true], bodyErrors: [''],
    inputText: true, inputImage: false, inputAudio: false, contextWindowText: '4096', modelType: 'chat',
    toolCallingEnabled: false, toolSearch: false, toolUrlContext: true, toolImageGen: false,
    hasOverwrite: true, overwriteName: 'Override' });
  return { page, get providers() { return providers; }, set providers(value) { providers = value; },
    get loads() { return loads; }, get writes() { return writes; }, get backs() { return backs; },
    fail() { fail = true; }, defer(promise) { release = promise; } };
}

test('model save preserves latest catalog changes, blocks invalid drafts, and reports failed writes without leaving', async () => {
  const f = fixture();
  f.page.setBodyText(0, '{broken');
  await f.page.confirm();
  assert.equal(f.loads, 0); assert.equal(f.backs, 0); assert.match(f.page.error, /JSON/);
  assert.equal(f.page.advancedExpanded, true);
  f.page.setBodyText(0, 'false'); f.page.contextWindowText = '1.5';
  await f.page.confirm(); assert.equal(f.loads, 0); assert.match(f.page.error, /整数/);
  f.page.contextWindowText = '4096';
  f.providers[0].name = 'Latest provider'; f.providers[0].models[0].displayName = 'Renamed elsewhere';
  f.providers[0].models[0].providerOverwrite.name = 'Latest override';
  f.providers[0].models.push({ id: 'added-elsewhere', displayName: 'Keep me' });
  let resolve;
  f.defer(new Promise(done => { resolve = done; }));
  const pending = f.page.confirm(); await Promise.resolve(); await f.page.confirm();
  assert.equal(f.writes, 1); assert.equal(f.page.saving, true); resolve(); await pending;
  assert.equal(f.backs, 1); assert.equal(f.page.saving, false);
  assert.equal(f.providers[0].name, 'Latest provider'); assert.equal(f.providers[0].models.length, 2);
  const saved = f.providers[0].models[0];
  assert.equal(saved.displayName, 'Renamed elsewhere'); assert.equal(saved.providerOverwrite.name, 'Latest override');
  assert.deepEqual(saved.customHeaders, [{ name: 'X-Draft', value: 'draft' }]);
  assert.deepEqual(saved.customBodies, [{ key: 'draft', value: false }]); assert.deepEqual(saved.tools, ['url_context']);
  assert.deepEqual(saved.abilities, []); assert.equal(saved.contextWindowTokens, 4096);
  f.fail(); await f.page.confirm(); assert.equal(f.backs, 1); assert.match(f.page.error, /disk unavailable/);
  f.providers[0].models = []; const before = f.writes;
  await f.page.confirm(); assert.equal(f.writes, before); assert.equal(f.backs, 1); assert.match(f.page.error, /已被删除/);
});

test('immediate override removal keeps draft fields pending and latest models intact, including failures and deletion', async () => {
  const f = fixture(); f.providers[0].models[0].customBodies = [{ key: 'external', value: 9 }];
  f.providers[0].models.push({ id: 'added-elsewhere' });
  f.fail(); await f.page.removeOverwrite();
  assert.equal(f.page.hasOverwrite, true); assert.match(f.page.error, /disk unavailable/); assert.equal(f.backs, 0);
  const ok = fixture(); ok.providers = f.providers;
  await ok.page.removeOverwrite();
  assert.equal(ok.providers[0].models.length, 2); assert.equal(ok.providers[0].models[0].providerOverwrite, null);
  assert.deepEqual(ok.providers[0].models[0].customBodies, [{ key: 'external', value: 9 }]);
  assert.deepEqual(ok.page.bodyKeys, ['draft']); assert.equal(ok.page.hasOverwrite, false); assert.equal(ok.backs, 0);
  ok.providers[0].models = []; const before = ok.writes;
  await ok.page.removeOverwrite(); assert.equal(ok.writes, before); assert.match(ok.page.error, /已被删除/);
});


test('DeepRead disclosure toggles animate both directions and settle immediately under motion policy or saving', () => {
  const cases = [
    ['ChatProviderSettingsPage', 'toggleUnconfigured', 'unconfiguredExpanded', { loaded: true }, { loaded: false }],
    ['ChatProviderDetailPage', 'toggleReadingAdvanced', 'advancedOpen', { ioBusy: false }, { ioBusy: true }],
    ['ChatModelParamsPage', 'toggleReadingAdvanced', 'advancedExpanded', { saving: false }, { saving: true }],
  ];
  for (const [name, method, state, ready, blocked] of cases) {
    const animations = [];
    const page = actualPage(`pages/${name}.ets`, [method], { Curve: { EaseOut: 'ease-out' } });
    page.getUIContext = () => ({ animateTo: (options, change) => { animations.push(options); change(); } });
    Object.assign(page, ready, { [state]: false, reduceMotion: false, appBackgrounded: false });
    page[method](); assert.equal(page[state], true, name);
    page[method](); assert.equal(page[state], false, name);
    assert.deepEqual(animations.map(options => options.duration), [220, 220], name);
    for (const policy of ['reduceMotion', 'appBackgrounded']) {
      page[policy] = true; page[method](); assert.equal(page[state], true, name);
      page[method](); assert.equal(page[state], false, name); page[policy] = false;
    }
    assert.equal(animations.length, 2, name);
    Object.assign(page, blocked); page[method](); assert.equal(page[state], false, name);
    assert.equal(animations.length, 2, name);
  }
});

test('DeepRead login overlays honor reduced motion without changing the host overlay contract', () => {
  let product = 'deepread';
  const page = actualPage('pages/ChatProviderDetailPage.ets', ['loginOverlayDuration', 'loginOverlayScale'], {
    getProductKind: () => product, MOTION_OVERLAY: 200,
  });
  Object.assign(page, { reduceMotion: false, appBackgrounded: false });
  assert.equal(page.loginOverlayDuration(), 200); assert.equal(page.loginOverlayScale(), 0.96);
  for (const policy of ['reduceMotion', 'appBackgrounded']) {
    page[policy] = true;
    assert.equal(page.loginOverlayDuration(), 0); assert.equal(page.loginOverlayScale(), 1);
    product = 'amber';
    assert.equal(page.loginOverlayDuration(), 200); assert.equal(page.loginOverlayScale(), 0.96);
    product = 'deepread'; page[policy] = false;
  }
});


test('DeepRead overlay policy retains host transitions, uses short opacity for reduced motion, and no transition in background', () => {
  let product = 'deepread';
  const effect = (kind, value) => ({ kind, value,
    animation(options) { return { kind, value, options }; },
    combine(other) { return effect(kind + '+' + other.kind, value); },
  });
  const TransitionEffect = {
    IDENTITY: effect('identity'), OPACITY: effect('opacity'),
    translate: value => effect('translate', value), scale: value => effect('scale', value),
  };
  for (const [file, method, kind, value] of [
    ['pages/BoardPage.ets', 'topicSheetTransition', 'translate', { y: 160 }],
    ['pages/SettingDeepReadPage.ets', 'modelSheetTransition', 'translate', { y: 160 }],
    ['components/ProviderTemplatePickerSheet.ets', 'pickerTransition', 'translate+opacity', { y: 80 }],
    ['components/DeepReadConfirmationDialog.ets', 'dialogTransition', 'scale', { x: 0.96, y: 0.96 }],
  ]) {
    const page = actualPage(file, [method], { getProductKind: () => product,
      TransitionEffect, MOTION_OVERLAY: 200, Curve: { EaseOut: 'ease-out' } });
    Object.assign(page, { reduceMotion: false, appBackgrounded: false });
    assert.deepEqual(page[method](true), { kind, value, options: { duration: 200, curve: 'ease-out' } }, file);
    page.reduceMotion = true;
    for (const card of [false, true]) {
      assert.deepEqual(page[method](card), { kind: 'opacity', value: undefined,
        options: { duration: 120, curve: 'ease-out' } }, file);
    }
    page.appBackgrounded = true;
    assert.equal(page[method](true), TransitionEffect.IDENTITY, file);
    assert.equal(page[method](false), TransitionEffect.IDENTITY, file);
    product = 'amber';
    assert.deepEqual(page[method](true), { kind, value, options: { duration: 200, curve: 'ease-out' } }, file);
    assert.equal(page[method](false).kind, 'opacity', file); product = 'deepread';
  }
  const board = actualPage('pages/BoardPage.ets', ['topicSheetDuration'], {
    getProductKind: () => product,
  });
  Object.assign(board, { reduceMotion: false, appBackgrounded: false });
  assert.equal(board.topicSheetDuration(240), 240);
  board.reduceMotion = true; assert.equal(board.topicSheetDuration(240), 120);
  board.appBackgrounded = true; assert.equal(board.topicSheetDuration(240), 0);
  product = 'amber'; assert.equal(board.topicSheetDuration(240), 240);
});
