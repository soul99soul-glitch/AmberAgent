// Calendar Kit owns both user events and the Amber reminders calendar.
// Its cache and in-flight account creation remain shared across port instances.
import { calendarManager } from '@kit.CalendarKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { DeviceScheduleError, decodeReminderEventDescription, decodeReminderEventTitle,
  encodeReminderEventDescription, encodeReminderEventTitle, reminderEventWindow } from '@amber/chat-domain';
import type { DecodedReminderDescription, DeviceCalendarEvent, DeviceCalendarEventDraft,
  DeviceRecurrenceFrequency, DeviceRecurrenceRule, DeviceReminder, DeviceReminderDraft,
  ReminderEventMeta, ReminderEventWindow } from '@amber/chat-domain';
import { DOMAIN, TAG, READ_CALENDAR, WRITE_CALENDAR, abilityContext, ensurePermissions,
  toScheduleError, parseNumericId } from './DeviceScheduleAccess.ets';

const REMINDER_ACCOUNT_NAME: string = 'amber_reminders';
const REMINDER_ACCOUNT_DISPLAY: string = 'Amber 提醒';

class LocatedEvent {
  calendar: calendarManager.Calendar;
  event: calendarManager.Event;

  constructor(calendar: calendarManager.Calendar, event: calendarManager.Event) {
    this.calendar = calendar;
    this.event = event;
  }
}

class EventRef {
  calendarId: number = 0;
  eventId: number = 0;
}

const parseEventRef = (raw: string): EventRef | null => {
  const m: RegExpExecArray | null = /^(\d+):(\d+)$/.exec(raw.trim());
  if (m === null) return null;
  const ref: EventRef = new EventRef();
  ref.calendarId = Number(m[1]);
  ref.eventId = Number(m[2]);
  return ref;
};

const calendarMgr = (): calendarManager.CalendarManager => calendarManager.getCalendarManager(abilityContext());

const isReminderCalendar = (cal: calendarManager.Calendar): boolean => {
  const account: calendarManager.CalendarAccount = cal.getAccount();
  return account.name === REMINDER_ACCOUNT_NAME && account.type === calendarManager.CalendarType.LOCAL;
};

const calendarDisplayName = (cal: calendarManager.Calendar): string => {
  const account: calendarManager.CalendarAccount = cal.getAccount();
  const display: string | undefined = account.displayName;
  return display !== undefined && display.length > 0 ? display : account.name;
};

const frequencyFromHarmony = (f: calendarManager.RecurrenceFrequency): DeviceRecurrenceFrequency => {
  if (f === calendarManager.RecurrenceFrequency.DAILY) return 'daily';
  if (f === calendarManager.RecurrenceFrequency.WEEKLY) return 'weekly';
  if (f === calendarManager.RecurrenceFrequency.MONTHLY) return 'monthly';
  return 'yearly';
};

const frequencyToHarmony = (f: DeviceRecurrenceFrequency): calendarManager.RecurrenceFrequency => {
  if (f === 'daily') return calendarManager.RecurrenceFrequency.DAILY;
  if (f === 'weekly') return calendarManager.RecurrenceFrequency.WEEKLY;
  if (f === 'monthly') return calendarManager.RecurrenceFrequency.MONTHLY;
  return calendarManager.RecurrenceFrequency.YEARLY;
};

const hasItems = (values: number[] | undefined): boolean => values !== undefined && values.length > 0;

const recurrenceFromHarmony = (rule: calendarManager.RecurrenceRule | undefined): DeviceRecurrenceRule | null => {
  if (rule === undefined) return null;
  const frequency: calendarManager.RecurrenceFrequency | undefined = rule.recurrenceFrequency;
  if (frequency === undefined) return null;
  const interval: number = rule.interval !== undefined && rule.interval > 0 ? rule.interval : 1;
  const expire: number | undefined = rule.expire;
  const count: number | undefined = rule.count;
  // Amber 无法表达的附加字段:仅用于展示,更新时由 buildCalendarEvent 原样透传
  const extra: string[] = [];
  if (hasItems(rule.daysOfWeek)) extra.push('days_of_week');
  if (hasItems(rule.daysOfMonth)) extra.push('days_of_month');
  if (hasItems(rule.daysOfYear)) extra.push('days_of_year');
  if (hasItems(rule.weeksOfMonth)) extra.push('weeks_of_month');
  if (hasItems(rule.weeksOfYear)) extra.push('weeks_of_year');
  if (hasItems(rule.monthsOfYear)) extra.push('months_of_year');
  if (hasItems(rule.excludedDates)) extra.push('excluded_dates');
  const out: DeviceRecurrenceRule = {
    frequency: frequencyFromHarmony(frequency),
    interval: interval,
    endMs: expire !== undefined && expire > 0 ? expire : null,
    count: count !== undefined && count > 0 ? count : null,
    extraRules: extra,
  };
  return out;
};

