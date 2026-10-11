// DeviceSchedulePort — 日历 / 提醒事项 / 通知 / 闹钟 工具的 HarmonyOS 实现
//
// 领域层:@amber/chat-domain device_schedule_tools.ts(参数校验/结果 JSON 全在那边)
// 平台映射:
//   - 系统日历      → @kit.CalendarKit calendarManager(getAllCalendars/getEvents/
//                     addEvent/updateEvent/deleteEvent);event_id = "<calendarId>:<eventId>"
//   - 提醒事项      → 应用自有本地日历账户 amber_reminders(显示名「Amber 提醒」);
//                     每条提醒 = 一个日程(有 due → 15 分钟窗口 + 准点 reminderTime;无 due →
//                     创建当天全天日程无提醒)。完成态 = 标题前缀「✓ 」+ 描述中的
//                     [amber-reminder:{...}] 元数据标记(解码取最后一个,位置不限;标记丢失时
//                     以「✓ 」前缀推断),并移除 reminderTime;日程不删除。
//   - 通知/闹钟     → @kit.BackgroundTasksKit reminderAgentManager 代理提醒
//                     (notification/one_time = Calendar 型定点;weekly = Alarm 型;
//                     timer = Timer 型倒计时)。代理提醒不带「是谁建的」语义,故在
//                     preferences(amber_device_schedule)记录 Amber agent 自建的 reminderId,
//                     与 getAllValidReminders 对账(过期/已响的自动剔除)。
// 重复日程:getEvents 显式请求 EVENT_COLUMNS(默认列不含 recurrenceRule);更新时模型未触及
//   重复规则 → 原样透传平台规则(保留 count/daysOfWeek/excludedDates 等);API 12 无单次实例
//   编辑接口,整系列确认由工具层 apply_to:"series" 把关。
// 权限:首次使用时按需申请 READ/WRITE_CALENDAR(用户拒绝过 → 仅对「被拒且未弹框」的权限
//   走 requestPermissionOnSetting 二次引导),通知未开启 → requestEnableNotification;仍被拒 → DeviceScheduleError
//   ('permission_denied'),工具层转为 ok:false JSON,不抛到工具循环。
// 依赖权限(module.json5):READ_CALENDAR、WRITE_CALENDAR(user_grant)、
//   PUBLISH_AGENT_REMINDER(system_grant,声明即授权)。

import { calendarManager } from '@kit.CalendarKit';
import { reminderAgentManager } from '@kit.BackgroundTasksKit';
import { notificationManager } from '@kit.NotificationKit';
import { abilityAccessCtrl, bundleManager, common, PermissionRequestResult, Permissions } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import {
  ALARM_WEEKDAY_ORDER, DeviceScheduleError, decodeReminderEventDescription, decodeReminderEventTitle,
  encodeReminderEventDescription, encodeReminderEventTitle, reminderEventWindow,
} from '@amber/chat-domain';
import type {
  DecodedReminderDescription, DeviceAlarm, DeviceAlarmKind, DeviceAlarmRequest, DeviceCalendarEvent,
  DeviceCalendarEventDraft, DeviceNotificationRequest, DeviceRecurrenceFrequency, DeviceRecurrenceRule,
  DeviceReminder, DeviceReminderDraft, DeviceScheduledNotification, DeviceSchedulePort,
  ReminderEventMeta, ReminderEventWindow,
} from '@amber/chat-domain';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeviceSchedulePort';

const READ_CALENDAR: Permissions = 'ohos.permission.READ_CALENDAR';
const WRITE_CALENDAR: Permissions = 'ohos.permission.WRITE_CALENDAR';

const REMINDER_ACCOUNT_NAME: string = 'amber_reminders';
const REMINDER_ACCOUNT_DISPLAY: string = 'Amber 提醒';

const PREF_NAME: string = 'amber_device_schedule';
const PREF_KEY_RECORDS: string = 'agent_reminders.v1';
const FAMILY_ALARM: string = 'alarm';
const FAMILY_NOTIFICATION: string = 'notification';
const ENTRY_ABILITY_NAME: string = 'EntryAbility';

// BusinessError 码(SDK d.ts @throws)
const ERR_PERMISSION: number = 201;
const ERR_PARAM: number = 401;
const ERR_CAPABILITY: number = 801;
const ERR_REMINDER_NOTIFICATION_DISABLED: number = 1700001;
const ERR_REMINDER_LIMIT: number = 1700002;
const ERR_REMINDER_NOT_EXIST: number = 1700003;
const ERR_CALENDAR_ACCOUNT_NOT_FOUND: number = 23900003;

