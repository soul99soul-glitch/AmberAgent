// probe-f44-document-preview.cjs — isolated smoke for F44 `readDocumentPreview`.
//
// METHOD: loads the REAL `harmony/entry/src/main/ets/platform_impl/DocumentSupport.ets`
// through probe-lib's transpile+require loader and runs its real `readDocumentPreview`
// body. Only platform edges are mocked: @kit.CoreFileKit (in-memory file table, no real
// file IO), @kit.ArkTS (util TextDecoder/Base64Helper over Buffer), and the entry XML/ZIP
// platform module (throwing stubs — never reached on these paths). The domain layer is
// the REAL source: document_transformer.ts, zip_archive.ts, docx/pptx/epub_parser.ts.
//
// Safety: no device, no network, no real user files (file:// path is served from an
// in-memory table), no temp files written.

const path = require('node:path');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const {
  REPO_ROOT, createLoader,
} = require('../../2026-09-13-harmony-merged-evidence/probe-lib.cjs');

const CHAT = path.join(REPO_ROOT, 'harmony/chat/src/main/ets');
const ENTRY = path.join(REPO_ROOT, 'harmony/entry/src/main/ets/platform_impl');

// Minimal ZIP (STORED, no deflate) with no word/document.xml, no ppt/slides/*, no
// META-INF/container.xml — lets real zip_archive + real parsers answer the negative
// paths without invoking the (unmockable) XML pull parser.
const ZIP_B64 = 'UEsDBBQAAAAAALWKLV1vYassFAAAABQAAAAIAAAAbWltZXR5cGVhcHBsaWNhdGlvbi9lcHViK3ppcFBLAwQUAAAAAAC1ii1dmHcp7QkAAAAJAAAADwAAAHdvcmQvc3R5bGVzLnhtbDxzdHlsZXMvPlBLAwQUAAAAAAC1ii1d2W0j6Q8AAAAPAAAAFAAAAHBwdC9wcmVzZW50YXRpb24ueG1sPHByZXNlbnRhdGlvbi8+UEsBAhQDFAAAAAAAtYotXW9hqywUAAAAFAAAAAgAAAAAAAAAAAAAAIABAAAAAG1pbWV0eXBlUEsBAhQDFAAAAAAAtYotXZh3Ke0JAAAACQAAAA8AAAAAAAAAAAAAAIABOgAAAHdvcmQvc3R5bGVzLnhtbFBLAQIUAxQAAAAAALWKLV3ZbSPpDwAAAA8AAAAUAAAAAAAAAAAAAACAAXAAAABwcHQvcHJlc2VudGF0aW9uLnhtbFBLBQYAAAAAAwADALUAAACxAAAAAAA=';
const ZIP_BYTES = new Uint8Array(Buffer.from(ZIP_B64, 'base64'));
assert.equal(ZIP_BYTES.length, 380);
assert.equal(crypto.createHash('sha256').update(ZIP_BYTES).digest('hex').slice(0, 16), '9179ca33dc725bc9');

// ---- in-memory file table (file:// edge) ----
const FILES = new Map([['/tmp/amber-f44/sample.txt', new Uint8Array(Buffer.from('hello file', 'utf8'))]]);
const fileIoMock = {
  OpenMode: { READ_ONLY: 0 },
  statSync(p) {
    if (p === '/tmp/amber-f44/huge.txt') return { mode: 0o100000, size: 11 * 1024 * 1024 };
    if (p === '/tmp/amber-f44/dir') return { mode: 0o040000, size: 0 };
    const b = FILES.get(p);
    if (!b) throw new Error('ENOENT');
    return { mode: 0o100000, size: b.length };
  },
  openSync(p) {
    if (!FILES.has(p)) throw new Error('ENOENT');
    return { fd: 1 };
  },
  readSync(_fd, buf) { new Uint8Array(buf).set(FILES.get('/tmp/amber-f44/sample.txt')); },
  closeSync() {},
};