const recurrenceToHarmony = (rule: DeviceRecurrenceRule): calendarManager.RecurrenceRule => {
  const out: calendarManager.RecurrenceRule = {
    recurrenceFrequency: frequencyToHarmony(rule.frequency),
    interval: rule.interval,
  };
  if (rule.endMs !== null) out.expire = rule.endMs;
  return out;
};

const toDeviceEvent = (cal: calendarManager.Calendar, ev: calendarManager.Event): DeviceCalendarEvent | null => {
  if (ev.id === undefined) return null;
  const loc: calendarManager.Location | undefined = ev.location;
  const out: DeviceCalendarEvent = {
    eventId: `${cal.id}:${ev.id}`,
    title: ev.title ?? '',
    startMs: ev.startTime,
    endMs: ev.endTime,
    allDay: ev.isAllDay === true,
    calendarId: `${cal.id}`,
    calendarName: calendarDisplayName(cal),
    location: loc !== undefined && loc.location !== undefined ? loc.location : '',
    notes: ev.description ?? '',
    recurrence: recurrenceFromHarmony(ev.recurrenceRule),
  };
  return out;
};

const requireDeviceEvent = (cal: calendarManager.Calendar, ev: calendarManager.Event): DeviceCalendarEvent => {
  const mapped: DeviceCalendarEvent | null = toDeviceEvent(cal, ev);
  if (mapped === null) throw new DeviceScheduleError('platform_error', '系统日历未返回日程 ID。');
  return mapped;
};

/**
 * draft → Calendar Kit Event。base(更新时的原日程)存在时保留:提醒/参与者/时区/服务/
 * identifier/农历标记;地点文字未变时保留经纬度;模型未触及重复规则时原样透传 base 的
 * recurrenceRule(不经 recurrenceToHarmony 重建,以免丢 count/daysOfWeek/excludedDates)。
 */
const buildCalendarEvent = (draft: DeviceCalendarEventDraft, base: calendarManager.Event | null): calendarManager.Event => {
  const ev: calendarManager.Event = {
    type: base !== null ? base.type : calendarManager.EventType.NORMAL,
    title: draft.title,
    startTime: draft.startMs,
    endTime: draft.endMs,
    isAllDay: draft.allDay,
    description: draft.notes,
  };
  const loc: calendarManager.Location = { location: draft.location };
  if (base !== null && base.location !== undefined && draft.location.length > 0) {
    const baseLoc: calendarManager.Location = base.location;
    const baseText: string = baseLoc.location !== undefined ? baseLoc.location : '';
    if (baseText === draft.location) {
      if (baseLoc.longitude !== undefined) loc.longitude = baseLoc.longitude;
      if (baseLoc.latitude !== undefined) loc.latitude = baseLoc.latitude;
    }
  }
  ev.location = loc;
  if (draft.recurrenceSpecified) {
    if (draft.recurrence !== null) ev.recurrenceRule = recurrenceToHarmony(draft.recurrence);
  } else if (base !== null && base.recurrenceRule !== undefined) {
    ev.recurrenceRule = base.recurrenceRule;
  }
  if (base !== null) {
    if (base.reminderTime !== undefined) ev.reminderTime = base.reminderTime;
    if (base.attendee !== undefined) ev.attendee = base.attendee;
    if (base.timeZone !== undefined) ev.timeZone = base.timeZone;
    if (base.service !== undefined) ev.service = base.service;
    if (base.identifier !== undefined) ev.identifier = base.identifier;
    if (base.isLunar !== undefined) ev.isLunar = base.isLunar;
  }
  return ev;
};

const allCalendars = async (): Promise<calendarManager.Calendar[]> => calendarMgr().getAllCalendars();

const findCalendarById = async (id: number): Promise<calendarManager.Calendar | null> => {
  const cals: calendarManager.Calendar[] = await allCalendars();
  const found: calendarManager.Calendar | undefined = cals.find((c: calendarManager.Calendar): boolean => c.id === id);
  return found !== undefined ? found : null;
};

