const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');

function loadBlobStore(writeLimit) {
  const files = new Map();
  const handles = new Map();
  let nextFd = 1;
  const io = {
    OpenMode: { READ_WRITE: 1, CREATE: 2, TRUNC: 4 },
    mkdir: async () => {},
    access: async name => files.has(name),
    stat: async name => ({ size: files.get(name).length }),
    open: async name => {
      const fd = nextFd++;
      files.set(name, Buffer.alloc(0));
      handles.set(fd, name);
      return { fd };
    },
    write: async (fd, value) => {
      const bytes = typeof value === 'string' ? Buffer.from(value) : Buffer.from(value);
      const count = Math.min(bytes.length, writeLimit);
      const name = handles.get(fd);
      assert.ok(name, 'write must use an open file');
      files.set(name, Buffer.concat([files.get(name), bytes.subarray(0, count)]));
      return count;
    },
    close: async file => handles.delete(file.fd),
    rename: async (from, to) => { files.set(to, files.get(from)); files.delete(from); },
    readText: async name => files.get(name).toString(),
    unlink: async name => files.delete(name),
  };
  const imports = {
    '@kit.CoreFileKit': { fileIo: io },
    '@kit.PerformanceAnalysisKit': { hilog: { warn() {} } },
    '@kit.ArkTS': { util: { TextEncoder: { create: () => ({ encodeInto: text => new TextEncoder().encode(text) }) } } },
    './EntryLocalToolPorts.ets': { sha256HexUtf8: () => 'a'.repeat(64) },
  };
  const source = fs.readFileSync(path.join(__dirname, '../main/ets/platform_impl/ConversationBlobStore.ets'), 'utf8');
  const js = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
  } }).outputText;
  const exports = {};
  new Function('require', 'exports', 'AppStorage', js)(name => {
    assert.ok(imports[name], name);
    return imports[name];
  }, exports, { get: () => ({ filesDir: '/sandbox' }) });
  return { port: exports.getChatBlobPort(), files, handles };
}

test('blob writes complete short writes before publishing and retain the full UTF-8 value', async () => {
  const { port, handles } = loadBlobStore(7);
  const value = 'data:image/svg+xml,<svg>中文图片</svg>';
  const hash = port.hash(value);
  await port.write(hash, value);
  assert.equal(await port.read(hash), value);
  await port.write(hash, value);
  assert.equal(await port.read(hash), value);
  assert.equal(handles.size, 0);
});

test('a write making no progress rejects without publishing or leaving a temporary file', async () => {
  const { port, files, handles } = loadBlobStore(0);
  const value = 'data:image/png;base64,AAAA';
  await assert.rejects(port.write(port.hash(value), value), /write/i);
  assert.equal(await port.read(port.hash(value)), null);
  assert.equal(files.size, 0);
  assert.equal(handles.size, 0);
});
