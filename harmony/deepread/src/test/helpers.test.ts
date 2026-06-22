import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { makeEmptyDeepReadOutput } from '../main/ets/domain/models.ts';
import {
  isComplete, sectionsReady, hasAnyReadySection, statusOf,
  hasReadableArticle, hasEnoughChinese,
} from '../main/ets/domain/helpers.ts';
import { STAGE_ORDER } from '../main/ets/domain/enums.ts';

const readyState = { status: 'READY' as const, errorMessage: null };
const pendingState = { status: 'PENDING' as const, errorMessage: null };

test('sectionsReady false on empty sectionStates', () => {
  const o = makeEmptyDeepReadOutput();
  assert.equal(sectionsReady(o), false);
});

test('sectionsReady true when all 4 stages READY', () => {
  const o = makeEmptyDeepReadOutput();
  for (const s of STAGE_ORDER) o.sectionStates[s] = readyState;
  assert.equal(sectionsReady(o), true);
});

test('sectionsReady false when any stage not READY', () => {
  const o = makeEmptyDeepReadOutput();
  for (const s of STAGE_ORDER) o.sectionStates[s] = readyState;
  o.sectionStates['ANALYSIS'] = pendingState;
  assert.equal(sectionsReady(o), false);
});

test('sectionsReady false when a required stage missing', () => {
  const o = makeEmptyDeepReadOutput();
  o.sectionStates['OVERVIEW'] = readyState;
  o.sectionStates['NARRATIVE'] = readyState;
  o.sectionStates['ANALYSIS'] = readyState;
  // EXTENDED_READING 缺失
  assert.equal(sectionsReady(o), false);
});

test('hasAnyReadySection false when none ready', () => {
  const o = makeEmptyDeepReadOutput();
  assert.equal(hasAnyReadySection(o), false);
});

test('hasAnyReadySection true when at least one READY', () => {
  const o = makeEmptyDeepReadOutput();
  o.sectionStates['OVERVIEW'] = readyState;
  assert.equal(hasAnyReadySection(o), true);
});

test('isComplete requires generationComplete AND sectionsReady', () => {
  const o = makeEmptyDeepReadOutput();
  assert.equal(isComplete(o), false);
  o.generationComplete = true;
  assert.equal(isComplete(o), false);
  for (const s of STAGE_ORDER) o.sectionStates[s] = readyState;
  assert.equal(isComplete(o), true);
});

test('statusOf returns PENDING for missing stage', () => {
  const o = makeEmptyDeepReadOutput();
  assert.equal(statusOf(o, 'OVERVIEW'), 'PENDING');
});

test('statusOf returns actual status', () => {
  const o = makeEmptyDeepReadOutput();
  o.sectionStates['OVERVIEW'] = { status: 'FAILED', errorMessage: 'timeout' };
  assert.equal(statusOf(o, 'OVERVIEW'), 'FAILED');
});

test('hasReadableArticle false on empty output', () => {
  assert.equal(hasReadableArticle(makeEmptyDeepReadOutput()), false);
});

test('hasReadableArticle true with rich content (all 3 gates pass)', () => {
  const o = makeEmptyDeepReadOutput();
  // summary genuinely >= 80 chars (Android :310 threshold)
  o.summary = '本段摘要内容长度必须达到并确实超过八十个字符门槛以便顺利通过可读性深度检查环节要求条件本段摘要内容长度必须达到并确实超过八十个字符门槛以便顺利通过可读性深度检查环节要求条件';
  // timeline AND corePoints both required (Android :321 uses AND)
  o.timeline = [
    { date: '2026-06-01', event: '事件一发生并且描述足够长确实超过二十个字符', isHighlight: false, imageUrl: null, imageCaption: null },
    { date: '2026-06-02', event: '事件二发生并且描述足够长确实超过二十个字符', isHighlight: false, imageUrl: null, imageCaption: null },
  ];
  o.corePoints = [
    { point: '核心观点一的标题足够长', supporting: '支撑论据文字长度需要确实超过三十个字符门槛要求条件以便通过检查', imageUrl: null, imageCaption: null },
    { point: '核心观点二的标题足够长', supporting: '支撑论据文字长度需要确实超过三十个字符门槛要求条件以便通过检查', imageUrl: null, imageCaption: null },
  ];
  // analysis: needs >= 2 perspectives with viewpoint >= 30 chars (Android :316-320)
  o.analysis = {
    coreDispute: null,
    implications: null,
    perspectives: [
      { holder: null, viewpoint: '本观点文字长度同样需要超过三十个字符以满足分析维度门槛条件要求' },
      { holder: null, viewpoint: '另一方的观点文字长度同样需要超过三十个字符以满足分析维度门槛' },
    ],
    quotes: [],
  };
  assert.equal(hasReadableArticle(o), true);
});

