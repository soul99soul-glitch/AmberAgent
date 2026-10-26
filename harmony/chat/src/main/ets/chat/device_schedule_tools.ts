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

import type { AgentTool } from './tool.ts';
import type { DeviceSchedulePort } from './device_schedule_contracts.ts';
import { createCalendarScheduleTools } from './device_schedule_calendar_tools.ts';
import { createReminderScheduleTools } from './device_schedule_reminder_tools.ts';
import { createAlertScheduleTools } from './device_schedule_alert_tools.ts';

export type {
  DeviceScheduleErrorCode, DeviceRecurrenceFrequency, DeviceRecurrenceRule, DeviceCalendarEvent,
  DeviceCalendarEventDraft, DeviceReminder, DeviceReminderDraft, DeviceNotificationRequest,
  DeviceScheduledNotification, DeviceAlarmKind, DeviceAlarmRequest, DeviceAlarm, DeviceSchedulePort,
  ReminderEventMeta, DecodedReminderDescription, ReminderEventWindow,
} from './device_schedule_contracts.ts';
export { DeviceScheduleError, DEVICE_SCHEDULE_TOOL_NAMES, ALARM_WEEKDAY_ORDER } from './device_schedule_contracts.ts';
export {
  parseIsoDateTime, formatIsoDateTime, REMINDER_COMPLETED_TITLE_PREFIX, encodeReminderEventDescription,
  decodeReminderEventDescription, encodeReminderEventTitle, decodeReminderEventTitle, reminderEventWindow,
} from './device_schedule_codec.ts';
export { recurrenceSummary } from './device_schedule_support.ts';

export const createDeviceScheduleTools = (
  port: DeviceSchedulePort,
  now: () => number = (): number => Date.now(),
): AgentTool[] => [
  ...createCalendarScheduleTools(port, now),
  ...createReminderScheduleTools(port, now),
  ...createAlertScheduleTools(port, now),
];
