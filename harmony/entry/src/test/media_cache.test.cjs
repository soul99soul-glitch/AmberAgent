// Run with: node --test harmony/entry/src/test/media_cache.test.cjs
// Execute MediaCache itself; only the platform HTTP/filesystem ports are simulated.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../../../chat/node_modules/typescript');

const MiB = 1024 * 1024;
const filename = path.resolve(__dirname, '../main/ets/platform_impl/MediaCache.ets');
const source = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
}).outputText;

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

const flush = () => new Promise(resolve => setImmediate(resolve));

function fixture({ imageBytes = 6 * MiB, streamBytes = 0, holdFirstWrite = false } = {}) {
  const files = new Map();
  const handles = new Map();
  const events = [];
  const requestOptions = [];
  const requestStarted = deferred();
  const writeStarted = deferred();
  const releaseWrite = deferred();
  let now = Date.UTC(2026, 9, 18);
  let nextFd = 1;
  let writes = 0;
  let stream;
  class Clock extends Date { static now() { return now; } }

  const fileIo = {
    OpenMode: { READ_WRITE: 1, CREATE: 2, TRUNC: 4 },
    mkdir: async () => {},
    listFile: async root => [...files.keys()].map(name => name.slice(root.length + 1)),
    access: async name => files.has(name),
    stat: async name => {
      if (!files.has(name)) throw new Error('ENOENT');
      return { ...files.get(name) };
    },
    utimes: (name, mtime) => { files.get(name).mtime = mtime / 1000; },
    open: async name => {
      const file = { fd: nextFd++ };
      files.set(name, { size: 0, mtime: now / 1000 });
      handles.set(file.fd, name);
      return file;
    },
    write: async (fd, data) => {
      const name = handles.get(fd);
      events.push({ type: 'write', fd, closed: name === undefined });
      if (name === undefined) throw new Error('EBADF');
      files.get(name).size += data.byteLength;
      writes++;
      if (holdFirstWrite && writes === 1) {
        writeStarted.resolve();
        await releaseWrite.promise;
      }
      events.push({ type: 'writeDone', fd });
      return data.byteLength;
    },
    close: async file => {
      events.push({ type: 'close', fd: file.fd });
      handles.delete(file.fd);
    },
    unlink: async name => { files.delete(name); },
    rename: async (from, to) => {
      files.set(to, files.get(from));
      files.delete(from);
    },
  };
  const http = {
    RequestMethod: { GET: 'GET' },
    HttpDataType: { ARRAY_BUFFER: 'buffer' },
    createHttp: () => {
      const handlers = {};
      const response = deferred();
      return {
        on: (name, callback) => { handlers[name] = callback; },
        destroy: () => {},
        request: async (_url, options) => {
          requestOptions.push(options);
          handlers.headersReceive({ 'content-type': 'image/png', 'content-length': String(imageBytes) });
          // NetworkKit API 12 defaults maxLimit to 5 MiB; the explicit option is available since API 11.
          if (imageBytes > (options.maxLimit ?? 5 * MiB)) throw new Error('2300023: maxLimit');
          return { responseCode: 200, result: new ArrayBuffer(imageBytes), header: { 'content-type': 'image/png' } };
        },
        requestInStream: () => {
          stream = { handlers, response };
          requestStarted.resolve();
          if (streamBytes > 0) {
            handlers.headersReceive({ 'content-type': 'video/mp4', 'content-length': String(streamBytes) });
            const chunk = new ArrayBuffer(MiB);
            for (let received = 0; received < streamBytes; received += MiB) handlers.dataReceive(chunk);
            handlers.dataEnd();
            response.resolve(200);
          }
          return response.promise;
        },
      };
    },
  };
  const imports = {
    '@kit.CoreFileKit': { fileIo },
    '@kit.NetworkKit': { http },
    '@kit.PerformanceAnalysisKit': { hilog: { warn() {} } },
    './EntryLocalToolPorts.ets': { sha256HexUtf8: value => crypto.createHash('sha256').update(value).digest('hex') },
  };
  const exports = {};
  vm.runInNewContext(source, {
    exports, require: name => {
      if (Object.hasOwn(imports, name)) return imports[name];
      throw new Error('Unexpected dependency ' + name);
    },
    AppStorage: { get: () => ({ filesDir: '/sandbox' }) },
    Date: Clock, Error, Promise, ArrayBuffer,
  }, { filename });
  return {
    cache: exports.getMediaCache(), files, events, requestOptions,
    requestStarted, writeStarted, releaseWrite,
    stream: () => stream,
    advance: ms => { now += ms; },
    totalBytes: () => [...files.values()].reduce((total, file) => total + file.size, 0),
  };
}

for (const failure of ['HTTP 404', 'request rejection']) {
  test(`${failure} drains an active stream write before closing and never writes to a closed fd`, async () => {
    const f = fixture({ holdFirstWrite: true });
    const url = 'https://media.test/failing.mp4';
    const running = f.cache.get(url, 'video');
    await f.requestStarted.promise;
    const { handlers, response } = f.stream();
    handlers.dataReceive(new ArrayBuffer(4));
    await f.writeStarted.promise;
    handlers.dataReceive(new ArrayBuffer(4));
    if (failure === 'HTTP 404') {
      handlers.dataEnd();
      response.resolve(404);
    } else {
      response.reject(new Error('connection lost'));
    }
    await flush();
    const closedWhileWriting = f.events.some(event => event.type === 'close');
    f.releaseWrite.resolve();
    assert.equal(await running, url);
    await flush();
    assert.equal(closedWhileWriting, false, 'the pending write must finish before closing its fd');
    assert.equal(f.events.some(event => event.type === 'write' && event.closed), false);
    assert.equal(f.events.filter(event => event.type === 'close').length, 1);
    assert.equal(f.files.size, 0, 'failed downloads must remove their temporary files');
  });
}

test('a successful stream writes every chunk before publishing the cached file', async () => {
  const f = fixture({ streamBytes: 3 * MiB });
  const result = await f.cache.get('https://media.test/success.mp4', 'video');
  assert.match(result, /^file:\/\//);
  assert.equal(f.files.get(result.slice('file://'.length)).size, 3 * MiB);
  assert.equal(f.events.filter(event => event.type === 'writeDone').length, 3);
  assert.equal(f.events.at(-1).type, 'close');
});

test('a 6 MiB image is cached with an explicit 25 MiB NetworkKit response limit', async () => {
  const f = fixture();
  const url = 'https://media.test/large.png';
  const result = await f.cache.get(url, 'image');
  assert.match(result, /^file:\/\//);
  assert.equal(f.requestOptions[0].maxLimit, 25 * MiB);
  assert.equal(f.files.get(result.slice('file://'.length)).size, 6 * MiB);
  assert.equal(await f.cache.get(url, 'image'), result);
  assert.equal(f.requestOptions.length, 1, 'the next read must reuse the cached image');
});

test('downloads immediately enforce the 256 MiB budget during the warm-up prune interval', async () => {
  const f = fixture({ streamBytes: 100 * MiB });
  await f.cache.warmUp();
  let latest;
  for (let index = 0; index < 3; index++) {
    f.advance(1000);
    latest = await f.cache.get(`https://media.test/${index}.mp4`, 'video');
  }
  await flush();
  assert.ok(f.totalBytes() <= 256 * MiB, 'three 100 MiB downloads must not leave 300 MiB cached');
  assert.equal(f.files.get(latest.slice('file://'.length)).size, 100 * MiB);
});
