import type { JsonObject } from './json.ts';
import type { AgentTool } from './tool.ts';
import { makeInputSchemaObj } from './tool.ts';
import type { DeviceReminder, DeviceSchedulePort } from './device_schedule_contracts.ts';
import {
  InputError, MSG_INVALID_ARGS, assertKeysSubset, has, optString, nonBlank, optDate, requiredDate,
  optBoolean, clampLimit, truncate, reminderFields, buildTool, REM_CREATE_KEYS, REM_UPDATE_KEYS, requireId,
  assertExactlyKey, priorityFrom,
} from './device_schedule_support.ts';

export const createReminderScheduleTools = (port: DeviceSchedulePort, now: () => number): AgentTool[] => {
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

  return [remindersList, reminderCreate, reminderUpdate, reminderDelete, reminderComplete];
};
