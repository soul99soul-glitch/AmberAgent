const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');

function fixture() {
  const held = new Set();
  const closed = [];
  let lockError = null;
  const fileIo = {
    OpenMode: { READ_WRITE: 2, CREATE: 64 },
    mkdir: async () => {},
    open: async name => ({
      name,
      tryLock(exclusive) {
        assert.equal(exclusive, true);
        if (lockError !== null) throw lockError;
        if (held.has(name)) throw { code: 13900034 };
        held.add(name);
      },
      unlock() { assert.equal(held.delete(name), true); },
    }),
    close: async file => { closed.push(file.name); },
  };
  const source = fs.readFileSync(path.join(__dirname, '../main/ets/platform_impl/FileStore.ets'), 'utf8');
  const code = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
  } }).outputText;
  const exports = {};
  new Function('require', 'exports', 'AppStorage', code)(name => {
    if (name === '@kit.CoreFileKit') return { fileIo };
    if (name === '@kit.ArkTS') return { util: {} };
    if (name === '@amber/deepread-domain') return { joinPath: (...parts) => parts.join('/').replace(/\/+/g, '/') };
    throw new Error(`Unexpected import: ${name}`);
  }, exports, { get: () => ({ filesDir: '/sandbox' }) });
  return { store: exports.createFileStore(), held, closed, setLockError: value => { lockError = value; } };
}

test('real FileStore nonblocking lock returns on contention without queuing the operation', async () => {
  const { store, held, closed } = fixture();
  let entered;
  const started = new Promise(resolve => { entered = resolve; });
  let release;
  const pending = new Promise(resolve => { release = resolve; });
  const first = store.tryWithExclusiveLock('novel-owner.lock', async () => {
    entered();
    await pending;
    return 'first';
  });
  await started;
  let duplicateRan = false;
  assert.equal(await store.tryWithExclusiveLock('novel-owner.lock', async () => { duplicateRan = true; }), null);
  assert.equal(duplicateRan, false);
  assert.equal(held.size, 1);
  assert.equal(closed.length, 1, 'the unsuccessful contender closes its own descriptor');
  release();
  assert.equal(await first, 'first');
  assert.equal(held.size, 0);
  assert.equal(closed.length, 2);
  assert.equal(await store.tryWithExclusiveLock('novel-owner.lock', async () => 'next'), 'next');
});

test('real FileStore releases the owner after operation errors and only treats documented contention as busy', async () => {
  const { store, held, closed, setLockError } = fixture();
  const operationError = new Error('provider failed');
  await assert.rejects(store.tryWithExclusiveLock('owner.lock', async () => { throw operationError; }),
    error => error === operationError);
  assert.equal(held.size, 0);
  assert.equal(closed.length, 1);
  assert.equal(await store.tryWithExclusiveLock('owner.lock', async () => 'recovered'), 'recovered');
  const ioError = { code: 13900005, message: 'I/O error' };
  setLockError(ioError);
  await assert.rejects(store.tryWithExclusiveLock('owner.lock', async () => 'must not run'), error => error === ioError);
  assert.equal(closed.length, 3);
  assert.equal(held.size, 0);
});
