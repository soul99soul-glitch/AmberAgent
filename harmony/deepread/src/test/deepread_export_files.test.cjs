const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const ts = require('typescript');

const parserExports = {};
const parserFile = path.resolve(__dirname, '../../../chat/src/main/ets/chat/markdown_blocks.ts');
vm.runInNewContext(ts.transpileModule(fs.readFileSync(parserFile, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText, { exports: parserExports });

const MODULE = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl/DeepReadExportFiles.ets');
const fixture = () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'deepread-export-files-'));
  let pickerResult = [path.join(root, 'picked-file')];
  let pickerWait = null;
  let apiVersion = 14;
  let webCapability = true;
  let writeFailure = null;
  let closeFailure = null;
  let cleanupFailure = null;
  let shareFailure = null;
  let chunkLimit = Infinity;
  const opened = [], closed = [], removed = [], pickerCalls = [], shared = [], writes = [];
  const fileIo = {
    OpenMode: { READ_WRITE: fs.constants.O_RDWR, CREATE: fs.constants.O_CREAT, TRUNC: fs.constants.O_TRUNC },
    openSync: (file, flags) => { opened.push(file); return { fd: fs.openSync(file, flags) }; },
    mkdirSync: directory => fs.mkdirSync(directory),
    rmdirSync: directory => fs.rmdirSync(directory),
    writeSync: (fd, bytes) => {
      assert.ok(bytes instanceof ArrayBuffer, 'SDK accepts ArrayBuffer/string, not Uint8Array');
      if (writeFailure) throw writeFailure;
      const value = Buffer.from(bytes);
      writes.push(Buffer.from(value));
      return fs.writeSync(fd, value.subarray(0, Math.min(value.length, chunkLimit)));
    },
    closeSync: fd => { closed.push(fd); fs.closeSync(fd); if (closeFailure) throw closeFailure; },
    unlinkSync: file => { removed.push(file); if (cleanupFailure) throw cleanupFailure; fs.unlinkSync(file); },
  };
  const imports = {
    '@amber/chat-domain': parserExports,
    '@kit.AbilityKit': {},
    '@kit.CoreFileKit': { fileIo, fileUri: { getUriFromPath: file => `file://app${file}` }, picker: {
      DocumentViewPicker: class {
        async save(options) {
          pickerCalls.push(Array.from(options.newFileNames));
          if (pickerWait) await pickerWait;
          return pickerResult;
        }
      },
    } },
    '@kit.ShareKit': { systemShare: {
      SharedData: class { constructor(record) { this.record = record; } },
      ShareController: class {
        constructor(data) { this.data = data; }
        async show(context, options) {
          assert.equal(Object.keys(options).length, 0, 'No screen-pixel anchor');
          const record = this.data.record;
          if (record.uri) {
            assert.ok(record.uri.startsWith('file://app'));
            const file = record.uri.slice('file://app'.length);
            assert.ok(fs.existsSync(file), 'the actual share file exists before the sheet opens');
            assert.equal(closed.length, opened.length, 'file descriptors are closed before sharing');
            shared.push({ ...record, bytes: fs.readFileSync(file) });
          } else shared.push({ ...record });
          if (shareFailure) throw shareFailure;
        }
      },
    } },
    '@kit.ArkData': { uniformTypeDescriptor: { UniformDataType:
      { PLAIN_TEXT: 'general.plain-text', MARKDOWN: 'general.markdown', PDF: 'com.adobe.pdf' } } },
    '@kit.ArkTS': { util: { generateRandomUUID: () => crypto.randomUUID(),
      TextEncoder: { create: () => ({ encodeInto: value => new TextEncoder().encode(value) }) } } },
    '@kit.BasicServicesKit': { deviceInfo: { get sdkApiVersion() { return apiVersion; } } },
  };
  const exports = {};
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(MODULE, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, require: spec => {
    assert.ok(spec in imports, `unexpected import ${spec}`); return imports[spec];
  }, canIUse: capability => {
    assert.equal(capability, 'SystemCapability.Web.Webview.Core'); return webCapability;
  }, Error, Promise, ArrayBuffer, Uint8Array, String, Number, Math }, { filename: MODULE });
  return { api: exports, context: { cacheDir: root }, root, opened, closed, removed, pickerCalls, shared, writes,
    setPickerResult: value => { pickerResult = value; }, setPickerWait: value => { pickerWait = value; },
    setApi: (version, capability) => { apiVersion = version; webCapability = capability; },
    setWriteFailure: value => { writeFailure = value; }, setCloseFailure: value => { closeFailure = value; },
    setCleanupFailure: value => { cleanupFailure = value; }, setShareFailure: value => { shareFailure = value; },
    setChunkLimit: value => { chunkLimit = value; },
    cleanup: () => fs.rmSync(root, { recursive: true, force: true }) };
};