const utilMock = {
  TextDecoder: { create: () => ({ decodeToString: (u8) => Buffer.from(u8).toString('utf8') }) },
  Base64Helper: class { decodeSync(s) { return new Uint8Array(Buffer.from(s, 'base64')); } },
};

// entry XML/ZIP platform adapters are intentionally unreachable on these paths
const entryMock = {
  createEntryXmlPull: () => { throw new Error('XML pull reached (unexpected)'); },
  createEntryXmlPullNoNs: () => { throw new Error('XML pull reached (unexpected)'); },
  inflateRawEntry: async () => { throw new Error('inflate reached (unexpected)'); },
  parseDocxEntry: async () => { throw new Error('unused'); },
  parsePptxEntry: async () => { throw new Error('unused'); },
  parseEpubEntry: async () => { throw new Error('unused'); },
};

const loader = createLoader(Object.assign((name, load) => {
  if (name === '@kit.CoreFileKit') return { fileIo: fileIoMock };
  if (name === '@kit.ArkTS') return { util: utilMock };
  if (name === './EntryDocumentParser.ets' || name.endsWith('EntryDocumentParser.ets')) return entryMock;
  if (name.endsWith('zip_archive.ts')) return load(path.join(CHAT, 'chat/zip_archive.ts'));
  if (name.endsWith('docx_parser.ts')) return load(path.join(CHAT, 'chat/docx_parser.ts'));
  if (name.endsWith('pptx_parser.ts')) return load(path.join(CHAT, 'chat/pptx_parser.ts'));
  if (name.endsWith('epub_parser.ts')) return load(path.join(CHAT, 'chat/epub_parser.ts'));
  if (name.endsWith('document_transformer.ts')) return load(path.join(CHAT, 'chat/document_transformer.ts'));
  if (name === '@amber/chat-domain') return domainMock;
  return {};
}, { sandboxGlobals: { console, setTimeout, clearTimeout, Math, Error, Promise, Uint8Array, ArrayBuffer, String, Number, Object } }));

const dt = loader.load(path.join(CHAT, 'chat/document_transformer.ts'));
const zip = loader.load(path.join(CHAT, 'chat/zip_archive.ts'));
const dx = loader.load(path.join(CHAT, 'chat/docx_parser.ts'));
const px = loader.load(path.join(CHAT, 'chat/pptx_parser.ts'));
const ep = loader.load(path.join(CHAT, 'chat/epub_parser.ts'));
const domainMock = {
  isLikelyTextFile: dt.isLikelyTextFile,
  readDocumentContent: dt.readDocumentContent,
  readZipEntries: zip.readZipEntries,
  readZipEntryText: zip.readZipEntryText,
  parseDocxFromZip: dx.parseDocxFromZip,
  parsePptxFromZip: px.parsePptxFromZip,
  parseEpubFromZip: ep.parseEpubFromZip,
};

const src = require('node:fs').readFileSync(path.join(ENTRY, 'DocumentSupport.ets'), 'utf8');
assert.ok(src.includes('export const readDocumentPreview'), 'provenance: function present');
console.log('[provenance] DocumentSupport.ets sha256/16 =',
  crypto.createHash('sha256').update(src).digest('hex').slice(0, 16));
assert.ok(src.includes('export const MAX_PREVIEW_INPUT_BYTES: number = 10 * 1024 * 1024'));
assert.ok(src.includes('export const MAX_PREVIEW_CHARS: number = 100 * 1024'));

const mod = loader.load(path.join(ENTRY, 'DocumentSupport.ets'));
assert.equal(mod.MAX_PREVIEW_INPUT_BYTES, 10 * 1024 * 1024);
assert.equal(mod.MAX_PREVIEW_CHARS, 100 * 1024);

const doc = (fileName, mime, url) => ({ type: 'document', url, fileName, mime, metadata: null });
const b64 = (s) => Buffer.from(s, 'utf8').toString('base64');
const T = async (name, fn) => { await fn(); console.log('  ok -', name); };

