// document_transformer 规格测试(D-074)
// Android 基准: DocumentAsPromptTransformer.kt(全文)
import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  MAX_INLINE_FILE_BYTES, MAX_INLINE_TEXT_CHARS, createDocumentTransformer, isLikelyTextFile, readDocumentContent
} from '../main/ets/chat/document_transformer.ts';
import type {
  DocumentFileHandle, DocumentReaderDeps,
} from '../main/ets/chat/document_transformer.ts';
import { makeUserMessage } from '../main/ets/chat/message.ts';
import type { UIMessage, UIMessagePartDocument } from '../main/ets/chat/message.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import type { TransformerContext } from '../main/ets/chat/transformer_pipeline.ts';

const ctx: TransformerContext = { assistant: makeAssistant({}) };

const doc = (fileName: string, mime: string, url: string = 'file://f'): UIMessagePartDocument => ({
  type: 'document', url, fileName, mime, metadata: null,
});

const handle = (overrides: Partial<DocumentFileHandle> = {}): DocumentFileHandle => ({
  exists: true,
  isFile: true,
  sizeBytes: 100,
  absolutePath: '/data/f',
  ...overrides,
});

const deps = (overrides: Partial<DocumentReaderDeps> = {}): DocumentReaderDeps => ({
  statFile: (): Promise<DocumentFileHandle | null> => Promise.resolve(handle()),
  readTextFile: (): Promise<string> => Promise.resolve('文本内容'),
  ...overrides,
});

test('常量与扩展名集(:130-143):41 扩展 + text/* + 无点后缀空串不中', () => {
  assert.equal(MAX_INLINE_TEXT_CHARS, 524288);
  assert.equal(MAX_INLINE_FILE_BYTES, 67108864);
  assert.equal(isLikelyTextFile(doc('a.txt', 'application/octet-stream')), true);
  assert.equal(isLikelyTextFile(doc('a.MD', 'x')), true, 'lowercase');
  assert.equal(isLikelyTextFile(doc('a.bin', 'text/plain')), true, 'mime text/* 直通');
  assert.equal(isLikelyTextFile(doc('noext', 'x')), false, '无点 → 空扩展不中');
  assert.equal(isLikelyTextFile(doc('.hidden', 'x')), false, "'.hidden' → 扩展 'hidden' 不中");
});

test('错误文案逐字(:74-81):invalid uri / not found / too large', async () => {
  assert.equal(
    await readDocumentContent(doc('a.pdf', 'application/pdf'), deps({
      statFile: (): Promise<DocumentFileHandle | null> => Promise.resolve(null),
    })),
    '[ERROR, invalid file uri: a.pdf]');
  assert.equal(
    await readDocumentContent(doc('a.pdf', 'application/pdf'), deps({
      statFile: (): Promise<DocumentFileHandle | null> => Promise.resolve(handle({ exists: false })),
    })),
    '[ERROR, file not found: a.pdf]');
  assert.equal(
    await readDocumentContent(doc('a.pdf', 'application/pdf'), deps({
      statFile: (): Promise<DocumentFileHandle | null> =>
        Promise.resolve(handle({ isFile: false })),
    })),
    '[ERROR, file not found: a.pdf]', 'isFile=false 同文案(:76)');
  const big: number = MAX_INLINE_FILE_BYTES + 1;
  assert.equal(
    await readDocumentContent(doc('big.bin', 'application/octet-stream'), deps({
      statFile: (): Promise<DocumentFileHandle | null> =>
        Promise.resolve(handle({ sizeBytes: big })),
    })),
    `[ERROR, file too large to inline: big.bin (${big} bytes)]`);
});

