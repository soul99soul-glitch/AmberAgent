// device_schedule_tools.test.ts — 日历/提醒/通知/闹钟工具(iOS Apple 工具对齐)
// 覆盖:工具声明与审批位、ISO-8601 解析(含偏移)、参数校验、结果形态、Port 错误映射。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { JsonObject, JsonValue } from '../main/ets/chat/json.ts';
import type { UIMessagePart } from '../main/ets/chat/message.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import type {
  DeviceAlarm, DeviceAlarmRequest, DeviceCalendarEvent, DeviceCalendarEventDraft,
  DeviceNotificationRequest, DeviceReminder, DeviceReminderDraft, DeviceScheduledNotification,
  DeviceSchedulePort,
} from '../main/ets/chat/device_schedule_tools.ts';
import {
  createDeviceScheduleTools, DeviceScheduleError, DEVICE_SCHEDULE_TOOL_NAMES,
  parseIsoDateTime, formatIsoDateTime,
  encodeReminderEventDescription, decodeReminderEventDescription,
  encodeReminderEventTitle, decodeReminderEventTitle, reminderEventWindow,
} from '../main/ets/chat/device_schedule_tools.ts';
import type { ToolRegistry } from '../main/ets/chat/tool_registry.ts';
import { createToolRegistry } from '../main/ets/chat/tool_registry.ts';
import { createToolSearchTool } from '../main/ets/chat/builtin_introspection_tools.ts';

process.env.TZ = 'Asia/Shanghai';

const NOW: number = Date.UTC(2026, 9, 9, 2, 0, 0); // 2026-10-09T10:00:00+08:00

// ===== Fake Port =====

class FakePort implements DeviceSchedulePort {
  events: DeviceCalendarEvent[] = [];
  reminders: DeviceReminder[] = [];
  alarms: DeviceAlarm[] = [];
  notification: DeviceScheduledNotification | null = null;
  calls: string[] = [];
  lastEventDraft: DeviceCalendarEventDraft | null = null;
  lastReminderDraft: DeviceReminderDraft | null = null;
  lastAlarmRequest: DeviceAlarmRequest | null = null;
  lastListRange: number[] = [];
  failWith: DeviceScheduleError | null = null;
  private seq: number = 100;

  private guard(name: string): void {
    this.calls.push(name);
    if (this.failWith !== null) throw this.failWith;
  }

  async listCalendarEvents(startMs: number, endMs: number): Promise<DeviceCalendarEvent[]> {
    this.guard('listCalendarEvents');
    this.lastListRange = [startMs, endMs];
    return this.events.filter((e: DeviceCalendarEvent): boolean => e.startMs < endMs && e.endMs > startMs);
  }
  async getCalendarEvent(eventId: string): Promise<DeviceCalendarEvent | null> {
    this.guard('getCalendarEvent');
    return this.events.find((e: DeviceCalendarEvent): boolean => e.eventId === eventId) ?? null;
  }
  async createCalendarEvent(draft: DeviceCalendarEventDraft): Promise<DeviceCalendarEvent> {
    this.guard('createCalendarEvent');
    this.lastEventDraft = draft;
    const ev: DeviceCalendarEvent = {
      eventId: `1:${this.seq++}`, title: draft.title, startMs: draft.startMs, endMs: draft.endMs,
      allDay: draft.allDay, calendarId: draft.calendarId ?? '1', calendarName: '默认',
      location: draft.location, notes: draft.notes, recurrence: draft.recurrence,
    };
    this.events.push(ev);
    return ev;
  }
  async updateCalendarEvent(eventId: string, draft: DeviceCalendarEventDraft): Promise<DeviceCalendarEvent> {
    this.guard('updateCalendarEvent');
    this.lastEventDraft = draft;
    const idx: number = this.events.findIndex((e: DeviceCalendarEvent): boolean => e.eventId === eventId);
    const prev: DeviceCalendarEvent = this.events[idx];
    const ev: DeviceCalendarEvent = {
      ...prev, title: draft.title, startMs: draft.startMs, endMs: draft.endMs, allDay: draft.allDay,
      location: draft.location, notes: draft.notes, recurrence: draft.recurrence,
      calendarId: draft.calendarId ?? prev.calendarId,
    };
    this.events[idx] = ev;
    return ev;
  }
  async deleteCalendarEvent(eventId: string): Promise<void> {
    this.guard('deleteCalendarEvent');
    this.events = this.events.filter((e: DeviceCalendarEvent): boolean => e.eventId !== eventId);
  }
  async listReminders(): Promise<DeviceReminder[]> {
    this.guard('listReminders');
    return this.reminders;
  }
  async getReminder(reminderId: string): Promise<DeviceReminder | null> {
    this.guard('getReminder');
    return this.reminders.find((r: DeviceReminder): boolean => r.reminderId === reminderId) ?? null;
  }
  async createReminder(draft: DeviceReminderDraft): Promise<DeviceReminder> {
    this.guard('createReminder');
    this.lastReminderDraft = draft;
    const r: DeviceReminder = {
      reminderId: `${this.seq++}`, title: draft.title, dueMs: draft.dueMs, notes: draft.notes,
      priority: draft.priority, completed: draft.completed, completedAtMs: draft.completedAtMs,
      listId: '9', listName: 'Amber 提醒',
    };
    this.reminders.push(r);
    return r;
  }
  async updateReminder(reminderId: string, draft: DeviceReminderDraft): Promise<DeviceReminder> {
    this.guard('updateReminder');
    this.lastReminderDraft = draft;
    const idx: number = this.reminders.findIndex((r: DeviceReminder): boolean => r.reminderId === reminderId);
    const r: DeviceReminder = {
      ...this.reminders[idx], title: draft.title, dueMs: draft.dueMs, notes: draft.notes,
      priority: draft.priority, completed: draft.completed, completedAtMs: draft.completedAtMs,
    };
    this.reminders[idx] = r;
    return r;
  }
  async deleteReminder(reminderId: string): Promise<void> {
    this.guard('deleteReminder');
    this.reminders = this.reminders.filter((r: DeviceReminder): boolean => r.reminderId !== reminderId);
  }
  async scheduleNotification(request: DeviceNotificationRequest): Promise<DeviceScheduledNotification> {
    this.guard('scheduleNotification');
    this.notification = { notificationId: '77', title: request.title, body: request.body, fireAtMs: request.fireAtMs };
    return this.notification;
  }
  async cancelNotification(): Promise<boolean> {
    this.guard('cancelNotification');
    const had: boolean = this.notification !== null;
    this.notification = null;
    return had;
  }
  async scheduleAlarm(request: DeviceAlarmRequest): Promise<DeviceAlarm> {
    this.guard('scheduleAlarm');
    this.lastAlarmRequest = request;
    const a: DeviceAlarm = { alarmId: `${this.seq++}`, state: 'scheduled', createdAtMs: NOW, ...request };
    this.alarms.push(a);
    return a;
  }
  async listAlarms(): Promise<DeviceAlarm[]> {
    this.guard('listAlarms');
    return this.alarms;
  }
  async cancelAlarm(alarmId: string): Promise<DeviceAlarm> {
    this.guard('cancelAlarm');
    const a: DeviceAlarm | undefined = this.alarms.find((x: DeviceAlarm): boolean => x.alarmId === alarmId);
    if (a === undefined) throw new DeviceScheduleError('not_found', '该 Amber 闹钟不存在、已响过或已被删除。');
    this.alarms = this.alarms.filter((x: DeviceAlarm): boolean => x.alarmId !== alarmId);
    return a;
  }
}