/** 用户日历写入目标:null → 系统默认日历;其余按 id 查找(不允许写进 Amber 提醒日历) */
const targetUserCalendar = async (calendarId: string | null): Promise<calendarManager.Calendar> => {
  if (calendarId === null) return calendarMgr().getCalendar();
  const trimmed: string = calendarId.trim();
  const cal: calendarManager.Calendar | null = /^\d+$/.test(trimmed) ? await findCalendarById(Number(trimmed)) : null;
  if (cal === null || isReminderCalendar(cal)) {
    throw new DeviceScheduleError('not_found', '找不到对应的可写日历。');
  }
  return cal;
};

/** getEvents 列:SDK 默认列 + recurrenceRule/isLunar(默认列不含,不显式请求就读不到重复规则) */
const EVENT_COLUMNS: (keyof calendarManager.Event)[] = [
  'id', 'type', 'title', 'startTime', 'endTime', 'isAllDay', 'description', 'timeZone',
  'location', 'service', 'attendee', 'reminderTime', 'identifier', 'recurrenceRule', 'isLunar',
];

const findEventIn = async (cal: calendarManager.Calendar, eventId: number): Promise<calendarManager.Event | null> => {
  const events: calendarManager.Event[] =
    await cal.getEvents(calendarManager.EventFilter.filterById([eventId]), EVENT_COLUMNS);
  const found: calendarManager.Event | undefined =
    events.find((e: calendarManager.Event): boolean => e.id === eventId);
  return found !== undefined ? found : null;
};

const locateUserEvent = async (eventId: string): Promise<LocatedEvent | null> => {
  const ref: EventRef | null = parseEventRef(eventId);
  if (ref === null) return null;
  const cal: calendarManager.Calendar | null = await findCalendarById(ref.calendarId);
  if (cal === null || isReminderCalendar(cal)) return null;
  const ev: calendarManager.Event | null = await findEventIn(cal, ref.eventId);
  return ev !== null ? new LocatedEvent(cal, ev) : null;
};

// ===== 提醒事项(Amber 提醒日历) =====

let reminderCalendarCache: calendarManager.Calendar | null = null;
/** 进行中的「Amber 提醒」日历创建;并发 reminder_create 共享同一次创建,避免建出多个同名账户 */
let reminderCalendarCreation: Promise<calendarManager.Calendar> | null = null;

const findReminderCalendar = async (): Promise<calendarManager.Calendar | null> => {
  const cals: calendarManager.Calendar[] = await allCalendars();
  const found: calendarManager.Calendar | undefined = cals.find(
    (c: calendarManager.Calendar): boolean => isReminderCalendar(c));
  return found !== undefined ? found : null;
};

const createReminderCalendar = async (): Promise<calendarManager.Calendar> => {
  const account: calendarManager.CalendarAccount = {
    name: REMINDER_ACCOUNT_NAME,
    type: calendarManager.CalendarType.LOCAL,
    displayName: REMINDER_ACCOUNT_DISPLAY,
  };
  const created: calendarManager.Calendar = await calendarMgr().createCalendar(account);
  try {
    const config: calendarManager.CalendarConfig = { enableReminder: true };
    await created.setConfig(config);
  } catch (e) {
    hilog.warn(DOMAIN, TAG, 'reminder calendar setConfig failed: %{public}s', (e as BusinessError).message);
  }
  reminderCalendarCache = created; // 先于 promise 结算写缓存,后来者直接命中
  return created;
};

const reminderCalendar = async (createIfMissing: boolean): Promise<calendarManager.Calendar | null> => {
  if (reminderCalendarCache !== null) return reminderCalendarCache;
  const inFlight: Promise<calendarManager.Calendar> | null = reminderCalendarCreation;
  if (inFlight !== null) return await inFlight;
  const found: calendarManager.Calendar | null = await findReminderCalendar();
  if (found !== null) {
    reminderCalendarCache = found;
    return found;
  }
  // 查询期间别的调用可能已建好 / 正在建
  if (reminderCalendarCache !== null) return reminderCalendarCache;
  if (!createIfMissing) return null;
  let creation: Promise<calendarManager.Calendar> | null = reminderCalendarCreation;
  if (creation === null) {
    const started: Promise<calendarManager.Calendar> = createReminderCalendar();
    reminderCalendarCreation = started;
    const settle = (): void => {
      if (reminderCalendarCreation === started) reminderCalendarCreation = null;
    };
    started.then((): void => settle(), (): void => settle());
    creation = started;
  }
  return await creation;
};

