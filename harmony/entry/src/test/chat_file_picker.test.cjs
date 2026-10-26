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
