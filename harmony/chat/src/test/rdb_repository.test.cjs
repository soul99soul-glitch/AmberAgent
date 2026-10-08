// Real Entry RDB adapters; query results and SDK initialization are controlled host ports.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { spawnSync } = require('node:child_process');
const ts = require('typescript');
const entry = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl');

function loadAdapter(filename, relationalStore, domain = {}) {
  const exports = {}, values = new Map([['abilityContext', {}]]);
  const imports = {
    '@kit.ArkData': { relationalStore },
    '@kit.PerformanceAnalysisKit': { hilog: { info() {}, warn() {} } },
    '@amber/chat-domain': domain,
    './ManagedFileStore.ets': { enqueueConversationFileMutation: operation => operation() },
  };
  const source = ts.transpileModule(fs.readFileSync(path.join(entry, filename), 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  vm.runInNewContext(source, { exports, require: name => {
    if (!(name in imports)) throw new Error('Unexpected adapter import: ' + name);
    return imports[name];
  }, AppStorage: { get: key => values.get(key), setOrCreate: (key, value) => values.set(key, value) },
  Promise, Error, Date, Map, Set, Math, Number, JSON, String });
  return { exports, values };
}

class Predicates {
  constructor(table) { this.table = table; }
  equalTo(key, value) { this.equal = [key, value]; return this; }
  like(key, value) { this.likeValue = value; return this; }
  in(key, values) { this.ids = values; return this; }
  orderByDesc() { return this; }
  orderByAsc() { return this; }
}
function resultSet(rows, closed) {
  let index = -1;
  return { goToNextRow: () => ++index < rows.length, getString: column => rows[index][column],
    getLong: column => rows[index][column], getColumnIndex: () => 0, close: () => closed.push(true) };
}
async function searchFixture(nodes, conversations) {
  const domain = await import('../main/ets/index.ts');
  const closed = [], queries = [];
  const store = { query: async predicates => {
    queries.push(predicates);
    if (predicates.table === 'message_node') {
      const term = predicates.likeValue.slice(1, -1).toLowerCase();
      return resultSet(nodes.filter(row => row[1].toLowerCase().includes(term)), closed);
    }
    return resultSet(conversations.filter(row => predicates.ids.includes(row[0]))
      .sort((a, b) => b[4] - a[4]), closed);
  } };
  const { exports } = loadAdapter('ChatRdbRepository.ets', { RdbPredicates: Predicates }, domain);
  const repository = exports.createChatRdbRepository();
  repository.store = store;
  return { repository, closed, queries, domain };
}
const conversationRow = (id, updated = 2) => [id, 'assistant', id + ' title', 1, updated, '[]', 0, 0];
const messageJson = text => JSON.stringify([{ id: 'm', role: 'assistant', parts: [{ type: 'text', text }], createdAt: '2026-01-01T00:00:00.000Z' }]);

if (!process.argv.includes('--probe')) test('message search keeps an older real body hit when latest JSON discriminator is a coarse false positive', async () => {
  const fixture = await searchFixture([
    ['c', messageJson('text needle present'), 0], ['c', messageJson('new answer'), 1],
  ], [conversationRow('c')]);
  const hits = await fixture.repository.searchMessages('text', 20);
  assert.equal(hits.length, 1);
  assert.equal(hits[0].conversationId, 'c');
  assert.match(hits[0].snippet, /text needle present/);
  assert.equal(fixture.queries[0].likeValue, '%text%');
  assert.equal(fixture.closed.length, 2);
});

if (!process.argv.includes('--probe')) test('message search selects latest real hit regardless of row order and preserves conversation recency/limit', async () => {
  const fixture = await searchFixture([
    ['old', messageJson('needle newest'), 9], ['old', messageJson('needle older'), 2],
    ['old', JSON.stringify([{ parts: [{ type: 'text', text: 'unrelated', metadata: { needle: true } }] }]), 10],
    ['recent', messageJson('needle recent'), 1],
  ], [conversationRow('old', 2), conversationRow('recent', 4)]);
  const all = await fixture.repository.searchMessages('needle', 20);
  assert.deepEqual(Array.from(all, hit => hit.conversationId), ['recent', 'old']);
  assert.match(all[1].snippet, /needle newest/);
  const limited = await fixture.repository.searchMessages('needle', 1);
  assert.deepEqual(Array.from(limited, hit => hit.conversationId), ['recent']);
});

if (!process.argv.includes('--probe')) test('message search trims query and returns no hits for empty query or nonpositive limit', async () => {
  const fixture = await searchFixture([['c', messageJson('needle'), 0]], [conversationRow('c')]);
  assert.equal((await fixture.repository.searchMessages(' needle ', 2)).length, 1);
  for (const [term, limit] of [['', 2], ['  ', 2], ['needle', 0], ['needle', -1]]) {
    assert.equal((await fixture.repository.searchMessages(term, limit)).length, 0);
  }
});

async function initializationProbe(filename) {
  let attempts = 0;
  const closed = [];
  const store = { executeSql: async () => {}, querySql: async () => resultSet([], closed),
    query: async () => resultSet([], closed) };
  const sdk = { RdbPredicates: Predicates, SecurityLevel: { S1: 1 }, getRdbStore: async () => {
    attempts++; if (attempts === 1) throw new Error('rdb-init-failure'); return store;
  } };
  const domain = filename.startsWith('Chat') ? await import('../main/ets/index.ts') : {};
  const { exports, values } = loadAdapter(filename, sdk, domain);
  const repository = filename.startsWith('Chat') ? exports.createChatRdbRepository() : exports.createRdbRepository();
  const unhandled = [];
  const handler = error => unhandled.push(error.message);
  process.on('unhandledRejection', handler);
  let caught = '';
  try { if (filename.startsWith('Chat')) await repository.getById('x'); else await repository.get('x'); }
  catch (error) { caught = error.message; }
  await new Promise(resolve => setImmediate(resolve));
  const degradedAfterFailure = values.get('chatStorageDegraded');
  const result = filename.startsWith('Chat') ? await repository.getById('x') : await repository.get('x');
  await new Promise(resolve => setImmediate(resolve));
  process.off('unhandledRejection', handler);
  return { caught, attempts, result, unhandled, degradedAfterFailure, degradedAfterRetry: values.get('chatStorageDegraded') };
}

if (!process.argv.includes('--probe')) for (const filename of ['RdbRepository.ets', 'ChatRdbRepository.ets']) {
  test(filename + ' handles initialization failure and clears pending initialization for retry', () => {
    // A child observes the actual process-level rejection without node:test treating
    // the intentional red reproduction as an unrelated post-test asynchronous error.
    const child = spawnSync(process.execPath, ['--import', path.resolve(__dirname, '../../../chat/node_modules/tsx/dist/loader.mjs'), __filename, '--probe', filename], {
      encoding: 'utf8', env: process.env,
    });
    assert.equal(child.status, 0, child.stderr);
    const result = JSON.parse(child.stdout.trim());
    assert.deepEqual(result.unhandled, []);
    assert.equal(result.attempts, 2);
    assert.equal(result.result, null);
    if (filename.startsWith('Chat')) {
      assert.equal(result.caught, '');
      assert.equal(result.degradedAfterFailure, true);
      assert.equal(result.degradedAfterRetry, false);
    } else assert.equal(result.caught, 'rdb-init-failure');
  });
}
if (process.argv.includes('--probe')) {
  // Hide the registered tests in the child; only report probe evidence.
  initializationProbe(process.argv.at(-1)).then(result => {
    process.stdout.write(JSON.stringify(result));
    process.exit(0);
  }, error => { process.stderr.write(error.stack); process.exit(1); });
}