const setup = (): { port: FakePort; tools: Map<string, AgentTool> } => {
  const port: FakePort = new FakePort();
  const tools: Map<string, AgentTool> = new Map();
  for (const t of createDeviceScheduleTools(port, (): number => NOW)) tools.set(t.name, t);
  return { port, tools };
};

const run = async (tools: Map<string, AgentTool>, name: string, input: JsonValue): Promise<JsonObject> => {
  const tool: AgentTool | undefined = tools.get(name);
  assert.ok(tool !== undefined, `missing tool ${name}`);
  const parts: UIMessagePart[] = await tool.execute(input);
  assert.equal(parts.length, 1);
  return JSON.parse((parts[0] as { text: string }).text) as JsonObject;
};

const ev = (id: string, startIso: string, endIso: string, title: string = 'E'): DeviceCalendarEvent => ({
  eventId: id, title, startMs: parseIsoDateTime(startIso) as number, endMs: parseIsoDateTime(endIso) as number,
  allDay: false, calendarId: '1', calendarName: '默认', location: '', notes: '', recurrence: null,
});

// ===== 声明 =====

test('声明:14 个工具名与 iOS 一致,全部强制前台审批且不可自动批准', () => {
  const { tools } = setup();
  assert.deepEqual([...tools.keys()], DEVICE_SCHEDULE_TOOL_NAMES);
  assert.equal(tools.size, 14);
  for (const t of tools.values()) {
    assert.equal(t.needsApproval, true, t.name);
    assert.equal(t.mandatoryApproval, true, t.name);
    assert.equal(t.allowsAutoApproval, false, t.name);
    assert.ok(t.parameters() !== null, t.name);
  }
  assert.deepEqual(tools.get('calendar_event_create')?.parameters()?.required, ['title', 'start_at', 'end_at']);
  assert.deepEqual(tools.get('alarm_schedule')?.parameters()?.required, ['title', 'kind']);
  assert.deepEqual(tools.get('notification_schedule')?.parameters()?.required, ['title', 'fire_at']);
  const recurrence: JsonObject = tools.get('calendar_event_update')?.parameters()?.properties['recurrence'] as JsonObject;
  assert.deepEqual(recurrence['enum'], ['none', 'daily', 'weekly', 'monthly']);
});

// ===== ISO-8601 =====

test('parseIsoDateTime:Z / ±HH:mm / ±HHmm / ±HH / 小数秒', () => {
  assert.equal(parseIsoDateTime('2026-10-09T02:00:00Z'), NOW);
  assert.equal(parseIsoDateTime('2026-10-09T10:00:00+08:00'), NOW);
  assert.equal(parseIsoDateTime('2026-10-09T10:00:00+0800'), NOW);
  assert.equal(parseIsoDateTime('2026-10-09T10:00+08'), NOW);
  assert.equal(parseIsoDateTime('2026-10-08T21:00:00-05:00'), NOW);
  assert.equal(parseIsoDateTime('2026-10-09T02:00:00.250Z'), NOW + 250);
  assert.equal(parseIsoDateTime(' 2026-10-09 10:00:00+08:00 '), NOW);
});

test('parseIsoDateTime:无时区 → 设备本地时间;纯日期 → 本地零点', () => {
  assert.equal(parseIsoDateTime('2026-10-09T10:00:00'), NOW);
  assert.equal(parseIsoDateTime('2026-10-09'), Date.UTC(2026, 9, 8, 16, 0, 0));
});

