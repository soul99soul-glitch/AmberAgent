// File grants are read during selection; only extracted text is retained by Deep Read.
import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { readZipEntries, readZipEntryText } from '@amber/chat-domain';
import type { ZipEntryRecord } from '@amber/chat-domain';
import { makeInputSource, extractDocxSourceText, decodeInputSourceText, MAX_INPUT_SOURCE_CHARS } from '@amber/deepread-domain';
import type { DeepReadInputSource } from '@amber/deepread-domain';
import { inflateRawEntry } from './EntryDocumentParser.ets';
import { parsePdfPreviewBytes } from './PdfDocumentParser.ets';

const TEXT_EXTENSIONS: string[] = ['txt', 'md', 'markdown', 'json', 'csv', 'tsv', 'log', 'xml', 'html', 'htm', 'yaml', 'yml'];
export const MAX_DEEPREAD_SOURCE_BYTES: number = 20 * 1024 * 1024;

const readSelectedBytes = (uri: string): Uint8Array => {
  const file: fileIo.File = fileIo.openSync(uri, fileIo.OpenMode.READ_ONLY);
  try {
    const size: number = fileIo.statSync(file.fd).size;
    if (size > MAX_DEEPREAD_SOURCE_BYTES) throw new Error('文件超过 20 MiB 大小限制。');
    const buffer: ArrayBuffer = new ArrayBuffer(size);
    let offset: number = 0;
    while (offset < size) {
      const chunk: ArrayBuffer = new ArrayBuffer(size - offset);
      const count: number = fileIo.readSync(file.fd, chunk, { offset: offset, length: chunk.byteLength });
      if (count <= 0) break;
      new Uint8Array(buffer).set(new Uint8Array(chunk, 0, count), offset);
      offset += count;
    }
    return new Uint8Array(buffer, 0, offset);
  } finally { fileIo.closeSync(file); }
};

const readDocx = async (bytes: Uint8Array): Promise<string> => {
  const entries: ZipEntryRecord[] = readZipEntries(bytes);
  const chunks: string[] = [];
  for (const name of ['word/document.xml', 'word/footnotes.xml', 'word/endnotes.xml']) {
    const entry: ZipEntryRecord | undefined = entries.find((record: ZipEntryRecord): boolean => record.name === name);
    if (entry === undefined) continue;
    const xml: string = await readZipEntryText(bytes, entry, inflateRawEntry);
    chunks.push(extractDocxSourceText(xml));
  }
  return chunks.join('\n\n');
};

export const importDeepReadSource = async (uri: string): Promise<DeepReadInputSource> => {
  const name: string = decodeURIComponent(uri.substring(uri.lastIndexOf('/') + 1).split('?')[0]) || '文件';
  try {
    const extension: string = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
    if (extension !== 'pdf' && extension !== 'docx' && !TEXT_EXTENSIONS.includes(extension)) {
      throw new Error('支持 txt、md、json、csv、文本 PDF 和 DOCX 文件。');
    }
    const bytes: Uint8Array = readSelectedBytes(uri);
    let text: string = '';
    if (extension === 'pdf') {
      text = await parsePdfPreviewBytes(bytes, MAX_INPUT_SOURCE_CHARS + 1);
      if (text.startsWith('[ERROR,')) throw new Error(text);
    } else if (extension === 'docx') {
      text = await readDocx(bytes);
    } else {
      // API 12 TextDecoder supports UTF-16 BOMs and GB18030; fatal UTF-8 avoids replacement glyphs.
      text = decodeInputSourceText(bytes, (input: Uint8Array, encoding: string): string =>
        util.TextDecoder.create(encoding, { fatal: true }).decodeToString(input));
    }
    if (text.trim().length === 0) throw new Error('文件中没有可提取文本。扫描 PDF 需先进行 OCR。');
    return makeInputSource('file', name, text);
  } catch (error) {
    return makeInputSource('file', name, '', null, error instanceof Error ? error.message : String(error));
  }
};