// ===== 错误映射 =====

const toScheduleError = (err: Error, action: string): DeviceScheduleError => {
  if (err instanceof DeviceScheduleError) return err;
  const code: number = (err as BusinessError).code;
  hilog.warn(DOMAIN, TAG, '%{public}s failed: code=%{public}d msg=%{public}s', action, code, err.message);
  if (code === ERR_PERMISSION) {
    return new DeviceScheduleError('permission_denied', `${action}失败:未获得所需权限,可在 设置 > 应用和元服务 > Amber > 权限 中开启。`);
  }
  if (code === ERR_CAPABILITY) return new DeviceScheduleError('unavailable', `${action}失败:当前设备不支持该能力。`);
  if (code === ERR_PARAM) return new DeviceScheduleError('invalid_arguments', `${action}失败:系统拒绝了参数(${err.message})。`);
  if (code === ERR_REMINDER_LIMIT) {
    return new DeviceScheduleError('capacity_reached', '系统提醒数量已达上限,请先取消一个闹钟或通知。');
  }
  if (code === ERR_REMINDER_NOTIFICATION_DISABLED) {
    return new DeviceScheduleError('permission_denied', '未开启 Amber 通知,无法发布提醒;请在系统设置中允许通知。');
  }
  if (code === ERR_REMINDER_NOT_EXIST || code === ERR_CALENDAR_ACCOUNT_NOT_FOUND) {
    return new DeviceScheduleError('not_found', `${action}失败:目标不存在或已被删除。`);
  }
  const detail: string = err.message !== undefined && err.message.length > 0 ? err.message : 'unknown error';
  return new DeviceScheduleError('platform_error', `${action}失败:${detail}${code !== undefined ? `(${code})` : ''}`);
};

// ===== Context / 权限 =====

const abilityContext = (): common.UIAbilityContext => {
  const ctx: common.UIAbilityContext | undefined = AppStorage.get<common.UIAbilityContext>('abilityContext');
  if (ctx === undefined) {
    throw new DeviceScheduleError('unavailable', '应用界面尚未就绪,暂时无法访问系统日历或提醒。');
  }
  return ctx;
};

const atManager: abilityAccessCtrl.AtManager = abilityAccessCtrl.createAtManager();
let cachedTokenId: number = -1;

const selfTokenId = (): number => {
  if (cachedTokenId < 0) {
    const info: bundleManager.BundleInfo = bundleManager.getBundleInfoForSelfSync(
      bundleManager.BundleFlag.GET_BUNDLE_INFO_WITH_APPLICATION);
    cachedTokenId = info.appInfo.accessTokenId;
  }
  return cachedTokenId;
};

let cachedBundleName: string = '';

const selfBundleName = (): string => {
  if (cachedBundleName.length === 0) {
    cachedBundleName = bundleManager.getBundleInfoForSelfSync(
      bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT).name;
  }
  return cachedBundleName;
};

const isGranted = (perm: Permissions): boolean => {
  try {
    return atManager.checkAccessTokenSync(selfTokenId(), perm) === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED;
  } catch (e) {
    hilog.debug(DOMAIN, TAG, 'checkAccessTokenSync failed: %{public}s', (e as BusinessError).message);
    return false;
  }
};

const ensurePermissions = async (perms: Permissions[], label: string): Promise<void> => {
  const missing: Permissions[] = perms.filter((p: Permissions): boolean => !isGranted(p));
  if (missing.length === 0) return;
  const ctx: common.UIAbilityContext = abilityContext();
  let granted: boolean = false;
  // 仅「本次被拒且系统未弹框」(= 用户此前已拒绝)的权限可走设置弹框;混入未被拒/已授予的
  // 权限会让 requestPermissionOnSetting 直接抛 12100012 / 12100011
  const settingCandidates: Permissions[] = [];
  try {
    const result: PermissionRequestResult = await atManager.requestPermissionsFromUser(ctx, missing);
    const auth: number[] = result.authResults;
    granted = auth.length === missing.length && auth.every((r: number): boolean => r === 0);
    const shown: boolean[] | undefined = result.dialogShownResults;
    if (!granted && shown !== undefined) {
      for (let i: number = 0; i < missing.length; i++) {
        const denied: boolean = i >= auth.length || auth[i] !== 0;
        if (denied && i < shown.length && shown[i] === false) settingCandidates.push(missing[i]);
      }
    }
  } catch (e) {
    hilog.warn(DOMAIN, TAG, 'requestPermissionsFromUser failed: %{public}s', (e as BusinessError).message);
  }
  if (!granted && settingCandidates.length > 0) {
    // 用户此前已拒绝:系统不再弹首次授权框 → 用权限设置弹框二次引导(API 12)
    try {
      await atManager.requestPermissionOnSetting(ctx, settingCandidates);
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'requestPermissionOnSetting failed: %{public}s', (e as BusinessError).message);
    }
    granted = missing.every((p: Permissions): boolean => isGranted(p));
  }
  if (!granted) {
    throw new DeviceScheduleError('permission_denied',
      `未获准访问${label},可在 设置 > 应用和元服务 > Amber > 权限 中开启。`);
  }
};