(async () => {
  await T('pdf 明确不支持(含扩展名归一)', async () => {
    assert.equal(
      await mod.readDocumentPreview(doc('a.pdf', 'application/octet-stream', `data:application/octet-stream;base64,${b64('x')}`)),
      '[ERROR, pdf preview not supported: a.pdf]');
  });

  await T('非 data:/file:// 一律拒绝(不外网)', async () => {
    assert.equal(
      await mod.readDocumentPreview(doc('a.txt', 'text/plain', 'https://example.com/a.txt')),
      '[ERROR, unsupported file uri: a.txt]');
  });

  await T('data:text/*;base64 文本直出', async () => {
    assert.equal(
      await mod.readDocumentPreview(doc('a.txt', 'text/plain', `data:text/plain;base64,${b64('hello 世界')}`)),
      'hello 世界');
  });

  await T('data 二进制非文本非 office → 明确不支持(不读占位)', async () => {
    assert.equal(
      await mod.readDocumentPreview(doc('a.bin', 'application/octet-stream', `data:application/octet-stream;base64,${b64('x')}`)),
      '[ERROR, preview unsupported for this file type: a.bin]');
  });

  await T('file:// 文本经 stat/read 预览', async () => {
    assert.equal(
      await mod.readDocumentPreview(doc('sample.txt', 'text/plain', 'file:///tmp/amber-f44/sample.txt')),
      'hello file');
  });

  await T('file:// 超 10MiB → 先 check 后拒绝(不 open/read)', async () => {
    const out = await mod.readDocumentPreview(doc('huge.txt', 'text/plain', 'file:///tmp/amber-f44/huge.txt'));
    assert.ok(out.startsWith('[ERROR, failed to read file: huge.txt (file exceeds preview limit'), out);
  });

  await T('data 超 10MiB → 先估长度再拒绝(不分配解码)', async () => {
    const payload = 'A'.repeat(14 * 1024 * 1024);
    const out = await mod.readDocumentPreview(doc('big.txt', 'text/plain', `data:text/plain;base64,${payload}`));
    assert.ok(out.startsWith('[ERROR, failed to read file: big.txt (data url exceeds preview limit'), out);
  });

  await T('预览截断 100KiB + 标记', async () => {
    const text = 'x'.repeat(150000);
    const out = await mod.readDocumentPreview(doc('a.txt', 'text/plain', `data:text/plain;base64,${b64(text)}`));
    const marker = `\n[TRUNCATED: preview exceeds ${mod.MAX_PREVIEW_CHARS} characters]`;
    assert.equal(mod.MAX_PREVIEW_CHARS, 102400);
    assert.equal(out.length, mod.MAX_PREVIEW_CHARS + marker.length);
    assert.ok(out.endsWith(marker));
    assert.equal(out.substring(0, mod.MAX_PREVIEW_CHARS), 'x'.repeat(mod.MAX_PREVIEW_CHARS));
  });

  await T('DOCX 路由到真实 zip+docx 解析(无 document.xml → 域层文案)', async () => {
    assert.equal(
      await mod.readDocumentPreview(doc('a.docx', 'application/octet-stream', `data:application/octet-stream;base64,${ZIP_B64}`)),
      'Unable to find document content in DOCX file');
  });

  await T('PPTX 路由到真实 zip+pptx 解析(无 slides → 域层文案)', async () => {
    assert.equal(
      await mod.readDocumentPreview(doc('a.pptx', 'application/octet-stream', `data:application/octet-stream;base64,${ZIP_B64}`)),
      'No slides found in PPTX file');
  });

  await T('EPUB 路由到真实 zip+epub 解析(无 container → 域层文案)', async () => {
    assert.equal(
      await mod.readDocumentPreview(doc('a.epub', 'application/octet-stream', `data:application/octet-stream;base64,${ZIP_B64}`)),
      'Unable to find OPF file in EPUB');
  });

  console.log('all F44 preview smoke assertions passed');
})().catch((e) => { console.error('FAIL', e); process.exit(1); });
