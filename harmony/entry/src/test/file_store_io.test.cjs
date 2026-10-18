const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');

function fixture(options = {}) {
  const files = new Map(Object.entries(options.files ?? {}).map(([name, bytes]) => [name, Buffer.from(bytes)]));
  const handles = new Map();
  const closed = [];
  let nextFd = 0;
  let reads = 0;
  let writes = 0;
  const OpenMode = { READ_ONLY: 1, READ_WRITE: 2, CREATE: 4, TRUNC: 8, APPEND: 16 };
  const fileIo = {
    OpenMode,
    mkdir: async () => {},
    access: async name => files.has(name),
    open: async (name, mode) => {
      if (mode & OpenMode.TRUNC) files.set(name, Buffer.alloc(0));
      if (!files.has(name)) {
        assert.ok(mode & OpenMode.CREATE, 'a missing file must only open in create mode');
        files.set(name, Buffer.alloc(0));
      }
      const file = { fd: ++nextFd };
      handles.set(file.fd, { name, position: mode & OpenMode.APPEND ? files.get(name).length : 0 });
      return file;
    },
    stat: async fd => ({ size: options.statSize ?? files.get(handles.get(fd).name).length }),
    close: async file => {
      assert.equal(handles.delete(file.fd), true);
      closed.push(file.fd);
    },
    write: async (fd, input, writeOptions) => {
      assert.equal(writeOptions, undefined, 'continue from the descriptor position; offset addresses the file');
      const handle = handles.get(fd);
      const bytes = Buffer.from(input);
      const count = options.writeCounts?.[writes++] ?? Math.min(options.maxWrite ?? bytes.length, bytes.length);
      if (count <= 0 || count > bytes.length) return count;
      const current = files.get(handle.name);
      const updated = Buffer.alloc(Math.max(current.length, handle.position + count));
      current.copy(updated);
      bytes.copy(updated, handle.position, 0, count);
      handle.position += count;
      files.set(handle.name, updated);
      return count;
    },
    read: async (fd, buffer, readOptions) => {
      assert.equal(readOptions, undefined, 'continue from the descriptor position; offset addresses the file');
      const handle = handles.get(fd);
      const bytes = files.get(handle.name);
      const available = Math.min(buffer.byteLength, bytes.length - handle.position);
      const count = options.readCounts?.[reads++] ?? Math.min(options.maxRead ?? available, available);
      if (count <= 0 || count > buffer.byteLength) return count;
      new Uint8Array(buffer).set(bytes.subarray(handle.position, handle.position + count));
      handle.position += count;
      return count;
    },
  };
  const source = fs.readFileSync(path.join(__dirname, '../main/ets/platform_impl/FileStore.ets'), 'utf8');
  const code = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
  } }).outputText;
  const exports = {};
  new Function('require', 'exports', 'AppStorage', code)(name => {
    if (name === '@kit.CoreFileKit') return { fileIo };
    if (name === '@kit.ArkTS') return { util: { TextEncoder: {
      create: encoding => ({
        encodeInto: text => text.length === 0 ? undefined : new Uint8Array(Buffer.from(text, encoding)),
      }),
    } } };
    if (name === '@amber/deepread-domain') return { joinPath: (...parts) => parts.join('/').replace(/\/+/g, '/') };
    throw new Error(`Unexpected import: ${name}`);
  }, exports, { get: () => ({ filesDir: '/sandbox' }) });
  return { store: exports.createFileStore(), files, handles, closed };
}

test('real FileStore writes complete UTF-8 text when writes stop inside multibyte characters', async () => {
  const { store, files, handles, closed } = fixture({ maxWrite: 2 });
  const content = '{"message":"你好 🌍"}\n';
  await store.writeText('novel/project.json', content);
  assert.deepEqual(files.get('/sandbox/novel/project.json'), Buffer.from(content));
  assert.equal(handles.size, 0);
  assert.equal(closed.length, 1);
});

test('real FileStore appends the complete UTF-8 transcript without replacing existing content', async () => {
  const name = '/sandbox/model-council/runs/run.jsonl';
  const prefix = '{"role":"user"}\n';
  const content = '{"message":"继续 🧭"}\n';
  const { store, files, handles, closed } = fixture({ files: { [name]: prefix }, maxWrite: 3 });
  await store.appendText('model-council/runs/run.jsonl', content);
  assert.deepEqual(files.get(name), Buffer.from(prefix + content));
  assert.equal(handles.size, 0);
  assert.equal(closed.length, 1);
});

test('real FileStore writes only the requested Uint8Array view through partial writes', async () => {
  const { store, files, handles } = fixture({ maxWrite: 2 });
  const bytes = new Uint8Array([99, 1, 2, 3, 4, 5, 88]);
  await store.writeBytes('recipe/file.bin', bytes.subarray(1, 6));
  assert.deepEqual(files.get('/sandbox/recipe/file.bin'), Buffer.from([1, 2, 3, 4, 5]));
  assert.equal(handles.size, 0);
});

test('real FileStore fills the expected bytes across short reads instead of returning zero padding', async () => {
  const content = Buffer.from([1, 2, 3, 4, 5, 6, 7]);
  const { store, handles, closed } = fixture({ files: { '/sandbox/file.bin': content }, maxRead: 2 });
  assert.deepEqual(await store.readBytes('file.bin'), new Uint8Array(content));
  assert.equal(handles.size, 0);
  assert.equal(closed.length, 1);
});

for (const [method, input] of [
  ['writeText', '保存 🌊'], ['appendText', '继续 🌊'], ['writeBytes', new Uint8Array([1, 2, 3, 4])],
]) {
  test(`real FileStore ${method} reports a stalled write and closes the descriptor`, async () => {
    const { store, handles, closed } = fixture({ writeCounts: [2, 0] });
    await assert.rejects(store[method]('file.bin', input), /byte count|write.*progress/i);
    assert.equal(handles.size, 0);
    assert.equal(closed.length, 1);
  });
}

test('real FileStore reports early EOF rather than returning an apparently complete file', async () => {
  const { store, handles, closed } = fixture({ files: { '/sandbox/file.bin': [1, 2] }, statSize: 5 });
  await assert.rejects(store.readBytes('file.bin'), /byte count|read.*progress|end of file/i);
  assert.equal(handles.size, 0);
  assert.equal(closed.length, 1);
});

test('real FileStore preserves empty and missing file behavior', async () => {
  const { store, files, handles, closed } = fixture({ files: {
    '/sandbox/empty.bin': [], '/sandbox/empty.txt': 'truncate me', '/sandbox/existing.txt': 'preserve me',
  } });
  assert.equal(await store.readBytes('missing.bin'), null);
  assert.deepEqual(await store.readBytes('empty.bin'), new Uint8Array(0));
  await store.writeText('empty.txt', '');
  await store.appendText('new-empty.txt', '');
  await store.appendText('existing.txt', '');
  await store.writeBytes('new-empty.bin', new Uint8Array(0));
  for (const name of ['empty.txt', 'new-empty.txt', 'new-empty.bin']) {
    assert.deepEqual(files.get('/sandbox/' + name), Buffer.alloc(0));
  }
  assert.deepEqual(files.get('/sandbox/existing.txt'), Buffer.from('preserve me'));
  assert.equal(handles.size, 0);
  assert.equal(closed.length, 5);
});