test('PDF support requires both API 14 and ArkWeb capability', () => {
  const f = fixture();
  try {
    f.setApi(12, true); assert.equal(f.api.isDeepReadPdfSupported(), false);
    f.setApi(13, true); assert.equal(f.api.isDeepReadPdfSupported(), false);
    f.setApi(14, false); assert.equal(f.api.isDeepReadPdfSupported(), false);
    f.setApi(14, true); assert.equal(f.api.isDeepReadPdfSupported(), true);
    f.setApi(26, true); assert.equal(f.api.isDeepReadPdfSupported(), true);
  } finally { f.cleanup(); }
});

test('text sharing uses plain text with its title and never creates a cache file', async () => {
  const f = fixture();
  try {
    await f.api.shareDeepReadText(f.context, '完整深读', '正文与来源😀');
    assert.deepEqual(f.shared, [{ utd: 'general.plain-text', title: '完整深读', content: '正文与来源😀' }]);
    assert.equal(f.opened.length, 0);
    assert.equal(f.pickerCalls.length, 0);
  } finally { f.cleanup(); }
});

test('Markdown save/share preserve exact UTF8 through short writes and retain the successful share file', async () => {
  const f = fixture();
  try {
    const content = '# 完整深读\n\n中文正文、表格 **Markdown** 和 emoji 😀\n';
    const saved = path.join(f.root, 'saved.md');
    f.setPickerResult([saved]);
    f.setChunkLimit(5);
    assert.equal(await f.api.saveDeepReadMarkdown(f.context, 'safe-hash.md', content), true);
    assert.equal(fs.readFileSync(saved, 'utf8'), content);
    assert.deepEqual(f.pickerCalls, [['safe-hash.md']]);
    await f.api.shareDeepReadMarkdown(f.context, 'shared-hash.md', content);
    const shared = f.shared[0];
    assert.equal(shared.utd, 'general.markdown');
    assert.equal(shared.title, 'shared-hash.md');
    assert.equal(shared.bytes.toString('utf8'), content);
    assert.ok(fs.existsSync(shared.uri.slice('file://app'.length)));
    assert.equal(path.basename(shared.uri), 'shared-hash.md');
    assert.equal(f.removed.length, 0);
  } finally { f.cleanup(); }
});

test('PDF save/share write only the view byteOffset/byteLength, close first and keep the shared file', async () => {
  const f = fixture();
  try {
    const bytes = new Uint8Array([99, 98, 37, 80, 68, 70, 45, 49, 46, 55, 10, 97, 96]).subarray(2, 11);
    const saved = path.join(f.root, 'saved.pdf');
    f.setPickerResult([saved]);
    f.setChunkLimit(3);
    assert.equal(await f.api.saveDeepReadPdf(f.context, 'hash.pdf', bytes), true);
    assert.deepEqual(fs.readFileSync(saved), Buffer.from('%PDF-1.7\n'));
    await f.api.shareDeepReadPdf(f.context, 'share-hash.pdf', bytes);
    assert.equal(f.shared[0].utd, 'com.adobe.pdf');
    assert.deepEqual(f.shared[0].bytes, Buffer.from('%PDF-1.7\n'));
    assert.ok(fs.existsSync(f.shared[0].uri.slice('file://app'.length)));
    assert.equal(path.basename(f.shared[0].uri), 'share-hash.pdf');
    assert.equal(f.removed.length, 0);
  } finally { f.cleanup(); }
});

test('picker cancellation is false without opening or writing a file', async () => {
  const f = fixture();
  try {
    f.setPickerResult([]);
    assert.equal(await f.api.saveDeepReadMarkdown(f.context, 'hash.md', '正文'), false);
    assert.equal(await f.api.saveDeepReadPdf(f.context, 'hash.pdf', new Uint8Array([1])), false);
    assert.equal(f.opened.length, 0);
  } finally { f.cleanup(); }
});

