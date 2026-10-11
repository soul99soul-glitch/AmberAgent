// device_schedule_tools — 日历 / 提醒事项 / 通知 / 闹钟 Agent 工具(iOS Apple 工具对齐)
//
// iOS 基准:
//   ai-core/.../Tool.kt:1016-1330 — 14 个工具声明(名/参数/描述/审批位)
//   iosApp/IOSAppleIntegrationsView.swift — EventKit 执行器(参数严格校验、
//     结果 JSON 形态 {ok,tool,...}/{ok:false,tool,reason})
//   iosApp/IOSLocalNotifications.swift — notification_schedule/cancel(单槽
//     agent 通知:新排程替换旧的,cancel 不接受参数)
//   iosApp/IOSAlarmKit.swift — alarm_schedule 校验(one_time/weekly/timer
//     互斥字段)+ 结果 {ok,tool,alarm:{...}} / 失败 {ok:false,tool,error}
//
// HarmonyOS 映射(entry/platform_impl/DeviceSchedulePort.ets 实现 Port):
//   - calendar_*  → Calendar Kit calendarManager(系统日历)
//   - reminders_* → 鸿蒙无「提醒事项」应用;落为 Amber 自有日历账户
//     「Amber 提醒」中的日程(带 reminderTime);完成态 = 标题前缀「✓ 」+
//     描述中的元数据标记(见 encode/decodeReminderEventDescription),不删除日程
//   - 重复日程:API 12 无单次实例编辑 → update/delete 作用于整个系列,须 apply_to:"series"
//     显式确认(否则 error_code recurring_series);未给 recurrence 时平台原样保留既有规则
//   - notification_* → reminderAgentManager 代理提醒(Calendar 型,定点触发)
//   - alarm_* / alarms_list → reminderAgentManager(Calendar/Alarm/Timer 型)
// 本模块只做:参数校验、ISO-8601 解析、合并/裁剪、结果 JSON;平台调用全部经 Port。

import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessagePart } from './message.ts';
import type { AgentTool, InputSchemaObj } from './tool.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';

// ===== 错误模型(Port 抛出;工具层映射为 ok:false JSON) =====

export type DeviceScheduleErrorCode =
  | 'invalid_arguments'
  | 'invalid_date'
  | 'invalid_date_range'
  | 'invalid_recurrence'
  | 'not_found'
  | 'permission_denied'
  | 'unavailable'
  | 'capacity_reached'
  | 'recurring_series'
  | 'platform_error';

export class DeviceScheduleError extends Error {
  readonly code: DeviceScheduleErrorCode;

  constructor(code: DeviceScheduleErrorCode, message: string) {
    super(message);
    this.name = 'DeviceScheduleError';
    this.code = code;
  }
}

// ===== Port 数据类型(纯数据,entry 以 ArkTS 实现) =====

export type DeviceRecurrenceFrequency = 'daily' | 'weekly' | 'monthly' | 'yearly';

export interface DeviceRecurrenceRule {
  frequency: DeviceRecurrenceFrequency;
  interval: number;
  /** 重复截止(epoch ms);null = 无截止 */
  endMs: number | null;
  /** 重复次数(Calendar Kit count);仅读取时由平台给出,工具层只展示 */
  count?: number | null;
  /** 平台规则中 Amber 无法表达的附加字段(如 days_of_week / excluded_dates);仅展示 */
  extraRules?: string[];
}

export interface DeviceCalendarEvent {
  eventId: string;
  title: string;
  startMs: number;
  endMs: number;
  allDay: boolean;
  calendarId: string;
  calendarName: string;
  location: string;
  notes: string;
  recurrence: DeviceRecurrenceRule | null;
}

/** 完整写入载荷(create 与 update 共用;update 由工具层合并既有值后给出) */
export interface DeviceCalendarEventDraft {
  title: string;
  startMs: number;
  endMs: number;
  allDay: boolean;
  location: string;
  notes: string;
  /** null = 默认日历(create)/ 保持原日历(update) */
  calendarId: string | null;
  recurrence: DeviceRecurrenceRule | null;
  /**
   * true = recurrence 由本次调用明确给出(null 表示清除重复);
   * false = 模型未触及重复规则 → 平台必须原样保留既有规则(不能用 recurrence 重建,
   * 否则会丢失 count/daysOfWeek/excludedDates 等 Amber 无法表达的字段)
   */
  recurrenceSpecified: boolean;
}

export interface DeviceReminder {
  reminderId: string;
  title: string;
  dueMs: number | null;
  notes: string;
  /** 0(无)…9,沿用 EventKit 语义 */
  priority: number;
  completed: boolean;
  completedAtMs: number | null;
  listId: string;
  listName: string;
}

export interface DeviceReminderDraft {
  title: string;
  dueMs: number | null;
  notes: string;
  priority: number;
  completed: boolean;
  completedAtMs: number | null;
  /** null = Amber 默认提醒清单 / 保持原清单 */
  listId: string | null;
}

export interface DeviceNotificationRequest {
  title: string;
  body: string;
  fireAtMs: number;
}

export interface DeviceScheduledNotification {
  notificationId: string;
  title: string;
  body: string;
  fireAtMs: number;
}

export type DeviceAlarmKind = 'one_time' | 'weekly' | 'timer';

export interface DeviceAlarmRequest {
  title: string;
  kind: DeviceAlarmKind;
  fireAtMs: number | null;
  /** 英文小写星期名,按 sunday…saturday 顺序去重 */
  weekdays: string[];
  hour: number | null;
  minute: number | null;
  durationSeconds: number | null;
}

export interface DeviceAlarm {
  alarmId: string;
  title: string;
  kind: DeviceAlarmKind;
  state: string;
  createdAtMs: number;
  fireAtMs: number | null;
  weekdays: string[];
  hour: number | null;
  minute: number | null;
  durationSeconds: number | null;
}