test('parseIsoDateTime:非法值返回 null', () => {
  for (const bad of ['', 'tomorrow', '2026-13-01T00:00:00Z', '2026-02-30T00:00:00Z', '2026-10-09T24:00:00Z',
    '2026-10-09T10:60:00Z', '2026-10-09Z', '2026/10/09 10:00', '2026-10-09T10:00:00+25:00', '10:00']) {
    assert.equal(parseIsoDateTime(bad), null, bad);
  }
  assert.notEqual(parseIsoDateTime('2028-02-29T00:00:00Z'), null);
});

test('formatIsoDateTime:本地偏移表示,可往返', () => {
  assert.equal(formatIsoDateTime(NOW), '2026-10-09T10:00:00+08:00');
  assert.equal(parseIsoDateTime(formatIsoDateTime(NOW + 61000)), NOW + 61000);
});

// ===== calendar_events_list =====

test('calendar_events_list:默认 now..+7 天,按开始时间排序,limit 裁剪 1…100', async () => {
  const { port, tools } = setup();
  port.events = [
    ev('1:3', '2026-10-11T09:00:00+08:00', '2026-10-11T10:00:00+08:00', 'C'),
    ev('1:1', '2026-10-09T12:00:00+08:00', '2026-10-09T13:00:00+08:00', 'A'),
    ev('1:2', '2026-10-10T09:00:00+08:00', '2026-10-10T10:00:00+08:00', 'B'),
  ];
  const out: JsonObject = await run(tools, 'calendar_events_list', {});
  assert.equal(out['ok'], true);
  assert.equal(out['tool'], 'calendar_events_list');
  assert.deepEqual(port.lastListRange, [NOW, NOW + 7 * 86400000]);
  const events: JsonObject[] = out['events'] as JsonObject[];
  assert.deepEqual(events.map((e: JsonObject): JsonValue => e['title']), ['A', 'B', 'C']);
  assert.deepEqual(Object.keys(events[0]).sort(), ['all_day', 'calendar', 'calendar_id', 'end_at', 'event_id',
    'location', 'notes', 'recurrence', 'recurring', 'start_at', 'title']);
  assert.equal(events[0]['start_at'], '2026-10-09T12:00:00+08:00');
  assert.deepEqual(events[0]['recurrence'], {});
  assert.equal(events[0]['recurring'], false);
  assert.equal(typeof out['visibility_note'], 'string');
  const limited: JsonObject = await run(tools, 'calendar_events_list', { limit: 0 });
  assert.equal((limited['events'] as JsonObject[]).length, 1);
  const lenient: JsonObject = await run(tools, 'calendar_events_list', { limit: 'abc' });
  assert.equal((lenient['events'] as JsonObject[]).length, 3);
});

test('calendar_events_list:范围倒置 / 超 366 天 / 非法日期 / 未知字段 → ok:false', async () => {
  const { port, tools } = setup();
  const inverted: JsonObject = await run(tools, 'calendar_events_list',
    { start_at: '2026-10-10T00:00:00Z', end_at: '2026-10-09T00:00:00Z' });
  assert.equal(inverted['ok'], false);
  assert.equal(inverted['error_code'], 'invalid_date_range');
  assert.equal(typeof inverted['reason'], 'string');
  const tooWide: JsonObject = await run(tools, 'calendar_events_list',
    { start_at: '2026-01-01T00:00:00Z', end_at: '2027-01-03T00:00:00Z' });
  assert.equal(tooWide['error_code'], 'invalid_date_range');
  const badDate: JsonObject = await run(tools, 'calendar_events_list', { start_at: 'next monday' });
  assert.equal(badDate['error_code'], 'invalid_date');
  const unknown: JsonObject = await run(tools, 'calendar_events_list', { calendar: 'x' });
  assert.equal(unknown['error_code'], 'invalid_arguments');
  assert.equal(port.calls.length, 0);
  const withDisplay: JsonObject = await run(tools, 'calendar_events_list', { display_title: '看日程' });
  assert.equal(withDisplay['ok'], true);
});

test('calendar_events_list:Port 权限拒绝 → ok:false + reason + error_code', async () => {
  const { port, tools } = setup();
  port.failWith = new DeviceScheduleError('permission_denied', '未获准访问日历,可在系统设置中修改权限。');
  const out: JsonObject = await run(tools, 'calendar_events_list', {});
  assert.deepEqual(out, {
    ok: false, tool: 'calendar_events_list',
    reason: '未获准访问日历,可在系统设置中修改权限。', error_code: 'permission_denied',
  });
});

test('Port 抛出普通 Error → platform_error;非对象输入 → invalid_arguments', async () => {
  const { tools } = setup();
  const brokenPort: FakePort = new FakePort();
  brokenPort.listCalendarEvents = (): Promise<DeviceCalendarEvent[]> => Promise.reject(new Error('boom'));
  const t: AgentTool = createDeviceScheduleTools(brokenPort, (): number => NOW)[0];
  const out: JsonObject = JSON.parse((((await t.execute({})) as UIMessagePart[])[0] as { text: string }).text) as JsonObject;
  assert.equal(out['error_code'], 'platform_error');
  assert.equal(out['reason'], 'boom');
  const notObj: JsonObject = await run(tools, 'calendar_event_delete', 'x');
  assert.equal(notObj['error_code'], 'invalid_arguments');
});

// ===== calendar_event_create =====