test('mime 分发(:83-87):pdf/docx/pptx/epub 走 parse*;未提供 → failed to read', async () => {
  const pdfMime = 'application/pdf';
  const docxMime = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
  const pptxMime = 'application/vnd.openxmlformats-officedocument.presentationml.presentation';
  const epubMime = 'application/epub+zip';
  const parseDeps = deps({
    parsePdf: (): Promise<string> => Promise.resolve('PDF_TEXT'),
    parseDocx: (): Promise<string> => Promise.resolve('DOCX_TEXT'),
    parsePptx: (): Promise<string> => Promise.resolve('PPTX_TEXT'),
    parseEpub: (): Promise<string> => Promise.resolve('EPUB_TEXT'),
  });
  assert.equal(await readDocumentContent(doc('a.pdf', pdfMime), parseDeps), 'PDF_TEXT');
  assert.equal(await readDocumentContent(doc('a.docx', docxMime), parseDeps), 'DOCX_TEXT');
  assert.equal(await readDocumentContent(doc('a.pptx', pptxMime), parseDeps), 'PPTX_TEXT');
  assert.equal(await readDocumentContent(doc('a.epub', epubMime), parseDeps), 'EPUB_TEXT');
  // 未提供 parser(entry 现状:document 模块未移植)→ 抛 → ERROR 文案(:125-127)
  assert.equal(
    await readDocumentContent(doc('a.pdf', pdfMime), deps()),
    '[ERROR, failed to read file: a.pdf]');
  // parser 抛错同路径
  assert.equal(
    await readDocumentContent(doc('a.docx', docxMime), deps({
      parseDocx: (): Promise<string> => Promise.reject(new Error('corrupt')),
    })),
    '[ERROR, failed to read file: a.docx]');
});

test('文本文件:readTextFile 上限 MAX+1;截断标记逐字(:90-106,:119-124)', async () => {
  let gotLimit: number = 0;
  const out: string = await readDocumentContent(doc('a.txt', 'text/plain'), deps({
    readTextFile: (_h: DocumentFileHandle, limit: number): Promise<string> => {
      gotLimit = limit;
      return Promise.resolve('x'.repeat(MAX_INLINE_TEXT_CHARS + 1));
    },
  }));
  assert.equal(gotLimit, MAX_INLINE_TEXT_CHARS + 1);
  assert.equal(
    out,
    'x'.repeat(MAX_INLINE_TEXT_CHARS) +
    `\n[TRUNCATED: document text exceeds ${MAX_INLINE_TEXT_CHARS} characters]`);
  // 未超限原样
  assert.equal(
    await readDocumentContent(doc('a.md', 'text/markdown'), deps()),
    '文本内容');
});

test('二进制占位逐字(:108-115)', async () => {
  const out: string = await readDocumentContent(
    doc('a.zip', 'application/zip'), deps());
  assert.equal(
    out,
    '[BINARY_OR_ARCHIVE_FILE]\nname: a.zip\nmime: application/zip\n' +
    'size_bytes: 100\nlocal_path: /data/f\n' +
    'The file is attached but was not inlined as text. Use available tools, such as terminal_execute, to inspect, extract, or process it.\n');
});

test('transform:每个 document prompt add(0) 逆序;非 document 消息恒量 copy;无 doc 不加', async () => {
  const t = createDocumentTransformer(deps());
  const two: UIMessage = {
    ...makeUserMessage('看图'),
    parts: [
      { type: 'text', text: '正文', metadata: null },
      doc('b.txt', 'text/plain'),
      doc('a.txt', 'text/plain'),
    ],
  };
  const plain: UIMessage = makeUserMessage('无文档');
  const out = await t.transform!(ctx, [two, plain]) as UIMessage[];
  const parts = out[0].parts;
  assert.equal(parts.length, 5, '2 prompts + 3 原 parts');
  assert.equal((parts[0] as { text: string }).text.includes('## user sent a file: a.txt'), true,
    '后 doc(a.txt)的 prompt 最前(add(0) 逆序忠实)');
  assert.equal((parts[1] as { text: string }).text.includes('## user sent a file: b.txt'), true);
  assert.equal((parts[2] as { text: string }).text, '正文');
  assert.equal(parts[3].type, 'document');
  assert.equal(out[1].parts.length, plain.parts.length, '无 doc 消息不加 prompt');
  assert.notEqual(out[1], plain, '恒量 copy(Android 无短路)');
});