test('a picker result after the export panel closes cannot write the selected URI', async () => {
  const f = fixture();
  try {
    let release;
    let active = true;
    f.setPickerWait(new Promise(resolve => { release = resolve; }));
    const pending = f.api.saveDeepReadMarkdown(f.context, 'hash.md', '正文', () => active);
    active = false;
    release();
    assert.equal(await pending, false);
    assert.equal(f.opened.length, 0);
    assert.equal(fs.existsSync(path.join(f.root, 'picked-file')), false);
  } finally { f.cleanup(); }
});

test('inactive exports never open picker/share UI and a late share gate cleans its prepared file', async () => {
  const f = fixture();
  try {
    assert.equal(await f.api.saveDeepReadPdf(f.context, 'hash.pdf', new Uint8Array([1]), () => false), false);
    await f.api.shareDeepReadText(f.context, '标题', '正文', () => false);
    await f.api.shareDeepReadMarkdown(f.context, 'inactive.md', '正文', () => false);
    assert.equal(f.pickerCalls.length, 0);
    assert.equal(f.shared.length, 0);
    let reads = 0;
    await f.api.shareDeepReadPdf(f.context, 'late.pdf', new Uint8Array([1]), () => ++reads === 1);
    assert.equal(f.shared.length, 0);
    assert.equal(fs.existsSync(f.opened[0]), false);
    assert.equal(fs.existsSync(path.dirname(f.opened[0])), false);
  } finally { f.cleanup(); }
});

test('share failure cleans the written cache file and preserves the original error when cleanup also fails', async () => {
  const f = fixture();
  try {
    const failure = new Error('system share rejected');
    f.setShareFailure(failure);
    await assert.rejects(() => f.api.shareDeepReadMarkdown(f.context, 'failed.md', '正文'), error => error === failure);
    assert.equal(fs.existsSync(f.opened[0]), false);
    f.setCleanupFailure(new Error('cleanup denied'));
    await assert.rejects(() => f.api.shareDeepReadPdf(f.context, 'failed.pdf', new Uint8Array([1])), error => error === failure);
    assert.ok(f.removed.includes(f.opened[1]));
  } finally { f.cleanup(); }
});

test('cache write failure is cleaned before sharing and close/cleanup failures do not mask the write error', async () => {
  const f = fixture();
  try {
    const failure = new Error('disk full while exporting');
    f.setWriteFailure(failure);
    await assert.rejects(() => f.api.shareDeepReadPdf(f.context, 'failed.pdf', new Uint8Array([1])), error => error === failure);
    assert.equal(f.shared.length, 0);
    assert.equal(fs.existsSync(f.opened[0]), false);
    assert.equal(f.closed.length, 1);
    f.setCloseFailure(new Error('close failed'));
    f.setCleanupFailure(new Error('cleanup denied'));
    await assert.rejects(() => f.api.shareDeepReadMarkdown(f.context, 'failed.md', '正文'), error => error === failure);
    assert.equal(f.shared.length, 0);
    assert.equal(f.closed.length, 2);
    assert.ok(f.removed.includes(f.opened[1]));
  } finally { f.cleanup(); }
});

test('a zero-byte write fails explicitly and cleans the incomplete share file', async () => {
  const f = fixture();
  try {
    f.setChunkLimit(0);
    await assert.rejects(() => f.api.shareDeepReadPdf(f.context, 'incomplete.pdf', new Uint8Array([1, 2])), /文件写入未完成/);
    assert.equal(fs.existsSync(f.opened[0]), false);
    assert.equal(f.shared.length, 0);
  } finally { f.cleanup(); }
});

for (const format of ['markdown', 'pdf']) {
  test(`a later failed ${format} share of the same display filename preserves the previous recipient URI`, async () => {
    const f = fixture();
    try {
      const fileName = `same-topic-hash.${format === 'markdown' ? 'md' : 'pdf'}`;
      const share = text => format === 'markdown'
        ? f.api.shareDeepReadMarkdown(f.context, fileName, text)
        : f.api.shareDeepReadPdf(f.context, fileName, new TextEncoder().encode(text));
      await share('旧版已交付正文');
      const handed = f.shared[0].uri.slice('file://app'.length);
      f.setShareFailure(new Error('second share rejected'));
      await assert.rejects(() => share('新版正文'), /second share rejected/);
      assert.ok(fs.existsSync(handed), 'the first successful share URI must remain readable');
      assert.equal(fs.readFileSync(handed, 'utf8'), '旧版已交付正文');
      assert.equal(f.shared[0].title, fileName);
      assert.equal(f.shared[1].title, fileName);
      assert.equal(path.basename(handed), fileName);
      assert.equal(fs.existsSync(f.opened[1]), false, 'only the failed invocation file is removed');
      assert.equal(fs.existsSync(path.dirname(f.opened[1])), false);
    } finally { f.cleanup(); }
  });
}