test('calendar_event_create:成功写入,截断与重复规则映射', async () => {
  const { port, tools } = setup();
  const out: JsonObject = await run(tools, 'calendar_event_create', {
    title: '  周会  ', start_at: '2026-10-12T09:00:00+08:00', end_at: '2026-10-12T10:00:00+08:00',
    location: 'L'.repeat(400), notes: 'n', recurrence: 'weekly', recurrence_interval: 2,
    recurrence_end_at: '2026-12-31T00:00:00+08:00',
  });
  assert.equal(out['ok'], true);
  assert.equal(out['title'], '周会');
  assert.equal(out['start_at'], '2026-10-12T09:00:00+08:00');
  assert.equal((out['location'] as string).length, 300);
  assert.deepEqual(out['recurrence'], { frequency: 'weekly', interval: 2, end_at: '2026-12-31T00:00:00+08:00' });
  assert.equal(port.lastEventDraft?.calendarId, null);
  assert.equal(port.lastEventDraft?.allDay, false);
});

test('calendar_event_create:缺 title / end<=start / 重复规则非法 → 不触达 Port', async () => {
  const { port, tools } = setup();
  const base: JsonObject = { title: 'x', start_at: '2026-10-12T09:00:00Z', end_at: '2026-10-12T10:00:00Z' };
  assert.equal((await run(tools, 'calendar_event_create', { ...base, title: '  ' }))['error_code'], 'invalid_arguments');
  assert.equal((await run(tools, 'calendar_event_create', { ...base, end_at: base['start_at'] }))['error_code'], 'invalid_date_range');
  assert.equal((await run(tools, 'calendar_event_create', { ...base, end_at: undefined as unknown as JsonValue }))['error_code'], 'invalid_date');
  assert.equal((await run(tools, 'calendar_event_create', { ...base, recurrence: 'yearly' }))['error_code'], 'invalid_recurrence');
  assert.equal((await run(tools, 'calendar_event_create', { ...base, recurrence: 'daily', recurrence_interval: 31 }))['error_code'], 'invalid_recurrence');
  assert.equal((await run(tools, 'calendar_event_create', { ...base, recurrence_interval: 2 }))['error_code'], 'invalid_recurrence');
  assert.equal((await run(tools, 'calendar_event_create',
    { ...base, recurrence: 'daily', recurrence_end_at: '2026-10-12T08:00:00Z' }))['error_code'], 'invalid_recurrence');
  assert.equal(port.calls.length, 0);
});

// ===== calendar_event_update / delete =====

test('calendar_event_update:仅改所给字段;空串清空;recurrence none 移除', async () => {
  const { port, tools } = setup();
  const existing: DeviceCalendarEvent = ev('1:5', '2026-10-12T09:00:00+08:00', '2026-10-12T10:00:00+08:00', '旧');
  existing.location = '会议室';
  existing.notes = '备注';
  existing.recurrence = { frequency: 'daily', interval: 1, endMs: null };
  port.events = [existing];
  const out: JsonObject = await run(tools, 'calendar_event_update',
    { event_id: '1:5', location: '', recurrence: 'none', apply_to: 'series' });
  assert.equal(out['ok'], true);
  assert.equal(port.lastEventDraft?.recurrenceSpecified, true);
  assert.equal(port.lastEventDraft?.recurrence, null);
  assert.equal(out['recurring'], false);
  assert.equal(out['title'], '旧');
  assert.equal(out['location'], '');
  assert.equal(out['notes'], '备注');
  assert.deepEqual(out['recurrence'], {});
  assert.equal(port.lastEventDraft?.calendarId, null);
  const moved: JsonObject = await run(tools, 'calendar_event_update', { event_id: '1:5', end_at: '2026-10-12T11:00:00+08:00' });
  assert.equal(moved['end_at'], '2026-10-12T11:00:00+08:00');
  assert.equal(port.lastEventDraft?.recurrence, null);
  assert.equal(port.lastEventDraft?.recurrenceSpecified, false);
});

test('calendar_event_update:只给 event_id / 不存在 / 合并后 end<=start → ok:false', async () => {
  const { port, tools } = setup();
  port.events = [ev('1:5', '2026-10-12T09:00:00+08:00', '2026-10-12T10:00:00+08:00')];
  assert.equal((await run(tools, 'calendar_event_update', { event_id: '1:5' }))['error_code'], 'invalid_arguments');
  assert.equal((await run(tools, 'calendar_event_update', { event_id: '1:404', title: 'x' }))['error_code'], 'not_found');
  const inverted: JsonObject = await run(tools, 'calendar_event_update',
    { event_id: '1:5', start_at: '2026-10-12T10:30:00+08:00' });
  assert.equal(inverted['error_code'], 'invalid_date_range');
  assert.equal(port.calls.includes('updateCalendarEvent'), false);
});

test('calendar_event_delete:只接受 event_id;成功返回 deleted:true 与标题', async () => {
  const { port, tools } = setup();
  port.events = [ev('1:5', '2026-10-12T09:00:00+08:00', '2026-10-12T10:00:00+08:00', '要删的')];
  assert.equal((await run(tools, 'calendar_event_delete', { event_id: '1:5', title: 'x' }))['error_code'], 'invalid_arguments');
  const out: JsonObject = await run(tools, 'calendar_event_delete', { event_id: '1:5' });
  assert.deepEqual(out, { ok: true, tool: 'calendar_event_delete', event_id: '1:5', title: '要删的', deleted: true });
  assert.equal(port.events.length, 0);
  assert.equal((await run(tools, 'calendar_event_delete', { event_id: '1:5' }))['error_code'], 'not_found');
});

// ===== reminders =====

