const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const source = fs.readFileSync(path.join(__dirname, '../main/ets/components/deepread/DeepReadNewsroomDesk.ets'), 'utf8');
const stageText = source.slice(source.indexOf('export function deepReadDeskStage'), source.indexOf('\n// Timers'));
const stageJS = ts.transpileModule(stageText.replace('export ', ''), {
  compilerOptions: { target: ts.ScriptTarget.ES2022 },
}).outputText;
const stage = new Function(stageJS + '; return deepReadDeskStage;')();

test('newsroom rail follows acquisition, planning, writing and durable save events, including iOS labels', () => {
  assert.equal(stage('COLLECTING', '搜索补充来源'), 0);
  assert.equal(stage('COLLECTING', '正在抓取网页正文 3/8'), 1);
  assert.equal(stage('PLANNING', '规划文章结构'), 1);
  assert.equal(stage('WRITING', '生成深度分析'), 2);
  assert.equal(stage('WRITING', '正在生成背景'), 2);
  assert.equal(stage('WRITING', '正在生成扩展阅读'), 3);
  assert.equal(stage('VERIFYING', '校验正文'), 3);
  assert.equal(stage('WRITING', '保存完整文章'), 3);
});

function method(name) {
  const start = source.search(new RegExp('^  (?:private )?' + name + '\\(', 'm'));
  assert.ok(start >= 0, name);
  let end = source.indexOf('{', start) + 1, depth = 1;
  for (; depth; end++) { if (source[end] === '{') depth++; if (source[end] === '}') depth--; }
  return source.slice(start, end);
}

test('cosmetic newsroom ticks cannot advance the business stage and stop when hidden, backgrounded or removed', () => {
  const timers = new Map();
  const pulseTimers = new Map();
  let nextTimer = 0;
  const frames = [];
  const code = ts.transpileModule('class Desk {' + ['stopTimer', 'syncTimer', 'stageIndex', 'aboutToDisappear',
    'motionEnabled', 'pulse', 'stageChanged', 'glyphs', 'placed', 'noteIndex'].map(method).join('\n')
    + '} return Desk;', { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const Desk = new Function('deepReadDeskStage', 'setInterval', 'clearInterval', 'Curve', 'setTimeout', 'clearTimeout', code)(stage,
    callback => { const id = nextTimer++; timers.set(id, callback); return id; }, id => timers.delete(id),
    { Linear: 'linear', EaseOut: 'easeOut' },
    callback => { const id = nextTimer++; pulseTimers.set(id, callback); return id; },
    id => pulseTimers.delete(id));
  const desk = new Desk();
  Object.assign(desk, { alive: true, active: true, appBackgrounded: false, reduceMotion: false,
    timer: -1, pulseTimer: -1, tick: 0, pulseOwner: 0, lastStage: -1, stage: 'COLLECTING', detail: '搜索来源', title: '活字排版示例',
    notes: ['第一条', '第二条', '第三条', '第四条', '第五条'],
    getUIContext: () => ({ animateTo: (_options, event) => event(),
      keyframeAnimateTo: (options, values) => frames.push({ options, values }) }) });
  desk.syncTimer();
  assert.equal(timers.size, 1);
  assert.equal(pulseTimers.size, 1);
  const tick = timers.get(desk.timer);
  for (let index = 0; index < 30; index++) tick();
  assert.equal(desk.tick, 30);
  assert.equal(desk.stageIndex(), 0);
  desk.active = false; desk.syncTimer();
  assert.equal(timers.size, 0);
  assert.equal(pulseTimers.size, 0);
  tick(); assert.equal(desk.tick, 30, 'queued callback is inert after visibility is lost');
  desk.active = true; desk.appBackgrounded = true; desk.syncTimer();
  assert.equal(timers.size, 0);
  desk.appBackgrounded = false; desk.reduceMotion = true; desk.syncTimer();
  assert.equal(timers.size, 1, 'reduced motion keeps the periodic clock for opacity-only desk notes');
  assert.equal(pulseTimers.size, 0);
  const beforeReducedTick = desk.tick, beforeReducedFrames = frames.length;
  const beforeNote = desk.noteIndex();
  timers.get(desk.timer)();
  assert.equal(desk.tick, beforeReducedTick + 14);
  assert.equal(desk.noteIndex(), (beforeNote + 1) % desk.notes.length, 'reduced-motion notes advance without replaying glyph animation');
  assert.equal(desk.placed(), desk.glyphs().length, 'lead type stays completely laid out');
  assert.equal(frames.length, beforeReducedFrames, 'a reduced-motion tick cannot start a pulse');
  desk.reduceMotion = false; desk.syncTimer();
  assert.equal(timers.size, 1);
  const beforeQueuedTick = desk.tick;
  tick(); assert.equal(desk.tick, beforeQueuedTick, 'an interval replaced by setting changes cannot emit a stale tick');
  const pulse = frames.at(-1);
  pulse.values[0].event(); assert.equal(desk.pulseScale, 1); assert.equal(desk.pulseOpacity, 0.5);
  pulse.values[1].event(); assert.equal(desk.pulseScale, 1.55); assert.equal(desk.pulseOpacity, 0);
  const nextPulse = pulseTimers.get(desk.pulseTimer);
  pulseTimers.delete(desk.pulseTimer); nextPulse();
  const reset = frames.at(-1); reset.values[0].event();
  assert.equal(desk.pulseScale, 1); assert.equal(desk.pulseOpacity, 0.5, 'reset follows the completed fade at the 1210ms deadline');
  desk.aboutToDisappear();
  assert.equal(timers.size, 0);
  assert.equal(pulseTimers.size, 0);
  const frameCount = frames.length; nextPulse();
  assert.equal(frames.length, frameCount, 'an old pulse deadline cannot restart after destruction');
  desk.pulseScale = 1; pulse.values[1].event();
  assert.equal(desk.pulseScale, 1, 'queued pulse frames cannot update a destroyed component');
});
