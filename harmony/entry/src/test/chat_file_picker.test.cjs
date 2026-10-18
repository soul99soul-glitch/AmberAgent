// Run actual ChatPage picker/read methods; ArkUI rendering is outside this port-level regression.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');

const page = fs.readFileSync(path.resolve(__dirname, '../main/ets/pages/ChatPage.ets'), 'utf8');
const pick = page.slice(page.indexOf('  private async pickFiles():'), page.indexOf('  // 长文本粘贴为文件'));
const readStart = page.indexOf('  private readUriBytes(');
const read = page.slice(readStart, page.indexOf('\n  // 末条 assistant', readStart));
const source = ts.transpileModule(`class Fixture {
  pickerToken = 0; pendingDocs = []; errorMsg = '';
  ${pick}\n${read}\n
}\nexports.Fixture = Fixture;`, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
}).outputText;

function fixture({ select = async () => [], files = {} } = {}) {
  const exports = {};
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
  new Function('exports', 'picker', 'fileIo', 'util', 'mimeByName', source)(exports, {
    DocumentViewPicker: class { select(options) { assert.equal(options.maxSelectNumber, 3); return select(); } },
    DocumentSelectOptions: class {},
  }, fileIo, { Base64Helper: class { encodeToStringSync(bytes) { return Buffer.from(bytes).toString('base64'); } } },
  () => 'text/plain');
  return { page: new exports.Fixture(), closed };
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
    assert.match(f.page.errorMsg, /bad\.txt/);
    assert.match(f.page.errorMsg, message);
    assert.equal(f.closed.includes('file://first.txt'), true);
    assert.equal(f.closed.includes('file://last.txt'), true);
  });
}

test('empty selection and picker cancellation stay silent and preserve the current composer', async () => {
  for (const select of [async () => [], async () => { throw new Error('cancel'); }]) {
    const { page } = fixture({ select });
    page.pendingDocs = [{ fileName: 'existing.txt' }];
    page.errorMsg = 'existing error';
    await page.pickFiles();
    assert.deepEqual(page.pendingDocs, [{ fileName: 'existing.txt' }]);
    assert.equal(page.errorMsg, 'existing error');
  }
});

test('a stale picker callback cannot append documents or replace a current error', async () => {
  let complete;
  const f = fixture({ select: () => new Promise(resolve => { complete = resolve; }) });
  const task = f.page.pickFiles();
  f.page.pickerToken++;
  f.page.errorMsg = 'new composer';
  complete(['file://stale.txt']);
  await task;
  assert.deepEqual(f.page.pendingDocs, []);
  assert.equal(f.page.errorMsg, 'new composer');
});
