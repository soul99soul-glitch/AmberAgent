// Execute the image preparation adapter with only native HTTP/filesystem ports replaced.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../../../chat/node_modules/typescript');

const MiB = 1024 * 1024;
const filename = path.resolve(__dirname, '../main/ets/platform_impl/ImageSourceSupport.ets');
const source = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
}).outputText;

function fixture({ size = 6 * MiB, enforceHttpLimit = true, writeError = false } = {}) {
  const files = new Map();
  const handles = new Map();
  const requests = [];
  let destroyed = 0;
  let closed = 0;
  const fileIo = {
    OpenMode: { READ_ONLY: 1, READ_WRITE: 2, CREATE: 4, TRUNC: 8 },
    openSync: name => { files.set(name, new Uint8Array()); handles.set(1, name); return { fd: 1 }; },
    writeSync: (fd, bytes) => {
      if (writeError) throw new Error('disk full');
      files.set(handles.get(fd), new Uint8Array(bytes)); return bytes.byteLength;
    },
    closeSync: () => { closed++; handles.clear(); },
    unlinkSync: name => files.delete(name),
  };
  const imports = {
    '@kit.CoreFileKit': { fileIo },
    '@kit.ArkTS': { util: { Base64Helper: class {
      decodeSync(value) { return new Uint8Array(Buffer.from(value, 'base64')); }
    } } },
    '@amber/chat-domain': { newId: () => 'prepared' },
    '@kit.NetworkKit': { http: {
      RequestMethod: { GET: 'GET' }, HttpDataType: { ARRAY_BUFFER: 1 },
      createHttp: () => ({
        destroy: () => { destroyed++; },
        request: async (_url, options) => {
          requests.push(options);
          if (enforceHttpLimit && size > (options.maxLimit ?? 5 * MiB)) throw new Error('2300023: maxLimit');
          const bytes = new Uint8Array(size);
          bytes.set([0x89, 0x50, 0x4e, 0x47]);
          return { responseCode: 200, result: bytes.buffer };
        },
      }),
    } },
  };
  const exports = {};
  vm.runInNewContext(source, {
    exports, require: name => {
      if (!Object.hasOwn(imports, name)) throw new Error('Unexpected dependency: ' + name);
      return imports[name];
    },
    AppStorage: { get: () => ({ cacheDir: '/cache' }) }, ArrayBuffer, Uint8Array, Error,
  }, { filename });
  return { ...exports, files, requests, destroyed: () => destroyed, closed: () => closed };
}

test('save/share prepares a 6 MiB image within the existing 25 MiB image allowance', async () => {
  const f = fixture();
  const prepared = await f.prepareImageSource('https://images.test/generated');
  assert.equal(f.requests[0].maxLimit, 25 * MiB);
  assert.equal(prepared.extension, 'png');
  assert.equal(prepared.temporary, true);
  assert.equal(f.files.get(prepared.path).length, 6 * MiB);
  assert.equal(f.destroyed(), 1);
  assert.equal(f.closed(), 1);
  f.releaseImageSource(prepared);
  assert.equal(f.files.size, 0);
});

test('an oversized HTTP response is rejected before creating a temporary image', async () => {
  const f = fixture({ size: 26 * MiB, enforceHttpLimit: false });
  await assert.rejects(() => f.prepareImageSource('https://images.test/large.png'), /25MB/);
  assert.equal(f.destroyed(), 1);
  assert.equal(f.files.size, 0);
});

test('HTTP and filesystem failures release their request and temporary image', async () => {
  const limited = fixture({ size: 26 * MiB });
  await assert.rejects(() => limited.prepareImageSource('https://images.test/large.png'), /maxLimit/);
  assert.equal(limited.destroyed(), 1);
  assert.equal(limited.files.size, 0);
  const failedWrite = fixture({ writeError: true });
  await assert.rejects(() => failedWrite.prepareImageSource('https://images.test/image.png'), /disk full/);
  assert.equal(failedWrite.destroyed(), 1);
  assert.equal(failedWrite.closed(), 1);
  assert.equal(failedWrite.files.size, 0);
});

test('inline image preparation keeps its existing decoded-byte and release behavior', async () => {
  const f = fixture();
  const bytes = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0, 0, 0, 0]);
  const prepared = await f.prepareImageSource('data:image/png;base64,' + bytes.toString('base64'));
  assert.deepEqual(Buffer.from(f.files.get(prepared.path)), bytes);
  assert.equal(f.requests.length, 0);
  f.releaseImageSource(prepared);
  assert.equal(f.files.size, 0);
});
