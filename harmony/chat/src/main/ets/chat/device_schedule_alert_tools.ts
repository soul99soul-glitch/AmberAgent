import type { JsonObject } from './json.ts';
import type { AgentTool } from './tool.ts';
import { makeInputSchemaObj } from './tool.ts';
import type {
  DeviceScheduledNotification, DeviceAlarmRequest, DeviceAlarm, DeviceSchedulePort,
} from './device_schedule_contracts.ts';
import { ALARM_WEEKDAY_ORDER } from './device_schedule_contracts.ts';
import { parseIsoDateTime, formatIsoDateTime } from './device_schedule_codec.ts';
import {
  MIN_FIRE_LEAD_MS, InputError, assertKeysSubset, optString, nonBlank, truncate, alarmFields, buildTool,
  validateAlarm,
} from './device_schedule_support.ts';

export const createAlertScheduleTools = (port: DeviceSchedulePort, now: () => number): AgentTool[] => {
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

  return [notificationSchedule, notificationCancel, alarmSchedule, alarmsList, alarmCancel];
};
