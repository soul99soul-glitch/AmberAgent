// Execute the page's real backup operations with file/picker/repository ports only mocked.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../../../chat/node_modules/typescript');
const pagePath = path.resolve(__dirname, '../main/ets/pages/SettingBackupPage.ets');
function method(source, name) {
  const start = source.indexOf(name + '(');
  let body = source.indexOf('{', start), depth = 1, end = body + 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(source.lastIndexOf('\n', start) + 1, end);
}
function fixture(options = {}) {
  const source = fs.readFileSync(pagePath, 'utf8');
  const exported = {};
  let closes = 0, backups = 0, inspections = 0, restores = 0;
  let degraded = options.degraded ?? false;
  const davCalls = [];
  const bytes = new Uint8Array(128);
  const names = ['packBackupBytes', 'doExport', 'doPickRestore', 'doRestore', 'doUploadWebDav', 'davClientOrNull', 'doDownloadWebDav'];
  for (const optional of ['writeBackupFile', 'readBackupFile']) {
    if (source.includes(optional + '(')) names.unshift(optional);
  }
  const code = 'class Page { busy=false; usePassphrase=false; exportConversations=true; exportAssistants=false;'
    + ' exportProviders=false; restoreConversations=true; restoreAssistants=false; restoreProviders=false;'
    + ' pendingArchivePath="file://restore"; preview=null; davUrl="https://dav.test/"; davUser="u"; davPass="p"; davPath="backups";'
    + ' passOrNull(){return null;} ctx(){return {cacheDir:"/cache"};} toast(){} setPreview(v){this.preview=v;} '
    + names.map(name => method(source, name)).join('\n') + '} exports.Page=Page;';
  vm.runInNewContext(ts.transpileModule(code, { compilerOptions: {
    target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS,
  } }).outputText, {
    exports: exported, Uint8Array, ArrayBuffer, Date, String, Error, Promise,
    AppStorage: { get: () => degraded },
    fileIo: {
      OpenMode: {}, openSync: () => ({ fd: 99 }), statSync: () => { if (options.statThrow) throw new Error('stat_fault'); return { size: bytes.length }; },
      writeSync: (_fd, data) => { if (options.writeThrow) throw new Error('disk_full'); return options.writeCount ?? data.byteLength; },
      readSync: (_fd, data) => { if (options.readThrow) throw new Error('read_fault'); return options.readCount ?? data.byteLength; },
      closeSync: () => { closes++; },
    },
    picker: { DocumentViewPicker: class { async save() { return ['file://export']; } async select() { return ['file://restore']; } } },
    getChatRepository: () => ({ list: async () => { if (options.degradedDuringRead) degraded = true; return []; },
      getById: async () => null, restoreConversations: async () => {} }),
    getChatKvStore: () => ({}), buildSyncSettingsBlob: async () => ({}), createEntrySyncCryptoPort: () => ({}),
    createSyncBackup: async () => { backups++; return bytes; },
    inspectSyncArchive: async () => { inspections++; return { manifest: {}, needsPassphrase: false }; },
    restoreSyncBackup: async () => { restores++; return { payload: { conversations: [] } }; },
    createWebDavTransport: () => ({}), webDavBackupFileName: () => 'backup.abin',
    createWebDavClient: () => ({ get: async () => bytes,
      mkcol: async name => { davCalls.push(['mkcol', name]); if (options.mkcolThrow) throw new Error('MKCOL failed: 409'); },
      put: async name => { davCalls.push(['put', name]); },
    }),
    settingsFromSyncPayload: () => ({}), hasActiveConversationRuns: () => false,
    beginConversationRestore: () => () => {}, markConversationsRestored: () => {},
    applySyncRestoreSettings: async () => null,
  });
  return { page: new exported.Page(), closes: () => closes, backups: () => backups,
    inspections: () => inspections, restores: () => restores, davCalls };
}

test('short export write fails and closes the descriptor', async () => {
  const f = fixture({ writeCount: 3 }); await f.page.doExport();
  assert.match(f.page.statusMsg, /导出失败/); assert.equal(f.closes(), 1);
});
test('export write exception closes the descriptor', async () => {
  const f = fixture({ writeThrow: true }); await f.page.doExport();
  assert.match(f.page.statusMsg, /disk_full/); assert.equal(f.closes(), 1);
});
for (const operation of ['doPickRestore', 'doRestore']) {
  test(operation + ' short read fails before inspection/restore and closes descriptor', async () => {
    const f = fixture({ readCount: 3 }); await f.page[operation]();
    assert.match(f.page.statusMsg, /失败/); assert.equal(f.inspections(), 0); assert.equal(f.restores(), 0);
    assert.equal(f.closes(), 1);
  });
  test(operation + ' read exception closes descriptor', async () => {
    const f = fixture({ readThrow: true }); await f.page[operation]();
    assert.match(f.page.statusMsg, /read_fault/); assert.equal(f.closes(), 1);
  });
}
test('degraded conversation storage cannot produce a successful empty backup', async () => {
  const f = fixture({ degraded: true }); await f.page.doExport();
  assert.match(f.page.statusMsg, /导出失败/); assert.equal(f.backups(), 0);
});
test('full write retains ordinary successful export behavior', async () => {
  const f = fixture(); await f.page.doExport();
  assert.match(f.page.statusMsg, /已导出/); assert.equal(f.closes(), 1);
});

test('first lazy storage failure while reading is checked before archive creation', async () => {
  const f = fixture({ degradedDuringRead: true }); await f.page.doExport();
  assert.match(f.page.statusMsg, /导出失败/); assert.equal(f.backups(), 0);
});
test('settings-only export works when conversation storage is degraded', async () => {
  const f = fixture({ degraded: true }); f.page.exportConversations = false; f.page.exportAssistants = true;
  await f.page.doExport(); assert.match(f.page.statusMsg, /已导出/); assert.equal(f.backups(), 1);
});
for (const operation of ['doPickRestore', 'doRestore']) {
  test(operation + ' full read retains successful behavior', async () => {
    const f = fixture(); await f.page[operation]();
    assert.equal(f.closes(), 1);
    if (operation === 'doPickRestore') { assert.match(f.page.statusMsg, /预览/); assert.equal(f.inspections(), 1); }
    else { assert.match(f.page.statusMsg, /已恢复/); assert.equal(f.restores(), 1); }
  });
}

test('remote upload creates the configured target once before PUT', async () => {
  const f = fixture(); await f.page.doUploadWebDav();
  assert.deepEqual(f.davCalls, [['mkcol', ''], ['put', 'backup.abin']]); assert.match(f.page.statusMsg, /已上传/);
});
test('missing DAV parent fails before PUT without retry', async () => {
  const f = fixture({ mkcolThrow: true }); await f.page.doUploadWebDav();
  assert.deepEqual(f.davCalls, [['mkcol', '']]); assert.match(f.page.statusMsg, /上传失败.*409/);
});
for (const options of [{ writeCount: 3 }, { writeThrow: true }]) {
  test('remote download write fault closes file and does not expose restore preview ' + JSON.stringify(options), async () => {
    const f = fixture(options); await f.page.doDownloadWebDav({ name: 'backup.abin' });
    assert.match(f.page.statusMsg, /下载失败/); assert.equal(f.closes(), 1); assert.equal(f.inspections(), 0);
  });
}
for (const operation of ['doPickRestore', 'doRestore']) {
  test(operation + ' stat failure closes the descriptor', async () => {
    const f = fixture({ statThrow: true }); await f.page[operation]();
    assert.match(f.page.statusMsg, /stat_fault/); assert.equal(f.closes(), 1);
  });
}
