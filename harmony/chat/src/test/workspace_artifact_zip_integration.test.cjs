const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../node_modules/typescript');
const zlib = require('node:zlib');
process.env.TSX_TSCONFIG_PATH ??= path.resolve(__dirname, '../../tsconfig.json');
require('../../node_modules/tsx/dist/cjs/index.cjs');
const artifact = require('../main/ets/chat/workspace_artifacts.ts');
const writer = require('../main/ets/chat/zip_writer.ts');
const reader = require('../main/ets/chat/zip_archive.ts');

function loadNovelArchiveCodec() {
  const source = fs.readFileSync(path.resolve(__dirname,
    '../../../entry/src/main/ets/platform_impl/NovelWorkspaceArchiveCodec.ets'), 'utf8');
  const compiled = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
  } }).outputText;
  const output = {};
  new Function('exports', 'require', compiled)(output, name => {
    if (name === '@amber/chat-domain') return { ...artifact, ...reader };
    if (name === './ArtifactPorts.ets') return {
      createDeflateRawPort: () => async bytes => new Uint8Array(zlib.deflateRawSync(bytes)),
    };
    if (name === './EntryDocumentParser.ets') return {
      inflateRawEntry: async bytes => new Uint8Array(zlib.inflateRawSync(bytes)),
    };
    if (name === '@kit.ArkTS') return { util: { TextEncoder: { create: () => ({
      encodeInto: text => new TextEncoder().encode(text),
    }) } } };
    throw new Error(`Unexpected Novel archive dependency: ${name}`);
  });
  return output;
}

test('the legacy artifact path exports the same ZIP writer used by the actual Novel ZIP/EPUB adapter', async () => {
  assert.equal(artifact.buildZipBytes, writer.buildZipBytes);
  assert.equal(artifact.dosTimeDate, writer.dosTimeDate);
  assert.equal(artifact.crc32, writer.crc32);
  const native = loadNovelArchiveCodec();
  const codec = native.createNovelWorkspaceArchiveCodec();
  const files = [{ path: 'branches/main/chapters/001-正文.md', bytes: new TextEncoder().encode('小说正文') }];
  const zip = await codec.create(files);
  assert.deepEqual(await codec.list(zip), [{ path: files[0].path,
    compressedSize: reader.readZipEntries(zip)[0].compressedSize,
    uncompressedSize: files[0].bytes.length, isDirectory: false }]);
  assert.deepEqual(await codec.extract(zip, files[0].path), files[0].bytes);
  const epub = await native.createNovelBookZipEncoder().encode([
    { name: 'mimetype', text: 'application/epub+zip', compression: 'stored' },
    { name: 'OEBPS/正文.xhtml', text: '<p>小说正文</p>', compression: 'deflate' },
  ]);
  const entries = reader.readZipEntries(epub);
  assert.deepEqual(entries.map(entry => [entry.name, entry.method]), [
    ['mimetype', 0], ['OEBPS/正文.xhtml', 8],
  ]);
  assert.equal(new TextDecoder().decode(await codec.extract(epub, entries[1].name)), '<p>小说正文</p>');
});
