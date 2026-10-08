const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const filename = path.resolve(__dirname, '../../../entry/src/main/ets/components/MemoryPresentation.ets');
const exportsObject = {};
const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
}).outputText;
vm.runInNewContext(code, { exports: exportsObject, Date, Math, String, Number }, { filename });
const view = exportsObject;
const record = (id, overrides = {}) => ({ id, content: '项目约定', scope: 'core', kind: 'note', archived: false,
  expiresAt: null, createdAt: id, updatedAt: id, ...overrides });
const now = 1000000;

test('active count excludes archived, expired and derived topics', () => {
  const records = [record(1), record(2, { archived: true }), record(3, { expiresAt: now }), record(4, { kind: 'topic' }), record(5, { invalidatedAt: now - 1 })];
  assert.equal(view.countActiveMemories(records, now), 1);
});
test('archive filter retains recoverable expired records and excludes topics', () => {
  const records = [record(1), record(2, { archived: true }), record(3, { expiresAt: now - 1 }),
    record(4, { kind: 'topic', archived: true })];
  assert.deepEqual(Array.from(view.filterMemoryRecords(records, 'archived', '', now), row => row.id), [3, 2]);
});
test('search matches visible Chinese scope and kind labels; whitespace is an empty query', () => {
  const records = [record(1), record(2, { scope: 'short_term', kind: 'user' })];
  assert.deepEqual(Array.from(view.filterMemoryRecords(records, 'active', '短期', now), row => row.id), [2]);
  assert.deepEqual(Array.from(view.filterMemoryRecords(records, 'all', ' 偏好 ', now), row => row.id), [2]);
  assert.equal(view.filterMemoryRecords(records, 'all', '  ', now).length, 2);
});
test('event labels distinguish restore from saves and invalidation from updates', () => {
  assert.equal(view.memoryEventLabel('memory_restored'), '恢复');
  assert.equal(view.memoryEventLabel('memory_invalidated'), '作废');
  assert.equal(view.memoryEventLabel('memory_updated'), '更新');
});
test('relative dates handle minute/day boundaries and future timestamps', () => {
  assert.equal(view.relativeMemoryDate(now - 30000, now), '刚刚');
  assert.equal(view.relativeMemoryDate(now - 120000, now), '2 分钟前');
  assert.equal(view.relativeMemoryDate(now - 2 * 86400000, now), '2 天前');
  assert.equal(view.relativeMemoryDate(now + 5000, now), '刚刚');
});