const notificationsEnabled = async (): Promise<boolean> => {
  try {
    return await notificationManager.isNotificationEnabled();
  } catch (e) {
    hilog.warn(DOMAIN, TAG, 'isNotificationEnabled failed: %{public}s', (e as BusinessError).message);
    return false;
  }
};

const ensureNotificationsEnabled = async (): Promise<void> => {
  if (await notificationsEnabled()) return;
  try {
    await notificationManager.requestEnableNotification(abilityContext());
  } catch (e) {
    if (e instanceof DeviceScheduleError) throw e;
    hilog.warn(DOMAIN, TAG, 'requestEnableNotification failed: %{public}s', (e as BusinessError).message);
  }
  if (!(await notificationsEnabled())) {
    throw new DeviceScheduleError('permission_denied', '未获得通知权限,请在系统设置中允许 Amber 发送通知。');
  }
};

// ===== 日历 helper =====

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

const parseNumericId = (raw: string): number | null => {
  const trimmed: string = raw.trim();
  return /^\d+$/.test(trimmed) ? Number(trimmed) : null;
};

// ===== 代理提醒(通知 / 闹钟)记录 =====

interface AgentReminderRecord {
  reminderId: number;
  family: string;
  kind: string;
  title: string;
  body: string;
  fireAtMs: number | null;
  weekdays: string[];
  hour: number | null;
  minute: number | null;
  durationSeconds: number | null;
  createdAtMs: number;
}

let prefsCache: preferences.Preferences | null = null;

const schedulePrefs = async (): Promise<preferences.Preferences> => {
  if (prefsCache === null) {
    prefsCache = await preferences.getPreferences(abilityContext(), PREF_NAME);
  }
  return prefsCache;
};

const loadRecords = async (): Promise<AgentReminderRecord[]> => {
  const p: preferences.Preferences = await schedulePrefs();
  const raw: preferences.ValueType = await p.get(PREF_KEY_RECORDS, '[]');
  if (typeof raw !== 'string') return [];
  try {
    const parsed: AgentReminderRecord[] = JSON.parse(raw) as AgentReminderRecord[];
    return Array.isArray(parsed) ? parsed : [];
  } catch (e) {
    hilog.warn(DOMAIN, TAG, 'agent reminder records corrupt, resetting: %{public}s', (e as Error).message);
    return [];
  }
};

const saveRecords = async (records: AgentReminderRecord[]): Promise<void> => {
  const p: preferences.Preferences = await schedulePrefs();
  await p.put(PREF_KEY_RECORDS, JSON.stringify(records));
  await p.flush();
};

let lockTail: Promise<void> = Promise.resolve();

function withRecordsLock<T>(task: () => Promise<T>): Promise<T> {
  const run: Promise<T> = lockTail.then((): Promise<T> => task());
  lockTail = run.then((): void => {}, (): void => {});
  return run;
}

const validReminderIds = async (): Promise<number[]> => {
  const infos: reminderAgentManager.ReminderInfo[] = await reminderAgentManager.getAllValidReminders();
  return infos.map((info: reminderAgentManager.ReminderInfo): number => info.reminderId);
};

/** 撤销代理提醒:成功或「提醒不存在」(1700003,已响过/已被清)→ true;其余失败 → false(记录保留) */
const tryCancelReminder = async (reminderId: number): Promise<boolean> => {
  try {
    await reminderAgentManager.cancelReminder(reminderId);
    return true;
  } catch (e) {
    const code: number = (e as BusinessError).code;
    if (code === ERR_REMINDER_NOT_EXIST) return true;
    hilog.warn(DOMAIN, TAG, 'cancelReminder(%{public}d) failed: code=%{public}d', reminderId, code);
    return false;
  }
};