test('repeated successful shares preserve independent immutable files with the same display filename', async () => {
  const f = fixture();
  try {
    await f.api.shareDeepReadMarkdown(f.context, 'same-topic.md', '第一份已交付正文');
    await f.api.shareDeepReadMarkdown(f.context, 'same-topic.md', '第二份已交付正文');
    const first = f.shared[0].uri.slice('file://app'.length);
    const second = f.shared[1].uri.slice('file://app'.length);
    assert.notEqual(first, second);
    assert.equal(path.basename(first), 'same-topic.md');
    assert.equal(path.basename(second), 'same-topic.md');
    assert.equal(fs.readFileSync(first, 'utf8'), '第一份已交付正文');
    assert.equal(fs.readFileSync(second, 'utf8'), '第二份已交付正文');
  } finally { f.cleanup(); }
});


test('TXT save and file share keep exact UTF8 and partial labels through short writes', async () => {
  const f = fixture();
  try {
    const content = '中文文章😀\n\n生成状态：部分稿\n\n已经保存的正文\n';
    const saved = path.join(f.root, 'saved.txt');
    f.setPickerResult([saved]); f.setChunkLimit(3);
    assert.equal(await f.api.saveDeepReadTextFile(f.context, 'article.txt', content), true);
    assert.equal(fs.readFileSync(saved, 'utf8'), content);
    assert.deepEqual(f.pickerCalls, [['article.txt']]);
    await f.api.shareDeepReadTextFile(f.context, 'article.txt', content);
    assert.equal(f.shared[0].utd, 'general.plain-text');
    assert.equal(f.shared[0].title, 'article.txt');
    assert.equal(f.shared[0].bytes.toString('utf8'), content);
    assert.ok(fs.existsSync(f.shared[0].uri.slice('file://app'.length)));
  } finally { f.cleanup(); }
});

test('TXT cancellation and late picker result never write, and system share errors stay observable', async () => {
  const f = fixture();
  try {
    f.setPickerResult([]);
    assert.equal(await f.api.saveDeepReadTextFile(f.context, 'article.txt', '正文'), false);
    let release, active = true;
    f.setPickerResult([path.join(f.root, 'late.txt')]);
    f.setPickerWait(new Promise(resolve => { release = resolve; }));
    const pending = f.api.saveDeepReadTextFile(f.context, 'article.txt', '正文', () => active);
    active = false; release(); assert.equal(await pending, false);
    assert.equal(f.opened.length, 0);
    f.setShareFailure(new Error('TXT share rejected'));
    await assert.rejects(() => f.api.shareDeepReadTextFile(f.context, 'article.txt', '正文'), /TXT share rejected/);
    assert.equal(fs.existsSync(f.opened[0]), false);
  } finally { f.cleanup(); }
});


test('actual AST text formatter retains list numbering, nesting, links, images, tables and code facts in saved bytes', async () => {
  const f = fixture();
  try {
    const markdown = '# 标题\n\n**加粗**、*强调*、`inline()`\n\n[来源](https://example.com/a?q=1)\n\n![图注](https://example.com/image.png)\n\n3. 第三项\n4. 第四项\n\n- 一级项\n  - 嵌套项\n\n> 引用事实\n\n|列甲|列乙|\n|---|---|\n|内容甲|内容乙|\n\n```js\nconst literal = "**not formatting**";\n```';
    const content = f.api.deepReadMarkdownToText(markdown);
    for (const fact of ['标题', '加粗、强调、inline()', '来源 (https://example.com/a?q=1)',
      '图注 (https://example.com/image.png)', '3. 第三项', '4. 第四项', '  - 嵌套项', '引用事实',
      '列甲\t列乙', '内容甲\t内容乙', 'const literal = "**not formatting**";']) assert.ok(content.includes(fact), fact);
    assert.equal(content.includes('# 标题'), false); assert.equal(content.includes('**加粗**'), false);
    assert.equal(content.includes('```'), false); assert.equal(content.includes('[来源]('), false);
    const saved = path.join(f.root, 'plain.txt'); f.setPickerResult([saved]);
    await f.api.saveDeepReadTextFile(f.context, 'plain.txt', content);
    assert.equal(fs.readFileSync(saved, 'utf8'), content);
  } finally { f.cleanup(); }
});