test('reminder_create → reminders_list:默认隐藏已完成,按 due 升序(无 due 置后)', async () => {
  const { port, tools } = setup();
  const created: JsonObject = await run(tools, 'reminder_create',
    { title: '交房租', due_at: '2026-10-15T09:00:00+08:00', priority: 1 });
  assert.equal(created['ok'], true);
  assert.equal(created['due_at'], '2026-10-15T09:00:00+08:00');
  assert.equal(created['completed'], false);
  assert.equal(created['priority'], 1);
  assert.equal('completed_at' in created, false);
  await run(tools, 'reminder_create', { title: '买牛奶' });
  await run(tools, 'reminder_create', { title: '早', due_at: '2026-10-10T09:00:00+08:00' });
  port.reminders[0].completed = true;
  const list: JsonObject = await run(tools, 'reminders_list', {});
  assert.deepEqual((list['reminders'] as JsonObject[]).map((r: JsonObject): JsonValue => r['title']), ['早', '买牛奶']);
  const all: JsonObject = await run(tools, 'reminders_list', { include_completed: true, limit: 2 });
  assert.deepEqual((all['reminders'] as JsonObject[]).map((r: JsonObject): JsonValue => r['title']), ['早', '交房租']);
});

test('reminder_create:priority 越界 / 空标题 / 非法 due → ok:false', async () => {
  const { port, tools } = setup();
  assert.equal((await run(tools, 'reminder_create', { title: 'x', priority: 10 }))['error_code'], 'invalid_arguments');
  assert.equal((await run(tools, 'reminder_create', { title: 'x', priority: 1.5 }))['error_code'], 'invalid_arguments');
  assert.equal((await run(tools, 'reminder_create', { title: '' }))['error_code'], 'invalid_arguments');
  assert.equal((await run(tools, 'reminder_create', { title: 'x', due_at: '明天' }))['error_code'], 'invalid_date');
  assert.equal(port.calls.length, 0);
});

test('reminder_update:remove_due_date 清 due;与 due_at 互斥;保留未给字段', async () => {
  const { port, tools } = setup();
  await run(tools, 'reminder_create', { title: 'A', due_at: '2026-10-15T09:00:00+08:00', notes: 'n', priority: 5 });
  const id: string = port.reminders[0].reminderId;
  assert.equal((await run(tools, 'reminder_update',
    { reminder_id: id, remove_due_date: true, due_at: '2026-10-16T09:00:00Z' }))['error_code'], 'invalid_arguments');
  const out: JsonObject = await run(tools, 'reminder_update', { reminder_id: id, remove_due_date: true });
  assert.equal(out['ok'], true);
  assert.equal('due_at' in out, false);
  assert.equal(out['notes'], 'n');
  assert.equal(out['priority'], 5);
  assert.equal(port.lastReminderDraft?.listId, null);
  assert.equal((await run(tools, 'reminder_update', { reminder_id: 'nope', title: 'x' }))['error_code'], 'not_found');
});

test('reminder_complete:置完成并记录 completed_at;delete 返回 deleted:true', async () => {
  const { port, tools } = setup();
  await run(tools, 'reminder_create', { title: 'A' });
  const id: string = port.reminders[0].reminderId;
  const done: JsonObject = await run(tools, 'reminder_complete', { reminder_id: id });
  assert.equal(done['completed'], true);
  assert.equal(done['completed_at'], formatIsoDateTime(NOW));
  assert.equal(port.lastReminderDraft?.completed, true);
  const del: JsonObject = await run(tools, 'reminder_delete', { reminder_id: id });
  assert.deepEqual(del, { ok: true, tool: 'reminder_delete', reminder_id: id, title: 'A', deleted: true });
  assert.equal((await run(tools, 'reminder_complete', { reminder_id: id }))['error_code'], 'not_found');
});

// ===== 提醒编解码(entry 复用) =====

test('提醒描述标记:编码/解码往返,无标记原样返回', () => {
  const encoded: string = encodeReminderEventDescription('第一行\n第二行', { hasDue: true, priority: 3, completedAtMs: NOW });
  assert.ok(encoded.startsWith('第一行\n第二行\n\n[amber-reminder:'));
  assert.deepEqual(decodeReminderEventDescription(encoded),
    { notes: '第一行\n第二行', meta: { hasDue: true, priority: 3, completedAtMs: NOW } });
  assert.deepEqual(decodeReminderEventDescription(encodeReminderEventDescription('', { hasDue: false, priority: 0, completedAtMs: null })),
    { notes: '', meta: { hasDue: false, priority: 0, completedAtMs: null } });
  assert.deepEqual(decodeReminderEventDescription('普通描述'), { notes: '普通描述', meta: null });
  assert.equal(decodeReminderEventTitle(encodeReminderEventTitle('交房租', true), true), '交房租');
  assert.equal(encodeReminderEventTitle('交房租', true), '✓ 交房租');
  assert.equal(decodeReminderEventTitle('✓ 交房租', false), '✓ 交房租');
});

test('reminderEventWindow:有 due → 15 分钟窗口准点提醒;无 due → 当天全天无提醒', () => {
  assert.deepEqual(reminderEventWindow(NOW, 0, false), { startMs: NOW, endMs: NOW + 900000, allDay: false, reminderMinutes: [0] });
  assert.deepEqual(reminderEventWindow(NOW, 0, true).reminderMinutes, []);
  const day = reminderEventWindow(null, NOW, false);
  assert.equal(day.startMs, Date.UTC(2026, 9, 8, 16, 0, 0));
  assert.equal(day.endMs - day.startMs, 86400000);
  assert.equal(day.allDay, true);
});

// ===== notifications =====