export interface DeviceSchedulePort {
  // —— 系统日历 ——
  /** [startMs, endMs) 内的事件(不含 Amber 提醒清单);工具层负责排序与 limit */
  listCalendarEvents(startMs: number, endMs: number): Promise<DeviceCalendarEvent[]>;
  getCalendarEvent(eventId: string): Promise<DeviceCalendarEvent | null>;
  createCalendarEvent(draft: DeviceCalendarEventDraft): Promise<DeviceCalendarEvent>;
  updateCalendarEvent(eventId: string, draft: DeviceCalendarEventDraft): Promise<DeviceCalendarEvent>;
  deleteCalendarEvent(eventId: string): Promise<void>;
  // —— 提醒事项(Amber 提醒日历) ——
  listReminders(): Promise<DeviceReminder[]>;
  getReminder(reminderId: string): Promise<DeviceReminder | null>;
  createReminder(draft: DeviceReminderDraft): Promise<DeviceReminder>;
  updateReminder(reminderId: string, draft: DeviceReminderDraft): Promise<DeviceReminder>;
  deleteReminder(reminderId: string): Promise<void>;
  // —— 本地通知(单槽:新排程替换旧的) ——
  scheduleNotification(request: DeviceNotificationRequest): Promise<DeviceScheduledNotification>;
  /** 返回是否确有待触发通知被取消 */
  cancelNotification(): Promise<boolean>;
  // —— 闹钟 / 计时器(仅 Amber 创建的) ——
  scheduleAlarm(request: DeviceAlarmRequest): Promise<DeviceAlarm>;
  listAlarms(): Promise<DeviceAlarm[]>;
  /** 不存在时抛 DeviceScheduleError('not_found') */
  cancelAlarm(alarmId: string): Promise<DeviceAlarm>;
}

// ===== 常量 =====

export const DEVICE_SCHEDULE_TOOL_NAMES: string[] = [
  'calendar_events_list', 'calendar_event_create', 'calendar_event_update', 'calendar_event_delete',
  'reminders_list', 'reminder_create', 'reminder_update', 'reminder_delete', 'reminder_complete',
  'notification_schedule', 'notification_cancel',
  'alarm_schedule', 'alarms_list', 'alarm_cancel',
];

export const ALARM_WEEKDAY_ORDER: string[] = [
  'sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday',
];

const DAY_MS: number = 86400000;
const MAX_LIST_RANGE_MS: number = 366 * DAY_MS;
const DEFAULT_LIST_LIMIT: number = 30;
const MIN_FIRE_LEAD_MS: number = 5000;
const IGNORED_INPUT_KEYS: string[] = ['display_title'];

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

export interface ReminderEventMeta {
  hasDue: boolean;
  priority: number;
  completedAtMs: number | null;
}

export interface DecodedReminderDescription {
  notes: string;
  meta: ReminderEventMeta | null;
}

export interface ReminderEventWindow {
  startMs: number;
  endMs: number;
  allDay: boolean;
  /** 日程提前提醒分钟数(Calendar Kit reminderTime) */
  reminderMinutes: number[];
}

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

// ===== 输入读取 helper =====

class InputError extends Error {
  readonly code: DeviceScheduleErrorCode;

  constructor(code: DeviceScheduleErrorCode, message: string) {
    super(message);
    this.code = code;
  }
}

const MSG_INVALID_ARGS: string = '工具参数无效。';
const MSG_INVALID_DATE: string = '日期必须是 ISO-8601 格式(例如 2026-10-09T15:00:00+08:00)。';
const MSG_INVALID_RANGE: string = '日期范围无效或过大。';
const MSG_INVALID_RECURRENCE: string = '重复规则无效。';

const toObject = (input: JsonValue): JsonObject => {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) {
    throw new InputError('invalid_arguments', '参数不是有效的 JSON 对象。');
  }
  const out: JsonObject = {};
  const src: JsonObject = input as JsonObject;
  for (const k of Object.keys(src)) {
    if (!IGNORED_INPUT_KEYS.includes(k)) out[k] = src[k];
  }
  return out;
};

const assertKeysSubset = (obj: JsonObject, allowed: string[], message: string = MSG_INVALID_ARGS): void => {
  for (const k of Object.keys(obj)) {
    if (!allowed.includes(k)) throw new InputError('invalid_arguments', `${message}(未知字段 ${k})`);
  }
};

const has = (obj: JsonObject, key: string): boolean =>
  Object.prototype.hasOwnProperty.call(obj, key) && obj[key] !== null && obj[key] !== undefined;

const optString = (obj: JsonObject, key: string): string | null => {
  if (!has(obj, key)) return null;
  const v: JsonValue = obj[key];
  if (typeof v !== 'string') throw new InputError('invalid_arguments', `${key} 必须是字符串。`);
  return v;
};

const nonBlank = (v: string | null): string | null => {
  if (v === null) return null;
  const t: string = v.trim();
  return t.length > 0 ? t : null;
};

const optDate = (obj: JsonObject, key: string): number | null => {
  const raw: string | null = nonBlank(optString(obj, key));
  if (raw === null) return null;
  const ms: number | null = parseIsoDateTime(raw);
  if (ms === null) throw new InputError('invalid_date', `${key}: ${MSG_INVALID_DATE}`);
  return ms;
};

const requiredDate = (obj: JsonObject, key: string): number => {
  const ms: number | null = optDate(obj, key);
  if (ms === null) throw new InputError('invalid_date', `缺少 ${key}。${MSG_INVALID_DATE}`);
  return ms;
};

/** 整数(容忍数字字符串);非整数返回 NaN 表示类型错误 */
const optInteger = (obj: JsonObject, key: string): number | null => {
  if (!has(obj, key)) return null;
  const v: JsonValue = obj[key];
  let n: number = Number.NaN;
  if (typeof v === 'number') n = v;
  else if (typeof v === 'string' && /^-?\d+$/.test(v.trim())) n = Number(v.trim());
  if (!Number.isInteger(n)) throw new InputError('invalid_arguments', `${key} 必须是整数。`);
  return n;
};

const optNumber = (obj: JsonObject, key: string): number | null => {
  if (!has(obj, key)) return null;
  const v: JsonValue = obj[key];
  let n: number = Number.NaN;
  if (typeof v === 'number') n = v;
  else if (typeof v === 'string' && v.trim().length > 0) n = Number(v.trim());
  if (!Number.isFinite(n)) throw new InputError('invalid_arguments', `${key} 必须是数字。`);
  return n;
};

const optBoolean = (obj: JsonObject, key: string): boolean | null => {
  if (!has(obj, key)) return null;
  const v: JsonValue = obj[key];
  if (typeof v === 'boolean') return v;
  if (v === 'true') return true;
  if (v === 'false') return false;
  throw new InputError('invalid_arguments', `${key} 必须是布尔值。`);
};

