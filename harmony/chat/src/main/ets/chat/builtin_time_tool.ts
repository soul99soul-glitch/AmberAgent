// builtin_time_tool — get_time_info 内置工具(D-057)
//
// Android 基准: app/.../core/ai/tools/TimeTool.kt(全文 45 行)
//   payload 键序逐字:year/month/day/weekday/weekday_en/weekday_index/date/
//   time/datetime/timezone/utc_offset/timestamp_ms
// 偏差:java.time → Date/Intl(weekday 本地化经 Intl 默认 locale;
//   weekday_index 保持 ISO MONDAY=1..SUNDAY=7;utc_offset 'Z'|±HH:mm)

import type { JsonObject } from './json.ts';
import type { UIMessagePart } from './message.ts';
import type { AgentTool } from './tool.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';

const WEEKDAY_EN: string[] = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];

const pad2 = (n: number): string => String(n).padStart(2, '0');

// ZoneOffset.id 等价:0 → 'Z',否则 ±HH:mm
const utcOffsetId = (offsetMinutesEast: number): string => {
  if (offsetMinutesEast === 0) return 'Z';
  const sign: string = offsetMinutesEast >= 0 ? '+' : '-';
  const abs: number = Math.abs(offsetMinutesEast);
  return `${sign}${pad2(Math.trunc(abs / 60))}:${pad2(abs % 60)}`;
};

export const createTimeTool = (nowProvider: () => Date = (): Date => new Date()): AgentTool =>
  makeAgentTool({
    name: 'get_time_info',
    description: 'Get the current local date and time info from the device. Returns year/month/day, weekday, ISO date/time strings, timezone, and timestamp.',
    parameters: () => makeInputSchemaObj({}),
    execute: (): Promise<UIMessagePart[]> => {
      const now: Date = nowProvider();
      // getDay: Sunday=0..Saturday=6 → ISO weekday_index Monday=1..Sunday=7
      const weekdayIndex: number = now.getDay() === 0 ? 7 : now.getDay();
      const weekdayEn: string = WEEKDAY_EN[weekdayIndex - 1];
      let weekdayLocal: string = weekdayEn;
      try {
        weekdayLocal = new Intl.DateTimeFormat(undefined, { weekday: 'long' }).format(now);
      } catch {
        weekdayLocal = weekdayEn;
      }
      let timezone: string = 'UTC';
      try {
        timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
      } catch {
        timezone = 'UTC';
      }
      // getTimezoneOffset: 东八区 → -480(UTC 减本地);取反为东偏分钟
      const offsetEastMinutes: number = -now.getTimezoneOffset();
      const date: string = `${now.getFullYear()}-${pad2(now.getMonth() + 1)}-${pad2(now.getDate())}`;
      const time: string = `${pad2(now.getHours())}:${pad2(now.getMinutes())}:${pad2(now.getSeconds())}`;
      const payload: JsonObject = {
        year: now.getFullYear(),
        month: now.getMonth() + 1,
        day: now.getDate(),
        weekday: weekdayLocal,
        weekday_en: weekdayEn,
        weekday_index: weekdayIndex,
        date,
        time,
        datetime: `${date}T${time}${utcOffsetId(offsetEastMinutes)}`,
        timezone,
        utc_offset: utcOffsetId(offsetEastMinutes),
        timestamp_ms: now.getTime(),
      };
      return Promise.resolve([{ type: 'text', text: JSON.stringify(payload), metadata: null }]);
    },
  });
