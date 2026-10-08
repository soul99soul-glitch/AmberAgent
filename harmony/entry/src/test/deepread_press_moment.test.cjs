const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const source = fs.readFileSync(path.join(__dirname, '../main/ets/components/deepread/DeepReadArticlePressMoment.ets'), 'utf8');
function method(name, text = source) {
  const start = text.indexOf(' ' + name + '(');
  assert.ok(start >= 0, 'production method ' + name);
  const open = text.indexOf('{', start);
  let depth = 1, end = open + 1;
  for (; depth; end++) { if (text[end] === '{') depth++; if (text[end] === '}') depth--; }
  return text.slice(start, end);
}
function fixture(overrides = {}) {
  const timers = new Map(), animations = [], keyframes = [];
  let nextId = 0, now = 0, impacts = 0;
  const methods = ['aboutToAppear', 'aboutToDisappear', 'activityChanged', 'hide', 'current',
    'reduceMotionChanged', 'startStamp', 'play'].map(name => method(name)).join('\n');
  const code = ts.transpileModule('class Press {' + methods + '} return new Press();',
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const press = new Function('setTimeout', 'clearTimeout', 'curves', 'Curve', 'DeepReadHaptics', code)(
    (callback, delay) => { const id = nextId++; timers.set(id, { callback, at: now + delay }); return id; },
    id => timers.delete(id), { springCurve: (...args) => ({ spring: args }) },
    { Linear: 'linear', EaseOut: 'easeOut' }, { impact: () => { impacts++; } });
  Object.assign(press, { alive: true, visible: false, active: true, appBackgrounded: false, reduceMotion: false,
    trigger: 1, inscription: '付印', caption: '文章已排版完成', motionOwner: 0, timer: -1, enterTimer: -1,
    captionTimer: -1, stampScale: 1, stampAngle: -10, ink: 0, captionShown: false,
    getUIContext: () => ({ animateTo: (options, event) => { animations.push({ options, event }); event(); },
      keyframeAnimateTo: (options, frames) => keyframes.push({ options, frames }) }), ...overrides });
  function advance(end) {
    while (true) {
      const due = [...timers].filter(([, timer]) => timer.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!due) break;
      now = due[1].at; timers.delete(due[0]); due[1].callback();
    }
    now = end;
  }
  return { press, timers, animations, keyframes, advance, impacts: () => impacts };
}

test('reduced motion uses only opacity, keeps impact feedback, and switching it on settles current animation without replay', () => {
  const reduced = fixture({ reduceMotion: true }); reduced.press.play(); reduced.advance(16);
  assert.equal(reduced.keyframes.length, 0); assert.equal(reduced.press.stampScale, 1);

  assert.equal(reduced.impacts(), 1, 'iOS does not disable sensory impact with reduce motion');
  const f = fixture(); f.press.play(); f.advance(16);
  const oldFrames = f.keyframes[0].frames, opacityEvent = f.animations[0].event;
  oldFrames[1].event(); assert.equal(f.press.stampScale, 0.92);
  const dismissTimer = f.press.timer;
  f.press.reduceMotion = true; f.press.reduceMotionChanged();
  assert.equal(f.press.stampScale, 1);
  assert.equal(f.press.ink, 0.88); assert.equal(f.press.captionShown, true);
  assert.equal(f.press.captionTimer, -1); assert.equal(f.press.timer, dismissTimer, 'the original dismissal deadline survives');
  f.press.reduceMotion = false; f.press.reduceMotionChanged();
  oldFrames[0].event(); opacityEvent();
  assert.equal(f.press.stampScale, 1, 'returning to normal motion does not revive old keyframes');
  assert.equal(f.impacts(), 1, 'a setting change is not a new completion');
  f.advance(1400); assert.equal(f.press.visible, false);
});

test('inactive, backgrounded, zero-trigger and disappeared components clear callbacks and cannot replay completion', () => {
  for (const overrides of [{ active: false }, { appBackgrounded: true }, { trigger: 0 }, { alive: false }]) {
    const f = fixture(overrides); f.press.play();
    assert.equal(f.press.visible, false); assert.equal(f.timers.size, 0); assert.equal(f.impacts(), 0);
  }
  for (const stop of ['activity', 'background', 'disappear']) {
    const f = fixture(); f.press.play(); f.advance(16);
    const oldFrames = f.keyframes[0].frames;
    if (stop === 'activity') { f.press.active = false; f.press.activityChanged(); }
    if (stop === 'background') { f.press.appBackgrounded = true; f.press.hide(); }
    if (stop === 'disappear') f.press.aboutToDisappear();
    assert.equal(f.timers.size, 0); assert.equal(f.press.visible, false);
    f.press.stampScale = 1; oldFrames[0].event(); f.advance(3000);
    assert.equal(f.press.stampScale, 1); assert.equal(f.press.visible, false);
    f.press.active = true; f.press.appBackgrounded = false; f.press.activityChanged();
    assert.equal(f.press.visible, false); assert.equal(f.impacts(), 1, 'return alone cannot replay an old seal');
  }
});

test('callbacks queued by a replaced trigger cannot clear the new timers, caption or seal', () => {
  const f = fixture(); f.press.play();
  const oldEnter = f.timers.get(f.press.enterTimer).callback;
  const oldCaption = f.timers.get(f.press.captionTimer).callback;
  const oldDismiss = f.timers.get(f.press.timer).callback;
  f.press.trigger = 2; f.press.play();
  const ids = [f.press.enterTimer, f.press.captionTimer, f.press.timer];
  oldEnter(); oldCaption(); oldDismiss();
  assert.deepEqual([f.press.enterTimer, f.press.captionTimer, f.press.timer], ids);
  assert.equal(f.press.visible, true); assert.equal(f.press.captionShown, false);
  assert.equal(f.keyframes.length, 0);
  f.advance(16); assert.equal(f.keyframes.length, 1);
  f.advance(300); assert.equal(f.press.captionShown, true);
  f.advance(1400); assert.equal(f.press.visible, false);
});