const clampLimit = (obj: JsonObject): number => {
  let raw: number | null;
  try {
    raw = optInteger(obj, 'limit');
  } catch {
    raw = null; // iOS `as? Int` 失败 → 默认值
  }
  const n: number = raw ?? DEFAULT_LIST_LIMIT;
  return Math.min(Math.max(n, 1), 100);
};

const truncate = (s: string, max: number): string => (s.length > max ? s.slice(0, max) : s);

// ===== 结果 JSON =====

const textPart = (payload: JsonObject): UIMessagePart[] =>
  [{ type: 'text', text: JSON.stringify(payload), metadata: null }];

const errorCodeOf = (e: Error): DeviceScheduleErrorCode => {
  if (e instanceof DeviceScheduleError || e instanceof InputError) return e.code;
  const maybe: { code?: string; name?: string } = e as { code?: string; name?: string };
  if (maybe.name === 'DeviceScheduleError' && typeof maybe.code === 'string') {
    return maybe.code as DeviceScheduleErrorCode;
  }
  return 'platform_error';
};

/** errorKey:EventKit/通知族用 reason,闹钟族用 error(iOS 原样) */
const failure = (tool: string, e: Error, errorKey: 'reason' | 'error'): UIMessagePart[] => {
  const message: string = e.message.length > 0 ? e.message : '操作失败。';
  const payload: JsonObject = { ok: false, tool };
  payload[errorKey] = message;
  payload['error_code'] = errorCodeOf(e);
  return textPart(payload);
};

const asError = (e: unknown): Error => (e instanceof Error ? e : new Error(String(e)));

const recurrencePayload = (rule: DeviceRecurrenceRule | null): JsonObject => {
  if (rule === null) return {};
  const out: JsonObject = { frequency: rule.frequency, interval: rule.interval };
  if (rule.endMs !== null) out['end_at'] = formatIsoDateTime(rule.endMs);
  if (rule.count !== undefined && rule.count !== null && rule.count > 0) out['count'] = rule.count;
  if (rule.extraRules !== undefined && rule.extraRules.length > 0) out['other_rules'] = [...rule.extraRules];
  return out;
};

const FREQUENCY_UNIT: Record<DeviceRecurrenceFrequency, string> = {
  daily: 'day', weekly: 'week', monthly: 'month', yearly: 'year',
};

/** 人类可读的重复摘要(英文,供模型转述),如 "every 2 weeks, until 2026-12-31T00:00:00+08:00" */
export const recurrenceSummary = (rule: DeviceRecurrenceRule): string => {
  const unit: string = FREQUENCY_UNIT[rule.frequency];
  let out: string = rule.interval > 1 ? `every ${rule.interval} ${unit}s` : `every ${unit}`;
  if (rule.count !== undefined && rule.count !== null && rule.count > 0) out += `, ${rule.count} occurrences`;
  if (rule.endMs !== null) out += `, until ${formatIsoDateTime(rule.endMs)}`;
  if (rule.extraRules !== undefined && rule.extraRules.length > 0) {
    out += ` (plus ${rule.extraRules.join('/')} rules)`;
  }
  return out;
};

const eventFields = (ev: DeviceCalendarEvent): JsonObject => {
  const out: JsonObject = {
    event_id: ev.eventId,
    title: ev.title,
    start_at: formatIsoDateTime(ev.startMs),
    end_at: formatIsoDateTime(ev.endMs),
    all_day: ev.allDay,
    calendar_id: ev.calendarId,
    calendar: ev.calendarName,
    location: ev.location,
    notes: ev.notes,
    recurring: ev.recurrence !== null,
    recurrence: recurrencePayload(ev.recurrence),
  };
  if (ev.recurrence !== null) out['recurrence_summary'] = recurrenceSummary(ev.recurrence);
  return out;
};

const reminderFields = (r: DeviceReminder): JsonObject => {
  const out: JsonObject = {
    reminder_id: r.reminderId,
    title: r.title,
    completed: r.completed,
    list_id: r.listId,
    list: r.listName,
    notes: r.notes,
    priority: r.priority,
  };
  if (r.dueMs !== null) out['due_at'] = formatIsoDateTime(r.dueMs);
  if (r.completedAtMs !== null) out['completed_at'] = formatIsoDateTime(r.completedAtMs);
  return out;
};

const alarmFields = (a: DeviceAlarm): JsonObject => {
  const out: JsonObject = {
    alarm_id: a.alarmId,
    title: a.title,
    kind: a.kind,
    state: a.state,
    created_at: formatIsoDateTime(a.createdAtMs),
  };
  if (a.fireAtMs !== null) out['fire_at'] = formatIsoDateTime(a.fireAtMs);
  if (a.weekdays.length > 0) out['weekdays'] = a.weekdays;
  if (a.hour !== null) out['hour'] = a.hour;
  if (a.minute !== null) out['minute'] = a.minute;
  if (a.durationSeconds !== null) out['duration_seconds'] = a.durationSeconds;
  return out;
};

const withHeader = (tool: string, body: JsonObject): JsonObject => {
  const out: JsonObject = { ok: true, tool };
  for (const k of Object.keys(body)) out[k] = body[k];
  return out;
};

// ===== 重复规则(putCalendarRecurrenceFields + recurrenceRules) =====

const RECURRENCE_KEYS: string[] = ['recurrence', 'recurrence_interval', 'recurrence_end_at'];

interface RecurrenceParse {
  specified: boolean;
  rule: DeviceRecurrenceRule | null;
}

const parseRecurrence = (obj: JsonObject, startMs: number): RecurrenceParse => {
  const raw: string | null = optString(obj, 'recurrence');
  if (raw === null) {
    if (has(obj, 'recurrence_interval') || has(obj, 'recurrence_end_at')) {
      throw new InputError('invalid_recurrence', `${MSG_INVALID_RECURRENCE}(recurrence_interval/recurrence_end_at 需同时给出 recurrence)`);
    }
    return { specified: false, rule: null };
  }
  if (raw === 'none') return { specified: true, rule: null };
  if (raw !== 'daily' && raw !== 'weekly' && raw !== 'monthly') {
    throw new InputError('invalid_recurrence', `${MSG_INVALID_RECURRENCE}(recurrence 取 none/daily/weekly/monthly)`);
  }
  let interval: number | null;
  try {
    interval = optInteger(obj, 'recurrence_interval');
  } catch {
    throw new InputError('invalid_recurrence', MSG_INVALID_RECURRENCE);
  }
  const n: number = interval ?? 1;
  if (n < 1 || n > 30) throw new InputError('invalid_recurrence', `${MSG_INVALID_RECURRENCE}(recurrence_interval 取 1…30)`);
  let endMs: number | null;
  try {
    endMs = optDate(obj, 'recurrence_end_at');
  } catch {
    throw new InputError('invalid_recurrence', `${MSG_INVALID_RECURRENCE}(recurrence_end_at 必须是 ISO-8601)`);
  }
  if (endMs !== null && endMs <= startMs) {
    throw new InputError('invalid_recurrence', `${MSG_INVALID_RECURRENCE}(recurrence_end_at 必须晚于开始时间)`);
  }
  return { specified: true, rule: { frequency: raw, interval: n, endMs } };
};

