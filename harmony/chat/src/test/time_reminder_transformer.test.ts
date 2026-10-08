// time_reminder_transformer 规格测试(D-072)
// Android 基准: TimeReminderTransformer.kt(全文)
import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  applyTimeReminder,
  createTimeReminderTransformer,
  TIME_GAP_THRESHOLD_SECONDS,
  javaLocalDateTimeString,
  DAY_OF_WEEK_FULL_EN,
} from '../main/ets/chat/time_reminder_transformer.ts';
import type {
  TimeReminderFormatters,
} from '../main/ets/chat/time_reminder_transformer.ts';
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
