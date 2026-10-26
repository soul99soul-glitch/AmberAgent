import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessagePart } from './message.ts';
import type { AgentTool, InputSchemaObj } from './tool.ts';
import { makeAgentTool } from './tool.ts';
import type {
  DeviceScheduleErrorCode, DeviceRecurrenceFrequency, DeviceRecurrenceRule, DeviceCalendarEvent,
  DeviceReminder, DeviceAlarmRequest, DeviceAlarm,
} from './device_schedule_contracts.ts';
import { DeviceScheduleError, ALARM_WEEKDAY_ORDER } from './device_schedule_contracts.ts';
import { parseIsoDateTime, formatIsoDateTime } from './device_schedule_codec.ts';

export const DAY_MS: number = 86400000;
export const MAX_LIST_RANGE_MS: number = 366 * DAY_MS;
const DEFAULT_LIST_LIMIT: number = 30;
export const MIN_FIRE_LEAD_MS: number = 5000;
const IGNORED_INPUT_KEYS: string[] = ['display_title'];

// ===== 输入读取 helper =====

export class InputError extends Error {
  readonly code: DeviceScheduleErrorCode;

  constructor(code: DeviceScheduleErrorCode, message: string) {
    super(message);
    this.code = code;
  }
}

export const MSG_INVALID_ARGS: string = '工具参数无效。';
const MSG_INVALID_DATE: string = '日期必须是 ISO-8601 格式(例如 2026-10-09T15:00:00+08:00)。';
export const MSG_INVALID_RANGE: string = '日期范围无效或过大。';
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

export const assertKeysSubset = (obj: JsonObject, allowed: string[], message: string = MSG_INVALID_ARGS): void => {
  for (const k of Object.keys(obj)) {
    if (!allowed.includes(k)) throw new InputError('invalid_arguments', `${message}(未知字段 ${k})`);
  }
};

export const has = (obj: JsonObject, key: string): boolean =>
  Object.prototype.hasOwnProperty.call(obj, key) && obj[key] !== null && obj[key] !== undefined;

export const optString = (obj: JsonObject, key: string): string | null => {
  if (!has(obj, key)) return null;
  const v: JsonValue = obj[key];
  if (typeof v !== 'string') throw new InputError('invalid_arguments', `${key} 必须是字符串。`);
  return v;
};

export const nonBlank = (v: string | null): string | null => {
  if (v === null) return null;
  const t: string = v.trim();
  return t.length > 0 ? t : null;
};

export const optDate = (obj: JsonObject, key: string): number | null => {
  const raw: string | null = nonBlank(optString(obj, key));
  if (raw === null) return null;
  const ms: number | null = parseIsoDateTime(raw);
  if (ms === null) throw new InputError('invalid_date', `${key}: ${MSG_INVALID_DATE}`);
  return ms;
};

export const requiredDate = (obj: JsonObject, key: string): number => {
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

export const optBoolean = (obj: JsonObject, key: string): boolean | null => {
  if (!has(obj, key)) return null;
  const v: JsonValue = obj[key];
  if (typeof v === 'boolean') return v;
  if (v === 'true') return true;
  if (v === 'false') return false;
  throw new InputError('invalid_arguments', `${key} 必须是布尔值。`);
};

export const clampLimit = (obj: JsonObject): number => {
  let raw: number | null;
  try {
    raw = optInteger(obj, 'limit');
  } catch {
    raw = null; // iOS `as? Int` 失败 → 默认值
  }
  const n: number = raw ?? DEFAULT_LIST_LIMIT;
  return Math.min(Math.max(n, 1), 100);
};

export const truncate = (s: string, max: number): string => (s.length > max ? s.slice(0, max) : s);

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

export const eventFields = (ev: DeviceCalendarEvent): JsonObject => {
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

export const reminderFields = (r: DeviceReminder): JsonObject => {
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

export const alarmFields = (a: DeviceAlarm): JsonObject => {
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

export interface RecurrenceParse {
  specified: boolean;
  rule: DeviceRecurrenceRule | null;
}

export const parseRecurrence = (obj: JsonObject, startMs: number): RecurrenceParse => {
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

export const APPLY_TO_SCHEMA: JsonObject = {
  type: 'string',
  enum: ['series'],
  description: 'Required when the target event is recurring (recurring:true in calendar_events_list). '
    + 'HarmonyOS cannot change or delete a single occurrence, so the operation applies to ALL occurrences of the series. '
    + 'Pass "series" only after the user confirmed changing the whole series.',
};

/** apply_to 只接受 "series";缺省 → null */
export const parseApplyTo = (obj: JsonObject): string | null => {
  const raw: string | null = optString(obj, 'apply_to');
  if (raw === null) return null;
  if (raw.trim() !== 'series') throw new InputError('invalid_arguments', `${MSG_INVALID_ARGS}(apply_to 只能取 series)`);
  return 'series';
};

export const assertSeriesAcknowledged = (existing: DeviceCalendarEvent, applyTo: string | null, action: string): void => {
  if (existing.recurrence === null || applyTo === 'series') return;
  throw new InputError('recurring_series',
    `「${existing.title}」是重复日程(${recurrenceSummary(existing.recurrence)})。HarmonyOS 无法只${action}其中一次,`
    + `${action}会作用于该系列的全部日程。请先向用户确认,确认后带上 apply_to:"series" 重试。`);
};

export const VISIBILITY_NOTE: string = 'Only calendars this app can read are included. With READ_CALENDAR, HarmonyOS may expose '
  + 'only the default calendar and calendars/events created by Amber; events owned by other apps or accounts may be missing. '
  + 'Recurring series are returned once (start_at/end_at = first occurrence; see recurring/recurrence_summary), not per occurrence.';

export const withRecurrence = (props: JsonObject): JsonObject => {
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

export const buildTool = (spec: ToolSpec): AgentTool => makeAgentTool({
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

export const CAL_CREATE_KEYS: string[] = ['title', 'start_at', 'end_at', 'location', 'notes', 'calendar_id', ...RECURRENCE_KEYS];
export const CAL_UPDATE_KEYS: string[] = ['event_id', 'apply_to', ...CAL_CREATE_KEYS];
export const CAL_DELETE_KEYS: string[] = ['event_id', 'apply_to'];
export const REM_CREATE_KEYS: string[] = ['title', 'due_at', 'notes', 'priority', 'list_id'];
export const REM_UPDATE_KEYS: string[] = ['reminder_id', 'title', 'due_at', 'remove_due_date', 'notes', 'priority', 'list_id'];
const ALARM_KEYS: string[] = ['title', 'kind', 'fire_at', 'weekdays', 'hour', 'minute', 'duration_seconds'];

export const requireId = (obj: JsonObject, key: string, notFoundName: string): string => {
  const id: string | null = nonBlank(optString(obj, key));
  if (id === null) throw new InputError('not_found', `找不到对应的${notFoundName}。`);
  return id;
};

export const assertExactlyKey = (obj: JsonObject, key: string, notFoundName: string): string => {
  const keys: string[] = Object.keys(obj);
  if (keys.length !== 1 || keys[0] !== key) {
    throw new InputError('invalid_arguments', `${MSG_INVALID_ARGS}(只接受 ${key})`);
  }
  return requireId(obj, key, notFoundName);
};

export const priorityFrom = (obj: JsonObject): number | null => {
  const p: number | null = optInteger(obj, 'priority');
  if (p !== null && (p < 0 || p > 9)) throw new InputError('invalid_arguments', 'priority 取 0…9。');
  return p;
};

export const validateAlarm = (obj: JsonObject, nowMs: number): DeviceAlarmRequest => {
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