test('notification_schedule:≥5 秒;空标题/正文用默认;结果含 identifier/fire_at', async () => {
  const { port, tools } = setup();
  const tooSoon: JsonObject = await run(tools, 'notification_schedule',
    { title: 'x', fire_at: formatIsoDateTime(NOW + 4000) });
  assert.equal(tooSoon['ok'], false);
  assert.equal(tooSoon['reason'], '提醒时间至少需要在 5 秒以后。');
  const missing: JsonObject = await run(tools, 'notification_schedule', { fire_at: '2026-10-09T11:00:00+08:00' });
  assert.equal(missing['reason'], '需要 title 与 ISO-8601 fire_at。');
  const out: JsonObject = await run(tools, 'notification_schedule', { title: ' ', fire_at: '2026-10-09T11:00:00+08:00' });
  assert.deepEqual(out, {
    ok: true, tool: 'notification_schedule', identifier: '77', title: 'Amber 提醒',
    body: '点按返回最近对话。', fire_at: '2026-10-09T11:00:00+08:00',
  });
  assert.equal(port.notification?.fireAtMs, NOW + 3600000);
});

test('notification_cancel:不接受参数;返回 cancelled 与 had_pending', async () => {
  const { tools } = setup();
  assert.equal((await run(tools, 'notification_cancel', { id: '1' }))['reason'], 'notification_cancel 不接受参数。');
  await run(tools, 'notification_schedule', { title: 't', fire_at: '2026-10-09T11:00:00+08:00' });
  assert.deepEqual(await run(tools, 'notification_cancel', {}),
    { ok: true, tool: 'notification_cancel', cancelled: true, had_pending: true });
  assert.deepEqual(await run(tools, 'notification_cancel', {}),
    { ok: true, tool: 'notification_cancel', cancelled: true, had_pending: false });
});

// ===== alarms =====

test('alarm_schedule:one_time / weekly(星期去重排序)/ timer 成功形态', async () => {
  const { port, tools } = setup();
  const one: JsonObject = await run(tools, 'alarm_schedule',
    { title: '起床', kind: 'one_time', fire_at: '2026-10-10T07:00:00+08:00' });
  assert.equal(one['ok'], true);
  const alarm: JsonObject = one['alarm'] as JsonObject;
  assert.equal(alarm['kind'], 'one_time');
  assert.equal(alarm['fire_at'], '2026-10-10T07:00:00+08:00');
  assert.equal(alarm['state'], 'scheduled');
  assert.equal('weekdays' in alarm, false);
  const weekly: JsonObject = await run(tools, 'alarm_schedule',
    { title: '健身', kind: 'weekly', weekdays: ['Friday', 'monday', 'friday'], hour: 7, minute: 30 });
  assert.deepEqual((weekly['alarm'] as JsonObject)['weekdays'], ['monday', 'friday']);
  assert.deepEqual(port.lastAlarmRequest?.weekdays, ['monday', 'friday']);
  const timer: JsonObject = await run(tools, 'alarm_schedule', { title: '泡面', kind: 'timer', duration_seconds: 180 });
  assert.equal((timer['alarm'] as JsonObject)['duration_seconds'], 180);
  const list: JsonObject = await run(tools, 'alarms_list', {});
  assert.equal((list['alarms'] as JsonObject[]).length, 3);
});

test('alarm_schedule:互斥字段 / 越界 / 标题 → ok:false 且用 error 键', async () => {
  const { port, tools } = setup();
  const cases: JsonObject[] = [
    { title: '', kind: 'timer', duration_seconds: 60 },
    { title: 'x'.repeat(81), kind: 'timer', duration_seconds: 60 },
    { title: 't', kind: 'snooze' },
    { title: 't', kind: 'one_time', fire_at: formatIsoDateTime(NOW + 1000) },
    { title: 't', kind: 'one_time', fire_at: '2026-10-10T07:00:00+08:00', hour: 7 },
    { title: 't', kind: 'weekly', weekdays: [], hour: 7, minute: 0 },
    { title: 't', kind: 'weekly', weekdays: ['funday'], hour: 7, minute: 0 },
    { title: 't', kind: 'weekly', weekdays: ['monday'], hour: 24, minute: 0 },
    { title: 't', kind: 'weekly', weekdays: ['monday'], hour: 7 },
    { title: 't', kind: 'timer', duration_seconds: 5 },
    { title: 't', kind: 'timer', duration_seconds: 86401 },
    { title: 't', kind: 'timer', duration_seconds: 60, weekdays: ['monday'] },
    { title: 't', kind: 'timer', duration_seconds: 60, sound: 'x' },
  ];
  for (const c of cases) {
    const out: JsonObject = await run(tools, 'alarm_schedule', c);
    assert.equal(out['ok'], false, JSON.stringify(c));
    assert.equal(typeof out['error'], 'string', JSON.stringify(c));
    assert.equal('reason' in out, false);
    assert.equal(out['error_code'], 'invalid_arguments');
  }
  assert.equal(port.calls.length, 0);
});

test('alarm_schedule:Port 容量上限 → capacity_reached', async () => {
  const { port, tools } = setup();
  port.failWith = new DeviceScheduleError('capacity_reached', '系统提醒数量已达上限,请先取消一个闹钟。');
  const out: JsonObject = await run(tools, 'alarm_schedule', { title: 't', kind: 'timer', duration_seconds: 60 });
  assert.deepEqual(out, { ok: false, tool: 'alarm_schedule', error: '系统提醒数量已达上限,请先取消一个闹钟。', error_code: 'capacity_reached' });
});