const recurrenceSchema = (): JsonObject => ({
  recurrence: {
    type: 'string',
    description: 'Optional recurrence. Use none to remove recurrence on update.',
    enum: ['none', 'daily', 'weekly', 'monthly'],
  },
  recurrence_interval: {
    type: 'integer',
    description: 'Optional recurrence interval, clamped to 1...30. Defaults to 1.',
  },
  recurrence_end_at: {
    type: 'string',
    description: 'Optional ISO-8601 recurrence end time; must be later than event start.',
  },
});

const APPLY_TO_SCHEMA: JsonObject = {
  type: 'string',
  enum: ['series'],
  description: 'Required when the target event is recurring (recurring:true in calendar_events_list). '
    + 'HarmonyOS cannot change or delete a single occurrence, so the operation applies to ALL occurrences of the series. '
    + 'Pass "series" only after the user confirmed changing the whole series.',
};

/** apply_to 只接受 "series";缺省 → null */
const parseApplyTo = (obj: JsonObject): string | null => {
  const raw: string | null = optString(obj, 'apply_to');
  if (raw === null) return null;
  if (raw.trim() !== 'series') throw new InputError('invalid_arguments', `${MSG_INVALID_ARGS}(apply_to 只能取 series)`);
  return 'series';
};

const assertSeriesAcknowledged = (existing: DeviceCalendarEvent, applyTo: string | null, action: string): void => {
  if (existing.recurrence === null || applyTo === 'series') return;
  throw new InputError('recurring_series',
    `「${existing.title}」是重复日程(${recurrenceSummary(existing.recurrence)})。HarmonyOS 无法只${action}其中一次,`
    + `${action}会作用于该系列的全部日程。请先向用户确认,确认后带上 apply_to:"series" 重试。`);
};

const VISIBILITY_NOTE: string = 'Only calendars this app can read are included. With READ_CALENDAR, HarmonyOS may expose '
  + 'only the default calendar and calendars/events created by Amber; events owned by other apps or accounts may be missing. '
  + 'Recurring series are returned once (start_at/end_at = first occurrence; see recurring/recurrence_summary), not per occurrence.';

const withRecurrence = (props: JsonObject): JsonObject => {
  const out: JsonObject = { ...props };
  const r: JsonObject = recurrenceSchema();
  for (const k of Object.keys(r)) out[k] = r[k];
  return out;
};

// ===== 工具工厂 =====

type Exec = (obj: JsonObject) => Promise<JsonObject>;

interface ToolSpec {
  name: string;
  description: string;
  schema: () => InputSchemaObj;
  errorKey: 'reason' | 'error';
  run: Exec;
}

const buildTool = (spec: ToolSpec): AgentTool => makeAgentTool({
  name: spec.name,
  description: spec.description,
  parameters: spec.schema,
  // iOS:needsApproval + mandatoryApproval,禁止自动批准(读/写一致)
  needsApproval: true,
  mandatoryApproval: true,
  allowsAutoApproval: false,
  execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
    try {
      const obj: JsonObject = toObject(input);
      return textPart(withHeader(spec.name, await spec.run(obj)));
    } catch (e) {
      return failure(spec.name, asError(e), spec.errorKey);
    }
  },
});

const CAL_CREATE_KEYS: string[] = ['title', 'start_at', 'end_at', 'location', 'notes', 'calendar_id', ...RECURRENCE_KEYS];
const CAL_UPDATE_KEYS: string[] = ['event_id', 'apply_to', ...CAL_CREATE_KEYS];
const CAL_DELETE_KEYS: string[] = ['event_id', 'apply_to'];
const REM_CREATE_KEYS: string[] = ['title', 'due_at', 'notes', 'priority', 'list_id'];
const REM_UPDATE_KEYS: string[] = ['reminder_id', 'title', 'due_at', 'remove_due_date', 'notes', 'priority', 'list_id'];
const ALARM_KEYS: string[] = ['title', 'kind', 'fire_at', 'weekdays', 'hour', 'minute', 'duration_seconds'];

const requireId = (obj: JsonObject, key: string, notFoundName: string): string => {
  const id: string | null = nonBlank(optString(obj, key));
  if (id === null) throw new InputError('not_found', `找不到对应的${notFoundName}。`);
  return id;
};

const assertExactlyKey = (obj: JsonObject, key: string, notFoundName: string): string => {
  const keys: string[] = Object.keys(obj);
  if (keys.length !== 1 || keys[0] !== key) {
    throw new InputError('invalid_arguments', `${MSG_INVALID_ARGS}(只接受 ${key})`);
  }
  return requireId(obj, key, notFoundName);
};

const priorityFrom = (obj: JsonObject): number | null => {
  const p: number | null = optInteger(obj, 'priority');
  if (p !== null && (p < 0 || p > 9)) throw new InputError('invalid_arguments', 'priority 取 0…9。');
  return p;
};