/**
 * 在「Amber 提醒」日历上执行 op。缓存的日历可能已被用户在系统日历里删除:平台调用失败时
 * 丢弃缓存、重新解析(必要时重建)后重试一次;工具语义错误(DeviceScheduleError)不重试。
 */
async function withReminderCalendar<T>(
  createIfMissing: boolean, op: (cal: calendarManager.Calendar | null) => Promise<T>): Promise<T> {
  const fromCache: boolean = reminderCalendarCache !== null;
  const cal: calendarManager.Calendar | null = await reminderCalendar(createIfMissing);
  try {
    return await op(cal);
  } catch (e) {
    if (e instanceof DeviceScheduleError) throw e;
    reminderCalendarCache = null;
    if (!fromCache) throw e as Error;
    hilog.info(DOMAIN, TAG, 'reminder calendar op failed (%{public}d), re-resolving calendar',
      (e as BusinessError).code);
    const fresh: calendarManager.Calendar | null = await reminderCalendar(createIfMissing);
    return await op(fresh);
  }
}

const toDeviceReminder = (cal: calendarManager.Calendar, ev: calendarManager.Event): DeviceReminder | null => {
  if (ev.id === undefined) return null;
  const decoded: DecodedReminderDescription = decodeReminderEventDescription(ev.description ?? '');
  const meta: ReminderEventMeta | null = decoded.meta;
  const completedAt: number | null = meta !== null ? meta.completedAtMs : null;
  const storedTitle: string = ev.title ?? '';
  const strippedTitle: string = decodeReminderEventTitle(storedTitle, true);
  // 有标记以标记为准;标记丢失(同步端/手工编辑抹掉描述)→ 以「✓ 」标题前缀推断完成态
  const completed: boolean = meta !== null ? completedAt !== null : strippedTitle !== storedTitle;
  // 无元数据(用户在系统日历里手工建的):全天视为无 due
  const hasDue: boolean = meta !== null ? meta.hasDue : ev.isAllDay !== true;
  const out: DeviceReminder = {
    reminderId: `${ev.id}`,
    title: completed ? strippedTitle : storedTitle,
    dueMs: hasDue ? ev.startTime : null,
    notes: decoded.notes,
    priority: meta !== null ? meta.priority : 0,
    completed: completed,
    completedAtMs: completedAt,
    listId: `${cal.id}`,
    listName: REMINDER_ACCOUNT_DISPLAY,
  };
  return out;
};

const requireDeviceReminder = (cal: calendarManager.Calendar, ev: calendarManager.Event): DeviceReminder => {
  const mapped: DeviceReminder | null = toDeviceReminder(cal, ev);
  if (mapped === null) throw new DeviceScheduleError('platform_error', '系统日历未返回提醒 ID。');
  return mapped;
};

const buildReminderEvent = (draft: DeviceReminderDraft, anchorMs: number, id: number | null): calendarManager.Event => {
  const window: ReminderEventWindow = reminderEventWindow(draft.dueMs, anchorMs, draft.completed);
  const meta: ReminderEventMeta = {
    hasDue: draft.dueMs !== null,
    priority: draft.priority,
    completedAtMs: draft.completed ? (draft.completedAtMs !== null ? draft.completedAtMs : Date.now()) : null,
  };
  const ev: calendarManager.Event = {
    type: draft.priority >= 1 && draft.priority <= 4
      ? calendarManager.EventType.IMPORTANT : calendarManager.EventType.NORMAL,
    title: encodeReminderEventTitle(draft.title, draft.completed),
    startTime: window.startMs,
    endTime: window.endMs,
    isAllDay: window.allDay,
    reminderTime: window.reminderMinutes,
    description: encodeReminderEventDescription(draft.notes, meta),
  };
  if (id !== null) ev.id = id;
  return ev;
};

const assertReminderList = (cal: calendarManager.Calendar, listId: string | null): void => {
  if (listId !== null && listId.trim() !== `${cal.id}`) {
    throw new DeviceScheduleError('not_found', `找不到对应的可写提醒清单(HarmonyOS 上仅有「${REMINDER_ACCOUNT_DISPLAY}」清单,list_id=${cal.id})。`);
  }
};

export class DeviceCalendarAdapter {
  // —— 系统日历 ——