const cancelReminderQuietly = async (reminderId: number): Promise<void> => {
  try {
    await reminderAgentManager.cancelReminder(reminderId);
  } catch (e) {
    hilog.info(DOMAIN, TAG, 'cancelReminder(%{public}d) ignored: %{public}d', reminderId, (e as BusinessError).code);
  }
};

const toLocalDateTime = (epochMs: number): reminderAgentManager.LocalDateTime => {
  const d: Date = new Date(epochMs);
  const out: reminderAgentManager.LocalDateTime = {
    year: d.getFullYear(),
    month: d.getMonth() + 1,
    day: d.getDate(),
    hour: d.getHours(),
    minute: d.getMinutes(),
    second: d.getSeconds(),
  };
  return out;
};

const openAppWantAgent = (): reminderAgentManager.WantAgent => {
  const agent: reminderAgentManager.WantAgent = { pkgName: selfBundleName(), abilityName: ENTRY_ABILITY_NAME };
  return agent;
};

/** sunday…saturday → Harmony daysOfWeek(1=周一 … 7=周日) */
const weekdayToHarmony = (day: string): number => {
  const idx: number = ALARM_WEEKDAY_ORDER.indexOf(day);
  return idx <= 0 ? 7 : idx;
};

const toAlarmKind = (raw: string): DeviceAlarmKind => {
  if (raw === 'weekly') return 'weekly';
  if (raw === 'timer') return 'timer';
  return 'one_time';
};

const recordToAlarm = (r: AgentReminderRecord): DeviceAlarm => {
  const out: DeviceAlarm = {
    alarmId: `${r.reminderId}`,
    title: r.title,
    kind: toAlarmKind(r.kind),
    state: 'scheduled',
    createdAtMs: r.createdAtMs,
    fireAtMs: r.fireAtMs,
    weekdays: r.weekdays,
    hour: r.hour,
    minute: r.minute,
    durationSeconds: r.durationSeconds,
  };
  return out;
};

const publishAlarm = async (request: DeviceAlarmRequest): Promise<number> => {
  if (request.kind === 'timer') {
    const timer: reminderAgentManager.ReminderRequestTimer = {
      reminderType: reminderAgentManager.ReminderType.REMINDER_TYPE_TIMER,
      triggerTimeInSeconds: Math.max(1, Math.round(request.durationSeconds ?? 0)),
      title: request.title,
      content: '计时结束',
      ringDuration: 60,
      wantAgent: openAppWantAgent(),
      slotType: notificationManager.SlotType.SOCIAL_COMMUNICATION,
    };
    return reminderAgentManager.publishReminder(timer);
  }
  if (request.kind === 'weekly') {
    const alarm: reminderAgentManager.ReminderRequestAlarm = {
      reminderType: reminderAgentManager.ReminderType.REMINDER_TYPE_ALARM,
      hour: request.hour ?? 0,
      minute: request.minute ?? 0,
      daysOfWeek: request.weekdays.map((d: string): number => weekdayToHarmony(d)),
      actionButton: [
        { title: '关闭', type: reminderAgentManager.ActionButtonType.ACTION_BUTTON_TYPE_CLOSE },
        { title: '稍后提醒', type: reminderAgentManager.ActionButtonType.ACTION_BUTTON_TYPE_SNOOZE },
      ],
      title: request.title,
      content: 'Amber 闹钟',
      ringDuration: 60,
      snoozeTimes: 2,
      timeInterval: 5 * 60,
      wantAgent: openAppWantAgent(),
      slotType: notificationManager.SlotType.SOCIAL_COMMUNICATION,
    };
    return reminderAgentManager.publishReminder(alarm);
  }
  const once: reminderAgentManager.ReminderRequestCalendar = {
    reminderType: reminderAgentManager.ReminderType.REMINDER_TYPE_CALENDAR,
    dateTime: toLocalDateTime(request.fireAtMs ?? Date.now()),
    actionButton: [
      { title: '关闭', type: reminderAgentManager.ActionButtonType.ACTION_BUTTON_TYPE_CLOSE },
      { title: '稍后提醒', type: reminderAgentManager.ActionButtonType.ACTION_BUTTON_TYPE_SNOOZE },
    ],
    title: request.title,
    content: 'Amber 闹钟',
    ringDuration: 60,
    snoozeTimes: 2,
    timeInterval: 5 * 60,
    wantAgent: openAppWantAgent(),
    slotType: notificationManager.SlotType.SOCIAL_COMMUNICATION,
  };
  return reminderAgentManager.publishReminder(once);
};