test('alarm_cancel:数字 ID;不存在 → not_found;alarms_list 拒绝参数', async () => {
  const { tools } = setup();
  const created: JsonObject = await run(tools, 'alarm_schedule', { title: 't', kind: 'timer', duration_seconds: 60 });
  const id: string = (created['alarm'] as JsonObject)['alarm_id'] as string;
  assert.equal((await run(tools, 'alarm_cancel', { alarm_id: 'abc' }))['error_code'], 'invalid_arguments');
  assert.equal((await run(tools, 'alarm_cancel', {}))['error_code'], 'invalid_arguments');
  assert.deepEqual(await run(tools, 'alarm_cancel', { alarm_id: id }),
    { ok: true, tool: 'alarm_cancel', alarm_id: id, cancelled: true });
  assert.equal((await run(tools, 'alarm_cancel', { alarm_id: id }))['error_code'], 'not_found');
  assert.equal((await run(tools, 'alarms_list', { all: true }))['ok'], false);
});

// ===== 重复日程:整系列确认 / 规则透传 / 列表摘要 =====

const recurringEvent = (): DeviceCalendarEvent => {
  const e: DeviceCalendarEvent = ev('1:7', '2026-10-12T09:00:00+08:00', '2026-10-12T10:00:00+08:00', '周会');
  e.recurrence = { frequency: 'weekly', interval: 2, endMs: null, count: 10, extraRules: ['days_of_week'] };
  return e;
};

test('calendar_events_list:重复日程输出 recurring:true + recurrence 摘要(含 count/other_rules)', async () => {
  const { port, tools } = setup();
  port.events = [recurringEvent()];
  const out: JsonObject = await run(tools, 'calendar_events_list', {});
  const e: JsonObject = (out['events'] as JsonObject[])[0];
  assert.equal(e['recurring'], true);
  assert.deepEqual(e['recurrence'], { frequency: 'weekly', interval: 2, count: 10, other_rules: ['days_of_week'] });
  assert.equal(e['recurrence_summary'], 'every 2 weeks, 10 occurrences (plus days_of_week rules)');
  assert.match(out['visibility_note'] as string, /default calendar/);
});

test('calendar_event_update:重复日程未带 apply_to → recurring_series 且不触达写入', async () => {
  const { port, tools } = setup();
  port.events = [recurringEvent()];
  const out: JsonObject = await run(tools, 'calendar_event_update', { event_id: '1:7', title: '新周会' });
  assert.equal(out['ok'], false);
  assert.equal(out['error_code'], 'recurring_series');
  assert.match(out['reason'] as string, /apply_to/);
  assert.equal(port.calls.includes('updateCalendarEvent'), false);
  const bad: JsonObject = await run(tools, 'calendar_event_update', { event_id: '1:7', title: 'x', apply_to: 'this' });
  assert.equal(bad['error_code'], 'invalid_arguments');
  const onlyAck: JsonObject = await run(tools, 'calendar_event_update', { event_id: '1:7', apply_to: 'series' });
  assert.equal(onlyAck['error_code'], 'invalid_arguments');
  assert.equal(port.calls.includes('updateCalendarEvent'), false);
});

test('calendar_event_update:apply_to series → 成功;未给 recurrence → 原规则原样透传(recurrenceSpecified=false)', async () => {
  const { port, tools } = setup();
  const existing: DeviceCalendarEvent = recurringEvent();
  port.events = [existing];
  const out: JsonObject = await run(tools, 'calendar_event_update', { event_id: '1:7', title: '新周会', apply_to: 'series' });
  assert.equal(out['ok'], true);
  assert.equal(out['title'], '新周会');
  assert.equal(port.lastEventDraft?.recurrenceSpecified, false);
  assert.deepEqual(port.lastEventDraft?.recurrence, existing.recurrence);
  assert.equal(out['recurring'], true);
  // 显式改重复规则 → recurrenceSpecified=true,新规则不带平台附加字段
  const changed: JsonObject = await run(tools, 'calendar_event_update',
    { event_id: '1:7', recurrence: 'daily', apply_to: 'series' });
  assert.equal(changed['ok'], true);
  assert.equal(port.lastEventDraft?.recurrenceSpecified, true);
  assert.deepEqual(port.lastEventDraft?.recurrence, { frequency: 'daily', interval: 1, endMs: null });
});

test('calendar_event_update:非重复日程无需 apply_to;给了也允许', async () => {
  const { port, tools } = setup();
  port.events = [ev('1:5', '2026-10-12T09:00:00+08:00', '2026-10-12T10:00:00+08:00')];
  assert.equal((await run(tools, 'calendar_event_update', { event_id: '1:5', title: 'a' }))['ok'], true);
  assert.equal((await run(tools, 'calendar_event_update', { event_id: '1:5', title: 'b', apply_to: 'series' }))['ok'], true);
  // 新增重复规则(原非重复)不需要 apply_to
  assert.equal((await run(tools, 'calendar_event_update', { event_id: '1:5', recurrence: 'weekly' }))['ok'], true);
});

test('calendar_event_delete:重复日程需 apply_to series,成功后标注 deleted_series', async () => {
  const { port, tools } = setup();
  port.events = [recurringEvent()];
  const refused: JsonObject = await run(tools, 'calendar_event_delete', { event_id: '1:7' });
  assert.equal(refused['error_code'], 'recurring_series');
  assert.equal(port.calls.includes('deleteCalendarEvent'), false);
  assert.equal((await run(tools, 'calendar_event_delete', { event_id: '1:7', apply_to: 'all' }))['error_code'], 'invalid_arguments');
  const out: JsonObject = await run(tools, 'calendar_event_delete', { event_id: '1:7', apply_to: 'series' });
  assert.deepEqual(out, {
    ok: true, tool: 'calendar_event_delete', event_id: '1:7', title: '周会', deleted: true, deleted_series: true,
  });
  assert.equal(port.events.length, 0);
});