test('hasReadableArticle false when timeline present but corePoints missing (AND logic)', () => {
  const o = makeEmptyDeepReadOutput();
  o.summary = '本段摘要内容长度必须达到并确实超过八十个字符门槛以便顺利通过可读性深度检查环节要求条件本段摘要内容长度必须达到并确实超过八十个字符门槛以便顺利通过可读性深度检查环节要求条件';
  o.timeline = [
    { date: '2026-06-01', event: '事件一发生并且描述足够长确实超过二十个字符', isHighlight: false, imageUrl: null, imageCaption: null },
    { date: '2026-06-02', event: '事件二发生并且描述足够长确实超过二十个字符', isHighlight: false, imageUrl: null, imageCaption: null },
  ];
  o.corePoints = null;  // 缺 corePoints → AND 失败
  o.analysis = {
    coreDispute: null, implications: null,
    perspectives: [
      { holder: null, viewpoint: '本观点文字长度同样需要超过三十个字符以满足分析维度门槛条件要求' },
      { holder: null, viewpoint: '另一方的观点文字长度同样需要超过三十个字符以满足分析维度门槛' },
    ],
    quotes: [],
  };
  assert.equal(hasReadableArticle(o), false);
});

test('hasReadableArticle false when summary contains fallback phrase', () => {
  const o = makeEmptyDeepReadOutput();
  o.summary = '当前可抓取信息仍偏薄' + '本段摘要内容长度必须达到并确实超过八十个字符门槛以便顺利通过可读性深度检查环节要求条件本段摘要内容长度必须达到并确实超过八十个字符门槛以便顺利通过可读性深度检查环节要求条件';
  o.timeline = [
    { date: '2026-06-01', event: '事件一发生并且描述足够长确实超过二十个字符', isHighlight: false, imageUrl: null, imageCaption: null },
    { date: '2026-06-02', event: '事件二发生并且描述足够长确实超过二十个字符', isHighlight: false, imageUrl: null, imageCaption: null },
  ];
  o.corePoints = [
    { point: '核心观点一的标题足够长', supporting: '支撑论据文字长度需要确实超过三十个字符门槛要求条件以便通过检查', imageUrl: null, imageCaption: null },
    { point: '核心观点二的标题足够长', supporting: '支撑论据文字长度需要确实超过三十个字符门槛要求条件以便通过检查', imageUrl: null, imageCaption: null },
  ];
  o.analysis = {
    coreDispute: null, implications: null,
    perspectives: [
      { holder: null, viewpoint: '本观点文字长度同样需要超过三十个字符以满足分析维度门槛条件要求' },
      { holder: null, viewpoint: '另一方的观点文字长度同样需要超过三十个字符以满足分析维度门槛' },
    ],
    quotes: [],
  };
  assert.equal(hasReadableArticle(o), false);
});

test('hasEnoughChinese true for predominantly CJK text', () => {
  assert.equal(hasEnoughChinese('这是一段中文内容,字符数超过八十这是一段中文内容,字符数超过八十这是一段中文内容,字符数超过八十'), true);
});

test('hasEnoughChinese false for predominantly latin text', () => {
  assert.equal(hasEnoughChinese('This is english text with some 中文 twelve chars'), false);
});