// ===== Port 实现 =====

class EntryDeviceSchedulePort implements DeviceSchedulePort {
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

  // —— 本地通知(单槽) ——

  async scheduleNotification(request: DeviceNotificationRequest): Promise<DeviceScheduledNotification> {
    await ensureNotificationsEnabled();
    try {
      return await withRecordsLock<DeviceScheduledNotification>(
        async (): Promise<DeviceScheduledNotification> => {
          // 先发布新的、登记成功后再撤旧的:新通知发布失败时旧通知仍在(单槽语义不丢提醒)
          const req: reminderAgentManager.ReminderRequestCalendar = {
            reminderType: reminderAgentManager.ReminderType.REMINDER_TYPE_CALENDAR,
            dateTime: toLocalDateTime(request.fireAtMs),
            title: request.title,
            content: request.body,
            tapDismissed: true,
            wantAgent: openAppWantAgent(),
            // 高优先级通道(LEVEL_HIGH:横幅 + 提示音),与闹钟一致;默认通道可能静默
            slotType: notificationManager.SlotType.SOCIAL_COMMUNICATION,
          };
          let reminderId: number = -1;
          try {
            reminderId = await reminderAgentManager.publishReminder(req);
          } catch (e) {
            if ((e as BusinessError).code !== ERR_REMINDER_LIMIT) throw e as Error;
            // 系统提醒数已满:退回「先撤旧再发新」,腾出旧通知占的名额后重试一次
            const remaining: AgentReminderRecord[] = [];
            let freed: boolean = false;
            for (const r of await loadRecords()) {
              if (r.family === FAMILY_NOTIFICATION && await tryCancelReminder(r.reminderId)) freed = true;
              else remaining.push(r);
            }
            if (!freed) throw e as Error;
            await saveRecords(remaining);
            reminderId = await reminderAgentManager.publishReminder(req);
          }
          const record: AgentReminderRecord = {
            reminderId: reminderId, family: FAMILY_NOTIFICATION, kind: 'notification',
            title: request.title, body: request.body, fireAtMs: request.fireAtMs, weekdays: [],
            hour: null, minute: null, durationSeconds: null, createdAtMs: Date.now(),
          };
          // 系统可能复用已失效提醒的 ID → 先剔除同 ID 的陈旧记录
          const withNew: AgentReminderRecord[] = (await loadRecords()).filter(
            (r: AgentReminderRecord): boolean => r.reminderId !== reminderId);
          withNew.push(record);
          try {
            await saveRecords(withNew);
          } catch (e) {
            // 未登记的通知无法再被 notification_cancel 撤销 → 回滚刚发布的
            await cancelReminderQuietly(reminderId);
            throw e as Error;
          }
          const kept: AgentReminderRecord[] = [];
          let dropped: boolean = false;
          for (const r of withNew) {
            if (r.family !== FAMILY_NOTIFICATION || r.reminderId === reminderId) {
              kept.push(r);
            } else if (await tryCancelReminder(r.reminderId)) {
              dropped = true;
            } else {
              kept.push(r); // 撤销失败:保留记录,之后 notification_cancel 可重试
            }
          }
          if (dropped) {
            try {
              await saveRecords(kept);
            } catch (e) {
              hilog.warn(DOMAIN, TAG, 'save records after replacing notification failed: %{public}s',
                (e as Error).message);
            }
          }
          const out: DeviceScheduledNotification = {
            notificationId: `${reminderId}`, title: request.title, body: request.body, fireAtMs: request.fireAtMs,
          };
          return out;
        });
    } catch (e) {
      throw toScheduleError(e as Error, '安排本地通知');
    }
  }

