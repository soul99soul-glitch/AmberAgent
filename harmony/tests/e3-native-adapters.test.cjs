const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../chat/node_modules/typescript');

const directory = path.resolve(__dirname, '../entry/src/main/ets/platform_impl');
const deferred = () => {
  let resolve;
  const promise = new Promise((complete) => { resolve = complete; });
  return { promise, resolve };
};
const controller = (abortOnRegistration = false) => {
  const listeners = new Set();
  const signal = {
    aborted: false,
    addEventListener: (_event, listener) => {
      listeners.add(listener);
      if (abortOnRegistration) { signal.aborted = true; listener(); }
    },
    removeEventListener: (_event, listener) => listeners.delete(listener),
  };
  return { signal, listeners, abort() { signal.aborted = true; for (const listener of listeners) listener(); } };
};
const load = (name, native, fileIo = {}, storage = {}) => {
  const source = fs.readFileSync(path.join(directory, name), 'utf8');
  const output = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
  } }).outputText;
  const exports = {};
  vm.runInNewContext(output, { exports, Uint8Array, ArrayBuffer, Promise, Error,
    require: (module) => {
      if (module === 'libamber_native.so') return { default: native };
      if (module === '@kit.CoreFileKit') return { fileIo };
      throw new Error(`Unexpected runtime import ${module}`);
    },
    AppStorage: { get: (key) => storage[key] },
  }, { filename: name });
  return exports;
};
const resources = () => {
  const data = {
    'python/python314.zip': new Uint8Array([9, 0, 255, 17, 4, 9]).subarray(1, 5),
    'python/amber_python.py': new Uint8Array([1, 2, 3, 4, 5]),
  };
  const files = new Map();
  const open = new Map();
  let next = 1;
  let directories = false;
  const fileIo = {
    OpenMode: { WRITE_ONLY: 1, CREATE: 2, TRUNC: 4 },
    access: async () => directories,
    mkdir: async () => { directories = true; },
    open: async (name) => { const fd = next++; files.set(name, []); open.set(fd, name); return { fd }; },
    write: async (fd, buffer) => {
      const bytes = new Uint8Array(buffer);
      const count = Math.min(2, bytes.length);
      files.get(open.get(fd)).push(...bytes.subarray(0, count));
      return count;
    },
    close: async (file) => { open.delete(file.fd); },
    renameSync: (from, to) => {
      assert.ok(![...open.values()].includes(from), 'close before publishing resource');
      files.set(to, files.get(from)); files.delete(from);
    },
  };
  const storage = { abilityContext: { filesDir: '/private/application',
    resourceManager: { getRawFileContentSync: (name) => data[name] } } };
  return { fileIo, storage, files, open, data };
};
const completed = { status: 'completed', exitCode: 0, stdout: '中文😀', stderr: '', errorCode: null };
const pythonOptions = { source: 'print(stdin)', stdin: '中文😀', timeoutMs: 15000 };

test('Python deploys exact binary resources through short writes and always supplies the fixed private root', async () => {
  const fixture = resources();
  let received;
  const native = { pythonVersion: () => '3.14.7', pythonExecute: async (_id, options) => { received = options; return completed; } };
  const port = await load('NativePythonTransport.ets', native, fixture.fileIo, fixture.storage).createNativePythonTransport();
  assert.equal(port.version(), '3.14.7');
  for (const name of ['python314.zip', 'amber_python.py']) {
    assert.deepEqual(fixture.files.get(`/private/application/amberagent/python/${name}`), [...fixture.data[`python/${name}`]]);
  }
  assert.equal(fixture.open.size, 0);
  assert.strictEqual(await port.execute('run', { ...pythonOptions, resourceRoot: '/user-selected' }), completed);
  assert.equal(received.resourceRoot, '/private/application/amberagent/python');
  assert.equal(received.stdin, '中文😀');
});