test('calendar_event_create:草稿 recurrenceSpecified 恒为 true(无重复 = 明确不重复)', async () => {
  const { port, tools } = setup();
  await run(tools, 'calendar_event_create',
    { title: 'x', start_at: '2026-10-12T09:00:00Z', end_at: '2026-10-12T10:00:00Z' });
  assert.equal(port.lastEventDraft?.recurrenceSpecified, true);
  assert.equal(port.lastEventDraft?.recurrence, null);
});

// ===== 提醒标记:任意位置 / 多个取最后 / \r\n / 标题幂等 =====

test('提醒描述标记:标记不在末尾也能解码并从 notes 剥除', () => {
  const desc: string = '买牛奶\n\n[amber-reminder:{"v":1,"due":1,"p":2,"c":null}]\n\n(由系统日历同步追加)';
  assert.deepEqual(decodeReminderEventDescription(desc),
    { notes: '买牛奶\n\n(由系统日历同步追加)', meta: { hasDue: true, priority: 2, completedAtMs: null } });
  const onlyAfter: string = '[amber-reminder:{"v":1,"due":0,"p":0,"c":null}] 尾巴';
  assert.deepEqual(decodeReminderEventDescription(onlyAfter),
    { notes: '尾巴', meta: { hasDue: false, priority: 0, completedAtMs: null } });
});

test('提醒描述标记:多个标记以最后一个为准,全部剥除;\\r\\n 归一', () => {
  const desc: string = 'a\r\nb\r\n\r\n[amber-reminder:{"v":1,"due":1,"p":1,"c":null}]\r\n\r\n'
    + '[amber-reminder:{"v":1,"due":0,"p":5,"c":123}]';
  assert.deepEqual(decodeReminderEventDescription(desc),
    { notes: 'a\nb', meta: { hasDue: false, priority: 5, completedAtMs: 123 } });
  // 只有无法解析的标记 → meta=null,内容(归一后)原样
  assert.deepEqual(decodeReminderEventDescription('x\r\n[amber-reminder:{oops}]'),
    { notes: 'x\n[amber-reminder:{oops}]', meta: null });
  // 尾部损坏、前面完好 → 用前面的
  assert.deepEqual(decodeReminderEventDescription('[amber-reminder:{"v":1,"due":1,"p":0,"c":null}]\n[amber-reminder:{bad}]'),
    { notes: '[amber-reminder:{bad}]', meta: { hasDue: true, priority: 0, completedAtMs: null } });
});

test('提醒标题:完成前缀编码幂等(reminder_complete 不会叠加 ✓)', () => {
  assert.equal(encodeReminderEventTitle('✓ 交房租', true), '✓ 交房租');
  assert.equal(encodeReminderEventTitle(encodeReminderEventTitle('交房租', true), true), '✓ 交房租');
  assert.equal(encodeReminderEventTitle('✓ 交房租', false), '✓ 交房租');
});

// ===== 通知 =====

test('notification_schedule:缺 title → invalid_arguments;缺/非法 fire_at → invalid_date', async () => {
  const { port, tools } = setup();
  const noTitle: JsonObject = await run(tools, 'notification_schedule', { fire_at: '2026-10-09T11:00:00+08:00' });
  assert.equal(noTitle['error_code'], 'invalid_arguments');
  const noFire: JsonObject = await run(tools, 'notification_schedule', { title: 't' });
  assert.equal(noFire['error_code'], 'invalid_date');
  const badFire: JsonObject = await run(tools, 'notification_schedule', { title: 't', fire_at: '明天' });
  assert.equal(badFire['error_code'], 'invalid_date');
  assert.equal(port.calls.length, 0);
});

test('notification_cancel:Port 取消失败 → ok:false,不得返回 cancelled:true', async () => {
  const { port, tools } = setup();
  port.failWith = new DeviceScheduleError('platform_error', '取消本地通知失败:系统错误(1700007)');
  const out: JsonObject = await run(tools, 'notification_cancel', {});
  assert.equal(out['ok'], false);
  assert.equal('cancelled' in out, false);
  assert.equal(out['error_code'], 'platform_error');
});

// ===== tool_search 别名(builtin_introspection_tools.searchAliases) =====

test('tool_search:中文/英文别名命中日历/提醒/通知/闹钟工具', async () => {
  const { tools } = setup();
  const registry: ToolRegistry = createToolRegistry([...tools.values()]);
  const search: AgentTool = createToolSearchTool(registry);
  const top = async (query: string): Promise<string[]> => {
    const parts: UIMessagePart[] = await search.execute({ query, limit: 3 });
    const payload: JsonObject = JSON.parse((parts[0] as { text: string }).text) as JsonObject;
    return (payload['expanded_tools'] as JsonValue[]).map((v: JsonValue): string => v as string);
  };
  const expectFirst: [string, string[]][] = [
    ['日历', ['calendar_']], ['日程', ['calendar_']], ['会议', ['calendar_']], ['calendar', ['calendar_']],
    ['提醒事项', ['reminder']], ['待办', ['reminder']], ['reminder', ['reminder']],
    ['闹钟', ['alarm']], ['定时', ['alarm']], ['倒计时', ['alarm']], ['alarm', ['alarm']],
    ['通知', ['notification_']], ['notification', ['notification_']],
  ];
  for (const [query, prefixes] of expectFirst) {
    const names: string[] = await top(query);
    assert.ok(names.length > 0, `${query}: no hit`);
    assert.ok(prefixes.some((p: string): boolean => names[0].startsWith(p)), `${query}: got ${names.join(',')}`);
  }
  const remind: string[] = await top('提醒');
  assert.ok(remind.some((n: string): boolean => n.startsWith('reminder')), `提醒: ${remind.join(',')}`);
});
