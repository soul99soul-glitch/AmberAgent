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

import type {
  DeviceAlarm, DeviceAlarmRequest, DeviceCalendarEvent, DeviceCalendarEventDraft,
  DeviceNotificationRequest, DeviceReminder, DeviceReminderDraft, DeviceScheduledNotification,
  DeviceSchedulePort,
} from '@amber/chat-domain';
import { DeviceCalendarAdapter } from './device_schedule/DeviceCalendarAdapter.ets';
import { DeviceAgentReminderAdapter } from './device_schedule/DeviceAgentReminderAdapter.ets';

// The public protocol composes Calendar Kit and the shared agent-reminder ledger.
class EntryDeviceSchedulePort implements DeviceSchedulePort {
  private calendar: DeviceCalendarAdapter = new DeviceCalendarAdapter();
  private agentReminders: DeviceAgentReminderAdapter = new DeviceAgentReminderAdapter();

  listCalendarEvents(startMs: number, endMs: number): Promise<DeviceCalendarEvent[]> {
    return this.calendar.listCalendarEvents(startMs, endMs);
  }

  getCalendarEvent(eventId: string): Promise<DeviceCalendarEvent | null> {
    return this.calendar.getCalendarEvent(eventId);
  }

  createCalendarEvent(draft: DeviceCalendarEventDraft): Promise<DeviceCalendarEvent> {
    return this.calendar.createCalendarEvent(draft);
  }

  updateCalendarEvent(eventId: string, draft: DeviceCalendarEventDraft): Promise<DeviceCalendarEvent> {
    return this.calendar.updateCalendarEvent(eventId, draft);
  }

  deleteCalendarEvent(eventId: string): Promise<void> {
    return this.calendar.deleteCalendarEvent(eventId);
  }

  listReminders(): Promise<DeviceReminder[]> {
    return this.calendar.listReminders();
  }

  getReminder(reminderId: string): Promise<DeviceReminder | null> {
    return this.calendar.getReminder(reminderId);
  }

  createReminder(draft: DeviceReminderDraft): Promise<DeviceReminder> {
    return this.calendar.createReminder(draft);
  }

  updateReminder(reminderId: string, draft: DeviceReminderDraft): Promise<DeviceReminder> {
    return this.calendar.updateReminder(reminderId, draft);
  }

  deleteReminder(reminderId: string): Promise<void> {
    return this.calendar.deleteReminder(reminderId);
  }

  scheduleNotification(request: DeviceNotificationRequest): Promise<DeviceScheduledNotification> {
    return this.agentReminders.scheduleNotification(request);
  }

  cancelNotification(): Promise<boolean> {
    return this.agentReminders.cancelNotification();
  }

  scheduleAlarm(request: DeviceAlarmRequest): Promise<DeviceAlarm> {
    return this.agentReminders.scheduleAlarm(request);
  }

  listAlarms(): Promise<DeviceAlarm[]> {
    return this.agentReminders.listAlarms();
  }

  cancelAlarm(alarmId: string): Promise<DeviceAlarm> {
    return this.agentReminders.cancelAlarm(alarmId);
  }
}

let sharedPort: EntryDeviceSchedulePort | null = null;

export const createEntryDeviceSchedulePort = (): DeviceSchedulePort => {
  if (sharedPort === null) sharedPort = new EntryDeviceSchedulePort();
  return sharedPort;
};
