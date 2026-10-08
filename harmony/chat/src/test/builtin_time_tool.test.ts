// builtin_time_tool.test.ts — get_time_info 内置工具(D-057 TDD)
//
// Android 基准: TimeTool.kt(全文 45 行)
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createTimeTool } from '../main/ets/chat/builtin_time_tool.ts';
import type { UIMessagePart } from '../main/ets/chat/message.ts';

describe('createTimeTool(TimeTool.kt)', () => {

  it('payload 键序与值(注入固定时间)', async () => {
    const fixed = new Date(2026, 6, 28, 15, 4, 5, 123); // 2026-07-28 15:04:05 local(周二)
    const t = createTimeTool(() => fixed);
    const out: UIMessagePart[] = await t.execute({});
    assert.equal(out.length, 1);
    const payload = JSON.parse((out[0] as { text: string }).text);
    assert.deepEqual(Object.keys(payload), [
      'year', 'month', 'day', 'weekday', 'weekday_en', 'weekday_index',
      'date', 'time', 'datetime', 'timezone', 'utc_offset', 'timestamp_ms',
    ]);
    assert.equal(payload.year, 2026);
    assert.equal(payload.month, 7);
    assert.equal(payload.day, 28);
    assert.equal(payload.weekday_en, 'Tuesday');
    assert.equal(payload.weekday_index, 2);
    assert.equal(payload.date, '2026-07-28');
    assert.equal(payload.time, '15:04:05');
    assert.equal(payload.timestamp_ms, fixed.getTime());
    assert.ok(typeof payload.weekday === 'string' && payload.weekday.length > 0);
    assert.ok(typeof payload.timezone === 'string' && payload.timezone.length > 0);
    // utc_offset:'Z' 或 ±HH:mm;datetime = dateTtime+offset
    assert.ok(/^Z|[+-]\d{2}:\d{2}$/.test(payload.utc_offset));
    assert.equal(payload.datetime, `2026-07-28T15:04:05${payload.utc_offset}`);
  });
  it('weekday_index ISO 映射:周日 → 7', async () => {
    const sunday = new Date(2026, 6, 26, 12, 0, 0); // 2026-07-26 周日
    const t = createTimeTool(() => sunday);
    const out = await t.execute({});
    const payload = JSON.parse((out[0] as { text: string }).text);
    assert.equal(payload.weekday_index, 7);
    assert.equal(payload.weekday_en, 'Sunday');
  });
});
