import type { JsonObject, JsonValue } from './json.ts';
import type {
  ReminderEventMeta, DecodedReminderDescription, ReminderEventWindow,
} from './device_schedule_contracts.ts';

// ===== ISO-8601 解析 / 格式化 =====

const ISO_RE: RegExp =
  /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d{1,9}))?)?)?\s*(Z|z|[+-]\d{2}(?::?\d{2})?)?$/;

const daysInMonth = (year: number, month: number): number => new Date(Date.UTC(year, month, 0)).getUTCDate();

/**
 * 解析 ISO-8601 日期时间 → epoch ms;非法返回 null。
 * 支持:`YYYY-MM-DDTHH:mm[:ss[.fff]][Z|±HH:mm|±HHmm|±HH]`、空格分隔、
 * 纯日期 `YYYY-MM-DD`(按本地零点)。无时区后缀 → 设备本地时间(比 iOS
 * ISO8601DateFormatter 宽松,登记偏差)。
 */
export const parseIsoDateTime = (raw: string): number | null => {
  const value: string = raw.trim();
  const m: RegExpExecArray | null = ISO_RE.exec(value);
  if (m === null) return null;
  const year: number = Number(m[1]);
  const month: number = Number(m[2]);
  const day: number = Number(m[3]);
  const hasTime: boolean = m[4] !== undefined;
  const hour: number = hasTime ? Number(m[4]) : 0;
  const minute: number = hasTime ? Number(m[5]) : 0;
  const second: number = m[6] !== undefined ? Number(m[6]) : 0;
  const fraction: string = m[7] ?? '';
  const ms: number = fraction.length > 0 ? Math.floor(Number(`0.${fraction}`) * 1000) : 0;
  if (month < 1 || month > 12) return null;
  if (day < 1 || day > daysInMonth(year, month)) return null;
  if (hour > 23 || minute > 59 || second > 59) return null;
  const zone: string | undefined = m[8];
  if (zone === undefined) {
    if (!hasTime && value.length !== 10) return null;
    const local: Date = new Date(year, month - 1, day, hour, minute, second, ms);
    return Number.isNaN(local.getTime()) ? null : local.getTime();
  }
  if (!hasTime) return null;
  let offsetMinutes: number = 0;
  if (zone !== 'Z' && zone !== 'z') {
    const sign: number = zone.charAt(0) === '-' ? -1 : 1;
    const digits: string = zone.slice(1).replace(':', '');
    const oh: number = Number(digits.slice(0, 2));
    const om: number = digits.length > 2 ? Number(digits.slice(2, 4)) : 0;
    if (oh > 18 || om > 59) return null;
    offsetMinutes = sign * (oh * 60 + om);
  }
  const utc: number = Date.UTC(year, month - 1, day, hour, minute, second, ms);
  return utc - offsetMinutes * 60000;
};

const pad2 = (n: number): string => (n < 10 ? `0${n}` : `${n}`);

/** epoch ms → 带本地偏移的 ISO-8601(如 2026-10-09T15:00:00+08:00) */
export const formatIsoDateTime = (epochMs: number): string => {
  const d: Date = new Date(epochMs);
  const offset: number = -d.getTimezoneOffset();
  const sign: string = offset >= 0 ? '+' : '-';
  const abs: number = Math.abs(offset);
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
    + `T${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`
    + `${sign}${pad2(Math.floor(abs / 60))}:${pad2(abs % 60)}`;
};

// ===== 提醒事项 ↔ 日历日程 编解码(entry 复用;纯函数可测) =====

export const REMINDER_COMPLETED_TITLE_PREFIX: string = '✓ ';
const REMINDER_MARKER_PREFIX: string = '[amber-reminder:';
/** 标记载荷是扁平 JSON 对象(无嵌套/数组),故不含 [ ] 与换行 */
const REMINDER_MARKER_SOURCE: string = '\\[amber-reminder:(\\{[^\\[\\]\\n]*\\})\\]';

