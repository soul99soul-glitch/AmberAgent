// Actual Entry facade/adapters execute unchanged; only native SDKs and Preferences are controlled.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const turn = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };
const failure = code => Object.assign(new Error(`SDK ${code}`), { code });
const alarm = title => ({ title, kind: 'timer', fireAtMs: null, weekdays: [], hour: null, minute: null, durationSeconds: 30 });
const notification = title => ({ title, body: 'body', fireAtMs: Date.now() + 60000 });
const reminder = title => ({ title, dueMs: null, notes: '', priority: 0, completed: false, completedAtMs: null, listId: null });
async function fixture() {
  const domain = await import('../main/ets/chat/device_schedule_tools.ts');
  const entry = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl');
  const events = [], active = new Map(), cancelFailures = new Set();
  let nextId = 1, stored = '[]', failPut = false, putGate = null, capacity = false;
  let calendars = [], creations = 0, createGate = null;
  const prefs = {
    async get(key) { assert.equal(key, 'agent_reminders.v1'); return stored; },
    async put(key, text) {
      assert.equal(key, 'agent_reminders.v1'); events.push(`put:${JSON.parse(text).map(row => row.reminderId).join(',')}`);
      const gate = putGate; putGate = null;
      if (gate) { gate.entered.resolve(); await gate.release.promise; }
      if (failPut) { failPut = false; throw failure(999); }
      stored = text;
    }, async flush() { events.push('flush'); },
  };
  function calendar(id, account = { name: 'amber_reminders', type: 'local' }) {
    const rows = new Map(); let badRead = false, reads = 0;
    return { id, rows, getAccount: () => account, async setConfig() {},
      async getEvents(filter) { reads++; if (badRead) throw failure(999);
        return [...rows.values()].filter(row => !filter?.ids || filter.ids.includes(row.id)); },
      async addEvent(event) { const id = nextId++; rows.set(id, { ...event, id }); return id; },
      async updateEvent(event) { rows.set(event.id, event); }, async deleteEvent(id) { rows.delete(id); },
      failRead: () => { badRead = true; }, reads: () => reads,
    };
  }
  const sdk = {
    '@amber/chat-domain': domain,
    '@kit.PerformanceAnalysisKit': { hilog: { warn() {}, info() {}, debug() {}, error() {} } },
    '@kit.BasicServicesKit': {},
    '@kit.AbilityKit': {
      abilityAccessCtrl: { createAtManager: () => ({ checkAccessTokenSync: () => 0 }), GrantStatus: { PERMISSION_GRANTED: 0 } },
      bundleManager: { BundleFlag: { GET_BUNDLE_INFO_WITH_APPLICATION: 1, GET_BUNDLE_INFO_DEFAULT: 0 },
        getBundleInfoForSelfSync: () => ({ name: 'app.amber.agent', appInfo: { accessTokenId: 1 } }) },
    },
    '@kit.ArkData': { preferences: { async getPreferences(_context, name) { assert.equal(name, 'amber_device_schedule'); return prefs; } } },
    '@kit.NotificationKit': { notificationManager: { isNotificationEnabled: async () => true, SlotType: { SOCIAL_COMMUNICATION: 1 } } },
    '@kit.BackgroundTasksKit': { reminderAgentManager: {
      ReminderType: { REMINDER_TYPE_TIMER: 'timer', REMINDER_TYPE_ALARM: 'alarm', REMINDER_TYPE_CALENDAR: 'calendar' },
      ActionButtonType: { ACTION_BUTTON_TYPE_CLOSE: 0, ACTION_BUTTON_TYPE_SNOOZE: 1 },
      async publishReminder(request) {
        if (capacity) { capacity = false; events.push('capacity'); throw failure(1700002); }
        const id = nextId++; active.set(id, request); events.push(`publish:${id}:${request.title}`); return id;
      },
      async cancelReminder(id) { events.push(`cancel:${id}`); if (cancelFailures.has(id)) throw failure(999);
        if (!active.delete(id)) throw failure(1700003); },
      getAllValidReminders: async () => [...active.keys()].map(reminderId => ({ reminderId })),
    } },
    '@kit.CalendarKit': { calendarManager: {
      CalendarType: { LOCAL: 'local' }, EventType: { NORMAL: 0, IMPORTANT: 1 }, EventFilter: { filterById: ids => ({ ids }) },
      RecurrenceFrequency: { DAILY: 'daily', WEEKLY: 'weekly', MONTHLY: 'monthly', YEARLY: 'yearly' },
      getCalendarManager: () => ({ getAllCalendars: async () => calendars, getCalendar: async () => calendars[0],
        async createCalendar(account) { creations++; if (createGate) await createGate.promise;
          const cal = calendar(nextId++, account); calendars.push(cal); return cal; } }),
    } },
  };
  const loaded = new Map();
  function load(filename) {
    if (sdk[filename]) return sdk[filename];
    if (loaded.has(filename)) return loaded.get(filename);
    const exports = {}; loaded.set(filename, exports);
    const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), { compilerOptions: {
      module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
    }, reportDiagnostics: true });
    assert.equal(code.diagnostics.length, 0, filename);
    vm.runInNewContext(code.outputText, { exports,
      require: dependency => sdk[dependency] || load(path.resolve(path.dirname(filename), dependency)),
      AppStorage: { get: () => ({}) }, Error, Promise, Map, JSON, String, Date, Array, Number,
    }, { filename });
    return exports;
  }
  const first = load(path.join(entry, 'DeviceSchedulePort.ets')).createEntryDeviceSchedulePort();
  const second = new first.constructor(); // Distinct real facade/adapters, same production module graph.
  return { first, second, events, active, cancelFailures, calendar, records: () => JSON.parse(stored),
    failPut: () => { failPut = true; }, gatePut: gate => { putGate = gate; }, failCapacity: () => { capacity = true; },
    setCalendars: value => { calendars = value; }, gateCreation: gate => { createGate = gate; }, creations: () => creations };
}

