// Exercise the production collection owner; animation, timers and persistence are controlled ports.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../../../chat/node_modules/typescript');
const tick = async () => { for (let i = 0; i < 30; i++) await Promise.resolve(); };
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };

const fixture = () => {
  const filename = path.resolve(__dirname, '../main/ets/platform_impl/home/HomeConversationCollection.ets');
  const output = ts.transpileModule(fs.readFileSync(filename, 'utf8').replace('@Observed', ''), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const exports = {}, timers = new Map(), storage = new Map(), events = [], toasts = [];
  let timerId = 0, busy = false;
  let items = [{ id: 'one', title: 'One', updateAt: '1' }, { id: 'two', title: 'Two', updateAt: '2' }];
  const repository = { list: async () => items, getById: async id => ({ ...items.find(item => item.id === id), messages: [] }),
    getConversationNodePage: async id => ({ totalNodeCount: id === 'one' ? 3 : 5 }),
    delete: async id => { events.push(['delete', id]); items = items.filter(item => item.id !== id); } };
  const service = { subscribeRunning: () => () => {}, isBusy: () => busy,
    forgetDeletedConversation: id => events.push(['forget', id]) };
  vm.runInNewContext(output, { exports, Promise, Math, Object,
    setTimeout: callback => { const id = ++timerId; timers.set(id, callback); return id; },
    clearTimeout: id => timers.delete(id),
    animateTo: (_options, apply) => apply(), Curve: { EaseOut: 'out', EaseIn: 'in' },
    AppStorage: { get: key => storage.get(key), setOrCreate: (key, value) => storage.set(key, value) },
    require: name => {
      if (name === '@kit.ArkUI') return { promptAction: { showToast: options => toasts.push(options.message) } };
      if (name === '@amber/chat-domain') return { patchConversation: (conversation, patch) => ({ ...conversation, ...patch }) };
      if (name.endsWith('ConversationRunGuard.ets')) return { beginConversationRestore: ids => {
        events.push(['lock', ids[0]]); return () => events.push(['release', ids[0]]);
      } };
      assert.ok(name.endsWith('tokens.ets'));
      return { MOTION_ENTER: 200 };
    },
  }, { filename });
  const ports = { getRepository: () => repository, getRunService: () => service, getKv: () => ({}),
    cleanupDeletedConversation: async conversation => events.push(['cleanup', conversation.id]) };
  const create = () => new exports.HomeConversationCollection(ports);
  return { create, repository, events, toasts, timers, storage,
    setBusy: value => { busy = value; }, setItems: value => { items = value; } };
};

test('Home collection shares cache and pending deletion across pane instances, and undo leaves persistence intact', async () => {
  const f = fixture(), home = f.create();
  assert.equal(f.timers.size, 0, 'Construction does not start asynchronous resources');
  home.activate(false, () => {}); home.refresh(); await tick();
  const left = f.create();
  assert.equal(left.activate(true, () => {}), true);
  assert.deepEqual(Array.from(left.items, item => item.id), ['one', 'two']);
  assert.equal(left.messageCount('one'), 3);
  home.onDelete(home.items[0]); await tick();
  left.refresh(); await tick();
  assert.deepEqual(Array.from(left.items, item => item.id), ['two'], 'Another pane excludes the shared pending identity');
  assert.equal(home.snackVisible, true);
  home.undoDelete(); await tick(); left.refresh(); await tick();
  assert.deepEqual(Array.from(left.items, item => item.id), ['one', 'two']);
  assert.equal(home.snackVisible, false);
  assert.equal(f.timers.size, 0);
  assert.deepEqual(f.events, [], 'Undo must not delete, forget or clean the conversation');
  home.dispose(); left.dispose();
});

test('Home collection disposal commits its pending deletion in restore-lock order and notifies retained panes', async () => {
  const f = fixture(), home = f.create(), deleted = [];
  home.activate(false, id => deleted.push(id)); home.refresh(); await tick();
  home.onDelete(home.items[0]); await tick();
  assert.deepEqual(deleted, ['one']);
  home.dispose(); await tick();
  assert.deepEqual(f.events, [['lock', 'one'], ['delete', 'one'], ['forget', 'one'], ['cleanup', 'one'], ['release', 'one']]);
  assert.equal(f.timers.size, 0);
  assert.equal(f.storage.get('homeListVersion'), 1);
  const replacement = f.create(); replacement.activate(true, () => {}); replacement.refresh(); await tick();
  assert.deepEqual(Array.from(replacement.items, item => item.id), ['two']);
  replacement.dispose();
});

test('Home collection refuses to delete a busy conversation and releases the shared pending exclusion', async () => {
  const f = fixture(), home = f.create();
  home.activate(false, () => {}); home.refresh(); await tick();
  home.onDelete(home.items[0]); await tick(); f.setBusy(true);
  const timer = [...f.timers.values()][0]; timer(); await tick();
  assert.deepEqual(f.events, []);
  assert.match(f.toasts[0], /仍在运行/);
  assert.equal(f.storage.get('homeListVersion'), 1);
  home.refresh(); await tick();
  assert.deepEqual(Array.from(home.items, item => item.id), ['one', 'two']);
  home.dispose();
});

test('Home collection rejects late list results and reuses unchanged count stamps with concurrency capped at eight', async () => {
  const f = fixture(), home = f.create(); home.activate(false, () => {});
  const slow = deferred(); let listCalls = 0;
  f.repository.list = () => ++listCalls === 1 ? slow.promise : Promise.resolve([{ id: 'current', updateAt: 'now' }]);
  home.refresh(); home.refresh(); await tick();
  slow.resolve([{ id: 'stale', updateAt: 'old' }]); await tick();
  assert.deepEqual(Array.from(home.items, item => item.id), ['current']);
  const items = Array.from({ length: 20 }, (_, index) => ({ id: `count-${index}`, updateAt: 'same' }));
  f.repository.list = async () => items;
  let active = 0, maxActive = 0, reads = 0;
  f.repository.getConversationNodePage = async () => { reads++; active++; maxActive = Math.max(maxActive, active);
    await Promise.resolve(); active--; return { totalNodeCount: 7 }; };
  home.refresh(); await tick();
  assert.equal(reads, 20); assert.equal(maxActive, 8);
  assert.equal(home.messageCount('count-19'), 7);
  home.refresh(); await tick();
  assert.equal(reads, 20, 'Unchanged updateAt stamps must skip full count reads');
  home.dispose();
});