  async listCalendarEvents(startMs: number, endMs: number): Promise<DeviceCalendarEvent[]> {
    await ensurePermissions([READ_CALENDAR], '日历');
    try {
      const out: DeviceCalendarEvent[] = [];
      let lastError: Error | null = null;
      let readable: number = 0;
      for (const cal of await allCalendars()) {
        if (isReminderCalendar(cal)) continue;
        try {
          const events: calendarManager.Event[] =
            await cal.getEvents(calendarManager.EventFilter.filterByTime(startMs, endMs), EVENT_COLUMNS);
          readable++;
          for (const ev of events) {
            const mapped: DeviceCalendarEvent | null = toDeviceEvent(cal, ev);
            if (mapped !== null) out.push(mapped);
          }
        } catch (e) {
          lastError = e as Error;
          hilog.warn(DOMAIN, TAG, 'getEvents on calendar %{public}d failed: %{public}s', cal.id, (e as BusinessError).message);
        }
      }
      if (readable === 0 && lastError !== null) throw lastError;
      return out;
    } catch (e) {
      throw toScheduleError(e as Error, '读取日历');
    }
  }

  async getCalendarEvent(eventId: string): Promise<DeviceCalendarEvent | null> {
    await ensurePermissions([READ_CALENDAR], '日历');
    try {
      const located: LocatedEvent | null = await locateUserEvent(eventId);
      return located !== null ? toDeviceEvent(located.calendar, located.event) : null;
    } catch (e) {
      throw toScheduleError(e as Error, '读取日历事件');
    }
  }

  async createCalendarEvent(draft: DeviceCalendarEventDraft): Promise<DeviceCalendarEvent> {
    await ensurePermissions([READ_CALENDAR, WRITE_CALENDAR], '日历写入');
    try {
      const cal: calendarManager.Calendar = await targetUserCalendar(draft.calendarId);
      const ev: calendarManager.Event = buildCalendarEvent(draft, null);
      ev.id = await cal.addEvent(ev);
      return requireDeviceEvent(cal, ev);
    } catch (e) {
      throw toScheduleError(e as Error, '新建日历事件');
    }
  }

  async updateCalendarEvent(eventId: string, draft: DeviceCalendarEventDraft): Promise<DeviceCalendarEvent> {
    await ensurePermissions([READ_CALENDAR, WRITE_CALENDAR], '日历写入');
    try {
      const located: LocatedEvent | null = await locateUserEvent(eventId);
      if (located === null) throw new DeviceScheduleError('not_found', '找不到对应的日历事件。');
      const source: calendarManager.Calendar = located.calendar;
      const dest: calendarManager.Calendar =
        draft.calendarId !== null ? await targetUserCalendar(draft.calendarId) : source;
      const ev: calendarManager.Event = buildCalendarEvent(draft, located.event);
      // updateEvent 省略 recurrenceRule 是否会清掉既有规则未见文档保证 → 明确移除重复(recurrence:none)
      // 与跨日历移动统一走「新建 + 删旧」(buildCalendarEvent 已带上 identifier/service/isLunar 等)
      const dropsRecurrence: boolean = draft.recurrenceSpecified && draft.recurrence === null
        && located.event.recurrenceRule !== undefined;
      if (dest.id === source.id && !dropsRecurrence) {
        ev.id = located.event.id;
        await source.updateEvent(ev);
        return requireDeviceEvent(source, ev);
      }
      const newId: number = await dest.addEvent(ev);
      ev.id = newId;
      try {
        await source.deleteEvent(located.event.id as number);
      } catch (e) {
        try {
          await dest.deleteEvent(newId); // 回滚,避免出现重复日程
        } catch (rollback) {
          hilog.error(DOMAIN, TAG, 'rollback addEvent failed: %{public}s', (rollback as BusinessError).message);
        }
        throw e as Error;
      }
      return requireDeviceEvent(dest, ev);
    } catch (e) {
      throw toScheduleError(e as Error, '更新日历事件');
    }
  }

  async deleteCalendarEvent(eventId: string): Promise<void> {
    await ensurePermissions([READ_CALENDAR, WRITE_CALENDAR], '日历写入');
    try {
      const located: LocatedEvent | null = await locateUserEvent(eventId);
      if (located === null) throw new DeviceScheduleError('not_found', '找不到对应的日历事件。');
      await located.calendar.deleteEvent(located.event.id as number);
    } catch (e) {
      throw toScheduleError(e as Error, '删除日历事件');
    }
  }