const validateAlarm = (obj: JsonObject, nowMs: number): DeviceAlarmRequest => {
  assertKeysSubset(obj, ALARM_KEYS, '闹钟参数不完整或包含未知字段。');
  const title: string = (optString(obj, 'title') ?? '').trim();
  if (title.length === 0 || title.length > 80) {
    throw new InputError('invalid_arguments', '闹钟标题不能为空且不能超过 80 个字符。');
  }
  const kind: string | null = optString(obj, 'kind');
  if (kind !== 'one_time' && kind !== 'weekly' && kind !== 'timer') {
    throw new InputError('invalid_arguments', 'kind 取 one_time / weekly / timer。');
  }
  let weekdaysRaw: JsonValue[] = [];
  if (has(obj, 'weekdays')) {
    const w: JsonValue = obj['weekdays'];
    if (!Array.isArray(w) || w.some((x: JsonValue): boolean => typeof x !== 'string')) {
      throw new InputError('invalid_arguments', 'weekdays 必须是字符串数组。');
    }
    weekdaysRaw = w;
  }
  const hour: number | null = optInteger(obj, 'hour');
  const minute: number | null = optInteger(obj, 'minute');
  const duration: number | null = optNumber(obj, 'duration_seconds');
  const fireRaw: string | null = optString(obj, 'fire_at');
  if (kind === 'one_time') {
    const fireAt: number | null = fireRaw === null ? null : parseIsoDateTime(fireRaw);
    if (fireAt === null || fireAt - nowMs < MIN_FIRE_LEAD_MS || weekdaysRaw.length > 0
      || hour !== null || minute !== null || duration !== null) {
      throw new InputError('invalid_arguments', '一次性闹钟需要至少 5 秒后的 fire_at,且不能混用重复或计时器参数。');
    }
    return { title, kind, fireAtMs: fireAt, weekdays: [], hour: null, minute: null, durationSeconds: null };
  }
  if (kind === 'weekly') {
    const requested: string[] = weekdaysRaw.map((x: JsonValue): string => (x as string).toLowerCase());
    if (requested.length === 0 || requested.some((d: string): boolean => !ALARM_WEEKDAY_ORDER.includes(d))
      || hour === null || hour < 0 || hour > 23 || minute === null || minute < 0 || minute > 59
      || fireRaw !== null || duration !== null) {
      throw new InputError('invalid_arguments', '每周闹钟需要有效的 weekdays、hour 与 minute,且不能混用其他时间参数。');
    }
    const days: string[] = ALARM_WEEKDAY_ORDER.filter((d: string): boolean => requested.includes(d));
    return { title, kind, fireAtMs: null, weekdays: days, hour, minute, durationSeconds: null };
  }
  if (duration === null || duration < 10 || duration > 86400 || fireRaw !== null
    || weekdaysRaw.length > 0 || hour !== null || minute !== null) {
    throw new InputError('invalid_arguments', '计时器需要 10 到 86400 秒的 duration_seconds,且不能混用其他时间参数。');
  }
  return { title, kind, fireAtMs: null, weekdays: [], hour: null, minute: null, durationSeconds: duration };
};

