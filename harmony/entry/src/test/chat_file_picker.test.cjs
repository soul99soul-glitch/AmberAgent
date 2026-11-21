// Run the actual Composer owner and attachment IO; ArkUI rendering is outside this port-level regression.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const { loadChatComposerSession } = require('./chat_composer_fixture.cjs');

const ioSource = ts.transpileModule(fs.readFileSync(
  path.resolve(__dirname, '../main/ets/platform_impl/chat/ChatAttachmentIO.ets'), 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
}).outputText;

function fixture({ select = async () => [], files = {} } = {}) {
  const state = { errorMsg: '' };
  const closed = [];
  const fileIo = {
    OpenMode: { READ_ONLY: 1 },
    openSync: uri => {
      if (files[uri] instanceof Error) throw files[uri];
      return { fd: uri };
    },
    statSync: uri => ({ size: typeof files[uri] === 'number' ? files[uri] : Buffer.byteLength(files[uri]) }),
    readSync: (uri, buffer) => { new Uint8Array(buffer).set(Buffer.from(files[uri])); },
    closeSync: file => closed.push(file.fd),
  };
  const util = { Base64Helper: class { encodeToStringSync(bytes) { return Buffer.from(bytes).toString('base64'); } } };
  const io = {};
  new Function('exports', 'require', ioSource)(io, name => {
    if (name === '@kit.CoreFileKit') return { fileIo };
    if (name === '@kit.ArkTS') return { util };
    throw new Error(`Unexpected attachment IO dependency: ${name}`);
  });
  const Composer = loadChatComposerSession({}, {
    picker: {
      DocumentViewPicker: class { select(options) { assert.equal(options.maxSelectNumber, 3); return select(); } },
      DocumentSelectOptions: class {},
    }, util, attachmentIO: io,
  });
  const page = new Composer();
  page.start({ error: message => { state.errorMsg = message; } });
  return { page, state, closed };
}

for (const [label, failure, message] of [
  ['oversized', 11 * 1024 * 1024, /10MB/],
  ['unreadable', new Error('permission denied'), /permission denied/],
]) {
  test(`a ${label} document shows its failure while later readable selections and existing attachments survive`, async () => {
    const f = fixture({
      select: async () => ['file://first.txt', 'file://bad.txt', 'file://last.txt'],
      files: { 'file://first.txt': 'first', 'file://bad.txt': failure, 'file://last.txt': 'last' },
    });
    f.page.pendingDocs = [{ fileName: 'existing.txt' }];
    await f.page.pickFiles();
    assert.deepEqual(f.page.pendingDocs.map(doc => doc.fileName), ['existing.txt', 'first.txt', 'last.txt']);
    assert.match(f.state.errorMsg, /bad\.txt/);
    assert.match(f.state.errorMsg, message);
    assert.equal(f.closed.includes('file://first.txt'), true);
    assert.equal(f.closed.includes('file://last.txt'), true);
    if (typeof failure === 'number') assert.equal(f.closed.includes('file://bad.txt'), true);
  });
}

test('empty selection and picker cancellation stay silent and preserve the current composer', async () => {
  for (const select of [async () => [], async () => { throw new Error('cancel'); }]) {
    const { page, state } = fixture({ select });
    page.pendingDocs = [{ fileName: 'existing.txt' }];
    state.errorMsg = 'existing error';
    await page.pickFiles();
    assert.deepEqual(page.pendingDocs, [{ fileName: 'existing.txt' }]);
    assert.equal(state.errorMsg, 'existing error');
  }
});

test('a stale picker callback cannot append documents or replace a current error', async () => {
  let complete;
  const f = fixture({ select: () => new Promise(resolve => { complete = resolve; }) });
  const task = f.page.pickFiles();
  f.page.invalidatePicker();
  f.state.errorMsg = 'new composer';
  complete(['file://stale.txt']);
  await task;
  assert.deepEqual(f.page.pendingDocs, []);
  assert.equal(f.state.errorMsg, 'new composer');
});

test('percent sign in filename, chunked virtual stream (stat.size === 0), and partial readSync succeed cleanly', async () => {
  const closed = [];
  const virtualPayload = Buffer.from('hello-virtual-stream-100%');
  const partialPayload = Buffer.from('partial-read-content');
  const fileIo = {
    OpenMode: { READ_ONLY: 1 },
    openSync: uri => ({ fd: uri }),
    statSync: uri => ({ size: uri.includes('virtual') ? 0 : partialPayload.length }),
    readSync: (uri, buffer, opts) => {
      const src = uri.includes('virtual') ? virtualPayload : partialPayload;
      const off = opts?.offset ?? 0;
      if (off >= src.length) return 0;
      // 模拟鸿蒙真实 readSync 语义：按文件 offset 读取并写入目标 buffer[0..take]
      const take = Math.min(5, src.length - off, opts?.length ?? buffer.byteLength);
      new Uint8Array(buffer, 0, take).set(src.subarray(off, off + take));
      return take;
    },
    closeSync: file => closed.push(file.fd),
  };
  const util = { Base64Helper: class { encodeToStringSync(bytes) { return Buffer.from(bytes).toString('base64'); } } };
  const io = {};
  new Function('exports', 'require', ioSource)(io, name => {
    if (name === '@kit.CoreFileKit') return { fileIo };
    if (name === '@kit.ArkTS') return { util };
    throw new Error(`Unexpected attachment IO dependency: ${name}`);
  });
  const Composer = loadChatComposerSession({}, {
    picker: {
      DocumentViewPicker: class {
        select() { return Promise.resolve(['file:///docs/报告100%_virtual.txt', 'file:///docs/part.txt']); }
      },
      DocumentSelectOptions: class {},
    },
    util,
    attachmentIO: io,
  });
  const errors = [];
  const page = new Composer();
  page.start({ error: msg => errors.push(msg), abilityContext: () => ({}) });
  await page.pickFiles();
  assert.deepEqual(errors, []);
  assert.equal(page.pendingDocs.length, 2);
  assert.equal(page.pendingDocs[0].fileName, '报告100%_virtual.txt');
  assert.equal(Buffer.from(page.pendingDocs[0].url.split(',')[1], 'base64').toString('utf8'), 'hello-virtual-stream-100%');
  assert.equal(Buffer.from(page.pendingDocs[1].url.split(',')[1], 'base64').toString('utf8'), 'partial-read-content');
  assert.equal(closed.length, 2);
});
