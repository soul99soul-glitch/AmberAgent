// Notifications and alarms share agent_reminders.v1 and one module-level lock.
// Publishing, registration, rollback and cancellation remain within that lock.
import { reminderAgentManager } from '@kit.BackgroundTasksKit';
import { notificationManager } from '@kit.NotificationKit';
import { preferences } from '@kit.ArkData';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { ALARM_WEEKDAY_ORDER, DeviceScheduleError } from '@amber/chat-domain';
import type { DeviceAlarm, DeviceAlarmKind, DeviceAlarmRequest, DeviceNotificationRequest,
  DeviceScheduledNotification } from '@amber/chat-domain';
import { DOMAIN, TAG, ERR_REMINDER_LIMIT, ERR_REMINDER_NOT_EXIST, abilityContext,
  selfBundleName, ensureNotificationsEnabled, toScheduleError, parseNumericId } from './DeviceScheduleAccess.ets';

const PREF_NAME: string = 'amber_device_schedule';
const PREF_KEY_RECORDS: string = 'agent_reminders.v1';
const FAMILY_ALARM: string = 'alarm';
const FAMILY_NOTIFICATION: string = 'notification';
const ENTRY_ABILITY_NAME: string = 'EntryAbility';

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

export class DeviceAgentReminderAdapter {
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