export const createDeviceScheduleTools = (
  port: DeviceSchedulePort,
  now: () => number = (): number => Date.now(),
): AgentTool[] => {
  // ---------- calendar_events_list ----------
  const calendarEventsList: AgentTool = buildTool({
    name: 'calendar_events_list',
    description: 'Read HarmonyOS system calendar events (Calendar Kit) in a bounded time range after the user grants calendar access. '
      + 'Visibility may be limited: depending on system policy only the default calendar and Amber-created calendars/events '
      + 'are readable, so events from other apps or accounts may be missing (an empty result does not prove the user is free). '
      + 'Recurring series appear once (recurring:true with recurrence_summary) instead of per occurrence.',
    schema: () => makeInputSchemaObj({
      start_at: { type: 'string', description: 'Optional ISO-8601 start time. Defaults to now.' },
      end_at: { type: 'string', description: 'Optional ISO-8601 end time. Defaults to seven days after start_at.' },
      limit: { type: 'integer', description: 'Optional maximum results, clamped to 1...100. Defaults to 30.' },
    }),
    errorKey: 'reason',
    run: async (obj: JsonObject): Promise<JsonObject> => {
      assertKeysSubset(obj, ['start_at', 'end_at', 'limit']);
      const start: number = optDate(obj, 'start_at') ?? now();
      const end: number = optDate(obj, 'end_at') ?? start + 7 * DAY_MS;
      if (end <= start || end - start > MAX_LIST_RANGE_MS) throw new InputError('invalid_date_range', MSG_INVALID_RANGE);
      const limit: number = clampLimit(obj);
      const events: DeviceCalendarEvent[] = (await port.listCalendarEvents(start, end))
        .slice()
        .sort((a: DeviceCalendarEvent, b: DeviceCalendarEvent): number => a.startMs - b.startMs)
        .slice(0, limit);
      return {
        start_at: formatIsoDateTime(start),
        end_at: formatIsoDateTime(end),
        events: events.map((ev: DeviceCalendarEvent): JsonObject => eventFields(ev)),
        visibility_note: VISIBILITY_NOTE,
      };
    },
  });

  // ---------- calendar_event_create ----------
  const calendarEventCreate: AgentTool = buildTool({
    name: 'calendar_event_create',
    description: 'Create one event in the HarmonyOS system calendar. The host requires foreground approval before writing.',
    schema: () => makeInputSchemaObj(withRecurrence({
      title: { type: 'string' },
      start_at: { type: 'string', description: 'ISO-8601 event start time.' },
      end_at: { type: 'string', description: 'ISO-8601 event end time; must be later than start_at.' },
      location: { type: 'string' },
      notes: { type: 'string' },
      calendar_id: { type: 'string', description: 'Optional calendar identifier from calendar_events_list. Omit to use the default calendar.' },
    }), ['title', 'start_at', 'end_at']),
    errorKey: 'reason',
    run: async (obj: JsonObject): Promise<JsonObject> => {
      assertKeysSubset(obj, CAL_CREATE_KEYS);
      const title: string | null = nonBlank(optString(obj, 'title'));
      if (title === null) throw new InputError('invalid_arguments', `${MSG_INVALID_ARGS}(title 不能为空)`);
      const start: number = requiredDate(obj, 'start_at');
      const end: number = requiredDate(obj, 'end_at');
      if (end <= start) throw new InputError('invalid_date_range', `${MSG_INVALID_RANGE}(end_at 必须晚于 start_at)`);
      const recurrence: RecurrenceParse = parseRecurrence(obj, start);
      const created: DeviceCalendarEvent = await port.createCalendarEvent({
        title: truncate(title, 200),
        startMs: start,
        endMs: end,
        allDay: false,
        location: truncate(optString(obj, 'location') ?? '', 300),
        notes: truncate(optString(obj, 'notes') ?? '', 2000),
        calendarId: nonBlank(optString(obj, 'calendar_id')),
        recurrence: recurrence.rule,
        recurrenceSpecified: true,
      });
      return eventFields(created);
    },
  });

  // ---------- calendar_event_update ----------
  const calendarEventUpdate: AgentTool = buildTool({
    name: 'calendar_event_update',
    description: 'Update one existing HarmonyOS calendar event by event_id. Only provided fields change; omitting recurrence '
      + 'keeps the existing recurrence rule untouched. For a recurring event the change applies to ALL occurrences of the series '
      + '(HarmonyOS has no per-occurrence editing), so apply_to:"series" is required, otherwise the call fails with '
      + 'error_code recurring_series. Changing calendar_id or removing recurrence (recurrence:"none") recreates the event '
      + '(the event_id changes). Foreground approval is required.',
    schema: () => makeInputSchemaObj(withRecurrence({
      event_id: { type: 'string' },
      apply_to: APPLY_TO_SCHEMA,
      title: { type: 'string' },
      start_at: { type: 'string', description: 'Optional ISO-8601 start time.' },
      end_at: { type: 'string', description: 'Optional ISO-8601 end time.' },
      location: { type: 'string', description: 'Optional. Pass an empty string to clear.' },
      notes: { type: 'string', description: 'Optional. Pass an empty string to clear.' },
      calendar_id: { type: 'string', description: 'Optional destination calendar identifier.' },
    }), ['event_id']),
    errorKey: 'reason',
    run: async (obj: JsonObject): Promise<JsonObject> => {
      assertKeysSubset(obj, CAL_UPDATE_KEYS);
      const changeKeys: string[] = Object.keys(obj).filter((k: string): boolean => k !== 'event_id' && k !== 'apply_to');
      if (changeKeys.length === 0) throw new InputError('invalid_arguments', `${MSG_INVALID_ARGS}(至少提供一个要修改的字段)`);
      const eventId: string = requireId(obj, 'event_id', '日历事件');
      const applyTo: string | null = parseApplyTo(obj);
      const existing: DeviceCalendarEvent | null = await port.getCalendarEvent(eventId);
      if (existing === null) throw new InputError('not_found', '找不到对应的日历事件。');
      let title: string = existing.title;
      if (has(obj, 'title')) {
        const t: string | null = nonBlank(optString(obj, 'title'));
        if (t === null) throw new InputError('invalid_arguments', `${MSG_INVALID_ARGS}(title 不能为空)`);
        title = truncate(t, 200);
      }
      const start: number = has(obj, 'start_at') ? requiredDate(obj, 'start_at') : existing.startMs;
      const end: number = has(obj, 'end_at') ? requiredDate(obj, 'end_at') : existing.endMs;
      if (end <= start) throw new InputError('invalid_date_range', `${MSG_INVALID_RANGE}(end_at 必须晚于 start_at)`);
      const recurrence: RecurrenceParse = parseRecurrence(obj, start);
      const location: string | null = optString(obj, 'location');
      const notes: string | null = optString(obj, 'notes');
      const calendarId: string | null = has(obj, 'calendar_id') ? nonBlank(optString(obj, 'calendar_id')) : null;
      if (has(obj, 'calendar_id') && calendarId === null) {
        throw new InputError('not_found', '找不到对应的可写日历。');
      }
      assertSeriesAcknowledged(existing, applyTo, '修改');
      const updated: DeviceCalendarEvent = await port.updateCalendarEvent(eventId, {
        title,
        startMs: start,
        endMs: end,
        allDay: existing.allDay && !has(obj, 'start_at') && !has(obj, 'end_at'),
        location: location !== null ? truncate(location, 300) : existing.location,
        notes: notes !== null ? truncate(notes, 2000) : existing.notes,
        calendarId: calendarId !== null && calendarId !== existing.calendarId ? calendarId : null,
        recurrence: recurrence.specified ? recurrence.rule : existing.recurrence,
        recurrenceSpecified: recurrence.specified,
      });
      return eventFields(updated);
    },
  });

  // ---------- calendar_event_delete ----------
  const calendarEventDelete: AgentTool = buildTool({
    name: 'calendar_event_delete',
    description: 'Delete one existing HarmonyOS calendar event by event_id. For a recurring event this deletes ALL occurrences '
      + 'of the series (HarmonyOS has no per-occurrence deletion), so apply_to:"series" is required, otherwise the call fails '
      + 'with error_code recurring_series. Foreground approval is required.',
    schema: () => makeInputSchemaObj({ event_id: { type: 'string' }, apply_to: APPLY_TO_SCHEMA }, ['event_id']),
    errorKey: 'reason',
    run: async (obj: JsonObject): Promise<JsonObject> => {
      assertKeysSubset(obj, CAL_DELETE_KEYS, `${MSG_INVALID_ARGS}(只接受 event_id 与 apply_to)`);
      const eventId: string = requireId(obj, 'event_id', '日历事件');
      const applyTo: string | null = parseApplyTo(obj);
      const existing: DeviceCalendarEvent | null = await port.getCalendarEvent(eventId);
      if (existing === null) throw new InputError('not_found', '找不到对应的日历事件。');
      assertSeriesAcknowledged(existing, applyTo, '删除');
      await port.deleteCalendarEvent(eventId);
      const out: JsonObject = { event_id: eventId, title: existing.title, deleted: true };
      if (existing.recurrence !== null) out['deleted_series'] = true;
      return out;
    },
  });

  // ---------- reminders_list ----------
  const remindersList: AgentTool = buildTool({
    name: 'reminders_list',
    description: 'Read a bounded list of Amber reminders. HarmonyOS has no system Reminders app, so reminders live as '
      + 'events in the app-owned "Amber 提醒" calendar (visible in the system Calendar). Requires calendar access.',
    schema: () => makeInputSchemaObj({
      include_completed: { type: 'boolean', description: 'Optional. Include completed reminders. Defaults to false.' },
      limit: { type: 'integer', description: 'Optional maximum results, clamped to 1...100. Defaults to 30.' },
    }),
    errorKey: 'reason',
    run: async (obj: JsonObject): Promise<JsonObject> => {
      assertKeysSubset(obj, ['include_completed', 'limit']);
      let includeCompleted: boolean;
      try {
        includeCompleted = optBoolean(obj, 'include_completed') ?? false;
      } catch {
        includeCompleted = false;
      }
      const limit: number = clampLimit(obj);
      const due = (r: DeviceReminder): number => r.dueMs ?? Number.MAX_SAFE_INTEGER;
      const items: DeviceReminder[] = (await port.listReminders())
        .filter((r: DeviceReminder): boolean => includeCompleted || !r.completed)
        .sort((a: DeviceReminder, b: DeviceReminder): number => due(a) - due(b))
        .slice(0, limit);
      return { reminders: items.map((r: DeviceReminder): JsonObject => reminderFields(r)) };
    },
  });

  // ---------- reminder_create ----------
  const reminderCreate: AgentTool = buildTool({
    name: 'reminder_create',
    description: 'Create one Amber reminder (stored as an event with an alert in the app-owned "Amber 提醒" calendar, '
      + 'since HarmonyOS has no Reminders app). Reminders without due_at become an all-day item on today without an alert. '
      + 'The host requires foreground approval before writing.',
    schema: () => makeInputSchemaObj({
      title: { type: 'string' },
      due_at: { type: 'string', description: 'Optional ISO-8601 due time. The system calendar alerts at this time.' },
      notes: { type: 'string' },
      priority: { type: 'integer', description: 'Optional priority from 0 (none) to 9; 1...4 mark the event as important.' },
      list_id: { type: 'string', description: 'Optional reminders-list identifier. Only the Amber reminders list exists on HarmonyOS; omit it.' },
    }, ['title']),
    errorKey: 'reason',
    run: async (obj: JsonObject): Promise<JsonObject> => {
      assertKeysSubset(obj, REM_CREATE_KEYS);
      const title: string | null = nonBlank(optString(obj, 'title'));
      if (title === null) throw new InputError('invalid_arguments', `${MSG_INVALID_ARGS}(title 不能为空)`);
      const created: DeviceReminder = await port.createReminder({
        title: truncate(title, 200),
        dueMs: optDate(obj, 'due_at'),
        notes: truncate(optString(obj, 'notes') ?? '', 2000),
        priority: priorityFrom(obj) ?? 0,
        completed: false,
        completedAtMs: null,
        listId: nonBlank(optString(obj, 'list_id')),
      });
      return reminderFields(created);
    },
  });

  // ---------- reminder_update ----------
  const reminderUpdate: AgentTool = buildTool({
    name: 'reminder_update',
    description: 'Update one Amber reminder by reminder_id. Only provided fields change. Foreground approval is required.',
    schema: () => makeInputSchemaObj({
      reminder_id: { type: 'string' },
      title: { type: 'string' },
      due_at: { type: 'string', description: 'Optional ISO-8601 due time.' },
      remove_due_date: { type: 'boolean', description: 'Optional. Set true to clear the due date.' },
      notes: { type: 'string', description: 'Optional. Pass an empty string to clear.' },
      priority: { type: 'integer', description: 'Optional priority from 0 to 9.' },
      list_id: { type: 'string', description: 'Optional destination reminders-list identifier (only the Amber list exists).' },
    }, ['reminder_id']),
    errorKey: 'reason',
    run: async (obj: JsonObject): Promise<JsonObject> => {
      assertKeysSubset(obj, REM_UPDATE_KEYS);
      if (Object.keys(obj).length < 2) throw new InputError('invalid_arguments', `${MSG_INVALID_ARGS}(至少提供一个要修改的字段)`);
      const reminderId: string = requireId(obj, 'reminder_id', '提醒事项');
      const removeDue: boolean = optBoolean(obj, 'remove_due_date') ?? false;
      if (removeDue && has(obj, 'due_at')) {
        throw new InputError('invalid_arguments', `${MSG_INVALID_ARGS}(remove_due_date 与 due_at 不能同时给出)`);
      }
      const existing: DeviceReminder | null = await port.getReminder(reminderId);
      if (existing === null) throw new InputError('not_found', '找不到对应的提醒事项。');
      let title: string = existing.title;
      if (has(obj, 'title')) {
        const t: string | null = nonBlank(optString(obj, 'title'));
        if (t === null) throw new InputError('invalid_arguments', `${MSG_INVALID_ARGS}(title 不能为空)`);
        title = truncate(t, 200);
      }
      let dueMs: number | null = existing.dueMs;
      if (removeDue) dueMs = null;
      else if (has(obj, 'due_at')) dueMs = requiredDate(obj, 'due_at');
      const notes: string | null = optString(obj, 'notes');
      const priority: number | null = priorityFrom(obj);
      const listId: string | null = has(obj, 'list_id') ? nonBlank(optString(obj, 'list_id')) : null;
      if (has(obj, 'list_id') && listId === null) throw new InputError('not_found', '找不到对应的可写提醒清单。');
      const updated: DeviceReminder = await port.updateReminder(reminderId, {
        title,
        dueMs,
        notes: notes !== null ? truncate(notes, 2000) : existing.notes,
        priority: priority ?? existing.priority,
        completed: existing.completed,
        completedAtMs: existing.completedAtMs,
        listId,
      });
      return reminderFields(updated);
    },
  });

  // ---------- reminder_delete ----------
  const reminderDelete: AgentTool = buildTool({
    name: 'reminder_delete',
    description: 'Delete one Amber reminder by reminder_id. Foreground approval is required.',
    schema: () => makeInputSchemaObj({ reminder_id: { type: 'string' } }, ['reminder_id']),
    errorKey: 'reason',
    run: async (obj: JsonObject): Promise<JsonObject> => {
      const reminderId: string = assertExactlyKey(obj, 'reminder_id', '提醒事项');
      const existing: DeviceReminder | null = await port.getReminder(reminderId);
      if (existing === null) throw new InputError('not_found', '找不到对应的提醒事项。');
      await port.deleteReminder(reminderId);
      return { reminder_id: reminderId, title: existing.title, deleted: true };
    },
  });

  // ---------- reminder_complete ----------
  const reminderComplete: AgentTool = buildTool({
    name: 'reminder_complete',
    description: 'Mark one Amber reminder complete by identifier. On HarmonyOS the calendar event is kept, its alert is removed '
      + 'and its title gets a "✓ " prefix. The host requires foreground approval before writing.',
    schema: () => makeInputSchemaObj({ reminder_id: { type: 'string' } }, ['reminder_id']),
    errorKey: 'reason',
    run: async (obj: JsonObject): Promise<JsonObject> => {
      const reminderId: string = assertExactlyKey(obj, 'reminder_id', '提醒事项');
      const existing: DeviceReminder | null = await port.getReminder(reminderId);
      if (existing === null) throw new InputError('not_found', '找不到对应的提醒事项。');
      const updated: DeviceReminder = await port.updateReminder(reminderId, {
        title: existing.title,
        dueMs: existing.dueMs,
        notes: existing.notes,
        priority: existing.priority,
        completed: true,
        completedAtMs: existing.completed && existing.completedAtMs !== null ? existing.completedAtMs : now(),
        listId: null,
      });
      return reminderFields(updated);
    },
  });

  // ---------- notification_schedule ----------
  const notificationSchedule: AgentTool = buildTool({
    name: 'notification_schedule',
    description: 'Schedule one local Amber notification via the HarmonyOS reminder agent. Only one agent notification is '
      + 'pending at a time; scheduling replaces the previous one. The host requires foreground approval before scheduling it.',
    schema: () => makeInputSchemaObj({
      title: { type: 'string' },
      body: { type: 'string' },
      fire_at: { type: 'string', description: 'ISO-8601 time at least five seconds in the future.' },
    }, ['title', 'fire_at']),
    errorKey: 'reason',
    run: async (obj: JsonObject): Promise<JsonObject> => {
      assertKeysSubset(obj, ['title', 'body', 'fire_at'], '需要 title 与 ISO-8601 fire_at。');
      const titleRaw: string | null = optString(obj, 'title');
      const fireRaw: string | null = optString(obj, 'fire_at');
      const fireAt: number | null = fireRaw === null ? null : parseIsoDateTime(fireRaw);
      if (titleRaw === null) throw new InputError('invalid_arguments', '需要 title 与 ISO-8601 fire_at。');
      if (fireAt === null) throw new InputError('invalid_date', '需要 title 与 ISO-8601 fire_at。');
      if (fireAt - now() < MIN_FIRE_LEAD_MS) throw new InputError('invalid_date', '提醒时间至少需要在 5 秒以后。');
      const title: string = titleRaw.trim();
      const body: string = (optString(obj, 'body') ?? '').trim();
      const scheduled: DeviceScheduledNotification = await port.scheduleNotification({
        title: title.length > 0 ? truncate(title, 80) : 'Amber 提醒',
        body: body.length > 0 ? truncate(body, 240) : '点按返回最近对话。',
        fireAtMs: fireAt,
      });
      return {
        identifier: scheduled.notificationId,
        title: scheduled.title,
        body: scheduled.body,
        fire_at: formatIsoDateTime(scheduled.fireAtMs),
      };
    },
  });

  // ---------- notification_cancel ----------
  const notificationCancel: AgentTool = buildTool({
    name: 'notification_cancel',
    description: 'Cancel the local notification previously scheduled by the Amber agent. Fails (ok:false) if the system '
      + 'could not cancel it, in which case the notification may still fire.',
    schema: () => makeInputSchemaObj({}),
    errorKey: 'reason',
    run: async (obj: JsonObject): Promise<JsonObject> => {
      if (Object.keys(obj).length > 0) throw new InputError('invalid_arguments', 'notification_cancel 不接受参数。');
      const hadPending: boolean = await port.cancelNotification();
      return { cancelled: true, had_pending: hadPending };
    },
  });

  // ---------- alarm_schedule ----------
  const alarmSchedule: AgentTool = buildTool({
    name: 'alarm_schedule',
    description: 'Schedule one Amber alarm or countdown timer through the HarmonyOS reminder agent (rings with a system '
      + 'reminder notification; it may not override silent mode or Do Not Disturb, and the system caps reminders per app). '
      + 'Foreground approval is always required.',
    schema: () => makeInputSchemaObj({
      title: { type: 'string', description: 'Required, maximum 80 characters.' },
      kind: { type: 'string', enum: ['one_time', 'weekly', 'timer'] },
      fire_at: { type: 'string', description: 'For one_time only: ISO-8601 time at least five seconds in the future.' },
      weekdays: {
        type: 'array',
        description: 'For weekly only. One or more English weekday names.',
        items: { type: 'string', enum: [...ALARM_WEEKDAY_ORDER] },
      },
      hour: { type: 'integer', description: 'For weekly only: 0...23.' },
      minute: { type: 'integer', description: 'For weekly only: 0...59.' },
      duration_seconds: { type: 'number', description: 'For timer only: 10...86400 seconds.' },
    }, ['title', 'kind']),
    errorKey: 'error',
    run: async (obj: JsonObject): Promise<JsonObject> => {
      const request: DeviceAlarmRequest = validateAlarm(obj, now());
      const alarm: DeviceAlarm = await port.scheduleAlarm(request);
      return { alarm: alarmFields(alarm) };
    },
  });

  // ---------- alarms_list ----------
  const alarmsList: AgentTool = buildTool({
    name: 'alarms_list',
    description: 'List only the active alarms and timers previously scheduled by Amber. Foreground approval is required.',
    schema: () => makeInputSchemaObj({}),
    errorKey: 'error',
    run: async (obj: JsonObject): Promise<JsonObject> => {
      if (Object.keys(obj).length > 0) throw new InputError('invalid_arguments', '闹钟参数不完整或包含未知字段。');
      const alarms: DeviceAlarm[] = await port.listAlarms();
      return { alarms: alarms.map((a: DeviceAlarm): JsonObject => alarmFields(a)) };
    },
  });

  // ---------- alarm_cancel ----------
  const alarmCancel: AgentTool = buildTool({
    name: 'alarm_cancel',
    description: 'Cancel one Amber-owned alarm or timer by alarm_id. Foreground approval is always required.',
    schema: () => makeInputSchemaObj({ alarm_id: { type: 'string' } }, ['alarm_id']),
    errorKey: 'error',
    run: async (obj: JsonObject): Promise<JsonObject> => {
      const keys: string[] = Object.keys(obj);
      const raw: string | null = keys.length === 1 && keys[0] === 'alarm_id' ? nonBlank(optString(obj, 'alarm_id')) : null;
      if (raw === null || !/^\d+$/.test(raw)) throw new InputError('invalid_arguments', 'alarm_id 不是有效的闹钟 ID。');
      const cancelled: DeviceAlarm = await port.cancelAlarm(raw);
      return { alarm_id: cancelled.alarmId, cancelled: true };
    },
  });

  return [
    calendarEventsList, calendarEventCreate, calendarEventUpdate, calendarEventDelete,
    remindersList, reminderCreate, reminderUpdate, reminderDelete, reminderComplete,
    notificationSchedule, notificationCancel,
    alarmSchedule, alarmsList, alarmCancel,
  ];
};
