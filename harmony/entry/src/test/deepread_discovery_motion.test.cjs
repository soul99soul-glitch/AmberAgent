const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const root = path.join(__dirname, '../main/ets');
const read = name => fs.readFileSync(path.join(root, name), 'utf8');
const motion = read('components/deepread/DeepReadDiscoveryMotion.ets');
const shake = read('components/deepread/DeepReadDiscoveryShake.ets');
const tab = read('components/deepread/DeepReadTabBar.ets');
const board = read('pages/BoardPage.ets');
const header = read('components/deepread/DeepReadDiscoveryHeader.ets');
const curves = { springCurve: (...values) => values };
const Curve = { EaseOut: 'easeOut', EaseInOut: 'easeInOut' };

// Execute the production controller members, omitting only ArkUI build DSL.
function logic(text, name, dependencies = {}) {
  const start = text.indexOf('struct ' + name + ' {');
  assert.ok(start >= 0, name);
  let end = text.indexOf('\n  @Builder\n', start);
  const build = text.indexOf('\n  build(', start);
  if (end < 0 || (build >= 0 && build < end)) end = build;
  assert.ok(end >= 0, name + ' build boundary');
  const members = text.slice(text.indexOf('{', start) + 1, end)
    .replace(/@StorageProp\('[^']+'\)\s*/g, '').replace(/@Watch\('[^']+'\)\s*/g, '')
    .replace(/@(?:Prop|State|BuilderParam)\s*/g, '');
  return make('class Controller {' + members + '\n}', dependencies);
}
function methods(text, names, dependencies = {}) {
  const members = names.map(name => {
    const found = new RegExp('^  (?:private )?(?:async )?' + name + '\\(', 'm').exec(text);
    assert.ok(found, name);
    let end = text.indexOf('{', found.index) + 1, depth = 1;
    for (; depth; end++) { if (text[end] === '{') depth++; if (text[end] === '}') depth--; }
    return text.slice(found.index, end);
  }).join('\n');
  return make('class Controller {' + members + '\n}', dependencies);
}
function make(code, dependencies) {
  const js = ts.transpileModule(code + '\nreturn Controller;', { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  return new (new Function(...Object.keys(dependencies), js)(...Object.values(dependencies)))();
}
function timers() {
  let id = 0;
  const pending = new Map();
  return { pending, set: (callback, ms) => { pending.set(++id, { callback, ms }); return id; },
    clear: id => pending.delete(id), fire: id => { const value = pending.get(id); pending.delete(id); value?.callback(); } };
}
function storage() {
  const values = new Map();
  return { values, get: key => values.get(key), setOrCreate: (key, value) => values.set(key, value) };
}
function ui() {
  const calls = [];
  return { calls, animateTo: (options, update) => { calls.push(options); update(); } };
}
const detectorJs = ts.transpileModule(shake.slice(shake.indexOf('export class DeepReadShakeDetector'), shake.indexOf('\n@Component'))
  .replace('export class', 'class') + '\nreturn DeepReadShakeDetector;', { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
const Detector = new Function(detectorJs)();
const pick = { title: '无链接热点', rank: 4, url: '', heat: '', seedUrls: [],
  inputSources: [{ url: null, content: '来源：Hacker News\n排名：4\n真实摘要', researchSource: { source: 'Hacker News' } }] };
function shaker(options = {}) {
  const clock = timers(), stored = storage(), context = ui(), sensorCalls = [], feedback = [], warnings = [];
  const component = logic(shake, 'DeepReadDiscoveryShake', {
    DeepReadShakeDetector: Detector, AppStorage: stored,
    sensor: { SensorId: { ACCELEROMETER: 1 }, on: (...args) => {
      sensorCalls.push(['on', ...args]); if (options.unsupported) throw Error('unsupported');
    }, off: (...args) => sensorCalls.push(['off', ...args]) },
    canIUse: () => options.capable ?? true, hilog: { warn: (...args) => warnings.push(args) },
    H: { success: () => feedback.push('success') }, Curve, curves,
    setTimeout: clock.set, clearTimeout: clock.clear,
    Math: { floor: Math.floor, random: () => 0 },
  });
  Object.assign(component, { reduceMotion: false, appBackgrounded: false, active: true,
    getPicks: () => [pick], getUIContext: () => context });
  return { component, clock, stored, context, sensorCalls, feedback, warnings };
}

test('physical shake needs two strong samples and respects a cooldown, rather than gravity or an isolated tap', () => {
  const d = new Detector();
  assert.equal(d.accept(0, 0, 9.8, 0), false);
  assert.equal(d.accept(20, 0, 0, 100), false);
  assert.equal(d.accept(20, 0, 0, 500), false, 'the old peak expires');
  assert.equal(d.accept(20, 0, 0, 550), true);
  assert.equal(d.accept(20, 0, 0, 650), false);
  assert.equal(d.accept(20, 0, 0, 1800), false);
  assert.equal(d.accept(20, 0, 0, 1850), true);
});

test('sensor ownership follows real visibility/modal/foreground and releases the identical callback', () => {
  const f = shaker(); const c = f.component;
  c.aboutToAppear(); c.syncActivity(); assert.equal(f.sensorCalls.length, 1);
  c.active = false; c.syncActivity(); assert.equal(f.sensorCalls[1][0], 'off');
  assert.equal(f.sensorCalls[1][2], f.sensorCalls[0][2]);
  c.active = true; c.syncActivity(); c.appBackgrounded = true; c.syncActivity();
  assert.deepEqual(f.sensorCalls.map(x => x[0]), ['on', 'off', 'on', 'off']);
  c.appBackgrounded = false; c.syncActivity(); c.aboutToDisappear();
  assert.deepEqual(f.sensorCalls.map(x => x[0]), ['on', 'off', 'on', 'off', 'on', 'off']);
  assert.equal(c.alive, false); assert.equal(f.clock.pending.size, 0);
  const late = f.sensorCalls[0][2]; late({ x: 100, y: 100, z: 100 }); late({ x: 100, y: 100, z: 100 });
  assert.equal(f.clock.pending.size, 0, 'late hardware delivery must not launch a shuffle');
});

test('manual pick works on unsupported sensors and keeps all original domain input metadata', () => {
  const f = shaker({ unsupported: true }); const c = f.component;
  c.reduceMotion = true; c.aboutToAppear(); assert.equal(c.subscribed, false); assert.equal(f.warnings.length, 1);
  c.trigger++; c.triggerChanged(); assert.strictEqual(c.lucky, pick);
  assert.strictEqual(c.lucky.inputSources, pick.inputSources); assert.equal(c.detail(pick), 'Hacker News 第 4');
  assert.equal(f.stored.get('deepreadDiscoveryScattered'), false); assert.deepEqual(f.feedback, ['success']);
  const capability = shaker({ capable: false }); capability.component.aboutToAppear();
  assert.equal(capability.sensorCalls.length, 0);
  capability.component.getPicks = () => []; capability.component.trigger++; capability.component.triggerChanged();
  assert.equal(capability.clock.pending.size, 0); assert.equal(capability.component.lucky, null);
});

test('shuffle admits one toss, keeps the genuine source, and releases work after expiry', () => {
  const f = shaker(); const c = f.component; c.aboutToAppear(); c.trigger++; c.triggerChanged();
  assert.equal(c.lucky, null); assert.equal(f.stored.get('deepreadDiscoveryScattered'), true);
  const scatter = c.scatterTimer;
  c.shuffle(); assert.equal(c.scatterTimer, scatter, 'an in-flight toss ignores another shake');
  f.clock.fire(scatter); assert.strictEqual(c.lucky, pick); assert.equal(f.stored.get('deepreadDiscoveryScattered'), false);
  assert.deepEqual(f.feedback, ['success']);
  f.clock.fire(c.bounceTimer);
  assert.equal(c.dieY, 0); assert.equal(c.dieScale, 1);
  f.clock.fire(c.expiryTimer); assert.equal(c.lucky, null); assert.equal(f.clock.pending.size, 0);
});

test('a newer lucky pick cancels old expiry, and a cleared background timer cannot mutate a later page', () => {
  const f = shaker(); const c = f.component; c.reduceMotion = true; c.aboutToAppear(); c.shuffle();
  const old = f.clock.pending.get(c.expiryTimer).callback; c.shuffle(); old(); assert.strictEqual(c.lucky, pick);
  c.reduceMotion = false; c.shuffle(); const lateScatter = f.clock.pending.get(c.scatterTimer).callback;
  c.appBackgrounded = true; c.syncActivity(); assert.equal(f.clock.pending.size, 0); assert.equal(c.lucky, null);
  f.stored.setOrCreate('deepreadDiscoveryScattered', true); lateScatter();
  assert.equal(f.stored.get('deepreadDiscoveryScattered'), true, 'a canceled old owner cannot settle a newer toss');
  assert.equal(c.lucky, null);
});

test('enabling reduced motion mid-toss lands immediately, and returns do not replay old manual trigger', () => {
  const f = shaker(); const c = f.component; c.trigger = 4; c.aboutToAppear(); c.triggerChanged();
  assert.equal(f.clock.pending.size, 0); c.trigger++; c.triggerChanged();
  c.reduceMotion = true; c.motionReduced(); assert.strictEqual(c.lucky, pick);
  assert.equal(c.scatterTimer, -1); assert.equal(f.stored.get('deepreadDiscoveryScattered'), false);
  c.aboutToDisappear(); assert.equal(f.clock.pending.size, 0);
});

test('card motion releases deferred work when hidden or backgrounded', () => {
  const context = ui(), clock = timers(); const c = logic(motion, 'DeepReadDiscoveryMotion', {
    curves, setTimeout: clock.set, clearTimeout: clock.clear });
  c.getUIContext = () => context; c.index = 25; c.aboutToAppear();
  assert.equal(c.ink, 0); assert.equal(context.calls.length, 0);
  clock.fire(c.entranceTimer);
  assert.equal(c.ink, 1); assert.equal(c.entranceY, 0);
  c.scattered = true; c.scatterSeed = 2; c.scatterChanged(); assert.notEqual(c.tossX, 0);
  c.scattered = false; c.scatterChanged(); assert.equal(c.tossX, 0); assert.equal(c.angle, 0);
  c.scattered = true; c.scatterChanged(); c.appBackgrounded = true; c.motionChanged();
  assert.equal(c.tossX, 0); assert.equal(c.ink, 1); const count = context.calls.length;
  c.aboutToDisappear(); c.scatterChanged(); assert.equal(context.calls.length, count);
  for (const flags of [{ playEntrance: false }, { active: false }]) {
    const clean = logic(motion, 'DeepReadDiscoveryMotion', { curves, setTimeout: clock.set, clearTimeout: clock.clear });
    Object.assign(clean, flags, { getUIContext: () => context }); clean.aboutToAppear();
    assert.equal(context.calls.length, count); assert.equal(clean.ink, 1); assert.equal(clean.entranceY, 0);
  }
  const delayed = logic(motion, 'DeepReadDiscoveryMotion', { curves, setTimeout: clock.set, clearTimeout: clock.clear });
  delayed.getUIContext = () => context; delayed.aboutToAppear(); const late = clock.pending.get(delayed.entranceTimer).callback;
  delayed.active = false; delayed.motionChanged(); assert.equal(clock.pending.size, 0); late();
  assert.equal(context.calls.length, count); assert.equal(delayed.ink, 1);
});

test('skeleton opacity pulses only while real loading UI is active, stopping on reduce/background/disappear', () => {
  const clock = timers(), context = ui();
  const c = logic(motion, 'DeepReadDiscoveryPlaceholder', { setInterval: clock.set, clearInterval: clock.clear, Curve });
  c.getUIContext = () => context; c.aboutToAppear();
  const pulse = clock.pending.get(c.pulseTimer); assert.equal(pulse.ms, 900); pulse.callback(); assert.equal(c.ink, 0.45);
  for (const flag of ['reduceMotion', 'appBackgrounded', 'active']) {
    c[flag] = flag !== 'active'; c.syncPulse(); assert.equal(clock.pending.size, 0); assert.equal(c.ink, 1);
    c[flag] = flag === 'active'; c.syncPulse(); assert.equal(clock.pending.size, 1);
  }
  c.aboutToDisappear(); assert.equal(clock.pending.size, 0); pulse.callback(); assert.equal(c.ink, 1);
});

test('five quick taps slam a double-frame seal, later taps re-ink, and reduced/background cancels bounce callbacks', () => {
  let now = 0; const context = ui(), clock = timers(), impacts = [];
  const c = methods(header.slice(header.indexOf('struct DeepReadDiscoveryMasthead {')), ['tapHeadline', 'settleMotion', 'aboutToDisappear'], {
    Date: { now: () => now }, H: { impact: () => impacts.push('impact') }, curves, Curve,
    setTimeout: clock.set, clearTimeout: clock.clear,
  });
  Object.assign(c, { alive: true, active: true, appBackgrounded: false, reduceMotion: false,
    seal: '', taps: [], sealIndex: 0, inscriptions: ['深读', '慢读'], stampCycle: 0, getUIContext: () => context });
  for (let i = 0; i < 4; i++) { now += 100; c.tapHeadline(); }
  assert.equal(c.seal, ''); now += 1700; c.tapHeadline(); assert.equal(c.seal, '', 'old taps expire');
  for (let i = 0; i < 4; i++) { now += 100; c.tapHeadline(); }
  assert.equal(c.seal, '深读');  assert.equal(c.stampInk, 0);
   clock.fire(c.stampTimer);

  context.calls.at(-1).onFinish(); assert.equal(c.impression, 1); c.tapHeadline(); clock.fire(c.stampTimer); assert.equal(c.seal, '慢读');
  const bounce = context.calls.at(-1).onFinish; c.appBackgrounded = true; c.settleMotion();
  const count = context.calls.length; bounce(); assert.equal(context.calls.length, count); assert.equal(c.impression, 1);
  c.appBackgrounded = false; c.reduceMotion = true; c.tapHeadline(); assert.equal(c.seal, '深读');
  clock.fire(c.stampTimer);
  assert.equal(c.impression, 1); assert.equal(context.calls.at(-1).onFinish, undefined);
  assert.deepEqual(impacts, ['impact', 'impact', 'impact']); c.aboutToDisappear(); c.tapHeadline();
  assert.equal(impacts.length, 3);
});

test('tab indicator consumes the previous selection so theme remount or article Back cannot replay it', () => {
  const stored = storage(), context = ui(), clock = timers(); stored.setOrCreate('deepreadPreviousTab', 0);
  const makeTab = () => { const c = methods(tab, ['aboutToAppear', 'settleMotion', 'aboutToDisappear'], {
    AppStorage: stored, curves, setTimeout: clock.set, clearTimeout: clock.clear });
    Object.assign(c, { selected: 1, reduceMotion: false, appBackgrounded: false, getUIContext: () => context }); return c; };
  const first = makeTab(); first.aboutToAppear(); assert.equal(first.indicator, 0); assert.equal(context.calls.length, 0);
  clock.fire(first.indicatorTimer); assert.equal(context.calls.length, 1); assert.equal(first.indicator, 1);
  makeTab().aboutToAppear(); assert.equal(context.calls.length, 1);
  stored.setOrCreate('deepreadPreviousTab', 0); const reduced = makeTab(); reduced.reduceMotion = true; reduced.aboutToAppear();
  assert.equal(context.calls.length, 1); assert.equal(reduced.indicator, 1);
  stored.setOrCreate('deepreadPreviousTab', 0); const hidden = makeTab(); hidden.aboutToAppear();
  const late = clock.pending.get(hidden.indicatorTimer).callback; hidden.aboutToDisappear(); late();
  assert.equal(context.calls.length, 1); assert.equal(clock.pending.size, 0);
});

test('discovery root arrival resets only its active key, skips reduced motion, and does not replay after article Back', () => {
  const stored = storage(), context = ui(), clock = timers(); stored.setOrCreate('deepreadTabDirection', -1);
  const c = methods(board, ['arrive', 'settleArrival'], { AppStorage: stored, curves,
    setTimeout: clock.set, clearTimeout: clock.clear });
  Object.assign(c, { firstArrival: true, pageVisible: true, deepreadActiveTab: 0,
    reduceMotion: false, appBackgrounded: false, arrivalTimer: -1, getUIContext: () => context });
  c.arrive(); assert.equal(context.calls.length, 0); assert.equal(c.arrivalOffset, -30);
   clock.fire(c.arrivalTimer);
  assert.equal(context.calls.length, 1); assert.equal(c.arrivalOffset, 0); assert.equal(stored.get('deepreadActiveTab'), 0);
  stored.setOrCreate('deepreadActiveTab', 1); c.arrive(); assert.equal(context.calls.length, 1); assert.equal(stored.get('deepreadActiveTab'), 0);
  c.firstArrival = true; c.reduceMotion = true; c.arrive(); assert.equal(context.calls.length, 1);
  c.arrivalOffset = 30; c.settleArrival(); assert.equal(c.arrivalOffset, 0);
});

test('discovery root cancels a pending arrival when it is hidden or deselected', () => {
  for (const change of [{ pageVisible: false }, { deepreadActiveTab: 1 }, { reduceMotion: true }, { appBackgrounded: true }]) {
    const stored = storage(), context = ui(), clock = timers(); stored.setOrCreate('deepreadTabDirection', 1);
    const c = methods(board, ['arrive', 'settleArrival'], { AppStorage: stored, curves,
      setTimeout: clock.set, clearTimeout: clock.clear });
    Object.assign(c, { firstArrival: true, pageVisible: true, deepreadActiveTab: 0, reduceMotion: false,
      appBackgrounded: false, arrivalTimer: -1, getUIContext: () => context });
    c.arrive(); const queued = clock.pending.get(c.arrivalTimer).callback;
    Object.assign(c, change); c.settleArrival(); queued();
    assert.equal(clock.pending.size, 0); assert.equal(c.arrivalOffset, 0); assert.equal(context.calls.length, 0);
  }
});

test('topic/source press feedback settles on cancellation/background and keeps reduced motion at zero duration', () => {
  const cards = read('components/deepread/DeepReadDiscoveryCards.ets'); const context = ui();
  const TouchType = { Down: 0, Up: 1, Cancel: 2, Move: 3 };
  const c = logic(cards, 'DeepReadDiscoveryTopicCard', { curves, TouchType });
  c.getUIContext = () => context; c.press({ type: TouchType.Down }); assert.equal(c.pressed, true);
  c.press({ type: TouchType.Cancel }); assert.equal(c.pressed, false);
  c.reduceMotion = true; c.press({ type: TouchType.Down }); assert.equal(context.calls.at(-1).duration, 0);
  c.appBackgrounded = true; c.settlePress(); assert.equal(c.pressed, false);
  const row = logic(cards, 'DeepReadDiscoverySourceCard', { curves, TouchType });
  row.getUIContext = () => context; row.pressRow({ type: TouchType.Down }, 4); assert.equal(row.pressedRank, 4);
  row.active = false; row.settlePress(); assert.equal(row.pressedRank, -1);
});

test('error notice executes a real entrance and uses opacity only in reduced motion', () => {
  const context = ui(), clock = timers(), c = logic(motion, 'DeepReadDiscoveryNotice', {
    Curve, setTimeout: clock.set, clearTimeout: clock.clear });
  c.getUIContext = () => context; c.aboutToAppear(); assert.equal(c.ink, 0);
  assert.equal(context.calls.length, 0); clock.fire(c.entranceTimer); assert.equal(c.ink, 1); assert.equal(c.y, 0);
  c.reduceMotion = true; c.aboutToAppear(); clock.fire(c.entranceTimer);
  c.active = false; c.settleMotion(); assert.equal(c.y, 0);
  c.active = true; c.aboutToAppear(); const late = clock.pending.get(c.entranceTimer).callback;
  c.aboutToDisappear(); const count = context.calls.length; late(); assert.equal(context.calls.length, count);
  assert.equal(clock.pending.size, 0);
});

test('lucky dice has one real bounce and clears its deferred frame on reduced motion or hide', () => {
  const f = shaker(), c = f.component; c.aboutToAppear(); c.shuffle(); f.clock.fire(c.scatterTimer);
  const late = f.clock.pending.get(c.bounceTimer).callback;
  c.reduceMotion = true; c.motionReduced(); assert.equal(c.bounceTimer, -1); assert.equal(c.dieY, 0);
  const count = f.context.calls.length; late(); assert.equal(f.context.calls.length, count);
  c.reduceMotion = false; c.shuffle(); f.clock.fire(c.scatterTimer); assert.notEqual(c.bounceTimer, -1);
  c.active = false; c.syncActivity(); assert.equal(c.bounceTimer, -1); assert.equal(f.clock.pending.size, 0);
  assert.equal(c.dieScale, 1); assert.equal(c.lucky, null);
});

test('lucky banner reports its measured height to keep the last discovery controls reachable', () => {
  const f = shaker(), c = f.component, heights = []; c.onHeightChanged = value => heights.push(value);
  c.aboutToAppear(); c.reportHeight({ height: 82 }); assert.deepEqual(heights, [82]);
  c.appBackgrounded = true; c.syncActivity(); c.reportHeight({ height: 82 });
  assert.deepEqual(heights, [82, 0, 0], 'hidden stale measurement cannot reserve banner space');
});

test('reduced motion retains a real opacity entrance without rise or stagger for cards and section header', () => {
  const context = ui(), clock = timers();
  const c = logic(motion, 'DeepReadDiscoveryMotion', { curves, Curve, setTimeout: clock.set, clearTimeout: clock.clear });
  c.getUIContext = () => context; c.reduceMotion = true; c.index = 6; c.aboutToAppear();
  assert.equal(c.ink, 0); assert.equal(c.entranceY, 0); assert.notEqual(c.entranceTimer, -1);
  c.motionChanged(); assert.equal(c.ink, 0, 'preference watcher must retain the scheduled fade');
  clock.fire(c.entranceTimer); assert.equal(c.ink, 1);
  assert.equal(context.calls[0].delay, 0);
  c.scatterEnabled = false; c.scattered = true; c.scatterChanged(); assert.equal(context.calls.length, 1);
  assert.equal(c.tossX, 0); assert.equal(c.angle, 0);
  const notice = logic(motion, 'DeepReadDiscoveryNotice', { Curve, setTimeout: clock.set, clearTimeout: clock.clear });
  notice.getUIContext = () => context; notice.aboutToAppear(); notice.reduceMotion = true; notice.settleMotion();
  assert.equal(notice.y, 0); assert.equal(notice.ink, 0); clock.fire(notice.entranceTimer);
  assert.equal(notice.ink, 1);
});

test('badge bounce and empty glyph wiggle render an initial frame, play once, and release deferred or finishing work', () => {
  const symbols = read('components/deepread/DeepReadDiscoverySymbol.ets'), context = ui(), clock = timers();
  const makeSymbol = variant => { const c = logic(symbols, 'DeepReadDiscoverySymbol', {
    curves, setTimeout: clock.set, clearTimeout: clock.clear });
    Object.assign(c, { variant, getUIContext: () => context }); return c; };
  const badge = makeSymbol('bounce'); badge.aboutToAppear();
  assert.equal(context.calls.length, 0);  clock.fire(badge.timer);
  assert.equal(badge.y, 0); assert.equal(badge.scaleValue, 1); context.calls.at(-1).onFinish();
  assert.equal(context.calls.length, 1, 'bounce has no repeating loop');
  const empty = makeSymbol('wiggle'); empty.aboutToAppear();  clock.fire(empty.timer);
   context.calls.at(-1).onFinish(); assert.equal(empty.angle, 0);
  const count = context.calls.length;
  empty.aboutToAppear(); const deferred = clock.pending.get(empty.timer).callback; empty.active = false; empty.settleMotion();
  deferred(); assert.equal(context.calls.length, count); assert.equal(empty.angle, 0); assert.equal(clock.pending.size, 0);
  empty.active = true; empty.aboutToAppear(); clock.fire(empty.timer); const finish = context.calls.at(-1).onFinish;
  empty.appBackgrounded = true; empty.settleMotion(); const hiddenCount = context.calls.length; finish();
  assert.equal(context.calls.length, hiddenCount); assert.equal(empty.angle, 0);
  const reduced = makeSymbol('wiggle'); reduced.reduceMotion = true; reduced.aboutToAppear();
  assert.equal(reduced.angle, 0); assert.equal(reduced.timer, -1); reduced.aboutToDisappear();
});

test('masthead horizontal rule waits for a rendered frame and cancels its deferred entrance when hidden', () => {
  const clock = timers(), context = ui();
  const c = logic(header, 'DeepReadDiscoveryMasthead', { curves, Curve, setTimeout: clock.set, clearTimeout: clock.clear });
  c.getUIContext = () => context; c.aboutToAppear(); assert.equal(c.ruleScale, 0); assert.equal(context.calls.length, 0);
   clock.fire(c.ruleTimer);
  assert.equal(c.ruleScale, 1);
  c.aboutToAppear(); const late = clock.pending.get(c.ruleTimer).callback; c.aboutToDisappear();
  assert.equal(c.ruleScale, 1); assert.equal(clock.pending.size, 0); late(); assert.equal(context.calls.length, 1);
});
