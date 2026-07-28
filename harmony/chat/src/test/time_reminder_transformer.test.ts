// time_reminder_transformer 规格测试(D-072)
// Android 基准: TimeReminderTransformer.kt(全文)
import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  applyTimeReminder, createTimeReminderTransformer, formatGap,
  TIME_GAP_THRESHOLD_SECONDS, javaLocalDateTimeString, dayOfWeekFullName,
  DAY_OF_WEEK_FULL_EN, DAY_OF_WEEK_FULL_ZH,
} from '../main/ets/chat/time_reminder_transformer.ts';
import type { TimeReminderFormatters } from '../main/ets/chat/time_reminder_transformer.ts';
import { makeUserMessage, toText } from '../main/ets/chat/message.ts';
import type { UIMessage } from '../main/ets/chat/message.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import type { TransformerContext } from '../main/ets/chat/transformer_pipeline.ts';

const ctx: TransformerContext = { assistant: makeAssistant({}) };

// 注入格式化器(固定英名 + 原样时间串,锁定文案模板)
const fmt: TimeReminderFormatters = {
  formatDayOfWeek: (ms: number): string => DAY_OF_WEEK_FULL_EN[new Date(ms).getDay()],
  formatLocalDateTime: (ms: number): string => javaLocalDateTimeString(ms),
};

const msgAt = (text: string, iso: string): UIMessage => ({
  ...makeUserMessage(text),
  createdAt: iso,
});

test('gate:enableTimeReminder=false(默认)→ 原引用返回(Android :24)', () => {
  const t = createTimeReminderTransformer({ enabled: (): boolean => false, ...fmt });
  const msgs: UIMessage[] = [
    msgAt('a', '2026-07-28T00:00:00Z'),
    msgAt('b', '2026-07-28T05:00:00Z'),
  ];
  assert.equal(t.transform!(ctx, msgs), msgs);
});

test('注入:gap 严格大于 3600s 才注入;3600 整不注入(:40)', () => {
  assert.equal(TIME_GAP_THRESHOLD_SECONDS, 3600);
  const just: UIMessage[] = applyTimeReminder([
    msgAt('a', '2026-07-28T00:00:00Z'),
    msgAt('b', '2026-07-28T01:00:00Z'), // 恰好 3600s
  ], fmt);
  assert.equal(just.length, 2, '3600 整不注入(严格大于)');
  const over: UIMessage[] = applyTimeReminder([
    msgAt('a', '2026-07-28T00:00:00Z'),
    msgAt('b', '2026-07-28T01:00:01Z'), // 3601s
  ], fmt);
  assert.equal(over.length, 3);
  assert.equal(over[1].role, 'user', '注入消息为 user(:60)');
  const content: string = toText(over[1]);
  assert.equal(
    content,
    `<time_reminder>Current time: ${DAY_OF_WEEK_FULL_EN[new Date('2026-07-28T01:00:01Z').getDay()]}, ` +
    `${javaLocalDateTimeString(Date.parse('2026-07-28T01:00:01Z'))} (1 h since last message)</time_reminder>`);
  assert.equal(over[0].createdAt, '2026-07-28T00:00:00Z', '原消息不动');
  assert.equal(over[2], over[over.length - 1]);
});

test('注入:首消息前不注入;多处大间隔多处注入', () => {
  const out: UIMessage[] = applyTimeReminder([
    msgAt('a', '2026-07-28T00:00:00Z'),
    msgAt('b', '2026-07-28T02:00:00Z'),
    msgAt('c', '2026-07-28T02:30:00Z'),
    msgAt('d', '2026-07-28T10:00:00Z'),
  ], fmt);
  assert.equal(out.length, 6);
  assert.equal(toText(out[0]), 'a', '首消息前无注入');
  assert.equal(toText(out[1]).startsWith('<time_reminder>'), true);
  assert.equal(toText(out[2]), 'b');
  assert.equal(toText(out[3]), 'c');
  assert.equal(toText(out[4]).startsWith('<time_reminder>'), true);
  assert.equal(toText(out[5]), 'd');
});

test('formatGap:分/时/天三档 + Kotlin 向零截断(:71-77)', () => {
  assert.equal(formatGap(3599), '59 min');
  assert.equal(formatGap(3600), '1 h', '3600 非 < 3600 → 落 h 档(Android 分支边界忠实)');
  assert.equal(formatGap(3661), '1 h'); // 3661/3600 → 1(截断)
  assert.equal(formatGap(86399), '23 h');
  assert.equal(formatGap(86400), '1 d');
  assert.equal(formatGap(90061), '1 d');
  assert.equal(formatGap(172800), '2 d');
});

test('解析失败(NaN)→ 不注入(安全退化,登记偏差)', () => {
  const out: UIMessage[] = applyTimeReminder([
    msgAt('a', 'not-a-date'),
    msgAt('b', '2026-07-28T10:00:00Z'),
  ], fmt);
  assert.equal(out.length, 2);
});

test('gate 开启走完整 transform:返回新数组注入', () => {
  const t = createTimeReminderTransformer({ enabled: (): boolean => true, ...fmt });
  const out = t.transform!(ctx, [
    msgAt('a', '2026-07-28T00:00:00Z'),
    msgAt('b', '2026-07-28T05:00:00Z'),
  ]) as UIMessage[];
  assert.equal(out.length, 3);
});

// ===== 格式化助手(java.time LocalDateTime.toString / TextStyle.FULL) =====

test('javaLocalDateTimeString:秒省略/秒保留/毫秒三档(本地时区构造,确定性)', () => {
  const noSec: number = new Date(2026, 6, 28, 15, 1, 0, 0).getTime();
  assert.equal(javaLocalDateTimeString(noSec), '2026-07-28T15:01');
  const withSec: number = new Date(2026, 6, 28, 15, 1, 26, 0).getTime();
  assert.equal(javaLocalDateTimeString(withSec), '2026-07-28T15:01:26');
  const withMs: number = new Date(2026, 6, 28, 15, 1, 26, 325).getTime();
  assert.equal(javaLocalDateTimeString(withMs), '2026-07-28T15:01:26.325');
  const padded: number = new Date(2026, 0, 5, 3, 4, 5, 7).getTime();
  assert.equal(javaLocalDateTimeString(padded), '2026-01-05T03:04:05.007');
});

test('dayOfWeekFullName:zh/en 二档(偏差登记);2026-07-28 = 周二', () => {
  const tue: number = new Date(2026, 6, 28, 12, 0, 0, 0).getTime();
  assert.equal(dayOfWeekFullName('en', tue), 'Tuesday');
  assert.equal(dayOfWeekFullName('zh-Hans', tue), '星期二');
  assert.equal(dayOfWeekFullName('fr', tue), 'Tuesday', '非 zh 落英文档(登记)');
});
