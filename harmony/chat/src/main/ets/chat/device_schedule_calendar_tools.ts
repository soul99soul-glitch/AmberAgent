import type { JsonObject } from './json.ts';
import type { AgentTool } from './tool.ts';
import { makeInputSchemaObj } from './tool.ts';
import type { DeviceCalendarEvent, DeviceSchedulePort } from './device_schedule_contracts.ts';
import { formatIsoDateTime } from './device_schedule_codec.ts';
import type { RecurrenceParse } from './device_schedule_support.ts';
import {
  DAY_MS, MAX_LIST_RANGE_MS, InputError, MSG_INVALID_ARGS, MSG_INVALID_RANGE, assertKeysSubset, has,
  optString, nonBlank, optDate, requiredDate, clampLimit, truncate, eventFields, parseRecurrence,
  APPLY_TO_SCHEMA, parseApplyTo, assertSeriesAcknowledged, VISIBILITY_NOTE, withRecurrence, buildTool,
  CAL_CREATE_KEYS, CAL_UPDATE_KEYS, CAL_DELETE_KEYS, requireId,
} from './device_schedule_support.ts';

export const createCalendarScheduleTools = (port: DeviceSchedulePort, now: () => number): AgentTool[] => {
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

  return [calendarEventsList, calendarEventCreate, calendarEventUpdate, calendarEventDelete];
};