test('Python registration cancellation prevents a native start', async () => {
  const fixture = resources();
  let starts = 0;
  const native = { pythonExecute: async () => { starts++; return completed; } };
  const port = await load('NativePythonTransport.ets', native, fixture.fileIo, fixture.storage).createNativePythonTransport();
  const cancel = controller(true);
  const result = await port.execute('run', pythonOptions, cancel.signal);
  assert.equal(result.status, 'cancelled');
  assert.equal(starts, 0);
  assert.equal(cancel.listeners.size, 0);
});

test('Python abort calls native cancel once and remains pending until actual execution settles', async () => {
  const fixture = resources();
  const pending = deferred();
  const cancellations = [];
  const native = { pythonExecute: () => pending.promise, pythonCancel: (id) => cancellations.push(id) };
  const port = await load('NativePythonTransport.ets', native, fixture.fileIo, fixture.storage).createNativePythonTransport();
  const cancel = controller();
  let settled = false;
  const executing = port.execute('run', pythonOptions, cancel.signal).then((result) => { settled = true; return result; });
  cancel.abort(); cancel.abort();
  await Promise.resolve();
  assert.deepEqual(cancellations, ['run']);
  assert.equal(settled, false);
  const result = { status: 'cancelled', exitCode: null, stdout: '', stderr: '', errorCode: 'cancelled' };
  pending.resolve(result);
  assert.strictEqual(await executing, result);
  assert.equal(cancel.listeners.size, 0);
});

test('Python background guard does not start the interpreter', async () => {
  const fixture = resources();
  fixture.storage.appBackgrounded = true;
  const port = await load('NativePythonTransport.ets', { pythonExecute() { throw new Error('must not start'); } },
    fixture.fileIo, fixture.storage).createNativePythonTransport();
  assert.equal((await port.execute('run', pythonOptions)).status, 'cancelled');
});

const moshOptions = { peerAddress: '127.0.0.1', port: 60001, sessionKey: 'fixture-session-key',
  columns: 43, rows: 24, connectTimeoutMs: 10000 };

test('Mosh abort after start discards a late handle and waits for close', async () => {
  const start = deferred();
  const close = deferred();
  const closeStarted = deferred();
  const calls = [];
  const native = { moshStart: () => start.promise, moshCancel: (id) => calls.push(['cancel', id]),
    moshClose: (handle, reason) => {
      calls.push(['close', handle.id, reason]); closeStarted.resolve(); return close.promise;
    } };
  const port = load('NativeMoshTransport.ets', native).createNativeMoshTransport();
  const cancel = controller();
  const executing = port.start('request', moshOptions, cancel.signal);
  cancel.abort();
  start.resolve({ id: 'late-handle', kind: 'mosh' });
  await closeStarted.promise;
  assert.deepEqual(calls, [['cancel', 'request'], ['close', 'late-handle', 'cancelled']]);
  close.resolve({ state: 'cancelled', bytes: new Uint8Array(), errorCode: null, lastHeardMs: null });
  await assert.rejects(executing, (error) => error.code === 'cancelled');
  assert.equal(cancel.listeners.size, 0);
});

test('Mosh read/write/resize/close preserve bytes and background starts are rejected', async () => {
  const calls = [];
  const packet = { state: 'reconnecting', bytes: new Uint8Array([0, 255, 27]), errorCode: null, lastHeardMs: 4000 };
  const native = { moshRead: (_handle, size) => { calls.push(size); return packet; },
    moshWrite: async (_handle, bytes) => calls.push([...bytes]),
    moshResize: async (_handle, cols, rows) => calls.push([cols, rows]),
    moshClose: async (_handle, reason) => { calls.push(reason); return packet; } };
  const storage = {};
  const port = load('NativeMoshTransport.ets', native, {}, storage).createNativeMoshTransport();
  const handle = { id: 'handle', kind: 'mosh' };
  assert.strictEqual(await port.read(handle, 100), packet);
  await port.write(handle, new Uint8Array([13, 0, 255]));
  await port.resize(handle, 43, 11);
  assert.strictEqual(await port.close(handle, 'release'), packet);
  assert.deepEqual(calls, [100, [13, 0, 255], [43, 11], 'release']);
  storage.appBackgrounded = true;
  await assert.rejects(port.start('request', moshOptions), (error) => error.code === 'disconnected');
});