  // —— 提醒事项 ——

  async listReminders(): Promise<DeviceReminder[]> {
    await ensurePermissions([READ_CALENDAR], '日历(提醒事项)');
    try {
      return await withReminderCalendar<DeviceReminder[]>(false,
        async (cal: calendarManager.Calendar | null): Promise<DeviceReminder[]> => {
          if (cal === null) return [];
          const out: DeviceReminder[] = [];
          for (const ev of await cal.getEvents()) {
            const mapped: DeviceReminder | null = toDeviceReminder(cal, ev);
            if (mapped !== null) out.push(mapped);
          }
          return out;
        });
    } catch (e) {
      throw toScheduleError(e as Error, '读取提醒事项');
    }
  }

  async getReminder(reminderId: string): Promise<DeviceReminder | null> {
    await ensurePermissions([READ_CALENDAR], '日历(提醒事项)');
    try {
      const id: number | null = parseNumericId(reminderId);
      if (id === null) return null;
      const eventId: number = id;
      return await withReminderCalendar<DeviceReminder | null>(false,
        async (cal: calendarManager.Calendar | null): Promise<DeviceReminder | null> => {
          if (cal === null) return null;
          const ev: calendarManager.Event | null = await findEventIn(cal, eventId);
          return ev !== null ? toDeviceReminder(cal, ev) : null;
        });
    } catch (e) {
      throw toScheduleError(e as Error, '读取提醒事项');
    }
  }

  async createReminder(draft: DeviceReminderDraft): Promise<DeviceReminder> {
    await ensurePermissions([READ_CALENDAR, WRITE_CALENDAR], '日历写入(提醒事项)');
    try {
      return await withReminderCalendar<DeviceReminder>(true,
        async (cal: calendarManager.Calendar | null): Promise<DeviceReminder> => {
          if (cal === null) throw new DeviceScheduleError('unavailable', '无法创建「Amber 提醒」日历。');
          assertReminderList(cal, draft.listId);
          const ev: calendarManager.Event = buildReminderEvent(draft, Date.now(), null);
          ev.id = await cal.addEvent(ev);
          return requireDeviceReminder(cal, ev);
        });
    } catch (e) {
      throw toScheduleError(e as Error, '新建提醒事项');
    }
  }

  async updateReminder(reminderId: string, draft: DeviceReminderDraft): Promise<DeviceReminder> {
    await ensurePermissions([READ_CALENDAR, WRITE_CALENDAR], '日历写入(提醒事项)');
    try {
      const id: number | null = parseNumericId(reminderId);
      if (id === null) throw new DeviceScheduleError('not_found', '找不到对应的提醒事项。');
      const eventId: number = id;
      return await withReminderCalendar<DeviceReminder>(false,
        async (cal: calendarManager.Calendar | null): Promise<DeviceReminder> => {
          const existing: calendarManager.Event | null = cal !== null ? await findEventIn(cal, eventId) : null;
          if (cal === null || existing === null) {
            throw new DeviceScheduleError('not_found', '找不到对应的提醒事项。');
          }
          assertReminderList(cal, draft.listId);
          // 无 due 的全天项保持原日期;其余以当前时间为锚
          const anchor: number = existing.isAllDay === true ? existing.startTime : Date.now();
          const ev: calendarManager.Event = buildReminderEvent(draft, anchor, eventId);
          await cal.updateEvent(ev);
          return requireDeviceReminder(cal, ev);
        });
    } catch (e) {
      throw toScheduleError(e as Error, '更新提醒事项');
    }
  }

  async deleteReminder(reminderId: string): Promise<void> {
    await ensurePermissions([READ_CALENDAR, WRITE_CALENDAR], '日历写入(提醒事项)');
    try {
      const id: number | null = parseNumericId(reminderId);
      if (id === null) throw new DeviceScheduleError('not_found', '找不到对应的提醒事项。');
      const eventId: number = id;
      await withReminderCalendar<void>(false, async (cal: calendarManager.Calendar | null): Promise<void> => {
        if (cal === null || (await findEventIn(cal, eventId)) === null) {
          throw new DeviceScheduleError('not_found', '找不到对应的提醒事项。');
        }
        await cal.deleteEvent(eventId);
      });
    } catch (e) {
      throw toScheduleError(e as Error, '删除提醒事项');
    }
  }

}
