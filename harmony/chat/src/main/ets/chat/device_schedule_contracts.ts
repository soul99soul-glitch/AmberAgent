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

export const DEVICE_SCHEDULE_TOOL_NAMES: string[] = [
  'calendar_events_list', 'calendar_event_create', 'calendar_event_update', 'calendar_event_delete',
  'reminders_list', 'reminder_create', 'reminder_update', 'reminder_delete', 'reminder_complete',
  'notification_schedule', 'notification_cancel',
  'alarm_schedule', 'alarms_list', 'alarm_cancel',
];

export const ALARM_WEEKDAY_ORDER: string[] = [
  'sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday',
];