export const encodeReminderEventDescription = (notes: string, meta: ReminderEventMeta): string => {
  const payload: JsonObject = {
    v: 1, due: meta.hasDue ? 1 : 0, p: meta.priority, c: meta.completedAtMs,
  };
  const marker: string = `${REMINDER_MARKER_PREFIX}${JSON.stringify(payload)}]`;
  return notes.length > 0 ? `${notes}\n\n${marker}` : marker;
};

const parseReminderMeta = (json: string): ReminderEventMeta | null => {
  let parsed: JsonValue;
  try {
    parsed = JSON.parse(json) as JsonValue;
  } catch {
    return null;
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return null;
  const obj: JsonObject = parsed as JsonObject;
  const p: JsonValue | undefined = obj['p'];
  const c: JsonValue | undefined = obj['c'];
  return {
    hasDue: obj['due'] === 1,
    priority: typeof p === 'number' && Number.isInteger(p) && p >= 0 && p <= 9 ? p : 0,
    completedAtMs: typeof c === 'number' ? c : null,
  };
};

/**
 * 解码提醒日程描述:\r\n 归一为 \n;在描述任意位置查找 [amber-reminder:{...}] 标记
 * (系统日历 / 同步端可能在标记后追加文字),以**最后一个**可解析的标记为准,
 * 所有可解析标记都从 notes 中剥除(连带其两侧空白)。无标记 → meta=null。
 */
export const decodeReminderEventDescription = (description: string): DecodedReminderDescription => {
  const text: string = description.replace(/\r\n?/g, '\n');
  const re: RegExp = new RegExp(REMINDER_MARKER_SOURCE, 'g');
  const pieces: string[] = [];
  let meta: ReminderEventMeta | null = null;
  let cursor: number = 0;
  let m: RegExpExecArray | null = re.exec(text);
  while (m !== null) {
    const parsed: ReminderEventMeta | null = parseReminderMeta(m[1]);
    if (parsed !== null) {
      meta = parsed;
      pieces.push(text.slice(cursor, m.index));
      cursor = m.index + m[0].length;
    }
    m = re.exec(text);
  }
  if (meta === null) return { notes: text, meta: null };
  pieces.push(text.slice(cursor));
  const last: number = pieces.length - 1;
  const notes: string = pieces
    .map((piece: string, i: number): string => {
      let out: string = piece;
      if (i > 0) out = out.replace(/^\s+/, '');
      if (i < last) out = out.replace(/\s+$/, '');
      return out;
    })
    .filter((piece: string): boolean => piece.trim().length > 0)
    .join('\n\n');
  return { notes, meta };
};

/** 幂等:已带「✓ 」前缀的标题不会被重复加前缀 */
export const encodeReminderEventTitle = (title: string, completed: boolean): string =>
  completed && !title.startsWith(REMINDER_COMPLETED_TITLE_PREFIX) ? `${REMINDER_COMPLETED_TITLE_PREFIX}${title}` : title;

export const decodeReminderEventTitle = (stored: string, completed: boolean): string =>
  completed && stored.startsWith(REMINDER_COMPLETED_TITLE_PREFIX)
    ? stored.slice(REMINDER_COMPLETED_TITLE_PREFIX.length)
    : stored;

/**
 * 提醒 → 日程时间窗:有 due → [due, due+15min] 且准点提醒;无 due → anchor 当天
 * 全天日程、无提醒;已完成 → 不再提醒。
 */
export const reminderEventWindow = (
  dueMs: number | null, anchorMs: number, completed: boolean,
): ReminderEventWindow => {
  if (dueMs !== null) {
    return { startMs: dueMs, endMs: dueMs + 15 * 60000, allDay: false, reminderMinutes: completed ? [] : [0] };
  }
  const a: Date = new Date(anchorMs);
  const start: number = new Date(a.getFullYear(), a.getMonth(), a.getDate()).getTime();
  const end: number = new Date(a.getFullYear(), a.getMonth(), a.getDate() + 1).getTime();
  return { startMs: start, endMs: end, allDay: true, reminderMinutes: [] };
};