  async cancelNotification(): Promise<boolean> {
    try {
      return await withRecordsLock<boolean>(async (): Promise<boolean> => {
        const records: AgentReminderRecord[] = await loadRecords();
        const valid: number[] = await validReminderIds();
        let hadPending: boolean = false;
        let failed: number = 0;
        const kept: AgentReminderRecord[] = [];
        for (const r of records) {
          if (r.family !== FAMILY_NOTIFICATION) {
            kept.push(r);
            continue;
          }
          if (await tryCancelReminder(r.reminderId)) {
            if (valid.indexOf(r.reminderId) >= 0) hadPending = true;
          } else {
            failed++;
            kept.push(r); // 撤销失败:保留记录,允许重试
          }
        }
        if (kept.length !== records.length) {
          try {
            await saveRecords(kept);
          } catch (e) {
            hilog.warn(DOMAIN, TAG, 'save records after cancel failed: %{public}s', (e as Error).message);
          }
        }
        if (failed > 0) {
          throw new DeviceScheduleError('platform_error',
            '取消本地通知失败:系统未能撤销已安排的通知,它仍可能按时弹出,请稍后重试。');
        }
        return hadPending;
      });
    } catch (e) {
      throw toScheduleError(e as Error, '取消本地通知');
    }
  }

  // —— 闹钟 / 计时器 ——

  async scheduleAlarm(request: DeviceAlarmRequest): Promise<DeviceAlarm> {
    await ensureNotificationsEnabled();
    try {
      return await withRecordsLock<DeviceAlarm>(async (): Promise<DeviceAlarm> => {
        const reminderId: number = await publishAlarm(request);
        const record: AgentReminderRecord = {
          reminderId: reminderId, family: FAMILY_ALARM, kind: request.kind, title: request.title, body: '',
          fireAtMs: request.fireAtMs, weekdays: request.weekdays, hour: request.hour, minute: request.minute,
          durationSeconds: request.durationSeconds, createdAtMs: Date.now(),
        };
        try {
          // 系统可能复用已失效提醒的 ID → 先剔除同 ID 的陈旧记录
          const records: AgentReminderRecord[] = (await loadRecords()).filter(
            (r: AgentReminderRecord): boolean => r.reminderId !== reminderId);
          records.push(record);
          await saveRecords(records);
        } catch (e) {
          // 未登记的闹钟 alarms_list / alarm_cancel 都管不到 → 撤回刚发布的,避免「幽灵闹钟」
          await cancelReminderQuietly(reminderId);
          throw e as Error;
        }
        return recordToAlarm(record);
      });
    } catch (e) {
      throw toScheduleError(e as Error, '安排闹钟');
    }
  }

  async listAlarms(): Promise<DeviceAlarm[]> {
    try {
      return await withRecordsLock<DeviceAlarm[]>(async (): Promise<DeviceAlarm[]> => {
        const valid: number[] = await validReminderIds();
        const records: AgentReminderRecord[] = await loadRecords();
        const live: AgentReminderRecord[] = records.filter(
          (r: AgentReminderRecord): boolean => valid.indexOf(r.reminderId) >= 0);
        if (live.length !== records.length) await saveRecords(live); // 已响过/被系统清掉的剔除
        return live
          .filter((r: AgentReminderRecord): boolean => r.family === FAMILY_ALARM)
          .map((r: AgentReminderRecord): DeviceAlarm => recordToAlarm(r));
      });
    } catch (e) {
      throw toScheduleError(e as Error, '读取闹钟');
    }
  }

  async cancelAlarm(alarmId: string): Promise<DeviceAlarm> {
    try {
      return await withRecordsLock<DeviceAlarm>(async (): Promise<DeviceAlarm> => {
        const id: number | null = parseNumericId(alarmId);
        const records: AgentReminderRecord[] = await loadRecords();
        const target: AgentReminderRecord | undefined = records.find(
          (r: AgentReminderRecord): boolean => r.family === FAMILY_ALARM && r.reminderId === id);
        if (target === undefined) {
          throw new DeviceScheduleError('not_found', '该 Amber 闹钟不存在、已响过或已被删除。');
        }
        const rest: AgentReminderRecord[] = records.filter((r: AgentReminderRecord): boolean => r !== target);
        try {
          await reminderAgentManager.cancelReminder(target.reminderId);
        } catch (e) {
          if ((e as BusinessError).code === ERR_REMINDER_NOT_EXIST) {
            await saveRecords(rest);
            throw new DeviceScheduleError('not_found', '该 Amber 闹钟不存在、已响过或已被删除。');
          }
          throw e as Error;
        }
        await saveRecords(rest);
        return recordToAlarm(target);
      });
    } catch (e) {
      throw toScheduleError(e as Error, '取消闹钟');
    }
  }
}

let sharedPort: EntryDeviceSchedulePort | null = null;

export const createEntryDeviceSchedulePort = (): DeviceSchedulePort => {
  if (sharedPort === null) sharedPort = new EntryDeviceSchedulePort();
  return sharedPort;
};