test('real port instances share the notification/alarm lock and retain both registrations', { timeout: 5000 }, async () => {
  const f = await fixture(), entered = deferred(), release = deferred(); f.gatePut({ entered, release });
  const a = f.first.scheduleAlarm(alarm('A')); await entered.promise;
  const b = f.second.scheduleNotification(notification('B')); await turn();
  assert.deepEqual(f.events, ['publish:1:A', 'put:1']);
  release.resolve(); await Promise.all([a, b]);
  assert.deepEqual(f.records().map(row => [row.family, row.title]), [['alarm', 'A'], ['notification', 'B']]);
});
test('notification registers new before cancelling old and rolls back failed registration', async () => {
  const f = await fixture(); await f.first.scheduleNotification(notification('old')); f.events.length = 0; f.failPut();
  await assert.rejects(f.second.scheduleNotification(notification('failed')), error => error.code === 'platform_error');
  assert.deepEqual(f.events, ['publish:2:failed', 'put:1,2', 'cancel:2']);
  assert.deepEqual([...f.active.keys()], [1]); assert.deepEqual(f.records().map(row => row.title), ['old']);
  f.events.length = 0; await f.second.scheduleNotification(notification('new'));
  assert.deepEqual(f.events, ['publish:3:new', 'put:1,3', 'flush', 'cancel:1', 'put:3', 'flush']);
  assert.deepEqual(f.records().map(row => row.title), ['new']);
});
test('alarm registration failure cancels the published alarm without poisoning the queue', async () => {
  const f = await fixture(); f.failPut();
  await assert.rejects(f.first.scheduleAlarm(alarm('failed')), error => error.code === 'platform_error');
  assert.deepEqual(f.events, ['publish:1:failed', 'put:1', 'cancel:1']);
  assert.equal(f.active.size, 0); assert.deepEqual(f.records(), []);
  await f.second.scheduleAlarm(alarm('next')); assert.equal((await f.first.listAlarms())[0].title, 'next');
});
test('cancel failures retain notification and alarm records for a later retry', async () => {
  const f = await fixture(), n = await f.first.scheduleNotification(notification('n')), a = await f.second.scheduleAlarm(alarm('a'));
  f.cancelFailures.add(Number(n.notificationId)); f.cancelFailures.add(Number(a.alarmId));
  await assert.rejects(f.second.cancelNotification(), error => error.code === 'platform_error');
  await assert.rejects(f.first.cancelAlarm(a.alarmId), error => error.code === 'platform_error');
  assert.equal(f.records().length, 2); assert.equal(f.active.size, 2); f.cancelFailures.clear();
  assert.equal(await f.first.cancelNotification(), true); await f.second.cancelAlarm(a.alarmId); assert.deepEqual(f.records(), []);
});
test('capacity replacement frees and saves old notification before the one publish retry', async () => {
  const f = await fixture(); await f.first.scheduleNotification(notification('old')); f.events.length = 0; f.failCapacity();
  await f.second.scheduleNotification(notification('new'));
  assert.deepEqual(f.events, ['capacity', 'cancel:1', 'put:', 'flush', 'publish:2:new', 'put:2', 'flush']);
});
test('Amber calendar creation is shared across port instances', { timeout: 5000 }, async () => {
  const f = await fixture(), create = deferred(); f.gateCreation(create);
  const a = f.first.createReminder(reminder('one')); await turn();
  const b = f.second.createReminder(reminder('two')); await turn(); assert.equal(f.creations(), 1);
  create.resolve(); const [one, two] = await Promise.all([a, b]); assert.equal(one.listId, two.listId);
});
test('invalid cached Amber calendar is re-resolved once and a failed retry is propagated', async () => {
  const f = await fixture(), old = f.calendar(1); f.setCalendars([old]); await f.first.listReminders(); old.failRead();
  const fresh = f.calendar(2); f.setCalendars([fresh]); assert.equal((await f.second.listReminders()).length, 0);
  assert.equal(old.reads(), 2); assert.equal(fresh.reads(), 1);
  fresh.failRead(); const broken = f.calendar(3); broken.failRead(); f.setCalendars([broken]);
  await assert.rejects(f.first.listReminders(), error => error.code === 'platform_error'); assert.equal(broken.reads(), 1);
});
test('calendar move preserves native recurrence and rolls back new event if source deletion fails', async () => {
  const f = await fixture(), source = f.calendar(1, { name: 'user', type: 'local' }), target = f.calendar(2, { name: 'work', type: 'local' });
  const rule = { recurrenceFrequency: 'weekly', interval: 2, daysOfWeek: [1], excludedDates: [100] };
  const existing = { id: 12, type: 0, title: 'event', startTime: 1000, endTime: 2000, recurrenceRule: rule };
  source.rows.set(12, existing); f.setCalendars([source, target]); const order = [];
  target.addEvent = async event => { order.push('add'); assert.equal(event.recurrenceRule, rule); target.rows.set(20, event); return 20; };
  source.deleteEvent = async () => { order.push('delete old'); throw failure(999); };
  target.deleteEvent = async id => { order.push('rollback new'); target.rows.delete(id); };
  await assert.rejects(f.first.updateCalendarEvent('1:12', { title: 'changed', startMs: 1000, endMs: 2000,
    allDay: false, location: '', notes: '', calendarId: '2', recurrence: null, recurrenceSpecified: false }), error => error.code === 'platform_error');
  assert.deepEqual(order, ['add', 'delete old', 'rollback new']); assert.equal(source.rows.get(12), existing); assert.equal(target.rows.size, 0);
});
