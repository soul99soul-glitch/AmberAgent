import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import ts from 'typescript';

interface ShareAPI {
  shareNovelFile(context: { cacheDir: string }, name: string, bytes: Uint8Array,
    format: 'txt' | 'markdown' | 'epub' | 'zip', isActive?: () => boolean): Promise<void>;
}
const loadAPI = (imports: Record<string, unknown>, supported = true): ShareAPI => {
  const source = fs.readFileSync(new URL('../../../entry/src/main/ets/platform_impl/NovelShareSupport.ets', import.meta.url), 'utf8');
  const compiled = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const module = { exports: {} };
  new Function('require', 'exports', 'module', 'canIUse', compiled)((id: string) => {
    assert.ok(id in imports, `unexpected import ${id}`);
    return imports[id];
  }, module.exports, module, () => supported);
  return module.exports as ShareAPI;
};

test('novel shares exact bytes with format-specific UTD and retains file after sheet opens', async t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'amber-novel-share-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const bytes = new TextEncoder().encode('# 小说😀\n\n正文。');
  const shared: Array<{ utd: string; uri: string; title: string }> = [];
  let failShare = false;
  let id = 0;
  const imports = {
    '@kit.AbilityKit': {},
    '@kit.ArkTS': { util: { generateRandomUUID: () => `share-${++id}` } },
    '@kit.ArkData': { uniformTypeDescriptor: { UniformDataType: {
      PLAIN_TEXT: 'general.plain-text', MARKDOWN: 'general.markdown',
      EPUB: 'general.epub', ZIP_ARCHIVE: 'general.zip-archive',
    } } },
    '@kit.CoreFileKit': { fileUri: { getUriFromPath: (file: string) => file }, fileIo: {
      OpenMode: { READ_WRITE: fs.constants.O_RDWR, CREATE: fs.constants.O_CREAT, TRUNC: fs.constants.O_TRUNC },
      mkdirSync: fs.mkdirSync, rmdirSync: fs.rmdirSync, unlinkSync: fs.unlinkSync,
      openSync: (file: string, mode: number) => ({ fd: fs.openSync(file, mode) }), closeSync: fs.closeSync,
      writeSync: (fd: number, data: ArrayBuffer) => {
        assert.ok(data instanceof ArrayBuffer, 'Harmony SDK accepts ArrayBuffer, not Uint8Array');
        return fs.writeSync(fd, new Uint8Array(data).slice(0, 3)); // exercise partial writes
      },
    } },
    '@kit.ShareKit': { systemShare: {
      SharedData: class { constructor(public record: { utd: string; uri: string; title: string }) {} },
      ShareController: class { constructor(public data: { record: { utd: string; uri: string; title: string } }) {}
        async show() {
          assert.deepEqual(new Uint8Array(fs.readFileSync(this.data.record.uri)), bytes);
          if (failShare) throw new Error('share unavailable');
          shared.push(this.data.record);
        }
      },
    } },
  };
  const api = loadAPI(imports);
  const formats = ['txt', 'markdown', 'epub', 'zip'] as const;
  const expected = ['general.plain-text', 'general.markdown', 'general.epub', 'general.zip-archive'];
  for (const [i, format] of formats.entries()) {
    await api.shareNovelFile({ cacheDir: root }, `book-${i}.${format}`, bytes, format);
    assert.equal(shared[i].utd, expected[i]);
    assert.equal(fs.existsSync(shared[i].uri), true);
  }
  const entries = fs.readdirSync(root);
  await api.shareNovelFile({ cacheDir: root }, 'stale.zip', bytes, 'zip', () => false);
  assert.deepEqual(fs.readdirSync(root), entries);
  failShare = true;
  await assert.rejects(api.shareNovelFile({ cacheDir: root }, 'failed.zip', bytes, 'zip'), /share unavailable/);
  assert.deepEqual(fs.readdirSync(root), entries, 'failed share cleans only its own file');
  await assert.rejects(loadAPI(imports, false).shareNovelFile({ cacheDir: root }, 'off.zip', bytes, 'zip'), /分享/);
  assert.deepEqual(fs.readdirSync(root), entries);
});
